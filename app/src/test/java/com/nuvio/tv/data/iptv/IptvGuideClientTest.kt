package com.nuvio.tv.data.iptv

import com.nuvio.tv.core.iptv.GuideParseLimits
import com.nuvio.tv.core.iptv.parseGuideInput
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class IptvGuideClientTest {
    private val xml = "<tv><channel id=\"one\"><display-name>One</display-name></channel></tv>"
    private suspend fun read(client: IptvGuideClient, url: String, validators: CatalogueValidators? = null) =
        client.fetch(url, validators) { input, _, check -> parseGuideInput(input, {}, {}, checkCancellation = check).channels }
    private fun gzip(text: String): ByteArray = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(text.toByteArray()) } }.toByteArray()

    @Test fun nonGuideBodyIsLoggedWithItsFormatButNotItsAddress() = runBlocking {
        val records = mutableListOf<String>()
        val handler = object : java.util.logging.Handler() {
            override fun publish(record: java.util.logging.LogRecord) { records += record.message }
            override fun flush() = Unit
            override fun close() = Unit
        }
        val logger = java.util.logging.Logger.getLogger("NuvioIptv")
        logger.addHandler(handler)
        try {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody("<html><body>Bad credentials</body></html>"))
                server.enqueue(MockResponse().setBody(Buffer().write(gzip("\uFEFF\n<?xml version=\"1.0\"?>\n<!DOCTYPE tv SYSTEM \"xmltv.dtd\">$xml"))))
                val address = server.url("/xmltv.php?username=u&password=SECRET_GUIDE_PASSWORD").toString()
                assertEquals(MetadataFailure.INVALID_RESPONSE, (runCatching { read(IptvGuideClient(), address) }.exceptionOrNull() as MetadataException).failure)
                assertEquals(GuideDownload.Imported(1), read(IptvGuideClient(), address))
            }
        } finally { logger.removeHandler(handler) }
        assertTrue(records.any { it.startsWith("guide GuideFormatException HTML") })
        assertTrue(records.none { "SECRET_GUIDE_PASSWORD" in it })
    }

    @Test fun sixDistinctRedirectsAreFollowedAndASeventhIsRefused() = runBlocking {
        MockWebServer().use { server ->
            for (count in listOf(6, 7)) {
                repeat(count) { server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/hop${count}_$it")) }
                if (count == 6) server.enqueue(MockResponse().setBody(xml))
                val result = runCatching { read(IptvGuideClient(), server.url("/start$count").toString()) }
                if (count == 6) assertEquals(GuideDownload.Imported(1), result.getOrThrow())
                else assertEquals(MetadataFailure.REDIRECT_LIMIT, (result.exceptionOrNull() as MetadataException).failure)
            }
        }
    }
    @Test fun sameOriginRedirectUsesFinalBodyAndClosesIt() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/guide"))
            server.enqueue(MockResponse().setBody(xml))
            assertEquals(GuideDownload.Imported(1), read(IptvGuideClient(), server.url("/first").toString()))
            assertEquals(2, server.requestCount)
            assertEquals("identity", server.takeRequest().getHeader("Accept-Encoding"))
        }
    }
    @Test fun redirectWithAnEmptyFragmentIsFollowed() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/cd/0/get/guide.xml.gz?dl=1#"))
            server.enqueue(MockResponse().setBody(xml))
            assertEquals(GuideDownload.Imported(1), read(IptvGuideClient(), server.url("/s/guide.xml.gz?rlkey=a&dl=1").toString()))
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun crossOriginRedirectCannotForwardCredentialsOrContactTarget() = runBlocking {
        MockWebServer().use { first -> MockWebServer().use { other ->
            first.enqueue(MockResponse().setResponseCode(302).addHeader("Location", other.url("/secret")))
            val error = failure { read(IptvGuideClient(), first.url("/?token=HIDDEN").toString()) }
            assertEquals(MetadataFailure.REDIRECT_REQUIRES_REVIEW, error.failure)
            assertEquals(0, other.requestCount)
            assertFalse(error.toString().contains("HIDDEN")); assertNull(error.cause)
        } }
    }
    @Test fun notModifiedIsAcceptedOnlyForConditionalRequests() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(304)); server.enqueue(MockResponse().setResponseCode(304))
            assertEquals(MetadataFailure.INVALID_RESPONSE, failure { read(IptvGuideClient(), server.url("/").toString()) }.failure)
            assertEquals(GuideDownload.NotModified, read(IptvGuideClient(), server.url("/").toString(), CatalogueValidators("v1")))
            server.takeRequest(); assertEquals("v1", server.takeRequest().getHeader("If-None-Match"))
        }
    }
    @Test fun gzipFilesAndHttpEncodedGzipUseTheSameBoundedDecoder() = runBlocking {
        MockWebServer().use { server ->
            for (encoding in listOf(false, true)) {
                val response = MockResponse().setBody(Buffer().write(gzip(xml)))
                if (encoding) response.addHeader("Content-Encoding", "gzip")
                server.enqueue(response)
                assertEquals(GuideDownload.Imported(1), read(IptvGuideClient(), server.url("/no-extension").toString()))
            }
        }
    }
    @Test fun chunkedWireBytesCannotBypassTransferBudget() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setChunkedBody(xml, 7))
            assertEquals(MetadataFailure.BODY_LIMIT, failure { read(IptvGuideClient(maxTransferBytes = 30), server.url("/").toString()) }.failure)
        }
    }
    @Test fun expansionAndMalformedXmlFailuresAreRedacted() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(Buffer().write(gzip(xml.replace("One", "x".repeat(8000))))).addHeader("Content-Encoding", "gzip"))
            val error = failure { IptvGuideClient().fetch(server.url("/?password=HIDDEN").toString()) { input, _, check ->
                parseGuideInput(input, {}, {}, GuideParseLimits(expandedBytes = 256), checkCancellation = check)
            } }
            assertEquals(MetadataFailure.INVALID_RESPONSE, error.failure); assertNull(error.cause)
            server.enqueue(MockResponse().setBody("<tv><HIDDEN"))
            assertFalse(failure { read(IptvGuideClient(), server.url("/").toString()) }.toString().contains("HIDDEN"))
        }
    }
    @Test fun partialHttpAndUnsupportedEncodingNeverReachConsumer() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206).setBody(xml))
            server.enqueue(MockResponse().setBody(xml).addHeader("Content-Encoding", "br"))
            assertEquals(MetadataFailure.HTTP_STATUS, failure { read(IptvGuideClient(), server.url("/").toString()) }.failure)
            assertEquals(MetadataFailure.INVALID_RESPONSE, failure { read(IptvGuideClient(), server.url("/").toString()) }.failure)
        }
    }
    @Test fun cancellationClosesTheSocketAndFinishesBeforeJoin() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val http = IptvMetadataClient.newClient()
            val job = async(Dispatchers.IO) { read(IptvGuideClient(http), server.url("/").toString()) }
            assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
            val call = http.dispatcher.runningCalls().single()
            job.cancel(); withTimeout(3000) { job.join() }
            assertTrue(call.isCanceled()); assertEquals(0, http.dispatcher.runningCallsCount())
        }
    }
    @Test fun loopStopsWithoutUnboundedRequests() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/"))
            assertEquals(MetadataFailure.REDIRECT_LIMIT, failure { read(IptvGuideClient(), server.url("/").toString()) }.failure)
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun networkFailureHasNoCredentialBearingCauseAndDoesNotRetry() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            val error = failure { read(IptvGuideClient(), server.url("/?token=HIDDEN").toString()) }
            assertEquals(MetadataFailure.NETWORK, error.failure)
            assertNull(error.cause); assertFalse(error.toString().contains("HIDDEN"))
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun simultaneousRefreshesAcrossClientsHaveOnlyTwoActiveRequests() = runBlocking {
        val entered = CountDownLatch(2); val release = CountDownLatch(1)
        val active = AtomicInteger(); val peak = AtomicInteger(); val requests = AtomicInteger()
        val http = IptvMetadataClient.newClient().newBuilder().addInterceptor { chain ->
            requests.incrementAndGet()
            val count = active.incrementAndGet(); peak.updateAndGet { maxOf(it, count) }; entered.countDown()
            try {
                check(release.await(5, TimeUnit.SECONDS))
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture").body(xml.toResponseBody()).build()
            } finally { active.decrementAndGet() }
        }.build()
        val jobs = (1..3).map { async(Dispatchers.IO) { read(IptvGuideClient(http), "https://fixture.invalid/$it") } }
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS)); delay(100)
            assertEquals(2, requests.get())
        } finally { release.countDown() }
        withTimeout(5000) { jobs.awaitAll() }
        assertEquals(3, requests.get()); assertEquals(2, peak.get())
    }
    @Test fun successiveHttp10GuidesDoNotReuseClosedConnections() = runBlocking {
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(MockResponse().setStatus("HTTP/1.0 200 OK").setBody(xml).setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)) }
            val client = IptvGuideClient()
            repeat(2) { assertEquals(GuideDownload.Imported(1), read(client, server.url("/").toString())) }
            assertEquals(2, server.requestCount)
            repeat(2) { assertEquals("close", server.takeRequest().getHeader("Connection")) }
        }
    }

    @Test fun httpsShortLinkFollowsOnlyLocationWithoutForwardingValidatorsOrStoringDestinationEtag() = runBlocking {
        val requests = mutableListOf<okhttp3.Request>()
        val http = IptvMetadataClient.newClient().newBuilder().addInterceptor { chain ->
            requests += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).message("Fixture")
                .code(if (requests.size == 1) 302 else 200)
                .header("Location", "https://guide.invalid/feed.xml")
                .header("ETag", "destination-private-tag").body(xml.toResponseBody()).build()
        }.build()
        val result = IptvGuideClient(http).fetch("https://short.invalid/code?token=PRIVATE", CatalogueValidators("old")) { input, cache, _ ->
            assertNull(cache.etag); assertNull(cache.lastModified); input.readBytes().size
        }
        assertTrue(result is GuideDownload.Imported)
        assertEquals(2, requests.size)
        assertEquals("https://guide.invalid/feed.xml", requests.last().url.toString())
        for (header in listOf("If-None-Match", "If-Modified-Since", "Authorization", "Cookie", "Referer")) assertNull(requests.last().header(header))
    }
    @Test fun shortLinkCannotDowngradeOrAcceptAnUnsolicited304() = runBlocking {
        for (target in listOf("http://guide.invalid/feed", "https://guide.invalid/feed")) {
            var requests = 0
            val http = IptvMetadataClient.newClient().newBuilder().addInterceptor { chain ->
                requests++
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).message("Fixture")
                    .code(if (requests == 1) 302 else 304).header("Location", target).body("".toResponseBody()).build()
            }.build()
            val error = failure { read(IptvGuideClient(http), "https://short.invalid/code", CatalogueValidators("old")) }
            assertEquals(if (target.startsWith("http:")) MetadataFailure.REDIRECT_REQUIRES_REVIEW else MetadataFailure.INVALID_RESPONSE, error.failure)
            assertEquals(if (target.startsWith("http:")) 1 else 2, requests)
        }
    }

    private suspend fun failure(block: suspend () -> Any): MetadataException {
        try { block() } catch (error: MetadataException) { return error }
        throw AssertionError("Expected metadata rejection")
    }
}
