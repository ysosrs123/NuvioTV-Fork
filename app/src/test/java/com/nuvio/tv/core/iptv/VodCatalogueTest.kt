package com.nuvio.tv.core.iptv

import java.io.StringReader
import org.junit.Assert.*
import org.junit.Test

class VodCatalogueTest {
    private fun movies(json: String) = mutableListOf<VodMovie>().let { list -> XtreamVodParser.movies(StringReader(json)) { list += it } to list }
    private fun series(json: String) = mutableListOf<VodSeries>().let { list -> XtreamVodParser.series(StringReader(json)) { list += it } to list }

    @Test fun vodStreamsAcceptStringNumbersAndBothTmdbSpellings() {
        val (count, rows) = movies("""[
            {"num":1,"name":"EN - The Matrix (1999)","stream_type":"movie","stream_id":"603","stream_icon":"https://img.invalid/m.jpg","rating":"7.9","added":"1600000000","category_id":"5","container_extension":"mkv","tmdb":"603"},
            {"num":2,"name":"Heat","stream_id":949,"rating_5based":4.1,"category_id":7,"container_extension":".MP4","tmdb_id":949,"year":"1995","imdb_id":"tt0113277"},
            {"num":3,"name":"Broken","stream_id":"abc"},
            {"num":4,"stream_id":5},
            "junk",
            {"num":5,"name":"Duplicate","stream_id":"603"}
        ]""")
        assertEquals(VodParseCount(2, 4), count)
        val matrix = rows[0]
        assertEquals(VodMovie("603", "EN - The Matrix (1999)", 1999, "5", "https://img.invalid/m.jpg", 7.9, 1_600_000_000L, "mkv", "603", null), matrix)
        val heat = rows[1]
        assertEquals("949", heat.providerId); assertEquals(1995, heat.year); assertEquals("7", heat.categoryId)
        assertEquals(8.2, heat.rating!!, 0.001); assertEquals("mp4", heat.extension); assertEquals("949", heat.tmdbId); assertEquals("tt0113277", heat.imdbId)
        assertFalse(matrix.toString().contains("img.invalid"))
    }

    @Test fun missingAndOddFieldsAreTolerated() {
        val (_, rows) = movies("""[{"name":"Plain","stream_id":1,"tmdb":"","rating":"0","added":"","container_extension":null,"category_id":null,"stream_icon":"not a url","year":"N/A"},
            {"name":"Linked","stream_id":2,"tmdb":"https://www.themoviedb.org/movie/00550-fight-club"}]""")
        val plain = rows[0]
        assertNull(plain.tmdbId); assertNull(plain.rating); assertNull(plain.addedSeconds); assertNull(plain.extension)
        assertNull(plain.categoryId); assertNull(plain.poster); assertNull(plain.year)
        assertEquals("550", rows[1].tmdbId)
    }

    @Test fun objectKeyedListsAndAuthenticationErrorsDoNotThrow() {
        assertEquals(1, movies("""{"10":{"name":"Keyed","stream_id":10}}""").second.size)
        val (count, rows) = movies("""{"user_info":{"auth":0}}""")
        assertTrue(rows.isEmpty()); assertEquals(1, count.invalid)
        assertEquals(VodParseCount(0, 0), movies("[]").first)
    }

    @Test fun seriesListsReadCoverYearAndTmdb() {
        val (_, rows) = series("""[{"num":1,"name":"Breaking Bad","series_id":"1396","cover":"https://img.invalid/c.jpg","releaseDate":"2008-01-20","rating":"9","category_id":"3","tmdb":"1396","last_modified":"1700000000"},
            {"name":"No id"}]""")
        assertEquals(VodSeries("1396", "Breaking Bad", 2008, "3", "https://img.invalid/c.jpg", 9.0, 1_700_000_000L, "1396"), rows.single())
    }

