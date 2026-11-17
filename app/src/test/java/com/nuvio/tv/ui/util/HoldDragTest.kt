package com.nuvio.tv.ui.util

import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.runtime.BroadcastFrameClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HoldDragTest {
    private val clock = BroadcastFrameClock()
    private var inFrame = false
    private val steps = mutableListOf<Pair<Float, Boolean>>()
    private var room = Int.MAX_VALUE

    private val list = object : ScrollScope {
        override fun scrollBy(pixels: Float): Float {
            steps += pixels to inFrame
            return if (room-- > 0) pixels else 0f
        }
    }

    private fun kotlinx.coroutines.test.TestScope.frames(count: Int) {
        var t = 1_000_000_000L
        repeat(count) {
            runCurrent()
            inFrame = true
            clock.sendFrame(t)
            inFrame = false
            runCurrent()
            t += 16_666_667L
        }
    }

    @Test fun everyStepIsTakenInsideTheFrame() = runTest {
        room = 4
        val job = launch(clock) { list.holdDrag(1, 1000f, 0.048f) { false } }
        frames(10)
        assertEquals(5, steps.size)
        assertTrue(steps.all { it.second })
        assertTrue(job.isCompleted)
    }

    @Test fun dragStartsGentlyAndPicksUpSpeed() = runTest {
        val job = launch(clock) { list.holdDrag(-1, 1000f, 0.048f) { false } }
        frames(12)
        job.cancel()
        val first = -steps.first().first
        val last = -steps.last().first
        assertTrue(first > 0f)
        assertTrue(last > first * 2f)
    }

    @Test fun releasingTheKeyStopsTheDragMidHold() = runTest {
        val job = launch(clock) { list.holdDrag(1, 1000f, 0.048f) { false } }
        frames(4)
        job.cancel()
        val taken = steps.size
        frames(4)
        assertTrue(taken > 0)
        assertEquals(taken, steps.size)
        assertTrue(job.isCancelled)
    }

    @Test fun aLongStallDoesNotThrowTheListFar() = runTest {
        val job = launch(clock) { list.holdDrag(1, 1000f, 0.048f) { false } }
        runCurrent()
        clock.sendFrame(1_000_000_000L)
        runCurrent()
        clock.sendFrame(1_500_000_000L)
        runCurrent()
        job.cancel()
        assertEquals(1, steps.size)
        assertTrue(steps.single().first <= 1000f * 0.048f + 0.001f)
    }

    @Test fun haltStopsTheDragBeforeAnyStep() = runTest {
        val job = launch(clock) { list.holdDrag(1, 1000f, 0.048f) { true } }
        frames(3)
        assertTrue(steps.isEmpty())
        assertTrue(job.isCompleted)
    }
}
