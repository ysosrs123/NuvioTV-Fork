#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
/*
 * felgpu - Dolby Vision FEL composition on OpenGL ES 3.2 (see felgpu.h)
 *
 * Passes per frame:
 *   1. EL luma, vertical 2x Lanczos-3            (w/2 x h,   R32F)
 *   2. EL chroma, vertical 2x Lanczos-3          (w/4 x h/2, RG32F)
 *   3. chroma: horizontal EL step, NLQ, MMR or polynomial prediction on the
 *      4:2:0 grid                                (w/2 x h/2, RG16UI)
 *   4. luma: horizontal EL step, NLQ, polynomial prediction, pack the
 *      output words                              (w x h, dma-buf)
 *
 * Luma prediction and the NLQ are functions of a single 10-bit code, so
 * they are evaluated on the CPU into a lookup texture once per frame;
 * polynomial-only chroma mappings use the same table.
 */
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "felgpu.h"

#define LUT_W       1024
#define LUT_ROWS    6       /* pred Y, NLQ Y, NLQ Cb, NLQ Cr, pred Cb, pred Cr */

struct felgpu {
    int w, h, taps;
    GLuint p_vert1, p_vert2, p_chroma, p_luma;
    GLuint el_y, el_cb, el_cr;      /* uploaded EL planes, R16UI */
    GLuint el_yv, el_cv;            /* vertically upsampled, R32F / RG32F */
    GLuint p_unpack_y, p_unpack_c;  /* hardware decoded EL: packed words -> el_y, el_cb, el_cr */
    GLuint fbo_el_y, fbo_el_c;
    GLuint fbo_yv, fbo_cv;
    GLuint chroma, fbo_chroma;      /* composed 4:2:0 chroma, RG16UI */
    GLuint lut;                     /* R32F LUT_W x LUT_ROWS */
    GLuint dither;                  /* R32F BN x BN blue noise thresholds */
    int dither_mode;                /* 0 off, 1 on, 2 left half only */
    int demo;                       /* EL on the left half only */
    int pattern;                    /* 1: grey ramp test pattern instead of the video */

    /* demo mode L1 measurement and text overlay */
    GLuint p_stats, p_reduce;
    GLuint stats, fbo_stats;        /* per sample max(R',G',B') PQ codes, RG16UI sw x sh */
    GLuint red, fbo_red;            /* max / sum per REDUCE_W samples, RGBA32UI rw x sh */
    int sw, sh, rw;
    int stats_valid;
    struct felgpu_stats stat;
    GLuint *red_buf;
    GLuint pbo;                     /* the reduction's readback (GL_PIXEL_PACK_BUFFER) */
    GLsync fence;                   /* the readback in flight, 0: none */
    unsigned measure_n;             /* frames since the demo mode's start */
    int m_gx0, m_gx1;               /* the sample columns of the measurement in flight */
    int l5[4], use_l5;              /* active area from the RPU */
    int bars, bars_pend, bars_n;    /* letterbox bars found in the picture, in sample rows */
    int txt[2 * FELGPU_OVERLAY_LINES * FELGPU_OVERLAY_LEN], txt_len[2 * FELGPU_OVERLAY_LINES];
    GLuint font[2 * 32];
    float vwt_y[12], vwt_c[12], hwt_y[12], hwt_c[12];
    int vbase_y[2], vbase_c[2], hbase_y[2], hbase_c[2];

    /* per frame uniforms */
    int use_el;
    float el_max, inv_bl;
    float piv[27];
    int npiv[3];
    int cpoly[2];
    float mmr[96][4];
    float mmrc[16];
    float lutbuf[LUT_ROWS][LUT_W];

    int profile;
    double prof_ms[5], prof_t;      /* passes 0-3, 4: EL unpack (set_el_tex) */
    long prof_n;
};

/* ------------------------------------------------------------------ */
/* shaders                                                            */
/* ------------------------------------------------------------------ */


/*
 * program() prepends "#version 320 es" and "#define TAPS n". Mali loads
 * uniform array elements with a per fragment index from memory: filter
 * weights and bases are picked per phase with constant indices instead.
 */
#define GLSL_HEAD \
    "precision highp float;\n" \
    "precision highp int;\n" \
    "precision highp usampler2D;\n" \
    "precision highp sampler2D;\n" \
    "#define WSEL(a, ph, k) ((ph) == 0 ? a[k] : a[TAPS + (k)])\n" \
    "#define BSEL(a, ph) ((ph) == 0 ? a[0] : a[1])\n"

/* the dma-buf is sampled as RGBA8 = one 32-bit pixel word Cr|Cb<<10|Y<<20 */
#define GLSL_WORD \
    "uint word_at(sampler2D t, ivec2 p) { return packUnorm4x8(texelFetch(t, p, 0)); }\n" \
    "uint y_of(uint w) { return (w >> 20) & 1023u; }\n" \
    "uint cb_of(uint w) { return (w >> 10) & 1023u; }\n" \
    "uint cr_of(uint w) { return w & 1023u; }\n" \
    "vec4 pack_px(uint y, uint cb, uint cr) { return unpackUnorm4x8(cr | (cb << 10) | (y << 20)); }\n" \
    "uniform sampler2D lut;\n" \
    "float lut_at(int row, uint i) { return texelFetch(lut, ivec2(int(min(i, 1023u)), row), 0).x; }\n" \
    "uint out10(float v) { return uint(clamp(floor(v * 1024.0 + 0.5), 0.0, 1023.0)); }\n" \
    /* \
     * 10-bit quantization with a static blue noise threshold instead of 0.5 \
     * left of dither_split (0: off, see felgpu_set_dither) \
     */ \
    "uniform sampler2D dither;\n" \
    "uniform int dither_split;\n" \
    /* test pattern: 4 bands of 12-bit grey ramps, the upper band of each pair dithered */ \
    "uniform int pattern;\n" \
    "uniform int pass_h;\n" \
    "float dth(ivec2 p, ivec2 off) {\n" \
    "  bool on = pattern != 0 ? dither_split > 0 && ((p.y * 4 / pass_h) & 1) == 0\n" \
    "                         : p.x < dither_split;\n" \
    "  return on ? texelFetch(dither, (p + off) & 63, 0).x : 0.5;\n" \
    "}\n" \
    /* 10-bit (limited range) codes 160..240 and 330..410, in 12-bit steps */ \
    "float pattern_v(ivec2 p, ivec2 size) {\n" \
    "  float lo = p.y * 4 / size.y < 2 ? 160.0 : 330.0;\n" \
    "  return floor((lo + 80.0 * float(p.x) / float(size.x)) * 4.0) / 4096.0;\n" \
    "}\n" \
    "uint out10d(float v, float t) { return uint(clamp(floor(v * 1024.0 + t), 0.0, 1023.0)); }\n"

/* horizontal 2x EL step + rounding to an integer EL code */
#define GLSL_EL_H \
    "uniform float hwt[12];\n" \
    "uniform int hbase[2];\n" \
    "uniform float el_max;\n" \
    "vec2 el_h(sampler2D t, ivec2 p) {\n" \
    "  int ph = p.x & 1;\n" \
    "  int w = textureSize(t, 0).x;\n" \
    "  int b = (p.x >> 1) + BSEL(hbase, ph);\n" \
    "  vec2 acc = vec2(0.0);\n" \
    "  for (int k = 0; k < TAPS; k++)\n" \
    "    acc += WSEL(hwt, ph, k) * texelFetch(t, ivec2(clamp(b + k, 0, w - 1), p.y), 0).xy;\n" \
    "  return clamp(floor(acc + 0.5), 0.0, el_max);\n" \
    "}\n" \
    /* one component: a gather per tap pair (the textures clamp to edge) */ \
    "float el_hg(sampler2D t, ivec2 p) {\n" \
    "  int ph = p.x & 1, b = (p.x >> 1) + BSEL(hbase, ph);\n" \
    "  vec2 sz = vec2(textureSize(t, 0));\n" \
    "  float acc = 0.0;\n" \
    "  for (int k = 0; k < TAPS; k += 2) {\n" \
    /* footprint lower-left (b + k, y): .w (b + k, y), .z (b + k + 1, y) */ \
    "    vec4 g = textureGather(t, (vec2(b + k, p.y) + 1.0) / sz, 0);\n" \
    "    acc += WSEL(hwt, ph, k) * g.w + WSEL(hwt, ph, k + 1) * g.z;\n" \
    "  }\n" \
    "  return clamp(floor(acc + 0.5), 0.0, el_max);\n" \
    "}\n"

