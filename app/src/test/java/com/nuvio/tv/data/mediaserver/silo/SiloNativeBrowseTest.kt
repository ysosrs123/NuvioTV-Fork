package com.nuvio.tv.data.mediaserver.silo

import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.data.mediaserver.DrawnArtworkWidths
import com.nuvio.tv.data.mediaserver.ServerConnection
import com.nuvio.tv.data.mediaserver.ServerHomeRow
import com.nuvio.tv.data.mediaserver.ServerLibrary
import com.nuvio.tv.data.mediaserver.ServerMediaKind
import com.nuvio.tv.data.mediaserver.ServerSession
import com.nuvio.tv.data.mediaserver.ServerTitleQuery
import com.nuvio.tv.data.mediaserver.mediabrowser.TestHttp
import com.nuvio.tv.data.mediaserver.mediabrowser.testIdentity
import com.nuvio.tv.domain.model.ContentType
import kotlinx.coroutines.test.runTest
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SiloNativeBrowseTest {
    private val base = "http://silo.local:8090"
    private val movies = ServerLibrary("1", "Films", ServerMediaKind.MOVIE)
    private val shows = ServerLibrary("2", "Shows", ServerMediaKind.SERIES)
    private val credential = """{"access":"acc","refresh":"ref","expires_at_ms":${Long.MAX_VALUE},"profile_id":"p1","profile_name":"Laura"}"""

    private class Reply(val status: Int, val body: String)

    private val notFound = Reply(404, """{"type":"https://siloserver.org/docs/api/v2/problems/not_found","title":"x","status":404,"detail":"","instance":"i"}""")

    private fun silo(widths: DrawnArtworkWidths? = null, routes: (Request) -> Reply?): Pair<SiloNativeProvider, TestHttp> {
        val reply = { request: Request -> routes(request) ?: notFound }
        val http = TestHttp(status = { reply(it).status }, responder = { reply(it).body })
        return SiloNativeProvider(http.client, testIdentity, now = { 1_000L }, drawnWidths = { widths }) to http
    }

    private fun session(libraries: List<ServerLibrary> = listOf(movies, shows)) = ServerSession(
        ServerConnection(
            id = "c1",
            providerId = SiloNativeProvider.ID,
            name = "Silo",
            address = base,
            remoteServerId = "srv",
            remoteUserId = "1",
            userName = "laura",
            credentialRef = "k1",
            libraries = libraries
        ),
        credential,
        base
    )

    private fun card(id: String, title: String, type: String = "movie", extra: String = "") =
        """{"content_id":"$id","type":"$type","title":"$title","year":1999,"genres":["Sci-Fi"],"keywords":[],"status":"matched",
           "poster_url":"/api/v2/artwork/tmdb/movies/603/poster/w500.r1.webp?exp=1&sig=a","rating_imdb":8.66$extra}"""

    @Test
    fun aLibraryPageCarriesTheProfileAndFollowsSilosCursors() = runTest {
        val (provider, http) = silo { request ->
            if (request.url.encodedPath != "/api/v2/catalog") return@silo null
            when (request.url.queryParameter("cursor")) {
                null -> Reply(200, """{"items":[${card("movie-tmdb-603", "The Matrix")}],"page":{"has_more":true,"next_cursor":"c2"},
                    "total":3,"total_exact":true,"window_cursor":"w1"}""")
                "c2" -> Reply(200, """{"items":[${card("movie-imdb-tt0133093", "The Matrix Reloaded")}],"page":{"has_more":true,"next_cursor":"c3"},
                    "total":3,"total_exact":true,"window_cursor":"w1"}""")
                "w1" -> Reply(200, """{"items":[${card("local-abc", "Home Video")}],"page":{"has_more":false},"total":3,"total_exact":true,"window_cursor":"w1"}""")
                else -> null
            }
        }

        val first = provider.libraryPage(session(), movies, 0, 1)
        val second = provider.libraryPage(session(), movies, 1, 1)
        val jumped = provider.libraryPage(session(), movies, 7, 1)

        val preview = first.items.single().preview
        assertEquals(3, first.totalCount)
        assertEquals("srv1:c1:movie-tmdb-603", preview.id)
        assertEquals(ContentType.MOVIE, preview.type)
        assertEquals("http://silo.local:8090/api/v2/artwork/tmdb/movies/603/poster/w500.r1.webp?exp=1&sig=a#nuvio-silo=c1.movie-tmdb-603.poster", preview.poster)
        assertEquals(8.7f, preview.imdbRating)
        assertEquals(603L, first.items.single().externalIds.tmdb)
        assertEquals("tt0133093", second.items.single().externalIds.imdb)
        assertEquals("Home Video", jumped.items.single().preview.name)
        val requests = http.requests
        assertEquals(listOf(null, "c2", "w1"), requests.map { it.url.queryParameter("cursor") })
        assertEquals("7", requests.last().url.queryParameter("seek"))
        requests.forEach {
            assertEquals("1", it.url.queryParameter("library_id"))
            assertEquals("movie", it.url.queryParameter("type"))
            assertEquals("-added_at", it.url.queryParameter("sort"))
            assertEquals("p1", it.header("X-Profile-Id"))
            assertEquals("Bearer acc", it.header("Authorization"))
            assertNull(it.url.queryParameter("offset"))
        }
    }

    @Test
    fun rowsAskForArtworkAtTheWidthHomeDrawsIt() = runTest {
        val (provider, http) = silo(widths = DrawnArtworkWidths.of(posterPx = 254, logoPx = 330, screenWidthPx = 3840)) { request ->
            when (request.url.encodedPath) {
                "/api/v2/catalog" -> Reply(200, """{"items":[${card("movie-tmdb-603", "The Matrix")}],"page":{"has_more":false},"total":1,"total_exact":true}""")
                "/api/v2/home/sections" -> Reply(200, """{"sections":[]}""")
                else -> null
            }
        }

        provider.libraryPage(session(), movies, 0, 1)
        provider.homeRows(session())

        val reads = http.requests.filter { it.url.encodedPath != "/api/v2/home/capabilities" }
        reads.forEach {
            assertEquals("254", it.url.queryParameter("poster_width"))
            assertEquals("2560", it.url.queryParameter("backdrop_width"))
            assertEquals("330", it.url.queryParameter("logo_width"))
        }
        assertEquals(listOf("medium", "small"), reads.map { it.url.queryParameter("image_size") })
    }

    @Test
    fun withoutAKnownScreenSiloGetsTheSizeNamesOnly() = runTest {
        val (provider, http) = silo { request ->
            if (request.url.encodedPath == "/api/v2/catalog") {
                Reply(200, """{"items":[],"page":{"has_more":false},"total":0,"total_exact":true}""")
            } else null
        }

        provider.libraryPage(session(), movies, 0, 1)

        val url = http.requests.single().url
        assertEquals("medium", url.queryParameter("image_size"))
        assertTrue(url.queryParameterNames.none { it.endsWith("_width") })
    }

    @Test
    fun aTitlePageAsksForTheBackdropAtScreenWidthAndUsesSilosWideThumb() = runTest {
        val (provider, http) = silo(widths = DrawnArtworkWidths.of(posterPx = 254, logoPx = 330, screenWidthPx = 1920)) { request ->
            if (request.url.encodedPath == "/api/v2/catalog/items/movie-tmdb-603") {
                Reply(200, """{"content_id":"movie-tmdb-603","type":"movie","title":"The Matrix","genres":[],"keywords":[],"status":"matched",
                    "backdrop_url":"/api/v2/artwork/b.webp?exp=1&sig=c","thumb_url":"/api/v2/artwork/t.webp?exp=1&sig=d",
                    "banner_url":"/api/v2/artwork/s.webp?exp=1&sig=e","ratings":[],"subtitles":[],"versions":[]}""")
            } else null
        }

        val meta = provider.details(session(), "movie-tmdb-603").meta
        val fresh = provider.freshArtwork(session(), "movie-tmdb-603")

        assertEquals("http://silo.local:8090/api/v2/artwork/t.webp?exp=1&sig=d#nuvio-silo=c1.movie-tmdb-603.landscape", meta.landscapePoster)
        assertEquals("http://silo.local:8090/api/v2/artwork/b.webp?exp=1&sig=c#nuvio-silo=c1.movie-tmdb-603.background", meta.background)
        assertEquals("http://silo.local:8090/api/v2/artwork/t.webp?exp=1&sig=d", fresh[SiloArtworkKind.LANDSCAPE])
        val page = http.requests.first().url
        assertEquals("large", page.queryParameter("image_size"))
        assertEquals("1920", page.queryParameter("backdrop_width"))
        assertNull(page.queryParameter("poster_width"))
        assertNull(page.queryParameter("logo_width"))
        assertEquals("254", http.requests.last().url.queryParameter("poster_width"))
    }

    @Test
    fun drawnWidthsStayInsideTheScreenAndSilosBackdropLimit() {
        assertEquals(DrawnArtworkWidths(poster = 1280, backdrop = 1280, logo = 330), DrawnArtworkWidths.of(4000, 330, 1280))
        assertEquals(2560, DrawnArtworkWidths.of(254, 330, 3840).backdrop)
    }

    @Test
    fun aStaleCursorStartsTheListAgain() = runTest {
        var stale = true
        val (provider, http) = silo { request ->
            if (request.url.encodedPath != "/api/v2/catalog") return@silo null
            val cursor = request.url.queryParameter("cursor")
            when {
                cursor == null -> Reply(200, """{"items":[${card("movie-tmdb-1", "A")}],"page":{"has_more":true,"next_cursor":"old"},
                    "total":2,"total_exact":true,"window_cursor":"w"}""")
                cursor == "old" && stale -> Reply(400, """{"type":"https://siloserver.org/docs/api/v2/problems/invalid_cursor","title":"x","status":400,"detail":"","instance":"i"}""")
                else -> Reply(200, """{"items":[${card("movie-tmdb-2", "B")}],"page":{"has_more":false},"total":2,"total_exact":true,"window_cursor":"w"}""")
            }
        }

        provider.libraryPage(session(), movies, 0, 1)
        val second = provider.libraryPage(session(), movies, 1, 1)
        stale = false

        assertEquals("B", second.items.single().preview.name)
        assertTrue(http.requests.any { it.url.queryParameter("seek") == "1" })
    }

    @Test
    fun seriesDetailsBringEveryEpisodeWithItsWatchedState() = runTest {
        val (provider, http) = silo { request ->
            when (request.url.encodedPath) {
                "/api/v2/catalog/items/series-tvdb-121361" -> Reply(200, """{"content_id":"series-tvdb-121361","type":"series","title":"Game of Thrones",
                    "year":2011,"last_air_date":"2019-05-19","show_status":"Ended","genres":["Drama"],"keywords":[],"status":"matched",
                    "imdb_id":"tt0944947","tmdb_id":"1399","tvdb_id":"121361","content_rating":"MA15+",
                    "cast":[{"name":"Emilia Clarke","character":"Daenerys","order":0,"photo_url":"/api/v2/artwork/p.webp?exp=1&sig=b"}],
                    "crew":[{"name":"David Benioff","job":"Writer"}],"ratings":[],"subtitles":[],"versions":[]}""")
                "/api/v2/catalog/series/series-tvdb-121361/seasons" -> Reply(200, """{"items":[{"content_id":"season-tvdb-121361-1","season_number":1,"title":"Season 1","episode_count":2}]}""")
                "/api/v2/catalog/series/series-tvdb-121361/seasons/1/episodes" -> Reply(200, """{"items":[
                    {"content_id":"episode-tvdb-121361-1-1","season_number":1,"episode_number":1,"title":"Winter Is Coming","runtime":62,"air_date":"2011-04-17",
                     "user_data":{"watched_count":1,"unplayed_count":0,"in_progress_count":0,"played":true}},
                    {"content_id":"episode-tvdb-121361-1-2","season_number":1,"episode_number":2,"title":"The Kingsroad","runtime":56,
                     "user_data":{"watched_count":0,"unplayed_count":1,"in_progress_count":1,"played":false,"position_seconds":600}}]}""")
                else -> null
            }
        }

        val details = provider.details(session(), "series-tvdb-121361")

        assertEquals("2011-2019", details.meta.releaseInfo)
        assertEquals(listOf("Winter Is Coming", "The Kingsroad"), details.meta.videos.map { it.title })
        assertEquals("srv1:c1:episode-tvdb-121361-1-2", details.meta.videos.last().id)
        assertEquals(TrackingExternalIds(imdb = "tt0944947", tmdb = 1399L, tvdb = "121361"), details.externalIds)
        assertEquals(listOf(true to 0L, false to 600_000L), details.userStates.map { it.played to it.positionMs })
        assertEquals(56 * 60_000L, details.userStates.last().durationMs)
        assertEquals("http://silo.local:8090/api/v2/artwork/p.webp?exp=1&sig=b", details.meta.castMembers.single().photo)
        assertEquals(listOf("David Benioff"), details.meta.writer)
        assertEquals("false", http.requests.first { it.url.encodedPath.endsWith("/seasons") }.url.queryParameter("include_artwork"))
    }

    @Test
    fun anAddonTitleIsFoundByTheIdSiloNamedItAfter() = runTest {
        val (provider, http) = silo { request ->
            when (request.url.encodedPath) {
                "/api/v2/catalog/items/movie-imdb-tt0133093" -> Reply(200, """{"content_id":"movie-imdb-tt0133093","type":"movie","title":"The Matrix",
                    "year":1999,"imdb_id":"tt0133093","tmdb_id":"603","genres":[],"keywords":[],"status":"matched","cast":[],"crew":[],
                    "ratings":[],"subtitles":[],"versions":[]}""")
                else -> null
            }
        }

        val found = provider.lookupTitle(
            session(),
            movies,
            ServerTitleQuery(TrackingExternalIds(imdb = "tt0133093", tmdb = 603L), "The Matrix", 1999)
        )

        assertEquals(listOf("movie-imdb-tt0133093"), found.map { it.itemId })
        assertEquals(603L, found.single().ids.tmdb)
        assertEquals(
            listOf("/api/v2/catalog/items/movie-tmdb-603", "/api/v2/catalog/items/movie-imdb-tt0133093"),
            http.requests.map { it.url.encodedPath }
        )
    }

    @Test
    fun aTitleWithoutAnIdNameFallsBackToATitleSearch() = runTest {
        val (provider, http) = silo { request ->
            if (request.url.encodedPath == "/api/v2/catalog") {
                Reply(200, """{"items":[${card("local-9f", "Heat")}, ${card("series-tvdb-5", "Heat", type = "series")}],"total":2,"total_exact":true,"window_cursor":"w"}""")
            } else {
                null
            }
        }

        val found = provider.lookupTitle(session(), movies, ServerTitleQuery(TrackingExternalIds(tmdb = 949L), "Heat", 1995))

        assertEquals(listOf("local-9f"), found.map { it.itemId })
        assertEquals("Heat 1995", http.requests.last().url.queryParameter("q"))
    }

    @Test
    fun anEpisodeIsFoundUnderItsSeriesId() = runTest {
        val (provider, http) = silo { request ->
            when (request.url.encodedPath) {
                "/api/v2/catalog/items/episode-tvdb-121361-2-3" -> Reply(200, """{"content_id":"episode-tvdb-121361-2-3","type":"episode","title":"x",
                    "season_number":2,"episode_number":3,"air_date":"2012-04-15","genres":[],"keywords":[],"status":"matched","cast":[],"crew":[],
                    "ratings":[],"subtitles":[],"versions":[]}""")
                "/api/v2/catalog/series/42/seasons/1/episodes" -> Reply(200, """{"items":[{"content_id":"777","season_number":1,"episode_number":4,"title":"y","runtime":40}]}""")
                else -> null
            }
        }

        val byId = provider.findEpisode(session(), "series-tvdb-121361", 2, 3)
        val listed = provider.findEpisode(session(), "42", 1, 4)
        val missing = provider.findEpisode(session(), "42", 9, 1)

        assertEquals("episode-tvdb-121361-2-3" to "2012-04-15", byId?.itemId to byId?.premiereDate)
        assertEquals("777", listed?.itemId)
        assertNull(missing)
        assertEquals(1, http.requests.count { it.url.encodedPath.startsWith("/api/v2/catalog/items/") })
    }

    @Test
    fun continueWatchingComesFromSilosOwnRowWithoutNextUp() = runTest {
        val movie = card(
            "movie-tmdb-603",
            "The Matrix",
            extra = ",\"position_seconds\":1200.5,\"duration_seconds\":8160,\"item_source\":\"in_progress\",\"progress_updated_at\":\"2026-10-01T10:00:00.000Z\""
        )
        val inProgress = card(
            "episode-tvdb-121361-1-2",
            "The Kingsroad",
            type = "episode",
            extra = ",\"series_id\":\"series-tvdb-121361\",\"series_title\":\"Game of Thrones\",\"season_number\":1,\"episode_number\":2," +
                "\"position_seconds\":600,\"duration_seconds\":3360,\"item_source\":\"in_progress\""
        )
        val nextUp = card(
            "episode-tvdb-121361-1-3",
            "Lord Snow",
            type = "episode",
            extra = ",\"series_id\":\"series-tvdb-121361\",\"season_number\":1,\"episode_number\":3,\"item_source\":\"next_up\""
        )
        val (provider, _) = silo { request ->
            when (request.url.encodedPath) {
                "/api/v2/home/layout" -> Reply(200, """{"sections":[{"id":"recent","section_type":"recently_added","title":"New"},
                    {"id":"cw-7","section_type":"continue_watching","title":"Continue Watching"}]}""")
                "/api/v2/home/sections/cw-7/items" ->
                    Reply(200, """{"id":"cw-7","section_type":"continue_watching","title":"Continue Watching","items":[$movie,$inProgress,$nextUp]}""")
                else -> null
            }
        }

        val entries = provider.resumeItems(session(), 20)

        assertEquals(listOf("The Matrix", "Game of Thrones"), entries.map { it.title.preview.name })
        val episode = entries.last()
        assertEquals("srv1:c1:series-tvdb-121361", episode.title.preview.id)
        assertEquals("121361", episode.title.externalIds.tvdb)
        assertEquals("srv1:c1:episode-tvdb-121361-1-2", episode.state.videoId)
        assertEquals(Triple(1, 2, 600_000L), Triple(episode.state.season, episode.state.episode, episode.state.positionMs))
        assertEquals(1_200_500L, entries.first().state.positionMs)

        assertEquals(listOf("The Matrix"), provider.resumeItems(session(listOf(movies)), 20).map { it.title.preview.name })
    }

    @Test
    fun theRowListAsksForAFewCardsWhenTheServerAllowsIt() = runTest {
        val rows = """{"sections":[{"id":"new","section_type":"recently_added","title":"New","total_count":100,"items":[${card("movie-tmdb-603", "The Matrix")}]}]}"""
        val (provider, http) = silo { request ->
            when (request.url.encodedPath) {
                "/api/v2/home/capabilities" -> Reply(200, """{"section_item_limit_max":100}""")
                "/api/v2/home/sections" -> Reply(200, rows)
                else -> null
            }
        }

        assertEquals(listOf("new"), provider.homeRows(session()).map { it.id })
        assertEquals(listOf("20"), http.requests.filter { it.url.encodedPath == "/api/v2/home/sections" }.map { it.url.queryParameter("item_limit") })
    }

    @Test
    fun aServerThatDoesNotOfferTheLimitIsNotSentIt() = runTest {
        val (provider, http) = silo { request ->
            if (request.url.encodedPath == "/api/v2/home/sections") Reply(200, """{"sections":[]}""") else null
        }

        provider.homeRows(session())

        assertNull(http.requests.single { it.url.encodedPath == "/api/v2/home/sections" }.url.queryParameter("item_limit"))
    }

    @Test
    fun aRowEmptiedByTheSmallerReadIsReadAgainInFull() = runTest {
        val full = """{"sections":[{"id":"new","section_type":"recently_added","title":"New","total_count":40,"items":[${card("movie-tmdb-603", "The Matrix")}]}]}"""
        val cut = """{"sections":[{"id":"new","section_type":"recently_added","title":"New","total_count":40,"items":[]}]}"""
        val (provider, http) = silo { request ->
            when (request.url.encodedPath) {
                "/api/v2/home/capabilities" -> Reply(200, """{"section_item_limit_max":100}""")
                "/api/v2/home/sections" -> Reply(200, if (request.url.queryParameter("item_limit") != null) cut else full)
                else -> null
            }
        }

        assertEquals(listOf("new"), provider.homeRows(session()).map { it.id })
        assertEquals(2, http.requests.count { it.url.encodedPath == "/api/v2/home/sections" })
    }

    @Test
    fun episodeCardsShowTheSeriesPicturesWhenSiloSendsThem() = runTest {
        val episode = card(
            "episode-tvdb-121361-1-3",
            "Lord Snow",
            type = "episode",
            extra = ",\"series_id\":\"series-tvdb-121361\",\"series_title\":\"Game of Thrones\"," +
                "\"backdrop_url\":\"/still.webp\",\"series_poster_url\":\"/series-poster.webp\",\"series_backdrop_url\":\"/series-backdrop.webp\""
        )
        val (provider, _) = silo { request ->
            if (request.url.encodedPath == "/api/v2/home/sections/next/items") {
                Reply(200, """{"id":"next","section_type":"next_up","title":"Next Up","items":[$episode]}""")
            } else null
        }

        val preview = provider.homeRowItems(session(), "next").single().preview

        assertTrue(preview.poster!!.contains("/series-poster.webp"))
        assertTrue(preview.background!!.contains("/series-backdrop.webp"))
    }

    @Test
    fun silosHomeRowsComeWithoutContinueWatchingAndEpisodesShowTheirSeries() = runTest {
        val episode = card(
            "episode-tvdb-121361-1-3",
            "Lord Snow",
            type = "episode",
            extra = ",\"series_id\":\"series-tvdb-121361\",\"series_title\":\"Game of Thrones\",\"season_number\":1,\"episode_number\":3"
        )
        val sections = """{"sections":[
            {"id":"cw","section_type":"continue_watching","title":"Continue Watching","items":[${card("movie-tmdb-603", "The Matrix")}]},
            {"id":"next","section_type":"next_up","title":"Next Up","items":[$episode]},
            {"id":"hp-1","section_type":"collection","title":"harryposter's picks","items":[${card("movie-tmdb-603", "The Matrix")},${card("series-tvdb-5", "Lost", type = "series")},${card("movie-tmdb-604", "Reloaded")}]},
            {"id":"books","section_type":"recently_added","title":"New books","items":[${card("audiobook-1", "Dune", type = "audiobook")}]}]}"""
        val (provider, http) = silo { request ->
            when (request.url.encodedPath) {
                "/api/v2/home/sections" -> Reply(200, sections)
                "/api/v2/home/sections/next/items" -> Reply(200, """{"id":"next","section_type":"next_up","title":"Next Up","items":[$episode,$episode]}""")
                else -> null
            }
        }

        val rows = provider.homeRows(session())
        val nextUp = provider.homeRowItems(session(), "next")

        assertEquals(
            listOf(
                ServerHomeRow("next", "Next Up", ServerMediaKind.SERIES),
                ServerHomeRow("hp-1", "harryposter's picks", ServerMediaKind.MOVIE)
            ),
            rows
        )
        assertEquals(listOf("srv1:c1:series-tvdb-121361" to "Game of Thrones"), nextUp.map { it.preview.id to it.preview.name })
        assertTrue(http.requests.all { it.header("X-Profile-Id") == "p1" })
    }

    @Test
    fun aMixedLibraryShowsAsMoviesAndSeriesThatEachAskForTheirOwnKind() = runTest {
        val (provider, http) = silo { request ->
            when (request.url.encodedPath) {
                "/api/v2/user/libraries" -> Reply(200, """{"items":[{"id":"12","name":"Kids","type":"mixed","sort_order":1},
                    {"id":"13","name":"Books","type":"audiobooks","sort_order":2}]}""")
                "/api/v2/catalog" -> Reply(200, """{"items":[],"total":0,"total_exact":true,"window_cursor":"w"}""")
                else -> null
            }
        }

        val libraries = provider.libraries(session())
        provider.libraryPage(session(libraries), libraries.last(), 0, 50)

        assertEquals(
            listOf(
                ServerLibrary("12:movie", "Kids · Movies", ServerMediaKind.MOVIE),
                ServerLibrary("12:series", "Kids · Series", ServerMediaKind.SERIES)
            ),
            libraries
        )
        val page = http.requests.last()
        assertEquals("12", page.url.queryParameter("library_id"))
        assertEquals("series", page.url.queryParameter("type"))
    }

    @Test
    fun aSearchAsksEachLibraryOfItsKindOnce() = runTest {
        val films4k = ServerLibrary("3", "4K", ServerMediaKind.MOVIE)
        val (provider, http) = silo { request ->
            if (request.url.encodedPath != "/api/v2/catalog") return@silo null
            Reply(200, """{"items":[${card("movie-tmdb-603", "The Matrix")}],"page":{"has_more":false}}""")
        }

        val found = provider.search(session(listOf(movies, films4k, shows)), ServerMediaKind.MOVIE, listOf(movies, films4k), "matrix", 30)

        assertEquals(listOf("The Matrix"), found.map { it.preview.name })
        val searches = http.requests.filter { it.url.encodedPath == "/api/v2/catalog" }
        assertEquals(setOf("1", "3"), searches.map { it.url.queryParameter("library_id") }.toSet())
        assertTrue(searches.all { it.url.queryParameter("q") == "matrix" && it.url.queryParameter("type") == "movie" })
    }

    @Test
    fun watchedMarksGoToSilosWatchedList() = runTest {
        val (provider, http) = silo { request -> if (request.url.encodedPath.startsWith("/api/v2/watched/")) Reply(204, "") else null }

        provider.setPlayed(session(), "movie-tmdb-603", true)
        provider.setPlayed(session(), "movie-tmdb-603", false)

        assertEquals(listOf("POST", "DELETE"), http.requests.map { it.method })
        assertTrue(http.requests.all { it.url.encodedPath == "/api/v2/watched/movie-tmdb-603" && it.header("X-Profile-Id") == "p1" })
    }

    @Test
    fun contentIdsGiveTheIdTheyWereMadeFrom() {
        assertEquals(TrackingExternalIds(tmdb = 603L), contentIdExternalIds("movie-tmdb-603"))
        assertEquals(TrackingExternalIds(tvdb = "121361"), contentIdExternalIds("series-tvdb-121361"))
        assertEquals(TrackingExternalIds(imdb = "tt0133093"), contentIdExternalIds("movie-imdb-tt0133093"))
        assertEquals(TrackingExternalIds(), contentIdExternalIds("local-abc"))
        assertEquals(TrackingExternalIds(), contentIdExternalIds("episode-tvdb-1-1-1"))
        assertEquals(
            listOf("series-tvdb-81189", "series-tmdb-1396", "series-imdb-tt0903747"),
            contentIdCandidates(ServerMediaKind.SERIES, TrackingExternalIds(imdb = "tt0903747", tmdb = 1396L, tvdb = "81189"))
        )
        assertEquals("episode-tmdb-1396-1-2", episodeContentId("series-tmdb-1396", 1, 2))
        assertNull(episodeContentId("42", 1, 2))
    }
}
