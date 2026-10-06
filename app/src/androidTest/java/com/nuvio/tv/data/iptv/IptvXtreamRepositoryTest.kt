package com.nuvio.tv.data.iptv

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.core.iptv.RefreshDecision
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class IptvXtreamRepositoryTest {
    private val connection = IptvSourceConnection("https://fixture.invalid", "fixture", "not-real")
    private val auth = """{"user_info": {"auth": 1, "status": "Active", "max_connections": "8", "allowed_output_formats": ["ts"]}}"""
    private val groups = """[{"category_id":"5","category_name":"News"}]"""
    private val rows = """[{"stream_id":42,"name":"One","category_id":"5","epg_channel_id":"guide.one"}]"""
    private fun client(requests: MutableList<Request>, reply: (String?) -> String): IptvXtreamClient {
        val http = IptvMetadataClient.newClient().newBuilder().addInterceptor { chain ->
            val request = chain.request(); requests += request
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                .body(reply(request.url.queryParameter("action")).toResponseBody("application/json".toMediaType())).build()
        }.build()
        return IptvXtreamClient(http)
    }

    @Test fun importAndCredentialRotationKeepStableIdentityAndFavourite() = fixture { store, ref ->
        val requests = mutableListOf<Request>()
        val repository = IptvPlaylistRepository(store, xtream = client(requests) { action -> when (action) {
            null -> auth; "get_live_categories" -> groups; else -> rows
        } })
        assertEquals(IptvPlaylistRefresh.Catalogue(RefreshDecision.PUBLISH), repository.refresh(ref))
        val item = store.snapshot(ref).channels.single()
        assertEquals("News", item.attributes["group-title"])
        assertEquals("42", item.channel.data.providerId)
        store.setOverlay(ref, item.channel.id, IptvChannelOverlay(customName = "My channel", favouriteRank = 1))
        store.editSource(ref, "Fixture", IptvSourceKind.XTREAM, "shared", connection.copy(password = "rotated"))
        assertNull(store.playbackItem(ref, item.channel.id))
        assertEquals(IptvPlaylistRefresh.Catalogue(RefreshDecision.PUBLISH), repository.refresh(ref))
        val after = store.playbackItem(ref, item.channel.id)!!
        assertEquals("My channel", after.overlay.customName)
        assertEquals(1, after.overlay.favouriteRank)
        assertTrue(after.channel.data.locator.endsWith("/fixture/rotated/42.ts"))
        assertEquals(6, requests.size)
        assertTrue(requests.all { it.url.encodedPath == "/player_api.php" })
    }

    @Test fun badAuthenticationAndPartialCatalogueKeepLastGoodRowsAndOverlay() = fixture { store, ref ->
        var currentAuth = auth
        var currentRows = rows
        val requests = mutableListOf<Request>()
        val repository = IptvPlaylistRepository(store, xtream = client(requests) { action -> when (action) {
            null -> currentAuth; "get_live_categories" -> groups; else -> currentRows
        } })
        repository.refresh(ref)
        val id = store.snapshot(ref).channels.single().channel.id
        store.setOverlay(ref, id, IptvChannelOverlay(favouriteRank = 0))
        val before = store.snapshot(ref)
        currentAuth = """{"user_info":{"auth":0,"status":"Active"}}"""
        try { repository.refresh(ref); fail("Rejected auth must fail") } catch (error: MetadataException) { assertEquals(MetadataFailure.AUTHENTICATION, error.failure) }
        assertEquals(4, requests.size)
        currentAuth = auth; currentRows = rows.dropLast(1) + ",{\"stream_id\":true,\"name\":\"Bad\"}]"
        assertEquals(IptvPlaylistRefresh.Catalogue(RefreshDecision.INVALID), repository.refresh(ref))
        assertEquals(before.channels, store.snapshot(ref).channels)
        assertEquals(before.source.activeGeneration, store.snapshot(ref).source.activeGeneration)
    }

    @Test fun editDuringRefreshCannotPublishOldCredentials() = fixture { store, ref ->
        val repository = IptvPlaylistRepository(store, xtream = client(mutableListOf()) { action -> when (action) {
            null -> auth; "get_live_categories" -> groups
            else -> {
                store.editSource(ref, "Edited", IptvSourceKind.XTREAM, "shared", connection.copy(password = "changed")); rows
            }
        } })
        assertEquals(IptvPlaylistRefresh.Catalogue(RefreshDecision.STALE), repository.refresh(ref))
        assertTrue(store.snapshot(ref).channels.isEmpty())
    }

    @Test fun duplicateKeysAndExcessiveDepthAreRejectedOnAndroidJson() = fixture { store, ref ->
        for (body in listOf("""{"user_info":{"auth":1,"auth":0,"status":"Active"}}""", "[".repeat(18) + "]".repeat(18))) {
            val requests = mutableListOf<Request>()
            val repository = IptvPlaylistRepository(store, xtream = client(requests) { body })
            try { repository.refresh(ref); fail("Malformed account must fail") } catch (error: MetadataException) { assertEquals(MetadataFailure.INVALID_RESPONSE, error.failure) }
            assertEquals(1, requests.size)
            assertTrue(store.snapshot(ref).channels.isEmpty())
        }
    }

    @Test fun independentSourcesKeepIdentitiesAndInvalidEditsKeepConnection() = fixture { store, ref ->
        val repository = IptvPlaylistRepository(store, xtream = client(mutableListOf()) { action -> when (action) {
            null -> auth; "get_live_categories" -> groups; else -> rows
        } })
        val other = store.createSource(1, "Second", IptvSourceKind.XTREAM, "shared", connection).ref
        repository.refresh(ref); repository.refresh(other)
        assertNotEquals(store.snapshot(ref).channels.single().channel.id, store.snapshot(other).channels.single().channel.id)
        try { store.editSource(ref, "Bad", IptvSourceKind.XTREAM, "shared", connection.copy(password = "")); fail("Invalid connection") }
        catch (_: MetadataException) { }
        assertEquals(connection, store.connection(ref))
        assertTrue(store.snapshot(ref).source.playbackEligible)
    }

    @Test fun removingAnXtreamSourceAlsoRemovesItsAutomaticGuideOnly() = fixture { store, ref ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "iptv-xtream-guide-${UUID.randomUUID()}.db"
        try {
            IptvGuideStore(context, name, EnvelopeIptvSecretBox(AndroidIptvSecretBox("nuvio.iptv.xtream.guide.$name"))).use { guides ->
                val links = IptvXtreamGuides(store, guides)
                val other = store.createSource(1, "Second", IptvSourceKind.XTREAM, "shared", connection).ref
                val automatic = links.ensure(ref); val kept = links.ensure(other)
                val shared = guides.createFeed(1, "Shared", "https://fixture.invalid/guide.xml")
                store.setGuideFeeds(other, listOf(kept, automatic, shared), listOf(automatic))
                links.removeSource(ref)
                assertEquals(listOf(other), store.sources(1).map { it.ref })
                assertEquals(setOf(kept, shared), guides.feeds(1).map { it.ref }.toSet())
                assertEquals(IptvGuideAssociations(listOf(kept.feedId, shared.feedId)), store.guideAssociations(other))
                links.removeFeed(shared)
                assertEquals(listOf(kept), guides.feeds(1).map { it.ref })
                assertEquals(listOf(kept.feedId), store.guideAssociations(other).feedIds)
            }
        } finally {
            context.deleteDatabase(name)
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("nuvio.iptv.xtream.guide.$name") }
        }
    }

    private fun fixture(block: suspend (IptvCatalogueStore, IptvSourceRef) -> Unit) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        val name = "iptv-xtream-$id.db"
        val alias = "nuvio.iptv.xtream.test.$id"
        try {
            IptvCatalogueStore(context, name, AndroidIptvSecretBox(alias)).use { store ->
                val ref = store.createSource(1, "Fixture", IptvSourceKind.XTREAM, "shared", connection).ref
                block(store, ref)
            }
        } finally {
            context.deleteDatabase(name)
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        }
    }
}
