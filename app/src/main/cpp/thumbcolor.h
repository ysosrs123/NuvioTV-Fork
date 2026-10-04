#ifndef THUMBCOLOR_H
#define THUMBCOLOR_H

#include <stdint.h>

/*
 * HDR -> SDR for thumbnail-sized frames, no FFmpeg dependency. Input is planar non-linear R'G'B', 16 bits full
 * range (swscale GBRP16). Linearises (PQ, or HLG + OOTF), tone-maps with the BT.2390 EETF on max(R,G,B),
 * converts BT.2020 -> BT.709 primaries and encodes with a 2.4 gamma.
 */

enum tc_transfer { TC_TRANSFER_PQ = 1, TC_TRANSFER_HLG = 2 };

typedef struct tc_params {
    enum tc_transfer transfer;
    float source_peak_nits;   /* <= 0 -> 1000 */
    float target_white_nits;  /* HDR level mapped to SDR white, <= 0 -> 203 (BT.2408) */
    int bt2020_primaries;     /* 1: convert BT.2020 -> BT.709 primaries */
} tc_params;

typedef struct tc_ctx tc_ctx;

/* Builds the LUTs (~100 KB). NULL on allocation failure. */
tc_ctx *tc_create(const tc_params *p);
void tc_free(tc_ctx *c);

/*
 * g/b/r strides in samples, out_stride in bytes.
 * out: RGBA8888 when rgb565 == 0, else RGB565 little-endian, ordered-dithered.
 */
void tc_convert(const tc_ctx *c, const uint16_t *g, int g_stride, const uint16_t *b, int b_stride,
                const uint16_t *r, int r_stride, int width, int height,
                uint8_t *out, int out_stride, int rgb565);

#endif
