package com.nuvio.tv.data.iptv

import com.nuvio.tv.core.iptv.VodKind
import com.nuvio.tv.core.iptv.VodMovie
import com.nuvio.tv.core.iptv.VodSeries
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class IptvVodXtreamTest {
    private fun connection(server: MockWebServer) = IptvSourceConnection(server.url("/panel").toString(), "user /+&", "pass?#%")

    @Test fun playUrlsAreBuiltFromStoredCredentialsOnly() {
        val connection = IptvSourceConnection("https://example.invalid:8080/base", "user name", "p&ss/word")
        assertEquals("https://example.invalid:8080/base/movie/user%20name/p&ss%2Fword/603.mkv", IptvXtreamClient.movieUrl(connection, "603", "mkv"))
        assertEquals("https://example.invalid:8080/base/series/user%20name/p&ss%2Fword/101.mp4", IptvXtreamClient.episodeUrl(connection, "101", "mp4"))
        for ((id, extension) in listOf("1.2" to "mkv", "../1" to "mkv", "1" to "m/kv", "1" to "", "" to "mp4", "1" to "toolongext")) {
            assertThrows(MetadataException::class.java) { IptvXtreamClient.movieUrl(connection, id, extension) }
        }
    }

    @Test fun listsAreStreamedWithTheRightActions() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""[{"category_id":"1","category_name":"Action"}]"""))
            server.enqueue(MockResponse().setBody("""[{"name":"EN - Heat (1995)","stream_id":949,"container_extension":"mkv","tmdb":"949"},{"name":"Bad"}]"""))
            server.enqueue(MockResponse().setBody("""[{"name":"Breaking Bad","series_id":"1396","tmdb_id":"1396"}]"""))
            val client = IptvXtreamClient()
            assertEquals("Action", client.vodCategories(connection(server), VodKind.MOVIE).single().name)
            val movies = mutableListOf<VodMovie>()
            val count = client.movies(connection(server)) { movies += it }
            assertEquals(1, count.accepted); assertEquals(1, count.invalid)
            assertEquals("949", movies.single().tmdbId)
            val series = mutableListOf<VodSeries>()
            client.series(connection(server)) { series += it }
            assertEquals("1396", series.single().providerId)
            val requests = (0..2).map { server.takeRequest().requestUrl!! }
            assertEquals(listOf("get_vod_categories", "get_vod_streams", "get_series"), requests.map { it.queryParameter("action") })
            assertTrue(requests.all { it.encodedPath == "/panel/player_api.php" && it.queryParameter("username") == "user /+&" && it.queryParameter("password") == "pass?#%" })
        }
    }

    @Test fun seriesInfoIsRequestedById() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"info":{"name":"Breaking Bad"},"episodes":{"1":[{"id":"101","episode_num":1,"container_extension":"mkv"}]}}"""))
            val info = IptvXtreamClient().seriesInfo(connection(server), "1396")
            assertEquals("101", info.episodes.single().providerId)
            val url = server.takeRequest().requestUrl!!
            assertEquals("get_series_info", url.queryParameter("action")); assertEquals("1396", url.queryParameter("series_id"))
            assertThrows(MetadataException::class.java) { runBlocking { IptvXtreamClient().seriesInfo(connection(server), "s1") } }
            Unit
        }
    }

    @Test fun listFailuresAreReportedAsMetadataFailures() = runBlocking {
        val cases = listOf(MockResponse().setResponseCode(401) to MetadataFailure.HTTP_STATUS,
            MockResponse().setResponseCode(302).setHeader("Location", "/elsewhere") to MetadataFailure.REDIRECT_REQUIRES_REVIEW,
            MockResponse().setBody("[{\"name\":") to MetadataFailure.INVALID_RESPONSE,
            MockResponse().setBody("<html>") to MetadataFailure.INVALID_RESPONSE)
        for ((response, failure) in cases) MockWebServer().use { server ->
            server.enqueue(response)
            val error = runCatching { IptvXtreamClient().movies(connection(server)) {} }.exceptionOrNull()
            assertEquals(failure, (error as MetadataException).failure)
        }
    }
}
