package com.nuvio.tv.core.assessment

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test

class AssessmentProfileOwnershipTest {
    private class MemoryStore : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        private val mutex = Mutex()
        var afterUpdate: (suspend (Preferences) -> Unit)? = null
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            val next = mutex.withLock { transform(data.value).also { data.value = it } }
            afterUpdate?.invoke(next)
            return next
        }
    }
    private class Fixture(val settings: PlayerSettingsDataStore, val active: MutableStateFlow<Int>,
        val first: MemoryStore, val second: MemoryStore)
    private suspend fun fixture(block: suspend (Fixture) -> Unit) {
        val first = MemoryStore(); val second = MemoryStore(); val active = MutableStateFlow(1)
        val manager = mockk<ProfileManager>(); every { manager.activeProfileId } returns active
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(1, "player_settings") } returns first
        every { factory.get(2, "player_settings") } returns second
        val settings = PlayerSettingsDataStore(factory, manager, mockk(relaxed = true))
        try {
            val done = booleanPreferencesKey("migration_target_buffer_size_reduced_done")
            withTimeout(5_000) {
                first.data.first { it[done] == true }
                active.value = 2; second.data.first { it[done] == true }
                settings.setEnableHttp2(false)
                active.value = 1; settings.setEnableHttp2(false)
                settings.playerSettings.first()
            }
            block(Fixture(settings, active, first, second))
        } finally {
            val field = PlayerSettingsDataStore::class.java.getDeclaredField("ioScope").apply { isAccessible = true }
            (field.get(settings) as CoroutineScope).cancel()
        }
    }
    @Test fun `profile switch after snapshot does not redirect subsequent apply writes`() = runBlocking {
        fixture { f ->
            f.first.afterUpdate = { prefs ->
                if (prefs[stringPreferencesKey("assessment_revert_snapshot")] != null) {
                    f.first.afterUpdate = null
                    f.active.value = 2
                }
            }
            DeviceAssessmentApplier.apply(f.settings, AssessmentApplyPlan(enableHttp2 = true), null, 2_048)
            assertEquals("Original profile must receive its requested write", true, f.first.data.value[booleanPreferencesKey("enable_http2")])
            assertEquals("Other profile must remain untouched", false, f.second.data.value[booleanPreferencesKey("enable_http2")])
        }
    }

    @Test fun `explicit profile keeps snapshot reads and writes together`() = runBlocking {
        fixture { f ->
            f.settings.setBufferBackBufferDurationMs(19_000)
            f.active.value = 2; f.settings.setBufferBackBufferDurationMs(4_000)
            DeviceAssessmentApplier.apply(f.settings, AssessmentApplyPlan(enableHttp2 = true), null, 2_048, profileId = 1)
            assertTrue(f.settings.playerSettingsForProfile(1).first().enableHttp2)
            assertFalse(f.settings.playerSettingsForProfile(2).first().enableHttp2)
            val snapshot = org.json.JSONObject(f.settings.assessmentRevertSnapshotForProfile(1).first()!!)
            assertEquals(19_000, snapshot.getInt("backBufferDurationMs"))
            assertNull(f.settings.assessmentRevertSnapshotForProfile(2).first())
        }
    }
    @Test fun `profile switch during revert restores and clears only original profile`() = runBlocking {
        fixture { f ->
            f.settings.setBufferBackBufferDurationMs(19_000)
            val before = f.settings.playerSettings.first()
            DeviceAssessmentApplier.apply(f.settings, AssessmentApplyPlan(enableHttp2 = true), null, 2_048)
            val otherBefore = f.second.data.value
            f.first.afterUpdate = { f.first.afterUpdate = null; f.active.value = 2 }
            assertTrue(DeviceAssessmentApplier.revert(f.settings))
            val after = f.settings.playerSettingsForProfile(1).first()
            assertEquals(before.bufferSettings, after.bufferSettings); assertFalse(after.enableHttp2)
            assertNull(f.settings.assessmentRevertSnapshotForProfile(1).first())
            assertEquals(otherBefore, f.second.data.value)
        }
    }
    @Test fun `failed apply retains snapshot and releases mutation for revert`() = runBlocking {
        fixture { f ->
            f.first.afterUpdate = { prefs ->
                if (prefs[stringPreferencesKey("assessment_revert_snapshot")] != null) {
                    f.first.afterUpdate = null; throw java.io.IOException("write interruption")
                }
            }
            try { DeviceAssessmentApplier.apply(f.settings, AssessmentApplyPlan(enableHttp2 = true), null, 2_048); fail("expected failure") }
            catch (_: java.io.IOException) { }
            assertNotNull(f.settings.assessmentRevertSnapshotForProfile(1).first())
            f.active.value = 2
            assertTrue(withTimeout(5_000) { DeviceAssessmentApplier.revert(f.settings, profileId = 1) })
            assertNull(f.settings.assessmentRevertSnapshotForProfile(2).first())
        }
    }
    @Test fun `cancelled apply retains original rollback and releases mutation`() = runBlocking {
        fixture { f ->
            val entered = CompletableDeferred<Unit>()
            f.first.afterUpdate = { prefs ->
                if (prefs[stringPreferencesKey("assessment_revert_snapshot")] != null) {
                    f.first.afterUpdate = null; entered.complete(Unit); awaitCancellation()
                }
            }
            val job = launch { DeviceAssessmentApplier.apply(f.settings, AssessmentApplyPlan(enableHttp2 = true), null, 2_048) }
            withTimeout(5_000) { entered.await() }; job.cancelAndJoin(); f.active.value = 2
            assertNotNull(f.settings.assessmentRevertSnapshotForProfile(1).first())
            assertTrue(withTimeout(5_000) { DeviceAssessmentApplier.revert(f.settings, profileId = 1) })
            assertFalse(f.settings.playerSettingsForProfile(2).first().enableHttp2)
        }
    }
    @Test fun `concurrent assessments serialize while retaining captured profiles`() = runBlocking {
        fixture { f ->
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            f.first.afterUpdate = { prefs ->
                if (prefs[stringPreferencesKey("assessment_revert_snapshot")] != null) {
                    f.first.afterUpdate = null; entered.complete(Unit); release.await()
                }
            }
            val first = async { DeviceAssessmentApplier.apply(f.settings, AssessmentApplyPlan(enableHttp2 = true), null, 2_048) }
            withTimeout(5_000) { entered.await() }; f.active.value = 2
            val second = async(start = CoroutineStart.UNDISPATCHED) { DeviceAssessmentApplier.apply(f.settings, AssessmentApplyPlan(enableHttp2 = true), null, 2_048) }
            assertNull(f.settings.assessmentRevertSnapshotForProfile(2).first())
            f.active.value = 1; release.complete(Unit)
            withTimeout(5_000) { first.await(); second.await() }
            assertTrue(f.settings.playerSettingsForProfile(1).first().enableHttp2)
            assertTrue(f.settings.playerSettingsForProfile(2).first().enableHttp2)
            assertNotNull(f.settings.assessmentRevertSnapshotForProfile(2).first())
        }
    }
    @Test fun `ordinary setters still use active profile and explicit setters stay pinned`() = runBlocking {
        fixture { f ->
            f.active.value = 2; f.settings.setEnableHttp2(true)
            f.settings.setBufferBackBufferDurationMs(90_000, profileId = 1)
            assertFalse(f.settings.playerSettingsForProfile(1).first().enableHttp2)
            assertTrue(f.settings.playerSettingsForProfile(2).first().enableHttp2)
            assertEquals(90_000, f.settings.playerSettingsForProfile(1).first().bufferSettings.backBufferDurationMs)
            assertNotEquals(90_000, f.settings.playerSettingsForProfile(2).first().bufferSettings.backBufferDurationMs)
        }
    }
    @Test fun `cancelled waiter cannot begin a second profile mutation`() = runBlocking {
        fixture { f ->
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            f.first.afterUpdate = { prefs ->
                if (prefs[stringPreferencesKey("assessment_revert_snapshot")] != null) {
                    f.first.afterUpdate = null; entered.complete(Unit); release.await()
                }
            }
            val first = async { DeviceAssessmentApplier.apply(f.settings, AssessmentApplyPlan(enableHttp2 = true), null, 2_048) }
            withTimeout(5_000) { entered.await() }; f.active.value = 2
            val second = launch(start = CoroutineStart.UNDISPATCHED) { DeviceAssessmentApplier.apply(f.settings, AssessmentApplyPlan(enableHttp2 = true), null, 2_048) }
            second.cancelAndJoin(); release.complete(Unit); withTimeout(5_000) { first.await() }
            assertNull(f.settings.assessmentRevertSnapshotForProfile(2).first())
            assertFalse(f.settings.playerSettingsForProfile(2).first().enableHttp2)
        }
    }

    @Test fun `network guard rechecks after waiting and precedes rollback and settings writes`() = runBlocking {
        fixture { f ->
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            f.first.afterUpdate = { prefs ->
                if (prefs[stringPreferencesKey("assessment_revert_snapshot")] != null) {
                    f.first.afterUpdate = null; entered.complete(Unit); release.await()
                }
            }
            val first = async { DeviceAssessmentApplier.apply(f.settings, AssessmentApplyPlan(enableHttp2 = true), null, 2_048, 1) }
            withTimeout(5_000) { entered.await() }
            val untouched = f.second.data.value; var current = true
            val second = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { DeviceAssessmentApplier.applyIfCurrent(f.settings, AssessmentApplyPlan(enableHttp2 = true), null, 2_048, 2) { current } }
            }
            current = false; release.complete(Unit)
            withTimeout(5_000) { first.await() }
            val failure = withTimeout(5_000) { second.await() }.exceptionOrNull()
            assertTrue(failure is DeviceAssessmentApplier.StaleAssessment)
            assertEquals(untouched, f.second.data.value)
        }
    }


    @Test fun `validated Apply rejects changed saved settings before snapshot`() = runBlocking {
        fixture { f ->
            val source = com.nuvio.tv.core.player.LastPlaybackDiagnostics.EMPTY
            val inputs = AssessmentInputs.capture(f.settings.playerSettingsForProfile(1).first(), source)
            f.settings.setBufferBackBufferDurationMs(19_000, profileId = 1)
            val before = f.first.data.value
            val failure = runCatching { DeviceAssessmentApplier.applyValidated(f.settings, AssessmentApplyPlan(enableHttp2 = true), null, 2_048, 1, inputs) { true } }.exceptionOrNull()
            assertTrue(failure is DeviceAssessmentApplier.StaleAssessment); assertEquals(before, f.first.data.value)
        }
    }
    @Test fun `validated Apply rejects changed saved source before snapshot`() = runBlocking {
        fixture { f ->
            val source = com.nuvio.tv.core.player.LastPlaybackDiagnostics.EMPTY
            val inputs = AssessmentInputs.capture(f.settings.playerSettingsForProfile(1).first(), source)
            f.settings.setLastPlaybackDiagnostics(source.copy(timestampMs = 100, streamUrl = "https://example.invalid/source"))
            val before = f.first.data.value
            val failure = runCatching { DeviceAssessmentApplier.applyValidated(f.settings, AssessmentApplyPlan(enableHttp2 = true), null, 2_048, 1, inputs) { true } }.exceptionOrNull()
            assertTrue(failure is DeviceAssessmentApplier.StaleAssessment); assertEquals(before, f.first.data.value)
        }
    }
    @Test fun `queued validated Apply reads current store even when UI guard stays true`() = runBlocking {
        fixture { f ->
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            f.first.afterUpdate = { prefs -> if (prefs[stringPreferencesKey("assessment_revert_snapshot")] != null) {
                f.first.afterUpdate = null; entered.complete(Unit); release.await()
            } }
            val inputs = AssessmentInputs.capture(f.settings.playerSettingsForProfile(1).first(), com.nuvio.tv.core.player.LastPlaybackDiagnostics.EMPTY)
            val first = async { DeviceAssessmentApplier.apply(f.settings, AssessmentApplyPlan(enableHttp2 = true), null, 2_048, 1) }
            withTimeout(5_000) { entered.await() }
            val second = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { DeviceAssessmentApplier.applyValidated(f.settings, AssessmentApplyPlan(enableHttp2 = false), null, 2_048, 1, inputs) { true } }
            }
            release.complete(Unit); withTimeout(5_000) { first.await() }
            val failure = withTimeout(5_000) { second.await() }.exceptionOrNull()
            assertTrue(failure is DeviceAssessmentApplier.StaleAssessment)
            assertTrue(f.settings.playerSettingsForProfile(1).first().enableHttp2)
        }
    }
    @Test fun `fresh validated Apply writes and retains rollback`() = runBlocking {
        fixture { f ->
            val before = f.settings.playerSettingsForProfile(1).first()
            val inputs = AssessmentInputs.capture(before, com.nuvio.tv.core.player.LastPlaybackDiagnostics.EMPTY)
            assertEquals(1, DeviceAssessmentApplier.applyValidated(f.settings, AssessmentApplyPlan(enableHttp2 = true), null, 2_048, 1, inputs) { true }.writtenCount)
            assertTrue(f.settings.playerSettingsForProfile(1).first().enableHttp2)
            assertNotNull(f.settings.assessmentRevertSnapshotForProfile(1).first())
            assertTrue(DeviceAssessmentApplier.revert(f.settings, 1)); assertFalse(f.settings.playerSettingsForProfile(1).first().enableHttp2)
        }
    }
    @Test fun `guard rechecks after suspending settings read before snapshot`() = runBlocking {
        val store = mockk<PlayerSettingsDataStore>(); var current = true
        val settings = com.nuvio.tv.data.local.PlayerSettings()
        every { store.playerSettingsForProfile(1) } returns flow { current = false; emit(settings) }
        val failure = runCatching { DeviceAssessmentApplier.applyIfCurrent(store, AssessmentApplyPlan(enableHttp2 = true), null, 2_048, 1) { current } }.exceptionOrNull()
        assertTrue(failure is DeviceAssessmentApplier.StaleAssessment)
        io.mockk.coVerify(exactly = 0) { store.setAssessmentRevertSnapshot(any(), any()) }
    }


    @Test fun `profile input values use same saved emission and original decoder`() = runBlocking {
        fixture { f ->
            val source = com.nuvio.tv.core.player.LastPlaybackDiagnostics(timestampMs = 900, streamUrl = "https://example.invalid/renewed")
            f.settings.setLastPlaybackDiagnostics(source); f.settings.setBufferBackBufferDurationMs(19_000)
            val (settings, diagnostics) = f.settings.assessmentInputValuesForProfile(1).first()
            assertEquals(f.settings.playerSettingsForProfile(1).first(), settings)
            assertEquals(source, diagnostics); assertEquals(19_000, settings.bufferSettings.backBufferDurationMs)
            assertEquals(com.nuvio.tv.core.player.LastPlaybackDiagnostics.EMPTY, f.settings.assessmentInputValuesForProfile(2).first().second)
        }
    }

}
