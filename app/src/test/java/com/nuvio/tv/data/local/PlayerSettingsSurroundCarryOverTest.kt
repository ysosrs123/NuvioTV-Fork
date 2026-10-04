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

class PlayerSettingsSurroundCarryOverTest {
    private class MemoryStore(initial: Preferences) : DataStore<Preferences> {
        override val data = MutableStateFlow(initial)
        private val mutex = Mutex()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            mutex.withLock { transform(data.value).also { data.value = it } }
    }

    private val legacyDtsHdKey = booleanPreferencesKey("allow_dts_hd_passthrough")
    private val dtsHdKey = booleanPreferencesKey("allow_dtshd_passthrough")
    private val trueHdKey = booleanPreferencesKey("allow_truehd_passthrough")
    private val modeKey = stringPreferencesKey("surround_format_mode")
    private val deniedHandlingKey = stringPreferencesKey("denied_codec_handling")

    private fun withStore(initial: Preferences, block: suspend (PlayerSettingsDataStore, MemoryStore) -> Unit) {
        val manager = mockk<ProfileManager>()
        every { manager.activeProfileId } returns MutableStateFlow(0)
        val store = MemoryStore(initial)
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), "player_settings") } returns store
        val settings = PlayerSettingsDataStore(factory, manager, mockk(relaxed = true))
        try {
            runBlocking { block(settings, store) }
        } finally {
            val field = PlayerSettingsDataStore::class.java.getDeclaredField("ioScope").apply { isAccessible = true }
            (field.get(settings) as CoroutineScope).cancel()
        }
    }

    @Test
    fun `fresh install reads as auto with every format allowed`() = withStore(emptyPreferences()) { settings, _ ->
        val snapshot = settings.playerSettings.first()
        assertEquals(SurroundFormatMode.AUTO, snapshot.surroundFormatMode)
        assertTrue(snapshot.allowDtshdPassthrough)
        assertTrue(snapshot.allowTruehdPassthrough)
        assertFalse(snapshot.useSystemPassthrough)
    }

    @Test
    fun `dts-hd switch stored under the old key is still read`() =
        withStore(preferencesOf(legacyDtsHdKey to false)) { settings, _ ->
            val snapshot = settings.playerSettings.first()
            assertFalse(snapshot.allowDtshdPassthrough)
            assertEquals(SurroundFormatMode.MANUAL, snapshot.surroundFormatMode)
        }

    @Test
    fun `new dts-hd key wins over the old one`() =
        withStore(preferencesOf(legacyDtsHdKey to false, dtsHdKey to true)) { settings, _ ->
            val snapshot = settings.playerSettings.first()
            assertTrue(snapshot.allowDtshdPassthrough)
            assertEquals(SurroundFormatMode.AUTO, snapshot.surroundFormatMode)
        }

    @Test
    fun `a format switched off before the mode existed reads as manual`() =
        withStore(preferencesOf(trueHdKey to false)) { settings, _ ->
            val snapshot = settings.playerSettings.first()
            assertEquals(SurroundFormatMode.MANUAL, snapshot.surroundFormatMode)
            assertFalse(snapshot.allowTruehdPassthrough)
        }

    @Test
    fun `stored transcode handling reads as manual`() =
        withStore(preferencesOf(deniedHandlingKey to DeniedCodecHandling.TRANSCODE_AC3.name)) { settings, _ ->
            val snapshot = settings.playerSettings.first()
            assertEquals(SurroundFormatMode.MANUAL, snapshot.surroundFormatMode)
            assertEquals(DeniedCodecHandling.TRANSCODE_AC3, snapshot.deniedCodecHandling)
        }

    @Test
    fun `switches stored on and pcm handling stay auto`() =
        withStore(
            preferencesOf(trueHdKey to true, deniedHandlingKey to DeniedCodecHandling.DECODE_PCM.name)
        ) { settings, _ ->
            assertEquals(SurroundFormatMode.AUTO, settings.playerSettings.first().surroundFormatMode)
        }

    @Test
    fun `a stored mode is never overridden by the switches`() =
        withStore(preferencesOf(trueHdKey to false, modeKey to SurroundFormatMode.AUTO.name)) { settings, _ ->
            assertEquals(SurroundFormatMode.AUTO, settings.playerSettings.first().surroundFormatMode)
        }

    @Test
    fun `turning the last switch back on keeps a carried-over manual mode`() =
        withStore(preferencesOf(trueHdKey to false)) { settings, store ->
            settings.setAllowTruehdPassthrough(true)
            assertEquals(SurroundFormatMode.MANUAL.name, store.data.value[modeKey])
            assertEquals(SurroundFormatMode.MANUAL, settings.playerSettings.first().surroundFormatMode)
        }

    @Test
    fun `writing the dts-hd switch moves it to the new key`() =
        withStore(preferencesOf(legacyDtsHdKey to false)) { settings, store ->
            settings.setAllowDtshdPassthrough(true)
            assertEquals(true, store.data.value[dtsHdKey])
            assertNull(store.data.value[legacyDtsHdKey])
            assertTrue(settings.playerSettings.first().allowDtshdPassthrough)
        }

    @Test
    fun `mode from stored values`() {
        assertEquals(
            SurroundFormatMode.AUTO,
            storedSurroundFormatMode(null, listOf(null, null, null, null, null), null)
        )
        assertEquals(
            SurroundFormatMode.MANUAL,
            storedSurroundFormatMode(null, listOf(true, null, false, null, null), null)
        )
        assertEquals(
            SurroundFormatMode.MANUAL,
            storedSurroundFormatMode(null, listOf(null, null, null, null, null), "TRANSCODE_AC3")
        )
        assertEquals(
            SurroundFormatMode.AUTO,
            storedSurroundFormatMode("AUTO", listOf(false, false, false, false, false), "TRANSCODE_AC3")
        )
    }
}
