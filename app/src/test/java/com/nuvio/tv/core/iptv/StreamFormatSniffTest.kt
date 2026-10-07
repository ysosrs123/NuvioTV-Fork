package com.nuvio.tv.core.iptv

import org.junit.Assert.*
import org.junit.Test

class StreamFormatSniffTest {
    private fun ts(packets: Int, lead: Int = 0) = ByteArray(lead + packets * 188).also { b -> repeat(packets) { b[lead + it * 188] = 0x47 } }

    @Test fun urlExtensionsAndQueryHints() {
        assertEquals(SniffedFormat.HLS, StreamFormatSniff.fromUrl("http://a.invalid/live/one.m3u8?token=x"))
        assertEquals(SniffedFormat.MPEG_TS, StreamFormatSniff.fromUrl("http://a.invalid/live/u/p/42.ts"))
        assertEquals(SniffedFormat.HLS, StreamFormatSniff.fromUrl("http://a.invalid/get.php?stream=4&output=m3u8"))
        assertEquals(SniffedFormat.MPEG_TS, StreamFormatSniff.fromUrl("http://a.invalid/play?extension=ts"))
        assertNull(StreamFormatSniff.fromUrl("http://a.invalid/live/u/p/42"))
        assertNull(StreamFormatSniff.fromUrl("http://a.invalid/dir.ts/stream"))
        assertNull(StreamFormatSniff.fromUrl("not a url"))
    }

    @Test fun contentTypes() {
        assertEquals(SniffedFormat.HLS, StreamFormatSniff.fromContentType("application/vnd.apple.mpegurl; charset=utf-8"))
        assertEquals(SniffedFormat.HLS, StreamFormatSniff.fromContentType("Application/X-MpegURL"))
        assertEquals(SniffedFormat.MPEG_TS, StreamFormatSniff.fromContentType("video/MP2T"))
        assertNull(StreamFormatSniff.fromContentType("application/octet-stream"))
        assertNull(StreamFormatSniff.fromContentType(null))
    }

    @Test fun bodyBytes() {
        assertEquals(SniffedFormat.HLS, StreamFormatSniff.fromBytes("﻿\n#EXTM3U\n#EXT-X-VERSION:3".toByteArray()))
        assertEquals(SniffedFormat.MPEG_TS, StreamFormatSniff.fromBytes(ts(5)))
        assertEquals(SniffedFormat.MPEG_TS, StreamFormatSniff.fromBytes(ts(4, lead = 17)))
        assertEquals(SniffedFormat.MPEG_TS, StreamFormatSniff.fromBytes(ts(2)))
        assertNull(StreamFormatSniff.fromBytes("<html>login</html>".toByteArray()))
        assertNull(StreamFormatSniff.fromBytes(ByteArray(1024)))
        assertNull(StreamFormatSniff.fromBytes(ByteArray(0)))
        val broken = ts(5).also { it[188] = 0 }
        assertNull(StreamFormatSniff.fromBytes(broken))
    }

    @Test fun decisionPrefersBodyThenTypeThenFinalAddress() {
        assertEquals(SniffedFormat.MPEG_TS, StreamFormatSniff.decide(ts(5), 940, "application/x-mpegurl", "http://a.invalid/x.m3u8"))
        assertEquals(SniffedFormat.HLS, StreamFormatSniff.decide(ByteArray(0), 0, "application/x-mpegurl", "http://a.invalid/x"))
        assertEquals(SniffedFormat.HLS, StreamFormatSniff.decide(ByteArray(0), 0, "text/plain", "http://cdn.invalid/x/index.m3u8"))
        assertNull(StreamFormatSniff.decide(ByteArray(10), 10, "text/plain", "http://cdn.invalid/x"))
    }

    @Test fun hostKeyIsHashedAndStable() {
        val key = HostKey.of("http://User:Pw@Example.invalid/live/u/p/1")
        assertEquals(key, HostKey.of("http://example.invalid:80/other"))
        assertNotEquals(key, HostKey.of("https://example.invalid/other"))
        assertNotEquals(key, HostKey.of("http://example.invalid:8080/other"))
        assertEquals(16, key!!.length)
        assertFalse(key.contains("example"))
        assertNull(HostKey.of("example.invalid:8080"))
    }
}
