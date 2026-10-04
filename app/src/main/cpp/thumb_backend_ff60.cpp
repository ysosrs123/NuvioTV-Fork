/*
 * Fallback backend: the FFmpeg 6.0 inside libmediainfo.so. Built against third_party/ffmpeg-n6.0 only.
 * libmediainfo.so exports no version symbol, the AAR is SHA-256 pinned in app/build.gradle.kts.
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
#define BACKEND_NAME "ff60"

static_assert(LIBAVCODEC_VERSION_MAJOR == 60, "ff60 backend must build against the n6.0 headers");

namespace {
#include "thumb_backend_impl.inc"

bool load_symbols(Api &api, char *version, size_t n) {
    // Soname lookup: returns the instance already loaded by MediaInfoBuilder, if any.
    void *h = dlopen("libmediainfo.so", RTLD_NOW | RTLD_LOCAL);
    if (!h) {
        LOGW("ff60: dlopen libmediainfo.so failed: %s", dlerror());
        return false;
    }
    if (!resolve_all(api, &h, 1)) return false;
    snprintf(version, n, "libmediainfo FFmpeg 6.0 (avcodec %d.%d.%d headers)",
             LIBAVCODEC_VERSION_MAJOR, LIBAVCODEC_VERSION_MINOR, LIBAVCODEC_VERSION_MICRO);
    return true;
}
}  // namespace

const thumb::Backend *thumb::backend_ff60() { return get(); }
