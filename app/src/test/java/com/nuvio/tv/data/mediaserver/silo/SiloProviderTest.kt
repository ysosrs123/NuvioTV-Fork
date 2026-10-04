package com.nuvio.tv.data.mediaserver.silo

import com.nuvio.tv.data.mediaserver.MemoryServerPersistence
import com.nuvio.tv.data.mediaserver.ServerConnection
import com.nuvio.tv.data.mediaserver.ServerItemRef
import com.nuvio.tv.data.mediaserver.ServerLibrary
import com.nuvio.tv.data.mediaserver.ServerMediaKind
import com.nuvio.tv.data.mediaserver.ServerPlayMethod
import com.nuvio.tv.data.mediaserver.ServerPlaybackRequest
import com.nuvio.tv.data.mediaserver.ServerPlaybackTarget
import com.nuvio.tv.data.mediaserver.ServerPlayerCapabilities
import com.nuvio.tv.data.mediaserver.ServerRepository
import com.nuvio.tv.data.mediaserver.ServerSession
import com.nuvio.tv.data.mediaserver.emby.EmbyProvider
import com.nuvio.tv.data.mediaserver.jellyfin.JellyfinProvider
import com.nuvio.tv.data.mediaserver.mediabrowser.PublicInfo
import com.nuvio.tv.data.mediaserver.mediabrowser.TestHttp
import com.nuvio.tv.data.mediaserver.mediabrowser.testIdentity
import com.nuvio.tv.data.mediaserver.mediabrowser.text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SiloProviderTest {
    private fun session(address: String = "http://192.168.1.20:8096") = ServerSession(
        connection = ServerConnection(
            id = "csilo",
            providerId = SiloProvider.ID,
            name = "Silo Box",
            address = address,
            remoteServerId = "s1",
            remoteUserId = "u1",
            userName = "viewer",
            credentialRef = "k1",
            libraries = listOf(ServerLibrary("lib1", "Movies", ServerMediaKind.MOVIE))
        ),
        token = "secret"
    )

    @Test
    fun namesItselfSilo() {
        val silo = SiloProvider(TestHttp().client, testIdentity)
        assertEquals("silo", silo.id)
        assertEquals("Silo", silo.displayName)
    }

    @Test
    fun signsInOnJellyfinRoutesWithoutApiPrefix() = runTest {
        val http = TestHttp { request ->
            when (request.url.encodedPath) {
                "/System/Info/Public" ->
                    """{"ServerName": "Silo Box", "Version": "12.1.0", "ProductName": "Jellyfin Server", "Id": "srv"}"""
                "/Users/AuthenticateByName" ->
                    """{"User": {"Id": "u1", "Name": "viewer"}, "AccessToken": "tok", "ServerId": "srv"}"""
                else -> error("Unexpected ${request.url}")
            }
        }
        val signIn = SiloProvider(http.client, testIdentity).signIn("192.168.1.20:8096", "viewer", "pw")

        assertEquals("http://192.168.1.20:8096", signIn.address)
        assertEquals("Silo Box", signIn.serverName)
        assertEquals("srv", signIn.serverId)
        assertEquals("u1", signIn.userId)
        assertEquals("tok", signIn.token)
        val auth = http.requests.last()
        assertEquals("POST", auth.method)
        assertTrue(auth.header("Authorization").orEmpty().startsWith("MediaBrowser Client="))
        assertNull(auth.header("X-Emby-Authorization"))
        assertTrue(http.requests.none { it.url.encodedPath.startsWith("/emby") })
    }

    @Test
    fun acceptsTheOlderEmulatedVersion() = runTest {
        val http = TestHttp { request ->
            when (request.url.encodedPath) {
                "/System/Info/Public" ->
                    """{"ServerName": "Silo Box", "Version": "10.11.6", "ProductName": "Jellyfin Server", "Id": "srv"}"""
                else -> """{"User": {"Id": "u1"}, "AccessToken": "tok", "ServerId": "srv"}"""
            }
        }
        val signIn = SiloProvider(http.client, testIdentity).signIn("http://silo.local:8096", "viewer", "pw")
        assertEquals("tok", signIn.token)
        assertEquals("viewer", signIn.userName)
    }

    @Test
    fun usesQueryScopedRoutesAndTheAuthorizationHeader() = runTest {
        val http = TestHttp { request ->
            when {
                request.url.encodedPath.endsWith("/UserViews") ->
                    """{"Items": [{"Id": "lib1", "Name": "Movies", "CollectionType": "movies"}]}"""
                request.url.encodedPath.endsWith("/UserItems/Resume") -> """{"Items": []}"""
                else -> ""
            }
        }
        val silo = SiloProvider(http.client, testIdentity)

        val libraries = silo.libraries(session())
        silo.resumeItems(session(), limit = 5)
        silo.setPlayed(session(), "i1", played = true)

        assertEquals(listOf(ServerLibrary("lib1", "Movies", ServerMediaKind.MOVIE)), libraries)
        assertEquals(
            listOf(
                "GET http://192.168.1.20:8096/UserViews",
                "GET http://192.168.1.20:8096/UserItems/Resume",
                "POST http://192.168.1.20:8096/UserPlayedItems/i1"
            ),
            http.requests.map { "${it.method} ${it.url.toString().substringBefore('?')}" }
        )
        http.requests.forEach { request ->
            assertEquals("u1", request.url.queryParameter("userId"))
            assertTrue(request.header("Authorization").orEmpty().endsWith("""Token="secret""""))
        }
    }

    @Test
    fun listsEachFileVersionAsItsOwnSource() = runTest {
        val http = TestHttp {
            """{"Id": "item1", "Name": "Film", "Type": "Movie", "MediaSources": [
                 {"Id": "ver-a", "Name": "Film 2160p", "Container": "mkv", "Size": 100, "Path": "/media/Film.2160p.mkv",
                  "MediaStreams": [{"Type": "Video", "Width": 3840, "Height": 2160}]},
                 {"Id": "ver-b", "Name": "Film 1080p", "Container": "mkv", "Size": 50, "Path": "/media/Film.1080p.mkv",
                  "MediaStreams": [{"Type": "Video", "Width": 1920, "Height": 1080}]}]}"""
        }
        val candidates = SiloProvider(http.client, testIdentity).candidates(session(), "item1")

        assertEquals(listOf("ver-a", "ver-b"), candidates.map { it.target.mediaSourceId })
        assertEquals(setOf(ServerItemRef("csilo", "item1")), candidates.map { it.target.item }.toSet())
        assertEquals(listOf("Film.2160p.mkv", "Film.1080p.mkv"), candidates.map { it.filename })
        assertEquals(2, candidates.map { it.target.key() }.toSet().size)
    }

    @Test
    fun playsTheRequestedVersionDirectly() = runTest {
        val http = TestHttp { request ->
            if (request.url.encodedPath.endsWith("/PlaybackInfo")) {
                """{"PlaySessionId": "ps1", "MediaSources": [
                     {"Id": "ver-a", "SupportsDirectPlay": true},
                     {"Id": "ver-b", "SupportsDirectPlay": true}]}"""
            } else {
                ""
            }
        }
        val silo = SiloProvider(http.client, testIdentity)
        val playback = silo.preparePlayback(
            session(),
            ServerPlaybackRequest(
                target = ServerPlaybackTarget(ServerItemRef("csilo", "item1"), mediaSourceId = "ver-b"),
                capabilities = ServerPlayerCapabilities()
            )
        )

        val info = http.requests.single()
        assertEquals("/Items/item1/PlaybackInfo", info.url.encodedPath)
        assertEquals("ver-b", info.url.queryParameter("mediaSourceId"))
        assertTrue(info.text.contains(""""MediaSourceId":"ver-b""""))
        assertEquals(ServerPlayMethod.DIRECT_PLAY, playback.playMethod)
        assertEquals("ver-b", playback.mediaSourceId)
        assertTrue(
            playback.url.startsWith(
                "http://192.168.1.20:8096/Videos/item1/stream?static=true&mediaSourceId=ver-b&playSessionId=ps1"
            )
        )
        assertTrue(playback.url.endsWith("&ApiKey=secret"))
    }

    @Test
    fun embyEntryDoesNotAcceptASiloServer() {
        val emby = EmbyProvider(TestHttp().client, testIdentity)
        assertFalse(emby.isSupported(PublicInfo(version = "12.1.0", productName = "Jellyfin Server")))
    }

    @Test
    fun storedConnectionsStaySilo() {
        val persistence = MemoryServerPersistence()
        val providers = listOf(
            JellyfinProvider(TestHttp().client, testIdentity),
            EmbyProvider(TestHttp().client, testIdentity),
            SiloProvider(TestHttp().client, testIdentity)
        )
        val repository = ServerRepository(persistence, providers, CoroutineScope(Dispatchers.Unconfined))
        repository.store(session().connection, token = "secret")

        val reloaded = ServerRepository(persistence, providers, CoroutineScope(Dispatchers.Unconfined))
        val connection = reloaded.connection("csilo")!!

        assertEquals("silo", connection.providerId)
        assertTrue(reloaded.provider(connection) is SiloProvider)
        assertEquals("Silo · Silo Box", reloaded.sourceLabel(connection))
        assertEquals("secret", reloaded.session("csilo")?.token)
    }
}
