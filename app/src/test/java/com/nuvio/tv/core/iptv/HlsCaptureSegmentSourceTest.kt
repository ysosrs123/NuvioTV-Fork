package com.nuvio.tv.core.iptv

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HlsCaptureSegmentSourceTest {
    @get:Rule val temp = TemporaryFolder()
    private val address = URI("https://fixture.invalid/list.m3u8")
    private fun playlist(sequence: Long, segments: List<String>, ended: Boolean = false, discontinuity: Long = 0) =
        "#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:$sequence\n#EXT-X-DISCONTINUITY-SEQUENCE:$discontinuity\n" +
            segments.joinToString("") { "#EXTINF:1,\n$it\n" } + if (ended) "#EXT-X-ENDLIST\n" else ""
    private class Http(val playlists: List<String>) : HlsCaptureHttp {
        val opened = mutableListOf<String>(); var count = 0; var active = 0; var stopped = false
        override suspend fun open(address: URI, maxBytes: Long): InputStream {
            check(!stopped && active == 0); active++; opened += address.path
            val bytes = if (address.path.endsWith("m3u8")) playlists[minOf(count++, playlists.lastIndex)].toByteArray() else byteArrayOf(1, 2, 3, 4)
            return object : ByteArrayInputStream(bytes) {
                var closed = false
                override fun close() { if (!closed) { closed = true; active-- }; super.close() }
            }
        }
        override suspend fun close(): Boolean { stopped = true; return active == 0 }
    }
    private class Time { var now = 0L; val waits = mutableListOf<Long>(); suspend fun wait(ms: Long) { waits += ms; now += ms } }
    private fun source(http: HlsCaptureHttp, time: Time = Time(), timeout: Long = 10_000) = HlsCaptureSegmentSource(
        address, http, 4, stallTimeoutMs = timeout, monotonicMs = { time.now }, wait = { time.wait(it) })
    private suspend fun read(source: HlsCaptureSegmentSource): CaptureInput {
        val next = requireNotNull(source.next()); next.body.use { assertArrayEquals(byteArrayOf(1,2,3,4), it.readBytes()) }; return next
    }
    @Test fun completedPlaylistCapturesEachSegmentOnceThenEnds() = runBlocking {
        val http = Http(listOf(playlist(10, listOf("a.ts", "b.ts"), true)))
        val source = source(http)
        assertEquals(0L, read(source).startMs); assertEquals(2000L, read(source).endMs)
        assertNull(source.next()); assertTrue(source.close())
        assertEquals(listOf("/list.m3u8", "/a.ts", "/b.ts"), http.opened)
    }
    @Test fun slidingReloadKeepsOverlapAndWaitsWithoutDuplicateMedia() = runBlocking {
        val http = Http(listOf(playlist(10, listOf("a.ts", "b.ts")), playlist(11, listOf("b.ts", "c.ts"), true)))
        val time = Time(); val source = source(http, time)
        read(source); read(source); assertEquals(2000L, read(source).startMs)
        assertNull(source.next()); assertTrue(source.close()); assertEquals(listOf(1000L), time.waits)
        assertEquals(listOf("/list.m3u8", "/a.ts", "/b.ts", "/list.m3u8", "/c.ts"), http.opened)
    }
    @Test fun unchangedReloadUsesHalfTargetAndStallsAtBoundedDeadline() = runBlocking {
        val http = Http(listOf(playlist(0, listOf("a.ts"))))
        val time = Time(); val source = source(http, time, 2100)
        read(source)
        try { source.next(); fail() } catch (error: HlsCaptureException) { assertEquals(HlsCaptureFailure.STALLED, error.failure) }
        assertEquals(listOf(1000L, 500L, 500L), time.waits)
        assertEquals(1, http.opened.count { it.endsWith(".ts") }); assertTrue(source.close())
    }
    @Test fun expiredWindowStopsBeforeOpeningItsNewSegment() = runBlocking {
        val http = Http(listOf(playlist(0, listOf("a.ts")), playlist(2, listOf("c.ts"), true)))
        val source = source(http); read(source)
        try { source.next(); fail() } catch (error: HlsCaptureException) { assertEquals(HlsCaptureFailure.EXPIRED, error.failure) }
        assertFalse(http.opened.contains("/c.ts")); assertTrue(source.close())
    }
    @Test fun changedOverlapStopsBeforeReplacementMedia() = runBlocking {
        val http = Http(listOf(playlist(0, listOf("a.ts")), playlist(0, listOf("changed.ts", "b.ts"), true)))
        val source = source(http); read(source)
        try { source.next(); fail() } catch (error: HlsCaptureException) { assertEquals(HlsCaptureFailure.CHANGED_SEGMENT, error.failure) }
        assertFalse(http.opened.contains("/changed.ts")); assertTrue(source.close())
    }
    @Test fun previousBodyMustCloseAndOutstandingBodyPreventsClosureConfirmation() = runBlocking {
        val http = Http(listOf(playlist(0, listOf("a.ts", "b.ts"), true)))
        val source = source(http); val body = source.next()!!.body
        try { source.next(); fail() } catch (_: IllegalStateException) { }
        assertFalse(source.close()); body.close(); assertTrue(source.close())
        assertFalse(http.opened.contains("/b.ts"))
    }
    @Test fun unsupportedManifestDoesNotOpenAnyMediaOrRetry() = runBlocking {
        val http = Http(listOf(playlist(0, listOf("a.ts"), true).replace("#EXTINF", "#EXT-X-KEY:METHOD=AES-128\n#EXTINF")))
        val source = source(http)
        try { source.next(); fail() } catch (error: HlsCaptureException) { assertEquals(HlsCaptureFailure.UNSUPPORTED_PLAYLIST, error.failure) }
        try { source.next(); fail() } catch (error: HlsCaptureException) { assertEquals(HlsCaptureFailure.CLOSED, error.failure) }
        assertEquals(listOf("/list.m3u8"), http.opened); assertTrue(source.close())
    }
    @Test fun discontinuityMetadataSplitsStoredContiguousBounds() = runBlocking {
        val first = playlist(0, listOf("a.ts", "b.ts"), true).replace("#EXTINF:1,\nb.ts", "#EXT-X-DISCONTINUITY\n#EXTINF:1,\nb.ts")
        val http = Http(listOf(first)); val source = source(http)
        CaptureSegmentStore(temp.newFolder(), 8, 4).use { store ->
            repeat(2) { val next = source.next()!!; next.body.use { store.append(next.startMs, next.endMs, next.continuity, it) } }
            assertEquals(CaptureBounds(1000, 2000), store.contiguousBounds())
            assertNull(source.next()); assertTrue(source.close())
        }
    }
    @Test fun closingDuringPollCancelsWaitAndNeverReloads() = runBlocking {
        val http = Http(listOf(playlist(0, listOf("a.ts")))); val waiting = CompletableDeferred<Unit>()
        val source = HlsCaptureSegmentSource(address, http, 4, wait = { waiting.complete(Unit); awaitCancellation() })
        read(source)
        val pull = async { source.next() }; waiting.await()
        source.close(); pull.join(); assertTrue(pull.isCancelled); assertTrue(source.close())
        assertEquals(1, http.count)
    }
    @Test fun transportCloseDuringReloadWaitConfirmsAfterTheWorkerUnwinds() = runBlocking {
        val http = Http(listOf(playlist(0, listOf("a.ts")))); val waiting = CompletableDeferred<Unit>()
        val source = HlsCaptureSegmentSource(address, http, 4, wait = {
            waiting.complete(Unit)
            try { awaitCancellation() } finally { withContext(NonCancellable) { delay(200) } }
        })
        CaptureSegmentStore(temp.newFolder(), 12, 4).use { store ->
            val transport = SegmentCaptureTransport(store, source); transport.start()
            withTimeout(5000) { waiting.await() }
            assertEquals(1, store.snapshot().size)
            assertTrue(transport.close()); assertEquals(CaptureTransportState.CLOSED, transport.state.value)
            assertEquals(1, http.count); assertTrue(http.stopped); assertEquals(0, http.active)
        }
    }
    @Test fun transportCloseRetriesAFailedSegmentBodyCloseBeforeConfirming() = runBlocking {
        var attempts = 0; var open = 0; var stopped = false
        val http = object : HlsCaptureHttp {
            override suspend fun open(address: URI, maxBytes: Long): InputStream {
                open++
                if (address.path.endsWith("m3u8")) return object : ByteArrayInputStream(playlist(0, listOf("a.ts"), true).toByteArray()) {
                    override fun close() { open--; super.close() }
                }
                return object : ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)) {
                    override fun close() { if (++attempts == 1) throw IOException("close failed"); open--; super.close() }
                }
            }
            override suspend fun close(): Boolean { stopped = true; return open == 0 }
        }
        val source = HlsCaptureSegmentSource(address, http, 4)
        CaptureSegmentStore(temp.newFolder(), 12, 4).use { store ->
            val transport = SegmentCaptureTransport(store, source); transport.start()
            withTimeout(5000) { transport.state.first { it == CaptureTransportState.FAILED } }
            assertTrue(transport.close()); assertEquals(2, attempts); assertEquals(0, open); assertTrue(stopped)
        }
    }
    @Test fun lateMediaAfterCancellationClosesWithoutReturningIt() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var active = 0
        val http = object : HlsCaptureHttp {
            override suspend fun open(address: URI, maxBytes: Long): InputStream {
                if (address.path.endsWith("m3u8")) return ByteArrayInputStream(playlist(0, listOf("a.ts"), true).toByteArray())
                entered.complete(Unit); withContext(NonCancellable) { release.await() }; active++
                return object : ByteArrayInputStream(ByteArray(4)) { override fun close() { active--; super.close() } }
            }
            override suspend fun close(): Boolean { release.complete(Unit); return active == 0 }
        }
        val source = source(http); val pull = async { source.next() }; entered.await()
        source.close(); pull.join(); assertTrue(pull.isCancelled); assertEquals(0, active); assertTrue(source.close())
    }
    @Test fun failedBodyCloseRetainsHandleUntilExplicitRetry() = runBlocking {
        var allowClose = false; var active = 0
        val http = object : HlsCaptureHttp {
            override suspend fun open(address: URI, maxBytes: Long): InputStream {
                if (address.path.endsWith("m3u8")) return ByteArrayInputStream(playlist(0, listOf("a.ts"), true).toByteArray())
                active++
                return object : ByteArrayInputStream(ByteArray(4)) { override fun close() {
                    if (!allowClose) throw IOException("hidden"); active--; super.close()
                } }
            }
            override suspend fun close() = active == 0
        }
        val source = source(http); val body = source.next()!!.body
        try { body.close(); fail() } catch (_: IOException) { }
        assertFalse(source.close()); allowClose = true; body.close(); assertTrue(source.close())
    }
    @Test fun failedManifestBodyCloseIsAlsoRetainedForSourceCleanup() = runBlocking {
        var allowClose = false; var active = 0
        val http = object : HlsCaptureHttp {
            override suspend fun open(address: URI, maxBytes: Long): InputStream {
                active++
                return object : ByteArrayInputStream(playlist(0, listOf("a.ts"), true).toByteArray()) {
                    override fun close() { if (!allowClose) throw IOException("hidden"); active--; super.close() }
                }
            }
            override suspend fun close() = active == 0
        }
        val source = source(http)
        try { source.next(); fail() } catch (error: HlsCaptureException) { assertEquals(HlsCaptureFailure.NETWORK, error.failure) }
        assertFalse(source.close()); assertEquals(1, active)
        allowClose = true; assertTrue(source.close()); assertEquals(0, active)
    }
    @Test fun invalidInitialAddressIsRejectedWithoutOpeningAnything() {
        val http = Http(emptyList())
        for (uri in listOf("file:///tmp/list", "https://user:pass@fixture.invalid/list", "https://fixture.invalid/list#fragment")) {
            try { HlsCaptureSegmentSource(URI(uri), http, 4); fail() }
            catch (error: HlsCaptureException) { assertEquals(HlsCaptureFailure.ADDRESS, error.failure) }
        }
        assertTrue(http.opened.isEmpty())
    }
}
