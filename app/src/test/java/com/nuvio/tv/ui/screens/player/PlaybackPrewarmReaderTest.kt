package com.nuvio.tv.ui.screens.player

import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class PlaybackPrewarmReaderTest {
    private class TrackedBody(
        private val available: Long,
        private val declared: Long = -1,
        private val fail: Boolean = false
    ) : ResponseBody() {
        var readBytes = 0L
        var closed = false
        var onClose: () -> Unit = {}
        private val input = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (fail) throw IOException("interrupted body")
                if (readBytes == available) return -1
                val count = minOf(byteCount, available - readBytes).toInt()
                sink.write(ByteArray(count) { 7 })
                readBytes += count
                return count.toLong()
            }
            override fun timeout() = Timeout.NONE
            override fun close() { onClose(); closed = true }
        }.buffer()
        override fun contentType(): MediaType? = null
        override fun contentLength() = declared
        override fun source(): BufferedSource = input
    }

    private fun response(body: ResponseBody, range: String? = "bytes 0-7/100", code: Int = 206,
                         encoding: String? = null): Response = Response.Builder()
        .request(Request.Builder().url("https://example.invalid/media").build())
        .protocol(Protocol.HTTP_1_1).code(code).message("test").body(body)
        .apply {
            range?.let { header("Content-Range", it) }
            encoding?.let { header("Content-Encoding", it) }
        }.build()

    @Test fun `valid head retains exact bytes and returns connection without cancelling`() {
        val body = TrackedBody(8, 8)
        val result = PlaybackPrewarmReader.read(response(body), 8, start = 0) { fail("cancelled") }!!
        assertEquals(0L, result.start)
        assertEquals(100L, result.total)
        assertArrayEquals(ByteArray(8) { 7 }, result.bytes)
        assertTrue(body.closed)
    }

    @Test fun `suffix and explicit tail validate both offsets and total`() {
        for (start in listOf(null, 92L)) {
            val result = PlaybackPrewarmReader.read(
                response(TrackedBody(8), "bytes 92-99/100"), 8, start, 100
            ) { fail("cancelled") }!!
            assertEquals(92L, result.start)
        }
    }

    @Test fun `small files may return shorter head or suffix at EOF`() {
        for (start in listOf(null, 0L)) {
            val result = PlaybackPrewarmReader.read(
                response(TrackedBody(3), "bytes 0-2/3"), 8, start
            ) { fail("cancelled") }!!
            assertEquals(3, result.bytes.size)
        }
    }

    @Test fun `ignored ranges and HTTP failures are cancelled before close without reading`() {
        for (code in listOf(200, 302, 401, 403, 416, 429, 503)) {
            val body = TrackedBody(Long.MAX_VALUE)
            var cancelled = false
            body.onClose = { assertTrue("cancel before close for $code", cancelled) }
            assertNull(PlaybackPrewarmReader.read(response(body, code = code), 8, 0) { cancelled = true })
            assertEquals(0L, body.readBytes)
            assertTrue(body.closed)
        }
    }

    @Test fun `malformed oversized mismatched or opaque ranges are rejected before reading`() {
        for (range in listOf(null, "bytes 0-7/*", "bytes */100", "items 0-7/100",
            "bytes 0-99/100", "bytes 1-8/100", "bytes 0-6/100", "bytes 8-0/100",
            "bytes 0-7/7", "bytes 0-7/0", "bytes -1-6/100",
            "bytes 0-7/999999999999999999999999", "bytes 0-7/100, 8-15/100")) {
            val body = TrackedBody(Long.MAX_VALUE)
            var cancelled = false
            assertNull(range, PlaybackPrewarmReader.read(response(body, range), 8, 0) { cancelled = true })
            assertTrue(cancelled)
            assertEquals(0L, body.readBytes)
        }
    }

    @Test fun `suffix must end at EOF and match requested width`() {
        for (range in listOf("bytes 91-98/100", "bytes 93-99/100", "bytes 0-7/100")) {
            val body = TrackedBody(8)
            assertNull(PlaybackPrewarmReader.read(response(body, range), 8) {})
            assertEquals(0L, body.readBytes)
        }
    }

    @Test fun `changed total from head is rejected for explicit tail`() {
        val body = TrackedBody(8)
        assertNull(PlaybackPrewarmReader.read(response(body, "bytes 92-99/101"), 8, 92, 100) {})
        assertEquals(0L, body.readBytes)
    }

    @Test fun `huge offsets do not overflow`() {
        val result = PlaybackPrewarmReader.read(
            response(TrackedBody(3), "bytes 9223372036854775804-9223372036854775806/9223372036854775807"),
            8, 9223372036854775804L
        ) { fail("cancelled") }!!
        assertEquals(3, result.bytes.size)
    }

    @Test fun `content length mismatch is rejected before reading`() {
        for (declared in listOf(0L, 7L, 9L, Long.MAX_VALUE)) {
            val body = TrackedBody(8, declared)
            assertNull(PlaybackPrewarmReader.read(response(body), 8, 0) {})
            assertEquals(0L, body.readBytes)
        }
    }

    @Test fun `encoded representation is rejected before reading`() {
        val body = TrackedBody(8)
        assertNull(PlaybackPrewarmReader.read(response(body, encoding = "gzip"), 8, 0) {})
        assertEquals(0L, body.readBytes)
    }

    @Test fun `truncated body never produces a cache window`() {
        val body = TrackedBody(7)
        var cancelled = false
        assertNull(PlaybackPrewarmReader.read(response(body), 8, 0) { cancelled = true })
        assertTrue(cancelled)
        assertTrue(body.closed)
    }

    @Test fun `unbounded unknown length body reads only window plus bounded transport lookahead`() {
        val limit = 262144
        val body = TrackedBody(Long.MAX_VALUE)
        var cancelled = false
        assertNull(PlaybackPrewarmReader.read(response(body, "bytes 0-262143/999999999"), limit, 0) {
            cancelled = true
        })
        assertTrue(cancelled)
        assertTrue(body.closed)
        assertTrue("read ${body.readBytes}", body.readBytes <= limit + 8192L)
    }

    @Test fun `interleaved ICY metadata cannot be adopted as file bytes`() {
        val body = TrackedBody(8)
        val response = response(body).newBuilder().header("icy-metaint", "8192").build()
        var cancelled = false
        assertNull(PlaybackPrewarmReader.read(response, 8, 0) { cancelled = true })
        assertTrue(cancelled)
        assertEquals(0L, body.readBytes)
        assertTrue(body.closed)
    }

    @Test fun `read failure cancels and closes the response`() {
        val body = TrackedBody(8, fail = true)
        var cancelled = false
        assertNull(PlaybackPrewarmReader.read(response(body), 8, 0) { cancelled = true })
        assertTrue(cancelled)
        assertTrue(body.closed)
    }
    @Test fun `rejection reasons identify header geometry and body failures`() {
        fun check(response: Response, expected: PlaybackPrewarmReader.Rejection) {
            val reasons = mutableListOf<PlaybackPrewarmReader.Rejection>()
            assertNull(PlaybackPrewarmReader.read(response, 8, 0, onRejected = reasons::add) {})
            assertEquals(listOf(expected), reasons)
        }
        check(response(TrackedBody(8), code = 429), PlaybackPrewarmReader.Rejection.HTTP_STATUS)
        check(response(TrackedBody(8), encoding = "gzip"), PlaybackPrewarmReader.Rejection.CONTENT_ENCODING)
        check(response(TrackedBody(8), range = null), PlaybackPrewarmReader.Rejection.CONTENT_RANGE)
        check(response(TrackedBody(8), range = "bytes 1-8/100"), PlaybackPrewarmReader.Rejection.RANGE_START)
        check(response(TrackedBody(8), range = "bytes 0-6/100"), PlaybackPrewarmReader.Rejection.RANGE_LENGTH)
        check(response(TrackedBody(8, 9)), PlaybackPrewarmReader.Rejection.CONTENT_LENGTH)
        check(response(TrackedBody(7)), PlaybackPrewarmReader.Rejection.SHORT_BODY)
        check(response(TrackedBody(9)), PlaybackPrewarmReader.Rejection.OVERLONG_BODY)
        check(response(TrackedBody(8, fail = true)), PlaybackPrewarmReader.Rejection.IO_FAILURE)
    }

    @Test fun `broken rejection observer cannot skip cancel before close`() {
        val body = TrackedBody(Long.MAX_VALUE)
        var cancelled = false
        body.onClose = { assertTrue(cancelled) }
        assertNull(PlaybackPrewarmReader.read(response(body, code = 200), 8, 0,
            onRejected = { error("observer") }) { cancelled = true })
        assertTrue(body.closed)
        assertEquals(0L, body.readBytes)
    }


    private fun rejectHeaders(headers: List<Pair<String, String>>, expected: PlaybackPrewarmReader.Rejection) {
        val body = TrackedBody(8, 8)
        var cancelled = false
        body.onClose = { assertTrue("cancel before close", cancelled) }
        val response = response(body).newBuilder().apply {
            removeHeader("Content-Range")
            headers.forEach { (name, value) -> addHeader(name, value) }
        }.build()
        val reasons = mutableListOf<PlaybackPrewarmReader.Rejection>()
        assertNull(PlaybackPrewarmReader.read(response, 8, 0, onRejected = reasons::add) { cancelled = true })
        assertTrue(cancelled)
        assertTrue(body.closed)
        assertEquals(0L, body.readBytes)
        assertEquals(listOf(expected), reasons)
    }

    @Test fun `every encoding field is checked before payload read`() {
        for (encodings in listOf(listOf("gzip", "identity"), listOf("identity", "gzip"),
            listOf("br", "IDENTITY"), listOf("", "identity"), listOf("gzip, identity"))) {
            rejectHeaders(listOf("Content-Range" to "bytes 0-7/100") +
                encodings.map { "Content-Encoding" to it }, PlaybackPrewarmReader.Rejection.CONTENT_ENCODING)
        }
    }

    @Test fun `repeated identity encoding fields retain valid payload`() {
        val body = TrackedBody(8, 8)
        val response = response(body).newBuilder().addHeader("Content-Encoding", "identity")
            .addHeader("content-encoding", " IDENTITY ").build()
        assertNotNull(PlaybackPrewarmReader.read(response, 8, 0) { fail("valid identity cancelled") })
        assertTrue(body.closed)
    }

    @Test fun `range geometry must come from exactly one header`() {
        for (ranges in listOf(listOf("bytes 1-8/100", "bytes 0-7/100"),
            listOf("bytes 0-7/100", "bytes 1-8/100"), listOf("bytes 0-7/100", "bytes 0-7/100"))) {
            rejectHeaders(ranges.map { "Content-Range" to it }, PlaybackPrewarmReader.Rejection.CONTENT_RANGE)
        }
    }

    @Test fun `ambiguous or malformed length fields are rejected before payload read`() {
        for (lengths in listOf(listOf("9", "8"), listOf("8", "9"), listOf("8", "8"),
            listOf("8, 8"), listOf("+8"), listOf(""), listOf("9"), listOf("999999999999999999999999"))) {
            rejectHeaders(listOf("Content-Range" to "bytes 0-7/100") +
                lengths.map { "Content-Length" to it }, PlaybackPrewarmReader.Rejection.CONTENT_LENGTH)
        }
    }

    @Test fun `single explicit content length remains valid`() {
        val body = TrackedBody(8, 8)
        val response = response(body).newBuilder().header("Content-Length", " 8 ").build()
        assertNotNull(PlaybackPrewarmReader.read(response, 8, 0) { fail("valid length cancelled") })
        assertTrue(body.closed)
    }

}
