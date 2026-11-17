package com.nuvio.tv.ui.screens.player

import android.os.SystemClock
import android.util.Log
import com.nuvio.tv.R
import com.nuvio.tv.core.torrent.TorrentState
import com.nuvio.tv.core.torrent.torrentInitialLoadingProgress
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "PlayerTorrent"

internal suspend fun PlayerRuntimeController.startTorrentStream(
    infoHash: String,
    fileIdx: Int?,
    filename: String? = null,
    trackers: List<String> = emptyList(),
    freshTorrent: Boolean = false
): String {
    isTorrentStream = true
    if (torrentErrorRecoveryHash != infoHash) torrentErrorRecoveryHash = null
    currentInfoHash = infoHash
    currentFileIdx = fileIdx
    lastTorrentRetryTarget = TorrentRetryTarget(infoHash, fileIdx)

    setLoadingStatus(
        phase = "torrent_starting_engine",
        message = context.getString(R.string.player_torrent_starting_engine),
        showOverlay = true
    )
    _uiState.update {
        it.copy(
            showLoadingOverlay = true,
            loadingMessage = context.getString(R.string.player_torrent_starting_engine),
            loadingProgress = null,
            isTorrentStream = true
        )
    }

    val effectiveFilename = filename ?: currentFilename
    return torrentService.startStream(infoHash, fileIdx, effectiveFilename, trackers, freshTorrent)
}

internal fun PlayerRuntimeController.stopTorrentStream() {
    torrentMetadataWatchJob?.cancel()
    torrentMetadataWatchJob = null
    torrentStreamJob?.cancel()
    torrentStreamJob = null
    torrentStateObserverJob?.cancel()
    torrentStateObserverJob = null

    if (isTorrentStream) {
        torrentService.stopStream()
    }

    isTorrentStream = false
    currentInfoHash = null
    currentFileIdx = null
}

internal fun PlayerRuntimeController.observeTorrentState() {
    torrentStateObserverJob?.cancel()
    torrentStateObserverJob = scope.launch {
        collectTorrentStates(torrentService.state, torrentRecoveryStartGuard) { torrentState ->
            when (torrentState) {
                is TorrentState.Idle -> Unit
                is TorrentState.Connecting -> onTorrentConnecting(torrentState)
                is TorrentState.Streaming -> onTorrentStreaming(torrentState)
                is TorrentState.Error -> onTorrentError(torrentState.message)
            }
        }
    }
}

private fun PlayerRuntimeController.onTorrentError(errorMessage: String) {
    val infoHash = currentInfoHash
    val action = TorrentErrorRecovery.decide(
        firstFrameRendered = hasRenderedFirstFrame,
        recoveryUsed = infoHash != null && torrentErrorRecoveryHash == infoHash,
        canRecover = infoHash != null
    )
    Log.e(TAG, "Torrent error: $errorMessage action=$action")
    when (action) {
        TorrentErrorRecovery.Action.Recover -> recoverTorrentAfterError(infoHash!!, errorMessage)
        TorrentErrorRecovery.Action.NextSource -> moveOnAfterTorrentError(errorMessage)
        TorrentErrorRecovery.Action.ShowError -> _uiState.update {
            it.copy(
                error = context.getString(R.string.player_error_torrent, errorMessage),
                showLoadingOverlay = false,
                torrentBufferingMessage = null
            )
        }
    }
}

private fun PlayerRuntimeController.recoverTorrentAfterError(infoHash: String, errorMessage: String) {
    torrentErrorRecoveryHash = infoHash
    val positionMs = currentPlaybackPositionMs()?.takeIf { it > 0L } ?: 0L
    val stream = Stream(
        name = _uiState.value.currentStreamName,
        title = null,
        description = null,
        url = null,
        ytId = null,
        infoHash = infoHash,
        fileIdx = currentFileIdx,
        externalUrl = null,
        behaviorHints = null,
        addonName = currentAddonName ?: "",
        addonLogo = currentAddonLogo,
        sources = currentTorrentSources
    )
    Log.w(
        TAG,
        "TORRENT_RECOVER hash=${infoHash.take(8)} positionMs=$positionMs error=$errorMessage; " +
            "removing the torrent and adding it again"
    )
    releasePlayer()
    resetLoadingOverlayForNewStream()
    _uiState.update {
        it.copy(
            error = null,
            showLoadingOverlay = it.loadingOverlayEnabled,
            torrentBufferingMessage = null,
            pendingSeekPosition = if (positionMs > 0L) positionMs else it.pendingSeekPosition
        )
    }
    launchTorrentSourceStream(stream, infoHash, loadSavedProgress = true, recovering = true)
}

