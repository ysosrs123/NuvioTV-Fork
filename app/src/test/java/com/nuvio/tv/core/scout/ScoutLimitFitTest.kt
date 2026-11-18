package com.nuvio.tv.core.scout

import com.nuvio.tv.core.ratinglimit.RatingLimit
import com.nuvio.tv.core.ratinglimit.RatingLimitGate
import com.nuvio.tv.domain.model.ScoutMix
import com.nuvio.tv.domain.model.ScoutMixShow
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoutLimitFitTest {
    private val gate = mockk<RatingLimitGate>()
    private val limit = ScoutLimit(gate, RatingLimit.entries.first())

    private fun gateAllows(allowed: (String) -> Boolean) {
        coEvery { gate.keepFitting(any(), any<List<Any>>(), any(), any()) } answers {
            @Suppress("UNCHECKED_CAST")
            val items = secondArg<List<Any>>()
            val idOf = arg<(Any) -> Pair<String, String>>(3)
            items.filter { allowed(idOf(it).first) }
        }
    }

    @Test
    fun `without a limit the list is untouched and nothing is looked up`() = runBlocking {
        val items = listOf("a", "b")
        assertSame(items, scoutKeepFitting(null, items) { it to "series" })
    }

    @Test
    fun `only titles that fit stay, in their order`() = runBlocking {
        gateAllows { it != "b" }
        assertEquals(listOf("a", "c"), scoutKeepFitting(limit, listOf("a", "b", "c")) { it to "series" })
    }

    @Test
    fun `a long list stops being checked once enough fit`() = runBlocking {
        gateAllows { true }
        val items = (1..60).map { "t$it" }
        val kept = scoutKeepFitting(limit, items, enough = 15) { it to "series" }
        assertEquals(items.take(20), kept)
        coVerify(exactly = 1) { gate.keepFitting(any(), any<List<Any>>(), any(), any()) }
    }

    @Test
    fun `a mix keeps only the shows that fit`() = runBlocking {
        gateAllows { it == "tt1" }
        val mix = ScoutMix("m", "Mix", listOf(ScoutMixShow("tt1", "One"), ScoutMixShow("tt2", "Two")))
        assertEquals(listOf("tt1"), mix.fittingLimit(limit).shows.map { it.id })
        assertSame(mix, mix.fittingLimit(null))
    }

    @Test
    fun `the family check passes on that a title with no US rating may stay`() = runBlocking {
        coEvery { gate.keepFitting(any(), any<List<Any>>(), true, any()) } answers { secondArg() }
        val family = ScoutLimit(gate, RatingLimit.PG, keepUnknown = true)
        assertEquals(listOf("a"), scoutKeepFitting(family, listOf("a")) { it to "series" })
        coVerify { gate.keepFitting(RatingLimit.PG, any<List<Any>>(), true, any()) }
    }

    private fun gateRanks(ranks: Map<String, Int?>) {
        coEvery { gate.keepFitting(any(), any<List<Any>>(), any(), any()) } answers {
            val rule = firstArg<RatingLimit>()
            val keepUnknown = thirdArg<Boolean>()
            val idOf = arg<(Any) -> Pair<String, String>>(3)
            secondArg<List<Any>>().filter { item ->
                val rank = ranks[idOf(item).first]
                if (rank == null) keepUnknown else rule.allows(rank)
            }
        }
    }

    @Test
    fun `a family mix with no limit leaves out shows rated above TV-PG and keeps unrated ones`() = runBlocking {
        gateRanks(mapOf("pg" to 2, "teen" to 3))
        val check = scoutFamilyLimit(null, gate)
        assertEquals(listOf("pg", "none"), scoutKeepFitting(check, listOf("pg", "teen", "none")) { it to "series" })
    }

    @Test
    fun `a strict limit on a family mix is the only check, unrated shows stay out`() {
        val young = ScoutLimit(gate, RatingLimit.YOUNG)
        assertSame(young, scoutFamilyLimit(young, gate))
        val teen = scoutFamilyLimit(ScoutLimit(gate, RatingLimit.TEEN), gate)!!
        assertEquals(RatingLimit.PG, teen.limit)
        assertFalse(teen.keepUnknown)
        assertNull(scoutFamilyLimit(null, null))
    }

    @Test
    fun `a kids family mix scans the whole list in one pass until enough fit both rules`() = runBlocking {
        val items = (1..60).map { "t$it" }
        gateRanks(items.associateWith { if (it.removePrefix("t").toInt() > 40) 1 else 2 })
        val check = scoutFamilyLimit(ScoutLimit(gate, RatingLimit.YOUNG), gate)
        val kept = scoutKeepFitting(check, items, enough = 17) { it to "series" }
        assertEquals(items.drop(40), kept)
    }

    @Test
    fun `a title whose rating could not be found is left out`() {
        RatingLimit.entries.forEach { assertFalse(it.name, it.allows(null)) }
    }

    @Test
    fun `family mixes leave out the moods the Moods tab leaves out on Family night`() {
        assertEquals(setOf(ScoutMixFeel.EDGE, ScoutMixFeel.WEIRD, ScoutMixFeel.DARK), SCOUT_NOT_FAMILY_FEELS)
    }

    @Test
    fun `a Family night mood row leaves out a show rated PG here but TV-14 in the US`() = runBlocking {
        every { gate.activeLimit() } returns null
        gateRanks(mapOf("dragon-maid" to 3, "bluey" to 0))
        val family = scoutRowLimit(gate, family = true)
        assertEquals(listOf("bluey", "unrated"), scoutKeepFitting(family, listOf("dragon-maid", "bluey", "unrated")) { it to "series" })
        assertNull(scoutRowLimit(gate, family = false))
        assertNull(scoutRowLimit(null, family = true))
    }

    @Test
    fun `a mood row on a limited profile keeps the limit, and Family night tightens a teen limit to PG`() = runBlocking {
        every { gate.activeLimit() } returns RatingLimit.TEEN
        assertEquals(RatingLimit.TEEN, scoutRowLimit(gate, family = false)!!.limit)
        val family = scoutRowLimit(gate, family = true)!!
        assertEquals(RatingLimit.PG, family.limit)
        assertFalse(family.keepUnknown)
    }

    @Test
    fun `Family night rows are known by their slice id`() {
        assertTrue(ScoutSlices.isFamily("family_night"))
        assertTrue(ScoutSlices.isFamily(ScoutAnswers(mood = ScoutMood.entries.first(), family = true).encode()))
        assertFalse(ScoutSlices.isFamily(ScoutAnswers(mood = ScoutMood.entries.first()).encode()))
        assertFalse(ScoutSlices.isFamily("theme_spy"))
    }
}
