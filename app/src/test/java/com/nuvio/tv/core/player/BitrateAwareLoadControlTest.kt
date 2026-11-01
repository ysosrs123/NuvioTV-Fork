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

class BitrateAwareLoadControlTest {

    private val mb = 1024L * 1024L
    private val playerId = PlayerId("test")
    private val allocator = DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE)

    private fun params(bufferedMs: Long, speed: Float = 1f, rebuffering: Boolean = false) = LoadControl.Parameters(
        playerId, Timeline.EMPTY, MediaPeriodId(Any()), 0L, bufferedMs * 1000L, speed,
        true, rebuffering, C.TIME_UNSET, C.TIME_UNSET
    )

    private fun loadControl(budgetMb: Long = 20) = BitrateAwareLoadControl(
        minBufferMs = 15_000,
        maxBufferMs = 45_000,
        bufferForPlaybackMs = 5_000,
        bufferForPlaybackAfterRebufferMs = 3_000,
        prioritizeTimeOverSizeThresholds = true,
        backBufferDurationMs = 0,
        retainBackBufferFromKeyframe = true,
        budgetBytes = budgetMb * mb,
        allocator = allocator,
    ).also {
        it.onPrepared(playerId)
        it.onTracksSelected(params(0), TrackGroupArray.EMPTY, arrayOf<ExoTrackSelection?>())
    }

    private fun fillMb(count: Long) =
        repeat((count * mb / C.DEFAULT_BUFFER_SEGMENT_SIZE).toInt()) { allocator.allocate() }

    @Test
    fun `a heavy film's first start waits for six seconds, later starts do not`() {
        val lc = loadControl()
        lc.startCushion.arm(heavy = true)
        assertFalse(lc.shouldStartPlayback(params(bufferedMs = 5_500)))
        assertTrue(lc.shouldStartPlayback(params(bufferedMs = 6_000)))
        assertTrue(lc.shouldStartPlayback(params(bufferedMs = 5_000)))
    }

    @Test
    fun `a heavy film starts on a full budget before six seconds`() {
        val lc = loadControl()
        lc.startCushion.arm(heavy = true)
        fillMb(20)
        assertTrue(lc.shouldStartPlayback(params(bufferedMs = 5_500)))
    }

    @Test
    fun `a rebuffer before the first start keeps the user's setting`() {
        val lc = loadControl()
        lc.startCushion.arm(heavy = true)
        assertTrue(lc.shouldStartPlayback(params(bufferedMs = 3_000, rebuffering = true)))
    }

    @Test
    fun `time comes first a little past the budget`() {
        val lc = loadControl()
        fillMb(22)
        assertTrue(lc.shouldContinueLoading(params(bufferedMs = 10_000)))
    }

    @Test
    fun `loading stops a quarter past the budget once playback can start`() {
        val lc = loadControl()
        fillMb(26)
        assertFalse(lc.shouldContinueLoading(params(bufferedMs = 6_000)))
        assertTrue(lc.shouldStartPlayback(params(bufferedMs = 6_000, speed = 2f)))
    }

    @Test
    fun `loading goes on past the ceiling while too little is buffered to play`() {
        val lc = loadControl()
        fillMb(26)
        assertTrue(lc.shouldContinueLoading(params(bufferedMs = 2_000)))
    }

    @Test
    fun `a lowered budget lowers the ceiling`() {
        val lc = loadControl(budgetMb = 40)
        fillMb(26)
        assertTrue(lc.shouldContinueLoading(params(bufferedMs = 6_000)))
        lc.setBudgetBytesOverride(20 * mb)
        assertFalse(lc.shouldContinueLoading(params(bufferedMs = 6_000)))
    }

    @Test
    fun `the ceiling stays below the allocator's 2 GiB int limit on 8 GB boxes`() {
        val intLimit = Int.MAX_VALUE.toLong()
        val lc = loadControl(budgetMb = 2000)
        assertEquals(2000 * mb, lc.ceilingBytes())
        assertTrue(lc.ceilingBytes() < intLimit)
        lc.setBudgetBytesOverride(2500 * mb)
        assertEquals(2000 * mb, lc.ceilingBytes())
        lc.setBudgetBytesOverride(1000 * mb)
        assertEquals(1250 * mb, lc.ceilingBytes())
    }

    @Test
    fun `loading starts again a little below the maximum instead of at the minimum`() {
        val lc = loadControl()
        fillMb(5)
        assertFalse(lc.shouldContinueLoading(params(bufferedMs = 46_000)))
        assertFalse(lc.shouldContinueLoading(params(bufferedMs = 43_000)))
        assertTrue(lc.shouldContinueLoading(params(bufferedMs = 41_000)))
    }

    @Test
    fun `topping up waits for a piece of room below the budget`() {
        val lc = loadControl()
        assertFalse(lc.shouldContinueLoading(params(bufferedMs = 46_000)))
        fillMb(19)
        assertFalse(lc.shouldContinueLoading(params(bufferedMs = 30_000)))
    }

    private fun reservable(targetMb: Long = 20) = ReservableLoadControl(
        allocator = allocator,
        minBufferMs = 15_000,
        maxBufferMs = 45_000,
        bufferForPlaybackMs = 5_000,
        bufferForPlaybackAfterRebufferMs = 3_000,
        targetBufferBytes = (targetMb * mb).toInt(),
        prioritizeTimeOverSizeThresholds = false,
        backBufferDurationMs = 0,
        retainBackBufferFromKeyframe = true,
    ).also {
        it.onPrepared(playerId)
        it.onTracksSelected(params(0), TrackGroupArray.EMPTY, arrayOf<ExoTrackSelection?>())
    }

    @Test
    fun `native memory tops up too and a pressure cap still holds it back`() {
        val lc = reservable()
        fillMb(5)
        assertFalse(lc.shouldContinueLoading(params(bufferedMs = 46_000)))
        assertTrue(lc.shouldContinueLoading(params(bufferedMs = 41_000)))
        lc.setPressureCapBytes(6 * mb)
        assertFalse(lc.shouldContinueLoading(params(bufferedMs = 41_000)))
        assertEquals(42_000, lc.topUp.resumeBelowMs)
    }

    @Test
    fun `stopping the player frees the idle buffer unless the teardown takes it over`() {
        val own = DefaultAllocator(false, C.DEFAULT_BUFFER_SEGMENT_SIZE)
        fun control() = BitrateAwareLoadControl(
            minBufferMs = 15_000,
            maxBufferMs = 45_000,
            bufferForPlaybackMs = 5_000,
            bufferForPlaybackAfterRebufferMs = 3_000,
            prioritizeTimeOverSizeThresholds = true,
            backBufferDurationMs = 0,
            retainBackBufferFromKeyframe = true,
            budgetBytes = 20 * mb,
            allocator = own,
        ).also {
            it.onPrepared(playerId)
            it.onTracksSelected(params(0), TrackGroupArray.EMPTY, arrayOf<ExoTrackSelection?>())
        }
        fun fillAndEmpty() = List(16) { own.allocate() }.forEach(own::release)

        val first = control()
        fillAndEmpty()
        first.onStopped(playerId)
        assertEquals(0, own.availableBytes)

        val second = control()
        fillAndEmpty()
        second.stopTrim.deferred = true
        second.onStopped(playerId)
        second.onReleased(playerId)
        assertEquals(16 * C.DEFAULT_BUFFER_SEGMENT_SIZE, own.availableBytes)
    }
}