    @Test fun categoriesSkipBadRowsAndKeepFirstName() {
        val list = XtreamVodParser.categories(StringReader("""[{"category_id":"1","category_name":"EN | Action"},{"category_id":2,"category_name":"Drama"},
            {"category_id":"1","category_name":"Other"},{"category_name":"No id"},{"category_id":"3","category_name":""}]"""), VodKind.MOVIE)
        assertEquals(listOf(VodCategory("1", "EN | Action", VodKind.MOVIE), VodCategory("2", "Drama", VodKind.MOVIE)), list)
    }

    @Test fun seriesInfoReadsSeasonMapsListsAndNestedLists() {
        val map = """{"seasons":[],"info":{"name":"Breaking Bad","cover":"https://img.invalid/c.jpg","tmdb_id":"1396","releaseDate":"2008"},
            "episodes":{"1":[{"id":"101","episode_num":1,"title":"Breaking Bad - S01E01 - Pilot","container_extension":"mkv","info":{"duration_secs":3480,"plot":"A teacher.","movie_image":"https://img.invalid/e1.jpg","tmdb_id":62085}},
                             {"id":"102","episode_num":"2","title":"Cat's in the Bag","container_extension":"mkv","info":{"duration":"00:48:10"}}],
                        "2":[{"id":"201","episode_num":1,"season":2,"title":"Seven Thirty-Seven","container_extension":"mp4","info":[]}]}}"""
        val info = XtreamVodParser.seriesInfo(StringReader(map), "1396")
        assertEquals("1396", info.series!!.providerId); assertEquals("1396", info.series!!.tmdbId); assertEquals(2008, info.series!!.year)
        assertEquals(listOf("101", "102", "201"), info.episodes.map { it.providerId })
        val pilot = info.episodes[0]
        assertEquals(VodEpisode("101", 1, 1, "Pilot", "mkv", 3480, "A teacher.", "https://img.invalid/e1.jpg", "62085"), pilot)
        assertEquals(2890, info.episodes[1].durationSeconds)
        assertEquals(2 to 1, info.episodes[2].season to info.episodes[2].episode)

        val nested = XtreamVodParser.seriesInfo(StringReader("""{"info":[],"episodes":[[{"id":1,"season":1,"episode_num":1}],[{"id":2,"season":2,"episode_num":3},{"id":"x","season":2,"episode_num":4}]]}"""), "9")
        assertNull(nested.series)
        assertEquals(listOf(1 to 1, 2 to 3), nested.episodes.map { it.season to it.episode })
        assertEquals(1, nested.invalidRows)

        val flat = XtreamVodParser.seriesInfo(StringReader("""{"episodes":[{"id":5,"title":"Show S03E04"}]}"""), "9")
        assertEquals(3 to 4, flat.episodes.single().season to flat.episodes.single().episode)
        assertTrue(XtreamVodParser.seriesInfo(StringReader("""{"episodes":null}"""), "9").episodes.isEmpty())
    }

    @Test fun movieInfoReadsIdsAndDuration() {
        val info = XtreamVodParser.movieInfo(StringReader("""{"info":{"tmdb_id":"603","imdb_id":"tt0133093","releasedate":"1999-03-31","duration_secs":"8160","plot":"Neo."},
            "movie_data":{"stream_id":603,"container_extension":"mkv"}}"""))!!
        assertEquals(VodMovieInfo("603", "tt0133093", 1999, 8160, "Neo.", null, "mkv", VodDetails(plot = "Neo.", year = 1999, durationSeconds = 8160)), info)
        assertNull(XtreamVodParser.movieInfo(StringReader("""{"info":[],"movie_data":[]}""")))
    }

