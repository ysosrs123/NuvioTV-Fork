package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.local.SeekingMode

/**
 * A run of seek keys while the video plays. Playback holds where it is and every key goes on from the pending
 * target, so quick presses add up. The run ends with one seek [waitMs] after the last key, at once on OK, or
 * without a seek on Back. Only while thumbnails run for this playback: without one to look at the seek goes on
 * release. Gentle waits [HOLD_SETTLE_MS] after a tap too; Fast never waits. Confirm with OK opens a run paused too
 * and without thumbnails, and only OK seeks: after [CONFIRM_IDLE_MS] without a key the run ends without one.
 */
internal class SeekSettle {
    /** Playback position when the run began; null while no run is open. */
    var fromMs: Long? = null
        private set
    var presses = 0
        private set

    val open: Boolean get() = fromMs != null

    /** A seek key went down while playback stood at [playbackMs]. */
    fun press(playbackMs: Long) {
        if (fromMs == null) fromMs = playbackMs
        presses++
    }

    /** The last press turned out to be a TV's backlog after a hold, not a press of its own. */
    fun undoPress() {
        if (presses > 0) presses--
    }

    /** Closes the run; null when none was open. */
    fun end(): Run? {
        val from = fromMs ?: return null
        val run = Run(from, presses)
        fromMs = null
        presses = 0
        return run
    }

    class Run(val fromMs: Long, val presses: Int)

    companion object {
        /** Long enough for the next quick tap on a remote (200 to 400 ms apart). */
        const val TAP_SETTLE_MS = 800L
        /** After a hold there was a run of thumbnails to look at. */
        const val HOLD_SETTLE_MS = 2_000L
        /** Confirm with OK, paused: a run left this long is dropped. */
        const val CONFIRM_IDLE_MS = 15_000L
        /** Confirm with OK while the film plays on: a forgotten marker goes away sooner. */
        const val CONFIRM_PLAYING_IDLE_MS = 5_000L

        fun waitMs(afterHold: Boolean, mode: SeekingMode = SeekingMode.BALANCED, playing: Boolean = false): Long = when {
            mode == SeekingMode.FAST -> 0L
            mode == SeekingMode.CONFIRM -> if (playing) CONFIRM_PLAYING_IDLE_MS else CONFIRM_IDLE_MS
            afterHold || mode == SeekingMode.GENTLE -> HOLD_SETTLE_MS
            else -> TAP_SETTLE_MS
        }

        fun applies(
            open: Boolean,
            userPaused: Boolean,
            inParty: Boolean,
            playing: Boolean,
            thumbnails: Boolean,
            mode: SeekingMode = SeekingMode.BALANCED
        ): Boolean = open || when (mode) {
            SeekingMode.FAST -> false
            SeekingMode.CONFIRM -> !inParty
            else -> !userPaused && !inParty && playing && thumbnails
        }

        /**
         * Whether playback holds on the target while a run waits. On Confirm with OK the film plays on while the
         * marker moves, so one tap never leaves a frozen picture; OK jumps, Back cancels.
         */
        fun holdsDuringWait(mode: SeekingMode): Boolean = mode != SeekingMode.CONFIRM

        /** Whether a run that waits out [waitMs] ends with the seek; on Confirm with OK only OK seeks. */
        fun seeksAfterWait(mode: SeekingMode): Boolean = mode != SeekingMode.CONFIRM

        /** Whether a key release while paused goes on waiting rather than seeking at once. */
        fun waitsWhilePaused(mode: SeekingMode): Boolean = mode == SeekingMode.CONFIRM

        /**
         * Where a step of [deltaMs] lands: from the pending target while one is open, else from where playback is.
         * [gridStep] snaps the step to the thumbnail grid when thumbnails are on.
         */
        fun target(
            pendingMs: Long?,
            playbackMs: Long,
            deltaMs: Long,
            durationMs: Long,
            gridStep: (Long, Long) -> Long? = { _, _ -> null }
        ): Long {
            val base = pendingMs ?: playbackMs.coerceAtLeast(0L)
            return (gridStep(base, deltaMs) ?: (base + deltaMs))
                .coerceAtLeast(0L)
                .coerceAtMost(durationMs)
        }
    }
}
