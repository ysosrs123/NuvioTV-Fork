package com.nuvio.tv.data.mediaserver.silo

import com.nuvio.tv.data.mediaserver.ServerConnection
import com.nuvio.tv.data.mediaserver.ServerGenre
import com.nuvio.tv.data.mediaserver.ServerLibrary
import com.nuvio.tv.data.mediaserver.ServerMediaKind
import com.nuvio.tv.data.mediaserver.ServerSession
import com.nuvio.tv.data.mediaserver.ServerSort
import com.nuvio.tv.data.mediaserver.mediabrowser.TestHttp
import com.nuvio.tv.data.mediaserver.mediabrowser.testIdentity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SiloLibraryBrowseTest {
    private val base = "http://silo.local:8090"
    private val movies = ServerLibrary("1", "Films", ServerMediaKind.MOVIE)
    private val credential = """{"access":"acc","refresh":"ref","expires_at_ms":${Long.MAX_VALUE},"profile_id":"p1","profile_name":"Laura"}"""

    private fun session() = ServerSession(
        ServerConnection(
            id = "c1",
            providerId = SiloNativeProvider.ID,
            name = "Silo",
            address = base,
            remoteServerId = "srv",
            remoteUserId = "1",
            userName = "laura",
            credentialRef = "k1",
            libraries = listOf(movies)
        ),
        credential,
        base
    )

    private fun silo(): Pair<SiloNativeProvider, TestHttp> {
        val http = TestHttp { request ->
            when (request.url.encodedPath) {
                "/api/v2/catalog/filters" -> """{"genres":["Crime"," Drama ","Crime",""],"studios":["A24"]}"""
                else -> """{"items":[],"page":{"has_more":false},"total":0,"total_exact":true}"""
            }
        }
        return SiloNativeProvider(http.client, testIdentity, now = { 1_000L }, drawnWidths = { null }) to http
    }

    @Test
    fun genresComeFromTheLibrarysFilterMenu() = runTest {
        val (provider, http) = silo()

        assertEquals(listOf(ServerGenre("Crime", "Crime"), ServerGenre("Drama", "Drama")), provider.libraryGenres(session(), movies))
        val request = http.requests.single().url
        assertEquals("1", request.queryParameter("library_id"))
        assertEquals("true", request.queryParameter("skip_technical"))
    }

    @Test
    fun eachOrderAndAGenreGoToTheCatalog() = runTest {
        val (provider, http) = silo()

        provider.libraryPage(session(), movies, 0, 50, ServerSort.TITLE, null)
        assertEquals("title", http.requests.last().url.queryParameter("sort"))
        assertNull(http.requests.last().url.queryParameter("genre"))

        provider.libraryPage(session(), movies, 0, 50, ServerSort.RELEASED, "Crime")
        assertEquals("-release_date", http.requests.last().url.queryParameter("sort"))
        assertEquals("Crime", http.requests.last().url.queryParameter("genre"))

        provider.libraryPage(session(), movies, 0, 50)
        assertEquals("-added_at", http.requests.last().url.queryParameter("sort"))
    }
}
