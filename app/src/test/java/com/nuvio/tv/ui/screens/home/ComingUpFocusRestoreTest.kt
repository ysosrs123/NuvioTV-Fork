package com.nuvio.tv.ui.screens.home

import org.junit.Assert.assertEquals
import org.junit.Test

class ComingUpFocusRestoreTest {
    @Test
    fun `back from a Coming up card goes back to that row, not Continue Watching`() {
        val comingUp = HomeScreenFocusState(focusedRowKey = MODERN_COMING_UP_ROW_KEY, focusedRowIndex = -1, focusedItemIndex = 4, hasSavedFocus = true)
        assertEquals(4, classicSavedCardRowIndex(comingUp, MODERN_COMING_UP_ROW_KEY))
        assertEquals(-1, classicSavedCardRowIndex(comingUp, "continue_watching"))
        val cw = comingUp.copy(focusedRowKey = "continue_watching", focusedItemIndex = 2)
        assertEquals(2, classicSavedCardRowIndex(cw, "continue_watching"))
        assertEquals(-1, classicSavedCardRowIndex(cw, MODERN_COMING_UP_ROW_KEY))
        assertEquals(-1, classicSavedCardRowIndex(cw.copy(hasSavedFocus = false), "continue_watching"))
    }
}
