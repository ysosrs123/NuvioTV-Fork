package com.nuvio.tv.ui.screens.detail

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DetailPresentationGateTest {
    @Test fun `optional release is staggered but Down skips all remaining delay`() = runTest {
        val gate = DetailPresentationGate()
        val quiet = async { gate.awaitQuietPeriod(120) }
        runCurrent()
        advanceTimeBy(100)
        assertFalse(quiet.isCompleted)
        gate.requestSecondary()
        quiet.await()
        gate.awaitQuietPeriod(300)
        assertEquals(100L, testScheduler.currentTime)
    }

    @Test fun `secondary work waits for the current presentation not a stale frame`() = runTest {
        val gate = DetailPresentationGate()
        val old = gate.begin()
        val current = gate.begin()
        val wait = async { gate.awaitPresentation() }
        runCurrent()
        gate.acknowledge(old)
        runCurrent()
        assertFalse(wait.isCompleted)
        gate.acknowledge(current)
        wait.await()
    }

    @Test fun `navigation demand releases secondary work immediately`() = runTest {
        val gate = DetailPresentationGate()
        gate.begin()
        val wait = async { gate.awaitPresentation() }
        runCurrent()
        gate.requestSecondary()
        wait.await()
        gate.begin()
        gate.awaitPresentation()
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test fun `suppressed presentation is bounded and cancellation remains cancellation`() = runTest {
        val gate = DetailPresentationGate()
        gate.begin()
        val wait = async { gate.awaitPresentation() }
        runCurrent()
        advanceTimeBy(750)
        runCurrent()
        assertTrue(wait.isCompleted)
        gate.begin()
        val cancelled = async { gate.awaitPresentation() }
        runCurrent()
        cancelled.cancel()
        assertTrue(cancelled.isCancelled)
    }
}
