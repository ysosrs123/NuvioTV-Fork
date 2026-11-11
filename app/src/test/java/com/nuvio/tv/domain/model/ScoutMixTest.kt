package com.nuvio.tv.domain.model

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ScoutMixTest {
    private fun video(show: String, season: Int, episode: Int) =
        Video("$show:$season:$episode", "E$episode", "2020-01-01", null, season = season, episode = episode, overview = null)

    private fun meta(show: String, episodes: Int) = Meta(
        id = show,
        type = ContentType.SERIES,
        name = show,
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList(),
        runtime = null,
        director = emptyList(),
        cast = emptyList(),
        videos = (1..episodes).map { video(show, 1, it) },
        country = null,
        awards = null,
        language = null,
        links = emptyList()
    )

    private fun show(id: String, genres: List<String> = emptyList()) = ScoutMixShow(id, id, genres = genres)

    private val words = ScoutMixNameWords(
        oneShow = { "$it mix" },
        genre = { "$it mix" },
        twoShows = { a, b -> "$a & $b" },
        moreShows = { a, b, more -> "$a, $b & $more more" }
    )

    @Test
    fun `each show is as likely as any other whatever its length`() = runTest {
        val shows = listOf(show("long"), show("short"))
        val metas = mapOf("long" to meta("long", 200), "short" to meta("short", 2))
        val random = Random(7)
        val counts = mutableMapOf("long" to 0, "short" to 0)
        repeat(2000) {
            val pick = pickScoutMixEpisode(shows, unwatchedOnly = true, random = random) { ScoutMixEpisodes(metas.getValue(it.id)) }
            counts[(pick as ScoutMixPick.Episode).show.id] = counts.getValue(pick.show.id) + 1
        }
        assertTrue(counts.toString(), counts.getValue("short") in 850..1150)
    }

    @Test
    fun `only the shows the pick needs are looked up`() = runTest {
        val looked = mutableListOf<String>()
        pickScoutMixEpisode(listOf(show("a"), show("b"), show("c")), unwatchedOnly = true) {
            looked += it.id
            ScoutMixEpisodes(meta(it.id, 3))
        }
        assertEquals(1, looked.size)
    }

    @Test
    fun `opening the page carries on with an episode stopped partway and looks up only its show`() = runTest {
        val looked = mutableListOf<String>()
        val partway = WatchProgress(
            contentId = "b", contentType = "series", name = "b", poster = null, backdrop = null, logo = null,
            videoId = "b:1:2", season = 1, episode = 2, episodeTitle = null, position = 600_000L, duration = 2_400_000L, lastWatched = 1L
        )
        val pick = pickScoutMixEpisode(listOf(show("a"), show("b"), show("c")), unwatchedOnly = true, resume = show("b") to partway) {
            looked += it.id
            ScoutMixEpisodes(meta(it.id, 3))
        }
        assertEquals("b:1:2", (pick as ScoutMixPick.Episode).video.id)
        assertEquals(listOf("b"), looked)
    }

    @Test
    fun `later picks in the same visit are not pulled back to the stopped episode`() = runTest {
        val partway = WatchProgress(
            contentId = "a", contentType = "series", name = "a", poster = null, backdrop = null, logo = null,
            videoId = "a:1:1", season = 1, episode = 1, episodeTitle = null, position = 600_000L, duration = 2_400_000L, lastWatched = 1L
        )
        val pick = pickScoutMixEpisode(
            listOf(show("a")), unwatchedOnly = true, played = setOf("a" to "a:1:1"), current = "a" to "a:1:1",
            resume = show("a") to partway
        ) { ScoutMixEpisodes(meta(it.id, 3)) }
        assertTrue((pick as ScoutMixPick.Episode).video.id != "a:1:1")
    }

    @Test
    fun `a show that fails is skipped`() = runTest {
        val pick = pickScoutMixEpisode(listOf(show("bad"), show("good")), unwatchedOnly = true, random = Random(1)) {
            if (it.id == "bad") null else ScoutMixEpisodes(meta("good", 1))
        }
        assertEquals("good", (pick as ScoutMixPick.Episode).show.id)
    }

    @Test
    fun `the show just played goes last`() = runTest {
        val shows = listOf(show("a"), show("b"))
        repeat(50) { seed ->
            val pick = pickScoutMixEpisode(shows, unwatchedOnly = true, lastShowId = "a", random = Random(seed)) {
                ScoutMixEpisodes(meta(it.id, 3))
            }
            assertEquals("b", (pick as ScoutMixPick.Episode).show.id)
        }
    }

    @Test
    fun `the show just played comes back when nothing else is left`() = runTest {
        val pick = pickScoutMixEpisode(listOf(show("a"), show("b")), unwatchedOnly = true, lastShowId = "a") {
            if (it.id == "b") ScoutMixEpisodes(meta("b", 1), watched = setOf(1 to 1)) else ScoutMixEpisodes(meta("a", 2))
        }
        assertEquals("a", (pick as ScoutMixPick.Episode).show.id)
    }

    @Test
    fun `unwatched only leaves out what was watched`() = runTest {
        val pick = pickScoutMixEpisode(listOf(show("a")), unwatchedOnly = true) {
            ScoutMixEpisodes(meta("a", 3), watched = setOf(1 to 1, 1 to 2))
        }
        assertEquals("a:1:3", (pick as ScoutMixPick.Episode).video.id)
    }

    @Test
    fun `an episode played this session counts as seen before the history catches up`() = runTest {
        val pick = pickScoutMixEpisode(listOf(show("a")), unwatchedOnly = true, played = setOf("a" to "a:1:1")) {
            ScoutMixEpisodes(meta("a", 1))
        }
        assertEquals(ScoutMixPick.AllSeen, pick)
    }

    @Test
    fun `everything seen says so`() = runTest {
        val pick = pickScoutMixEpisode(listOf(show("a")), unwatchedOnly = true) {
            ScoutMixEpisodes(meta("a", 1), watched = setOf(1 to 1))
        }
        assertEquals(ScoutMixPick.AllSeen, pick)
    }

    @Test
    fun `no lookup answering is not the same as everything seen`() = runTest {
        assertEquals(ScoutMixPick.Unavailable, pickScoutMixEpisode(listOf(show("a")), unwatchedOnly = true) { null })
        assertEquals(ScoutMixPick.NoShows, pickScoutMixEpisode(emptyList(), unwatchedOnly = true) { null })
    }

    @Test
    fun `remix avoids the current episode until it is the only one`() = runTest {
        val current = "a" to "a:1:1"
        repeat(30) { seed ->
            val pick = pickScoutMixEpisode(listOf(show("a")), unwatchedOnly = false, current = current, random = Random(seed)) {
                ScoutMixEpisodes(meta("a", 2))
            }
            assertEquals("a:1:2", (pick as ScoutMixPick.Episode).video.id)
        }
        val only = pickScoutMixEpisode(listOf(show("a")), unwatchedOnly = true, current = current) { ScoutMixEpisodes(meta("a", 1)) }
        assertEquals("a:1:1", (only as ScoutMixPick.Episode).video.id)
    }

    @Test
    fun `with watched episodes allowed the pool starts over without repeating the last one`() = runTest {
        val played = setOf("a" to "a:1:1", "a" to "a:1:2")
        repeat(30) { seed ->
            val pick = pickScoutMixEpisode(
                listOf(show("a")), unwatchedOnly = false, played = played, lastPlayed = "a" to "a:1:2", random = Random(seed)
            ) { ScoutMixEpisodes(meta("a", 2)) }
            assertEquals("a:1:1", (pick as ScoutMixPick.Episode).video.id)
        }
    }

    @Test
    fun `a show is the same show under its other id`() {
        val mix = ScoutMix("m", "M", listOf(ScoutMixShow("kitsu:1", "A", imdbId = "tt1")))
        assertTrue(mix.holds(ScoutMixShow("tt1", "A")))
        assertTrue(mix.holds(ScoutMixShow("x", "A", imdbId = "tt1")))
        assertFalse(mix.holds(ScoutMixShow("tt2", "B")))
        assertEquals(1, mix.adding(ScoutMixShow("tt1", "A")).shows.size)
        assertEquals(0, mix.removing(ScoutMixShow("tt1", "A")).shows.size)
    }

    @Test
    fun `only shows can go in a mix`() {
        listOf("series", "TV", "anime").forEach { assertTrue(ScoutMixes.canHold(it)) }
        listOf("movie", "other", null).forEach { assertFalse(ScoutMixes.canHold(it)) }
    }

    @Test
    fun `suggested name reads from the shows`() {
        assertEquals("Ted mix", suggestScoutMixName(listOf(show("Ted", listOf("Comedy"))), emptyList(), words))
        assertEquals(
            "Comedy mix",
            suggestScoutMixName(listOf(show("Ted", listOf("Drama", "Comedy")), show("Parks", listOf("comedy"))), emptyList(), words)
        )
        assertEquals("Ted & Bear", suggestScoutMixName(listOf(show("Ted", listOf("Comedy")), show("Bear")), emptyList(), words))
        assertEquals("A, B & 2 more", suggestScoutMixName(listOf(show("A"), show("B"), show("C"), show("D")), emptyList(), words))
    }

    @Test
    fun `suggested name stays clear of other mixes and within the limit`() {
        assertEquals("Ted mix 2", suggestScoutMixName(listOf(show("Ted")), listOf("TED MIX"), words))
        assertEquals("Ted mix 3", suggestScoutMixName(listOf(show("Ted")), listOf("Ted mix", "Ted mix 2"), words))
        val long = "x".repeat(200)
        assertEquals(ScoutMixes.NAME_LIMIT, suggestScoutMixName(listOf(show(long)), emptyList(), words).length)
    }

    @Test
    fun `tile picture fills the whole frame whatever the number of shows`() {
        (1..4).forEach { count ->
            val cells = scoutMixArtCells(count)
            assertEquals(count, cells.size)
            val area = cells.sumOf { ((it.right - it.left) * (it.bottom - it.top)).toDouble() }
            assertEquals(1.0, area, 0.001)
        }
        assertEquals(4, scoutMixArtCells(9).size)
        assertTrue(scoutMixArtCells(0).isEmpty())
    }

    @Test
    fun `a show is looked up for its logo once, even when it has none`() {
        val shows = listOf(ScoutMixShow("a", "A"), ScoutMixShow("b", "B"), ScoutMixShow("c", "C"), ScoutMixShow("d", "D", logo = "kept"))
        val after = withFoundLogos(shows, mapOf("a" to "la", "b" to "", "d" to "other"))
        assertEquals(listOf("la", null, null, "other"), after.map { it.logo })
        assertEquals(listOf(false, false, true, false), after.map { it.needsLogoLookup() })
        assertEquals("kept", withFoundLogos(shows, mapOf("d" to ""))[3].logo)
    }

    @Test
    fun `dealt posters stay on the card, on its far half, with the first one drawn last`() {
        for (count in 1..6) {
            val cards = scoutMixDeck(count)
            assertEquals(minOf(count, 4), cards.size)
            cards.forEach { card ->
                assertTrue(card.left >= .45f && card.left + card.width <= 1f)
                assertTrue(card.top > 0f && card.top + card.height < 1f)
            }
            assertEquals(cards.minOf { it.left }, cards.last().left)
        }
        assertTrue(scoutMixDeck(0).isEmpty())
    }

    @Test
    fun `right to left deals the same posters at the other edge`() {
        val ltr = scoutMixDeck(3)
        val rtl = scoutMixDeck(3, mirrored = true)
        ltr.zip(rtl).forEach { (a, b) ->
            assertEquals(1f - a.left - a.width, b.left, 1e-4f)
            assertEquals(-a.degrees, b.degrees, 1e-4f)
            assertTrue(b.left + b.width <= .55f)
        }
    }

    @Test
    fun `hero panels cover the whole width and meet on the same slant`() {
        assertEquals(listOf(ScoutMixHeroPanel(0f, 1f, 1f, 0f)), scoutMixHeroPanels(1))
        for (count in 2..5) {
            val panels = scoutMixHeroPanels(count)
            assertEquals(minOf(count, 3), panels.size)
            assertEquals(0f, panels.first().topLeft)
            assertEquals(0f, panels.first().bottomLeft)
            assertEquals(1f, panels.last().topRight)
            assertEquals(1f, panels.last().bottomRight)
            panels.zipWithNext().forEach { (a, b) ->
                assertEquals(a.topRight, b.topLeft, 1e-4f)
                assertEquals(a.bottomRight, b.bottomLeft, 1e-4f)
            }
        }
        assertTrue(scoutMixHeroPanels(0).isEmpty())
    }
}
