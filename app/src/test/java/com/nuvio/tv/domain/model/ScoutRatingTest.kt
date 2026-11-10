package com.nuvio.tv.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ScoutRatingTest {
    private val title = ScoutTitle("tt1", "movie", "Arrival", "http://p")

    @Test
    fun `rating survives saving and loading`() {
        val entry = ScoutRatingEntry(title, ScoutRating.LOVE, 42L)
        assertEquals(entry, decodeScoutRating("tt1", encodeScoutRating(entry)))
    }

    @Test
    fun `missing poster stays missing`() {
        val entry = ScoutRatingEntry(title.copy(poster = null), ScoutRating.LIKE, 1L)
        assertNull(decodeScoutRating("tt1", encodeScoutRating(entry))!!.title.poster)
    }

    @Test
    fun `damaged rating is dropped`() {
        assertNull(decodeScoutRating("tt1", "nonsense"))
        assertNull(decodeScoutRating("tt1", "BAD\u001F1\u001Fmovie\u001FA\u001F"))
    }

    @Test
    fun `finished film is asked about`() {
        val target = scoutAskTargetFor(true, "tt1", "movie", "Arrival", null, null, null)
        assertEquals("tt1", target!!.askKey)
    }

    @Test
    fun `leaving before the end asks nothing`() {
        assertNull(scoutAskTargetFor(false, "tt1", "movie", "Arrival", null, null, null))
    }

    @Test
    fun `episode with another in the same season asks nothing`() {
        assertNull(scoutAskTargetFor(true, "tt2", "series", "Show", null, 2, 2))
    }

    @Test
    fun `last episode of a season asks once per season`() {
        assertEquals("tt2:2", scoutAskTargetFor(true, "tt2", "series", "Show", null, 2, 3)!!.askKey)
        assertEquals("tt2:2", scoutAskTargetFor(true, "tt2", "tv", "Show", null, 2, null)!!.askKey)
    }

    @Test
    fun `unknown type or blank id asks nothing`() {
        assertNull(scoutAskTargetFor(true, "tt1", "channel", "X", null, null, null))
        assertNull(scoutAskTargetFor(true, "", "movie", "X", null, null, null))
    }

    @Test
    fun `no question once rated asked or switched off`() {
        assertNotNull(title)
        val target = ScoutAskTarget(title, "tt1")
        val rated = ScoutProfile(ratings = mapOf("tt1" to ScoutRatingEntry(title, ScoutRating.LIKE, 1L)))
        assertEquals(false, scoutShouldAsk(rated, target))
        assertEquals(false, scoutShouldAsk(ScoutProfile(asked = setOf("tt1")), target))
        assertEquals(false, scoutShouldAsk(ScoutProfile(settings = ScoutSettings(ratingsEnabled = false)), target))
        assertEquals(false, scoutShouldAsk(ScoutProfile(settings = ScoutSettings(askAfterFilm = false)), target))
    }
}
