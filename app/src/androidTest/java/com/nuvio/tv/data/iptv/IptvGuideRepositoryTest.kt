package com.nuvio.tv.data.iptv

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.core.iptv.RefreshDecision
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import java.time.Instant
import java.util.UUID
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IptvGuideRepositoryTest {
    private val start = Instant.parse("2026-10-05T00:00:00Z").toEpochMilli()
    private val window = IptvGuideWindow(start, start + 86_400_000)
    private fun xml(title: String = "Fixture") = "<tv><channel id=\"one\"><display-name>One</display-name></channel><programme channel=\"one\" start=\"20261005000000 +0000\" stop=\"20261005010000 +0000\"><title>$title</title></programme></tv>"
    private fun response(request: Request, body: String = xml(), status: Int = 200) = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
        .message("Fixture").code(status).header("ETag", "v1").body(body.toResponseBody("application/xml".toMediaType()))

    @Test fun validResponsePersistsAnd304RequiresRetainedWindowCoverage() = fixture { store, ref ->
        val requests = mutableListOf<Request>(); var status = 200
        val http = IptvMetadataClient.newClient().newBuilder().addInterceptor { chain ->
            requests += chain.request(); response(chain.request(), status = status).build()
        }.build()
        val repository = IptvGuideRepository(store, IptvGuideClient(http))
        assertEquals(IptvGuideRefresh.Guide(RefreshDecision.PUBLISH), repository.refresh(ref, window))
        status = 304
        assertEquals(IptvGuideRefresh.Unchanged, repository.refresh(ref, window))
        assertEquals("v1", requests.last().header("If-None-Match"))
        val larger = IptvGuideWindow(start, window.untilMillis + 86_400_000)
        assertEquals(MetadataFailure.INVALID_RESPONSE, failure { repository.refresh(ref, larger) }.failure)
        assertNull(requests.last().header("If-None-Match"))
        assertEquals("Fixture", store.programmes(ref, "one", window).programmes.single().titles.single().text)
    }
    @Test fun endpointEditDuring200AndSupersedingRefreshDuring304StayStale() = fixture { store, ref ->
        var mode = 0
        val http = IptvMetadataClient.newClient().newBuilder().addInterceptor { chain ->
            if (mode == 1) store.beginRefresh(ref)
            if (mode == 2) store.editFeed(ref, "Edited", "https://fixture.invalid/new")
            response(chain.request(), status = if (mode == 1) 304 else 200).build()
        }.build()
        val repo = IptvGuideRepository(store, IptvGuideClient(http))
        repo.refresh(ref, window); mode = 1
        assertEquals(IptvGuideRefresh.Guide(RefreshDecision.STALE), repo.refresh(ref, window))
        mode = 2
        assertEquals(IptvGuideRefresh.Guide(RefreshDecision.STALE), repo.refresh(ref, window))
        assertTrue(store.channelPage(ref).isEmpty()); assertNull(store.validators(ref))
    }
    @Test fun truncatedGzipAndRejectedRowsCannotAdvanceTheWorkingEtag() = fixture { store, ref ->
        var mode = 0
        val http = IptvMetadataClient.newClient().newBuilder().addInterceptor { chain ->
            val compressed = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(xml().toByteArray()) } }.toByteArray()
            response(chain.request()).header("ETag", if (mode == 0) "v1" else "bad").apply {
                if (mode == 1) body(compressed.copyOf(compressed.size - 4).toResponseBody())
                if (mode == 2) body(xml().replace("20261005010000 +0000", "invalid").toResponseBody())
            }.build()
        }.build()
        val repo = IptvGuideRepository(store, IptvGuideClient(http))
        repo.refresh(ref, window); mode = 1
        failure { repo.refresh(ref, window) }
        mode = 2
        assertEquals(IptvGuideRefresh.Guide(RefreshDecision.INVALID), repo.refresh(ref, window))
        assertEquals("v1", store.validators(ref)!!.etag)
        assertEquals(1, store.programmes(ref, "one", window).programmes.size)
    }
    @Test fun cancellationWaitsForBodyAndStagingCleanupBeforeReturning() = fixture { store, ref ->
        store.importGuide(store.beginRefresh(ref), xml().byteInputStream(), window, IptvCacheValidators("old"))
        val entered = CompletableDeferred<Unit>(); var closed = false
        val http = IptvMetadataClient.newClient().newBuilder().addInterceptor { chain ->
            val body = object : ResponseBody() {
                override fun contentType() = "application/xml".toMediaType()
                override fun contentLength() = -1L
                private val input = object : Source {
                    private val pending = Buffer().writeUtf8("<tv><channel id=\"one\"><display-name>One</display-name></channel>" +
                        (1..110).joinToString("") { xml("Candidate $it").substringAfter("</channel>").substringBefore("</tv>") })
                    override fun timeout() = Timeout.NONE
                    override fun close() { closed = true }
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        if (pending.size > 0) return pending.read(sink, byteCount)
                        entered.complete(Unit)

                        while (!chain.call().isCanceled()) Thread.sleep(5)
                        throw java.io.IOException("cancelled fixture stream")
                    }
                }.buffer()
                override fun source(): BufferedSource = input
            }
            response(chain.request()).body(body).build()
        }.build()
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.currentCoroutineContext()).async(Dispatchers.IO) {
            IptvGuideRepository(store, IptvGuideClient(http)).refresh(ref, window)
        }
        withTimeout(5000) { entered.await() }; job.cancel(); withTimeout(5000) { job.join() }
        assertTrue(closed)
        assertEquals("old", store.validators(ref)!!.etag)
        assertEquals("Fixture", store.programmes(ref, "one", window).programmes.single().titles.single().text)
        assertEquals(RefreshDecision.PUBLISH, store.importGuide(store.beginRefresh(ref), xml().byteInputStream(), window))
    }
    @Test fun windowCoverageAndEndpointAreCapturedTogetherAndRequestTextIsRedacted() = fixture { store, ref ->
        store.importGuide(store.beginRefresh(ref), xml().byteInputStream(), window, IptvCacheValidators("secret-etag"))
        val request = store.prepareRefresh(ref, window)
        assertEquals("secret-etag", request.validators!!.etag)
        assertFalse(request.toString().contains("secret-etag")); assertFalse(request.toString().contains("fixture.invalid"))
        store.editFeed(ref, "Edited", "https://fixture.invalid/new")
        assertFalse(store.acceptNotModified(request.ticket, window))
        val next = store.prepareRefresh(ref, window)
        assertEquals("https://fixture.invalid/new", next.endpoint); assertNull(next.validators)
    }
    @Test fun versionOneMigrationRetainsRowsButForcesUnconditionalRefresh() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString(); val name = "guide-migration-$id.db"; val alias = "guide.migration.$id"
        try {

            context.openOrCreateDatabase(name, 0, null).use { db ->
                db.execSQL("CREATE TABLE feeds(id TEXT PRIMARY KEY, profile INTEGER NOT NULL, label TEXT NOT NULL, endpoint BLOB NOT NULL, version INTEGER NOT NULL, requested INTEGER NOT NULL, active_stage TEXT, active_generation INTEGER, active_version INTEGER, validators BLOB)")
                db.execSQL("CREATE TABLE stages(id TEXT PRIMARY KEY, feed TEXT NOT NULL REFERENCES feeds(id), generation INTEGER NOT NULL, UNIQUE(feed,generation))")
                db.execSQL("CREATE TABLE channels(stage TEXT NOT NULL REFERENCES stages(id) ON DELETE CASCADE, external_id TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(stage,external_id))")
                db.execSQL("CREATE TABLE programmes(stage TEXT NOT NULL REFERENCES stages(id) ON DELETE CASCADE, id TEXT NOT NULL, external_id TEXT NOT NULL, start INTEGER NOT NULL, stop INTEGER, precise INTEGER NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(stage,id))")
                db.execSQL("CREATE INDEX guide_window ON programmes(stage,external_id,start,stop)")
                val box = AndroidIptvSecretBox(alias)
                db.execSQL("INSERT INTO feeds VALUES(?,?,?,?,?,?,?,?,?,?)", arrayOf("legacy", 1, "Legacy", box.seal("iptv.guide.v1:1:legacy:endpoint", "https://fixture.invalid/old"), 1, 1, "active", 1, 1, box.seal("iptv.guide.v1:1:legacy:validators", "{\"etag\":\"v1\"}")))
                db.execSQL("INSERT INTO stages VALUES('active','legacy',1)")
                db.execSQL("INSERT INTO channels VALUES('active','one','{\"id\":\"one\",\"names\":[] }')")
                db.version = 1
            }
            IptvGuideStore(context, name, AndroidIptvSecretBox(alias)).use { store ->
                val ref = IptvGuideRef(1, "legacy")
                assertEquals("one", store.channelPage(ref).single().externalId)
                assertEquals("v1", store.validators(ref)!!.etag)
                assertNull(store.prepareRefresh(ref, window).validators)
                assertEquals(RefreshDecision.PUBLISH, store.importGuide(store.beginRefresh(ref), xml().byteInputStream(), window))
            }
        } finally { context.deleteDatabase(name); KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) } }
    }
    @Test fun capacityFailureReportsStorageFullAndPreservesActiveGuide() = fixture(128L * 1024) { store, ref ->
        var body = xml()
        val http = IptvMetadataClient.newClient().newBuilder().addInterceptor { chain -> response(chain.request(), body).build() }.build()
        val repo = IptvGuideRepository(store, IptvGuideClient(http))
        assertEquals(IptvGuideRefresh.Guide(RefreshDecision.PUBLISH), repo.refresh(ref, window))
        body = "<tv><channel id=\"one\"><display-name>One</display-name></channel>" +
            (1..150).joinToString("") { xml("$it " + "x".repeat(2000)).substringAfter("</channel>").substringBefore("</tv>") } + "</tv>"
        assertEquals(IptvGuideRefresh.StorageFull, repo.refresh(ref, window))
        assertEquals("Fixture", store.programmes(ref, "one", window).programmes.single().titles.single().text)
    }
    @Test fun localDocumentImportsClosesAndRefreshesWithoutHttpOrCacheValidators() = fixture { store, ref ->
        store.editFeed(ref, "Local", "content://fixture.documents/guide.xml")
        var closed = false; var text = xml("Local first")
        val http = IptvMetadataClient.newClient().newBuilder().addInterceptor { error("Local guide must not use HTTP") }.build()
        val repo = IptvGuideRepository(store, IptvGuideClient(http), openDocument = { address ->
            assertEquals("content://fixture.documents/guide.xml", address)
            object : java.io.ByteArrayInputStream(text.toByteArray()) { override fun close() { closed = true; super.close() } }
        })
        assertEquals(IptvGuideRefresh.Guide(RefreshDecision.PUBLISH), repo.refresh(ref, window))
        assertTrue(closed); assertNull(store.validators(ref)?.etag)
        text = xml("Local second"); repo.refresh(ref, window)
        assertEquals("Local second", store.programmes(ref,"one",window).programmes.single().titles.single().text)
        text = "<tv><secret"
        assertEquals(MetadataFailure.INVALID_RESPONSE, failure { repo.refresh(ref, window) }.failure)
        assertEquals("Local second", store.programmes(ref,"one",window).programmes.single().titles.single().text)
    }
    @Test fun missingDocumentPermissionKeepsLastGoodGuideAndRedactsProviderErrors() = fixture { store, ref ->
        store.editFeed(ref, "Local", "content://fixture.documents/guide.xml")
        store.importGuide(store.beginRefresh(ref), xml().byteInputStream(), window)
        val repo = IptvGuideRepository(store, openDocument = { throw SecurityException("PRIVATE_DOCUMENT_PATH") })
        val error = failure { repo.refresh(ref, window) }
        assertNull(error.cause); assertFalse(error.toString().contains("PRIVATE_DOCUMENT_PATH"))
        assertEquals("Fixture",store.programmes(ref,"one",window).programmes.single().titles.single().text)
    }

    private suspend fun failure(block: suspend () -> Any): MetadataException {
        try { block() } catch (error: MetadataException) { return error }
        throw AssertionError("Expected rejection")
    }
    private fun fixture(maxBytes: Long = 256L * 1024 * 1024, block: suspend (IptvGuideStore, IptvGuideRef) -> Unit) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString(); val name = "guide-http-$id.db"; val alias = "guide.http.$id"
        try {
            IptvGuideStore(context, name, AndroidIptvSecretBox(alias), maxBytes).use { store ->
                block(store, store.createFeed(1, "Fixture", "https://fixture.invalid/guide?token=SECRET"))
            }
        } finally { context.deleteDatabase(name); KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) } }
    }
}
