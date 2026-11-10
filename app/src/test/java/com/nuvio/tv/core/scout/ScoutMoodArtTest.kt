package com.nuvio.tv.core.scout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoutMoodArtTest {
    @Test
    fun `every mood offered has a picture of its own in that list`() {
        ScoutWatch.entries.forEach { watch ->
            listOf(false, true).forEach { family ->
                val pictures = ScoutMood.choicesFor(watch, family).map { scoutMoodArt(it, watch, family) }
                assertTrue(pictures.all { it.startsWith("https://") })
                assertEquals(pictures.size, pictures.toSet().size)
            }
        }
    }

    @Test
    fun `every list of moods fills the two rows`() {
        ScoutWatch.entries.forEach { watch ->
            listOf(false, true).forEach { family -> assertEquals(12, ScoutMood.choicesFor(watch, family).size) }
        }
    }

    @Test
    fun `series moods show series and film moods show films`() {
        listOf(false, true).forEach { family ->
            ScoutMood.choicesFor(ScoutWatch.SERIES, family).forEach { mood ->
                assertNotEquals(scoutMoodArt(mood, ScoutWatch.MOVIE, family), scoutMoodArt(mood, ScoutWatch.SERIES, family))
            }
        }
    }

    @Test
    fun `anime romance has its own anime picture`() {
        assertNotEquals(scoutMoodArt(ScoutMood.ROMANTIC, ScoutWatch.MOVIE, false), scoutMoodArt(ScoutMood.ROMANTIC, ScoutWatch.ANIME, false))
    }

    @Test
    fun `family night swaps the grown-up pictures`() {
        listOf(ScoutMood.LAUGH, ScoutMood.ACTION, ScoutMood.EDGE, ScoutMood.DARK, ScoutMood.TEARS, ScoutMood.WEIRD).forEach { mood ->
            assertNotEquals(scoutMoodArt(mood, ScoutWatch.ANYTHING, false), scoutMoodArt(mood, ScoutWatch.ANYTHING, true))
        }
    }
}