    @Test fun infoReadsProviderDetails() {
        val movie = XtreamVodParser.movieInfo(StringReader("""{"info":{"name":"Heist","cover_big":"http://p.example/c.jpg","plot":"A job.","cast":"A  One,\n B Two",
            "director":"C Three","genre":"Crime, Thriller","rating":"7.4","backdrop_path":["","https://p.example/b.jpg"],"duration":"01:40:00","releasedate":"2024-05-01"},
            "movie_data":{"container_extension":"mp4"}}"""))!!
        assertEquals(VodDetails("A job.", "A One, B Two", "C Three", "Crime, Thriller", 7.4, "http://p.example/c.jpg", "https://p.example/b.jpg", 2024, 6000), movie.details)
        assertEquals("A job.", movie.plot)

        val series = XtreamVodParser.seriesInfo(StringReader("""{"info":{"name":"Show (2019)","cover":"http://p.example/s.jpg","plot":"Things.","actors":["X","Y"],
            "rating_5based":"4","backdrop_path":"javascript:alert(1)","episode_run_time":"45"},"episodes":{"1":[{"id":"7","episode_num":1}]}}"""), "9")
        assertEquals(VodDetails("Things.", "X, Y", null, null, 8.0, "http://p.example/s.jpg", null, 2019, 2700), series.details)
        assertEquals(VodDetails(), XtreamVodParser.seriesInfo(StringReader("""{"info":[],"episodes":[]}"""), "9").details)
        assertEquals(VodDetails(), XtreamVodParser.movieInfo(StringReader("""{"movie_data":{"stream_id":1}}"""))!!.details)
    }

    @Test fun jsonReaderRejectsMalformedInput() {
        for (bad in listOf("[", "[1,]", "{\"a\" 1}", "[01]", "[\"\\x\"]", "[1] x", "[tru]", "")) {
            assertThrows(bad, IllegalArgumentException::class.java) { VodJsonReader(StringReader(bad)).readDocument() }
        }
        assertEquals(listOf("é", VodJsonNumber("-1.5e3"), true, null, mapOf("k" to "\n")),
            VodJsonReader(StringReader("""["\u00e9",-1.5e3,true,null,{"k":"\n"}]""")).readDocument())
        assertThrows(IllegalArgumentException::class.java) { VodJsonReader(StringReader("[[[[1]]]]"), maxDepth = 3).readDocument() }
        assertThrows(IllegalArgumentException::class.java) { VodJsonReader(StringReader("[\"abcdef\"]"), maxString = 3).readDocument() }
    }

    @Test fun titleLimitAndCancellationStopParsing() {
        assertThrows(IllegalArgumentException::class.java) {
            XtreamVodParser.movies(StringReader("""[{"name":"A","stream_id":1},{"name":"B","stream_id":2}]"""), max = 1) {}
        }
        var rows = 0
        assertThrows(IllegalStateException::class.java) {
            XtreamVodParser.movies(StringReader("""[{"name":"A","stream_id":1},{"name":"B","stream_id":2}]"""), checkCancellation = { if (rows > 0) throw IllegalStateException() }) { rows++ }
        }
        assertEquals(1, rows)
    }

    @Test fun playlistEntriesAreClassifiedByPathTypeAndGroup() {
        fun kind(name: String, url: String, vararg attributes: Pair<String, String>) = PlaylistVod.classify(name, url, attributes.toMap())
        assertEquals(VodKind.MOVIE, kind("Heat", "http://p.invalid:8080/movie/u/p/949.mkv"))
        assertEquals(VodKind.SERIES, kind("Show S01E01", "http://p.invalid:8080/series/u/p/1001.mp4"))
        assertEquals(VodKind.MOVIE, kind("Anything", "http://p.invalid/stream/1", "tvg-type" to "movie"))
        assertEquals(VodKind.SERIES, kind("Anything", "http://p.invalid/stream/1", "tvg-type" to "series"))
        assertNull(kind("Movies 24/7", "http://p.invalid/movie/u/p/1.ts", "tvg-type" to "live"))
        assertEquals(VodKind.MOVIE, kind("Heat (1995)", "http://p.invalid/v/949.mkv", "group-title" to "VOD | Action"))
        assertEquals(VodKind.MOVIE, kind("Heat (1995)", "http://p.invalid/v/949", "group-title" to "Films FR"))
        assertEquals(VodKind.SERIES, kind("Bluey S01 E02", "http://p.invalid/v/2", "group-title" to "TV Shows"))
        assertEquals(VodKind.SERIES, kind("Bluey S01 E02", "http://p.invalid/v/2.mp4", "group-title" to "Movies"))
        assertNull(kind("Sky Cinema Premiere HD", "http://p.invalid/live/1.ts", "group-title" to "UK | Movies"))
        assertNull(kind("HBO Movies", "http://p.invalid/play/7", "group-title" to "Cinema"))
        assertNull(kind("Kids Shows", "http://p.invalid/live/8.m3u8", "group-title" to "Shows"))
        assertNull(kind("ABC News", "http://movie.invalid/live/9.ts", "group-title" to "News"))
        assertNull(kind("Moviestar", "http://p.invalid/live/9.ts", "group-title" to "Moviestar+"))
    }

