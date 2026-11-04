package com.nuvio.tv.data.mediaserver.silo

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

class SiloLibraryFilterTest {
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

    @Test
    fun aLetterIsANamePrefixAndUnwatchedIsAWatchedRule() = runTest {
        val http = TestHttp { """{"items":[],"page":{"has_more":false},"total":0,"total_exact":true}""" }
        val provider = SiloNativeProvider(http.client, testIdentity, now = { 1_000L }, drawnWidths = { null })

        provider.libraryPage(session(), movies, 0, 50, ServerSort.TITLE, null, ServerLibraryFilter('H', unwatched = true))
        val filtered = http.requests.last().url
        assertEquals("h", filtered.queryParameter("name_prefix"))
        assertEquals(
            """[{"match":"all","rules":[{"field":"watched","op":"is","value":false}]}]""",
            filtered.queryParameter("groups")
        )
        assertEquals("title", filtered.queryParameter("sort"))

        provider.libraryPage(session(), movies, 0, 50, ServerSort.TITLE, null)
        val plain = http.requests.last().url
        assertNull(plain.queryParameter("name_prefix"))
        assertNull(plain.queryParameter("groups"))
    }
}
