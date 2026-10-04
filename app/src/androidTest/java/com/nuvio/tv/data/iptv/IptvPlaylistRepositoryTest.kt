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

/** Production HTTP parser -> repository -> real SQLite, with in-process fixture responses only. */
@RunWith(AndroidJUnit4::class)
class IptvPlaylistRepositoryTest {
    private val valid = "#EXTM3U\n#EXTINF:-1 tvg-id=\"one\" group-title=\"News\",Fixture\nhttps://fixture.invalid/live?secret=not-real\n"

    @Test fun onlyValidatedImportsAdvanceCacheAndNotModifiedRetainsUserOverlay() = fixture { store, ref ->
        val requests = mutableListOf<Request>()
        var body = valid
        var status = 200
        var etag = "v1"
        val client = IptvMetadataClient.newClient().newBuilder().addInterceptor { chain ->
            requests += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("Fixture")
                .header("ETag", etag).body(body.toResponseBody("text/plain".toMediaType())).build()
        }.build()
        val repository = IptvPlaylistRepository(store, IptvMetadataClient(client))
        assertEquals(IptvPlaylistRefresh.Catalogue(RefreshDecision.PUBLISH), repository.refresh(ref))
        val id = store.snapshot(ref).channels.single().channel.id
        store.setOverlay(ref, id, IptvChannelOverlay(customName = "Mine", favouriteRank = 0))
        val before = store.snapshot(ref)
        body = valid + "#EXTINF:-1,Truncated\n"
        etag = "invalid"
        assertEquals(IptvPlaylistRefresh.Catalogue(RefreshDecision.INVALID), repository.refresh(ref))
        assertEquals(before.channels, store.snapshot(ref).channels)
        assertEquals(before.validators, store.snapshot(ref).validators)
        status = 304; body = ""
        assertEquals(IptvPlaylistRefresh.Unchanged, repository.refresh(ref))
        assertEquals("v1", requests.last().header("If-None-Match"))
        assertEquals(before.channels, store.snapshot(ref).channels)
        assertEquals(3, requests.size) // No logo, guide or media fetches.
    }

    @Test fun sourceEditDuringHttpResponsePreventsPromotionAndHlsNeverBecomesCatalogue() = fixture { store, ref ->
        var editDuringResponse = true
        var body = valid
        val client = IptvMetadataClient.newClient().newBuilder().addInterceptor { chain ->
            if (editDuringResponse) store.editSource(ref, "Edited", IptvSourceKind.M3U, "shared", IptvSourceConnection("https://fixture.invalid/new"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                .body(body.toResponseBody("text/plain".toMediaType())).build()
        }.build()
        val repository = IptvPlaylistRepository(store, IptvMetadataClient(client))
        assertEquals(IptvPlaylistRefresh.Catalogue(RefreshDecision.STALE), repository.refresh(ref))
        assertTrue(store.snapshot(ref).channels.isEmpty())
        editDuringResponse = false
        body = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nsegment.ts\n"
        assertEquals(IptvPlaylistRefresh.HlsPlaybackInput, repository.refresh(ref))
        assertTrue(store.snapshot(ref).channels.isEmpty())
        assertNull(store.snapshot(ref).validators)
    }

    private fun fixture(block: suspend (IptvCatalogueStore, IptvSourceRef) -> Unit) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        val name = "iptv-repository-$id.db"
        val alias = "nuvio.iptv.repository.test.$id"
        try {
            IptvCatalogueStore(context, name, AndroidIptvSecretBox(alias)).use { store ->
                val ref = store.createSource(1, "Fixture", IptvSourceKind.M3U, "shared", IptvSourceConnection("https://fixture.invalid/list")).ref
                block(store, ref)
            }
        } finally {
            context.deleteDatabase(name)
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        }
    }
}
