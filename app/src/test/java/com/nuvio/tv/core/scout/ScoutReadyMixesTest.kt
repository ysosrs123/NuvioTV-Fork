package com.nuvio.tv.core.scout

import com.nuvio.tv.domain.model.ScoutPick
import com.nuvio.tv.domain.model.ScoutRating
import com.nuvio.tv.domain.model.ScoutRatingEntry
import com.nuvio.tv.domain.model.ScoutTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class ScoutReadyMixesTest {
    private val today = LocalDate.of(2026, 11, 12)

    @Test
    fun `a recipe survives being written down`() {
        val recipe = ScoutMixRecipe(ScoutMixDecade.NINETIES, ScoutMixKind.SITCOMS, ScoutMixFeel.LAUGH, ScoutMixFrom.UK_IRELAND, ScoutMixBlend.SURPRISE, family = true)
        assertEquals(recipe, ScoutMixRecipe.decode(recipe.encode()))
        assertEquals(ScoutMixRecipe(), ScoutMixRecipe.decode(ScoutMixRecipe().encode()))
    }

    @Test
    fun `damaged recipes are ignored`() {
        assertNull(ScoutMixRecipe.decode(null))
        assertNull(ScoutMixRecipe.decode("o.christmas.f"))
        assertNull(ScoutMixRecipe.decode("r.99.x.x.x.b.x"))
    }

    @Test
    fun `a decade asks for shows that started in it`() {
        val f = ScoutMixRecipe(decade = ScoutMixDecade.NINETIES).filters("AU", today)
        assertEquals("1990-01-01", f.releaseDateGte)
        assertEquals("1999-12-31", f.releaseDateLte)
        assertTrue(f.withGenres!!.contains("35"))
        assertTrue(f.withoutGenres!!.split(',').containsAll(listOf("10763", "10764", "10766", "10767")))
    }

    @Test
    fun `a widened decade reaches its neighbours but not past today`() {
        val f = ScoutMixRecipe(decade = ScoutMixDecade.TWENTIES).filters("AU", today, years = 10)
        assertEquals("2010-01-01", f.releaseDateGte)
        assertEquals("2026-12-31", f.releaseDateLte)
    }

    @Test
    fun `sitcoms leave cartoons out and countries narrow the search`() {
        val f = ScoutMixRecipe(kind = ScoutMixKind.SITCOMS, from = ScoutMixFrom.UK_IRELAND).filters("AU", today)
        assertEquals("35", f.withGenres)
        assertTrue(f.withoutGenres!!.split(',').contains("16"))
        assertEquals("GB|IE", f.withOriginCountry)
        assertEquals(20, f.voteCountGte)
    }

    @Test
    fun `sitcoms ask for the sitcom keyword, and without it leave out shows that are mainly something else`() {
        val strict = ScoutMixRecipe(kind = ScoutMixKind.SITCOMS).filters("AU", today)
        assertEquals("193171|381587|210605", strict.withKeywords)
        val loose = ScoutMixRecipe(kind = ScoutMixKind.SITCOMS).filters("AU", today, strict = false)
        assertNull(loose.withKeywords)
        assertTrue(loose.withoutGenres!!.split(',').containsAll(listOf("18", "10765", "10759", "80", "9648", "16")))
    }

    @Test
    fun `cop shows are crime with police keywords, not mystery or fantasy`() {
        val strict = ScoutMixRecipe(kind = ScoutMixKind.CRIME).filters("AU", today)
        assertEquals("80", strict.withGenres)
        assertEquals("268067|15167", strict.withKeywords)
        assertTrue(strict.withoutGenres!!.split(',').containsAll(listOf("16", "10765")))
        val loose = ScoutMixRecipe(kind = ScoutMixKind.CRIME).filters("AU", today, strict = false)
        assertEquals("268067|15167|6149|703", loose.withKeywords)
    }

    @Test
    fun `sci-fi asks for science fiction or space and leaves anime out`() {
        val f = ScoutMixRecipe(kind = ScoutMixKind.SCI_FI).filters("AU", today)
        assertEquals("281358|9882|3801", f.withKeywords)
        assertTrue(f.withoutGenres!!.split(',').contains("16"))
    }

    @Test
    fun `cartoons leave out cartoons made for adults`() {
        val f = ScoutMixRecipe(kind = ScoutMixKind.CARTOONS).filters("AU", today)
        assertEquals("161919|315444", f.withoutKeywords)
    }

    @Test
    fun `a mood's own keywords win over the kind's`() {
        val f = ScoutMixRecipe(kind = ScoutMixKind.SITCOMS, feel = ScoutMixFeel.LAUGH).filters("AU", today)
        assertTrue(f.withKeywords != "193171|381587|210605")
    }

    @Test
    fun `a country mix keeps shows made only there`() {
        val local = (1..SCOUT_REGION_FEWEST).map { pick(it, listOf("AU")) }
        val clickbait = pick(999, listOf("AU", "US"))
        val kept = scoutFromRegion(local + clickbait, ScoutMixFrom.AUSTRALIA_NZ)
        assertEquals(SCOUT_REGION_FEWEST, kept.size)
        assertFalse(clickbait in kept)
    }

    @Test
    fun `a country with few shows of its own lets in co-productions that name it first`() {
        val local = listOf(pick(1, listOf("CA")))
        val first = pick(2, listOf("CA", "US"))
        val second = pick(3, listOf("US", "CA"))
        assertEquals(listOf(local[0], first), scoutFromRegion(local + first + second, ScoutMixFrom.CANADA))
    }

    @Test
    fun `anywhere keeps every show`() {
        val all = listOf(pick(1, listOf("AU", "US")), pick(2, listOf("KR")))
        assertEquals(all, scoutFromRegion(all, ScoutMixFrom.ANYWHERE))
    }

    private fun pick(id: Int, origin: List<String>) = ScoutPick(tmdbId = id, tv = true, name = "Show $id", origin = origin)

    @Test
    fun `anime is Japanese animation`() {
        val f = ScoutMixRecipe(kind = ScoutMixKind.ANIME).filters("AU", today)
        assertEquals("16", f.withGenres)
        assertEquals("JP", f.withOriginCountry)
    }

    @Test
    fun `family night asks for a family rating`() {
        val f = ScoutMixRecipe(family = true).filters("AU", today)
        assertEquals("AU", f.certificationCountry)
        assertNotNull(f.certificationLte)
    }

    @Test
    fun `easter falls on the right sunday`() {
        assertEquals(LocalDate.of(2025, 4, 20), scoutEaster(2025))
        assertEquals(LocalDate.of(2026, 4, 5), scoutEaster(2026))
        assertEquals(LocalDate.of(2027, 3, 28), scoutEaster(2027))
    }

    @Test
    fun `christmas runs on to new year's day`() {
        assertTrue(ScoutOccasion.CHRISTMAS.inSeason(LocalDate.of(2026, 12, 20)))
        assertTrue(ScoutOccasion.CHRISTMAS.inSeason(LocalDate.of(2027, 1, 1)))
        assertFalse(ScoutOccasion.CHRISTMAS.inSeason(LocalDate.of(2027, 1, 2)))
        assertEquals(LocalDate.of(2026, 12, 1), ScoutOccasion.CHRISTMAS.nextStart(today))
        assertEquals(LocalDate.of(2027, 10, 1), ScoutOccasion.HALLOWEEN.nextStart(today))
    }

    @Test
    fun `the occasion in season leads its tab`() {
        val tiles = ScoutReadyMixes.tab(ScoutReadyTab.YEAR, false, LocalDate.of(2026, 10, 15), "AU")
        assertEquals(ScoutOccasion.HALLOWEEN, tiles.first().occasion)
        assertEquals(ScoutOccasion.THANKSGIVING, tiles[1].occasion)
        assertEquals(ScoutOccasion.HALLOWEEN, ScoutReadyMixes.inSeason(LocalDate.of(2026, 10, 15))?.occasion)
        assertNull(ScoutReadyMixes.inSeason(LocalDate.of(2026, 7, 1)))
    }

    @Test
    fun `the viewer's own country leads around the world, and family night hides the darker mixes`() {
        assertEquals(ScoutMixFrom.AUSTRALIA_NZ, ScoutReadyMixes.tab(ScoutReadyTab.WORLD, false, today, "nz").first().recipe?.from)
        assertEquals(16, ScoutReadyMixes.tab(ScoutReadyTab.WORLD, false, today, "AU").size)
        assertTrue(ScoutReadyMixes.tab(ScoutReadyTab.CLASSIC, true, today, "AU").none { it.key == "cops" })
        assertTrue(ScoutReadyMixes.tab(ScoutReadyTab.CLASSIC, false, today, "AU").any { it.key == "cops" })
    }

    @Test
    fun `countries the profile watches lead around the world, then its own`() {
        val taste = scoutCountryWeights(
            listOf(
                ScoutTasteTitle("a", countries = listOf("KR")),
                ScoutTasteTitle("b", countries = listOf("KR", "KR")),
                ScoutTasteTitle("c", countries = listOf("GB")),
                ScoutTasteTitle("d", countries = listOf("JP"))
            ),
            mapOf("d" to ScoutRatingEntry(ScoutTitle("d", "series", "D", null), ScoutRating.NOT_FOR_ME, 1L))
        )
        assertEquals(2.0 * scoutTasteWeight(null), taste["KR"] ?: 0.0, 1e-9)
        assertNull(taste["JP"])
        val order = ScoutReadyMixes.tab(ScoutReadyTab.WORLD, false, today, "AU", taste).map { it.recipe?.from }
        assertEquals(listOf(ScoutMixFrom.KOREA, ScoutMixFrom.UK_IRELAND, ScoutMixFrom.AUSTRALIA_NZ, ScoutMixFrom.US), order.take(4))
        assertEquals(16, order.size)
    }

    @Test
    fun `a ready-made mix is rebuilt each monday`() {
        val zone = ZoneOffset.UTC
        val sunday = LocalDate.of(2026, 11, 15).atStartOfDay(zone).toInstant().toEpochMilli()
        val monday = LocalDate.of(2026, 11, 16)
        assertTrue(scoutReadyFresh(sunday, LocalDate.of(2026, 11, 15), zone))
        assertFalse(scoutReadyFresh(sunday, monday, zone))
        assertFalse(scoutReadyFresh(0L, monday, zone))
        assertEquals(scoutReadyWeekSeed(LocalDate.of(2026, 11, 17)), scoutReadyWeekSeed(LocalDate.of(2026, 11, 22)))
    }

    private fun episode(imdb: String, s: Int, e: Int, rating: String? = "TV-PG") =
        ScoutOccasionEpisode(imdb, "Show $imdb", s, e, rating?.let { mapOf("US" to it) }.orEmpty(), null, null)

    @Test
    fun `no show takes over an occasion mix`() {
        val many = (1..30).map { episode("tt1", it, 1) } + episode("tt2", 1, 1)
        val chosen = scoutOccasionChoice(many, family = false, seed = 1L)
        assertEquals(4, chosen.getValue("tt1").size)
        assertEquals(1, chosen.getValue("tt2").size)
    }

    @Test
    fun `family night keeps only episodes rated for everyone`() {
        val list = listOf(episode("tt1", 1, 1, "TV-PG"), episode("tt2", 1, 1, "TV-MA"), episode("tt3", 1, 1, null))
        assertEquals(setOf("tt1"), scoutOccasionChoice(list, family = true, seed = 1L).keys)
        assertEquals(setOf("tt1", "tt2", "tt3"), scoutOccasionChoice(list, family = false, seed = 1L).keys)
    }

    @Test
    fun `the bundled list is read with its pictures`() {
        val text = """{"version":1,"occasions":{"christmas":{"episodes":[
            {"imdb":"tt0386676","name":"The Office","s":2,"e":10,"r":{"US":"TV-14"},"po":"/p.jpg","bd":"/b.jpg"},
            {"imdb":"","name":"Broken","s":1,"e":1},
            {"imdb":"tt1","name":"Special","s":0,"e":3}
        ]}}}"""
        val read = parseScoutOccasions(text).getValue("christmas")
        assertEquals(2, read.size)
        assertEquals("https://image.tmdb.org/t/p/w342/p.jpg", read[0].poster)
        assertEquals("TV-14", read[0].ratings["US"])
        assertEquals(0, read[1].season)
    }

    private fun pick(id: Int) = ScoutPick(tmdbId = id, tv = true, name = "S$id")

    @Test
    fun `the blend chooses between taste, popularity and the less obvious`() {
        val byVotes = (1..14).map(::pick)
        val byTaste = listOf(5, 3, 1, 14, 2, 4, 6, 7, 8, 9, 10, 11, 12, 13).map(::pick)
        assertEquals(byTaste, scoutBlendOrder(byVotes, byTaste, ScoutMixBlend.CLOSE, 1L))
        assertEquals(listOf(5, 1, 3, 2, 14, 4).map(::pick), scoutBlendOrder(byVotes, byTaste, ScoutMixBlend.BALANCED, 1L).take(6))
        val surprise = scoutBlendOrder(byVotes, byTaste, ScoutMixBlend.SURPRISE, 1L)
        assertEquals((11..14).toSet(), surprise.take(4).map { it.tmdbId }.toSet())
        assertEquals(14, surprise.size)
    }
}
