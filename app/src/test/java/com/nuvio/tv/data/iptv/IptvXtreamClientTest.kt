package com.nuvio.tv.data.iptv

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

class IptvXtreamClientTest {
    private val auth = """{"user_info":{"auth":1,"status":"Active","max_connections":"12","allowed_output_formats":["ts","m3u8"]},"server_info":{"url":"do-not-contact.invalid"}}"""
    private val categories = """[{"category_id":"1","category_name":"News"}]"""
    private val channels = """[{"stream_id":42,"name":"Fixture","category_id":"1","epg_channel_id":"guide.one","tv_archive":1,"tv_archive_duration":"2","direct_source":"https://do-not-contact.invalid/media","stream_icon":"https://do-not-contact.invalid/logo"}]"""
    private fun connection(server: MockWebServer) = IptvSourceConnection(server.url("/panel").toString(), "user /+&", "pass?#%")
    private fun enqueue(server: MockWebServer, account: String = auth, groups: String = categories, rows: String = channels) {
        listOf(account, groups, rows).forEach { server.enqueue(MockResponse().setBody(it)) }
    }

    @Test fun onlyThreeMetadataCallsAndEncodedPathSegmentsUseEnteredOrigin() = runBlocking {
        MockWebServer().use { server ->
            enqueue(server)
            val result = IptvXtreamClient().catalogue(connection(server))
            assertTrue(result.canPublish)
            assertEquals(12, result.account.advertisedConnections)
            val record = result.records.single()
            assertEquals("42", record.data.providerId)
            assertEquals("guide.one", record.data.guideId)
            assertEquals("News", record.attributes["group-title"])
            assertEquals("ADVERTISED", record.attributes["archive-availability"])
            assertEquals("https://do-not-contact.invalid/logo", record.attributes["tvg-logo"])
            assertEquals(server.url("/panel/live/42.ts").toString(), record.data.locator)
            assertEquals(server.url("/panel/live/user%20%2F+&/pass%3F%23%25/42.ts").toString(), IptvXtreamClient.streamUrl(connection(server), record.data.locator))
            val requests = (0..2).map { server.takeRequest().requestUrl!! }
            assertEquals(listOf(null, "get_live_categories", "get_live_streams"), requests.map { it.queryParameter("action") })
            assertTrue(requests.all { it.encodedPath == "/panel/player_api.php" && it.queryParameter("username") == "user /+&" && it.queryParameter("password") == "pass?#%" })
            assertEquals(3, server.requestCount)
            assertFalse(result.toString().contains("pass"))
        }
    }

