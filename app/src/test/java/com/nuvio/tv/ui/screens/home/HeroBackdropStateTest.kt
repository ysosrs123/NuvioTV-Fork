package com.nuvio.tv.ui.screens.home

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeroBackdropStateTest {
    @After fun clear() { HeroBackdropState.update(null); HeroBackdropState.lastDisplayedUrl = null }

    @Test fun delayedDisplayedHeroCannotSeedANewlyClickedTitle() {
        HeroBackdropState.update("previous-hero")
        assertNull(HeroBackdropState.consumeForTitle("obsession", "movie"))
        HeroBackdropState.updateForTitle("obsession", "movie", "obsession-art")
        assertEquals("obsession-art", HeroBackdropState.consumeForTitle("obsession", "movie"))
        assertNull(HeroBackdropState.consumeForTitle("obsession", "movie"))
    }

    @Test fun navigationRequiresBothTitleAndTypeAndClearsMismatches() {
        HeroBackdropState.updateForTitle("123", "movie", "movie-art")
        assertNull(HeroBackdropState.consumeForTitle("123", "series"))
        assertNull(HeroBackdropState.consumeForTitle("123", "movie"))
        HeroBackdropState.updateForTitle("123", "movie", "movie-art")
        assertNull(HeroBackdropState.consumeForTitle("456", "movie"))
    }
}
