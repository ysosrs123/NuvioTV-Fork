package com.nuvio.tv.core.scout.ask

import com.nuvio.tv.core.scout.ScoutAiSorted
import com.nuvio.tv.core.scout.ScoutAiState
import com.nuvio.tv.core.scout.scoutApplySorted
import com.nuvio.tv.core.scout.scoutSortDue
import com.nuvio.tv.domain.model.ScoutPick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AskPicksAiTest {
    private val list = listOf(
        AskCandidate("movie:1", "Arrival", 2016, false),
        AskCandidate("tv:2", "Dark", 2017, true),
        AskCandidate("movie:3", "Dark", 2005, false)
    )

    @Test
    fun `a title not on the list cannot get through`() {
        val parsed = AskParsed("", listOf(AskPick("Made Up Film", 2020, false, "x"), AskPick("Arrival", 2016, false, "good")))
        assertEquals(listOf(AskRanked("movie:1", "good")), matchSorted(parsed, list))
    }

    @Test
    fun `a whole list line copied by the AI still matches`() {
        val parsed = AskParsed("", listOf(AskPick("Arrival | 2016 | movie", 2016, false, "a"), AskPick("Dark (2017) series", 2017, true, "b")))
        assertEquals(listOf("movie:1", "tv:2"), matchSorted(parsed, list).map { it.key })
    }

    @Test
    fun `a shared name goes by year and type, and an unsure one is dropped`() {
        val exact = AskParsed("", listOf(AskPick("Dark", 2017, true, "a")))
        assertEquals("tv:2", matchSorted(exact, list).single().key)
        val unsure = AskParsed("", listOf(AskPick("Dark", null, null, "a")))
        assertTrue(matchSorted(unsure, list).isEmpty())
        val wrongYearOneMatch = AskParsed("", listOf(AskPick("Arrival", 2015, false, "a")))
        assertEquals("movie:1", matchSorted(wrongYearOneMatch, list).single().key)
    }

    @Test
    fun `the sort prompt holds the list and only what was approved`() {
        val prompt = buildSortPrompt(list, AskTaste(listOf("Moon"), listOf("Grown Ups"), listOf("Horror"), "No jump scares"), "German")
        assertTrue(prompt.user.contains("Arrival | 2016 | movie"))
        assertTrue(prompt.user.contains("Dark | 2017 | series"))
        assertTrue(prompt.user.contains("Moon"))
        assertTrue(prompt.user.contains("Grown Ups"))
        assertTrue(prompt.user.contains("Horror"))
        assertTrue(prompt.user.contains("No jump scares"))
        assertTrue(prompt.system.contains("German"))
    }

    @Test
    fun `at most a hundred candidates are sent`() {
        val many = (1..150).map { AskCandidate("movie:$it", "Film$it", 2000, false) }
        val prompt = buildSortPrompt(many, AskTaste(), "English")
        assertTrue(prompt.user.contains("Film100 |"))
        assertFalse(prompt.user.contains("Film101 |"))
    }

    @Test
    fun `ask carries the edited taste words`() {
        assertTrue(buildAskPrompt("a film", AskTaste(summary = "No jump scares"), "English").user.contains("No jump scares"))
        assertFalse(buildAskPrompt("a film", AskTaste(), "English").user.contains("own words"))
    }

    @Test
    fun `the summary is read from the reply`() {
        assertEquals("Slow \"big idea\" sci-fi", parseSummary("""{"rowName":"Slow \"big idea\" sci-fi","includeSeen":false,"titles":[]}"""))
        assertEquals(null, parseSummary("no json"))
        assertEquals(null, parseSummary("""{"rowName":"  "}"""))
        assertTrue(parseSummary("""{"rowName":"${"x".repeat(900)}"}""")!!.length <= ASK_MAX_SUMMARY)
    }

    private fun pick(id: Int) = ScoutPick(id, false, "t$id", stretch = id == 2)

    @Test
    fun `sorted titles go first with their reason, the others keep their order, gone titles are ignored`() {
        val out = scoutApplySorted(listOf(pick(1), pick(2), pick(3)), listOf(ScoutAiSorted("movie:3", "why"), ScoutAiSorted("movie:9", "gone"), ScoutAiSorted("movie:2", "b")))
        assertEquals(listOf(3, 2, 1), out.map { it.tmdbId })
        assertEquals("why", out[0].aiReason)
        assertFalse(out[1].stretch)
        assertEquals(null, out[2].aiReason)
    }

    @Test
    fun `sorting is off until asked for and runs about weekly`() {
        val day = 24L * 60 * 60 * 1000
        assertFalse(scoutSortDue(ScoutAiState(), 100 * day))
        assertTrue(scoutSortDue(ScoutAiState(sortOn = true), 100 * day))
        assertFalse(scoutSortDue(ScoutAiState(sortOn = true, sortedAt = 95 * day), 100 * day))
        assertTrue(scoutSortDue(ScoutAiState(sortOn = true, sortedAt = 93 * day), 100 * day))
    }
}
