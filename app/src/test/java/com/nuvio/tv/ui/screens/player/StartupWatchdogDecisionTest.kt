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
        videoDecoderOpened: Boolean = false,
        openRequestAgeMs: Long? = null,
        loadErrorSeen: Boolean = false,
        readWaitGiven: Boolean = false,
    ) = StartupWatchdogDecision.next(
        elapsedMs, 20_000L, ceilingMs, slowStart, bufferedAheadMs, lastBufferedAheadMs, ready, playing,
        positionMs, lastPositionMs, videoRestarted, tracksRead, hasVideoTrack, videoSelected, playbackThreadAnswers,
        videoDecoderOpened = videoDecoderOpened,
        openRequestAgeMs = openRequestAgeMs,
        loadErrorSeen = loadErrorSeen,
        readWaitGiven = readWaitGiven,
    )

    @Test
    fun anOpenDecoderFedWellAheadWithNoPictureIsAFormatTheBoxCannotShow() {
        // AV1 labelled Dolby Vision: 120 s buffered, DV decoder open, never a frame.
        assertEquals(Step.Fire("video_never_decoded"), step(elapsedMs = 40_000L, bufferedAheadMs = 120_000L,
            lastBufferedAheadMs = 120_000L, videoDecoderOpened = true))
        // Decoder not open, or only a little buffered: still the decoder message.
        assertEquals(Step.Fire("decoder_unresponsive"), step(elapsedMs = 40_000L, bufferedAheadMs = 120_000L,
            lastBufferedAheadMs = 120_000L))
        assertEquals(Step.Fire("decoder_unresponsive"), step(elapsedMs = 40_000L, bufferedAheadMs = 4_000L,
            lastBufferedAheadMs = 4_000L, videoDecoderOpened = true))
        // Ready means the renderers are going; that stays with the existing paths.
        assertEquals(Step.Fire("decoder_unresponsive"), step(elapsedMs = 40_000L, bufferedAheadMs = 120_000L,
            lastBufferedAheadMs = 120_000L, videoDecoderOpened = true, ready = true))
    }

    @Test
    fun aRequestStillInsideItsReadWaitGetsOneMoreInterval() {
        // Resume point answered 12 s ago, nothing buffered yet, no load error.
        assertEquals(Step.WaitForRead, step(openRequestAgeMs = 12_000L, videoDecoderOpened = true))
        // Only once.
        assertEquals(Step.Fire("no_data_buffered"), step(elapsedMs = 40_000L, openRequestAgeMs = 3_000L, readWaitGiven = true))
        // Not after a load error, not past the read wait, not with no request open, not past the ceiling.
        assertEquals(Step.Fire("no_data_buffered"), step(openRequestAgeMs = 12_000L, loadErrorSeen = true))
        assertEquals(Step.Fire("no_data_buffered"), step(openRequestAgeMs = 16_000L))
        assertEquals(Step.Fire("no_data_buffered"), step(openRequestAgeMs = null))
        assertEquals(Step.Fire("no_data_buffered"), step(elapsedMs = 41_000L, ceilingMs = 60_000L, openRequestAgeMs = 12_000L))
        // A stuck player thread is not a slow server.
        assertEquals(Step.Fire("decoder_unresponsive"), step(openRequestAgeMs = 12_000L, playbackThreadAnswers = false))
    }

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
