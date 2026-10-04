package com.nuvio.tv.data.mediaserver.mediabrowser

import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.data.mediaserver.ServerConnectTimeout
import com.nuvio.tv.data.mediaserver.ServerConnection
import com.nuvio.tv.data.mediaserver.ServerException
import com.nuvio.tv.data.mediaserver.ServerFailure
import com.nuvio.tv.data.mediaserver.ServerIndexEntry
import com.nuvio.tv.data.mediaserver.ServerItemRef
import com.nuvio.tv.data.mediaserver.ServerLibrary
import com.nuvio.tv.data.mediaserver.ServerMediaKind
import com.nuvio.tv.data.mediaserver.ServerPlaybackEvent
import com.nuvio.tv.data.mediaserver.ServerPlaybackEventType
import com.nuvio.tv.data.mediaserver.ServerPlaybackRequest
import com.nuvio.tv.data.mediaserver.ServerPlaybackTarget
import com.nuvio.tv.data.mediaserver.ServerPlayerCapabilities
import com.nuvio.tv.data.mediaserver.ServerSegment
import com.nuvio.tv.data.mediaserver.ServerSegmentKind
import com.nuvio.tv.data.mediaserver.ServerSession
import com.nuvio.tv.data.mediaserver.ServerTitleQuery
import com.nuvio.tv.data.mediaserver.emby.EmbyProvider
import com.nuvio.tv.data.mediaserver.jellyfin.JellyfinProvider
import com.nuvio.tv.data.mediaserver.silo.SiloProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

