package com.nuvio.tv.core.player.thumbnail

import com.nuvio.tv.core.player.thumbnail.PlaybackDecodePacer.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Drop-aware pacing and stall safety for 4K decodes during playback. */
class PlaybackDecodePacerTest {
    private val s = 1_000L

    /** One decode of [ms] starting at [at]. */
    private fun PlaybackDecodePacer.decode(at: Long, ms: Long = 2 * s) {
        decodeStarted(at)
        decodeEnded(at + ms)
    }

    @Test fun noDropsKeepsTheBasePace() {
        val p = PlaybackDecodePacer()
        p.observeDrops(0, 0, excluded = false)
        for (t in 1..300) {
            if (t % 6 == 0) p.decode(t * s)
            assertEquals(Event.NONE, p.observeDrops(t * s, 0, excluded = false))
        }
        assertEquals(4_000L, p.paceMs)
        assertTrue(p.allows(false))
    }

    @Test fun burstsStepTheLadderThenStopThePass() {
        val p = PlaybackDecodePacer()
        p.observeDrops(0, 0, false)
        p.decode(1 * s)
        assertEquals(Event.SLOWER, p.observeDrops(5 * s, 4, false))      // 4 > 3 in 20 s
        assertEquals(8_000L, p.paceMs)
        p.decode(20 * s)
        assertEquals(Event.NONE, p.observeDrops(21 * s, 6, false))       // 2 since the change: not yet
        assertEquals(Event.SLOWER, p.observeDrops(23 * s, 8, false))     // 4 since the change
        assertEquals(16_000L, p.paceMs)
        p.decode(40 * s)
        assertEquals(Event.STOPPED_DROPS, p.observeDrops(42 * s, 12, false))
        assertFalse(p.allows(settleTarget = false))
        assertTrue(p.allows(settleTarget = true))
        assertEquals(12, p.countedDrops)
    }

    @Test fun sustainedDropsBelowTheBurstRateStillSlowDown() {
        // One drop every 10 s, never 4 in one burst.
        val p = PlaybackDecodePacer()
        p.observeDrops(0, 0, false)
        var dropped = 0
        var event = Event.NONE
        var t = 0L
        while (event == Event.NONE && t < 120 * s) {
            t += 10 * s
            p.decode(t - 2 * s)
            dropped += 1
            event = p.observeDrops(t, dropped, false)
        }
        assertEquals(Event.SLOWER, event)
        assertEquals(60 * s, t)                                            // the 6th drop within 60 s
    }

    /** Drops that already slowed the pace must not count towards stopping it. */
    @Test fun theSlowestPaceGetsAFairTrial() {
        val p = PlaybackDecodePacer()
        p.observeDrops(0, 0, false)
        p.decode(55 * s)
        assertEquals(Event.SLOWER, p.observeDrops(60 * s, 6, false))
        p.decode(80 * s)
        assertEquals(Event.SLOWER, p.observeDrops(87 * s, 10, false))
        assertEquals(16_000L, p.paceMs)
        p.decode(100 * s)
        assertEquals(Event.NONE, p.observeDrops(102 * s, 11, false))     // 1 drop at 16 s: keep going
        assertTrue(p.allows(false))
        p.decode(118 * s)
        assertEquals(Event.STOPPED_DROPS, p.observeDrops(120 * s, 14, false)) // 4 at 16 s: stop
    }

    @Test fun manyDropsInAMinuteStopAtOnce() {
        val p = PlaybackDecodePacer()
        p.observeDrops(0, 0, false)
        p.decode(1 * s)
        assertEquals(Event.STOPPED_DROPS, p.observeDrops(3 * s, 11, false))
    }

    @Test fun seekAndResumeDropsAreIgnored() {
        val p = PlaybackDecodePacer()
        p.observeDrops(0, 0, false)
        p.decode(1 * s)
        assertEquals(Event.NONE, p.observeDrops(3 * s, 9, excluded = true))   // seek buffering
        assertEquals(Event.NONE, p.observeDrops(4 * s, 9, false))
        assertEquals(4_000L, p.paceMs)
        assertEquals(9, p.ignoredDrops)
        assertEquals(0, p.countedDrops)
    }

    @Test fun dropsFarFromAnyDecodeAreNotOurs() {
        val p = PlaybackDecodePacer()
        p.observeDrops(0, 0, false)
        p.decode(1 * s)                                                     // ends at 3 s
        assertEquals(Event.NONE, p.observeDrops(30 * s, 8, false))        // 27 s after it
        assertEquals(4_000L, p.paceMs)
        assertEquals(8, p.ignoredDrops)
    }

    @Test fun dropsDuringALongDecodeCount() {
        val p = PlaybackDecodePacer()
        p.observeDrops(0, 0, false)
        p.decodeStarted(1 * s)
        assertEquals(Event.SLOWER, p.observeDrops(40 * s, 4, false))
    }

    @Test fun aNewCounterObjectResetsTheBaseline() {
        val p = PlaybackDecodePacer()
        p.observeDrops(0, 50, false)
        p.decode(1 * s)
        assertEquals(Event.NONE, p.observeDrops(2 * s, 2, false))         // renderer re-enabled: 50 -> 2
        assertEquals(Event.NONE, p.observeDrops(3 * s, -1, false))        // no video renderer
        assertEquals(Event.NONE, p.observeDrops(4 * s, 0, false))
        assertEquals(Event.NONE, p.observeDrops(5 * s, 2, false))
        assertEquals(2, p.countedDrops)
        assertEquals(4_000L, p.paceMs)
    }

    @Test fun twoQuietMinutesHalveThePaceAgain() {
        val p = PlaybackDecodePacer()
        p.observeDrops(0, 0, false)
        p.decode(1 * s)
        assertEquals(Event.SLOWER, p.observeDrops(5 * s, 4, false))
        assertEquals(Event.NONE, p.observeDrops(124 * s, 4, false))
        assertEquals(Event.FASTER, p.observeDrops(125 * s, 4, false))
        assertEquals(4_000L, p.paceMs)
    }

    @Test fun aStallAfterADecodeStopsAllPlaybackDecodesWithOneRetry() {
        val p = PlaybackDecodePacer()
        p.decode(100 * s)                                                   // ends at 102 s
        assertEquals(Event.STOPPED_STALL, p.observeStall(118 * s, 115 * s))
        assertFalse(p.allows(settleTarget = true))
        assertEquals(Event.NONE, p.observeStall(119 * s, 115 * s))         // same stall, counted once
        // Another stall while stopped restarts the 5-minute clock.
        assertEquals(Event.NONE, p.observeStall(200 * s, 200 * s))
        assertEquals(Event.NONE, p.observeStall(450 * s, 200 * s))
        assertEquals(Event.RESUMED_AFTER_STALL, p.observeStall(500 * s, 200 * s))
        assertTrue(p.allows(false))
        assertEquals(16_000L, p.paceMs)
        p.decode(600 * s)
        assertEquals(Event.STOPPED_STALL, p.observeStall(605 * s, 603 * s))
        assertEquals(2, p.stallStops)
        assertEquals(Event.NONE, p.observeStall(2_000 * s, 603 * s))
        assertFalse(p.allows(true))
    }

    @Test fun aStallFarFromAnyDecodeIsNotOurs() {
        val p = PlaybackDecodePacer()
        p.decode(100 * s)
        assertEquals(Event.NONE, p.observeStall(130 * s, 125 * s))         // 23 s after the decode ended
        assertTrue(p.allows(false))
        assertEquals(Event.NONE, p.observeStall(131 * s, 0L))
        assertEquals(0, p.stallStops)
    }
}
