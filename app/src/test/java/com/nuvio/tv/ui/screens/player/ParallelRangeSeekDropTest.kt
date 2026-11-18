package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A long seek drops the pieces left behind; a long idle drops the pooled connections before the next piece. */
class ParallelRangeSeekDropTest {
    @Test
    fun `a long seek leaves the old position's pieces behind`() {
        val held = listOf(10L, 11L, 12L, 13L, 14L)
        assertEquals(held, ParallelRangeDataSource.leftBySeek(held, reader = 10L, target = 200L, window = 4))
        // The pieces already fetched at the new position stay.
        assertEquals(held, ParallelRangeDataSource.leftBySeek(held + 200L + 202L, 10L, 200L, window = 4))
        // A seek within the reader's window keeps everything.
        assertNull(ParallelRangeDataSource.leftBySeek(held, reader = 10L, target = 13L, window = 4))
        assertNull(ParallelRangeDataSource.leftBySeek(held, reader = -1L, target = 200L, window = 4))
        // Backwards: the old position's pieces go, those at the new one stay.
        assertEquals(held, ParallelRangeDataSource.leftBySeek(held + 1L, reader = 12L, target = 0L, window = 4))
    }

    @Test
    fun `the jump to a resume point leaves the read-ahead from the start behind`() {
        val head = listOf(0L, 1L, 2L, 3L, 4L)
        val tail = 10_487L
        // Body of an 88 GB file in 8 MB pieces: the start pieces go, the index piece at the end stays.
        assertEquals(head, ParallelRangeDataSource.leftByResumeStart(head + tail, target = 1010L, window = 4, totalChunks = 10_488L))
        // The pieces at the resume point stay.
        assertEquals(head, ParallelRangeDataSource.leftByResumeStart(head + 1010L + 1011L, 1010L, window = 4, totalChunks = 10_488L))
        // Still reading the start, or the index at the end: nothing dropped yet.
        assertNull(ParallelRangeDataSource.leftByResumeStart(head, target = 0L, window = 4, totalChunks = 10_488L))
        assertNull(ParallelRangeDataSource.leftByResumeStart(head, target = 5L, window = 4, totalChunks = 10_488L))
        assertNull(ParallelRangeDataSource.leftByResumeStart(head, target = tail, window = 4, totalChunks = 10_488L))
        // A resume point inside the first pieces uses them.
        assertNull(ParallelRangeDataSource.leftByResumeStart(head, target = 3L, window = 4, totalChunks = 10_488L))
        // Length not known yet.
        assertNull(ParallelRangeDataSource.leftByResumeStart(head, target = 1010L, window = 4, totalChunks = -1L))
    }

    @Test
    fun `a long idle is spotted once before the next piece`() {
        var t = 0L
        val m = RequestMeter { t }
        assertFalse(m.takeStale(60_000L))
        m.started()
        t = 120_000L
        assertFalse(m.takeStale(60_000L))
        m.ended()
        t = 179_000L
        assertFalse(m.takeStale(60_000L))
        t = 180_000L
        assertTrue(m.takeStale(60_000L))
        assertFalse(m.takeStale(60_000L))
    }
}
