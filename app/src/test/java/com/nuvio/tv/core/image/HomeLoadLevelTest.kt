package com.nuvio.tv.core.image

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLoadLevelTest {
    private val mib = 1024L * 1024

    @After fun reset() = HomeLoadLevel.setForTest(HomeLoadLevel.Full)

    @Test fun onlySmallBoxesGetTheSmallLevel() {
        assertEquals(HomeLoadLevel.Small, homeLoadLevel(1_024L * mib, lowRamDevice = false, cores = 4))
        assertEquals(HomeLoadLevel.Small, homeLoadLevel(3_800L * mib, lowRamDevice = true, cores = 8))
        assertEquals(HomeLoadLevel.Small, homeLoadLevel(3_800L * mib, lowRamDevice = false, cores = 2))
        assertEquals(HomeLoadLevel.Full, homeLoadLevel(1_950L * mib, lowRamDevice = false, cores = 4))
        assertEquals(HomeLoadLevel.Full, homeLoadLevel(3_800L * mib, lowRamDevice = false, cores = 8))
    }

    @Test fun decodeLanesUseTheSameSmallBoxLine() {
        listOf(
            Triple(1_024L * mib, false, 4),
            Triple(1_950L * mib, false, 4),
            Triple(3_800L * mib, true, 8),
            Triple(3_800L * mib, false, 2),
        ).forEach { (ram, low, cores) ->
            val small = homeLoadLevel(ram, low, cores) == HomeLoadLevel.Small
            assertEquals(small, homePosterDiskDecodeSlots(ram, low, cores) == 2)
        }
    }

    @Test fun fullLevelKeepsTodaysAmounts() {
        val full = HomeLoadLevel.Full
        assertEquals(8, full.startRows)
        assertEquals(3, full.memoryRowsAhead)
        assertEquals(HOME_POSTER_DISK_WARM_SCREENS, full.diskWarmScreens)
        assertTrue(full.warmOtherRowsToDisk)
        assertEquals(homePosterWarmWindow(1000, 100, 107, 99), homePosterWarmWindow(1000, 100, 107, 99, full))
        assertEquals(homePosterMemoryWarmColumns(40, 8), homePosterMemoryWarmColumns(40, 8, full.memoryScreensFromPosition))
    }

    @Test fun smallLevelLoadsLessAhead() {
        val small = HomeLoadLevel.Small
        assertEquals(4, small.startRows)
        assertEquals(1, small.memoryRowsAhead)
        assertFalse(small.warmOtherRowsToDisk)
        val window = homePosterWarmWindow(1000, 100, 107, 99, small)
        assertEquals((108..115).toList() + (99 downTo 97).toList(), window)
        assertEquals(6, homePosterWarmWindow(1000, 100, 101, 99, small).count { it > 101 })
        assertEquals(36..47, homePosterMemoryWarmColumns(40, 8, small.memoryScreensFromPosition))
        assertEquals(listOf(2, 3, 4), homePosterMemoryWarmRows(20, 2, 3, 1, small.memoryRowsAhead))
    }

    @Test fun smallLevelMovingLeftLooksLeft() {
        val window = homePosterWarmWindow(1000, 99, 106, 100, HomeLoadLevel.Small)
        assertEquals((98 downTo 91).toList() + (107..109).toList(), window)
    }

    @Test fun smallLevelWarmsOneScreenToDisk() {
        val indices = homePosterDiskWarmIndices(
            count = 500, onScreen = false, first = 0, last = -1, firstColumn = 0, screenColumns = 8,
            screens = HomeLoadLevel.Small.diskWarmScreens,
        ).toList()
        assertEquals((0 until 8).toList(), indices)
    }

    @Test fun onlyRowsPastTheLimitEndWithSeeAll() {
        assertEquals(150, HomeLoadLevel.Full.rowTitleLimit)
        assertEquals(60, HomeLoadLevel.Small.rowTitleLimit)
        assertFalse(homeRowEndsWithSeeAll(100, hasMore = true, limit = 150))
        assertFalse(homeRowEndsWithSeeAll(149, hasMore = true, limit = 150))
        assertFalse(homeRowEndsWithSeeAll(150, hasMore = false, limit = 150))
        assertTrue(homeRowEndsWithSeeAll(150, hasMore = true, limit = 150))
        assertTrue(homeRowEndsWithSeeAll(200, hasMore = true, limit = 150))
        assertTrue(homeRowEndsWithSeeAll(2_000, hasMore = false, limit = 150))
        assertTrue(homeRowEndsWithSeeAll(100, hasMore = true, limit = 60))
    }
}
