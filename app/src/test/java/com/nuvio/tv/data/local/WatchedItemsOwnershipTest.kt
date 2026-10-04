package com.nuvio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.google.gson.Gson
import com.nuvio.tv.TestPreferencesStore
import com.nuvio.tv.WindowsFileLockRetryRule
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.WatchedItem
import io.mockk.every
import io.mockk.mockk
import io.mockk.coEvery
import io.mockk.spyk
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.sync.WatchedItemsSyncService
import com.nuvio.tv.core.tracking.TrackingProgressProviderRegistry
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.nio.file.Files
import kotlin.coroutines.coroutineContext

class WatchedItemsOwnershipTest {
    @get:Rule val fileLockRetry = WindowsFileLockRetryRule()
    private fun item(id: String, timestamp: Long = 1) = WatchedItem(id, "movie", "fixture", watchedAt = timestamp)
    private val key = stringSetPreferencesKey("watched_items")
    private fun preferences(factory: ProfileDataStoreFactory): WatchedItemsPreferences {
        val manager = mockk<ProfileManager>()
        every { manager.activeProfileId } returns MutableStateFlow(1)
        return WatchedItemsPreferences(factory, manager)
    }
    private fun actual(block: suspend (ProfileDataStoreFactory, WatchedItemsPreferences) -> Unit) = runBlocking {
        withTimeout(30_000) {
            val dir = Files.createTempDirectory("watched-ownership-").toFile()
            val context = mockk<Context>()
            every { context.filesDir } returns dir
            every { context.applicationContext } returns context
            val factory = ProfileDataStoreFactory(context)
            try { block(factory, preferences(factory)) }
            finally { factory.clearProfileScopedData(); dir.deleteRecursively() }
        }
    }

    @Test fun `actual DataStore concurrent collectors mark and metadata share one incremental decode`() = actual { _, prefs ->
        prefs.markAsWatched(item("first"), 1)
        val outputs = List(6) { Channel<List<WatchedItem>>(Channel.UNLIMITED) }
        val jobs = outputs.map { output -> CoroutineScope(coroutineContext).launch { prefs.observeAllItems(1).collect { output.send(it) } } }
        try {
            outputs.forEach { assertEquals(listOf(item("first")), it.receive()) }
            val baseline = prefs.decodeStats(1)
            prefs.markAsWatched(item("next"), 1)
            outputs.forEach { assertEquals(listOf(item("first"), item("next")), it.receive()) }
            assertEquals(baseline.first + 1 to baseline.second + 1, prefs.decodeStats(1))
            prefs.advanceLastSuccessfulPushMs(55, 1)
            prefs.setDeltaState(42, profileId = 1)
            assertEquals(baseline.first + 1 to baseline.second + 1, prefs.decodeStats(1))
            prefs.markAsWatched(item("next"), 1)
            assertEquals(baseline.first + 1 to baseline.second + 1, prefs.decodeStats(1))
            delay(100)
            outputs.forEach { assertTrue(it.tryReceive().isFailure) }
        } finally { jobs.forEach { it.cancelAndJoin() } }
    }

    @Test fun `actual DataStore batch unmark timestamp and malformed preserve order and decode deltas`() = actual { factory, prefs ->
        val store = factory.get(1, "watched_items_preferences")
        store.edit { it[key] = linkedSetOf(Gson().toJson(item("a")), "{broken", "null") }
        assertEquals(listOf(item("a")), prefs.getAllItems(1))
        assertEquals(3L, prefs.decodeStats(1).second)
        prefs.markAsWatchedBatch(listOf(item("b"), item("c")), 1)
        assertEquals(5L, prefs.decodeStats(1).second)
        prefs.markAsWatched(item("a", 9), 1)
        assertEquals(listOf(item("b"), item("c"), item("a", 9)), prefs.getAllItems(1))
        assertEquals(6L, prefs.decodeStats(1).second)
        prefs.unmarkAsWatched("b", profileId = 1)
        assertEquals(listOf(item("c"), item("a", 9)), prefs.getAllItems(1))
        assertEquals(6L, prefs.decodeStats(1).second)
        assertTrue("{broken" in store.data.first()[key]!!)
    }

