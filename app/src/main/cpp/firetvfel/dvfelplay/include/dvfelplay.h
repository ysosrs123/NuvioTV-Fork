/*
 *  Copyright (C) 2026 Team CoreELEC
 *
 *  SPDX-License-Identifier: GPL-2.0-or-later
 */

/*
 * dvfelplay - Dolby Vision profile 7 FEL playback on Android with an Amlogic
 * S5 (S928X) and the dvfel kernel module, for any MediaCodec based player.
 *
 * The S5 Dolby Vision hardware has no enhancement layer input. The player
 * keeps decoding the base layer with its own MediaCodec instance (the Amlogic
 * Dolby Vision decoder, "video/dolby-vision", rendering into a SurfaceView),
 * and passes every access unit through dvfp_filter() before queueing it:
 *
 *   - the RPU is rewritten to profile 8.1 with a no-op mapping,
 *   - the EL NAL units (type 63) are removed from the base layer stream.
 *
 * In FEL mode this library decodes the EL itself (a second MediaCodec, its
 * pictures imported by the kernel) and composes BL + EL + the original RPU
 * mapping on the GPU for every frame the video composer shows (the dvfel
 * kernel module sits between video_composer.0 and video_render.0).
 *
 * Usage, per video stream (one FEL stream at a time per device):
 *
 *   first access unit:  dvfp_probe()  -> profile 7 FEL? dvfp_available()?
 *   decoder setup:      dvfp_open(w, h, DVFP_MODE_FEL or DVFP_MODE_CONVERT)
 *                       before the first frame can reach the display
 *   every access unit:  dvfp_filter() -> queue the output to MediaCodec
 *                       queued:  dvfp_commit(pts_us)   (the pts given to
 *                                MediaCodec for this access unit)
 *                       not queued: dvfp_drop()
 *   seek / flush:       dvfp_flush() (with the MediaCodec flush)
 *   end:                dvfp_close()
 *
 * Input is Annex-B (start codes); one call per access unit (the BL picture
 * with its NAL 62 RPU and NAL 63 EL units). Codec config data (VPS/SPS/PPS
 * only) may be passed through unfiltered.
 *
 * All functions of one handle must be called from one thread (the decoder
 * input thread); the handle runs its own EL decoder and GPU threads.
 */
#ifndef DVFELPLAY_H
#define DVFELPLAY_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define DVFP_API_VERSION 1

#if defined(__GNUC__)
#define DVFP_EXPORT __attribute__((visibility("default")))
#else
#define DVFP_EXPORT
#endif

typedef struct dvfp dvfp;

/* logging: the default goes to the Android log (tag "dvfelplay") */
enum dvfp_log_level
{
  DVFP_LOG_DEBUG = 0,
  DVFP_LOG_INFO = 1,
  DVFP_LOG_WARN = 2,
  DVFP_LOG_ERROR = 3,
};
typedef void (*dvfp_log_fn)(void* opaque, int level, const char* msg);
DVFP_EXPORT void dvfp_set_log(dvfp_log_fn fn, void* opaque);

DVFP_EXPORT int dvfp_api_version(void);
/* this library's version string (date) */
DVFP_EXPORT const char* dvfp_version(void);

/*
 * 1 if FEL composition can run in this process: the dvfel kernel module is
 * loaded (recent enough for direct display), /dev/dvfel and the ION device
 * are accessible
 */
DVFP_EXPORT int dvfp_available(void);
/* the loaded kernel module version (empty string if none); returns buf */
DVFP_EXPORT const char* dvfp_module_version(char* buf, size_t size);

/*
 * Installs the kernel module bundled with this library (Dune HD / R_volution
 * firmware): loads it and installs it with a boot script (loaded at every
 * boot), unless the same or a newer module is loaded already. Needs root
 * (su); when the firmware's root access is off, ask(opaque, title, text)
 * decides whether to switch it on for the installation (> 0: yes; it is
 * switched off again afterwards). A "no" is remembered for this module
 * version. Blocks for the installation (a few seconds) and the question; the
 * result is kept for the process (one try). dir: a writable directory of
 * the app (NULL: /data/data/<package>/files). msg (optional): a line about
 * the outcome for the user. A firmware that brings the module itself (Dune HD
 * r25 on, plugin s928x_dvfel) keeps its own: its module, of any version, is
 * READY, and nothing is installed there (FAILED when it is not loaded).
 * Optional symbol.
 */
enum dvfp_module_status
{
  DVFP_MODULE_INSTALLED = 1, /* installed now */
  DVFP_MODULE_READY = 0,     /* loaded already */
  DVFP_MODULE_DECLINED = -1, /* the user said no (now or before) */
  DVFP_MODULE_NO_ROOT = -2,  /* no su, or root access could not be switched on */
  DVFP_MODULE_FAILED = -3,
};
typedef int (*dvfp_ask_fn)(void* opaque, const char* title, const char* text);
DVFP_EXPORT int dvfp_module_install(const char* dir, dvfp_ask_fn ask, void* opaque, char* msg,
                                    size_t msg_size);
/* the version of the bundled kernel module ("" if none) */
DVFP_EXPORT const char* dvfp_bundled_module_version(void);

