package com.nuvio.tv.ui.screens.home

import org.junit.Assert.*
import org.junit.Test

class HeroScenePresentationTest {
    @Test fun `late logo completion can only revise the current visit`() {
        val first = HeroScenePresentation<String>().select("a")
        val displayed = first.ready(first.ticket, "art and text")
        assertEquals("art and text plus logo", displayed.revise(first.ticket) { "$it plus logo" }.displayed)
        val returned = displayed.select("b").select("a")
        assertSame(returned, returned.revise(first.ticket) { "stale logo" })
    }

    @Test fun `old scene covers loading but a timed out target never inherits its art`() {
        val a = HeroScenePresentation<String>().select("a")
        val b = a.ready(a.ticket, "a pixels + a text").select("b")
        assertEquals("a pixels + a text", b.displayed)
        val fallback = b.fallback(b.ticket, "b text + neutral gradient")
        assertEquals("b text + neutral gradient", fallback.displayed)
        assertFalse(fallback.committed)
        assertEquals("b scene", fallback.ready(b.ticket, "b scene").displayed)
    }

    @Test fun `rapid return rejects the first visit completion and timeout`() {
        val a = HeroScenePresentation<String>().select("a")
        val returned = a.select("b").select("a")
        assertSame(returned, returned.ready(a.ticket, "stale a"))
        assertSame(returned, returned.fallback(a.ticket, "stale fallback"))
        assertEquals("new a", returned.ready(returned.ticket, "new a").displayed)
    }

    @Test fun `late alternate provider cannot replace a presented composition`() {
        val requested = HeroScenePresentation<String>().select("a")
        val shown = requested.ready(requested.ticket, "first valid landscape")
        assertSame(shown, shown.ready(shown.ticket, "different TMDB composition"))
        assertSame(shown, shown.fallback(shown.ticket, "timeout"))
        assertFalse(shown.select("b").committed)
    }

    @Test fun `known portrait aliases are excluded even when advertised as background`() {
        assertTrue(landscapeHeroArtwork("a", "poster", "poster", null).urls.isEmpty())
        assertEquals(listOf("landscape", "original"),
            landscapeHeroArtwork("a", "poster", "poster", "landscape", "original").urls)
        assertTrue(landscapeHeroArtwork("a", "customPoster", "rawPoster", null, "rawPoster", "rawPoster").urls.isEmpty())
        assertTrue(landscapeHeroArtwork(null, "p", "b", "l").urls.isEmpty())
    }

    @Test fun `addon landscape poster is the last hero choice`() {
        assertEquals(listOf("poster"), landscapeHeroArtwork("a", "poster", "poster", null, widePoster = "poster").urls)
        assertEquals(listOf("background", "poster"),
            landscapeHeroArtwork("a", "poster", "background", null, widePoster = "poster").urls)
        assertTrue(landscapeHeroArtwork(null, "poster", null, null, widePoster = "poster").urls.isEmpty())
    }

    @Test fun `a collection folder without its own backdrop shows its cover picture`() {
        assertEquals(listOf("cover"),
            landscapeHeroArtwork("folder", "cover", "cover", null, "cover", "cover", widePoster = "cover").urls)
    }

    @Test fun `own stand-in keeps its logo after the timeout`() {
        val a = HeroScenePresentation<String>().select("a")
        val standIn = a.fallback(a.ticket, "a text + logo")
        assertSame(standIn, standIn.timeout(a.ticket, "a text"))
        assertEquals("a pixels + logo", standIn.ready(a.ticket, "a pixels + logo").displayed)
    }

    @Test fun `timeout still clears a scene left from another title`() {
        val a = HeroScenePresentation<String>().select("a")
        val b = a.ready(a.ticket, "a pixels + a logo").select("b")
        val timedOut = b.timeout(b.ticket, "b text")
        assertEquals("b text", timedOut.displayed)
        assertSame(timedOut, timedOut.timeout(b.ticket, "b text again"))
        assertSame(b, b.timeout(a.ticket, "stale"))
    }

    @Test fun `surface sizing normalizes TMDB variants without rewriting addon URLs`() {
        val root = "https://image.tmdb.org/t/p/"
        assertEquals(root+"w780/a.jpg", heroTransferUrl(root+"original/a.jpg", 720))
        assertEquals(root+"w1280/a.jpg", heroTransferUrl(root+"w780/a.jpg", 1382))
        assertEquals(root+"original/a.jpg", heroTransferUrl(root+"w1280/a.jpg", 1920))
        val addon = "https://addon.example/image?width=2000&token=opaque"
        assertEquals(addon, heroTransferUrl(addon, 720))
    }

    @Test fun `neighbour decode stays inside the large image partition`() {
        val mib = 1024L*1024
        assertFalse(canWarmDecodedHero(true, 8_000*mib, 256*mib, 1920, 1080))
        assertFalse(canWarmDecodedHero(false, 2_000*mib, 256*mib, 1920, 1080))
        assertFalse(canWarmDecodedHero(false, 8_000*mib, 64*mib, 1920, 1080))
        assertTrue(canWarmDecodedHero(false, 8_000*mib, 256*mib, 1920, 1080))
        assertFalse(canWarmDecodedHero(false, 8_000*mib, 256*mib, 3840, 2160))
    }
}
