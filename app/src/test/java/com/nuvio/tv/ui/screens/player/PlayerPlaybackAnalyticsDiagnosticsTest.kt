package com.nuvio.tv.ui.screens.player

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class PlayerPlaybackAnalyticsDiagnosticsTest {

    private var nowMs = 1_000_000L
    private var positionMs = 1_432_000L
    private var playing = true

    @Before
    fun setUp() {
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } answers { nowMs }
    }

    @After
    fun tearDown() {
        unmockkStatic(SystemClock::class)
    }

    private fun player(): Player = mockk(relaxed = true) {
        every { playWhenReady } answers { playing }
        every { isPlaying } answers { playing }
        every { playbackState } returns Player.STATE_READY
        every { currentPosition } answers { positionMs }
        every { bufferedPosition } answers { positionMs + 20_000L }
        every { duration } returns 7_000_000L
        every { playbackParameters } returns PlaybackParameters.DEFAULT
    }

    @Test
    fun `a pause with no samples is not counted as a freeze on resume`() {
        val diagnostics = PlayerPlaybackAnalyticsDiagnostics()
        val player = player()
        diagnostics.recordProgressSnapshot(player, hasRenderedFirstFrame = true)
        playing = false
        nowMs += 1_000L
        diagnostics.recordProgressSnapshot(player, hasRenderedFirstFrame = true)
        nowMs += 58_600L
        playing = true
        diagnostics.recordProgressSnapshot(player, hasRenderedFirstFrame = true)
        nowMs += 1_000L
        positionMs += 1_000L
        diagnostics.recordProgressSnapshot(player, hasRenderedFirstFrame = true)
        assertEquals(0, diagnostics.hudSample().positionStallCount)
    }

    @Test
    fun `position stuck while playing is still counted`() {
        val diagnostics = PlayerPlaybackAnalyticsDiagnostics()
        val player = player()
        repeat(8) {
            diagnostics.recordProgressSnapshot(player, hasRenderedFirstFrame = true)
            nowMs += 1_000L
        }
        assertEquals(1, diagnostics.hudSample().positionStallCount)
    }

    @Test
    fun `vod buffer below duration reports integer percent`() {
        assertEquals(0, safeBufferedPercentage(bufferedPositionMs = 0L, durationMs = 10_000L))
        assertEquals(25, safeBufferedPercentage(bufferedPositionMs = 2_500L, durationMs = 10_000L))
        assertEquals(99, safeBufferedPercentage(bufferedPositionMs = 9_999L, durationMs = 10_000L))
    }

    @Test
    fun `fully buffered or empty duration reports 100`() {
        assertEquals(100, safeBufferedPercentage(bufferedPositionMs = 10_000L, durationMs = 10_000L))
        assertEquals(100, safeBufferedPercentage(bufferedPositionMs = 12_000L, durationMs = 10_000L))
        assertEquals(100, safeBufferedPercentage(bufferedPositionMs = 0L, durationMs = 0L))
    }

    @Test
    fun `unset or negative times are omitted`() {
        assertNull(safeBufferedPercentage(bufferedPositionMs = 1_000L, durationMs = C.TIME_UNSET))
        assertNull(safeBufferedPercentage(bufferedPositionMs = C.TIME_UNSET, durationMs = 10_000L))
        assertNull(safeBufferedPercentage(bufferedPositionMs = -1L, durationMs = 10_000L))
        assertNull(safeBufferedPercentage(bufferedPositionMs = 1_000L, durationMs = -5L))
    }

    @Test
    fun `live iptv timestamps that overflow Media3 percentInt clamp to 100`() {
        assertEquals(
            100,
            safeBufferedPercentage(bufferedPositionMs = 1_535_769_691_039L, durationMs = 10L)
        )
        assertEquals(
            100,
            safeBufferedPercentage(bufferedPositionMs = 1_243_001_841_137L, durationMs = 25L)
        )
    }
}
