package com.nuvio.tv.core.iptv

import org.junit.Assert.*
import org.junit.Test

class LiveBufferPolicyTest {
    private val mb = 1024 * 1024

    @Test fun plainPlanKeepsOldCeilingAndDoublesAfterRebuffer() {
        val plan = LiveBufferPolicy.plan(LiveStartBuffer.NORMAL, LiveCushion.OFF, 48 * mb, 12 * mb)
        assertEquals(LiveBufferPlan(2_500, 5_000, 5_000, 8_000, 12 * mb, 0), plan)
        val fast = LiveBufferPolicy.plan(LiveStartBuffer.FAST, LiveCushion.OFF, 48 * mb, 12 * mb)
        assertEquals(1_000, fast.startMs); assertEquals(2_000, fast.rebufferMs); assertEquals(2_000, fast.minMs)
        val safe = LiveBufferPolicy.plan(LiveStartBuffer.SAFE, LiveCushion.OFF, 48 * mb, 12 * mb)
        assertEquals(10_000, safe.minMs); assertEquals(10_000, safe.maxMs)
    }

    @Test fun cushionPlanLoadsContinuouslyUpToTargetPlusMargin() {
        val plan = LiveBufferPolicy.plan(LiveStartBuffer.FAST, LiveCushion.SECONDS_20, 48 * mb, 12 * mb)
        assertEquals(30_000, plan.minMs); assertEquals(30_000, plan.maxMs)
        assertEquals(1_000, plan.startMs); assertEquals(48 * mb, plan.targetBytes); assertEquals(20_000L, plan.cushionMs)
        for (start in LiveStartBuffer.entries) for (cushion in LiveCushion.entries) {
            val each = LiveBufferPolicy.plan(start, cushion, 16 * mb, 12 * mb)
            assertTrue(each.minMs >= each.startMs && each.minMs >= each.rebufferMs && each.maxMs >= each.minMs)
        }
    }

    @Test fun memoryCapFollowsDeviceClassAndHeap() {
        assertTrue(LiveBufferPolicy.lowMemory(2_000L * mb, false))
        assertTrue(LiveBufferPolicy.lowMemory(4_000L * mb, true))
        assertFalse(LiveBufferPolicy.lowMemory(3_800L * mb, false))
        assertEquals(16 * mb, LiveBufferPolicy.capBytes(true, 512L * mb))
        assertEquals(48 * mb, LiveBufferPolicy.capBytes(false, 512L * mb))
        assertEquals(40 * mb, LiveBufferPolicy.capBytes(false, 200L * mb))
        assertEquals(8 * mb, LiveBufferPolicy.capBytes(false, 16L * mb))
    }

    @Test fun targetShrinksWhenTheBitrateWouldOverflowTheCap() {
        assertEquals(0L, LiveBufferPolicy.targetMs(0, 8e6, 48 * mb))
        assertEquals(20_000L, LiveBufferPolicy.targetMs(20_000, null, 16 * mb))
        assertEquals(20_000L, LiveBufferPolicy.targetMs(20_000, 4e6, 48 * mb))
        val capped = LiveBufferPolicy.targetMs(60_000, 8e6, 16 * mb)
        assertTrue(capped in 12_000..13_000)
    }

    @Test fun hlsCushionStaysInsideTheWindow() {
        assertNull(LiveBufferPolicy.hlsStartMs(12_000, 30_000, 0))
        assertEquals(15_000L, LiveBufferPolicy.hlsStartMs(42_000, 60_000, 32_000))
        assertEquals(22_000L, LiveBufferPolicy.hlsStartMs(42_000, 60_000, 20_000))
        assertEquals(7_500L, LiveBufferPolicy.hlsStartMs(12_000, 30_000, 20_000))
        assertNull(LiveBufferPolicy.hlsStartMs(3_000, 12_000, 20_000))
    }

    @Test fun speedSlowsUntilTargetWithHysteresis() {
        val speed = LiveCushionSpeed()
        assertEquals(0.97f, speed.update(2_000, 20_000))
        assertEquals(0.97f, speed.update(19_000, 20_000))
        assertEquals(1f, speed.update(20_000, 20_000))
        assertEquals(1f, speed.update(17_000, 20_000))
        assertEquals(0.97f, speed.update(15_900, 20_000))
        assertEquals(1f, speed.update(5_000, 0))
        assertEquals(0.97f, speed.update(5_000, 10_000))
        assertEquals(0.97f, speed.update(9_000, 10_000))
        assertEquals(1f, speed.update(10_000, 10_000))
        assertEquals(1f, speed.update(8_100, 10_000))
        assertEquals(0.97f, speed.update(7_900, 10_000))
        speed.reset(); assertEquals(1f, speed.speed)
    }

    @Test fun holdsCountDistinctHoldersOnce() {
        val holds = LiveHolds<Any>()
        val a = Any(); val b = Any()
        assertTrue(holds.acquire(a)); assertFalse(holds.acquire(a)); assertFalse(holds.acquire(b))
        assertFalse(holds.release(a)); assertFalse(holds.release(a)); assertTrue(holds.held())
        assertTrue(holds.release(b)); assertFalse(holds.held()); assertFalse(holds.release(b))
    }
}