/*
 * hardware decoded EL (felgpu_set_el_tex): the packed words of the EL picture,
 * laid out like the BL, into the EL planes. Chroma at even x/y is the original
 * 4:2:0 sample (see dvfel_uapi.h), so the planes match an uploaded EL exactly.
 */
static const char *fs_el_unpack_y =
    GLSL_HEAD GLSL_WORD
    "uniform sampler2D src;\n"
    "layout(location = 0) out uint o;\n"
    "void main() {\n"
    "  o = y_of(word_at(src, ivec2(gl_FragCoord.xy)));\n"
    "}\n";

static const char *fs_el_unpack_c =
    GLSL_HEAD GLSL_WORD
    "uniform sampler2D src;\n"
    "layout(location = 0) out uint cb;\n"
    "layout(location = 1) out uint cr;\n"
    "void main() {\n"
    "  uint w = word_at(src, ivec2(gl_FragCoord.xy) * 2);\n"
    "  cb = cb_of(w);\n"
    "  cr = cr_of(w);\n"
    "}\n";

#define STR_(x) #x
#define STR(x) STR_(x)

/* demo mode: the L1 measurement samples every STATS_STEP-th pixel */
#define STATS_STEP 8
/*
 * the L1 readout is drawn 4 times a second: every MEASURE_EVERY-th frame is
 * measured, its result read back without waiting (a pixel pack buffer and a
 * fence, taken a few frames later). A glReadPixels into memory each frame
 * waited for the GPU in the middle of the frame (+15 ms a frame on a heavy
 * film, a 4K24 FEL composition over its 41.7 ms).
 */
#define MEASURE_EVERY 6
/* samples per fragment of the first reduction, the CPU sums the rest */
#define REDUCE_W 16
/* overlay text luma, 10-bit limited range (about 200 cd/m^2) */
#define TEXT_Y 560

/*
 * demo mode text: 5x7 glyphs in 6x10 cells of ovl_s pixels, from 16 cells
 * right of each half's left edge; the box behind the lines is darkened.
 * Glyph rows 0-3 are in .x (row 0 in bits 19-15), rows 4-6 in .y.
 */
#define GLSL_OVERLAY \
    "uniform bool ovl_on;\n" \
    "uniform int ovl_s;\n" \
    /* the darkened box of each half (x0, y0, x1, y1), empty if the half has no text */ \
    "uniform ivec4 ovl_box0;\n" \
    "uniform ivec4 ovl_box1;\n" \
    "uniform int ovl_len[2 * " STR(FELGPU_OVERLAY_LINES) "];\n" \
    "uniform int txt[2 * " STR(FELGPU_OVERLAY_LINES) " * " STR(FELGPU_OVERLAY_LEN) "];\n" \
    "uniform uvec2 font[32];\n" \
    "void overlay(ivec2 p, int W, inout uint y, inout uvec2 c) {\n" \
    "  bool right = p.x >= W / 2;\n" \
    "  ivec4 b = right ? ovl_box1 : ovl_box0;\n" \
    "  if (p.x < b.x || p.x >= b.z || p.y < b.y || p.y >= b.w)\n" \
    "    return;\n" \
    "  int side = right ? 1 : 0, s = ovl_s;\n" \
    "  ivec2 q = p - ivec2(side * (W / 2) + 16 * s, 8 * s);\n" \
    "  int yi = 64 + (int(y) - 64) * 3 / 10;\n" \
    "  int cb = 512 + (int(c.x) - 512) * 3 / 10, cr = 512 + (int(c.y) - 512) * 3 / 10;\n" \
    "  if (q.x >= 0 && q.y >= 0) {\n" \
    "    int line = q.y / (10 * s), col = q.x / (6 * s);\n" \
    "    int gx = (q.x - col * 6 * s) / s, gy = (q.y - line * 10 * s) / s;\n" \
    "    int l = side * " STR(FELGPU_OVERLAY_LINES) " + line;\n" \
    "    if (col < ovl_len[l] && gx < 5 && gy < 7) {\n" \
    "      uvec2 g = font[txt[l * " STR(FELGPU_OVERLAY_LEN) " + col]];\n" \
    "      uint bits = gy < 4 ? g.x >> uint(5 * (3 - gy)) : g.y >> uint(5 * (6 - gy));\n" \
    "      if (((bits >> uint(4 - gx)) & 1u) != 0u) {\n" \
    "        yi = " STR(TEXT_Y) "; cb = 512; cr = 512;\n" \
    "      }\n" \
    "    }\n" \
    "  }\n" \
    "  y = uint(yi);\n" \
    "  c = uvec2(uint(cb), uint(cr));\n" \
    "}\n"

static const char *vs_src =
    "#version 320 es\n"
    "void main() {\n"
    "  vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));\n"
    "  gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);\n"
    "}\n";

/* vertical 2x upsampling of the EL chroma: src0 (Cb) -> .x, src1 (Cr) -> .y */
static const char *fs_el_vert2 =
    GLSL_HEAD
    "uniform usampler2D src0;\n"
    "uniform usampler2D src1;\n"
    "uniform float wt[12];\n"
    "uniform int base[2];\n"
    "out vec2 o;\n"
    "void main() {\n"
    "  ivec2 p = ivec2(gl_FragCoord.xy);\n"
    "  int ph = p.y & 1;\n"
    "  int h = textureSize(src0, 0).y;\n"
    "  int b = (p.y >> 1) + BSEL(base, ph);\n"
    "  vec2 acc = vec2(0.0);\n"
    "  for (int t = 0; t < TAPS; t++) {\n"
    "    ivec2 q = ivec2(p.x, clamp(b + t, 0, h - 1));\n"
    "    acc += WSEL(wt, ph, t) * vec2(float(texelFetch(src0, q, 0).x), float(texelFetch(src1, q, 0).x));\n"
    "  }\n"
    "  o = acc;\n"
    "}\n";

/* vertical 2x upsampling of the EL luma */
static const char *fs_el_vert1 =
    GLSL_HEAD
    "uniform usampler2D src0;\n"
    "uniform float wt[12];\n"
    "uniform int base[2];\n"
    "out float o;\n"
    "void main() {\n"
    "  ivec2 p = ivec2(gl_FragCoord.xy);\n"
    "  int ph = p.y & 1;\n"
    "  int b = (p.y >> 1) + BSEL(base, ph);\n"
    "  vec2 sz = vec2(textureSize(src0, 0));\n"
    "  float acc = 0.0;\n"
    /* a gather per tap pair, footprint lower-left (x, b + t): .w (x, b + t), .x (x, b + t + 1) */
    "  for (int t = 0; t < TAPS; t += 2) {\n"
    "    uvec4 g = textureGather(src0, (vec2(p.x, b + t) + 1.0) / sz, 0);\n"
    "    acc += WSEL(wt, ph, t) * float(g.w) + WSEL(wt, ph, t + 1) * float(g.x);\n"
    "  }\n"
    "  o = acc;\n"
    "}\n";

