package com.nuvio.tv.data.mediaserver.plex

import com.nuvio.tv.data.mediaserver.ServerConnection
import com.nuvio.tv.data.mediaserver.ServerException
import com.nuvio.tv.data.mediaserver.ServerFailure
import com.nuvio.tv.data.mediaserver.ServerItemRef
import com.nuvio.tv.data.mediaserver.ServerLibrary
import com.nuvio.tv.data.mediaserver.ServerMediaKind
import com.nuvio.tv.data.mediaserver.ServerPlayMethod
import com.nuvio.tv.data.mediaserver.ServerPlaybackEvent
import com.nuvio.tv.data.mediaserver.ServerPlaybackEventType
import com.nuvio.tv.data.mediaserver.ServerPlaybackRequest
import com.nuvio.tv.data.mediaserver.ServerPlaybackTarget
import com.nuvio.tv.data.mediaserver.ServerPlayerCapabilities
import com.nuvio.tv.data.mediaserver.ServerSegmentKind
import com.nuvio.tv.data.mediaserver.ServerSession
import com.nuvio.tv.data.mediaserver.ServerSignInIssue
import com.nuvio.tv.data.mediaserver.ServerTitleQuery
import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.data.mediaserver.mediabrowser.TestHttp
import com.nuvio.tv.data.mediaserver.mediabrowser.testIdentity
import com.nuvio.tv.domain.model.ContentType
import kotlinx.coroutines.test.runTest
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PlexProviderTest {
    private val base = "https://192-168-10-24.abc123.plex.direct:32400"
    private val movies = ServerLibrary("1", "Movies", ServerMediaKind.MOVIE)
    private val shows = ServerLibrary("2", "TV Shows", ServerMediaKind.SERIES)
    private val credential = """{"account":"acct","user":"usert","server":"srvt","user_id":"7","user_uuid":"u7","user_name":"Alex"}"""

    private class Reply(val status: Int, val body: String)

    private fun plex(now: Long = 1_000L, routes: (Request) -> Reply?): Pair<PlexProvider, TestHttp> {
        val answered = java.util.IdentityHashMap<Request, Reply>()
        val reply = { request: Request -> synchronized(answered) { answered.getOrPut(request) { routes(request) ?: Reply(404, "") } } }
        val http = TestHttp(status = { reply(it).status }, responder = { reply(it).body })
        return PlexProvider(http.client, testIdentity, now = { now }) to http
    }

    private fun session(libraries: List<ServerLibrary> = listOf(movies, shows)) = ServerSession(
        ServerConnection(
            id = "c1",
            providerId = PlexProvider.ID,
            name = "HOME-PC",
            address = base,
            remoteServerId = "machine1",
            remoteUserId = "7",
            userName = "Alex",
            credentialRef = "k1",
            libraries = libraries
        ),
        credential,
        base
    )

    private fun container(body: String) = """{"MediaContainer":{$body}}"""

    private val movie = """{"ratingKey":"101","key":"/library/metadata/101","type":"movie","title":"The Matrix","year":1999,
        "summary":"A hacker.","thumb":"/library/metadata/101/thumb/5","art":"/library/metadata/101/art/5","audienceRating":8.66,
        "duration":8160000,"viewOffset":600000,"viewCount":0,"librarySectionID":1,
        "Guid":[{"id":"imdb://tt0133093"},{"id":"tmdb://603"},{"id":"tvdb://169"}],"Genre":[{"tag":"Science Fiction"}],
        "Image":[{"type":"clearLogo","url":"/library/metadata/101/clearLogo/5"}],
        "Media":[{"id":11,"duration":8160000,"bitrate":24000,"videoResolution":"4k","videoCodec":"hevc","videoDynamicRange":"Dolby Vision",
          "audioCodec":"truehd","audioChannels":8,"container":"mkv",
          "Part":[{"id":21,"key":"/library/parts/21/1700000000/file.mkv","file":"/media/Movies/The Matrix (1999).mkv","size":60000000000,"indexes":"sd",
            "Stream":[{"id":31,"streamType":1,"codec":"hevc"},
              {"id":32,"streamType":2,"codec":"truehd","languageCode":"eng","extendedDisplayTitle":"English (TRUEHD 7.1)","selected":true},
              {"id":33,"streamType":2,"codec":"ac3","languageCode":"fra","displayTitle":"French (AC3 5.1)"},
              {"id":34,"streamType":3,"codec":"srt","key":"/library/streams/34","languageCode":"eng","displayTitle":"English (SRT External)"},
              {"id":35,"streamType":3,"codec":"pgs","languageCode":"eng","displayTitle":"English (PGS)"}]}]}],
        "Marker":[{"type":"intro","startTimeOffset":1000,"endTimeOffset":61000},{"type":"credits","startTimeOffset":7800000,"endTimeOffset":8160000},
          {"type":"commercial","startTimeOffset":5,"endTimeOffset":6}],
        "Chapter":[{"tag":"Opening","startTimeOffset":0},{"startTimeOffset":600000}]}"""

    @Test
    fun theLinkCodeIsShortAndA404MeansStillWaitingUntilTheCodeRunsOut() = runTest {
        var checks = 0
        val (provider, http) = plex { request ->
            when {
                request.method == "POST" && request.url.encodedPath == "/api/v2/pins" ->
                    Reply(201, """{"id":42,"code":"K7QX","expiresIn":900,"authToken":null}""")
                request.url.encodedPath == "/api/v2/pins/42" -> {
                    checks++
                    when (checks) {
                        1 -> Reply(404, "")
                        2 -> Reply(200, """{"id":42,"code":"K7QX","authToken":null}""")
                        else -> Reply(200, """{"id":42,"code":"K7QX","authToken":"tok"}""")
                    }
                }
                else -> null
            }
        }

        val pin = provider.account.startPin()

        assertEquals("K7QX", pin.code)
        assertEquals(901_000L, pin.expiresAtMs)
        assertNull(http.requests.first().url.queryParameter("strong"))
        assertEquals("device-1", http.requests.first().header("X-Plex-Client-Identifier"))
        assertEquals("Nuvio", http.requests.first().header("X-Plex-Product"))
        assertEquals(PlexPinStatus.Waiting, provider.account.checkPin(pin))
        assertEquals(PlexPinStatus.Waiting, provider.account.checkPin(pin))
        val linked = provider.account.checkPin(pin)
        assertTrue(linked is PlexPinStatus.Linked)
        assertEquals("tok", (linked as PlexPinStatus.Linked).accountToken)
    }

    @Test
    fun anExpiredCodeEndsTheWait() = runTest {
        val (provider, _) = plex(now = 2_000_000L) { request ->
            if (request.url.encodedPath == "/api/v2/pins/42") Reply(404, "") else null
        }
        assertEquals(PlexPinStatus.Expired, provider.account.checkPin(PlexPin(42, "K7QX", 1_000L)))
    }

    @Test
    fun serversComeWithTheirOwnTokensAndOnlyServersCount() = runTest {
        val (provider, http) = plex { request ->
            if (request.url.encodedPath != "/api/v2/resources") return@plex null
            Reply(200, """[
                {"name":"HOME-PC","provides":"server","clientIdentifier":"machine1","owned":true,"accessToken":"owned-token",
                 "connections":[{"protocol":"https","uri":"$base","local":true},{"protocol":"https","uri":"https://1-2-3-4.abc123.plex.direct:32400","local":false}]},
                {"name":"Dave's Plex","provides":"server","clientIdentifier":"machine2","owned":false,"accessToken":"shared-token","connections":[]},
                {"name":"No token","provides":"server","clientIdentifier":"machine3","owned":false,"connections":[]},
                {"name":"Phone","provides":"client,player","clientIdentifier":"phone1"}]""")
        }

        val servers = provider.account.servers("acct")

        assertEquals(listOf("machine1", "machine2"), servers.map { it.machineId })
        assertEquals("owned-token", servers[0].accessToken)
        assertEquals("shared-token", servers[1].accessToken)
        assertEquals(2, servers[0].addresses.size)
        assertEquals("acct", http.requests.single().header("X-Plex-Token"))
        assertEquals("1", http.requests.single().url.queryParameter("includeHttps"))
        assertEquals("1", http.requests.single().url.queryParameter("includeRelay"))
    }

    @Test
    fun theHomeNetworkWinsOverTheInternetAndTheInternetBecomesTheSecondAddress() = runTest {
        val (provider, _) = plex { null }
        val remote = "https://1-2-3-4.abc123.plex.direct:32400"
        val relay = "https://5-6-7-8.abc123.plex.direct:8443"
        val server = PlexServer(
            "machine1", "HOME-PC", true, "t",
            listOf(PlexAddress(relay, local = false, relay = true), PlexAddress(remote, local = false, relay = false), PlexAddress(base, local = true, relay = false))
        )

        val reached = provider.account.reach(server) { "machine1" }
        val onlyRelay = provider.account.reach(server) { uri -> if (uri == relay) "machine1" else null }
        val otherServer = provider.account.reach(server) { "someone-else" }

        assertEquals(PlexReach.LOCAL, reached.reach)
        assertEquals(base, reached.address)
        assertEquals(remote, reached.secondAddress)
        assertEquals(PlexReach.RELAY, onlyRelay.reach)
        assertEquals(PlexReach.OFFLINE, otherServer.reach)
        assertNull(otherServer.address)
    }

    @Test
    fun aHomeConnectionIsAlsoTriedByItsPlainAddress() = runTest {
        val (provider, _) = plex { request ->
            if (request.url.encodedPath != "/api/v2/resources") return@plex null
            Reply(200, """[{"name":"HOME-PC","provides":"server","clientIdentifier":"machine1","owned":true,"accessToken":"t",
                "connections":[{"protocol":"https","address":"192.168.1.24","port":32400,"uri":"$base","local":true},
                {"protocol":"https","address":"1.2.3.4","port":29163,"uri":"https://1-2-3-4.abc123.plex.direct:29163","local":false}]}]""")
        }

        val server = provider.account.servers("acct").single()
        val reached = provider.account.reach(server) { uri -> if (uri.startsWith("http://")) "machine1" else null }

        assertEquals(listOf(base, "http://192.168.1.24:32400", "https://1-2-3-4.abc123.plex.direct:29163"), server.addresses.map { it.uri })
        assertEquals(PlexReach.LOCAL, reached.reach)
        assertEquals("http://192.168.1.24:32400", reached.address)
        assertEquals("https://1-2-3-4.abc123.plex.direct:29163", reached.secondAddress)
    }

    @Test
    fun anUnencryptedInternetAddressIsNeverTried() = runTest {
        val (provider, _) = plex { null }
        val tried = mutableListOf<String>()
        val server = PlexServer(
            "machine1", "HOME-PC", true, "t",
            listOf(PlexAddress("http://1.2.3.4:32400", local = false, relay = false), PlexAddress("http://192.168.1.24:32400", local = true, relay = false))
        )

        val reached = provider.account.reach(server) { uri -> tried += uri; "machine1" }

        assertEquals(listOf("http://192.168.1.24:32400"), tried)
        assertEquals(PlexReach.LOCAL, reached.reach)
    }

    @Test
    fun anAddressWithACertificateProblemIsJustNotReachable() = runTest {
        val (provider, _) = plex { null }
        val remote = "https://plex.example.com"
        val server = PlexServer("machine1", "HOME-PC", true, "t", listOf(PlexAddress(remote, false, false), PlexAddress(base, true, false)))

        val reached = provider.account.reach(server) { uri ->
            if (uri == remote) throw ServerException(ServerFailure.CERTIFICATE) else "machine1"
        }

        assertEquals(PlexReach.LOCAL, reached.reach)
        assertEquals(base, reached.address)
    }

    @Test
    fun theAccountsOwnUserWithAPinIsCheckedByPlex() = runTest {
        val (provider, http) = plex { request ->
            if (request.url.encodedPath == "/api/v2/home/users/u7/switch") {
                if (request.url.queryParameter("pin") == "4321") Reply(201, """{"id":7,"uuid":"u7","title":"Alex","authToken":"fresh"}""") else Reply(403, "")
            } else {
                null
            }
        }
        val owner = PlexAccount("acct", "7", "u7", "Alex")
        val me = PlexHomeUser("7", "u7", "Alex", hasPin = true, admin = true, restricted = false)

        assertNull(provider.account.switchUser(owner, me, "0000"))
        assertEquals("fresh", provider.account.switchUser(owner, me, "4321")?.token)
        assertEquals(2, http.requests.size)
    }

    @Test
    fun aFailedHomeUserListIsAFailureNotASingleUser() = runTest {
        val (provider, _) = plex { request -> if (request.url.encodedPath == "/api/v2/home/users") Reply(503, "") else null }
        try {
            provider.account.homeUsers(PlexAccount("acct", "7", "u7", "Alex"))
            fail("expected a failure")
        } catch (error: ServerException) {
            assertEquals(ServerFailure.UNREACHABLE, error.failure)
        }
    }

    @Test
    fun withoutPlexHomeTheAccountIsTheOnlyUser() = runTest {
        val (provider, _) = plex { request -> if (request.url.encodedPath == "/api/v2/home/users") Reply(404, "") else null }
        val users = provider.account.homeUsers(PlexAccount("acct", "7", "u7", "Alex"))
        assertEquals(listOf("u7"), users.map { it.uuid })
        assertFalse(users.single().hasPin)
    }

    @Test
    fun homeUsersShowTheirPinAndAWrongPinIsNotAnError() = runTest {
        val (provider, http) = plex { request ->
            when (request.url.encodedPath) {
                "/api/v2/home/users" -> Reply(200, """{"users":[{"id":7,"uuid":"u7","title":"Alex","admin":true},
                    {"id":8,"uuid":"u8","title":"Sam","protected":true},{"id":9,"uuid":"u9","title":"Kids","restricted":true}]}""")
                "/api/v2/home/users/u8/switch" ->
                    if (request.url.queryParameter("pin") == "1234") Reply(201, """{"id":8,"uuid":"u8","title":"Sam","authToken":"sam-token"}""")
                    else Reply(403, "")
                else -> null
            }
        }
        val owner = PlexAccount("acct", "7", "u7", "Alex")

        val users = provider.account.homeUsers(owner)
        val sam = users.first { it.uuid == "u8" }

        assertEquals(listOf(false, true, false), users.map { it.hasPin })
        assertTrue(users.first().admin)
        assertTrue(users.last().restricted)
        assertNull(provider.account.switchUser(owner, sam, "0000"))
        assertEquals("sam-token", provider.account.switchUser(owner, sam, "1234")?.token)
        assertEquals("acct", http.requests.last().header("X-Plex-Token"))
        assertEquals(owner, provider.account.switchUser(owner, users.first(), null))
    }

    @Test
    fun aSignInKeepsTheServerTokenAndTheAccountForUserChanges() = runTest {
        val (provider, _) = plex { null }
        val owner = PlexAccount("acct", "7", "u7", "Alex")
        val sam = PlexAccount("sam-token", "8", "u8", "Sam")
        val server = PlexServer("machine1", "HOME-PC", true, "srv-token", emptyList()).reached(PlexReach.LOCAL, base, null)

        val signIn = provider.signIn(server, owner, sam)
        val stored = provider.credentialOrNull(signIn.token)!!

        assertEquals("machine1", signIn.serverId)
        assertEquals("7", signIn.userId)
        assertEquals("Alex", signIn.userName)
        assertEquals("Sam", stored.userName)
        assertEquals(base, signIn.address)
        assertEquals("acct", stored.accountToken)
        assertEquals("sam-token", stored.userToken)
        assertEquals("srv-token", stored.serverToken)
        assertTrue(stored.homeUser)
        assertFalse(signIn.toString().contains("token"))
    }

    @Test
    fun movieAndShowLibrariesOnly() = runTest {
        val (provider, http) = plex { request ->
            if (request.url.encodedPath != "/library/sections") return@plex null
            Reply(200, container(""""Directory":[{"key":"1","title":"Movies","type":"movie"},{"key":"2","title":"TV Shows","type":"show"},
                {"key":"3","title":"Music","type":"artist"},{"key":"4","title":"Photos","type":"photo"}]"""))
        }

        val libraries = provider.libraries(session())

        assertEquals(listOf(movies, shows), libraries)
        assertEquals("srvt", http.requests.single().header("X-Plex-Token"))
    }

    @Test
    fun aLibraryPageMapsTitlesIdsAndSignedArtwork() = runTest {
        val (provider, http) = plex { request ->
            if (request.url.encodedPath != "/library/sections/1/all") return@plex null
            Reply(200, container(""""size":1,"totalSize":250,"Metadata":[$movie]"""))
        }

        val page = provider.libraryPage(session(), movies, 50, 25)
        val title = page.items.single()
        val request = http.requests.single()

        assertEquals(250, page.totalCount)
        assertEquals("srv1:c1:101", title.preview.id)
        assertEquals(ContentType.MOVIE, title.preview.type)
        assertEquals(8.7f, title.preview.imdbRating)
        assertEquals("tt0133093", title.externalIds.imdb)
        assertEquals(603L, title.externalIds.tmdb)
        assertEquals("169", title.externalIds.tvdb)
        assertEquals("1", request.url.queryParameter("type"))
        assertEquals("50", request.url.queryParameter("X-Plex-Container-Start"))
        assertEquals("25", request.url.queryParameter("X-Plex-Container-Size"))
        assertEquals("addedAt:desc", request.url.queryParameter("sort"))
        val poster = title.preview.poster!!
        assertTrue(poster.startsWith("$base/photo/:/transcode?width=400&height=600"))
        assertTrue(poster.endsWith("#nuvio-plex=c1"))
        assertFalse(poster.contains("srvt"))
        assertTrue(title.preview.logo!!.contains("clearLogo"))
    }

    @Test
    fun searchAsksEachLibraryWithTheTypedTitle() = runTest {
        val (provider, http) = plex { request ->
            when (request.url.encodedPath) {
                "/library/sections/1/all" -> Reply(200, container(""""Metadata":[$movie]"""))
                "/library/sections/9/all" -> Reply(200, container(""""Metadata":[$movie]"""))
                else -> null
            }
        }
        val other = ServerLibrary("9", "4K Movies", ServerMediaKind.MOVIE)

        val found = provider.search(session(), ServerMediaKind.MOVIE, listOf(movies, other), "matrix", 20)

        assertEquals(1, found.size)
        assertEquals(setOf("matrix"), http.requests.map { it.url.queryParameter("title") }.toSet())
        assertEquals(2, http.requests.size)
    }

    @Test
    fun aShowsDetailsListEveryEpisodeWithItsState() = runTest {
        val (provider, _) = plex { request ->
            when (request.url.encodedPath) {
                "/library/metadata/200" -> Reply(200, container(""""Metadata":[{"ratingKey":"200","type":"show","title":"Rick and Morty","Guid":[{"id":"tvdb://275274"}]}]"""))
                "/library/metadata/200/allLeaves" -> Reply(200, container(""""Metadata":[
                    {"ratingKey":"201","type":"episode","title":"Pilot","parentIndex":1,"index":1,"duration":1320000,"viewCount":1},
                    {"ratingKey":"202","type":"episode","title":"Lawnmower Dog","parentIndex":1,"index":2,"duration":1320000,"viewOffset":300000,"lastViewedAt":1700000000}]"""))
                else -> null
            }
        }

        val details = provider.details(session(), "200")

        assertEquals(ContentType.SERIES, details.meta.type)
        assertEquals("275274", details.externalIds.tvdb)
        assertEquals(listOf(1 to 1, 1 to 2), details.meta.videos.map { it.season to it.episode })
        assertEquals(22, details.meta.videos.first().runtime)
        val (pilot, dog) = details.userStates
        assertTrue(pilot.played)
        assertFalse(dog.played)
        assertEquals(300_000L, dog.positionMs)
        assertEquals(1_700_000_000_000L, dog.lastPlayedEpochMs)
        assertEquals(ServerEpisodeKey(1, 2), ServerEpisodeKey(dog.season!!, dog.episode!!))
        assertEquals("202", provider.findEpisode(session(), "200", 1, 2)?.itemId)
        assertNull(provider.findEpisode(session(), "200", 3, 1))
    }

    private data class ServerEpisodeKey(val season: Int, val episode: Int)

    @Test
    fun continueWatchingSkipsTheNextUnstartedEpisodeAndShowsEpisodesAsTheirShow() = runTest {
        val (provider, _) = plex { request ->
            when (request.url.encodedPath) {
                "/hubs/continueWatching/items" -> Reply(200, container(""""Metadata":[
                    $movie,
                    {"ratingKey":"202","type":"episode","title":"Lawnmower Dog","grandparentRatingKey":"200","grandparentTitle":"Rick and Morty",
                     "grandparentThumb":"/library/metadata/200/thumb/1","parentIndex":1,"index":2,"viewOffset":300000,"duration":1320000,"librarySectionID":2},
                    {"ratingKey":"203","type":"episode","title":"Anatomy Park","grandparentRatingKey":"200","parentIndex":1,"index":3,"duration":1320000,"librarySectionID":2},
                    {"ratingKey":"301","type":"movie","title":"Other library","viewOffset":5,"librarySectionID":77}]"""))
                else -> null
            }
        }

        val entries = provider.resumeItems(session(), 10)

        assertEquals(listOf("srv1:c1:101", "srv1:c1:200"), entries.map { it.title.preview.id })
        assertEquals("Rick and Morty", entries[1].title.preview.name)
        assertEquals(2, entries[1].state.episode)
        assertTrue(entries.none { it.state.played })
    }

    @Test
    fun olderServersFallBackToOnDeck() = runTest {
        val (provider, http) = plex { request ->
            when (request.url.encodedPath) {
                "/library/onDeck" -> Reply(200, container(""""Metadata":[$movie]"""))
                else -> null
            }
        }
        assertEquals(1, provider.resumeItems(session(), 10).size)
        assertEquals(listOf("/hubs/continueWatching/items", "/library/onDeck"), http.requests.map { it.url.encodedPath })
    }

    @Test
    fun titleLookupSearchesByName() = runTest {
        val (provider, http) = plex { request ->
            if (request.url.encodedPath == "/library/sections/1/all") Reply(200, container(""""Metadata":[$movie]""")) else null
        }

        val found = requireNotNull(provider.lookupTitle(session(), movies, ServerTitleQuery(TrackingExternalIds(imdb = "tt0133093"), "The Matrix", 1999)))

        assertEquals(listOf("101"), found.map { it.itemId })
        assertEquals("tt0133093", found.single().ids.imdb)
        assertEquals("The Matrix", http.requests.single().url.queryParameter("title"))
    }

    @Test
    fun oneCandidatePerVersionWithItsFileDetails() = runTest {
        val (provider, _) = plex { request ->
            if (request.url.encodedPath == "/library/metadata/101") Reply(200, container(""""Metadata":[$movie]""")) else null
        }

        val candidate = provider.candidates(session(), "101").single()

        assertEquals(ServerPlaybackTarget(ServerItemRef("c1", "101"), "21"), candidate.target)
        assertEquals("4K", candidate.title)
        assertEquals("The Matrix (1999).mkv", candidate.filename)
        assertEquals(24_000_000L, candidate.bitrateBps)
        assertEquals("HEVC Dolby Vision", candidate.video)
        assertEquals("TRUEHD 7.1", candidate.audio)
        assertEquals("MKV", candidate.container)
    }

    @Test
    fun directPlayReadsTheFileWithTheTokenInAHeaderAndOffersExternalSubtitles() = runTest {
        val (provider, http) = plex { request ->
            when (request.url.encodedPath) {
                "/library/metadata/101" -> Reply(200, container(""""Metadata":[$movie]"""))
                "/library/parts/21/1700000000/file.mkv" -> Reply(206, "x")
                else -> null
            }
        }
        val request = ServerPlaybackRequest(ServerPlaybackTarget(ServerItemRef("c1", "101"), "21"), ServerPlayerCapabilities())

        val playback = provider.preparePlayback(session(), request)

        assertEquals(ServerPlayMethod.DIRECT_PLAY, playback.playMethod)
        assertEquals("$base/library/parts/21/1700000000/file.mkv", playback.url)
        assertFalse(playback.url.contains("srvt"))
        assertEquals("srvt", playback.headers["X-Plex-Token"])
        assertEquals("device-1", playback.headers["X-Plex-Client-Identifier"])
        val subtitle = playback.subtitles.single()
        assertEquals("$base/library/streams/34", subtitle.url)
        assertEquals("eng", subtitle.lang)
        assertEquals("srvt", subtitle.headers?.get("X-Plex-Token"))
        assertEquals(listOf(32, 33), playback.audioTracks.map { it.index })
        assertTrue(playback.audioTracks.first().selected)
        assertTrue(playback.transcodeOffered)
        val probe = http.requests.last()
        assertEquals("bytes=0-0", probe.header("Range"))
    }

    @Test
    fun aRefusedFileAwayFromHomeSaysAPassIsNeeded() = runTest {
        val (provider, _) = plex { request ->
            when (request.url.encodedPath) {
                "/library/metadata/101" -> Reply(200, container(""""Metadata":[$movie]"""))
                "/library/parts/21/1700000000/file.mkv" -> Reply(403, "")
                else -> null
            }
        }
        val request = ServerPlaybackRequest(ServerPlaybackTarget(ServerItemRef("c1", "101"), "21"), ServerPlayerCapabilities())

        try {
            provider.preparePlayback(session(), request)
            fail("expected a refusal")
        } catch (error: ServerException) {
            assertEquals(ServerFailure.FORBIDDEN, error.failure)
            assertEquals(ServerSignInIssue.PLEX_REMOTE_PASS, error.issue)
        }
    }

    @Test
    fun aConversionAsksForADecisionAndPicksTheChosenTracksFirst() = runTest {
        val (provider, http) = plex { request ->
            when (request.url.encodedPath) {
                "/library/metadata/101" -> Reply(200, container(""""Metadata":[$movie]"""))
                "/library/parts/21" -> Reply(200, "")
                "/video/:/transcode/universal/decision" -> Reply(200, container(""""generalDecisionCode":1000,"transcodeDecisionCode":1001,"transcodeDecisionText":"Transcode video""""))
                else -> null
            }
        }
        val request = ServerPlaybackRequest(
            ServerPlaybackTarget(ServerItemRef("c1", "101"), "21"),
            ServerPlayerCapabilities(allowDirectPlay = false),
            audioStreamIndex = 33,
            subtitleStreamIndex = 35
        )

        val playback = provider.preparePlayback(session(), request)

        val choose = http.requests.first { it.url.encodedPath == "/library/parts/21" }
        assertEquals("PUT", choose.method)
        assertEquals("33", choose.url.queryParameter("audioStreamID"))
        assertEquals("35", choose.url.queryParameter("subtitleStreamID"))
        val decision = http.requests.first { it.url.encodedPath == "/video/:/transcode/universal/decision" }
        assertEquals("/library/metadata/101", decision.url.queryParameter("path"))
        assertEquals("hls", decision.url.queryParameter("protocol"))
        assertEquals("burn", decision.url.queryParameter("subtitles"))
        assertNotNull(decision.header("X-Plex-Client-Profile-Extra"))
        assertEquals(ServerPlayMethod.TRANSCODE, playback.playMethod)
        assertTrue(playback.url.startsWith("$base/video/:/transcode/universal/start.m3u8?"))
        assertEquals(decision.url.queryParameter("session"), playback.playSessionId)
        assertEquals(listOf("Transcode video"), playback.transcodeReasons)
        assertTrue(playback.audioTracks.first { it.index == 33 }.selected)
        assertTrue(playback.burnInSubtitles.first { it.index == 35 }.selected)
    }

    @Test
    fun aDecisionPlexCannotMeetIsUnsupported() = runTest {
        val (provider, _) = plex { request ->
            when (request.url.encodedPath) {
                "/library/metadata/101" -> Reply(200, container(""""Metadata":[$movie]"""))
                "/video/:/transcode/universal/decision" -> Reply(200, container(""""generalDecisionCode":2000,"generalDecisionText":"Transcoder not allowed""""))
                else -> null
            }
        }
        val request = ServerPlaybackRequest(ServerPlaybackTarget(ServerItemRef("c1", "101"), "21"), ServerPlayerCapabilities(allowDirectPlay = false))
        try {
            provider.preparePlayback(session(), request)
            fail("expected a refusal")
        } catch (error: ServerException) {
            assertEquals(ServerFailure.UNSUPPORTED, error.failure)
            assertEquals("Transcoder not allowed", error.detail)
        }
    }

    @Test
    fun progressGoesToTheTimelineAndAStoppedConversionIsEnded() = runTest {
        val (provider, http) = plex { request ->
            when (request.url.encodedPath) {
                "/library/metadata/101" -> Reply(200, container(""""Metadata":[$movie]"""))
                "/video/:/transcode/universal/decision" -> Reply(200, container(""""generalDecisionCode":1000"""))
                "/:/timeline", "/video/:/transcode/universal/stop" -> Reply(200, "")
                else -> null
            }
        }
        val request = ServerPlaybackRequest(ServerPlaybackTarget(ServerItemRef("c1", "101"), "21"), ServerPlayerCapabilities(allowDirectPlay = false))
        val playback = provider.preparePlayback(session(), request)

        provider.report(session(), playback, ServerPlaybackEvent(ServerPlaybackEventType.PROGRESS, 61_000L, isPaused = false))
        provider.report(session(), playback, ServerPlaybackEvent(ServerPlaybackEventType.PAUSE, 62_000L, isPaused = true))
        provider.report(session(), playback, ServerPlaybackEvent(ServerPlaybackEventType.STOP, 63_000L, isPaused = false))
        provider.report(session(), playback, ServerPlaybackEvent(ServerPlaybackEventType.PROGRESS, 64_000L, isPaused = false))

        val timeline = http.requests.filter { it.url.encodedPath == "/:/timeline" }
        assertEquals(listOf("playing", "paused", "stopped"), timeline.map { it.url.queryParameter("state") })
        assertEquals("61000", timeline.first().url.queryParameter("time"))
        assertEquals("8160000", timeline.first().url.queryParameter("duration"))
        assertEquals("101", timeline.first().url.queryParameter("ratingKey"))
        assertEquals(playback.playSessionId, timeline.first().header("X-Plex-Session-Identifier"))
        assertEquals(1, http.requests.count { it.url.encodedPath == "/video/:/transcode/universal/stop" })
    }

    @Test
    fun marksWatchedButNotTwiceRightAfterAPlay() = runTest {
        val (provider, http) = plex { request ->
            when (request.url.encodedPath) {
                "/:/scrobble", "/:/unscrobble", "/:/timeline", "/library/parts/21/1700000000/file.mkv" -> Reply(200, "")
                "/library/metadata/101" -> Reply(200, container(""""Metadata":[$movie]"""))
                else -> null
            }
        }

        provider.setPlayed(session(), "101", played = true)
        provider.setPlayed(session(), "101", played = false)
        val playback = provider.preparePlayback(
            session(),
            ServerPlaybackRequest(ServerPlaybackTarget(ServerItemRef("c1", "101"), "21"), ServerPlayerCapabilities())
        )
        provider.report(session(), playback, ServerPlaybackEvent(ServerPlaybackEventType.STOP, 8_000_000L, isPaused = false))
        provider.setPlayed(session(), "101", played = true)

        val marks = http.requests.filter { it.url.encodedPath.startsWith("/:/") && it.url.encodedPath != "/:/timeline" }
        assertEquals(listOf("/:/scrobble", "/:/unscrobble"), marks.map { it.url.encodedPath })
        assertEquals("101", marks.first().url.queryParameter("key"))
        assertEquals("com.plexapp.plugins.library", marks.first().url.queryParameter("identifier"))
    }

    @Test
    fun titleLookupWithoutANameLeavesItToTheIndex() = runTest {
        val (provider, http) = plex { null }

        assertNull(provider.lookupTitle(session(), movies, ServerTitleQuery(TrackingExternalIds(imdb = "tt0133093"), null, 1999)))
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun playbackThatNeverStartedDoesNotBlockMarkWatched() = runTest {
        val (provider, http) = plex { request ->
            when (request.url.encodedPath) {
                "/library/metadata/101" -> Reply(200, container(""""Metadata":[$movie]"""))
                "/video/:/transcode/universal/decision" -> Reply(200, container(""""generalDecisionCode":1000"""))
                "/:/scrobble", "/:/timeline", "/video/:/transcode/universal/stop" -> Reply(200, "")
                else -> null
            }
        }
        val request = ServerPlaybackRequest(ServerPlaybackTarget(ServerItemRef("c1", "101"), "21"), ServerPlayerCapabilities(allowDirectPlay = false))
        val abandoned = provider.preparePlayback(session(), request)
        val playing = provider.preparePlayback(session(), request)

        provider.setPlayed(session(), "101", played = true)
        provider.report(session(), playing, ServerPlaybackEvent(ServerPlaybackEventType.START, 0L, isPaused = false))
        provider.setPlayed(session(), "101", played = true)

        val stops = http.requests.filter { it.url.encodedPath == "/video/:/transcode/universal/stop" }
        assertEquals(listOf(abandoned.playSessionId), stops.map { it.url.queryParameter("session") })
        assertEquals(1, http.requests.count { it.url.encodedPath == "/:/scrobble" })
    }

    @Test
    fun markWatchedAfterAPartialPlayIsSent() = runTest {
        val (provider, http) = plex { request ->
            when (request.url.encodedPath) {
                "/:/scrobble", "/:/timeline", "/library/parts/21/1700000000/file.mkv" -> Reply(200, "")
                "/library/metadata/101" -> Reply(200, container(""""Metadata":[$movie]"""))
                else -> null
            }
        }
        val playback = provider.preparePlayback(
            session(),
            ServerPlaybackRequest(ServerPlaybackTarget(ServerItemRef("c1", "101"), "21"), ServerPlayerCapabilities())
        )
        provider.report(session(), playback, ServerPlaybackEvent(ServerPlaybackEventType.START, 0L, isPaused = false))
        provider.report(session(), playback, ServerPlaybackEvent(ServerPlaybackEventType.STOP, 600_000L, isPaused = false))
        provider.setPlayed(session(), "101", played = true)

        assertEquals(1, http.requests.count { it.url.encodedPath == "/:/scrobble" })
    }

    @Test
    fun introAndCreditsMarkersAndChapters() = runTest {
        val (provider, _) = plex { request ->
            if (request.url.encodedPath == "/library/metadata/101") Reply(200, container(""""Metadata":[$movie]""")) else null
        }

        val segments = provider.segments(session(), "101", "21")
        val chapters = provider.chapters(session(), "101", "21")

        assertEquals(listOf(ServerSegmentKind.INTRO, ServerSegmentKind.OUTRO), segments.map { it.kind })
        assertEquals(1_000L, segments.first().startMs)
        assertEquals(listOf(0L, 600_000L), chapters.map { it.startMs })
        assertEquals(listOf("Opening", null), chapters.map { it.name })
    }

    @Test
    fun thumbnailsComeFromThePartsBifFile() = runTest {
        val (provider, http) = plex { request ->
            if (request.url.encodedPath == "/library/parts/21/indexes/sd") Reply(206, "BIF") else null
        }

        val bytes = provider.bif(session(), "101", "21", 320, 0L..15L, 1_000L)

        assertTrue(bytes.partial)
        assertEquals("srvt", http.requests.single().url.queryParameter("X-Plex-Token"))
        assertEquals("bytes=0-15", http.requests.single().header("Range"))
    }

    @Test
    fun plexHubsBecomeHomeRowsWithoutContinueWatching() = runTest {
        val (provider, _) = plex { request ->
            when (request.url.encodedPath) {
                "/hubs" -> Reply(200, container(""""Hub":[
                    {"key":"/hubs/home/continueWatching","title":"Continue Watching","type":"mixed","hubIdentifier":"home.continue"},
                    {"key":"/hubs/home/onDeck","title":"On Deck","type":"episode","hubIdentifier":"home.ondeck"},
                    {"key":"/hubs/home/recentlyAdded?type=1","title":"Recently Added Movies","type":"movie","hubIdentifier":"home.movies.recent"},
                    {"key":"/hubs/home/recentlyAdded?type=2","title":"Recently Added TV","type":"mixed","hubIdentifier":"home.television.recent"},
                    {"key":"/hubs/home/recentlyAdded?type=1&personal","title":"Recently Added Videos","type":"clip","hubIdentifier":"home.videos.recent"},
                    {"key":"/hubs/home/music","title":"Music","type":"artist","hubIdentifier":"home.music"}]"""))
                "/hubs/home/recentlyAdded" -> Reply(200, container(""""Metadata":[$movie]"""))
                else -> null
            }
        }

        val rows = provider.homeRows(session())
        val items = provider.homeRowItems(session(), rows.first().id)

        assertEquals(listOf("Recently Added Movies", "Recently Added TV"), rows.map { it.title })
        assertEquals(listOf(ServerMediaKind.MOVIE, ServerMediaKind.SERIES), rows.map { it.kind })
        assertEquals("srv1:c1:101", items.single().preview.id)
    }

    @Test
    fun locateFindsTheServersMachineId() = runTest {
        val (provider, http) = plex { request ->
            if (request.url.encodedPath == "/identity") Reply(200, container(""""machineIdentifier":"machine1","version":"1.43"""")) else null
        }

        val location = provider.locate("$base/")

        assertEquals("machine1", location?.serverId)
        assertEquals(base, location?.address)
        assertNull(http.requests.single().header("X-Plex-Token"))
    }

    @Test
    fun theStaticLinkUsesThePartId() = runTest {
        val (provider, _) = plex { null }
        val url = provider.staticStreamUrl(session(), ServerPlaybackTarget(ServerItemRef("c1", "101"), "21"))
        assertEquals("$base/library/parts/21/file?X-Plex-Token=srvt", url)
    }
}
