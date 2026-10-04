package com.nuvio.tv.ui.screens.player

import android.media.AudioDeviceInfo
import android.media.AudioTrack
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.nuvio.tv.ui.screens.player.iec.IecPassthroughAudioSink
import java.lang.reflect.Field
import kotlin.math.abs

private const val JITTER_MIN_INTERVAL_MS = 20L
private const val JITTER_MAX_WINDOW_MS = 250L
private const val JITTER_MAX_PLAUSIBLE_MS = 500L
private const val JITTER_EVENT_MS = 20L
private const val JITTER_MIN_SAMPLES = 25

/** How far the reported audio position strays from wall clock over one playback. */
internal data class AudioClockJitter(
    val samples: Int,
    val events: Int,
    val maxAbsMs: Long,
    val meanAbsMs: Long,
    val driftWindows: Int,
    val driftLastMs: Long,
    val driftMaxAbsMs: Long,
    val driftMeanAbsMs: Long
)

internal data class AudioRouteSnapshot(val deviceLabel: String, val changeCount: Int)

/**
 * Read-only figures for the stats overlay, plus the reduced start threshold for
 * platform DIRECT tracks. The platform track is reached by reflection on the media3
 * sink; while the IEC track is active the figures come from the IEC sink instead.
 */
internal class PlaybackAudioSinkMonitor(
    private val platformSink: DefaultAudioSink?,
    private val iecSink: IecPassthroughAudioSink?,
    private val reducedStartThresholdFrames: Int
) {

    @Volatile
    private var playbackActive: Boolean = false

    @Volatile
    private var jitterLastPosUs: Long = C.TIME_UNSET

    @Volatile
    private var jitterLastWallMs: Long = 0L

    @Volatile
    private var jitterSamples: Int = 0

    @Volatile
    private var jitterEvents: Int = 0

    @Volatile
    private var jitterMaxAbsMs: Long = 0L

    @Volatile
    private var jitterSumAbsMs: Long = 0L

    @Volatile
    private var driftWallAccumMs: Long = 0L

    @Volatile
    private var driftPosAccumMs: Long = 0L

    @Volatile
    private var driftExpectedAccumMs: Long = 0L

    @Volatile
    private var driftWindows: Int = 0

    @Volatile
    private var driftLastMs: Long = 0L

    @Volatile
    private var driftMaxAbsMs: Long = 0L

    @Volatile
    private var driftSumAbsMs: Long = 0L

    @Volatile
    private var audioTrackFieldLookupFailed: Boolean = false

    private var cachedAudioTrackField: Field? = null

    private var startThresholdAppliedThisTrack: Boolean = false

    // A new track re-baselines without counting: only a device change on the same
    // track is a route change.
    @Volatile
    private var routeLastTrackIdentity: Int = 0

    @Volatile
    private var routeLastDeviceId: Int = -1

    @Volatile
    private var routeChangeCount: Int = 0

    fun onTrackReset() {
        jitterLastPosUs = C.TIME_UNSET
        jitterLastWallMs = 0L
        jitterSamples = 0
        jitterEvents = 0
        jitterMaxAbsMs = 0L
        jitterSumAbsMs = 0L
        driftWallAccumMs = 0L
        driftPosAccumMs = 0L
        driftExpectedAccumMs = 0L
        driftWindows = 0
        driftLastMs = 0L
        driftMaxAbsMs = 0L
        driftSumAbsMs = 0L
        startThresholdAppliedThisTrack = false
    }

    /** Samples only count while the player is playing; a transition restarts the window. */
    fun setPlaybackActive(active: Boolean) {
        playbackActive = active
        jitterLastPosUs = C.TIME_UNSET
        jitterLastWallMs = 0L
        driftWallAccumMs = 0L
        driftPosAccumMs = 0L
        driftExpectedAccumMs = 0L
    }

    /** Called with the sink position the renderer has just read, on the playback thread. */
    fun onPositionRead(positionUs: Long, playbackSpeed: Float) {
        if (positionUs == AudioSink.CURRENT_POSITION_NOT_SET) return
        val nowMs = SystemClock.elapsedRealtime()
        val lastWall = jitterLastWallMs
        if (lastWall != 0L && nowMs - lastWall < JITTER_MIN_INTERVAL_MS) return

        val lastPos = jitterLastPosUs
        if (lastPos != C.TIME_UNSET && lastWall != 0L) {
            val wallDeltaMs = nowMs - lastWall
            val posDeltaMs = (positionUs - lastPos) / 1_000L
            val expectedMs = (wallDeltaMs * playbackSpeed).toLong()
            val absMs = abs(posDeltaMs - expectedMs)
            val counted = playbackActive &&
                wallDeltaMs in JITTER_MIN_INTERVAL_MS..JITTER_MAX_WINDOW_MS &&
                absMs <= JITTER_MAX_PLAUSIBLE_MS
            if (counted) {
                jitterSamples += 1
                jitterSumAbsMs += absMs
                if (absMs > jitterMaxAbsMs) jitterMaxAbsMs = absMs
                if (absMs >= JITTER_EVENT_MS) jitterEvents += 1
                driftWallAccumMs += wallDeltaMs
                driftPosAccumMs += posDeltaMs
                driftExpectedAccumMs += expectedMs
                if (driftWallAccumMs >= 1_000L) {
                    val driftMs = driftPosAccumMs - driftExpectedAccumMs
                    val driftAbs = abs(driftMs)
                    driftWindows += 1
                    driftLastMs = driftMs
                    driftSumAbsMs += driftAbs
                    if (driftAbs > driftMaxAbsMs) driftMaxAbsMs = driftAbs
                    driftWallAccumMs = 0L
                    driftPosAccumMs = 0L
                    driftExpectedAccumMs = 0L
                }
            }
        }
        jitterLastPosUs = positionUs
        jitterLastWallMs = nowMs
    }

    fun audioClockJitter(): AudioClockJitter? {
        val n = jitterSamples
        if (n < JITTER_MIN_SAMPLES) return null
        val w = driftWindows
        return AudioClockJitter(
            samples = n,
            events = jitterEvents,
            maxAbsMs = jitterMaxAbsMs,
            meanAbsMs = jitterSumAbsMs / n,
            driftWindows = w,
            driftLastMs = driftLastMs,
            driftMaxAbsMs = driftMaxAbsMs,
            driftMeanAbsMs = if (w > 0) driftSumAbsMs / w else 0L
        )
    }

    /**
     * Lets a DIRECT track start before its whole buffer has filled. The buffer size is
     * left alone. Applied once per track, after media3 has created it.
     */
    fun maybeApplyStartThreshold() {
        if (reducedStartThresholdFrames <= 0 || startThresholdAppliedThisTrack) return
        if (Build.VERSION.SDK_INT < 31) return
        if (iecSink?.isIecActive == true) return
        try {
            val track = platformAudioTrack() ?: return
            val bufferFrames = track.bufferSizeInFrames
            if (bufferFrames > 0) {
                val target = reducedStartThresholdFrames.coerceIn(1, (bufferFrames - 1).coerceAtLeast(1))
                val applied = track.setStartThresholdInFrames(target)
                Log.i(
                    TAG,
                    "start threshold requested=$reducedStartThresholdFrames target=$target " +
                        "applied=$applied buffer=$bufferFrames"
                )
                startThresholdAppliedThisTrack = true
            }
        } catch (t: Throwable) {
            Log.w(TAG, "start threshold not applied: ${t.message}")
            startThresholdAppliedThisTrack = true
        }
    }

    /** The output track's own underrun count, or null when no track can be read. */
    fun nativeAudioTrackUnderrunCount(): Int? {
        if (iecSink?.isIecActive == true) return iecSink.iecUnderrunCount()
        return try {
            platformAudioTrack()?.underrunCount
        } catch (t: Throwable) {
            audioTrackFieldLookupFailed = true
            Log.w(TAG, "native AudioTrack underrun count unavailable: ${t.message}")
            null
        }
    }

    /** One sample of the output track's routed device. Called from the overlay sampler only. */
    fun sampleAudioRoute(): AudioRouteSnapshot? {
        return try {
            val identity: Int
            val device: AudioDeviceInfo?
            if (iecSink?.isIecActive == true) {
                identity = iecSink.iecTrackIdentity() ?: return null
                device = iecSink.iecRoutedDevice()
            } else {
                val track = platformAudioTrack() ?: return null
                identity = System.identityHashCode(track)
                device = track.routedDevice
            }
            val deviceId = device?.id ?: -1
            when {
                identity != routeLastTrackIdentity -> {
                    routeLastTrackIdentity = identity
                    routeLastDeviceId = deviceId
                }
                routeLastDeviceId != -1 && deviceId != routeLastDeviceId -> {
                    routeChangeCount += 1
                    routeLastDeviceId = deviceId
                }
                routeLastDeviceId == -1 -> routeLastDeviceId = deviceId
            }
            AudioRouteSnapshot(routeDeviceLabel(device), routeChangeCount)
        } catch (t: Throwable) {
            audioTrackFieldLookupFailed = true
            Log.w(TAG, "audio route sample unavailable: ${t.message}")
            null
        }
    }

    private fun platformAudioTrack(): AudioTrack? {
        val sink = platformSink ?: return null
        if (audioTrackFieldLookupFailed) return null
        val field = cachedAudioTrackField
            ?: DefaultAudioSink::class.java.getDeclaredField("audioTrack")
                .apply { isAccessible = true }
                .also { cachedAudioTrackField = it }
        return field.get(sink) as? AudioTrack
    }

    private fun routeDeviceLabel(device: AudioDeviceInfo?): String {
        val type = device?.type ?: return "none"
        return when (type) {
            AudioDeviceInfo.TYPE_HDMI -> "HDMI"
            AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI ARC"
            AudioDeviceInfo.TYPE_HDMI_EARC -> "HDMI eARC"
            AudioDeviceInfo.TYPE_LINE_DIGITAL -> "SPDIF"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speaker"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth"
            AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "Submix"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB"
            else -> "type $type"
        }
    }

    private companion object {
        const val TAG = "PassthroughSink"
    }
}
