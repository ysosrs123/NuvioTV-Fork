package com.nuvio.tv.core.player.thumbnail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class UsenetCopyTest {
    @Test
    fun theCopyHasAMinuteToBeConfirmed() {
        assertNull(UsenetCopy.giveUp(confirmed = false, sinceStartMs = 59_000L, sinceProgressMs = null))
        assertEquals("no copy confirmed in 60 s", UsenetCopy.giveUp(false, 60_000L, null))
        assertEquals("no copy confirmed in 60 s", UsenetCopy.giveUp(false, 61_000L, 2_000L))
        // A confirmed copy that keeps coming has no time limit.
        assertNull(UsenetCopy.giveUp(confirmed = true, sinceStartMs = 600_000L, sinceProgressMs = 3_000L))
    }

    @Test
    fun twentySecondsWithoutACopyThumbnailEndIt() {
        assertNull(UsenetCopy.giveUp(false, 30_000L, 19_000L))
        assertEquals("no copy thumbnail in 20 s", UsenetCopy.giveUp(false, 30_000L, 20_000L))
        assertNotNull(UsenetCopy.giveUp(true, 300_000L, 25_000L))
    }

    @Test
    fun aScrubStopReadsOnePictureFromTheStream() {
        val stepAt = 100_000L
        // Still stepping: nothing yet.
        assertEquals(-1, UsenetCopy.landingSlot(42, stepAt + 300L, stepAt, servedStopAt = 0L, needed = true))
        // Rested: that one slot.
        assertEquals(42, UsenetCopy.landingSlot(42, stepAt + 800L, stepAt, servedStopAt = 0L, needed = true))
        // Once per stop.
        assertEquals(-1, UsenetCopy.landingSlot(42, stepAt + 5_000L, stepAt, servedStopAt = stepAt, needed = true))
        // The next stop reads again.
        assertEquals(50, UsenetCopy.landingSlot(50, stepAt + 9_000L, stepAt + 8_000L, servedStopAt = stepAt, needed = true))
        // A spot that already shows a thumbnail, or no scrub at all, reads nothing.
        assertEquals(-1, UsenetCopy.landingSlot(42, stepAt + 800L, stepAt, servedStopAt = 0L, needed = false))
        assertEquals(-1, UsenetCopy.landingSlot(-1, stepAt + 800L, stepAt, servedStopAt = 0L, needed = true))
        assertEquals(-1, UsenetCopy.landingSlot(42, 800L, 0L, servedStopAt = 0L, needed = true))
    }

    @Test
    fun theCopyMakesWhatTheGeneratingScreenCountsFirst() {
        val order = SeekGrid.latticeOrder(12, listOf(6, 3, 1), anchorSlot = 0, beforeSlots = 0, afterSlots = 4)
        assertEquals(listOf(0, 1, 2, 3, 4, 6, 9, 5, 7, 8, 10, 11), order)
        val same = (0 until 12).associateWith { intArrayOf(it) }
        assertEquals(listOf(0, 3, 6, 9, 1, 2, 4, 5, 7, 8, 10, 11), UsenetCopy.screenFirst(order, same, 3))
        assertEquals(order, UsenetCopy.screenFirst(order, same, 1))
        // A copy of another length: copy slot 2 serves own slots 2 and 3, so it counts.
        val pal = mapOf(0 to intArrayOf(0), 1 to intArrayOf(1), 2 to intArrayOf(2, 3), 3 to intArrayOf(4))
        assertEquals(listOf(0, 2, 1, 3), UsenetCopy.screenFirst(listOf(0, 1, 2, 3), pal, 3))
        // ... and its picture is taken where own slot 3 lands, not slot 2.
        assertEquals(3, UsenetCopy.pictureFor(pal.getValue(2), 3))
        assertEquals(2, UsenetCopy.pictureFor(pal.getValue(2), 1))
        assertEquals(4, UsenetCopy.pictureFor(pal.getValue(3), 3))
    }

    private fun fetches(
        screenUp: Boolean = false, playing: Boolean = true, serverCap: Int = 8, smallBox: Boolean = false,
        rateLimited: Boolean = false, torBox: Boolean = false, refused: Boolean = false,
        headroom: Int = ThumbFetchLanes.MAX,
    ) = UsenetCopy.fetches(screenUp, playing, serverCap, smallBox, rateLimited, torBox, refused, headroom)

    @Test
    fun behindTheGeneratingScreenTorBoxTakesFourAndOthersWhatTheLedgerAllows() {
        assertEquals(4, fetches(screenUp = true, playing = false))
        assertEquals(4, fetches(screenUp = true, playing = false, torBox = true))
        assertEquals(3, fetches(screenUp = true, playing = false, serverCap = 3))
        assertEquals(2, fetches(screenUp = true, playing = false, smallBox = true))
        assertEquals(1, fetches(screenUp = true, playing = false, serverCap = 1))
        // Playback's buffer is not read while the screen holds it.
        assertEquals(4, fetches(screenUp = true, playing = false, headroom = 0))
    }

    @Test
    fun whilePlayingFourOnServersThatDidNotRefuseAndThreeOnTorBox() {
        assertEquals(4, fetches())
        assertEquals(4, fetches(screenUp = true, playing = true))
        assertEquals(4, fetches(playing = false))
        assertEquals(3, fetches(torBox = true))
        assertEquals(1, fetches(refused = true))
        assertEquals(3, fetches(torBox = true, refused = true))
        assertEquals(2, fetches(serverCap = 2))
        assertEquals(2, fetches(torBox = true, serverCap = 2))
        assertEquals(1, fetches(smallBox = true))
    }

    @Test
    fun whilePlayingTheBufferCapsTheLanes() {
        assertEquals(2, fetches(headroom = 2))
        assertEquals(1, fetches(headroom = 0))
        assertEquals(3, fetches(torBox = true, headroom = 5))
    }

    @Test
    fun aRateLimitMeansOneCopyPictureAtATime() {
        // A server that answered "too many requests" in the last half hour gets one at a time.
        assertEquals(1, fetches(screenUp = true, playing = false, rateLimited = true))
        assertEquals(1, fetches(rateLimited = true))
    }
}
