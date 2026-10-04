package com.nuvio.tv.ui.screens.home

import org.junit.Assert.*
import org.junit.Test

class HeroArtworkTest {
    private fun selection(id: String, vararg urls: String?) = heroArtworkSelection(id, *urls)

    @Test fun focusChangeClearsThePreviousTitleBeforeNewImageLoads() {
        val a = HeroArtworkLoadState<String>(selection("north", "north-art"))
        val loaded = a.loaded(a.ticket!!, "north pixels")
        val b = loaded.select(selection("road", "road-art"))
        assertNull(b.ready)
        assertEquals("road-art", b.ticket!!.url)
        assertSame(b, b.loaded(a.ticket!!, "late north pixels"))
    }

    @Test fun rapidReturnRejectsAnEarlierRequestForTheSameTitleAndUrl() {
        val a = HeroArtworkLoadState<String>(selection("north", "north-art"))
        val returned = a.select(selection("road", "road-art")).select(a.selection)
        assertSame(returned, returned.loaded(a.ticket!!, "stale pixels"))
        assertEquals("fresh pixels", returned.loaded(returned.ticket!!, "fresh pixels").ready)
    }

    @Test fun enrichmentKeepsOnlyTheSameTitlesReadyImage() {
        val a = HeroArtworkLoadState<String>(selection("north", "addon-art"))
        val enriched = a.loaded(a.ticket!!, "addon pixels")
            .select(selection("north", "tmdb-art", "addon-art"))
        assertEquals("addon pixels", enriched.ready)
        assertSame(enriched, enriched.failed(a.ticket!!))
        assertEquals("tmdb pixels", enriched.loaded(enriched.ticket!!, "tmdb pixels").ready)
    }

    @Test fun brokenBackdropsTryOwnPosterThenStopWithoutOldTitleArtwork() {
        val a = HeroArtworkLoadState<String>(selection("north", "north-art"))
        var b = a.loaded(a.ticket!!, "north pixels")
            .select(selection("road", "broken-road", "road-poster"))
        b = b.failed(b.ticket!!)
        assertEquals("road-poster", b.ticket!!.url)
        assertNull(b.ready)
        b = b.failed(b.ticket!!)
        assertNull(b.ticket)
        assertNull(b.ready)
    }

    @Test fun emptyOrUnresolvedFocusNeverInheritsArtwork() {
        val a = HeroArtworkLoadState<String>(selection("north", "north-art"))
        val loaded = a.loaded(a.ticket!!, "north pixels")
        assertNull(loaded.select(selection("missing", null, "")).ready)
        val unresolved = heroArtworkSelection(null, "row-fallback")
        assertTrue(unresolved.urls.isEmpty())
        assertNull(loaded.select(unresolved).ticket)
    }

    @Test fun candidatesKeepBackdropPriorityAndSkipBlanksAndDuplicates() {
        assertEquals(listOf("enriched", "addon", "poster"),
            selection("north", "enriched", "addon", "", null, "poster", "addon").urls)
    }
}
