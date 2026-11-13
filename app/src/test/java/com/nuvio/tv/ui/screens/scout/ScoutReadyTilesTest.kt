package com.nuvio.tv.ui.screens.scout

import com.nuvio.tv.core.scout.ScoutReadyMixes
import com.nuvio.tv.core.scout.ScoutReadyTab
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class ScoutReadyTilesTest {
    private val mixes = ScoutReadyMixes.tab(ScoutReadyTab.YEAR, false, LocalDate.of(2026, 11, 13), "AU")

    private fun tile(index: Int, vararg arts: String) =
        ScoutReadyTileState(mixes[index], art = arts.firstOrNull(), arts = arts.toList(), loaded = true)

    @Test
    fun `tiles side by side do not repeat one picture`() {
        val tiles = scoutDistinctArt(listOf(tile(0, "riverdale", "simpsons"), tile(1, "riverdale", "office"), tile(2, "riverdale", "friends")))
        assertEquals(listOf("riverdale", "office", "friends"), tiles.map { it.art })
    }

    @Test
    fun `a tile with no other picture keeps its own`() {
        val tiles = scoutDistinctArt(listOf(tile(0, "riverdale"), tile(1, "riverdale")))
        assertEquals(listOf("riverdale", "riverdale"), tiles.map { it.art })
    }

    @Test
    fun `a tile still loading stays as it is`() {
        val waiting = ScoutReadyTileState(mixes[1])
        assertEquals(listOf("riverdale", null), scoutDistinctArt(listOf(tile(0, "riverdale"), waiting)).map { it.art })
    }

    @Test
    fun `two shows with one name are named once`() {
        assertEquals(listOf("Doctor Who", "Sherlock"), scoutDistinctNames(listOf("Doctor Who", "Sherlock", "Doctor Who ")))
    }
}
