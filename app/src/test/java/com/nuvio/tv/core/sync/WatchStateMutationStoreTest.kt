package com.nuvio.tv.core.sync

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.nuvio.tv.TestPreferencesStore
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import com.nuvio.tv.domain.model.WatchedMutationKey
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchStateMutationStoreTest {
    @Test
    fun `progress mutations stay isolated by profile`() = runTest {
        val harness = harness()
        val first = progress("first", 100L)
        val second = progress("second", 200L)

        harness.store.queueProgressUpserts(mapOf("first" to first), 1)
        harness.store.queueProgressUpserts(mapOf("second" to second), 2)

        assertEquals(mapOf("first" to first), harness.store.pendingProgressUpserts(1))
        assertEquals(mapOf("second" to second), harness.store.pendingProgressUpserts(2))
    }

    @Test
    fun `stale progress acknowledgement cannot clear a newer write`() = runTest {
        val harness = harness()
        val older = progress("item", 100L)
        val newer = progress("item", 200L)

        harness.store.queueProgressUpserts(mapOf("item" to older), 1)
        harness.store.queueProgressUpserts(mapOf("item" to newer), 1)
        harness.store.acknowledgeProgressUpserts(mapOf("item" to older), 1)

        assertEquals(mapOf("item" to newer), harness.store.pendingProgressUpserts(1))
    }

    @Test
    fun `progress delete and upsert supersede each other`() = runTest {
        val harness = harness()
        val item = progress("item", 100L)

        harness.store.queueProgressUpserts(mapOf("item" to item), 1)
        harness.store.queueProgressDeletes(listOf("item"), 1)

        assertTrue(harness.store.pendingProgressUpserts(1).isEmpty())
        assertEquals(setOf("item"), harness.store.pendingProgressDeletes(1))

        harness.store.queueProgressUpserts(mapOf("item" to item), 1)

        assertEquals(mapOf("item" to item), harness.store.pendingProgressUpserts(1))
        assertTrue(harness.store.pendingProgressDeletes(1).isEmpty())
    }

    @Test
    fun `stale watched acknowledgement cannot clear a newer write`() = runTest {
        val harness = harness()
        val older = watched("show", watchedAt = 100L)
        val newer = watched("show", watchedAt = 200L)

        harness.store.queueWatchedUpserts(listOf(older), 1)
        harness.store.queueWatchedUpserts(listOf(newer), 1)
        harness.store.acknowledgeWatchedUpserts(listOf(older), 1)

        assertEquals(listOf(newer), harness.store.pendingWatchedUpserts(1).values.toList())
    }

    @Test
    fun `watched delete and upsert supersede each other`() = runTest {
        val harness = harness()
        val item = watched("show", watchedAt = 100L)
        val key = WatchedMutationKey("show", 1, 2)

        harness.store.queueWatchedUpserts(listOf(item), 1)
        harness.store.queueWatchedDeletes(listOf(key), 1)

        assertTrue(harness.store.pendingWatchedUpserts(1).isEmpty())
        assertEquals(setOf(key), harness.store.pendingWatchedDeletes(1))

        harness.store.queueWatchedUpserts(listOf(item), 1)

        assertEquals(item, harness.store.pendingWatchedUpserts(1)[key])
        assertTrue(harness.store.pendingWatchedDeletes(1).isEmpty())
    }

    @Test
    fun `pending mutations survive store recreation and clear only after acknowledgement`() = runTest {
        val harness = harness()
        val item = progress("item", 100L)
        val watched = watched("show", watchedAt = 100L)
        harness.store.queueProgressUpserts(mapOf("item" to item), 1)
        harness.store.queueWatchedUpserts(listOf(watched), 1)

        val recreated = WatchStateMutationStore(harness.factory)

        assertTrue(recreated.hasPendingProgressMutations(1))
        assertTrue(recreated.hasPendingWatchedMutations(1))
        recreated.acknowledgeProgressUpserts(mapOf("item" to item), 1)
        recreated.acknowledgeWatchedUpserts(listOf(watched), 1)
        assertFalse(recreated.hasPendingProgressMutations(1))
        assertFalse(recreated.hasPendingWatchedMutations(1))
    }

    @Test
    fun `incomplete pending entries are skipped`() = runTest {
        val harness = harness()
        val item = progress("item", 100L)
        harness.store.queueProgressUpserts(mapOf("item" to item), 1)
        harness.factory.get(1, "watch_state_pending_mutations").edit { prefs ->
            prefs[stringSetPreferencesKey("progress_upserts")] =
                prefs[stringSetPreferencesKey("progress_upserts")].orEmpty() + """{"key":"old","progress":{"contentType":"movie"}}"""
            prefs[stringSetPreferencesKey("watched_upserts")] = setOf("""{"key":{"contentId":"show"},"item":{}}""")
        }

        assertEquals(mapOf("item" to item), harness.store.pendingProgressUpserts(1))
        assertTrue(harness.store.pendingWatchedUpserts(1).isEmpty())
        harness.store.queueProgressUpserts(mapOf("next" to progress("next", 200L)), 1)
        assertEquals(setOf("item", "next"), harness.store.pendingProgressUpserts(1).keys)
    }

    @Test
    fun `pending entries saved by earlier release builds are still read`() = runTest {
        val harness = harness()
        val gson = com.google.gson.Gson()
        val item = progress("tt1", 100L)
        val watched = watched("show", watchedAt = 100L)
        harness.factory.get(1, "watch_state_pending_mutations").edit { prefs ->
            prefs[stringSetPreferencesKey("progress_upserts")] = setOf("""{"a":"tt1","b":${gson.toJson(item)}}""")
            prefs[stringSetPreferencesKey("watched_upserts")] =
                setOf("""{"a":{"contentId":"show","season":1,"episode":2},"b":${gson.toJson(watched)}}""")
        }

        assertEquals(mapOf("tt1" to item), harness.store.pendingProgressUpserts(1))
        assertEquals(mapOf(WatchedMutationKey("show", 1, 2) to watched), harness.store.pendingWatchedUpserts(1))

        harness.store.queueProgressUpserts(mapOf("tt2" to progress("tt2", 200L)), 1)
        val rewritten = harness.factory.get(1, "watch_state_pending_mutations").data.first()[stringSetPreferencesKey("progress_upserts")].orEmpty()
        assertTrue(rewritten.all { it.contains("\"key\"") && it.contains("\"progress\"") })
        assertEquals(setOf("tt1", "tt2"), harness.store.pendingProgressUpserts(1).keys)
    }

    private fun harness(): Harness {
        val stores = mutableMapOf<Int, TestPreferencesStore>()
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), any()) } answers {
            stores.getOrPut(firstArg()) { TestPreferencesStore() }
        }
        return Harness(WatchStateMutationStore(factory), factory)
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

    private fun watched(contentId: String, watchedAt: Long) = WatchedItem(
        contentId = contentId,
        contentType = "series",
        title = contentId,
        season = 1,
        episode = 2,
        watchedAt = watchedAt
    )

    private data class Harness(
        val store: WatchStateMutationStore,
        val factory: ProfileDataStoreFactory
    )
}
