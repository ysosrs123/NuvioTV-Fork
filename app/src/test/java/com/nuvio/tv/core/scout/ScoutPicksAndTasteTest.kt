package com.nuvio.tv.core.scout

import com.nuvio.tv.domain.model.ScoutExclusions
import com.nuvio.tv.domain.model.ScoutFeature
import com.nuvio.tv.domain.model.ScoutPick
import com.nuvio.tv.domain.model.ScoutPool
import com.nuvio.tv.domain.model.ScoutProfile
import com.nuvio.tv.domain.model.ScoutRating
import com.nuvio.tv.domain.model.ScoutRatingEntry
import com.nuvio.tv.domain.model.ScoutTitle
import com.nuvio.tv.domain.model.WatchedItem
import com.nuvio.tv.ui.screens.scout.scoutEpisodeFits
import com.nuvio.tv.ui.screens.scout.scoutShapingTitles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoutPicksAndTasteTest {
    private fun pick(id: Int, rating: Float = 7f, votes: Int = 10000, seed: String? = null, genres: List<Int> = emptyList()) =
        ScoutPick(tmdbId = id, tv = false, name = "T$id", rating = rating, votes = votes, seedName = seed, genres = genres)

    private fun entry(id: String, rating: ScoutRating, at: Long = 0, poster: String? = null) =
        ScoutRatingEntry(ScoutTitle(id, "movie", id, poster), rating, at)

    private fun watched(id: String, at: Long, type: String = "movie", poster: String? = null) =
        WatchedItem(contentId = id, contentType = type, title = id, season = null, episode = null, watchedAt = at, poster = poster)

    @Test
    fun `the reason says only what is known, shared picks first`() {
        assertEquals(ScoutWhy.SuitsEveryone, scoutWhy(pick(1, seed = "Ted"), null, suitsEveryone = true))
        assertEquals(ScoutWhy.Close("Ted"), scoutWhy(pick(1, seed = "Ted"), ScoutPickDetail(ended = true)))
        assertEquals(ScoutWhy.Finished, scoutWhy(pick(1), ScoutPickDetail(ended = true)))
        assertEquals(ScoutWhy.Gem, scoutWhy(pick(1, 8f, 1000), null))
        assertEquals(ScoutWhy.WellRated, scoutWhy(pick(1, 7.2f), null))
        assertEquals(ScoutWhy.New, scoutWhy(pick(1, 5f), null))
        assertEquals(ScoutWhy.New, scoutWhy(ScoutPick(tmdbId = 1, tv = false, name = "x"), null))
    }

    @Test
    fun `a long show does not count as a quick episode`() {
        assertFalse(scoutEpisodeFits(ScoutPickDetail(minutes = 42)))
        assertTrue(scoutEpisodeFits(ScoutPickDetail(minutes = 22)))
        assertTrue(scoutEpisodeFits(ScoutPickDetail(minutes = null)))
        assertTrue(scoutEpisodeFits(null))
    }

    @Test
    fun `ordering keeps out what must never be offered`() {
        val out = scoutOrderPicks(
            listOf(pick(1), pick(2), pick(3, genres = listOf(27)), pick(2)),
            null,
            ScoutExclusions(tmdbKeys = setOf("movie:1"), genres = setOf(27)),
            1L
        )
        assertEquals(listOf("movie:2"), out.map { it.key })
    }

    @Test
    fun `titles from the profile's pool come first and carry their seed`() {
        val pool = ScoutPool(picked = listOf(pick(9, seed = "Parks")))
        repeat(20) { seed ->
            val out = scoutOrderPicks(listOf(pick(1, 9f), pick(2, 9f), pick(9)), pool, ScoutExclusions(), seed.toLong())
            assertEquals(9, out.first().tmdbId)
            assertEquals("Parks", out.first().seedName)
        }
    }

    @Test
    fun `the same seed gives the same order, another usually differs`() {
        val list = (1..30).map { pick(it) }
        val a = scoutOrderPicks(list, null, ScoutExclusions(), 5L).map { it.tmdbId }
        assertEquals(a, scoutOrderPicks(list, null, ScoutExclusions(), 5L).map { it.tmdbId })
        assertFalse(a == scoutOrderPicks(list, null, ScoutExclusions(), 6L).map { it.tmdbId })
    }

    @Test
    fun `anything anyone in the room has seen stays out`() {
        val other = ScoutGroupMember(null, ScoutExclusions(tmdbKeys = setOf("movie:1")))
        val out = scoutOrderPicks(listOf(pick(1), pick(2)), null, ScoutExclusions(), 1L, listOf(other))
        assertEquals(listOf("movie:2"), out.map { it.key })
    }

    @Test
    fun `with two people a title counts by whoever it suits least`() {
        val mine = ScoutPool(picked = listOf(pick(9)))
        val theirs = ScoutGroupMember(ScoutPool(picked = listOf(pick(8), pick(7))), ScoutExclusions())
        val both = ScoutGroupMember(ScoutPool(picked = listOf(pick(7))), ScoutExclusions())
        val mineAndBoth = ScoutPool(picked = listOf(pick(9), pick(7)))
        repeat(20) { seed ->
            val out = scoutOrderPicks(listOf(pick(9), pick(8), pick(7)), mineAndBoth, ScoutExclusions(), seed.toLong(), listOf(both))
            assertEquals(7, out.first().tmdbId)
        }
        repeat(20) { seed ->
            val out = scoutOrderPicks(listOf(pick(9), pick(8)), mine, ScoutExclusions(), seed.toLong(), listOf(theirs))
            assertEquals(2, out.size)
        }
    }

    @Test
    fun `bars are shares of the top value, loved counts more, not for me not at all`() {
        val titles = listOf(
            ScoutTasteTitle("a", genres = listOf(18), decade = 2010, countries = listOf("US")),
            ScoutTasteTitle("b", genres = listOf(35), decade = 2020, countries = listOf("GB")),
            ScoutTasteTitle("c", genres = listOf(27), decade = 1980, countries = listOf("FR"))
        )
        val s = scoutTasteSummary(titles, mapOf("a" to entry("a", ScoutRating.LOVE), "c" to entry("c", ScoutRating.NOT_FOR_ME)))
        assertEquals(listOf("18", "35"), s.genres.map { it.key })
        assertEquals(1f, s.genres[0].share, 0.001f)
        assertEquals(1f / 3f, s.genres[1].share, 0.001f)
        assertFalse(s.decades.any { it.key == "1980" })
    }

    @Test
    fun `decades run oldest to newest and name the two biggest`() {
        val titles = listOf(2020, 2020, 2020, 1990, 1990, 1970).mapIndexed { i, d -> ScoutTasteTitle("t$i", decade = d) }
        val s = scoutTasteSummary(titles, emptyMap())
        assertEquals(listOf("1970", "1990", "2020"), s.decades.map { it.key })
        assertEquals(listOf("1990", "2020"), s.topDecades)
    }

    @Test
    fun `directors and actors come apart, each with its own limit`() {
        val cast = (1..20).map { ScoutTastePerson(it, "A$it") }
        val crew = (101..120).map { ScoutTastePerson(it, "D$it", crew = true) }
        val titles = (1..3).map { ScoutTasteTitle("t$it", people = cast + crew) }
        val s = scoutTasteSummary(titles, emptyMap())
        assertEquals(12, s.directors.size)
        assertTrue(s.directors.all { it.crew })
        assertEquals(12, s.actors.size)
        assertFalse(s.actors.any { it.crew })
    }

    @Test
    fun `a person needs two titles, and one title never counts them twice`() {
        val p = ScoutTastePerson(1, "Denis", crew = true)
        val once = listOf(ScoutTasteTitle("a", people = listOf(p, p)))
        assertTrue(scoutTasteSummary(once, emptyMap()).directors.isEmpty())
        val twice = listOf(ScoutTasteTitle("a", people = listOf(p)), ScoutTasteTitle("b", people = listOf(p)))
        assertEquals(listOf(p), scoutTasteSummary(twice, emptyMap()).directors)
    }

    @Test
    fun `lately needs a real rise among recent titles`() {
        val old = (1..30).map { ScoutTasteTitle("o$it", genres = listOf(878), countries = listOf("US")) }
        val recent = (1..12).map { ScoutTasteTitle("r$it", genres = listOf(18), countries = listOf("KR")) }
        val s = scoutTasteSummary(old + recent, emptyMap(), recent.map { it.contentId }.toSet())
        assertEquals(18, s.latelyGenre)
        assertEquals("KR", s.latelyCountry)
        val flat = scoutTasteSummary(old, emptyMap(), old.take(12).map { it.contentId }.toSet())
        assertNull(flat.latelyGenre)
        assertNull(scoutTasteSummary(old, emptyMap(), setOf("o1")).latelyGenre)
    }

    @Test
    fun `no history gives an empty page`() {
        assertTrue(scoutTasteSummary(emptyList(), emptyMap()).isEmpty)
    }

    @Test
    fun `thumbs count on top of the latest titles, and a disliked title is looked up too`() {
        val many = (1..500).map { watched("w$it", it.toLong()) }
        val targets = scoutTasteTargets(many, listOf(entry("r1", ScoutRating.LOVE), entry("w500", ScoutRating.NOT_FOR_ME)))
        assertEquals(SCOUT_TASTE_TITLES + 1, targets.size)
        assertEquals("w500", targets.first().first)
        assertTrue(targets.any { it.first == "r1" })
        assertTrue(targets.any { it.first == "w500" })
    }

    @Test
    fun `a rewatched title counts once and series are not films`() {
        val t = scoutTasteTargets(listOf(watched("s", 1, "series"), watched("s", 2, "series"), watched("m", 3)), emptyList())
        assertEquals(listOf("m" to false, "s" to true), t)
    }

    @Test
    fun `nudge picks the best known title not rated or skipped`() {
        val pool = ScoutPool(picked = listOf(pick(1, votes = 100), pick(2, votes = 5000), pick(3, votes = 9000)))
        assertEquals(3, scoutNudge(pool, ScoutProfile(), emptySet())?.tmdbId)
        assertEquals(2, scoutNudge(pool, ScoutProfile(), setOf("movie:3"))?.tmdbId)
        val rated = ScoutProfile(ratings = mapOf("tmdb:3" to entry("tmdb:3", ScoutRating.LIKE)))
        assertEquals(2, scoutNudge(pool, rated, emptySet())?.tmdbId)
        assertNull(scoutNudge(null, ScoutProfile(), emptySet()))
    }

    @Test
    fun `a title rated under another id is not asked about again`() {
        val pool = ScoutPool(picked = listOf(pick(3, votes = 9000), pick(2, votes = 5000)))
        val rated = ScoutProfile(ratings = mapOf("tt1" to ScoutRatingEntry(ScoutTitle("tt1", "movie", "T3"), ScoutRating.LIKE, 0)))
        assertEquals(2, scoutNudge(pool, rated, emptySet())?.tmdbId)
    }

    @Test
    fun `titles shaping the picks lead with thumbs and need a poster`() {
        val profile = ScoutProfile(ratings = mapOf("a" to entry("a", ScoutRating.LOVE, poster = "p")))
        val list = scoutShapingTitles(listOf(watched("b", 1, poster = "q"), watched("c", 2)), profile)
        assertEquals(listOf("a", "b"), list.map { it.id })
        assertEquals(ScoutRating.LOVE, list[0].rating)
        assertNull(list[1].rating)
    }

    @Test
    fun `titles rank by how much was watched and the thumb given`() {
        fun episodes(id: String, count: Int) = (1..count).map { com.nuvio.tv.domain.model.WatchedItem(id, "series", id, 1, it, it.toLong(), "p") }
        val history = episodes("short", 2) + episodes("long", 40) + episodes("loved", 6) + episodes("disliked", 90)
        val profile = ScoutProfile(ratings = mapOf(
            "loved" to entry("loved", ScoutRating.LOVE),
            "disliked" to entry("disliked", ScoutRating.NOT_FOR_ME)
        ))
        assertEquals(listOf("loved", "long", "short"), scoutShapingTitles(history, profile).map { it.id })
    }

    @Test
    fun `people rank by watches and a disliked title pulls them down`() {
        val star = ScoutTastePerson(1, "Star")
        val other = ScoutTastePerson(2, "Other")
        val titles = listOf(
            ScoutTasteTitle("a", people = listOf(star, other)),
            ScoutTasteTitle("b", people = listOf(star, other)),
            ScoutTasteTitle("c", people = listOf(star))
        )
        assertEquals(listOf(star, other), scoutTasteSummary(titles, emptyMap(), watches = mapOf("a" to 30)).actors)
        val disliked = mapOf("c" to entry("c", ScoutRating.NOT_FOR_ME))
        assertEquals("the penalty keeps a disliked star below", other, scoutTasteSummary(titles, disliked, watches = mapOf("a" to 1, "b" to 1)).actors.first())
    }

    @Test
    fun `a failed lookup rests before it is tried again`() {
        val now = 1_000_000_000L
        val failed = ScoutTasteTitle("a", failedAt = now)
        assertFalse(failed.needsLookUp(now + 1000))
        assertTrue(failed.needsLookUp(now + SCOUT_FAILED_RETRY_MS))
        assertTrue((null as ScoutTasteTitle?).needsLookUp(now))
    }

    @Test
    fun `not for me counts at once over an older list`() {
        fun rated(id: String, name: String, rating: ScoutRating) = id to ScoutRatingEntry(ScoutTitle(id, "movie", name), rating, 1)
        val profile = ScoutProfile(ratings = mapOf(
            rated("tmdb:5", "Moon", ScoutRating.NOT_FOR_ME),
            rated("tmdb:6", "Sun", ScoutRating.LOVE)
        ))
        val now = com.nuvio.tv.domain.model.ScoutExclusions().withNotForMe(profile)
        assertTrue(now.blocks(ScoutPick(5, false, "Another name")))
        assertTrue(now.blocks(ScoutPick(7, false, "Moon")))
        assertFalse(now.blocks(ScoutPick(6, false, "Sun")))
    }

    @Test
    fun `shaping tiles use the backdrop already known for a title`() {
        val profile = ScoutProfile(ratings = mapOf("a" to entry("a", ScoutRating.LOVE, poster = "p")))
        val list = scoutShapingTitles(listOf(watched("b", 1, poster = "q")), profile, mapOf("a" to "wide"))
        assertEquals("wide", list.single { it.id == "a" }.backdrop)
        assertNull(list.single { it.id == "b" }.backdrop)
    }

    @Test
    fun `the menu entry follows the switch`() {
        assertEquals(
            ScoutFeature.available,
            com.nuvio.tv.appRootRoutes(discoverInSidebar = false, calendarInMenu = false)
                .contains(com.nuvio.tv.ui.navigation.Screen.Scout.route)
        )
    }
}
