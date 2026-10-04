package com.nuvio.tv.ui.v2.quality

import org.junit.Assert.*
import org.junit.Test

class AutomaticQualityGovernorTest {
    private fun observer() = AutomaticQualityGovernor().apply {
        resume(0)
        context(0, true, "home", false, VisualQualityTier.ENHANCED)
    }
    private fun busy(g: AutomaticQualityGovernor, start: Long = 31_000_000_000L, jank: (Int) -> Boolean = { true }) {
        repeat(1_300) { g.frame(start + it * 16_666_667L, jank(it)) }
    }
    private fun transition(g: AutomaticQualityGovernor, allowed: Boolean = true) {
        g.context(60_000_000_000L, allowed, "settings", true, VisualQualityTier.ENHANCED)
    }

    @Test fun sustainedMissesWaitForDifferentIdleUtilityDestination() {
        val g=observer(); busy(g)
        assertNull(g.takeReduction(55_000_000_000L))
        transition(g)
        assertNull(g.takeReduction(61_000_000_000L))
        assertEquals(VisualQualityTier.PERFORMANCE, g.takeReduction(62_000_000_000L))
        assertNull(g.takeReduction(63_000_000_000L))
    }
    @Test fun startupAndOccasionalTransitionSpikesDoNotDowngrade() {
        val g=observer(); busy(g, start=1_000_000_000L)
        busy(g, jank={ it % 120 == 0 }); transition(g)
        assertNull(g.takeReduction(62_000_000_000L))
    }
    @Test fun idleWindowsDoNotAccumulateActiveWork() {
        val g=observer()
        repeat(30) { g.frame(31_000_000_000L + it * 1_000_000_000L, true) }
        transition(g)
        assertNull(g.takeReduction(62_000_000_000L))
    }
    @Test fun playbackOrManualContextDiscardsPendingReduction() {
        val g=observer(); busy(g)
        g.context(55_000_000_000L, false, "player", false, VisualQualityTier.ENHANCED)
        transition(g)
        assertNull(g.takeReduction(62_000_000_000L))
    }
    @Test fun heldOrRecentInputDefersApplyingReduction() {
        val g=observer(); busy(g); transition(g)
        g.input(60_000_000_000L, 20, true)
        assertNull(g.takeReduction(64_000_000_000L))
        g.input(64_000_000_000L, 20, false)
        assertNull(g.takeReduction(65_000_000_000L))
        assertEquals(VisualQualityTier.PERFORMANCE, g.takeReduction(66_000_000_000L))
    }
    @Test fun backgroundAndPerformanceTierNeverProduceReduction() {
        val g=observer(); busy(g); transition(g); g.pause(61_000_000_000L)
        assertNull(g.takeReduction(63_000_000_000L))
        g.resume(64_000_000_000L)
        g.context(64_000_000_000L,true,"settings",true,VisualQualityTier.PERFORMANCE)
        busy(g, start=100_000_000_000L)
        assertNull(g.takeReduction(123_000_000_000L))
    }
    @Test fun anIdleGapBreaksConsecutiveBusyWindows() {
        val g = observer()
        repeat(650) { g.frame(31_000_000_000L + it * 16_666_667L, true) }
        repeat(650) { g.frame(44_000_000_000L + it * 16_666_667L, true) }
        transition(g)
        assertNull(g.takeReduction(62_000_000_000L))
    }
    @Test fun returningFromBackgroundRequiresFreshEvidence() {
        val g = observer(); busy(g); g.pause(55_000_000_000L)
        g.resume(56_000_000_000L); transition(g)
        assertNull(g.takeReduction(62_000_000_000L))
    }
}
