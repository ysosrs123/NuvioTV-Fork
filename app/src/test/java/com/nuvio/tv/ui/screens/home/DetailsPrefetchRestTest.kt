package com.nuvio.tv.ui.screens.home

import org.junit.Assert.assertEquals
import org.junit.Test

class DetailsPrefetchRestTest {
    @Test fun waitsTheRestOfTheSecondAfterTheSettle() {
        assertEquals(750L, DetailsPrefetchRest.remainingMs(settledAtMs = 1_000L, nowMs = 1_000L))
        assertEquals(400L, DetailsPrefetchRest.remainingMs(settledAtMs = 1_000L, nowMs = 1_350L))
    }

    @Test fun aSlowHeroFetchAddsNoFurtherWait() {
        assertEquals(0L, DetailsPrefetchRest.remainingMs(settledAtMs = 1_000L, nowMs = 2_400L))
    }
}
