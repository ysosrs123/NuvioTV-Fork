package com.nuvio.tv.core.player

import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.DefaultAllocator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartCushionTest {

    private val mb = 1024L * 1024L

    private fun StartCushion.start(
        bufferedMs: Long,
        base: Boolean = true,
        rebuffering: Boolean = false,
        allocatedMb: Long = 0L,
        targetMb: Long = 50L,
    ) = shouldStart(base, rebuffering, bufferedMs * 1000L, allocatedMb * mb, targetMb * mb)

    @Test
    fun `a buffer that stops growing short of the cushion starts the film`() {
        var now = 1_000L
        val cushion = StartCushion(userStartMs = 1_500, clock = { now })
        cushion.arm(heavy = true)
        assertFalse(cushion.start(bufferedMs = 5_000, allocatedMb = 52, targetMb = 108))
        now += 1_000
        assertFalse(cushion.start(bufferedMs = 5_000, allocatedMb = 52, targetMb = 108))
        now += 600
        assertTrue(cushion.start(bufferedMs = 5_000, allocatedMb = 52, targetMb = 108))
    }

    @Test
    fun `a buffer still growing keeps waiting`() {
        var now = 1_000L
        val cushion = StartCushion(userStartMs = 1_500, clock = { now })
        cushion.arm(heavy = true)
        for (ms in listOf(2_000L, 3_000L, 4_000L, 5_000L)) {
            assertFalse(cushion.start(bufferedMs = ms, allocatedMb = 30, targetMb = 108))
            now += 1_000
        }
    }

    @Test
    fun `a heavy film's first start waits for six seconds`() {
        val cushion = StartCushion(userStartMs = 2_500)
        assertEquals(6_000, cushion.arm(heavy = true))
        assertFalse(cushion.start(bufferedMs = 2_500))
        assertFalse(cushion.start(bufferedMs = 5_999))
        assertTrue(cushion.start(bufferedMs = 6_000))
    }

    @Test
    fun `a buffer full by bytes starts before six seconds`() {
        val cushion = StartCushion(userStartMs = 2_500)
        cushion.arm(heavy = true)
        assertFalse(cushion.start(bufferedMs = 4_000, allocatedMb = 47L, targetMb = 50L))
        assertTrue(cushion.start(bufferedMs = 4_000, allocatedMb = 48L, targetMb = 50L))
    }

    @Test
    fun `an unknown byte target never counts as full`() {
        val cushion = StartCushion(userStartMs = 2_500)
        cushion.arm(heavy = true)
        assertFalse(cushion.start(bufferedMs = 4_000, allocatedMb = 10L, targetMb = 0L))
    }

    @Test
    fun `only the first start waits`() {
        val cushion = StartCushion(userStartMs = 2_500)
        cushion.arm(heavy = true)
        assertTrue(cushion.start(bufferedMs = 6_000))
        assertFalse(cushion.armed)
        assertTrue(cushion.start(bufferedMs = 2_500))
    }

    @Test
    fun `a rebuffer keeps the user's own setting`() {
        val cushion = StartCushion(userStartMs = 2_500)
        cushion.arm(heavy = true)
        assertTrue(cushion.start(bufferedMs = 3_000, rebuffering = true))
        assertTrue(cushion.armed)
    }

    @Test
    fun `the cushion never starts what the load control would not`() {
        val cushion = StartCushion(userStartMs = 2_500)
        cushion.arm(heavy = true)
        assertFalse(cushion.start(bufferedMs = 8_000, base = false))
        assertTrue(cushion.armed)
    }

    @Test
    fun `light films and long user settings are left alone`() {
        val light = StartCushion(userStartMs = 2_500)
        assertEquals(0, light.arm(heavy = false))
        assertTrue(light.start(bufferedMs = 2_500))

        val patient = StartCushion(userStartMs = 8_000)
        assertEquals(0, patient.arm(heavy = true))
        assertTrue(patient.start(bufferedMs = 1_000))
    }

    @Test
    fun `arming again for the next stream waits again`() {
        val cushion = StartCushion(userStartMs = 2_500)
        cushion.arm(heavy = true)
        assertTrue(cushion.start(bufferedMs = 6_000))
        cushion.arm(heavy = true)
        assertFalse(cushion.start(bufferedMs = 3_000))
    }

    @Test
    fun `heavy goes by the real bitrate or else the guess`() {
        val heavy = 60_000_000L
        assertTrue(StartCushion.isHeavy(fileBitrateBps = 60_000_000L, guessedBitrateBps = 0L, heavyBps = heavy))
        assertFalse(StartCushion.isHeavy(fileBitrateBps = 59_999_999L, guessedBitrateBps = 80_000_000L, heavyBps = heavy))
        assertTrue(StartCushion.isHeavy(fileBitrateBps = 0L, guessedBitrateBps = 80_000_000L, heavyBps = heavy))
        assertFalse(StartCushion.isHeavy(fileBitrateBps = 0L, guessedBitrateBps = 0L, heavyBps = heavy))
    }

    @Test
    fun `the cushion never asks for more than the buffer can hold`() {
        val shortBuffer = StartCushion(userStartMs = 2_500, maxBufferMs = 4_000)
        assertEquals(4_000, shortBuffer.arm(heavy = true))
        assertTrue(shortBuffer.start(bufferedMs = 4_000))

        val tinyBuffer = StartCushion(userStartMs = 2_500, maxBufferMs = 2_000)
        assertEquals(0, tinyBuffer.arm(heavy = true))
    }

    private val playerId = PlayerId("test")

    private fun params(bufferedMs: Long, rebuffering: Boolean = false) = LoadControl.Parameters(
        playerId, Timeline.EMPTY, MediaPeriodId(Any()), 0L, bufferedMs * 1000L, 1f,
        true, rebuffering, C.TIME_UNSET, C.TIME_UNSET
    )

    @Test
    fun `the native memory load control starts a heavy film on six seconds or a full target`() {
        val allocator = DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE)
        val lc = ReservableLoadControl(
            allocator = allocator,
            minBufferMs = 15_000,
            maxBufferMs = 45_000,
            bufferForPlaybackMs = 2_500,
            bufferForPlaybackAfterRebufferMs = 5_000,
            targetBufferBytes = (20 * mb).toInt(),
            prioritizeTimeOverSizeThresholds = false,
            backBufferDurationMs = 0,
            retainBackBufferFromKeyframe = true,
        ).also {
            it.onPrepared(playerId)
            it.onTracksSelected(params(0), TrackGroupArray.EMPTY, arrayOf<ExoTrackSelection?>())
        }
        lc.startCushion.arm(heavy = true)
        assertFalse(lc.shouldStartPlayback(params(bufferedMs = 3_000)))
        repeat((20 * mb / C.DEFAULT_BUFFER_SEGMENT_SIZE).toInt()) { allocator.allocate() }
        assertTrue(lc.shouldStartPlayback(params(bufferedMs = 3_000)))
        assertTrue(lc.shouldStartPlayback(params(bufferedMs = 2_500)))
    }
}
