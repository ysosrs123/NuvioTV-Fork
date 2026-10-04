package com.nuvio.tv.ui.screens.home

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class PageHeroArtworkTest {
    @After fun reset() { HeroBackdropState.update(null); HeroBackdropState.lastDisplayedUrl = null }

    @Test fun `logo and backdrop travel together once for the clicked title`() {
        HeroBackdropState.updateForTitle("a", "series", "a.jpg", "a-logo.png")
        HeroBackdropState.selectPageArtwork("series:b", "b.jpg")
        assertEquals(TitleHeroArtwork("a.jpg", "a-logo.png"),
            HeroBackdropState.consumeArtworkForTitle("a", "series"))
        assertNull(HeroBackdropState.consumeArtworkForTitle("a", "series"))
    }

    @Test fun `different title or type cannot inherit a logo`() {
        for ((id, type) in listOf("b" to "series", "a" to "movie")) {
            HeroBackdropState.updateForTitle("a", "series", "a.jpg", "a-logo.png")
            assertNull(HeroBackdropState.consumeArtworkForTitle(id, type))
            assertNull(HeroBackdropState.consumeArtworkForTitle("a", "series"))
        }
    }

    @Test fun `legacy handoff clears the previous logo`() {
        HeroBackdropState.updateForTitle("a", "movie", "a.jpg", "a-logo.png")
        HeroBackdropState.update("legacy.jpg")
        assertNull(HeroBackdropState.consumeArtworkForTitle("a", "movie"))
        HeroBackdropState.updateForTitle("b", "movie", "b.jpg")
        assertEquals(TitleHeroArtwork("b.jpg", null),
            HeroBackdropState.consumeArtworkForTitle("b", "movie"))
    }

    @Test fun `focused title without artwork clears the previous page image`() {
        HeroBackdropState.selectPageArtwork("movie:a", "a.jpg")
        HeroBackdropState.selectPageArtwork("movie:b", null)
        assertEquals("movie:b", HeroBackdropState.pageArtwork.ownerKey)
        assertNull(HeroBackdropState.pageArtwork.url)
    }

    @Test fun `late success from a previous visit cannot replace displayed artwork`() {
        val first = HeroBackdropState.selectPageArtwork("movie:a", "a.jpg")
        val second = HeroBackdropState.selectPageArtwork("movie:b", "b.jpg")
        HeroBackdropState.recordDisplayedArtwork(second, "b.jpg")
        HeroBackdropState.selectPageArtwork("movie:a", "a.jpg")
        HeroBackdropState.recordDisplayedArtwork(first, "obsolete.jpg")
        assertEquals("b.jpg", HeroBackdropState.lastDisplayedUrl)
        assertEquals("a.jpg", HeroBackdropState.pageArtwork.url)
    }

    @Test fun `page focus updates do not steal a title specific navigation handoff`() {
        HeroBackdropState.updateForTitle("a", "movie", "a.jpg")
        HeroBackdropState.selectPageArtwork("movie:b", "b.jpg")
        assertEquals("a.jpg", HeroBackdropState.consumeForTitle("a", "movie"))
        assertEquals("b.jpg", HeroBackdropState.pageArtwork.url)
        assertNull(HeroBackdropState.consumeForTitle("a", "movie"))
    }

    @Test fun `same title and URL retain the request generation`() {
        val before = HeroBackdropState.selectPageArtwork("movie:a", "a.jpg")
        assertEquals(before, HeroBackdropState.selectPageArtwork("movie:a", "a.jpg"))
        assertNotEquals(before, HeroBackdropState.selectPageArtwork("movie:a", "better.jpg"))
    }
}