class MediaBrowserRequestTest {
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
            libraries = listOf(ServerLibrary("lib1", "Movies", ServerMediaKind.MOVIE))
        ),
        token = "secret"
    )

    @Test
    fun aShorterConnectLimitFromTheCallerReachesTheRequest() = runTest {
        val http = TestHttp { """{"ServerName": "Den", "Version": "10.10.0", "Id": "srv"}""" }
        val provider = JellyfinProvider(http.client, testIdentity)

        provider.locate("http://den.local:8096")
        withContext(ServerConnectTimeout(4_000L)) { provider.locate("http://den.local:8096") }

        assertEquals(listOf(10_000, 4_000), http.connectTimeouts)
    }

    @Test
    fun embySignInUsesApiRootAndEmbyHeader() = runTest {
        val http = TestHttp { request ->
            when (request.url.encodedPath) {
                "/emby/System/Info/Public" -> """{"ServerName": "Den", "Version": "4.8.10.0", "Id": "srv"}"""
                "/emby/Users/AuthenticateByName" -> """{"User": {"Id": "u1", "Name": "viewer"}, "AccessToken": "tok", "ServerId": "srv"}"""
                else -> error("Unexpected ${request.url}")
            }
        }
        val signIn = EmbyProvider(http.client, testIdentity).signIn("http://den.local:8096/emby/web/index.html", "viewer", "pw")

        assertEquals("http://den.local:8096", signIn.address)
        assertEquals("Den", signIn.serverName)
        assertEquals("srv", signIn.serverId)
        assertEquals("u1", signIn.userId)
        assertEquals("tok", signIn.token)
        val auth = http.requests.last()
        assertEquals("POST", auth.method)
        assertTrue(auth.text.contains("\"Pw\":\"pw\""))
        val header = auth.header("X-Emby-Authorization").orEmpty()
        assertTrue(header.startsWith("MediaBrowser Client=\"Nuvio\""))
        assertFalse(header.contains("Token="))
        assertNull(auth.header("Authorization"))
    }

    @Test
    fun embyRejectsJellyfinServers() = runTest {
        val http = TestHttp { """{"ServerName": "Den", "Version": "10.10.7", "ProductName": "Jellyfin Server", "Id": "srv"}""" }
        val error = runCatching {
            EmbyProvider(http.client, testIdentity).signIn("http://den.local:8096", "viewer", "pw")
        }.exceptionOrNull() as ServerException
        assertEquals(ServerFailure.UNSUPPORTED, error.failure)
        assertEquals(1, http.requests.size)
    }

    @Test
    fun embyUsesUserScopedRoutes() = runTest {
        val http = TestHttp { request ->
            when {
                request.url.encodedPath.endsWith("/Views") ->
                    """{"Items": [{"Id": "lib1", "Name": "Movies", "CollectionType": "movies"}, {"Id": "m", "Name": "Music", "CollectionType": "music"}]}"""
                request.url.encodedPath.endsWith("/Items/Resume") -> """{"Items": []}"""
                else -> ""
            }
        }
        val emby = EmbyProvider(http.client, testIdentity)
        val session = session("emby", "https://media.example.com")

        val libraries = emby.libraries(session)
        emby.resumeItems(session, limit = 10)
        emby.setPlayed(session, "i1", played = true)
        emby.setPlayed(session, "i1", played = false)

        assertEquals(listOf(ServerLibrary("lib1", "Movies", ServerMediaKind.MOVIE)), libraries)
        assertEquals(
            listOf(
                "GET https://media.example.com/emby/Users/u1/Views",
                "GET https://media.example.com/emby/Users/u1/Items/Resume",
                "POST https://media.example.com/emby/Users/u1/PlayedItems/i1",
                "DELETE https://media.example.com/emby/Users/u1/PlayedItems/i1"
            ),
            http.requests.map { "${it.method} ${it.url.toString().substringBefore('?')}" }
        )
        http.requests.forEach { request ->
            assertTrue(request.header("X-Emby-Authorization").orEmpty().endsWith("Token=\"secret\""))
            assertNull(request.url.queryParameter("userId"))
        }
    }

    @Test
    fun jellyfinUsesQueryScopedRoutes() = runTest {
        val http = TestHttp { request ->
            if (request.url.encodedPath.endsWith("/UserViews")) """{"Items": []}""" else ""
        }
        val jellyfin = JellyfinProvider(http.client, testIdentity)
        val session = session("jellyfin", "https://media.example.com/jellyfin")

        jellyfin.libraries(session)
        jellyfin.setPlayed(session, "i1", played = true)

        assertEquals(
            listOf(
                "GET https://media.example.com/jellyfin/UserViews?userId=u1",
                "POST https://media.example.com/jellyfin/UserPlayedItems/i1?userId=u1"
            ),
            http.requests.map { "${it.method} ${it.url}" }
        )
        http.requests.forEach { request ->
            assertTrue(request.header("Authorization").orEmpty().endsWith("Token=\"secret\""))
            assertNull(request.header("X-Emby-Authorization"))
        }
    }

    @Test
    fun embyLooksUpATitleByItsProviderIdsInOneRequest() = runTest {
        val http = TestHttp {
            """{"Items": [{"Id": "m1", "Name": "Apocalypse Now", "Type": "Movie", "ProductionYear": 1979,
                "ProviderIds": {"Imdb": "tt0078788", "Tmdb": "28"}}]}"""
        }
        val emby = EmbyProvider(http.client, testIdentity)
        val query = ServerTitleQuery(TrackingExternalIds(imdb = "tt0078788", tmdb = 28), name = "Apocalypse Now", year = 1979)

        val found = emby.lookupTitle(session("emby", "https://media.example.com"), ServerLibrary("lib1", "Movies", ServerMediaKind.MOVIE), query)

        assertEquals(listOf(ServerIndexEntry("m1", TrackingExternalIds(imdb = "tt0078788", tmdb = 28), year = 1979)), found)
        val request = http.requests.single()
        assertEquals("/emby/Users/u1/Items", request.url.encodedPath)
        assertEquals("imdb.tt0078788,tmdb.28", request.url.queryParameter("anyProviderIdEquals"))
        assertEquals("lib1", request.url.queryParameter("parentId"))
        assertEquals("Movie", request.url.queryParameter("includeItemTypes"))
        assertEquals("true", request.url.queryParameter("recursive"))
        assertEquals("ProviderIds,ProductionYear", request.url.queryParameter("fields"))
        assertNull(request.url.queryParameter("searchTerm"))
    }

    @Test
    fun embyCannotLookUpATitleWithoutImdbTmdbOrTvdbIds() = runTest {
        val http = TestHttp()
        val emby = EmbyProvider(http.client, testIdentity)
        val query = ServerTitleQuery(TrackingExternalIds(kitsu = 1), name = "Show", year = null)

        assertNull(emby.lookupTitle(session("emby", "https://media.example.com"), ServerLibrary("lib1", "Shows", ServerMediaKind.SERIES), query))
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun jellyfinTriesTheOriginalTitleOnceWhenTheNameFindsNothing() = runTest {
        val http = TestHttp { request ->
            if (request.url.queryParameter("searchTerm") == "Intouchables") {
                """{"Items": [{"Id": "m1", "ProductionYear": 2011, "ProviderIds": {"Imdb": "tt1675434"}}]}"""
            } else {
                """{"Items": [{"Id": "m9", "ProductionYear": 2011, "ProviderIds": {"Imdb": "tt0000009"}}]}"""
            }
        }
        val jellyfin = JellyfinProvider(http.client, testIdentity)
        val session = session("jellyfin", "https://media.example.com")
        val library = ServerLibrary("lib1", "Movies", ServerMediaKind.MOVIE)
        val ids = TrackingExternalIds(imdb = "tt1675434")

        val found = jellyfin.lookupTitle(session, library, ServerTitleQuery(ids, "The Intouchables", 2011, originalName = "Intouchables"))!!

        assertTrue(ServerIndexEntry("m1", ids, year = 2011) in found)
        assertEquals(listOf("The Intouchables", "Intouchables"), http.requests.map { it.url.queryParameter("searchTerm") })

        jellyfin.lookupTitle(session, library, ServerTitleQuery(ids, "Intouchables", 2011, originalName = "The Intouchables"))
        assertEquals(3, http.requests.size)
        jellyfin.lookupTitle(session, library, ServerTitleQuery(ids, "The Intouchables", 2011))
        assertEquals(4, http.requests.size)
        jellyfin.lookupTitle(session, library, ServerTitleQuery(ids, "The Intouchables", 2011, originalName = " the intouchables "))
        assertEquals(5, http.requests.size)
    }

    @Test
    fun embyAsksOnceWhateverTheOriginalTitle() = runTest {
        val http = TestHttp { """{"Items": []}""" }
        val emby = EmbyProvider(http.client, testIdentity)
        val query = ServerTitleQuery(TrackingExternalIds(imdb = "tt1675434"), "The Intouchables", 2011, originalName = "Intouchables")

        val found = emby.lookupTitle(session("emby", "https://media.example.com"), ServerLibrary("lib1", "Movies", ServerMediaKind.MOVIE), query)

        assertTrue(found!!.isEmpty())
        assertEquals(1, http.requests.size)
    }

    @Test
    fun jellyfinAndSiloLookUpATitleByName() = runTest {
        listOf("jellyfin", "silo").forEach { providerId ->
            val http = TestHttp { """{"Items": [{"Id": "m1", "ProductionYear": 1979, "ProviderIds": {"Imdb": "tt0078788"}}]}""" }
            val provider = if (providerId == "silo") SiloProvider(http.client, testIdentity) else JellyfinProvider(http.client, testIdentity)
            val session = session(providerId, "https://media.example.com")
            val library = ServerLibrary("lib1", "Movies", ServerMediaKind.MOVIE)
            val ids = TrackingExternalIds(imdb = "tt0078788")

            val found = provider.lookupTitle(session, library, ServerTitleQuery(ids, name = "Apocalypse Now", year = 1979))

            assertEquals(listOf(ServerIndexEntry("m1", ids, year = 1979)), found)
            val request = http.requests.single()
            assertEquals("/Items", request.url.encodedPath)
            assertEquals("u1", request.url.queryParameter("userId"))
            assertEquals("Apocalypse Now", request.url.queryParameter("searchTerm"))
            assertEquals("lib1", request.url.queryParameter("parentId"))
            assertEquals("20", request.url.queryParameter("limit"))
            assertNull(request.url.queryParameter("anyProviderIdEquals"))

            assertNull(provider.lookupTitle(session, library, ServerTitleQuery(ids, name = null, year = null)))
            assertEquals(1, http.requests.size)
        }
    }

    @Test
    fun requestsAndLinksUseTheAddressInUse() = runTest {
        val http = TestHttp { request ->
            if (request.url.encodedPath.endsWith("/PlaybackInfo")) {
                """{"PlaySessionId": "ps1", "MediaSources": [{"Id": "ms1", "SupportsDirectPlay": true}]}"""
            } else {
                """{"Items": []}"""
            }
        }
        val emby = EmbyProvider(http.client, testIdentity)
        val home = session("emby", "http://192.168.1.10:8096")
        val away = ServerSession(home.connection, home.token, "https://media.example.com")

        emby.libraries(away)
        val playback = emby.preparePlayback(
            away,
            ServerPlaybackRequest(ServerPlaybackTarget(ServerItemRef("cabc", "item1"), "ms1"), ServerPlayerCapabilities())
        )

        assertTrue(http.requests.all { it.url.toString().startsWith("https://media.example.com/emby/") })
        assertTrue(playback.url.startsWith("https://media.example.com/emby/Videos/item1/stream"))
    }

    @Test
    fun readsJellyfinMediaSegmentsInMilliseconds() = runTest {
        val http = TestHttp {
            """{"Items": [
                 {"Type": "Intro", "StartTicks": 300000000, "EndTicks": 905000000},
                 {"Type": "Outro", "StartTicks": 25000000000, "EndTicks": 26400000000},
                 {"Type": "Commercial", "StartTicks": 1, "EndTicks": 2},
                 {"Type": "Preview", "StartTicks": 26400000000, "EndTicks": 27000000000}],
               "TotalRecordCount": 4, "StartIndex": 0}"""
        }
        val segments = JellyfinProvider(http.client, testIdentity)
            .segments(session("jellyfin", "https://media.example.com/jellyfin"), "e1", "ms1")

        assertEquals(
            listOf(
                ServerSegment(ServerSegmentKind.INTRO, 30_000L, 90_500L),
                ServerSegment(ServerSegmentKind.OUTRO, 2_500_000L, 2_640_000L),
                ServerSegment(ServerSegmentKind.PREVIEW, 2_640_000L, 2_700_000L)
            ),
            segments
        )
        assertEquals("https://media.example.com/jellyfin/MediaSegments/e1", http.requests.single().url.toString())
    }

    @Test
    fun serversWithoutMarkersGiveNoSegments() = runTest {
        val missing = TestHttp(status = { 404 }) { "" }
        assertTrue(JellyfinProvider(missing.client, testIdentity).segments(session("jellyfin", "https://m.example"), "e1", null).isEmpty())
        val rejected = TestHttp(status = { 400 }) { "" }
        assertTrue(JellyfinProvider(rejected.client, testIdentity).segments(session("jellyfin", "https://m.example"), "e1", null).isEmpty())
        val empty = TestHttp { """{"Items": [], "TotalRecordCount": 0, "StartIndex": 0}""" }
        assertTrue(JellyfinProvider(empty.client, testIdentity).segments(session("jellyfin", "https://m.example"), "e1", null).isEmpty())
    }

    @Test
    fun siloAsksForTheMarkersOfThePlayedVersion() = runTest {
        val http = TestHttp { """{"Items": []}""" }
        SiloProvider(http.client, testIdentity).segments(session("silo", "http://silo.local:8097"), "item1", "ver-b")
        assertEquals("http://silo.local:8097/MediaSegments/ver-b", http.requests.single().url.toString())
    }

    @Test
    fun embyReadsIntroAndCreditMarkersFromChapters() = runTest {
        val http = TestHttp {
            """{"Id": "e1", "Type": "Episode", "RunTimeTicks": 26400000000, "Chapters": [
                 {"StartPositionTicks": 0, "MarkerType": "Chapter"},
                 {"StartPositionTicks": 300000000, "MarkerType": "IntroStart"},
                 {"StartPositionTicks": 1200000000, "MarkerType": "IntroEnd"},
                 {"StartPositionTicks": 25000000000, "MarkerType": "CreditsStart"}]}"""
        }
        val segments = EmbyProvider(http.client, testIdentity).segments(session("emby", "https://media.example.com"), "e1", "ms1")

        assertEquals(
            listOf(
                ServerSegment(ServerSegmentKind.INTRO, 30_000L, 120_000L),
                ServerSegment(ServerSegmentKind.OUTRO, 2_500_000L, 2_640_000L)
            ),
            segments
        )
        val request = http.requests.single()
        assertEquals("https://media.example.com/emby/Users/u1/Items/e1", request.url.toString().substringBefore('?'))
        assertEquals("Chapters", request.url.queryParameter("fields"))
    }

    @Test
    fun embyIntroWithoutAnEndIsIgnored() = runTest {
        val http = TestHttp {
            """{"Id": "e1", "Type": "Episode", "Chapters": [{"StartPositionTicks": 300000000, "MarkerType": "IntroStart"},
                 {"StartPositionTicks": 25000000000, "MarkerType": "CreditsStart"}]}"""
        }
        assertTrue(EmbyProvider(http.client, testIdentity).segments(session("emby", "https://media.example.com"), "e1", null).isEmpty())
    }

    @Test
    fun readsEveryEpisodeStateOfASeriesInOneRequest() = runTest {
        val body = """{"Items": [
             {"Id": "e1", "Type": "Episode", "ParentIndexNumber": 1, "IndexNumber": 1, "RunTimeTicks": 24000000000,
              "UserData": {"PlaybackPositionTicks": 0, "Played": true, "LastPlayedDate": "2026-10-03T01:58:51.2440629Z"}},
             {"Id": "e2", "Type": "Episode", "ParentIndexNumber": 1, "IndexNumber": 2, "RunTimeTicks": 24000000000,
              "UserData": {"PlaybackPositionTicks": 0, "Played": false}},
             {"Id": "e3", "Type": "Episode", "ParentIndexNumber": 1, "IndexNumber": 3, "LocationType": "Virtual",
              "UserData": {"Played": false}}]}"""
        val http = TestHttp { body }
        val jellyfin = JellyfinProvider(http.client, testIdentity)
        val emby = EmbyProvider(http.client, testIdentity)

        val states = jellyfin.episodeStates(session("jellyfin", "https://media.example.com/jellyfin"), "show1")
        emby.episodeStates(session("emby", "https://media.example.com"), "show1")

        assertEquals(listOf(1 to 1, 1 to 2), states.map { it.season to it.episode })
        assertTrue(states.first().played)
        assertEquals(1790992731244L, states.first().lastPlayedEpochMs)
        assertFalse(states.last().played)
        assertEquals(
            listOf("https://media.example.com/jellyfin/Shows/show1/Episodes", "https://media.example.com/emby/Shows/show1/Episodes"),
            http.requests.map { it.url.toString().substringBefore('?') }
        )
        http.requests.forEach { request ->
            assertEquals("u1", request.url.queryParameter("userId"))
            assertEquals("true", request.url.queryParameter("enableUserData"))
            assertEquals("false", request.url.queryParameter("enableImages"))
        }
    }

    @Test
    fun resumeItemsCarryTheirPositionAndEpisodeNumbers() = runTest {
        val http = TestHttp {
            """{"Items": [
                 {"Id": "m1", "Name": "Film", "Type": "Movie", "RunTimeTicks": 72000000000,
                  "UserData": {"PlaybackPositionTicks": 12000000000, "Played": false, "LastPlayedDate": "2026-10-01T10:00:00Z"}},
                 {"Id": "e7", "Name": "Seventh", "Type": "Episode", "SeriesId": "show1", "SeriesName": "Show",
                  "ParentIndexNumber": 2, "IndexNumber": 7, "RunTimeTicks": 24000000000,
                  "UserData": {"PlaybackPositionTicks": 6000000000, "Played": false, "LastPlayedDate": "2026-10-02T10:00:00Z"}},
                 {"Id": "e8", "Name": "Eighth", "Type": "Episode", "SeriesId": "show1", "SeriesName": "Show",
                  "ParentIndexNumber": 2, "IndexNumber": 8, "RunTimeTicks": 24000000000,
                  "UserData": {"PlaybackPositionTicks": 1000000000, "Played": false, "LastPlayedDate": "2026-09-30T10:00:00Z"}}]}"""
        }
        val jellyfin = JellyfinProvider(http.client, testIdentity)
        val session = session("jellyfin", "https://media.example.com/jellyfin").let {
            ServerSession(
                it.connection.copy(libraries = it.connection.libraries + ServerLibrary("lib2", "Shows", ServerMediaKind.SERIES)),
                it.token
            )
        }

        val entries = jellyfin.resumeItems(session, limit = 30)

        assertEquals("true", http.requests.single().url.queryParameter("enableUserData"))
        assertEquals(2, entries.size)
        val movie = entries.first()
        assertEquals(ServerItemRef("cabc", "m1").encode(), movie.title.preview.id)
        assertEquals(movie.title.preview.id, movie.state.videoId)
        assertEquals(1_200_000L, movie.state.positionMs)
        assertEquals(7_200_000L, movie.state.durationMs)
        val episode = entries.last()
        assertEquals(ServerItemRef("cabc", "show1").encode(), episode.title.preview.id)
        assertEquals("Show", episode.title.preview.name)
        assertEquals(ServerItemRef("cabc", "e7").encode(), episode.state.videoId)
        assertEquals(2 to 7, episode.state.season to episode.state.episode)
        assertEquals("Seventh", episode.state.title)
        assertEquals(600_000L, episode.state.positionMs)
    }

    @Test
    fun offersEveryFormatUntilDirectPlayFails() = runTest {
        val http = TestHttp {
            """{"PlaySessionId": "ps1", "MediaSources": [{"Id": "ms1", "SupportsDirectPlay": true, "TranscodingUrl": "/videos/i1/master.m3u8"}]}"""
        }
        val jellyfin = JellyfinProvider(http.client, testIdentity)
        val session = session("jellyfin", "https://media.example.com/jellyfin")
        val target = ServerPlaybackTarget(ServerItemRef("cabc", "i1"), mediaSourceId = "ms1")

        jellyfin.preparePlayback(session, ServerPlaybackRequest(target, ServerPlayerCapabilities()))
        jellyfin.preparePlayback(session, ServerPlaybackRequest(target, ServerPlayerCapabilities(allowDirectPlay = false)))

        val (direct, fallback) = http.requests
        assertEquals("1000000000", direct.url.queryParameter("maxStreamingBitrate"))
        assertTrue(direct.text.contains("\"MaxStreamingBitrate\":1000000000"))
        assertTrue(direct.text.contains("\"DirectPlayProfiles\":[{\"Type\":\"Video\"}]"))
        assertFalse(fallback.text.contains("\"DirectPlayProfiles\":[{\"Type\":\"Video\"}]"))
        assertTrue(fallback.text.contains(""""VideoCodec":"h264,hevc,vp8,vp9,av1""""))
    }

    @Test
    fun fallbackKeepsLosslessAudioAndHdrOnJellyfin() = runTest {
        val http = TestHttp {
            """{"PlaySessionId": "ps1", "MediaSources": [{"Id": "ms1", "SupportsDirectPlay": true, "TranscodingUrl": "/videos/i1/master.m3u8"}]}"""
        }
        val jellyfin = JellyfinProvider(http.client, testIdentity)
        val session = session("jellyfin", "https://media.example.com/jellyfin")
        val target = ServerPlaybackTarget(ServerItemRef("cabc", "i1"), mediaSourceId = "ms1")

        jellyfin.preparePlayback(session, ServerPlaybackRequest(target, ServerPlayerCapabilities()))
        jellyfin.preparePlayback(session, ServerPlaybackRequest(target, ServerPlayerCapabilities(allowDirectPlay = false)))

        val (direct, fallback) = http.requests.map { Json.parseToJsonElement(it.text).jsonObject.getValue("DeviceProfile").jsonObject }
        val directAudio = fallback.getValue("DirectPlayProfiles").jsonArray.single().jsonObject.getValue("AudioCodec").jsonPrimitive.content.split(',')
        listOf("truehd", "mlp", "dts", "dca", "pcm", "pcm_s24le", "pcm_bluray", "eac3", "ac3", "aac", "flac").forEach { codec ->
            assertTrue("$codec missing from $directAudio", codec in directAudio)
        }

        val profiles = fallback.getValue("TranscodingProfiles").jsonArray.map { it.jsonObject }
        assertEquals(listOf("mp4", "ts"), profiles.map { it.getValue("Container").jsonPrimitive.content })
        assertTrue(profiles.all { it.getValue("Protocol").jsonPrimitive.content == "hls" })
        val copied = profiles.first().getValue("AudioCodec").jsonPrimitive.content.split(',')
        assertTrue(copied.containsAll(listOf("truehd", "dts", "eac3", "ac3")))
        assertEquals("ac3", copied.first())
        profiles.forEach { profile ->
            listOf("AudioCodec", "VideoCodec").forEach { field ->
                val value = profile.getValue(field).jsonPrimitive.content
                assertTrue("$field $value is longer than the 40 characters Jellyfin accepts in its own links", value.length <= 40)
            }
        }

        val ranges = fallback.getValue("CodecProfiles").jsonArray.map { it.jsonObject }
        assertEquals(listOf("hevc", "av1"), ranges.map { it.getValue("Codec").jsonPrimitive.content })
        ranges.forEach { profile ->
            val condition = profile.getValue("Conditions").jsonArray.single().jsonObject
            assertEquals("EqualsAny", condition.getValue("Condition").jsonPrimitive.content)
            assertEquals("VideoRangeType", condition.getValue("Property").jsonPrimitive.content)
            val values = condition.getValue("Value").jsonPrimitive.content.split('|')
            assertTrue(values.containsAll(listOf("SDR", "HDR10", "HDR10Plus", "HLG", "DOVI", "DOVIWithHDR10", "DOVIWithEL")))
        }

        assertTrue(direct.getValue("CodecProfiles").jsonArray.isEmpty())
        assertEquals(1, direct.getValue("DirectPlayProfiles").jsonArray.single().jsonObject.size)
    }

    @Test
    fun embyFallbackKeepsLosslessAudioWithoutJellyfinOnlyConditions() = runTest {
        val http = TestHttp {
            """{"PlaySessionId": "ps1", "MediaSources": [{"Id": "ms1", "TranscodingUrl": "/videos/i1/master.m3u8"}]}"""
        }
        val emby = EmbyProvider(http.client, testIdentity)
        val target = ServerPlaybackTarget(ServerItemRef("cabc", "i1"), mediaSourceId = "ms1")

        emby.preparePlayback(
            session("emby", "https://media.example.com"),
            ServerPlaybackRequest(target, ServerPlayerCapabilities(allowDirectPlay = false))
        )

        val profile = Json.parseToJsonElement(http.requests.single().text).jsonObject.getValue("DeviceProfile").jsonObject
        val directAudio = profile.getValue("DirectPlayProfiles").jsonArray.single().jsonObject.getValue("AudioCodec").jsonPrimitive.content.split(',')
        assertTrue(directAudio.containsAll(listOf("truehd", "dts", "dca", "pcm")))
        assertEquals(listOf("ts"), profile.getValue("TranscodingProfiles").jsonArray.map { it.jsonObject.getValue("Container").jsonPrimitive.content })
        assertTrue(profile.getValue("CodecProfiles").jsonArray.isEmpty())
        assertFalse(http.requests.single().text.contains("VideoRangeType"))
    }

    @Test
    fun transcodesToHevcWithDolbyAudioAndBurnsInPgs() = runTest {
        val http = TestHttp { """{"PlaySessionId": "ps1", "MediaSources": [{"Id": "ms1", "TranscodingUrl": "/videos/i1/master.m3u8"}]}""" }
        val jellyfin = JellyfinProvider(http.client, testIdentity)
        val target = ServerPlaybackTarget(ServerItemRef("cabc", "i1"), mediaSourceId = "ms1")

        jellyfin.preparePlayback(
            session("jellyfin", "https://media.example.com/jellyfin"),
            ServerPlaybackRequest(target, ServerPlayerCapabilities(allowDirectPlay = false), subtitleStreamIndex = 3)
        )

        val request = http.requests.single()
        assertEquals("3", request.url.queryParameter("subtitleStreamIndex"))
        assertTrue(request.text.contains("\"SubtitleStreamIndex\":3"))
        assertTrue(request.text.contains("\"VideoCodec\":\"hevc,h264\",\"AudioCodec\":\"ac3,eac3,aac,mp3\""))
        assertTrue(request.text.contains("\"MaxAudioChannels\":\"8\""))
        assertTrue(request.text.contains("{\"Format\":\"pgssub\",\"Method\":\"Encode\"}"))
    }

    @Test
    fun embyPreparesAndReportsPlaybackThroughApiRoot() = runTest {
        val http = TestHttp { request ->
            if (request.url.encodedPath.endsWith("/PlaybackInfo")) {
                """{"PlaySessionId": "ps1", "MediaSources": [{"Id": "ms1", "SupportsDirectPlay": true}]}"""
            } else {
                ""
            }
        }
        val emby = EmbyProvider(http.client, testIdentity)
        val session = session("emby", "https://media.example.com")
        val playback = emby.preparePlayback(
            session,
            ServerPlaybackRequest(
                target = ServerPlaybackTarget(ServerItemRef("cabc", "i1"), mediaSourceId = "ms1"),
                capabilities = ServerPlayerCapabilities(),
                audioStreamIndex = 2
            )
        )
        emby.report(session, playback, ServerPlaybackEvent(ServerPlaybackEventType.PROGRESS, positionMs = 1_500, isPaused = false))

        val info = http.requests[0]
        assertEquals("/emby/Items/i1/PlaybackInfo", info.url.encodedPath)
        assertEquals("u1", info.url.queryParameter("userId"))
        assertEquals("ms1", info.url.queryParameter("mediaSourceId"))
        assertEquals("2", info.url.queryParameter("audioStreamIndex"))
        assertTrue(info.text.contains("\"AudioStreamIndex\":2"))
        assertTrue(info.text.contains("{\"Format\":\"subrip\",\"Method\":\"Embed\"}"))
        assertTrue(info.text.contains("{\"Format\":\"subrip\",\"Method\":\"External\"}"))
        assertTrue(playback.url.startsWith("https://media.example.com/emby/Videos/i1/stream?static=true"))
        val report = http.requests[1]
        assertEquals("/emby/Sessions/Playing/Progress", report.url.encodedPath)
        assertTrue(report.text.contains("\"PositionTicks\":15000000"))
        assertTrue(report.text.contains("\"EventName\":\"TimeUpdate\""))
        assertTrue(report.text.contains("\"PlaySessionId\":\"ps1\""))
    }
}
