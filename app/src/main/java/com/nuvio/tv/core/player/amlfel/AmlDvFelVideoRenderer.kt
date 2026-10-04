package com.nuvio.tv.core.player.amlfel

import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.BaseRenderer
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.video.VideoRendererEventListener
import com.nuvio.tv.core.player.DolbyVisionConversionStats
import com.nuvio.tv.core.player.DoviBridge
import com.nuvio.tv.core.player.HevcDvRpuStripper
import java.nio.ByteBuffer

/** Thrown (as the cause of an [ExoPlaybackException]) when a stream must leave the native FEL path. */
internal class AmlFelFallbackException(message: String) : Exception(message)

/**
 * Video renderer for Dolby Vision Profile 7 (FEL and MEL) on Amlogic boxes. Instead of MediaCodec, which
 * drops the enhancement layer, it feeds the unconverted P7 samples (BL + type-63 EL + type-62 RPU,
 * Annex-B) into the dual-layer amstream port, so the SoC decodes both layers and composes them.
 *
 * - Picture: an Amlogic sideband stream is attached to the SurfaceView so the HWC shows VD1 in its rect
 *   (decoder name [DECODER_NAME_SIDEBAND]). If that fails the SurfaceView is a transparent hole instead
 *   (decoder name [DECODER_NAME]).
 * - Timing: the hardware presents against the tsync system clock, which [render] slaves to the playback
 *   position every [PCR_SYNC_INTERVAL_MS], re-set on a drift of [PCR_RESYNC_THRESHOLD_90K] or more.
 *   Pause/resume = tsync VIDEO_PAUSE; seek = reopen the port.
 * - Fallback: non-P7 RPU, unsupported framing, no in-band RPU, DRM, device errors or a stalled decoder
 *   raise [AmlFelFallbackException]; the player then re-prepares the stream on the normal path.
 * Claims only DV profile 7 formats and is only in the renderer list when [AmlFelSupport.isNativeFelUsable].
 */
