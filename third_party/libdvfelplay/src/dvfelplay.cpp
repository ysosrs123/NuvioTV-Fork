/*
 *  Copyright (C) 2026 Team CoreELEC
 *
 *  SPDX-License-Identifier: GPL-2.0-or-later
 */

/*
 * Dolby Vision profile 7 FEL composition on Android with an Amlogic S5
 * (S928X), player independent (see dvfelplay.h). The Android counterpart
 * of CAMLDvFel (CoreELEC).
 *
 * The player's MediaCodec decodes the base layer; dvfp_filter() rewrites the
 * RPU to profile 8.1 with a no-op mapping and keeps the original RPU and the
 * EL NAL units of the access unit. The dvfel kernel module, routed in after
 * the video composer (video_composer.0 -> dvfel -> video_render.0), hands
 * every decoded base layer frame to the GPU thread here, which composes it
 * with the enhancement layer and the original RPU mapping.
 *
 * EL decoding: a second MediaCodec instance (the Amlogic HEVC decoder) renders
 * into an AImageReader; every output buffer carries the decoder's 10-bit AFBC
 * frame, which dvfel decompresses into a linear buffer of the EL pool
 * (DVFEL_IOC_EL_IMPORT) for the GPU.
 *
 * Pairing: the frame timestamp seen by the kernel is the video composer's
 * display time, not the packet pts. The kernel reports the rpu_data_crc32 of
 * the RPU the decoder got with the frame instead; each access unit's sent RPU
 * CRC is filed with its pts, and a frame takes the earliest pending pts with
 * its CRC (frames arrive in display order).
 */

#include "dvfelplay.h"

#include "dvfel_sc2_uapi.h"
#include "dvfel_uapi.h"
#include "felgpu.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <climits>
#include <cmath>
#include <condition_variable>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <deque>
#include <fstream>
#include <future>
#include <map>
#include <memory>
#include <mutex>
#include <set>
#include <string>
#include <thread>
#include <vector>

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2ext.h>
#include <GLES3/gl32.h>
#include <android/hardware_buffer.h>
#include <android/log.h>
#include <android/looper.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <media/NdkImageReader.h>
#include <media/NdkMediaCodec.h>
#include <pthread.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <sys/system_properties.h>
#include <unistd.h>

#include <libdovi/rpu_parser.h>

#ifndef DRM_FORMAT_ABGR8888
#define DRM_FORMAT_ABGR8888 0x34324241 /* 'AB24' */
#endif

#define DVFP_VERSION "2026.10.28.1"

