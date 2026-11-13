package com.nuvio.tv.core.scout

import com.nuvio.tv.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ScoutAnswersTest {
    private val today = LocalDate.of(2026, 11, 5)
    private fun ScoutAnswers.at(country: String = "AU") = slice(today, country)

    private fun all(): List<ScoutAnswers> = ScoutWatch.entries.flatMap { w ->
        (ScoutMood.choicesFor(w) + null).flatMap { m ->
            ScoutTime.choicesFor(w).flatMap { t ->
                listOf(false, true).filter { !it || m != ScoutMood.SCARY }.map { f -> ScoutAnswers(w, m, t, f) }
            }
        }
    }

    @Test
    fun `pressing the chip that is on takes it back`() {
        val a = ScoutAnswers().pressWatch(ScoutWatch.SERIES).pressMood(ScoutMood.EPIC).pressTime(ScoutTime.QUICK_EPISODES)
        assertEquals(ScoutWatch.ANYTHING, a.pressWatch(ScoutWatch.SERIES).watch)
        assertNull(a.pressMood(ScoutMood.EPIC).mood)
        assertEquals(ScoutTime.ANY, a.pressTime(ScoutTime.QUICK_EPISODES).time)
    }

    @Test
    fun `changing type keeps only answers the new type offers`() {
        val a = ScoutAnswers().pressWatch(ScoutWatch.SERIES).pressMood(ScoutMood.EPIC).pressTime(ScoutTime.COMPLETE)
        val anime = a.pressWatch(ScoutWatch.ANIME)
        assertNull(anime.mood)
        assertEquals(ScoutTime.COMPLETE, anime.time)
        val movie = a.pressWatch(ScoutWatch.MOVIE)
        assertEquals(ScoutMood.EPIC, movie.mood)
        assertEquals(ScoutTime.ANY, movie.time)
        assertNull(a.pressWatch(ScoutWatch.DOCUMENTARY).mood)
    }

    @Test
    fun `every type offers the choices agreed`() {
        assertEquals(12, ScoutMood.choicesFor(ScoutWatch.ANYTHING).size)
        assertEquals(ScoutMood.choicesFor(ScoutWatch.ANYTHING), ScoutMood.choicesFor(ScoutWatch.MOVIE))
        assertEquals(ScoutMood.choicesFor(ScoutWatch.ANYTHING), ScoutMood.choicesFor(ScoutWatch.SERIES))
        assertTrue(ScoutMood.ISEKAI in ScoutMood.choicesFor(ScoutWatch.ANIME))
        assertTrue(ScoutMood.TRUE_CRIME in ScoutMood.choicesFor(ScoutWatch.DOCUMENTARY))
        assertFalse(ScoutMood.FEEL_GOOD in ScoutMood.choicesFor(ScoutWatch.DOCUMENTARY))
        ScoutWatch.entries.forEach { assertEquals(ScoutTime.ANY, ScoutTime.choicesFor(it).first()) }
        assertTrue(ScoutTime.WEEKEND in ScoutTime.choicesFor(ScoutWatch.SERIES))
        assertFalse(ScoutTime.WEEKEND in ScoutTime.choicesFor(ScoutWatch.MOVIE))
    }

    @Test
    fun `scary goes away with family night and takes the answer with it`() {
        assertFalse(ScoutMood.SCARY in ScoutMood.choicesFor(ScoutWatch.ANYTHING, family = true))
        val a = ScoutAnswers(mood = ScoutMood.SCARY).pressFamily(true)
        assertNull(a.mood)
        assertTrue(a.family)
    }

    @Test
    fun `opposite tunes cancel each other, others stack`() {
        val a = ScoutAnswers().pressTune(ScoutTune.NEWER).pressTune(ScoutTune.LIGHTER).pressTune(ScoutTune.OLDER)
        assertEquals(setOf(ScoutTune.OLDER, ScoutTune.LIGHTER), a.tunes)
        assertEquals(setOf(ScoutTune.LIGHTER), a.pressTune(ScoutTune.OLDER).tunes)
        assertEquals(setOf(ScoutTune.OLDER, ScoutTune.DARKER), a.pressTune(ScoutTune.DARKER).tunes)
        val country = ScoutAnswers().pressTune(ScoutTune.INTERNATIONAL).pressTune(ScoutTune.FROM_COUNTRY)
        assertEquals(setOf(ScoutTune.FROM_COUNTRY), country.tunes)
    }

    @Test
    fun `changing an answer drops the tuning`() {
        val tuned = ScoutAnswers(mood = ScoutMood.EPIC).pressTune(ScoutTune.NEWER)
        assertTrue(tuned.pressMood(ScoutMood.TEARS).tunes.isEmpty())
        assertTrue(tuned.pressFamily(true).tunes.isEmpty())
        assertTrue(tuned.pressWatch(ScoutWatch.MOVIE).tunes.isEmpty())
        assertTrue(tuned.pressTime(ScoutTime.SHORT).tunes.isEmpty())
    }

    @Test
    fun `answers survive being saved as a row id`() {
        val list = all().map { it.copy(tunes = setOf(ScoutTune.INTERNATIONAL, ScoutTune.NEWER)) }
        list.forEach { assertEquals(it, ScoutAnswers.decode(it.encode())) }
        assertFalse(list.any { ',' in it.encode() })
        assertEquals(list.size, list.map { it.encode() }.toSet().size)
    }

    @Test
    fun `mood and tune and time codes are all different`() {
        assertEquals(ScoutMood.entries.size, ScoutMood.entries.map { it.code }.toSet().size)
        assertEquals(ScoutTime.entries.size, ScoutTime.entries.map { it.code }.toSet().size)
        assertEquals(ScoutTune.entries.size, ScoutTune.entries.map { it.code }.toSet().size)
    }

    @Test
    fun `unknown ids are not answers`() {
        listOf("", "mood_feel_good", "q.a.x.a", "q.z.x.a.x.", "q.a.zz.a.x.", "q.a.x.z.x.", "q.a.x.a.x.Z").forEach {
            assertNull(it, ScoutAnswers.decode(it))
        }
    }

    @Test
    fun `saved answers resolve through the slice lookup, ordinary slices still win`() {
        val id = ScoutAnswers(mood = ScoutMood.FEEL_GOOD, watch = ScoutWatch.SERIES).encode()
        assertEquals(id, ScoutSlices.byId(id)?.id)
        assertEquals("mood_tears", ScoutSlices.byId("mood_tears")?.id)
        assertNull(ScoutSlices.byId("nope"))
    }

    @Test
    fun `every general mood has a slice with both sides, cosy is gone`() {
        ScoutMood.choicesFor(ScoutWatch.ANYTHING).forEach { mood ->
            val slice = ScoutSlices.byId(mood.sliceId!!)
            assertEquals(mood.name, ScoutSliceGroup.MOOD, slice?.group)
            assertTrue(mood.name, slice?.movie != null && slice.tv != null)
        }
        assertNull(ScoutSlices.byId("mood_cosy"))
    }

    @Test
    fun `complete series asks for ended shows only and no films`() {
        val s = ScoutAnswers(watch = ScoutWatch.SERIES, time = ScoutTime.COMPLETE).at()
        assertNull(s.movie)
        assertEquals("3", s.tv?.withStatus)
    }

    @Test
    fun `finish in a weekend means real miniseries`() {
        val s = ScoutAnswers(watch = ScoutWatch.SERIES, time = ScoutTime.WEEKEND).at()
        assertNull(s.movie)
        assertEquals("2", s.tv?.withType)
    }

    @Test
    fun `lengths limit only the side they fit`() {
        val quick = ScoutAnswers(watch = ScoutWatch.SERIES, time = ScoutTime.QUICK_EPISODES).at()
        assertNull(quick.movie)
        assertEquals(30, quick.tv?.withRuntimeLte)
        val film = ScoutAnswers(watch = ScoutWatch.MOVIE, time = ScoutTime.UNDER_120).at()
        assertNull(film.tv)
        assertEquals(120, film.movie?.withRuntimeLte)
        val short = ScoutAnswers(time = ScoutTime.SHORT).at()
        assertEquals(90, short.movie?.withRuntimeLte)
        assertEquals(30, short.tv?.withRuntimeLte)
        val long = ScoutAnswers(time = ScoutTime.LONG).at()
        assertEquals(150, long.movie?.withRuntimeGte)
        assertEquals(40, long.tv?.withRuntimeGte)
    }

    @Test
    fun `family night leaves out horror and uses a rating for both sides`() {
        val s = ScoutAnswers(family = true).at("AU")
        assertEquals("AU", s.movie?.certificationCountry)
        assertEquals("PG", s.movie?.certificationLte)
        assertEquals("AU", s.tv?.certificationCountry)
        assertEquals("PG", s.tv?.certificationLte)
        assertTrue(s.movie?.withoutGenres.orEmpty().split(',').contains("27"))
        assertTrue(s.tv?.withoutGenres.orEmpty().split(',').contains("27"))
    }

    @Test
    fun `countries whose ratings were not sensible fall back to the US rating`() {
        listOf("FR", "CA", "BR", "IT", "", "XX").forEach { country ->
            val s = ScoutAnswers(family = true).at(country)
            assertEquals(country, "US", s.movie?.certificationCountry)
            assertEquals(country, "PG", s.movie?.certificationLte)
            assertEquals(country, "TV-PG", s.tv?.certificationLte)
        }
        assertEquals("DE" to "6", scoutFamilyRating(ScoutSide.MOVIE, "de"))
        assertEquals("US" to "TV-PG", scoutFamilyRating(ScoutSide.SERIES, "IE"))
        assertEquals("IE" to "PG", scoutFamilyRating(ScoutSide.MOVIE, "IE"))
    }

    @Test
    fun `anime is japanese animation, documentaries are documentaries`() {
        val isekai = ScoutAnswers(watch = ScoutWatch.ANIME, mood = ScoutMood.ISEKAI).at()
        assertEquals("16", isekai.tv?.withGenres)
        assertEquals("237451", isekai.tv?.withKeywords)
        assertEquals("JP", isekai.movie?.withOriginCountry)
        val comedy = ScoutAnswers(watch = ScoutWatch.ANIME, mood = ScoutMood.LAUGH).at()
        assertEquals("16,35", comedy.movie?.withGenres)
        val crime = ScoutAnswers(watch = ScoutWatch.DOCUMENTARY, mood = ScoutMood.TRUE_CRIME).at()
        assertEquals("99", crime.tv?.withGenres)
        assertEquals("33722", crime.tv?.withKeywords)
        assertEquals("99,18,10749", scoutAllGenres("99", "18,10749"))
        assertEquals("16", scoutAllGenres("16", "16"))
    }

    @Test
    fun `lighter never leaves out a genre the mood asks for`() {
        val dark = ScoutAnswers(mood = ScoutMood.DARK).pressTune(ScoutTune.LIGHTER).at()
        val without = dark.movie?.withoutGenres.orEmpty().split(',')
        assertFalse(without.contains("80"))
        assertTrue(without.contains("27"))
    }

    @Test
    fun `date tunes follow today`() {
        assertEquals("2021-11-05", ScoutAnswers().pressTune(ScoutTune.NEWER).at().movie?.releaseDateGte)
        assertEquals("2006-11-05", ScoutAnswers().pressTune(ScoutTune.OLDER).at().tv?.releaseDateLte)
    }

    @Test
    fun `international leaves out america and keeps a country already set`() {
        assertEquals("JP", ScoutAnswers(watch = ScoutWatch.ANIME).pressTune(ScoutTune.INTERNATIONAL).at().tv?.withOriginCountry)
        val other = ScoutAnswers().pressTune(ScoutTune.INTERNATIONAL).at().movie?.withOriginCountry.orEmpty().split('|')
        assertTrue(other.contains("GB"))
        assertTrue(other.contains("IN"))
        assertFalse(other.contains("US"))
    }

    @Test
    fun `from my country follows the box, with lower limits`() {
        val tuned = ScoutAnswers().pressTune(ScoutTune.FROM_COUNTRY)
        val india = tuned.at("IN")
        assertEquals("IN", india.movie?.withOriginCountry)
        assertNull(india.movie?.withOriginalLanguage)
        assertEquals(20, india.movie?.voteCountGte)
        assertEquals(5, india.tv?.voteCountGte)
        val france = tuned.at("fr")
        assertEquals("FR", france.movie?.withOriginCountry)
        assertEquals("fr", france.movie?.withOriginalLanguage)
        assertEquals("AU", tuned.at("AU").tv?.withOriginCountry)
        assertNull(tuned.at("AU").tv?.withOriginalLanguage)
    }

    @Test
    fun `from my country is not offered for anime, which has its own`() {
        assertFalse(ScoutAnswers(watch = ScoutWatch.ANIME).offersFromCountry)
        assertEquals("JP", ScoutAnswers(watch = ScoutWatch.ANIME, tunes = setOf(ScoutTune.FROM_COUNTRY)).at("AU").tv?.withOriginCountry)
    }

    @Test
    fun `darker and hidden gems and better rated do what they say`() {
        assertEquals("53|80|27", ScoutAnswers().pressTune(ScoutTune.DARKER).at().movie?.withGenres)
        val gems = ScoutAnswers().pressTune(ScoutTune.HIDDEN_GEMS).at()
        assertEquals(SCOUT_GEM_MAX_VOTES, gems.movie?.voteCountLte)
        assertTrue((gems.movie?.voteAverageGte ?: 0.0) >= 7.5)
        assertTrue((ScoutAnswers().pressTune(ScoutTune.BETTER_RATED).at().tv?.voteAverageGte ?: 0.0) >= 7.8)
    }

    @Test
    fun `talk, news, reality and soaps stay out of series`() {
        ScoutMood.choicesFor(ScoutWatch.ANYTHING).forEach { mood ->
            val without = ScoutAnswers(mood = mood).at().tv?.withoutGenres.orEmpty().split(',')
            assertTrue(mood.name, without.containsAll(listOf("10763", "10764", "10766", "10767")))
        }
    }

    @Test
    fun `each mood asks for what it says on series`() {
        val feelGood = ScoutAnswers(mood = ScoutMood.FEEL_GOOD).at().tv!!
        assertEquals("35|10751", feelGood.withGenres)
        val scary = ScoutAnswers(mood = ScoutMood.SCARY).at().tv!!
        assertFalse(scary.withKeywords.orEmpty().split('|').contains("162846"))
        assertEquals("4565", scary.withoutKeywords)
        val romantic = ScoutAnswers(mood = ScoutMood.ROMANTIC).at().tv!!
        assertTrue(romantic.withoutGenres.orEmpty().split(',').containsAll(listOf("80", "9648")))
        val epic = ScoutAnswers(mood = ScoutMood.EPIC).at().tv!!
        assertTrue(epic.withoutGenres.orEmpty().split(',').contains("9648"))
        assertFalse(epic.withKeywords.isNullOrBlank())
    }

    @Test
    fun `mind-bending keeps horror and crime out and has no plain time travel`() {
        val s = ScoutAnswers(mood = ScoutMood.MIND).at()
        assertTrue(s.movie!!.withoutGenres.orEmpty().split(',').contains("27"))
        assertTrue(s.tv!!.withoutGenres.orEmpty().split(',').contains("80"))
        listOf("4379", "4565", "12565").forEach { assertFalse(it, s.movie!!.withKeywords.orEmpty().split('|').contains(it)) }
    }

    @Test
    fun `documentaries leave out comedy`() {
        val plain = ScoutAnswers(watch = ScoutWatch.DOCUMENTARY).at()
        assertTrue(plain.movie!!.withoutGenres.orEmpty().split(',').contains("35"))
        assertTrue(plain.tv!!.withoutGenres.orEmpty().split(',').contains("35"))
        assertTrue(ScoutSlices.byId("genre_99")!!.movie!!.withoutGenres.orEmpty().split(',').contains("35"))
    }

    @Test
    fun `darker on a mood with no genre left stays a clean filter`() {
        val f = ScoutAnswers(mood = ScoutMood.ROMANTIC).pressTune(ScoutTune.DARKER).at().tv!!
        assertNull(f.withGenres)
    }

    @Test
    fun `no combination leaves the filters impossible`() {
        all().forEach { base ->
            ScoutTune.entries.forEach { tune ->
                val a = base.pressTune(tune)
                val s = a.at()
                listOfNotNull(s.movie, s.tv).forEach { f ->
                    val genres = f.withGenres.orEmpty().split(',', '|').filter(String::isNotBlank)
                    val without = f.withoutGenres.orEmpty().split(',')
                    assertTrue(a.encode(), genres.none { it in without })
                    assertTrue(a.encode(), (f.withRuntimeGte ?: 0) < (f.withRuntimeLte ?: Int.MAX_VALUE))
                    assertTrue(a.encode(), (f.voteCountGte ?: 0) < (f.voteCountLte ?: Int.MAX_VALUE))
                }
                assertTrue(a.encode(), s.movie != null || s.tv != null)
                assertTrue(a.titleParts().isNotEmpty())
            }
        }
    }

    @Test
    fun `relaxing loosens thresholds and keeps the answers`() {
        val f = ScoutAnswers(mood = ScoutMood.EPIC, family = true).at().movie!!
        val r = f.scoutRelaxed()
        assertTrue(r.voteCountGte!! < f.voteCountGte!!)
        assertTrue(r.voteAverageGte!! <= 6.0)
        assertEquals(f.certificationLte, r.certificationLte)
        assertEquals(f.withGenres, r.withGenres)
    }

    @Test
    fun `regions and the two added countries are in the picker`() {
        val regions = ScoutSlices.inGroup(ScoutSliceGroup.REGION)
        assertEquals(10, regions.size)
        assertEquals("IN|PK|BD|LK|NP|BT|MV|AF", ScoutSlices.byId("region_south_asia")?.movie?.withOriginCountry)
        regions.forEach { slice ->
            assertTrue(slice.id, slice.titleRes != 0)
            assertEquals(slice.id, slice.movie?.withOriginCountry, slice.tv?.withOriginCountry)
            assertFalse(slice.id, slice.movie?.withOriginCountry.orEmpty().split('|').contains("US"))
        }
        assertEquals("GB|IE", ScoutSlices.byId("region_uk_ireland")?.movie?.withOriginCountry)
        assertEquals("CA", ScoutSlices.byId("country_CA")?.tv?.withOriginCountry)
        val hongKong = ScoutSlices.byId("country_HK")
        assertEquals("HK", hongKong?.movie?.withOriginCountry)
        assertEquals(R.string.scout_country_hong_kong, hongKong?.titleRes)
    }

    @Test
    fun `the two new slices are in the picker`() {
        assertEquals(ScoutSliceGroup.MOOD, ScoutSlices.byId("family_night")?.group)
        assertEquals(ScoutSliceGroup.LENGTH, ScoutSlices.byId("complete_series")?.group)
        assertEquals("3", ScoutSlices.byId("complete_series")?.tv?.withStatus)
        assertEquals(ScoutSlices.all.size, ScoutSlices.all.map { it.id }.toSet().size)
    }

    @Test
    fun `genres are slices for Your taste but not a picker group`() {
        assertFalse(ScoutSliceGroup.GENRE.inPicker)
        assertEquals("10765", ScoutSlices.byId("genre_878")?.tv?.withGenres)
        assertNull(ScoutSlices.byId("genre_27")?.tv)
        assertEquals(878, scoutCanonicalGenre(10765))
        assertEquals(28, scoutCanonicalGenre(10759))
        assertEquals(18, scoutCanonicalGenre(18))
        assertEquals(setOf(878, 10765), scoutGenreLeaveOutIds(878))
    }
}
