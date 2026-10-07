package com.nuvio.tv.core.iptv

import org.junit.Assert.assertEquals
import org.junit.Test

class GuideLayoutTest {
    private val minute = 60_000L

    @Test fun titleStaysAtTheVisibleEdgeForProgrammesThatStartedEarlier() {
        assertEquals(20 * minute, guideStickyOffsetMillis(0, 120 * minute, 20 * minute, 15 * minute))
    }

    @Test fun programmesStartingInsideTheWindowNeedNoOffset() {
        assertEquals(0L, guideStickyOffsetMillis(30 * minute, 90 * minute, 20 * minute, 15 * minute))
        assertEquals(0L, guideStickyOffsetMillis(20 * minute, 90 * minute, 20 * minute, 15 * minute))
    }

    @Test fun offsetStopsShortOfTheEndSoTheTitleKeepsRoom() {
        assertEquals(45 * minute, guideStickyOffsetMillis(0, 60 * minute, 55 * minute, 15 * minute))
        assertEquals(0L, guideStickyOffsetMillis(0, 10 * minute, 5 * minute, 15 * minute))
        assertEquals(0L, guideStickyOffsetMillis(10 * minute, 10 * minute, 20 * minute, 0))
    }

    @Test fun visibleRowsCountsWholeRowsWithGaps() {
        assertEquals(5, guideVisibleRows(316f, 60f, 4f))
        assertEquals(6, guideVisibleRows(316f, 42f, 4f))
        assertEquals(0, guideVisibleRows(30f, 42f, 4f))
    }
}