namespace
{
constexpr const char* DVFEL_DEV = "/dev/dvfel";

std::string DevicePath()
{
  const std::string scoped = std::string(DVFEL_DEV) + "." + std::to_string(getuid());
  struct stat st{};
  if (lstat(scoped.c_str(), &st) == 0 && S_ISCHR(st.st_mode))
    return scoped;
  return DVFEL_DEV;
}
// the kernel module shows the composed frames directly from this version on
constexpr const char* MIN_MODULE = "2026.10.06.2";
// 4: with the lead time (DVFEL_IOC_SET_LEAD) more frames are in the chain
constexpr int GPU_BUFFERS = 4;
constexpr int EL_TAPS = 6;             // Lanczos-3, see felgpu.h
constexpr int EL_WAIT_MS = 80;         // wait for the EL frame of a job (the kernel waits 150)
// packets are fed well ahead of display; EL decoding is paced to stay at most
// EL_LEAD_US ahead of the frame the kernel asked for last
constexpr int64_t EL_LEAD_US = 350000;
constexpr size_t MAX_EL_QUEUE = 400;   // compressed EL packets
constexpr size_t MAX_CRC = 2048;       // packets waiting for their frame
constexpr size_t MAX_META = 512;       // RPU mappings kept for frames without EL
constexpr size_t CRC_SEARCH = 64;      // pending packets a frame may skip (dropped frames)
constexpr int64_t PTS_TOLERANCE_US = 1000;
constexpr int EL_POOL = 12;

// hardware EL decoder: renders into an AImageReader (at most 2 images, the
// decoder allows 12 output buffers in all); the dma-buf of each image
// carries the decoder frame
constexpr const char* HW_EL_CODEC = "OMX.amlogic.hevc.decoder.awesome2";
constexpr int HW_EL_IMAGES = 2;
// SC2 (S905X4): the Dolby Vision core composes. Each EL picture goes to the
// kernel and its image back to the reader at once, with a release fence: the
// decoder decodes into the buffer again only after the display. The decoder
// has 12 output buffers in all: the reader gets 3 images (4: the decoder
// cannot allocate its buffers)
constexpr int SC2_EL_IMAGES = 3;
constexpr const char* PROP_SC2_IMAGES = "debug.dvfp.sc2_images"; // test: SC2_EL_IMAGES
// EL pictures the kernel holds (queued ahead of their frames, on display)
constexpr int SC2_EL_AHEAD = 4;
// the pictures in the decoder and the ones with the kernel stay below the 12
// buffers, or the decoder waits for a fence (1 s) and then decodes into a
// buffer still on display (seen in a pause)
constexpr int SC2_EL_BUFFERS = 8; // 6: the decoder gives no output, 10: more fence waits
constexpr const char* PROP_SC2_BUFFERS = "debug.dvfp.sc2_buffers"; // test: SC2_EL_BUFFERS
// the SC2 kernel module this library talks to
constexpr const char* MIN_MODULE_SC2 = "2026.10.25.2";
constexpr int HW_EL_IMAGE_WAIT_MS = 40;
// a picture still in the EL decoder this far (us) behind its latest output
// will not come out any more (dropped by the decoder as broken)
constexpr int64_t EL_LOST_US = 1000000;
// no input buffer and no output for this long: the EL decoder is stuck, it is flushed
constexpr int EL_STALL_MS = 1500;

// test switches (adb shell setprop ...), read once a second while composing
constexpr const char* PROP_DITHER = "debug.dvfp.dither";   // 0 round, 1 dither (default), 2 half
constexpr const char* PROP_PATTERN = "debug.dvfp.pattern"; // 1: grey ramps
constexpr const char* PROP_DEMO = "debug.dvfp.demo";       // 1: left half composed only
constexpr const char* PROP_NOGPU = "debug.dvfp.nogpu";     // 1: dvfel path without GPU work
constexpr const char* PROP_PROFILE = "debug.dvfp.profile"; // 1: GPU pass times every 5 s to the log, 2: frame times only
constexpr const char* PROP_NICE = "debug.dvfp.nice";       // nice value of the GPU and EL threads (test)
constexpr const char* PROP_DUMP = "debug.dvfp.dump";       // n: dump n composed frames for tools/felbench
constexpr const char* PROP_LEAD = "debug.dvfp.lead_ms";    // lead time (test), default: the module's out_lead_ms

// ---------------------------------------------------------------------------
// logging

std::mutex g_logLock;
dvfp_log_fn g_logFn = nullptr;
void* g_logOpaque = nullptr;

__attribute__((format(printf, 2, 3))) void Log(int level, const char* fmt, ...)
{
  char msg[512];
  va_list ap;
  va_start(ap, fmt);
  vsnprintf(msg, sizeof(msg), fmt, ap);
  va_end(ap);
  std::lock_guard<std::mutex> lock(g_logLock);
  if (g_logFn)
  {
    g_logFn(g_logOpaque, level, msg);
    return;
  }
  static const int prio[] = {ANDROID_LOG_DEBUG, ANDROID_LOG_INFO, ANDROID_LOG_WARN, ANDROID_LOG_ERROR};
  __android_log_write(prio[std::clamp(level, 0, 3)], "dvfelplay", msg);
}
#define LOGD(...) Log(DVFP_LOG_DEBUG, __VA_ARGS__)
#define LOGI(...) Log(DVFP_LOG_INFO, __VA_ARGS__)
#define LOGW(...) Log(DVFP_LOG_WARN, __VA_ARGS__)
#define LOGE(...) Log(DVFP_LOG_ERROR, __VA_ARGS__)

// ---------------------------------------------------------------------------
// ION (Android 4.12+ kernel API); the frame buffers must be physically
// contiguous for the kernel's VICP: the codec_mm CMA heap

constexpr const char* ION_DEV = "/dev/ion";
constexpr const char* ION_HEAP = "codec_mm_cma";
struct ion_allocation_data
{
  uint64_t len;
  uint32_t heap_id_mask;
  uint32_t flags;
  uint32_t fd;
  uint32_t unused;
};
struct ion_heap_data
{
  char name[32];
  uint32_t type;
  uint32_t heap_id;
  uint32_t reserved0;
  uint32_t reserved1;
  uint32_t reserved2;
};
struct ion_heap_query
{
  uint32_t cnt;
  uint32_t reserved0;
  uint64_t heaps;
  uint32_t reserved1;
  uint32_t reserved2;
};
#define ION_IOC_MAGIC 'I'
#define ION_IOC_ALLOC _IOWR(ION_IOC_MAGIC, 0, struct ion_allocation_data)
#define ION_IOC_HEAP_QUERY _IOWR(ION_IOC_MAGIC, 8, struct ion_heap_query)

// heap id of ION_HEAP, -1 if missing
int IonHeapId(int ion)
{
  ion_heap_query q{};
  if (ioctl(ion, ION_IOC_HEAP_QUERY, &q) < 0 || !q.cnt)
    return -1;
  std::vector<ion_heap_data> heaps(q.cnt);
  q.heaps = reinterpret_cast<uintptr_t>(heaps.data());
  if (ioctl(ion, ION_IOC_HEAP_QUERY, &q) < 0)
    return -1;
  for (uint32_t i = 0; i < q.cnt && i < heaps.size(); i++)
    if (!strncmp(heaps[i].name, ION_HEAP, sizeof(heaps[i].name)))
      return static_cast<int>(heaps[i].heap_id);
  return -1;
}

int IonAlloc(int ion, int heapId, size_t len)
{
  ion_allocation_data a{};
  a.len = len;
  a.heap_id_mask = 1u << heapId;
  // the Amlogic codec_mm heap allocates only with ION_FLAG_EXTEND_MESON_HEAP;
  // uncached otherwise: written by VICP and the GPU
  a.flags = 1u << 30;
  return ioctl(ion, ION_IOC_ALLOC, &a) < 0 ? -1 : static_cast<int>(a.fd);
}

// ---------------------------------------------------------------------------
// helpers

std::string ReadLine(const char* path)
{
  std::ifstream f(path);
  std::string s;
  std::getline(f, s);
  return s;
}

// an Amlogic S905X4 (SC2): FEL composition by the SoC's Dolby Vision core
// (the dvfel SC2 module), not on the GPU
bool UsesHardwareComposer()
{
  static const bool sc2 = [] {
    char v[PROP_VALUE_MAX] = {};
    return __system_property_get("ro.board.platform", v) > 0 &&
           (!strcmp(v, "sc2") || !strcmp(v, "gazelle"));
  }();
  return sc2;
}

bool IsGazelleBoard()
{
  static const bool gazelle = [] {
    char board[PROP_VALUE_MAX] = {};
    return __system_property_get("ro.board.platform", board) > 0 && !strcmp(board, "gazelle");
  }();
  return gazelle;
}

// Gazelle's MediaCodec registry uses the original HEVC component name.
const char* HardwareElCodec()
{
  static const std::string codec = [] {
    char name[PROP_VALUE_MAX] = {};
    __system_property_get("debug.dvfp.el_codec", name);
    if (name[0]) return std::string(name);
    char board[PROP_VALUE_MAX] = {};
    __system_property_get("ro.board.platform", board);
    return std::string(!strcmp(board, "gazelle") ?
                       "OMX.amlogic.hevc.decoder.awesome" : HW_EL_CODEC);
  }();
  return codec.c_str();
}

int PropInt(const char* name, int fallback)
{
  char v[PROP_VALUE_MAX] = {};
  if (__system_property_get(name, v) <= 0)
    return fallback;
  char* end = nullptr;
  const long n = strtol(v, &end, 10);
  return end != v ? static_cast<int>(n) : fallback;
}

// a file in the app's internal files dir: the player's menu switches the demo
// mode with dvfp_demo ("1" / "0"); dvfp_fel exists while the FEL composition
// runs (the menu shows the switch then)
std::string AppFile(const char* name)
{
  static const std::string dir = [] {
    std::string pkg = ReadLine("/proc/self/cmdline");
    pkg = pkg.substr(0, pkg.find('\0'));
    pkg = pkg.substr(0, pkg.find(':'));
    return "/data/data/" + pkg + "/files/";
  }();
  return dir + name;
}

// the menu's demo mode: 1 on, 0 off, -1 not set
int DemoFile()
{
  FILE* f = fopen(AppFile("dvfp_demo").c_str(), "r");
  if (!f)
    return -1;
  const int c = fgetc(f);
  fclose(f);
  return c == '1' ? 1 : c == '0' ? 0 : -1;
}

// test dumps: <external files dir of the app>/dvfp_dump
std::string DumpDir()
{
  std::string pkg = ReadLine("/proc/self/cmdline");
  pkg = pkg.substr(0, pkg.find('\0'));
  pkg = pkg.substr(0, pkg.find(':'));
  const std::string dir = "/sdcard/Android/data/" + pkg + "/files/dvfp_dump";
  mkdir(("/sdcard/Android/data/" + pkg + "/files").c_str(), 0770);
  mkdir(dir.c_str(), 0770);
  return dir;
}

// the first size bytes of a dma-buf of len bytes to a file
bool DumpFd(const std::string& path, int fd, size_t len, size_t size)
{
  void* p = mmap(nullptr, len, PROT_READ, MAP_SHARED, fd, 0);
  if (p == MAP_FAILED)
    return false;
  FILE* f = fopen(path.c_str(), "wb");
  const bool ok = f && fwrite(p, 1, size, f) == size;
  if (f)
    fclose(f);
  munmap(p, len);
  return ok;
}

// the calling thread's nice value from PROP_NICE (unset: unchanged)
void SetThreadNice(const char* what)
{
  const int nice = PropInt(PROP_NICE, INT_MIN);
  if (nice == INT_MIN)
    return;
  const int r = setpriority(PRIO_PROCESS, gettid(), nice);
  LOGI("%s thread nice %d: %s", what, nice, r ? strerror(errno) : "set");
}

double Fixed(int64_t ipart, uint64_t fpart, uint64_t denom)
{
  return static_cast<double>(ipart) + std::ldexp(static_cast<double>(fpart), -static_cast<int>(denom));
}

// original (profile 7) RPU -> composition metadata; nullptr if not usable
std::shared_ptr<felgpu_meta> ParseRpu(const uint8_t* nal, size_t size,
                                      const std::shared_ptr<felgpu_meta>& prev)
{
  DoviRpuOpaque* rpu = dovi_parse_unspec62_nalu(nal, size);
  if (!rpu)
    return nullptr;

  std::shared_ptr<felgpu_meta> meta;
  const DoviRpuDataHeader* hdr = dovi_rpu_get_header(rpu);
  const DoviRpuDataMapping* map = hdr ? dovi_rpu_get_data_mapping(rpu) : nullptr;

  if (hdr && !map && hdr->use_prev_vdr_rpu_flag)
  {
    meta = prev;
  }
  else if (hdr && map && hdr->coefficient_data_type == 0)
  {
    const uint64_t denom = hdr->coefficient_log2_denom;
    meta = std::make_shared<felgpu_meta>();
    felgpu_meta& m = *meta;
    std::memset(&m, 0, sizeof(m));
    m.bl_bits = static_cast<int>(hdr->bl_bit_depth_minus8) + 8;
    m.el_bits = static_cast<int>(hdr->el_bit_depth_minus8) + 8;
    m.use_el = !hdr->disable_residual_flag && map->nlq_method_idc == 0 && map->nlq;

    for (int c = 0; c < 3 && meta; c++)
    {
      const DoviReshapingCurve& cv = map->curves[c];
      const int n = static_cast<int>(cv.num_pivots_minus2) + 2;
      if (n > FELGPU_MAX_PIECES + 1 || cv.pivots.len < static_cast<size_t>(n))
      {
        meta = nullptr;
        break;
      }
      m.npiv[c] = n;
      int acc = 0;
      for (int i = 0; i < n; i++) // pivots are coded as deltas
        m.pivots[c][i] = acc += cv.pivots.data[i];

      for (int k = 0; k < n - 1; k++)
      {
        m.mapping[c][k] = cv.mapping_idc;
        if (cv.mapping_idc == 0 && cv.polynomial)
        {
          const DoviPolynomialCurve& p = *cv.polynomial;
          const int order = static_cast<int>(p.poly_order_minus1.data[k]) + 1;
          for (int j = 0; j <= order && j < 3; j++)
            m.poly[c][k][j] = Fixed(p.poly_coef_int.list[k]->data[j], p.poly_coef.list[k]->data[j], denom);
        }
        else if (cv.mapping_idc == 1 && cv.mmr)
        {
          const DoviMMRCurve& r = *cv.mmr;
          const int order = r.mmr_order_minus1.data[k] + 1;
          m.mmr_order[c][k] = order;
          m.mmr_const[c][k] = Fixed(r.mmr_constant_int.data[k], r.mmr_constant.data[k], denom);
          for (int o = 0; o < order && o < 3; o++)
            for (int t = 0; t < 7; t++)
              m.mmr[c][k][o][t] = Fixed(r.mmr_coef_int.list[k]->list[o]->data[t],
                                        r.mmr_coef.list[k]->list[o]->data[t], denom);
        }
        else
        {
          meta = nullptr;
          break;
        }
      }
      if (meta && m.use_el)
      {
        const DoviRpuDataNlq& q = *map->nlq;
        m.nlq_offset[c] = q.nlq_offset[c];
        m.nlq_slope[c] = Fixed(q.linear_deadzone_slope_int[c], q.linear_deadzone_slope[c], denom);
        m.nlq_thresh[c] = Fixed(q.linear_deadzone_threshold_int[c], q.linear_deadzone_threshold[c], denom);
        m.nlq_max[c] = Fixed(q.vdr_in_max_int[c], q.vdr_in_max[c], denom);
      }
    }
  }

  // level 1 and the level 5 active area for the demo mode measurement; they
  // change per frame, also when the mapping is reused from the previous RPU
  if (meta)
  {
    int l1Max = -1, l1Avg = -1;
    int l5[4] = {-1, -1, -1, -1};
    const DoviVdrDmData* dm = dovi_rpu_get_vdr_dm_data(rpu);
    if (dm && dm->dm_data.level1)
    {
      l1Max = dm->dm_data.level1->max_pq;
      l1Avg = dm->dm_data.level1->avg_pq;
    }
    // all zero offsets are often written for letterboxed titles too: the
    // bars are then found in the picture
    if (dm && dm->dm_data.level5 &&
        (dm->dm_data.level5->active_area_left_offset || dm->dm_data.level5->active_area_right_offset ||
         dm->dm_data.level5->active_area_top_offset || dm->dm_data.level5->active_area_bottom_offset))
    {
      l5[0] = dm->dm_data.level5->active_area_left_offset;
      l5[1] = dm->dm_data.level5->active_area_right_offset;
      l5[2] = dm->dm_data.level5->active_area_top_offset;
      l5[3] = dm->dm_data.level5->active_area_bottom_offset;
    }
    if (dm)
      dovi_rpu_free_vdr_dm_data(dm);
    if (meta == prev && (meta->l1_max_pq != l1Max || meta->l1_avg_pq != l1Avg ||
                         std::memcmp(meta->l5, l5, sizeof(l5)) != 0))
      meta = std::make_shared<felgpu_meta>(*prev);
    meta->l1_max_pq = l1Max;
    meta->l1_avg_pq = l1Avg;
    std::memcpy(meta->l5, l5, sizeof(l5));
  }

  if (map)
    dovi_rpu_free_data_mapping(map);
  if (hdr)
    dovi_rpu_free_header(hdr);
  dovi_rpu_free(rpu);
  return meta;
}

// SMPTE ST 2084, 12-bit PQ code -> cd/m^2
float PqNits(int code)
{
  const double m1 = 0.1593017578125, m2 = 78.84375;
  const double c1 = 0.8359375, c2 = 18.8515625, c3 = 18.6875;
  const double e = std::pow(std::max(code, 0) / 4095.0, 1.0 / m2);
  return static_cast<float>(10000.0 * std::pow(std::max(e - c1, 0.0) / (c2 - c3 * e), 1.0 / m1));
}

std::string Nits(float v, bool sign = false)
{
  char s[24];
  if (std::fabs(v) < 10.0f)
    snprintf(s, sizeof(s), "%.1f", std::fabs(v));
  else
    snprintf(s, sizeof(s), "%ld", std::lround(std::fabs(v)));
  return std::string(sign ? (v < 0.0f ? "-" : "+") : (v < 0.0f ? "-" : "")) + s;
}

// demo mode: the measured L1 of both pictures, the RPU's own and the measured
// area at the top of each half, see felgpu_get_stats
void SetDemoOverlay(felgpu* fel, const felgpu_stats& s, const felgpu_meta& m)
{
  const float* n = s.nits;
  const char* src = s.source == FELGPU_AREA_L5 ? "L5" : s.source == FELGPU_AREA_DETECTED ? "AUTO" : "FULL";
  char area[48];
  snprintf(area, sizeof(area), "AREA %dX%d %s", s.x1 - s.x0, s.y1 - s.y0, src);
  felgpu_set_overlay(fel, 0, ("FEL  MAX " + Nits(n[0]) + "  AVG " + Nits(n[1]) + " NIT").c_str());
  felgpu_set_overlay(fel, 1, m.l1_max_pq < 0 ? "RPU L1  -"
                                             : ("RPU L1  MAX " + Nits(PqNits(m.l1_max_pq)) + "  AVG " +
                                                Nits(PqNits(m.l1_avg_pq)) + " NIT")
                                                   .c_str());
  felgpu_set_overlay(fel, 2, area);
  felgpu_set_overlay(fel, 3, ("BL   MAX " + Nits(n[2]) + "  AVG " + Nits(n[3]) + " NIT").c_str());
  felgpu_set_overlay(fel, 4, ("FEL-BL  MAX " + Nits(n[0] - n[2], true) + "  AVG " +
                              Nits(n[1] - n[3], true) + " NIT")
                                 .c_str());
}

// NAL unit payload without the emulation prevention bytes (00 00 03)
std::vector<uint8_t> Unescape(const uint8_t* nal, size_t size)
{
  std::vector<uint8_t> out;
  out.reserve(size);
  int zeros = 0;
  for (size_t i = 0; i < size; i++)
  {
    const uint8_t b = nal[i];
    if (zeros >= 2 && b == 3)
    {
      zeros = 0;
      continue;
    }
    zeros = b ? 0 : zeros + 1;
    out.push_back(b);
  }
  return out;
}

// rpu_data_crc32 of an RPU NAL unit (without start code) as the dvfel kernel
// module reports it: the 32 bits before the RBSP stop bit byte 0x80. The
// decoder hands the kernel the RPU already unescaped, and the module removes
// 00 00 03 once more: a CRC that starts with 03 after two zero bytes comes as
// 00..., so the same is done here.
bool RpuTailCrc(const uint8_t* nal, size_t size, uint32_t& crc)
{
  const std::vector<uint8_t> rbsp = Unescape(nal, size);
  uint8_t win[5] = {}, last[5] = {};
  uint32_t n = 0;
  int zeros = 0;
  bool have = false;
  for (const uint8_t b : rbsp)
  {
    if (zeros >= 2 && b == 3)
    {
      zeros = 0;
      continue;
    }
    zeros = b ? 0 : zeros + 1;
    std::memmove(win, win + 1, 4);
    win[4] = b;
    n++;
    if (b && n >= 5)
    {
      std::memcpy(last, win, 5);
      have = true;
    }
  }
  if (!have || last[4] != 0x80)
    return false;
  crc = (uint32_t(last[0]) << 24) | (uint32_t(last[1]) << 16) | (uint32_t(last[2]) << 8) | last[3];
  return true;
}

// HEVC NAL unit types of an Annex-B access unit: the first slice's type
// (-1 without slices) and whether it ends a coded video sequence
void ScanNals(const std::vector<uint8_t>& au, int& vclType, bool& endOfSeq)
{
  vclType = -1;
  endOfSeq = false;
  for (size_t i = 0; i + 4 < au.size(); i++)
  {
    if (au[i] != 0 || au[i + 1] != 0 || au[i + 2] != 1)
      continue;
    // like FFmpeg, skip invalid NAL units (forbidden bit, temporal id 0)
    // and other layers; some EL tracks carry such stray units
    const uint8_t h0 = au[i + 3], h1 = au[i + 4];
    const int layer = ((h0 & 1) << 5) | (h1 >> 3);
    if ((h0 & 0x80) || layer != 0 || (h1 & 7) == 0)
    {
      i += 3;
      continue;
    }
    const int type = (h0 >> 1) & 0x3f;
    if (type < 32 && vclType < 0)
      vclType = type;
    else if (type == 36 || type == 37) // end of sequence / bitstream
      endOfSeq = true;
    i += 3;
  }
}

// calls fn(nal, size) for each NAL unit (without start code) of an Annex-B
// buffer; trailing zero bytes (of a following 4-byte start code) are not part
// of the unit
template<typename F>
void ForEachNal(const uint8_t* p, size_t size, F fn)
{
  size_t i = 0;
  // the first start code
  while (i + 3 <= size && !(p[i] == 0 && p[i + 1] == 0 && p[i + 2] == 1))
    i++;
  while (i + 3 <= size)
  {
    const size_t start = i + 3;
    size_t j = start;
    while (j + 3 <= size && !(p[j] == 0 && p[j + 1] == 0 && p[j + 2] == 1))
      j++;
    size_t end = j + 3 <= size ? j : size;
    while (end > start && p[end - 1] == 0)
      end--;
    if (end > start)
      fn(p + start, end - start);
    i = j;
  }
}

constexpr int NalType(const uint8_t* nal)
{
  return (nal[0] >> 1) & 0x3f;
}

// NDK functions newer than the minimum API level of some players, loaded at
// run time
struct NdkImageApi
{
  struct NativeHandle
  {
    int version, numFds, numInts;
    int data[0];
  };
  media_status_t (*newWithUsage)(int32_t, int32_t, int32_t, uint64_t, int32_t, AImageReader**);
  media_status_t (*getHardwareBuffer)(const AImage*, AHardwareBuffer**);
  const NativeHandle* (*getNativeHandle)(const AHardwareBuffer*); // LL-NDK

  bool Load()
  {
    void* media = dlopen("libmediandk.so", RTLD_NOW);
    void* window = dlopen("libnativewindow.so", RTLD_NOW);
    if (!media || !window)
      return false;
    newWithUsage = reinterpret_cast<decltype(newWithUsage)>(dlsym(media, "AImageReader_newWithUsage"));
    getHardwareBuffer =
        reinterpret_cast<decltype(getHardwareBuffer)>(dlsym(media, "AImage_getHardwareBuffer"));
    getNativeHandle = reinterpret_cast<decltype(getNativeHandle)>(
        dlsym(window, "AHardwareBuffer_getNativeHandle"));
    return newWithUsage && getHardwareBuffer && getNativeHandle;
  }
};
constexpr int32_t AIMAGE_FORMAT_PRIVATE_ = 0x22;
constexpr uint64_t AHB_USAGE_GPU_SAMPLED_IMAGE = 1ULL << 8;

// std::map lookup of the entry closest to pts, within the tolerance
template<typename T>
typename std::map<int64_t, T>::iterator FindPts(std::map<int64_t, T>& m, int64_t pts)
{
  auto it = m.lower_bound(pts - PTS_TOLERANCE_US);
  if (it != m.end() && std::llabs(it->first - pts) <= PTS_TOLERANCE_US)
    return it;
  return m.end();
}

constexpr uint8_t START_CODE[] = {0, 0, 0, 1};

// ---------------------------------------------------------------------------
// display vsync tracking (AChoreographer on a looper thread of its own)

class VsyncTracker
{
public:
  static VsyncTracker& Get()
  {
    static VsyncTracker* t = new VsyncTracker(); // lives with the process
    return *t;
  }

