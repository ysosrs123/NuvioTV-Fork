package com.nuvio.tv.core.player.thumbnail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Grid stepping: every tap must move, in its direction, onto the next slot's keyframe. */
class SeekGridTest {
    private val spacing = 10_000L

    /** Nearest keyframe to each slot centre. */
    private fun slotPts(keyframesMs: LongArray, durationMs: Long): LongArray {
        val slots = ((durationMs - 1) / spacing).toInt() + 1
        return LongArray(slots) { s ->
            val c = s * spacing + spacing / 2
            keyframesMs.minByOrNull { kotlin.math.abs(it - c) }!!
        }
    }

    /** One tap from playback position [pos], landing on the slot keyframe. */
    private fun tap(pos: Long, delta: Long, pts: LongArray): Long {
        val g = SeekGrid.gridPoint(pos, delta, spacing)
        val t = SeekGrid.stepPastBase(g, pos, delta > 0, spacing, pts.size) { pts[it] }
        return pts[(t / spacing).toInt().coerceIn(0, pts.size - 1)]
    }

    @Test fun gridPointUnchanged() {
        assertEquals(290_000L, SeekGrid.gridPoint(285_600, 10_000, spacing))
        assertEquals(280_000L, SeekGrid.gridPoint(285_600, -10_000, spacing))
        assertEquals(270_000L, SeekGrid.gridPoint(280_000, -10_000, spacing))
        assertEquals(310_000L, SeekGrid.gridPoint(280_000, 30_000, spacing))
    }

    /** A LEFT tap just after a landing must not land on the same keyframe again. */
    @Test fun leftTapsMoveBackEveryTime() {
        val kfs = LongArray(200) { it * 2_700L + 494L }.also { it[105] = 285_494L }
        val pts = slotPts(kfs, 540_000)
        var pos = 285_494L + 150
        val seen = ArrayList<Long>()
        repeat(5) {
            val next = tap(pos, -10_000, pts)
            assertTrue("LEFT from $pos landed on $next", next < pos - 3_000)
            seen += next
            pos = next + 150
        }
        assertEquals(5, seen.distinct().size)
    }

    @Test fun rightTapsStillOneSlot() {
        val kfs = LongArray(200) { it * 2_700L + 494L }
        val pts = slotPts(kfs, 540_000)
        var pos = pts[28] + 150
        repeat(5) { i ->
            val next = tap(pos, 10_000, pts)
            assertEquals(pts[29 + i], next)
            pos = next + 150
        }
    }

    /** A held scrub steps from a grid point: one slot per step. */
    @Test fun heldStepFromGridPointIsOneSlot() {
        val kfs = LongArray(200) { it * 2_700L + 494L }
        val pts = slotPts(kfs, 540_000)
        val back = SeekGrid.stepPastBase(270_000, 280_000, false, spacing, pts.size) { pts[it] }
        assertEquals(270_000L, back)
        val fwd = SeekGrid.stepPastBase(290_000, 280_000, true, spacing, pts.size) { pts[it] }
        assertEquals(290_000L, fwd)
    }

    /** Sparse keyframes (20 s GOP): slots sharing the base's keyframe are skipped. */
    @Test fun sharedKeyframeSkipped() {
        val kfs = LongArray(30) { it * 20_000L + 1_000L }
        val pts = slotPts(kfs, 600_000)
        val pos = 281_000L + 150
        val next = tap(pos, -10_000, pts)
        assertTrue("landed on $next", next < 281_000L)
        val fwd = tap(pos, 10_000, pts)
        assertTrue("landed on $fwd", fwd > 281_150L)
    }

    @Test fun edgesUnchanged() {
        val kfs = LongArray(20) { it * 5_000L + 2_000L }
        val pts = slotPts(kfs, 100_000)
        assertEquals(0L, SeekGrid.stepPastBase(0, 3_000, false, spacing, pts.size) { pts[it] })
    }

    @Test fun latticeStartsAroundResumePoint() {
        val slots = 874
        val order = SeekGrid.latticeOrder(slots, listOf(30, 12), anchorSlot = 450, beforeSlots = 60, afterSlots = 120)
        // 456 and 444 are both 6 away, ahead wins the tie
        assertEquals(listOf(456, 444, 468, 432), order.take(4))
        val local = (0 until slots step 12).filter { it in 390..570 }
        assertEquals(local.toSet(), order.take(local.size).toSet())
        val full = ((0 until slots step 30) + (0 until slots step 12)).toSet()
        assertEquals(full.size, order.size)
        assertEquals(full, order.toSet())
    }

    @Test fun latticeFromStartUnchangedShape() {
        val order = SeekGrid.latticeOrder(100, listOf(30, 6, 3), anchorSlot = 0, beforeSlots = 60, afterSlots = 120)
        assertEquals(listOf(0, 3, 6, 9), order.take(4))
        assertEquals(((0 until 100 step 3) + (0 until 100 step 30) + (0 until 100 step 6)).toSet().size, order.size)
    }
}
