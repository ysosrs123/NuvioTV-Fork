package com.nuvio.tv.core.scout

import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.ScoutSettings
import com.nuvio.tv.domain.model.TmdbCollectionFilters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoutLeaveOutTest {
    private fun slice(id: String) = ScoutSlices.byId(id)!!

    private fun item(language: String?, genres: List<Int>?) = MetaPreview(
        id = "tmdb:1", type = ContentType.MOVIE, name = "A", poster = null, posterShape = PosterShape.POSTER,
        background = null, logo = null, description = null, releaseInfo = null, imdbRating = null, genres = emptyList(),
        originalLanguage = language, tmdbGenreIds = genres
    )

    @Test
    fun `a language tile is covered when its language is left out`() {
        val japanese = ScoutLeaveOut.of(ScoutSettings(leaveOutLanguages = setOf("ja")))
        assertTrue(japanese.covers(slice("language_ja")))
        assertFalse(japanese.covers(slice("language_ko")))
        assertFalse(japanese.covers(slice("mood_laugh")))
    }

    @Test
    fun `a genre tile is covered when its genre is left out, for films and series`() {
        val animation = ScoutLeaveOut.of(ScoutSettings(leaveOutGenres = setOf(16)))
        assertTrue(animation.covers(slice("genre_16")))
        assertFalse(animation.covers(slice("genre_35")))
        val action = ScoutLeaveOut.of(ScoutSettings(leaveOutGenres = setOf(28)))
        assertTrue(action.covers(slice("genre_28")))
    }

    @Test
    fun `a tile asking for one of several genres stays while any is still wanted`() {
        val one = ScoutLeaveOut.of(ScoutSettings(leaveOutGenres = setOf(16)))
        assertFalse(one.covers(slice("mood_feel_good")))
    }

    @Test
    fun `nothing left out covers nothing and changes no query`() {
        val none = ScoutLeaveOut.of(ScoutSettings())
        assertFalse(none.covers(slice("language_ja")))
        val filters = TmdbCollectionFilters(withGenres = "35")
        assertEquals(filters, none.applyTo(filters))
    }

    @Test
    fun `left out genres join the query's own without list`() {
        val leaveOut = ScoutLeaveOut.of(ScoutSettings(leaveOutGenres = setOf(16, 28)))
        val applied = leaveOut.applyTo(TmdbCollectionFilters(withoutGenres = "27, 16"))
        assertEquals(setOf("27", "16", "28", "10759"), applied.withoutGenres!!.split(',').toSet())
    }

    @Test
    fun `titles in a left out language or genre are dropped, unknown ones stay`() {
        val leaveOut = ScoutLeaveOut.of(ScoutSettings(leaveOutGenres = setOf(16), leaveOutLanguages = setOf("ja", "indian")))
        assertFalse(leaveOut.keeps(item("ja", listOf(18))))
        assertFalse(leaveOut.keeps(item("ta", null)))
        assertFalse(leaveOut.keeps(item("en", listOf(16, 35))))
        assertTrue(leaveOut.keeps(item("en", listOf(35))))
        assertTrue(leaveOut.keeps(item(null, null)))
    }
}