  int64_t Snap(int64_t desired)
  {
    const int64_t last = m_last, period = m_period;
    {
      // test statistics: how far ahead the player releases its frames
      timespec ts;
      clock_gettime(CLOCK_MONOTONIC, &ts);
      const int64_t margin = desired - (int64_t(ts.tv_sec) * 1000000000 + ts.tv_nsec);
      m_snapN++;
      m_snapSum += margin;
      if (margin < 0)
        m_snapLate++;
      int64_t mn = m_snapMin;
      while (margin < mn && !m_snapMin.compare_exchange_weak(mn, margin))
        ;
    }
    if (!last || period <= 0)
      return desired;
    // the vsync nearest to the desired time, half a period before it
    const double k = std::floor(double(desired - last) / double(period) + 0.5);
    return last + int64_t(k * double(period)) - period / 2;
  }

  int64_t Period() const { return m_period; }

  // release statistics since the last call: frames, late ones, avg/min ms ahead
  std::string TakeStats()
  {
    const int64_t n = m_snapN.exchange(0), late = m_snapLate.exchange(0), sum = m_snapSum.exchange(0),
                  mn = m_snapMin.exchange(INT64_MAX);
    char b[128];
    snprintf(b, sizeof(b), "%lld released, %lld late, ahead avg %.1f min %.1f ms", (long long)n,
             (long long)late, n ? sum / 1e6 / n : 0.0, n ? mn / 1e6 : 0.0);
    return b;
  }

private:
  typedef void (*FrameCb64)(int64_t, void*);
  typedef void (*FrameCbLegacy)(long, void*);
  struct AChoreographer;
  AChoreographer* (*m_getInstance)() = nullptr;
  void (*m_post64)(AChoreographer*, FrameCb64, void*) = nullptr;
  void (*m_postLegacy)(AChoreographer*, FrameCbLegacy, void*) = nullptr;
  AChoreographer* m_chor = nullptr;
  std::atomic<int64_t> m_last{0};
  std::atomic<int64_t> m_period{0};
  std::atomic<int64_t> m_snapN{0}, m_snapLate{0}, m_snapSum{0}, m_snapMin{INT64_MAX};
  int64_t m_deltas[32] = {};
  int m_nDeltas = 0;

  VsyncTracker() { std::thread(&VsyncTracker::Run, this).detach(); }

  void Run()
  {
    pthread_setname_np(pthread_self(), "dvfp-vsync");
    void* lib = dlopen("libandroid.so", RTLD_NOW);
    if (lib)
    {
      m_getInstance = reinterpret_cast<decltype(m_getInstance)>(dlsym(lib, "AChoreographer_getInstance"));
      m_post64 = reinterpret_cast<decltype(m_post64)>(dlsym(lib, "AChoreographer_postFrameCallback64"));
      if (PropInt("debug.dvfp.legacy_vsync", 1))
        m_postLegacy = reinterpret_cast<decltype(m_postLegacy)>(dlsym(lib, "AChoreographer_postFrameCallback"));
    }
    ALooper* looper = ALooper_prepare(0);
    if (!looper || !m_getInstance || (!m_post64 && !m_postLegacy) || !(m_chor = m_getInstance()))
    {
      LOGW("no AChoreographer: render times are not aligned to the vsync");
      return;
    }
    if (!m_post64)
      LOGI("using API24 Choreographer with monotonic timestamp reconstruction");
    Post();
    while (true)
      ALooper_pollOnce(-1, nullptr, nullptr, nullptr);
  }

  void Post()
  {
    if (m_post64)
      m_post64(m_chor, &VsyncTracker::OnFrame, this);
    else
      m_postLegacy(m_chor, &VsyncTracker::OnFrameLegacy, this);
  }

  static void OnFrameLegacy(long frameTimeNanos, void* data)
  {
    int64_t full = frameTimeNanos;
    if (sizeof(long) == 4)
    {
      // API24 truncates nanoseconds to long on ARM32. Reconstruct the nearest
      // CLOCK_MONOTONIC epoch, including a wrap between vsync and callback.
      timespec ts;
      clock_gettime(CLOCK_MONOTONIC, &ts);
      const int64_t now = int64_t(ts.tv_sec) * 1000000000 + ts.tv_nsec;
      const uint32_t lowDelta = uint32_t(frameTimeNanos) - uint32_t(now);
      const int64_t delta = lowDelta < 0x80000000u ? int64_t(lowDelta)
                                               : int64_t(lowDelta) - 0x100000000LL;
      full = now + delta;
    }
    OnFrame(full, data);
  }

  static void OnFrame(int64_t frameTimeNanos, void* data)
  {
    auto* t = static_cast<VsyncTracker*>(data);
    const int64_t last = t->m_last;
    if (last)
    {
      // the period: median of the recent intervals (a skipped vsync is an
      // outlier; after a display mode change, e.g. the Dune AFR switching
      // to 24 Hz behind Android's back, the new period wins in 16 vsyncs)
      const int64_t d = frameTimeNanos - last;
      const int64_t p = t->m_period;
      if (d > 4000000 && d < 100000000)
      {
        t->m_deltas[t->m_nDeltas++ % 32] = d;
        const int n = std::min(t->m_nDeltas, 32);
        if (n >= 8)
        {
          int64_t s[32];
          std::copy(t->m_deltas, t->m_deltas + n, s);
          std::nth_element(s, s + n / 2, s + n);
          const int64_t np = s[n / 2];
          if (p <= 0 || std::llabs(np - p) > 100000)
            LOGI("display vsync period %.3f ms", np / 1e6);
          t->m_period = np;
        }
      }
    }
    t->m_last = frameTimeNanos;
    t->Post();
  }
};
} // namespace

// ---------------------------------------------------------------------------

struct dvfp
{
  ~dvfp() { Stop(); }

  bool Start(int width, int height);
  void Stop();

  int Filter(const uint8_t* in, size_t size, const uint8_t** out, size_t* outSize);
  void Commit(int64_t pts);
  void Drop();
  void Flush();
  void GetStats(dvfp_stats& s) const;
  int64_t LeadNs() const;
  void SetDemo(int on) { m_demo = on ? 1 : 0; }

  int mode{DVFP_MODE_CONVERT};

private:
  struct ElPacket
  {
    int64_t pts;
    std::vector<uint8_t> data; // Annex-B
    std::shared_ptr<felgpu_meta> meta; // the RPU travels with the EL picture
    bool flush;
    bool gap{false}; // the access unit of pts carried no EL
  };
  struct ElFrame
  {
    int buf{-1}; // EL pool buffer
    std::shared_ptr<felgpu_meta> meta;
  };

  bool StartElDecoder();
  void ElThread();
  void GpuThread(std::promise<bool> ready);
  // SC2: hands an EL picture (the image of pts) to the kernel, which keeps it
  // until the display is done with it; false: the image is deleted
  bool Sc2Queue(AImage* image, int64_t pts);
  // SC2: deletes the images the display is done with (DVFEL_IOC_SC2_WAIT_DONE)
  void Sc2DoneThread();
  // SC2: deletes all images (after the session ended)
  void Sc2ReleaseAll();
  // takes the EL frame of pts out of m_elFrames; release with ReleaseElFrame
  bool WaitForFrame(int64_t pts, ElFrame& el);
  void ReleaseElFrame(ElFrame& el); // m_lock held
  // the packet pts of a frame by the CRC of its RPU; -1 if not known
  int64_t PtsForRpuCrc(uint32_t crc);
  // the mapping of a frame decoded before the last flush, by its RPU CRC
  bool StaleMeta(uint32_t crc, std::shared_ptr<felgpu_meta>& meta);

  int m_width{0};
  int m_height{0};
  int m_elWidth{0};
  int m_elHeight{0};
  int m_elPoolFd[EL_POOL];
  bool m_elPoolBusy[EL_POOL]{};
  bool m_started{false};
  // SC2: composition by the Dolby Vision core (FEL mode on an S905X4)
  bool m_sc2{false};
  std::set<uint32_t> m_sc2Ids;             // EL pictures the kernel holds (ids), m_lock
  struct HeldImage { AImage* image; int fence; };
  std::map<uint32_t, HeldImage> m_sc2Images; // m_lock, release only after display DONE
  bool m_sc2HoldImages{false};
  std::set<uint32_t> m_sc2EpochIds;        // held pictures queued since the EL decoder flush, m_lock
  std::atomic<int> m_sc2Held{0};           // their number
  int m_sc2MaxImages{SC2_EL_IMAGES};       // the reader's images
  int m_sc2Buffers{SC2_EL_BUFFERS};        // pictures in the decoder + with the kernel
  std::thread m_sc2DoneThread;
  uint64_t m_sc2QueueErrors{0};
  uint64_t m_commits{0}, m_lastFlushCommit{0}; // decoder input thread only
  std::atomic<bool> m_stop{false};
  std::atomic<int> m_leadUs{0}; // the kernel holds the frames back by this (DVFEL_IOC_SET_LEAD)
  std::atomic<int> m_demo{0};   // dvfp_set_demo (debug.dvfp.demo 1 turns it on too)

  // the EL decoder, set up by Start
  NdkImageApi m_api{};
  AImageReader* m_reader{nullptr};
  AMediaCodec* m_elCodec{nullptr};
  int m_elDev{-1};

  // the filtered access unit and what belongs to it until commit/drop
  std::vector<uint8_t> m_out;
  std::vector<uint8_t> m_pendingRpu;
  std::vector<uint8_t> m_pendingEl;
  bool m_pendingCrcValid{false};
  uint32_t m_pendingCrc{0};
  std::shared_ptr<felgpu_meta> m_lastMeta;
  int m_convertErrors{0};
  // the last RPU (original, as sent to the decoder, its CRC): pictures
  // without one get it again
  std::vector<uint8_t> m_lastRpuIn;
  std::vector<uint8_t> m_lastRpuOut;
  bool m_lastCrcValid{false};
  uint32_t m_lastCrc{0};
  bool m_elSeen{false}; // an access unit with EL was committed

  mutable std::mutex m_lock;
  std::condition_variable m_elCond;    // EL input queue
  std::condition_variable m_readyCond; // new EL frame
  std::deque<ElPacket> m_elQueue;
  std::map<int64_t, ElFrame> m_elFrames; // by display pts
  std::map<int64_t, uint32_t> m_crcByPts; // RPU CRC of the packets given to the decoder
  std::map<int64_t, std::shared_ptr<felgpu_meta>> m_metaByPts; // RPU mapping by packet pts
  // the RPU mappings by CRC of the packets not shown before the last flush:
  // frames decoded before it may still reach the display (the decoder's
  // output queue), they get their mapping without the residual
  std::map<uint32_t, std::shared_ptr<felgpu_meta>> m_staleMeta;
  int64_t m_lastJobPts{INT64_MIN}; // last frame the kernel asked for
  // frames shown before the first decodable EL picture after a start or seek
  // (skipped leading pictures) get no EL: no waiting for them
  int64_t m_noElBefore{INT64_MIN};
  // frames whose EL is missing or not decodable (an access unit without EL,
  // the pictures after it until the next EL IRAP): no waiting for them
  std::set<int64_t> m_noElPts;
  int m_noPairLogged{0}; // since start or the last flush
  int m_noCrcLogged{0};

  std::thread m_elThread;
  std::thread m_gpuThread;

  // statistics
  std::atomic<uint64_t> m_composed{0};
  std::atomic<uint64_t> m_passthrough{0};
  std::atomic<uint64_t> m_elDecoded{0};
  std::atomic<uint64_t> m_noElSkipped{0}; // passed through without waiting
  std::atomic<uint64_t> m_mappedOnly{0};  // without EL, composed with the mapping only
  std::atomic<uint64_t> m_noCrc{0};
  std::atomic<uint64_t> m_stale{0}; // decoded before a flush, shown after it
  uint64_t m_rpuRepeated{0};        // access units without an RPU (filter)
  std::atomic<uint64_t> m_elGaps{0}; // access units without EL in a stream with EL
  // hardware EL decoder: pictures in and out, pts of the last output
  std::atomic<uint64_t> m_elInputs{0};
  std::atomic<uint64_t> m_elOutputs{0};
  std::atomic<int64_t> m_elLastOut{0};
  std::atomic<int> m_elInFlight{0}; // pictures in the EL decoder that will come out
  std::atomic<uint64_t> m_elOverflow{0}; // outputs dropped with the pool full
  double m_gpuMsSum{0};                  // GPU thread
  float m_gpuMsMax{0};
  std::vector<float> m_gpuMs; // per composed frame, GPU thread until Stop
};

// ---------------------------------------------------------------------------
// filter

