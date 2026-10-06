package com.nuvio.tv.data.iptv

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test

class IptvStalkerClientTest {
    private val mac = "00:1a:79:ab:cd:ef"
    private fun server(profile: String = """{"js":{"id":"7","status":0}}""", override: (RecordedRequest) -> MockResponse? = { null }) = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = override(request) ?: when (request.requestUrl?.queryParameter("action")) {
                "handshake" -> MockResponse().setBody("""{"js":{"token":"TOKEN1"}}""")
                "get_profile" -> MockResponse().setBody(profile)
                "get_genres" -> MockResponse().setBody("""{"js":[{"id":"*","title":"All"},{"id":"3","title":"News"}]}""")
                "get_all_channels" -> MockResponse().setBody("""{"js":{"data":[{"id":"1","name":"One","number":"101","cmd":"ffmpeg http://localhost/ch/1_","tv_genre_id":"3","xmltv_id":"one.uk"}]}}""")
                "create_link" -> MockResponse().setBody("""{"js":{"cmd":"ffmpeg http://media.invalid/live/1.ts?play_token=abc"}}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
    }
    private fun connection(server: MockWebServer, address: String = "/c/", macAddress: String? = mac) =
        IptvSourceConnection(server.url(address).toString(), macAddress)

    @Test fun refreshHandshakesChecksProfileAndBuildsCatalogueRecords() = runBlocking {
        server().use { server ->
            val download = IptvStalkerClient().catalogue(connection(server))
            assertTrue(download.canPublish)
            val record = download.records.single()
            assertEquals("1", record.data.providerId); assertEquals("one.uk", record.data.guideId); assertEquals("http://localhost/ch/1_", record.data.locator)
            assertEquals("ffmpeg http://localhost/ch/1_", record.attributes[IptvStalkerClient.COMMAND_ATTRIBUTE])
            assertEquals("News", record.attributes["group-title"]); assertEquals("101", record.attributes["channel-number"])
            val requests = (0 until server.requestCount).map { server.takeRequest() }
            assertEquals(listOf("handshake", "get_profile", "get_genres", "get_all_channels"), requests.map { it.requestUrl!!.queryParameter("action") })
            assertTrue(requests.all { it.requestUrl!!.encodedPath == "/portal.php" })
            assertTrue(requests.all { it.getHeader("Cookie")!!.startsWith("mac=00%3A1A%3A79%3AAB%3ACD%3AEF;") })
            assertNull(requests.first().getHeader("Authorization"))
            assertTrue(requests.drop(1).all { it.getHeader("Authorization") == "Bearer TOKEN1" })
            assertFalse(download.toString().contains("TOKEN1")); assertFalse(download.toString().contains("00:1A"))
        }
    }
    @Test fun streamLinkIsCreatedOnlyOnRequest() = runBlocking {
        server().use { server ->
            assertEquals("http://media.invalid/live/1.ts?play_token=abc", IptvStalkerClient().streamUrl(connection(server), "ffmpeg http://localhost/ch/1_"))
            val actions = (0 until server.requestCount).map { server.takeRequest().requestUrl!! }
            assertEquals(listOf("handshake", "get_profile", "create_link"), actions.map { it.queryParameter("action") })
            assertEquals("ffmpeg http://localhost/ch/1_", actions.last().queryParameter("cmd"))
        }
    }
    @Test fun blockedAccountsRedirectsAndBadInputFailExplicitly() = runBlocking {
        server(profile = """{"js":{"status":1}}""").use { server ->
            assertEquals(MetadataFailure.AUTHENTICATION, failure { IptvStalkerClient().catalogue(connection(server)) })
            assertEquals(2, server.requestCount)
        }
        server(override = { if (it.requestUrl?.queryParameter("action") == "handshake") MockResponse().setResponseCode(302).addHeader("Location", "http://elsewhere.invalid/") else null }).use { server ->
            assertEquals(MetadataFailure.REDIRECT_REQUIRES_REVIEW, failure { IptvStalkerClient().catalogue(connection(server)) })
        }
        server().use { server ->
            assertEquals(MetadataFailure.INVALID_ADDRESS, failure { IptvStalkerClient().catalogue(connection(server, macAddress = "not-a-mac")) })
            assertEquals(0, server.requestCount)
        }
        server(override = { if (it.requestUrl?.queryParameter("action") == "handshake") MockResponse().setBody("""{"js":{"token":""}}""") else null }).use { server ->
            assertEquals(MetadataFailure.INVALID_RESPONSE, failure { IptvStalkerClient().catalogue(connection(server)) })
        }
    }
    private suspend fun failure(block: suspend () -> Any): MetadataFailure = try { block(); throw AssertionError("Expected failure") } catch (error: MetadataException) { error.failure }
}
