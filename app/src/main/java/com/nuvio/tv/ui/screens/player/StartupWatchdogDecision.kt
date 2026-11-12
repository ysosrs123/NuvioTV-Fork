package com.nuvio.tv.ui.screens.player

/** What the start-up watchdog does at one check when no first frame has rendered yet. */
internal object StartupWatchdogDecision {
    sealed interface Step {
        /** A slow server is still delivering its first data: keep waiting up to its ceiling. */
        data object WaitForData : Step
        /** Buffered data keeps growing: wait one more interval. */
        data object Extend : Step
        /** The sound plays but no picture came: restart the video output in place once. */
        data object RestartVideo : Step
        data class Fire(val reason: String) : Step
    }

    /** Playback moved on this far since the last check without a picture: the sound is playing alone. */
    const val PLAYING_WITHOUT_PICTURE_MS = 2_000L

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
    ): Step {
        val anotherIntervalFits = elapsedMs + intervalMs <= ceilingMs
        if (slowStart && bufferedAheadMs == 0L && !ready && anotherIntervalFits) return Step.WaitForData
        if (ready && playing && hasVideoTrack && videoSelected && !videoRestarted &&
            positionMs - lastPositionMs >= PLAYING_WITHOUT_PICTURE_MS
        ) return Step.RestartVideo
        if (bufferedAheadMs > lastBufferedAheadMs && anotherIntervalFits) return Step.Extend
        return Step.Fire(
            when {
                !tracksRead -> "tracks_not_read"
                hasVideoTrack && !videoSelected -> "video_track_unsupported"
                !hasVideoTrack -> "no_video_track"
                bufferedAheadMs == 0L && !ready && playbackThreadAnswers -> "no_data_buffered"
                else -> "decoder_unresponsive"
            }
        )
    }
}
