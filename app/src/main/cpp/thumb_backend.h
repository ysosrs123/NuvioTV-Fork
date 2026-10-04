/*
 * Two FFmpeg backends for the keyframe decoder, both already in the APK and loaded with dlopen/dlsym:
 *   "mpv":  MPV's FFmpeg (libavcodec 62), ~2x faster at 4K. Used when its majors match the vendored headers.
 *   "ff60": FFmpeg 6.0 inside libmediainfo.so, the fallback.
 * Each backend is its own compilation unit of thumb_backend_impl.inc against its own headers, so no struct
 * layout crosses between the two FFmpeg versions.
 */
#pragma once

#include <cstddef>
#include <cstdint>

namespace thumb {

// Must match ThumbNative.INFO_* on the Kotlin side.
enum {
    INFO_WIDTH = 0, INFO_HEIGHT, INFO_BIT_DEPTH, INFO_SAR_NUM, INFO_SAR_DEN, INFO_TRC, INFO_PRIMARIES,
    INFO_MATRIX, INFO_RANGE, INFO_MAX_CLL, INFO_MASTERING_MAX_NITS,
    INFO_DOVI_IPT,   // 1: Dolby Vision metadata with an IPT signal (profile 5)
    INFO_COUNT
};

struct Backend {
    const char *name;
    const char *version;
    /** extradata is copied. width/height: coded size (WMV3 needs it). nullptr when this FFmpeg lacks the decoder. */
    void *(*open)(const char *decoder, const uint8_t *extradata, int extradata_len, int width, int height, int threads);
    /** Buffer of len bytes for the next keyframe, owned by the handle. nullptr on OOM. */
    uint8_t *(*packet)(void *h, int len);
    /** Decodes the packet filled through packet(), fills info[INFO_COUNT]. 0 or a negative AVERROR. */
    int (*decode)(void *h, int32_t *info);
    /** pixels: RGBA_8888 or RGB_565. transfer: 0 SDR, 1 PQ, 2 HLG, 3 Dolby Vision IPT. */
    int (*render)(void *h, uint8_t *pixels, int stride, int dw, int dh, bool rgb565, int transfer, bool bt2020,
                  bool fullRange, float peakNits, bool rowSkip);
    /** Drops the decoded picture and scratch buffers, keeps the decoder. */
    void (*trim)(void *h);
    void (*release)(void *h);
};

/** Both return nullptr when the backend cannot be loaded. */
const Backend *backend_mpv();
const Backend *backend_ff60();

}  // namespace thumb
