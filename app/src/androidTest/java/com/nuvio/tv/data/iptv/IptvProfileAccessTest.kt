package com.nuvio.tv.data.iptv

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyStore
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IptvProfileAccessTest {
    @Test fun deletingOneProfileRemovesItsCredentialsAndRevokesLateWrites() = fixture { catalogue, guides, access ->
        val old = access.open(2)
        access.use(old) {
            val source = catalogue.createSource(2, "Old", IptvSourceKind.M3U, "shared", IptvSourceConnection("https://fixture.invalid/old"))
            val feed = guides.createFeed(2, "Old", "https://fixture.invalid/guide")
            catalogue.commitCatalogue(source.ref, catalogue.beginRefresh(source.ref), listOf(IptvCatalogueRecord(com.nuvio.tv.core.iptv.ChannelCandidate("One", "https://fixture.invalid/live"))), true)
            val channel = catalogue.page(source.ref).items.single().channel.id
            catalogue.setOverlay(source.ref, channel, IptvChannelOverlay(favouriteRank = 0, manualGuide = com.nuvio.tv.core.iptv.GuideKey(feed.feedId, "one")))
            catalogue.setGuideFeeds(source.ref, listOf(feed))
            val start = java.time.Instant.parse("2026-10-05T00:00:00Z").toEpochMilli()
            val xml = "<tv><channel id=\"one\"><display-name>One</display-name></channel><programme channel=\"one\" start=\"20261005000000 +0000\" stop=\"20261005010000 +0000\"><title>Fixture</title></programme></tv>"
            guides.importGuide(guides.beginRefresh(feed), xml.byteInputStream(), IptvGuideWindow(start, start + 86400000))
        }
        val other = catalogue.createSource(1, "Keep", IptvSourceKind.M3U, "shared", IptvSourceConnection("https://fixture.invalid/keep"))
        access.removeProfile(2)
        assertTrue(catalogue.sources(2).isEmpty()); assertTrue(guides.feeds(2).isEmpty())
        assertEquals(other, catalogue.sources(1).single())
        assertThrows(IllegalStateException::class.java) { access.use(old) { catalogue.createSource(2, "Late", IptvSourceKind.M3U, "shared", IptvSourceConnection("https://fixture.invalid/late")) } }
        access.use(access.open(2)) { catalogue.createSource(2, "New profile", IptvSourceKind.M3U, "shared", IptvSourceConnection("https://fixture.invalid/new")) }
        assertEquals("New profile", catalogue.sources(2).single().label)
    }
    @Test fun globalCleanupRevokesEveryExistingSession() = fixture { catalogue, guides, access ->
        val first = access.open(1); val second = access.open(2)
        catalogue.createSource(1, "One", IptvSourceKind.M3U, "shared", IptvSourceConnection("https://fixture.invalid/one"))
        guides.createFeed(2, "Two", "https://fixture.invalid/two")
        access.clearAllProfiles()
        assertThrows(IllegalStateException::class.java) { access.use(first) {} }
        assertThrows(IllegalStateException::class.java) { access.use(second) {} }
        assertTrue(catalogue.sources(1).isEmpty()); assertTrue(guides.feeds(2).isEmpty())
    }
    private fun fixture(block: (IptvCatalogueStore, IptvGuideStore, IptvProfileAccess) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString(); val name = "profile-$id.db"; val guide = "profile-guide-$id.db"; val alias = "profile.$id"
        try {
            IptvCatalogueStore(context, name, AndroidIptvSecretBox(alias)).use { catalogue ->
                IptvGuideStore(context, guide, AndroidIptvSecretBox(alias)).use { guides -> block(catalogue, guides, IptvProfileAccess(catalogue, guides)) }
            }
        } finally {
            context.deleteDatabase(name); context.deleteDatabase(guide)
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        }
    }
}
