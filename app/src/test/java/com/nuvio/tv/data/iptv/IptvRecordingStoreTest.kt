package com.nuvio.tv.data.iptv

import com.nuvio.tv.core.iptv.RecordingFailure
import com.nuvio.tv.core.iptv.RecordingStatus
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class IptvRecordingStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private fun entry(id: String, profile: Int = 1, status: RecordingStatus = RecordingStatus.SCHEDULED) = IptvRecording(
        id = id, profileId = profile, sourceId = "source-1", accountId = "shared-default", channelId = "channel:1",
        channelName = "News", title = "Evening news", description = null, startMillis = 1_000, stopMillis = 2_000,
        status = status, createdAtMillis = 500)

    @Test fun entriesSurviveReopenWithAllFields() {
        val file = File(temp.root, "iptv/recordings.json")
        val store = IptvRecordingStore(file)
        val full = entry("aaaaaaaa-1").copy(description = "Line \"one\"\nline two", failure = RecordingFailure.LOW_STORAGE, status = RecordingStatus.SCHEDULED,
            file = "/storage/x.ts", bytes = 42, gaps = 2, programmeStartMillis = 1_060, programmeStopMillis = 1_880, startedAtMillis = 1_001, finishedAtMillis = 1_999)
        store.insert(full)
        store.insert(entry("bbbbbbbb-2", profile = 2))
        val reopened = IptvRecordingStore(file)
        assertEquals(listOf(full, entry("bbbbbbbb-2", profile = 2)), reopened.all())
        assertFalse(File(file.parentFile, "recordings.json.tmp").exists())
        assertFalse(file.readText().contains("password"))
    }

    @Test fun updatesEnforceTransitions() {
        val store = IptvRecordingStore(File(temp.root, "r.json"))
        store.insert(entry("aaaaaaaa-1"))
        assertEquals(RecordingStatus.RECORDING, store.update("aaaaaaaa-1") { it.copy(status = RecordingStatus.RECORDING) }?.status)
        assertEquals(10L, store.update("aaaaaaaa-1") { it.copy(bytes = 10) }?.bytes)
        try { store.update("aaaaaaaa-1") { it.copy(status = RecordingStatus.SCHEDULED) }; fail() } catch (_: IllegalArgumentException) { }
        assertEquals(RecordingStatus.DONE, store.update("aaaaaaaa-1") { it.copy(status = RecordingStatus.DONE) }?.status)
        try { store.update("aaaaaaaa-1") { it.copy(status = RecordingStatus.FAILED) }; fail() } catch (_: IllegalArgumentException) { }
        assertNull(store.update("missing-1") { it })
        try { store.insert(entry("aaaaaaaa-1")); fail() } catch (_: IllegalArgumentException) { }
    }

    @Test fun profileRemovalAndLimits() {
        val store = IptvRecordingStore(File(temp.root, "r.json"), maxEntries = 3)
        store.insert(entry("aaaaaaaa-1", profile = 1))
        store.insert(entry("bbbbbbbb-2", profile = 2))
        store.insert(entry("cccccccc-3", profile = 1))
        try { store.insert(entry("dddddddd-4")); fail() } catch (_: IllegalStateException) { }
        assertEquals(listOf("aaaaaaaa-1", "cccccccc-3"), store.removeProfile(1).map { it.id })
        assertEquals(listOf("bbbbbbbb-2"), IptvRecordingStore(File(temp.root, "r.json")).all().map { it.id })
        assertEquals("bbbbbbbb-2", store.remove("bbbbbbbb-2")?.id)
        assertNull(store.remove("bbbbbbbb-2"))
        store.insert(entry("eeeeeeee-5"))
        assertEquals(1, store.clear().size)
        assertTrue(IptvRecordingStore(File(temp.root, "r.json")).all().isEmpty())
    }

    @Test fun corruptFileIsSetAsideAndInvalidRowsSkipped() {
        val file = File(temp.root, "r.json")
        file.writeText("{not json")
        assertTrue(IptvRecordingStore(file).all().isEmpty())
        assertTrue(File(temp.root, "r.json.bad").exists())
        val store = IptvRecordingStore(file)
        store.insert(entry("aaaaaaaa-1"))
        file.writeText(file.readText().replace("]", ",{\"id\":\"../x\"}]"))
        assertEquals(listOf("aaaaaaaa-1"), IptvRecordingStore(file).all().map { it.id })
    }

    @Test fun failedWritesKeepUpdatesInMemoryButNotInserts() {
        val folder = File(temp.root, "list")
        val store = IptvRecordingStore(File(folder, "r.json"))
        store.insert(entry("aaaaaaaa-1"))
        folder.deleteRecursively()
        folder.writeText("not a folder")
        try { store.update("aaaaaaaa-1") { it.copy(status = RecordingStatus.RECORDING) }; fail() } catch (_: java.io.IOException) { }
        assertEquals(RecordingStatus.RECORDING, store.get("aaaaaaaa-1")?.status)
        try { store.insert(entry("bbbbbbbb-2")); fail() } catch (_: java.io.IOException) { }
        assertNull(store.get("bbbbbbbb-2"))
        try { store.remove("aaaaaaaa-1"); fail() } catch (_: java.io.IOException) { }
        assertNull(store.get("aaaaaaaa-1"))
    }
}