private fun PlayerRuntimeController.moveOnAfterTorrentError(
    errorMessage: String,
    cancelStart: Boolean = true
) {
    if (cancelStart) {
        torrentStreamJob?.cancel()
        torrentStreamJob = null
    }
    torrentErrorRecoveryHash = null
    val positionMs = currentPlaybackPositionMs()?.takeIf { it > 0L } ?: 0L
    Log.w(TAG, "TORRENT_RECOVER failed positionMs=$positionMs error=$errorMessage; moving on")
    val message = context.getString(R.string.player_error_torrent, errorMessage)
    releasePlayer()
    if (positionMs > 0L) _uiState.update { it.copy(pendingSeekPosition = positionMs) }
    if (!advanceToNextLiveSource(message)) {
        surfaceSourceFailoverError(message)
    }
}

private fun PlayerRuntimeController.watchTorrentMetadata(infoHash: String) {
    torrentMetadataWatchJob?.cancel()
    torrentMetadataWatchJob = scope.launch {
        var waitingSinceMs = 0L
        var lastDownloadAtMs: Long? = null
        while (true) {
            delay(TorrentMetadataGiveUp.CHECK_INTERVAL_MS)
            if (currentInfoHash != infoHash || hasRenderedFirstFrame) return@launch
            val state = torrentService.state.value
            if (state is TorrentState.Streaming || state is TorrentState.Error) return@launch
            if (state !is TorrentState.Connecting || state.phase != "add_magnet") continue
            val now = SystemClock.elapsedRealtime()
            if (waitingSinceMs == 0L) waitingSinceMs = now
            if (state.downloadSpeed > 0L) lastDownloadAtMs = now
            val waitedMs = now - waitingSinceMs
            if (TorrentMetadataGiveUp.shouldGiveUp(waitedMs, lastDownloadAtMs?.let { now - it })) {
                giveUpOnTorrentMetadata(state, waitedMs)
                return@launch
            }
        }
    }
}

private fun PlayerRuntimeController.giveUpOnTorrentMetadata(state: TorrentState.Connecting, waitedMs: Long) {
    Log.w(
        TAG,
        "No torrent metadata after ${waitedMs}ms seeds=${state.seeds} peers=${state.peers} " +
            "speed=${state.downloadSpeed}; stopping and moving on"
    )
    val detail = context.getString(
        R.string.player_torrent_connecting_status,
        context.getString(R.string.player_torrent_fetching_metadata),
        context.getString(R.string.player_torrent_peer_info, state.seeds, state.peers),
        formatSpeed(context, state.downloadSpeed)
    )
    val message = context.getString(R.string.player_error_failed_start_torrent, detail)
    stopTorrentStream()
    if (!advanceToNextLiveSource(message)) {
        surfaceSourceFailoverError(message)
    }
}

private fun PlayerRuntimeController.onTorrentConnecting(torrentState: TorrentState.Connecting) {
    if (hasRenderedFirstFrame) return
    val phaseLabel = context.getString(
        when (torrentState.phase) {
            "add_magnet" -> R.string.player_torrent_fetching_metadata
            "prepare_stream", "attach_route" -> R.string.player_torrent_preparing_stream
            else -> R.string.player_torrent_starting_engine
        }
    )
    val message = if (_uiState.value.hideTorrentStats) {
        phaseLabel
    } else {
        context.getString(
            R.string.player_torrent_connecting_status,
            phaseLabel,
            context.getString(R.string.player_torrent_peer_info, torrentState.seeds, torrentState.peers),
            formatSpeed(context, torrentState.downloadSpeed)
        )
    }
    recordLoadingDiagnosticEvent(
        phase = "torrent_connecting_peers",
        message = message
    )
    _uiState.update {
        it.copy(
            showLoadingOverlay = true,
            loadingMessage = message,
            loadingProgress = null,
            torrentBufferingMessage = null
        )
    }
}

