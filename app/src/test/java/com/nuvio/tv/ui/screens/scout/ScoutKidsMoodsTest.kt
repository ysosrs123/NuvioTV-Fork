package com.nuvio.tv.ui.screens.scout

import com.nuvio.tv.core.scout.ScoutMood
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoutKidsMoodsTest {
    @Test
    fun `a mood with nothing that fits is left out, unless it is the one showing`() {
        val start = ScoutScreenState(kids = true)
        val empty = start.copy(emptyMoods = setOf(start.moodKey(ScoutMood.WEIRD)))
        assertFalse(ScoutMood.WEIRD in empty.moods())
        assertTrue(ScoutMood.LAUGH in empty.moods())
        assertTrue(ScoutMood.WEIRD in empty.copy(tab = ScoutTab.Feeling(ScoutMood.WEIRD)).moods())
    }

    @Test
    fun `an empty mood for films can still show for series`() {
        val start = ScoutScreenState(kids = true)
        val empty = start.copy(emptyMoods = setOf(start.moodKey(ScoutMood.WEIRD)))
        val series = empty.copy(answers = empty.answers.pressWatch(com.nuvio.tv.core.scout.ScoutWatch.SERIES))
        assertTrue(ScoutMood.WEIRD in series.moods())
    }

    @Test
    fun `a profile without a limit keeps every mood`() {
        assertTrue(ScoutMood.SCARY in ScoutScreenState().moods())
        assertFalse(ScoutMood.SCARY in ScoutScreenState(kids = true).moods())
    }
}