@UnstableApi
internal class AmlDvFelVideoRenderer(
    eventHandler: Handler?,
    eventListener: VideoRendererEventListener?,
    private val playbackSpeedProvider: () -> Float = { 1f },
    private val nativeMelEnabled: Boolean = true,
    private val onSelectionChanged: (Boolean) -> Unit = {},
) : BaseRenderer(C.TRACK_TYPE_VIDEO) {

    companion object {
        const val TAG = "AmlFel"
        const val NAME = "AmlDvFelVideoRenderer"
        /** Transparent-hole mode: the player screen switches the SurfaceView to TRANSLUCENT for it. */
        const val DECODER_NAME = "amstream_dves_hevc"
        /** Sideband mode: the SurfaceView stays opaque; HWC places VD1 in its rect. */
        const val DECODER_NAME_SIDEBAND = "amstream_dves_hevc.sideband"
        /** Media time 0 maps to this PTS so preroll can never go negative (90 kHz u32 wraps after 13 h). */
        private const val PTS_BASE_US = 10_000_000L
        private const val PCR_SYNC_INTERVAL_MS = 500L
        /**
         * pcrscr is only re-set when it has drifted this far from the audio clock (25 ms, below one 24p
         * frame of 41.7 ms). Hard-setting it every 500 ms made every jitter of a passthrough AudioTrack's
         * position a jump of the video clock (repeated / skipped frames); the pcrscr free-runs on the
         * system clock between corrections, which drifts from the audio clock by only ppm.
         */
        private const val PCR_RESYNC_THRESHOLD_90K = 2_250
        private const val PCRSCR_SYSFS = "/sys/class/tsync/pts_pcrscr"
        private const val DISPLAY_MODE_SYSFS = "/sys/class/display/mode"
        private const val DISPLAY_MODE_POLL_MS = 100L
        private const val SIDEBAND_REFRESH_AFTER_MODE_MS = 700L
        private const val READY_AHEAD_US = 300_000L
        private const val FIRST_FRAME_TIMEOUT_MS = 4_000L
        /** After the clock first moves, how long to wait for a frame near the target before taking what is shown. */
        private const val FIRST_FRAME_TARGET_GRACE_MS = 750L
        private const val STALL_TIMEOUT_MS = 8_000L
        private const val VPTS_POLL_MS = 250L
        /** No in-band RPU NAL at all within this many samples = not a single-track P7 stream: fall back. */
        private const val NO_RPU_FALLBACK_SAMPLES = 12
        /** Backward search window for the trailing RPU NAL of a sample (HUD live rows). */
        private const val DM_SCAN_MAX_BYTES = 256 * 1024
        private const val DM_WRITER = DolbyVisionConversionStats.LIVE_DM_WRITER_RENDERER
        /**
         * Feed pacing. BL and EL are parsed out of one stream ring buffer by two decoders; filled to the brim
         * (write back-pressure only), both decoders stop for good on dense content (frame counts freeze, the display
         * queue drains, then the EL goes missing). Stop reading new samples while more than [FEED_MAX_AHEAD_US] of
         * media is queued or the port buffer is more than [FEED_MAX_FILL_PERCENT] full.
         */
        private const val FEED_MAX_AHEAD_US = 2_500_000L
        private const val FEED_MAX_FILL_PERCENT = 50
        /**
         * Preroll. After a session open (start, seek) the renderer reports ready only once the first frame is up and
         * [PREROLL_FRAMES] decoded frames wait in the amvideo queue (video held paused meanwhile), at most
         * [PREROLL_MAX_MS] after the first frame. Without it Media3 restarts the clock with nothing decoded ahead: on
         * open-GOP content BL and EL of the next pictures share one HEVC core in hierarchical-B order, the second
         * picture's EL comes late and a BL-only frame shows = a blink at the seek. [PREROLL_FALLBACK_MS] applies when
         * the queue depth cannot be read.
         */
        private const val PREROLL_FRAMES = 4
        private const val PREROLL_MAX_MS = 600L
        private const val PREROLL_FALLBACK_MS = 250L
        private const val PREROLL_POLL_MS = 20L

        /** True when [error] (or a cause) asks for the native FEL path to be abandoned for this stream. */
        fun isFallbackError(error: Throwable?): Boolean {
            var t = error
            var depth = 0
            while (t != null && depth++ < 8) {
                if (t is AmlFelFallbackException) return true
                t = t.cause
            }
            return false
        }
    }

    private val eventDispatcher = VideoRendererEventListener.EventDispatcher(eventHandler, eventListener)
    private val dvStats = DolbyVisionConversionStats.session
    private var streamSeen = false
    private var targetClockSet = false
    private var statusFailures = 0
    private val inputBuffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT)
    private val counters = DecoderCounters()
    private val bufferStatus = IntArray(3)

    private var inputFormat: Format? = null
    private var videoOutput: Any? = null
    private var handle = 0L
    private var sidebandHandle = 0L
    private var samplePending = false
    private var inputEnded = false
    private var eosSent = false
    private var lastQueuedTimeUs = C.TIME_UNSET
    private var lastPositionUs = 0L
    private var started = false
    private var pausedApplied = false
    private var firstFrameReported = false
    private var sessionFirstFrame = false
    private var sessionOpenedRealtimeMs = 0L
    private var firstFrameWaitFromMs = 0L
    private var vptsMovedRealtimeMs = 0L
    private var vptsAtOpen = -1L
    private var lastPcrSyncRealtimeMs = 0L
    private var sidebandRefreshAtMs = 0L
    private var lastDisplayMode: String? = null
    private var lastDisplayModePollMs = 0L
    private var lastProgressRealtimeMs = 0L
    private var pcrErrorLogged = false
    private var elProbeDone = false
    private var elProbeSamples = 0
    private var elProbeUnknownLogged = 0
    private var elProbeRpuSeen = false
    private var elLabel = "P7"
    private var framingChecked = false
    private var probeScratch = ByteArray(0)
    private var dmScratch = ByteArray(1024)
    private var lastVptsPollMs = 0L
    private var lastPolledVpts = -1L
    private var leadingCheck = false
    private var leadingSawIrap = false
    private var prerollDone = false
    private var firstFrameRealtimeMs = 0L
    private var lastPrerollPollMs = 0L

    override fun getName(): String = NAME

    override fun supportsFormat(format: Format): Int {
        if (!MimeTypes.isVideo(format.sampleMimeType)) {
            return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
        }
        if (!AmlFelSupport.isProfile7(format)) {
            return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_SUBTYPE)
        }
        // The amstream port takes clear bytes only (no crypto/session/secure-buffer contract): protected
        // streams stay on the DRM-aware MediaCodec path.
        if (isProtected(format)) return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_DRM)
        // Listed before MediaCodec: equal support resolves to the first renderer (MappingTrackSelector).
        return RendererCapabilities.create(
            C.FORMAT_HANDLED,
            RendererCapabilities.ADAPTIVE_NOT_SUPPORTED,
            RendererCapabilities.TUNNELING_NOT_SUPPORTED,
            RendererCapabilities.HARDWARE_ACCELERATION_SUPPORTED,
            RendererCapabilities.DECODER_SUPPORT_PRIMARY
        )
    }

    override fun handleMessage(messageType: Int, message: Any?) {
        if (messageType == Renderer.MSG_SET_VIDEO_OUTPUT) {
            // Never drawn into; carries the sideband stream while a session runs (re-attached when the
            // surface is replaced mid-session, e.g. after a SurfaceView format change).
            if (message !== videoOutput) {
                detachSideband()
                sidebandRefreshAtMs = 0L // a new output is attached fresh; a pending refresh would detach it
                videoOutput = message
                firstFrameReported = false
                // Output readiness is independent of decoder progress; never consume a null-output event.
                if (message == null && handle != 0L && !pausedApplied) applyPause()
                if (handle != 0L) attachSideband()
            }
        } else {
            super.handleMessage(messageType, message)
        }
    }

    override fun onEnabled(joining: Boolean, mayRenderStartOfStream: Boolean) {
        onSelectionChanged(true)
        resetLeadingCheck()
        eventDispatcher.enabled(counters)
        elProbeDone = false
        elProbeSamples = 0
        elProbeUnknownLogged = 0
        elProbeRpuSeen = false
        framingChecked = false
        firstFrameReported = false
    }

    override fun onStreamChanged(
        formats: Array<Format>, startPositionUs: Long, offsetUs: Long,
        mediaPeriodId: androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
    ) {
        if (streamSeen) throw fallback("stream replacement requires normal decoder reconfiguration")
        streamSeen = true
        elProbeDone = false
        elProbeSamples = 0
        elProbeRpuSeen = false
        framingChecked = false
    }

    override fun onPositionReset(positionUs: Long, joining: Boolean) {
        resetLeadingCheck()
        closeSession()
        inputEnded = false
        eosSent = false
        lastQueuedTimeUs = C.TIME_UNSET
        lastPositionUs = positionUs
    }

    override fun onStarted() {
        started = true
        lastProgressRealtimeMs = SystemClock.elapsedRealtime()
        // Time spent held before the first frame (frame-rate settle, thumbnail screen) does not count against it.
        if (handle != 0L && !sessionFirstFrame) firstFrameWaitFromMs = lastProgressRealtimeMs
        if (handle != 0L && pausedApplied) {
            val r = AmlFelNative.setPaused(handle, false)
            if (r < 0) throw fallback("native resume failed: errno ${-r}")
            pausedApplied = false
        }
        lastPcrSyncRealtimeMs = 0L // re-slave the clock on the next render call
    }

    override fun onStopped() {
        started = false
        // Before the first frame of a session is up, let it appear; render() pauses right after.
        if (handle != 0L && sessionFirstFrame && !pausedApplied) applyPause()
    }

    override fun onDisabled() {
        try {
            closeSession()
            detachSideband()
            sidebandRefreshAtMs = 0L
            inputFormat = null
            streamSeen = false
            eventDispatcher.disabled(counters)
        } finally {
            onSelectionChanged(false)
        }
    }

    override fun onReset() {
        try {
            closeSession()
            detachSideband()
            sidebandRefreshAtMs = 0L
        } finally {
            onSelectionChanged(false)
        }
    }

    override fun onRelease() {
        try {
            closeSession()
            detachSideband()
            sidebandRefreshAtMs = 0L
        } finally {
            onSelectionChanged(false)
        }
    }

    override fun isReady(): Boolean {
        if (inputFormat == null) return false
        if (inputEnded && lastQueuedTimeUs == C.TIME_UNSET) return true
        // No session yet (after a seek until the first sample opens the port) or still prerolling: not ready, so
        // Media3 keeps the clock stopped (render() keeps feeding meanwhile).
        if (handle == 0L || !prerollDone) return false
        if (inputEnded) return true
        if (samplePending || isSourceReady()) return true
        return lastQueuedTimeUs != C.TIME_UNSET && lastQueuedTimeUs - lastPositionUs >= READY_AHEAD_US
    }

    override fun isEnded(): Boolean =
        eosSent && !samplePending && (lastQueuedTimeUs == C.TIME_UNSET ||
            (sessionFirstFrame && lastPositionUs >= lastQueuedTimeUs +
                (1_000_000f / (inputFormat?.frameRate?.takeIf { it > 0 } ?: 24f)).toLong()))

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        lastPositionUs = positionUs
        if (kotlin.math.abs(playbackSpeedProvider() - 1f) > 0.001f) throw fallback("native DV7 requires normal playback speed")
        if (inputFormat == null) {
            val holder = formatHolder
            holder.clear()
            inputBuffer.clear()
            if (readSource(holder, inputBuffer, SampleStream.FLAG_REQUIRE_FORMAT) != C.RESULT_FORMAT_READ) return
            onInputFormatChanged(checkNotNull(holder.format))
        }
        feed()
        if (handle == 0L) return
        val now = SystemClock.elapsedRealtime()
        maybeReportFirstFrame(now)
        updatePreroll(now)
        slaveClock(positionUs, now)
        maybeRefreshSideband(now)
        checkStall(now)
    }

    // --- input ---

    private fun feed() {
        while (true) {
            if (samplePending) {
                val remaining = AmlFelNative.drain(handle)
                if (remaining < 0) throw failure("write", remaining)
                if (remaining > 0) return // stream buffer full: natural back-pressure
                samplePending = false
                if (!sessionFirstFrame) lastProgressRealtimeMs = SystemClock.elapsedRealtime()
            }
            if (!inputEnded && handle != 0L && feedPaced()) return
            if (inputEnded) {
                // No AMSTREAM_SET_EOS on the dual-layer port: it leaves the EL decoder spinning and wedges close()
                // until reboot. The frames already queued are presented anyway; playback ends on position
                // (isEnded) and the port is closed like any mid-stream stop.
                eosSent = true
                return
            }
            val holder = formatHolder
            holder.clear()
            inputBuffer.clear()
            when (readSource(holder, inputBuffer, /* readFlags= */ 0)) {
                C.RESULT_NOTHING_READ -> return
                C.RESULT_FORMAT_READ -> onInputFormatChanged(checkNotNull(holder.format))
                C.RESULT_BUFFER_READ -> {
                    if (inputBuffer.isEndOfStream) {
                        inputEnded = true
                        continue
                    }
                    if (inputBuffer.isEncrypted) throw fallback("encrypted sample: not supported by the native FEL path")
                    inputBuffer.flip()
                    val data = inputBuffer.data ?: continue
                    val length = data.remaining()
                    if (length <= 0) continue
                    if (length > 32 * 1024 * 1024) throw fallback("access unit exceeds native admission limit")
                    if (!framingChecked) checkFraming(data)
                    if (!elProbeDone) probeElType(data)
                    if (leadingCheck && dropLeadingPicture(data)) {
                        counters.skippedInputBufferCount++
                        continue
                    }
                    sampleLiveDm(data, inputBuffer.timeUs - streamOffsetUs)
                    ensureSession()
                    val r = AmlFelNative.queueSample(handle, data, data.position(), length, toStreamPtsUs(inputBuffer.timeUs))
                    if (r < 0) throw failure("queue", r)
                    samplePending = true
                    // Greatest PTS, not the last one: HEVC is fed in decode order, so the final sample can be a
                    // reordered picture that is presented before an earlier-fed one (isEnded / isReady use this).
                    lastQueuedTimeUs = if (lastQueuedTimeUs == C.TIME_UNSET) inputBuffer.timeUs
                    else maxOf(lastQueuedTimeUs, inputBuffer.timeUs)
                    counters.queuedInputBufferCount++
                }
            }
        }
    }

    /**
     * True while enough is queued (see [FEED_MAX_AHEAD_US], [FEED_MAX_FILL_PERCENT]). The fill level is read before
     * every sample (one cheap ioctl): a cached reading let a fast burst (preroll after a seek, or high-bitrate content
     * where 2.5 s exceeds the whole buffer) fill it completely between polls. Overshoot is at most one sample. If the
     * status cannot be read, admission pauses and repeated failures fall back.
     */
    private fun feedPaced(): Boolean {
        if (lastQueuedTimeUs != C.TIME_UNSET && lastQueuedTimeUs - lastPositionUs > FEED_MAX_AHEAD_US) return true
        if (AmlFelNative.getBufferStatus(handle, bufferStatus) != 0 || bufferStatus[0] <= 0) {
            if (++statusFailures >= 8) throw fallback("native buffer status unavailable")
            return true
        }
        statusFailures = 0
        return bufferStatus[1].toLong() * 100 > bufferStatus[0].toLong() * FEED_MAX_FILL_PERCENT
    }

    private fun onInputFormatChanged(format: Format) {
        val previous = inputFormat
        inputFormat = format
        eventDispatcher.inputFormatChanged(format, null)
        if (format.width > 0 && format.height > 0) {
            eventDispatcher.videoSizeChanged(VideoSize(format.width, format.height, format.pixelWidthHeightRatio))
        }
        if (!AmlFelSupport.isProfile7(format)) {
            throw fallback("format is not Dolby Vision profile 7 (${format.sampleMimeType} ${format.codecs})")
        }
        if (isProtected(format)) throw fallback("protected (DRM) input is not supported by the native FEL path")
        if (previous != null && handle != 0L &&
            (previous.width != format.width || previous.height != format.height)
        ) {
            // A resolution change needs new decoder instances; the next sample reopens the port.
            closeSession()
        }
        if (previous != null && isMaterialChange(previous, format) && handle != 0L) {
            throw fallback("native stream configuration changed")
        }
        if (previous != null && isMaterialChange(previous, format)) {
            // A different stream/configuration: the FEL/MEL classification and the Annex-B check describe the
            // previous one, and without a reset here a MEL stream after FEL would skip the MEL fallback.
            elProbeDone = false
            elProbeSamples = 0
            elProbeUnknownLogged = 0
            elProbeRpuSeen = false
            framingChecked = false
        }
    }

    private fun resetLeadingCheck() {
        leadingCheck = true
        leadingSawIrap = false
    }

    /**
     * RASL leading pictures after the random-access point a session starts at (CRA, the usual seek point of open-GOP
     * P7 discs) reference pictures before it, which were never fed. HEVC requires a decoder starting there to discard
     * them (NoRaslOutputFlag); the dual-layer stream-parser decoders run with nal_skip_policy 1 and decode them without
     * references (BL/EL dropped unevenly -> a BL-only frame at each seek). A sample carries BL + EL + RPU, so dropping
     * whole samples keeps the layers paired; RASL pictures are never references for trailing pictures.
     */
    private fun dropLeadingPicture(data: ByteBuffer): Boolean {
        val type = firstVclNalType(data)
        if (type < 0) return false
        if (type in 16..23) { // IRAP (BLA/IDR/CRA)
            leadingSawIrap = true
            return false
        }
        if (leadingSawIrap && (type == 8 || type == 9)) return true // RASL_N / RASL_R
        leadingCheck = false
        return false
    }

    /** NAL type of the first VCL NAL (< 32) in an Annex-B sample = the BL picture (EL NALs are wrapped in type 63). */
    private fun firstVclNalType(data: ByteBuffer): Int {
        val end = data.limit()
        var i = data.position()
        while (i + 3 < end) {
            if (data.get(i).toInt() == 0 && data.get(i + 1).toInt() == 0 && data.get(i + 2).toInt() == 1) {
                val type = (data.get(i + 3).toInt() shr 1) and 0x3F
                if (type < 32) return type
                i += 3
            } else {
                i++
            }
        }
        return -1
    }

    private fun isProtected(format: Format): Boolean =
        format.drmInitData != null || format.cryptoType != C.CRYPTO_TYPE_NONE

    /** Same stream configuration (a seek re-announcing the format) vs a different one. */
    private fun isMaterialChange(a: Format, b: Format): Boolean =
        a.frameRate != b.frameRate || a.sampleMimeType != b.sampleMimeType || a.codecs != b.codecs || a.width != b.width || a.height != b.height ||
            a.initializationData.size != b.initializationData.size ||
            a.initializationData.indices.any { !a.initializationData[it].contentEquals(b.initializationData[it]) }

    /** The port parses Annex-B only (what the Matroska/TS extractors emit). */
    private fun checkFraming(data: ByteBuffer) {
        framingChecked = true
        val p = data.position()
        val annexB = data.remaining() >= 4 && data.get(p).toInt() == 0 && data.get(p + 1).toInt() == 0 &&
            (data.get(p + 2).toInt() == 1 || (data.get(p + 2).toInt() == 0 && data.get(p + 3).toInt() == 1))
        if (!annexB) throw fallback("sample framing is not Annex-B (length-delimited container)")
    }

    /** Classify before native admission; unknown or missing RPUs take the normal path. */
    private fun probeElType(data: ByteBuffer) {
        elProbeSamples++
        val length = data.remaining()
        if (probeScratch.size < length) probeScratch = ByteArray(length)
        data.duplicate().get(probeScratch, 0, length)
        val rpu = HevcDvRpuStripper.findRpuNalAnnexB(probeScratch, length)
        if (rpu != null) {
            elProbeRpuSeen = true
        } else if (!elProbeRpuSeen && elProbeSamples >= NO_RPU_FALLBACK_SAMPLES) {
            // Single-track P7 (disc remux MKV) carries an in-band RPU in every access unit. None at all means the
            // BL of a dual-track P7 (MP4: the RPU travels with the EL track; Media3 emits start codes, so the
            // Annex-B check passes) or out-of-band RPUs: the port would get a BL-only stream.
            throw fallback("no RPU NAL in the first $elProbeSamples samples (dual-track P7 or out-of-band RPU) " +
                "-> normal Dolby Vision path")
        }
        val code = if (rpu == null) null else DoviBridge.detectRpuElType(probeScratch, rpu.first, rpu.second)
        when (code) {
            DoviBridge.EL_TYPE_FEL, DoviBridge.EL_TYPE_MEL, DoviBridge.EL_TYPE_NONE -> {
                elProbeDone = true
                dvStats.recordElType(code)
                // HUD "DV HDR" row (MaxCLL/MaxFALL/MDL): the conversion path records it in its extractor hooks,
                // which the native path does not run. Read from the same RPU.
                if (rpu != null) {
                    dvStats.recordRpuMetadata(
                        DoviBridge.getRpuStaticMetadata(probeScratch, rpu.first, rpu.second)
                    )
                }
            }
            else -> throw fallback("unclassified DV7 input; normal path required before native submission")
        }
        when (code) {
            DoviBridge.EL_TYPE_FEL -> {
                probeScratch = ByteArray(0)
                elLabel = "FEL"
                Log.i(TAG, "RPU: profile 7 FEL on the native path")
            }
            DoviBridge.EL_TYPE_MEL -> {
                if (!nativeMelEnabled) throw fallback("RPU: profile 7 MEL -> normal Dolby Vision path")
                probeScratch = ByteArray(0)
                elLabel = "MEL"
                Log.i(TAG, "RPU: profile 7 MEL on the native path")
            }
            else -> throw fallback("RPU is not profile 7")
        }
    }

    /**
     * HUD live rows: every ~0.5 s of media, the RPU of the sample being fed is summarised (L1 scene light,
     * L5 active area, CM version, levels) and stored by media time; the HUD shows the entry at the playback
     * position (the feed runs up to [FEED_MAX_AHEAD_US] ahead). The RPU is the last NAL of a P7 access unit, so it
     * is searched backwards from the end (at most [DM_SCAN_MAX_BYTES]); only the RPU bytes are copied.
     */
    private fun sampleLiveDm(data: ByteBuffer, mediaTimeUs: Long) {
        if (!DoviBridge.isAvailable() || !dvStats.shouldSampleLiveDm(DM_WRITER, mediaTimeUs)) return
        val start = data.position()
        val end = data.limit()
        val floor = maxOf(start, end - DM_SCAN_MAX_BYTES)
        var nalBegin = -1
        var i = end - 5
        while (i >= floor) {
            if (data.get(i + 3).toInt() == 0x7C && data.get(i + 2).toInt() == 1 &&
                data.get(i + 1).toInt() == 0 && data.get(i).toInt() == 0
            ) {
                nalBegin = i + 3
                break
            }
            i--
        }
        if (nalBegin < 0) {
            dvStats.recordLiveDm(DM_WRITER, mediaTimeUs, null)
            return
        }
        var nalEnd = end
        var j = nalBegin + 2
        while (j + 2 < end) {
            if (data.get(j).toInt() == 0 && data.get(j + 1).toInt() == 0 && data.get(j + 2).toInt() == 1) {
                nalEnd = j
                break
            }
            j++
        }
        while (nalEnd > nalBegin && data.get(nalEnd - 1).toInt() == 0) nalEnd-- // next start code's leading zero
        val length = nalEnd - nalBegin
        if (length <= 2 || length > 65_536) {
            dvStats.recordLiveDm(DM_WRITER, mediaTimeUs, null)
            return
        }
        if (dmScratch.size < length) dmScratch = ByteArray(length)
        val view = data.duplicate()
        view.position(nalBegin)
        view.get(dmScratch, 0, length)
        dvStats.recordLiveDm(DM_WRITER, mediaTimeUs, DoviBridge.getRpuDmInfo(dmScratch, 0, length))
    }

    // --- session ---

    private fun ensureSession() {
        if (handle != 0L) return
        val format = inputFormat ?: throw fallback("no input format")
        // Sideband first (HWC switches VD1 to the amvideo path and sets the axis), then the port.
        if (sidebandHandle == 0L) attachSideband()
        val opened = AmlFelNative.open(format.width, format.height, AmlFelSupport.rate96k(format.frameRate))
        if (opened <= 0L) throw failure("open", opened)
        handle = opened
        val now = SystemClock.elapsedRealtime()
        sessionOpenedRealtimeMs = now
        firstFrameWaitFromMs = now
        vptsMovedRealtimeMs = 0L
        lastProgressRealtimeMs = now
        lastPcrSyncRealtimeMs = 0L
        sessionFirstFrame = false
        firstFrameReported = false
        targetClockSet = false
        statusFailures = 0
        pausedApplied = false
        lastDisplayMode = readDisplayMode()
        lastDisplayModePollMs = now
        vptsAtOpen = AmlFelNative.getVpts(handle)
        lastPolledVpts = vptsAtOpen
        lastVptsPollMs = now
        prerollDone = false
        lastPrerollPollMs = 0L
        // Parameter sets first (csd-0); no PTS for them.
        format.initializationData.firstOrNull()?.takeIf { it.isNotEmpty() }?.let { csd ->
            val direct = ByteBuffer.allocateDirect(csd.size).put(csd)
            direct.flip()
            var r = AmlFelNative.queueSample(handle, direct, 0, csd.size, /* ptsUs= */ -1L)
            var attempts = 0
            while (r >= 0) {
                r = AmlFelNative.drain(handle)
                if (r == 0) break
                if (++attempts > 50) { r = -11; break }
                SystemClock.sleep(2)
            }
            if (r < 0) throw failure("csd write", r)
        }
        val decoderName = if (sidebandHandle != 0L) DECODER_NAME_SIDEBAND else DECODER_NAME
        eventDispatcher.decoderInitialized(decoderName, now, SystemClock.elapsedRealtime() - now)
    }

    private fun closeSession() {
        if (handle == 0L) return
        AmlFelNative.close(handle) // also un-pauses tsync
        handle = 0L
        samplePending = false
        pausedApplied = false
        sessionFirstFrame = false
    }

    /** Attaches the sideband stream to the current Surface; on failure the transparent-hole mode is used. */
    private fun attachSideband() {
        if (sidebandHandle != 0L) return
        val surface = videoOutput as? Surface
        if (surface == null || !surface.isValid) {
            Log.w(TAG, "sideband: no valid Surface (${videoOutput?.javaClass?.simpleName}); transparent-hole mode")
            return
        }
        val r = AmlFelNative.attachSideband(surface)
        if (r > 0L) {
            sidebandHandle = r
        } else {
            Log.w(TAG, "sideband: attach failed (errno ${-r}); transparent-hole mode")
        }
    }

    private fun detachSideband() {
        if (sidebandHandle == 0L) return
        AmlFelNative.detachSideband(sidebandHandle)
        sidebandHandle = 0L
    }

    /**
     * Re-sends the sideband stream after the HDMI output mode changed. Every mode set of the vendor composer
     * (MesonHwc) blanks all planes and switches the video composer off ("VideoComposerDev set (0)"), and
     * SurfaceFlinger does not re-send a sideband layer, so VD1 would stay black while the TV keeps Dolby Vision.
     * This happens only on real mode switches (AFR at the start of a title, where the composer's DV policy also
     * bounces 24 -> 60 -> 24 Hz); the TV is re-syncing then anyway, so the refresh is not visible. Seeks and FEL
     * starts cause no mode set on the FEL kernel (it sends the composer no VIDEO_FORMAT event during a FEL session).
     * Refreshed [SIDEBAND_REFRESH_AFTER_MODE_MS] after the last change of /sys/class/display/mode.
     */
    private fun maybeRefreshSideband(now: Long) {
        if (videoOutput == null) return
        if (now - lastDisplayModePollMs >= DISPLAY_MODE_POLL_MS) {
            lastDisplayModePollMs = now
            val mode = readDisplayMode()
            if (mode != null && mode != lastDisplayMode) {
                lastDisplayMode = mode
                sidebandRefreshAtMs = now + SIDEBAND_REFRESH_AFTER_MODE_MS
            }
        }
        if (sidebandRefreshAtMs == 0L || now < sidebandRefreshAtMs) return
        sidebandRefreshAtMs = 0L
        detachSideband()
        attachSideband()
    }

    private fun readDisplayMode(): String? =
        runCatching { java.io.File(DISPLAY_MODE_SYSFS).readText().trim() }.getOrNull()

    // --- presentation ---

    private fun toStreamPtsUs(timeUs: Long): Long = timeUs - streamOffsetUs + PTS_BASE_US

    private fun toPts90k(positionUs: Long): Int = (toStreamPtsUs(positionUs) * 9L / 100L).toInt()

    private fun maybeReportFirstFrame(now: Long) {
        val surface = videoOutput as? Surface
        if (sessionFirstFrame) {
            if (!firstFrameReported && surface?.isValid == true) {
                firstFrameReported = true
                eventDispatcher.renderedFirstFrame(surface)
                if (started && pausedApplied) {
                    if (AmlFelNative.setPaused(handle, false) < 0) throw fallback("output resume failed")
                    pausedApplied = false
                }
            }
            return
        }
        val vpts = AmlFelNative.getVpts(handle)
        if (vpts <= 0 || vpts == vptsAtOpen) {
            if (started && now - firstFrameWaitFromMs >= FIRST_FRAME_TIMEOUT_MS) {
                throw fallback("native first-frame deadline expired")
            }
            return
        }
        if (vptsMovedRealtimeMs == 0L) vptsMovedRealtimeMs = now
        // VIDEO_START may seed the clock. Apply our target after that event, before accepting preroll.
        if (!targetClockSet) {
            if (AmlFelNative.setPcrscr(handle, toPts90k(lastPositionUs)) < 0) throw fallback("startup clock unavailable")
            targetClockSet = true
        }
        val frameUs = (1_000_000.0 / (inputFormat?.frameRate?.takeIf { it > 0 } ?: 24f)).toLong()
        val target90k = toPts90k(lastPositionUs)
        val delta90k = vpts.toInt() - target90k // signed difference handles u32 wrap
        val onTarget = delta90k >= -(frameUs * 9 / 100).toInt() && delta90k <= (frameUs * 18 / 100).toInt()
        // A frame near the target is preferred; a clip whose first shown frame sits elsewhere (open GOP, odd
        // first timestamp) still counts once the clock has been moving for a moment.
        if (!onTarget && now - vptsMovedRealtimeMs < FIRST_FRAME_TARGET_GRACE_MS) return
        if (surface?.isValid != true) return
        sessionFirstFrame = true
        firstFrameRealtimeMs = now
        firstFrameReported = true
        Log.i(TAG, "first frame: $elLabel session at ${lastPositionUs / 1000} ms, ${now - sessionOpenedRealtimeMs} ms after open")
        if (!onTarget) Log.i(TAG, "first frame taken off target: clock ${delta90k / 90} ms from the seek position")
        // This is kernel presentation progress, not independent proof of FEL reconstruction or scanout.
        eventDispatcher.renderedFirstFrame(surface)
        if (!started && !pausedApplied) applyPause()
    }

    /** Ends the session's preroll (see [PREROLL_FRAMES]); [isReady] stays false until then. */
    private fun updatePreroll(now: Long) {
        if (prerollDone || !sessionFirstFrame) return
        if (now - lastPrerollPollMs < PREROLL_POLL_MS) return
        lastPrerollPollMs = now
        val avail = AmlFelSupport.readVframeAvail()
        val elapsed = now - firstFrameRealtimeMs
        val limit = if (avail < 0) PREROLL_FALLBACK_MS else PREROLL_MAX_MS
        if (avail < PREROLL_FRAMES && elapsed < limit && !inputEnded) return
        prerollDone = true
    }

    private fun applyPause() {
        val r = AmlFelNative.setPaused(handle, true)
        if (r < 0) throw fallback("native pause failed: errno ${-r}")
        pausedApplied = true
    }

    /**
     * Slaves the hardware presentation clock (pcrscr) to ExoPlayer's (audio) playback position: set
     * unconditionally after a start/resume (lastPcrSyncRealtimeMs == 0), afterwards only when it has
     * drifted by [PCR_RESYNC_THRESHOLD_90K] or more (or when it cannot be read).
     */
    private fun slaveClock(positionUs: Long, now: Long) {
        if (!started || !sessionFirstFrame) return
        if (now - lastPcrSyncRealtimeMs < PCR_SYNC_INTERVAL_MS) return
        val forced = lastPcrSyncRealtimeMs == 0L
        lastPcrSyncRealtimeMs = now
        val target = toPts90k(positionUs)
        if (!forced) {
            // AMSTREAM_GET_PCRSCR is a stub in the amstream driver (always 0); tsync exposes the clock in sysfs.
            val current = readPcrscr()
            if (current > 0) {
                val drift = current.toInt() - target // u32 wrap-safe signed difference
                if (drift > -PCR_RESYNC_THRESHOLD_90K && drift < PCR_RESYNC_THRESHOLD_90K) return
            }
        }
        val r = AmlFelNative.setPcrscr(handle, target)
        if (r < 0 && !pcrErrorLogged) {
            pcrErrorLogged = true
            throw fallback("native clock correction failed: errno ${-r}")
        }
    }

    /** tsync system clock pcrscr (90 kHz, u32) from /sys/class/tsync/pts_pcrscr ("0x..."), or -1 if unreadable. */
    private fun readPcrscr(): Long = runCatching {
        java.io.File(PCRSCR_SYSFS).readText().trim().removePrefix("0x").toLong(16) and 0xffffffffL
    }.getOrDefault(-1L)

    /**
     * A decoder that stops never recovers. After startup, progress requires a changed VPTS while playing: with feed
     * pacing the buffer no longer fills, so a stopped decoder shows as a frozen VPTS rather than a blocked write.
     */
    private fun checkStall(now: Long) {
        if (!started || inputEnded) return
        if (now - lastVptsPollMs >= VPTS_POLL_MS) {
            lastVptsPollMs = now
            val vpts = AmlFelNative.getVpts(handle)
            if (vpts >= 0L && vpts != lastPolledVpts) { // a failed read (-errno) is not progress
                lastPolledVpts = vpts
                lastProgressRealtimeMs = now
            }
        }
        if (now - lastProgressRealtimeMs < STALL_TIMEOUT_MS) return
        throw fallback("no decoder/presentation progress for ${now - lastProgressRealtimeMs} ms")
    }

    // --- errors ---

    private fun failure(operation: String, code: Long): ExoPlaybackException = failure(operation, code.toInt())

    private fun failure(operation: String, code: Int): ExoPlaybackException {
        closeSession()
        detachSideband()
        return createRendererException(
            AmlFelFallbackException("native FEL $operation failed: errno ${-code}"),
            inputFormat,
            PlaybackException.ERROR_CODE_DECODING_FAILED
        )
    }

    private fun fallback(reason: String): ExoPlaybackException {
        Log.i(TAG, "fallback: $reason")
        closeSession()
        detachSideband() // the normal path draws buffers into this Surface again
        return createRendererException(
            AmlFelFallbackException(reason),
            inputFormat,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
        )
    }
}
