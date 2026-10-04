#include "thumbdovi.h"

#include <math.h>

/* SMPTE ST 2084 */
#define PQ_M1 (2610.0 / 16384.0)
#define PQ_M2 (2523.0 / 4096.0 * 128.0)
#define PQ_C1 (3424.0 / 4096.0)
#define PQ_C2 (2413.0 / 4096.0 * 32.0)
#define PQ_C3 (2392.0 / 4096.0 * 32.0)

static double pq_eotf(double e) {        /* PQ [0,1] -> linear [0,1] of 10000 nits */
    if (e <= 0.0) return 0.0;
    double p = pow(e, 1.0 / PQ_M2);
    double num = p - PQ_C1;
    if (num < 0.0) num = 0.0;
    return pow(num / (PQ_C2 - PQ_C3 * p), 1.0 / PQ_M1);
}

static double pq_oetf(double y) {        /* linear [0,1] -> PQ [0,1] */
    if (y <= 0.0) return 0.0;
    if (y > 1.0) y = 1.0;
    double p = pow(y, PQ_M1);
    return pow((PQ_C1 + PQ_C2 * p) / (1.0 + PQ_C3 * p), PQ_M2);
}

double td_pq_to_nits(double pq) { return pq_eotf(pq) * 10000.0; }

static double clamp01(double x) { return x < 0.0 ? 0.0 : (x > 1.0 ? 1.0 : x); }

/*
 * BT.2020 RGB -> LMS of ICtCp (BT.2100), which includes a 4 % crosstalk. FFmpeg's rgb_to_lms expects an
 * LMS->RGB matrix without crosstalk, so td_convert takes it out before inverting. Against libplacebo's
 * apply_dolbyvision this gives 42-45 dB PSNR, 35-40 dB with the crosstalk left in.
 */
static const double kCrosstalk = 0.04;
static const double kRgb2Lms[9] = {
    1688.0 / 4096.0, 2146.0 / 4096.0, 262.0 / 4096.0,
    683.0 / 4096.0, 2951.0 / 4096.0, 462.0 / 4096.0,
    99.0 / 4096.0, 309.0 / 4096.0, 3688.0 / 4096.0,
};

static void invert3(const double m[9], double out[9]) {
    double a = m[0], b = m[1], c = m[2], d = m[3], e = m[4], f = m[5], g = m[6], h = m[7], i = m[8];
    double A = e * i - f * h, B = -(d * i - f * g), C = d * h - e * g;
    double det = a * A + b * B + c * C;
    double inv = det != 0.0 ? 1.0 / det : 0.0;
    out[0] = A * inv; out[1] = -(b * i - c * h) * inv; out[2] = (b * f - c * e) * inv;
    out[3] = B * inv; out[4] = (a * i - c * g) * inv;  out[5] = -(a * f - c * d) * inv;
    out[6] = C * inv; out[7] = -(a * h - b * g) * inv; out[8] = (a * e - b * d) * inv;
}

static void mul3(const double a[9], const double b[9], double out[9]) {
    for (int r = 0; r < 3; r++)
        for (int c = 0; c < 3; c++)
            out[r * 3 + c] = a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c];
}

static double reshape(const td_params *p, int comp, const double s[3]) {
    const double x = s[comp];
    const int pieces = p->num_pivots[comp] - 1;
    int k = 0;
    while (k + 1 < pieces && x >= p->pivots[comp][k + 1]) k++;
    const td_piece *pc = &p->pieces[comp][k];
    double y;
    if (pc->method == TD_POLYNOMIAL) {
        y = pc->poly[0] + pc->poly[1] * x;
        if (pc->poly_order >= 2) y += pc->poly[2] * x * x;
    } else {
        const double t[7] = {s[0], s[1], s[2], s[0] * s[1], s[0] * s[2], s[1] * s[2], s[0] * s[1] * s[2]};
        double tp[7] = {1, 1, 1, 1, 1, 1, 1};
        y = pc->mmr_constant;
        for (int o = 0; o < pc->mmr_order && o < 3; o++)
            for (int j = 0; j < 7; j++) {
                tp[j] *= t[j];
                y += pc->mmr[o][j] * tp[j];
            }
    }
    return clamp01(y);
}

void td_convert(const td_params *p, const uint16_t *y, const uint16_t *u, const uint16_t *v, int in_stride,
                int width, int height, uint16_t *g, uint16_t *b, uint16_t *r, int out_stride) {
    const double ct = kCrosstalk;
    const double cross[9] = {1 - 2 * ct, ct, ct, ct, 1 - 2 * ct, ct, ct, ct, 1 - 2 * ct};
    double cross_inv[9], hpe[9], lms2rgb[9], linear[9];
    invert3(cross, cross_inv);
    mul3(cross_inv, kRgb2Lms, hpe);          /* BT.2020 RGB -> LMS without crosstalk */
    invert3(hpe, lms2rgb);
    mul3(lms2rgb, p->rgb_to_lms, linear);   /* linear DV RGB -> BT.2020 RGB */
    for (int row = 0; row < height; row++) {
        for (int col = 0; col < width; col++) {
            const int i = row * in_stride + col;
            const double s[3] = {y[i] / 65535.0, u[i] / 65535.0, v[i] / 65535.0};
            double ipt[3];
            for (int c = 0; c < 3; c++) ipt[c] = reshape(p, c, s) - p->ycc_offset[c];
            double lin[3];
            for (int c = 0; c < 3; c++) {
                const double e = p->ycc_to_rgb[c * 3] * ipt[0] + p->ycc_to_rgb[c * 3 + 1] * ipt[1] +
                                 p->ycc_to_rgb[c * 3 + 2] * ipt[2];
                lin[c] = pq_eotf(clamp01(e));
            }
            double rgb[3];
            for (int c = 0; c < 3; c++)
                rgb[c] = linear[c * 3] * lin[0] + linear[c * 3 + 1] * lin[1] + linear[c * 3 + 2] * lin[2];
            const int o = row * out_stride + col;
            r[o] = (uint16_t) lrint(pq_oetf(rgb[0]) * 65535.0);
            g[o] = (uint16_t) lrint(pq_oetf(rgb[1]) * 65535.0);
            b[o] = (uint16_t) lrint(pq_oetf(rgb[2]) * 65535.0);
        }
    }
}
