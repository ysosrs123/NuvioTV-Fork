package com.nuvio.tv.core.scout.sync

import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.domain.model.ScoutRating
import com.nuvio.tv.domain.model.ScoutRatingEntry
import com.nuvio.tv.domain.model.ScoutTitle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoutRatingSyncLogicTest {
    private val film = TrackerTitle(movie = true, imdb = "tt1", tmdb = 10)
    private fun out(rating: ScoutRating?) = OutAction(rating, 5L)
    private fun entry(id: String, rating: ScoutRating, source: String? = null, type: String = "movie") =
        ScoutRatingEntry(ScoutTitle(id, type, "Name $id"), rating, 1L, source)

    @Test
    fun `thumbs map to three seven and ten`() {
        assertEquals(3.0, ScoutRating.NOT_FOR_ME.trackerValue(), 0.0)
        assertEquals(7.0, ScoutRating.LIKE.trackerValue(), 0.0)
        assertEquals(10.0, ScoutRating.LOVE.trackerValue(), 0.0)
    }

    @Test
    fun `tracker values map back with five left out`() {
        assertEquals(ScoutRating.LOVE, scoutRatingFromTracker(9.0))
        assertEquals(ScoutRating.LIKE, scoutRatingFromTracker(8.5))
        assertEquals(ScoutRating.LIKE, scoutRatingFromTracker(6.0))
        assertNull(scoutRatingFromTracker(5.0))
        assertNull(scoutRatingFromTracker(5.5))
        assertEquals(ScoutRating.NOT_FOR_ME, scoutRatingFromTracker(4.5))
        assertEquals(ScoutRating.NOT_FOR_ME, scoutRatingFromTracker(1.0))
        assertNull(scoutRatingFromTracker(0.0))
    }

    @Test
    fun `scout ids become tracker titles`() {
        assertEquals(TrackerTitle(true, "tt0848228", null), ScoutTitle("tt0848228", "movie", "A").trackerTitle())
        assertEquals(TrackerTitle(false, null, 1396), ScoutTitle("tmdb:1396", "series", "B").trackerTitle())
        assertNull(ScoutTitle("kitsu:12", "series", "C").trackerTitle())
    }

    @Test
    fun `film and show with the same tmdb number never match`() {
        val ratings = listOf(TrackerRating(TrackerTitle(false, null, 10), 9.0)).byKey()
        assertNull(ratings.find(TrackerTitle(true, null, 10)))
    }

    @Test
    fun `a new thumb is written when the tracker has none`() {
        assertEquals(PushStep.Write(10.0), planPush(out(ScoutRating.LOVE), remote = null, lastSent = null))
    }

    @Test
    fun `a rating the user gave on the tracker is never replaced`() {
        assertEquals(PushStep.Keep, planPush(out(ScoutRating.LOVE), remote = 8.0, lastSent = null))
        assertEquals(PushStep.Keep, planPush(out(ScoutRating.LOVE), remote = 8.0, lastSent = 7.0))
    }

    @Test
    fun `a value Arc sent is replaced by a newer thumb`() {
        assertEquals(PushStep.Write(3.0), planPush(out(ScoutRating.NOT_FOR_ME), remote = 7.0, lastSent = 7.0))
    }

    @Test
    fun `the same value already there needs no write`() {
        assertEquals(PushStep.Done(7.0), planPush(out(ScoutRating.LIKE), remote = 7.0, lastSent = null))
    }

    @Test
    fun `clearing removes only what Arc sent`() {
        assertEquals(PushStep.Remove, planPush(out(null), remote = 10.0, lastSent = 10.0))
        assertEquals(PushStep.Keep, planPush(out(null), remote = 9.0, lastSent = 10.0))
        assertEquals(PushStep.Keep, planPush(out(null), remote = 9.0, lastSent = null))
        assertEquals(PushStep.Done(null), planPush(out(null), remote = null, lastSent = 10.0))
    }

    @Test
    fun `half steps and one decimal count as the same value`() {
        assertEquals(PushStep.Remove, planPush(out(null), remote = 10.0, lastSent = 9.9))
    }

    @Test
    fun `import adds new thumbs with their source`() {
        val changes = planImport(
            TrackingProviderId.SIMKL, listOf(TrackerRating(film, 9.0, 100L, "Arrival")),
            emptyMap(), emptySet(), emptyMap(), emptySet(), now = 7L
        )
        val added = changes.single().entry!!
        assertEquals("tt1", added.title.id)
        assertEquals(ScoutRating.LOVE, added.rating)
        assertEquals("simkl", added.source)
        assertEquals(100L, added.ratedAt)
    }

    @Test
    fun `a thumb pressed in Arc always wins`() {
        val changes = planImport(
            TrackingProviderId.SIMKL, listOf(TrackerRating(film, 2.0, 100L, "Arrival")),
            mapOf("tt1" to entry("tt1", ScoutRating.LOVE)), emptySet(), emptyMap(), emptySet(), now = 7L
        )
        assertTrue(changes.isEmpty())
    }

    @Test
    fun `a cleared or pending title is not brought back`() {
        val remote = listOf(TrackerRating(film, 9.0, 100L, "Arrival"))
        assertTrue(planImport(TrackingProviderId.TRAKT, remote, emptyMap(), setOf("tt1"), emptyMap(), emptySet(), 7L).isEmpty())
        assertTrue(planImport(TrackingProviderId.TRAKT, remote, emptyMap(), emptySet(), emptyMap(), setOf("tt1"), 7L).isEmpty())
    }

    @Test
    fun `a rating Arc sent does not come back as an import`() {
        val remote = listOf(TrackerRating(film, 10.0, 100L, "Arrival"))
        assertTrue(planImport(TrackingProviderId.TRAKT, remote, emptyMap(), emptySet(), mapOf("tt1" to 10.0), emptySet(), 7L).isEmpty())
    }

    @Test
    fun `imported thumbs follow the tracker and leave with it`() {
        val local = mapOf(
            "tt1" to entry("tt1", ScoutRating.LOVE, "simkl"),
            "tt2" to entry("tt2", ScoutRating.LIKE, "simkl"),
            "tt3" to entry("tt3", ScoutRating.LIKE, "trakt")
        )
        val changes = planImport(
            TrackingProviderId.SIMKL, listOf(TrackerRating(film, 6.0, 100L, null)),
            local, emptySet(), emptyMap(), emptySet(), 7L
        ).associateBy { it.contentId }
        assertEquals(ScoutRating.LIKE, changes["tt1"]!!.entry!!.rating)
        assertNull(changes["tt2"]!!.entry)
        assertTrue("tt3" !in changes)
    }

    @Test
    fun `a five removes an imported thumb`() {
        val changes = planImport(
            TrackingProviderId.SIMKL, listOf(TrackerRating(film, 5.0, 100L, "Arrival")),
            mapOf("tt1" to entry("tt1", ScoutRating.LOVE, "simkl")), emptySet(), emptyMap(), emptySet(), 7L
        )
        assertNull(changes.single().entry)
    }

    @Test
    fun `an existing thumb under a tmdb id is matched`() {
        val local = mapOf("tmdb:10" to entry("tmdb:10", ScoutRating.LIKE, "trakt"))
        val changes = planImport(TrackingProviderId.TRAKT, listOf(TrackerRating(film, 10.0, 1L, "Arrival")), local, emptySet(), emptyMap(), emptySet(), 7L)
        assertEquals("tmdb:10", changes.single().contentId)
        assertEquals(ScoutRating.LOVE, changes.single().entry!!.rating)
    }

    @Test
    fun `ratings answers are read in every known shape`() {
        val page = Json.parseToJsonElement(
            """{"movies":[{"rating":8,"rating_precise":8.5,"rated_at":"2025-10-21T14:00:00Z","movie":{"title":"A","ids":{"imdb":"tt1","tmdb":10}}},
               {"rating":9,"ids":{"tmdb":11}},{"rating":7,"tmdb":12}],
               "shows":[{"rating":3,"show":{"title":"B","ids":{"tmdb":20}}},{"rating":6}]}"""
        ) as JsonObject
        val ratings = parseTrackerRatings(page)
        assertEquals(4, ratings.size)
        assertEquals(8.5, ratings[0].value, 0.0)
        assertEquals(1761055200000L, ratings[0].ratedAtMs)
        assertEquals("A", ratings[0].name)
        assertEquals(TrackerTitle(true, null, 12), ratings[2].title)
        assertEquals(TrackerTitle(false, null, 20), ratings[3].title)
    }

    @Test
    fun `request body carries ids, whole ratings and the date`() {
        val body = ratingsBody(listOf(Triple(film, 7.0, 0L), Triple(TrackerTitle(false, "tt9", null), null, null))).toString()
        assertEquals(
            """{"movies":[{"ids":{"imdb":"tt1","tmdb":10},"rating":7,"rated_at":"1970-01-01T00:00:00Z"}],"shows":[{"ids":{"imdb":"tt9"}}]}""",
            body
        )
    }
}
