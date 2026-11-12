package com.nuvio.tv.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoutPicksTest {
    private fun watched(id: String, at: Long, type: String = "movie") = WatchedItem(id, type, "Title $id", watchedAt = at)
    private fun rated(id: String, r: ScoutRating, at: Long = 1L, type: String = "movie") =
        ScoutRatingEntry(ScoutTitle(id, type, "Title $id"), r, at)
    private fun pick(id: Int, name: String = "P$id", votes: Int = 500, rating: Float = 7f, genres: List<Int> = emptyList()) =
        ScoutPick(id, false, name, votes = votes, rating = rating, genres = genres)
    private fun seed(name: String, weight: Double) = ScoutSeed(name, false, name, weight, null)

    @Test
    fun `episodes of one show make one seed`() {
        val seeds = scoutSeeds(listOf(watched("a", 1, "series"), watched("a", 5, "series"), watched("a", 3, "series")), emptyList())
        assertEquals(1, seeds.size)
        assertTrue(seeds[0].tv)
    }

    @Test
    fun `loved outranks liked outranks finished`() {
        val seeds = scoutSeeds(
            listOf(watched("recent", 9), watched("l", 2), watched("o", 1)),
            listOf(rated("l", ScoutRating.LIKE), rated("o", ScoutRating.LOVE))
        )
        assertEquals(listOf("o", "l", "recent"), seeds.map { it.contentId })
    }

    @Test
    fun `not for me is a negative seed`() {
        val seeds = scoutSeeds(listOf(watched("x", 1)), listOf(rated("x", ScoutRating.NOT_FOR_ME)))
        assertTrue(seeds.single().weight < 0)
    }

    @Test
    fun `rating without a watch still seeds`() {
        val seeds = scoutSeeds(emptyList(), listOf(rated("r", ScoutRating.LIKE, type = "series")))
        assertEquals("r", seeds.single().contentId)
        assertTrue(seeds.single().tv)
    }

    @Test
    fun `seeds are capped`() {
        val seeds = scoutSeeds((1..40).map { watched("m$it", it.toLong()) }, emptyList())
        assertEquals(SCOUT_MAX_SEEDS, seeds.size)
        assertEquals("m40", seeds.first().contentId)
    }

    @Test
    fun `title recommended by two seeds ranks above one`() {
        val ranked = scoutRank(
            listOf(seed("A", 1.0) to listOf(pick(1), pick(2)), seed("B", 1.0) to listOf(pick(2))),
            ScoutExclusions()
        )
        assertEquals(2, ranked.first().tmdbId)
    }

    @Test
    fun `not for me pushes its neighbours down and can remove them`() {
        val ranked = scoutRank(
            listOf(seed("Liked", 1.0) to listOf(pick(1), pick(2)), seed("Disliked", -3.0) to listOf(pick(1))),
            ScoutExclusions()
        )
        assertEquals(listOf(2), ranked.map { it.tmdbId })
    }

    @Test
    fun `reason names a liked seed never a disliked one`() {
        val ranked = scoutRank(
            listOf(seed("Disliked", -0.1) to listOf(pick(1)), seed("Liked", 1.0) to listOf(pick(1))),
            ScoutExclusions()
        )
        assertEquals("Liked", ranked.single().seedName)
    }

    @Test
    fun `watched rated and left out titles never appear`() {
        val results = listOf(seed("A", 1.0) to listOf(pick(1, "Blade Runner: 2049"), pick(2), pick(3, genres = listOf(27))))
        val ranked = scoutRank(results, ScoutExclusions(
            names = setOf(scoutNormalizedName("Blade Runner 2049")),
            tmdbKeys = setOf("movie:2"),
            genres = setOf(27)
        ))
        assertTrue(ranked.isEmpty())
    }

    @Test
    fun `remake under the same name is not blocked by the older film`() {
        val older = ScoutExclusions(names = setOf(scoutExclusionName("Dune", "1984")))
        assertFalse(older.blocks(pick(1, "Dune").copy(released = "2021-09-15")))
        assertTrue(older.blocks(pick(1, "Dune").copy(released = "1984-12-14")))
        assertTrue(older.blocks(pick(1, "Dune").copy(released = "1985-01-01")))
    }

    @Test
    fun `no year known blocks on name alone`() {
        assertEquals("dune", scoutExclusionName("Dune", null))
        assertTrue(ScoutExclusions(names = setOf("dune")).blocks(pick(1, "Dune").copy(released = "2021-01-01")))
    }

    @Test
    fun `barely voted titles are dropped`() {
        assertTrue(scoutRank(listOf(seed("A", 1.0) to listOf(pick(1, votes = 3))), ScoutExclusions()).isEmpty())
    }

    @Test
    fun `movie and series with the same id stay apart`() {
        val tv = pick(1).copy(tv = true)
        assertEquals(2, scoutRank(listOf(seed("A", 1.0) to listOf(pick(1), tv)), ScoutExclusions()).size)
    }

    @Test
    fun `names match across accents and punctuation`() {
        assertEquals(scoutNormalizedName("Amelie!"), scoutNormalizedName("Amélie"))
    }

    @Test
    fun `rotation is stable for one seed and differs for another`() {
        val picks = (1..100).map { pick(it) }
        assertEquals(scoutRotate(picks, 20, 7L), scoutRotate(picks, 20, 7L))
        assertFalse(scoutRotate(picks, 20, 7L) == scoutRotate(picks, 20, 8L))
        assertEquals(20, scoutRotate(picks, 20, 7L).distinct().size)
    }

    @Test
    fun `refresh only when missing or a day old`() {
        val now = SCOUT_REFRESH_MS * 3
        assertTrue(scoutShouldRefresh(null, "", now))
        assertFalse(scoutShouldRefresh(ScoutPool(builtAt = now - 1000, version = SCOUT_POOL_VERSION), "", now))
        assertTrue(scoutShouldRefresh(ScoutPool(builtAt = now - SCOUT_REFRESH_MS), "", now))
    }

    @Test
    fun `changed history rebuilds early but not within the hour`() {
        val now = SCOUT_REFRESH_MS * 3
        assertFalse(scoutShouldRefresh(ScoutPool(builtAt = now - 1000, historySignature = "a", version = SCOUT_POOL_VERSION), "b", now))
        assertTrue(scoutShouldRefresh(ScoutPool(builtAt = now - SCOUT_MIN_REBUILD_GAP_MS, historySignature = "a", version = SCOUT_POOL_VERSION), "b", now))
    }

    @Test
    fun `a pool saved before the current version is rebuilt at once`() {
        val now = SCOUT_REFRESH_MS * 3
        assertTrue(scoutShouldRefresh(ScoutPool(builtAt = now - 1000, historySignature = "a"), "a", now))
    }
}
