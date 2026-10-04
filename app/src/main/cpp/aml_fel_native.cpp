// Native Dolby Vision Profile 7 FEL output for Amlogic boxes through the legacy amstream dual-layer
// port /dev/amstream_dves_hevc.
//
// The port takes the unconverted P7 elementary stream (Annex-B: BL VCL + type-63-wrapped EL + type-62
// RPU) and runs the BL and EL decoders itself; the Dolby core composes both on VD1/VD2. Each sample
// carries a 64-bit microsecond PTS. No end-of-stream is sent: AMSTREAM_SET_EOS wedges the EL decoder
// on this port. Presentation follows the tsync system clock (pcrscr, 90 kHz), which the renderer
// slaves to the audio clock; pause/resume use AMSTREAM_IOC_VPAUSE on /dev/amvideo.
//
// Non-blocking: queueSample() copies one sample, drain() writes as much as the stream buffer accepts.
//
// attachSideband() puts an Amlogic sideband handle on the player's SurfaceView so the HWC shows VD1
// in that rect. It is attached through the window's perform(NATIVE_WINDOW_SET_SIDEBAND_STREAM) hook,
// reached at a fixed offset of the ANativeWindow struct (layout checked at compile time, magic and
// version at run time).
#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <errno.h>
#include <fcntl.h>
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <unistd.h>
#include <new>
#include <vector>

#define LOG_TAG "AmlFelNative"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

constexpr const char *kDvesHevc = "/dev/amstream_dves_hevc";
constexpr const char *kAmvideo = "/dev/amvideo";

// ioctl numbers (magic 'S'); identical to vcodec.c.
constexpr unsigned long kIocSysinfo = 0x4004530a;     // _IOW('S', 0x0a, int)
constexpr unsigned long kIocVbStatus = 0x80045308;    // _IOR('S', 0x08, int)
constexpr unsigned long kIocVpause = 0x40045317;      // _IOW('S', 0x17, int), on /dev/amvideo
constexpr unsigned long kIocGet = 0xc01053c1;         // _IOWR('S', 0xc1, struct am_ioctl_parm)
constexpr unsigned long kIocSet = 0x401053c2;         // _IOW('S', 0xc2, struct am_ioctl_parm)

// AMSTREAM_SET_* / AMSTREAM_GET_* (include/linux/amlogic/media/utils/amstream.h)
constexpr uint32_t kSetVformat = 0x105;
constexpr uint32_t kSetVid = 0x107;
constexpr uint32_t kSetTstampUs64 = 0x10f;
constexpr uint32_t kPortInit = 0x111;
constexpr uint32_t kSetPcrscr = 0x118;
constexpr uint32_t kSetDrmmode = 0x11c;
constexpr uint32_t kSetDvMetaWithEl = 0x179;
constexpr uint32_t kGetVpts = 0x805;
constexpr uint32_t kVformatHevc = 11;      // VFORMAT_HEVC
constexpr uint32_t kDecFormatHevc = 15;    // VIDEO_DEC_FORMAT_HEVC
constexpr uint32_t kExternalPts = 1;       // dec_sysinfo.param: timestamps come from the app

struct SetParam { uint64_t data; uint32_t cmd; uint32_t reserved; };
struct SysInfo { uint32_t format, width, height, rate, extra, status, ratio; void *param; uint64_t ratio64; };
static_assert(sizeof(SetParam) == 16, "am_ioctl_parm is 16 bytes");

struct Session {
    int fd = -1;
    int cntl = -1;          // /dev/amvideo, opened lazily for pause/resume
    bool paused = false;
    std::vector<uint8_t> pending;
    size_t pendingOffset = 0;
    size_t pendingLength = 0;
    int64_t pendingPtsUs = 0;
    bool pendingPtsSet = false;
};

int setParam(int fd, uint32_t cmd, uint64_t data) {
    SetParam p{data, cmd, 0};
    if (ioctl(fd, kIocSet, &p) < 0) {
        int e = errno;
        LOGW("AMSTREAM_IOC_SET 0x%x failed: %s", cmd, strerror(e));
        return -e;
    }
    return 0;
}

Session *toSession(jlong handle) { return reinterpret_cast<Session *>(static_cast<uintptr_t>(handle)); }

// --- sideband ---

