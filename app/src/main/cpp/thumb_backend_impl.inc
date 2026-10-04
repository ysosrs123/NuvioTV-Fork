/*
 * One FFmpeg backend of the keyframe decoder (see thumb_backend.h). Included by one .cpp per backend, inside
 * an anonymous namespace, after that file's own FFmpeg headers. The including file defines load_symbols().
 */

#define FF_FUNCS(X) \
    X(avcodec_find_decoder_by_name) X(av_mallocz) X(avcodec_alloc_context3) X(avcodec_open2) X(avcodec_send_packet) \
    X(avcodec_receive_frame) X(avcodec_free_context) X(avcodec_flush_buffers) \
    X(av_packet_alloc) X(av_packet_free) X(av_new_packet) X(av_packet_unref) \
    X(av_frame_alloc) X(av_frame_free) X(av_frame_unref) X(av_frame_get_side_data) \
    X(av_pix_fmt_desc_get) X(av_log_set_level) \
    X(sws_getCachedContext) X(sws_scale) X(sws_freeContext) X(sws_setColorspaceDetails) X(sws_getCoefficients)

struct Api {
#define DECL(name) decltype(&::name) name = nullptr;
    FF_FUNCS(DECL)
#undef DECL
};

Api g_api;
bool g_tried = false;
bool g_ready = false;
char g_version[64] = "";
pthread_mutex_t g_init_lock = PTHREAD_MUTEX_INITIALIZER;

bool load_symbols(Api &api, char *version, size_t n);

void *find_symbol(void *const *handles, int count, const char *name) {
    for (int i = 0; i < count; i++) {
        if (!handles[i]) continue;
        if (void *s = dlsym(handles[i], name)) return s;
    }
    return nullptr;
}

