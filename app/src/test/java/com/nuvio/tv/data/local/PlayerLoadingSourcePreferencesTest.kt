package com.nuvio.tv.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.nuvio.tv.core.profile.ProfileManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PlayerLoadingSourcePreferencesTest {
    private class MemoryStore : DataStore<Preferences> {
        override val data = MutableStateFlow<Preferences>(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }
    @Test fun `image subtitle size stays independent of text and other profiles`() = runBlocking {
        val active = MutableStateFlow(0)
        val manager = mockk<ProfileManager>()
        every { manager.activeProfileId } returns active
        val stores = listOf(MemoryStore(), MemoryStore())
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), "player_settings") } answers { stores[firstArg()] }
        val settings = PlayerSettingsDataStore(factory, manager, mockk(relaxed = true))
        try {
            assertFalse(settings.playerSettings.first().speculativeStreamSearchEnabled)
            settings.setSpeculativeStreamSearchEnabled(true)
            assertTrue(settings.playerSettings.first().speculativeStreamSearchEnabled)
            active.value = 1
            assertFalse(settings.playerSettings.first().speculativeStreamSearchEnabled)
            active.value = 0
            assertTrue(settings.playerSettings.first().speculativeStreamSearchEnabled)
            settings.setSpeculativeStreamSearchEnabled(false)
            assertFalse(settings.playerSettings.first().speculativeStreamSearchEnabled)
            settings.setSubtitleSize(100)
            settings.setSubtitleBitmapSize(60)
            assertEquals(100, settings.playerSettings.first().subtitleStyle.size)
            assertEquals(60, settings.playerSettings.first().subtitleStyle.bitmapSize)
            settings.setSubtitleSize(120)
            assertEquals(60, settings.playerSettings.first().subtitleStyle.bitmapSize)
            active.value = 1
            assertEquals(100, settings.playerSettings.first().subtitleStyle.bitmapSize)
            settings.setSubtitleBitmapSize(999)
            assertEquals(200, settings.playerSettings.first().subtitleStyle.bitmapSize)
            settings.setSubtitleBitmapSize(0)
            assertEquals(50, settings.playerSettings.first().subtitleStyle.bitmapSize)
            active.value = 0
            assertEquals(60, settings.playerSettings.first().subtitleStyle.bitmapSize)
            assertEquals(120, settings.playerSettings.first().subtitleStyle.size)
        } finally {
            val scope = PlayerSettingsDataStore::class.java.getDeclaredField("ioScope").apply { isAccessible = true }
            (scope.get(settings) as CoroutineScope).cancel()
        }
    }
    @Test fun `source visibility is persisted independently from status and between profiles`() = runBlocking {
        val active = MutableStateFlow(0)
        val manager = mockk<ProfileManager>()
        every { manager.activeProfileId } returns active
        val stores = listOf(MemoryStore(), MemoryStore())
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), "player_settings") } answers { stores[firstArg()] }
        val settings = PlayerSettingsDataStore(factory, manager, mockk(relaxed = true))
        try {
            assertTrue(settings.playerSettings.first().showPlayerLoadingSource)
            settings.setShowPlayerLoadingSource(false)
            assertFalse(settings.playerSettings.first().showPlayerLoadingSource)
            assertTrue(settings.playerSettings.first().showPlayerLoadingStatus)
            active.value = 1
            assertTrue(settings.playerSettings.first().showPlayerLoadingSource)
            settings.setShowPlayerLoadingStatus(false)
            assertTrue(settings.playerSettings.first().showPlayerLoadingSource)
            active.value = 0
            assertFalse(settings.playerSettings.first().showPlayerLoadingSource)
            assertTrue(settings.playerSettings.first().showPlayerLoadingStatus)
        } finally {
            val scope = PlayerSettingsDataStore::class.java.getDeclaredField("ioScope").apply { isAccessible = true }
            (scope.get(settings) as CoroutineScope).cancel()
        }
    }
}