int dvfp::Filter(const uint8_t* in, size_t size, const uint8_t** out, size_t* outSize)
{
  // what an earlier access unit left uncommitted belongs to no packet
  Drop();
  m_out.clear();
  m_out.reserve(size + 64 + m_lastRpuOut.size());
  bool ok = true;
  bool vcl = false, hasRpu = false;
  // where the end of sequence / end of bitstream NAL units at the end begin
  size_t tail = SIZE_MAX;

  ForEachNal(in, size, [&](const uint8_t* nal, size_t n) {
    const int type = n >= 2 ? NalType(nal) : -1;
    if (type == 36 || type == 37)
    {
      if (tail == SIZE_MAX)
        tail = m_out.size();
    }
    else if (type != 63)
    {
      tail = SIZE_MAX;
    }
    if (type >= 0 && type < 32)
      vcl = true;
    if (type == 63)
    {
      // a NAL 63 carries an EL NAL unit behind its own 2-byte header; it never
      // goes to the base layer decoder
      if (mode == DVFP_MODE_FEL && n > 4)
      {
        m_pendingEl.insert(m_pendingEl.end(), START_CODE, START_CODE + 4);
        m_pendingEl.insert(m_pendingEl.end(), nal + 2, nal + n);
      }
      return;
    }
    if (type == 62)
    {
      // the original RPU, before the conversion
      m_pendingRpu.assign(nal, nal + n);
      const uint8_t* sent = nal;
      size_t sentSize = n;
      const DoviData* converted = nullptr;
      DoviRpuOpaque* rpu = dovi_parse_unspec62_nalu(nal, n);
      const DoviRpuDataHeader* hdr = rpu ? dovi_rpu_get_header(rpu) : nullptr;
      // SC2: the Dolby Vision core composes with the original profile 7 RPU
      if (hdr && hdr->guessed_profile == 7 && !m_sc2)
      {
        // profile 8.1 with a no-op mapping (libdovi mode 2)
        if (dovi_convert_rpu_with_mode(rpu, 2) >= 0)
          converted = dovi_write_unspec62_nalu(rpu);
        if (converted && converted->data && converted->len)
        {
          sent = converted->data;
          sentSize = converted->len;
        }
        else
        {
          ok = false;
        }
      }
      else if (!hdr)
      {
        ok = false;
      }
      if (hdr)
        dovi_rpu_free_header(hdr);
      if (rpu)
        dovi_rpu_free(rpu);
      m_out.insert(m_out.end(), START_CODE, START_CODE + 4);
      m_out.insert(m_out.end(), sent, sent + sentSize);
      m_pendingCrcValid = RpuTailCrc(sent, sentSize, m_pendingCrc);
      hasRpu = true;
      m_lastRpuIn = m_pendingRpu;
      m_lastRpuOut.assign(sent, sent + sentSize);
      m_lastCrcValid = m_pendingCrcValid;
      m_lastCrc = m_pendingCrc;
      if (converted)
        dovi_data_free(converted);
      return;
    }
    m_out.insert(m_out.end(), START_CODE, START_CODE + 4);
    m_out.insert(m_out.end(), nal, nal + n);
  });

  // a picture without an RPU (seen in files muxed from a dual layer source
  // whose EL had another picture order: about half the access units without
  // RPU and EL) would go to the display as the base layer as it is, a flash
  // between the composed frames; it gets the last RPU instead, after its
  // slices, as the decoder of a Dolby Vision display keeps the last metadata
  if (vcl && !hasRpu && !m_lastRpuOut.empty())
  {
    const size_t at = tail == SIZE_MAX ? m_out.size() : tail;
    m_out.insert(m_out.begin() + at, m_lastRpuOut.begin(), m_lastRpuOut.end());
    m_out.insert(m_out.begin() + at, START_CODE, START_CODE + 4);
    m_pendingRpu = m_lastRpuIn;
    m_pendingCrcValid = m_lastCrcValid;
    m_pendingCrc = m_lastCrc;
    if (m_rpuRepeated++ < 5)
      LOGI("access unit without an RPU: the last one repeated");
  }

  *out = m_out.data();
  *outSize = m_out.size();
  // the RPU goes as it is then; the EL is removed all the same
  if (!ok && m_convertErrors++ < 10)
    LOGW("RPU not converted to profile 8.1, passed unchanged");
  return 0;
}

void dvfp::Drop()
{
  m_pendingRpu.clear();
  m_pendingEl.clear();
  m_pendingCrcValid = false;
}

void dvfp::Commit(int64_t pts)
{
  if (!m_started || pts < 0)
  {
    Drop();
    return;
  }

  ++m_commits;
  std::shared_ptr<felgpu_meta> meta;
  if (!m_pendingRpu.empty())
    meta = ParseRpu(m_pendingRpu.data(), m_pendingRpu.size(), m_lastMeta);
  if (meta)
    m_lastMeta = meta;

  if (m_sc2 && m_pendingCrcValid)
  {
    // the kernel names the frames the display shows by these
    dvfel_sc2_hint hint{};
    hint.pts_us = pts;
    hint.rpu_crc = m_pendingCrc;
    ioctl(m_elDev, DVFEL_IOC_SC2_BL_HINT, &hint);
  }
  std::lock_guard<std::mutex> lock(m_lock);
  if (m_pendingCrcValid)
  {
    m_crcByPts[pts] = m_pendingCrc;
    while (m_crcByPts.size() > MAX_CRC)
      m_crcByPts.erase(m_crcByPts.begin());
  }
  if (meta)
  {
    // for frames that get no EL (composed with the mapping only)
    m_metaByPts[pts] = meta;
    while (m_metaByPts.size() > MAX_META)
      m_metaByPts.erase(m_metaByPts.begin());
  }
  if (!m_pendingEl.empty() && m_elQueue.size() < MAX_EL_QUEUE)
  {
    // the RPU closes the EL access unit as in a demuxed EL stream: without a
    // NAL unit after the last slice, the Amlogic HEVC decoder's back end times
    // out (200 ms) on some pictures, and the pictures referencing them are lost
    if (!m_pendingRpu.empty())
    {
      m_pendingEl.insert(m_pendingEl.end(), START_CODE, START_CODE + 4);
      m_pendingEl.insert(m_pendingEl.end(), m_pendingRpu.begin(), m_pendingRpu.end());
    }
    m_elQueue.push_back({pts, std::move(m_pendingEl), meta, false});
    m_elSeen = true;
    m_elCond.notify_all();
  }
  else if ((m_elSeen || !m_pendingEl.empty()) && m_elQueue.size() < 2 * MAX_EL_QUEUE)
  {
    // an access unit without EL (or no room for it): the EL pictures after it
    // may reference its picture, the EL decoder waits for its next IRAP
    m_elQueue.push_back({pts, {}, nullptr, false, true});
    m_elGaps++;
    m_elCond.notify_all();
  }
  m_pendingEl.clear();
  m_pendingRpu.clear();
  m_pendingCrcValid = false;
}

void dvfp::Flush()
{
  // the access unit filtered last stays: it may be queued after the flush
  // (VLC restarts its decoder between the filter and the queueing)
  if (!m_started)
    return;
  if (m_sc2)
  {
    // Gazelle: VLC has already released BL surfaces ahead of their display
    // time. Keep their EL/hints alive briefly before flushing this side.
    // Diagnostic opt-in, bounded and stop-interruptible, never frees owned EL.
    char board[PROP_VALUE_MAX] = {};
    __system_property_get("ro.board.platform", board);
    const int drainMs = !strcmp(board, "gazelle") && m_elDecoded > 0 && m_commits != m_lastFlushCommit
        ? std::clamp(PropInt("debug.dvfp.seek_drain_ms", 0), 0, 250) : 0;
    m_lastFlushCommit = m_commits;
    if (drainMs) {
      LOGI("Gazelle seek: draining scheduled BL/EL for %d ms", drainMs);
      const auto until = std::chrono::steady_clock::now() + std::chrono::milliseconds(drainMs);
      while (!m_stop && std::chrono::steady_clock::now() < until)
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    }
    uint32_t what = DVFEL_SC2_FLUSH_EL | DVFEL_SC2_FLUSH_HINTS;
    ioctl(m_elDev, DVFEL_IOC_SC2_FLUSH, &what);
  }
  std::lock_guard<std::mutex> lock(m_lock);
  m_elQueue.clear();
  m_elQueue.push_back({0, {}, nullptr, true});
  m_staleMeta.clear();
  for (const auto& c : m_crcByPts)
  {
    auto m = m_metaByPts.find(c.first);
    if (m != m_metaByPts.end() && m->second)
      m_staleMeta[c.second] = m->second;
  }
  m_crcByPts.clear();
  m_metaByPts.clear();
  m_noElPts.clear();
  m_lastJobPts = INT64_MIN;
  m_noPairLogged = 0;
  m_noCrcLogged = 0;
  m_elCond.notify_all();
}

int64_t dvfp::PtsForRpuCrc(uint32_t crc)
{
  std::lock_guard<std::mutex> lock(m_lock);
  // frames come in display order, the map is in pts order: the frame is
  // normally the first pending packet; earlier ones it skips were dropped
  size_t n = 0;
  for (auto it = m_crcByPts.begin(); it != m_crcByPts.end() && n < CRC_SEARCH; ++it, n++)
  {
    if (it->second != crc)
      continue;
    const int64_t pts = it->first;
    m_crcByPts.erase(m_crcByPts.begin(), std::next(it));
    return pts;
  }
  return -1;
}

bool dvfp::StaleMeta(uint32_t crc, std::shared_ptr<felgpu_meta>& meta)
{
  std::lock_guard<std::mutex> lock(m_lock);
  auto it = m_staleMeta.find(crc);
  if (it == m_staleMeta.end())
    return false;
  meta = it->second;
  return true;
}

// ---------------------------------------------------------------------------
// start / stop

bool dvfp::Start(int width, int height)
{
  if (m_started)
    return true;
  if (width <= 0 || height <= 0 || (width & 3) || (height & 3))
    return false;

  m_width = width;
  m_height = height;
  m_elWidth = width / 2;
  m_elHeight = height / 2;
  for (int i = 0; i < EL_POOL; i++)
  {
    m_elPoolFd[i] = -1;
    m_elPoolBusy[i] = false;
  }
  m_stop = false;
  m_noElBefore = INT64_MIN;
  m_noPairLogged = 0;
  m_noCrcLogged = 0;

  if (UsesHardwareComposer())
  {
    // the Dolby Vision core composes: the EL decoder only, its pictures to the kernel
    m_sc2 = true;
    m_sc2Held = 0;
    m_sc2HoldImages = IsGazelleBoard() && PropInt("debug.dvfp.hold_images",0);
    m_sc2MaxImages = m_sc2HoldImages ? std::clamp(PropInt("debug.dvfp.hold_max",2),2,3) : std::clamp(PropInt(PROP_SC2_IMAGES, 1), 1, 2);
    m_sc2Buffers = std::clamp(PropInt(PROP_SC2_BUFFERS, 6), 4, 7);
    if (!StartElDecoder())
    {
      m_sc2 = false;
      return false;
    }
    if (ioctl(m_elDev, DVFEL_IOC_SC2_START) < 0)
    {
      LOGE("DVFEL_IOC_SC2_START failed: %s", strerror(errno));
      AMediaCodec_stop(m_elCodec);
      AMediaCodec_delete(m_elCodec);
      AImageReader_delete(m_reader);
      close(m_elDev);
      m_elCodec = nullptr;
      m_reader = nullptr;
      m_elDev = -1;
      m_sc2 = false;
      return false;
    }
    m_elThread = std::thread(&dvfp::ElThread, this);
    m_sc2DoneThread = std::thread(&dvfp::Sc2DoneThread, this);
    m_started = true;
    // the player's sign that a FEL composition runs (removed by Stop); no demo mode here
    const int sfd = open(AppFile("dvfp_fel").c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (sfd >= 0)
    {
      const char state[] = "demo=0\nsc2=1\n";
      (void)!write(sfd, state, sizeof(state) - 1);
      close(sfd);
    }
    LOGI("FEL composition by the Dolby Vision core started for %dx%d (SC2)", width, height);
    return true;
  }

  std::promise<bool> ready;
  std::future<bool> ok = ready.get_future();
  m_gpuThread = std::thread(&dvfp::GpuThread, this, std::move(ready));
  if (!ok.get())
  {
    m_gpuThread.join();
    for (int i = 0; i < EL_POOL; i++)
    {
      if (m_elPoolFd[i] >= 0)
        close(m_elPoolFd[i]);
      m_elPoolFd[i] = -1;
    }
    LOGE("GPU compositor failed to start, no FEL composition");
    return false;
  }
  if (!StartElDecoder())
  {
    m_stop = true;
    m_gpuThread.join();
    for (int i = 0; i < EL_POOL; i++)
    {
      if (m_elPoolFd[i] >= 0)
        close(m_elPoolFd[i]);
      m_elPoolFd[i] = -1;
    }
    return false;
  }
  m_elThread = std::thread(&dvfp::ElThread, this);
  m_started = true;
  // the menu's switch is for one playback: every file starts with the demo
  // mode as dvfp_set_demo (default: off)
  unlink(AppFile("dvfp_demo").c_str());
  // (unless the GPU thread has written its state already)
  const int sfd = open(AppFile("dvfp_fel").c_str(), O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, 0600);
  if (sfd >= 0)
  {
    const char state[] = "demo=0\n";
    (void)!write(sfd, state, sizeof(state) - 1);
    close(sfd);
  }
  LOGI("FEL composition started for %dx%d", width, height);
  return true;
}

bool dvfp::StartElDecoder()
{
  m_elDev = open(DevicePath().c_str(), O_RDWR | O_CLOEXEC);
  ANativeWindow* window = nullptr;
  bool ok = m_api.Load() && m_elDev >= 0;
  if (ok)
    ok = m_api.newWithUsage(m_elWidth, m_elHeight, AIMAGE_FORMAT_PRIVATE_,
                            AHB_USAGE_GPU_SAMPLED_IMAGE | (m_sc2 ? (1ULL << 11) : 0),
                            m_sc2 ? m_sc2MaxImages : HW_EL_IMAGES, &m_reader) == AMEDIA_OK &&
         AImageReader_getWindow(m_reader, &window) == AMEDIA_OK;
  if (ok)
  {
    m_elCodec = AMediaCodec_createCodecByName(HardwareElCodec());
    AMediaFormat* fmt = AMediaFormat_new();
    AMediaFormat_setString(fmt, AMEDIAFORMAT_KEY_MIME, strstr(HardwareElCodec(), "dolby-vision") ? "video/dolby-vision" : "video/hevc");
    AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_WIDTH, m_elWidth);
    AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_HEIGHT, m_elHeight);
    ok = m_elCodec && AMediaCodec_configure(m_elCodec, fmt, window, nullptr, 0) == AMEDIA_OK &&
         AMediaCodec_start(m_elCodec) == AMEDIA_OK;
    AMediaFormat_delete(fmt);
  }
  if (!ok)
  {
    LOGE("cannot start the hardware EL decoder %s", HardwareElCodec());
    if (m_elCodec)
      AMediaCodec_delete(m_elCodec);
    if (m_reader)
      AImageReader_delete(m_reader);
    if (m_elDev >= 0)
      close(m_elDev);
    m_elCodec = nullptr;
    m_reader = nullptr;
    m_elDev = -1;
    return false;
  }
  LOGI("hardware EL decoder %s started (%dx%d)", HardwareElCodec(), m_elWidth, m_elHeight);
  return true;
}