enum dvfp_el_type
{
  DVFP_EL_NONE = 0,
  DVFP_EL_MEL = 1,
  DVFP_EL_FEL = 2,
};
struct dvfp_rpu_info
{
  int has_rpu;  /* an RPU (NAL 62) was found and parsed */
  int profile;  /* guessed Dolby Vision profile (4, 5, 7, 8 ...), 0 if unknown */
  int el_type;  /* enum dvfp_el_type (profile 7) */
  int has_el;   /* the access unit carries EL NAL units (type 63) */
};
/* Dolby Vision information of an Annex-B access unit; 0 on success */
DVFP_EXPORT int dvfp_probe(const uint8_t* au, size_t size, struct dvfp_rpu_info* info);

enum dvfp_mode
{
  /* RPU -> profile 8.1 (profile 7 only), EL removed; no composition */
  DVFP_MODE_CONVERT = 0,
  /* the above plus EL decoding and GPU composition (profile 7 FEL) */
  DVFP_MODE_FEL = 1,
};
/*
 * width, height: the base layer size (the EL is half of it in both
 * directions). DVFP_MODE_FEL starts the EL decoder and the GPU compositor;
 * the kernel routes the video composer through dvfel from then on. NULL on
 * failure (FEL: play with DVFP_MODE_CONVERT instead).
 */
DVFP_EXPORT dvfp* dvfp_open(int width, int height, int mode);
DVFP_EXPORT void dvfp_close(dvfp* h);
DVFP_EXPORT int dvfp_mode(const dvfp* h);

/*
 * filters one Annex-B access unit. *out (owned by the handle, valid until the
 * next call of any dvfp function on it) is what goes to the decoder. An RPU
 * that cannot be converted goes unchanged (logged). A picture without an RPU
 * in a stream that had one gets the last RPU again (the Dolby Vision decoder
 * and the composition then keep the mapping instead of showing the base layer
 * as it is). Returns 0, < 0 on invalid arguments (*out is then the input).
 */
DVFP_EXPORT int dvfp_filter(dvfp* h, const uint8_t* in, size_t size, const uint8_t** out,
                            size_t* out_size);
/* the last filtered access unit was queued to the decoder with this pts (us) */
DVFP_EXPORT void dvfp_commit(dvfp* h, int64_t pts_us);
/* the last filtered access unit was not queued (it is filtered again) */
DVFP_EXPORT void dvfp_drop(dvfp* h);
/*
 * seek / decoder flush; the access unit filtered last stays pending (it may
 * be queued after the flush, then commit it as usual)
 */
DVFP_EXPORT void dvfp_flush(dvfp* h);

struct dvfp_stats
{
  uint64_t composed;      /* frames composed with their EL */
  uint64_t mapped_only;   /* composed with the RPU mapping only (no EL) */
  uint64_t passthrough;   /* frames without EL (includes mapped_only) */
  uint64_t no_el_skipped; /* no EL could come (leading pictures, EL ahead) */
  uint64_t el_decoded;    /* EL pictures decoded and imported */
  uint64_t el_inputs;     /* EL access units given to the EL decoder */
  uint64_t el_outputs;
  uint64_t el_overflow;   /* EL pictures dropped with the pool full */
  uint64_t no_crc_match;  /* frames whose RPU CRC matched no packet (shown as decoded) */
  uint64_t stale;         /* decoded before a flush, shown after it (mapping only) */
  double gpu_ms_avg;      /* GPU time per composed frame */
  double gpu_ms_max;
};
DVFP_EXPORT void dvfp_get_stats(const dvfp* h, struct dvfp_stats* stats);

/*
 * the render time to give MediaCodec (releaseOutputBufferAtTime, ns of
 * CLOCK_MONOTONIC) for a frame meant for desired_ns: the middle between the
 * display's vsyncs before the one nearest to desired_ns, so that jitter never
 * moves two frames into one vsync (a drop) and none into the next (a repeat).
 * Tracks the vsyncs with AChoreographer (started at the first call; returns
 * desired_ns unchanged until the period is known). Optional symbol (newer
 * than the first API version 1 libraries).
 */
DVFP_EXPORT int64_t dvfp_snap_present_ns(int64_t desired_ns);

/*
 * lead time (DVFP_MODE_FEL, kernel module 2026.10.07.1+): the player
 * presents every frame this many ns (whole vsyncs of the display) before
 * its time, i.e. it schedules and renders it for (time - lead), and snaps
 * that with dvfp_snap_present_ns(); the kernel module holds each composed
 * frame back to the vsync it was meant for, which gives the GPU this much
 * more time. 0: none (old module, not FEL, or the vsync period not known
 * yet). Follows the display rate (call it per frame). Optional symbol.
 */
DVFP_EXPORT int64_t dvfp_present_lead_ns(const dvfp* h);

/*
 * demo mode (DVFP_MODE_FEL): split screen, the left half with the FEL
 * composition, the right half as the base layer converted to profile 8.1
 * shows it; the top of each half shows the level 1 brightness measured over
 * the active picture (max and average of max(R', G', B'), in nits) with and
 * without the enhancement layer, their difference, the RPU's own level 1 and
 * the measured area. 0: off (default). Takes effect within a second.
 * Optional symbol.
 *
 * A player's menu can switch it during playback through two files in the
 * app's internal files dir (/data/data/<package>/files): dvfp_fel exists
 * while the FEL composition runs (show the switch then; it holds "demo=0" or
 * "demo=1", the current state); dvfp_demo holds "1"
 * (on) or "0" (off) and, once there, overrides dvfp_set_demo for the current
 * playback (the library removes it when a FEL composition starts).
 */
DVFP_EXPORT void dvfp_set_demo(dvfp* h, int on);

#ifdef __cplusplus
}
#endif

#endif
