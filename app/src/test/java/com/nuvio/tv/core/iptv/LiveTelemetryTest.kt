package com.nuvio.tv.core.iptv
import org.junit.Assert.*
import org.junit.Test
class LiveTelemetryTest {
    @Test fun preparationIsNotAStallAndFirstFrameIsMeasuredOnce() {
        val t = LiveTelemetry(); t.start(100); t.buffering(true, 110); t.buffering(false, 200)
        t.firstFrame(300); t.firstFrame(500)
        val s=t.sample(600); assertEquals(200L,s.firstFrameMs); assertEquals(0,s.rebuffers)
    }
    @Test fun repeatedBufferingCallbacksDoNotDoubleCountAndOngoingStallsRemainVisible() {
        val t=LiveTelemetry(); t.start(0); t.firstFrame(50); t.buffering(true,100); t.buffering(true,150)
        assertEquals(100L,t.sample(200).rebufferMs); t.buffering(false,250); t.buffering(false,300)
        t.buffering(true,400); val s=t.sample(500)
        assertEquals(2,s.rebuffers); assertEquals(250L,s.rebufferMs)
    }
    @Test fun transferRateIncludesIdlePeriodsAndNewSessionsDoNotInheritTotals() {
        val t=LiveTelemetry(); t.start(0); assertNull(t.sample(0).bitsPerSecond)
        t.transferred(125000); assertEquals(1000000.0,t.sample(1000).bitsPerSecond!!,0.1)
        assertEquals(0.0,t.sample(2000).bitsPerSecond!!,0.0)
        assertEquals(0L,LiveTelemetry().sample(2000).bytes)
    }
}