    @Test fun guideUrlUsesTheEnteredOriginAndEncodesCredentials() {
        val url = IptvXtreamClient.guideUrl(IptvSourceConnection("https://example.invalid:8080/base", "user name", "p&ss/word"))
        assertEquals("https://example.invalid:8080/base/xmltv.php?username=user%20name&password=p%26ss%2Fword", url)
        assertEquals("https://example.invalid/xmltv.php?username=u&password=p",
            IptvXtreamClient.guideUrl(IptvSourceConnection("https://example.invalid/", "u", "p")))
        for (endpoint in listOf("https://example.invalid/player_api.php", "https://u:p@example.invalid/"))
            try { IptvXtreamClient.guideUrl(IptvSourceConnection(endpoint, "u", "p")); fail() } catch (_: MetadataException) { }
    }
    @Test fun streamUrlUsesTheCurrentConnectionForStoredAndOlderLocators() {
        val connection = IptvSourceConnection("http://example.invalid:8080", "u", "p/w")
        assertEquals("http://example.invalid:8080/live/u/p%2Fw/42.m3u8", IptvXtreamClient.streamUrl(connection, "http://old.invalid/live/42.m3u8"))
        assertEquals("http://example.invalid:8080/live/u/p%2Fw/42.ts", IptvXtreamClient.streamUrl(connection, "http://example.invalid:8080/live/old/secret/42.ts"))
        for (locator in listOf("http://example.invalid/live/", "http://example.invalid/live/42", "not a url"))
            try { IptvXtreamClient.streamUrl(connection, locator); fail() } catch (_: MetadataException) { }
    }
    @Test fun rejectedAccountsStopBeforeCatalogueRequests() = runBlocking {
        for (account in listOf("""{"auth":0,"status":"Active"}""", """{"auth":1,"status":"Expired"}""", """{"auth":1,"status":"Active","exp_date":"20"}""")) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody("{\"user_info\":$account}"))
                assertEquals(MetadataFailure.AUTHENTICATION, failure { IptvXtreamClient(nowSeconds = { 21 }).catalogue(connection(server)) }.failure)
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test fun http10WithImmediateConnectionClosureReadsCompleteMetadata() = runBlocking {
        MockWebServer().use { server ->
            listOf(auth, categories, channels).forEach { body ->
                server.enqueue(MockResponse().setStatus("HTTP/1.0 200 OK").setBody(body).setSocketPolicy(SocketPolicy.DISCONNECT_AT_END))
            }
            val transportError = java.util.concurrent.atomic.AtomicReference<java.io.IOException>()
            val http = IptvMetadataClient.newClient().newBuilder().eventListener(object : okhttp3.EventListener() {
                override fun callFailed(call: okhttp3.Call, ioe: java.io.IOException) { transportError.set(ioe) }
            }).build()
            val result = try { IptvXtreamClient(http).catalogue(connection(server)) }
                catch (error: MetadataException) { throw AssertionError("Controlled fixture transport failure", transportError.get() ?: error) }
            assertTrue(result.canPublish)
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun absentOutputDefaultsToTsAndExplicitHlsIsRespected() = runBlocking {
        for ((account, suffix) in listOf(auth.replace("\"ts\",", "") to ".m3u8", """{"user_info":{"auth":"1","status":"Active","exp_date":null}}""" to ".ts")) {
            MockWebServer().use { server ->
                enqueue(server, account)
                assertTrue(IptvXtreamClient().catalogue(connection(server)).records.single().data.locator.endsWith(suffix))
            }
        }
    }

    @Test fun malformedJsonIsRejectedEquallyAcrossRuntimes() = runBlocking {
        for (body in listOf(auth + "{}", auth.replace("\"auth\":1", "\"auth\":1,\"auth\":0"),
            auth.replace("\"auth\":1", "\"auth\":1,\"\\u0061uth\":0"), "{'user_info':{}}", "[".repeat(18) + "]".repeat(18),
            "{\"unknown\":\"" + "a".repeat(65537) + "\"}", auth.replace("\"auth\":1", "\"auth\":01"))) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody(body))
                assertEquals(MetadataFailure.INVALID_RESPONSE, failure { IptvXtreamClient().catalogue(connection(server)) }.failure)
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test fun invalidUtf8IsRejectedBeforeJsonParsing() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(Buffer().write(byteArrayOf(0xc3.toByte(), 0x28))))
            assertEquals(MetadataFailure.INVALID_RESPONSE, failure { IptvXtreamClient().catalogue(connection(server)) }.failure)
        }
    }

    @Test fun sizeLimitIncludesChunkedAndGzipExpandedBodies() = runBlocking {
        val bytes = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(auth.toByteArray()) } }.toByteArray()
        for (response in listOf(MockResponse().setChunkedBody(auth, 3), MockResponse().setBody(Buffer().write(bytes)).addHeader("Content-Encoding", "gzip"))) {
            MockWebServer().use { server ->
                server.enqueue(response)
                assertEquals(MetadataFailure.BODY_LIMIT, failure { IptvXtreamClient(maxBodyBytes = 40).catalogue(connection(server)) }.failure)
            }
        }
    }

    @Test fun redirectDoesNotForwardCredentialsOrContactDestination() = runBlocking {
        MockWebServer().use { server -> MockWebServer().use { other ->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", other.url("/")))
            assertEquals(MetadataFailure.REDIRECT_REQUIRES_REVIEW, failure { IptvXtreamClient().catalogue(connection(server)) }.failure)
            assertEquals(0, other.requestCount)
        } }
    }

    @Test fun failuresAreRedactedAndDoNotRetryOrAcceptPartialOr304() = runBlocking {
        MockWebServer().use { server ->
            for (status in listOf(503, 206, 304)) {
                server.enqueue(MockResponse().setResponseCode(status).setBody("secret password"))
                val error = failure { IptvXtreamClient().catalogue(connection(server)) }
                assertEquals(status, error.status); assertNull(error.cause)
                assertFalse(error.toString().contains("secret")); assertFalse(error.toString().contains("pass"))
            }
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun ambiguousCategoriesAndInvalidRowsCannotPublish() = runBlocking {
        MockWebServer().use { server ->
            enqueue(server, groups = """[{"category_id":1,"category_name":"A"},{"category_id":1,"category_name":"B"}]""")
            assertEquals(MetadataFailure.INVALID_RESPONSE, failure { IptvXtreamClient().catalogue(connection(server)) }.failure)
            assertEquals(2, server.requestCount)
        }
        MockWebServer().use { server ->
            enqueue(server, rows = channels.dropLast(1) + ",{\"stream_id\":42,\"name\":\"Conflict\"}]")
            assertFalse(IptvXtreamClient().catalogue(connection(server)).canPublish)
        }
    }

    @Test fun invalidBaseOrCredentialsAreRejectedWithoutRequests() = runBlocking {
        MockWebServer().use { server ->
            val valid = connection(server)
            for (value in listOf(valid.copy(endpoint = server.url("/player_api.php").toString()), valid.copy(endpoint = server.url("/?query=yes").toString()),
                valid.copy(username = ".."), valid.copy(password = ""), valid.copy(password = "line\nbreak"))) {
                assertEquals(MetadataFailure.INVALID_ADDRESS, failure { IptvXtreamClient().catalogue(value) }.failure)
            }
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun cancellationStopsPendingAuthenticationRequest() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val http = IptvMetadataClient.newClient()
            val job = async(Dispatchers.IO) { IptvXtreamClient(http).catalogue(connection(server)) }
            assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
            val call = http.dispatcher.runningCalls().single()
            job.cancel(); withTimeout(3000) { job.join() }
            assertTrue(call.isCanceled())
        }
    }

    private suspend fun failure(block: suspend () -> Any): MetadataException {
        try { block() } catch (error: MetadataException) { return error }
        throw AssertionError("Expected metadata rejection")
    }
}