int64_t dvfp::LeadNs() const
{
  // whole vsyncs, as the kernel rounds it
  const int64_t lead = int64_t(m_leadUs) * 1000, period = VsyncTracker::Get().Period();
  if (lead <= 0 || period <= 0)
    return 0;
  return (lead + period / 2) / period * period;
}

void dvfp::Stop()
{
  if (!m_started && !m_gpuThread.joinable())
    return;

  if (m_started)
    unlink(AppFile("dvfp_fel").c_str());
  m_stop = true;
  m_elCond.notify_all();
  m_readyCond.notify_all();
  if (m_elThread.joinable())
    m_elThread.join();
  if (m_gpuThread.joinable())
    m_gpuThread.join();
  if (m_sc2DoneThread.joinable())
    m_sc2DoneThread.join();
  if (m_sc2)
  {
    // the queued pictures go back; the one on display stays with the kernel
    // (its dma-buf reference) until the driver lets it go
    if (m_elDev >= 0)
      ioctl(m_elDev, DVFEL_IOC_SC2_STOP);
    Sc2ReleaseAll();
  }

  if (m_elCodec)
  {
    AMediaCodec_stop(m_elCodec);
    AMediaCodec_delete(m_elCodec);
    m_elCodec = nullptr;
  }
  if (m_reader)
  {
    AImageReader_delete(m_reader);
    m_reader = nullptr;
  }
  if (m_elDev >= 0)
  {
    close(m_elDev);
    m_elDev = -1;
  }

  if (m_started)
  {
    const uint64_t n = m_composed;
    std::vector<float> t = m_gpuMs;
    std::sort(t.begin(), t.end());
    auto pct = [&t](double p) { return t.empty() ? 0.0f : t[static_cast<size_t>(p * (t.size() - 1))]; };
    const auto slow = std::count_if(t.begin(), t.end(), [](float ms) { return ms > 41.7f; });
    LOGI("stopped, %llu frames composed (gpu avg %.1f ms, median %.1f, p95 %.1f, max %.1f, %ld over "
         "41.7 ms), %llu shown without EL (%llu without waiting, %llu with the mapping only), %llu "
         "EL frames decoded, %llu frames without a matching RPU CRC, %llu from before a flush; "
         "%llu access units without an RPU (the last repeated), %llu without EL",
         (unsigned long long)n, n ? m_gpuMsSum / n : 0.0, pct(0.5), pct(0.95), pct(1.0), (long)slow,
         (unsigned long long)m_passthrough.load(), (unsigned long long)m_noElSkipped.load(),
         (unsigned long long)m_mappedOnly.load(), (unsigned long long)m_elDecoded.load(),
         (unsigned long long)m_noCrc.load(), (unsigned long long)m_stale.load(),
         (unsigned long long)m_rpuRepeated, (unsigned long long)m_elGaps.load());
  }

  std::lock_guard<std::mutex> lock(m_lock);
  for (auto& f : m_elFrames)
    ReleaseElFrame(f.second);
  m_elFrames.clear();
  m_elQueue.clear();
  m_crcByPts.clear();
  m_metaByPts.clear();
  m_staleMeta.clear();
  m_noElPts.clear();
  // the EL pool, used by both threads
  for (int i = 0; i < EL_POOL; i++)
  {
    if (m_elPoolFd[i] >= 0)
      close(m_elPoolFd[i]);
    m_elPoolFd[i] = -1;
  }
  m_started = false;
}

void dvfp::ReleaseElFrame(ElFrame& el)
{
  if (el.buf >= 0 && el.buf < EL_POOL)
  {
    m_elPoolBusy[el.buf] = false;
    m_elCond.notify_all();
  }
  el.buf = -1;
  el.meta.reset();
}

bool dvfp::Sc2Queue(AImage* image, int64_t pts)
{
  uint32_t crc = 0;
  {
    std::lock_guard<std::mutex> lock(m_lock);
    auto c = m_crcByPts.find(pts);
    if (c != m_crcByPts.end())
      crc = c->second;
  }
  AHardwareBuffer* hb = nullptr;
  const NdkImageApi::NativeHandle* h = nullptr;
  if (m_api.getHardwareBuffer(image, &hb) == AMEDIA_OK && hb)
    h = m_api.getNativeHandle(hb);
  dvfel_sc2_el el{};
  el.fence_fd = -1;
  bool queued = false;
  if (h && h->numFds > 0)
  {
    el.fd = h->data[0];
    el.rpu_crc = crc;
    el.pts_us = pts;
    // Register ownership before the done consumer can erase this ID. A fast
    // drop/flush can otherwise complete between QUEUE and insertion.
    std::lock_guard<std::mutex> lock(m_lock);
    queued = ioctl(m_elDev, DVFEL_IOC_SC2_EL_QUEUE, &el) == 0;
    if (queued) {
      m_sc2Ids.insert(el.id);
      m_sc2EpochIds.insert(el.id);
      m_sc2Held = static_cast<int>(m_sc2Ids.size());
      if (m_sc2HoldImages)
        m_sc2Images.emplace(el.id,HeldImage{image,el.fence_fd});
    }
    if (!queued && m_sc2QueueErrors++ < 10)
      LOGE("DVFEL_IOC_SC2_EL_QUEUE failed for %lld us: %s", (long long)pts, strerror(errno));
  }
  else if (m_sc2QueueErrors++ < 10)
  {
    LOGI("EL picture %lld us not queued (no buffer)", (long long)pts);
  }
  // the image goes back at once; the decoder waits on the fence before it
  // decodes into the buffer again (the display shows it until then)
  if (!queued || !m_sc2HoldImages)
    AImage_deleteAsync(image, queued ? el.fence_fd : -1);
  if (!queued)
    return false;
  return true;
}

void dvfp::Sc2DoneThread()
{
  pthread_setname_np(pthread_self(), "dvfp-sc2");
  while (!m_stop)
  {
    dvfel_sc2_done done{};
    done.timeout_ms = 100;
    if (ioctl(m_elDev, DVFEL_IOC_SC2_WAIT_DONE, &done) < 0)
    {
      if (errno != EINTR)
        usleep(10000);
      continue;
    }
    std::lock_guard<std::mutex> lock(m_lock);
    for (uint32_t i = 0; i < done.count && i < DVFEL_SC2_MAX_DONE; i++) {
      m_sc2Ids.erase(done.id[i]);
      m_sc2EpochIds.erase(done.id[i]);
      auto image=m_sc2Images.find(done.id[i]);
      if (image!=m_sc2Images.end()) {
        AImage_deleteAsync(image->second.image,image->second.fence);
        m_sc2Images.erase(image);
      }
    }
    m_sc2Held = static_cast<int>(m_sc2Ids.size());
    if (done.count)
      m_elCond.notify_all();
  }
}

void dvfp::Sc2ReleaseAll()
{
  std::lock_guard<std::mutex> lock(m_lock);
  for (auto& image:m_sc2Images)
    AImage_deleteAsync(image.second.image,image.second.fence);
  m_sc2Images.clear();
  m_sc2Ids.clear();
  m_sc2EpochIds.clear();
  m_sc2Held = 0;
}

void dvfp::GetStats(dvfp_stats& s) const
{
  s = {};
  s.composed = m_composed;
  s.mapped_only = m_mappedOnly;
  s.passthrough = m_passthrough;
  s.no_el_skipped = m_noElSkipped;
  s.el_decoded = m_elDecoded;
  s.el_inputs = m_elInputs;
  s.el_outputs = m_elOutputs;
  s.el_overflow = m_elOverflow;
  s.no_crc_match = m_noCrc;
  s.stale = m_stale;
  std::lock_guard<std::mutex> lock(m_lock); // m_gpuMsSum: GPU thread
  s.gpu_ms_avg = s.composed ? m_gpuMsSum / s.composed : 0.0;
  s.gpu_ms_max = m_gpuMsMax;
}

// ---------------------------------------------------------------------------
// EL decoder thread

