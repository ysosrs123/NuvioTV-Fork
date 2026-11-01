package com.nuvio.tv.core.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerSessionTrackerTest {

    @After
    fun restoreTimerScope() {
        PlayerSessionTracker.timerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    private val settled get() = PlayerSessionTracker.settled.value

    private fun TestScope.advance(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test
    fun `stays open until every overlapping session closes`() = runTest {
        val previous = PlayerSessionTracker.open()
        val next = PlayerSessionTracker.open()

        previous.close()
        assertTrue(PlayerSessionTracker.isSessionOpen.first())

        next.close()
        assertFalse(PlayerSessionTracker.isSessionOpen.first())
    }

    @Test
    fun `closing a session twice does not close another one`() = runTest {
        val exited = PlayerSessionTracker.open()
        exited.close()
        val active = PlayerSessionTracker.open()

        exited.close()
        assertTrue(PlayerSessionTracker.isSessionOpen.first())

        active.close()
        assertFalse(PlayerSessionTracker.isSessionOpen.first())
    }

    @Test
    fun `settles a minute after the first frame`() = runTest {
        PlayerSessionTracker.timerScope = backgroundScope
        val session = PlayerSessionTracker.open()
        assertFalse(settled)

        advance(5_000)
        session.firstFrame()
        advance(PlayerSessionTracker.SETTLE_MS - 1)
        assertFalse(settled)
        advance(1)
        assertTrue(settled)

        session.close()
        assertTrue(settled)
    }

    @Test
    fun `first frame shown is set at the first frame and cleared by the next stream`() = runTest {
        PlayerSessionTracker.timerScope = backgroundScope
        val session = PlayerSessionTracker.open()
        assertFalse(PlayerSessionTracker.firstFrameShown)
        session.firstFrame()
        assertTrue(PlayerSessionTracker.firstFrameShown)
        advance(PlayerSessionTracker.SETTLE_MS)
        assertTrue(PlayerSessionTracker.firstFrameShown)
        session.streamStarting()
        assertFalse(PlayerSessionTracker.firstFrameShown)
        session.firstFrame()
        assertTrue(PlayerSessionTracker.firstFrameShown)
        session.close()
        assertFalse(PlayerSessionTracker.firstFrameShown)
    }

    @Test
    fun `a stream that never shows a frame settles after the longer wait`() = runTest {
        PlayerSessionTracker.timerScope = backgroundScope
        val session = PlayerSessionTracker.open()

        advance(PlayerSessionTracker.NO_FRAME_SETTLE_MS - 1)
        assertFalse(settled)
        advance(1)
        assertTrue(settled)

        session.close()
    }

    @Test
    fun `a new stream or session starts the wait again`() = runTest {
        PlayerSessionTracker.timerScope = backgroundScope
        val first = PlayerSessionTracker.open()
        first.firstFrame()
        advance(30_000)

        first.streamStarting()
        first.firstFrame()
        advance(40_000)
        assertFalse(settled)

        val next = PlayerSessionTracker.open()
        advance(30_000)
        assertFalse(settled)
        next.firstFrame()
        advance(PlayerSessionTracker.SETTLE_MS)
        assertTrue(settled)

        first.close()
        next.close()
    }

    @Test
    fun `closing settles at once, an older session closing does not`() = runTest {
        PlayerSessionTracker.timerScope = backgroundScope
        val previous = PlayerSessionTracker.open()
        previous.firstFrame()
        val next = PlayerSessionTracker.open()

        previous.close()
        assertFalse(settled)

        next.firstFrame()
        advance(10_000)
        next.close()
        assertTrue(settled)

        advance(PlayerSessionTracker.SETTLE_MS)
        assertTrue(settled)
    }

    @Test
    fun `a repeated first frame does not push the settle point back`() = runTest {
        PlayerSessionTracker.timerScope = backgroundScope
        val session = PlayerSessionTracker.open()
        session.firstFrame()
        advance(50_000)

        session.firstFrame()
        advance(PlayerSessionTracker.SETTLE_MS - 50_000)
        assertTrue(settled)

        session.close()
    }

    @Test
    fun `a closed session no longer unsettles`() = runTest {
        PlayerSessionTracker.timerScope = backgroundScope
        val session = PlayerSessionTracker.open()
        session.close()

        session.firstFrame()
        session.streamStarting()
        assertTrue(settled)
    }
}