    @Test fun `cold load concurrent one shots and unsubscribed restart read persisted history`() = actual { factory, prefs ->
        val persisted = listOf(item("cold"))
        factory.get(1, "watched_items_preferences").edit { it[key] = persisted.mapTo(linkedSetOf()) { value -> Gson().toJson(value) } }
        val results = coroutineScope { List(6) { async { prefs.getAllItems(1) } }.awaitAll() }
        results.forEach { assertEquals(persisted, it) }
        assertEquals(1L, prefs.decodeStats(1).second)
        assertEquals(persisted, prefs.observeAllItems(1).first())
        prefs.markAsWatched(item("later"), 1)
        assertEquals(listOf(item("cold"), item("later")), prefs.observeAllItems(1).first())
    }

    @Test fun `actual DataStore 5431 snapshot is shared and ordinary mark only adds one parse`() = actual { factory, prefs ->
        val gson = Gson()
        val history = (0 until 5431).map { item("large$it", it.toLong()) }
        factory.get(1, "watched_items_preferences").edit { it[key] = history.mapTo(linkedSetOf()) { value -> gson.toJson(value) } }
        coroutineScope {
            List(6) { async { assertEquals(history, prefs.observeAllItems(1).first()) } }.awaitAll()
        }
        assertEquals(5431L, prefs.decodeStats(1).second)
        prefs.markAsWatched(item("new"), 1)
        assertEquals(5432L, prefs.decodeStats(1).second)
        assertEquals(5432, prefs.getAllItems(1).size)
    }

    @Test fun `escaped composite identifiers are replaced and removed by exact decoded key`() = actual { _, prefs ->
        val id = "quoted\"|_"
        prefs.markAsWatched(item(id), 1)
        prefs.markAsWatched(item(id, 9), 1)
        assertEquals(listOf(item(id, 9)), prefs.getAllItems(1))
        prefs.unmarkAsWatched(id, profileId = 1)
        assertTrue(prefs.getAllItems(1).isEmpty())
    }

    @Test fun `deliberately delayed shared upstream cannot stale a fresh read or new subscriber`() = runBlocking {
        withTimeout(15_000) {
            val backing = TestPreferencesStore()
            val release = CompletableDeferred<Unit>()
            val observing = CompletableDeferred<Unit>()
            val delayed = object : DataStore<Preferences> {
                override val data: Flow<Preferences> = flow {
                    emit(backing.value)
                    observing.complete(Unit)
                    release.await()
                    emitAll(backing.data)
                }
                override suspend fun updateData(transform: suspend (Preferences) -> Preferences) = backing.updateData(transform)
            }
            val lifetime = ProfileStoreLifetime(delayed)
            val factory = mockk<ProfileDataStoreFactory>()
            every { factory.get(any(), any()) } returns lifetime
            val prefs = preferences(factory)
            prefs.markAsWatched(item("before"), 1)
            val output = Channel<List<WatchedItem>>(Channel.UNLIMITED)
            val collector = launch { prefs.observeAllItems(1).collect { output.send(it) } }
            try {
                assertEquals(listOf(item("before")), output.receive())
                observing.await()
                prefs.markAsWatched(item("after"), 1)
                val expected = listOf(item("before"), item("after"))
                assertEquals(expected, prefs.getAllItems(1))
                assertEquals(expected, prefs.observeAllItems(1).first())
                release.complete(Unit)
                assertEquals(expected, output.receive())
            } finally { collector.cancelAndJoin(); lifetime.retire() }
        }
    }

