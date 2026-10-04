package com.nuvio.tv.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
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
import org.junit.Assert.*
import org.junit.Test

class PlayerLibassPreferencesTest {
    private class MemoryStore(initial: Preferences) : DataStore<Preferences> {
        override val data = MutableStateFlow(initial)
        private val mutex = Mutex()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            mutex.withLock { transform(data.value).also { data.value = it } }
    }

    @Test
    fun `both readers and profile migrations preserve absent false and true libass choices`() = runBlocking {
        val key = booleanPreferencesKey("use_libass")
        val active = MutableStateFlow(0)
        val manager = mockk<ProfileManager>()
        every { manager.activeProfileId } returns active
        val stores = listOf(MemoryStore(emptyPreferences()),
            MemoryStore(preferencesOf(key to false)), MemoryStore(preferencesOf(key to true)))
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), "player_settings") } answers { stores[firstArg()] }
        val settings = PlayerSettingsDataStore(factory, manager, mockk(relaxed = true))
        try {
            assertFalse(PlayerSettings().useLibass)
            assertEquals(LibassRenderType.OVERLAY_OPEN_GL, PlayerSettings().libassRenderType)
            for ((id, expected) in listOf(false, false, true).withIndex()) {
                active.value = id
                assertEquals(expected, settings.useLibass.first())
                val snapshot = settings.playerSettings.first()
                assertEquals(expected, snapshot.useLibass)
                assertEquals(LibassRenderType.OVERLAY_OPEN_GL, snapshot.libassRenderType)
                assertEquals(if (id == 0) null else expected, stores[id].data.value[key])
            }
        } finally {
            val field = PlayerSettingsDataStore::class.java.getDeclaredField("ioScope").apply { isAccessible = true }
            (field.get(settings) as CoroutineScope).cancel()
        }
    }
}
