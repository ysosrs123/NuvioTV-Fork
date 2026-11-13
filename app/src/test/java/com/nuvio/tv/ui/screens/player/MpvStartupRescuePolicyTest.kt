package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvStartupRescuePolicyTest {

    private fun enabled(
        hasError: Boolean = false,
        releasing: Boolean = false,
        failoverTriggered: Boolean = false,
        recoveryArmed: Boolean = false,
        hasStream: Boolean = true,
        inBackground: Boolean = false,
        playerPaused: Boolean = false,
    ) = MpvStartupWatchdogPolicy.watchdogEnabled(
        hasError = hasError,
        releasing = releasing,
        failoverTriggered = failoverTriggered,
        recoveryArmed = recoveryArmed,
        hasStream = hasStream,
        inBackground = inBackground,
        playerPaused = playerPaused
    )

    @Test
    fun `progress tick keeps running through a pause until the first frame`() {
        assertFalse(MpvStartupWatchdogPolicy.progressTickMayStop(hasRenderedFirstFrame = false))
        assertTrue(MpvStartupWatchdogPolicy.progressTickMayStop(hasRenderedFirstFrame = true))
    }

    @Test
    fun `OK before the first frame does not pause`() {
        assertFalse(MpvStartupWatchdogPolicy.playPauseShouldPause(isPlaying = true, hasRenderedFirstFrame = false))
        assertFalse(MpvStartupWatchdogPolicy.playPauseShouldPause(isPlaying = false, hasRenderedFirstFrame = false))
    }

    @Test
    fun `OK after the first frame pauses a playing stream`() {
        assertTrue(MpvStartupWatchdogPolicy.playPauseShouldPause(isPlaying = true, hasRenderedFirstFrame = true))
        assertFalse(MpvStartupWatchdogPolicy.playPauseShouldPause(isPlaying = false, hasRenderedFirstFrame = true))
    }

    @Test
    fun `resume restarts the tick only for a start that showed no frame`() {
        assertTrue(MpvStartupWatchdogPolicy.restartTickOnResume(false, hasStream = true, hasView = true))
        assertFalse(MpvStartupWatchdogPolicy.restartTickOnResume(true, hasStream = true, hasView = true))
        assertFalse(MpvStartupWatchdogPolicy.restartTickOnResume(false, hasStream = false, hasView = true))
        assertFalse(MpvStartupWatchdogPolicy.restartTickOnResume(false, hasStream = true, hasView = false))
    }

    @Test
    fun `resume unpauses a solo start but leaves a party start to the party`() {
        assertTrue(MpvStartupWatchdogPolicy.unpauseOnResume(hasRenderedFirstFrame = false, inParty = false))
        assertFalse(MpvStartupWatchdogPolicy.unpauseOnResume(hasRenderedFirstFrame = false, inParty = true))
        assertFalse(MpvStartupWatchdogPolicy.unpauseOnResume(hasRenderedFirstFrame = true, inParty = false))
    }

    @Test
    fun `watchdog runs for a playing start`() {
        assertTrue(enabled())
    }

    @Test
    fun `watchdog holds while the player is paused or in the background`() {
        assertFalse(enabled(playerPaused = true))
        assertFalse(enabled(inBackground = true))
    }

    @Test
    fun `watchdog stays off once an error or recovery owns the start`() {
        assertFalse(enabled(hasError = true))
        assertFalse(enabled(releasing = true))
        assertFalse(enabled(failoverTriggered = true))
        assertFalse(enabled(recoveryArmed = true))
        assertFalse(enabled(hasStream = false))
    }

    @Test
    fun `stalled start fires within the absolute limit once the watchdog runs again`() {
        var counters = MpvStartupWatchdogPolicy.Counters()
        var action = MpvStartupWatchdogPolicy.Action.Continue
        var ticks = 0
        while (action == MpvStartupWatchdogPolicy.Action.Continue && ticks < 1_000) {
            val step = MpvStartupWatchdogPolicy.step(
                MpvStartupWatchdogPolicy.Input(
                    enabled = enabled(),
                    waitingForSurface = false,
                    idleActive = false,
                    cacheProgressing = false,
                    counters = counters
                )
            )
            counters = step.counters
            action = step.action
            ticks++
        }
        assertEquals(MpvStartupWatchdogPolicy.Action.StallTimeout, action)
        assertTrue(ticks * MpvStartupWatchdogPolicy.POLL_INTERVAL_MS <= MpvStartupWatchdogPolicy.ABSOLUTE_TIMEOUT_MS)
    }

    @Test
    fun `a pause resets the counters so the start gets a fresh window`() {
        val step = MpvStartupWatchdogPolicy.step(
            MpvStartupWatchdogPolicy.Input(
                enabled = enabled(playerPaused = true),
                waitingForSurface = false,
                idleActive = false,
                cacheProgressing = false,
                counters = MpvStartupWatchdogPolicy.Counters(stallTicks = 30, absoluteTicks = 50)
            )
        )
        assertEquals(MpvStartupWatchdogPolicy.Action.Continue, step.action)
        assertEquals(MpvStartupWatchdogPolicy.Counters(), step.counters)
    }
}
