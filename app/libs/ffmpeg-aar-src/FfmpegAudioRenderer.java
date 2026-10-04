/*
 * Copyright 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * ---------------------------------------------------------------------------
 * This file is a reconstruction (via CFR 0.152 decompilation) of the
 * FfmpegAudioRenderer class shipped in the NuvioTV fork's vendored
 * lib-decoder-ffmpeg-release.aar, which carries private additions on top of
 * androidx.media3 (forceOpticalPassthrough / AC-3 transcode, downmix control).
 * Those additions' original author is the upstream NuvioTV developer
 * (halibiram / NuvioMedia); the AOSP portions are the media3 authors'.
 *
 * NuvioTV-Fork modification (2026-08, GPL v3.0 fork of NuvioTV):
 *   Per-MIME denied-codec transcode. transcodeToAc3 also engages for
 *   MIME types in an app-supplied denied set (setDeniedTranscodeMimes), not
 *   only under the global forceOpticalPassthrough flag. With the set empty and
 *   the flag unchanged, behaviour is bit-for-bit equivalent to the original.
 */
package androidx.media3.decoder.ffmpeg;

import android.os.Handler;
import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.util.TraceUtil;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.decoder.CryptoConfig;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DecoderAudioRenderer;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import com.google.common.base.Preconditions;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

