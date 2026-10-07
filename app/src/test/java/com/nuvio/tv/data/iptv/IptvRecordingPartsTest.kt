package com.nuvio.tv.data.iptv

import androidx.media3.common.C
import com.nuvio.tv.core.iptv.RecordingFiles
import com.nuvio.tv.core.iptv.RecordingParts
import java.io.File
import java.util.concurrent.TimeUnit
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

class IptvRecordingPartsTest {
    @get:Rule val temp = TemporaryFolder()

    private val main = "News - 2026-10-07 1100 - 0f3c9a2e.ts"
    private fun packets(marker: Int, count: Int = 4) = ByteArray(188 * count) { if (it % 188 == 0) 0x47 else marker.toByte() }
    private fun output(dir: File, limit: Long, headroom: Long = 0) =
        IptvRecordingOutput(dir, { index, _ -> File(dir, RecordingFiles.partial(RecordingParts.name(main, index))) }, limit, headroom)
    private fun server(routes: (RecordedRequest) -> MockResponse) = MockWebServer().apply {
        dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = routes(request) }
        start()
    }
    private fun playlist(text: String) = MockResponse().setHeader("Content-Type", "application/vnd.apple.mpegurl").setBody(text)
    private fun ts(bytes: ByteArray) = MockResponse().setHeader("Content-Type", "video/mp2t").setBody(Buffer().write(bytes))

    @Test fun transportStreamSplitsIntoPacketAlignedParts() {
        val dir = temp.newFolder()
        val out = output(dir, 188L * 3 + 100)
        out.open()
        val data = packets(1, 10)
        out.write(data, 0, 300, true)
        out.write(data, 300, data.size - 300, true)
        out.close()
        val files = out.files()
        assertEquals(4, files.size)
        assertEquals(listOf(564L, 564L, 564L, 188L), files.map { it.length() })
        assertEquals(RecordingFiles.partial(main), files[0].name)
        assertEquals(RecordingFiles.partial("News - 2026-10-07 1100 - 0f3c9a2e (part 4).ts"), files[3].name)
        assertArrayEquals(data, files.map { it.readBytes() }.reduce { a, b -> a + b })
        assertEquals(data.size.toLong(), out.bytes)
        val reopened = output(dir, 188L * 3 + 100)
        reopened.open()
        assertEquals(4, reopened.parts)
        assertEquals(data.size.toLong(), reopened.bytes)
        assertEquals(188L * 9, reopened.partStart)
        reopened.close()
    }

    @Test fun segmentsAreNeverSplitAndRollbackStaysInsideThePart() {
        val dir = temp.newFolder()
        val out = output(dir, 1_000)
        out.open()
        val segment = packets(2, 3)
        out.beforeSegment(); out.write(segment, 0, segment.size, false)
        out.beforeSegment(); out.write(segment, 0, segment.size, false)
        assertEquals(1, out.parts)
        assertEquals(1128L, out.partSize)
        out.beforeSegment()
        assertEquals(2, out.parts)
        val mark = out.bytes
        out.write(segment, 0, 100, false)
        assertTrue(out.rollback(mark))
        assertEquals(0L, out.partSize)
        assertFalse(out.rollback(10))
        out.write(segment, 0, segment.size, false)
        out.close()
        assertEquals(listOf(1128L, 564L), out.files().map { it.length() })
    }

    @Test fun largeFileSystemsKeepOnePart() {
        val dir = temp.newFolder()
        val out = output(dir, Long.MAX_VALUE, RecordingParts.SEGMENT_HEADROOM_BYTES)
        out.open()
        repeat(50) { out.beforeSegment(); out.write(packets(it), 0, 752, true) }
        out.close()
        assertEquals(1, out.parts)
        assertEquals(752L * 50, out.files().single().length())
    }

    @Test fun copierRollsTransportStreamsAndHlsIntoParts() = runBlocking {
        val body = packets(1, 400)
        val live = server { ts(body).throttleBody(188L * 20, 100, TimeUnit.MILLISECONDS) }
        live.use {
            val dir = temp.newFolder()
            val out = output(dir, 188L * 50)
            val progress = IptvRecordingProgress()
            val result = IptvRecordingCopier(freeBytes = { Long.MAX_VALUE }, pause = { }, minimumStallMillis = 2_000)
                .copy(live.url("/live.ts").toString(), IptvStreamFormat.MPEG_TS, out, System.currentTimeMillis() + 1_500, progress)
            assertNull(result.failure)
            assertTrue(result.parts > 1)
            assertEquals(result.parts, out.files().size)
            assertEquals(result.bytes, progress.committed)
            assertTrue(out.files().dropLast(1).all { it.length() == 188L * 50 } && out.files().last().length() <= 188L * 50)
            assertArrayEquals(body.copyOf(result.bytes.toInt()), out.files().map { it.readBytes() }.reduce { a, b -> a + b })
        }
        val hls = server { request ->
            val path = request.path.orEmpty()
            when {
                path == "/index.m3u8" -> playlist("#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:0\n" +
                    (0 until 3).joinToString("") { "#EXTINF:1.0,\nseg$it.ts\n" } + "#EXT-X-ENDLIST\n")
                path.startsWith("/seg") -> ts(packets(path.removePrefix("/seg").removeSuffix(".ts").toInt() + 10, 5))
                else -> MockResponse().setResponseCode(404)
            }
        }
        hls.use {
            val dir = temp.newFolder()
            val out = output(dir, 188L * 8)
            val result = IptvRecordingCopier(freeBytes = { Long.MAX_VALUE }, pause = { }, minimumStallMillis = 2_000)
                .copy(hls.url("/index.m3u8").toString(), IptvStreamFormat.HLS, out, System.currentTimeMillis() + 30_000)
            assertNull(result.failure)
            assertEquals(2, result.parts)
            assertEquals(listOf(188L * 10, 188L * 5), out.files().map { it.length() })
            out.files().forEach { file -> assertEquals(0, file.length() % (188 * 5)) }
            assertArrayEquals((0 until 3).map { packets(it + 10, 5) }.reduce { a, b -> a + b }, out.files().map { it.readBytes() }.reduce { a, b -> a + b })
        }
    }

    @Test fun partsReaderAndDataSourceReadAcrossPartBoundaries() {
        val dir = temp.newFolder()
        val first = File(dir, "a.ts").apply { writeBytes(ByteArray(300) { it.toByte() }) }
        val second = File(dir, "b.ts").apply { writeBytes(ByteArray(200) { (it + 300).toByte() }) }
        val reader = IptvPartsReader(listOf(first, second))
        assertEquals(500L, reader.length())
        val buffer = ByteArray(100)
        assertEquals(20, reader.read(280, buffer, 0, 100))
        assertEquals(100, reader.read(300, buffer, 0, 100))
        assertEquals(44.toByte(), buffer[0])
        assertEquals(-1, reader.read(500, buffer, 0, 10))
        val source = IptvRecordingDataSource(reader)
        assertEquals(250L, source.start(250, C.LENGTH_UNSET.toLong()))
        val all = java.io.ByteArrayOutputStream()
        while (true) {
            val count = source.read(buffer, 0, buffer.size)
            if (count == C.RESULT_END_OF_INPUT) break
            all.write(buffer, 0, count)
        }
        source.close()
        assertArrayEquals(ByteArray(250) { (it + 250).toByte() }, all.toByteArray())
        assertEquals(50L, IptvRecordingDataSource(reader).start(10, 50))
        try { IptvRecordingDataSource(reader).start(600, C.LENGTH_UNSET.toLong()); fail() } catch (_: java.io.IOException) { }
        reader.close()
    }

    @Test fun shareReaderReadsAheadAndReconnectsOnce() {
        val share = FakeShare()
        val data = ByteArray(5_000) { (it % 127).toByte() }
        share.files["TV/a.ts"] = data
        val reader = IptvShareReader(share, "TV/a.ts", windowBytes = 1_024)
        assertEquals(5_000L, reader.length())
        val buffer = ByteArray(4_096)
        assertEquals(1_024, reader.read(0, buffer, 0, 4_096))
        assertEquals(1, share.connects)
        assertEquals(100, reader.read(924, buffer, 0, 100))
        assertEquals((924 % 127).toByte(), buffer[0])
        share.down = true
        try { reader.read(3_000, buffer, 0, 10); fail() } catch (error: IptvShareException) { assertEquals(IptvShareError.UNREACHABLE, error.error) }
        share.down = false
        assertEquals(10, reader.read(3_000, buffer, 0, 10))
        assertEquals((3_000 % 127).toByte(), buffer[0])
        assertEquals(-1, reader.read(5_000, buffer, 0, 10))
        reader.close()
        assertFalse(reader.toString().contains("TV"))
    }
}
