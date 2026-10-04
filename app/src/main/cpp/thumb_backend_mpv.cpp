/*
 * Primary backend: MPV's FFmpeg (mpv-android-lib, FFmpeg master a7522f3fef, libavcodec 62 / libavutil 60 /
 * libswscale 9). Built against third_party/ffmpeg-a7522f3fef. Refused unless every library's major version
 * equals the headers', so an mpv-android-lib update falls back to ff60 instead of misreading structs.
 */
#include <android/log.h>
#include <dlfcn.h>
#include <pthread.h>
#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>

extern "C" {
#include "libavcodec/avcodec.h"
#include "libavutil/dovi_meta.h"
#include "libavutil/frame.h"
#include "libavutil/mastering_display_metadata.h"
#include "libavutil/pixdesc.h"
#include "libswscale/swscale.h"
#include "thumbcolor.h"
#include "thumbdovi.h"
}
#include "thumb_backend.h"

#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "ThumbDecode", __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "ThumbDecode", __VA_ARGS__)
#define BACKEND_NAME "mpv"

static_assert(LIBAVCODEC_VERSION_MAJOR == 62, "mpv backend must build against the a7522f3fef headers");

namespace {
#include "thumb_backend_impl.inc"

bool load_symbols(Api &api, char *version, size_t n) {
    // Dependency order, so each DT_NEEDED resolves to the instance already loaded.
    static const char *const kLibs[] = {"libavutil.so", "libswresample.so", "libavcodec.so", "libswscale.so"};
    constexpr int kCount = sizeof kLibs / sizeof kLibs[0];
    void *handles[kCount] = {};
    for (int i = 0; i < kCount; i++) {
        handles[i] = dlopen(kLibs[i], RTLD_NOW | RTLD_LOCAL);
        if (!handles[i]) {
            LOGI("mpv: %s not available (%s)", kLibs[i], dlerror());
            return false;
        }
    }
    using VersionFn = unsigned (*)();
    auto codec_v = reinterpret_cast<VersionFn>(find_symbol(handles, kCount, "avcodec_version"));
    auto util_v = reinterpret_cast<VersionFn>(find_symbol(handles, kCount, "avutil_version"));
    auto sws_v = reinterpret_cast<VersionFn>(find_symbol(handles, kCount, "swscale_version"));
    if (!codec_v || !util_v || !sws_v) {
        LOGW("mpv: version symbols missing");
        return false;
    }
    const unsigned cv = codec_v(), uv = util_v(), sv = sws_v();
    if ((cv >> 16) != LIBAVCODEC_VERSION_MAJOR || (uv >> 16) != LIBAVUTIL_VERSION_MAJOR ||
        (sv >> 16) != LIBSWSCALE_VERSION_MAJOR) {
        LOGW("mpv: FFmpeg majors avcodec %u / avutil %u / swscale %u differ from the headers (%d/%d/%d); "
             "update third_party/ffmpeg-* to use it", cv >> 16, uv >> 16, sv >> 16,
             LIBAVCODEC_VERSION_MAJOR, LIBAVUTIL_VERSION_MAJOR, LIBSWSCALE_VERSION_MAJOR);
        return false;
    }
    if (!resolve_all(api, handles, kCount)) return false;
    snprintf(version, n, "avcodec %u.%u.%u avutil %u.%u.%u swscale %u.%u.%u", cv >> 16, (cv >> 8) & 0xff, cv & 0xff,
             uv >> 16, (uv >> 8) & 0xff, uv & 0xff, sv >> 16, (sv >> 8) & 0xff, sv & 0xff);
    return true;
}
}  // namespace

const thumb::Backend *thumb::backend_mpv() { return get(); }
