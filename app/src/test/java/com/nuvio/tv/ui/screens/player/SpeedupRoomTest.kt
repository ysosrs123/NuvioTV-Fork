package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedupRoomTest {

    @Test
    fun `new pieces only fill what the live depth leaves`() {
        assertEquals(8, ParallelRangeDataSource.speedupRoom(depth = 8, running = 0))
        assertEquals(3, ParallelRangeDataSource.speedupRoom(depth = 8, running = 5))
        assertEquals(0, ParallelRangeDataSource.speedupRoom(depth = 8, running = 8))
    }

    @Test
    fun `requests already over the depth start nothing new`() {
        // Pieces left behind by a seek or read by another cursor of the same file still hold a connection.
        assertEquals(-11, ParallelRangeDataSource.speedupRoom(depth = 8, running = 19))
    }

    @Test
    fun `a miscount below zero never adds room`() {
        assertEquals(4, ParallelRangeDataSource.speedupRoom(depth = 4, running = -2))
    }
}