void dvfp::ElThread()
{
  pthread_setname_np(pthread_self(), "dvfp-el");
  SetThreadNice("EL");
  AMediaCodec* codec = m_elCodec;
  AImageReader* reader = m_reader;
  const int dev = m_elDev;

  // display order pairing: the EL decoder outputs in display order; the
  // earliest pending packet pts is the output picture's slot. IRAP handling:
  // pictures before the first IRAP after a start or flush are not decodable.
  std::multiset<int64_t> slots;
  std::map<int64_t, std::shared_ptr<felgpu_meta>> metas;
  int skipped = 0;
  bool reordered = false;
  int64_t lastLabel = INT64_MIN;
  bool needIrap = true, skipRasl = false;
  // needIrap because of an access unit without EL (not a start or flush): the
  // frames before the IRAP whose EL pictures were decoded keep them
  bool gapWait = false;
  int gapsLogged = 0;
  // the EL's last parameter sets (VPS / SPS / PPS, Annex B, by type): an EL
  // track may carry them at its start only, and the decoder forgets them at a
  // flush - after a seek it then decoded nothing (every frame fell back to
  // the base layer, late). They go in front of the first IRAP after a flush
  // that does not carry its own.
  std::map<int, std::vector<uint8_t>> params;
  bool resendParams = false;
  int paramsResent = 0;
  uint64_t importErrors = 0, importUs = 0, imports = 0;
  uint64_t staleImages = 0, lateImages = 0;
  int truncated = 0;
  m_elInputs = 0;
  m_elOutputs = 0;
  // the pts of the pictures in the decoder: each needs a pool buffer when it
  // comes out
  std::multiset<int64_t> inFlight;
  m_elInFlight = 0;
  auto outOfDecoder = [&](int64_t label) {
    auto it = inFlight.find(label);
    if (it != inFlight.end())
      inFlight.erase(it);
    // output is in display order: pictures far behind it are lost
    while (!inFlight.empty() && *inFlight.begin() < label - EL_LOST_US)
      inFlight.erase(inFlight.begin());
    m_elInFlight = static_cast<int>(inFlight.size());
  };

  // a free EL pool buffer (m_lock held): frames the display has passed may go
  auto freeBuf = [this]() -> int {
    while (true)
    {
      for (int i = 0; i < EL_POOL; i++)
        if (!m_elPoolBusy[i])
          return i;
      if (m_lastJobPts == INT64_MIN || m_elFrames.empty() ||
          m_elFrames.begin()->first >= m_lastJobPts - PTS_TOLERANCE_US)
        return -1;
      ReleaseElFrame(m_elFrames.begin()->second);
      m_elFrames.erase(m_elFrames.begin());
    }
  };

  // takes the decoded pictures out of the decoder, always: an output the
  // decoder cannot hand over stalls its back end, which times out (200 ms)
  // and marks the picture and everything referencing it as broken until the
  // next IRAP. With the pool full (should not happen with the pacing), the
  // new picture (the farthest ahead) is dropped instead.
  auto drain = [&](int64_t timeoutUs) -> bool {
    while (!m_stop)
    {
      int buf;
      {
        std::lock_guard<std::mutex> lock(m_lock);
        if (m_sc2)
        {
          // Keep the displayed image out of the decoder's available queue,
          // instead of returning an unsignaled fence that stalls reuse.
          if (m_sc2HoldImages && m_sc2Images.size() >= static_cast<size_t>(m_sc2MaxImages))
            return true;
          // no pool: the image goes to the kernel and back at once
          buf = 0;
        }
        else
        {
          buf = freeBuf();
          if (buf >= 0)
            m_elPoolBusy[buf] = true;
        }
      }
      auto unbusy = [&] {
        if (buf < 0 || m_sc2)
          return;
        std::lock_guard<std::mutex> lock(m_lock);
        m_elPoolBusy[buf] = false;
      };

      AMediaCodecBufferInfo info;
      const ssize_t o = AMediaCodec_dequeueOutputBuffer(codec, &info, timeoutUs);
      timeoutUs = 0;
      if (o < 0 || (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM))
      {
        if (o >= 0)
          AMediaCodec_releaseOutputBuffer(codec, o, false);
        unbusy();
        if (o == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED || o == AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED)
          continue;
        if (o < 0 && o != AMEDIACODEC_INFO_TRY_AGAIN_LATER)
        {
          LOGE("fatal EL dequeue output error: %zd", o);
          m_stop = true;
        }
        return true;
      }
      AMediaCodec_releaseOutputBuffer(codec, o, buf >= 0);
      m_elOutputs++;
      m_elLastOut = info.presentationTimeUs;
      outOfDecoder(info.presentationTimeUs);
      if (buf < 0 && m_elOverflow++ < 10)
      {
        std::lock_guard<std::mutex> lock(m_lock);
        LOGI("EL pool full, picture %lld us dropped (EL frames %zu, in the decoder %d, last job %lld)",
             (long long)info.presentationTimeUs, m_elFrames.size(), m_elInFlight.load(),
             (long long)m_lastJobPts);
      }

      // the picture as an image of the reader (its timestamp is the buffer's
      // presentation time): older images arrived late for an earlier output,
      // whose picture is lost; they must not be taken for this one
      AImage* image = nullptr;
      for (int i = 0; buf >= 0 && i < HW_EL_IMAGE_WAIT_MS && !m_stop && !image; i++)
      {
        if (AImageReader_acquireNextImage(reader, &image) != AMEDIA_OK)
        {
          image = nullptr;
          usleep(1000);
          continue;
        }
        int64_t ts = 0;
        AImage_getTimestamp(image, &ts);
        if (ts / 1000 != info.presentationTimeUs)
        {
          if (staleImages++ < 10)
            LOGI("EL image %lld us is not the output %lld us, dropped", (long long)(ts / 1000),
                 (long long)info.presentationTimeUs);
          AImage_delete(image);
          image = nullptr;
        }
      }
      if (buf >= 0 && !image && lateImages++ < 10)
        LOGI("no EL image for output %lld us", (long long)info.presentationTimeUs);
      bool imported = false;
      // SC2: the image itself goes to the kernel once its frame is known
      AImage* keep = nullptr;
      if (image && m_sc2)
      {
        keep = image;
        image = nullptr;
      }
      if (image)
      {
        AHardwareBuffer* hb = nullptr;
        const NdkImageApi::NativeHandle* h = nullptr;
        if (m_api.getHardwareBuffer(image, &hb) == AMEDIA_OK && hb)
          h = m_api.getNativeHandle(hb);
        if (h && h->numFds > 0)
        {
          dvfel_el_import im{};
          im.src_fd = h->data[0];
          im.dst_fd = m_elPoolFd[buf];
          im.width = m_elWidth;
          im.height = m_elHeight;
          imported = ioctl(dev, DVFEL_IOC_EL_IMPORT, &im) == 0;
          if (imported)
          {
            imports++;
            importUs += im.us;
          }
          else if (importErrors++ < 5)
            LOGE("DVFEL_IOC_EL_IMPORT failed: %s", strerror(errno));
        }
        AImage_delete(image);
      }

      // output is in display order: the earliest pending pts is this picture's slot
      for (; skipped > 0 && !slots.empty(); skipped--)
        slots.erase(slots.begin());
      if (slots.empty())
      {
        unbusy();
        if (keep)
          AImage_delete(keep);
        continue;
      }
      const int64_t slot = *slots.begin();
      slots.erase(slots.begin());
      // the pts of the packet that carried the picture
      const int64_t label = info.presentationTimeUs;
      std::unique_lock<std::mutex> lock(m_lock);
      if (!reordered && lastLabel != INT64_MIN && label < lastLabel)
      {
        reordered = true;
        LOGI("the EL picture order differs from the base layer, pairing by display order");
        for (auto it = m_elFrames.lower_bound(slot); it != m_elFrames.end();)
        {
          ReleaseElFrame(it->second);
          it = m_elFrames.erase(it);
        }
      }
      lastLabel = label;
      const int64_t pts = reordered ? slot : label;
      std::shared_ptr<felgpu_meta> meta;
      // the RPU travels with the EL picture
      auto m = metas.find(label);
      if (m != metas.end())
      {
        meta = std::move(m->second);
        metas.erase(m);
      }
      if (m_sc2)
      {
        // the frame of display time pts takes it (by the CRC of its RPU)
        lock.unlock();
        if (keep && Sc2Queue(keep, pts))
          m_elDecoded++;
        continue;
      }
      if (!imported || !meta)
      {
        if (buf >= 0)
          m_elPoolBusy[buf] = false;
        continue;
      }
      auto& entry = m_elFrames[pts];
      ReleaseElFrame(entry);
      entry.buf = buf;
      entry.meta = std::move(meta);
      m_elDecoded++;
      m_readyCond.notify_all();
    }
    return true;
  };

  // empties the decoder (a seek, or a stalled decoder): the EL starts again at
  // its next IRAP, the frames before it are shown without EL at once
  auto flushDecoder = [&] {
    AMediaCodec_flush(codec);
    // SC2: the EL pictures queued for frames before the seek go back
    if (m_sc2)
    {
      uint32_t what = DVFEL_SC2_FLUSH_EL;
      ioctl(dev, DVFEL_IOC_SC2_FLUSH, &what);
    }
    m_elOutputs = m_elInputs.load(); // the pictures in the decoder are gone
    inFlight.clear();
    m_elInFlight = 0;
    // pictures released to the reader before the flush
    AImage* image = nullptr;
    while (AImageReader_acquireNextImage(reader, &image) == AMEDIA_OK)
      AImage_delete(image);
    slots.clear();
    metas.clear();
    skipped = 0;
    lastLabel = INT64_MIN;
    needIrap = true;
    gapWait = false;
    skipRasl = false;
    resendParams = true;
    std::lock_guard<std::mutex> lock(m_lock);
    for (auto& f : m_elFrames)
      ReleaseElFrame(f.second);
    m_elFrames.clear();
    m_noElBefore = INT64_MAX; // until the first IRAP of the EL
    // Old display-owned pictures remain in m_sc2Ids and retain their fences.
    // Only decoder preroll accounting starts a new epoch.
    m_sc2EpochIds.clear();
  };
  int stalls = 0;
  uint64_t idleOuts = 0;
  auto idleFrom = std::chrono::steady_clock::now();
  auto nextPaceTrace = idleFrom;

  while (!m_stop)
  {
    ElPacket in;
    bool have = false;
    {
      std::unique_lock<std::mutex> lock(m_lock);
      // pace decoding by the display: stay at most EL_LEAD_US ahead of the
      // last requested frame; the pool bounds it too
      auto paced = [this] {
        // SC2: the display paces it: the pictures in the decoder and the ones
        // the kernel holds (queued ahead of their frames, on display) need
        // reader images
        if (m_sc2) {
          // An old displayed EL must remain protected, but it must not impose
          // the steady-state feeding limit on a newly flushed decoder.
          if (PropInt("debug.dvfp.seek_preroll", 0) && IsGazelleBoard() &&
              m_sc2EpochIds.empty() && m_sc2Held < 2)
            return m_elInFlight < EL_POOL - m_sc2Held.load();
          // nothing with the kernel (start, seek): the decoder may need more
          // pictures for its first output than the bound (no fence to wait on)
          return m_sc2Held == 0 ? m_elInFlight < EL_POOL
                                : m_sc2Held < SC2_EL_AHEAD && m_sc2Held + m_elInFlight < m_sc2Buffers;
        }
        // the pictures still in the decoder need pool buffers too (before
        // the first frame after a start or flush nothing can be evicted)
        if (static_cast<int>(m_elFrames.size()) + m_elInFlight >= EL_POOL)
          return false;
        return m_lastJobPts == INT64_MIN || m_elFrames.empty() ||
               m_elFrames.rbegin()->first < m_lastJobPts + EL_LEAD_US;
      };
      // flushes and gaps (no picture) are never held back
      auto ready = [&] {
        return !m_elQueue.empty() && (m_elQueue.front().flush || m_elQueue.front().gap || paced());
      };
      if (m_sc2 && PropInt("debug.dvfp.trace_pacing",0) && !m_elQueue.empty() &&
          !ready() && std::chrono::steady_clock::now() >= nextPaceTrace) {
        LOGI("Gazelle pace held=%d epoch=%zu inFlight=%d queued=%zu first=%lld inputs=%llu outputs=%llu",
             m_sc2Held.load(),m_sc2EpochIds.size(),m_elInFlight.load(),m_elQueue.size(),(long long)m_elQueue.front().pts,
             (unsigned long long)m_elInputs.load(),(unsigned long long)m_elOutputs.load());
        nextPaceTrace=std::chrono::steady_clock::now()+std::chrono::milliseconds(100);
      }
      m_elCond.wait_for(lock, std::chrono::milliseconds(5), [&] { return m_stop || ready(); });
      if (m_stop)
        break;
      if (ready())
      {
        in = std::move(m_elQueue.front());
        m_elQueue.pop_front();
        have = true;
      }
    }
    if (!have)
    {
      drain(0);
      // the pool counts the pictures in the decoder: a decoder that keeps a
      // pool's worth and gives nothing out would block the feeding for good
      const uint64_t outs = m_elOutputs.load();
      const auto now = std::chrono::steady_clock::now();
      if (outs != idleOuts || m_elInFlight < EL_POOL)
      {
        idleOuts = outs;
        idleFrom = now;
      }
      else if (now - idleFrom > std::chrono::milliseconds(EL_STALL_MS))
      {
        if (stalls++ < 10)
          LOGW("EL decoder stalled (%d pictures in it, the pool full, no output for %d ms): flushed",
               m_elInFlight.load(), EL_STALL_MS);
        flushDecoder();
        idleFrom = now;
      }
      continue;
    }

    if (in.flush)
    {
      flushDecoder();
      continue;
    }
    if (in.gap)
    {
      // the EL pictures after it until the next IRAP may reference the
      // missing one: decoded, they would be broken (and the Amlogic decoder
      // then stalls), the frames get the mapping only
      if (!needIrap)
      {
        needIrap = true;
        gapWait = true;
        if (gapsLogged++ < 5)
          LOGI("access unit %lld us without EL: the EL waits for its next IRAP", (long long)in.pts);
      }
      std::lock_guard<std::mutex> lock(m_lock);
      m_noElPts.insert(in.pts);
      while (m_noElPts.size() > MAX_META)
        m_noElPts.erase(m_noElPts.begin());
      m_readyCond.notify_all();
      continue;
    }

    int type;
    bool endOfSeq;
    ScanNals(in.data, type, endOfSeq);
    // the access unit's parameter sets (base layer id only) replace the kept ones of their type
    bool hasSps = false;
    {
      std::map<int, std::vector<uint8_t>> found;
      ForEachNal(in.data.data(), in.data.size(), [&](const uint8_t* nal, size_t n) {
        if (n < 2)
          return;
        const int t = NalType(nal);
        const int layer = ((nal[0] & 1) << 5) | (nal[1] >> 3);
        if (layer != 0 || t < 32 || t > 34)
          return;
        if (t == 33)
          hasSps = true;
        static const uint8_t startCode[4] = {0, 0, 0, 1};
        auto& v = found[t];
        v.insert(v.end(), startCode, startCode + 4);
        v.insert(v.end(), nal, nal + n);
      });
      for (auto& f : found)
        params[f.first] = std::move(f.second);
    }
    if (type < 0)
      continue;
    bool decode = true;
    if (type >= 16 && type <= 23) // IRAP: BLA 16-18, IDR 19-20, CRA 21
    {
      if (resendParams && !hasSps && params.count(33))
      {
        std::vector<uint8_t> ps;
        for (auto& p : params)
          ps.insert(ps.end(), p.second.begin(), p.second.end());
        in.data.insert(in.data.begin(), ps.begin(), ps.end());
        if (paramsResent++ < 5)
          LOGI("EL parameter sets (%zu bytes) resent before the IRAP at %lld us after a flush", ps.size(),
               (long long)in.pts);
      }
      resendParams = false;
      skipRasl = type <= 18 || (type == 21 && needIrap);
      if (needIrap && !gapWait)
      {
        // frames shown before it (skipped leading pictures) get no EL
        std::lock_guard<std::mutex> lock(m_lock);
        m_noElBefore = in.pts;
      }
      needIrap = false;
      gapWait = false;
    }
    else if (needIrap || (skipRasl && (type == 8 || type == 9)))
    {
      decode = false;
    }
    if (endOfSeq)
      needIrap = true;

    if (!decode)
    {
      // after a start or flush the skipped pictures are the earliest pending
      // ones; after a gap the pictures decoded before it still come out
      if (!gapWait)
      {
        slots.insert(in.pts);
        skipped++;
      }
      std::lock_guard<std::mutex> lock(m_lock);
      m_noElPts.insert(in.pts);
      while (m_noElPts.size() > MAX_META)
        m_noElPts.erase(m_noElPts.begin());
      m_readyCond.notify_all();
      continue;
    }
    slots.insert(in.pts);
    metas[in.pts] = std::move(in.meta);
    while (metas.size() > 4 * EL_POOL) // never output, e.g. broken pictures
      metas.erase(metas.begin());

    // an input buffer; decoded pictures are taken out meanwhile. A decoder
    // that neither takes input nor gives output (seen once after a seek: 12
    // pictures in it, nothing out, for the rest of the film - this loop never
    // got to the next seek's flush either) is flushed after EL_STALL_MS, and
    // a flush queued meanwhile (a seek) is not kept waiting.
    ssize_t idx;
    const auto waitFrom = std::chrono::steady_clock::now();
    const uint64_t outsBefore = m_elOutputs.load();
    bool flushed = false;
    while ((idx = AMediaCodec_dequeueInputBuffer(codec, 0)) < 0 && !m_stop)
    {
      if (idx != AMEDIACODEC_INFO_TRY_AGAIN_LATER)
      {
        LOGE("fatal EL dequeue input error: %zd", idx);
        m_stop = true;
        break;
      }
      if (!drain(5000))
      {
        std::unique_lock<std::mutex> lock(m_lock);
        m_elCond.wait_for(lock, std::chrono::milliseconds(5));
      }
      bool seek;
      {
        std::lock_guard<std::mutex> lock(m_lock);
        seek = !m_elQueue.empty() && m_elQueue.front().flush;
      }
      // SC2: outputs wait while the display holds all images (e.g. pause)
      const bool stalled = !(m_sc2 && m_sc2Held >= SC2_EL_AHEAD) && m_elOutputs.load() == outsBefore &&
                           std::chrono::steady_clock::now() - waitFrom > std::chrono::milliseconds(EL_STALL_MS);
      if (seek || stalled)
      {
        if (stalled && stalls++ < 10)
          LOGW("EL decoder stalled (%d pictures in it, no output for %d ms): flushed", m_elInFlight.load(),
               EL_STALL_MS);
        flushDecoder();
        flushed = true;
        break;
      }
    }
    if (m_stop)
      break;
    if (flushed || idx < 0)
      continue; // this picture is dropped: the EL starts again at its next IRAP
    size_t cap = 0;
    uint8_t* p = AMediaCodec_getInputBuffer(codec, idx, &cap);
    const size_t size = std::min(cap, in.data.size());
    if (in.data.size() > cap && truncated++ < 10)
      LOGW("EL picture %lld us of %zu bytes truncated to the input buffer (%zu bytes)",
           (long long)in.pts, in.data.size(), cap);
    if (p)
      std::memcpy(p, in.data.data(), size);
    AMediaCodec_queueInputBuffer(codec, idx, 0, p ? size : 0, static_cast<uint64_t>(in.pts), 0);
    m_elInputs++;
    inFlight.insert(in.pts);
    m_elInFlight = static_cast<int>(inFlight.size());
    drain(0);
  }

  LOGI("hardware EL decoder stopped, %llu pictures imported (VICP avg %.1f ms), %llu import errors",
       (unsigned long long)imports, imports ? importUs / 1000.0 / imports : 0.0,
       (unsigned long long)importErrors);
}