    @Test fun playlistParserMovesVodEntriesOutOfLiveChannels() {
        val text = """#EXTM3U
#EXTINF:-1 tvg-id="abc.au" group-title="News",ABC News
http://p.invalid/live/u/p/1.ts
#EXTINF:-1 tvg-logo="http://img.invalid/heat.jpg" group-title="VOD | Action",EN - Heat (1995) 4K
http://p.invalid/movie/u/p/949.mkv
#EXTINF:-1 group-title="Series | Drama" tvg-logo="http://img.invalid/bb.jpg",Breaking Bad S01 E01
http://p.invalid/series/u/p/1001.mkv
#EXTINF:-1 group-title="Series | Drama",Breaking Bad S01 E02
http://p.invalid/series/u/p/1002.mkv
#EXTINF:-1 group-title="Series | Drama",Breaking Bad S02E01
http://p.invalid/series/u/p/2001.mkv
#EXTINF:-1 tvg-type="series" group-title="Specials",Lone Special
http://p.invalid/v/77.mp4
"""
        val parsed = PlaylistCatalogueParser().parse(StringReader(text))
        assertEquals(listOf("ABC News"), parsed.channels.map { it.name })
        assertTrue(parsed.canPublish)
        assertEquals(5, parsed.vod.size)
        val catalogue = PlaylistVod.catalogue(parsed.vod)
        assertEquals(listOf("EN - Heat (1995) 4K", "Lone Special"), catalogue.movies.map { it.movie.name })
        val heat = catalogue.movies[0]
        assertEquals(1995, heat.movie.year); assertEquals("mkv", heat.movie.extension); assertEquals("http://img.invalid/heat.jpg", heat.movie.poster)
        assertEquals("http://p.invalid/movie/u/p/949.mkv", heat.locator)
        assertTrue(heat.movie.providerId.matches(Regex("m[0-9a-f]{20}")))
        val show = catalogue.series.single()
        assertEquals("Breaking Bad", show.name); assertEquals("http://img.invalid/bb.jpg", show.cover)
        assertEquals(listOf(1 to 1, 1 to 2, 2 to 1), catalogue.episodes.map { it.episode.season to it.episode.episode })
        assertTrue(catalogue.episodes.all { it.seriesId == show.providerId })
        assertEquals(setOf("VOD | Action", "Series | Drama", "Specials"), catalogue.categories.map { it.name }.toSet())
        assertEquals(PlaylistVod.catalogue(parsed.vod), catalogue)
        assertFalse(catalogue.toString().contains("p.invalid"))
    }

    @Test fun vodOnlyPlaylistIsNotReportedEmpty() {
        val parsed = PlaylistCatalogueParser().parse(StringReader("#EXTM3U\n#EXTINF:-1,Heat\nhttp://p.invalid/movie/u/p/1.mp4\n"))
        assertTrue(parsed.diagnostics.isEmpty())
        assertTrue(parsed.channels.isEmpty())
        assertEquals(1, parsed.vod.size)
    }
}
