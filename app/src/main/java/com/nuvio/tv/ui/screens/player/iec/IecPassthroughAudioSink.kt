package com.nuvio.tv.ui.screens.player.iec

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.extractor.DtsUtil
import java.nio.ByteBuffer
import java.util.ArrayDeque

/**
 * HDMI HBR passthrough that packs TrueHD / DTS-HD / DTS:X into IEC 61937 and
 * writes a CBR [AudioFormat.ENCODING_IEC61937] track.
 *
 * Android's RAW packer (`ENCODING_DOLBY_TRUEHD` / `ENCODING_DTS_HD`) is
 * byte-paced, so silence sprints and the media clock drifts. IEC bursts are
 * constant-rate at 192 kHz, so written frames equal content time.
 *
 * Formats this sink does not pack (AC-3, E-AC-3, DTS core, PCM) go through
 * the wrapped [AudioSink] unchanged. If IEC HBR cannot be opened, the same
 * wrapped sink is used — codecs are never rejected here.
 */
internal class IecPassthroughAudioSink(
    sink: AudioSink,
    private val trackFactory: IecAudioTrackFactory = PlatformIecAudioTrackFactory(),
    private val hbrIecEnabled: Boolean = true,
    private val onDiagnosticEvent: ((String) -> Unit)? = null,
    private val onIecBecameReady: (() -> Unit)? = null
) : ForwardingAudioSink(sink) {

    private val matPacker = TrueHdMatPacker()
    private var iecTrack: IecAudioTrack? = null
    private var mode: Mode = Mode.FORWARD
    private val pendingFrames = ArrayDeque<ByteArray>()
    // DTS-HD bursts handed back after they were written; MAT frames go back to matPacker.
    private val dtsBurstPool = ArrayDeque<ByteArray>()
    private var pendingOffset: Int = 0
    private var leftover: ByteArray = ByteArray(0)
    private var startPtsUs: Long = C.TIME_UNSET
    // PTS of the first buffer after a reset, kept only to report how far the anchor moved.
    private var firstBufferPtsUs: Long = C.TIME_UNSET
    private var discardedAuSinceReset: Int = 0
    private var writtenFrames: Long = 0L
    private var headAnchorFrames: Long = 0L
    private var playing: Boolean = false
    private var handledEndOfStream: Boolean = false
    private var audioSessionId: Int = 0
    private var volume: Float = 1f
    private var dtsChannelCount: Int = 8
    private var configuredFormat: Format? = null
    private var configuredBufferSize: Int = 0
    private var configuredOutputChannels: IntArray? = null
    private var iecFailedThisSession: Boolean = false
    private var consecutiveWriteStalls: Int = 0
    private var totalWriteStalls: Long = 0L
    private var lastHealthNanos: Long = 0L
    private var lastHealthUnderruns: Int = -1
    private var tunnelingRequested: Boolean = false
    // Whether the open IEC track was created with FLAG_HW_AV_SYNC. The request can change
    // (disableTunneling) while the track lives on, so diagnostics report this, not the request.
    private var iecTrackHwAvSync: Boolean = false
    private var sinkListener: AudioSink.Listener? = null
    // Wall-clock start of the current hw_av_sync track's playback, for the drain bound in
    // hasPendingData(); zero until the first write while playing, reset with the track.
    private var hwAvSyncPlayStartNanos: Long = 0L
    // Injectable for tests; production reads System.nanoTime().
    internal var nanoTime: () -> Long = System::nanoTime

    init {
        trackFactory.setReadyListener { onIecBecameReady?.invoke() }
        // The probe opens a direct stream; a sink that cannot use IEC must not pay for it.
        if (hbrIecEnabled) trackFactory.startProbe()
    }

    val isIecActive: Boolean
        get() = mode != Mode.FORWARD && iecTrack != null

    fun diagnosticRawLine(): String {
        val track = iecTrack
        return "iec_state mode=$mode active=$isIecActive tunneling=$tunnelingRequested playing=$playing " +
            "payload=${track?.payload ?: "none"} session=${track?.audioSessionId?.takeIf { it > 0 } ?: audioSessionId} " +
            "hwAvSync=$iecTrackHwAvSync written=$writtenFrames head=${track?.playbackHeadFrames() ?: -1} " +
            "pending=${pendingFrames.size} leftover=${leftover.size} startPtsUs=$startPtsUs " +
            "stalls=$totalWriteStalls failed=$iecFailedThisSession"
    }

    private fun emitIec(op: String) {
        onDiagnosticEvent?.invoke("$op ${diagnosticRawLine()}")
    }

    fun claimsHbr(format: Format): Boolean {
        return hbrIecEnabled && !iecFailedThisSession && isHbrPassthrough(format) && iecAvailable(format)
    }

    override fun setListener(listener: AudioSink.Listener) {
        sinkListener = listener
        super.setListener(listener)
    }

    // Once IEC has failed in this session the format is answered by the wrapped sink, the same
    // way claimsHbr already does, so a recovery re-selects RAW or a decoder instead of IEC.
    override fun getFormatSupport(format: Format): Int {
        if (!iecFailedThisSession && isHbrPassthrough(format) && iecAvailable(format)) {
            return AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        }
        return super.getFormatSupport(format)
    }

    override fun supportsFormat(format: Format): Boolean {
        if (!iecFailedThisSession && isHbrPassthrough(format) && iecAvailable(format)) return true
        return super.supportsFormat(format)
    }

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        configuredFormat = inputFormat
        configuredBufferSize = specifiedBufferSize
        configuredOutputChannels = outputChannels
        releaseIec()
        // IEC61937 AudioTrack.Builder can block for seconds on HALs that advertise
        // the encoding then reject the track. Never wait for that on this thread:
        // TrueHD may use DOLBY_MAT immediately; IEC only if a background probe
        // already proved it initializes. DTS-HD uses RAW until then.
        val tryCustomHbr = hbrIecEnabled && !iecFailedThisSession &&
            (isTrueHd(inputFormat) || (isHbrPassthrough(inputFormat) && trackFactory.iec61937Ready()))
        if (tryCustomHbr && startIec(inputFormat)) {
            android.util.Log.i(
                "IecPassthrough",
                "HBR active payload=${iecTrack?.payload} mime=${inputFormat.sampleMimeType} " +
                    "hwAvSync=$iecTrackHwAvSync"
            )
            onDiagnosticEvent?.invoke(
                "iec_hbr_active payload=${iecTrack?.payload} mime=${inputFormat.sampleMimeType} " +
                    "hwAvSync=$iecTrackHwAvSync"
            )
            return
        }
        mode = Mode.FORWARD
        if (isHbrPassthrough(inputFormat)) {
            android.util.Log.i(
                "IecPassthrough",
                "HBR RAW mime=${inputFormat.sampleMimeType} (compressed, not PCM)"
            )
            onDiagnosticEvent?.invoke(
                "iec_hbr_raw_fallback mime=${inputFormat.sampleMimeType} " +
                    "iecFailedThisSession=$iecFailedThisSession tunneling=$tunnelingRequested"
            )
        }
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int
    ): Boolean {
        if (mode == Mode.FORWARD) {
            return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        }
        if (iecTrack == null) {
            if (!playing) return false
            if (!ensureIecTrack()) {
                return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
            }
        }
        maybeReportHealth()
        if (firstBufferPtsUs == C.TIME_UNSET && presentationTimeUs != C.TIME_UNSET) {
            firstBufferPtsUs = presentationTimeUs
        }
        // DTS-HD packs every access unit, so the first buffer is the anchor. TrueHD anchors in
        // handleTrueHd on the first unit the MAT packer accepts: after a flush the packer discards
        // units until a major sync (the spec allows 128 between syncs, ~107 ms), and anchoring on
        // the first buffer would start the clock early by that span. Audio then leads video by
        // the discarded time for the rest of the segment; media3's discontinuity tolerance
        // (200 ms) never corrects it.
        if (mode == Mode.DTS_HD && startPtsUs == C.TIME_UNSET && presentationTimeUs != C.TIME_UNSET) {
            startPtsUs = presentationTimeUs
        }
        if (!drainPending()) return false
        val accepted = when (mode) {
            Mode.TRUEHD -> handleTrueHd(buffer, presentationTimeUs)
            Mode.DTS_HD -> handleDtsHd(buffer)
            Mode.FORWARD -> super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        }
        drainPending()
        return accepted
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        if (mode == Mode.FORWARD) return super.getCurrentPositionUs(sourceEnded)
        val track = iecTrack ?: return AudioSink.CURRENT_POSITION_NOT_SET
        if (writtenFrames == 0L || startPtsUs == C.TIME_UNSET) {
            return AudioSink.CURRENT_POSITION_NOT_SET
        }
        val head = minOf(track.playbackHeadFrames(), writtenFrames) - headAnchorFrames
        return startPtsUs + head * C.MICROS_PER_SECOND / track.sampleRate
    }

    override fun play() {
        playing = true
        if (mode == Mode.FORWARD) {
            super.play()
            return
        }
        if (!ensureIecTrack()) {
            emitIec("iec_play")
            return
        }
        iecTrack?.play()
        // Data fed while paused (a seek while paused, then resume) reaches the track
        // without drainPending() running while playing, so start the drain clock here too.
        if (tunnelingRequested && hwAvSyncPlayStartNanos == 0L && writtenFrames > 0L) {
            hwAvSyncPlayStartNanos = nanoTime()
        }
        emitIec("iec_play")
    }

    override fun pause() {
        playing = false
        if (mode == Mode.FORWARD) {
            super.pause()
            return
        }
        if (tunnelingRequested) {
            resetIecState(keepTrack = false)
        } else {
            iecTrack?.pause()
        }
        emitIec("iec_pause")
    }

    override fun flush() {
        if (mode == Mode.FORWARD) {
            super.flush()
            return
        }
        if (tunnelingRequested) {
            resetIecState(keepTrack = false)
            if (ensureIecTrack() && playing) {
                iecTrack?.play()
            }
        } else {
            resetIecState(keepTrack = true)
            iecTrack?.flush()
        }
        emitIec("iec_flush")
    }

    override fun handleDiscontinuity() {
        if (mode == Mode.FORWARD) {
            super.handleDiscontinuity()
            return
        }
        headAnchorFrames = iecTrack?.playbackHeadFrames() ?: 0L
        startPtsUs = C.TIME_UNSET
        firstBufferPtsUs = C.TIME_UNSET
        discardedAuSinceReset = 0
        emitIec("iec_discontinuity")
    }

    override fun reset() {
        releaseIec()
        mode = Mode.FORWARD
        tunnelingRequested = false
        super.reset()
    }

    override fun release() {
        releaseIec()
        mode = Mode.FORWARD
        super.release()
    }

    override fun playToEndOfStream() {
        if (mode != Mode.FORWARD) {
            drainPending()
            handledEndOfStream = true
        } else {
            super.playToEndOfStream()
        }
    }

    override fun isEnded(): Boolean {
        return if (mode != Mode.FORWARD) {
            handledEndOfStream && !hasPendingData()
        } else {
            super.isEnded()
        }
    }

    override fun hasPendingData(): Boolean {
        if (mode == Mode.FORWARD) return super.hasPendingData()
        if (pendingFrames.isNotEmpty() || leftover.isNotEmpty()) return true
        val track = iecTrack ?: return false
        if (writtenFrames <= track.playbackHeadFrames()) return false
        if (!tunnelingRequested) return true
        // Under hw_av_sync the head is HAL-dependent: it advances on some devices and never
        // moves on others, and an always-true answer makes isEnded() unreachable. Trust a
        // head that reports the track drained; otherwise bound the wait by wall clock, with
        // an allowance for a HAL that holds output while it establishes sync.
        val start = hwAvSyncPlayStartNanos
        if (start == 0L) return true
        val playedFrames = (nanoTime() - start) / 1_000L * track.sampleRate / 1_000_000L
        val allowanceFrames = HW_AV_SYNC_DRAIN_ALLOWANCE_US * track.sampleRate / 1_000_000L
        return playedFrames < writtenFrames + allowanceFrames
    }

    override fun setAudioSessionId(audioSessionId: Int) {
        this.audioSessionId = audioSessionId
        if (!isIecActive) super.setAudioSessionId(audioSessionId)
    }

    override fun enableTunnelingV21() {
        tunnelingRequested = true
        super.enableTunnelingV21()
        if (!isIecActive) return
        val format = configuredFormat ?: return
        releaseIec()
        if (!startIec(format)) {
            mode = Mode.FORWARD
            super.configure(format, configuredBufferSize, configuredOutputChannels)
        }
    }

    override fun disableTunneling() {
        tunnelingRequested = false
        super.disableTunneling()
    }

    override fun setVolume(volume: Float) {
        this.volume = volume
        if (isIecActive) {
            iecTrack?.setVolume(volume)
        } else {
            super.setVolume(volume)
        }
    }

    override fun getAudioTrackBufferSizeUs(): Long {
        if (!isIecActive) return super.getAudioTrackBufferSizeUs()
        val track = iecTrack ?: return C.TIME_UNSET
        // Two MAT frames (40 ms) or four DTS-HD bursts (~43 ms).
        val bytes = if (mode == Mode.TRUEHD) {
            TrueHdMatPacker.MAT_BUFFER_SIZE * 2
        } else {
            (8192 shl 2) * 4
        }
        val frames = bytes / track.frameSizeBytes
        return frames * C.MICROS_PER_SECOND / track.sampleRate
    }

    private fun iecAvailable(format: Format): Boolean {
        if (!hbrIecEnabled) return false
        return trackFactory.canOpen(IEC_SAMPLE_RATE, hbrIecChannelCount(format))
    }

    private fun openIec(format: Format): Boolean {
        val channelCount = hbrIecChannelCount(format)
        val frameBytes = if (format.sampleMimeType == MimeTypes.AUDIO_TRUEHD) {
            TrueHdMatPacker.MAT_BUFFER_SIZE
        } else {
            Iec61937Packer.dtsHdIecPeriod(channelCount, 512) shl 2
        }
        val bufferBytes = frameBytes * if (format.sampleMimeType == MimeTypes.AUDIO_TRUEHD) 2 else 4
        val targetBufferBytes = IEC_BUFFER_TARGET_MS * IEC_SAMPLE_RATE / 1000 * channelCount * 2
        val track = trackFactory.openHbr(
            sampleRate = IEC_SAMPLE_RATE,
            channelCount = channelCount,
            bufferSizeBytes = maxOf(bufferBytes, targetBufferBytes),
            sessionId = audioSessionId,
            trueHd = format.sampleMimeType == MimeTypes.AUDIO_TRUEHD,
            hwAvSync = tunnelingRequested
        ) ?: return false
        track.setVolume(volume)
        iecTrack = track
        iecTrackHwAvSync = tunnelingRequested
        val session = track.audioSessionId.takeIf { it > 0 } ?: audioSessionId
        if (session > 0) {
            sinkListener?.onAudioSessionIdChanged(session)
        }
        return true
    }

    private fun startIec(format: Format): Boolean {
        if (!openIec(format)) return false
        mode = if (isTrueHd(format)) Mode.TRUEHD else Mode.DTS_HD
        dtsChannelCount = format.channelCount.takeIf { it > 0 } ?: 8
        return true
    }

    private fun ensureIecTrack(): Boolean {
        if (iecTrack != null) return true
        val format = configuredFormat ?: return false
        if (startIec(format)) {
            emitIec("iec_reopen")
            return true
        }
        emitIec("iec_reopen_failed")
        mode = Mode.FORWARD
        super.configure(format, configuredBufferSize, configuredOutputChannels)
        if (playing) super.play()
        return false
    }

    private fun hbrIecChannelCount(format: Format): Int {
        if (format.sampleMimeType == MimeTypes.AUDIO_TRUEHD) return 8
        val count = format.channelCount
        return if (count > 0) Iec61937Packer.dtsHdChannelMask(count) else 8
    }

    private fun handleTrueHd(buffer: ByteBuffer, presentationTimeUs: Long): Boolean {
        val carried = leftover.size
        val data = concat(leftover, buffer)
        var offset = 0
        var discardedInBuffer = 0
        while (offset + 10 <= data.size) {
            val auSize = TrueHdMatPacker.trueHdAccessUnitSize(data, offset)
            if (auSize < 10) {
                // Not an access unit. The extractor hands over access-unit-aligned samples, so
                // drop the remainder and resync on the next sample rather than carrying the bad
                // head forward under every later buffer (a frozen clock with no error).
                onDiagnosticEvent?.invoke(
                    "iec_truehd_resync auSize=$auSize dropped=${data.size - offset}"
                )
                offset = data.size
                break
            }
            if (offset + auSize > data.size) break
            val auStart = offset
            val au = data.copyOfRange(offset, offset + auSize)
            offset += auSize
            val wasSynced = matPacker.isSynced
            val frameReady = matPacker.packAccessUnit(au)
            if (startPtsUs == C.TIME_UNSET && presentationTimeUs != C.TIME_UNSET) {
                if (matPacker.isSynced) {
                    anchorTrueHd(presentationTimeUs, auStart >= carried, discardedInBuffer)
                } else if (!wasSynced) {
                    discardedAuSinceReset++
                    if (auStart >= carried) discardedInBuffer++
                }
            }
            if (frameReady) {
                while (matPacker.hasFrame()) {
                    val mat = matPacker.pollFrame()!!
                    pendingFrames.add(
                        if (iecTrack?.payload == HbrPayload.MAT) mat
                        else Iec61937Packer.packTrueHdInPlace(mat)
                    )
                }
            }
        }
        leftover = if (offset >= data.size) ByteArray(0) else data.copyOfRange(offset, data.size)
        buffer.position(buffer.limit())
        return true
    }

    // The buffer's PTS is that of its first access unit; the packer accepted the unit at index
    // [discardedInBuffer] (every earlier unit in this buffer was discarded), so the clock starts
    // at PTS + index * unit duration. A unit that began in the carried leftover belongs to the
    // previous buffer's time; anchoring on this buffer's PTS is then the closest available.
    private fun anchorTrueHd(bufferPtsUs: Long, startedInBuffer: Boolean, discardedInBuffer: Int) {
        val offsetUs = if (startedInBuffer) {
            discardedInBuffer * 40L * C.MICROS_PER_SECOND / matPacker.baseSampleRate()
        } else {
            0L
        }
        startPtsUs = bufferPtsUs + offsetUs
        val deltaUs = if (firstBufferPtsUs != C.TIME_UNSET) startPtsUs - firstBufferPtsUs else 0L
        val line = "iec_anchor mode=TRUEHD bufferPts=$bufferPtsUs discardedAu=$discardedAuSinceReset " +
            "inBuffer=$discardedInBuffer anchorPts=$startPtsUs deltaUs=$deltaUs " +
            "leftoverFallback=${!startedInBuffer}"
        onDiagnosticEvent?.invoke(line)
        android.util.Log.i("IecPassthrough", line)
    }

    private fun handleDtsHd(buffer: ByteBuffer): Boolean {
        // DtsUtil's byte[] overload reads indices 0 and 4..7 only, so it gets just that head
        // rather than a copy of the whole access unit. A unit shorter than eight bytes gives a
        // head exactly as short, which keeps the original out-of-bounds-to-512 behaviour.
        val head = ByteArray(buffer.remaining().coerceAtMost(8))
        val position = buffer.position()
        buffer.get(head)
        buffer.position(position)
        val sampleCount = try {
            DtsUtil.parseDtsAudioSampleCount(head)
        } catch (_: Exception) {
            512
        }
        val period = Iec61937Packer.dtsHdIecPeriod(dtsChannelCount, sampleCount)
        val burst = acquireDtsBurst(period shl 2)
        Iec61937Packer.packDtsHdInto(buffer, period, burst)
        pendingFrames.add(burst)
        return true
    }

    private fun acquireDtsBurst(size: Int): ByteArray {
        val pooled = dtsBurstPool.poll()
        return if (pooled != null && pooled.size == size) pooled else ByteArray(size)
    }

    // Pending frames always belong to the current mode: resetIecState and drainPending run
    // before mode changes, so routing by mode is exact (a 960-sample 8-channel DTS burst is
    // 61440 bytes too, which is why size alone would not be).
    private fun recycleFrame(frame: ByteArray) {
        if (mode == Mode.TRUEHD) {
            matPacker.recycleFrame(frame)
        } else if (dtsBurstPool.size < FRAME_POOL_LIMIT) {
            dtsBurstPool.add(frame)
        }
    }

    private fun drainPending(): Boolean {
        val track = iecTrack ?: return true
        if (playing) {
            track.play()
            if (tunnelingRequested && hwAvSyncPlayStartNanos == 0L) {
                hwAvSyncPlayStartNanos = nanoTime()
            }
        }
        while (pendingFrames.isNotEmpty()) {
            val frame = pendingFrames.first()
            val timestampNs = if (tunnelingRequested && startPtsUs != C.TIME_UNSET) {
                startPtsUs * 1000L + writtenFrames * 1_000_000_000L / track.sampleRate
            } else {
                -1L
            }
            while (pendingOffset < frame.size) {
                val written = track.write(frame, pendingOffset, frame.size - pendingOffset, timestampNs)
                if (written < 0) {
                    return fallbackToWrappedSink("write_error code=$written")
                }
                if (written == 0) {
                    // A paused track keeps a full buffer by design; only stalls while the track
                    // should be draining count towards giving up on IEC.
                    if (playing) {
                        totalWriteStalls++
                        if (++consecutiveWriteStalls >= MAX_WRITE_STALLS) {
                            return fallbackToWrappedSink("write_stalls=$consecutiveWriteStalls")
                        }
                    }
                    return false
                }
                consecutiveWriteStalls = 0
                pendingOffset += written
                writtenFrames += written / track.frameSizeBytes
            }
            recycleFrame(pendingFrames.removeFirst())
            pendingOffset = 0
        }
        return true
    }

    private fun fallbackToWrappedSink(reason: String): Boolean {
        val format = configuredFormat
        android.util.Log.w("IecPassthrough", "IEC write failed; falling back to RAW")
        onDiagnosticEvent?.invoke("iec_fallback_to_raw reason=$reason mime=${format?.sampleMimeType}")
        trackFactory.markIecUnusable()
        iecFailedThisSession = true
        resetIecState(keepTrack = false)
        mode = Mode.FORWARD
        if (format != null) {
            super.reset()
            try {
                super.configure(format, configuredBufferSize, configuredOutputChannels)
            } catch (e: AudioSink.ConfigurationException) {
                // This runs inside handleBuffer, where media3 catches only InitializationException
                // and WriteException; a ConfigurationException escaping here reaches the playback
                // thread uncaught and ends the process. Surface the refusal as a recoverable write
                // failure instead: the renderer reports it and the recovery re-selects tracks with
                // IEC already marked unusable, so the format is decoded from then on.
                onDiagnosticEvent?.invoke(
                    "iec_fallback_configure_refused mime=${format.sampleMimeType} reason=$reason"
                )
                throw AudioSink.WriteException(WRITE_ERROR_FALLBACK_REFUSED, format, true)
                    .apply { initCause(e) }
            }
            if (playing) super.play()
        }
        return true
    }

    private fun resetIecState(keepTrack: Boolean) {
        matPacker.reset()
        while (pendingFrames.isNotEmpty()) recycleFrame(pendingFrames.removeFirst())
        pendingOffset = 0
        leftover = ByteArray(0)
        startPtsUs = C.TIME_UNSET
        firstBufferPtsUs = C.TIME_UNSET
        discardedAuSinceReset = 0
        writtenFrames = 0L
        headAnchorFrames = 0L
        handledEndOfStream = false
        consecutiveWriteStalls = 0
        hwAvSyncPlayStartNanos = 0L
        if (!keepTrack) {
            iecTrack?.release()
            iecTrack = null
            iecTrackHwAvSync = false
            totalWriteStalls = 0L
            lastHealthNanos = 0L
            lastHealthUnderruns = -1
        }
    }

    // One line while IEC is active: at most every HEALTH_INTERVAL_NANOS, and at once when the
    // track's underrun count changes. The HUD and analytics underrun counters only see
    // DefaultAudioSink, which holds no track while IEC is active, so this is the only view of
    // the IEC track's health a user can produce from logcat.
    private fun maybeReportHealth() {
        val track = iecTrack ?: return
        val underruns = track.underrunCount()
        val now = System.nanoTime()
        if (underruns == lastHealthUnderruns && now - lastHealthNanos < HEALTH_INTERVAL_NANOS) return
        lastHealthNanos = now
        lastHealthUnderruns = underruns
        val line = "iec_health mode=$mode payload=${track.payload} underruns=$underruns " +
            "head=${track.playbackHeadFrames()} written=$writtenFrames " +
            "pending=${pendingFrames.size} stalls=$totalWriteStalls playing=$playing"
        android.util.Log.i("IecPassthrough", line)
        onDiagnosticEvent?.invoke(line)
    }

    private fun releaseIec() {
        resetIecState(keepTrack = false)
        mode = Mode.FORWARD
    }

    private enum class Mode { FORWARD, TRUEHD, DTS_HD }

    companion object {
        const val IEC_SAMPLE_RATE = 192_000
        // Target buffer for the app-packed IEC61937 track. The track is written from the
        // playback thread; a garbage-collection or scheduler stall on that thread that
        // outlasts the track's buffer starves the HAL and drops audio. Observed feeder
        // stalls reach several hundred milliseconds, while the default request (~40 ms) is
        // only floored/rounded up to ~60-85 ms of real headroom by the HALs measured. Ask
        // for enough to ride out the worst observed stall. createTrack falls back to the
        // HAL minimum if a device rejects the larger allocation, so this never reduces the
        // buffer or fails an open the default would have made.
        private const val IEC_BUFFER_TARGET_MS = 1_000
        internal const val MAX_WRITE_STALLS = 1_000
        // Reported as the WriteException error code when the wrapped sink refuses the format
        // during a fallback; not an AudioTrack return value.
        internal const val WRITE_ERROR_FALLBACK_REFUSED = -1_000
        private const val HEALTH_INTERVAL_NANOS = 5_000_000_000L
        // Wall-clock allowance past the written duration before a hw_av_sync track whose head
        // never advances is treated as drained; covers a HAL that holds output while syncing.
        private const val HW_AV_SYNC_DRAIN_ALLOWANCE_US = 1_500_000L
        private const val FRAME_POOL_LIMIT = 8

        fun isTrueHd(format: Format): Boolean {
            return format.sampleMimeType == MimeTypes.AUDIO_TRUEHD
        }

        fun isHbrPassthrough(format: Format): Boolean {
            val mime = format.sampleMimeType ?: return false
            return isTrueHd(format) ||
                mime == MimeTypes.AUDIO_DTS_HD ||
                mime == MimeTypes.AUDIO_DTS_X ||
                mime.startsWith("audio/vnd.dts.hd") ||
                mime.startsWith("audio/vnd.dts.uhd")
        }

        private fun concat(prefix: ByteArray, buffer: ByteBuffer): ByteArray {
            if (prefix.isEmpty() && buffer.hasArray() && buffer.arrayOffset() == 0 &&
                buffer.position() == 0 && buffer.remaining() == buffer.array().size
            ) {
                val copy = ByteArray(buffer.remaining())
                val pos = buffer.position()
                buffer.get(copy)
                buffer.position(pos)
                return copy
            }
            val combined = ByteArray(prefix.size + buffer.remaining())
            System.arraycopy(prefix, 0, combined, 0, prefix.size)
            val pos = buffer.position()
            buffer.get(combined, prefix.size, buffer.remaining())
            buffer.position(pos)
            return combined
        }
    }
}
