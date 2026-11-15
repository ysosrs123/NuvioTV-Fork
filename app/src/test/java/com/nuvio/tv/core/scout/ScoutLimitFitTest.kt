package com.nuvio.tv.core.scout

import com.nuvio.tv.core.ratinglimit.RatingLimit
import com.nuvio.tv.core.ratinglimit.RatingLimitGate
import com.nuvio.tv.domain.model.ScoutMix
import com.nuvio.tv.domain.model.ScoutMixShow
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class ScoutLimitFitTest {
    private val gate = mockk<RatingLimitGate>()
    private val limit = ScoutLimit(gate, RatingLimit.entries.first())

    private fun gateAllows(allowed: (String) -> Boolean) {
        coEvery { gate.keepFitting(any(), any<List<Any>>(), any()) } answers {
            @Suppress("UNCHECKED_CAST")
            val items = secondArg<List<Any>>()
            val idOf = thirdArg<(Any) -> Pair<String, String>>()
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
        coVerify(exactly = 1) { gate.keepFitting(any(), any<List<Any>>(), any()) }
    }

    @Test
    fun `a mix keeps only the shows that fit`() = runBlocking {
        gateAllows { it == "tt1" }
        val mix = ScoutMix("m", "Mix", listOf(ScoutMixShow("tt1", "One"), ScoutMixShow("tt2", "Two")))
        assertEquals(listOf("tt1"), mix.fittingLimit(limit).shows.map { it.id })
        assertSame(mix, mix.fittingLimit(null))
    }

    @Test
    fun `a title whose rating could not be found is left out`() {
        RatingLimit.entries.forEach { assertFalse(it.name, it.allows(null)) }
    }

    @Test
    fun `family mixes leave out the moods the Moods tab leaves out on Family night`() {
        assertEquals(setOf(ScoutMixFeel.EDGE, ScoutMixFeel.WEIRD, ScoutMixFeel.DARK), SCOUT_NOT_FAMILY_FEELS)
    }
}