bool dvfp::WaitForFrame(int64_t pts, ElFrame& el)
{
  std::unique_lock<std::mutex> lock(m_lock);
  // the display reached pts: let the paced EL decoder run ahead of it
  m_lastJobPts = pts;
  while (!m_elFrames.empty() && m_elFrames.begin()->first < pts - PTS_TOLERANCE_US)
  {
    ReleaseElFrame(m_elFrames.begin()->second);
    m_elFrames.erase(m_elFrames.begin());
  }
  m_noElPts.erase(m_noElPts.begin(), m_noElPts.lower_bound(pts - PTS_TOLERANCE_US));
  m_elCond.notify_all();

  // no EL will come: shown before the first decodable EL picture (after a
  // start or seek), its EL missing or not decodable, or the EL (in display
  // order) is past this frame already
  auto never = [&] {
    return (m_noElBefore != INT64_MIN && pts + PTS_TOLERANCE_US < m_noElBefore) ||
           m_noElPts.count(pts) != 0 ||
           (!m_elFrames.empty() && m_elFrames.rbegin()->first > pts + PTS_TOLERANCE_US &&
            FindPts(m_elFrames, pts) == m_elFrames.end());
  };
  if (never())
  {
    m_noElSkipped++;
    return false;
  }
  const bool found = m_readyCond.wait_for(lock, std::chrono::milliseconds(EL_WAIT_MS), [&] {
    return m_stop || FindPts(m_elFrames, pts) != m_elFrames.end() || never();
  });
  if (found && !m_stop && FindPts(m_elFrames, pts) == m_elFrames.end())
  {
    m_noElSkipped++;
    return false;
  }
  if (!found || m_stop)
  {
    if (!m_stop && m_noPairLogged < 40)
    {
      m_noPairLogged++;
      auto next = m_elFrames.lower_bound(pts);
      LOGI("no EL for pts %lld us: EL frames %zu [%lld..%lld] next %lld, EL queue %zu (newest queued "
           "%lld), decoder in %llu out %llu last out %lld",
           (long long)pts, m_elFrames.size(),
           (long long)(m_elFrames.empty() ? 0 : m_elFrames.begin()->first),
           (long long)(m_elFrames.empty() ? 0 : m_elFrames.rbegin()->first),
           (long long)(next == m_elFrames.end() ? -1 : next->first), m_elQueue.size(),
           (long long)(m_elQueue.empty() ? -1 : m_elQueue.back().pts),
           (unsigned long long)m_elInputs.load(), (unsigned long long)m_elOutputs.load(),
           (long long)m_elLastOut.load());
    }
    return false;
  }

  // the frame goes to the caller; everything older is not needed any more
  auto f = FindPts(m_elFrames, pts);
  el = std::move(f->second);
  f->second.buf = -1;
  for (auto it = m_elFrames.begin(), end = std::next(f); it != end;)
  {
    ReleaseElFrame(it->second);
    it = m_elFrames.erase(it);
  }
  return el.buf >= 0;
}

// ---------------------------------------------------------------------------
// GPU thread

void dvfp::GpuThread(std::promise<bool> ready)
{
  pthread_setname_np(pthread_self(), "dvfp-gpu");
  SetThreadNice("GPU");
  bool announced = false;
  auto fail = [&](const char* what) {
    LOGE("%s", what);
    if (!announced)
      ready.set_value(false);
    announced = true;
  };

  int dev = -1, ion = -1;
  EGLDisplay dpy = EGL_NO_DISPLAY;
  EGLContext ctx = EGL_NO_CONTEXT;
  struct felgpu* fel = nullptr;
  dvfel_reg_bufs2 reg2{};
  dvfel_reg_bufs& reg = reg2.base;
  for (int i = 0; i < DVFEL_MAX_BUFS; i++)
    reg.in_fd[i] = reg.out_fd[i] = reg.el_fd[i] = -1;
  for (int i = 0; i < DVFEL_MAX_OUT; i++)
    reg2.lin_fd[i] = -1;
  GLuint tin[GPU_BUFFERS]{}, tel[EL_POOL]{};
  GLuint tlin[DVFEL_MAX_OUT]{}, flin[DVFEL_MAX_OUT]{};
  EGLImageKHR images[GPU_BUFFERS + EL_POOL + DVFEL_MAX_OUT]{};
  int nimg = 0;
  // the display shows the composed frames directly: one display buffer per
  // kernel slot, the job names the one to write
  const int nLin = std::atoi(ReadLine("/sys/module/dvfel/parameters/slots").c_str());

  auto createImage = PFNEGLCREATEIMAGEKHRPROC(eglGetProcAddress("eglCreateImageKHR"));
  auto destroyImage = PFNEGLDESTROYIMAGEKHRPROC(eglGetProcAddress("eglDestroyImageKHR"));
  auto targetTexture =
      PFNGLEGLIMAGETARGETTEXTURE2DOESPROC(eglGetProcAddress("glEGLImageTargetTexture2DOES"));
  auto importTex = [&](int fd, int width, int height) -> GLuint {
    const EGLint attr[] = {EGL_WIDTH, width, EGL_HEIGHT, height,
                           EGL_LINUX_DRM_FOURCC_EXT, DRM_FORMAT_ABGR8888,
                           EGL_DMA_BUF_PLANE0_FD_EXT, fd,
                           EGL_DMA_BUF_PLANE0_OFFSET_EXT, 0,
                           EGL_DMA_BUF_PLANE0_PITCH_EXT, width * 4,
                           EGL_NONE};
    EGLImageKHR img = createImage(dpy, EGL_NO_CONTEXT, EGL_LINUX_DMA_BUF_EXT, nullptr, attr);
    if (img == EGL_NO_IMAGE_KHR)
    {
      LOGE("eglCreateImageKHR(fd %d, %dx%d) failed: %#x", fd, width, height, eglGetError());
      return 0;
    }
    images[nimg++] = img;
    GLuint tex;
    glGenTextures(1, &tex);
    glBindTexture(GL_TEXTURE_2D, tex);
    targetTexture(GL_TEXTURE_2D, img);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    const GLenum err = glGetError();
    if (err != GL_NO_ERROR)
      LOGE("binding the EGL image of fd %d failed: %#x", fd, err);
    return err == GL_NO_ERROR ? tex : 0;
  };

  do
  {
    if (nLin < 2 || nLin > DVFEL_MAX_OUT)
    {
      fail("unexpected dvfel slots parameter");
      break;
    }
    if (!createImage || !destroyImage || !targetTexture)
    {
      fail("EGL image extensions missing");
      break;
    }
    // the process' default display; it is never terminated here
    dpy = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    EGLint maj, min;
    if (dpy == EGL_NO_DISPLAY || !eglInitialize(dpy, &maj, &min))
    {
      fail("eglInitialize failed");
      break;
    }
    eglBindAPI(EGL_OPENGL_ES_API);
    // a high priority context (EGL_IMG_context_priority) made it worse: p95 38 -> 53 ms
    const EGLint ctxAttr[] = {EGL_CONTEXT_MAJOR_VERSION, 3, EGL_CONTEXT_MINOR_VERSION, 2, EGL_NONE};
    ctx = eglCreateContext(dpy, EGL_NO_CONFIG_KHR, EGL_NO_CONTEXT, ctxAttr);
    if (ctx == EGL_NO_CONTEXT || !eglMakeCurrent(dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, ctx))
    {
      fail("cannot create a surfaceless GLES 3.2 context");
      break;
    }

    // test: debug.dvfp.taps 4 (Lanczos-2 EL upsampling, not bit exact with 6)
    fel = felgpu_create(m_width, m_height, PropInt("debug.dvfp.taps", EL_TAPS) == 4 ? 4 : EL_TAPS);
    if (!fel)
    {
      fail("felgpu_create failed");
      break;
    }

    dev = open(DevicePath().c_str(), O_RDWR | O_CLOEXEC);
    ion = open(ION_DEV, O_RDONLY | O_CLOEXEC);
    const int heapId = ion >= 0 ? IonHeapId(ion) : -1;
    if (dev < 0 || heapId < 0)
    {
      fail("cannot open /dev/dvfel or find the codec_mm ION heap");
      break;
    }
    reg.width = m_width;
    reg.height = m_height;
    reg.count = GPU_BUFFERS;
    bool ok = true;
    for (int i = 0; i < GPU_BUFFERS && ok; i++)
    {
      reg.in_fd[i] = IonAlloc(ion, heapId, DVFEL_BUF_SIZE(m_width, m_height));
      tin[i] = reg.in_fd[i] >= 0 ? importTex(reg.in_fd[i], m_width, m_height) : 0;
      ok = tin[i] != 0;
    }
    // the display buffers the GPU writes
    for (int i = 0; i < nLin && ok; i++)
    {
      reg2.lin_fd[i] = IonAlloc(ion, heapId, DVFEL_BUF_SIZE(m_width, m_height));
      tlin[i] = reg2.lin_fd[i] >= 0 ? importTex(reg2.lin_fd[i], m_width, m_height) : 0;
      if (!tlin[i])
      {
        ok = false;
        break;
      }
      glGenFramebuffers(1, &flin[i]);
      glBindFramebuffer(GL_FRAMEBUFFER, flin[i]);
      glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tlin[i], 0);
      ok = glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
    }
    // the pool the kernel decompresses the EL pictures into
    for (int i = 0; i < EL_POOL && ok; i++)
    {
      m_elPoolFd[i] = IonAlloc(ion, heapId, DVFEL_BUF_SIZE(m_elWidth, m_elHeight));
      tel[i] = m_elPoolFd[i] >= 0 ? importTex(m_elPoolFd[i], m_elWidth, m_elHeight) : 0;
      ok = tel[i] != 0;
    }
    if (!ok)
    {
      LOGE("ION allocation of %d bytes from heap %d or its import failed: %s",
           (int)DVFEL_BUF_SIZE(m_width, m_height), heapId, strerror(errno));
      fail("cannot allocate or import the frame buffers");
      break;
    }
    glFinish();
    // routes the video composer through dvfel (kernel vc_path 2)
    reg2.out_count = nLin;
    if (ioctl(dev, DVFEL_IOC_REG_BUFS2, &reg2) < 0)
    {
      fail("DVFEL_IOC_REG_BUFS2 failed");
      break;
    }
    {
      // lead time: the player renders the frames ahead, the kernel holds them
      // back to their vsync (more time for the GPU); old modules: none
      std::string lead = ReadLine("/sys/module/dvfel/parameters/out_lead_ms");
      const int ms = std::clamp(PropInt(PROP_LEAD, lead.empty() ? 0 : std::atoi(lead.c_str())), 0, 500);
      uint32_t us = uint32_t(ms) * 1000;
      if (ms > 0 && ioctl(dev, DVFEL_IOC_SET_LEAD, &us) == 0)
        m_leadUs = int(us);
      LOGI("lead time %d ms%s", m_leadUs / 1000, ms > 0 && !m_leadUs ? " (not supported by the module)" : "");
    }
    ready.set_value(true);
    announced = true;
    LOGI("GPU compositor ready (%s), %d display buffers (direct)",
         reinterpret_cast<const char*>(glGetString(GL_RENDERER)), nLin);

    std::shared_ptr<felgpu_meta> current;
    int dither = -1, pattern = -1, demo = -1, noGpu = 0, profile = -1;
    auto nextCheck = std::chrono::steady_clock::now();
    auto nextProfile = nextCheck;
    auto nextOverlay = nextCheck;
    std::vector<float> profMs; // composed frames since the last profile line
    int dumpLeft = PropInt(PROP_DUMP, 0); // frames to dump (every 24th composed one)
    uint64_t dumpCount = 0;
    while (!m_stop)
    {
      if (std::chrono::steady_clock::now() >= nextCheck)
      {
        nextCheck += std::chrono::seconds(1);
        const int pr = std::clamp(PropInt(PROP_PROFILE, 0), 0, 2);
        if (pr != profile)
        {
          profile = pr;
          felgpu_set_profile(fel, pr == 1); // 2: frame times only (no glFinish per pass)
          double ms[5];
          felgpu_get_profile(fel, ms, 1);
          profMs.clear();
          nextProfile = std::chrono::steady_clock::now() + std::chrono::seconds(5);
        }
        if (profile && std::chrono::steady_clock::now() >= nextProfile)
        {
          nextProfile += std::chrono::seconds(5);
          double ms[5];
          const int n = felgpu_get_profile(fel, ms, 1);
          std::sort(profMs.begin(), profMs.end());
          const float p95 = profMs.empty() ? 0.0f : profMs[(profMs.size() - 1) * 95 / 100];
          const float mx = profMs.empty() ? 0.0f : profMs.back();
          const long over = std::count_if(profMs.begin(), profMs.end(), [](float v) { return v > 41.7f; });
          LOGI("profile %d frames: EL-V luma %.2f, EL-V chroma %.2f, chroma %.2f, luma+pack %.2f ms; "
               "total p95 %.1f max %.1f, %ld over 41.7 ms; mapping %s",
               n, ms[0], ms[1], ms[2], ms[3], p95, mx, over,
               !current ? "-" : (current->mapping[1][0] || current->mapping[2][0]) ? "MMR chroma"
                                                                                  : "polynomial");
          profMs.clear();
          LOGI("present: lead %.1f ms, %s", LeadNs() / 1e6, VsyncTracker::Get().TakeStats().c_str());
        }
        const int d = std::clamp(PropInt(PROP_DITHER, 1), 0, 2);
        if (d != dither)
        {
          dither = d;
          felgpu_set_dither(fel, d);
          LOGI("dither mode %d", d);
        }
        const int p = PropInt(PROP_PATTERN, 0) == 1 ? 1 : 0;
        if (p != pattern)
        {
          pattern = p;
          felgpu_set_pattern(fel, p);
        }
        // the player's menu (dvfp_demo) over dvfp_set_demo and debug.dvfp.demo
        const int menu = DemoFile();
        const int dm = menu >= 0 ? menu : m_demo || PropInt(PROP_DEMO, 0) == 1 ? 1 : 0;
        if (dm != demo)
        {
          demo = dm;
          felgpu_set_demo(fel, dm);
          for (int line = 0; line < 2 * FELGPU_OVERLAY_LINES; line++)
            felgpu_set_overlay(fel, line, "");
          // the state for the player's menu
          if (FILE* f = fopen(AppFile("dvfp_fel").c_str(), "w"))
          {
            fprintf(f, "demo=%d\n", dm);
            fclose(f);
          }
          LOGI("demo mode %d", dm);
        }
        noGpu = PropInt(PROP_NOGPU, 0) == 1;
      }

      dvfel_job2 job{};
      job.job.timeout_ms = 100;
      if (ioctl(dev, DVFEL_IOC_WAIT_JOB2, &job) < 0)
      {
        if (errno == ETIMEDOUT || errno == EINTR)
          continue;
        LOGE("DVFEL_IOC_WAIT_JOB2 failed: %s", strerror(errno));
        break;
      }

      dvfel_job_done done{};
      done.id = job.job.id;
      done.status = DVFEL_DONE_PASSTHROUGH;

      // where the composed frame goes
      const GLuint target = job.out < static_cast<uint32_t>(nLin) ? flin[job.out] : 0;

      ElFrame el;
      bool haveEl = false;
      int64_t pts = -1;
      std::shared_ptr<felgpu_meta> staleMeta;
      if (job.job.buf < GPU_BUFFERS && target && !noGpu)
      {
        pts = (job.job.flags & DVFEL_JOB_RPU_CRC) ? PtsForRpuCrc(job.rpu_crc) : -1;
        if (pts >= 0)
        {
          haveEl = WaitForFrame(pts, el);
        }
        else if (StaleMeta(job.rpu_crc, staleMeta))
        {
          m_stale++;
        }
        else
        {
          m_noCrc++;
          if (m_noCrcLogged < 10)
          {
            m_noCrcLogged++;
            std::lock_guard<std::mutex> lock(m_lock);
            std::string pending;
            char item[32];
            for (auto it = m_crcByPts.begin(); it != m_crcByPts.end() && pending.size() < 80; ++it)
            {
              snprintf(item, sizeof(item), " %lld:%08x", (long long)(it->first / 1000), it->second);
              pending += item;
            }
            LOGI("frame %u (rpu crc %08x, flags %#x) matches no packet (pending %zu:%s)", job.seq,
                 job.rpu_crc, job.job.flags, m_crcByPts.size(), pending.c_str());
          }
        }
      }

      if (haveEl)
      {
        const auto t0 = std::chrono::steady_clock::now();
        if (el.meta != current)
        {
          felgpu_set_meta(fel, el.meta.get());
          current = el.meta;
        }
        felgpu_set_el_tex(fel, tel[el.buf]);
        felgpu_set_active_area(fel, el.meta->l5[0] >= 0 ? el.meta->l5 : nullptr);
        felgpu_run(fel, tin[job.job.buf], target);
        glFinish();
        const float ms = std::chrono::duration<float, std::milli>(std::chrono::steady_clock::now() - t0).count();
        // demo mode: the overlay text is drawn from the next frame, updated at
        // 4 Hz to stay readable
        felgpu_stats stats;
        if (demo == 1 && felgpu_get_stats(fel, &stats) && std::chrono::steady_clock::now() >= nextOverlay)
        {
          nextOverlay = std::chrono::steady_clock::now() + std::chrono::milliseconds(250);
          SetDemoOverlay(fel, stats, *el.meta);
        }
        {
          std::lock_guard<std::mutex> lock(m_lock);
          m_gpuMsSum += ms;
          m_gpuMsMax = std::max(m_gpuMsMax, ms);
        }
        if (m_gpuMs.size() < 1000000)
          m_gpuMs.push_back(ms);
        if (profile)
          profMs.push_back(ms);
        done.status = DVFEL_DONE_COMPOSED;
        m_composed++;
        if (dumpLeft > 0 && ++dumpCount % 24 == 0)
        {
          // test data for tools/felbench: input, EL, mapping and output of the frame
          dumpLeft--;
          const size_t bl = DVFEL_BUF_SIZE(m_width, m_height), elb = DVFEL_BUF_SIZE(m_elWidth, m_elHeight);
          const std::string base = DumpDir() + "/f" + std::to_string(dumpCount);
          const bool ok = DumpFd(base + "_bl.bin", reg.in_fd[job.job.buf], bl, size_t(m_width) * m_height * 4) &&
                          DumpFd(base + "_el.bin", m_elPoolFd[el.buf], elb, size_t(m_elWidth) * m_elHeight * 4) &&
                          DumpFd(base + "_out.bin", reg2.lin_fd[job.out], bl, size_t(m_width) * m_height * 4);
          FILE* mf = fopen((base + "_meta.bin").c_str(), "wb");
          if (mf)
          {
            fwrite(el.meta.get(), sizeof(felgpu_meta), 1, mf);
            fclose(mf);
          }
          LOGI("frame %llu dumped to %s_*.bin (%s), dither %d", (unsigned long long)dumpCount,
               base.c_str(), ok && mf ? "ok" : "FAILED", dither);
        }
      }
      else
      {
        // no EL (e.g. the leading pictures after a seek): the frame's own
        // mapping without the residual looks close to its neighbours, the
        // base layer as is would flash
        std::shared_ptr<felgpu_meta> meta = std::move(staleMeta);
        if (pts >= 0)
        {
          std::lock_guard<std::mutex> lock(m_lock);
          auto m = FindPts(m_metaByPts, pts);
          if (m != m_metaByPts.end())
            meta = m->second;
        }
        if (meta)
        {
          felgpu_meta mapping = *meta;
          mapping.use_el = 0;
          felgpu_set_meta(fel, &mapping);
          current = nullptr;
          felgpu_set_active_area(fel, mapping.l5[0] >= 0 ? mapping.l5 : nullptr);
          felgpu_run(fel, tin[job.job.buf], target);
          glFinish();
          done.status = DVFEL_DONE_COMPOSED;
          m_mappedOnly++;
        }
        m_passthrough++;
      }
      {
        std::lock_guard<std::mutex> lock(m_lock);
        ReleaseElFrame(el);
      }
      ioctl(dev, DVFEL_IOC_JOB_DONE, &done);
    }
  } while (false);

  if (dev >= 0)
  {
    ioctl(dev, DVFEL_IOC_UNREG_BUFS);
    close(dev);
  }
  if (fel)
    felgpu_destroy(fel);
  if (ctx != EGL_NO_CONTEXT)
  {
    glDeleteFramebuffers(DVFEL_MAX_OUT, flin);
    glDeleteTextures(GPU_BUFFERS, tin);
    glDeleteTextures(DVFEL_MAX_OUT, tlin);
    glDeleteTextures(EL_POOL, tel);
    for (int i = 0; i < nimg; i++)
      destroyImage(dpy, images[i]);
    eglMakeCurrent(dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    eglDestroyContext(dpy, ctx);
  }
  for (int i = 0; i < GPU_BUFFERS; i++)
    if (reg.in_fd[i] >= 0)
      close(reg.in_fd[i]);
  for (int i = 0; i < DVFEL_MAX_OUT; i++)
    if (reg2.lin_fd[i] >= 0)
      close(reg2.lin_fd[i]);
  if (ion >= 0)
    close(ion);
  if (!announced)
    ready.set_value(false);
}

