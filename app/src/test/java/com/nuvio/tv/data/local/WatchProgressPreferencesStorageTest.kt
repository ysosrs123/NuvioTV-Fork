package com.nuvio.tv.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.WatchProgress
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchProgressPreferencesStorageTest {
    private val gson = Gson()

    @Test
    fun `empty legacy storage initializes without writing bucket files`() = runTest {
        val harness = harness(emptyMap())

        val result = harness.preferences.getAllRawEntries()

        assertTrue(result.isEmpty())
        assertEquals(0, harness.recent.updateCount)
        assertEquals(0, harness.archive.updateCount)
        assertEquals(1, harness.metadata.updateCount)
    }

    @Test
    fun `first save writes only the recent store`() = runTest {
        val harness = harness(emptyMap())
        harness.preferences.getAllRawEntries()
        harness.resetUpdateCounts()

        harness.preferences.saveProgress(progress("item", lastWatched = 1L))

        assertEquals(setOf("item"), harness.recent.keys())
        assertTrue(harness.archive.keys().isEmpty())
        assertEquals(1, harness.recent.updateCount)
        assertEquals(0, harness.archive.updateCount)
    }

    @Test
    fun `legacy snapshot migrates into recent and archive stores`() = runTest {
        val harness = harness(legacyEntries())

        val result = harness.preferences.getAllRawEntries()

        assertEquals(250, result.size)
        assertEquals(200, harness.recent.keys().size)
        assertEquals(50, harness.archive.keys().size)
        assertNull(harness.metadata.value[watchProgressEntriesKey])
        assertEquals(
            WATCH_PROGRESS_STORAGE_VERSION,
            harness.metadata.value[watchProgressStorageVersionKey]
        )
        assertEquals(1, harness.recent.updateCount)
        assertEquals(1, harness.archive.updateCount)
        assertEquals(1, harness.metadata.updateCount)
    }

    @Test
    fun `migration preserves a fifteen thousand item library`() = runTest {
        val entries = (0 until 15_000).associate { index ->
            "large$index" to progress("large$index", lastWatched = index.toLong())
        }
        val harness = harness(entries)

        val result = harness.preferences.getAllRawEntries()

        assertEquals(15_000, result.size)
        assertEquals(WATCH_PROGRESS_RECENT_LIMIT, harness.recent.keys().size)
        assertEquals(14_800, harness.archive.keys().size)
        assertTrue("large14999" in harness.recent.keys())
        assertTrue("large0" in harness.archive.keys())
        harness.resetUpdateCounts()

        harness.preferences.saveProgress(
            entries.getValue("large14999").copy(position = 5_000L, lastWatched = 20_000L)
        )

        assertEquals(1, harness.recent.updateCount)
        assertEquals(0, harness.archive.updateCount)
    }

    @Test
    fun `migration is idempotent after storage version is recorded`() = runTest {
        val harness = harness(legacyEntries())
        harness.preferences.getAllRawEntries()
        harness.resetUpdateCounts()
        val second = WatchProgressPreferences(harness.factory, harness.profileManager)

        val result = second.getAllRawEntries()

        assertEquals(250, result.size)
        assertEquals(0, harness.recent.updateCount)
        assertEquals(0, harness.archive.updateCount)
        assertEquals(0, harness.metadata.updateCount)
    }

    @Test
    fun `saving an existing recent item writes only the recent store`() = runTest {
        val entries = legacyEntries()
        val harness = harness(entries)
        harness.preferences.getAllRawEntries()
        harness.resetUpdateCounts()

        harness.preferences.saveProgress(
            entries.getValue("item249").copy(position = 5_000L, lastWatched = 1_000L)
        )

        assertEquals(1, harness.recent.updateCount)
        assertEquals(0, harness.archive.updateCount)
        assertEquals(0, harness.metadata.updateCount)
        assertEquals(5_000L, harness.preferences.getAllRawEntries().getValue("item249").position)
    }

    @Test
    fun `saving an archived item promotes it and evicts the oldest recent item`() = runTest {
        val entries = legacyEntries()
        val harness = harness(entries)
        harness.preferences.getAllRawEntries()
        harness.resetUpdateCounts()

        harness.preferences.saveProgress(
            entries.getValue("item0").copy(position = 5_000L, lastWatched = 1_000L)
        )

        val recentKeys = harness.recent.keys()
        val archiveKeys = harness.archive.keys()
        assertEquals(200, recentKeys.size)
        assertEquals(50, archiveKeys.size)
        assertTrue("item0" in recentKeys)
        assertTrue("item50" in archiveKeys)
        assertFalse("item0" in archiveKeys)
        assertEquals(1, harness.recent.updateCount)
        assertEquals(1, harness.archive.updateCount)
    }

    @Test
    fun `removing an archived item does not rewrite the recent store`() = runTest {
        val harness = harness(legacyEntries())
        harness.preferences.getAllRawEntries()
        harness.resetUpdateCounts()

        harness.preferences.removeProgress("item0")

        assertEquals(0, harness.recent.updateCount)
        assertEquals(1, harness.archive.updateCount)
        assertFalse("item0" in harness.preferences.getAllRawEntries())
    }

    @Test
    fun `batch saves preserve the recent limit and merged reads`() = runTest {
        val entries = legacyEntries()
        val harness = harness(entries)
        harness.preferences.getAllRawEntries()
        harness.resetUpdateCounts()
        val updates = (0 until 5).map { index ->
            entries.getValue("item$index").copy(lastWatched = 2_000L + index)
        }

        harness.preferences.saveProgressBatch(updates)

        assertEquals(WATCH_PROGRESS_RECENT_LIMIT, harness.recent.keys().size)
        assertEquals(250, harness.preferences.getAllRawEntries().size)
        updates.forEach { update ->
            assertTrue(update.contentId in harness.recent.keys())
        }
        assertEquals(1, harness.recent.updateCount)
        assertEquals(1, harness.archive.updateCount)
    }

    @Test
    fun `remote merge rebalances new entries across both buckets`() = runTest {
        val harness = harness(legacyEntries())
        harness.preferences.getAllRawEntries()
        harness.resetUpdateCounts()
        val remote = progress("remote", lastWatched = 2_000L)

        harness.preferences.mergeRemoteEntries(
            remoteEntries = mapOf("remote" to remote),
            removeMissingRemoteEntries = false
        )

        val result = harness.preferences.getAllRawEntries()
        assertEquals(251, result.size)
        assertEquals(remote, result["remote"])
        assertTrue("remote" in harness.recent.keys())
        assertEquals(WATCH_PROGRESS_RECENT_LIMIT, harness.recent.keys().size)
        assertEquals(51, harness.archive.keys().size)
        assertEquals(1, harness.recent.updateCount)
        assertEquals(1, harness.archive.updateCount)
    }

    @Test
    fun `remote delta deletes archived entries and promotes new progress`() = runTest {
        val harness = harness(legacyEntries())
        harness.preferences.getAllRawEntries()
        harness.resetUpdateCounts()
        val remote = progress("remote", lastWatched = 2_000L)

        harness.preferences.applyRemoteChanges(
            upserts = mapOf("remote" to remote),
            deletes = listOf("item0")
        )

        val result = harness.preferences.getAllRawEntries()
        assertEquals(250, result.size)
        assertFalse("item0" in result)
        assertEquals(remote, result["remote"])
        assertTrue("remote" in harness.recent.keys())
        assertEquals(WATCH_PROGRESS_RECENT_LIMIT, harness.recent.keys().size)
    }

    @Test
    fun `empty remote replacement clears non-pending local progress`() = runTest {
        val harness = harness(legacyEntries())
        harness.preferences.getAllRawEntries()
        harness.resetUpdateCounts()

        harness.preferences.replaceWithRemoteEntries(emptyMap())

        assertTrue(harness.preferences.getAllRawEntries().isEmpty())
    }

    @Test
    fun `pending progress upsert survives an empty snapshot`() = runTest {
        val harness = harness(mapOf("item" to progress("item", 100L)))
        harness.preferences.getAllRawEntries()

        val preserved = harness.preferences.replaceWithRemoteEntries(
            remoteEntries = emptyMap(),
            pendingUpsertKeys = setOf("item")
        )

        assertTrue(preserved)
        assertEquals(setOf("item"), harness.preferences.getAllRawEntries().keys)
    }

    @Test
    fun `pending progress delete suppresses a snapshot upsert`() = runTest {
        val harness = harness(emptyMap())
        val remote = progress("item", 100L)

        harness.preferences.replaceWithRemoteEntries(
            remoteEntries = mapOf("item" to remote),
            pendingDeleteKeys = setOf("item")
        )

        assertTrue(harness.preferences.getAllRawEntries().isEmpty())
    }

    @Test
    fun `remote delta delete cannot remove a pending progress upsert`() = runTest {
        val local = progress("item", 100L)
        val harness = harness(mapOf("item" to local))
        harness.preferences.getAllRawEntries()

        val preserved = harness.preferences.applyRemoteChanges(
            upserts = emptyMap(),
            deletes = listOf("item"),
            pendingUpsertKeys = setOf("item")
        )

        assertTrue(preserved)
        assertEquals(local, harness.preferences.getAllRawEntries()["item"])
    }

    @Test
    fun `remote delta upsert cannot restore a pending progress delete`() = runTest {
        val harness = harness(emptyMap())

        harness.preferences.applyRemoteChanges(
            upserts = mapOf("item" to progress("item", 100L)),
            deletes = emptyList(),
            pendingDeleteKeys = setOf("item")
        )

        assertTrue(harness.preferences.getAllRawEntries().isEmpty())
    }

    @Test
    fun `explicit progress flow remains bound after active profile changes`() = runTest {
        val harness = multiProfileHarness()
        val first = progress("first", 100L)
        val second = progress("second", 200L)
        harness.preferences.saveProgress(first, profileId = 1)
        harness.preferences.saveProgress(second, profileId = 2)
        val fixedProfileFlow = harness.preferences.getProgress("first", profileId = 1)

        harness.activeProfile.value = 2

        assertEquals(first, fixedProfileFlow.first())
    }

    @Test
    fun `clear preserving non trakt ids filters both buckets`() = runTest {
        val entries = legacyEntries().toMutableMap().apply {
            this["custom:recent"] = progress("custom:recent", lastWatched = 2_000L)
            this["custom:archive"] = progress("custom:archive", lastWatched = -1L)
        }
        val harness = harness(entries)
        harness.preferences.getAllRawEntries()

        harness.preferences.clearAllPreservingNonTraktIds { it.startsWith("custom:") }

        val result = harness.preferences.getAllRawEntries()
        assertEquals(setOf("custom:recent", "custom:archive"), result.keys)
        assertEquals(setOf("custom:recent", "custom:archive"), harness.recent.keys())
        assertTrue(harness.archive.keys().isEmpty())
    }

    @Test
    fun `progress flow merges both stores after migration`() = runTest {
        val harness = harness(legacyEntries())

        val result = harness.preferences.allRawProgress.first()

        assertEquals(250, result.size)
        assertEquals("item249", result.first().contentId)
        assertEquals("item0", result.last().contentId)
    }

    @Test
    fun `progress flow does not expose an intermediate bucket write`() = runTest {
        val entries = legacyEntries()
        val harness = harness(entries)
        harness.preferences.allRawProgress.first()
        val initialEmission = CompletableDeferred<Unit>()
        val nextEmission = async {
            var initialSeen = false
            harness.preferences.allRawProgress.first {
                if (!initialSeen) {
                    initialSeen = true
                    initialEmission.complete(Unit)
                    false
                } else {
                    true
                }
            }
        }
        initialEmission.await()

        harness.preferences.saveProgress(
            entries.getValue("item0").copy(position = 5_000L, lastWatched = 1_000L)
        )
        val result = nextEmission.await()

        assertEquals(250, result.size)
        assertEquals(5_000L, result.first { it.contentId == "item0" }.position)
    }

    // A pull deletes local entries the remote does not return, so the sync point decides
    // what counts as "already pushed". These pin that boundary, because a sync point taken
    // later than the data it describes silently deletes everything saved in between.

    @Test
    fun `progress saved after the sync point survives a pull that omits it`() = runTest {
        val harness = harness(emptyMap())
        harness.preferences.saveProgress(progress("unsynced", lastWatched = 200L))

        harness.preferences.mergeRemoteEntries(
            remoteEntries = mapOf("remote" to progress("remote", lastWatched = 50L)),
            lastSuccessfulPushMs = 100L
        )

        assertTrue(harness.preferences.getAllRawEntries().containsKey("unsynced"))
    }

    @Test
    fun `progress older than the sync point is dropped when the remote omits it`() = runTest {
        val harness = harness(emptyMap())
        harness.preferences.saveProgress(progress("synced", lastWatched = 50L))

        harness.preferences.mergeRemoteEntries(
            remoteEntries = mapOf("remote" to progress("remote", lastWatched = 50L)),
            lastSuccessfulPushMs = 100L
        )

        assertFalse(harness.preferences.getAllRawEntries().containsKey("synced"))
    }

    @Test
    fun `an entry saved exactly at the sync point counts as pushed`() = runTest {
        val harness = harness(emptyMap())
        harness.preferences.saveProgress(progress("boundary", lastWatched = 100L))

        harness.preferences.mergeRemoteEntries(
            remoteEntries = mapOf("remote" to progress("remote", lastWatched = 50L)),
            lastSuccessfulPushMs = 100L
        )

        assertFalse(harness.preferences.getAllRawEntries().containsKey("boundary"))
    }

    @Test fun `artwork edits current progress without restoring an old position or completion`() = runTest {
        val h=harness(emptyMap());val old=progress("item",100)
        h.preferences.saveProgress(old)
        val storage=h.preferences.captureArtworkStorage(1)
        val completed=old.copy(position=10000,duration=10000,lastWatched=900,source="newer",progressPercent=100f)
        h.preferences.saveProgress(completed)
        h.preferences.updateArtworkIfPresent(old.copy(poster="art",duration=5400000),storage) { true }
        assertEquals(completed.copy(poster="art"),h.preferences.getAllRawEntries().getValue("item"))
    }

    @Test fun `artwork cannot insert a removed entry`() = runTest {
        val h=harness(emptyMap());val old=progress("item",100)
        h.preferences.saveProgress(old);val storage=h.preferences.captureArtworkStorage(1)
        h.preferences.removeProgress("item")
        h.preferences.updateArtworkIfPresent(old.copy(poster="art"),storage) { true }
        assertTrue(h.preferences.getAllRawEntries().isEmpty())
    }

    @Test fun `artwork cannot repopulate cleared history`() = runTest {
        val h=harness(emptyMap());val old=progress("item",100)
        h.preferences.saveProgress(old);val storage=h.preferences.captureArtworkStorage(1)
        h.preferences.clearAll()
        h.preferences.updateArtworkIfPresent(old.copy(poster="art"),storage) { true }
        assertTrue(h.preferences.getAllRawEntries().isEmpty())
    }

    @Test fun `artwork preserves current display fields and fills only missing data`() = runTest {
        val h=harness(emptyMap());val old=progress("item",100)
        h.preferences.saveProgress(old);val storage=h.preferences.captureArtworkStorage(1)
        val latest=old.copy(name="chosen",poster="new",logo="chosen-logo",position=7000)
        h.preferences.saveProgress(latest)
        h.preferences.updateArtworkIfPresent(old.copy(name="old",poster="old",logo="old",backdrop="fill"),storage) { true }
        assertEquals(latest.copy(backdrop="fill"),h.preferences.getAllRawEntries().getValue("item"))
    }

    @Test fun `artwork on archived history preserves bucket placement and recent entries`() = runTest {
        val h=harness(legacyEntries());h.preferences.getAllRawEntries();val storage=h.preferences.captureArtworkStorage(1)
        val recentKeys=h.recent.keys();val archiveKeys=h.archive.keys()
        h.preferences.updateArtworkIfPresent(progress("item0",0).copy(poster="art"),storage) { true }
        assertEquals(recentKeys,h.recent.keys());assertEquals(archiveKeys,h.archive.keys())
        assertEquals("art",h.preferences.getAllRawEntries().getValue("item0").poster)
    }

    @Test fun `old episode artwork preserves newer episode progress`() = runTest {
        val h=harness(emptyMap());val old=progress("show",100).copy(contentType="series",season=1,episode=1,videoId="show:1:1")
        h.preferences.saveProgress(old);val storage=h.preferences.captureArtworkStorage(1)
        val newer=old.copy(episode=2,videoId="show:1:2",lastWatched=200,position=7000)
        h.preferences.saveProgress(newer)
        h.preferences.updateArtworkIfPresent(old.copy(poster="art",episodeTitle="first"),storage) { true }
        val values=h.preferences.getAllRawEntries()
        assertEquals(newer,values.getValue("show_s1e2"))
        assertFalse("show" in values)
        assertEquals(old.copy(poster="art",episodeTitle="first"),values.getValue("show_s1e1"))
    }

    @Test fun `old episode artwork preserves a persisted newer legacy parent mirror`() = runTest {
        val old=progress("show",100).copy(contentType="series",season=1,episode=1,videoId="show:1:1")
        val newer=old.copy(episode=2,videoId="show:1:2",lastWatched=200,position=7000)
        val h=harness(mapOf("show" to newer,"show_s1e1" to old));h.preferences.getAllRawEntries()
        val storage=h.preferences.captureArtworkStorage(1)
        h.preferences.updateArtworkIfPresent(old.copy(poster="art",episodeTitle="first"),storage) { true }
        val values=h.preferences.getAllRawEntries()
        assertEquals(newer,values.getValue("show"))
        assertEquals(old.copy(poster="art",episodeTitle="first"),values.getValue("show_s1e1"))
    }

    @Test fun `retired captured stores cannot edit a recreated identical profile`() = runTest {
        val h=harness(emptyMap());val wrapped=mapOf(
            WATCH_PROGRESS_METADATA_FEATURE to ProfileStoreLifetime(h.metadata),
            WATCH_PROGRESS_RECENT_FEATURE to ProfileStoreLifetime(h.recent),
            WATCH_PROGRESS_ARCHIVE_FEATURE to ProfileStoreLifetime(h.archive))
        every { h.factory.get(any(),any()) } answers { wrapped.getValue(secondArg<String>()) }
        val old=progress("item",100);h.preferences.saveProgress(old)
        val storage=h.preferences.captureArtworkStorage(1)
        wrapped.values.forEach { it.retire() }
        val fresh=mapOf(WATCH_PROGRESS_METADATA_FEATURE to TestPreferencesDataStore(),
            WATCH_PROGRESS_RECENT_FEATURE to TestPreferencesDataStore(),WATCH_PROGRESS_ARCHIVE_FEATURE to TestPreferencesDataStore())
        every { h.factory.get(any(),any()) } answers { fresh.getValue(secondArg<String>()) }
        h.preferences.saveProgress(old.copy(position=9000,lastWatched=500))
        val error=runCatching { h.preferences.updateArtworkIfPresent(old.copy(poster="stale"),storage) { true } }.exceptionOrNull()
        assertTrue(error is kotlinx.coroutines.CancellationException)
        assertEquals(old.copy(position=9000,lastWatched=500),h.preferences.getAllRawEntries().getValue("item"))
    }

    @Test fun `artwork rechecks owner inside a delayed transaction`() = runTest {
        val h=harness(emptyMap());val old=progress("item",100);h.preferences.saveProgress(old)
        val storage=h.preferences.captureArtworkStorage(1);var current=true
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        h.recent.beforeUpdate={ entered.complete(Unit);release.await() }
        val update=async { h.preferences.updateArtworkIfPresent(old.copy(poster="stale"),storage) { current } }
        entered.await();current=false;release.complete(Unit);update.await()
        assertEquals(old,h.preferences.getAllRawEntries().getValue("item"))
    }

    @Test fun `old artwork cannot patch a reused ID with a different content type`() = runTest {
        val h=harness(emptyMap());val old=progress("item",100)
        h.preferences.saveProgress(old);val storage=h.preferences.captureArtworkStorage(1)
        val newer=old.copy(contentType="series",position=7000,lastWatched=200)
        h.preferences.saveProgress(newer)
        h.preferences.updateArtworkIfPresent(old.copy(poster="stale",name="old movie"),storage) { true }
        assertEquals(newer,h.preferences.getAllRawEntries().getValue("item"))
    }

    @Test fun `artwork preserves current TV series type aliases`() = runTest {
        val h=harness(emptyMap());val old=progress("show",100).copy(contentType="TV",season=1,episode=1,videoId="show:1:1")
        h.preferences.saveProgress(old);val storage=h.preferences.captureArtworkStorage(1)
        val newer=old.copy(contentType="series",position=7000,lastWatched=200)
        h.preferences.saveProgress(newer)
        h.preferences.updateArtworkIfPresent(old.copy(poster="art"),storage) { true }
        assertEquals(newer.copy(poster="art"),h.preferences.getAllRawEntries().getValue("show_s1e1"))
    }

    @Test fun `ordinary progress replacement cannot inherit artwork from another item type`() = runTest {
        val h=harness(emptyMap());val old=progress("item",100).copy(name="movie",poster="movie-art",backdrop="movie-bg",logo="movie-logo",episodeTitle="movie-title")
        h.preferences.saveProgress(old)
        val newer=old.copy(contentType="series",name="series",poster=null,backdrop=null,logo=null,episodeTitle=null,position=7000,lastWatched=200)
        h.preferences.saveProgress(newer)
        assertEquals(newer,h.preferences.getAllRawEntries().getValue("item"))
    }

    @Test fun `remote progress replacement cannot inherit artwork from another item type`() = runTest {
        val h=harness(emptyMap());val old=progress("item",100).copy(name="movie",poster="movie-art",backdrop="movie-bg",logo="movie-logo",episodeTitle="movie-title")
        h.preferences.saveProgress(old)
        val newer=old.copy(contentType="series",name="series",poster=null,backdrop=null,logo=null,episodeTitle=null,position=7000,lastWatched=200)
        h.preferences.applyRemoteChanges(mapOf("item" to newer),emptyList())
        assertEquals(newer,h.preferences.getAllRawEntries().getValue("item"))
    }

    @Test fun `ordinary progress replacement retains artwork for TV series aliases`() = runTest {
        val h=harness(emptyMap());val old=progress("show",100).copy(contentType="TV",season=1,episode=1,videoId="show:1:1",poster="series-art",backdrop="series-bg",logo="series-logo",episodeTitle="first")
        h.preferences.saveProgress(old)
        val newer=old.copy(contentType="series",poster=null,backdrop=null,logo=null,episodeTitle=null,position=7000,lastWatched=200)
        h.preferences.saveProgress(newer)
        assertEquals(newer.copy(poster=old.poster,backdrop=old.backdrop,logo=old.logo,episodeTitle=old.episodeTitle),h.preferences.getAllRawEntries().getValue("show_s1e1"))
    }

    private fun harness(entries: Map<String, WatchProgress>): Harness {
        val metadata = TestPreferencesDataStore(preferences(entries = entries))
        val recent = TestPreferencesDataStore()
        val archive = TestPreferencesDataStore()
        val stores = mapOf(
            WATCH_PROGRESS_METADATA_FEATURE to metadata,
            WATCH_PROGRESS_RECENT_FEATURE to recent,
            WATCH_PROGRESS_ARCHIVE_FEATURE to archive
        )
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), any()) } answers {
            stores.getValue(secondArg<String>())
        }
        val profileManager = mockk<ProfileManager>()
        every { profileManager.activeProfileId } returns MutableStateFlow(1)
        return Harness(
            preferences = WatchProgressPreferences(factory, profileManager),
            factory = factory,
            profileManager = profileManager,
            metadata = metadata,
            recent = recent,
            archive = archive
        )
    }

    private fun multiProfileHarness(): MultiProfileHarness {
        val activeProfile = MutableStateFlow(1)
        val stores = mutableMapOf<Pair<Int, String>, TestPreferencesDataStore>()
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), any()) } answers {
            stores.getOrPut(firstArg<Int>() to secondArg<String>()) {
                TestPreferencesDataStore()
            }
        }
        val profileManager = mockk<ProfileManager>()
        every { profileManager.activeProfileId } returns activeProfile
        return MultiProfileHarness(
            preferences = WatchProgressPreferences(factory, profileManager),
            activeProfile = activeProfile
        )
    }

    private fun legacyEntries(): Map<String, WatchProgress> {
        return (0 until 250).associate { index ->
            "item$index" to progress("item$index", lastWatched = index.toLong())
        }
    }

    private fun progress(contentId: String, lastWatched: Long) = WatchProgress(
        contentId = contentId,
        contentType = "movie",
        name = contentId,
        poster = null,
        backdrop = null,
        logo = null,
        videoId = contentId,
        season = null,
        episode = null,
        episodeTitle = null,
        position = 1_000L,
        duration = 10_000L,
        lastWatched = lastWatched
    )

    private fun preferences(entries: Map<String, WatchProgress>): Preferences {
        return emptyPreferences().toMutablePreferences().apply {
            this[watchProgressEntriesKey] = gson.toJson(entries)
        }.toPreferences()
    }

    private fun TestPreferencesDataStore.keys(): Set<String> {
        val json = value[watchProgressEntriesKey] ?: "{}"
        return gson.fromJson(json, JsonObject::class.java).keySet()
    }

    private data class Harness(
        val preferences: WatchProgressPreferences,
        val factory: ProfileDataStoreFactory,
        val profileManager: ProfileManager,
        val metadata: TestPreferencesDataStore,
        val recent: TestPreferencesDataStore,
        val archive: TestPreferencesDataStore
    ) {
        fun resetUpdateCounts() {
            metadata.resetUpdateCount()
            recent.resetUpdateCount()
            archive.resetUpdateCount()
        }
    }

    private data class MultiProfileHarness(
        val preferences: WatchProgressPreferences,
        val activeProfile: MutableStateFlow<Int>
    )

    private class TestPreferencesDataStore(
        initial: Preferences = emptyPreferences()
    ) : DataStore<Preferences> {
        private val mutex = Mutex()
        private val state = MutableStateFlow(initial)

        override val data: Flow<Preferences> = state

        var updateCount: Int = 0
            private set

        val value: Preferences
            get() = state.value

        var beforeUpdate: suspend () -> Unit = {}

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            return mutex.withLock {
                beforeUpdate()
                transform(state.value).also { updated ->
                    updateCount += 1
                    state.value = updated
                }
            }
        }

        fun resetUpdateCount() {
            updateCount = 0
        }
    }
}
