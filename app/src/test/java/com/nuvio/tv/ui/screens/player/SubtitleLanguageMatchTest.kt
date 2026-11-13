package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.domain.model.Subtitle
import com.nuvio.tv.ui.util.LANGUAGE_NAME_ALIASES
import com.nuvio.tv.ui.util.resolveLanguageNameAlias
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleLanguageMatchTest {
    private fun referenceAlias(tokenized: String): String? {
        if (tokenized.isBlank()) return null
        LANGUAGE_NAME_ALIASES[tokenized]?.let { return it }
        return LANGUAGE_NAME_ALIASES.entries
            .sortedByDescending { it.key.length }
            .firstOrNull { (name, _) ->
                tokenized == name ||
                    tokenized.startsWith("$name ") ||
                    tokenized.endsWith(" $name") ||
                    tokenized.contains(" $name ")
            }
            ?.value
    }

    private fun subtitle(lang: String, id: Int) = Subtitle(
        id = "$lang-$id",
        url = "https://subs.example/$lang/$id.srt",
        lang = lang,
        addonName = "Addon ${id % 5}",
        addonLogo = null
    )

    @Test fun `alias look-up answers as before`() {
        val inputs = listOf(
            "", " ", "eng", "en", "english", "english sdh", "sdh english", "forced english track",
            "chinese", "chinese (cantonese)", "chinese (cantonese) traditional", "mandarin chinese",
            "lao", "malay", "malayalam", "malay subs", "brazilian portuguese", "xx", "pt br",
            "englishman", "not english", "serbian montenegrin"
        ) + LANGUAGE_NAME_ALIASES.keys + LANGUAGE_NAME_ALIASES.keys.map { "$it forced" }
        inputs.forEach { input ->
            assertEquals("input '$input'", referenceAlias(input), resolveLanguageNameAlias(input))
        }
    }

    @Test fun `language codes normalise as before`() {
        mapOf(
            "eng" to "en",
            "English" to "en",
            "English  (SDH)" to "en",
            "pt_BR" to "pt-br",
            "pob" to "pt-br",
            "Spanish (Latin America)" to "es-419",
            "zh-TW" to "zh-tw",
            "ger" to "de",
            "xx" to "xx"
        ).forEach { (input, expected) ->
            assertEquals("input '$input'", expected, PlayerSubtitleUtils.normalizeLanguageCode(input))
        }
    }

    @Test fun `overlay key is the same on a repeated look-up`() {
        listOf("eng", "English", "pt_BR", "es-419", "zh-TW", "sr-ME", "", "   ").forEach { input ->
            assertEquals(normalizeOverlayLanguageKey(input), normalizeOverlayLanguageKey(input))
        }
        assertEquals("en", normalizeOverlayLanguageKey("eng"))
        assertEquals("pt-br", normalizeOverlayLanguageKey("pt_BR"))
        assertEquals("zh", normalizeOverlayLanguageKey("zh-TW"))
    }

    @Test fun `grouping matches a filter per language and keeps order`() {
        val languages = listOf(
            "eng", "English", "en", "spa", "Spanish (Latin America)", "pob", "por", "fre", "ger", "ita",
            "jpn", "kor", "chi", "zh-TW", "rus", "pol", "tur", "ara", "heb", "hin", "", "xx"
        )
        val subtitles = (0 until 5000).map { subtitle(languages[it % languages.size], it) }

        val grouped = groupAddonSubtitlesByLanguageKey(subtitles)

        assertEquals(subtitles.size, grouped.values.sumOf { it.size })
        grouped.forEach { (key, list) ->
            assertEquals(subtitles.filter { normalizeOverlayLanguageKey(it.lang) == key }, list)
        }
        assertTrue(grouped.keys.containsAll(listOf("en", "es-419", "pt-br", "pt", "zh")))
    }
}
