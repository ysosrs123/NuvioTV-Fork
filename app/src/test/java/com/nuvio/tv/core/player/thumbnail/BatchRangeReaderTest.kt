package com.nuvio.tv.core.player.thumbnail

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class BatchRangeReaderTest {
    private val file = ByteArray(4 * 1024 * 1024) { (it * 31 + it / 7).toByte() }

    private class MemoryOf : MultiRangeSupport.Memory {
        val saved = HashMap<String, String>()
        override fun load(): Map<String, String> = saved.toMap()
        override fun save(key: String, value: String?) {
            if (value == null) saved.remove(key) else saved[key] = value
        }
    }

    /** Serves [file] by range; [multi] false answers a many-range request with its first range only. */
    private fun server(multi: Boolean): MockWebServer = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val spec = request.getHeader("Range")!!.removePrefix("bytes=").split(',')
                    .map { it.split('-').let { (a, b) -> a.toLong()..b.toLong() } }
                fun part(r: LongRange) = file.copyOfRange(r.first.toInt(), minOf(r.last.toInt() + 1, file.size))
                if (spec.size == 1 || !multi) {
                    val r = spec.first()
                    return MockResponse().setResponseCode(206)
                        .setHeader("Content-Range", "bytes ${r.first}-${r.last}/${file.size}")
                        .setBody(Buffer().write(part(r)))
                }
                val body = Buffer()
                for (r in spec) {
                    body.writeUtf8("\r\n--XYZ\r\nContent-Type: video/x-matroska\r\nContent-Range: bytes ${r.first}-${r.last}/${file.size}\r\n\r\n")
                    body.write(part(r))
                }
                body.writeUtf8("\r\n--XYZ--\r\n")
                return MockResponse().setResponseCode(206)
                    .setHeader("Content-Type", "multipart/byteranges; boundary=XYZ").setBody(body)
            }
        }
    }

    private fun readAt(reader: RangeReader, offset: Long, length: Int): ByteArray {
        val out = ByteArray(length)
        val n = reader.readInto(offset, length, ByteBuffer.wrap(out))
        return out.copyOf(n)
    }

    @Test fun aMultipartBodyIsSplitIntoItsRanges() {
        val body = Buffer().writeUtf8("\r\n--b1\r\nContent-Range: bytes 10-13/100\r\n\r\nabcd\r\n--b1\r\n" +
            "content-range: bytes 50-51/100\r\n\r\nxy\r\n--b1--\r\n")
        var total = 0L
        val parts = MultipartRanges.read(body, "b1", 1024) { total = it }
        assertEquals(listOf(10L, 50L), parts.map { it.first })
        assertEquals("abcd", String(parts[0].second))
        assertEquals("xy", String(parts[1].second))
        assertEquals(100L, total)
        assertEquals("b1", MultipartRanges.boundary("multipart/byteranges; boundary=\"b1\""))
        assertNull(MultipartRanges.contentRange("bytes 9-3/100"))
    }

    @Test fun aMultipartBodyAboveTheCapIsRefused() {
        val body = Buffer().writeUtf8("--b\r\nContent-Range: bytes 0-99/100\r\n\r\n" + "x".repeat(100) + "\r\n--b--\r\n")
        assertThrows(java.io.IOException::class.java) { MultipartRanges.read(body, "b", 50) }
    }

    @Test fun overlappingPiecesBecomeOneRange() {
        assertEquals(listOf(0L..150L, 200L..300L), BatchRangeReader.ranges(listOf(200L..300L, 0L..100L, 50L..150L)))
        assertEquals(listOf(0L..200L), BatchRangeReader.ranges(listOf(0L..100L, 100L..200L)))
    }

    @Test fun cdnNodesShareOneAnswerAndANoIsTriedAgainAfterAWeek() {
        assertEquals("tb-cdn.pw:443", MultiRangeSupport.key("https://nexus-171.apac.tb-cdn.pw/dld/x?token=y"))
        assertEquals(MultiRangeSupport.key("https://nexus-1.eu.tb-cdn.pw/a"), MultiRangeSupport.key("https://nexus-9.apac.tb-cdn.pw/b"))
        assertEquals("10.0.0.5:3100", MultiRangeSupport.key("http://10.0.0.5:3100/stream"))
        assertEquals("tb-cdn.io:443", MultiRangeSupport.key("https://nexus-341.wnam.tb-cdn.io/a"))
        assertEquals("store.tb-cdn.io:443", MultiRangeSupport.key("https://store-013.wnam.tb-cdn.io/a"))
        val s = MultiRangeSupport(MemoryOf())
        assertTrue(s.allowed("https://a.example.com/f", now = 0))
        s.note("https://a.example.com/f", yes = false, now = 0)
        assertFalse(s.allowed("https://b.example.com/f", now = 1000))
        assertTrue(s.allowed("https://a.example.com/f", now = MultiRangeSupport.RETRY_NO_MS + 1))
    }

    @Test fun manyRangesComeBackInOneRequest() {
        server(multi = true).use { srv ->
            val reader = HttpRangeReader(srv.url("/f").toString(), emptyMap(), OkHttpClient())
            val parts = reader.readRanges(listOf(100L..200L, 1_000_000L..1_000_300L), 4096)
            assertEquals(1, srv.requestCount)
            assertArrayEquals(file.copyOfRange(100, 200), parts[0].second)
            assertArrayEquals(file.copyOfRange(1_000_000, 1_000_300), parts[1].second)
            assertEquals(file.size.toLong(), reader.totalLength)
        }
    }

    @Test fun aServerSendingOnlyTheFirstRangeIsRefused() {
        server(multi = false).use { srv ->
            val reader = HttpRangeReader(srv.url("/f").toString(), emptyMap(), OkHttpClient())
            assertThrows(HttpRangeReader.MultiRangeRefused::class.java) {
                reader.readRanges(listOf(100L..200L, 1_000_000L..1_000_300L), 4096)
            }
        }
    }

    @Test fun plannedKeyframesAreFetchedTogetherAndServedFromTheBatch() {
        server(multi = true).use { srv ->
            val inner = HttpRangeReader(srv.url("/f").toString(), emptyMap(), OkHttpClient())
            val support = MultiRangeSupport(MemoryOf())
            val reader = BatchRangeReader(inner, support, budgetBytes = 8L * 1024 * 1024)
            val starts = (0 until 6).map { 100_000L + it * 500_000L }
            reader.plan(starts.map { BatchRangeReader.Planned(it, 64 * 1024) })
            for (s in starts) assertArrayEquals(file.copyOfRange(s.toInt(), s.toInt() + 64 * 1024), readAt(reader, s, 64 * 1024))
            assertEquals(1, srv.requestCount)
            assertEquals(1, reader.batches)
            assertEquals(true, support.known(srv.url("/f").toString()))
        }
    }

    @Test fun aServerWithoutManyRangesGetsSingleReadsAndIsRemembered() {
        server(multi = false).use { srv ->
            val inner = HttpRangeReader(srv.url("/f").toString(), emptyMap(), OkHttpClient())
            val support = MultiRangeSupport(MemoryOf())
            val reader = BatchRangeReader(inner, support, budgetBytes = 8L * 1024 * 1024)
            val starts = (0 until 4).map { 100_000L + it * 500_000L }
            reader.plan(starts.map { BatchRangeReader.Planned(it, 64 * 1024) })
            for (s in starts) assertArrayEquals(file.copyOfRange(s.toInt(), s.toInt() + 64 * 1024), readAt(reader, s, 64 * 1024))
            assertEquals(1 + starts.size, srv.requestCount)
            assertEquals(false, support.known(srv.url("/f").toString()))
            assertTrue(reader.refused)
        }
    }

    @Test fun readsOutsideThePlanAreSingleReads() {
        server(multi = true).use { srv ->
            val inner = HttpRangeReader(srv.url("/f").toString(), emptyMap(), OkHttpClient())
            val reader = BatchRangeReader(inner, MultiRangeSupport(MemoryOf()), budgetBytes = 8L * 1024 * 1024)
            assertArrayEquals(file.copyOfRange(5000, 5100), readAt(reader, 5000, 100))
            assertEquals(1, srv.requestCount)
            assertEquals(0, reader.batches)
        }
    }

    @Test fun aSmallBudgetMeansSingleReads() {
        server(multi = true).use { srv ->
            val inner = HttpRangeReader(srv.url("/f").toString(), emptyMap(), OkHttpClient())
            val reader = BatchRangeReader(inner, MultiRangeSupport(MemoryOf()), budgetBytes = 100L * 1024)
            val starts = (0 until 4).map { 100_000L + it * 500_000L }
            reader.plan(starts.map { BatchRangeReader.Planned(it, 64 * 1024) })
            readAt(reader, starts[0], 64 * 1024)
            assertEquals(0, reader.batches)
        }
    }
}
