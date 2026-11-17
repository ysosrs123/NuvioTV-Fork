package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerStartPositionTest {

    @Test
    fun `a position carried over by a rebuild is the start, not a seek after the buffer filled`() {
        assertEquals(600_000L, PlayerStartPosition.choose(null, 600_000L) { 0L })
    }

    @Test
    fun `the caller's position wins, then the carried over one, then the saved resume`() {
        assertEquals(5_000L, PlayerStartPosition.choose(5_000L, 600_000L) { 9L })
        assertEquals(9L, PlayerStartPosition.choose(null, null) { 9L })
        assertEquals(9L, PlayerStartPosition.choose(null, 0L) { 9L })
    }

    @Test
    fun `a share-only resume is applied once the length is known and the file can seek`() {
        assertTrue(PlayerStartPosition.percentResumeDue(0L, 42f, 7_200_000L, seekable = true, firstFrameShown = false))
        assertFalse(PlayerStartPosition.percentResumeDue(0L, 42f, Long.MIN_VALUE + 1, seekable = true, firstFrameShown = false))
        assertFalse(PlayerStartPosition.percentResumeDue(0L, 42f, 7_200_000L, seekable = false, firstFrameShown = false))
    }

    @Test
    fun `a saved position or a film already showing is left to the usual path`() {
        assertFalse(PlayerStartPosition.percentResumeDue(60_000L, 42f, 7_200_000L, seekable = true, firstFrameShown = false))
        assertFalse(PlayerStartPosition.percentResumeDue(0L, null, 7_200_000L, seekable = true, firstFrameShown = false))
        assertFalse(PlayerStartPosition.percentResumeDue(0L, 0f, 7_200_000L, seekable = true, firstFrameShown = false))
        assertFalse(PlayerStartPosition.percentResumeDue(0L, 42f, 7_200_000L, seekable = true, firstFrameShown = true))
    }

    @Test
    fun `a resume point at or past the real end is caught`() {
        assertTrue(PlayerStartPosition.pastTheEnd(128_759_440L, 2_548_966L))
        assertTrue(PlayerStartPosition.pastTheEnd(2_546_000L, 2_548_966L))
        assertFalse(PlayerStartPosition.pastTheEnd(1_200_000L, 2_548_966L))
        assertFalse(PlayerStartPosition.pastTheEnd(0L, 2_548_966L))
        assertFalse(PlayerStartPosition.pastTheEnd(128_759_440L, 0L))
    }
}
