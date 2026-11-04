package com.nuvio.tv.data.mediaserver.plex

import com.nuvio.tv.data.mediaserver.ServerConnection
import com.nuvio.tv.data.mediaserver.ServerLibrary
import com.nuvio.tv.data.mediaserver.ServerLibraryFilter
import com.nuvio.tv.data.mediaserver.ServerMediaKind
import com.nuvio.tv.data.mediaserver.ServerSession
import com.nuvio.tv.data.mediaserver.ServerSort
import com.nuvio.tv.data.mediaserver.mediabrowser.TestHttp
import com.nuvio.tv.data.mediaserver.mediabrowser.testIdentity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlexLibraryFilterTest {
    private val base = "https://192-168-10-24.abc123.plex.direct:32400"
    private val movies = ServerLibrary("1", "Movies", ServerMediaKind.MOVIE)
    private val credential = """{"account":"acct","user":"usert","server":"srvt","user_id":"7","user_uuid":"u7","user_name":"Alex"}"""

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
            libraries = listOf(movies)
        ),
        credential,
        base
    )

    @Test
    fun aLetterReadsThatLettersListAndUnwatchedAsksForUnwatched() = runTest {
        val http = TestHttp { """{"MediaContainer":{"size":0,"totalSize":0,"Metadata":[]}}""" }
        val provider = PlexProvider(http.client, testIdentity, now = { 1_000L })

        provider.libraryPage(session(), movies, 50, 50, ServerSort.TITLE, "322", ServerLibraryFilter('M', unwatched = true))
        val lettered = http.requests.last().url
        assertEquals("/library/sections/1/firstCharacter/M", lettered.encodedPath)
        assertEquals("titleSort", lettered.queryParameter("sort"))
        assertEquals("322", lettered.queryParameter("genre"))
        assertEquals("1", lettered.queryParameter("unwatched"))
        assertEquals("50", lettered.queryParameter("X-Plex-Container-Start"))

        provider.libraryPage(session(), movies, 0, 50, ServerSort.TITLE, null)
        val plain = http.requests.last().url
        assertEquals("/library/sections/1/all", plain.encodedPath)
        assertNull(plain.queryParameter("unwatched"))
    }
}
