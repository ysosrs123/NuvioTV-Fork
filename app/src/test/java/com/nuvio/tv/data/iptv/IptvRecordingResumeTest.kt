package com.nuvio.tv.data.iptv

import com.nuvio.tv.core.iptv.RecordingMark
import com.nuvio.tv.core.iptv.RecordingStatus
import com.nuvio.tv.core.iptv.RecordingTimeline
import com.nuvio.tv.core.iptv.TsClock
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class IptvRecordingResumeTest {
    @get:Rule val temp = TemporaryFolder()

    private fun entry(id: String) = IptvRecording(id = id, profileId = 1, sourceId = "source-1", accountId = "shared-default", channelId = "channel:1",
        channelName = "Sport", title = "Melbourne Victory v Adelaide United", description = null, startMillis = 1_000, stopMillis = 2_000,
        status = RecordingStatus.DONE, createdAtMillis = 500)

    private fun packets(count: Int, pcrStart: Long): ByteArray {
        val out = ByteArrayOutputStream()
        repeat(count) { index ->
            val packet = ByteArray(TsClock.PACKET) { 0x22 }
            packet[0] = 0x47; packet[1] = 0x01; packet[2] = 0x00
            if (index % 4 == 0) {
                val pcr = pcrStart + index * 900L
                packet[3] = 0x30; packet[4] = 7; packet[5] = 0x10
                packet[6] = (pcr shr 25).toByte(); packet[7] = (pcr shr 17).toByte(); packet[8] = (pcr shr 9).toByte()
                packet[9] = (pcr shr 1).toByte(); packet[10] = ((pcr and 1) shl 7).toByte()
            } else packet[3] = 0x10
            out.write(packet)
        }
        return out.toByteArray()
    }

    @Test fun resumePositionSurvivesReopen() {
        val file = File(temp.root, "r.json")
        val store = IptvRecordingStore(file)
        store.insert(entry("aaaaaaaa-1"))
        store.insert(entry("bbbbbbbb-2"))
        store.update("aaaaaaaa-1") { it.copy(resumeMillis = 3_600_000, lengthMillis = 10_800_000) }
        val reopened = IptvRecordingStore(file).all().associateBy { it.id }
        assertEquals(3_600_000L, reopened.getValue("aaaaaaaa-1").resumeMillis)
        assertEquals(10_800_000L, reopened.getValue("aaaaaaaa-1").lengthMillis)
        assertNull(reopened.getValue("bbbbbbbb-2").resumeMillis)
        store.update("aaaaaaaa-1") { it.copy(resumeMillis = null) }
        assertNull(IptvRecordingStore(file).get("aaaaaaaa-1")?.resumeMillis)
        assertFalse(file.readText().contains("\"resume\""))
    }

    @Test fun indexIsKeptPerRecordingAndIgnoresDamage() {
        val folder = File(temp.root, "index")
        val index = IptvRecordingIndex(folder)
        val timeline = RecordingTimeline(listOf(RecordingMark(0, 0), RecordingMark(1_880, 4_000, cut = true)), 3_760, 8_000)
        index.save("aaaaaaaa-1", timeline)
        index.save("../escape", timeline)
        assertEquals(timeline, IptvRecordingIndex(folder).load("aaaaaaaa-1"))
        assertEquals(listOf("aaaaaaaa-1.idx"), folder.list()?.toList())
        File(folder, "bbbbbbbb-2.idx").writeText("nonsense")
        assertNull(index.load("bbbbbbbb-2"))
        assertNull(index.load("cccccccc-3"))
        index.delete("aaaaaaaa-1")
        assertNull(index.load("aaaaaaaa-1"))
    }

    @Test fun shareReaderReadsLittleAfterAJumpAndMoreWhenSequential() {
        val share = FakeShare()
        val data = ByteArray(2_000_000) { (it % 251).toByte() }
        share.files["TV/a.ts"] = data
        val reader = IptvShareReader(share, "TV/a.ts")
        val buffer = ByteArray(1_000)
        assertEquals(1_000, reader.read(0, buffer, 0, 1_000))
        assertEquals(1_000, reader.read(131_072, buffer, 0, 1_000))
        share.down = true
        assertEquals(10, reader.read(131_072 + 500_000, buffer, 0, 10))
        assertEquals(((131_072 + 500_000) % 251).toByte(), buffer[0])
        try { reader.read(900_000, buffer, 0, 10); fail() } catch (_: IptvShareException) { }
        share.down = false
        assertEquals(data.copyOfRange(1_500_000, 1_700_000).toList(), reader.window(1_500_000, 200_000).toList())
        assertEquals(500, reader.window(1_999_500, 2_000).size)
        reader.close()
    }

    @Test fun windowReadsAcrossParts() {
        val first = File(temp.root, "a.ts").apply { writeBytes(ByteArray(300) { 1 }) }
        val second = File(temp.root, "a (part 2).ts").apply { writeBytes(ByteArray(300) { 2 }) }
        IptvPartsReader(listOf(first, second)).use { reader ->
            val window = reader.window(250, 100)
            assertEquals(List(50) { 1.toByte() } + List(50) { 2.toByte() }, window.toList())
        }
    }

    @Test fun recordedHlsKeepsATimeIndexWithDiscontinuities() = runBlocking {
        val one = packets(500, 1_000)
        val two = packets(500, 5_000_000_000)
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = when (request.path) {
                    "/live.m3u8" -> MockResponse().setHeader("Content-Type", "application/vnd.apple.mpegurl")
                        .setBody("#EXTM3U\n#EXT-X-TARGETDURATION:5\n#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:5.0,\na.ts\n#EXT-X-DISCONTINUITY\n#EXTINF:5.0,\nb.ts\n#EXT-X-ENDLIST\n")
                    "/a.ts" -> MockResponse().setHeader("Content-Type", "video/mp2t").setBody(Buffer().write(one))
                    "/b.ts" -> MockResponse().setHeader("Content-Type", "video/mp2t").setBody(Buffer().write(two))
                    else -> MockResponse().setResponseCode(404)
                }
            }
            start()
        }
        server.use {
            val progress = IptvRecordingProgress()
            val result = IptvRecordingCopier(freeBytes = { Long.MAX_VALUE }, pause = { }, minimumStallMillis = 2_000)
                .copy(server.url("/live.m3u8").toString(), IptvStreamFormat.HLS, File(temp.root, "out.ts"), System.currentTimeMillis() + 30_000, progress)
            assertNull(result.failure)
            val timeline = requireNotNull(progress.clock.timeline(progress.committed))
            assertEquals(RecordingMark(0, 0), timeline.marks.first())
            assertEquals(listOf(one.size.toLong()), timeline.marks.filter { it.cut }.map { it.offset })
            assertEquals(4_960L, timeline.marks.first { it.cut }.millis)
            assertEquals(9_920L, timeline.endMillis)
        }
    }
}
