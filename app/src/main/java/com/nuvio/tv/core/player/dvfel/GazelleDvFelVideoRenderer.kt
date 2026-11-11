package com.nuvio.tv.core.player.dvfel

import android.media.MediaFormat
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import com.nuvio.tv.core.player.amlfel.AmlFelFallbackException
import com.nuvio.tv.core.player.amlfel.AmlFelSupport
import java.nio.ByteBuffer

/**
 * Fire TV Cube 3 (Gazelle) Dolby Vision Profile 7 FEL.
 *
 * Claims only DV profile 7. MediaCodec decodes the base layer on the Dolby Vision decoder configured as
 * profile 8 (DvheSt); every sample runs through libdvfelplay in [onQueueInputBuffer], which strips the
 * NAL 63 enhancement layer, decodes it on a second hardware decoder and hands it to /dev/dvfel, where
 * the SoC's Dolby Vision core composes BL + EL. Seek/release are hooked in [GazelleFelCodecAdapter].
 *
 * Anything unexpected (no P7 RPU, MEL, open failure, encrypted samples) throws an [AmlFelFallbackException]
 * cause, so the player retries the stream once on the AUTO path (see AmlDvFelVideoRenderer.isFallbackError).
 */
@UnstableApi
internal class GazelleDvFelVideoRenderer(
    builder: MediaCodecVideoRenderer.Builder,
    private val session: GazelleFelSession,
) : MediaCodecVideoRenderer(builder) {

    companion object {
        const val NAME = "GazelleDvFelVideoRenderer"
        /** Prefix on the decoder name reported to analytics/diagnostics. */
        const val DECODER_PREFIX = "dvfel-sc2:"
        private const val TAG = "DvFel"
        /** MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheSt (profile 8). */
        private const val DV_PROFILE_DVHE_ST = 0x100
        private const val STATS_EVERY_FRAMES = 24
        private val P7 = Regex("^(\\s*)(dvhe|dvh1)\\.07\\.", RegexOption.IGNORE_CASE)

        /** dvhe.07.LL -> dvhe.08.LL (dvh1 too), MIME video/dolby-vision. */
        fun asProfile8(f: Format): Format {
            val codecs = f.codecs ?: return f
            val out = codecs.split(',').joinToString(",") { c ->
                P7.replace(c) { m -> "${m.groupValues[1]}${m.groupValues[2]}.08." }
            }
            return f.buildUpon().setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION).setCodecs(out).build()
        }

        /** Opt-in debug switch (VLC ran with --no-drop-late-frames): `setprop debug.nuvio.dvfel_drop_late 0`. */
        private fun dropLateFrames(): Boolean = runCatching {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
                .invoke(null, "debug.nuvio.dvfel_drop_late") as? String
        }.getOrNull() != "0"
    }

    private val dropLate = dropLateFrames()
    private var framesSinceStats = 0

    override fun getName(): String = NAME

    override fun supportsFormat(mediaCodecSelector: MediaCodecSelector, format: Format): Int {
        if (!AmlFelSupport.isProfile7(format)) return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
        // super's static path also accepts HEVC decoders for profile 8: require a real DV decoder
        if (MediaCodecUtil.getDecoderInfos(MimeTypes.VIDEO_DOLBY_VISION, false, false).isEmpty()) {
            return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_SUBTYPE)
        }
        val caps = super.supportsFormat(mediaCodecSelector, asProfile8(format))
        // never tunnel this path
        return (caps and RendererCapabilities.TUNNELING_SUPPORT_MASK.inv()) or
            RendererCapabilities.TUNNELING_NOT_SUPPORTED
    }

    override fun getDecoderInfos(
        mediaCodecSelector: MediaCodecSelector,
        format: Format,
        requiresSecureDecoder: Boolean,
    ): List<MediaCodecInfo> =
        super.getDecoderInfos(mediaCodecSelector, asProfile8(format), requiresSecureDecoder)
            .filter { it.mimeType == MimeTypes.VIDEO_DOLBY_VISION }

    override fun getMediaFormat(
        format: Format,
        codecMimeType: String,
        codecMaxValues: MediaCodecVideoRenderer.CodecMaxValues,
        codecOperatingRate: Float,
        deviceNeedsNoPostProcessWorkaround: Boolean,
        tunnelingAudioSessionId: Int,
    ): MediaFormat = super.getMediaFormat(
        asProfile8(format), codecMimeType, codecMaxValues, codecOperatingRate,
        deviceNeedsNoPostProcessWorkaround, tunnelingAudioSessionId,
    ).apply { setInteger(MediaFormat.KEY_PROFILE, DV_PROFILE_DVHE_ST) }

    override fun onCodecInitialized(
        name: String,
        configuration: MediaCodecAdapter.Configuration,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        super.onCodecInitialized(DECODER_PREFIX + name, configuration, initializedTimestampMs, initializationDurationMs)
    }

    override fun onQueueInputBuffer(buffer: DecoderInputBuffer) {
        super.onQueueInputBuffer(buffer)
        if (buffer.isEndOfStream) return
        val data = buffer.data ?: return
        if (buffer.isEncrypted) throw fallback("encrypted P7 is not supported")
        if (!session.isOpen) openSession(data)
        val n = session.filterAndCommit(data, buffer.timeUs)
        if (n < 0) throw fallback("dvfp_filter failed ($n)")
        data.limit(data.position() + n)
    }

    private fun openSession(data: ByteBuffer) {
        val p = DvFelPlay.probe(data) ?: throw fallback("dvfp_probe failed")
        Log.i(TAG, "probe: hasRpu=${p.hasRpu} profile=${p.profile} elType=${p.elType} hasEl=${p.hasEl}")
        if (!p.hasRpu || p.profile != 7) throw fallback("first sample has no P7 RPU")
        // Any P7 with an EL runs as a FEL session. The EL type comes from the first sample's RPU only, and a
        // FEL film has MEL-looking stretches (black frames, fades, titles): a start or resume there must not
        // drop the film to the AUTO path. A real MEL EL adds nothing, so composing it gives the same picture.
        if (p.elType == DvFelPlay.EL_NONE && !p.hasEl) throw fallback("no EL (elType=0): AUTO path")
        if (p.elType != DvFelPlay.EL_FEL) Log.i(TAG, "first sample's RPU says elType=${p.elType}: FEL session anyway")
        // FEL whose first sample carries no NAL 63: the EL was stripped upstream of the renderer.
        if (!p.hasEl) Log.w(TAG, "P7 RPU but no EL NAL units in the first sample")
        val f = codecInputFormat ?: throw fallback("no codec input format")
        if (f.width <= 0 || f.height <= 0) throw fallback("unknown BL size ${f.width}x${f.height}")
        if (!session.open(f.width, f.height, DvFelPlay.MODE_FEL, p.elType)) {
            throw fallback("dvfp_open(FEL) failed")
        }
    }

    override fun onProcessedOutputBuffer(presentationTimeUs: Long) {
        super.onProcessedOutputBuffer(presentationTimeUs)
        if (++framesSinceStats >= STATS_EVERY_FRAMES) {
            framesSinceStats = 0
            session.snapshotStats()
        }
    }

    // Dropping to a keyframe flushes the codec -> dvfp_flush -> up to 180 ms drain. Never do it.
    override fun shouldDropBuffersToKeyframe(earlyUs: Long, elapsedRealtimeUs: Long, isLastFrame: Boolean): Boolean = false

    override fun shouldDropOutputBuffer(earlyUs: Long, elapsedRealtimeUs: Long, isLastFrame: Boolean): Boolean =
        dropLate && super.shouldDropOutputBuffer(earlyUs, elapsedRealtimeUs, isLastFrame)

    private fun fallback(msg: String): ExoPlaybackException {
        Log.i(TAG, "fallback: $msg")
        return createRendererException(
            AmlFelFallbackException("Gazelle FEL: $msg"),
            codecInputFormat,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        )
    }
}