bool resolve_all(Api &api, void *const *handles, int count) {
    bool ok = true;
#define RESOLVE(name) \
    api.name = reinterpret_cast<decltype(&::name)>(find_symbol(handles, count, #name)); \
    if (!api.name) { LOGW("%s: missing symbol %s", BACKEND_NAME, #name); ok = false; }
    FF_FUNCS(RESOLVE)
#undef RESOLVE
    return ok;
}

bool ensure_loaded() {
    pthread_mutex_lock(&g_init_lock);
    if (!g_tried) {
        g_tried = true;
        g_ready = load_symbols(g_api, g_version, sizeof g_version);
        if (g_ready) g_api.av_log_set_level(AV_LOG_ERROR);
    }
    bool r = g_ready;
    pthread_mutex_unlock(&g_init_lock);
    return r;
}

struct Handle {
    AVCodecContext *ctx = nullptr;
    AVPacket *pkt = nullptr;
    AVFrame *frame = nullptr;
    bool have_frame = false;
    struct SwsContext *sws = nullptr;
    uint8_t *g16 = nullptr;       // GBRP16 scratch for the HDR path
    size_t g16_size = 0;
    tc_ctx *tone = nullptr;
    tc_params tone_params{};
    bool have_dovi = false;       // current frame has Dolby Vision IPT metadata (profile 5)
    td_params dovi{};
    float dovi_peak_nits = 0;
};

/** Copies FFmpeg's parsed Dolby Vision metadata of an IPT frame into td_params. */
bool read_dovi(const AVFrame *f, td_params *p, float *peak_nits) {
    AVFrameSideData *sd = g_api.av_frame_get_side_data(f, AV_FRAME_DATA_DOVI_METADATA);
    if (!sd || !sd->data) return false;
    const auto *md = reinterpret_cast<const AVDOVIMetadata *>(sd->data);
    const AVDOVIRpuDataHeader *hdr = av_dovi_get_header(md);
    const AVDOVIDataMapping *map = av_dovi_get_mapping(md);
    const AVDOVIColorMetadata *col = av_dovi_get_color(md);
    if (col->signal_color_space != 2) return false;          // 2 = IPT
    if (hdr->bl_bit_depth < 8 || hdr->bl_bit_depth > 16 || hdr->coef_log2_denom > 62) return false;
    const double den = static_cast<double>(1ULL << hdr->coef_log2_denom);
    const double bl_max = static_cast<double>((1 << hdr->bl_bit_depth) - 1);
    for (int c = 0; c < 3; c++) {
        const AVDOVIReshapingCurve &cv = map->curves[c];
        if (cv.num_pivots < 2 || cv.num_pivots > TD_MAX_PIECES + 1) return false;
        p->num_pivots[c] = cv.num_pivots;
        for (int k = 0; k < cv.num_pivots; k++) p->pivots[c][k] = cv.pivots[k] / bl_max;
        for (int k = 0; k < cv.num_pivots - 1; k++) {
            td_piece &pc = p->pieces[c][k];
            pc.method = cv.mapping_idc[k] == AV_DOVI_MAPPING_MMR ? TD_MMR : TD_POLYNOMIAL;
            pc.poly_order = cv.poly_order[k];
            for (int j = 0; j < 3; j++) pc.poly[j] = cv.poly_coef[k][j] / den;
            pc.mmr_order = cv.mmr_order[k];
            pc.mmr_constant = cv.mmr_constant[k] / den;
            for (int o = 0; o < 3; o++)
                for (int j = 0; j < 7; j++) pc.mmr[o][j] = cv.mmr_coef[k][o][j] / den;
        }
    }
    for (int i = 0; i < 9; i++) {
        p->ycc_to_rgb[i] = av_q2d(col->ycc_to_rgb_matrix[i]);
        p->rgb_to_lms[i] = av_q2d(col->rgb_to_lms_matrix[i]);
    }
    for (int i = 0; i < 3; i++) p->ycc_offset[i] = av_q2d(col->ycc_to_rgb_offset[i]);
    *peak_nits = col->source_max_pq > 0 ? static_cast<float>(td_pq_to_nits(col->source_max_pq / 4095.0)) : 1000.0f;
    return true;
}

void b_release(void *handle) {
    auto *h = static_cast<Handle *>(handle);
    if (!h) return;
    if (h->frame) g_api.av_frame_free(&h->frame);
    if (h->pkt) g_api.av_packet_free(&h->pkt);
    if (h->ctx) g_api.avcodec_free_context(&h->ctx);
    if (h->sws) g_api.sws_freeContext(h->sws);
    free(h->g16);
    tc_free(h->tone);
    delete h;
}

void *b_open(const char *decoder, const uint8_t *extradata, int extradata_len, int width, int height, int threads) {
    if (!ensure_loaded()) return nullptr;
    const AVCodec *c = g_api.avcodec_find_decoder_by_name(decoder);
    if (!c) return nullptr;
    auto *h = new Handle();
    h->ctx = g_api.avcodec_alloc_context3(c);
    h->pkt = g_api.av_packet_alloc();
    h->frame = g_api.av_frame_alloc();
    if (!h->ctx || !h->pkt || !h->frame) { b_release(h); return nullptr; }
    if (extradata && extradata_len > 0) {
        // FFmpeg owns and frees extradata, so it must come from av_mallocz, with input padding.
        h->ctx->extradata = static_cast<uint8_t *>(g_api.av_mallocz(extradata_len + AV_INPUT_BUFFER_PADDING_SIZE));
        if (!h->ctx->extradata) { b_release(h); return nullptr; }
        memcpy(h->ctx->extradata, extradata, extradata_len);
        h->ctx->extradata_size = extradata_len;
    }
    if (width > 0 && height > 0) {            // WMV3 cannot decode without the coded size
        h->ctx->width = h->ctx->coded_width = width;
        h->ctx->height = h->ctx->coded_height = height;
    }
    h->ctx->thread_count = threads < 1 ? 1 : threads;
    h->ctx->thread_type = FF_THREAD_SLICE;
    if (g_api.avcodec_open2(h->ctx, c, nullptr) < 0) { b_release(h); return nullptr; }
    return h;
}

uint8_t *b_packet(void *handle, int len) {
    auto *h = static_cast<Handle *>(handle);
    if (!h || len <= 0) return nullptr;
    if (h->have_frame) { g_api.av_frame_unref(h->frame); h->have_frame = false; }
    g_api.avcodec_flush_buffers(h->ctx);            // reset after the previous drain
    g_api.av_packet_unref(h->pkt);
    if (g_api.av_new_packet(h->pkt, len) < 0) return nullptr;
    return h->pkt->data;
}

int b_decode(void *handle, int32_t *out) {
    auto *h = static_cast<Handle *>(handle);
    if (!h || !h->pkt->data) return AVERROR(EINVAL);
    h->pkt->flags |= AV_PKT_FLAG_KEY;
    int r = g_api.avcodec_send_packet(h->ctx, h->pkt);
    g_api.av_packet_unref(h->pkt);
    if (r < 0) return r;
    g_api.avcodec_send_packet(h->ctx, nullptr);      // drain to get this picture now
    r = g_api.avcodec_receive_frame(h->ctx, h->frame);
    if (r < 0) return r;
    h->have_frame = true;

    memset(out, 0, sizeof(int32_t) * thumb::INFO_COUNT);
    AVFrame *f = h->frame;
    const AVPixFmtDescriptor *d = g_api.av_pix_fmt_desc_get(static_cast<AVPixelFormat>(f->format));
    out[thumb::INFO_WIDTH] = f->width;
    out[thumb::INFO_HEIGHT] = f->height;
    out[thumb::INFO_BIT_DEPTH] = d ? d->comp[0].depth : 8;
    out[thumb::INFO_SAR_NUM] = f->sample_aspect_ratio.num;
    out[thumb::INFO_SAR_DEN] = f->sample_aspect_ratio.den;
    out[thumb::INFO_TRC] = f->color_trc;
    out[thumb::INFO_PRIMARIES] = f->color_primaries;
    out[thumb::INFO_MATRIX] = f->colorspace;
    out[thumb::INFO_RANGE] = f->color_range;
    AVFrameSideData *sd = g_api.av_frame_get_side_data(f, AV_FRAME_DATA_CONTENT_LIGHT_LEVEL);
    if (sd) out[thumb::INFO_MAX_CLL] = static_cast<int32_t>(reinterpret_cast<AVContentLightMetadata *>(sd->data)->MaxCLL);
    sd = g_api.av_frame_get_side_data(f, AV_FRAME_DATA_MASTERING_DISPLAY_METADATA);
    if (sd) {
        auto *m = reinterpret_cast<AVMasteringDisplayMetadata *>(sd->data);
        if (m->has_luminance && m->max_luminance.den)
            out[thumb::INFO_MASTERING_MAX_NITS] = m->max_luminance.num / m->max_luminance.den;
    }
    h->have_dovi = read_dovi(f, &h->dovi, &h->dovi_peak_nits);
    out[thumb::INFO_DOVI_IPT] = h->have_dovi ? 1 : 0;
    return 0;
}

/**
 * Dolby Vision profile 5: scale the IPT base layer to the output size at 4:4:4, reshape and convert to PQ BT.2020
 * (thumbdovi.c), then the HDR10 tone-map.
 */
int render_dovi(Handle *h, const uint8_t *const src[4], const int src_ls[4], int src_h, uint8_t *pixels, int stride,
                int dw, int dh, bool rgb565) {
    if (!h->have_dovi) return AVERROR(EINVAL);
    AVFrame *f = h->frame;
    h->sws = g_api.sws_getCachedContext(h->sws, f->width, src_h, static_cast<AVPixelFormat>(f->format),
                                        dw, dh, AV_PIX_FMT_YUV444P16LE, SWS_BILINEAR, nullptr, nullptr, nullptr);
    if (!h->sws) return AVERROR(ENOMEM);
    const size_t plane = static_cast<size_t>(dw) * dh;
    const size_t need = plane * 2 * 6;                     // YUV444P16 in + GBRP16 out
    if (h->g16_size < need) {
        free(h->g16);
        h->g16 = static_cast<uint8_t *>(malloc(need));
        h->g16_size = h->g16 ? need : 0;
    }
    if (!h->g16) return AVERROR(ENOMEM);
    auto *base = reinterpret_cast<uint16_t *>(h->g16);
    uint16_t *yp = base, *up = base + plane, *vp = base + 2 * plane;
    uint16_t *gp = base + 3 * plane, *bp = base + 4 * plane, *rp = base + 5 * plane;
    uint8_t *dst[4] = {reinterpret_cast<uint8_t *>(yp), reinterpret_cast<uint8_t *>(up), reinterpret_cast<uint8_t *>(vp), nullptr};
    int dst_ls[4] = {dw * 2, dw * 2, dw * 2, 0};
    g_api.sws_scale(h->sws, src, src_ls, 0, src_h, dst, dst_ls);
    td_convert(&h->dovi, yp, up, vp, dw, dw, dh, gp, bp, rp, dw);
    tc_params want{};
    want.transfer = TC_TRANSFER_PQ;
    want.source_peak_nits = h->dovi_peak_nits;
    want.target_white_nits = 0;
    want.bt2020_primaries = 1;
    if (!h->tone || memcmp(&want, &h->tone_params, sizeof want) != 0) {
        tc_free(h->tone);
        h->tone = tc_create(&want);
        h->tone_params = want;
    }
    if (!h->tone) return AVERROR(ENOMEM);
    tc_convert(h->tone, gp, dw, bp, dw, rp, dw, dw, dh, pixels, stride, rgb565 ? 1 : 0);
    return 0;
}

int b_render(void *handle, uint8_t *pixels, int stride, int dw, int dh, bool rgb565, int transfer, bool bt2020,
           bool fullRange, float peakNits, bool rowSkip) {
    auto *h = static_cast<Handle *>(handle);
    if (!h || !h->have_frame) return AVERROR(EINVAL);
    AVFrame *f = h->frame;

    // Row pre-decimation: every other row of every plane.
    const uint8_t *src[4] = {f->data[0], f->data[1], f->data[2], f->data[3]};
    int src_ls[4] = {f->linesize[0], f->linesize[1], f->linesize[2], f->linesize[3]};
    int src_h = f->height;
    if (rowSkip && src_h >= 4 * dh) {
        for (int p = 0; p < 4; p++) src_ls[p] *= 2;
        src_h /= 2;
    }
    if (transfer == 3) return render_dovi(h, src, src_ls, src_h, pixels, stride, dw, dh, rgb565);
    const bool hdr = transfer == 1 || transfer == 2;
    const AVPixelFormat dst_fmt = hdr ? AV_PIX_FMT_GBRP16LE : (rgb565 ? AV_PIX_FMT_RGB565LE : AV_PIX_FMT_RGBA);
    h->sws = g_api.sws_getCachedContext(h->sws, f->width, src_h, static_cast<AVPixelFormat>(f->format),
                                        dw, dh, dst_fmt, SWS_BILINEAR, nullptr, nullptr, nullptr);
    if (!h->sws) return AVERROR(ENOMEM);
    const int cs = bt2020 ? SWS_CS_BT2020 : (f->height > 576 ? SWS_CS_ITU709 : SWS_CS_ITU601);
    g_api.sws_setColorspaceDetails(h->sws, g_api.sws_getCoefficients(cs), fullRange ? 1 : 0,
                                   g_api.sws_getCoefficients(SWS_CS_DEFAULT), 1, 0, 1 << 16, 1 << 16);
    if (!hdr) {
        uint8_t *dst[4] = {pixels, nullptr, nullptr, nullptr};
        int dst_ls[4] = {stride, 0, 0, 0};
        g_api.sws_scale(h->sws, src, src_ls, 0, src_h, dst, dst_ls);
        return 0;
    }
    const size_t need = static_cast<size_t>(dw) * dh * 2 * 3;
    if (h->g16_size < need) {
        free(h->g16);
        h->g16 = static_cast<uint8_t *>(malloc(need));
        h->g16_size = h->g16 ? need : 0;
    }
    if (!h->g16) return AVERROR(ENOMEM);
    uint8_t *dst[4] = {h->g16, h->g16 + static_cast<size_t>(dw) * dh * 2,
                       h->g16 + static_cast<size_t>(dw) * dh * 4, nullptr};
    int dst_ls[4] = {dw * 2, dw * 2, dw * 2, 0};
    g_api.sws_scale(h->sws, src, src_ls, 0, src_h, dst, dst_ls);
    tc_params want{};
    want.transfer = transfer == 2 ? TC_TRANSFER_HLG : TC_TRANSFER_PQ;
    want.source_peak_nits = peakNits;
    want.target_white_nits = 0;   // default 203 (BT.2408)
    want.bt2020_primaries = bt2020 ? 1 : 0;
    if (!h->tone || memcmp(&want, &h->tone_params, sizeof want) != 0) {
        tc_free(h->tone);
        h->tone = tc_create(&want);
        h->tone_params = want;
    }
    if (!h->tone) return AVERROR(ENOMEM);
    // GBRP plane order: [0]=G [1]=B [2]=R
    tc_convert(h->tone, reinterpret_cast<uint16_t *>(dst[0]), dw, reinterpret_cast<uint16_t *>(dst[1]), dw,
               reinterpret_cast<uint16_t *>(dst[2]), dw, dw, dh, pixels, stride, rgb565 ? 1 : 0);
    return 0;
}

void b_trim(void *handle) {
    auto *h = static_cast<Handle *>(handle);
    if (!h) return;
    if (h->have_frame) { g_api.av_frame_unref(h->frame); h->have_frame = false; }
    g_api.avcodec_flush_buffers(h->ctx);
    free(h->g16); h->g16 = nullptr; h->g16_size = 0;
    if (h->sws) { g_api.sws_freeContext(h->sws); h->sws = nullptr; }
}

const thumb::Backend *get() {
    static thumb::Backend backend{BACKEND_NAME, g_version, b_open, b_packet, b_decode, b_render, b_trim, b_release};
    return ensure_loaded() ? &backend : nullptr;
}

#undef FF_FUNCS
