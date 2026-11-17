package com.nuvio.tv.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerSettingsBadValueTest {
    private class MemoryStore(initial: Preferences) : DataStore<Preferences> {
        override val data = MutableStateFlow(initial)
        private val mutex = Mutex()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            mutex.withLock { transform(data.value).also { data.value = it } }
    }

    @Test
    fun `a value saved with the wrong type only resets that setting`() = runBlocking {
        val store = MemoryStore(
            preferencesOf(
                booleanPreferencesKey("decoder_priority") to true,
                booleanPreferencesKey("use_libass") to true
            )
        )
        val manager = mockk<ProfileManager>()
        every { manager.activeProfileId } returns MutableStateFlow(1)
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), "player_settings") } returns store
        val settings = PlayerSettingsDataStore(factory, manager, mockk(relaxed = true))
        try {
            val snapshot = settings.playerSettings.first()
            assertTrue(snapshot.useLibass)
            assertEquals(PlayerSettings().decoderPriority, snapshot.decoderPriority)

            settings.setDecoderPriority(2)
            assertEquals(2, store.data.value[intPreferencesKey("decoder_priority")])
            assertEquals(2, settings.playerSettings.first().decoderPriority)
            assertTrue(settings.playerSettings.first().useLibass)
        } finally {
            val field = PlayerSettingsDataStore::class.java.getDeclaredField("ioScope").apply { isAccessible = true }
            (field.get(settings) as CoroutineScope).cancel()
        }
    }
}
