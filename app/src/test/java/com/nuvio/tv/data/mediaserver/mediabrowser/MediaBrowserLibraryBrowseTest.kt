package com.nuvio.tv.data.mediaserver.mediabrowser

import com.nuvio.tv.data.mediaserver.ServerConnection
import com.nuvio.tv.data.mediaserver.ServerGenre
import com.nuvio.tv.data.mediaserver.ServerLibrary
import com.nuvio.tv.data.mediaserver.ServerMediaKind
import com.nuvio.tv.data.mediaserver.ServerSession
import com.nuvio.tv.data.mediaserver.ServerSort
import com.nuvio.tv.data.mediaserver.emby.EmbyProvider
import com.nuvio.tv.data.mediaserver.jellyfin.JellyfinProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaBrowserLibraryBrowseTest {
    private val library = ServerLibrary("lib1", "Movies", ServerMediaKind.MOVIE)

    private fun session(providerId: String, address: String) = ServerSession(
        connection = ServerConnection(
            id = "cabc",
            providerId = providerId,
            name = "Home",
            address = address,
            remoteServerId = "s1",
            remoteUserId = "u1",
            userName = "viewer",
            credentialRef = "k1",
            libraries = listOf(library)
        ),
        token = "secret"
    )

    @Test
    fun readsTheLibrarysGenres() = runTest {
        val http = TestHttp { """{"Items": [{"Id": "g1", "Name": "Action"}, {"Id": "g2", "Name": "  "}, {"Id": "g3", "Name": "Drama"}]}""" }
        val jellyfin = JellyfinProvider(http.client, testIdentity)

        val genres = jellyfin.libraryGenres(session("jellyfin", "https://media.example.com/jellyfin"), library)

        assertEquals(listOf(ServerGenre("g1", "Action"), ServerGenre("g3", "Drama")), genres)
        val request = http.requests.single().url
        assertEquals("/jellyfin/Genres", request.encodedPath)
        assertEquals("lib1", request.queryParameter("parentId"))
        assertNull(request.queryParameter("limit"))
    }

    @Test
    fun eachOrderAsksTheServerForItsOwnSort() = runTest {
        val http = TestHttp { """{"Items": [{"Id": "m1", "Name": "Heat", "Type": "Movie"}], "TotalRecordCount": 1}""" }
        val emby = EmbyProvider(http.client, testIdentity)
        val session = session("emby", "https://media.example.com")

        emby.libraryPage(session, library, 0, 50, ServerSort.TITLE, null)
        assertEquals("SortName", http.requests.last().url.queryParameter("sortBy"))
        assertEquals("Ascending", http.requests.last().url.queryParameter("sortOrder"))

        emby.libraryPage(session, library, 0, 50, ServerSort.RELEASED, null)
        assertEquals("PremiereDate,SortName", http.requests.last().url.queryParameter("sortBy"))
        assertEquals("Descending", http.requests.last().url.queryParameter("sortOrder"))

        emby.libraryPage(session, library, 0, 50)
        assertEquals("DateCreated,SortName", http.requests.last().url.queryParameter("sortBy"))
    }

    @Test
    fun aChosenGenreWinsOverTheFirstGenreFallback() = runTest {
        val http = TestHttp { request ->
            when {
                request.url.encodedPath.endsWith("/Genres") -> """{"Items": [{"Id": "g1", "Name": "Action"}]}"""
                request.url.queryParameter("genreIds") == null -> """{"Items": [], "TotalRecordCount": 0}"""
                else -> """{"Items": [{"Id": "m1", "Name": "Heat", "Type": "Movie"}], "TotalRecordCount": 1}"""
            }
        }
        val jellyfin = JellyfinProvider(http.client, testIdentity)
        val session = session("jellyfin", "https://media.example.com/jellyfin")

        jellyfin.libraryPage(session, library, 0, 50)
        assertEquals("g1", http.requests.last().url.queryParameter("genreIds"))

        jellyfin.libraryPage(session, library, 0, 50, ServerSort.TITLE, "g7")
        assertEquals("g7", http.requests.last().url.queryParameter("genreIds"))
        assertEquals("SortName", http.requests.last().url.queryParameter("sortBy"))
        assertEquals(1, http.requests.count { it.url.encodedPath.endsWith("/Genres") })
    }
}
