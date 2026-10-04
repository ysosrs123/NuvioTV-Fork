package com.nuvio.tv.ui.screens.home

import org.junit.Assert.*
import org.junit.Test

class HomeRowNavigationTest {
    @Test fun verticalNavigationCrossesRowsInsteadOfTransformedNeighbours() {
        assertEquals(1, adjacentHomeRowIndex(0, 12, true))
        assertEquals(0, adjacentHomeRowIndex(1, 12, false))
        assertEquals(9, adjacentHomeRowIndex(8, 12, true))
    }
    @Test fun boundariesAndEmptyOrRemovedRowsDoNotCreateInvalidTargets() {
        assertNull(adjacentHomeRowIndex(0, 12, false))
        assertNull(adjacentHomeRowIndex(11, 12, true))
        assertNull(adjacentHomeRowIndex(-1, 12, true))
        assertNull(adjacentHomeRowIndex(0, 0, true))
        assertNull(adjacentHomeRowIndex(12, 12, false))
    }
}
