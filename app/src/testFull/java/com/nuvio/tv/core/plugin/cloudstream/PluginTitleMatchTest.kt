package com.nuvio.tv.core.plugin.cloudstream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginTitleMatchTest {

    @Test
    fun `two different number-only titles are not a match`() {
        assertEquals(0.0, calculateSimilarity("1917", "2012"), 0.0)
        assertEquals(1.0, calculateSimilarity("1917", "1917"), 0.0)
    }

    @Test
    fun `different parts of a film are not a match`() {
        assertEquals(
            0.0,
            calculateSimilarity(
                "Harry Potter and the Deathly Hallows: Part 1",
                "Harry Potter and the Deathly Hallows: Part 2"
            ),
            0.0
        )
    }

    @Test
    fun `the same part still matches, and a site title without a part still matches`() {
        assertTrue(calculateSimilarity("Dune Part 2", "Dune: Part 2 (2024)") >= 0.9)
        assertTrue(calculateSimilarity("Invincible S4 Castellano", "Invincible") >= 0.9)
    }

    @Test
    fun `a similar-looking title has no word-for-word evidence`() {
        assertTrue(calculateSimilarity("The Marvels", "The Creator") < 0.5)
        assertFalse(titleContainedWholeWords("The Marvels", "The Creator"))
        assertTrue(calculateSimilarity("Mulan", "Minari") < 0.5)
        assertFalse(titleContainedWholeWords("Mulan", "Minari"))
    }

    @Test
    fun `a release-style site title keeps its word-for-word evidence`() {
        val site = "Spider-Man: No Way Home (2021) Dual Audio Hindi-English 480p 720p 1080p"
        assertTrue(calculateSimilarity(site, "Spider-Man: No Way Home") < 0.5)
        assertTrue(titleContainedWholeWords(site, "Spider-Man: No Way Home"))
    }

    @Test
    fun `a title with digits inside a word keeps its word-for-word evidence`() {
        assertTrue(titleContainedWholeWords("Numb3rs - Die Logik des Verbrechens", "Numb3rs"))
    }

    @Test
    fun `part of a word is not word-for-word evidence`() {
        assertFalse(titleContainedWholeWords("The Boyscout", "Boys"))
    }
}