// AOSP system/window.h + nativebase.h (android14-release): the platform ANativeWindow behind the NDK's
// opaque handle. Only `common` and `perform` are used; the other members pin the layout.
struct NativeBase {
    int magic;
    int version;
    void *reserved[4];
    void (*incRef)(NativeBase *);
    void (*decRef)(NativeBase *);
};
struct NativeWindowLayout {
    NativeBase common;
    uint32_t flags;
    int minSwapInterval;
    int maxSwapInterval;
    float xdpi;
    float ydpi;
    intptr_t oem[4];
    void *setSwapInterval;
    void *dequeueBufferDeprecated;
    void *lockBufferDeprecated;
    void *queueBufferDeprecated;
    void *query;
    int (*perform)(NativeWindowLayout *window, int operation, ...);
    void *cancelBufferDeprecated;
    void *dequeueBuffer;
    void *queueBuffer;
    void *cancelBuffer;
};
static_assert(offsetof(NativeWindowLayout, perform) == (sizeof(void *) == 8 ? 152 : 88), "perform offset");
static_assert(sizeof(NativeWindowLayout) == (sizeof(void *) == 8 ? 192 : 108), "ANativeWindow size");

constexpr int kNativeWindowMagic = ('_' << 24) | ('w' << 16) | ('n' << 8) | 'd';
constexpr int kSetSidebandStream = 18;  // NATIVE_WINDOW_SET_SIDEBAND_STREAM

// native_handle_t with 1 fd and 3 ints = am_sideband_handle_t (libamgralloc_ext).
struct SidebandNativeHandle {
    int version;  // sizeof(native_handle_t) = 12
    int numFds;
    int numInts;
    int fakeFd;
    uint32_t id;
    int flags;
    int channel;
};
static_assert(sizeof(SidebandNativeHandle) == 28, "native_handle_t + 1 fd + 3 ints");

constexpr uint32_t kSidebandId = 0xabcdcdef;  // AM_SIDEBAND_IDENTIFIER

// AM_TV_SIDEBAND(1) / AM_OMX_SIDEBAND(2) / AM_AMCODEX_SIDEBAND(3) -> private_handle_t PRIV_FLAGS_VIDEO_*.
int sidebandFlags(int type) {
    switch (type) {
        case 1: return 0x10;  // VIDEO_OVERLAY
        case 2: return 0x20;  // VIDEO_OMX
        case 3: return 0x40;  // VIDEO_AMCODEX
        default: return 0;
    }
}

struct Sideband {
    ANativeWindow *window = nullptr;
    SidebandNativeHandle handle{};
};

Sideband *toSideband(jlong handle) { return reinterpret_cast<Sideband *>(static_cast<uintptr_t>(handle)); }

}  // namespace

extern "C" {

// Bit 0: dual-layer port writable; bit 1: /dev/amvideo openable for pause; bit 2: amdolby_vision present.
JNIEXPORT jint JNICALL
Java_com_nuvio_tv_core_player_amlfel_AmlFelNative_nativeProbeDevice(JNIEnv *, jclass) {
    jint bits = 0;
    if (access(kDvesHevc, W_OK) == 0) bits |= 1;
    if (access(kAmvideo, R_OK | W_OK) == 0) bits |= 2;
    struct stat st{};
    if (stat("/sys/class/amdolby_vision", &st) == 0) bits |= 4;
    return bits;
}

// Returns a session handle (> 0) or -errno. rate = 96000 / fps (e.g. 4004 for 23.976).
JNIEXPORT jlong JNICALL
Java_com_nuvio_tv_core_player_amlfel_AmlFelNative_nativeOpen(
        JNIEnv *, jclass, jint width, jint height, jint rate, jint metaWithEl) {
    int fd = open(kDvesHevc, O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) {
        int e = errno;
        LOGW("open %s failed: %s", kDvesHevc, strerror(e));
        return -e;
    }
    int r = 0;
    do {
        if ((r = setParam(fd, kSetVformat, kVformatHevc)) < 0) break;
        if ((r = setParam(fd, kSetVid, 0)) < 0) break;
        SysInfo si{kDecFormatHevc, static_cast<uint32_t>(width), static_cast<uint32_t>(height),
                   static_cast<uint32_t>(rate > 0 ? rate : 4004), 0, 0, 0,
                   reinterpret_cast<void *>(static_cast<uintptr_t>(kExternalPts)), 0};
        if (ioctl(fd, kIocSysinfo, &si) < 0) { r = -errno; LOGW("SYSINFO failed: %s", strerror(-r)); break; }
        if ((r = setParam(fd, kSetDrmmode, 0)) < 0) break;
        if ((r = setParam(fd, kPortInit, 0)) < 0) break;
        if (metaWithEl >= 0 && (r = setParam(fd, kSetDvMetaWithEl, static_cast<uint64_t>(metaWithEl))) < 0) break;
    } while (false);
    if (r < 0) {
        close(fd);
        return r;
    }
    Session *s = new (std::nothrow) Session();
    if (!s) { close(fd); return -ENOMEM; }
    s->fd = fd;
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(s));
}

