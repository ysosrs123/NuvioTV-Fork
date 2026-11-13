package com.nuvio.tv.core.scout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoutSlicesTest {
    @Test
    fun `ids are unique`() {
        assertEquals(ScoutSlices.all.size, ScoutSlices.all.map { it.id }.distinct().size)
    }

    @Test
    fun `every slice offers something`() {
        ScoutSlices.all.forEach { assertTrue(it.id, it.movie != null || it.tv != null || it.award != null) }
    }

    @Test
    fun `award tiles sit above the acclaim tiles`() {
        val ids = ScoutSlices.inGroup(ScoutSliceGroup.ACCLAIM).map { it.id }
        assertEquals(16, ids.size)
        assertTrue(ids.take(12).all { it.startsWith("award_") })
    }

    @Test
    fun `nominee tiles use the same awards as their winner tiles`() {
        listOf("bafta", "globes", "emmy").forEach { name ->
            val winners = ScoutSlices.byId("award_$name")?.award
            val nominees = ScoutSlices.byId("award_${name}_nominees")?.award
            assertEquals(name, winners?.awards, nominees?.awards)
            assertTrue(name, winners!!.winners && !nominees!!.winners)
        }
    }

    @Test
    fun `golden globes count both best picture awards`() {
        assertEquals(listOf("golden_globe_drama", "golden_globe_comedy"), ScoutSlices.byId("award_globes")?.award?.awards)
    }

    @Test
    fun `award tabs keep their order and leave out missing categories`() {
        val lists = mapOf(
            "pic" to ScoutAwardLists(wonMovies = (1..10).toList()),
            "dir" to ScoutAwardLists(wonMovies = (11..20).toList())
        )
        val choice = ScoutAwardChoice(
            listOf("pic"),
            tabs = listOf(ScoutAwardTab(1, listOf("pic")), ScoutAwardTab(2, listOf("missing")), ScoutAwardTab(3, listOf("dir")))
        )
        val tabs = scoutAwardTabs(choice, lists)
        assertEquals(listOf(1, 3), tabs.map { it.titleRes })
        assertEquals((11..20).toList(), tabs[1].ids)
    }

    @Test
    fun `nominee tabs leave out each category's winners`() {
        val lists = mapOf("dir" to ScoutAwardLists(wonMovies = listOf(1, 2), nominatedMovies = (1..9).toList()))
        val choice = ScoutAwardChoice(listOf("dir"), winners = false, tabs = listOf(ScoutAwardTab(7, listOf("dir"))))
        assertEquals((3..9).toList(), scoutAwardTabs(choice, lists).single().ids)
    }

    @Test
    fun `a category of series makes a series tab`() {
        val lists = mapOf("limited" to ScoutAwardLists(wonSeries = (1..6).toList()))
        val tab = scoutAwardTabs(ScoutAwardChoice(listOf("limited"), tabs = listOf(ScoutAwardTab(5, listOf("limited")))), lists).single()
        assertTrue(tab.series)
    }

    @Test
    fun `every award with categories starts with its main list`() {
        ScoutSlices.inGroup(ScoutSliceGroup.ACCLAIM).mapNotNull { it.award }.filter { it.tabs.isNotEmpty() }.forEach { award ->
            assertTrue(award.awards.toString(), award.tabs.flatMap { it.awards }.containsAll(award.awards))
        }
    }

    @Test
    fun `bafta names its main tab best film`() {
        assertEquals(com.nuvio.tv.R.string.scout_award_tab_best_film, ScoutSlices.byId("award_bafta")?.award?.tabs?.first()?.titleRes)
        assertEquals(com.nuvio.tv.R.string.scout_award_tab_picture, ScoutSlices.byId("award_oscar_winners")?.award?.tabs?.first()?.titleRes)
    }

    @Test
    fun `criterion reads its own list`() {
        assertEquals(listOf("criterion_collection"), ScoutSlices.byId("award_criterion")?.award?.awards)
    }

    @Test
    fun `winners keep the file's order and combine several awards`() {
        val lists = mapOf(
            "a" to ScoutAwardLists(wonSeries = listOf(1, 2, 3)),
            "b" to ScoutAwardLists(wonSeries = listOf(9, 8, 3))
        )
        val titles = scoutAwardTitles(ScoutAwardChoice(listOf("a", "b")), lists)
        assertEquals(listOf(1, 2, 3, 9, 8), titles.series)
        assertEquals(5, titles.count)
    }

    @Test
    fun `nominees leave out the winners`() {
        val lists = mapOf("a" to ScoutAwardLists(wonMovies = listOf(1, 2), nominatedMovies = listOf(1, 2, 3, 4, 5, 6, 7)))
        assertEquals(listOf(3, 4, 5, 6, 7), scoutAwardTitles(ScoutAwardChoice(listOf("a"), winners = false), lists).movies)
    }

    @Test
    fun `a side with only a stray title is left out`() {
        val lists = mapOf("a" to ScoutAwardLists(wonMovies = (1..30).toList(), wonSeries = listOf(99)))
        val titles = scoutAwardTitles(ScoutAwardChoice(listOf("a")), lists)
        assertEquals(30, titles.movies.size)
        assertTrue(titles.series.isEmpty())
    }

    @Test
    fun `pinned art belongs to real tiles`() {
        assertEquals(19, SCOUT_PINNED_ART.size)
        SCOUT_PINNED_ART.forEach { (id, path) ->
            assertNotNull(id, ScoutSlices.byId(id))
            assertTrue(id, path.startsWith("/") && path.endsWith(".jpg"))
        }
    }

    @Test
    fun `an unknown award has no titles`() {
        assertEquals(0, scoutAwardTitles(ScoutAwardChoice(listOf("missing")), emptyMap()).count)
    }

    @Test
    fun `every group has slices and home tiles exist`() {
        ScoutSliceGroup.entries.forEach { assertTrue(it.name, ScoutSlices.inGroup(it).isNotEmpty()) }
        ScoutSlices.homeTiles.forEach { assertNotNull(it, ScoutSlices.byId(it)) }
    }

    @Test
    fun `news talk reality and soaps never appear in series`() {
        ScoutSlices.all.mapNotNull { it.tv }.forEach { filters ->
            listOf("10763", "10764", "10766", "10767").forEach { genre ->
                assertTrue(filters.withoutGenres.orEmpty().split(",").contains(genre))
            }
        }
    }

    @Test
    fun `hidden gems really are little known`() {
        val gems = ScoutSlices.byId("acclaim_gems")!!
        assertEquals(SCOUT_GEM_MAX_VOTES, gems.movie!!.voteCountLte)
        assertEquals(SCOUT_GEM_MAX_VOTES_TV, gems.tv!!.voteCountLte)
    }

    @Test
    fun `length slices stay to their type`() {
        assertEquals(null, ScoutSlices.byId("length_90")!!.tv)
        assertEquals(null, ScoutSlices.byId("length_quick_episode")!!.movie)
    }
}
