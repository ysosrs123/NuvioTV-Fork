package com.nuvio.tv.core.iptv

import org.junit.Assert.*
import org.junit.Test

class SportsRecordsTest {
    @Test fun recordsReadPerSport() {
        assertEquals(SportsRecord(RecordShape.WIN_DRAW_LOSS, listOf(4, 0, 1)), SportsRecords.parse("soccer", "4-0-1"))
        assertEquals(SportsRecord(RecordShape.WIN_LOSS, listOf(2, 2)), SportsRecords.parse("american-football", "2-2"))
        assertEquals(SportsRecord(RecordShape.WIN_LOSS_TIE, listOf(2, 2, 1)), SportsRecords.parse("american-football", "2-2-1"))
        assertEquals(SportsRecord(RecordShape.WIN_LOSS_OVERTIME, listOf(10, 5, 2)), SportsRecords.parse("ice-hockey", "10-5-2"))
        assertEquals(SportsRecord(RecordShape.WIN_LOSS, listOf(19, 4)), SportsRecords.parse("australian-football", "19-4"))
        assertEquals(SportsRecord(RecordShape.WIN_LOSS, listOf(1, 0)), SportsRecords.parse("basketball", "1-0"))
    }

    @Test fun unknownShapesStayAsTheyAre() {
        assertNull(SportsRecords.parse("soccer", "4-1"))
        assertNull(SportsRecords.parse("rugby", "3-1-0"))
        assertNull(SportsRecords.parse("australian-football", "19-4-1"))
        assertNull(SportsRecords.parse("soccer", "WWDLL"))
        assertNull(SportsRecords.parse("soccer", null))
    }
}
