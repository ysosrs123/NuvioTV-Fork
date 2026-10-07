package com.nuvio.tv.core.iptv

import org.junit.Assert.*
import org.junit.Test

class MultiviewGeometryTest {
    private val width = 1920f
    private val height = 1080f
    private val gap = 2f

    private fun check(rects: List<TileRect>) {
        rects.forEach {
            assertTrue(it.x >= -0.01f && it.y >= -0.01f && it.right <= width + 0.01f && it.bottom <= height + 0.01f)
            assertTrue(it.width > 0f && it.height > 0f)
        }
        for (i in rects.indices) for (j in i + 1 until rects.size) {
            val a = rects[i]; val b = rects[j]
            assertFalse("$a overlaps $b", a.x < b.right - 0.01f && b.x < a.right - 0.01f && a.y < b.bottom - 0.01f && b.y < a.bottom - 0.01f)
        }
    }

    @Test fun everyLayoutFitsWithoutOverlapForEveryCount() {
        for (layout in MultiviewLayout.entries) for (slots in 1..4) for (main in 0 until slots) check(multiviewGeometry(layout, slots, main, width, height, gap))
    }

    @Test fun gridIsTwoByTwoAndSixteenByNine() {
        val rects = multiviewGeometry(MultiviewLayout.GRID, 4, 0, width, height, gap)
        assertEquals(rects[0].width, rects[3].width, .01f)
        assertEquals(rects[0].width * 9f / 16f, rects[0].height, .01f)
        assertEquals(rects[0].y, rects[1].y, .01f)
        assertTrue(rects[2].y > rects[0].y)
    }

    @Test fun sideBySideSplitsTheScreenInTwoAndFallsBackToGrid() {
        val rects = multiviewGeometry(MultiviewLayout.SIDE_BY_SIDE, 2, 0, width, height, gap)
        assertEquals((width - gap) / 2f, rects[0].width, .01f)
        assertEquals(height, rects[1].height, .01f)
        assertEquals(rects[0].right + gap, rects[1].x, .01f)
        assertEquals(multiviewGeometry(MultiviewLayout.GRID, 3, 0, width, height, gap), multiviewGeometry(MultiviewLayout.SIDE_BY_SIDE, 3, 0, width, height, gap))
        assertEquals(2, multiviewMaxTiles(MultiviewLayout.SIDE_BY_SIDE, 4))
        assertEquals(4, multiviewMaxTiles(MultiviewLayout.ONE_OVER_TWO, 4))
        assertEquals(MultiviewLayout.GRID, multiviewEffectiveLayout(MultiviewLayout.SIDE_BY_SIDE, 3))
        assertEquals(MultiviewLayout.GRID, multiviewEffectiveLayout(MultiviewLayout.FOCUS, 1))
        assertEquals(MultiviewLayout.ONE_OVER_TWO, multiviewEffectiveLayout(MultiviewLayout.ONE_OVER_TWO, 3))
    }

    @Test fun oneOverTwoPutsTheMainTileOnTopAndTheRestInARow() {
        val rects = multiviewGeometry(MultiviewLayout.ONE_OVER_TWO, 3, 2, width, height, gap)
        val big = rects[2]
        assertTrue(big.width > rects[0].width && big.bottom <= rects[0].y + 0.01f)
        assertEquals(rects[0].y, rects[1].y, .01f)
        assertEquals(rects[0].right + gap, rects[1].x, .01f)
        assertEquals(width / 2f, big.x + big.width / 2f, .01f)
    }

    @Test fun focusKeepsTheMainTileLargeOnTheLeft() {
        val rects = multiviewGeometry(MultiviewLayout.FOCUS, 4, 1, width, height, gap)
        assertTrue(rects[1].width > rects[0].width && rects[1].x < rects[0].x)
        assertEquals(rects[0].x, rects[2].x, .01f)
        assertTrue(rects[0].y < rects[2].y && rects[2].y < rects[3].y)
    }

    @Test fun singleSlotFillsTheScreenAtSixteenByNine() {
        val rect = multiviewGeometry(MultiviewLayout.FOCUS, 1, 0, width, height, gap).single()
        assertEquals(TileRect(0f, 0f, width, height), rect)
    }
}
