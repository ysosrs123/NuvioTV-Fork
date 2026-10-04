/*
 * JNI side of the thumbnail keyframe decoder. Picks one FFmpeg backend per process (thumb_backend.h).
 * `setprop debug.nuvio.thumb.backend ff60` (or `mpv`) before app start forces the choice.
 *
 * One handle = one decoder context, used from one thread at a time (the Kotlin side serialises).
 */
#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <dlfcn.h>
#include <pthread.h>
#include <sys/system_properties.h>
#include <cerrno>
#include <cstdio>
#include <cstring>

#include "thumb_backend.h"

#define TAG "ThumbDecode"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

namespace {

const thumb::Backend *g_backend = nullptr;
bool g_chosen = false;
pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;

const thumb::Backend *backend() {
    pthread_mutex_lock(&g_lock);
    if (!g_chosen) {
        g_chosen = true;
        char forced[PROP_VALUE_MAX] = "";
        __system_property_get("debug.nuvio.thumb.backend", forced);
        if (strcmp(forced, "ff60") != 0) g_backend = thumb::backend_mpv();
        if (!g_backend && strcmp(forced, "mpv") != 0) g_backend = thumb::backend_ff60();
        if (g_backend) LOGI("backend=%s %s%s", g_backend->name, g_backend->version, forced[0] ? " (forced)" : "");
        else LOGW("no FFmpeg backend available");
    }
    const thumb::Backend *b = g_backend;
    pthread_mutex_unlock(&g_lock);
    return b;
}

struct JHandle {
    const thumb::Backend *b;
    void *h;
};

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_nuvio_tv_core_player_thumbnail_ThumbNative_nativeInit(JNIEnv *, jclass) {
    return backend() ? JNI_TRUE : JNI_FALSE;
}

/** "<name> <version>", "" when none. */
JNIEXPORT jstring JNICALL
Java_com_nuvio_tv_core_player_thumbnail_ThumbNative_nativeBackend(JNIEnv *env, jclass) {
    const thumb::Backend *b = backend();
    char s[160] = "";
    if (b) snprintf(s, sizeof s, "%s %s", b->name, b->version);
    return env->NewStringUTF(s);
}

/** threads > 1 enables slice threading. Returns 0 when the decoder is missing. */
JNIEXPORT jlong JNICALL
Java_com_nuvio_tv_core_player_thumbnail_ThumbNative_nativeOpen(JNIEnv *env, jclass, jstring decoder, jbyteArray extradata,
                                                                jint width, jint height, jint threads) {
    const thumb::Backend *b = backend();
    if (!b || !decoder) return 0;
    const char *name = env->GetStringUTFChars(decoder, nullptr);
    if (!name) return 0;
    jbyte *extra = nullptr;
    jsize extra_len = 0;
    if (extradata) {
        extra_len = env->GetArrayLength(extradata);
        extra = env->GetByteArrayElements(extradata, nullptr);
    }
    void *h = b->open(name, reinterpret_cast<const uint8_t *>(extra), extra_len, width, height, threads);
    if (extra) env->ReleaseByteArrayElements(extradata, extra, JNI_ABORT);
    env->ReleaseStringUTFChars(decoder, name);
    if (!h) return 0;
    return reinterpret_cast<jlong>(new JHandle{b, h});
}

/** Decodes one keyframe, fills info[] and keeps the frame in the handle for nativeRender. 0 or a negative AVERROR. */
JNIEXPORT jint JNICALL
Java_com_nuvio_tv_core_player_thumbnail_ThumbNative_nativeDecode(JNIEnv *env, jclass, jlong handle,
                                                                  jbyteArray data, jint len, jintArray info) {
    auto *j = reinterpret_cast<JHandle *>(handle);
    if (!j || len <= 0) return -EINVAL;
    uint8_t *buf = j->b->packet(j->h, len);
    if (!buf) return -ENOMEM;
    env->GetByteArrayRegion(data, 0, len, reinterpret_cast<jbyte *>(buf));
    int32_t out[thumb::INFO_COUNT];
    int r = j->b->decode(j->h, out);
    if (r == 0) env->SetIntArrayRegion(info, 0, thumb::INFO_COUNT, reinterpret_cast<jint *>(out));
    return r;
}

/**
 * Scales and tone-maps the decoded frame into bitmap (RGBA_8888 or RGB_565). The bitmap's size is the output size.
 * rowSkip: pre-decimate rows by 2, for >= 4x vertical downscales. 0 or a negative error.
 */
JNIEXPORT jint JNICALL
Java_com_nuvio_tv_core_player_thumbnail_ThumbNative_nativeRender(JNIEnv *env, jclass, jlong handle, jobject bitmap,
                                                                  jint transfer, jboolean bt2020, jboolean fullRange,
                                                                  jfloat peakNits, jboolean rowSkip) {
    auto *j = reinterpret_cast<JHandle *>(handle);
    if (!j) return -EINVAL;
    AndroidBitmapInfo bi;
    if (AndroidBitmap_getInfo(env, bitmap, &bi) != ANDROID_BITMAP_RESULT_SUCCESS) return -EINVAL;
    const bool rgb565 = bi.format == ANDROID_BITMAP_FORMAT_RGB_565;
    if (!rgb565 && bi.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return -EINVAL;
    void *pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) return -EINVAL;
    int r = j->b->render(j->h, static_cast<uint8_t *>(pixels), static_cast<int>(bi.stride), static_cast<int>(bi.width),
                         static_cast<int>(bi.height), rgb565, transfer, bt2020, fullRange, peakNits, rowSkip);
    AndroidBitmap_unlockPixels(env, bitmap);
    return r;
}

JNIEXPORT void JNICALL
Java_com_nuvio_tv_core_player_thumbnail_ThumbNative_nativeTrim(JNIEnv *, jclass, jlong handle) {
    auto *j = reinterpret_cast<JHandle *>(handle);
    if (j) j->b->trim(j->h);
}

/** Asks the allocator to return freed pages to the system (mallopt M_PURGE, Android 9+). No-op where absent. */
JNIEXPORT void JNICALL
Java_com_nuvio_tv_core_player_thumbnail_ThumbNative_nativePurge(JNIEnv *, jclass) {
    using mallopt_fn = int (*)(int, int);
    static const auto fn = reinterpret_cast<mallopt_fn>(dlsym(RTLD_DEFAULT, "mallopt"));
    constexpr int kMPurge = -101;   // bionic <malloc.h> M_PURGE
    if (fn) fn(kMPurge, 0);
}

JNIEXPORT void JNICALL
Java_com_nuvio_tv_core_player_thumbnail_ThumbNative_nativeRelease(JNIEnv *, jclass, jlong handle) {
    auto *j = reinterpret_cast<JHandle *>(handle);
    if (!j) return;
    j->b->release(j->h);
    delete j;
}

}  // extern "C"
