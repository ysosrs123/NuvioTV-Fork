#ifndef THUMBDOVI_H
#define THUMBDOVI_H

#include <stdint.h>

/*
 * Dolby Vision profile 5 (IPTPQc2 base layer) -> PQ-encoded BT.2020 R'G'B', no FFmpeg dependency.
 * Follows the chain FFmpeg documents in dovi_meta.h, as implemented by libplacebo:
 *   reshaping (polynomial or MMR) -> ycc_to_rgb -> PQ EOTF -> rgb_to_lms -> BT.2020 LMS->RGB -> PQ inverse EOTF.
 * The output feeds thumbcolor.c like a PQ HDR10 frame.
 */

#define TD_MAX_PIECES 8

enum td_method { TD_POLYNOMIAL = 0, TD_MMR = 1 };

typedef struct td_piece {
    int method;
    int poly_order;              /* 1..2 */
    double poly[3];              /* x^0, x^1, x^2 */
    int mmr_order;               /* 1..3 */
    double mmr_constant;
    double mmr[3][7];            /* per order: s0, s1, s2, s0s1, s0s2, s1s2, s0s1s2 */
} td_piece;

typedef struct td_params {
    int num_pivots[3];                       /* 2..9 */
    double pivots[3][TD_MAX_PIECES + 1];     /* normalised to [0,1] */
    td_piece pieces[3][TD_MAX_PIECES];
    double ycc_to_rgb[9];                    /* row-major */
    double ycc_offset[3];                    /* normalised */
    double rgb_to_lms[9];                    /* row-major */
} td_params;

/*
 * y/u/v: base-layer planes scaled to the output size at 4:4:4, 16 bits (swscale YUV444P16, no matrix).
 * g/b/r: PQ-encoded BT.2020 output planes. Strides in samples.
 */
void td_convert(const td_params *p, const uint16_t *y, const uint16_t *u, const uint16_t *v, int in_stride,
                int width, int height, uint16_t *g, uint16_t *b, uint16_t *r, int out_stride);

/* PQ [0,1] -> nits */
double td_pq_to_nits(double pq);

#endif