private fun PlayerRuntimeController.onTorrentStreaming(torrentState: TorrentState.Streaming) {
    val speed = formatSpeed(context, torrentState.downloadSpeed)
    val peerInfo = context.getString(R.string.player_torrent_peer_info, torrentState.seeds, torrentState.peers)
    val statsHidden = _uiState.value.hideTorrentStats

    if (!hasRenderedFirstFrame) {
        val bufferedAheadMs = _exoPlayer
            ?.let { player -> (player.bufferedPosition - player.currentPosition).coerceAtLeast(0L) }
            ?: 0L
        val progress = torrentInitialLoadingProgress(
            bufferedAheadMs = bufferedAheadMs,
            downloadedBytes = torrentState.downloadedBytes,
            deliveredBytes = torrentState.deliveredBytes
        )
        val message = if (statsHidden) {
            null
        } else {
            context.getString(
                R.string.player_torrent_loading_status,
                formatMB(context, torrentState.loadedBytes),
                peerInfo,
                speed
            )
        }
        recordLoadingDiagnosticEvent(
            phase = "torrent_preloading",
            message = message,
            progress = progress,
            detail = "${torrentState.seeds}/${torrentState.peers}"
        )
        _uiState.update {
            it.copy(
                showLoadingOverlay = true,
                loadingMessage = message,
                loadingProgress = progress,
                torrentDownloadSpeed = torrentState.downloadSpeed,
                torrentUploadSpeed = torrentState.uploadSpeed,
                torrentPeers = torrentState.peers,
                torrentSeeds = torrentState.seeds,
                torrentBufferProgress = torrentState.bufferProgress,
                torrentTotalProgress = torrentState.totalProgress,
                torrentBufferingMessage = null
            )
        }
    } else {
        val message = if (statsHidden) null else context.getString(R.string.player_torrent_status, peerInfo, speed)
        _uiState.update {
            it.copy(
                loadingProgress = null,
                torrentDownloadSpeed = torrentState.downloadSpeed,
                torrentUploadSpeed = torrentState.uploadSpeed,
                torrentPeers = torrentState.peers,
                torrentSeeds = torrentState.seeds,
                torrentBufferProgress = torrentState.bufferProgress,
                torrentTotalProgress = torrentState.totalProgress,
                torrentBufferingMessage = message
            )
        }
    }
}

internal fun PlayerRuntimeController.launchTorrentSourceStream(
    stream: Stream,
    infoHash: String,
    loadSavedProgress: Boolean,
    recovering: Boolean = false
) {
    torrentStreamJob?.cancel()
    val recoveryToken = if (recovering) torrentRecoveryStartGuard.begin() else 0L
    torrentStreamJob = scope.launch {
        try {
            observeTorrentState()
            watchTorrentMetadata(infoHash)

            currentTorrentSources = stream.sources
            val trackers = stream.sources
                ?.filter { it.startsWith("tracker:") }
                ?.map { it.removePrefix("tracker:") }
                ?: emptyList()
            val localUrl = startTorrentStream(
                infoHash = infoHash,
                fileIdx = stream.getEffectiveFileIdx(),
                filename = stream.behaviorHints?.filename,
                trackers = trackers,
                freshTorrent = recovering
            )

            currentStreamUrl = localUrl
            currentHeaders = emptyMap()
            currentStreamMimeType = null
            recordDistinctPluginPlay(
                bingeGroup = stream.behaviorHints?.bingeGroup ?: currentStreamBingeGroup,
                playbackIdentity = infoHash
            )

            preparePlaybackBeforeStart(
                url = localUrl,
                headers = emptyMap(),
                loadSavedProgress = loadSavedProgress
            )
            if (recovering) {
                torrentRecoveryStartGuard.end(recoveryToken)
                val state = torrentService.state.value
                if (currentInfoHash == infoHash && state is TorrentState.Error) onTorrentError(state.message)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start torrent stream", e)
            if (recovering) {
                if (currentInfoHash == infoHash) {
                    moveOnAfterTorrentError(
                        e.message ?: context.getString(R.string.error_unknown),
                        cancelStart = false
                    )
                }
                return@launch
            }
            _uiState.update {
                it.copy(
                    error = context.getString(
                        R.string.player_error_failed_start_torrent,
                        e.message ?: context.getString(R.string.error_unknown)
                    ),
                    showLoadingOverlay = false,
                    loadingProgress = null
                )
            }
        } finally {
            if (recovering) torrentRecoveryStartGuard.end(recoveryToken)
        }
    }
}

private fun formatSpeed(context: android.content.Context, bytesPerSec: Long): String {
    return when {
        bytesPerSec >= 1_048_576 -> context.getString(R.string.unit_speed_mb_s, String.format("%.1f", bytesPerSec / 1_048_576.0))
        bytesPerSec >= 1_024 -> context.getString(R.string.unit_speed_kb_s, String.format("%.0f", bytesPerSec / 1_024.0))
        else -> context.getString(R.string.unit_speed_b_s, bytesPerSec)
    }
}

private fun formatMB(context: android.content.Context, bytes: Long): String =
    context.getString(R.string.unit_size_mb, String.format("%.1f", bytes / 1_048_576.0))
