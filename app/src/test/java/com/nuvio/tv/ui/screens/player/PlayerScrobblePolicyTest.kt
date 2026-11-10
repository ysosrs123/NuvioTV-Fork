package com.nuvio.tv.ui.screens.player

import androidx.media3.common.Player
import com.nuvio.tv.core.tracking.TrackingScrobbleAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerScrobblePolicyTest {
    @Test
    fun `leaving while paused below the watched mark sends a stop`() {
        assertTrue(shouldSendStopWhenLeftPaused(hasPausedScrobble = true, hasActiveScrobble = false, progressPercent = 40f))
        assertTrue(shouldSendStopWhenLeftPaused(hasPausedScrobble = true, hasActiveScrobble = false, progressPercent = 79.99f))
    }

    @Test
    fun `leaving is left to the normal stop when playing, unpaused or past the watched mark`() {
        assertFalse(shouldSendStopWhenLeftPaused(hasPausedScrobble = false, hasActiveScrobble = false, progressPercent = 40f))
        assertFalse(shouldSendStopWhenLeftPaused(hasPausedScrobble = true, hasActiveScrobble = true, progressPercent = 40f))
        assertFalse(shouldSendStopWhenLeftPaused(hasPausedScrobble = true, hasActiveScrobble = false, progressPercent = 80f))
    }

    @Test
    fun `ready non-playing state emits pause`() {
        assertEquals(
            TrackingScrobbleAction.PAUSE,
            trackingActionForNonPlayingState(Player.STATE_READY)
        )
    }

    @Test
    fun `ended and idle states emit stop`() {
        assertEquals(
            TrackingScrobbleAction.STOP,
            trackingActionForNonPlayingState(Player.STATE_ENDED)
        )
        assertEquals(
            TrackingScrobbleAction.STOP,
            trackingActionForNonPlayingState(Player.STATE_IDLE)
        )
    }

    @Test
    fun `buffering state emits no terminal scrobble`() {
        assertNull(trackingActionForNonPlayingState(Player.STATE_BUFFERING))
    }

    @Test
    fun `active pause remains eligible above watched threshold`() {
        assertTrue(shouldSendPauseScrobble(hasActiveScrobble = true, progressPercent = 90f))
    }

    @Test
    fun `pause requires active scrobble and accepts early progress`() {
        assertFalse(shouldSendPauseScrobble(hasActiveScrobble = false, progressPercent = 45f))
        assertTrue(shouldSendPauseScrobble(hasActiveScrobble = true, progressPercent = 0f))
        assertTrue(shouldSendPauseScrobble(hasActiveScrobble = true, progressPercent = 0.5f))
    }

    @Test
    fun `stop closes active sessions below one percent`() {
        assertTrue(shouldSendStopScrobble(hasActiveScrobble = true, progressPercent = 0f))
        assertTrue(shouldSendStopScrobble(hasActiveScrobble = true, progressPercent = 0.5f))
        assertFalse(shouldSendStopScrobble(hasActiveScrobble = false, progressPercent = 0.5f))
    }

    @Test
    fun `completion stop remains eligible without an active session`() {
        assertFalse(shouldSendStopScrobble(hasActiveScrobble = false, progressPercent = 79.99f))
        assertTrue(shouldSendStopScrobble(hasActiveScrobble = false, progressPercent = 80f))
    }

    @Test
    fun `films and episodes are recognised, other content is not`() {
        assertTrue(isFilmOrEpisode("movie", null, null))
        assertTrue(isFilmOrEpisode("Movie", null, null))
        assertTrue(isFilmOrEpisode("series", 1, 3))
        assertTrue(isFilmOrEpisode("tv", 2, 1))
        assertFalse(isFilmOrEpisode("series", null, null))
        assertFalse(isFilmOrEpisode("channel", null, null))
        assertFalse(isFilmOrEpisode("other", 1, 1))
        assertFalse(isFilmOrEpisode(null, 1, 1))
    }

    @Test
    fun `an 8 second stream for a film is an error card`() {
        assertTrue(isErrorCardPlayback(placeholderRejected = false, filmOrEpisode = true, playerDurationMs = 8_000L))
        assertTrue(isErrorCardPlayback(placeholderRejected = false, filmOrEpisode = true, playerDurationMs = 120_999L))
        assertFalse(isErrorCardPlayback(placeholderRejected = false, filmOrEpisode = true, playerDurationMs = 121_000L))
        assertFalse(isErrorCardPlayback(placeholderRejected = false, filmOrEpisode = true, playerDurationMs = 7_080_000L))
    }

    @Test
    fun `an unknown length is not judged`() {
        assertFalse(isErrorCardPlayback(placeholderRejected = false, filmOrEpisode = true, playerDurationMs = 0L))
        assertFalse(isErrorCardPlayback(placeholderRejected = false, filmOrEpisode = true, playerDurationMs = -9_223_372_036_854_775_807L))
    }

    @Test
    fun `short content outside films and episodes keeps its scrobbles and thumbnails`() {
        assertFalse(isErrorCardPlayback(placeholderRejected = false, filmOrEpisode = false, playerDurationMs = 90_000L))
    }

    @Test
    fun `a rejected error card never scrobbles whatever its length`() {
        assertTrue(isErrorCardPlayback(placeholderRejected = true, filmOrEpisode = false, playerDurationMs = 0L))
        assertTrue(isErrorCardPlayback(placeholderRejected = true, filmOrEpisode = true, playerDurationMs = 7_080_000L))
    }

    private val knivesOutMs = 7_813_888L
    private val minute = 60_000L

    @Test
    fun `a retry that lands on a 2 minute clip of a film is a placeholder`() {
        assertTrue(
            isPlaceholderPlayback(
                placeholderRejected = false,
                filmOrEpisode = true,
                playerDurationMs = 120_000L,
                longestDurationMs = knivesOutMs,
                expectedRuntimeMs = null
            )
        )
    }

    @Test
    fun `a clip a little over 2 minutes is still caught against the real file or the runtime`() {
        assertTrue(
            isPlaceholderPlayback(
                placeholderRejected = false,
                filmOrEpisode = true,
                playerDurationMs = 150_000L,
                longestDurationMs = knivesOutMs,
                expectedRuntimeMs = null
            )
        )
        assertTrue(
            isPlaceholderPlayback(
                placeholderRejected = false,
                filmOrEpisode = true,
                playerDurationMs = 150_000L,
                longestDurationMs = 150_000L,
                expectedRuntimeMs = 130 * minute
            )
        )
    }

    @Test
    fun `the real file keeps its progress`() {
        assertFalse(
            isPlaceholderPlayback(
                placeholderRejected = false,
                filmOrEpisode = true,
                playerDurationMs = knivesOutMs,
                longestDurationMs = knivesOutMs,
                expectedRuntimeMs = 130 * minute
            )
        )
    }

    @Test
    fun `short real episodes and shorts are not placeholders`() {
        assertFalse(
            isPlaceholderPlayback(
                placeholderRejected = false,
                filmOrEpisode = true,
                playerDurationMs = 11 * minute,
                longestDurationMs = 11 * minute,
                expectedRuntimeMs = 11 * minute
            )
        )
        assertFalse(
            isPlaceholderPlayback(
                placeholderRejected = false,
                filmOrEpisode = true,
                playerDurationMs = 4 * minute,
                longestDurationMs = 4 * minute,
                expectedRuntimeMs = 45 * minute
            )
        )
        assertFalse(
            isPlaceholderPlayback(
                placeholderRejected = false,
                filmOrEpisode = true,
                playerDurationMs = 150_000L,
                longestDurationMs = 150_000L,
                expectedRuntimeMs = null
            )
        )
    }

    @Test
    fun `trailers and other short content are not judged`() {
        assertFalse(
            isPlaceholderPlayback(
                placeholderRejected = false,
                filmOrEpisode = false,
                playerDurationMs = 90_000L,
                longestDurationMs = knivesOutMs,
                expectedRuntimeMs = 130 * minute
            )
        )
    }

    @Test
    fun `an unknown length is not a placeholder`() {
        assertFalse(
            isPlaceholderPlayback(
                placeholderRejected = false,
                filmOrEpisode = true,
                playerDurationMs = -9_223_372_036_854_775_807L,
                longestDurationMs = knivesOutMs,
                expectedRuntimeMs = 130 * minute
            )
        )
        assertFalse(
            isPlaceholderPlayback(
                placeholderRejected = false,
                filmOrEpisode = true,
                playerDurationMs = 0L,
                longestDurationMs = knivesOutMs,
                expectedRuntimeMs = 130 * minute
            )
        )
    }

    @Test
    fun `a rejected stream is a placeholder whatever its length`() {
        assertTrue(
            isPlaceholderPlayback(
                placeholderRejected = true,
                filmOrEpisode = false,
                playerDurationMs = knivesOutMs,
                longestDurationMs = knivesOutMs,
                expectedRuntimeMs = null
            )
        )
    }

    @Test
    fun `a film whose length was first reported far too long keeps its progress`() {
        assertFalse(
            isPlaceholderPlayback(
                placeholderRejected = false,
                filmOrEpisode = true,
                playerDurationMs = 120 * minute,
                longestDurationMs = 26 * 60 * minute,
                expectedRuntimeMs = null
            )
        )
    }

    @Test
    fun `a runtime under 20 minutes is not used to judge`() {
        assertFalse(
            isPlaceholderPlayback(
                placeholderRejected = false,
                filmOrEpisode = true,
                playerDurationMs = 150_000L,
                longestDurationMs = 150_000L,
                expectedRuntimeMs = 15 * minute
            )
        )
    }

    @Test
    fun `an ended placeholder film opens the sources list once`() {
        assertTrue(
            shouldOfferSourcesWhenPlaceholderEnds(
                placeholderPlayback = true,
                alreadyHandled = false,
                hasError = false,
                filmOrEpisode = true
            )
        )
        assertFalse(
            shouldOfferSourcesWhenPlaceholderEnds(
                placeholderPlayback = true,
                alreadyHandled = true,
                hasError = false,
                filmOrEpisode = true
            )
        )
    }

    @Test
    fun `a real ending, an error or other content does not open the sources list`() {
        assertFalse(
            shouldOfferSourcesWhenPlaceholderEnds(
                placeholderPlayback = false,
                alreadyHandled = false,
                hasError = false,
                filmOrEpisode = true
            )
        )
        assertFalse(
            shouldOfferSourcesWhenPlaceholderEnds(
                placeholderPlayback = true,
                alreadyHandled = false,
                hasError = true,
                filmOrEpisode = true
            )
        )
        assertFalse(
            shouldOfferSourcesWhenPlaceholderEnds(
                placeholderPlayback = true,
                alreadyHandled = false,
                hasError = false,
                filmOrEpisode = false
            )
        )
    }
}
