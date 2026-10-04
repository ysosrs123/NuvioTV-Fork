package com.nuvio.tv.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.nuvio.tv.core.profile.ProfileManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test

class PlayerControlLayoutStoreTest {
    private val key = stringPreferencesKey("player_control_layout")
    private class MemoryStore : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        val mutex = Mutex()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            mutex.withLock { transform(data.value).also { data.value = it } }
    }
    private class Fixture(val settings: PlayerSettingsDataStore, val active: MutableStateFlow<Int>, val first: MemoryStore, val second: MemoryStore)
    private suspend fun fixture(block: suspend (Fixture) -> Unit) {
        val first = MemoryStore(); val second = MemoryStore(); val active = MutableStateFlow(1)
        val manager = mockk<ProfileManager>(); every { manager.activeProfileId } returns active
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(1, "player_settings") } returns first
        every { factory.get(2, "player_settings") } returns second
        val settings = PlayerSettingsDataStore(factory, manager, mockk(relaxed = true))
        try {
            withTimeout(5_000) {
                val done = booleanPreferencesKey("migration_target_buffer_size_reduced_done")
                first.data.first { it[done] == true }; active.value = 2; second.data.first { it[done] == true }
                active.value = 1; settings.playerSettings.first()
            }
            block(Fixture(settings, active, first, second))
        } finally {
            val field = PlayerSettingsDataStore::class.java.getDeclaredField("ioScope").apply { isAccessible = true }
            (field.get(settings) as CoroutineScope).cancel()
        }
    }
    private fun custom() = PlayerControlLayout.default().withGroup(PlayerControlAction.AUDIO, PlayerControlGroup.CENTRE).withVisibility(PlayerControlAction.PLAY_PAUSE, false)
    @Test fun `absent layout does not change old defaults`() = runBlocking {
        fixture { f ->
            val snapshot = f.settings.controlLayoutSnapshot.first()
            assertEquals(1, snapshot.profileId); assertNull(snapshot.layout); assertNull(snapshot.serialized)
            assertNull(f.settings.playerSettings.first().controlLayout)
        }
    }
    @Test fun `saved layout roundtrips in actual profile settings and separate profiles`() = runBlocking {
        fixture { f ->
            assertTrue(f.settings.saveControlLayout(f.settings.controlLayoutSnapshot.first(), custom()))
            assertEquals(custom(), f.settings.playerSettingsForProfile(1).first().controlLayout)
            f.active.value = 2
            val snapshot = f.settings.controlLayoutSnapshot.first(); assertEquals(2, snapshot.profileId); assertNull(snapshot.layout)
            assertTrue(f.settings.saveControlLayout(snapshot, PlayerControlLayout.default()))
            f.active.value = 1; assertEquals(custom(), f.settings.controlLayoutSnapshot.first().layout)
            assertEquals(PlayerControlLayout.default(), f.settings.playerSettingsForProfile(2).first().controlLayout)
        }
    }
    @Test fun `stale revision cannot overwrite a saved edit`() = runBlocking {
        fixture { f ->
            val snapshot = f.settings.controlLayoutSnapshot.first()
            assertTrue(f.settings.saveControlLayout(snapshot, custom()))
            val before = f.first.data.value
            assertFalse(f.settings.saveControlLayout(snapshot, PlayerControlLayout.default()))
            assertFalse(f.settings.saveControlLayout(snapshot, null)); assertEquals(before, f.first.data.value)
        }
    }
    @Test fun `profile switch before save rejects original editor and does not write another profile`() = runBlocking {
        fixture { f ->
            val snapshot = f.settings.controlLayoutSnapshot.first(); val before = f.first.data.value
            f.active.value = 2; val other = f.second.data.value
            assertFalse(f.settings.saveControlLayout(snapshot, custom()))
            assertEquals(before, f.first.data.value); assertEquals(other, f.second.data.value)
        }
    }
    @Test fun `profile switch while waiting for datastore rejects queued save`() = runBlocking {
        fixture { f ->
            val snapshot = f.settings.controlLayoutSnapshot.first(); val before = f.first.data.value
            f.first.mutex.lock()
            val pending = async(start = CoroutineStart.UNDISPATCHED) { f.settings.saveControlLayout(snapshot, custom()) }
            f.active.value = 2; val other = f.second.data.value; f.first.mutex.unlock()
            assertFalse(withTimeout(5_000) { pending.await() })
            assertEquals(before, f.first.data.value); assertEquals(other, f.second.data.value)
        }
    }
    @Test fun `reset removes only layout and keeps all playback and rollback preferences`() = runBlocking {
        fixture { f ->
            f.settings.setBufferBackBufferDurationMs(19_000); f.settings.setAssessmentRevertSnapshot("saved rollback")
            assertTrue(f.settings.saveControlLayout(f.settings.controlLayoutSnapshot.first(), custom()))
            val before = f.first.data.value.asMap().filterKeys { it != key }
            assertTrue(f.settings.saveControlLayout(f.settings.controlLayoutSnapshot.first(), null))
            assertNull(f.settings.playerSettings.first().controlLayout)
            assertEquals(before, f.first.data.value.asMap()); assertNull(f.second.data.value[key])
        }
    }
    @Test fun `unknown or malformed restored values remain intact during read and unrelated writes`() = runBlocking {
        fixture { f ->
            for (raw in listOf("v2|audio,left,1", "broken", "v1|audio,wrong,1")) {
                f.first.edit { it[key] = raw }
                assertNull(f.settings.playerSettings.first().controlLayout)
                val snapshot = f.settings.controlLayoutSnapshot.first(); assertEquals(raw, snapshot.serialized)
                f.settings.setBufferBackBufferDurationMs(7_000)
                assertEquals(raw, f.first.data.value[key])
                assertTrue(f.settings.saveControlLayout(snapshot, null)); assertNull(f.first.data.value[key])
            }
        }
    }
    @Test fun `two editors from same revision cannot lose the first successful layout`() = runBlocking {
        fixture { f ->
            val snapshot = f.settings.controlLayoutSnapshot.first()
            val start = CompletableDeferred<Unit>()
            val results = listOf(custom(), PlayerControlLayout.default()).map { layout -> async { start.await(); layout to f.settings.saveControlLayout(snapshot, layout) } }
            start.complete(Unit)
            val finished = withTimeout(5_000) { results.awaitAll() }
            assertEquals(1, finished.count { it.second })
            assertEquals(finished.single { it.second }.first, f.settings.playerSettings.first().controlLayout)
        }
    }
    @Test fun `actual saved layouts and reset preserve playback observer state`() = runBlocking {
        fixture { f ->
            val applied = mutableListOf<PlayerRuntimeSettingsSnapshot>()
            val raw = kotlinx.coroutines.channels.Channel<PlayerRuntimeSettingsSnapshot>(kotlinx.coroutines.channels.Channel.UNLIMITED)
            val observer = launch(start = CoroutineStart.UNDISPATCHED) {
                f.settings.runtimePlayerSettings.onEach { raw.trySend(it) }.playbackSettingsChanges().collect { applied += it }
            }
            try {
                withTimeout(5_000) { raw.receive() }; yield()
                val count = applied.size
                assertTrue(f.settings.saveControlLayout(f.settings.controlLayoutSnapshot.first(), custom()))
                val saved = withTimeout(5_000) { raw.receive() }; yield()
                assertEquals(custom(), saved.settings.controlLayout); assertEquals(count, applied.size)
                assertTrue(f.settings.saveControlLayout(f.settings.controlLayoutSnapshot.first(), null))
                assertNull(withTimeout(5_000) { raw.receive() }.settings.controlLayout); yield()
                assertEquals(count, applied.size)
                f.settings.setBufferBackBufferDurationMs(17_000)
                withTimeout(5_000) { raw.receive() }; yield()
                assertEquals(count + 1, applied.size)
            } finally { observer.cancelAndJoin(); raw.close() }
        }
    }
    @Test fun `runtime settings carry their actual store profile through switches`() = runBlocking {
        fixture { f ->
            assertEquals(1, f.settings.runtimePlayerSettings.first().profileId)
            assertTrue(f.settings.saveControlLayout(f.settings.controlLayoutSnapshot.first(), custom()))
            f.active.value = 2
            val second = f.settings.runtimePlayerSettings.first()
            assertEquals(2, second.profileId); assertNull(second.settings.controlLayout)
            f.active.value = 1
            val first = f.settings.runtimePlayerSettings.first()
            assertEquals(1, first.profileId); assertEquals(custom(), first.settings.controlLayout)
        }
    }

    @Test fun `editor preview Cancel never writes real profile preferences`() = runBlocking {
        fixture { f ->
            val snapshot = f.settings.controlLayoutSnapshot.first(); val before = f.first.data.value
            val draft = com.nuvio.tv.ui.screens.settings.PlayerControlLayoutDraft(snapshot)
                .visibility(PlayerControlAction.PLAY_PAUSE, false).group(PlayerControlAction.AUDIO, PlayerControlGroup.CENTRE)
            assertNotNull(draft.layout); assertEquals(before, f.first.data.value)
            assertNull(f.settings.playerSettings.first().controlLayout)
        }
    }
    @Test fun `editor Save retires captured revision and Reset preserves unrelated settings`() = runBlocking {
        fixture { f ->
            f.settings.setBufferBackBufferDurationMs(13_000)
            val draft = com.nuvio.tv.ui.screens.settings.PlayerControlLayoutDraft(f.settings.controlLayoutSnapshot.first())
                .visibility(PlayerControlAction.PLAY_PAUSE, false)
            assertTrue(f.settings.saveControlLayout(draft.snapshot, draft.layout))
            assertEquals(draft.layout, f.settings.playerSettings.first().controlLayout)
            assertFalse(f.settings.saveControlLayout(draft.snapshot, draft.reset().layout))
            val fresh = com.nuvio.tv.ui.screens.settings.PlayerControlLayoutDraft(f.settings.controlLayoutSnapshot.first()).reset()
            val before = f.first.data.value.asMap().filterKeys { it != key }
            assertTrue(f.settings.saveControlLayout(fresh.snapshot, fresh.layout))
            assertEquals(before, f.first.data.value.asMap()); assertNull(f.settings.playerSettings.first().controlLayout)
        }
    }
    @Test fun `profile change rejects editor Save with original draft intact`() = runBlocking {
        fixture { f ->
            val draft = com.nuvio.tv.ui.screens.settings.PlayerControlLayoutDraft(f.settings.controlLayoutSnapshot.first())
                .visibility(PlayerControlAction.PLAY_PAUSE, false)
            val first = f.first.data.value; f.active.value = 2; val second = f.second.data.value
            assertFalse(draft.belongsTo(2)); assertFalse(f.settings.saveControlLayout(draft.snapshot, draft.layout))
            val rejected = draft.reject(); assertTrue(rejected.rejected); assertEquals(draft.layout, rejected.layout)
            assertEquals(first, f.first.data.value); assertEquals(second, f.second.data.value)
        }
    }


    @Test fun `legacy style reads do not rewrite saved preference bytes`() = runBlocking {
        fixture { f ->
            val legacy = "v1|audio,centre,0;play_pause,right,1"
            f.first.updateData { it.toMutablePreferences().apply { this[key] = legacy } }
            val snapshot = f.settings.controlLayoutSnapshot.first()
            assertEquals(legacy,snapshot.serialized);assertEquals(PlayerControlButtonStyle.LABELLED,snapshot.layout!!.style)
            assertEquals(legacy,f.first.data.value[key])
            assertTrue(f.settings.saveControlLayout(snapshot,snapshot.layout.withStyle(PlayerControlButtonStyle.PREVIOUS)))
            assertTrue(f.first.data.value[key]!!.startsWith("v2|previous|"))
            assertFalse(f.settings.saveControlLayout(snapshot,null))
        }
    }
    @Test fun `actual profile style saves remain isolated and Reset removes only that profiles layout`() = runBlocking {
        fixture { f ->
            val labelled=custom().withStyle(PlayerControlButtonStyle.LABELLED)
            assertTrue(f.settings.saveControlLayout(f.settings.controlLayoutSnapshot.first(),labelled))
            f.active.value=2
            assertNull(f.settings.controlLayoutSnapshot.first().layout)
            assertTrue(f.settings.saveControlLayout(f.settings.controlLayoutSnapshot.first(),custom()))
            f.active.value=1
            val snapshot=f.settings.controlLayoutSnapshot.first();assertEquals(labelled,snapshot.layout)
            assertTrue(f.settings.saveControlLayout(snapshot,null))
            assertNull(f.first.data.value[key]);assertEquals(custom(),f.settings.playerSettingsForProfile(2).first().controlLayout)
        }
    }
    @Test fun `style only save leaves playback observer unchanged but emits the raw new layout`() = runBlocking {
        fixture { f ->
            assertTrue(f.settings.saveControlLayout(f.settings.controlLayoutSnapshot.first(),custom()))
            val applied=mutableListOf<PlayerRuntimeSettingsSnapshot>()
            val raw=kotlinx.coroutines.channels.Channel<PlayerRuntimeSettingsSnapshot>(kotlinx.coroutines.channels.Channel.UNLIMITED)
            val observer=launch(start=CoroutineStart.UNDISPATCHED) {
                f.settings.runtimePlayerSettings.onEach { raw.trySend(it) }.playbackSettingsChanges().collect { applied+=it }
            }
            try {
                withTimeout(5_000) { raw.receive() };yield();val count=applied.size
                val labelled=custom().withStyle(PlayerControlButtonStyle.LABELLED)
                assertTrue(f.settings.saveControlLayout(f.settings.controlLayoutSnapshot.first(),labelled))
                assertEquals(labelled,withTimeout(5_000) { raw.receive() }.settings.controlLayout);yield()
                assertEquals(count,applied.size)
            } finally { observer.cancelAndJoin();raw.close() }
        }
    }
    @Test fun `Cancel and stale Save cannot commit a provisional style to either profile`() = runBlocking {
        fixture { f ->
            val snapshot=f.settings.controlLayoutSnapshot.first()
            val draft=com.nuvio.tv.ui.screens.settings.PlayerControlLayoutDraft(snapshot).style(PlayerControlButtonStyle.LABELLED)
            assertNull(f.first.data.value[key]);assertNull(f.second.data.value[key])
            f.active.value=2
            assertFalse(f.settings.saveControlLayout(draft.snapshot,draft.layout))
            assertNull(f.first.data.value[key]);assertNull(f.second.data.value[key])
        }
    }

}
