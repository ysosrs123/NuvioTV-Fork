package com.nuvio.tv.core.player

import android.annotation.SuppressLint
import android.content.Context
import android.content.IntentFilter
import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.delay
import kotlin.math.max

/**
 * How long playback waits after a display mode request before it starts. At least two seconds (or the viewer's
 * own delay), then until Android reports the HDMI audio link up again, three seconds more at most. A box drops
 * HDMI audio while the TV re-syncs; starting before it is back loses the first moment and can open audio on a
 * dead link. A TV panel, or a box that never reports the link, just waits the minimum.
 */
object AfrSettle {
    private const val TAG = "AfrSettle"
    const val MIN_SETTLE_MS = 2_000L
    const val MAX_USER_DELAY_MS = 10_000L
    private const val HDMI_EXTRA_WAIT_MS = 3_000L
    private const val POLL_MS = 100L

    fun minimumMs(userDelayMs: Long): Long = max(MIN_SETTLE_MS, userDelayMs.coerceIn(0L, MAX_USER_DELAY_MS))

    fun capMs(userDelayMs: Long): Long = minimumMs(userDelayMs) + HDMI_EXTRA_WAIT_MS

    /** [hdmiPlugState]: 1 up, 0 down, null when the box gives no report. */
    internal fun settled(sinceRequestMs: Long, hdmiPlugState: Int?, userDelayMs: Long): Boolean = when {
        sinceRequestMs >= capMs(userDelayMs) -> true
        sinceRequestMs < minimumMs(userDelayMs) -> false
        else -> hdmiPlugState != 0
    }

    /** Suspends until the TV is taken to have settled after a request made at [requestedAtElapsed]. */
    suspend fun awaitSettled(context: Context, requestedAtElapsed: Long, userDelayMs: Long): Long {
        while (true) {
            val since = SystemClock.elapsedRealtime() - requestedAtElapsed
            if (since >= minimumMs(userDelayMs)) {
                val state = hdmiPlugState(context)
                if (settled(since, state, userDelayMs)) {
                    Log.d(TAG, "settled after ${since}ms (hdmiAudio=${state ?: "no report"})")
                    return since
                }
                delay(POLL_MS)
            } else {
                delay(minimumMs(userDelayMs) - since)
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun hdmiPlugState(context: Context): Int? = runCatching {
        context.registerReceiver(null, IntentFilter(AudioManager.ACTION_HDMI_AUDIO_PLUG))
            ?.getIntExtra(AudioManager.EXTRA_AUDIO_PLUG_STATE, -1)
            ?.takeIf { it == 0 || it == 1 }
    }.getOrNull()
}
