package com.nuvio.tv.core.player.thumbnail

import com.nuvio.tv.core.player.thumbnail.ThumbDecodeLanes.Companion.RETRY_AFTER_DECODES
import com.nuvio.tv.core.player.thumbnail.ThumbDecodeLanes.Companion.WINDOW
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** How many keyframes are decoded at once, and when that changes. */
class ThumbDecodeLanesTest {
    private fun lanes(strong: Boolean = true, fourK: Boolean = true, cpus: Int = 8, phase: FetchPhase = FetchPhase.GENERATING) =
        ThumbDecodeLanes(strong, fourK, cpus, phase)

    /** [n] decodes of [ms] with [concurrent] running; every one but the last must leave the count alone. */
    private fun ThumbDecodeLanes.feed(ms: Long, concurrent: Int, n: Int = WINDOW, held: Boolean = false): ThumbDecodeLanes.Change? {
        repeat(n - 1) { assertNull("decode ${it + 1} of $n", onDecode(ms, concurrent, held)) }
        return onDecode(ms, concurrent, held)
    }

    @Test fun startsAtTwoOnStrongBoxesAndOneForSmallBox4K() {
        assertEquals(2, lanes(strong = true, fourK = true).lanes)
        assertEquals(2, lanes(strong = true, fourK = false).lanes)
        assertEquals(2, lanes(strong = true, fourK = true, phase = FetchPhase.PLAYBACK).lanes)
        assertEquals(1, lanes(strong = false, fourK = true).lanes)
        assertEquals(1, lanes(strong = false, fourK = true, phase = FetchPhase.PLAYBACK).lanes)
        assertEquals(2, lanes(strong = false, fourK = false).lanes)
        assertEquals(2, lanes(strong = false, fourK = false, phase = FetchPhase.PLAYBACK).lanes)
        assertEquals(1, lanes(cpus = 2).lanes)
    }

    @Test fun ceilingsPerPhaseBoxAndResolution() {
        val strong4K = lanes(strong = true, fourK = true, cpus = 8)
        assertEquals(4, strong4K.ceiling(FetchPhase.GENERATING))
        assertEquals(4, strong4K.ceiling(FetchPhase.PLAYER_CLOSED))
        assertEquals(2, strong4K.ceiling(FetchPhase.PLAYBACK))
        val strongHd = lanes(strong = true, fourK = false, cpus = 8)
        assertEquals(6, strongHd.ceiling(FetchPhase.GENERATING))
        assertEquals(2, strongHd.ceiling(FetchPhase.PLAYBACK))
        assertEquals(3, lanes(strong = true, fourK = false, cpus = 4).ceiling(FetchPhase.GENERATING))
        assertEquals(6, lanes(strong = true, fourK = false, cpus = 12).ceiling(FetchPhase.GENERATING))
        val small4K = lanes(strong = false, fourK = true, cpus = 4)
        assertEquals(1, small4K.ceiling(FetchPhase.GENERATING))
        assertEquals(1, small4K.ceiling(FetchPhase.PLAYBACK))
        assertEquals(2, lanes(strong = false, fourK = false, cpus = 4).ceiling(FetchPhase.PLAYBACK))
        for (cpus in listOf(-1, 0, 1, 2)) for (phase in FetchPhase.values()) {
            assertEquals("cpus=$cpus $phase", 1, lanes(cpus = cpus).ceiling(phase))
        }
    }

    @Test fun rampsUpWhileEachDecodeStaysWithinAQuarter() {
        val c = lanes(cpus = 8)
        val first = c.feed(300, concurrent = 2)
        assertNotNull(first)
        assertEquals(2, first!!.from)
        assertEquals(3, first.to)
        assertEquals(300L, first.medianMs)
        val second = c.feed(360, concurrent = 3)                 // 360 <= 1.25 x 300
        assertEquals(4, second!!.to)
        assertTrue(second.reason, second.reason.startsWith("gain"))
        assertNull(c.feed(370, concurrent = 4, n = 20))           // at the 4K ceiling
        assertEquals(4, c.lanes)
    }

    @Test fun stepsBackWhenALaneBringsNoGainAndRetriesLater() {
        val c = lanes(cpus = 8)
        assertEquals(3, c.feed(300, concurrent = 2)!!.to)
        val back = c.feed(400, concurrent = 3)                   // 400 > 1.25 x 300
        assertEquals(3, back!!.from)
        assertEquals(2, back.to)
        assertEquals(400L, back.medianMs)
        assertTrue(back.reason, back.reason.startsWith("no gain"))
        val retry = c.feed(300, concurrent = 2, n = RETRY_AFTER_DECODES)
        assertEquals(3, retry!!.to)
    }