/*
 * chroma: one fragment per 4:2:0 sample. MMR coefficients per chroma
 * component cc (0 Cb, 1 Cr) and piece k at mmr[(cc*8+k)*6 + 2*order + 0/1]:
 * (y, cb, cr, 0) and (y*cb, y*cr, cb*cr, y*cb*cr); unused orders are zero.
 * Polynomial pieces are converted to this form on the CPU.
 */
static const char *fs_chroma =
    GLSL_HEAD GLSL_WORD GLSL_EL_H
    "uniform sampler2D bl;\n"
    "uniform sampler2D elc;\n"
    "uniform float piv[27];\n"
    "uniform int npiv[3];\n"
    "uniform vec4 mmr[96];\n"
    "uniform float mmrc[16];\n"
    "uniform bool cpoly[2];\n"
    "uniform bool use_el;\n"
    "uniform float inv_bl;\n"
    "out uvec2 o;\n"
    "float mmr_eval(int b, float c0, vec3 sig) {\n"
    "  vec4 xx = vec4(sig.x * sig.y, sig.x * sig.z, sig.y * sig.z, sig.x * sig.y * sig.z);\n"
    "  vec3 x2 = sig * sig; vec4 xx2 = xx * xx;\n"
    "  return c0\n"
    "       + dot(mmr[b].xyz, sig) + dot(mmr[b + 1], xx)\n"
    "       + dot(mmr[b + 2].xyz, x2) + dot(mmr[b + 3], xx2)\n"
    "       + dot(mmr[b + 4].xyz, x2 * sig) + dot(mmr[b + 5], xx2 * xx);\n"
    "}\n"
    "float predict_mmr(int cc, float s, vec3 sig) {\n"
    "  int c = cc + 1, n = npiv[c], k = 0;\n"
    /* a single piece (the common case) with constant indices */
    "  if (n == 2)\n"
    "    return mmr_eval(cc * 48, mmrc[cc * 8], sig);\n"
    "  s = clamp(s, piv[c * 9], piv[c * 9 + n - 1]);\n"
    "  for (int i = 1; i < 8; i++)\n"
    "    k += (i < n - 1 && s >= piv[c * 9 + i]) ? 1 : 0;\n"
    "  return mmr_eval((cc * 8 + k) * 6, mmrc[cc * 8 + k], sig);\n"
    "}\n"
    "void main() {\n"
    "  ivec2 c = ivec2(gl_FragCoord.xy);\n"
    "  ivec2 p = c * 2;\n"
    "  int W = textureSize(bl, 0).x;\n"
    "  uint w0 = word_at(bl, p);\n"
    "  uint cb = cb_of(w0), cr = cr_of(w0);\n"
    "  float vcb, vcr;\n"
    "  if (cpoly[0] && cpoly[1]) {\n"
    "    vcb = lut_at(4, cb);\n"
    "    vcr = lut_at(5, cr);\n"
    "  } else {\n"
    /* luma for MMR: h (y[2n-1] + 2 y[2n] + y[2n+1] + 2) >> 2 on 2 rows, v (a + b + 1) >> 1 */
    "    int xl = max(p.x - 1, 0), xr = min(p.x + 1, W - 1);\n"
    "    uint h0 = (y_of(word_at(bl, ivec2(xl, p.y))) + 2u * y_of(w0) +\n"
    "               y_of(word_at(bl, ivec2(xr, p.y))) + 2u) >> 2;\n"
    "    uint h1 = (y_of(word_at(bl, ivec2(xl, p.y + 1))) + 2u * y_of(word_at(bl, ivec2(p.x, p.y + 1))) +\n"
    "               y_of(word_at(bl, ivec2(xr, p.y + 1))) + 2u) >> 2;\n"
    "    vec3 sig = vec3(float(min((h0 + h1 + 1u) >> 1, 1023u)), float(cb), float(cr)) * inv_bl;\n"
    "    vcb = cpoly[0] ? lut_at(4, cb) : predict_mmr(0, sig.y, sig);\n"
    "    vcr = cpoly[1] ? lut_at(5, cr) : predict_mmr(1, sig.z, sig);\n"
    "  }\n"
    /* composed on the whole picture also in demo mode: the luma pass picks the BL chroma */
    "  if (use_el) {\n"
    "    vec2 el = el_h(elc, c);\n"
    "    vcb += lut_at(2, uint(el.x));\n"
    "    vcr += lut_at(3, uint(el.y));\n"
    "  }\n"
    "  if (pattern != 0)\n"
    "    vcb = vcr = 0.5;\n"
    "  o = uvec2(out10d(vcb, dth(c, ivec2(17, 31))), out10d(vcr, dth(c, ivec2(41, 7))));\n"
    "}\n";

/* luma + packing: one fragment per output pixel word */
static const char *fs_luma =
    GLSL_HEAD GLSL_WORD GLSL_EL_H GLSL_OVERLAY
    "uniform sampler2D bl;\n"
    "uniform sampler2D ely;\n"
    "uniform usampler2D chroma;\n"
    "uniform bool use_el;\n"
    "uniform int el_split;\n"   /* demo: the composition only left of this column */
    "out vec4 o;\n"
    "void main() {\n"
    "  ivec2 p = ivec2(gl_FragCoord.xy);\n"
    "  uint w = word_at(bl, p);\n"
    "  uvec2 c = texelFetch(chroma, p >> 1, 0).xy;\n"
    "  float v = lut_at(0, y_of(w));\n"
    "  if (p.x >= el_split) {\n"   /* demo: the untouched base layer, as a no-op P8.1 RPU shows it */
    "    v = float(y_of(w)) / 1024.0;\n"
    "    uint wc = word_at(bl, (p >> 1) << 1);\n"
    "    c = pattern != 0 ? uvec2(512u) : uvec2(cb_of(wc), cr_of(wc));\n"
    "  } else if (use_el)\n"
    "    v += lut_at(1, uint(el_hg(ely, p)));\n"
    "  if (pattern != 0)\n"
    "    v = pattern_v(p, textureSize(bl, 0));\n"
    "  uint y = out10d(v, dth(p, ivec2(0)));\n"
    "  if (ovl_on)\n"
    "    overlay(p, textureSize(bl, 0).x, y, c);\n"
    "  o = pack_px(y, c.x, c.y);\n"
    "}\n";

/*
 * demo mode L1 measurement, one fragment per STATS_STEP x STATS_STEP block:
 * max(R', G', B') as a 12-bit PQ code of the composed picture (.x) and of
 * the base layer (.y) at the block's centre (an even, 4:2:0 sited pixel)
 */
static const char *fs_stats =
    GLSL_HEAD GLSL_WORD GLSL_EL_H
    "#define STATS_STEP " STR(STATS_STEP) "\n"
    "uniform sampler2D bl;\n"
    "uniform sampler2D ely;\n"
    "uniform usampler2D chroma;\n"
    "uniform bool use_el;\n"
    "uniform ivec2 act_x;\n"   /* sample columns of the active area; zero outside */
    "out uvec2 o;\n"
    /* limited range BT.2020 non-constant luminance Y'CbCr, 10-bit codes */
    "uint pq_max(float y, float cb, float cr) {\n"
    "  float Y = (y - 64.0) / 876.0, u = (cb - 512.0) / 896.0, v = (cr - 512.0) / 896.0;\n"
    "  float m = max(Y + 1.4746 * v, max(Y - 0.16455 * u - 0.57135 * v, Y + 1.8814 * u));\n"
    "  return uint(clamp(m, 0.0, 1.0) * 4095.0 + 0.5);\n"
    "}\n"
    "void main() {\n"
    "  ivec2 g = ivec2(gl_FragCoord.xy);\n"
    "  if (g.x < act_x.x || g.x >= act_x.y) {\n"
    "    o = uvec2(0u);\n"
    "    return;\n"
    "  }\n"
    "  ivec2 p = g * STATS_STEP + STATS_STEP / 2;\n"
    "  uint w = word_at(bl, p);\n"
    "  float v = lut_at(0, y_of(w));\n"
    "  if (use_el)\n"
    "    v += lut_at(1, uint(el_hg(ely, p)));\n"
    "  uvec2 c = texelFetch(chroma, p >> 1, 0).xy;\n"
    "  o = uvec2(pq_max(clamp(floor(v * 1024.0 + 0.5), 0.0, 1023.0), float(c.x), float(c.y)),\n"
    "            pq_max(float(y_of(w)), float(cb_of(w)), float(cr_of(w))));\n"
    "}\n";