@UnstableApi
public final class FfmpegAudioRenderer
extends DecoderAudioRenderer<FfmpegAudioDecoder> {
    private static final String TAG = "FfmpegAudioRenderer";
    private static final int NUM_BUFFERS = 16;
    private static final int DEFAULT_INPUT_BUFFER_SIZE = 5760;
    private volatile int userCenterMixLevelDb;
    @Nullable
    private volatile String requestedOutputLayoutName;
    private volatile int requestedOutputChannelCount;
    private volatile boolean downmixNormalizationEnabled;
    @Nullable
    private volatile FfmpegAudioDecoder activeDecoder;
    private volatile boolean rendererEnabled;
    private volatile boolean downmixActive;
    private volatile boolean forceOpticalPassthrough;
    private volatile Set<String> deniedTranscodeMimes = Collections.emptySet();

    public FfmpegAudioRenderer() {
        this(null, null, new AudioProcessor[0]);
    }

    public FfmpegAudioRenderer(@Nullable Handler eventHandler, @Nullable AudioRendererEventListener eventListener, AudioProcessor... audioProcessors) {
        this(eventHandler, eventListener, (AudioSink) new DefaultAudioSink.Builder().setAudioProcessors(audioProcessors).build());
    }

    public FfmpegAudioRenderer(@Nullable Handler eventHandler, @Nullable AudioRendererEventListener eventListener, AudioSink audioSink) {
        super(eventHandler, eventListener, audioSink);
    }

    public String getName() {
        return TAG;
    }

    protected void onEnabled(boolean joining, boolean mayRenderStartOfStream) throws ExoPlaybackException {
        super.onEnabled(joining, mayRenderStartOfStream);
        this.rendererEnabled = true;
    }

    protected void onDisabled() {
        try {
            super.onDisabled();
        } finally {
            this.rendererEnabled = false;
            this.downmixActive = false;
            this.activeDecoder = null;
        }
    }

    protected int supportsFormatInternal(Format format) {
        boolean supportsConfiguredOutput;
        String mimeType = (String) Preconditions.checkNotNull((Object) format.sampleMimeType);
        if (!FfmpegLibrary.isAvailable() || !MimeTypes.isAudio((String) mimeType)) {
            return 0;
        }
        if (!FfmpegLibrary.supportsFormat(mimeType)) {
            return 1;
        }
        if (this.forceOpticalPassthrough && "audio/ac3".equals(mimeType)) {
            return 1;
        }
        boolean transcodeToAc3 = this.shouldTranscodeToAc3(mimeType, format.channelCount);
        if (!(transcodeToAc3 || format.channelCount > 0 && format.sampleRate > 0)) {
            return format.cryptoType == 0 ? 4 : 2;
        }
        if (transcodeToAc3) {
            int sampleRate = format.sampleRate > 0 ? format.sampleRate : 48000;
            supportsConfiguredOutput = this.sinkSupportsFormat(new Format.Builder().setSampleMimeType("audio/ac3").setChannelCount(6).setSampleRate(sampleRate).build());
        } else {
            int outputChannelCount = this.resolveOutputChannelCount(format.channelCount);
            boolean shouldRequestDownmix = this.shouldRequestDownmix(format.channelCount, outputChannelCount);
            int outputEncoding = shouldRequestDownmix ? 4 : 2;
            supportsConfiguredOutput = this.sinkSupportsFormat(format, outputEncoding, outputChannelCount);
        }
        if (!supportsConfiguredOutput) {
            return 1;
        }
        if (format.cryptoType != 0) {
            return 2;
        }
        return 4;
    }

    public int supportsMixedMimeTypeAdaptation() {
        return 8;
    }

    protected FfmpegAudioDecoder createDecoder(Format format, @Nullable CryptoConfig cryptoConfig) throws FfmpegDecoderException {
        String outputLayoutName;
        int nativeOutputChannelCount;
        int outputEncoding;
        int initialInputBufferSize;
        TraceUtil.beginSection((String) "createFfmpegAudioDecoder");
        String mimeType = (String) Preconditions.checkNotNull((Object) format.sampleMimeType);
        boolean transcodeToAc3 = this.shouldTranscodeToAc3(mimeType, format.channelCount);
        initialInputBufferSize = format.maxInputSize != -1 ? format.maxInputSize : 5760;
        if (transcodeToAc3) {
            outputEncoding = 5;
            nativeOutputChannelCount = 6;
            outputLayoutName = "5.1";
            this.downmixActive = false;
        } else {
            int outputChannelCount = this.resolveOutputChannelCount(format.channelCount);
            boolean shouldRequestDownmix = this.shouldRequestDownmix(format.channelCount, outputChannelCount);
            outputEncoding = shouldRequestDownmix ? 4 : 2;
            outputLayoutName = shouldRequestDownmix ? this.requestedOutputLayoutName : null;
            nativeOutputChannelCount = shouldRequestDownmix ? outputChannelCount : 0;
            this.downmixActive = shouldRequestDownmix;
        }
        FfmpegAudioDecoder decoder = new FfmpegAudioDecoder(format, 16, 16, initialInputBufferSize, nativeOutputChannelCount, outputLayoutName, outputEncoding);
        decoder.setUserCenterMixLevelDb(this.userCenterMixLevelDb);
        decoder.setDownmixNormalizationEnabled(this.downmixNormalizationEnabled);
        this.activeDecoder = decoder;
        TraceUtil.endSection();
        return decoder;
    }

    protected Format getOutputFormat(FfmpegAudioDecoder decoder) {
        Preconditions.checkNotNull((Object) ((Object) decoder));
        int encoding = decoder.getEncoding();
        if (encoding == 5) {
            return new Format.Builder().setSampleMimeType("audio/ac3").setChannelCount(decoder.getChannelCount()).setSampleRate(decoder.getSampleRate()).build();
        }
        return new Format.Builder().setSampleMimeType("audio/raw").setChannelCount(decoder.getChannelCount()).setSampleRate(decoder.getSampleRate()).setPcmEncoding(encoding).build();
    }

    public void setCenterMixLevelDb(int centerMixLevelDb) {
        this.userCenterMixLevelDb = centerMixLevelDb;
        FfmpegAudioDecoder decoder = this.activeDecoder;
        if (decoder != null) {
            decoder.setUserCenterMixLevelDb(centerMixLevelDb);
        }
    }

    public void setAudioOutputChannels(@Nullable String outputLayoutName, int outputChannelCount) {
        this.requestedOutputLayoutName = outputLayoutName;
        this.requestedOutputChannelCount = outputChannelCount;
    }

    public void setDownmixNormalizationEnabled(boolean downmixNormalizationEnabled) {
        this.downmixNormalizationEnabled = downmixNormalizationEnabled;
        FfmpegAudioDecoder decoder = this.activeDecoder;
        if (decoder != null) {
            decoder.setDownmixNormalizationEnabled(downmixNormalizationEnabled);
        }
    }

    public void setForceOpticalPassthrough(boolean enabled) {
        this.forceOpticalPassthrough = enabled;
    }

    /**
     * MIME types the app wants transcoded to AC-3 when they are selected, even
     * without the global forceOpticalPassthrough flag. Intended for formats whose
     * passthrough the user (or a learned rejection) has denied, on chains that cannot
     * accept multichannel LPCM. The set must never contain "audio/ac3" (guarded here
     * anyway) and the caller is responsible for only populating it when AC-3
     * passthrough itself is usable on the chain. Passing null or an empty set restores
     * the original behaviour exactly.
     */
    public void setDeniedTranscodeMimes(@Nullable Set<String> mimeTypes) {
        this.deniedTranscodeMimes = mimeTypes == null || mimeTypes.isEmpty()
                ? Collections.<String>emptySet()
                : Collections.unmodifiableSet(new HashSet<String>(mimeTypes));
    }

    public boolean isCenterMixActive() {
        return this.rendererEnabled && this.activeDecoder != null && this.downmixActive;
    }

    public boolean isAudioPathActive() {
        return this.rendererEnabled && this.activeDecoder != null;
    }

    /**
     * Transcode predicate shared by supportsFormatInternal and createDecoder. A MIME in
     * deniedTranscodeMimes counts as if forceOpticalPassthrough were set; with an empty
     * set the result matches the unmodified renderer.
     */
    private boolean shouldTranscodeToAc3(String mimeType, int channelCount) {
        if ("audio/ac3".equals(mimeType)) {
            return false;
        }
        if (!this.forceOpticalPassthrough && !this.deniedTranscodeMimes.contains(mimeType)) {
            return false;
        }
        boolean isDtsOrTrueHd = "audio/vnd.dts".equals(mimeType) || "audio/vnd.dts.hd".equals(mimeType) || "audio/true-hd".equals(mimeType);
        return channelCount > 2 || channelCount <= 0 || isDtsOrTrueHd;
    }

    private boolean sinkSupportsFormat(Format inputFormat, int pcmEncoding, int channelCount) {
        if (channelCount <= 0 || inputFormat.sampleRate <= 0) {
            return false;
        }
        return this.sinkSupportsFormat(Util.getPcmFormat((int) pcmEncoding, (int) channelCount, (int) inputFormat.sampleRate));
    }

    private int resolveOutputChannelCount(int inputChannelCount) {
        if (inputChannelCount <= 0) {
            return inputChannelCount;
        }
        int configuredChannelCount = this.requestedOutputChannelCount;
        if (configuredChannelCount <= 0 || configuredChannelCount >= inputChannelCount) {
            return inputChannelCount;
        }
        return configuredChannelCount;
    }

    private boolean shouldRequestDownmix(int inputChannelCount, int outputChannelCount) {
        return this.requestedOutputChannelCount > 0 && outputChannelCount < inputChannelCount;
    }
}
