package com.nuvio.tv.ui.util

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.layout.PrefetchRequestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalFoundationApi::class)
class PrefetchQueueTest {
    private val ms = 1_000_000L
    private val frame = 16_683_333L
    private var now = 1_000 * ms
    private val queue = PrefetchQueue<Job>({ now }) { job, scope -> job.run(scope) }
    private val order = mutableListOf<String>()

    private interface Job {
        fun run(scope: PrefetchRequestScope): Boolean
    }

    /** Work done in [step]-sized pieces, each only when it fits, like a paused composition. */
    private open inner class Work(val name: String, var left: Long, val step: Long = ms) : Job {
        override fun run(scope: PrefetchRequestScope): Boolean {
            while (left > 0 && scope.availableTimeNanos() > step) {
                now += step
                left -= step
                order += name
                onStep()
            }
            return left > 0
        }

        open fun onStep() {}
    }

    private fun runStill() = queue.run(lastDrawNs = now - 10 * frame, frameStartNs = now - 10 * frame, frameIntervalNs = frame)

    private fun runMoving() = queue.run(lastDrawNs = now - 2 * ms, frameStartNs = now - 4 * ms, frameIntervalNs = frame)

    @Test fun stillScreenGivesBuiltAheadWorkASliceAndComesStraightBack() {
        val row = Work("row", left = 60 * ms)
        queue.addHigh(row)
        val start = now
        assertEquals(PrefetchNext.Soon, runStill())
        assertTrue(now - start <= PREFETCH_IDLE_SLICE_NS)
        assertTrue(row.left > 0)
    }

    @Test fun aWholeRowIsReadyInAboutItsOwnTimeOnAStillScreen() {
        val row = Work("row", left = 60 * ms)
        queue.addHigh(row)
        val start = now
        var rounds = 0
        while (runStill() != PrefetchNext.Done) rounds++
        assertEquals(0L, row.left)
        assertTrue(rounds <= 15)
        assertTrue(now - start <= 61 * ms)
    }

    @Test fun movingScreenGetsTheTimeUntilTheNextFrame() {
        val row = Work("row", left = 60 * ms)
        queue.addHigh(row)
        // The frame is counted from the later of its start and the last draw, as in Compose's own scheduler.
        val nextFrame = (now - 2 * ms) + frame
        assertEquals(PrefetchNext.NextFrame, runMoving())
        assertTrue(now <= nextFrame)
        assertTrue(now >= nextFrame - 2 * ms)
    }

    @Test fun aStepTooBigForTheSliceKeepsTheBiggerSliceForTheRestOfItsWork() {
        val big = Work("big", left = 30 * ms, step = 10 * ms)
        queue.addHigh(big)
        assertEquals(PrefetchNext.Soon, runStill())
        assertEquals(30 * ms, big.left)
        assertEquals(1, queue.stalls)
        runStill()
        assertEquals(20 * ms, big.left)
        runStill()
        assertEquals(10 * ms, big.left)
        assertEquals(1, queue.stalls)
    }

    @Test fun nothingWaitsForGood() {
        val huge = Work("huge", left = 100 * ms, step = 100 * ms)
        queue.addHigh(huge)
        repeat(PREFETCH_MAX_STALLS) { assertEquals(PrefetchNext.Soon, runStill()) }
        assertEquals(PrefetchNext.Done, runStill())
        assertTrue(queue.isEmpty)
    }

    @Test fun workThatCannotMoveEvenWithoutALimitWaitsForTheNextFrame() {
        val stuck = object : Job {
            override fun run(scope: PrefetchRequestScope) = true
        }
        queue.addHigh(stuck)
        repeat(PREFETCH_MAX_STALLS) { assertEquals(PrefetchNext.Soon, runStill()) }
        assertEquals(PrefetchNext.NextFrame, runStill())
        assertEquals(PrefetchNext.NextFrame, runStill())
    }

    @Test fun stallsStartOverWhenTheScreenMoves() {
        queue.addHigh(Work("big", left = 10 * ms, step = 10 * ms))
        runStill()
        assertEquals(1, queue.stalls)
        now += 4 * ms
        runMoving()
        assertEquals(0, queue.stalls)
    }

    @Test fun stallsBelongToOneRequest() {
        queue.addLow(Work("big", left = 10 * ms, step = 10 * ms))
        runStill()
        assertEquals(1, queue.stalls)
        queue.addHigh(Work("small", left = 1 * ms))
        runStill()
        assertEquals(0, queue.stalls)
    }

    @Test fun finishedWorkMakesWayForTheNextInTheSameRound() {
        queue.addLow(Work("b", left = 2 * ms))
        queue.addLow(Work("c", left = 2 * ms))
        assertEquals(PrefetchNext.Done, runStill())
        assertEquals(listOf("b", "b", "c", "c"), order)
    }

    @Test fun urgentWorkGoesFirst() {
        queue.addLow(Work("low", left = 1 * ms))
        queue.addHigh(Work("high", left = 1 * ms))
        runStill()
        assertEquals(listOf("high", "low"), order)
    }

    @Test fun urgentWorkArrivingMidwayGoesNextAndTheRestResumesAfter() {
        val low = Work("low", left = 20 * ms)
        queue.addLow(low)
        runStill()
        queue.addHigh(Work("high", left = 2 * ms))
        while (runStill() != PrefetchNext.Done) Unit
        val firstHigh = order.indexOf("high")
        assertTrue(firstHigh > 0)
        assertEquals(listOf("high", "high"), order.subList(firstHigh, firstHigh + 2))
        assertEquals("low", order.last())
        assertEquals(0L, low.left)
    }

    @Test fun workThatAddsWorkWhileItRunsIsSafe() {
        val parent = object : Work("parent", left = 3 * ms) {
            override fun onStep() {
                if (left == 2 * ms) queue.addLow(Work("child", left = 1 * ms))
            }
        }
        queue.addLow(parent)
        while (runStill() != PrefetchNext.Done) Unit
        assertEquals(listOf("parent", "parent", "parent", "child"), order)
    }
}