    @Test fun decodesThatSlowDownLaterGiveTheLaneBack() {
        val c = lanes(cpus = 4)                                   // ceiling 3
        assertEquals(3, c.feed(300, concurrent = 2)!!.to)
        assertNull(c.feed(330, concurrent = 3, n = 10))
        assertNull(c.onDecode(400, 3, false))
        assertNull(c.onDecode(400, 3, false))
        assertNull(c.onDecode(400, 3, false))                     // median 365
        assertEquals(2, c.onDecode(400, 3, false)!!.to)           // median 400
    }

    @Test fun aLaneThatSlowsEveryDecodeIsGivenBack() {
        val c = lanes(cpus = 8)
        assertNull(c.feed(200, concurrent = 1, n = 20))           // other counts never decide
        assertEquals(2, c.lanes)
        val ch = c.feed(300, concurrent = 2)                     // 300 > 1.25 x 200
        assertEquals(1, ch!!.to)
    }

    @Test fun noLaneIsAddedWhileHot() {
        val c = lanes(cpus = 8)
        assertNull(c.feed(300, concurrent = 2, n = 20, held = true))
        assertEquals(2, c.lanes)
        assertEquals(3, c.onDecode(300, 2, held = false)!!.to)
    }

    @Test fun clockCapStepsDownToOneAndNoFurther() {
        val c = lanes(cpus = 8)
        val ch = c.onClockCap()
        assertEquals(2, ch!!.from)
        assertEquals(1, ch.to)
        assertEquals("clock capped", ch.reason)
        assertNull(c.onClockCap())
        assertEquals(1, c.lanes)
    }

    @Test fun memoryStepsDownAndHoldsTheLaneBackForAWhile() {
        val c = lanes(cpus = 8)
        assertEquals(1, c.onMemoryTrim()!!.to)
        assertNull(c.onMemoryRefused())
        assertEquals(1, c.lanes)
        val again = c.feed(300, concurrent = 1, n = RETRY_AFTER_DECODES)
        assertEquals(2, again!!.to)
    }

    @Test fun aMemoryRefusalOfAnExtraLaneDropsOne() {
        val c = lanes(cpus = 8)
        assertEquals(3, c.feed(300, concurrent = 2)!!.to)
        val ch = c.onMemoryRefused()
        assertEquals(3, ch!!.from)
        assertEquals(2, ch.to)
        assertEquals("memory refused", ch.reason)
    }

    @Test fun playbackClampsToItsCeiling() {
        val c = lanes(cpus = 8)
        c.feed(300, concurrent = 2)
        c.feed(330, concurrent = 3)
        assertEquals(4, c.lanes)
        val ch = c.onPhase(FetchPhase.PLAYBACK)
        assertEquals(4, ch!!.from)
        assertEquals(2, ch.to)
        assertEquals("playback", ch.reason)
        assertNull(c.onPhase(FetchPhase.PLAYBACK))
        assertNull(c.feed(300, concurrent = 2, n = 20))           // playback ceiling
        assertNull(c.onPhase(FetchPhase.PLAYER_CLOSED))
        assertEquals(2, c.lanes)
        assertEquals(3, c.onDecode(300, 2, false)!!.to)            // room again with the player closed
    }

    @Test fun smallBox4KStaysAtOneEverywhere() {
        val c = lanes(strong = false, fourK = true, cpus = 4, phase = FetchPhase.PLAYBACK)
        assertNull(c.feed(900, concurrent = 1, n = 30))
        assertEquals(1, c.lanes)
        assertNull(c.onPhase(FetchPhase.GENERATING))
        assertNull(c.onDecode(900, 1, false))
        assertEquals(1, c.lanes)
    }

    @Test fun alwaysBetweenOneAndTheCeiling() {
        val rnd = Random(7)
        for (strong in listOf(false, true)) for (fourK in listOf(false, true)) for (cpus in listOf(1, 2, 4, 8)) {
            val c = lanes(strong = strong, fourK = fourK, cpus = cpus, phase = FetchPhase.PLAYBACK)
            repeat(3_000) {
                when (rnd.nextInt(20)) {
                    0 -> c.onClockCap()
                    1 -> c.onMemoryTrim()
                    2 -> c.onMemoryRefused()
                    3 -> c.onPhase(FetchPhase.values()[rnd.nextInt(3)])
                    else -> c.onDecode(rnd.nextLong(1, 1_000), rnd.nextInt(0, 8), rnd.nextInt(5) == 0)
                }
                assertTrue("lanes ${c.lanes} ceiling ${c.ceiling()}", c.lanes in 1..c.ceiling())
            }
        }
    }

    @Test fun median() {
        assertEquals(0L, ThumbDecodeLanes.median(emptyList()))
        assertEquals(5L, ThumbDecodeLanes.median(listOf(9L, 5L, 1L)))
        assertEquals(4L, ThumbDecodeLanes.median(listOf(1L, 3L, 5L, 100L)))
    }
}
