package com.nuvio.tv.core.player.thumbnail

import com.nuvio.tv.core.player.thumbnail.SeekThumbnails.PrepareUi
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CopyEarlyRowTest {
    private fun generating(recent: List<Long>) = PrepareUi.Generating(1, 10, 30, 0L, 10_000L, recent)

    @Test
    fun picturesAreTakenOnlyWhileTheScreenChecksForTheSameTitle() {
        assertTrue(CopyEarlyRow.wanted("tt1:1:2", "tt1:1:2", PrepareUi.Checking))
        assertFalse(CopyEarlyRow.wanted("tt1:1:2", "tt1:1:3", PrepareUi.Checking))
        assertFalse(CopyEarlyRow.wanted("tt1:1:2", null, PrepareUi.Checking))
        assertFalse(CopyEarlyRow.wanted("", "", PrepareUi.Checking))
        assertFalse(CopyEarlyRow.wanted("tt1:1:2", "tt1:1:2", PrepareUi.Hidden))
        assertFalse(CopyEarlyRow.wanted("tt1:1:2", "tt1:1:2", generating(emptyList())))
        assertFalse(CopyEarlyRow.wanted("tt1:1:2", "tt1:1:2", PrepareUi.Ready(10)))
    }

    @Test
    fun theRowKeepsTheOrderTheyWereMadeInAndLetsTheOldestGo() {
        var row: CopyEarlyRow.Row<String>? = null
        val dropped = ArrayList<String>()
        for (kf in 0 until 11) {
            val added = CopyEarlyRow.add(row, "a", kf, kf * 300_000L, "p$kf", keep = 8)
            row = added.row
            dropped += added.dropped
        }
        val r = row!!
        assertEquals((3 until 11).map { "p$it" }, r.frames.map { it.picture })
        assertEquals((4..11).toList(), r.frames.map { it.seq })
        assertEquals(11, r.newestSeq)
        assertEquals(listOf("p0", "p1", "p2"), dropped)
    }

    @Test
    fun aKeyframeAlreadyInTheRowIsNotShownTwice() {
        val first = CopyEarlyRow.add(null, "a", 5, 1_000L, "p5").row
        val again = CopyEarlyRow.add(first, "a", 5, 1_000L, "p5b")
        assertEquals(1, again.row!!.frames.size)
        assertEquals("p5", again.row!!.frames.single().picture)
        assertEquals(listOf("p5b"), again.dropped)
    }

    @Test
    fun anotherTitlesPicturesAreDroppedWhole() {
        var row = CopyEarlyRow.add(null, "a", 1, 0L, "a1").row
        row = CopyEarlyRow.add(row, "a", 2, 0L, "a2").row
        val b = CopyEarlyRow.add(row, "b", 1, 0L, "b1")
        assertEquals("b", b.row!!.identity)
        assertEquals(listOf("b1"), b.row!!.frames.map { it.picture })
        assertEquals(1, b.row!!.newestSeq)
        assertEquals(listOf("a1", "a2"), b.dropped)
    }

    @Test
    fun theScreenShowsThemUntilTheSessionsRowHasPictures() {
        assertTrue(CopyEarlyRow.showsEarly(PrepareUi.Checking, "a", "a", 2))
        assertFalse(CopyEarlyRow.showsEarly(PrepareUi.Checking, "a", "a", 0))
        assertFalse(CopyEarlyRow.showsEarly(PrepareUi.Checking, "a", "b", 2))
        assertFalse(CopyEarlyRow.showsEarly(PrepareUi.Checking, "a", null, 2))
        // The session took them and generates with an empty row of its own (the copy fills): they stay.
        assertTrue(CopyEarlyRow.showsEarly(generating(emptyList()), "a", "a", 2))
        assertFalse(CopyEarlyRow.showsEarly(generating(listOf(30_000L)), "a", "a", 2))
        assertFalse(CopyEarlyRow.showsEarly(PrepareUi.Ready(10), "a", "a", 2))
        assertFalse(CopyEarlyRow.showsEarly(PrepareUi.Hidden, "a", "a", 2))
    }

    @Test
    fun theyAreLetGoOnceTheScreenNoLongerNeedsThem() {
        assertFalse(CopyEarlyRow.ends(PrepareUi.Checking))
        assertFalse(CopyEarlyRow.ends(generating(emptyList())))
        assertTrue(CopyEarlyRow.ends(generating(listOf(30_000L))))
        assertTrue(CopyEarlyRow.ends(PrepareUi.Ready(10)))
        assertTrue(CopyEarlyRow.ends(PrepareUi.Hidden))
    }

    @Test
    fun picturesAreScaledDownToTheRowNeverUp() {
        assertArrayEquals(intArrayOf(240, 102), CopyEarlyRow.scaledSize(480, 204))
        assertArrayEquals(intArrayOf(240, 135), CopyEarlyRow.scaledSize(1280, 720))
        assertArrayEquals(intArrayOf(200, 112), CopyEarlyRow.scaledSize(200, 112))
        assertArrayEquals(intArrayOf(0, 0), CopyEarlyRow.scaledSize(0, 720))
    }
}
