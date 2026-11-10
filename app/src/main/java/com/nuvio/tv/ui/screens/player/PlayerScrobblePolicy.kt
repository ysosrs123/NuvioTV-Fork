package com.nuvio.tv.ui.screens.player

import androidx.media3.common.Player
import com.nuvio.tv.core.player.PlaceholderStreamPolicy
import com.nuvio.tv.core.tracking.TrackingScrobbleAction

internal fun trackingActionForNonPlayingState(playbackState: Int): TrackingScrobbleAction? = when (playbackState) {
    Player.STATE_BUFFERING -> null
    Player.STATE_ENDED, Player.STATE_IDLE -> TrackingScrobbleAction.STOP
    else -> TrackingScrobbleAction.PAUSE
}

internal fun shouldSendPauseScrobble(
    hasActiveScrobble: Boolean,
    progressPercent: Float
): Boolean = hasActiveScrobble && progressPercent in 0f..100f

internal fun shouldSendStopScrobble(
    hasActiveScrobble: Boolean,
    progressPercent: Float
): Boolean = hasActiveScrobble || progressPercent >= 80f

internal fun shouldSendStopWhenLeftPaused(
    hasPausedScrobble: Boolean,
    hasActiveScrobble: Boolean,
    progressPercent: Float
): Boolean = hasPausedScrobble && !hasActiveScrobble && progressPercent in 0f..<80f

/** A film, or an episode of a series: real streams of these are never under about 2 minutes. */
internal fun isFilmOrEpisode(contentType: String?, season: Int?, episode: Int?): Boolean {
    val type = contentType?.trim()?.lowercase() ?: return false
    if (type == "movie") return true
    return type in listOf("series", "tv", "anime") && season != null && episode != null
}

/**
 * No scrobbles and no thumbnails: the stream was rejected as an error card, or a film or
 * episode plays a stream under about 2 minutes. [playerDurationMs] is the player's own length
 * for this stream, not the longest one seen, which a failed attempt of a real file can leave behind.
 */
internal fun isErrorCardPlayback(
    placeholderRejected: Boolean,
    filmOrEpisode: Boolean,
    playerDurationMs: Long
): Boolean = placeholderRejected || (filmOrEpisode && isShortPlaceholderDuration(playerDurationMs))

/**
 * No progress, no scrobbles and no watched mark: an error card as above, or a film or episode stream far
 * shorter than the title. [longestDurationMs] is the longest length this playback has shown, which a retry
 * that lands on a provider's error clip keeps from the real file; [expectedRuntimeMs] is the title's runtime.
 */
internal fun isPlaceholderPlayback(
    placeholderRejected: Boolean,
    filmOrEpisode: Boolean,
    playerDurationMs: Long,
    longestDurationMs: Long,
    expectedRuntimeMs: Long?
): Boolean {
    if (isErrorCardPlayback(placeholderRejected, filmOrEpisode, playerDurationMs)) return true
    if (!filmOrEpisode || playerDurationMs <= 0L) return false
    if (playerDurationMs < MAX_PLACEHOLDER_LENGTH_MS &&
        playerDurationMs < longestDurationMs / PLACEHOLDER_LENGTH_DIVISOR
    ) return true
    val verdict = PlaceholderStreamPolicy.evaluate(
        contentLengthBytes = null,
        durationMs = playerDurationMs,
        expectedRuntimeMs = expectedRuntimeMs
    )
    return verdict is PlaceholderStreamPolicy.Verdict.Reject &&
        verdict.reason == PlaceholderStreamPolicy.Reason.ImplausibleDuration
}

private const val PLACEHOLDER_LENGTH_DIVISOR = 10L
private const val MAX_PLACEHOLDER_LENGTH_MS = 10L * 60_000L

/** When a placeholder film or episode stream ends, the sources list opens once, unless an error already shows. */
internal fun shouldOfferSourcesWhenPlaceholderEnds(
    placeholderPlayback: Boolean,
    alreadyHandled: Boolean,
    hasError: Boolean,
    filmOrEpisode: Boolean
): Boolean = placeholderPlayback && filmOrEpisode && !alreadyHandled && !hasError
