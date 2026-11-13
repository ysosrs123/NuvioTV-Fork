package com.nuvio.tv.ui.screens.player

/** What the start-up watchdog does at one check when no first frame has rendered yet. */
internal object StartupWatchdogDecision {
    sealed interface Step {
        /** A slow server is still delivering its first data: keep waiting up to its ceiling. */
        data object WaitForData : Step
        /** Buffered data keeps growing: wait one more interval. */
        data object Extend : Step
        /** The server took the request and its read wait has not run out yet: wait one more interval, once. */
        data object WaitForRead : Step
        /** The sound plays but no picture came: restart the video output in place once. */
        data object RestartVideo : Step
        data class Fire(val reason: String) : Step
    }

    /** Playback moved on this far since the last check without a picture: the sound is playing alone. */
    const val PLAYING_WITHOUT_PICTURE_MS = 2_000L

    /** Read timeout of the playback HTTP client. */
    const val READ_WAIT_MS = 15_000L

    /** Buffered this far ahead with an open decoder and still no picture: the decoder is fed but cannot show it. */
    const val WELL_AHEAD_MS = 10_000L

    fun next(
        elapsedMs: Long,
        intervalMs: Long,
        ceilingMs: Long,
        slowStart: Boolean,
        bufferedAheadMs: Long,
        lastBufferedAheadMs: Long,
        ready: Boolean,
        playing: Boolean,
        positionMs: Long,
        lastPositionMs: Long,
        videoRestarted: Boolean,
        tracksRead: Boolean,
        hasVideoTrack: Boolean,
        videoSelected: Boolean,
        playbackThreadAnswers: Boolean = true,
        videoFileWithUnreadPicture: Boolean = false,
        videoDecoderOpened: Boolean = false,
        openRequestAgeMs: Long? = null,
        loadErrorSeen: Boolean = false,
        readWaitGiven: Boolean = false,
    ): Step {
        val anotherIntervalFits = elapsedMs + intervalMs <= ceilingMs
        if (slowStart && bufferedAheadMs == 0L && !ready && anotherIntervalFits) return Step.WaitForData
        if (ready && playing && hasVideoTrack && videoSelected && !videoRestarted &&
            positionMs - lastPositionMs >= PLAYING_WITHOUT_PICTURE_MS
        ) return Step.RestartVideo
        if (bufferedAheadMs > lastBufferedAheadMs && anotherIntervalFits) return Step.Extend
        if (bufferedAheadMs == 0L && !ready && playbackThreadAnswers && !loadErrorSeen && !readWaitGiven &&
            openRequestAgeMs != null && openRequestAgeMs < READ_WAIT_MS && anotherIntervalFits
        ) return Step.WaitForRead
        return Step.Fire(
            when {
                !tracksRead -> "tracks_not_read"
                hasVideoTrack && !videoSelected -> "video_track_unsupported"
                !hasVideoTrack && videoFileWithUnreadPicture -> "video_track_unreadable"
                !hasVideoTrack -> "no_video_track"
                bufferedAheadMs == 0L && !ready && playbackThreadAnswers -> "no_data_buffered"
                videoDecoderOpened && playbackThreadAnswers && !ready && bufferedAheadMs >= WELL_AHEAD_MS ->
                    "video_never_decoded"
                else -> "decoder_unresponsive"
            }
        )
    }

    /**
     * MP4 and QuickTime files whose picture is in a format the reader does not know (Apple ProRes and other editing
     * formats) come back with their sound tracks only, so the tracks show no video at all.
     */
    fun isVideoFileWithUnreadPicture(fileName: String?, streamMimeType: String?): Boolean {
        val mime = streamMimeType?.lowercase()?.substringBefore(';')?.trim().orEmpty()
        if (mime in MP4_FAMILY_VIDEO_MIMES) return true
        if (mime.startsWith("audio/")) return false
        val name = fileName?.substringBefore('?')?.substringBefore('#')?.lowercase() ?: return false
        return MP4_FAMILY_VIDEO_EXTENSIONS.any { name.endsWith(it) }
    }

    private val MP4_FAMILY_VIDEO_MIMES = setOf("video/quicktime", "video/mp4", "video/x-m4v")
    private val MP4_FAMILY_VIDEO_EXTENSIONS = listOf(".mov", ".qt", ".mp4", ".m4v")
}
