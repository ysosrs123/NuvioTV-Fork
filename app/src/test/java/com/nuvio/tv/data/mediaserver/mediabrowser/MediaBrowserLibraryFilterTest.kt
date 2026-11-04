package com.nuvio.tv.data.mediaserver.mediabrowser

import com.nuvio.tv.data.mediaserver.ServerConnection
import com.nuvio.tv.data.mediaserver.ServerLibrary
import com.nuvio.tv.data.mediaserver.ServerLibraryFilter
import com.nuvio.tv.data.mediaserver.ServerMediaKind
import com.nuvio.tv.data.mediaserver.ServerSession
import com.nuvio.tv.data.mediaserver.ServerSort
import com.nuvio.tv.data.mediaserver.jellyfin.JellyfinProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaBrowserLibraryFilterTest {
    private val library = ServerLibrary("lib1", "Movies", ServerMediaKind.MOVIE)

    private fun session() = ServerSession(
        connection = ServerConnection(
            id = "cabc",
            providerId = "jellyfin",
            name = "Home",
            address = "https://media.example.com",
            remoteServerId = "s1",
            remoteUserId = "u1",
            userName = "viewer",
            credentialRef = "k1",
            libraries = listOf(library)
        ),
        token = "secret"
    )

    @Test
    fun letterAndUnwatchedAreServerFilters() = runTest {
        val http = TestHttp { """{"Items": [], "TotalRecordCount": 0}""" }
        val jellyfin = JellyfinProvider(http.client, testIdentity)

        jellyfin.libraryPage(session(), library, 0, 50, ServerSort.TITLE, null, ServerLibraryFilter('B', unwatched = true))

        val request = http.requests.single().url
        assertEquals("B", request.queryParameter("nameStartsWith"))
        assertEquals("false", request.queryParameter("isPlayed"))
        assertEquals("SortName", request.queryParameter("sortBy"))
    }

    @Test
    fun anEmptyFilteredListDoesNotFallBackToTheFirstGenre() = runTest {
        val http = TestHttp { request ->
            if (request.url.encodedPath.endsWith("/Genres")) """{"Items": [{"Id": "g1", "Name": "Action"}]}"""
            else """{"Items": [], "TotalRecordCount": 0}"""
        }
        val jellyfin = JellyfinProvider(http.client, testIdentity)

        jellyfin.libraryPage(session(), library, 0, 50, ServerSort.TITLE, null, ServerLibraryFilter(null, unwatched = true))

        assertNull(http.requests.single().url.queryParameter("genreIds"))
    }
}
