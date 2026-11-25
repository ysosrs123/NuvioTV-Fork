// SPDX-License-Identifier: GPL-2.0-or-later
// Ported from Wholphin FEL (signde/Wholphin, native/wholphinfel.cpp) for Nuvio Arc on Fire TV Cube 3.
#include "annexb.h"
#include <android/log.h>
#include <cstring>
#include <dvfelplay.h>
#include <jni.h>

struct Session {
  int width, height;
  dvfp *fel = nullptr;
  bool classified = false;
  bool pending = false;
  ~Session() {
    if (fel)
      dvfp_close(fel);
  }
};
static void fail(JNIEnv *env, const char *message) {
  env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_nuvio_tv_core_player_firetvfel_FireTvFel_nativeAvailable(JNIEnv *,
                                                                    jobject) {
  return dvfp_available() == 1;
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_nuvio_tv_core_player_firetvfel_FireTvFel_nativeCreate(JNIEnv *,
                                                                 jobject,
                                                                 jint w,
                                                                 jint h) {
  return reinterpret_cast<jlong>(new Session{w, h});
}
extern "C" JNIEXPORT jint JNICALL
Java_com_nuvio_tv_core_player_firetvfel_FireTvFel_nativePrepare(
    JNIEnv *env, jobject, jlong ptr, jobject buffer, jint offset, jint size) {
  auto *s = reinterpret_cast<Session *>(ptr);
  auto *base = static_cast<uint8_t *>(env->GetDirectBufferAddress(buffer));
  auto cap = env->GetDirectBufferCapacity(buffer);
  if (!s || !base || offset < 0 || size < 0 || offset > cap ||
      size > cap - offset) {
    fail(env, "Invalid FEL input buffer");
    return 0;
  }
  auto *bytes = base + offset;
  if (!s->classified) {
    dvfp_rpu_info info{};
    if (dvfp_probe(bytes, size, &info) != 0) {
      fail(env, "Invalid Dolby Vision RPU");
      return 0;
    }
    if (!info.has_rpu)
      return size;
    if (info.profile != 7) {
      fail(env, "Expected original Profile 7 RPU");
      return 0;
    }
    if (info.el_type == DVFP_EL_FEL) {
      s->fel = dvfp_open(s->width, s->height, DVFP_MODE_FEL);
      if (!s->fel) {
        fail(env, "Fire TV FEL session could not start");
        return 0;
      }
    } else if (info.el_type != DVFP_EL_MEL) {
      fail(env, "Unknown Profile 7 enhancement layer");
      return 0;
    }
    s->classified = true;
    __android_log_print(
        ANDROID_LOG_INFO, "NuvioFireTvFel", "Original P7 %s, %dx%d; RPU preserved",
        s->fel ? "FEL hardware composition" : "MEL", s->width, s->height);
  }
  if (!s->fel)
    return nuviofel::stripEl(bytes, size);
  const uint8_t *out = nullptr;
  size_t outSize = 0;
  if (dvfp_filter(s->fel, bytes, size, &out, &outSize) != 0 || !out ||
      outSize > static_cast<size_t>(cap - offset)) {
    dvfp_drop(s->fel);
    fail(env, "FEL input filtering failed");
    return 0;
  }
  memmove(bytes, out, outSize);
  s->pending = true;
  return static_cast<jint>(outSize);
}
extern "C" JNIEXPORT void JNICALL
Java_com_nuvio_tv_core_player_firetvfel_FireTvFel_nativeCommit(JNIEnv *,
                                                                 jobject,
                                                                 jlong ptr,
                                                                 jlong pts) {
  auto *s = reinterpret_cast<Session *>(ptr);
  if (s && s->fel && s->pending) {
    dvfp_commit(s->fel, pts);
    s->pending = false;
  }
}
extern "C" JNIEXPORT void JNICALL
Java_com_nuvio_tv_core_player_firetvfel_FireTvFel_nativeFlush(JNIEnv *,
                                                                jobject,
                                                                jlong ptr) {
  auto *s = reinterpret_cast<Session *>(ptr);
  if (s && s->fel) {
    dvfp_drop(s->fel);
    s->pending = false;
    dvfp_flush(s->fel);
  }
}
extern "C" JNIEXPORT void JNICALL
Java_com_nuvio_tv_core_player_firetvfel_FireTvFel_nativeClose(JNIEnv *,
                                                                jobject,
                                                                jlong ptr) {
  delete reinterpret_cast<Session *>(ptr);
}