/* one fragment per REDUCE_W samples of a row: max and sum of both values */
static const char *fs_reduce =
    GLSL_HEAD
    "uniform usampler2D s;\n"
    "out uvec4 o;\n"
    "void main() {\n"
    "  ivec2 q = ivec2(gl_FragCoord.xy);\n"
    "  int x0 = q.x * " STR(REDUCE_W) ", x1 = min(x0 + " STR(REDUCE_W) ", textureSize(s, 0).x);\n"
    "  uvec4 a = uvec4(0u);\n"
    "  for (int x = x0; x < x1; x++) {\n"
    "    uvec2 v = texelFetch(s, ivec2(x, q.y), 0).xy;\n"
    "    a = uvec4(max(a.xy, v), a.zw + v);\n"
    "  }\n"
    "  o = a;\n"
    "}\n";

/* overlay glyphs: 5x7, '#' set */
static const char font_chars[] = " 0123456789ABEFGILMNPRTUVX.+-%O";
static const char *const font_rows[][7] = {
    { ".....", ".....", ".....", ".....", ".....", ".....", "....." },
    { ".###.", "#...#", "#..##", "#.#.#", "##..#", "#...#", ".###." },
    { "..#..", ".##..", "..#..", "..#..", "..#..", "..#..", ".###." },
    { ".###.", "#...#", "....#", "...#.", "..#..", ".#...", "#####" },
    { "#####", "...#.", "..#..", "...#.", "....#", "#...#", ".###." },
    { "...#.", "..##.", ".#.#.", "#..#.", "#####", "...#.", "...#." },
    { "#####", "#....", "####.", "....#", "....#", "#...#", ".###." },
    { "..##.", ".#...", "#....", "####.", "#...#", "#...#", ".###." },
    { "#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#..." },
    { ".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###." },
    { ".###.", "#...#", "#...#", ".####", "....#", "...#.", ".##.." },
    { ".###.", "#...#", "#...#", "#####", "#...#", "#...#", "#...#" }, /* A */
    { "####.", "#...#", "#...#", "####.", "#...#", "#...#", "####." }, /* B */
    { "#####", "#....", "#....", "####.", "#....", "#....", "#####" }, /* E */
    { "#####", "#....", "#....", "####.", "#....", "#....", "#...." }, /* F */
    { ".###.", "#...#", "#....", "#.###", "#...#", "#...#", ".####" }, /* G */
    { ".###.", "..#..", "..#..", "..#..", "..#..", "..#..", ".###." }, /* I */
    { "#....", "#....", "#....", "#....", "#....", "#....", "#####" }, /* L */
    { "#...#", "##.##", "#.#.#", "#.#.#", "#...#", "#...#", "#...#" }, /* M */
    { "#...#", "#...#", "##..#", "#.#.#", "#..##", "#...#", "#...#" }, /* N */
    { "####.", "#...#", "#...#", "####.", "#....", "#....", "#...." }, /* P */
    { "####.", "#...#", "#...#", "####.", "#.#..", "#..#.", "#...#" }, /* R */
    { "#####", "..#..", "..#..", "..#..", "..#..", "..#..", "..#.." }, /* T */
    { "#...#", "#...#", "#...#", "#...#", "#...#", "#...#", ".###." }, /* U */
    { "#...#", "#...#", "#...#", "#...#", "#...#", ".#.#.", "..#.." }, /* V */
    { "#...#", "#...#", ".#.#.", "..#..", ".#.#.", "#...#", "#...#" }, /* X */
    { ".....", ".....", ".....", ".....", ".....", ".##..", ".##.." }, /* . */
    { ".....", "..#..", "..#..", "#####", "..#..", "..#..", "....." }, /* + */
    { ".....", ".....", ".....", "#####", ".....", ".....", "....." }, /* - */
    { "##...", "##..#", "...#.", "..#..", ".#...", "#..##", "...##" }, /* % */
    { ".###.", "#...#", "#...#", "#...#", "#...#", "#...#", ".###." }, /* O */
};

/* ------------------------------------------------------------------ */
/* GL helpers                                                         */
/* ------------------------------------------------------------------ */

static GLuint compile(GLenum type, const char *src)
{
    GLuint s = glCreateShader(type);
    glShaderSource(s, 1, &src, NULL);
    glCompileShader(s);
    GLint ok;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[4096];
        glGetShaderInfoLog(s, sizeof(log), NULL, log);
        fprintf(stderr, "felgpu: shader compile failed:\n%s\n", log);
        return 0;
    }
    return s;
}

static GLuint program(const char *fs, int taps)
{
    char *src = NULL;
    if (asprintf(&src, "#version 320 es\n#define TAPS %d\n%s", taps, fs) < 0)
        return 0;
    GLuint v = compile(GL_VERTEX_SHADER, vs_src), f = compile(GL_FRAGMENT_SHADER, src);
    free(src);
    if (!v || !f)
        return 0;
    GLuint p = glCreateProgram();
    glAttachShader(p, v);
    glAttachShader(p, f);
    glLinkProgram(p);
    glDeleteShader(v);
    glDeleteShader(f);
    GLint ok;
    glGetProgramiv(p, GL_LINK_STATUS, &ok);
    if (!ok) {
        char log[4096];
        glGetProgramInfoLog(p, sizeof(log), NULL, log);
        fprintf(stderr, "felgpu: link failed:\n%s\n", log);
        return 0;
    }
    return p;
}

static GLuint make_tex(GLenum ifmt, int w, int h)
{
    GLuint tex;
    glGenTextures(1, &tex);
    glBindTexture(GL_TEXTURE_2D, tex);
    glTexStorage2D(GL_TEXTURE_2D, 1, ifmt, w, h);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    return tex;
}

static GLuint make_fbo(GLuint tex)
{
    GLuint fbo;
    glGenFramebuffers(1, &fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0);
    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
        fprintf(stderr, "felgpu: framebuffer incomplete\n");
    return fbo;
}

static void bind_tex(GLuint p, const char *name, int unit, GLuint tex)
{
    glActiveTexture(GL_TEXTURE0 + unit);
    glBindTexture(GL_TEXTURE_2D, tex);
    glUniform1i(glGetUniformLocation(p, name), unit);
}

static void draw(GLuint fbo, int w, int h)
{
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    glViewport(0, 0, w, h);
    glDrawArrays(GL_TRIANGLES, 0, 3);
}

/* Lanczos with radius a (taps = 2a) */
static double lanczos(double x, double a)
{
    x = fabs(x);
    if (x < 1e-9) return 1.0;
    if (x >= a) return 0.0;
    return a * sin(M_PI * x) * sin(M_PI * x / a) / (M_PI * M_PI * x * x);
}

