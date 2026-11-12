package com.nuvio.tv.data.mdblist

import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MdbListSaveMemoryTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun repository(
        harness: MdbListSyncTestHarness,
        scope: CoroutineScope,
        playerOpen: () -> Boolean = { false },
        encode: (MdbListSyncSnapshot) -> String = { json.encodeToString(it) }
    ) = MdbListSyncRepository(
        harness.storage, harness.http.store, harness.http.api, harness.activeProfile, scope, { harness.http.now },
        scope.coroutineContext[ContinuationInterceptor] as CoroutineDispatcher,
        playerOpen = playerOpen, encode = encode
    ) { harness.remote }

    @Test
    fun `automatic refresh waits while the player is open`() = runTest {
        val harness = MdbListSyncTestHarness(backgroundScope)
        var open = true
        val repository = repository(harness, backgroundScope, playerOpen = { open })
        repository.refresh(TrackingRefreshIntent.AUTOMATIC)
        val before = harness.remote.calls.count { it == "activities" }
        harness.http.now += MdbListSyncRepository.AUTOMATIC_INTERVAL_MS
        repository.refresh(TrackingRefreshIntent.AUTOMATIC)
        assertEquals(before, harness.remote.calls.count { it == "activities" })
        repository.refresh(TrackingRefreshIntent.USER_INITIATED)
        assertEquals(before + 1, harness.remote.calls.count { it == "activities" })
        open = false
        harness.http.now += MdbListSyncRepository.AUTOMATIC_INTERVAL_MS
        repository.refresh(TrackingRefreshIntent.AUTOMATIC)
        assertEquals(before + 2, harness.remote.calls.count { it == "activities" })
    }

    @Test
    fun `a save that runs out of memory is skipped and not retried for fifteen minutes`() = runTest {
        val harness = MdbListSyncTestHarness(backgroundScope)
        harness.seed()
        var full = true
        val repository = repository(harness, backgroundScope, encode = {
            if (full) throw OutOfMemoryError("test") else json.encodeToString(it)
        })
        repository.ensureLoaded()
        val original = harness.storage.profiles[1]
        harness.remote.activities = harness.remote.activities.copy(serverTime = "2026-09-06T01:00:00Z")

        repository.refresh(TrackingRefreshIntent.USER_INITIATED)
        assertEquals(original, harness.storage.profiles[1])
        assertEquals(MDBLIST_TEST_TIME, repository.currentSnapshot()?.watermark)
        assertEquals(MdbListSyncError.UNAVAILABLE, repository.state.value.error)

        val calls = harness.remote.calls.count { it == "activities" }
        harness.http.now += MdbListSyncRepository.AUTOMATIC_INTERVAL_MS - 1
        repository.refresh(TrackingRefreshIntent.AUTOMATIC)
        repository.refresh(TrackingRefreshIntent.USER_INITIATED)
        assertEquals(calls, harness.remote.calls.count { it == "activities" })

        full = false
        harness.http.now += 1
        repository.refresh(TrackingRefreshIntent.AUTOMATIC)
        assertEquals(calls + 1, harness.remote.calls.count { it == "activities" })
        assertNull(repository.state.value.error)
    }
}
