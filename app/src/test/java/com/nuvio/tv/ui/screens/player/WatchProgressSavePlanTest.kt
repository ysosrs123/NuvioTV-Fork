package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Test

class WatchProgressSavePlanTest {
    @Test
    fun `first completed save marks the episode`() {
        assertEquals(WatchProgressSavePlan.MARK_COMPLETED, watchProgressSavePlan(completed = true, alreadyMarkedCompleted = false))
    }

    @Test
    fun `completed save after marking does not mark again`() {
        assertEquals(WatchProgressSavePlan.ALREADY_COMPLETED, watchProgressSavePlan(completed = true, alreadyMarkedCompleted = true))
    }

    @Test
    fun `in-progress save is stored while not marked`() {
        assertEquals(WatchProgressSavePlan.SAVE_IN_PROGRESS, watchProgressSavePlan(completed = false, alreadyMarkedCompleted = false))
    }

    @Test
    fun `stale in-progress save after marking is skipped`() {
        assertEquals(WatchProgressSavePlan.SKIP, watchProgressSavePlan(completed = false, alreadyMarkedCompleted = true))
    }
}
