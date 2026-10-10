package com.nuvio.tv.core.iptv

import org.junit.Assert.*
import org.junit.Test

class MultiviewSizingTest {
    @Test fun rungFollowsThePictureSizeOnTheTv() {
        assertEquals(540, multiviewRung(540, MultiviewQuality.AUTO))
        assertEquals(1080, multiviewRung(1060, MultiviewQuality.AUTO))
        assertEquals(1080, multiviewRung(1440, MultiviewQuality.AUTO))
        assertEquals(360, multiviewRung(350, MultiviewQuality.AUTO))
        assertEquals(720, multiviewRung(540, MultiviewQuality.SHARPEST))
        assertEquals(1080, multiviewRung(1080, MultiviewQuality.SHARPEST))
        assertEquals(360, multiviewRung(540, MultiviewQuality.LIGHTEST))
        assertEquals(360, multiviewRung(360, MultiviewQuality.LIGHTEST))
    }

    @Test fun fourPicturesOnA4kTvStayAt1080WhenTheDecoderCanManage() {
        val fourK = List(4) { 1060 }
        assertEquals(List(4) { 1080 }, multiviewHeights(fourK, 0, MultiviewQuality.AUTO, multiviewPixelRate(2160, 60)))
        assertEquals(List(4) { 1080 }, multiviewHeights(fourK, 0, MultiviewQuality.AUTO, null))
        assertEquals(List(4) { 540 }, multiviewHeights(List(4) { 520 }, 0, MultiviewQuality.AUTO, multiviewPixelRate(2160, 60)))
    }

    @Test fun aSmallerBudgetLowersTheOtherPicturesBeforeTheFocusedOne() {
        val budget = multiviewPixelRate(1080) * 2
        val heights = multiviewHeights(List(4) { 1060 }, 2, MultiviewQuality.AUTO, budget)
        assertEquals(1080, heights[2])
        assertTrue(heights.sumOf { multiviewPixelRate(it) } <= budget)
        assertTrue(heights.filterIndexed { i, _ -> i != 2 }.all { it < 1080 })
        val tiny = multiviewHeights(List(4) { 1060 }, 0, MultiviewQuality.AUTO, 1)
        assertEquals(List(4) { 360 }, tiny)
    }

    @Test fun roomForAnotherPicture() {
        assertTrue(multiviewHasRoom(listOf(multiviewPixelRate(1080)), null))
        assertFalse(multiviewHasRoom(List(4) { multiviewPixelRate(1080) }, multiviewPixelRate(1080) * 4))
        assertTrue(multiviewHasRoom(List(2) { multiviewPixelRate(1080) }, multiviewPixelRate(2160, 60)))
    }

    @Test fun decodeBudgetUsesTheLargestPointTheDecoderCovers() {
        assertEquals(3840L * 2160 * 60, multiviewDecodeBudget { w, h, f -> w <= 3840 && h <= 2160 && f <= 60 })
        assertEquals(1920L * 1080 * 60, multiviewDecodeBudget { w, h, f -> w <= 1920 && h <= 1080 && f <= 60 })
        assertNull(multiviewDecodeBudget { _, _, _ -> false })
        assertTrue(multiviewHasRoom(List(2) { multiviewPixelRate(1080) }, multiviewDecodeBudget { w, h, f -> w <= 3840 && h <= 2160 && f <= 60 }))
    }
}
