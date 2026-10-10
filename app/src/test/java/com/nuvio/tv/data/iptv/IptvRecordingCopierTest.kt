package com.nuvio.tv.data.iptv

import com.nuvio.tv.core.iptv.RecordingFailure
import com.nuvio.tv.core.iptv.RecordingStorage
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.Protocol
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class IptvRecordingCopierTest {
    @get:Rule val temp = TemporaryFolder()

    private fun packets(marker: Int, count: Int = 4) = ByteArray(188 * count) { if (it % 188 == 0) 0x47 else marker.toByte() }
    private fun copier(free: Long = Long.MAX_VALUE) = IptvRecordingCopier(freeBytes = { free }, pause = { }, minimumStallMillis = 2_000)
    private fun output() = File(temp.root, "out.ts.part")
    private fun server(routes: (RecordedRequest) -> MockResponse) = MockWebServer().apply {
        dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = routes(request) }
        start()
    }
    private suspend fun <T> logged(block: suspend () -> T): Pair<T, List<String>> {
        val messages = mutableListOf<String>()
        val handler = object : Handler() {
            override fun publish(record: LogRecord) { synchronized(messages) { messages += record.message } }
            override fun flush() { }
            override fun close() { }
        }
        val logger = Logger.getLogger("NuvioIptv").apply { addHandler(handler) }
        return try { block() to synchronized(messages) { messages.toList() } } finally { logger.removeHandler(handler) }
    }
    private fun ts(bytes: ByteArray) = MockResponse().setHeader("Content-Type", "video/mp2t").setBody(Buffer().write(bytes))
    private fun playlist(text: String) = MockResponse().setHeader("Content-Type", "application/vnd.apple.mpegurl").setBody(text)
    private fun media(first: Long, count: Int, ended: Boolean = false) = buildString {
        append("#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:$first\n")
        repeat(count) { append("#EXTINF:1.0,\nseg${first + it}.ts\n") }
        if (ended) append("#EXT-X-ENDLIST\n")
    }

    @Test fun transportStreamIsCopiedUntilTheStopTime() = runBlocking {
        val body = packets(1, 400)
        val server = server { ts(body).throttleBody(188L * 20, 100, TimeUnit.MILLISECONDS) }
        server.use {
            val started = System.currentTimeMillis()
            val result = copier().copy(server.url("/live/1.ts").toString(), IptvStreamFormat.AUTO, output(), started + 1_200)
            assertNull(result.failure)
            assertEquals(0, result.gaps)
            assertTrue(result.bytes > 0)
            assertEquals(result.bytes, output().length())
            assertTrue(System.currentTimeMillis() - started < 5_000)
            assertEquals(body.copyOf(result.bytes.toInt()).toList(), output().readBytes().toList())
        }
    }

    @Test fun droppedConnectionReconnectsKeepsDataAndCountsGap() = runBlocking {
        val calls = AtomicInteger()
        val server = server { if (calls.getAndIncrement() == 0) ts(packets(1)) else ts(packets(2, 400)).throttleBody(188L * 20, 100, TimeUnit.MILLISECONDS) }
        server.use {
            val result = copier().copy(server.url("/live/1.ts").toString(), IptvStreamFormat.MPEG_TS, output(), System.currentTimeMillis() + 1_000)
            assertNull(result.failure)
            assertEquals(1, result.gaps)
            val bytes = output().readBytes()
            assertEquals(packets(1).toList(), bytes.copyOf(188 * 4).toList())
            assertTrue(bytes.size > 188 * 4)
            assertEquals(2.toByte(), bytes[188 * 4 + 1])
        }
    }

    @Test fun streamResetMidBodyReconnectsAndCountsGap() = runBlocking {
        val calls = AtomicInteger()
        val server = server {
            if (calls.getAndIncrement() == 0) ts(packets(1, 400)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
            else ts(packets(2, 400)).throttleBody(188L * 20, 100, TimeUnit.MILLISECONDS)
        }
        server.protocols = listOf(Protocol.H2_PRIOR_KNOWLEDGE)
        server.use {
            val client = IptvRecordingCopier.newClient().newBuilder().protocols(listOf(Protocol.H2_PRIOR_KNOWLEDGE)).build()
            val (result, logged) = logged {
                IptvRecordingCopier(client, freeBytes = { Long.MAX_VALUE }, pause = { }, minimumStallMillis = 2_000)
                    .copy(server.url("/live/1.ts").toString(), IptvStreamFormat.MPEG_TS, output(), System.currentTimeMillis() + 1_500)
            }
            assertNull(result.failure)
            assertEquals(1, result.gaps)
            assertTrue(server.requestCount >= 2)
            val bytes = output().readBytes()
            assertEquals(1.toByte(), bytes[1])
            assertEquals(2.toByte(), bytes.last())
            assertEquals(1, logged.count { it.startsWith("recording connection StreamResetException") })
        }
    }

    @Test fun leadingBytesBeforeSyncAreSkipped() = runBlocking {
        val server = server { ts(byteArrayOf(1, 2, 3) + packets(5, 400)).throttleBody(188L * 20, 100, TimeUnit.MILLISECONDS) }
        server.use {
            val result = copier().copy(server.url("/a").toString(), IptvStreamFormat.AUTO, output(), System.currentTimeMillis() + 500)
            assertNull(result.failure)
            assertEquals(0x47.toByte(), output().readBytes()[0])
        }
    }

    @Test fun endedHlsPlaylistAppendsSegmentsInOrderThroughVariant() = runBlocking {
        val server = server { request ->
            when (request.path) {
                "/master.m3u8" -> playlist("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=100\nlow/index.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=900\nhigh/index.m3u8\n")
                "/high/index.m3u8" -> playlist(media(7, 3, ended = true))
                "/high/seg7.ts" -> ts(packets(7))
                "/high/seg8.ts" -> ts(packets(8))
                "/high/seg9.ts" -> ts(packets(9))
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.use {
            val started = System.currentTimeMillis()
            val result = copier().copy(server.url("/master.m3u8").toString(), IptvStreamFormat.AUTO, output(), started + 30_000)
            assertNull(result.failure)
            assertEquals(0, result.gaps)
            assertTrue(System.currentTimeMillis() - started < 10_000)
            assertEquals((packets(7) + packets(8) + packets(9)).toList(), output().readBytes().toList())
        }
    }

    @Test fun liveHlsReloadsWithoutRepeatsAndNotesMissedSegments() = runBlocking {
        val reloads = AtomicInteger()
        val server = server { request ->
            val path = request.path.orEmpty()
            when {
                path == "/live.m3u8" -> playlist(when (reloads.getAndIncrement()) {
                    0 -> media(0, 3)
                    1 -> media(1, 4)
                    else -> media(6, 2, ended = true)
                })
                path.startsWith("/seg") -> ts(packets(path.removePrefix("/seg").removeSuffix(".ts").toInt()))
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.use {
            val result = copier().copy(server.url("/live.m3u8").toString(), IptvStreamFormat.HLS, output(), System.currentTimeMillis() + 30_000)
            assertNull(result.failure)
            assertEquals(1, result.gaps)
            val expected = listOf(0, 1, 2, 3, 4, 6, 7).flatMap { packets(it).toList() }
            assertEquals(expected, output().readBytes().toList())
        }
    }

    @Test fun encryptedFragmentedAndUnknownStreamsFailWithoutData() = runBlocking {
        val server = server { request ->
            when (request.path) {
                "/key.m3u8" -> playlist("#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-KEY:METHOD=AES-128,URI=\"k\"\n#EXTINF:2,\na.ts\n")
                "/fmp4.m3u8" -> playlist("#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:2,\na.m4s\n")
                "/audio.m3u8" -> playlist(media(0, 1))
                "/seg0.ts" -> MockResponse().setBody("ID3 tag then audio")
                else -> MockResponse().setHeader("Content-Type", "text/html").setBody("<html>no</html>")
            }
        }
        server.use {
            val now = System.currentTimeMillis() + 10_000
            assertEquals(IptvRecordingCopy(0, 0, RecordingFailure.ENCRYPTED_STREAM), copier().copy(server.url("/key.m3u8").toString(), IptvStreamFormat.AUTO, output(), now))
            assertEquals(IptvRecordingCopy(0, 0, RecordingFailure.UNSUPPORTED_STREAM), copier().copy(server.url("/fmp4.m3u8").toString(), IptvStreamFormat.AUTO, output(), now))
            assertEquals(IptvRecordingCopy(0, 0, RecordingFailure.UNSUPPORTED_STREAM), copier().copy(server.url("/audio.m3u8").toString(), IptvStreamFormat.AUTO, output(), now))
            assertEquals(IptvRecordingCopy(0, 0, RecordingFailure.UNSUPPORTED_STREAM), copier().copy(server.url("/page").toString(), IptvStreamFormat.AUTO, output(), now))
            assertEquals(0L, output().length())
        }
    }

    @Test fun lowStorageStopsBeforeWriting() = runBlocking {
        val server = server { ts(packets(1, 400)) }
        server.use {
            val result = copier(free = 1024).copy(server.url("/a.ts").toString(), IptvStreamFormat.AUTO, output(), System.currentTimeMillis() + 5_000)
            assertEquals(IptvRecordingCopy(0, 0, RecordingFailure.LOW_STORAGE), result)
        }
    }

    @Test fun haltStopsTheCopyAtTheNextSpaceCheck() = runBlocking {
        val server = server { ts(packets(1, 50_000)) }
        server.use {
            var checks = 0
            val result = IptvRecordingCopier(freeBytes = { Long.MAX_VALUE }, pause = { }, minimumStallMillis = 2_000,
                halt = { if (++checks > 1) RecordingFailure.SHARE_FULL else null })
                .copy(server.url("/a.ts").toString(), IptvStreamFormat.AUTO, output(), System.currentTimeMillis() + 30_000)
            assertEquals(RecordingFailure.SHARE_FULL, result.failure)
            assertTrue(result.bytes >= RecordingStorage.CHECK_INTERVAL_BYTES)
            assertEquals(result.bytes, output().length())
            assertEquals(2, checks)
        }
    }

    @Test fun unreachableStreamGivesUpAfterBoundedAttempts() = runBlocking {
        val server = server { MockResponse().setResponseCode(403) }
        server.use {
            val result = copier().copy(server.url("/a.ts").toString(), IptvStreamFormat.AUTO, output(), System.currentTimeMillis() + 60_000)
            assertEquals(IptvRecordingCopy(0, 0, RecordingFailure.NETWORK), result)
            assertEquals(7, server.requestCount)
        }
        assertEquals(RecordingFailure.SOURCE_UNAVAILABLE, copier().copy("not a url", IptvStreamFormat.AUTO, output(), Long.MAX_VALUE).failure)
        assertEquals(RecordingFailure.SOURCE_UNAVAILABLE, copier().copy("http://u:p@a.invalid/x", IptvStreamFormat.AUTO, output(), Long.MAX_VALUE).failure)
    }

    @Test fun cancellationKeepsWrittenBytes() = runBlocking {
        val server = server { ts(packets(3, 4000)).throttleBody(188L * 20, 50, TimeUnit.MILLISECONDS) }
        server.use {
            val progress = IptvRecordingProgress()
            val job = async(Dispatchers.Default) {
                copier().copy(server.url("/a.ts").toString(), IptvStreamFormat.AUTO, output(), System.currentTimeMillis() + 60_000, progress)
            }
            while (progress.bytes == 0L) delay(10)
            val started = System.currentTimeMillis()
            val (_, logged) = logged {
                job.cancel()
                try { job.await(); fail() } catch (_: CancellationException) { }
            }
            assertTrue(logged.none { it.startsWith("recording connection") })
            assertTrue(System.currentTimeMillis() - started < 5_000)
            assertTrue(output().length() > 0)
            assertEquals(progress.bytes, output().length())
        }
    }
}