/* output index o maps to source position o / 2 + off */
static void kernel(double off, int taps, float wt[12], int base[2])
{
    int r = taps / 2;
    for (int ph = 0; ph < 2; ph++) {
        double p = ph * 0.5 + off, sum = 0, w[6];
        int i0 = (int)floor(p);
        double f = p - i0;
        base[ph] = i0 - (r - 1);
        for (int t = 0; t < taps; t++)
            sum += w[t] = lanczos((t - (r - 1)) - f, r);
        for (int t = 0; t < taps; t++)
            wt[ph * taps + t] = (float)(w[t] / sum);
    }
}

/*
 * Blue noise dither thresholds, BN x BN tileable, by void-and-cluster
 * (Ulichney 1993): a Gaussian (sigma 1.5) energy on the torus ranks every
 * cell; thresholds are (rank + 0.5) / BN^2, uniform in [0, 1).
 */
#define BN 64
#define BN_N (BN * BN)

static void bn_update(float *e, const float *gk, int at, float sign)
{
    int ax = at % BN, ay = at / BN;
    for (int y = 0; y < BN; y++)
        for (int x = 0; x < BN; x++)
            e[y * BN + x] += sign * gk[((y - ay + BN) % BN) * BN + (x - ax + BN) % BN];
}

/* most energetic set cell (cluster) or least energetic empty one (void) */
static int bn_find(const float *e, const unsigned char *bin, int set)
{
    int best = -1;
    for (int i = 0; i < BN_N; i++)
        if (bin[i] == set && (best < 0 || (set ? e[i] > e[best] : e[i] < e[best])))
            best = i;
    return best;
}

static void blue_noise(float *out)
{
    static float gk[BN_N], e[BN_N], e0[BN_N];
    static unsigned char bin[BN_N], bin0[BN_N];
    static int rank[BN_N];
    int ones = 0;

    for (int y = 0; y < BN; y++)
        for (int x = 0; x < BN; x++) {
            int dx = x < BN / 2 ? x : BN - x, dy = y < BN / 2 ? y : BN - y;
            gk[y * BN + x] = expf(-(float)(dx * dx + dy * dy) / (2.0f * 1.5f * 1.5f));
        }

    /* initial pattern: about 10% set, then move clusters into voids */
    memset(bin, 0, sizeof(bin));
    memset(e, 0, sizeof(e));
    uint32_t seed = 0x1234567u;
    while (ones < BN_N / 10) {
        seed = seed * 1664525u + 1013904223u;
        int i = (seed >> 8) % BN_N;
        if (!bin[i]) {
            bin[i] = 1;
            bn_update(e, gk, i, 1.0f);
            ones++;
        }
    }
    for (int it = 0; it < BN_N; it++) {
        int c = bn_find(e, bin, 1);
        bin[c] = 0;
        bn_update(e, gk, c, -1.0f);
        int v = bn_find(e, bin, 0);
        bin[v] = 1;
        bn_update(e, gk, v, 1.0f);
        if (v == c)
            break;
    }
    memcpy(bin0, bin, sizeof(bin));
    memcpy(e0, e, sizeof(e));

    /* ranks below the initial pattern: remove the tightest clusters */
    for (int r = ones - 1; r >= 0; r--) {
        int c = bn_find(e, bin, 1);
        bin[c] = 0;
        bn_update(e, gk, c, -1.0f);
        rank[c] = r;
    }
    /* ranks above it: fill the largest voids */
    memcpy(bin, bin0, sizeof(bin));
    memcpy(e, e0, sizeof(e));
    for (int r = ones; r < BN_N; r++) {
        int v = bn_find(e, bin, 0);
        bin[v] = 1;
        bn_update(e, gk, v, 1.0f);
        rank[v] = r;
    }
    for (int i = 0; i < BN_N; i++)
        out[i] = (rank[i] + 0.5f) / BN_N;
}

static double now_ms(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1e3 + ts.tv_nsec / 1e6;
}

static void prof_mark(struct felgpu *f, int pass)
{
    if (!f->profile)
        return;
    glFinish();
    double t = now_ms();
    if (pass >= 0)
        f->prof_ms[pass] += t - f->prof_t;
    f->prof_t = t;
}

/* ------------------------------------------------------------------ */

struct felgpu *felgpu_create(int w, int h, int taps)
{
    struct felgpu *f = calloc(1, sizeof(*f));
    if (!f)
        return NULL;
    if (taps != 4 && taps != 6)
        taps = 6;
    f->w = w;
    f->h = h;
    f->taps = taps;
    f->p_vert1 = program(fs_el_vert1, taps);
    f->p_vert2 = program(fs_el_vert2, taps);
    f->p_chroma = program(fs_chroma, taps);
    f->p_luma = program(fs_luma, taps);
    f->p_unpack_y = program(fs_el_unpack_y, taps);
    f->p_unpack_c = program(fs_el_unpack_c, taps);
    f->p_stats = program(fs_stats, taps);
    f->p_reduce = program(fs_reduce, taps);
    if (!f->p_vert1 || !f->p_vert2 || !f->p_chroma || !f->p_luma || !f->p_unpack_y ||
        !f->p_unpack_c || !f->p_stats || !f->p_reduce) {
        free(f);
        return NULL;
    }
    f->el_y = make_tex(GL_R16UI, w / 2, h / 2);
    f->el_cb = make_tex(GL_R16UI, w / 4, h / 4);
    f->el_cr = make_tex(GL_R16UI, w / 4, h / 4);
    f->el_yv = make_tex(GL_R32F, w / 2, h);
    f->el_cv = make_tex(GL_RG32F, w / 4, h / 2);
    f->chroma = make_tex(GL_RG16UI, w / 2, h / 2);
    f->lut = make_tex(GL_R32F, LUT_W, LUT_ROWS);
    f->dither = make_tex(GL_R32F, BN, BN);
    {
        static float bn[BN_N];
        blue_noise(bn);
        glBindTexture(GL_TEXTURE_2D, f->dither);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, BN, BN, GL_RED, GL_FLOAT, bn);
    }
    f->fbo_yv = make_fbo(f->el_yv);
    f->fbo_cv = make_fbo(f->el_cv);
    f->fbo_chroma = make_fbo(f->chroma);
    f->fbo_el_y = make_fbo(f->el_y);
    f->fbo_el_c = make_fbo(f->el_cb);
    {
        static const GLenum bufs[] = { GL_COLOR_ATTACHMENT0, GL_COLOR_ATTACHMENT1 };
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT1, GL_TEXTURE_2D, f->el_cr, 0);
        glDrawBuffers(2, bufs);
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
            fprintf(stderr, "felgpu: EL chroma framebuffer incomplete\n");
    }

    f->sw = w / STATS_STEP;
    f->sh = h / STATS_STEP;
    f->rw = (f->sw + REDUCE_W - 1) / REDUCE_W;
    f->stats = make_tex(GL_RG16UI, f->sw, f->sh);
    f->red = make_tex(GL_RGBA32UI, f->rw, f->sh);
    f->fbo_stats = make_fbo(f->stats);
    f->fbo_red = make_fbo(f->red);
    /* the readback, then the brightest sample of each row */
    f->red_buf = calloc((size_t)f->rw * f->sh * 4 + f->sh, sizeof(GLuint));
    glGenBuffers(1, &f->pbo);
    glBindBuffer(GL_PIXEL_PACK_BUFFER, f->pbo);
    glBufferData(GL_PIXEL_PACK_BUFFER, (GLsizeiptr)f->rw * f->sh * 4 * sizeof(GLuint), NULL, GL_STREAM_READ);
    glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
    f->bars_pend = f->sh;
    for (size_t g = 0; g < sizeof(font_rows) / sizeof(font_rows[0]) && g < 32; g++) {
        GLuint rows = 0, rows2 = 0;
        for (int r = 0; r < 7; r++)
            for (int x = 0; x < 5; x++)
                if (font_rows[g][r][x] == '#') {
                    if (r < 4)
                        rows |= 1u << (5 * (3 - r) + 4 - x);
                    else
                        rows2 |= 1u << (5 * (6 - r) + 4 - x);
                }
        f->font[2 * g] = rows;
        f->font[2 * g + 1] = rows2;
    }

    /* EL sample phase: centered 2x (see fel_compose.c); chroma top-left sited */
    const double ce = 0.5;
    kernel(-ce / 2, taps, f->vwt_y, f->vbase_y);
    kernel(-ce / 2, taps, f->hwt_y, f->hbase_y);
    kernel(-ce / 4, taps, f->vwt_c, f->vbase_c);
    kernel(-ce / 4, taps, f->hwt_c, f->hbase_c);
    return f;
}

