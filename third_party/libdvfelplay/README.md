# libdvfelplay (vendored)

Dolby Vision Profile 7 FEL playback helper for the rooted Fire TV Cube 3 (Gazelle), used by the
`NATIVE_FEL_CUBE3` DV7 mode (`app/src/main/java/com/nuvio/tv/core/player/dvfel`).

- Upstream: https://github.com/signde/vlc-fel-android (`libdvfelplay/`)
- Tag: `v0.1.0-fc3-experimental`
- Commit: `58b7b69259c9ed1839ec6247a562cfcbda751175`
- Licence: GPL-2.0-or-later (see `LICENSE`)

Only `CMakeLists.txt`, `include/` and `src/` are copied, unchanged. Nuvio does **not** build this
library with its own CMake project (Nuvio is `minSdk 24`; the library needs API 26). It is prebuilt
with `scripts/build-dvfelplay.sh` (or the "Build libdvfelplay" workflow) and shipped as
`app/src/dvfel/jniLibs/armeabi-v7a/libdvfelplay.so`. The app loads it at run time through
`libnuvio_dvfel.so` (`app/src/main/cpp/dvfel_jni.cpp`), and only on a Gazelle board. Both libraries
are packaged only in the separate FEL APK (`-PDVFEL_GAZELLE_ENABLED=true`), never in the normal one.

Release build recipe (matches upstream's `docs/BUILD.Gazelle.md` at the tag above):

- NDK 28.2.13676358, `armeabi-v7a`, API 26, `c++_static`
- libdovi 3.3.2 (`DV7/libdovi/android-armeabi-v7a`)
- `DVFEL_KO` and `DVFEL_KO_SC2` empty: no kernel module is embedded
- target `dvfelplay` only (skips the `felbench` tool)

The app never calls `dvfp_module_install` or `dvfp_set_demo`.
