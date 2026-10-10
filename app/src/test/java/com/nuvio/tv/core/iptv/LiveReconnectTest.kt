package com.nuvio.tv.core.iptv

import org.junit.Assert.*
import org.junit.Test

class LiveReconnectTest {
    @Test fun backoffStartsImmediatelyAndLevelsOff() {
        assertEquals(listOf(0L, 250L, 1_000L, 2_000L, 2_000L, 2_000L), (0..5).map(LiveReconnect::delay))
    }

    @Test fun windowFollowsTheBufferUpToFifteenSeconds() {
        assertEquals(0L, LiveReconnect.window(300))
        assertEquals(4_250L, LiveReconnect.window(5_000))
        assertEquals(15_000L, LiveReconnect.window(60_000))
    }

    @Test fun attemptsStopWhenTheBufferWouldRunOut() {
        assertEquals(0L, LiveReconnect.next(0, 0, 0))
        assertNull(LiveReconnect.next(1, 0, 0))
        assertEquals(250L, LiveReconnect.next(1, 100, 2_000))
        assertEquals(1_000L, LiveReconnect.next(2, 500, 2_000))
        assertNull(LiveReconnect.next(3, 1_600, 2_000))
        assertNull(LiveReconnect.next(6, 14_000, 15_000))
    }

    @Test fun providerRefusalsAreHandedOver() {
        for (status in listOf(401, 403, 404, 429, 503, 509)) assertTrue("$status", LiveReconnect.handOver(status))
        for (status in listOf(500, 502, 504)) assertFalse("$status", LiveReconnect.handOver(status))
    }

    @Test fun syncNeedsThreeAlignedPackets() {
        val bytes = ByteArray(1_000)
        bytes[10] = 0x47; bytes[100] = 0x47; bytes[288] = 0x47; bytes[476] = 0x47; bytes[664] = 0x47
        assertEquals(100, LiveTsSync.find(bytes, 0, bytes.size))
        assertEquals(-1, LiveTsSync.find(bytes, 0, 476))
        assertEquals(100, LiveTsSync.find(bytes, 0, 477))
        assertEquals(288, LiveTsSync.find(bytes, 101, bytes.size))
    }

    @Test fun readyBytesNeverSplitAPacketAcrossAJoin() {
        assertEquals(0, LiveTsSync.ready(187, 0))
        assertEquals(188, LiveTsSync.ready(200, 0))
        assertEquals(88, LiveTsSync.ready(88, 100))
        assertEquals(88, LiveTsSync.ready(200, 100))
        assertEquals(276, LiveTsSync.ready(276, 100))
        assertEquals(0, LiveTsSync.ready(0, 0))
    }

    @Test fun altSvcRecognisesHttp3Only() {
        assertTrue(LiveAltSvc.advertisesH3("h3=\":443\"; ma=86400"))
        assertTrue(LiveAltSvc.advertisesH3("h2=\":443\", H3-29=\":443\""))
        assertFalse(LiveAltSvc.advertisesH3("h2=\":443\"; ma=60"))
        assertFalse(LiveAltSvc.advertisesH3("clear"))
        assertFalse(LiveAltSvc.advertisesH3(null))
    }

    @Test fun userAgentChoicesResolve() {
        assertNull(LiveUserAgent.resolve(null))
        assertNull(LiveUserAgent.resolve(LiveUserAgent.DEFAULT))
        assertNull(LiveUserAgent.resolve("unknown"))
        assertEquals("okhttp/4.12.0", LiveUserAgent.resolve("okhttp"))
        assertNull(LiveUserAgent.resolve("retired"))
        val custom = LiveUserAgent.custom("  MyBox/1.0  ")
        assertEquals("custom:MyBox/1.0", custom)
        assertEquals(LiveUserAgent.CUSTOM, LiveUserAgent.kind(custom))
        assertEquals("MyBox/1.0", LiveUserAgent.resolve(custom))
        assertNull(LiveUserAgent.custom(" "))
        assertNull(LiveUserAgent.custom("bad\nvalue"))
        assertNull(LiveUserAgent.custom("x".repeat(201)))
        assertEquals("channel", LiveUserAgent.pick("channel", "source", "app"))
        assertEquals("source", LiveUserAgent.pick(null, "source", "app"))
        assertEquals("app", LiveUserAgent.pick(null, null, "app"))
    }
}
