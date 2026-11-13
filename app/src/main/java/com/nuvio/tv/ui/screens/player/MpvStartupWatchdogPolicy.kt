package com.nuvio.tv.ui.screens.player

internal object MpvStartupWatchdogPolicy {
    const val POLL_INTERVAL_MS = 500L
    const val SURFACE_WAIT_MS = 8_000L
    const val IDLE_AFTER_LOAD_MS = 4_000L
    const val STALL_TIMEOUT_MS = 20_000L
    const val ABSOLUTE_TIMEOUT_MS = 30_000L
    const val CACHE_GROWTH_TIMEOUT_MS = 120_000L
    const val MIN_CACHE_GROWTH_SEC = 0.25

    val surfaceWaitTicks: Int = (SURFACE_WAIT_MS / POLL_INTERVAL_MS).toInt()
    val idleTicksLimit: Int = (IDLE_AFTER_LOAD_MS / POLL_INTERVAL_MS).toInt()
    val stallTicksLimit: Int = (STALL_TIMEOUT_MS / POLL_INTERVAL_MS).toInt()
    val absoluteTicksLimit: Int = (ABSOLUTE_TIMEOUT_MS / POLL_INTERVAL_MS).toInt()
    val cacheGrowthTicksLimit: Int = (CACHE_GROWTH_TIMEOUT_MS / POLL_INTERVAL_MS).toInt()

    enum class Action {
        Continue,
        SurfaceTimeout,
        IdleError,
        StallTimeout,
        AbsoluteTimeout,
    }

    data class Counters(
        val surfaceWaitTicks: Int = 0,
        val idleTicks: Int = 0,
        val stallTicks: Int = 0,
        val absoluteTicks: Int = 0,
        val cacheGrowthTicks: Int = 0,
    )

    data class Input(
        val enabled: Boolean,
        val waitingForSurface: Boolean,
        val idleActive: Boolean,
        val cacheProgressing: Boolean,
        val counters: Counters,
    )

    data class Step(
        val action: Action,
        val counters: Counters,
    )

    /**
     * Until the first frame, the MPV progress tick is the only first-frame check and start-up
     * watchdog, so a pause must not stop it.
     */
    fun progressTickMayStop(hasRenderedFirstFrame: Boolean): Boolean = hasRenderedFirstFrame

    /** OK before the first frame keeps the start going, as ExoPlayer does while it buffers. */
    fun playPauseShouldPause(isPlaying: Boolean, hasRenderedFirstFrame: Boolean): Boolean =
        isPlaying && hasRenderedFirstFrame

    /** Coming back to an MPV start that never showed a frame: restart the tick. */
    fun restartTickOnResume(hasRenderedFirstFrame: Boolean, hasStream: Boolean, hasView: Boolean): Boolean =
        !hasRenderedFirstFrame && hasStream && hasView

    /** A watch party owns play and pause, so only a solo start is unpaused on resume. */
    fun unpauseOnResume(hasRenderedFirstFrame: Boolean, inParty: Boolean): Boolean =
        !hasRenderedFirstFrame && !inParty

    /**
     * The player's own pause state decides, not the manual-pause flag: a retry rebuilt with
     * startPaused still plays on MPV and must stay watched.
     */
    fun watchdogEnabled(
        hasError: Boolean,
        releasing: Boolean,
        failoverTriggered: Boolean,
        recoveryArmed: Boolean,
        hasStream: Boolean,
        inBackground: Boolean,
        playerPaused: Boolean,
    ): Boolean = !hasError &&
        !releasing &&
        !failoverTriggered &&
        !recoveryArmed &&
        hasStream &&
        !inBackground &&
        !playerPaused

    fun cacheIsProgressing(previousSec: Double, currentSec: Double): Boolean {
        return currentSec > previousSec + MIN_CACHE_GROWTH_SEC
    }

    fun step(input: Input): Step {
        if (!input.enabled) {
            return Step(Action.Continue, Counters())
        }
        if (input.waitingForSurface) {
            val next = input.counters.surfaceWaitTicks + 1
            if (next >= surfaceWaitTicks) {
                return Step(Action.SurfaceTimeout, Counters())
            }
            return Step(Action.Continue, Counters(surfaceWaitTicks = next))
        }

        val absolute = input.counters.absoluteTicks + 1
        if (input.idleActive) {
            val idle = input.counters.idleTicks + 1
            if (idle >= idleTicksLimit) {
                return Step(Action.IdleError, Counters())
            }
            if (absolute >= absoluteTicksLimit) {
                return Step(Action.AbsoluteTimeout, Counters())
            }
            return Step(
                Action.Continue,
                Counters(
                    idleTicks = idle,
                    stallTicks = input.counters.stallTicks,
                    absoluteTicks = absolute,
                    cacheGrowthTicks = input.counters.cacheGrowthTicks
                )
            )
        }

        if (input.cacheProgressing) {
            val growth = input.counters.cacheGrowthTicks + 1
            if (growth >= cacheGrowthTicksLimit) {
                return Step(Action.AbsoluteTimeout, Counters())
            }
            return Step(
                Action.Continue,
                Counters(absoluteTicks = input.counters.absoluteTicks, cacheGrowthTicks = growth)
            )
        }
        if (absolute >= absoluteTicksLimit) {
            return Step(Action.AbsoluteTimeout, Counters())
        }
        val stall = input.counters.stallTicks + 1
        if (stall >= stallTicksLimit) {
            return Step(Action.StallTimeout, Counters())
        }
        return Step(
            Action.Continue,
            Counters(
                stallTicks = stall,
                absoluteTicks = absolute,
                cacheGrowthTicks = input.counters.cacheGrowthTicks
            )
        )
    }
}
