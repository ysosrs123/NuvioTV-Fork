package com.nuvio.tv.core.player.thumbnail

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CopyPicturesEarlyTest {
    private val spacingUs = SeekThumbnails.SPACING_MS * 1000L

    /** Keyframes every [everyUs], like a 720p copy with a keyframe at each scene cut. */
    private fun nearestIn(count: Int, everyUs: Long): (Long) -> Int = { t ->
        ((t + everyUs / 2) / everyUs).toInt().coerceIn(0, count - 1)
    }

    @Test
    fun theWholeFilmFirstThenWherePlaybackStarts() {
        val copyMs = 7_434_000L
        val kfs = CopyEarly.keyframes(copyMs, anchorMs = 3_595_000L, cap = 60, nearest = nearestIn(1091, 6_800_000L))
        assertEquals(60, kfs.size)
        assertEquals(kfs.size, kfs.toSet().size)
        // The coarse sweep: one slot every 30 slots over the whole film, from the start.
        val coarse = (0 until ReleaseMatch.slotCount(copyMs, SeekThumbnails.SPACING_MS) step 30).map {
            nearestIn(1091, 6_800_000L)(it * spacingUs + spacingUs / 2)
        }.distinct()
        assertEquals(coarse, kfs.take(coarse.size))
        // Then near the resume point (3595 s = keyframe ~529).
        val local = kfs.drop(coarse.size)
        assertTrue(local.isNotEmpty())
        assertTrue(local.all { kotlin.math.abs(it - 529) <= 40 })
    }

    @Test
    fun aKeyframeServingSeveralSlotsIsMadeOnce() {
        val kfs = CopyEarly.keyframes(600_000L, 0L, cap = 100, nearest = nearestIn(10, 60_000_000L))
        assertEquals(kfs.size, kfs.toSet().size)
        assertTrue(kfs.size <= 10)
        assertTrue(CopyEarly.keyframes(0L, 0L, 10) { 0 }.isEmpty())
        assertTrue(CopyEarly.keyframes(600_000L, 0L, 0) { 0 }.isEmpty())
    }

    @Test
    fun smallBoxesKeepFewerAndEveryStopIsHonoured() {
        val strong = CopyEarly.caps(true)
        val small = CopyEarly.caps(false)
        assertTrue(small.first < strong.first && small.second < strong.second)
        assertTrue(strong.second <= 4L * 1024 * 1024)
        assertNull(CopyEarly.stopsBecause(0, 0L, small, null))
        assertNotNull(CopyEarly.stopsBecause(small.first, 0L, small, null))
        assertNotNull(CopyEarly.stopsBecause(0, small.second, small, null))
        assertNotNull(CopyEarly.stopsBecause(0, 0L, small, "memory (MemAvailable 90 MB < 150)"))
    }

    private fun pic(kf: Int, w: Int = 480, h: Int = 204) = CopyEarlyPicture(kf, ByteArray(10), w, h)

    @Test
    fun picturesGoIntoTheSlotsWhoseKeyframeThePassPicked() {
        val kfSlots = mapOf(5 to listOf(30, 31), 9 to listOf(60), 12 to listOf(90))
        val stored = setOf(60)
        val r = CopyEarlyFit.slots(listOf(pic(5), pic(9), pic(7), pic(12, w = 320, h = 136)), kfSlots, 480, 270,
            wanted = { it !in stored })
        assertEquals(1, r.used.size)
        assertEquals(listOf(30, 31), r.used.single().slots)
        // Drawn at the copy's own size, centred with bars, as the pass draws it.
        assertEquals(480, r.used.single().plan.renderW)
        assertEquals(204, r.used.single().plan.renderH)
        assertEquals(33, r.used.single().plan.top)
        assertEquals(2, r.otherKeyframe)
        assertEquals(1, r.otherShape)
    }

    @Test
    fun aListNobodyTakesIsEmptyAndTakingTwiceGivesNothing() = runBlocking {
        val e = CopyEarly("copy", 0L)
        assertTrue(e.take().isEmpty())
        e.drop()
        assertEquals(0, e.count)
        val link = CopyLink("https://x/y", emptyMap(), "a ready link")
        link.early = CopyEarly("copy", 0L)
        assertTrue(link.takeEarly().isEmpty())
        assertNull(link.early)
        link.early = CopyEarly("copy", 0L)
        link.dropPreread()
        assertNull(link.early)
    }
}
