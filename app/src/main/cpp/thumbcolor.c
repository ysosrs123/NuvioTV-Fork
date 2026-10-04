#include "thumbcolor.h"

#include <math.h>
#include <stdlib.h>

#define LUT_BITS 12
#define LUT_SIZE (1 << LUT_BITS)

struct tc_ctx {
    tc_params p;
    float lin[LUT_SIZE];      /* 12-bit code -> linear (PQ: nits, HLG: scene linear 0..1) */
    float scale[LUT_SIZE];    /* sqrt(maxRGB / peak) -> multiplier */
    uint16_t oetf[LUT_SIZE];  /* sqrt(L) -> 8.8 fixed-point code value */
    float inv_peak;
};

/* SMPTE ST 2084 */
static const double PQ_M1 = 2610.0 / 16384.0, PQ_M2 = 2523.0 / 4096.0 * 128.0;
static const double PQ_C1 = 3424.0 / 4096.0, PQ_C2 = 2413.0 / 4096.0 * 32.0, PQ_C3 = 2392.0 / 4096.0 * 32.0;

static double pq_eotf(double e) {            /* signal 0..1 -> nits */
    double ep = pow(e < 0 ? 0 : e, 1.0 / PQ_M2);
    double num = ep - PQ_C1;
    if (num < 0) num = 0;
    return 10000.0 * pow(num / (PQ_C2 - PQ_C3 * ep), 1.0 / PQ_M1);
}

static double pq_inv(double nits) {           /* nits -> signal 0..1 */
    double y = pow((nits < 0 ? 0 : nits) / 10000.0, PQ_M1);
    return pow((PQ_C1 + PQ_C2 * y) / (1.0 + PQ_C3 * y), PQ_M2);
}

/* BT.2100 HLG inverse OETF: signal 0..1 -> scene linear 0..1 */
static double hlg_inv_oetf(double e) {
    const double a = 0.17883277, b = 0.28466892, c = 0.55991073;
    if (e <= 0.5) return e * e / 3.0;
    return (exp((e - c) / a) + b) / 12.0;
}

/* BT.2390 EETF, black at 0: nits in [0, lw] -> [0, lmax] */
static double eetf(double nits, double lw, double lmax) {
    if (lw <= lmax) return nits;
    double pw = pq_inv(lw);
    double e1 = pq_inv(nits) / pw;
    if (e1 > 1.0) e1 = 1.0;
    double max_lum = pq_inv(lmax) / pw;
    double ks = 1.5 * max_lum - 0.5;
    double e2 = e1;
    if (e1 >= ks) {
        double t = (e1 - ks) / (1.0 - ks), t2 = t * t, t3 = t2 * t;
        e2 = (2 * t3 - 3 * t2 + 1) * ks + (t3 - 2 * t2 + t) * (1.0 - ks) + (-2 * t3 + 3 * t2) * max_lum;
    }
    return pq_eotf(e2 * pw);
}

tc_ctx *tc_create(const tc_params *p) {
    tc_ctx *c = (tc_ctx *) calloc(1, sizeof(*c));
    if (!c) return NULL;
    c->p = *p;
    if (c->p.source_peak_nits <= 0) c->p.source_peak_nits = 1000.0f;
    if (c->p.target_white_nits <= 0) c->p.target_white_nits = 203.0f;
    /* HLG: BT.2100 reference display of 1000 nits */
    double peak = c->p.transfer == TC_TRANSFER_HLG ? 1000.0 : c->p.source_peak_nits;
    double white = c->p.target_white_nits;
    c->inv_peak = (float) (1.0 / peak);
    for (int i = 0; i < LUT_SIZE; i++) {
        double e = (i + 0.5) / LUT_SIZE;
        c->lin[i] = (float) (c->p.transfer == TC_TRANSFER_HLG ? hlg_inv_oetf(e) : pq_eotf(e));
        double u = (double) i / (LUT_SIZE - 1);
        double m = u * u * peak;
        c->scale[i] = (float) (m > 1e-6 ? eetf(m, peak, white) / m / white : 1.0 / white);
        double l = u * u;
        c->oetf[i] = (uint16_t) lrint(pow(l, 1.0 / 2.4) * 255.0 * 256.0);
    }
    return c;
}

