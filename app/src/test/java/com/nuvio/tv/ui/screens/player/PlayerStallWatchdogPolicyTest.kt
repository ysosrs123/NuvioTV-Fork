package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerStallWatchdogPolicyTest {

    private fun evaluate(
        bufferedMs: Long,
        playheadMs: Long,
        stalledForMs: Long,
        durationMs: Long = 3_600_000L,
    ) = PlayerStallWatchdogPolicy.evaluate(
        PlayerStallWatchdogPolicy.Input(
            bufferedPositionMs = bufferedMs,
            playheadMs = playheadMs,
            durationMs = durationMs,
            stalledForMs = stalledForMs,
            thresholdMs = 15_000L,
        )
    )

    @Test
    fun `waits before the threshold`() {
        assertEquals(
            PlayerStallWatchdogPolicy.Decision.KeepWaiting,
            evaluate(bufferedMs = 100_500L, playheadMs = 100_000L, stalledForMs = 14_999L)
        )
    }

    @Test
    fun `keeps waiting while seconds of film are buffered ahead`() {
        assertEquals(
            PlayerStallWatchdogPolicy.Decision.KeepWaiting,
            evaluate(bufferedMs = 1_005_070L, playheadMs = 1_000_000L, stalledForMs = 15_000L)
        )
        assertEquals(
            PlayerStallWatchdogPolicy.Decision.KeepWaiting,
            evaluate(bufferedMs = 1_001_500L, playheadMs = 1_000_000L, stalledForMs = 40_000L)
        )
    }

    @Test
    fun `skips past the buffered edge once the long wait runs out`() {
        assertEquals(
            PlayerStallWatchdogPolicy.Decision.SeekPastBufferedEdge(1_005_320L),
            evaluate(bufferedMs = 1_005_070L, playheadMs = 1_000_000L, stalledForMs = 60_000L)
        )
    }

    @Test
    fun `stuck request with almost nothing ahead still skips past the edge`() {
        assertEquals(
            PlayerStallWatchdogPolicy.Decision.SeekPastBufferedEdge(1_001_449L),
            evaluate(bufferedMs = 1_001_199L, playheadMs = 1_000_000L, stalledForMs = 15_000L)
        )
        assertEquals(
            PlayerStallWatchdogPolicy.Decision.SeekPastBufferedEdge(1_000_250L + 40L),
            evaluate(bufferedMs = 1_000_040L, playheadMs = 1_000_000L, stalledForMs = 15_000L)
        )
    }

    @Test
    fun `buffered not ahead of the playhead gives up`() {
        assertEquals(
            PlayerStallWatchdogPolicy.Decision.SkipBufferedNotAhead,
            evaluate(bufferedMs = 1_000_000L, playheadMs = 1_000_000L, stalledForMs = 15_000L)
        )
    }

    @Test
    fun `unknown duration gives up`() {
        assertEquals(
            PlayerStallWatchdogPolicy.Decision.SkipUnknownDuration,
            evaluate(bufferedMs = 1_000_500L, playheadMs = 1_000_000L, stalledForMs = 15_000L, durationMs = C.TIME_UNSET)
        )
    }

    @Test
    fun `skip target is capped just before the end`() {
        assertEquals(
            PlayerStallWatchdogPolicy.Decision.SeekPastBufferedEdge(3_599_999L),
            evaluate(bufferedMs = 3_599_900L, playheadMs = 3_599_000L, stalledForMs = 15_000L)
        )
    }
}
