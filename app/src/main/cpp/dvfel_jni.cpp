// JNI shim for libdvfelplay (third_party/libdvfelplay, prebuilt as src/dvfel/jniLibs/armeabi-v7a/libdvfelplay.so):
// Dolby Vision Profile 7 FEL on the rooted Fire TV Cube 3 (Gazelle) with the dvfel kernel helper.
//
// libdvfelplay is loaded with dlopen at run time, never linked, because it targets API 26 and Nuvio's
// minSdk is 24. The Kotlin side (core/player/dvfel/DvFelPlay.kt) only loads this shim on a Gazelle board.
//
// All calls that take a handle must come from one thread (ExoPlayer's playback thread).

#include <jni.h>
#include <dlfcn.h>
#include <stdint.h>
#include <string.h>
#include <android/log.h>

#define TAG "NuvioDvFel"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

namespace {

// API version 1 types, copied from dvfelplay.h (field order must stay exact).
typedef struct dvfp dvfp;
struct dvfp_rpu_info {
  int has_rpu;
  int profile;
  int el_type;
  int has_el;
};
struct dvfp_stats {
  uint64_t composed, mapped_only, passthrough, no_el_skipped, el_decoded,
      el_inputs, el_outputs, el_overflow, no_crc_match, stale;
  double gpu_ms_avg, gpu_ms_max;
};
constexpr int kStatsCount = 10;

struct Api {
  int (*api_version)();
  int (*available)();
  const char* (*version)();
  const char* (*module_version)(char*, size_t);
  int (*probe)(const uint8_t*, size_t, dvfp_rpu_info*);
  dvfp* (*open)(int, int, int);
  void (*close)(dvfp*);
  int (*mode)(const dvfp*);
  int (*filter)(dvfp*, const uint8_t*, size_t, const uint8_t**, size_t*);
  void (*commit)(dvfp*, int64_t);
  void (*drop)(dvfp*);
  void (*flush)(dvfp*);
  void (*get_stats)(const dvfp*, dvfp_stats*);
};

Api g{};
// 0 not tried, 1 loaded, -1 failed
int g_state = 0;

bool Load() {
  void* lib = dlopen("libdvfelplay.so", RTLD_NOW | RTLD_LOCAL);
  if (!lib) {
    LOGW("dlopen(libdvfelplay.so) failed: %s", dlerror());
    return false;
  }
#define SYM(n) (g.n = reinterpret_cast<decltype(g.n)>(dlsym(lib, "dvfp_" #n)))
  const bool ok = SYM(api_version) && SYM(available) && SYM(version) && SYM(module_version) &&
                  SYM(probe) && SYM(open) && SYM(close) && SYM(mode) && SYM(filter) &&
                  SYM(commit) && SYM(drop) && SYM(flush) && SYM(get_stats);
#undef SYM
  if (!ok) {
    LOGW("libdvfelplay.so is missing a dvfp_* symbol");
    dlclose(lib);
    g = Api{};
    return false;
  }
  const int api = g.api_version();
  if (api != 1) {
    LOGW("libdvfelplay API version %d, need 1", api);
    dlclose(lib);
    g = Api{};
    return false;
  }
  LOGI("libdvfelplay %s loaded", g.version() ? g.version() : "?");
  return true;
}

inline bool Loaded() { return g_state == 1; }
inline dvfp* H(jlong h) { return reinterpret_cast<dvfp*>(static_cast<intptr_t>(h)); }

uint8_t* DirectBase(JNIEnv* env, jobject buf, jlong* cap) {
  if (!buf) return nullptr;
  auto* base = static_cast<uint8_t*>(env->GetDirectBufferAddress(buf));
  *cap = env->GetDirectBufferCapacity(buf);
  return (base && *cap >= 0) ? base : nullptr;
}

}  // namespace

#define JNI_FN(ret, name) \
  extern "C" JNIEXPORT ret JNICALL Java_com_nuvio_tv_core_player_dvfel_DvFelPlay_##name

JNI_FN(jboolean, nativeLoad)(JNIEnv*, jclass) {
  if (g_state == 0) g_state = Load() ? 1 : -1;
  return Loaded() ? JNI_TRUE : JNI_FALSE;
}

JNI_FN(jboolean, nativeAvailable)(JNIEnv*, jclass) {
  return (Loaded() && g.available() == 1) ? JNI_TRUE : JNI_FALSE;
}

