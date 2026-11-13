#!/usr/bin/env bash
# Builds third_party/libdvfelplay (Fire TV Cube 3 FEL helper) and installs it as
# app/src/dvfel/jniLibs/armeabi-v7a/libdvfelplay.so.
#
# Recipe (see third_party/libdvfelplay/README.md): NDK 28.2.13676358, armeabi-v7a, API 26,
# c++_static, libdovi from DV7/libdovi/android-armeabi-v7a, no kernel module embedded,
# target dvfelplay only.
#
# NDK lookup: $ANDROID_NDK, then $ANDROID_NDK_HOME, then <sdk>/ndk/$NDK_VERSION where <sdk> is
# $ANDROID_HOME, $ANDROID_SDK_ROOT, sdk.dir from local.properties, or ~/Android/Sdk.
set -euo pipefail

NDK_VERSION="${NDK_VERSION:-28.2.13676358}"
ABI=armeabi-v7a
API=26

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$ROOT/third_party/libdvfelplay"
LIBDOVI="$ROOT/DV7/libdovi/android-$ABI"
BUILD="${BUILD_DIR:-$ROOT/build/dvfelplay-$ABI}"
OUT_DIR="$ROOT/app/src/dvfel/jniLibs/$ABI"

sdk_dir() {
  if [[ -n "${ANDROID_HOME:-}" ]]; then echo "$ANDROID_HOME"; return; fi
  if [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then echo "$ANDROID_SDK_ROOT"; return; fi
  if [[ -f "$ROOT/local.properties" ]]; then
    local d
    d="$(sed -n 's/^sdk\.dir=//p' "$ROOT/local.properties" | tail -n1)"
    if [[ -n "$d" ]]; then echo "$d"; return; fi
  fi
  echo "$HOME/Android/Sdk"
}

SDK="$(sdk_dir)"
NDK="${ANDROID_NDK:-${ANDROID_NDK_HOME:-$SDK/ndk/$NDK_VERSION}}"
TOOLCHAIN="$NDK/build/cmake/android.toolchain.cmake"
[[ -f "$TOOLCHAIN" ]] || { echo "NDK not found at $NDK (want $NDK_VERSION)" >&2; exit 1; }
if [[ -f "$NDK/source.properties" ]] && ! grep -q "Pkg.Revision = $NDK_VERSION" "$NDK/source.properties"; then
  echo "warning: $NDK is not NDK $NDK_VERSION" >&2
fi
[[ -f "$LIBDOVI/lib/libdovi.a" ]] || { echo "libdovi not found at $LIBDOVI" >&2; exit 1; }

# cmake and ninja: PATH first, then the newest SDK CMake
CMAKE="$(command -v cmake || true)"
NINJA="$(command -v ninja || true)"
if [[ -z "$CMAKE" || -z "$NINJA" ]]; then
  SDK_CMAKE="$(ls -d "$SDK"/cmake/*/bin 2>/dev/null | sort -V | tail -n1 || true)"
  [[ -z "$CMAKE" && -x "$SDK_CMAKE/cmake" ]] && CMAKE="$SDK_CMAKE/cmake"
  [[ -z "$NINJA" && -x "$SDK_CMAKE/ninja" ]] && NINJA="$SDK_CMAKE/ninja"
fi
[[ -n "$CMAKE" ]] || { echo "cmake not found" >&2; exit 1; }
[[ -n "$NINJA" ]] || { echo "ninja not found" >&2; exit 1; }

rm -rf "$BUILD"
# Only CMakeLists.txt, include/ and src/ are vendored, but upstream's CMakeLists also declares
# felbench (tools/felbench.cpp), and CMake refuses to configure a target with a missing source.
# Configure a staged copy with an empty placeholder; felbench itself is never built.
STAGE="$BUILD/src"
mkdir -p "$STAGE/tools"
cp -R "$SRC/CMakeLists.txt" "$SRC/include" "$SRC/src" "$STAGE/"
: > "$STAGE/tools/felbench.cpp"
"$CMAKE" -S "$STAGE" -B "$BUILD/out" -G Ninja \
  -DCMAKE_MAKE_PROGRAM="$NINJA" \
  -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
  -DANDROID_ABI="$ABI" \
  -DANDROID_PLATFORM="android-$API" \
  -DANDROID_STL=c++_static \
  -DCMAKE_BUILD_TYPE=Release \
  -DLIBDOVI_DIR="$LIBDOVI" \
  -DDVFEL_KO= \
  -DDVFEL_KO_SC2= \
  -DDVFP_BUILD_TEST=OFF
"$CMAKE" --build "$BUILD/out" --target dvfelplay

LIB="$BUILD/out/libdvfelplay.so"
STRIP="$(ls "$NDK"/toolchains/llvm/prebuilt/*/bin/llvm-strip | head -n1)"
NM="$(ls "$NDK"/toolchains/llvm/prebuilt/*/bin/llvm-nm | head -n1)"

# only dvfp_* may be exported
EXTRA="$("$NM" -D --defined-only "$LIB" | awk '{print $NF}' | grep -v '^dvfp_' || true)"
if [[ -n "$EXTRA" ]]; then
  echo "unexpected exported symbols:" >&2
  echo "$EXTRA" >&2
  exit 1
fi

mkdir -p "$OUT_DIR"
"$STRIP" --strip-unneeded -o "$OUT_DIR/libdvfelplay.so" "$LIB"
echo "built $OUT_DIR/libdvfelplay.so"
"$NM" -D --defined-only "$OUT_DIR/libdvfelplay.so" | awk '{print "  " $NF}'
