package com.nuvio.tv.core.iptv

import org.junit.Assert.*
import org.junit.Test

class CatalogueReviewTest {
    @Test fun onlyLargeDropsOfAListWithChannelsAreHeld() {
        assertTrue(CatalogueReview.holds(RefreshDecision.SHRINK_REQUIRES_REVIEW, 100))
        assertTrue(CatalogueReview.holds(RefreshDecision.EMPTY_REQUIRES_REVIEW, 1))
        assertFalse(CatalogueReview.holds(RefreshDecision.EMPTY_REQUIRES_REVIEW, 0))
        for (decision in listOf(RefreshDecision.PUBLISH, RefreshDecision.STALE, RefreshDecision.INVALID)) assertFalse(CatalogueReview.holds(decision, 100))
        assertTrue(CatalogueReview.clearsHeld(RefreshDecision.PUBLISH))
        assertFalse(CatalogueReview.clearsHeld(RefreshDecision.STALE))
    }

    @Test fun heldListsExpireAndFollowTheSourceSettings() {
        val held = HeldCatalogue(RefreshDecision.SHRINK_REQUIRES_REVIEW, 1200, 300, 4, 1_000)
        assertEquals(held, CatalogueReview.current(held, 4, 2_000))
        assertNull(CatalogueReview.current(held, 5, 2_000))
        assertNull(CatalogueReview.current(held, 4, 1_000 + CatalogueReview.MAX_AGE_MILLIS + 1))
        assertNull(CatalogueReview.current(held, 4, 500))
        assertNull(CatalogueReview.current(null, 4, 2_000))
    }

    @Test fun summariesAndContentRoundTrip() {
        val held = HeldCatalogue(RefreshDecision.EMPTY_REQUIRES_REVIEW, 10, 0, 2, 99)
        assertEquals(held, CatalogueReview.decodeSummary(CatalogueReview.encodeSummary(held)))
        assertNull(CatalogueReview.decodeSummary("{}"))
        assertNull(CatalogueReview.decodeSummary(CatalogueReview.encodeSummary(held.copy(decision = RefreshDecision.PUBLISH))))
        val content = HeldContent(listOf(HeldCandidate(ChannelCandidate("A", "http://a/1", "1", "a.uk", null), mapOf("group-title" to "News")),
            HeldCandidate(ChannelCandidate("B", "http://a/2"), emptyMap())), "etag", null, listOf("https://epg.example/g.xml"))
        val decoded = CatalogueReview.decodeContent(CatalogueReview.encodeContent(content))
        assertEquals(content.records.map { it.channel }, decoded.records.map { it.channel })
        assertEquals(content.records.map { it.attributes }, decoded.records.map { it.attributes })
        assertEquals("etag", decoded.etag); assertNull(decoded.lastModified); assertEquals(content.guideUrls, decoded.guideUrls)
    }
}