void tc_free(tc_ctx *c) { free(c); }

static const uint8_t BAYER4[16] = {0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5};

static inline int lut_index(float v) {        /* v in [0,1], sqrt spacing */
    if (!(v > 0.0f)) return 0;                 /* also catches NaN */
    int i = (int) (sqrtf(v) * (LUT_SIZE - 1) + 0.5f);
    return i > LUT_SIZE - 1 ? LUT_SIZE - 1 : i;
}

void tc_convert(const tc_ctx *c, const uint16_t *g, int g_stride, const uint16_t *b, int b_stride,
                const uint16_t *r, int r_stride, int width, int height,
                uint8_t *out, int out_stride, int rgb565) {
    const int hlg = c->p.transfer == TC_TRANSFER_HLG;
    const int conv = c->p.bt2020_primaries;
    const float inv_peak = c->inv_peak;
    for (int y = 0; y < height; y++) {
        const uint16_t *gr = g + (long) y * g_stride, *br = b + (long) y * b_stride, *rr = r + (long) y * r_stride;
        uint8_t *o = out + (long) y * out_stride;
        for (int x = 0; x < width; x++) {
            float R = c->lin[rr[x] >> (16 - LUT_BITS)];
            float G = c->lin[gr[x] >> (16 - LUT_BITS)];
            float B = c->lin[br[x] >> (16 - LUT_BITS)];
            if (hlg) {                         /* BT.2100 OOTF, Lw = 1000 nits, gamma 1.2 */
                float ys = 0.2627f * R + 0.6780f * G + 0.0593f * B;
                float k = ys > 1e-7f ? 1000.0f * powf(ys, 0.2f) : 0.0f;
                R *= k; G *= k; B *= k;
            }
            float m = R > G ? (R > B ? R : B) : (G > B ? G : B);
            float s = c->scale[lut_index(m * inv_peak)];
            R *= s; G *= s; B *= s;             /* SDR white = 1.0 */
            if (conv) {
                float r2 = 1.6605f * R - 0.5876f * G - 0.0728f * B;
                float g2 = -0.1246f * R + 1.1329f * G - 0.0083f * B;
                float b2 = -0.0182f * R - 0.1006f * G + 1.1187f * B;
                R = r2; G = g2; B = b2;
            }
            int vr = c->oetf[lut_index(R > 1.0f ? 1.0f : R)];
            int vg = c->oetf[lut_index(G > 1.0f ? 1.0f : G)];
            int vb = c->oetf[lut_index(B > 1.0f ? 1.0f : B)];
            if (rgb565) {
                int d = BAYER4[((y & 3) << 2) | (x & 3)];
                int r5 = (vr + (2 * d + 1) * 64) >> 11;
                int g6 = (vg + (2 * d + 1) * 32) >> 10;
                int b5 = (vb + (2 * d + 1) * 64) >> 11;
                if (r5 > 31) r5 = 31;
                if (g6 > 63) g6 = 63;
                if (b5 > 31) b5 = 31;
                uint16_t px = (uint16_t) ((r5 << 11) | (g6 << 5) | b5);
                o[2 * x] = (uint8_t) (px & 0xFF);
                o[2 * x + 1] = (uint8_t) (px >> 8);
            } else {
                o[4 * x] = (uint8_t) ((vr + 128) >> 8);
                o[4 * x + 1] = (uint8_t) ((vg + 128) >> 8);
                o[4 * x + 2] = (uint8_t) ((vb + 128) >> 8);
                o[4 * x + 3] = 255;
            }
        }
    }
}
