package com.nuvio.tv.ui.screens.detail

import org.junit.Assert.*
import org.junit.Test

class DetailBackdropSelectionTest {
    @Test fun `navigation artwork survives raw and enriched metadata`() {
        val loading = DetailBackdropSelection().select("clicked-title")
        assertEquals("clicked-title", loading.select("clicked-title", "addon", "poster").url)
        assertEquals("clicked-title", loading.select("clicked-title", "tmdb", "poster").url)
    }

    @Test fun `direct entry retains first backdrop when enrichment arrives`() {
        val initial = DetailBackdropSelection().select(null, "addon", "poster")
        assertEquals(initial, initial.select(null, "tmdb", "new-poster"))
    }

    @Test fun `missing artwork can arrive later without blocking metadata`() {
        val empty = DetailBackdropSelection().select(null, "", " ")
        assertNull(empty.url)
        assertEquals("enriched", empty.select(null, "enriched").url)
    }

    @Test fun `failed image advances once to a working fallback`() {
        val initial = DetailBackdropSelection().select("broken-seed", "addon", "poster")
        val fallback = initial.failed("broken-seed").select("broken-seed", "addon", "poster")
        assertEquals("addon", fallback.url)
        assertEquals("poster", fallback.failed("addon").select("broken-seed", "addon", "poster").url)
    }

    @Test fun `stale failure cannot clear a newer fallback`() {
        val current = DetailBackdropSelection().select("bad").failed("bad").select("good")
        assertEquals(current, current.failed("bad"))
        assertEquals(current, current.failed(null))
    }

    @Test fun `exhausted fallbacks do not retry forever and accept a new candidate`() {
        val exhausted = DetailBackdropSelection().select("bad").failed("bad").select("bad")
        assertNull(exhausted.url)
        assertEquals("later", exhausted.select("bad", "later").url)
    }

    @Test fun `new navigation entry cannot retain previous title or failures`() {
        val old = DetailBackdropSelection().select("shared").failed("shared").select("old-title")
        val next = DetailBackdropSelection().select("shared", "new-title")
        assertEquals("old-title", old.url)
        assertEquals("shared", next.url)
        assertTrue(next.failedUrls.isEmpty())
    }
}
