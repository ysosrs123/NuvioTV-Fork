package com.nuvio.tv.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.ui.screens.player.subtitleLanguagesAfterPlayerChoice
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
import org.junit.Assert.assertNull
import org.junit.Test

class SubtitlePlayerChoiceTest {
    private class MemoryStore(initial: Preferences) : DataStore<Preferences> {
        override val data = MutableStateFlow(initial)
        private val mutex = Mutex()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            mutex.withLock { transform(data.value).also { data.value = it } }
    }

    private val preferredKey = stringPreferencesKey("subtitle_preferred_language")
    private val secondaryKey = stringPreferencesKey("subtitle_secondary_language")
    private val tertiaryKey = stringPreferencesKey("subtitle_tertiary_language")
    private val beforeOffKey = stringPreferencesKey("subtitle_preferred_language_before_off")

    private val threeLanguages = preferencesOf(preferredKey to "es-419", secondaryKey to "es", tertiaryKey to "en")
    private val offKeptAside = preferencesOf(preferredKey to "none", beforeOffKey to "es-419", secondaryKey to "es", tertiaryKey to "en")

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

    private suspend fun PlayerSettingsDataStore.languages() = playerSettings.first().subtitleStyle.let {
        listOf(it.preferredLanguage, it.secondaryPreferredLanguage, it.tertiaryPreferredLanguage)
    }

    @Test fun `picking the second or third language in the player keeps the first one`() = settings(threeLanguages) { player, store ->
        player.rememberPlayerSubtitleChoice("es")
        assertEquals(listOf("es-419", "es", "en"), player.languages())
        player.rememberPlayerSubtitleChoice("en")
        assertEquals(listOf("es-419", "es", "en"), player.languages())
        assertEquals("es-419", store.data.value[preferredKey])
    }

    @Test fun `an off saved by the player before reads as the language it kept aside`() = settings(offKeptAside) { player, store ->
        val style = player.playerSettings.first().subtitleStyle
        assertEquals(listOf("es-419", "es", "en"), player.languages())
        assertEquals(false, style.isPreferredLanguageSystemDefault)
        assertNull(style.preferredLanguageBeforeOff)
        assertEquals("none", store.data.value[preferredKey])
    }

    @Test fun `None chosen in settings stays None`() = settings(threeLanguages) { player, _ ->
        player.setSubtitlePreferredLanguage("none")
        assertEquals(listOf("none", "es", "en"), player.languages())
    }

    @Test fun `picking the second language after an older off brings the first one back`() = settings(offKeptAside) { player, store ->
        player.rememberPlayerSubtitleChoice("es")
        assertEquals("es-419", store.data.value[preferredKey])
        assertEquals(listOf("es-419", "es", "en"), player.languages())
        assertNull(player.playerSettings.first().subtitleStyle.preferredLanguageBeforeOff)
        assertNull(store.data.value[beforeOffKey])
    }

    @Test fun `a language outside the three goes first and moves the others down`() = settings(threeLanguages) { player, store ->
        player.rememberPlayerSubtitleChoice("fr")
        assertEquals(listOf("fr", "es-419", "es"), player.languages())

        player.rememberPlayerSubtitleChoice("it")
        assertEquals(listOf("it", "fr", "es-419"), player.languages())
        assertNull(store.data.value[beforeOffKey])

        player.rememberPlayerSubtitleChoice("es-419")
        assertEquals(listOf("it", "fr", "es-419"), player.languages())
    }

    @Test fun `a new language after an older off goes before the language kept aside`() = settings(offKeptAside) { player, store ->
        player.rememberPlayerSubtitleChoice("fr")
        assertEquals(listOf("fr", "es-419", "es"), player.languages())
        assertNull(store.data.value[beforeOffKey])
    }

    @Test fun `a list with the same language twice loses the copy when a new one goes first`() = settings(
        preferencesOf(preferredKey to "es", secondaryKey to "es", tertiaryKey to "en")
    ) { player, _ ->
        player.rememberPlayerSubtitleChoice("fr")
        assertEquals(listOf("fr", "es", "en"), player.languages())
    }

    @Test fun `the device language moves down as the language it stands for`() = settings(
        preferencesOf(secondaryKey to "es", tertiaryKey to "de")
    ) { player, store ->
        val device = player.playerSettings.first().subtitleStyle.preferredLanguage
        player.rememberPlayerSubtitleChoice("fr")
        assertEquals("fr", store.data.value[preferredKey])
        assertEquals(device, store.data.value[secondaryKey])
        assertEquals("es", store.data.value[tertiaryKey])
    }

