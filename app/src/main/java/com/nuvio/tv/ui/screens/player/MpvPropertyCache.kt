package com.nuvio.tv.ui.screens.player

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * The last value mpv reported for each observed property. Reading one never waits on mpv's core, which a
 * synchronous property read does: behind a stalled stream that wait froze the screen for seconds.
 * A null value means mpv reported the property as unavailable.
 */
internal class MpvPropertyCache(private val clockMs: () -> Long) {

    private val values = ConcurrentHashMap<String, Any>()

    @Volatile
    private var pendingSeek: PendingSeek? = null

    /** False from a seek until mpv itself reports a position; the target set at the seek is only a stand-in. */
    @Volatile
    var positionReportedSinceSeek = true
        private set

    fun put(name: String, value: Any?) {
        val seek = pendingSeek
        if (seek != null && name == MpvCachedProperty.SEEKING && value == true) {
            // From here mpv reports the seek itself, so its positions are current again.
            pendingSeek = null
        } else if (seek != null && (name == MpvCachedProperty.TIME_POS || name == MpvCachedProperty.AUDIO_PTS)) {
            val landed = value is Double && abs(value - seek.targetSec) <= SEEK_LANDED_MARGIN_SEC
            // Reports already on their way from before the seek would pull the position back for a moment.
            if (!landed && clockMs() - seek.requestedAtMs < SEEK_POSITION_WAIT_MS) return
            if (name == MpvCachedProperty.TIME_POS) pendingSeek = null
        }
        if (value == null) values.remove(name) else values[name] = value
        if (name == MpvCachedProperty.TIME_POS && value != null) positionReportedSinceSeek = true
    }

    fun clear() {
        values.clear()
        pendingSeek = null
        positionReportedSinceSeek = true
    }

    /** The position moves to the seek target at once, as a synchronous read during the seek reports it. */
    fun onSeekRequested(targetSec: Double) {
        pendingSeek = PendingSeek(targetSec, clockMs())
        positionReportedSinceSeek = false
        values[MpvCachedProperty.TIME_POS] = targetSec
        values.remove(MpvCachedProperty.AUDIO_PTS)
    }

    fun double(name: String): Double? = (values[name] as? Number)?.toDouble()

    fun long(name: String): Long? = (values[name] as? Number)?.toLong()

    fun boolean(name: String): Boolean? = values[name] as? Boolean

    fun string(name: String): String? = values[name] as? String

    fun positionMs(): Long {
        val timePos = double(MpvCachedProperty.TIME_POS) ?: 0.0
        val seconds = mpvPlaybackPositionSec(
            timePosSec = timePos,
            audioPtsSec = double(MpvCachedProperty.AUDIO_PTS),
            seeking = boolean(MpvCachedProperty.SEEKING) == true,
            audioDelaySec = double(MpvCachedProperty.AUDIO_DELAY) ?: 0.0
        )
        return (seconds * 1000.0).roundToLong().coerceAtLeast(0L)
    }

    fun durationMs(): Long {
        val seconds = double(MpvCachedProperty.DURATION) ?: 0.0
        return (seconds * 1000.0).roundToLong().coerceAtLeast(0L)
    }

    private class PendingSeek(val targetSec: Double, val requestedAtMs: Long)

    private companion object {
        /** Close enough to the target to be a report from after the seek; a ten second skip is well outside it. */
        const val SEEK_LANDED_MARGIN_SEC = 1.5
        const val SEEK_POSITION_WAIT_MS = 2_000L
    }
}

internal enum class MpvCachedFormat { FLAG, INT64, DOUBLE, STRING }

internal object MpvCachedProperty {
    const val DURATION = "duration"
    const val TIME_POS = "time-pos"
    const val AUDIO_PTS = "audio-pts"
    const val AUDIO_DELAY = "audio-delay"
    const val PAUSE = "pause"
    const val PAUSED_FOR_CACHE = "paused-for-cache"
    const val SEEKING = "seeking"
    const val CORE_IDLE = "core-idle"
    const val IDLE_ACTIVE = "idle-active"
    const val EOF_REACHED = "eof-reached"
    const val SEEKABLE = "seekable"
    const val DEMUXER_CACHE_DURATION = "demuxer-cache-duration"
    const val PATH = "path"
    const val VID = "vid"
    const val SUB_TEXT = "sub-text"
    const val FRAME_DROP_COUNT = "frame-drop-count"
    const val DECODER_FRAME_DROP_COUNT = "decoder-frame-drop-count"
    const val VIDEO_GAMMA = "video-params/gamma"
    const val AUDIO_OUT_CHANNEL_COUNT = "audio-out-params/channel-count"

    val all: Map<String, MpvCachedFormat> = linkedMapOf(
        DURATION to MpvCachedFormat.DOUBLE,
        TIME_POS to MpvCachedFormat.DOUBLE,
        AUDIO_PTS to MpvCachedFormat.DOUBLE,
        AUDIO_DELAY to MpvCachedFormat.DOUBLE,
        PAUSE to MpvCachedFormat.FLAG,
        PAUSED_FOR_CACHE to MpvCachedFormat.FLAG,
        SEEKING to MpvCachedFormat.FLAG,
        CORE_IDLE to MpvCachedFormat.FLAG,
        IDLE_ACTIVE to MpvCachedFormat.FLAG,
        EOF_REACHED to MpvCachedFormat.FLAG,
        SEEKABLE to MpvCachedFormat.FLAG,
        DEMUXER_CACHE_DURATION to MpvCachedFormat.DOUBLE,
        PATH to MpvCachedFormat.STRING,
        VID to MpvCachedFormat.STRING,
        SUB_TEXT to MpvCachedFormat.STRING,
        FRAME_DROP_COUNT to MpvCachedFormat.INT64,
        DECODER_FRAME_DROP_COUNT to MpvCachedFormat.INT64,
        VIDEO_GAMMA to MpvCachedFormat.STRING,
        AUDIO_OUT_CHANNEL_COUNT to MpvCachedFormat.INT64
    )
}