// ---------------------------------------------------------------------------
// C API

extern "C" {

void dvfp_set_log(dvfp_log_fn fn, void* opaque)
{
  std::lock_guard<std::mutex> lock(g_logLock);
  g_logFn = fn;
  g_logOpaque = opaque;
}

int dvfp_api_version(void)
{
  return DVFP_API_VERSION;
}

const char* dvfp_version(void)
{
  return DVFP_VERSION;
}

const char* dvfp_module_version(char* buf, size_t size)
{
  if (!buf || !size)
    return buf;
  const std::string v = ReadLine("/sys/module/dvfel/version");
  snprintf(buf, size, "%s", v.c_str());
  return buf;
}

// Date/revision components must compare numerically: revision 10 follows 9.
static bool ModuleVersionAtLeast(const std::string& value, const std::string& minimum)
{
  unsigned parts[2][4] = {};
  const std::string* versions[2] = {&value, &minimum};
  for (unsigned n = 0; n < 2; ++n) {
    unsigned component = 0;
    bool digit = false;
    for (char c : *versions[n]) {
      if (c >= '0' && c <= '9') {
        if (parts[n][component] > 100000) return false;
        parts[n][component] = parts[n][component] * 10 + (c - '0');
        digit = true;
      } else if (c == '.' && digit && component < 3) {
        ++component;
        digit = false;
      } else return false;
    }
    if (!digit || component < 2) return false;
  }
  for (unsigned i = 0; i < 4; ++i)
    if (parts[0][i] != parts[1][i]) return parts[0][i] > parts[1][i];
  return true;
}

int dvfp_available(void)
{
  const std::string v = ReadLine("/sys/module/dvfel/version");
  // versions are dates with an optional numeric revision
  if (UsesHardwareComposer()) // the Dolby Vision core composes: no GPU buffers (ION)
    return !v.empty() && ModuleVersionAtLeast(v, MIN_MODULE_SC2) && access(DevicePath().c_str(), R_OK | W_OK) == 0;
  return !v.empty() && ModuleVersionAtLeast(v, MIN_MODULE) && access(DevicePath().c_str(), R_OK | W_OK) == 0 &&
         access(ION_DEV, R_OK) == 0;
}

int dvfp_probe(const uint8_t* au, size_t size, struct dvfp_rpu_info* info)
{
  if (!au || !info)
    return -1;
  *info = {};
  ForEachNal(au, size, [&](const uint8_t* nal, size_t n) {
    if (n < 2)
      return;
    const int type = NalType(nal);
    if (type == 63)
      info->has_el = 1;
    if (type != 62 || info->has_rpu)
      return;
    DoviRpuOpaque* rpu = dovi_parse_unspec62_nalu(nal, n);
    const DoviRpuDataHeader* hdr = rpu ? dovi_rpu_get_header(rpu) : nullptr;
    if (hdr)
    {
      info->has_rpu = 1;
      info->profile = hdr->guessed_profile;
      if (hdr->el_type)
        info->el_type = !strcmp(hdr->el_type, "FEL") ? DVFP_EL_FEL
                        : !strcmp(hdr->el_type, "MEL") ? DVFP_EL_MEL
                                                       : DVFP_EL_NONE;
      dovi_rpu_free_header(hdr);
    }
    if (rpu)
      dovi_rpu_free(rpu);
  });
  return 0;
}

dvfp* dvfp_open(int width, int height, int mode)
{
  VsyncTracker::Get(); // the vsync period is known by the first frame
  if (mode != DVFP_MODE_CONVERT && mode != DVFP_MODE_FEL)
    return nullptr;
  auto* h = new (std::nothrow) dvfp();
  if (!h)
    return nullptr;
  h->mode = mode;
  if (mode == DVFP_MODE_FEL)
  {
    if (!dvfp_available())
    {
      char v[32];
      LOGE("dvfel kernel module not usable (version '%s', need %s)", dvfp_module_version(v, sizeof(v)),
           MIN_MODULE);
      delete h;
      return nullptr;
    }
    if (!h->Start(width, height))
    {
      delete h;
      return nullptr;
    }
  }
  LOGI("dvfelplay %s opened, %s, %dx%d", DVFP_VERSION,
       mode == DVFP_MODE_FEL ? "FEL composition" : "profile 8.1 conversion", width, height);
  return h;
}

void dvfp_close(dvfp* h)
{
  delete h;
}

int dvfp_mode(const dvfp* h)
{
  return h ? h->mode : -1;
}

int dvfp_filter(dvfp* h, const uint8_t* in, size_t size, const uint8_t** out, size_t* out_size)
{
  if (!h || !in || !out || !out_size)
  {
    if (out && out_size)
    {
      *out = in;
      *out_size = size;
    }
    return -1;
  }
  return h->Filter(in, size, out, out_size);
}

void dvfp_commit(dvfp* h, int64_t pts_us)
{
  if (h)
    h->Commit(pts_us);
}

void dvfp_drop(dvfp* h)
{
  if (h)
    h->Drop();
}

void dvfp_flush(dvfp* h)
{
  if (h)
    h->Flush();
}

void dvfp_get_stats(const dvfp* h, struct dvfp_stats* stats)
{
  if (!stats)
    return;
  if (!h)
  {
    *stats = {};
    return;
  }
  h->GetStats(*stats);
}

int64_t dvfp_snap_present_ns(int64_t desired_ns)
{
  return VsyncTracker::Get().Snap(desired_ns);
}

int64_t dvfp_present_lead_ns(const dvfp* h)
{
  return h ? h->LeadNs() : 0;
}

void dvfp_set_demo(dvfp* h, int on)
{
  if (h)
    h->SetDemo(on);
}

} // extern "C"
