package com.nuvio.tv.data.iptv

import com.nuvio.tv.core.iptv.RecordingStatus
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class IptvRecordingPlayedTest {
    @get:Rule val temp = TemporaryFolder()
    private fun entry(id: String) = IptvRecording(id = id, profileId = 1, sourceId = "source-1", accountId = "shared-default", channelId = "channel:1",
        channelName = "Sport", title = "Sydney FC v Western Sydney Wanderers", description = null, startMillis = 1_000, stopMillis = 2_000,
        status = RecordingStatus.DONE, createdAtMillis = 500)

    @Test fun playedAndFixtureSurviveReopen() {
        val file = File(temp.root, "r.json")
        val store = IptvRecordingStore(file)
        store.insert(entry("aaaaaaaa-1").copy(fixtureKey = "a-league-men:401"))
        store.insert(entry("bbbbbbbb-2"))
        assertEquals(9_000L, store.update("aaaaaaaa-1") { it.copy(playedAtMillis = 9_000) }?.playedAtMillis)
        val reopened = IptvRecordingStore(file).all().associateBy { it.id }
        assertEquals(9_000L, reopened.getValue("aaaaaaaa-1").playedAtMillis)
        assertEquals("a-league-men:401", reopened.getValue("aaaaaaaa-1").fixtureKey)
        assertNull(reopened.getValue("bbbbbbbb-2").playedAtMillis)
        assertNull(reopened.getValue("bbbbbbbb-2").fixtureKey)
    }

    @Test fun olderListsWithoutPlayedLoad() {
        val file = File(temp.root, "r.json")
        file.writeText("""{"version":1,"recordings":[{"id":"aaaaaaaa-1","profile":1,"source":"source-1","account":"shared-default","channel":"channel:1",""" +
            """"channelName":"Sport","start":1000,"stop":2000,"status":"DONE","created":500}]}""")
        val loaded = IptvRecordingStore(file).all().single()
        assertNull(loaded.playedAtMillis)
        assertNull(loaded.fixtureKey)
        assertEquals(RecordingStatus.DONE, loaded.status)
    }
}
