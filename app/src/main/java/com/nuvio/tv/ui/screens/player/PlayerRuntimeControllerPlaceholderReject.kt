package com.nuvio.tv.ui.screens.player

import android.util.Log
import android.widget.Toast
import com.nuvio.tv.R
import com.nuvio.tv.core.player.PlaceholderStreamPolicy
import com.nuvio.tv.core.player.thumbnail.SeekThumbnails
import kotlinx.coroutines.flow.update

/**
 * Stops the stream that [PlaceholderStreamPolicy] rejected. Called from the byte
 * floor at STATE_READY and from the duration backstop on the progress tick
 * (decoded duration is only trustworthy once playback is under way).
 *
 * Halting by setting [error] also keeps a rejected placeholder from marking the
 * title watched, saving progress or arming next-episode auto-play, since every
 * watch-state consumer derives hasFatalError from a non-blank error.
 *
 * Upstream: NuvioMedia/NuvioTV. Licensed under GPL-3.0.
 */
internal fun PlayerRuntimeController.rejectPlaceholderStream(
    verdict: PlaceholderStreamPolicy.Verdict.Reject
) {
    Log.w(
        PlayerRuntimeController.TAG,
        "PLACEHOLDER_REJECT reason=${verdict.reason} detail=${verdict.detail} " +
            "host=${currentStreamUrl.safeHost()}"
    )
    placeholderRejectedForCurrentStream = true
    SeekThumbnails.stopSession(handBackPlayer = false)
    runCatching { _exoPlayer?.stop() }
    cancelNextEpisodeAutoPlayOnFatalError()
    val card = withSendRule(providerNoticeCard(context, reportSourceLine())) ?: return
    _uiState.update {
        it.copy(
            error = card.message,
            errorCard = card,
            showLoadingOverlay = false
        )
    }
}

/**
 * A film or episode stream judged a placeholder played to its end: says so and opens the sources
 * list instead of finishing the title. Runs once per stream.
 */
internal fun PlayerRuntimeController.offerSourcesAfterPlaceholderEnded() {
    if (!shouldOfferSourcesWhenPlaceholderEnds(
            placeholderPlayback = isPlaceholderPlayback(),
            alreadyHandled = placeholderRejectedForCurrentStream,
            hasError = !_uiState.value.error.isNullOrBlank(),
            filmOrEpisode = isFilmOrEpisode(contentType, currentSeason, currentEpisode)
        )
    ) return
    placeholderRejectedForCurrentStream = true
    Log.w(
        PlayerRuntimeController.TAG,
        "PLACEHOLDER_ENDED durationMs=${currentPlaybackDurationMs()} longestMs=$lastKnownDuration " +
            "host=${currentStreamUrl.safeHost()}"
    )
    cancelNextEpisodeAutoPlayOnFatalError()
    Toast.makeText(context, context.getString(R.string.player_error_placeholder_stream), Toast.LENGTH_LONG).show()
    showSourcesPanel()
}