// Copies [offset, offset+length) of a direct ByteBuffer into the session as the next sample, checked in
// with ptsUs (microseconds) unless ptsUs < 0. Returns 0, -EBUSY if a previous sample is still pending,
// or -errno.
JNIEXPORT jint JNICALL
Java_com_nuvio_tv_core_player_amlfel_AmlFelNative_nativeQueueSample(
        JNIEnv *env, jclass, jlong handle, jobject buffer, jint offset, jint length, jlong ptsUs) {
    Session *s = toSession(handle);
    if (!s || s->fd < 0) return -EBADF;
    if (s->pendingOffset < s->pendingLength) return -EBUSY;
    auto *base = static_cast<uint8_t *>(env->GetDirectBufferAddress(buffer));
    jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (!base || offset < 0 || length <= 0 || length > 32 * 1024 * 1024 || static_cast<jlong>(offset) + length > capacity) return -EINVAL;
    if (s->pending.size() < static_cast<size_t>(length)) {
        try { s->pending.resize(static_cast<size_t>(length)); } catch (...) { return -ENOMEM; }
    }
    memcpy(s->pending.data(), base + offset, static_cast<size_t>(length));
    s->pendingOffset = 0;
    s->pendingLength = static_cast<size_t>(length);
    s->pendingPtsUs = ptsUs;
    s->pendingPtsSet = ptsUs < 0;  // negative = no timestamp (codec parameter sets)
    return 0;
}

// Writes as much of the pending sample as the stream buffer takes. Returns the bytes still pending
// (0 = sample complete) or -errno. The PTS is checked in right before the sample's first byte.
JNIEXPORT jint JNICALL
Java_com_nuvio_tv_core_player_amlfel_AmlFelNative_nativeDrain(JNIEnv *, jclass, jlong handle) {
    Session *s = toSession(handle);
    if (!s || s->fd < 0) return -EBADF;
    if (s->pendingOffset >= s->pendingLength) return 0;
    if (!s->pendingPtsSet) {
        int r = setParam(s->fd, kSetTstampUs64, static_cast<uint64_t>(s->pendingPtsUs));
        if (r < 0) return r;
        s->pendingPtsSet = true;
    }
    while (s->pendingOffset < s->pendingLength) {
        ssize_t w = write(s->fd, s->pending.data() + s->pendingOffset, s->pendingLength - s->pendingOffset);
        if (w > 0) {
            s->pendingOffset += static_cast<size_t>(w);
        } else if (w < 0 && errno == EINTR) {
            continue;
        } else if (w < 0 && errno != EAGAIN) {
            int e = errno;
            LOGW("write failed: %s", strerror(e));
            return -e;
        } else {
            break;  // EAGAIN (or 0): stream buffer full, retry on the next render call
        }
    }
    return static_cast<jint>(s->pendingLength - s->pendingOffset);
}

// pts90k is the 90 kHz system time (u32 wrap) in the same base as the checked-in sample PTS.
JNIEXPORT jint JNICALL
Java_com_nuvio_tv_core_player_amlfel_AmlFelNative_nativeSetPcrscr(JNIEnv *, jclass, jlong handle, jint pts90k) {
    Session *s = toSession(handle);
    if (!s || s->fd < 0) return -EBADF;
    return setParam(s->fd, kSetPcrscr, static_cast<uint32_t>(pts90k));
}

// Last displayed video PTS (90 kHz, u32) or -errno.
JNIEXPORT jlong JNICALL
Java_com_nuvio_tv_core_player_amlfel_AmlFelNative_nativeGetVpts(JNIEnv *, jclass, jlong handle) {
    Session *s = toSession(handle);
    if (!s || s->fd < 0) return -EBADF;
    SetParam p{0, kGetVpts, 0};
    if (ioctl(s->fd, kIocGet, &p) < 0) return -errno;
    return static_cast<jlong>(static_cast<uint32_t>(p.data));
}

// Stream buffer status into out[0..2] = size, data_len, free_len. Returns 0 or -errno.
JNIEXPORT jint JNICALL
Java_com_nuvio_tv_core_player_amlfel_AmlFelNative_nativeGetBufferStatus(
        JNIEnv *env, jclass, jlong handle, jintArray out) {
    Session *s = toSession(handle);
    if (!s || s->fd < 0) return -EBADF;
    // struct am_io_param { int data; int len; union { char buf[1]; struct buf_status status; ...}; }
    // The kernel copies its own (possibly larger) struct: use a generous buffer.
    int32_t raw[64] = {};
    if (ioctl(s->fd, kIocVbStatus, raw) < 0) return -errno;
    jint values[3] = {raw[2], raw[3], raw[4]};
    if (out && env->GetArrayLength(out) >= 3) env->SetIntArrayRegion(out, 0, 3, values);
    return 0;
}

