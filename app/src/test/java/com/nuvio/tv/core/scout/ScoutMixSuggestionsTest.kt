package com.nuvio.tv.core.scout

import com.nuvio.tv.domain.model.ScoutPick
import org.junit.Assert.assertEquals
import org.junit.Test

class ScoutMixSuggestionsTest {
    private fun pick(id: Int, name: String = "Show $id", tv: Boolean = true) = ScoutPick(id, tv, name)

    @Test
    fun `shows several seeds suggest come first, then the higher placed`() {
        val ranked = rankScoutMixSuggestions(
            listOf(listOf(pick(1), pick(2), pick(3)), listOf(pick(4), pick(3))),
            emptySet(),
            emptySet()
        )
        assertEquals(listOf(3, 1, 4, 2), ranked.map { it.tmdbId })
    }

    @Test
    fun `the mix's own shows and films are left out`() {
        val ranked = rankScoutMixSuggestions(
            listOf(listOf(pick(1), pick(2, "The Office"), pick(3, tv = false), pick(4))),
            setOf(1),
            setOf(com.nuvio.tv.domain.model.scoutNormalizedName("The Office"))
        )
        assertEquals(listOf(4), ranked.map { it.tmdbId })
    }
}
