package com.nuvio.tv.core.iptv

import org.junit.Assert.*
import org.junit.Test

class ChannelIdentityTest {
    private val initial = StoredChannel("saved", "source", ChannelCandidate("Old", "https://example.invalid/old", "101", "guide", "hd"))
    @Test fun rotatingLocatorAndNamePreserveAuthoritativeIdentity() {
        val next = initial.data.copy(name = "New", locator = "https://example.invalid/new")
        val result = ChannelIdentityReconciler().reconcile("source", listOf(initial), listOf(next))
        assertEquals("saved", result.channels.single().channel.id)
        assertEquals(IdentityMatch.PROVIDER_ID, result.channels.single().match)
    }
    @Test fun equalGuideIdWithConflictingProviderIdCannotStealFavourite() {
        val result = ChannelIdentityReconciler().reconcile("source", listOf(initial), listOf(initial.data.copy(providerId = "102")))
        assertNotEquals("saved", result.channels.single().channel.id)
        assertFalse(result.unavailable.single().available)
    }
    @Test fun missingIdCannotStealAuthoritativeMatchByAppearingFirst() {
        val result = ChannelIdentityReconciler().reconcile("source", listOf(initial), listOf(initial.data.copy(providerId = null), initial.data.copy(locator = "https://example.invalid/rotated")))
        assertNotEquals("saved", result.channels[0].channel.id)
        assertEquals("saved", result.channels[1].channel.id)
    }
    @Test fun rotatingTokenChurnKeepsOnlyOverlaidTombstonesWithinTheLimit() {
        val reconciler = ChannelIdentityReconciler()
        var stored = emptyList<StoredChannel>(); var favourite: String? = null
        repeat(8) { refresh ->
            val incoming = (0 until 10_000).map { ChannelCandidate("Channel $it", "https://example.invalid/$it?token=$refresh") }
            val result = reconciler.reconcile("source", stored, incoming)
            if (refresh == 0) favourite = result.channels.first().channel.id
            val kept = requireNotNull(result.retainTombstones(setOfNotNull(favourite)))
            if (refresh > 0) { assertEquals(listOf(favourite), kept.retained.map { it.id }); assertTrue(kept.dropped.size >= 9_999) }
            stored = result.channels.map { it.channel } + kept.retained
            assertTrue(stored.size <= 10_001)
        }
        val full = ChannelReconciliation((0 until 3).map { ReconciledChannel(StoredChannel("n$it", "source", ChannelCandidate("N", "https://example.invalid/n$it")), IdentityMatch.NEW) },
            (0 until 3).map { StoredChannel("t$it", "source", ChannelCandidate("T", "https://example.invalid/t$it"), available = false) })
        assertNull(full.retainTombstones(setOf("t0", "t1"), limit = 4))
        assertEquals(listOf("t0"), full.retainTombstones(setOf("t0"), limit = 4)!!.retained.map { it.id })
    }
    @Test fun nameAloneNeverBinds() {
        val result = ChannelIdentityReconciler().reconcile("source", listOf(initial), listOf(ChannelCandidate("Old", "https://example.invalid/new")))
        assertNotEquals("saved", result.channels.single().channel.id)
    }
    @Test fun uniqueGuideAndExplicitVariantSurviveRotation() {
        val old = initial.copy(data = initial.data.copy(providerId = null))
        val result = ChannelIdentityReconciler().reconcile("source", listOf(old), listOf(old.data.copy(locator = "https://example.invalid/new")))
        assertEquals("saved", result.channels.single().channel.id)
        assertEquals(IdentityMatch.GUIDE_VARIANT, result.channels.single().match)
    }
    @Test fun ambiguousGuideIdsAbstainWithoutExactLocator() {
        val old = initial.copy(data = initial.data.copy(providerId = null))
        val next = listOf(old.data.copy(locator = "https://example.invalid/a"), old.data.copy(locator = "https://example.invalid/b"))
        val result = ChannelIdentityReconciler().reconcile("source", listOf(old), next)
        assertTrue(result.channels.none { it.channel.id == "saved" })
    }
    @Test(expected = IllegalArgumentException::class) fun crossSourceMixIsRejected() {
        ChannelIdentityReconciler().reconcile("other", listOf(initial), listOf(initial.data))
    }
    @Test(expected = IllegalArgumentException::class) fun conflictingDuplicateProviderIdBlocksRefresh() {
        ChannelIdentityReconciler().reconcile("source", listOf(initial), listOf(initial.data, initial.data.copy(name = "Different")))
    }
    @Test fun staleOrPartialRefreshNeverPublishes() {
        val ticket = RefreshTicket("source", 1, 1)
        assertEquals(RefreshDecision.STALE, decideCatalogueRefresh(ticket, ticket.copy(requestGeneration = 2), true, 10, 10))
        assertEquals(RefreshDecision.STALE, decideCatalogueRefresh(ticket, ticket.copy(configurationVersion = 2), true, 10, 10))
        assertEquals(RefreshDecision.INVALID, decideCatalogueRefresh(ticket, ticket, false, 10, 10, true))
        assertEquals(RefreshDecision.EMPTY_REQUIRES_REVIEW, decideCatalogueRefresh(ticket, ticket, true, 10, 0))
        assertEquals(RefreshDecision.SHRINK_REQUIRES_REVIEW, decideCatalogueRefresh(ticket, ticket, true, 10, 4))
        assertEquals(RefreshDecision.PUBLISH, decideCatalogueRefresh(ticket, ticket, true, 10, 4, true))
    }
}
