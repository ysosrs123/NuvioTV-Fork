package com.nuvio.tv.ui.screens.home

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeScrollCardsTest {
    @Test fun countsEveryCardThatFitsTheRow() {
        assertEquals(8, nestedPrefetchCountFor(1920, 228, 12, minimum = 2, maximum = 12))
        assertEquals(4, nestedPrefetchCountFor(1920, 468, 12, minimum = 2, maximum = 12))
    }

    @Test fun countsACardThatIsCutOffAtTheEdge() {
        assertEquals(3, nestedPrefetchCountFor(201, 90, 10, minimum = 2, maximum = 12))
        assertEquals(2, nestedPrefetchCountFor(200, 90, 10, minimum = 2, maximum = 12))
    }

    @Test fun neverGoesBelowTheMinimum() {
        assertEquals(2, nestedPrefetchCountFor(1920, 1800, 12, minimum = 2, maximum = 12))
        assertEquals(2, nestedPrefetchCountFor(100, 400, 12, minimum = 2, maximum = 12))
    }

    @Test fun neverGoesAboveTheMaximum() {
        assertEquals(12, nestedPrefetchCountFor(3840, 120, 8, minimum = 2, maximum = 12))
    }

    @Test fun unknownSizesFallBackToTheMinimum() {
        assertEquals(2, nestedPrefetchCountFor(0, 228, 12, minimum = 2, maximum = 12))
        assertEquals(2, nestedPrefetchCountFor(-48, 228, 12, minimum = 2, maximum = 12))
        assertEquals(2, nestedPrefetchCountFor(1920, 0, 12, minimum = 2, maximum = 12))
        assertEquals(2, nestedPrefetchCountFor(1920, -5, 12, minimum = 2, maximum = 12))
    }

    @Test fun negativeSpacingCountsAsNone() {
        assertEquals(4, nestedPrefetchCountFor(400, 100, -20, minimum = 2, maximum = 12))
    }

    @Test fun aRangeThatIsNotARangeGivesTheMinimum() {
        assertEquals(5, nestedPrefetchCountFor(1920, 228, 12, minimum = 5, maximum = 5))
        assertEquals(6, nestedPrefetchCountFor(1920, 228, 12, minimum = 6, maximum = 3))
    }
}
