package com.nuvio.tv.data.iptv

import com.nuvio.tv.core.iptv.PlaylistKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

class IptvMetadataClientTest {
    private val catalogue = "#EXTM3U\n#EXTINF:-1 tvg-id=\"one\" tvg-logo=\"http://unused.invalid/logo\",One\nmedia/One.ts?token=Exact\n"

    @Test fun followsSameOriginAndResolvesAgainstFinalResponseWithoutOpeningMedia() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/final/list.m3u"))
            server.enqueue(MockResponse().setBody(catalogue).addHeader("ETag", "\"v1\""))
            val result = IptvMetadataClient().playlist(server.url("/first").toString()) as PlaylistDownload.Candidate
            assertTrue(result.catalogue.canPublish)
            assertEquals(server.url("/final/media/One.ts?token=Exact").toString(), result.catalogue.channels.single().locator)
            assertEquals("\"v1\"", result.validators.etag)
            assertEquals(2, server.requestCount)
            assertFalse(result.toString().contains("token"))
        }
    }

    @Test fun crossOriginRedirectDoesNotContactOtherServer() = runBlocking {
        MockWebServer().use { first -> MockWebServer().use { other ->
            first.enqueue(MockResponse().setResponseCode(302).addHeader("Location", other.url("/secret")))
            assertEquals(MetadataFailure.REDIRECT_REQUIRES_REVIEW, failure { IptvMetadataClient().playlist(first.url("/").toString()) }.failure)
            assertEquals(0, other.requestCount)
        } }
    }

    @Test fun notModifiedRequiresAConditionalRequest() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(304))
            server.enqueue(MockResponse().setResponseCode(304))
            val client = IptvMetadataClient()
            assertEquals(MetadataFailure.INVALID_RESPONSE, failure { client.playlist(server.url("/").toString()) }.failure)
            assertEquals(PlaylistDownload.NotModified, client.playlist(server.url("/").toString(), CatalogueValidators("\"v1\"")))
            server.takeRequest()
            assertEquals("\"v1\"", server.takeRequest().getHeader("If-None-Match"))
        }
    }

    @Test fun partialHttpResponseCannotBecomeCatalogue() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206).setBody(catalogue))
            val error = failure { IptvMetadataClient().playlist(server.url("/?password=hidden").toString()) }
            assertEquals(206, error.status)
            assertFalse(error.toString().contains("hidden"))
            assertNull(error.cause)
        }
    }

    @Test fun byteBudgetAlsoBoundsChunkedBodies() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setChunkedBody(catalogue.repeat(5), 7))
            assertEquals(MetadataFailure.BODY_LIMIT, failure { IptvMetadataClient(maxExpandedBytes = 30).playlist(server.url("/").toString()) }.failure)
        }
    }

    @Test fun byteBudgetAppliesAfterTransparentGzipExpansion() = runBlocking {
        val bytes = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(catalogue.repeat(5).toByteArray()) } }.toByteArray()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(Buffer().write(bytes)).addHeader("Content-Encoding", "gzip"))
            assertEquals(MetadataFailure.BODY_LIMIT, failure { IptvMetadataClient(maxExpandedBytes = 150).playlist(server.url("/").toString()) }.failure)
        }
    }

    @Test fun hlsRemainsPlaybackInputAndNeverPublishesAsChannelRows() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nsegment.ts\n"))
            val candidate = IptvMetadataClient().playlist(server.url("/live.m3u8").toString()) as PlaylistDownload.Candidate
            assertEquals(PlaylistKind.HLS, candidate.catalogue.kind)
            assertFalse(candidate.catalogue.canPublish)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun cancellationCancelsTheSocketCall() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val http = IptvMetadataClient.newClient()
            val job = async(Dispatchers.IO) { IptvMetadataClient(http).playlist(server.url("/").toString()) }
            assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
            val active = http.dispatcher.runningCalls().single()
            job.cancel()
            withTimeout(3_000) { job.join() }
            assertTrue(active.isCanceled())
        }
    }

    @Test fun redirectLoopHasABoundedRequestCount() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/"))
            assertEquals(MetadataFailure.REDIRECT_LIMIT, failure { IptvMetadataClient().playlist(server.url("/").toString()) }.failure)
            assertEquals(1, server.requestCount)
        }
    }

    private suspend fun failure(block: suspend () -> Any): MetadataException {
        try { block() } catch (error: MetadataException) { return error }
        throw AssertionError("Expected metadata rejection")
    }
}
