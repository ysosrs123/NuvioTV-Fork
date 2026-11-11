package com.nuvio.tv.ui.components

import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class MDBListRatingFormatTest {
    private val saved = Locale.getDefault()

    @After
    fun restore() = Locale.setDefault(saved)

    @Test
    fun `tmdb out of 100 shows as a whole number`() {
        Locale.setDefault(Locale.forLanguageTag("pl-PL"))
        assertEquals("86", formatMDBListRating("tmdb", 86.0))
        assertEquals("86", formatMDBListRating("tmdb", 8.6))
        assertEquals("7", formatMDBListRating("tmdb", 7.0))
        assertEquals("10", formatMDBListRating("tmdb", 10.0))
    }

    @Test
    fun `imdb keeps one decimal in the viewer's locale`() {
        Locale.setDefault(Locale.forLanguageTag("pl-PL"))
        assertEquals("9,4", formatMDBListRating("imdb", 9.4))
        assertEquals("91", formatMDBListRating("tomatoes", 91.0))
    }
}