    @Test fun `delete recreate and global reset retire held flows and reject old store edits`() = actual { factory, prefs ->
        prefs.markAsWatched(item("deleted"), 2)
        val held = prefs.observeAllItems(2)
        val oldStore = factory.get(2, "watched_items_preferences")
        assertEquals(listOf(item("deleted")), held.first())
        factory.clearProfile(2)
        assertEquals(emptyList<WatchedItem>(), held.first())
        assertThrows(CancellationException::class.java) { factory.get(2, "watched_items_preferences") }
        factory.markProfileCreated(2)
        prefs.markAsWatched(item("recreated"), 2)
        assertEquals(listOf(item("recreated")), prefs.getAllItems(2))
        assertEquals(emptyList<WatchedItem>(), held.first())
        try { oldStore.edit { it[key] = setOf(Gson().toJson(item("late"))) }; fail("retired edit admitted") }
        catch (_: CancellationException) { }
        val resetHeld = prefs.observeAllItems(2)
        factory.clearProfileScopedData()
        assertEquals(emptyList<WatchedItem>(), resetHeld.first())
        assertEquals(emptyList<WatchedItem>(), prefs.getAllItems(2))
    }

    @Test fun `clearAll drops cached data and delta state but preserves push time and subsequent marks`() = actual { _, prefs ->
        prefs.markAsWatched(item("clear"), 1)
        prefs.advanceLastSuccessfulPushMs(50, 1)
        prefs.setDeltaState(8, profileId = 1)
        val held = prefs.observeAllItems(1)
        assertEquals(listOf(item("clear")), held.first())
        prefs.clearAll(1)
        assertEquals(emptyList<WatchedItem>(), prefs.getAllItems(1))
        assertEquals(emptyList<WatchedItem>(), held.first())
        assertEquals(50L, prefs.getLastSuccessfulPushMs(1))
        assertEquals(0L, prefs.getDeltaCursor(1)); assertFalse(prefs.isDeltaInitialized(1))
        prefs.markAsWatched(item("new"), 1)
        assertEquals(listOf(item("new")), held.first())
    }

    @Test fun `clear history invalidates suspended snapshot delta and push metadata commits`() = actual { _, prefs ->
        prefs.markAsWatched(item("old"), 1)
        val generation = prefs.captureGeneration(1)
        prefs.clearAll(1)
        prefs.markAsWatched(item("current"), 1)
        for (commit in listOf<suspend () -> Unit>(
            { prefs.replaceWithRemoteItems(listOf(item("late")), profileId = 1, generation = generation); Unit },
            { prefs.applyRemoteChanges(listOf(item("late")), emptyList(), profileId = 1, generation = generation) },
            { prefs.setDeltaState(99, profileId = 1, generation = generation) },
            { prefs.advanceLastSuccessfulPushMs(99, 1, generation) }
        )) {
            try { commit(); fail("retired sync generation admitted") }
            catch (_: CancellationException) { }
        }
        assertEquals(listOf(item("current")), prefs.getAllItems(1))
        assertEquals(0L, prefs.getDeltaCursor(1))
        assertEquals(0L, prefs.getLastSuccessfulPushMs(1))
    }

    @Test fun `recreated profile rejects captured remote generation without changing new data`() = actual { factory, prefs ->
        prefs.markAsWatched(item("old"), 2)
        val generation = prefs.captureGeneration(2)
        factory.clearProfile(2)
        factory.markProfileCreated(2)
        prefs.markAsWatched(item("new"), 2)
        try { prefs.mergeRemoteItems(listOf(item("late")), 2, generation); fail("old remote generation admitted") }
        catch (_: CancellationException) { }
        assertEquals(listOf(item("new")), prefs.getAllItems(2))
    }

