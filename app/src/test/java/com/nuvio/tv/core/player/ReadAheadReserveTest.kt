package com.nuvio.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadAheadReserveTest {
    private val mb = 1024L * 1024L

    @Test fun aReserveTakesUpToSixtyPercentOfTheBudget() {
        assertEquals(30 * mb, ReadAheadReserve.reservable(100 * mb, 30 * mb))
        assertEquals(60 * mb, ReadAheadReserve.reservable(100 * mb, 80 * mb))
        assertEquals(0L, ReadAheadReserve.reservable(100 * mb, -5 * mb))
    }

    @Test fun aBudgetAtTheSizingMinimumLendsNothing() {
        // On a 2 GB Fire TV Stick: a 30 MB reserve on the 50 MB minimum left a 4K remux 20 MB at its first frame.
        assertEquals(0L, ReadAheadReserve.reservable(50 * mb, 30 * mb))
        assertEquals(0L, ReadAheadReserve.reservable(20 * mb, 10 * mb))
        assertEquals(51 * mb * 60 / 100, ReadAheadReserve.reservable(51 * mb, 40 * mb))
    }
}
