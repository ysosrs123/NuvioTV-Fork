package com.nuvio.tv.data.mediaserver.plex

import com.nuvio.tv.data.mediaserver.ServerConnection
import com.nuvio.tv.data.mediaserver.ServerGenre
import com.nuvio.tv.data.mediaserver.ServerLibrary
import com.nuvio.tv.data.mediaserver.ServerMediaKind
import com.nuvio.tv.data.mediaserver.ServerSession
import com.nuvio.tv.data.mediaserver.ServerSort
import com.nuvio.tv.data.mediaserver.mediabrowser.TestHttp
import com.nuvio.tv.data.mediaserver.mediabrowser.testIdentity
import kotlinx.coroutines.test.runTest
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlexLibraryBrowseTest {
    private val base = "https://192-168-10-24.abc123.plex.direct:32400"
    private val movies = ServerLibrary("1", "Movies", ServerMediaKind.MOVIE)
    private val concerts = ServerLibrary("3", "Other Videos", ServerMediaKind.MOVIE)
    private val credential = """{"account":"acct","user":"usert","server":"srvt","user_id":"7","user_uuid":"u7","user_name":"Alex"}"""

    private fun plex(routes: (Request) -> String?): Pair<PlexProvider, TestHttp> {
        val http = TestHttp(
            status = { if (routes(it) == null) 404 else 200 },
            responder = { routes(it).orEmpty() }
        )
        return PlexProvider(http.client, testIdentity, now = { 1_000L }) to http
    }

    private fun session() = ServerSession(
        ServerConnection(
            id = "c1",
            providerId = PlexProvider.ID,
            name = "HOME-PC",
            address = base,
            remoteServerId = "machine1",
            remoteUserId = "7",
            userName = "Alex",
            credentialRef = "k1",
            libraries = listOf(movies, concerts)
        ),
        credential,
        base
    )

    private fun container(body: String) = """{"MediaContainer":{$body}}"""

    private val sections = container(
        """"Directory":[{"key":"1","title":"Movies","type":"movie","agent":"tv.plex.agents.movie"},
            {"key":"3","title":"Other Videos","type":"movie","agent":"tv.plex.agents.none"}]"""
    )

    private fun routes(request: Request): String? = when (request.url.encodedPath) {
        "/library/sections" -> sections
        "/library/sections/1/all", "/library/sections/3/all" -> container(""""size":0,"totalSize":0,"Metadata":[]""")
        "/library/sections/1/genre" -> container(
            """"size":2,"Directory":[{"key":"322","title":"Action","type":"genre"},{"key":"11","title":" Comedy ","type":"genre"},
                {"key":"","title":"Broken","type":"genre"}]"""
        )
        "/library/sections/3/genre" -> container(""""size":0""")
        else -> null
    }

    @Test
    fun readsALibrarysGenresWithTheirKeys() = runTest {
        val (provider, _) = plex(::routes)

        assertEquals(listOf(ServerGenre("322", "Action"), ServerGenre("11", "Comedy")), provider.libraryGenres(session(), movies))
        assertTrue(provider.libraryGenres(session(), concerts).isEmpty())
    }

    @Test
    fun eachOrderAndAGenreGoToTheSection() = runTest {
        val (provider, http) = plex(::routes)

        provider.libraryPage(session(), movies, 0, 50, ServerSort.TITLE, null)
        assertEquals("titleSort", http.requests.last().url.queryParameter("sort"))
        assertNull(http.requests.last().url.queryParameter("genre"))

        provider.libraryPage(session(), movies, 0, 50, ServerSort.RELEASED, "322")
        assertEquals("originallyAvailableAt:desc", http.requests.last().url.queryParameter("sort"))
        assertEquals("322", http.requests.last().url.queryParameter("genre"))

        provider.libraryPage(session(), movies, 0, 50)
        assertEquals("addedAt:desc", http.requests.last().url.queryParameter("sort"))
    }

    @Test
    fun personalMediaKeepsTheAddedOrderForNewestRelease() = runTest {
        val (provider, http) = plex(::routes)

        provider.libraryPage(session(), concerts, 0, 50, ServerSort.RELEASED, null)
        provider.libraryPage(session(), movies, 0, 50, ServerSort.RELEASED, null)

        assertEquals("addedAt:desc", http.requests.first { it.url.encodedPath == "/library/sections/3/all" }.url.queryParameter("sort"))
        assertEquals(1, http.requests.count { it.url.encodedPath == "/library/sections" })
    }
}
