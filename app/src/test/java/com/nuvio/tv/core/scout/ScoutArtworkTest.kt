package com.nuvio.tv.core.scout

import com.nuvio.tv.core.poster.withCustomPosterUrl
import com.nuvio.tv.core.scout.ask.AskItem
import com.nuvio.tv.core.scout.ask.toMeta
import com.nuvio.tv.core.scout.ask.toSaved
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.ui.screens.scout.ask.askVoiceShouldStop
import com.nuvio.tv.ui.screens.scout.ask.joinAskWords
import com.nuvio.tv.ui.screens.scout.ask.unquoted
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoutArtworkTest {
    private val pattern = "https://art.example/{imdb_id}/poster.jpg"

    private fun title(imdb: String? = null) = MetaPreview(
        id = "tmdb:157336",
        type = ContentType.MOVIE,
        rawType = "movie",
        name = "Interstellar",
        poster = "https://image.tmdb.org/t/p/w500/a.jpg",
        posterShape = PosterShape.POSTER,
        background = "https://image.tmdb.org/t/p/w1280/b.jpg",
        logo = null,
        description = null,
        releaseInfo = "2014",
        imdbRating = null,
        genres = emptyList(),
        imdbId = imdb
    )

    @Test
    fun addonArtReplacesTheTmdbArtAndKeepsWhatItLacks() {
        val shown = title().withChosenArt("tt0816692", "https://addon/p.jpg", null, "https://addon/l.png", "169", listOf("Sci-Fi"))
        assertEquals("https://addon/p.jpg", shown.poster)
        assertEquals("https://image.tmdb.org/t/p/w1280/b.jpg", shown.background)
        assertEquals("https://addon/l.png", shown.logo)
        assertEquals("tt0816692", shown.imdbId)
        assertEquals("169", shown.runtime)
    }

    @Test
    fun blankAddonArtIsIgnored() {
        assertEquals(title().poster, title().withChosenArt(null, " ", "", "").poster)
    }

    @Test
    fun customAddressWinsOverEverythingAndKeepsAFallback() {
        val shown = title().withChosenArt("tt0816692", "https://addon/p.jpg", null, null).withCustomPosterUrl(pattern)
        assertEquals("https://art.example/tt0816692/poster.jpg", shown.poster)
        assertEquals("https://addon/p.jpg", shown.rawPosterUrl)
    }

    @Test
    fun savedRowKeepsTheChosenArtNotTheCustomAddress() {
        val shown = title("tt0816692").withCustomPosterUrl(pattern)
        val back = AskItem(shown, "why").toSaved().toMeta()
        assertEquals(title().poster, back.poster)
        assertEquals("tt0816692", back.imdbId)
        assertEquals("https://art.example/tt0816692/poster.jpg", back.withCustomPosterUrl(pattern).poster)
    }

    @Test
    fun voiceKeepsListeningThroughShortPauses() {
        assertFalse(askVoiceShouldStop(keepGoing = true, hasWords = true, quietMs = 2_000, tooLong = false))
        assertTrue(askVoiceShouldStop(keepGoing = true, hasWords = true, quietMs = 5_000, tooLong = false))
        assertFalse(askVoiceShouldStop(keepGoing = true, hasWords = false, quietMs = 5_000, tooLong = false))
        assertTrue(askVoiceShouldStop(keepGoing = true, hasWords = false, quietMs = 10_000, tooLong = false))
        assertTrue(askVoiceShouldStop(keepGoing = true, hasWords = true, quietMs = 0, tooLong = true))
        assertTrue(askVoiceShouldStop(keepGoing = false, hasWords = false, quietMs = 0, tooLong = false))
    }

    @Test
    fun wordsAddUpAcrossPauses() {
        assertEquals("I want a series that teenagers like", joinAskWords("I want a series", "that teenagers like"))
        assertEquals("just this", joinAskWords("", "just this"))
        assertEquals("just this", joinAskWords("just this", " "))
    }

    @Test
    fun starterTextsLoseTheirQuotesInAnyLanguage() {
        assertEquals("Films like Inception", unquoted("\"Films like Inception\""))
        assertEquals("寒い場所", unquoted("「寒い場所」"))
        assertEquals("Filme wie Inception", unquoted("„Filme wie Inception“"))
    }
}
