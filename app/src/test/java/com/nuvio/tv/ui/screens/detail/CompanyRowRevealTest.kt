package com.nuvio.tv.ui.screens.detail

import org.junit.Assert.assertEquals
import org.junit.Test

class CompanyRowRevealTest {
    @Test
    fun rowPeekingInAtTheBottomIsRevealedInFull() {
        assertEquals(
            276f,
            companyRowRevealOverflowPx(rowTopPx = 1040f, rowHeightPx = 300, windowHeightPx = 1080, paddingPx = 16f),
            0.01f
        )
    }

    @Test
    fun rowStartingBelowTheWindowCountsItsWholeHeight() {
        assertEquals(
            356f,
            companyRowRevealOverflowPx(rowTopPx = 1120f, rowHeightPx = 300, windowHeightPx = 1080, paddingPx = 16f),
            0.01f
        )
    }

    @Test
    fun visibleRowOnlyNeedsThePaddingThatIsMissing() {
        assertEquals(
            6f,
            companyRowRevealOverflowPx(rowTopPx = 770f, rowHeightPx = 300, windowHeightPx = 1080, paddingPx = 16f),
            0.01f
        )
        assertEquals(
            0f,
            companyRowRevealOverflowPx(rowTopPx = 600f, rowHeightPx = 300, windowHeightPx = 1080, paddingPx = 16f),
            0.01f
        )
    }
}
