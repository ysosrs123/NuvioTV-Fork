package com.nuvio.tv.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.AppFont
import com.nuvio.tv.domain.model.SubtitleFont
import io.mockk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test

class SubtitleAppearancePreferencesTest {
    private class MemoryStore(initial: Preferences) : DataStore<Preferences> {
        override val data = MutableStateFlow(initial)
        private val mutex = Mutex()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            mutex.withLock { transform(data.value).also { data.value = it } }
    }
    private val fontKey = stringPreferencesKey("subtitle_font")
    private val edgeKey = stringPreferencesKey("subtitle_edge_style")
    private val oldOutline = booleanPreferencesKey("subtitle_outline_enabled")
    private fun settings(initial: List<Preferences>, block: suspend (PlayerSettingsDataStore, ThemeDataStore, MutableStateFlow<Int>, List<MemoryStore>) -> Unit) = runBlocking {
        val active = MutableStateFlow(0)
        val manager = mockk<ProfileManager> { every { activeProfileId } returns active }
        val stores = initial.map { MemoryStore(it) }
        val themeStores = initial.map { MemoryStore(emptyPreferences()) }
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), "player_settings") } answers { stores[firstArg()] }
        every { factory.get(any(), "theme_settings") } answers { themeStores[firstArg()] }
        val player = PlayerSettingsDataStore(factory, manager, mockk(relaxed = true))
        try { block(player, ThemeDataStore(factory, manager), active, stores) }
        finally {
            val field = PlayerSettingsDataStore::class.java.getDeclaredField("ioScope").apply { isAccessible = true }
            (field.get(player) as CoroutineScope).cancel()
        }
    }

    @Test fun `new profiles default to shadow while legacy on and off survive without writing keys`() = settings(
        listOf(emptyPreferences(), preferencesOf(oldOutline to true), preferencesOf(oldOutline to false))
    ) { player, _, active, stores ->
        for (id in stores.indices) {
            active.value = id
            val style = player.playerSettings.first().subtitleStyle
            assertNull(style.font)
            assertEquals(when (id) { 0 -> SubtitleEdgeStyle.DROP_SHADOW; 1 -> SubtitleEdgeStyle.OUTLINE; else -> SubtitleEdgeStyle.NONE }, style.effectiveEdgeStyle)
            assertNull(stores[id].data.value[fontKey]); assertNull(stores[id].data.value[edgeKey])
        }
    }

    @Test fun `stored UI font names still read as the same subtitle font`() = settings(
        AppFont.entries.map { preferencesOf(fontKey to it.name) }
    ) { player, _, active, stores ->
        for ((id, font) in AppFont.entries.withIndex()) {
            active.value = id
            val chosen = player.playerSettings.first().subtitleStyle.font
            assertEquals(font, chosen?.appFont)
            assertEquals(font.displayName, chosen?.displayName)
            assertEquals(font.name, stores[id].data.value[fontKey])
        }
    }

    @Test fun `subtitle-only fonts are stored by name and read back`() = settings(
        listOf(emptyPreferences())
    ) { player, theme, _, stores ->
        for (font in listOf(SubtitleFont.ROBOTO, SubtitleFont.MONTSERRAT, SubtitleFont.NOTO_SANS_SEMIBOLD, SubtitleFont.ASAP_CONDENSED)) {
            player.setSubtitleFont(font)
            assertEquals(font.name, stores[0].data.value[fontKey])
            assertEquals(font, player.playerSettings.first().subtitleStyle.font)
            assertNull(font.appFont)
        }
        assertEquals(AppFont.INTER, theme.selectedFont.first())
        assertEquals(SubtitleFont.NOTO_SANS_SEMIBOLD, parseSubtitleFont("NOTO_SANS_SEMIBOLD"))
        assertNull(parseSubtitleFont(null))
    }

    @Test fun `unknown font and edge retain renderer default and legacy off`() = settings(
        listOf(preferencesOf(fontKey to "FUTURE_FONT", edgeKey to "FUTURE_EDGE", oldOutline to false))
    ) { player, _, _, stores ->
        val style = player.playerSettings.first().subtitleStyle
        assertNull(style.font); assertEquals(SubtitleEdgeStyle.NONE, style.effectiveEdgeStyle)
        assertEquals("FUTURE_FONT", stores[0].data.value[fontKey])
    }

    @Test fun `all subtitle font choices are independent of UI choice and other profiles`() = settings(
        listOf(emptyPreferences(), emptyPreferences())
    ) { player, theme, active, _ ->
        assertEquals(5, AppFont.entries.size)
        assertEquals(9, SubtitleFont.entries.size)
        for (font in SubtitleFont.entries) {
            player.setSubtitleFont(font)
            assertEquals(font, player.playerSettings.first().subtitleStyle.font)
            assertEquals(AppFont.INTER, theme.selectedFont.first())
        }
        theme.setFont(AppFont.ATKINSON_HYPERLEGIBLE_NEXT)
        assertEquals(SubtitleFont.ASAP_CONDENSED, player.playerSettings.first().subtitleStyle.font)
        active.value = 1
        assertNull(player.playerSettings.first().subtitleStyle.font)
        theme.setFont(AppFont.SOURCE_SANS_3)
        player.setSubtitleFont(SubtitleFont.DM_SANS)
        active.value = 0
        assertEquals(SubtitleFont.ASAP_CONDENSED, player.playerSettings.first().subtitleStyle.font)
        assertEquals(AppFont.ATKINSON_HYPERLEGIBLE_NEXT, theme.selectedFont.first())
        player.setSubtitleFont(null)
        assertNull(player.playerSettings.first().subtitleStyle.font)
        assertEquals(AppFont.ATKINSON_HYPERLEGIBLE_NEXT, theme.selectedFont.first())
    }

    @Test fun `edge choices persist and old outline setter clears the new mode`() = settings(
        listOf(emptyPreferences(), emptyPreferences())
    ) { player, _, active, stores ->
        for (edge in SubtitleEdgeStyle.entries) {
            player.setSubtitleEdgeStyle(edge)
            assertEquals(edge, player.playerSettings.first().subtitleStyle.effectiveEdgeStyle)
            assertEquals(edge.name, stores[0].data.value[edgeKey])
            assertEquals(edge == SubtitleEdgeStyle.OUTLINE, stores[0].data.value[oldOutline])
        }
        active.value = 1
        assertEquals(SubtitleEdgeStyle.DROP_SHADOW, player.playerSettings.first().subtitleStyle.effectiveEdgeStyle)
        active.value = 0
        player.setSubtitleOutlineEnabled(true)
        assertEquals(SubtitleEdgeStyle.OUTLINE, player.playerSettings.first().subtitleStyle.effectiveEdgeStyle)
        assertNull(stores[0].data.value[edgeKey])
    }

    @Test fun `reset restores shadow atomically but preserves UI and subtitle language`() = settings(
        listOf(preferencesOf(stringPreferencesKey("subtitle_preferred_language") to "fr"))
    ) { player, theme, _, stores ->
        theme.setFont(AppFont.SOURCE_SANS_3)
        player.setSubtitleFont(SubtitleFont.ATKINSON_HYPERLEGIBLE_NEXT)
        player.setSubtitleEdgeStyle(SubtitleEdgeStyle.NONE)
        player.setSubtitleSize(180)
        player.resetSubtitleAppearance()
        val style = player.playerSettings.first().subtitleStyle
        assertNull(style.font); assertEquals(SubtitleEdgeStyle.DROP_SHADOW, style.effectiveEdgeStyle)
        assertEquals(100, style.size)
        assertEquals("fr", style.preferredLanguage)
        assertEquals(AppFont.SOURCE_SANS_3, theme.selectedFont.first())
        assertNull(stores[0].data.value[fontKey]); assertEquals(SubtitleEdgeStyle.DROP_SHADOW.name, stores[0].data.value[edgeKey])
        assertEquals(false, stores[0].data.value[oldOutline])
    }

    @Test fun `existing size and color choices without an edge choice use shadow`() = settings(
        listOf(preferencesOf(intPreferencesKey("subtitle_size") to 150, intPreferencesKey("subtitle_text_color") to -65536))
    ) { player, _, _, stores ->
        val style=player.playerSettings.first().subtitleStyle
        assertEquals(SubtitleEdgeStyle.DROP_SHADOW,style.effectiveEdgeStyle)
        assertEquals(150,style.size);assertEquals(-65536,style.textColor)
        assertNull(stores[0].data.value[edgeKey])
    }
    @Test fun `future edge without a legacy choice keeps safe outline fallback and raw value`() = settings(
        listOf(preferencesOf(edgeKey to "FUTURE_EDGE"))
    ) { player, _, _, stores ->
        assertEquals(SubtitleEdgeStyle.OUTLINE,player.playerSettings.first().subtitleStyle.effectiveEdgeStyle)
        assertEquals("FUTURE_EDGE",stores[0].data.value[edgeKey])
    }
    private val languageKey = stringPreferencesKey("subtitle_preferred_language")
    private val forcedKey = booleanPreferencesKey("subtitle_use_forced_subtitles")
    @Test fun `no saved subtitle language means the device language with forced only when the audio matches`() = settings(
        listOf(emptyPreferences(), preferencesOf(languageKey to "fr"))
    ) { player, _, active, _ ->
        val fresh = player.playerSettings.first().subtitleStyle
        assertTrue(fresh.isPreferredLanguageSystemDefault)
        assertNotEquals("none", fresh.preferredLanguage)
        assertTrue(fresh.useForcedSubtitles)
        active.value = 1
        val chosen = player.playerSettings.first { it.subtitleStyle.preferredLanguage == "fr" }.subtitleStyle
        assertFalse(chosen.isPreferredLanguageSystemDefault)
        assertFalse(chosen.useForcedSubtitles)
    }
    @Test fun `a subtitle choice in the player becomes the preference and keeps the forced rule`() = settings(
        listOf(emptyPreferences())
    ) { player, _, _, stores ->
        assertEquals("es", player.selectableSubtitleLanguage("es"))
        assertNull(player.selectableSubtitleLanguage("und"))
        assertNull(player.selectableSubtitleLanguage("none"))
        player.rememberPlayerSubtitleChoice("es")
        val picked = player.playerSettings.first { it.subtitleStyle.preferredLanguage == "es" }.subtitleStyle
        assertTrue(picked.useForcedSubtitles)
        assertEquals(true, stores[0].data.value[forcedKey])
    }
}
