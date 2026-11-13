package com.nuvio.tv.core.scout

import com.nuvio.tv.domain.model.ScoutExclusions
import com.nuvio.tv.domain.model.ScoutPick
import com.nuvio.tv.domain.model.scoutHistorySignature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoutLeaveOutLanguagesTest {
    private fun pick(id: Int, language: String?, rating: Float = 7.5f) =
        ScoutPick(tmdbId = id, tv = false, name = "t$id", rating = rating, votes = 500, genres = listOf(18), language = language)

    @Test
    fun `indian languages and chinese cover several original languages`() {
        val codes = scoutLeaveOutLanguageCodes(setOf(SCOUT_LANGUAGE_INDIAN, "zh", "unknown"))
        assertTrue(codes.containsAll(listOf("hi", "ta", "te", "ml", "kn", "zh", "cn")))
        assertFalse("en" in codes)
    }

    @Test
    fun `a left-out language blocks its titles and nothing else`() {
        val exclusions = ScoutExclusions(languages = scoutLeaveOutLanguageCodes(setOf(SCOUT_LANGUAGE_INDIAN)))
        assertTrue(exclusions.blocks(pick(1, "te")))
        assertFalse(exclusions.blocks(pick(2, "en")))
        assertFalse(exclusions.blocks(pick(3, null)))
    }

    @Test
    fun `choosing languages rebuilds the pool, choosing none keeps the old signature`() {
        val plain = scoutHistorySignature(emptyList(), setOf(27))
        assertEquals(plain, scoutHistorySignature(emptyList(), setOf(27), emptySet()))
        assertNotEquals(plain, scoutHistorySignature(emptyList(), setOf(27), setOf("ko")))
    }

    @Test
    fun `a language marked not for me twice is avoided, once is not`() {
        val liked = (1..5).map { ScoutTasteTitle("l$it", language = "en") to 1.0 }
        val twice = scoutTasteVector(liked + listOf(ScoutTasteTitle("a", language = "hi") to -1.0, ScoutTasteTitle("b", language = "hi") to -1.0))
        val once = scoutTasteVector(liked + listOf(ScoutTasteTitle("a", language = "hi") to -1.0))
        assertEquals(setOf("hi"), twice.avoidedLanguages)
        assertTrue(once.avoidedLanguages.isEmpty())
    }

    @Test
    fun `a language the profile also loves is not avoided`() {
        val loved = (1..4).map { ScoutTasteTitle("k$it", language = "ko") to 1.0 }
        val taste = scoutTasteVector(loved + listOf(ScoutTasteTitle("a", language = "ko") to -1.0, ScoutTasteTitle("b", language = "ko") to -1.0))
        assertTrue(taste.avoidedLanguages.isEmpty())
    }

    @Test
    fun `stretch picks skip an avoided language`() {
        val liked = (1..5).map { ScoutTasteTitle("l$it", language = "en") to 1.0 }
        val taste = scoutTasteVector(liked + listOf(ScoutTasteTitle("a", language = "hi") to -1.0, ScoutTasteTitle("b", language = "hi") to -1.0))
        val picks = (1..20).map { pick(it, "en", rating = 6f) } + (21..60).map { pick(it, "hi") }
        val out = scoutAddStretch(picks, emptyMap(), taste)
        assertTrue(out.none { it.stretch })
        val open = scoutAddStretch(picks, emptyMap(), scoutTasteVector(liked))
        assertTrue(open.any { it.stretch && it.language == "hi" })
    }
}
