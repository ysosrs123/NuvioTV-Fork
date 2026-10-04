## Current FFmpeg AAR (0.9.3-beta-nt1)

`lib-decoder-ffmpeg-release.aar` SHA-256:
`24c571cb833b1dd38c127b94d63d43e74b31ff2c5c5ebcfe8535dd12e404cdb6`.

The shipped `lib-decoder-ffmpeg-release.aar` was built from the module sources in
`ffmpeg-aar-src/0.9.3-beta-nt1/` and FFmpeg n7.0.2 (https://github.com/FFmpeg/FFmpeg/tree/n7.0.2),
with the configure line in `.github/workflows/build-ffmpeg-decoder.yml`. FFmpeg is built without
`--enable-gpl` and `--enable-nonfree` and is used under the LGPL 2.1 or later.
The `ffmpeg-decoder-downmix` module holds newer source that is not in this binary yet.

`ffmpeg-aar-src/0.9.3-beta-nt1/` holds the three module files that have changed since this binary
was built (`ffmpeg_jni.cc`, `FfmpegAudioRenderer.java`, `FfmpegAudioDecoder.java`). The other module
files (`CMakeLists.txt`, `build_ffmpeg.sh`, `build.gradle.kts`, `FfmpegLibrary.java`,
`FfmpegDecoderException.java`, `package-info.java`, `NOTICE.md`) are the same as in
`ffmpeg-decoder-downmix`. These files are kept for reference and are not compiled into the app.

Combines official0.9.3 downmix limiter / multi-frame buffer-growth corrections with fork per-MIME denied-codec AC-3 routing and legal-rate high-sample-rate AC-3 resampling. Limiter timing on the planar transcode path uses the output encoder rate.

Native inputs: FFmpeg7.0.2 release archive (release signature verified, key fingerprint `FCF986EA15E6E293A5644F10B4322F04D67658D8`), Android NDK27.0.12077973, minimumAPI24. Decoder list follows `.github/workflows/build-ffmpeg-decoder.yml`, including the AC-3 encoder. ARMv7/ARM64/x86/x86_64 are included;16KB ELF LOAD alignment and Android system dependencies verified. Public Java API retains all entries from the previous fork AAR. The module's copyright, NOTICE and licences apply.

Host ASan/UBSan tests cover actual JNI decode/transcode code; they do not establish Android passthrough, MAT/DTS/DV or device performance acceptance.

The following text records the previous binary and its reconstruction; its statement that native code exists only in a binary is historical and does not describe this source-built artifact.

The binary ffmpeg extension was build with following decoders:

```
ENABLED_DECODERS=(vorbis opus flac alac pcm_mulaw pcm_alaw mp3 amrnb amrwb aac ac3 eac3 dca mlp truehd)
```

Complete [build instructions](https://github.com/androidx/media/blob/release/libraries/decoder_ffmpeg/README.md).

To assemble ``.aar``:

```
./gradlew :extension-ffmpeg:bundleReleaseAar
```

## Fork modification: lib-decoder-ffmpeg-release.aar (2026-08)

`FfmpegAudioRenderer.class` inside `classes.jar` was rebuilt from the reconstruction
source at `ffmpeg-aar-src/FfmpegAudioRenderer.java` (CFR 0.152 decompilation of the
original class, plus one change, `setDeniedTranscodeMimes`: the AC-3 transcode
decision is now per-MIME, consulting an app-supplied denied set as well as the global
`forceOpticalPassthrough` flag). With the set empty the behaviour is equivalent to the
original. All other AAR entries, including every `libffmpegJNI.so`, are byte-identical
to the previous AAR. The custom audio pipeline (transcode/downmix, Java + native) is
the upstream NuvioTV developer's private work, present only in this binary; the
reconstruction source is provided as corresponding source for the modified class.

Rebuild recipe (JDK 21, CFR 0.152, media3-decoder 1.8.0 sources, android.jar API 34):
compile `ffmpeg-aar-src/FfmpegAudioRenderer.java` with `--release 11 -g` against
{android.jar, lib-common classes.jar, lib-exoplayer classes.jar, this AAR's
classes.jar, media3-decoder 1.8.0 classes, androidx-annotation, guava}; `jar uf` the
resulting class into `classes.jar`; `zip` it back into the AAR.
