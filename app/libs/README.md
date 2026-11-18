## Current FFmpeg AAR

`lib-decoder-ffmpeg-release.aar` SHA-256:
`472ad29e08fc0c5e12c0c89e96d1130ff0529eefba31e85d31b14dccaec7baa4`.

Built 24 Oct 2026 by `.github/workflows/build-ffmpeg-decoder.yml` from the current `ffmpeg-decoder-downmix`
module and FFmpeg n7.0.2 (https://github.com/FFmpeg/FFmpeg/tree/n7.0.2), NDK 27.0.12077973, for ARMv7, ARM64,
x86 and x86_64. FFmpeg is built without `--enable-gpl` and `--enable-nonfree` and is used under the LGPL 2.1 or
later. Native source and binary match again: this build adds the AC-3 encoder flush at the end of the stream and
the LFE mixed into stereo downmixes at -6 dB. 16 KB ELF LOAD alignment checked on every ABI; the library needs only
Android system libraries.

`ffmpeg-aar-src/0.9.3-beta-nt1/` holds the three module files of the previous binary for reference; they are not
compiled into the app.

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

## Fork modification: DTS Express mapping

`FfmpegLibrary.class` inside `classes.jar` was rebuilt from `ffmpeg-decoder-downmix` with one change:
`getCodecName` maps `audio/vnd.dts.hd;profile=lbr` (DTS Express) to the `dca` decoder, as Media3 1.11 does.
The `dca` decoder in every `libffmpegJNI.so` already decodes DTS Express. All other AAR entries, including
every `libffmpegJNI.so` and every other class, are byte-identical to the previous AAR
(`24c571cb833b1dd38c127b94d63d43e74b31ff2c5c5ebcfe8535dd12e404cdb6`).

Rebuild recipe (JDK 17): compile `FfmpegLibrary.java` with `--release 11 -g -XDstringConcat=inline` against
{lib-common classes.jar, androidx-annotation, checker-qual}; the unchanged source compiles to the exact bytes of
the previous class. Replace the class in `classes.jar` and put `classes.jar` back into the AAR.

## Fork modification: lib-exoplayer-release.aar (playback engine)

`lib-exoplayer-release.aar` SHA-256:
`82e9ca76a9c1d7881b53103ad29ef21e0324b61488a9d2148b6d8b6fafb44bdd`.

Base: the engine AAR built from halibiram/media 4dd1d98b9f (calloc sample chunks), with `DefaultAudioSink`
from halibiram/media #2 (TrueHD resync after a dropped chunk), SHA-256
`a3d513f0f1b53b474875bf96ad9db66fec1ebdf484be8b08b566f1cee0dd1bef`.

Frame release: `VideoFrameReleaseHelper` (with `$Api30`, `$DisplayHelper`, `$VSyncSampler`) and
`VideoFrameReleaseControl` are rebuilt from those sources with the media3 1.9.0 frame release fixes
5c9fd2366a, 49e2996647, ae3e5a4e5f, 5aa19e3142, be757e7e85 and 2656df9623 (vsync hysteresis, smoothing
kept through outliers and speed changes, reset at stream changes, vsync re-sampled after a display mode
change, `Surface.setFrameRate` only for changes of 0.1 fps or more). The vsync re-sample removes a pending
frame callback before posting one, as media3 does since fd8a6b2c57.

Audio clock: `AudioTrackPositionTracker`, `AudioTimestampPoller` (with `$AudioTimestampWrapper`) and
`DefaultAudioSink` are rebuilt from the same sources with bc0652cb54 (media3 1.11.0: the clock stops at the
written frames during an underrun and restarts from a fresh timestamp), 8ca4ef9db0 (media3 1.10.0: a new
timestamp is read when the output latency changes, latency limit 10 s). The playback head wrap check
stays as in 1.8.0.
`DefaultAudioSink` changes by one call; its inner classes are unchanged.

Codec reuse: `MediaCodecVideoRenderer` (with its inner classes, which change only in line numbers) and
`DecoderReuseEvaluation` are rebuilt with 65088aafd8 and 070291b7ec (media3 1.9.1 and 1.10.1, #2941,
#3120): below API 30, and on MiTV boxes on API 30, the video codec is replaced when the frame rate changes
to one that is not a whole multiple of the old one, unless the frame-rate strategy is off. Unlike upstream,
secure codecs are always kept, so `MediaCodecRenderer.canReuseCodec` keeps its 1.8.0 signature.

All other AAR entries, including every `libmedia3_exoplayer_jni.so`, and every other class are
byte-identical to the base AAR.

Rebuild recipe (JDK 17): compile the changed source files with `-source 8 -target 8 -g -proc:none` against
{android.jar API 35 and core-lambda-stubs as boot class path, this AAR's classes.jar, lib-common,
lib-datasource, lib-extractor, guava, androidx-annotation, checker-qual, error_prone_annotations}. The
unchanged sources compile to the base classes byte for byte, apart from one `MethodParameters` attribute
on `$DisplayHelper` and the constant pool order of `DefaultAudioSink`. `AudioTrackPositionTracker` and
`AudioTimestampPoller` then go through the typedef remover of the Android Gradle plugin
(`com.android.tools.lint:lint-typedef-remover`, typedefs `$PlayState` and `$State`), as in the library
build. Replace the classes in `classes.jar` and put `classes.jar` back into the AAR, keeping
entry order and timestamps.

## AV1 AAR: dav1d instead of libgav1

`lib-decoder-av1-release.aar` SHA-256:
`6d1d44eb871918a22e370c9625e29d4661fe818a28e26a61eee1554486a4df34`.

It is the media3 1.11.1 AV1 module (`Libdav1dVideoRenderer`, dav1d) taken from Just Player's
`app/libs/lib-decoder-av1-release.aar` (commit aa85148, SHA-256
`0110fb5d50431a4269ebc21c4295bd53abfe11f0158e563368a4a46150999d94`), with one class rebuilt so it
links against the media3 1.8.0 libraries used here. Every `libdav1dJNI.so`, the manifest,
`proguard.txt` and the other classes are byte-identical to that AAR; the other classes match a
compile of the media3 1.11.1 sources. The old AAR had the libgav1 module from media3 1.8.0.

`av1-aar-src/Dav1dDecoder.java` is media3 1.11.1 `Dav1dDecoder.java` without the decoder callback
(`Decoder.Callback`, added in media3 1.11.0, not in 1.8.0; it only wakes the player early and is
never set by the 1.8.0 renderer). It is kept for reference and is not compiled into the app.

Rebuild recipe (JDK 17): compile `av1-aar-src/Dav1dDecoder.java` together with the other media3
1.11.1 `decoder_av1` sources using `--release 8 -g` against {android.jar API 35, lib-common
classes.jar, lib-exoplayer classes.jar, media3-decoder 1.8.0, androidx-annotation, guava,
checker-qual}; put the new `Dav1dDecoder.class` into the source AAR's `classes.jar` and zip it back
into the AAR, keeping entry order and timestamps.

The native library is media3's JNI wrapper (Apache 2.0), dav1d (BSD 2-Clause) and cpu_features
(Apache 2.0). It needs no other shared library and its arm64 LOAD segments are 16 KB aligned.

## Fork modification: lib-extractor-release.aar

`lib-extractor-release.aar` SHA-256:
`6294942881a22669f9508085da0c6e1b94d8218d9f5e71fd64c466fba23a6d4a`.

Base: the extractor AAR built by halibiram from halibiram/media (with the MP4 subtitle seek fix 359711b60), SHA-256
`d611a11f7bc6cef11f789c1cae4fe75ab9332a9e393405e98cfa8e147d698316`.

`DefaultExtractorInput`, `Id3Peeker`, `ts/Ac3Extractor`, `ts/Ac4Extractor` and `ts/AdtsExtractor` are rebuilt
with 545659dc42 (media3 1.12.0, #3502): the peek buffer grows as bytes arrive instead of to the requested
length, an ID3 header with an invalid minor version or size is not treated as a tag, the tag is only read
into memory when its frames are wanted, and the three audio sniffers skip ID3 tags through `Id3Peeker`.
A file that starts with `ID3` followed by garbage no longer allocates up to 256 MB while sniffing.
Unlike upstream, `advancePeekPosition` grows by up to 1 MB before data arrives and then doubles with the
bytes already read, so large peeks (the Matroska early audio scan, HEVC SEI checks) cost what they did in
1.8.0 instead of copying the buffer once per 512 KB.

All other AAR entries and classes are byte-identical to the base AAR.

Rebuild recipe: as for `lib-exoplayer-release.aar` above, with the extractor's own `classes.jar` first on
the class path; no typedef removal is needed. The unchanged sources compile to the base classes apart from
the constant pool order of the three extractors.
