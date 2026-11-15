package com.nuvio.tv.core.image

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeStartCalmTest {
    @After fun reset() = HomeStartCalm.resetForTest()

    @Test fun calmForTheFirstThreeSecondsWhateverIsLoading() {
        assertTrue(homeStartCalm(0, visibleLoading = false))
        assertTrue(homeStartCalm(2_999, visibleLoading = false))
        assertFalse(homeStartCalm(3_000, visibleLoading = false))
    }

    @Test fun postersOnScreenStillLoadingKeepItCalmUpToTheCap() {
        assertTrue(homeStartCalm(5_000, visibleLoading = true))
        assertTrue(homeStartCalm(7_999, visibleLoading = true))
        assertFalse(homeStartCalm(8_000, visibleLoading = true))
    }

    @Test fun notCalmBeforeHomeHasStarted() {
        assertFalse(HomeStartCalm.active(visibleLoading = true, nowMs = 1_000))
    }

    @Test fun onlyTheFirstStartCounts() {
        HomeStartCalm.begin(nowMs = 10_000)
        HomeStartCalm.begin(nowMs = 20_000)
        assertTrue(HomeStartCalm.active(nowMs = 12_000))
        assertFalse(HomeStartCalm.active(nowMs = 13_000))
        assertFalse(HomeStartCalm.active(visibleLoading = true, nowMs = 20_500))
    }

    @Test fun diskWarmUpOfARowOffScreenCoversAFewScreensNotTheWholeRow() {
        val indices = homePosterDiskWarmIndices(
            count = 5_000, onScreen = false, first = 0, last = -1, firstColumn = 0, screenColumns = 8
        ).toList()
        assertEquals((0 until 24).toList(), indices)
    }

    @Test fun diskWarmUpOfARowOffScreenStartsHalfAScreenBeforeWhereItStands() {
        val indices = homePosterDiskWarmIndices(
            count = 100, onScreen = false, first = 0, last = -1, firstColumn = 40, screenColumns = 8
        ).toList()
        assertEquals(36, indices.first())
        assertEquals(63, indices.last())
    }

    @Test fun diskWarmUpOfAShortRowStopsAtItsEnd() {
        val indices = homePosterDiskWarmIndices(
            count = 10, onScreen = false, first = 0, last = -1, firstColumn = 0, screenColumns = 8
        ).toList()
        assertEquals((0 until 10).toList(), indices)
    }

    @Test fun diskWarmUpOfARowOnScreenReachesOutFromTheVisiblePosters() {
        val indices = homePosterDiskWarmIndices(
            count = 2_000, onScreen = true, first = 100, last = 107, firstColumn = 100, screenColumns = 8
        ).toList()
        assertEquals(listOf(108, 99, 109, 98), indices.take(4))
        assertEquals(24, indices.size)
        assertTrue(indices.all { it in 88..119 })
    }

    @Test fun diskWarmUpOfAnEmptyRowIsEmpty() {
        assertTrue(homePosterDiskWarmIndices(0, false, 0, -1, 0, 8).none())
        assertTrue(homePosterDiskWarmIndices(10, true, 0, -1, 0, 8).none())
    }
}