void felgpu_destroy(struct felgpu *f)
{
    if (!f)
        return;
    GLuint tex[] = { f->el_y, f->el_cb, f->el_cr, f->el_yv, f->el_cv, f->chroma, f->lut, f->dither,
                     f->stats, f->red };
    GLuint fbo[] = { f->fbo_yv, f->fbo_cv, f->fbo_chroma, f->fbo_el_y, f->fbo_el_c, f->fbo_stats,
                     f->fbo_red };
    glDeleteTextures(10, tex);
    glDeleteFramebuffers(7, fbo);
    glDeleteProgram(f->p_unpack_y);
    glDeleteProgram(f->p_unpack_c);
    glDeleteProgram(f->p_vert1);
    glDeleteProgram(f->p_vert2);
    glDeleteProgram(f->p_chroma);
    glDeleteProgram(f->p_luma);
    glDeleteProgram(f->p_stats);
    glDeleteProgram(f->p_reduce);
    if (f->fence)
        glDeleteSync(f->fence);
    glDeleteBuffers(1, &f->pbo);
    free(f->red_buf);
    free(f);
}

void felgpu_set_el(struct felgpu *f, const uint16_t *y, int y_stride,
                   const uint16_t *cb, const uint16_t *cr, int c_stride)
{
    glPixelStorei(GL_UNPACK_ALIGNMENT, 2);
    glPixelStorei(GL_UNPACK_ROW_LENGTH, y_stride);
    glBindTexture(GL_TEXTURE_2D, f->el_y);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, f->w / 2, f->h / 2, GL_RED_INTEGER, GL_UNSIGNED_SHORT, y);
    glPixelStorei(GL_UNPACK_ROW_LENGTH, c_stride);
    glBindTexture(GL_TEXTURE_2D, f->el_cb);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, f->w / 4, f->h / 4, GL_RED_INTEGER, GL_UNSIGNED_SHORT, cb);
    glBindTexture(GL_TEXTURE_2D, f->el_cr);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, f->w / 4, f->h / 4, GL_RED_INTEGER, GL_UNSIGNED_SHORT, cr);
    glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
}

void felgpu_set_el_tex(struct felgpu *f, GLuint el)
{
    prof_mark(f, -1);
    glUseProgram(f->p_unpack_y);
    bind_tex(f->p_unpack_y, "src", 0, el);
    draw(f->fbo_el_y, f->w / 2, f->h / 2);
    glUseProgram(f->p_unpack_c);
    bind_tex(f->p_unpack_c, "src", 0, el);
    draw(f->fbo_el_c, f->w / 4, f->h / 4);
    prof_mark(f, 4);
}

static int piece_of(const struct felgpu_meta *m, int c, int code)
{
    int n = m->npiv[c], k = 0;
    for (int i = 1; i < n - 1; i++)
        if (code >= m->pivots[c][i])
            k = i;
    return k;
}

/* polynomial prediction of a single component code (luma, or poly chroma) */
static float predict_poly(const struct felgpu_meta *m, int c, int code)
{
    int n = m->npiv[c];
    if (code < m->pivots[c][0]) code = m->pivots[c][0];
    if (code > m->pivots[c][n - 1]) code = m->pivots[c][n - 1];
    int k = piece_of(m, c, code);
    double s = code / (double)(1 << m->bl_bits);
    const double *a = m->poly[c][k];
    return (float)((a[2] * s + a[1]) * s + a[0]);
}

static float nlq_res(const struct felgpu_meta *m, int c, int R)
{
    int d = R - m->nlq_offset[c];
    if (d == 0)
        return 0.0f;
    double sg = d < 0 ? -1.0 : 1.0;
    double r = m->nlq_slope[c] * (d - 0.5 * sg) + m->nlq_thresh[c] * sg;
    if (r > m->nlq_max[c]) r = m->nlq_max[c];
    if (r < -m->nlq_max[c]) r = -m->nlq_max[c];
    return (float)r;
}

void felgpu_set_meta(struct felgpu *f, const struct felgpu_meta *m)
{
    double scale = 1.0 / (1 << m->bl_bits);

    f->use_el = m->use_el;
    f->el_max = (float)((1 << m->el_bits) - 1);
    f->inv_bl = (float)scale;
    memset(f->piv, 0, sizeof(f->piv));
    for (int c = 0; c < 3; c++) {
        f->npiv[c] = m->npiv[c];
        for (int i = 0; i < m->npiv[c] && i < 9; i++)
            f->piv[c * 9 + i] = (float)(m->pivots[c][i] * scale);
    }

    /* chroma: all polynomial -> LUT, otherwise MMR form for every piece */
    memset(f->mmr, 0, sizeof(f->mmr));
    memset(f->mmrc, 0, sizeof(f->mmrc));
    for (int cc = 0; cc < 2; cc++) {
        int c = cc + 1;
        f->cpoly[cc] = 1;
        for (int k = 0; k < m->npiv[c] - 1; k++) {
            float *v = f->mmr[(cc * 8 + k) * 6];
            if (m->mapping[c][k] == 0) {
                /* a0 + a1 s + a2 s^2 on the own component */
                f->mmrc[cc * 8 + k] = (float)m->poly[c][k][0];
                v[c] = (float)m->poly[c][k][1];
                v[8 + c] = (float)m->poly[c][k][2];
            } else {
                f->cpoly[cc] = 0;
                f->mmrc[cc * 8 + k] = (float)m->mmr_const[c][k];
                for (int o = 0; o < m->mmr_order[c][k] && o < 3; o++) {
                    float *lin = v + o * 8, *x = v + o * 8 + 4;
                    lin[0] = (float)m->mmr[c][k][o][0];
                    lin[1] = (float)m->mmr[c][k][o][1];
                    lin[2] = (float)m->mmr[c][k][o][2];
                    x[0] = (float)m->mmr[c][k][o][3];
                    x[1] = (float)m->mmr[c][k][o][4];
                    x[2] = (float)m->mmr[c][k][o][5];
                    x[3] = (float)m->mmr[c][k][o][6];
                }
            }
        }
    }

    /* lookup tables */
    for (int i = 0; i < LUT_W; i++) {
        f->lutbuf[0][i] = predict_poly(m, 0, i);
        f->lutbuf[4][i] = f->cpoly[0] ? predict_poly(m, 1, i) : 0.0f;
        f->lutbuf[5][i] = f->cpoly[1] ? predict_poly(m, 2, i) : 0.0f;
        for (int c = 0; c < 3; c++)
            f->lutbuf[1 + c][i] = m->use_el ? nlq_res(m, c, i) : 0.0f;
    }
    glBindTexture(GL_TEXTURE_2D, f->lut);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, LUT_W, LUT_ROWS, GL_RED, GL_FLOAT, f->lutbuf);
}

/* first column of a pass without dither */
static int dither_split(const struct felgpu *f, int width)
{
    return f->dither_mode == 1 ? width : f->dither_mode == 2 ? width / 2 : 0;
}

