package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvPropertyCacheTest {

    private var now = 0L

    @Test
    fun `nothing reported reads as unknown`() {
        val cache = MpvPropertyCache { now }
        assertEquals(0L, cache.positionMs())
        assertEquals(0L, cache.durationMs())
        assertNull(cache.boolean(MpvCachedProperty.PAUSE))
        assertNull(cache.string(MpvCachedProperty.PATH))
        assertNull(cache.long(MpvCachedProperty.FRAME_DROP_COUNT))
    }

    @Test
    fun `reported values are read back by type`() {
        val cache = MpvPropertyCache { now }
        cache.put(MpvCachedProperty.PAUSE, false)
        cache.put(MpvCachedProperty.PATH, "https://host/file.mkv")
        cache.put(MpvCachedProperty.FRAME_DROP_COUNT, 12L)
        cache.put(MpvCachedProperty.DEMUXER_CACHE_DURATION, 8.5)

        assertEquals(false, cache.boolean(MpvCachedProperty.PAUSE))
        assertEquals("https://host/file.mkv", cache.string(MpvCachedProperty.PATH))
        assertEquals(12L, cache.long(MpvCachedProperty.FRAME_DROP_COUNT))
        assertEquals(8.5, cache.double(MpvCachedProperty.DEMUXER_CACHE_DURATION)!!, 0.0)
    }

    @Test
    fun `a value of another type is not misread`() {
        val cache = MpvPropertyCache { now }
        cache.put(MpvCachedProperty.VID, "1")
        assertNull(cache.boolean(MpvCachedProperty.VID))
        assertNull(cache.double(MpvCachedProperty.VID))
    }

    @Test
    fun `an unavailable report clears the value`() {
        val cache = MpvPropertyCache { now }
        cache.put(MpvCachedProperty.DURATION, 5400.0)
        cache.put(MpvCachedProperty.DURATION, null)
        assertNull(cache.double(MpvCachedProperty.DURATION))
        assertEquals(0L, cache.durationMs())
    }

    @Test
    fun `duration is rounded to milliseconds`() {
        val cache = MpvPropertyCache { now }
        cache.put(MpvCachedProperty.DURATION, 5423.4567)
        assertEquals(5_423_457L, cache.durationMs())
    }

    @Test
    fun `position follows the video clock in normal playback`() {
        val cache = MpvPropertyCache { now }
        cache.put(MpvCachedProperty.TIME_POS, 124.333)
        cache.put(MpvCachedProperty.AUDIO_PTS, 124.291)
        assertEquals(124_333L, cache.positionMs())
    }

    @Test
    fun `position falls back to the audio clock when video reports zero`() {
        val cache = MpvPropertyCache { now }
        cache.put(MpvCachedProperty.TIME_POS, 0.0)
        cache.put(MpvCachedProperty.AUDIO_PTS, 123.785)
        cache.put(MpvCachedProperty.SEEKING, false)
        assertEquals(123_785L, cache.positionMs())
    }

    @Test
    fun `position trusts the seek target while seeking`() {
        val cache = MpvPropertyCache { now }
        cache.put(MpvCachedProperty.TIME_POS, 30.0)
        cache.put(MpvCachedProperty.AUDIO_PTS, 158.194)
        cache.put(MpvCachedProperty.SEEKING, true)
        assertEquals(30_000L, cache.positionMs())
    }

    @Test
    fun `audio delay widens the glitch margin`() {
        val cache = MpvPropertyCache { now }
        cache.put(MpvCachedProperty.TIME_POS, 100.0)
        cache.put(MpvCachedProperty.AUDIO_PTS, 115.0)
        cache.put(MpvCachedProperty.AUDIO_DELAY, 6.0)
        assertEquals(100_000L, cache.positionMs())
    }

    @Test
    fun `clear forgets everything`() {
        val cache = MpvPropertyCache { now }
        cache.put(MpvCachedProperty.TIME_POS, 50.0)
        cache.put(MpvCachedProperty.PAUSE, true)
        cache.clear()
        assertEquals(0L, cache.positionMs())
        assertNull(cache.boolean(MpvCachedProperty.PAUSE))
    }

    @Test
    fun `a seek moves the position to its target at once`() {
        val cache = MpvPropertyCache { now }
        cache.put(MpvCachedProperty.TIME_POS, 900.0)
        cache.put(MpvCachedProperty.AUDIO_PTS, 900.1)
        cache.onSeekRequested(targetSec = 60.0)
        assertEquals(60_000L, cache.positionMs())
        assertNull(cache.double(MpvCachedProperty.AUDIO_PTS))
    }

    @Test
    fun `reports from before the seek do not pull the position back`() {
        val cache = MpvPropertyCache { now }
        cache.put(MpvCachedProperty.TIME_POS, 900.0)
        cache.onSeekRequested(targetSec = 60.0)
        now = 100L
        cache.put(MpvCachedProperty.TIME_POS, 900.04)
        cache.put(MpvCachedProperty.AUDIO_PTS, 900.02)
        assertEquals(60_000L, cache.positionMs())

        cache.put(MpvCachedProperty.TIME_POS, 58.5)
        assertEquals(58_500L, cache.positionMs())
        cache.put(MpvCachedProperty.TIME_POS, 59.0)
        assertEquals(59_000L, cache.positionMs())
    }

    @Test
    fun `a seek that never lands stops holding the position after two seconds`() {
        val cache = MpvPropertyCache { now }
        cache.onSeekRequested(targetSec = 60.0)
        now = 1_999L
        cache.put(MpvCachedProperty.TIME_POS, 300.0)
        assertEquals(60_000L, cache.positionMs())
        now = 2_000L
        cache.put(MpvCachedProperty.TIME_POS, 300.0)
        assertEquals(300_000L, cache.positionMs())
    }

    @Test
    fun `a report from before a ten second skip does not count as landed`() {
        val cache = MpvPropertyCache { now }
        cache.put(MpvCachedProperty.TIME_POS, 100.0)
        cache.onSeekRequested(targetSec = 110.0)
        now = 50L
        cache.put(MpvCachedProperty.TIME_POS, 100.2)
        assertEquals(110_000L, cache.positionMs())
    }

    @Test
    fun `mpv reporting the seek ends the hold`() {
        val cache = MpvPropertyCache { now }
        cache.put(MpvCachedProperty.TIME_POS, 100.0)
        cache.onSeekRequested(targetSec = 110.0)
        cache.put(MpvCachedProperty.SEEKING, true)
        cache.put(MpvCachedProperty.TIME_POS, 104.0)
        assertEquals(104_000L, cache.positionMs())
    }

    @Test
    fun `the seek target alone does not count as a position from mpv`() {
        val cache = MpvPropertyCache { now }
        cache.put(MpvCachedProperty.TIME_POS, 0.0)
        assertTrue(cache.positionReportedSinceSeek)
        cache.onSeekRequested(targetSec = 55.339)
        assertEquals(55_339L, cache.positionMs())
        assertFalse(cache.positionReportedSinceSeek)

        now = 100L
        cache.put(MpvCachedProperty.TIME_POS, 0.04)
        assertFalse(cache.positionReportedSinceSeek)
        cache.put(MpvCachedProperty.TIME_POS, 55.3)
        assertTrue(cache.positionReportedSinceSeek)
    }

    @Test
    fun `clear also forgets a pending seek`() {
        val cache = MpvPropertyCache { now }
        cache.onSeekRequested(targetSec = 60.0)
        cache.clear()
        cache.put(MpvCachedProperty.TIME_POS, 300.0)
        assertEquals(300_000L, cache.positionMs())
    }

    @Test
    fun `every cached property is observed with a format`() {
        assertTrue(MpvCachedProperty.all.containsKey(MpvCachedProperty.DURATION))
        assertTrue(MpvCachedProperty.all.containsKey(MpvCachedProperty.TIME_POS))
        assertEquals(MpvCachedFormat.FLAG, MpvCachedProperty.all[MpvCachedProperty.PAUSE])
        assertEquals(MpvCachedFormat.STRING, MpvCachedProperty.all[MpvCachedProperty.PATH])
    }
}
