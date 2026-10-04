package com.nuvio.tv.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.nuvio.tv.core.profile.ProfileManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test

class PlayerSettingsAudioRejectionsTest {
    private class MemoryStore : DataStore<Preferences> {
        override val data = MutableStateFlow<Preferences>(emptyPreferences())
        private val lock = Mutex()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            lock.withLock { transform(data.value).also { data.value = it } }
    }

    @Test fun `forgetting clears learned rejections of the active profile only`() = runBlocking {
        val active = MutableStateFlow(0)
        val manager = mockk<ProfileManager>()
        every { manager.activeProfileId } returns active
        val stores = listOf(MemoryStore(), MemoryStore())
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), "player_settings") } answers { stores[firstArg()] }
        val settings = PlayerSettingsDataStore(factory, manager, mockk(relaxed = true))
        try {
            val dts = "type:hdmi::DTS"
            val trueHd = "type:hdmi::TRUEHD"
            val seen = stringSetPreferencesKey("audio_rejections_seen")
            val confirmed = stringSetPreferencesKey("audio_rejections_confirmed")
            settings.recordAudioRejection("type:hdmi", "DTS")
            settings.recordAudioRejection("type:hdmi", "TRUEHD")
            settings.setAllowDtshdPassthrough(false)
            stores[0].updateData { prefs -> prefs.toMutablePreferences().apply { this[seen] = setOf(dts) } }
            active.value = 1
            settings.recordAudioRejection("type:hdmi", "DTS")
            active.value = 0
            val profile0 = stores[0].data
            assertEquals(setOf(dts, trueHd), profile0.value[confirmed])
            assertEquals(setOf(dts), profile0.value[seen])

            settings.clearLearnedAudioRejections()

            assertTrue(profile0.value[seen].isNullOrEmpty())
            assertTrue(profile0.value[confirmed].isNullOrEmpty())
            assertEquals(false, profile0.value[booleanPreferencesKey("allow_dtshd_passthrough")])
            assertEquals(setOf(dts), stores[1].data.value[confirmed])
            settings.recordAudioRejection("type:hdmi", "DTS")
            assertEquals(setOf(dts), profile0.value[confirmed])

            val evidence = setOf("1700000000000;DTS,EAC3;;type:hdmi")
            settings.saveAudioRefusalEvidence(evidence)
            assertEquals(evidence, settings.audioRefusalEvidence())
            settings.clearLearnedAudioRejections()
            assertTrue(settings.audioRefusalEvidence().isEmpty())
            settings.saveAudioRefusalEvidence(evidence)
            settings.saveAudioRefusalEvidence(emptySet())
            assertTrue(settings.audioRefusalEvidence().isEmpty())
        } finally {
            val scope = PlayerSettingsDataStore::class.java.getDeclaredField("ioScope").apply { isAccessible = true }
            (scope.get(settings) as CoroutineScope).cancel()
        }
    }
}