/* SMPTE ST 2084: normalized PQ -> cd/m^2 */
static float pq_nits(double n)
{
    const double m1 = 0.1593017578125, m2 = 78.84375;
    const double c1 = 0.8359375, c2 = 18.8515625, c3 = 18.6875;
    double e = pow(n < 0.0 ? 0.0 : n, 1.0 / m2);
    return (float)(10000.0 * pow(fmax(e - c1, 0.0) / (c2 - c3 * e), 1.0 / m1));
}

/* first and last + 1 sample (at pixel g * STATS_STEP + STATS_STEP / 2) inside [lo, n - hi) */
static void sample_range(int n, int samples, int lo, int hi, int *g0, int *g1)
{
    *g0 = 0;
    *g1 = samples;
    while (*g0 < samples && *g0 * STATS_STEP + STATS_STEP / 2 < lo)
        (*g0)++;
    while (*g1 > *g0 && (*g1 - 1) * STATS_STEP + STATS_STEP / 2 >= n - hi)
        (*g1)--;
}

/* letterbox bars: rows whose brightest sample stays below this 12-bit PQ code (about 0.03 cd/m^2) */
#define BAR_PQ 150
/* frames a larger bar has to be seen before it is taken (a dark scene may extend the bars), in measurements */
#define BAR_FRAMES (48 / MEASURE_EVERY)

/*
 * letterbox bars in sample rows from the brightest BL sample of each row: the
 * smaller of the black runs at the top and at the bottom (bars are
 * symmetric, a dark picture edge is not). Smaller bars are taken at once,
 * larger ones only after BAR_FRAMES frames; black frames tell nothing.
 */
static void find_bars(struct felgpu *f, const GLuint *row_max)
{
    int t = 0, b = 0;
    while (t < f->sh && row_max[t] < BAR_PQ)
        t++;
    if (t == f->sh)
        return;
    while (row_max[f->sh - 1 - b] < BAR_PQ)
        b++;
    int c = t < b ? t : b;
    if (c <= f->bars) {
        f->bars = c;
        f->bars_n = 0;
        f->bars_pend = f->sh;
    } else {
        if (c < f->bars_pend)
            f->bars_pend = c;
        if (++f->bars_n >= BAR_FRAMES) {
            f->bars = f->bars_pend;
            f->bars_n = 0;
            f->bars_pend = f->sh;
        }
    }
}

/* demo mode: the L1 of a measurement read back (f->red_buf), see felgpu_get_stats */
static void measure_result(struct felgpu *f)
{
    int gx0 = f->m_gx0, gx1 = f->m_gx1, gy0, gy1;

    /*
     * per row: the brightest base layer sample, for the bars (the bars of the
     * base layer are clean black, the mapping and the EL may lift them a bit)
     */
    GLuint *row_max = f->red_buf + (size_t)f->rw * f->sh * 4;
    for (int y = 0; y < f->sh; y++) {
        GLuint m = 0;
        for (int x = 0; x < f->rw; x++)
            if (f->red_buf[(y * f->rw + x) * 4 + 1] > m)
                m = f->red_buf[(y * f->rw + x) * 4 + 1];
        row_max[y] = m;
    }

    if (f->use_l5) {
        sample_range(f->h, f->sh, f->l5[2], f->l5[3], &gy0, &gy1);
    } else {
        find_bars(f, row_max);
        gy0 = f->bars;
        gy1 = f->sh - f->bars;
    }
    if (gx1 <= gx0 || gy1 <= gy0) {
        gx0 = gy0 = 0;
        gx1 = f->sw;
        gy1 = f->sh;
    }

    GLuint mx[2] = { 0, 0 };
    uint64_t sum[2] = { 0, 0 };
    for (int y = gy0; y < gy1; y++)
        for (int x = 0; x < f->rw; x++) {
            const GLuint *v = f->red_buf + (y * f->rw + x) * 4;
            for (int i = 0; i < 2; i++) {
                if (v[i] > mx[i])
                    mx[i] = v[i];
                sum[i] += v[2 + i];
            }
        }
    const double n = (double)(gx1 - gx0) * (gy1 - gy0) * 4095.0;
    for (int i = 0; i < 2; i++) {
        f->stat.nits[2 * i] = pq_nits(mx[i] / 4095.0);
        f->stat.nits[2 * i + 1] = pq_nits(sum[i] / n);
    }
    f->stat.x0 = gx0 * STATS_STEP;
    f->stat.x1 = gx1 * STATS_STEP;
    f->stat.y0 = gy0 * STATS_STEP;
    f->stat.y1 = gy1 * STATS_STEP;
    f->stat.source = f->use_l5 ? FELGPU_AREA_L5
                   : gy0 > 0 ? FELGPU_AREA_DETECTED : FELGPU_AREA_FULL;
    f->stats_valid = 1;
}

/* the measurement in flight, when the GPU has finished it (never waits) */
static void measure_collect(struct felgpu *f)
{
    if (!f->fence)
        return;
    const GLenum r = glClientWaitSync(f->fence, 0, 0);
    if (r != GL_ALREADY_SIGNALED && r != GL_CONDITION_SATISFIED)
        return;
    glDeleteSync(f->fence);
    f->fence = 0;
    const size_t n = (size_t)f->rw * f->sh * 4 * sizeof(GLuint);
    glBindBuffer(GL_PIXEL_PACK_BUFFER, f->pbo);
    const void *m = glMapBufferRange(GL_PIXEL_PACK_BUFFER, 0, (GLsizeiptr)n, GL_MAP_READ_BIT);
    if (m) {
        memcpy(f->red_buf, m, n);
        glUnmapBuffer(GL_PIXEL_PACK_BUFFER);
    }
    glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
    if (m)
        measure_result(f);
}

/* demo mode: L1 of the composed picture and of the base layer, every MEASURE_EVERY-th frame */
static void measure(struct felgpu *f, GLuint bl_tex)
{
    measure_collect(f);
    if (f->fence || f->measure_n++ % MEASURE_EVERY)
        return;
    int gx0, gx1;
    sample_range(f->w, f->sw, f->use_l5 ? f->l5[0] : 0, f->use_l5 ? f->l5[1] : 0, &gx0, &gx1);
    f->m_gx0 = gx0;
    f->m_gx1 = gx1;

    GLuint p = f->p_stats;
    glUseProgram(p);
    glUniform2i(glGetUniformLocation(p, "act_x"), gx0, gx1);
    glUniform1i(glGetUniformLocation(p, "use_el"), f->use_el);
    glUniform1f(glGetUniformLocation(p, "el_max"), f->el_max);
    glUniform1fv(glGetUniformLocation(p, "hwt"), 12, f->hwt_y);
    glUniform1iv(glGetUniformLocation(p, "hbase"), 2, f->hbase_y);
    bind_tex(p, "bl", 0, bl_tex);
    bind_tex(p, "ely", 1, f->el_yv);
    bind_tex(p, "chroma", 2, f->chroma);
    bind_tex(p, "lut", 3, f->lut);
    draw(f->fbo_stats, f->sw, f->sh);

    p = f->p_reduce;
    glUseProgram(p);
    bind_tex(p, "s", 0, f->stats);
    draw(f->fbo_red, f->rw, f->sh);
    glBindBuffer(GL_PIXEL_PACK_BUFFER, f->pbo);
    glReadPixels(0, 0, f->rw, f->sh, GL_RGBA_INTEGER, GL_UNSIGNED_INT, 0);
    glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
    f->fence = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
}