// Pause (1) / resume (0) presentation through tsync VIDEO_PAUSE on /dev/amvideo.
JNIEXPORT jint JNICALL
Java_com_nuvio_tv_core_player_amlfel_AmlFelNative_nativeSetPaused(JNIEnv *, jclass, jlong handle, jboolean paused) {
    Session *s = toSession(handle);
    if (!s) return -EBADF;
    if (s->cntl < 0) {
        s->cntl = open(kAmvideo, O_RDWR | O_CLOEXEC);
        if (s->cntl < 0) {
            int e = errno;
            LOGW("open %s failed: %s", kAmvideo, strerror(e));
            return -e;
        }
    }
    if (ioctl(s->cntl, kIocVpause, paused ? 1 : 0) < 0) {
        int e = errno;
        LOGW("VPAUSE %d failed: %s", paused ? 1 : 0, strerror(e));
        return -e;
    }
    s->paused = paused;
    return 0;
}

// Always un-pauses tsync before closing (a leftover VIDEO_PAUSE would freeze every later player).
JNIEXPORT void JNICALL
Java_com_nuvio_tv_core_player_amlfel_AmlFelNative_nativeClose(JNIEnv *, jclass, jlong handle) {
    Session *s = toSession(handle);
    if (!s) return;
    if (s->cntl >= 0) {
        if (s->paused && ioctl(s->cntl, kIocVpause, 0) < 0) LOGW("VPAUSE 0 on close failed: %s", strerror(errno));
        close(s->cntl);
    }
    if (s->fd >= 0) close(s->fd);
    delete s;
}

// Attaches an Amlogic sideband stream (type 1..3, channel) to the Surface. Returns a handle (> 0) that
// keeps the window and the native handle alive until nativeDetachSideband, or -errno.
JNIEXPORT jlong JNICALL
Java_com_nuvio_tv_core_player_amlfel_AmlFelNative_nativeAttachSideband(
        JNIEnv *env, jclass, jobject surface, jint type, jint channel) {
    int flags = sidebandFlags(type);
    if (!surface || flags == 0) return -EINVAL;
    ANativeWindow *window = ANativeWindow_fromSurface(env, surface);
    if (!window) return -EINVAL;
    auto *layout = reinterpret_cast<NativeWindowLayout *>(window);
    if (layout->common.magic != kNativeWindowMagic ||
        layout->common.version != static_cast<int>(sizeof(NativeWindowLayout)) || !layout->perform) {
        LOGW("sideband: unexpected ANativeWindow (magic %x version %d); not attaching",
             layout->common.magic, layout->common.version);
        ANativeWindow_release(window);
        return -ENOSYS;
    }
    Sideband *sb = new (std::nothrow) Sideband();
    if (!sb) {
        ANativeWindow_release(window);
        return -ENOMEM;
    }
    int fd = open("/dev/null", O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        int e = errno;
        ANativeWindow_release(window);
        delete sb;
        return -e;
    }
    sb->window = window;
    sb->handle = SidebandNativeHandle{12, 1, 3, fd, kSidebandId, flags, channel};
    int r = layout->perform(layout, kSetSidebandStream, &sb->handle);
    if (r != 0) {
        LOGW("sideband: perform(SET_SIDEBAND_STREAM) failed: %d", r);
        close(fd);
        ANativeWindow_release(window);
        delete sb;
        return r < 0 ? r : -EIO;
    }
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(sb));
}

// Clears the sideband stream on the Surface (a no-op error if the surface is already gone) and frees it.
JNIEXPORT void JNICALL
Java_com_nuvio_tv_core_player_amlfel_AmlFelNative_nativeDetachSideband(JNIEnv *, jclass, jlong handle) {
    Sideband *sb = toSideband(handle);
    if (!sb) return;
    auto *layout = reinterpret_cast<NativeWindowLayout *>(sb->window);
    int r = layout->perform(layout, kSetSidebandStream, static_cast<SidebandNativeHandle *>(nullptr));
    if (r != 0) LOGW("sideband: detach returned %d", r);
    close(sb->handle.fakeFd);
    ANativeWindow_release(sb->window);
    delete sb;
}

}  // extern "C"
