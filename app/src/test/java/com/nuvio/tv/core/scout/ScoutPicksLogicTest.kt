package com.nuvio.tv.core.scout

import com.nuvio.tv.domain.model.ScoutExclusions
import com.nuvio.tv.domain.model.ScoutPick
import com.nuvio.tv.domain.model.ScoutRating
import com.nuvio.tv.domain.model.ScoutRatingEntry
import com.nuvio.tv.domain.model.ScoutSeed
import com.nuvio.tv.domain.model.ScoutTitle
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoutPicksLogicTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 1000 * day

    private fun film(id: String, at: Long = now - day) = WatchedItem(id, "movie", id, watchedAt = at)
    private fun episode(id: String, number: Int, at: Long) = WatchedItem(id, "series", id, 1, number, at)
    private fun rated(id: String, rating: ScoutRating) = ScoutRatingEntry(ScoutTitle(id, "movie", id), rating, now)
    private fun progress(id: String, type: String, position: Long, duration: Long, at: Long, episode: Int? = null) = WatchProgress(
        contentId = id, contentType = type, name = id, poster = null, backdrop = null, logo = null, videoId = id,
        season = episode?.let { 1 }, episode = episode, episodeTitle = null, position = position, duration = duration, lastWatched = at
    )

    private fun pick(id: Int, rating: Float = 7f, votes: Int = 1000, genres: List<Int> = listOf(18)) =
        ScoutPick(id, false, "t$id", rating = rating, votes = votes, genres = genres)

    private fun facts(
        key: String,
        words: List<String> = emptyList(),
        crew: List<Int> = emptyList(),
        language: String = "en",
        collection: Int? = null,
        votes: Int = 1000,
        genres: List<Int> = listOf(18)
    ) = ScoutTasteTitle(
        contentId = key,
        genres = genres,
        people = crew.map { ScoutTastePerson(it, "p$it", crew = true) },
        keywords = words.mapIndexed { i, w -> ScoutKeyword(w.hashCode() + i, w) },
        language = language,
        collection = collection,
        votes = votes,
        fetchedAt = now,
        ageKnown = true,
        factsVersion = 2
    )

    @Test
    fun `a taste row holds only its genre and counts series genres as the film one`() {
        val comedy = pick(1, genres = listOf(35))
        val adventure = pick(2, genres = listOf(12, 14))
        val seriesAction = pick(3, genres = listOf(10759))
        assertEquals(listOf(comedy), scoutInRowGenre(listOf(comedy, adventure, seriesAction), 35))
        assertEquals(listOf(seriesAction), scoutInRowGenre(listOf(comedy, adventure, seriesAction), 28))
        assertEquals(3, scoutInRowGenre(listOf(comedy, adventure, seriesAction), null).size)
    }

    @Test
    fun `a row names a keyword only when most of its titles carry it`() {
        val picks = (1..4).map { pick(it) }
        val facts = picks.take(3).associate { it.key to facts(it.key, words = listOf("heist")) } +
            (picks[3].key to facts(picks[3].key, words = listOf("sitcom")))
        assertEquals("heist", scoutRowKeyword(listOf("sitcom", "heist"), picks, facts))
        assertNull(scoutRowKeyword(listOf("sitcom"), picks, facts))
        assertNull(scoutRowKeyword(listOf("heist"), picks + (5..8).map { pick(it) }, facts))
        assertNull(scoutRowKeyword(emptyList(), picks, facts))
    }

    @Test
    fun `age levels follow the us ratings and unknown stays unknown`() {
        assertEquals(1, scoutAgeLevel("PG", false))
        assertEquals(3, scoutAgeLevel("TV-MA", true))
        assertEquals(0, scoutAgeLevel("TV-Y7", true))
        assertNull(scoutAgeLevel("NR", false))
        assertNull(scoutAgeLevel(null, true))
    }

    @Test
    fun `the floor is the lowest level the profile really watches`() {
        assertNull(scoutAgeFloor(List(4) { 3 to 1.0 }))
        assertEquals(2, scoutAgeFloor(List(9) { 3 to 1.0 } + (1 to 1.0)))
        assertEquals(1, scoutAgeFloor(List(7) { 3 to 1.0 } + List(3) { 1 to 1.0 }))
        assertEquals(0, scoutAgeFloor(List(6) { 0 to 1.0 }))
    }

    @Test
    fun `a pick below the floor is pushed down but not removed, unknown ratings are left alone`() {
        val adult = (1..6).map { facts("h$it").copy(ageLevel = 3) to 1.0 }
        val taste = scoutTasteVector(adult)
        assertEquals(2, taste.ageFloor)
        val low = pick(1)
        val score = { level: Int? -> scoutScore(low, 1.0, facts(low.key).copy(ageLevel = level), taste, emptyMap(), 1.0) }
        assertEquals(score(2), score(null), 0.0001)
        assertEquals(score(2), score(3), 0.0001)
        assertEquals(score(2) - 0.6, score(1), 0.0001)
        assertEquals(score(2) - 1.2, score(0), 0.0001)
    }

    @Test
    fun `facts saved before age ratings are looked up again`() {
        assertFalse(facts("a").copy(ageKnown = false).fresh(now + 1000))
    }

    @Test
    fun `love beats like beats finished and a not for me is negative`() {
        val signals = scoutSignals(
            listOf(film("a"), film("b"), film("c"), film("d")),
            emptyList(),
            listOf(rated("a", ScoutRating.LOVE), rated("b", ScoutRating.LIKE), rated("d", ScoutRating.NOT_FOR_ME)),
            now
        ).associateBy { it.contentId }
        assertTrue(signals.getValue("a").weight > signals.getValue("b").weight)
        assertTrue(signals.getValue("b").weight > signals.getValue("c").weight)
        assertTrue(signals.getValue("d").weight < 0)
    }

    @Test
    fun `a film started again after finishing counts as rewatched`() {
        val signals = scoutSignals(
            listOf(film("again", now - 10 * day), film("once", now - 10 * day)),
            listOf(progress("again", "movie", 10, 100, now - day)),
            emptyList(),
            now
        ).associateBy { it.contentId }
        assertTrue(signals.getValue("again").weight > signals.getValue("once").weight)
    }

    @Test
    fun `a series left after two episodes counts against it only once two weeks have passed`() {
        val old = scoutSignals(listOf(episode("s", 1, now - 30 * day), episode("s", 2, now - 30 * day)), emptyList(), emptyList(), now)
        assertTrue(old.single().weight < 0)
        val fresh = scoutSignals(listOf(episode("s", 1, now - 2 * day)), emptyList(), emptyList(), now)
        assertTrue(fresh.single().weight > 0)
        val many = scoutSignals((1..3).map { episode("s", it, now - 30 * day) }, emptyList(), emptyList(), now)
        assertTrue(many.single().weight > 0)
    }

    @Test
    fun `a film stopped under a fifth is a mild minus, a rated one keeps its rating`() {
        val stopped = scoutSignals(emptyList(), listOf(progress("f", "movie", 10, 100, now - 30 * day)), emptyList(), now)
        assertEquals(-1.0, stopped.single().weight, 0.001)
        val half = scoutSignals(emptyList(), listOf(progress("f", "movie", 50, 100, now - 30 * day)), emptyList(), now)
        assertTrue(half.isEmpty())
        val rated = scoutSignals(emptyList(), listOf(progress("f", "movie", 10, 100, now - 30 * day)), listOf(rated("f", ScoutRating.LOVE)), now)
        assertEquals(6.0, rated.single().weight, 0.001)
    }

    @Test
    fun `a profile of young titles gets a ceiling, any real teen or adult share removes it`() {
        assertEquals(0, scoutAgeCeiling(List(6) { 0 to 1.0 }))
        assertEquals(1, scoutAgeCeiling(List(8) { 0 to 1.0 } + List(2) { 1 to 1.0 }))
        assertEquals(0, scoutAgeCeiling(List(30) { 0 to 1.0 } + (3 to 1.0)))
        assertEquals(1, scoutAgeCeiling(List(25) { 0 to 1.0 } + List(4) { 1 to 1.0 } + (3 to 1.0)))
        assertNull(scoutAgeCeiling(List(2) { 0 to 1.0 }))
        assertNull(scoutAgeCeiling(List(5) { 3 to 1.0 }))
    }

    @Test
    fun `a family profile with parents and kids is not capped`() {
        val kids = List(12) { 0 to 1.0 } + List(4) { 1 to 1.0 }
        val parents = List(3) { 3 to 1.0 } + (2 to 1.0)
        assertNull(scoutAgeCeiling(kids + parents))
        val adult = scoutTasteVector((1..6).map { facts("h$it").copy(ageLevel = 3) to 1.0 })
        assertEquals(2, adult.ageFloor)
        assertNull(scoutAgeCeiling((1..6).map { 3 to 1.0 }))
    }

    @Test
    fun `under a ceiling only suitable titles stay, unknown ones only when they are for families`() {
        val family = pick(1, genres = listOf(16, 10751))
        val kidsShow = pick(2, genres = listOf(10762))
        val anime = pick(3, genres = listOf(16, 10759))
        val crime = pick(4, genres = listOf(10751, 80))
        assertTrue(scoutWithinCeiling(anime, 3, null))
        assertTrue(scoutWithinCeiling(anime, 1, 1))
        assertFalse(scoutWithinCeiling(family, 2, 1))
        assertTrue(scoutWithinCeiling(family, null, 0))
        assertTrue(scoutWithinCeiling(kidsShow, null, 0))
        assertFalse(scoutWithinCeiling(anime, null, 1))
        assertFalse(scoutWithinCeiling(crime, null, 1))
    }

    @Test
    fun `titles being watched seed only when finished ones are too few`() {
        val watching = scoutWatchingSeeds(
            listOf(progress("s1", "series", 10, 100, now - day, 1), progress("s2", "series", 10, 100, now - 2 * day, 1), progress("m1", "movie", 50, 100, now)),
            emptyList()
        )
        assertEquals(listOf("m1", "s1", "s2"), watching.map { it.contentId })
        assertTrue(watching.all { it.weight in 0.0..0.5 && it.finishedOrder == null })
        val one = scoutSignals(listOf(film("f1")), emptyList(), emptyList(), now)
        assertEquals(listOf("f1", "m1", "s1", "s2"), scoutSignalSeeds(one, watching).map { it.contentId })
        val three = scoutSignals((1..3).map { film("f$it", now - it * day) }, emptyList(), emptyList(), now)
        assertEquals(3, scoutSignalSeeds(three, watching).size)
        assertTrue(scoutWatchingSeeds(listOf(progress("f1", "movie", 50, 100, now)), one).isEmpty())
    }

    @Test
    fun `seeds are capped and picks work from history without any ratings`() {
        val signals = scoutSignals((1..30).map { film("m$it", now - it * day) }, emptyList(), emptyList(), now)
        assertEquals(SCOUT_PICKS_SEEDS, scoutSignalSeeds(signals).size)
    }

    @Test
    fun `tally drops blocked titles and those with too few votes, and unnamed sources give no seed name`() {
        val tally = scoutTally(
            listOf(
                ScoutSeed("x", false, "Seed", 2.0, 0) to listOf(pick(1), pick(2, votes = 10), pick(3)),
                ScoutSeed("", false, "", 2.0, null) to listOf(pick(4))
            ),
            ScoutExclusions(tmdbKeys = setOf("movie:3"))
        )
        assertEquals(setOf("movie:1", "movie:4"), tally.keys)
        assertEquals("Seed", tally.getValue("movie:1").first.seedName)
        assertEquals(null, tally.getValue("movie:4").first.seedName)
    }

    @Test
    fun `a title under the rating floor is dropped, one with no rating stays`() {
        val tally = scoutTally(
            listOf(ScoutSeed("x", false, "Seed", 2.0, 0) to listOf(pick(1, rating = 5.9f), pick(2, rating = 6f), ScoutPick(3, false, "t3", votes = 1000))),
            ScoutExclusions()
        )
        assertEquals(setOf("movie:2", "movie:3"), tally.keys)
    }

    @Test
    fun `facts become a pick only with a name and a poster`() {
        val full = facts("movie:5").copy(name = "Five", poster = "p", rating = 7f)
        assertEquals("Five", full.toScoutPick(5, false)?.name)
        assertEquals(null, facts("movie:5").copy(name = "Five").toScoutPick(5, false))
        assertEquals(null, facts("movie:5").copy(poster = "p").toScoutPick(5, false))
    }

    @Test
    fun `a fresh row sorts by taste and skips what is blocked or barely voted on`() {
        val taste = scoutTasteVector(listOf(facts("a", listOf("space", "ai"), listOf(9)) to 6.0))
        val titles = (1..12).map { pick(it, votes = 100) } + pick(50, votes = 5) + pick(51, votes = 100)
        val all = titles.associate { it.key to facts(it.key, listOf("space", "ai")) } +
            ("movie:12" to facts("movie:12", listOf("space", "ai"), listOf(9))) + ("movie:1" to facts("movie:1", listOf("wedding")))
        val row = scoutFreshRow(titles, all, taste, mapOf("space" to 2, "ai" to 2), ScoutExclusions(tmdbKeys = setOf("movie:51")), setOf("movie:2"))
        assertEquals(12, row.first().tmdbId)
        assertFalse(row.any { it.tmdbId in setOf(50, 51, 2) })
    }

    @Test
    fun `a fresh row with too few matches stays hidden`() {
        val taste = scoutTasteVector(listOf(facts("a", listOf("space"), listOf(9)) to 6.0))
        val titles = (1..20).map { pick(it, votes = 100) }
        val all = titles.associate { it.key to facts(it.key, listOf("wedding")) }
        assertTrue(scoutFreshRow(titles, all, taste, emptyMap(), ScoutExclusions(), emptySet()).isEmpty())
    }

    @Test
    fun `a taste row keeps its id when others come and go, and two of a kind stay apart`() {
        val sci = com.nuvio.tv.domain.model.ScoutTasteRow(genre = 878, language = "en")
        val ko = com.nuvio.tv.domain.model.ScoutTasteRow(genre = 53, language = "ko")
        assertEquals(listOf("878_en", "53_ko"), scoutWithRowIds(listOf(sci, ko)).map { it.id })
        assertEquals(listOf("53_ko"), scoutWithRowIds(listOf(ko)).map { it.id })
        assertEquals(listOf("878_en", "878_en_2"), scoutWithRowIds(listOf(sci, sci)).map { it.id })
    }

    @Test
    fun `re-ranking lifts the title that shares the profile's keywords and people`() {
        val own = listOf(facts("a", listOf("time loop", "dystopia"), listOf(9)) to 6.0, facts("b", listOf("time loop"), listOf(9)) to 3.0)
        val taste = scoutTasteVector(own)
        val tally = mapOf("movie:1" to (pick(1) to 5.0), "movie:2" to (pick(2) to 4.0))
        val all = mapOf(
            "movie:1" to facts("movie:1", listOf("wedding")),
            "movie:2" to facts("movie:2", listOf("time loop", "dystopia"), listOf(9))
        )
        assertEquals(listOf(2, 1), scoutReRank(tally, all, taste, 100).map { it.tmdbId })
    }

    @Test
    fun `disliked keywords push a title down`() {
        val taste = scoutTasteVector(listOf(facts("a", listOf("space")) to 6.0, facts("n", listOf("zombie")) to -3.0))
        val tally = mapOf("movie:1" to (pick(1) to 5.0), "movie:2" to (pick(2) to 5.0))
        val all = mapOf("movie:1" to facts("movie:1", listOf("zombie")), "movie:2" to facts("movie:2", listOf("garden")))
        assertEquals(listOf(2, 1), scoutReRank(tally, all, taste, 100).map { it.tmdbId })
    }

    @Test
    fun `a popular title is marked down for a profile that likes obscure ones`() {
        val taste = scoutTasteVector((1..5).map { facts("o$it", votes = 300) to 3.0 })
        val tally = mapOf("movie:1" to (pick(1, votes = 30000) to 5.0), "movie:2" to (pick(2, votes = 400) to 5.0))
        val all = mapOf("movie:1" to facts("movie:1", votes = 30000), "movie:2" to facts("movie:2", votes = 400))
        assertEquals(listOf(2, 1), scoutReRank(tally, all, taste, 100).map { it.tmdbId })
    }

    @Test
    fun `language is a lift and never a filter`() {
        val taste = scoutTasteVector((1..4).map { facts("k$it", language = "ko") to 3.0 })
        val tally = mapOf("movie:1" to (pick(1) to 5.0), "movie:2" to (pick(2) to 5.0))
        val all = mapOf("movie:1" to facts("movie:1", language = "en"), "movie:2" to facts("movie:2", language = "ko"))
        val ranked = scoutReRank(tally, all, taste, 100)
        assertEquals(2, ranked.first().tmdbId)
        assertEquals(2, ranked.size)
    }

    @Test
    fun `one of a film series and three by one person stay up top, the rest move down but stay in the pool`() {
        val picks = (1..6).map { pick(it) }
        val all = mapOf(
            "movie:1" to facts("movie:1", collection = 7),
            "movie:2" to facts("movie:2", collection = 7),
            "movie:3" to facts("movie:3", crew = listOf(5)),
            "movie:4" to facts("movie:4", crew = listOf(5)),
            "movie:5" to facts("movie:5", crew = listOf(5)),
            "movie:6" to facts("movie:6", crew = listOf(5))
        )
        assertEquals(listOf(1, 3, 4, 5, 2, 6), scoutLimitDuplicates(picks, all).map { it.tmdbId })
    }

    @Test
    fun `nearly the same keywords count as the same idea`() {
        val words = listOf("heist", "vault", "crew")
        val all = mapOf("movie:1" to facts("movie:1", words), "movie:2" to facts("movie:2", words), "movie:3" to facts("movie:3", listOf("farm")))
        assertEquals(listOf(1, 3, 2), scoutLimitDuplicates(listOf(pick(1), pick(2), pick(3)), all).map { it.tmdbId })
    }

    @Test
    fun `about one pick in five is a stretch from outside the main language or genres`() {
        val picks = (1..60).map { pick(it, rating = 7.5f, genres = if (it > 30) listOf(99) else listOf(18)) }
        val taste = scoutTasteVector((1..3).map { facts("o$it") to 3.0 })
        val all = picks.associate { it.key to facts(it.key) }
        val out = scoutAddStretch(picks, all, taste)
        assertEquals(picks.size, out.map { it.key }.toSet().size)
        val stretches = out.take(20).filter { it.stretch }
        assertEquals(4, stretches.size)
        assertTrue(stretches.all { 99 in it.genres })
        assertFalse(out.first().stretch)
    }

    @Test
    fun `reasons name a loved person and a shared keyword only when true`() {
        val own = listOf(facts("a", listOf("time loop"), listOf(9)) to 6.0, facts("b", listOf("time loop"), listOf(8)) to 1.0)
        val taste = scoutTasteVector(own, setOf("a"))
        val frequency = mapOf("time loop" to 3)
        val loved = scoutWithReason(pick(1), facts("movie:1", listOf("time loop"), listOf(9)), taste, frequency)
        assertEquals("p9", loved.whyPerson)
        assertTrue(loved.whyLoved)
        assertEquals("time loop", loved.whyKeyword)
        val watched = scoutWithReason(pick(1), facts("movie:1", crew = listOf(8)), taste, frequency)
        assertFalse(watched.whyLoved)
        val none = scoutWithReason(pick(1), facts("movie:1", listOf("other"), listOf(100)), taste, frequency)
        assertEquals(null, none.whyPerson)
        assertEquals(null, none.whyKeyword)
    }

    @Test
    fun `two different tastes become two groups, one taste stays one`() {
        val scifi = (1..4).map { facts("s$it", listOf("space", "ai", "w$it"), genres = listOf(878)) to 3.0 }
        val sitcom = (1..4).map { facts("c$it", listOf("office", "family", "x$it"), genres = listOf(35)) to 2.0 }
        val groups = scoutTasteGroups(scifi + sitcom)
        assertEquals(2, groups.size)
        assertEquals(setOf(878, 35), groups.map { it.genre }.toSet())
        assertEquals(1, scoutTasteGroups(scifi).size)
    }

    @Test
    fun `korean titles form their own group by language`() {
        val korean = (1..3).map { facts("k$it", listOf("revenge"), language = "ko", genres = listOf(53)) to 3.0 }
        val other = (1..3).map { facts("e$it", listOf("garden"), genres = listOf(10751)) to 2.0 }
        val groups = scoutTasteGroups(korean + other)
        assertTrue(groups.any { it.language == "ko" })
    }

    @Test
    fun `a pick shown over two weeks and never opened rests, opened ones do not`() {
        var entry = scoutShownToday(null, now)
        entry = scoutShownToday(entry, now)
        assertEquals(1, entry.days)
        (1..6).forEach { entry = scoutShownToday(entry, now + it * 3 * day) }
        assertEquals(7, entry.days)
        val at = now + 19 * day
        assertEquals(setOf("movie:1"), scoutResting(mapOf("movie:1" to entry), emptySet(), at))
        assertTrue(scoutResting(mapOf("movie:1" to entry), setOf("movie:1"), at).isEmpty())
        assertTrue(scoutResting(mapOf("movie:1" to entry), emptySet(), at + 100 * day).isEmpty())
    }

    @Test
    fun `old cached facts count as stale for the TMDB limit`() {
        val title = facts("a")
        assertTrue(title.fresh(now + day))
        assertFalse(title.fresh(now + 181 * day))
        assertFalse(title.copy(fetchedAt = 0).fresh(now + 365 * day))
    }
}
