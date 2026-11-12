package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.ui.screens.player.StartupWatchdogDecision.Step
import org.junit.Assert.assertEquals
import org.junit.Test

class StartupWatchdogDecisionTest {
    private fun step(
        elapsedMs: Long = 20_000L,
        ceilingMs: Long = 60_000L,
        slowStart: Boolean = false,
        bufferedAheadMs: Long = 0L,
        lastBufferedAheadMs: Long = 0L,
        ready: Boolean = false,
        playing: Boolean = false,
        positionMs: Long = 0L,
        lastPositionMs: Long = 0L,
        videoRestarted: Boolean = false,
        tracksRead: Boolean = true,
        hasVideoTrack: Boolean = true,
        videoSelected: Boolean = true,
        playbackThreadAnswers: Boolean = true,
    ) = StartupWatchdogDecision.next(
        elapsedMs, 20_000L, ceilingMs, slowStart, bufferedAheadMs, lastBufferedAheadMs, ready, playing,
        positionMs, lastPositionMs, videoRestarted, tracksRead, hasVideoTrack, videoSelected, playbackThreadAnswers,
    )

    @Test
    fun aUsenetResumeStillFetchingWaitsInsteadOfBlamingTheDecoder() {
        // Headers read, resume point at 282 s still on its way from the NAS.
        assertEquals(Step.WaitForData, step(slowStart = true, ceilingMs = 80_000L, positionMs = 282_049L, lastPositionMs = 282_049L))
        // Past the ceiling it gives up, and says the stream did not deliver.
        assertEquals(Step.Fire("no_data_buffered"), step(elapsedMs = 61_000L, slowStart = true, ceilingMs = 80_000L))
    }

    @Test
    fun nothingBufferedIsNeverCalledADecoderProblem() {
        assertEquals(Step.Fire("no_data_buffered"), step())
        assertEquals(Step.Fire("tracks_not_read"), step(tracksRead = false))
        assertEquals(Step.Fire("decoder_unresponsive"), step(bufferedAheadMs = 5_000L, lastBufferedAheadMs = 5_000L, ready = true))
    }

    @Test
    fun aStuckPlayerThreadIsADecoderProblemEvenWithNothingBuffered() {
        // Dolby Vision file at its resume point: data arrived, the decoder never opened, nothing counted as buffered.
        assertEquals(Step.Fire("decoder_unresponsive"), step(positionMs = 392_000L, lastPositionMs = 392_000L, playbackThreadAnswers = false))
        assertEquals(Step.Fire("tracks_not_read"), step(tracksRead = false, playbackThreadAnswers = false))
    }

    @Test
    fun soundWithoutPictureRestartsTheVideoOnceThenReports() {
        // Playing from 0 to 6.3 s with 34 s buffered and no picture.
        val first = step(elapsedMs = 40_000L, ceilingMs = 80_000L, slowStart = true, bufferedAheadMs = 33_954L,
            lastBufferedAheadMs = 12_240L, ready = true, playing = true, positionMs = 6_300L)
        assertEquals(Step.RestartVideo, first)
        val after = step(elapsedMs = 60_000L, ceilingMs = 80_000L, slowStart = true, bufferedAheadMs = 31_603L,
            lastBufferedAheadMs = 33_954L, ready = true, playing = true, positionMs = 26_356L, lastPositionMs = 6_300L,
            videoRestarted = true)
        assertEquals(Step.Fire("decoder_unresponsive"), after)
    }

    @Test
    fun growingBufferStillExtends() {
        assertEquals(Step.Extend, step(bufferedAheadMs = 12_240L, lastBufferedAheadMs = 0L))
        assertEquals(Step.Fire("no_data_buffered"), step(elapsedMs = 41_000L, bufferedAheadMs = 0L))
    }
}
