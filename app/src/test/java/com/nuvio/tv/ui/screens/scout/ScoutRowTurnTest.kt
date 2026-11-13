package com.nuvio.tv.ui.screens.scout

import com.nuvio.tv.domain.model.ScoutPick
import org.junit.Assert.assertEquals
import org.junit.Test

class ScoutRowTurnTest {
    private fun movie(id: Int) = ScoutPick(tmdbId = id, tv = false, name = "Movie $id")

    @Test
    fun `different titles start a new turn`() {
        assertEquals(4, nextRowTurn(listOf(movie(1), movie(2)), listOf(movie(3), movie(4)), 3))
    }

    @Test
    fun `the same titles in another order start a new turn`() {
        assertEquals(4, nextRowTurn(listOf(movie(1), movie(2)), listOf(movie(2), movie(1)), 3))
    }

    @Test
    fun `the same row shown again keeps its turn`() {
        val row = listOf(movie(1), movie(2))
        assertEquals(3, nextRowTurn(row, row.map { it.copy(overview = "later detail") }, 3))
    }

    @Test
    fun `a movie and a show with one number are different titles`() {
        assertEquals(1, nextRowTurn(listOf(movie(7)), listOf(ScoutPick(tmdbId = 7, tv = true, name = "Show 7")), 0))
    }
}
