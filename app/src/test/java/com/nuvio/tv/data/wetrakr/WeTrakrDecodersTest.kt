package com.nuvio.tv.data.wetrakr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WeTrakrDecodersTest {
    @Test
    fun `the account gives the user id and name`() {
        val user = decodeWeTrakrUser("""{"id":42,"info":{"username":"alex","display_name":"Alex"},"plan":"free"}""")
        assertEquals(WeTrakrUser(42, "alex", "Alex", "free"), user)
        assertThrows(WeTrakrDecodingException::class.java) { decodeWeTrakrUser("""{"info":{}}""") }
    }

    @Test
    fun `last activities are flattened by section`() {
        val activities = decodeWeTrakrActivities(
            """{"all":"2026-09-22T10:09:59.528Z","movies":{"all":"2026-09-22T01:18:05.815Z","last_tracking_watched_at":"2026-09-22T01:18:05.815Z"},"journal_visible_until":"2026-09-22T10:09:54.000Z"}"""
        )
        assertEquals("2026-09-22T01:18:05.815Z", activities.stamps["movies.last_tracking_watched_at"])
        assertEquals("2026-09-22T10:09:54.000Z", activities.journalVisibleUntil)
    }

    @Test
    fun `compact watched rows give the show ids for episodes`() {
        val rows = weTrakrJson(
            """[{"type":"episode","id":5092071,"ids":{"tmdb":5119022,"imdb":"tt31140164","tvdb":10287742},"show_id":1333953,"show_ids":{"tmdb":245318,"imdb":"tt31137273","tvdb":445651},"season_id":1,"season_number":1,"number":4,"last_watched_at":"2026-09-22T01:18:05.815Z","plays":2}]"""
        ).array()
        val episode = decodeWeTrakrWatchedEpisodes(rows).single()
        assertEquals(1333953L, episode.showId)
        assertEquals("tt31137273", episode.showIds.imdb)
        assertEquals(1 to 4, episode.season to episode.number)
        assertEquals(2, episode.plays)
        val movie = decodeWeTrakrWatchedMovies(weTrakrJson("""[{"type":"movie","id":126,"ids":{"tmdb":155,"imdb":"tt0468569"},"last_watched_at":"2026-09-12T20:41:00.000Z","plays":1}]""").array()).single()
        assertEquals(WeTrakrIds(126, "tt0468569", 155), movie.ids)
    }

    @Test
    fun `playing rows become sessions with the parent show`() {
        val rows = weTrakrJson(
            """[{"type":"episode","id":77,"season_number":2,"number":5,"title":"Hello","show":{"id":900,"title":"Severance","ids":{"imdb":"tt11280740"},"poster_path":"/p.jpg"},"playback":{"status":"paused","progress_percent":41,"runtime_seconds":3000,"tracked_at":"2026-11-03T09:00:00.000Z"}}]"""
        ).array()
        val session = decodeWeTrakrPlayback(rows, WeTrakrType.EPISODE).single()
        assertEquals("Severance", session.title.title)
        assertEquals("https://image.tmdb.org/t/p/w342/p.jpg", session.title.poster)
        assertEquals(41f, session.progress)
        assertEquals(2 to 5, session.season to session.number)
        assertEquals(3000L, session.runtimeSeconds)
        assertEquals("Hello", session.episodeTitle)
    }

    @Test
    fun `list rows keep title, year and artwork`() {
        val entry = decodeWeTrakrListEntries(
            weTrakrJson("""[{"id":126,"type":"movie","title":"The Dark Knight","ids":{"tmdb":155,"imdb":"tt0468569"},"release_date":"2008-07-18T12:00:00.000Z","poster_path":"https://example.com/p.jpg"}]""").array(),
            WeTrakrType.MOVIE
        ).single()
        assertEquals(2008, entry.title.year)
        assertEquals("https://example.com/p.jpg", entry.title.poster)
        assertNull(entry.listedAt)
    }

    @Test
    fun `journal entries keep the play and the show of an episode`() {
        val page = decodeWeTrakrJournal(
            """{"retention_days":30,"visible_until":"2026-11-03T10:00:00.000Z","journal":[{"entry_id":"a1","category":"watched","status":"added","action_at":"2026-11-03T09:59:00.000Z","type":"episode","id":77,"ids":{"tmdb":1},"media_id":900,"season_id":3,"season_number":2,"number":5,"play_id":"p1","watched_at":"2026-11-03T09:00:00.000Z"}]}"""
        )
        val entry = page.entries.single()
        assertEquals(900L, entry.showId)
        assertEquals(2 to 5, entry.season to entry.number)
        assertNull(entry.ids.wetrakr)
        assertEquals("2026-11-03T10:00:00.000Z", page.visibleUntil)
    }

    @Test
    fun `odd values are dropped instead of failing the sync`() {
        val rows = weTrakrJson("""[{"type":"movie","id":1,"ids":{"imdb":"nm123"},"last_watched_at":"x"},{"type":"movie"}]""").array()
        assertEquals(WeTrakrIds(wetrakr = 1), decodeWeTrakrWatchedMovies(rows).single().ids)
        assertTrue(decodeWeTrakrPlayback(rows, WeTrakrType.MOVIE).isEmpty())
    }
}
