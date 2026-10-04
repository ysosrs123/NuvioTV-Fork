package com.nuvio.tv.core.assessment

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DeviceAssessmentApplierTest {
    private class MemoryStore : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        private val mutex = Mutex()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            mutex.withLock { transform(data.value).also { data.value = it } }
    }

    private suspend fun withStore(block: suspend (PlayerSettingsDataStore) -> Unit) {
        val memory = MemoryStore()
        val manager = mockk<ProfileManager>()
        every { manager.activeProfileId } returns MutableStateFlow(0)
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(0, "player_settings") } returns memory
        val settings = PlayerSettingsDataStore(factory, manager, mockk(relaxed = true))
        try {
            // Let the real one-time migration transaction finish before choosing custom values.
            withTimeout(5_000) {
                memory.data.first {
                    it[booleanPreferencesKey("migration_target_buffer_size_reduced_done")] == true
                }
            }
            block(settings)
        } finally {
            val scopeField = PlayerSettingsDataStore::class.java.getDeclaredField("ioScope")
                .apply { isAccessible = true }
            (scopeField.get(settings) as CoroutineScope).cancel()
        }
    }

    @Test
    fun `reverting a network recommendation preserves custom buffer settings including zero rewind`() = runBlocking {
        withStore { store ->
            for (backBufferMs in listOf(0, 9_000, 120_000)) {
                store.setBufferBackBufferDurationMs(backBufferMs)
                store.setBufferRetainBackBufferFromKeyframe(true)
                store.setEnableHttp2(false)
                val before = store.playerSettings.first()
                DeviceAssessmentApplier.apply(store, AssessmentApplyPlan(enableHttp2 = true), null, 2_048)
                assertEquals(backBufferMs, JSONObject(store.assessmentRevertSnapshot.first()!!)
                    .getInt("backBufferDurationMs"))
                assertTrue(store.playerSettings.first().enableHttp2)
                assertTrue(DeviceAssessmentApplier.revert(store))
                val restored = store.playerSettings.first()
                assertEquals(before.bufferSettings, restored.bufferSettings)
                assertEquals(before.enableHttp2, restored.enableHttp2)
                assertEquals(before.nuvioPerformanceModeEnabled, restored.nuvioPerformanceModeEnabled)
                assertNull(store.assessmentRevertSnapshot.first())
            }
        }
    }

    @Test
    fun `revert restores rewind changed indirectly by the native preset setter`() = runBlocking {
        withStore { store ->
            store.setBufferBackBufferDurationMs(23_000)
            val before = store.playerSettings.first()
            DeviceAssessmentApplier.apply(store,
                AssessmentApplyPlan(nuvioPerformanceModeEnabled = false), null, 2_048)
            assertNotEquals(23_000, store.playerSettings.first().bufferSettings.backBufferDurationMs)
            assertTrue(DeviceAssessmentApplier.revert(store))
            assertEquals(before.bufferSettings, store.playerSettings.first().bufferSettings)
        }
    }

    @Test
    fun `legacy snapshots retain the current rewind when the original value is unavailable`() = runBlocking {
        withStore { store ->
            DeviceAssessmentApplier.apply(store, AssessmentApplyPlan(enableHttp2 = true), null, 2_048)
            val legacy = JSONObject(store.assessmentRevertSnapshot.first()!!)
            legacy.remove("backBufferDurationMs")
            store.setAssessmentRevertSnapshot(legacy.toString())
            store.setBufferBackBufferDurationMs(17_000)
            assertTrue(DeviceAssessmentApplier.revert(store))
            assertEquals(17_000, store.playerSettings.first().bufferSettings.backBufferDurationMs)
            assertFalse(store.playerSettings.first().enableHttp2)
            assertNull(store.assessmentRevertSnapshot.first())
        }
    }

    private fun oldFormatSnapshot(s: PlayerSettings) = JSONObject().apply {
        put("timestampMs", 1L)
        put("nuvioPerformanceModeEnabled", s.nuvioPerformanceModeEnabled)
        put("bufferEngineEnabled", s.bufferEngineEnabled)
        put("parallelNetworkEnabled", s.parallelNetworkEnabled)
        put("bufferBudgetManaged", s.bufferBudgetManaged)
        put("allowLargeTargetBuffer", s.allowLargeTargetBuffer)
        put("targetBufferSizeMb", s.bufferSettings.targetBufferSizeMb)
        put("minBufferMs", s.bufferSettings.minBufferMs)
        put("maxBufferMs", s.bufferSettings.maxBufferMs)
        put("backBufferDurationMs", s.bufferSettings.backBufferDurationMs)
        put("bufferForPlaybackMs", s.bufferSettings.bufferForPlaybackMs)
        put("bufferForPlaybackAfterRebufferMs", s.bufferSettings.bufferForPlaybackAfterRebufferMs)
        put("useParallelConnections", s.useParallelConnections)
        put("parallelConnectionCount", s.parallelConnectionCount)
        put("parallelChunkSizeKb", s.parallelChunkSizeKb)
        put("enableHttp2", s.enableHttp2)
        put("vodCacheEnabled", s.vodCacheEnabled)
        put("vodCacheSizeMode", s.vodCacheSizeMode.name)
        put("frameRateMatchingMode", s.frameRateMatchingMode.name)
        put("resolutionMatchingEnabled", s.resolutionMatchingEnabled)
        put("dv7HandlingMode", s.dv7HandlingMode.name)
        put("dv5ToDv81Enabled", s.dv5ToDv81Enabled)
        put("stripHdr10PlusSei", s.stripHdr10PlusSei)
        put("forceOpticalPassthrough", s.forceOpticalPassthrough)
        put("deniedCodecHandling", "TRANSCODE_AC3")
        put("allowAc3Passthrough", false)
        put("allowEac3Passthrough", false)
        put("allowTrueHdPassthrough", false)
        put("allowDtsPassthrough", false)
        put("allowDtsHdPassthrough", false)
        put("matPassthroughEnabled", true)
    }

    @Test
    fun `old snapshot with audio format fields reverts the kept fields and ignores the rest`() = runBlocking {
        withStore { store ->
            store.setBufferBackBufferDurationMs(21_000)
            store.setEnableHttp2(false)
            store.setForceOpticalPassthrough(true)
            val before = store.playerSettings.first()
            store.setAssessmentRevertSnapshot(oldFormatSnapshot(before).toString())
            store.setEnableHttp2(true)
            store.setForceOpticalPassthrough(false)
            val changed = store.playerSettings.first()

            assertTrue(DeviceAssessmentApplier.revert(store))

            val restored = store.playerSettings.first()
            assertFalse(restored.enableHttp2)
            assertTrue(restored.forceOpticalPassthrough)
            assertEquals(before.bufferSettings, restored.bufferSettings)
            assertEquals(changed.surroundFormatMode, restored.surroundFormatMode)
            assertEquals(changed.deniedCodecHandling, restored.deniedCodecHandling)
            assertEquals(changed.allowAc3Passthrough, restored.allowAc3Passthrough)
            assertEquals(changed.allowEac3Passthrough, restored.allowEac3Passthrough)
            assertEquals(changed.allowTruehdPassthrough, restored.allowTruehdPassthrough)
            assertEquals(changed.allowDtsPassthrough, restored.allowDtsPassthrough)
            assertEquals(changed.allowDtshdPassthrough, restored.allowDtshdPassthrough)
            assertNull(store.assessmentRevertSnapshot.first())
        }
    }

    @Test
    fun `new snapshots no longer capture audio format settings`() = runBlocking {
        withStore { store ->
            DeviceAssessmentApplier.apply(store, AssessmentApplyPlan(enableHttp2 = true), null, 2_048)
            val snapshot = JSONObject(store.assessmentRevertSnapshot.first()!!)
            assertTrue(snapshot.has("forceOpticalPassthrough"))
            listOf("deniedCodecHandling", "allowAc3Passthrough", "allowEac3Passthrough", "allowTrueHdPassthrough",
                "allowDtsPassthrough", "allowDtsHdPassthrough", "matPassthroughEnabled")
                .forEach { assertFalse(it, snapshot.has(it)) }
        }
    }

    @Test
    fun `revert without a snapshot leaves settings alone`() = runBlocking {
        withStore { store ->
            store.setBufferBackBufferDurationMs(19_000)
            val before = store.playerSettings.first()
            assertFalse(DeviceAssessmentApplier.revert(store))
            assertEquals(before, store.playerSettings.first())
        }
    }
}
