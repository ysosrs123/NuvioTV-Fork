package com.nuvio.tv.ui.screens.player

import android.util.Log
import com.nuvio.tv.R
import com.nuvio.tv.core.player.PlaceholderStreamPolicy
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
    runCatching { _exoPlayer?.stop() }
    cancelNextEpisodeAutoPlayOnFatalError()
    _uiState.update {
        it.copy(
            error = context.getString(R.string.player_error_placeholder_stream),
            showLoadingOverlay = false
        )
    }
}