    @Test fun `the player mirror agrees with what is stored`() {
        val starts = listOf(
            threeLanguages,
            preferencesOf(preferredKey to "none", beforeOffKey to "es-419", secondaryKey to "es", tertiaryKey to "en"),
            preferencesOf(preferredKey to "none", secondaryKey to "es", tertiaryKey to "en"),
            preferencesOf(preferredKey to "fr", tertiaryKey to "en"),
            preferencesOf(secondaryKey to "es"),
            preferencesOf(preferredKey to "en", secondaryKey to "pt", tertiaryKey to "fr"),
            preferencesOf(preferredKey to "en-GB", secondaryKey to "pt-br", tertiaryKey to "fr"),
            preferencesOf(preferredKey to "zh-TW", secondaryKey to "zh-CN", tertiaryKey to "en")
        )
        val picks = listOf("es-419", "es", "en", "en-GB", "en-AU", "pt", "pt-br", "fr", "it", "zh", "zh-TW")
        for (start in starts) for (pick in picks) settings(start) { player, _ ->
            val before = player.playerSettings.first().subtitleStyle
            val expected = subtitleLanguagesAfterPlayerChoice(before, pick)
            player.rememberPlayerSubtitleChoice(pick)
            val after = player.playerSettings.first().subtitleStyle
            val stored = SubtitleLanguageRanking(after.preferredLanguage, after.secondaryPreferredLanguage, after.tertiaryPreferredLanguage)
            if (expected == null) {
                assertEquals("$start / $pick", SubtitleLanguageRanking(before.preferredLanguage, before.secondaryPreferredLanguage, before.tertiaryPreferredLanguage), stored)
            } else {
                assertEquals("$start / $pick", expected, stored)
            }
        }
    }

    @Test fun `a regional English pick counts as English`() = settings(
        preferencesOf(preferredKey to "en", secondaryKey to "es", tertiaryKey to "fr")
    ) { player, _ ->
        player.rememberPlayerSubtitleChoice("en-GB")
        assertEquals(listOf("en", "es", "fr"), player.languages())
        player.rememberPlayerSubtitleChoice("en-AU")
        assertEquals(listOf("en", "es", "fr"), player.languages())
    }

    @Test fun `Brazilian Portuguese and Latin American Spanish stay their own languages`() = settings(
        preferencesOf(preferredKey to "pt", secondaryKey to "es", tertiaryKey to "en")
    ) { player, _ ->
        player.rememberPlayerSubtitleChoice("pt-br")
        assertEquals(listOf("pt-br", "pt", "es"), player.languages())
        player.rememberPlayerSubtitleChoice("es-419")
        assertEquals(listOf("es-419", "pt-br", "pt"), player.languages())
    }

    @Test fun `plain Chinese does not replace a Chinese script`() = settings(
        preferencesOf(preferredKey to "zh-TW", secondaryKey to "en", tertiaryKey to "fr")
    ) { player, _ ->
        player.rememberPlayerSubtitleChoice("zh")
        assertEquals(listOf("zh", "zh-TW", "en"), player.languages())
    }

    @Test fun `a language chosen in settings replaces what was kept aside`() = settings(offKeptAside) { player, store ->
        player.setSubtitlePreferredLanguage("de")
        assertNull(store.data.value[beforeOffKey])
        assertNull(player.playerSettings.first().subtitleStyle.preferredLanguageBeforeOff)
        player.rememberPlayerSubtitleChoice("es")
        assertEquals(listOf("de", "es", "en"), player.languages())
    }

    @Test fun `the device language default stays the default when the second language is picked`() = settings(
        preferencesOf(secondaryKey to "es")
    ) { player, store ->
        player.rememberPlayerSubtitleChoice("es")
        assertNull(store.data.value[preferredKey])
        assertEquals("es", player.playerSettings.first().subtitleStyle.secondaryPreferredLanguage)
    }

    @Test fun `an older off of the device language default brings the default back`() = settings(
        preferencesOf(preferredKey to "none", beforeOffKey to SubtitleLanguageOption.DEVICE, secondaryKey to "es")
    ) { player, store ->
        assertEquals(true, player.playerSettings.first().subtitleStyle.isPreferredLanguageSystemDefault)
        player.rememberPlayerSubtitleChoice("es")
        assertEquals(SubtitleLanguageOption.DEVICE, store.data.value[preferredKey])
        assertEquals(true, player.playerSettings.first().subtitleStyle.isPreferredLanguageSystemDefault)
    }

    @Test fun `an older turn off kept nothing aside so the pick becomes the preferred language without a copy`() = settings(
        emptyPreferences()
    ) { player, store ->
        player.setSubtitlePreferredLanguage("none")
        player.setSubtitleSecondaryLanguage("es")
        player.setSubtitleTertiaryLanguage("en")
        player.rememberPlayerSubtitleChoice("es")
        assertEquals("es", store.data.value[preferredKey])
        assertEquals(listOf("es", "en", null), player.languages())
    }
}
