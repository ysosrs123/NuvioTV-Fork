package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.local.SubtitleStyleSettings
import com.nuvio.tv.domain.model.Subtitle
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitlePreferredLanguagesTest {
    private fun style(
        preferred: String = "fr",
        secondary: String? = "de",
        tertiary: String? = "en",
        showOnly: Boolean = true
    ) = SubtitleStyleSettings(
        preferredLanguage = preferred,
        isPreferredLanguageSystemDefault = false,
        secondaryPreferredLanguage = secondary,
        tertiaryPreferredLanguage = tertiary,
        showOnlyPreferredLanguages = showOnly
    )

    private fun controller(style: SubtitleStyleSettings) = mockk<PlayerRuntimeController>(relaxed = true) {
        every { _uiState } returns MutableStateFlow(PlayerUiState(subtitleStyle = style))
    }

    private fun track(index: Int, language: String) = TrackInfo(index = index, name = "Track ${index + 1}", language = language)

    private fun subtitle(lang: String) = Subtitle(id = "$lang-1", url = "https://subs.example/$lang.srt", lang = lang, addonName = "Addon", addonLogo = null)

    @Test fun `targets run preferred then secondary then third`() {
        assertEquals(listOf("fr", "de", "en"), subtitleLanguageTargets(style()))
        assertEquals(listOf("fr", "de"), subtitleLanguageTargets(style(tertiary = null)))
        assertEquals(listOf("fr", "en"), subtitleLanguageTargets(style(secondary = null)))
        assertEquals(emptyList<String>(), subtitleLanguageTargets(style(preferred = "none")))
    }

    @Test fun `the third language is picked only when the first two are missing`() {
        val runtime = controller(style())
        val targets = runtime.subtitleLanguageTargets()
        fun pick(vararg languages: String) = runtime.findBestInternalSubtitleTrackIndex(
            subtitleTracks = languages.mapIndexed { index, language -> track(index, language) },
            targets = targets,
            normalOnly = true
        )

        assertEquals(1, pick("it", "en"))
        assertEquals(0, pick("de", "en"))
        assertEquals(2, pick("en", "de", "fr"))
        assertEquals(-1, pick("it", "nl"))
        assertEquals(-1, controller(style(tertiary = null)).findBestInternalSubtitleTrackIndex(
            subtitleTracks = listOf(track(0, "it"), track(1, "en")),
            targets = subtitleLanguageTargets(style(tertiary = null)),
            normalOnly = true
        ))
    }

    @Test fun `show only preferred languages keeps all three and hides the rest`() {
        val all = listOf("fr", "it", "de", "nl", "en").map(::subtitle)

        assertEquals(listOf("fr", "de", "en"), controller(style()).filterToVisibleAddonSubtitles(all).map { it.lang })
        assertEquals(listOf("fr", "de"), controller(style(tertiary = null)).filterToVisibleAddonSubtitles(all).map { it.lang })
        assertEquals(all, controller(style(showOnly = false)).filterToVisibleAddonSubtitles(all))
    }

    @Test fun `show only preferred languages keeps the second and third when the first is none`() {
        val all = listOf("fr", "de", "en").map(::subtitle)

        assertEquals(listOf("de", "en"), controller(style(preferred = "none")).filterToVisibleAddonSubtitles(all).map { it.lang })
        assertEquals(listOf("en"), controller(style(preferred = "none", secondary = null)).filterToVisibleAddonSubtitles(all).map { it.lang })
        assertEquals(emptyList<Subtitle>(), controller(style(preferred = "none", secondary = null, tertiary = null)).filterToVisibleAddonSubtitles(all))
    }
}
