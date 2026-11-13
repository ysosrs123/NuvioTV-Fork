package com.nuvio.tv.core.ratinglimit

import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.ContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RatingLimitTest {
    @Test
    fun `film and tv ratings share one scale`() {
        assertEquals(0, ratingRank("G"))
        assertEquals(0, ratingRank("TV-Y"))
        assertEquals(1, ratingRank("TV-Y7"))
        assertEquals(1, ratingRank("TV-G"))
        assertEquals(2, ratingRank("PG"))
        assertEquals(2, ratingRank("tv-pg"))
        assertEquals(3, ratingRank("PG-13"))
        assertEquals(3, ratingRank("TV-14"))
        assertEquals(4, ratingRank("R"))
        assertEquals(4, ratingRank("TV-MA"))
        assertNull(ratingRank("NR"))
        assertNull(ratingRank(""))
        assertNull(ratingRank(null))
    }

    @Test
    fun `each limit lets through its own level and below, never an unknown rating`() {
        assertTrue(RatingLimit.PRESCHOOL.allows(ratingRank("TV-Y")))
        assertFalse(RatingLimit.PRESCHOOL.allows(ratingRank("TV-G")))
        assertTrue(RatingLimit.YOUNG.allows(ratingRank("TV-Y7")))
        assertFalse(RatingLimit.YOUNG.allows(ratingRank("PG")))
        assertTrue(RatingLimit.PG.allows(ratingRank("TV-PG")))
        assertFalse(RatingLimit.PG.allows(ratingRank("PG-13")))
        assertTrue(RatingLimit.TEEN.allows(ratingRank("TV-14")))
        assertFalse(RatingLimit.TEEN.allows(ratingRank("R")))
        RatingLimit.entries.forEach { assertFalse(it.allows(null)) }
    }

    @Test
    fun `the gate asks for the PIN above the limit or without a rating, and not once unlocked`() {
        assertEquals(RatingCheck.Allowed, ratingCheck(null, TitleRating(4, "R"), false))
        assertEquals(RatingCheck.Allowed, ratingCheck(RatingLimit.PG, TitleRating(2, "PG"), false))
        assertEquals(RatingCheck.NeedsPin(RatingLimit.PG, "PG-13"), ratingCheck(RatingLimit.PG, TitleRating(3, "PG-13"), false))
        assertEquals(RatingCheck.NeedsPin(RatingLimit.PG, null), ratingCheck(RatingLimit.PG, TitleRating(null, null), false))
        assertEquals(RatingCheck.Allowed, ratingCheck(RatingLimit.PG, TitleRating(3, "PG-13"), true))
    }

    @Test
    fun `codes survive a round trip`() {
        RatingLimit.entries.forEach { assertEquals(it, RatingLimit.fromCode(it.code)) }
        assertNull(RatingLimit.fromCode("x"))
    }

    @Test
    fun `episodes count as their show`() {
        assertEquals("tv|tt0944947", ratingTitleKey("tt0944947:3:9", "series"))
        assertEquals("tv|tmdb:1399", ratingTitleKey("tmdb:1399:3:9", "series"))
        assertEquals("movie|tt0107290", ratingTitleKey("tt0107290", "movie"))
    }

    @Test
    fun `a film takes the first US rating given for any of its releases`() {
        val countries = listOf<Pair<String?, List<String?>>>(
            "GB" to listOf("12A"),
            "US" to listOf("", null, "PG-13", "R")
        )
        assertEquals("PG-13", usFilmCertification(countries))
        assertNull(usFilmCertification(listOf("GB" to listOf("15"))))
    }

    @Test
    fun `a limited profile keeps only film and series rows`() {
        val addon = Addon(
            id = "a", name = "A", version = "1", description = null, logo = null, baseUrl = "x",
            catalogs = listOf(
                CatalogDescriptor(type = ContentType.MOVIE, id = "m", name = "Films"),
                CatalogDescriptor(type = ContentType.SERIES, id = "s", name = "Shows"),
                CatalogDescriptor(type = ContentType.UNKNOWN, rawType = "other", id = "o", name = "AllDebrid")
            ),
            types = emptyList(), resources = emptyList()
        )
        assertEquals(listOf("m", "s"), listOf(addon).withRatedCatalogsOnly().single().catalogs.map { it.id })
    }
}
