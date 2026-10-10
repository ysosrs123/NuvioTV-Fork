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

    private fun tiles(vararg heights: Int) = heights.map { MultiviewTileLoad(it) }
    private val tsFullHd = MultiviewTileLoad(540, 1920L * 1080 * 50, 50, adjustable = false)

    @Test fun fourPicturesOnA4kTvStayAt1080WhenTheDecoderCanManage() {
        val fourK = tiles(1060, 1060, 1060, 1060)
        assertEquals(List(4) { 1080 }, multiviewHeights(fourK, 0, MultiviewQuality.AUTO, MultiviewDecode(multiviewPixelRate(2160, 60))))
        assertEquals(List(4) { 1080 }, multiviewHeights(fourK, 0, MultiviewQuality.AUTO, MultiviewDecode(null)))
        assertEquals(List(4) { 540 }, multiviewHeights(tiles(520, 520, 520, 520), 0, MultiviewQuality.AUTO, MultiviewDecode(multiviewPixelRate(2160, 60))))
    }

    @Test fun aSmallerBudgetLowersTheOtherPicturesBeforeTheFocusedOne() {
        val budget = MultiviewDecode(multiviewPixelRate(1080) * 2)
        val fourK = tiles(1060, 1060, 1060, 1060)
        val heights = multiviewHeights(fourK, 2, MultiviewQuality.AUTO, budget)
        assertEquals(1080, heights[2])
        assertTrue(multiviewShare(fourK, heights, budget) <= 1.0)
        assertTrue(heights.filterIndexed { i, _ -> i != 2 }.all { it < 1080 })
        assertEquals(List(4) { 360 }, multiviewHeights(fourK, 0, MultiviewQuality.AUTO, MultiviewDecode(1)))
    }

    @Test fun singleQualityStreamsCountAtTheirRealSizeAndOnlyAdaptivePicturesStepDown() {
        val decode = MultiviewDecode(multiviewPixelRate(1080) * 3)
        val loads = listOf(tsFullHd, tsFullHd, MultiviewTileLoad(540))
        val heights = multiviewHeights(loads, 2, MultiviewQuality.AUTO, decode)
        assertEquals(listOf(540, 540, 540), heights)
        val tight = MultiviewDecode(multiviewPixelRate(1080) * 2 + multiviewPixelRate(540) / 2)
        assertEquals(listOf(540, 540, 360), multiviewHeights(loads, 2, MultiviewQuality.AUTO, tight))
        assertTrue(multiviewBudgetLine(loads, heights, decode, "added").startsWith("multiview budget=avc:"))
    }

    @Test fun anotherPictureFitsWhenTheOthersCanStepDown() {
        val decode = MultiviewDecode(multiviewPixelRate(1080) * 2)
        assertTrue(multiviewFits(tiles(1060, 1060, 1060) + MultiviewTileLoad(1060), decode))
        assertFalse(multiviewHeights(tiles(1060, 1060, 1060, 1060), 3, MultiviewQuality.AUTO, decode).all { it == 1080 })
        assertTrue(multiviewFits(listOf(tsFullHd, MultiviewTileLoad(540)), decode))
        assertFalse(multiviewFits(listOf(tsFullHd, tsFullHd, MultiviewTileLoad(540)), decode))
        assertTrue(multiviewFits(listOf(tsFullHd, tsFullHd, MultiviewTileLoad(540)), MultiviewDecode(null)))
    }

    @Test fun hevcPicturesUseTheLargerOfTheTwoDecoders() {
        val hevc = MultiviewTileLoad(1060, 3840L * 2160 * 50, 50, hevc = true, adjustable = false)
        val decode = MultiviewDecode(1920L * 1080 * 60, 7680L * 4320 * 60)
        assertTrue(multiviewFits(listOf(hevc, hevc, MultiviewTileLoad(540)), decode))
        assertFalse(multiviewFits(listOf(hevc, MultiviewTileLoad(540)), MultiviewDecode(1920L * 1080 * 60, 1920L * 1080 * 30)))
        assertEquals(7680L * 4320 * 60, decode.budget(true))
        assertEquals(1920L * 1080 * 60, decode.budget(false))
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

    @Test fun decodeCapacityTakesTheLargerClaimAndDeclaredDecoderInstances() {
        val fullHd60 = 1920L * 1080 * 60
        assertNull(multiviewDecodeCapacity(null, null, 9))
        assertEquals(3840L * 2160 * 60, multiviewDecodeCapacity(fullHd60, 3840L * 2160 * 60, 9))
        assertEquals(4 * multiviewPixelRate(1080), multiviewDecodeCapacity(fullHd60, null, 9))
        assertEquals(2 * multiviewPixelRate(1080), multiviewDecodeCapacity(fullHd60, fullHd60, 2))
        assertEquals(fullHd60, multiviewDecodeCapacity(fullHd60, null, CODEC_DEFAULT_INSTANCES))
        assertEquals(fullHd60, multiviewDecodeCapacity(fullHd60, null, 1))
        assertEquals(4 * 1920L * 1080 * 30, multiviewDecodeCapacity(1920L * 1080 * 30, null, 4))
        val am9 = MultiviewDecode(multiviewDecodeCapacity(fullHd60, null, 9))
        val ts = MultiviewTileLoad(540, 1920L * 1080 * 50, 50, adjustable = false)
        assertTrue(multiviewFits(listOf(ts, ts, ts, MultiviewTileLoad(540)), am9))
    }
}
