package com.nuvio.tv.data.iptv

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.core.iptv.GuideKey
import com.nuvio.tv.core.iptv.RefreshDecision
import com.nuvio.tv.core.iptv.SetupBundles
import com.nuvio.tv.core.iptv.SetupImportMode
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IptvSetupStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun playlist(count: Int) = "#EXTM3U\n" + (1..count).joinToString("") { "#EXTINF:-1 tvg-id=\"c$it\" group-title=\"News\",Channel $it\nhttps://fixture.invalid/live/$it\n" }

    private class Fixture(val store: IptvCatalogueStore, val guides: IptvGuideStore, val ref: IptvSourceRef, var body: String) {
        val repository = IptvPlaylistRepository(store, IptvMetadataClient(IptvMetadataClient.newClient().newBuilder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                .body(body.toResponseBody("text/plain".toMediaType())).build()
        }.build()), xtreamGuides = IptvXtreamGuides(store, guides))
    }

    private fun fixture(body: String, block: suspend (Fixture) -> Unit) = runBlocking {
        val id = UUID.randomUUID().toString()
        val name = "iptv-setup-$id.db"
        val guideName = "iptv-setup-guide-$id.db"
        val alias = "nuvio.iptv.setup.test.$id"
        try {
            IptvCatalogueStore(context, name, AndroidIptvSecretBox(alias)).use { store ->
                IptvGuideStore(context, guideName, EnvelopeIptvSecretBox(AndroidIptvSecretBox(alias))).use { guides ->
                    try {
                        val ref = store.createSource(1, "Fixture", IptvSourceKind.M3U, "src-a", IptvSourceConnection("https://fixture.invalid/list")).ref
                        block(Fixture(store, guides, ref, body))
                    } finally { store.pending.clear() }
                }
            }
        } finally {
            context.deleteDatabase(name)
            context.deleteDatabase(guideName)
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        }
    }

    @Test fun aLargeDropIsHeldUntilAcceptedAndKeepsTheOldListMeanwhile() = fixture(playlist(10)) { f ->
        assertEquals(IptvPlaylistRefresh.Catalogue(RefreshDecision.PUBLISH), f.repository.refresh(f.ref))
        f.body = playlist(3)
        assertEquals(RefreshDecision.SHRINK_REQUIRES_REVIEW, (f.repository.refresh(f.ref) as IptvPlaylistRefresh.Catalogue).decision)
        assertEquals(10, f.store.channelCounts(1)[f.ref.sourceId])
        val held = requireNotNull(f.repository.held(f.ref))
        assertEquals(10, held.previous); assertEquals(3, held.candidate)
        f.body = playlist(2)
        f.repository.refresh(f.ref)
        assertEquals(2, f.repository.held(f.ref)?.candidate)
        assertEquals(RefreshDecision.PUBLISH, (f.repository.acceptHeld(f.ref) as IptvPlaylistRefresh.Catalogue).decision)
        assertEquals(2, f.store.channelCounts(1)[f.ref.sourceId])
        assertNull(f.repository.held(f.ref))
        assertEquals(RefreshDecision.STALE, (f.repository.acceptHeld(f.ref) as IptvPlaylistRefresh.Catalogue).decision)
    }

    @Test fun keepingOrEditingDropsTheHeldListAndAFullRefreshClearsIt() = fixture(playlist(10)) { f ->
        f.repository.refresh(f.ref)
        f.body = playlist(1)
        assertEquals(RefreshDecision.SHRINK_REQUIRES_REVIEW, (f.repository.refresh(f.ref) as IptvPlaylistRefresh.Catalogue).decision)
        assertEquals(1, f.repository.held(f.ref)?.candidate)
        f.repository.keepCurrent(f.ref)
        assertNull(f.repository.held(f.ref))
        f.body = playlist(1)
        f.repository.refresh(f.ref)
        assertNotNull(f.repository.held(f.ref))
        f.body = playlist(10)
        assertEquals(RefreshDecision.PUBLISH, (f.repository.refresh(f.ref) as IptvPlaylistRefresh.Catalogue).decision)
        assertNull(f.repository.held(f.ref))
        f.body = playlist(1)
        f.repository.refresh(f.ref)
        assertNotNull(f.repository.held(f.ref))
        f.store.editSource(f.ref, "Fixture", IptvSourceKind.M3U, "src-a", IptvSourceConnection("https://fixture.invalid/other"))
        assertNull(f.repository.held(f.ref))
        assertEquals(RefreshDecision.STALE, (f.repository.acceptHeld(f.ref) as IptvPlaylistRefresh.Catalogue).decision)
    }

    @Test fun accountsAreRemovedOnlyWhenNoSourceUsesThem() = fixture(playlist(1)) { f ->
        f.store.saveAccount(1, "grp-1", "Family", 3)
        f.store.saveAccount(1, "src-a", "Fixture", 1)
        assertFalse(f.store.removeAccount(1, "src-a"))
        assertTrue(f.store.removeAccount(1, "grp-1"))
        assertFalse(f.store.removeAccount(1, "grp-1"))
        assertEquals(listOf("src-a"), f.store.accounts(1).map { it.id })
    }

    @Test fun exportedSetupImportsIntoAnotherProfileAndAppliesChannelChangesAfterTheFirstLoad() = fixture(playlist(3)) { f ->
        val preferences = IptvLivePreferences(context)
        val bundles = IptvSetupBundles(f.store, f.guides, preferences)
        f.repository.refresh(f.ref)
        val guide = f.guides.createFeed(1, "Guide", "https://guides.invalid/guide.xml")
        f.store.setGuideFeeds(f.ref, listOf(guide), listOf(guide))
        val channels = f.store.snapshot(f.ref).channels.sortedBy { it.channel.data.name }
        f.store.setOverlay(f.ref, channels[0].channel.id, IptvChannelOverlay(customName = "Mine", favouriteRank = 0))
        f.store.setOverlay(f.ref, channels[1].channel.id, IptvChannelOverlay(hidden = true, manualGuide = GuideKey(guide.feedId, "bbc1")))
        val bundle = SetupBundles.decode(SetupBundles.encode(bundles.export(1, logins = true)))
        assertEquals(1, bundle.sources.size); assertEquals(1, bundle.guides.size); assertEquals(2, bundle.overlays.size)
        try {
            val imported = bundles.import(2, bundle.copy(settings = null), SetupImportMode.REPLACE)
            assertEquals(1, imported.created.size)
            val copy = imported.created.single()
            assertEquals("Fixture", f.store.sources(2).single().label)
            assertEquals(1, f.guides.feeds(2).size)
            assertNotNull(f.store.pending.imported(copy))
            assertEquals(listOf(imported.feeds.single().feedId), f.store.guideAssociations(copy).feedIds)
            assertEquals(IptvPlaylistRefresh.Catalogue(RefreshDecision.PUBLISH), f.repository.refresh(copy))
            assertNull(f.store.pending.imported(copy))
            val restored = f.store.snapshot(copy).channels.associateBy { it.channel.data.name }
            assertEquals("Mine", restored.getValue("Channel 1").overlay.customName)
            assertEquals(0, restored.getValue("Channel 1").overlay.favouriteRank)
            assertTrue(restored.getValue("Channel 2").overlay.hidden)
            assertEquals(GuideKey(imported.feeds.single().feedId, "bbc1"), restored.getValue("Channel 2").overlay.manualGuide)
            val again = bundles.import(2, bundle.copy(settings = null), SetupImportMode.MERGE)
            assertTrue(again.created.isEmpty()); assertEquals(1, again.reused)
            assertEquals(1, f.store.sources(2).size); assertEquals(1, f.guides.feeds(2).size)
        } finally {
            f.store.sources(2).forEach { preferences.removeSource(it.ref) }
            IptvProfileAccess(f.store, f.guides).removeProfile(2)
        }
    }

    @Test fun backupsWithoutLoginsNeverHoldTheLogin() = fixture(playlist(1)) { f ->
        val xtream = f.store.createSource(1, "Account", IptvSourceKind.XTREAM, "src-b", IptvSourceConnection("http://x.invalid:8080", "me", "secret-pass")).ref
        val text = SetupBundles.encode(IptvSetupBundles(f.store, f.guides, IptvLivePreferences(context)).export(1, logins = false))
        assertFalse(text.contains("secret-pass")); assertFalse(text.contains("\"me\""))
        assertEquals(1, SetupBundles.decode(text).summary().needLogin)
        f.store.removeSource(xtream)
    }
}