JNI_FN(jstring, nativeLibVersion)(JNIEnv* env, jclass) {
  const char* v = Loaded() ? g.version() : nullptr;
  return env->NewStringUTF(v ? v : "");
}

JNI_FN(jstring, nativeModuleVersion)(JNIEnv* env, jclass) {
  char buf[64] = {0};
  if (Loaded()) g.module_version(buf, sizeof(buf));
  buf[sizeof(buf) - 1] = 0;
  return env->NewStringUTF(buf);
}

JNI_FN(jint, nativeProbe)(JNIEnv* env, jclass, jobject buf, jint offset, jint length, jintArray out) {
  if (!Loaded()) return -100;
  jlong cap = 0;
  uint8_t* base = DirectBase(env, buf, &cap);
  if (!base) return -3;
  if (offset < 0 || length < 0 || static_cast<jlong>(offset) + length > cap) return -4;
  if (!out || env->GetArrayLength(out) < 4) return -5;
  dvfp_rpu_info info{};
  const int r = g.probe(base + offset, static_cast<size_t>(length), &info);
  const jint vals[4] = {info.has_rpu, info.profile, info.el_type, info.has_el};
  env->SetIntArrayRegion(out, 0, 4, vals);
  return r;
}

JNI_FN(jlong, nativeOpen)(JNIEnv*, jclass, jint width, jint height, jint mode) {
  if (!Loaded()) return 0;
  return static_cast<jlong>(reinterpret_cast<intptr_t>(g.open(width, height, mode)));
}

JNI_FN(jint, nativeMode)(JNIEnv*, jclass, jlong h) {
  if (!Loaded() || !h) return -1;
  return g.mode(H(h));
}

// Filters one access unit in place: [offset, offset + length) of the direct buffer [buf] is replaced by
// libdvfelplay's output. Returns the new length, -1 filter error, -2 output larger than the buffer,
// -3 not a direct buffer, -4 bad range.
JNI_FN(jint, nativeFilterInPlace)(JNIEnv* env, jclass, jlong h, jobject buf, jint offset, jint length) {
  if (!Loaded() || !h) return -1;
  jlong cap = 0;
  uint8_t* base = DirectBase(env, buf, &cap);
  if (!base) return -3;
  if (offset < 0 || length < 0 || static_cast<jlong>(offset) + length > cap) return -4;
  uint8_t* in = base + offset;
  const uint8_t* out = nullptr;
  size_t n = 0;
  if (g.filter(H(h), in, static_cast<size_t>(length), &out, &n) < 0) return -1;
  if (out != in) {
    if (static_cast<jlong>(n) > cap - offset) {
      g.drop(H(h));
      return -2;
    }
    memmove(in, out, n);
  }
  return static_cast<jint>(n);
}

JNI_FN(void, nativeCommit)(JNIEnv*, jclass, jlong h, jlong ptsUs) {
  if (Loaded() && h) g.commit(H(h), static_cast<int64_t>(ptsUs));
}

JNI_FN(void, nativeDrop)(JNIEnv*, jclass, jlong h) {
  if (Loaded() && h) g.drop(H(h));
}

JNI_FN(void, nativeFlush)(JNIEnv*, jclass, jlong h) {
  if (Loaded() && h) g.flush(H(h));
}

JNI_FN(void, nativeClose)(JNIEnv*, jclass, jlong h) {
  if (Loaded() && h) g.close(H(h));
}

// The 10 uint64_t counters of dvfp_stats, in declaration order.
JNI_FN(void, nativeStats)(JNIEnv* env, jclass, jlong h, jlongArray out) {
  if (!Loaded() || !h || !out || env->GetArrayLength(out) < kStatsCount) return;
  dvfp_stats s{};
  g.get_stats(H(h), &s);
  const jlong vals[kStatsCount] = {
      static_cast<jlong>(s.composed),   static_cast<jlong>(s.mapped_only),
      static_cast<jlong>(s.passthrough), static_cast<jlong>(s.no_el_skipped),
      static_cast<jlong>(s.el_decoded), static_cast<jlong>(s.el_inputs),
      static_cast<jlong>(s.el_outputs), static_cast<jlong>(s.el_overflow),
      static_cast<jlong>(s.no_crc_match), static_cast<jlong>(s.stale)};
  env->SetLongArrayRegion(out, 0, kStatsCount, vals);
}
