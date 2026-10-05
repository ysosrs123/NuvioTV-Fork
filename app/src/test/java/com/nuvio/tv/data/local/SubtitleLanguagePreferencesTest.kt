package com.nuvio.tv.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleLanguagePreferencesTest {
    private class MemoryStore(initial: Preferences) : DataStore<Preferences> {
        override val data = MutableStateFlow(initial)
        private val mutex = Mutex()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            mutex.withLock { transform(data.value).also { data.value = it } }
    }

    private val preferredKey = stringPreferencesKey("subtitle_preferred_language")
    private val secondaryKey = stringPreferencesKey("subtitle_secondary_language")
    private val tertiaryKey = stringPreferencesKey("subtitle_tertiary_language")
    private val forcedKey = booleanPreferencesKey("subtitle_use_forced_subtitles")

    private fun settings(initial: Preferences, block: suspend (PlayerSettingsDataStore, MemoryStore) -> Unit) = runBlocking {
        val manager = mockk<ProfileManager> { every { activeProfileId } returns MutableStateFlow(0) }
        val store = MemoryStore(initial)
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), "player_settings") } returns store
        val player = PlayerSettingsDataStore(factory, manager, mockk(relaxed = true))
        try { block(player, store) }
        finally {
            val field = PlayerSettingsDataStore::class.java.getDeclaredField("ioScope").apply { isAccessible = true }
            (field.get(player) as CoroutineScope).cancel()
        }
    }

    @Test fun `third language is unset by default`() = settings(preferencesOf(preferredKey to "es-419")) { player, store ->
        val style = player.playerSettings.first().subtitleStyle
        assertNull(style.tertiaryPreferredLanguage)
        assertNull(store.data.value[tertiaryKey])
    }

    @Test fun `third language is stored normalised next to the other two and cleared by none`() = settings(emptyPreferences()) { player, store ->
        player.setSubtitlePreferredLanguage("es-419")
        player.setSubtitleSecondaryLanguage("es")
        player.setSubtitleTertiaryLanguage(" PT_BR ")
        val style = player.playerSettings.first().subtitleStyle
        assertEquals("es-419", style.preferredLanguage)
        assertEquals("es", style.secondaryPreferredLanguage)
        assertEquals("pt-br", style.tertiaryPreferredLanguage)
        assertEquals("pt-br", store.data.value[tertiaryKey])

        player.setSubtitleTertiaryLanguage(null)
        assertNull(player.playerSettings.first().subtitleStyle.tertiaryPreferredLanguage)
        assertNull(store.data.value[tertiaryKey])
        assertEquals("es", store.data.value[secondaryKey])

        player.setSubtitleTertiaryLanguage("en")
        player.setSubtitleTertiaryLanguage("  ")
        assertNull(store.data.value[tertiaryKey])
    }

    @Test fun `a stored third language is normalised on load`() = settings(
        preferencesOf(preferredKey to "fr", forcedKey to false, tertiaryKey to "zh_tw")
    ) { player, store ->
        val style = player.playerSettings.first().subtitleStyle
        assertEquals("zh-TW", style.tertiaryPreferredLanguage)
        assertFalse(style.useForcedSubtitles)
        assertEquals("zh-TW", store.data.value[tertiaryKey])
    }

    @Test fun `a forced third language turns into the forced subtitles rule`() = settings(
        preferencesOf(preferredKey to "fr", forcedKey to false, tertiaryKey to "forced")
    ) { player, store ->
        val style = player.playerSettings.first().subtitleStyle
        assertNull(style.tertiaryPreferredLanguage)
        assertTrue(style.useForcedSubtitles)
        assertNull(store.data.value[tertiaryKey])
        assertEquals(true, store.data.value[forcedKey])
    }
}