    @Test fun `actual snapshot service rejects remote response delivered after clear history`() = actual { _, prefs ->
        val received = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val auth = mockk<AuthManager>(relaxed = true)
        coEvery { auth.refreshSessionIfJwtExpired(any()) } returns false
        val postgrest = mockk<Postgrest>()
        coEvery { postgrest.rpc(any(), any<JsonObject>()) } throws IllegalStateException("fixture cursor unavailable")
        val settings = mockk<TraktSettingsDataStore>()
        coEvery { settings.getWatchProgressSource(1) } returns WatchProgressSource.NUVIO_SYNC
        val manager = mockk<ProfileManager>()
        every { manager.activeProfileId } returns MutableStateFlow(1)
        val service = spyk(WatchedItemsSyncService(auth, postgrest, prefs, TrackingProgressProviderRegistry(emptySet()), settings, manager, mockk(relaxed = true), mockk(relaxed = true)))
        coEvery { service.pullFromRemote(1) } coAnswers {
            received.complete(Unit)
            release.await()
            Result.success(listOf(item("late remote")))
        }
        prefs.markAsWatched(item("old"), 1)
        val sync = CoroutineScope(coroutineContext).async { service.syncSnapshotFromRemote(1) }
        received.await()
        prefs.clearAll(1)
        prefs.markAsWatched(item("current"), 1)
        release.complete(Unit)
        assertTrue(sync.await().isFailure)
        assertEquals(listOf(item("current")), prefs.getAllItems(1))
        assertEquals(0L, prefs.getDeltaCursor(1))
    }

    @Test fun `active profile collector rebinds after global reset with unchanged profile number`() = actual { factory, prefs ->
        prefs.markAsWatched(item("old"), 1)
        val output = Channel<List<WatchedItem>>(Channel.UNLIMITED)
        val collector = CoroutineScope(coroutineContext).launch { prefs.allItems.collect { output.send(it) } }
        try {
            assertEquals(listOf(item("old")), output.receive())
            factory.clearProfileScopedData()
            assertEquals(emptyList<WatchedItem>(), output.receive())
            prefs.markAsWatched(item("new"), 1)
            var latest = output.receive()
            while (latest.isEmpty()) latest = output.receive()
            assertEquals(listOf(item("new")), latest)
        } finally { collector.cancelAndJoin() }
    }

    @Test fun `retirement waits for admitted edit and forbids every later edit`() = runBlocking {
        val admitted = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val lifetime = ProfileStoreLifetime(TestPreferencesStore())
        val edit = async { lifetime.edit { admitted.complete(Unit); finish.await(); it[key] = setOf("fixture") } }
        admitted.await()
        val retire = async { lifetime.retire() }
        yield(); assertFalse(retire.isCompleted)
        finish.complete(Unit); edit.await(); retire.await()
        assertEquals(emptyPreferences(), lifetime.data.first())
        try { lifetime.edit { it[key] = setOf("late") }; fail("late edit admitted") }
        catch (_: CancellationException) { }
    }

    @Test fun `upstream failure reaches observers and later subscription can restart`() = runBlocking {
        val backing = TestPreferencesStore()
        var broken = true
        val source = object : DataStore<Preferences> {
            override val data = flow { if (broken) error("fixture failure") else emitAll(backing.data) }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences) = backing.updateData(transform)
        }
        val lifetime = ProfileStoreLifetime(source)
        val owner = WatchedItemsOwner(lifetime) { }
        try {
            try { withTimeout(5000) { owner.observe().first() }; fail("failure hidden") }
            catch (error: IllegalStateException) { assertEquals("fixture failure", error.message) }
            broken = false
            delay(50)
            assertEquals(emptyList<WatchedItem>(), withTimeout(5000) { owner.observe().first() })
        } finally { lifetime.retire() }
    }

    @Test fun `active upstream cancellation reaches reader rather than orphaning shared cache`() = runBlocking {
        val source = object : DataStore<Preferences> {
            override val data = flow<Preferences> { throw CancellationException("fixture upstream cancelled") }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences) = emptyPreferences()
        }
        val lifetime = ProfileStoreLifetime(source)
        val owner = WatchedItemsOwner(lifetime) { }
        try {
            try { withTimeout(5000) { owner.observe().first() }; fail("upstream cancellation hidden") }
            catch (error: CancellationException) { assertEquals("fixture upstream cancelled", error.message) }
        } finally { lifetime.retire() }
    }
}
