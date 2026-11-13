package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.ui.screens.player.StartupWatchdogDecision.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupUnreadVideoMessageTest {
    private fun fire(
        tracksRead: Boolean = true,
        hasVideoTrack: Boolean = false,
        videoSelected: Boolean = false,
        bufferedAheadMs: Long = 0L,
        ready: Boolean = false,
        fileName: String? = "Film.ProRes4444.mov",
        streamMimeType: String? = "video/quicktime",
    ) = StartupWatchdogDecision.next(
        elapsedMs = 60_000L,
        intervalMs = 20_000L,
        ceilingMs = 60_000L,
        slowStart = false,
        bufferedAheadMs = bufferedAheadMs,
        lastBufferedAheadMs = bufferedAheadMs,
        ready = ready,
        playing = false,
        positionMs = 0L,
        lastPositionMs = 0L,
        videoRestarted = false,
        tracksRead = tracksRead,
        hasVideoTrack = hasVideoTrack,
        videoSelected = videoSelected,
        videoFileWithUnreadPicture = StartupWatchdogDecision.isVideoFileWithUnreadPicture(fileName, streamMimeType),
    )

    @Test
    fun aQuickTimeFilmReadWithoutItsPictureSaysTheFormatCannotPlay() {
        assertEquals(Step.Fire("video_track_unreadable"), fire())
        assertEquals(Step.Fire("video_track_unreadable"), fire(bufferedAheadMs = 50_000L, ready = true))
        assertEquals(
            Step.Fire("video_track_unreadable"),
            fire(fileName = "https://host.example/dl/Film.MOV?token=abc", streamMimeType = "application/octet-stream")
        )
        assertEquals(Step.Fire("video_track_unreadable"), fire(fileName = null, streamMimeType = "video/mp4; charset=binary"))
    }

    @Test
    fun aVideoTrackThatWasReadKeepsItsOwnMessages() {
        assertEquals(Step.Fire("video_track_unsupported"), fire(hasVideoTrack = true))
        assertEquals(Step.Fire("no_data_buffered"), fire(hasVideoTrack = true, videoSelected = true))
        assertEquals(
            Step.Fire("decoder_unresponsive"),
            fire(hasVideoTrack = true, videoSelected = true, bufferedAheadMs = 5_000L, ready = true)
        )
        assertEquals(Step.Fire("tracks_not_read"), fire(tracksRead = false))
    }

    @Test
    fun soundOnlyAndOtherContainersKeepTheOldMessage() {
        assertEquals(Step.Fire("no_video_track"), fire(fileName = "Album.m4a", streamMimeType = "audio/mp4"))
        assertEquals(Step.Fire("no_video_track"), fire(fileName = "Song.mp4", streamMimeType = "audio/mp4"))
        assertEquals(Step.Fire("no_video_track"), fire(fileName = "radio.mp3", streamMimeType = "audio/mpeg"))
        assertEquals(Step.Fire("no_video_track"), fire(fileName = "Film.mkv", streamMimeType = "video/x-matroska"))
        assertEquals(Step.Fire("no_video_track"), fire(fileName = "live.m3u8", streamMimeType = "application/x-mpegURL"))
        assertEquals(Step.Fire("no_video_track"), fire(fileName = null, streamMimeType = null))
    }

    @Test
    fun onlyMp4AndQuickTimeFilesCount() {
        assertTrue(StartupWatchdogDecision.isVideoFileWithUnreadPicture("a.mov", null))
        assertTrue(StartupWatchdogDecision.isVideoFileWithUnreadPicture("a.m4v#t=10", null))
        assertTrue(StartupWatchdogDecision.isVideoFileWithUnreadPicture(null, "Video/QuickTime"))
        assertFalse(StartupWatchdogDecision.isVideoFileWithUnreadPicture("a.mov.mkv", null))
        assertFalse(StartupWatchdogDecision.isVideoFileWithUnreadPicture("movie", "video/mp2t"))
        assertFalse(StartupWatchdogDecision.isVideoFileWithUnreadPicture("a.ts", null))
    }
}
