package com.nuvio.tv.ui.screens.player

import org.junit.Assert.*
import org.junit.Test

class PlaybackTransferSessionTest {
    private val url = "https://origin.example/movie.mkv"
    private fun owner(coverage: PlaybackTransferCoverage = PlaybackTransferCoverage.PROGRESSIVE) = PlaybackTransferSession(url, coverage)
    private fun PlaybackTransferSession.snap(time: Long = 0) = snapshot { time }

    @Test fun `same URL source replacement isolates late bytes endpoint and size`() {
        val old = owner(); val current = owner()
        old.recordBytes(100, true)
        old.recordEndpoint("https://old.example:8443/file")
        old.recordLength(url, 0, 900, true, true)
        assertNotEquals(old.id, current.id)
        assertEquals(0L, current.snap().networkBytes)
        assertNull(current.snap().endpoint)
        assertNull(current.snap().contentLength)
    }
    @Test fun `network and cache payload are distinct without overlap`() {
        val owner = owner()
        owner.recordBytes(100, true); owner.recordBytes(40, false)
        owner.recordBytes(-1, true); owner.recordBytes(0, true)
        val snap = owner.snap(99)
        assertEquals(100L, snap.networkBytes)
        assertEquals(140L, snap.readBytes)
        assertEquals(99L, snap.sampledAtMs)
    }
    @Test fun `concurrent callback snapshots remain internally consistent`() {
        val owner = owner()
        val threads = (1..4).map { Thread { repeat(5000) { owner.recordBytes(100, true) } }.apply { start() } }
        repeat(100) { val snap = owner.snap(); assertEquals(snap.networkBytes, snap.readBytes) }
        threads.forEach { it.join() }
        assertEquals(2_000_000L, owner.snap().networkBytes)
    }
    @Test fun `counters exceed signed int without overflow`() {
        val owner = owner(); repeat(3) { owner.recordBytes(Int.MAX_VALUE, true) }
        assertEquals(Int.MAX_VALUE.toLong() * 3, owner.snap().networkBytes)
    }
    @Test fun `unbounded selected resource records full length at resume offset`() {
        val owner = owner(); owner.recordLength(url, 100, 900, true, true)
        assertEquals(1000L, owner.snap().contentLength)
    }
    @Test fun `ancillary bounded encoded unknown and overflowing lengths cannot set file size`() {
        val owner = owner()
        owner.recordLength("https://origin.example/sub.srt", 0, 90000, true, true)
        owner.recordLength(url, 100, 200, false, true)
        owner.recordLength(url, 0, 200, true, false)
        owner.recordLength(url, 0, -1, true, true)
        owner.recordLength(url, Long.MAX_VALUE, 1, true, true)
        assertNull(owner.snap().contentLength)
    }
    @Test fun `unsupported route does not turn absent instrumentation into a measurement`() {
        val owner = owner(PlaybackTransferCoverage.UNAVAILABLE)
        owner.recordBytes(100, true); owner.recordEndpoint(url); owner.recordLength(url, 0, 100, true, true)
        assertFalse(owner.snap().coverage.available)
        assertNull(owner.snap().endpoint)
        assertNull(owner.snap().contentLength)
    }
    @Test fun `endpoint follows media redirects and retains port without path or credentials`() {
        val owner = owner(); owner.recordEndpoint("https://user:secret@cdn.example:8443/private?token=abc")
        assertEquals(PlaybackEndpoint("https", "cdn.example", 8443), owner.snap().endpoint)
        assertEquals("cdn.example:8443", owner.snap().endpoint?.display)
        owner.recordEndpoint("file:///cache/span")
        assertEquals(8443, owner.snap().endpoint?.port)
        owner.recordEndpoint("http://new.example/file")
        assertEquals(80, owner.snap().endpoint?.port)
    }
    @Test fun `unsupported endpoints cannot be pinged`() {
        for (url in listOf(null, "file:///cache", "https://", "https://host:99999/file", "garbage"))
            assertNull(PlaybackEndpoint.from(url))
        assertEquals(443, PlaybackEndpoint.from("https://cdn.example/file")?.port)
    }
    @Test fun `connect result rejects replacement redirect and expiry`() {
        val owner = owner(); owner.recordEndpoint(url)
        val snap = owner.snap(100)
        val result = PlaybackConnectSample(snap.sessionId, snap.endpoint!!, 22, 100)
        assertTrue(result.matches(owner.snap(500)))
        assertFalse(result.matches(owner.snap(10101)))
        assertFalse(result.matches(owner.snap(99)))
        val replacement = owner(); replacement.recordEndpoint(url)
        assertFalse(result.matches(replacement.snap(500)))
        owner.recordEndpoint("https://cdn.example:8443/file")
        assertFalse(result.matches(owner.snap(500)))
    }
}
