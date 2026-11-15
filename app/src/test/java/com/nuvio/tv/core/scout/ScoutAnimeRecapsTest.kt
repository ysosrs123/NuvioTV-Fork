package com.nuvio.tv.core.scout

import com.nuvio.tv.domain.model.ScoutExclusions
import com.nuvio.tv.domain.model.ScoutPick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoutAnimeRecapsTest {
    private fun anime(id: Int, name: String, language: String = "ja", genres: List<Int> = listOf(16, 28)) =
        ScoutPick(tmdbId = id, tv = false, name = name, genres = genres, language = language, votes = 300)

    @Test
    fun `a film tmdb tags as a re-cut is left out`() {
        val siblingsBond = anime(820232, "Demon Slayer: Kimetsu no Yaiba - Sibling's Bond")
        assertTrue(scoutIsAnimeRecap(siblingsBond, setOf("movie:820232")))
        assertTrue(ScoutExclusions(recaps = setOf("movie:820232")).blocks(siblingsBond))
    }

    @Test
    fun `real anime films stay`() {
        val tagged = setOf("movie:820232")
        assertFalse(scoutIsAnimeRecap(anime(635302, "Demon Slayer -Kimetsu no Yaiba- The Movie: Mugen Train"), tagged))
        assertFalse(scoutIsAnimeRecap(anime(1218925, "Chainsaw Man - The Movie: Reze Arc"), tagged))
        assertFalse(ScoutExclusions().blocks(anime(810693, "Jujutsu Kaisen 0")))
    }

    @Test
    fun `a new re-cut with no tags yet is caught by its name`() {
        assertTrue(scoutIsAnimeRecap(anime(1544707, "Chainsaw Man - The Compilation Part 2"), emptySet()))
        assertTrue(scoutIsAnimeRecap(anime(1519438, "Demon Slayer: Kimetsu no Yaiba Special Edition Swordsmith Village Arc Part 1"), emptySet()))
        assertTrue(scoutIsAnimeRecap(anime(1810957, "Sword Art Online - Extra Edition"), emptySet()))
    }

    @Test
    fun `the name check only applies to japanese animated films`() {
        assertFalse(scoutIsAnimeRecap(anime(1, "The Compilation", language = "en"), emptySet()))
        assertFalse(scoutIsAnimeRecap(anime(2, "Recap", genres = listOf(18)), emptySet()))
        assertFalse(scoutIsAnimeRecap(anime(3, "Compilation").copy(tv = true), setOf("tv:3")))
    }

    @Test
    fun `the gundam films are kept`() {
        val gundam = anime(39228, "Mobile Suit Gundam I")
        assertFalse(scoutIsAnimeRecap(gundam, setOf("movie:39228", "movie:39229", "movie:39230")))
        assertEquals(setOf("movie:39228", "movie:39229", "movie:39230"), SCOUT_RECAP_KEEP)
    }

    @Test
    fun `the japanese films tile asks tmdb to leave the tagged films out`() {
        val japanese = ScoutSlices.all.firstOrNull { it.id == "language_ja" }
        assertEquals(SCOUT_RECAP_KEYWORDS, japanese?.movie?.withoutKeywords)
    }
}
