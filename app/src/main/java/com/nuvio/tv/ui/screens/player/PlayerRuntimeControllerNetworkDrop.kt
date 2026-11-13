package com.nuvio.tv.ui.screens.player

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.PlaybackException
import com.nuvio.tv.core.network.NetworkValidation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A load lost to a short Wi-Fi drop. Returns true when handled here: playback waits for the
 * network and then plays on from [positionMs], instead of spending retries that cannot work.
 */
internal fun PlayerRuntimeController.handleNetworkDropError(error: PlaybackException, positionMs: Long): Boolean {
    val network = NetworkValidation.state.value
    val now = SystemClock.elapsedRealtime()
    val action = NetworkDropRecovery.decide(
        networkError = NetworkDropRecovery.isNetworkDropError(error.errorCode, error.cause),
        msSinceNetworkLost = if (network.known && network.dropId > 0) now - network.droppedAtMs else null,
        validatedNow = network.validated,
        alreadyRecoveredForDrop = network.dropId == networkDropRecoveredId,
        torrent = isTorrentStream || _uiState.value.isTorrentStream,
        live = _uiState.value.isLive || livePlaybackLatched,
        loopback = PlayerMediaSourceFactory.isLoopbackUrl(currentStreamUrl)
    )
    networkDropWatchJob?.cancel()
    networkDropWatchJob = null
    if (action == NetworkDropAction.NONE) return false
    val url = currentStreamUrl
    val headers = currentHeaders.toMap()
    val generation = playbackRecoveryGate.generation
    val position = positionMs.coerceAtLeast(0L)
    when (action) {
        NetworkDropAction.RECOVER -> {
            recoverAfterNetworkDrop(network.dropId, position)
            return true
        }
        NetworkDropAction.WAIT -> {
            Log.i(PlayerRuntimeController.TAG, "NETWORK_DROP waiting for the network at ${position}ms host=${url.safeHost()}")
            cancelStallWatchdog()
            cancelStartupWatchdog()
            showRecoveryOverlay()
            val remainingMs = (NetworkDropRecovery.WINDOW_MS - (now - network.droppedAtMs)).coerceAtLeast(0L)
            networkDropWatchJob = scope.launch {
                val back = withTimeoutOrNull(remainingMs) { NetworkValidation.state.first { it.validated } }
                if (!playbackRecoveryGate.isCurrent(generation, url, headers)) return@launch
                if (back != null && !isInBackground) {
                    recoverAfterNetworkDrop(network.dropId, position)
                } else {
                    Log.w(PlayerRuntimeController.TAG, "NETWORK_DROP network not back in time host=${url.safeHost()}")
                    networkDropRecoveredId = network.dropId
                    currentExoPlayerListener?.onPlayerError(error)
                }
            }
            return true
        }
        else -> {
            networkDropWatchJob = scope.launch {
                val dropped = withTimeoutOrNull(NetworkDropRecovery.WINDOW_MS) {
                    NetworkValidation.state.first { it.dropId > network.dropId }
                } ?: return@launch
                val leftMs = NetworkDropRecovery.WINDOW_MS - (SystemClock.elapsedRealtime() - dropped.droppedAtMs)
                val back = withTimeoutOrNull(leftMs.coerceAtLeast(0L)) {
                    NetworkValidation.state.first { it.validated }
                } ?: return@launch
                if (!playbackRecoveryGate.isCurrent(generation, url, headers) || isInBackground) return@launch
                if (_uiState.value.error == null || back.dropId == networkDropRecoveredId) return@launch
                recoverAfterNetworkDrop(back.dropId, position)
            }
            return false
        }
    }
}

private fun PlayerRuntimeController.recoverAfterNetworkDrop(dropId: Int, positionMs: Long) {
    networkDropRecoveredId = dropId
    Log.i(PlayerRuntimeController.TAG, "NETWORK_DROP network back, playing on at ${positionMs}ms host=${currentStreamUrl.safeHost()}")
    _uiState.update { it.copy(error = null, errorCard = null) }
    scheduleOwnedPlaybackRecovery(positionMs, NetworkDropRecovery.RETURN_SETTLE_MS)
}