void felgpu_run(struct felgpu *f, GLuint bl_tex, GLuint out_fbo)
{
    GLuint p;

    prof_mark(f, -1);
    if (f->use_el) {
        p = f->p_vert1;
        glUseProgram(p);
        glUniform1fv(glGetUniformLocation(p, "wt"), 12, f->vwt_y);
        glUniform1iv(glGetUniformLocation(p, "base"), 2, f->vbase_y);
        bind_tex(p, "src0", 0, f->el_y);
        draw(f->fbo_yv, f->w / 2, f->h);
        prof_mark(f, 0);
        p = f->p_vert2;
        glUseProgram(p);
        glUniform1fv(glGetUniformLocation(p, "wt"), 12, f->vwt_c);
        glUniform1iv(glGetUniformLocation(p, "base"), 2, f->vbase_c);
        bind_tex(p, "src0", 0, f->el_cb);
        bind_tex(p, "src1", 1, f->el_cr);
        draw(f->fbo_cv, f->w / 4, f->h / 2);
        prof_mark(f, 1);
    }

    p = f->p_chroma;
    glUseProgram(p);
    glUniform1fv(glGetUniformLocation(p, "piv"), 27, f->piv);
    glUniform1iv(glGetUniformLocation(p, "npiv"), 3, f->npiv);
    glUniform4fv(glGetUniformLocation(p, "mmr"), 96, &f->mmr[0][0]);
    glUniform1fv(glGetUniformLocation(p, "mmrc"), 16, f->mmrc);
    glUniform1iv(glGetUniformLocation(p, "cpoly"), 2, f->cpoly);
    glUniform1i(glGetUniformLocation(p, "use_el"), f->use_el);
    glUniform1f(glGetUniformLocation(p, "inv_bl"), f->inv_bl);
    glUniform1f(glGetUniformLocation(p, "el_max"), f->el_max);
    glUniform1fv(glGetUniformLocation(p, "hwt"), 12, f->hwt_c);
    glUniform1iv(glGetUniformLocation(p, "hbase"), 2, f->hbase_c);
    bind_tex(p, "bl", 0, bl_tex);
    bind_tex(p, "elc", 1, f->el_cv);
    bind_tex(p, "lut", 2, f->lut);
    bind_tex(p, "dither", 3, f->dither);
    glUniform1i(glGetUniformLocation(p, "dither_split"), dither_split(f, f->w / 2));
    glUniform1i(glGetUniformLocation(p, "pattern"), f->pattern);
    glUniform1i(glGetUniformLocation(p, "pass_h"), f->h / 2);
    draw(f->fbo_chroma, f->w / 2, f->h / 2);
    prof_mark(f, 2);

    if (f->demo && !f->pattern)
        measure(f, bl_tex);

    p = f->p_luma;
    glUseProgram(p);
    glUniform1i(glGetUniformLocation(p, "use_el"), f->use_el);
    glUniform1f(glGetUniformLocation(p, "el_max"), f->el_max);
    glUniform1fv(glGetUniformLocation(p, "hwt"), 12, f->hwt_y);
    glUniform1iv(glGetUniformLocation(p, "hbase"), 2, f->hbase_y);
    bind_tex(p, "bl", 0, bl_tex);
    bind_tex(p, "ely", 1, f->el_yv);
    bind_tex(p, "chroma", 2, f->chroma);
    bind_tex(p, "lut", 3, f->lut);
    bind_tex(p, "dither", 4, f->dither);
    glUniform1i(glGetUniformLocation(p, "dither_split"), dither_split(f, f->w));
    glUniform1i(glGetUniformLocation(p, "el_split"), f->demo ? f->w / 2 : f->w);
    glUniform1i(glGetUniformLocation(p, "pattern"), f->pattern);
    glUniform1i(glGetUniformLocation(p, "pass_h"), f->h);
    /* overlay boxes, see GLSL_OVERLAY: text from 16 cells right, 8 down of each half's corner */
    const int s = f->h >= 1080 ? f->h / 540 : 1;
    int ovl = 0, box[2][4];
    for (int side = 0; side < 2; side++) {
        /* lines down to the last non-empty one, as wide as the longest */
        int nl = 0, cols = 0;
        for (int l = 0; l < FELGPU_OVERLAY_LINES; l++) {
            int n = f->txt_len[side * FELGPU_OVERLAY_LINES + l];
            if (n > 0)
                nl = l + 1;
            if (n > cols)
                cols = n;
        }
        int x = side * (f->w / 2) + 16 * s, y = 8 * s;
        box[side][0] = x - 3 * s;
        box[side][1] = y - 3 * s;
        box[side][2] = nl ? x + cols * 6 * s + 2 * s : box[side][0];
        box[side][3] = y + nl * 10 * s;
        ovl |= nl;
    }
    glUniform1i(glGetUniformLocation(p, "ovl_on"), f->demo && ovl);
    glUniform1i(glGetUniformLocation(p, "ovl_s"), s);
    glUniform4iv(glGetUniformLocation(p, "ovl_box0"), 1, box[0]);
    glUniform4iv(glGetUniformLocation(p, "ovl_box1"), 1, box[1]);
    glUniform1iv(glGetUniformLocation(p, "ovl_len"), 2 * FELGPU_OVERLAY_LINES, f->txt_len);
    glUniform1iv(glGetUniformLocation(p, "txt"), 2 * FELGPU_OVERLAY_LINES * FELGPU_OVERLAY_LEN, f->txt);
    glUniform2uiv(glGetUniformLocation(p, "font"), 32, f->font);
    draw(out_fbo, f->w, f->h);
    prof_mark(f, 3);
    f->prof_n++;
}

void felgpu_set_profile(struct felgpu *f, int on)
{
    f->profile = on;
}

void felgpu_set_dither(struct felgpu *f, int mode)
{
    f->dither_mode = mode;
}

void felgpu_set_demo(struct felgpu *f, int on)
{
    if (on != f->demo) {
        f->stats_valid = 0;
        f->measure_n = 0;
    }
    f->demo = on;
}

int felgpu_get_stats(struct felgpu *f, struct felgpu_stats *s)
{
    if (!f->stats_valid)
        return 0;
    *s = f->stat;
    return 1;
}

void felgpu_set_active_area(struct felgpu *f, const int offsets[4])
{
    f->use_l5 = offsets && offsets[0] >= 0 && offsets[1] >= 0 && offsets[2] >= 0 && offsets[3] >= 0;
    if (f->use_l5)
        memcpy(f->l5, offsets, sizeof(f->l5));
}

void felgpu_set_overlay(struct felgpu *f, int line, const char *text)
{
    if (line < 0 || line >= 2 * FELGPU_OVERLAY_LINES)
        return;
    int n = 0;
    for (; text && text[n] && n < FELGPU_OVERLAY_LEN; n++) {
        const char *g = strchr(font_chars, text[n]);
        f->txt[line * FELGPU_OVERLAY_LEN + n] = g ? (int)(g - font_chars) : 0;
    }
    f->txt_len[line] = n;
}

void felgpu_set_pattern(struct felgpu *f, int pattern)
{
    f->pattern = pattern;
}

void felgpu_print_profile(struct felgpu *f)
{
    long n = f->prof_n ? f->prof_n : 1;
    if (f->profile)
        printf("per pass avg: EL-V luma %.2f, EL-V chroma %.2f, chroma %.2f, luma+pack %.2f ms\n",
               f->prof_ms[0] / n, f->prof_ms[1] / n, f->prof_ms[2] / n, f->prof_ms[3] / n);
}

int felgpu_get_profile(struct felgpu *f, double ms[5], int reset)
{
    long n = f->prof_n;
    for (int i = 0; i < 5; i++) {
        ms[i] = n ? f->prof_ms[i] / n : 0.0;
        if (reset)
            f->prof_ms[i] = 0.0;
    }
    if (reset)
        f->prof_n = 0;
    return (int)n;
}
