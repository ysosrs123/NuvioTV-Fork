package com.nuvio.tv.ui.screens.player

/**
 * Catches an MPV start or resume that shows a picture but never moves on, such as a bitstream the output takes
 * but never plays. Watches the first [WINDOW_MS] after a start; fires once when the position has not moved for
 * [STALL_MS] while playback should be running.
 */
internal class MpvNoProgressCheck {

    private var armedAtMs: Long? = null
    private var fired = false
    private var lastPositionMs = -1L
    private var stillSinceMs = 0L
    private var lastStepMs = -1L

    fun arm(nowMs: Long) {
        armedAtMs = nowMs
        fired = false
        lastPositionMs = -1L
        stillSinceMs = nowMs
        lastStepMs = -1L
    }

    fun disarm() {
        armedAtMs = null
    }

    /** True once, when the stall is found. */
    fun step(
        nowMs: Long,
        positionMs: Long,
        playing: Boolean,
        userPaused: Boolean,
        pausedForCache: Boolean,
        seeking: Boolean
    ): Boolean {
        val armedAt = armedAtMs ?: return false
        if (fired) return false
        if (nowMs - armedAt > WINDOW_MS) {
            armedAtMs = null
            return false
        }
        // A gap means the progress loop was stopped, for a pause for example; the stall clock starts again.
        val gap = lastStepMs >= 0L && nowMs - lastStepMs > MAX_STEP_GAP_MS
        lastStepMs = nowMs
        if (gap || !playing || userPaused || pausedForCache || seeking || positionMs != lastPositionMs) {
            lastPositionMs = positionMs
            stillSinceMs = nowMs
            return false
        }
        if (nowMs - stillSinceMs < STALL_MS) return false
        fired = true
        armedAtMs = null
        return true
    }

    companion object {
        const val WINDOW_MS = 30_000L
        const val STALL_MS = 6_000L
        const val MAX_STEP_GAP_MS = 1_000L
    }
}
