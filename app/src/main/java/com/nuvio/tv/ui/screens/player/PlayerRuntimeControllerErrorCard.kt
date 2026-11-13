package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import kotlinx.coroutines.flow.update

internal fun PlayerRuntimeController.errorCardFor(error: PlaybackException): PlayerErrorCard? =
    withSendRule(runCatching {
        error.errorCard(
            context = context,
            source = reportSourceLine(),
            lastLoadErrorClass = playbackAnalyticsDiagnostics.lastLoadErrorClass,
            lastLoadErrorMs = playbackAnalyticsDiagnostics.lastLoadErrorDurationMs,
            bytesLoaded = playbackAnalyticsDiagnostics.bytesLoadedSoFar,
        )
    }.getOrNull())

internal fun PlayerRuntimeController.withSendRule(card: PlayerErrorCard?): PlayerErrorCard? {
    if (card == null || !OutsideFailureStreak.blocksSend(card.cause)) return card
    return card.copy(sendAllowed = OutsideFailureStreak.shared.record(card.cause, currentStreamUrl))
}

/** The card for a start-up watchdog reason, or null to keep the general text when Arc cannot prove the cause. */
internal fun PlayerRuntimeController.watchdogErrorCard(reason: String, elapsedMs: Long, player: Player?): PlayerErrorCard? {
    val source = reportSourceLine()
    return when (reason) {
        "video_track_unsupported", "video_never_decoded" -> boxCannotDecodeCard(context, player?.firstVideoTrackFormat(), source, fromWatchdog = true)
        "video_track_unreadable" -> pictureUnreadableCard(context, source)
        "tracks_not_read", "no_video_track", "no_data_buffered" -> {
            val status = playbackAnalyticsDiagnostics.lastLoadErrorHttpStatus
            if (status != null) return httpErrorCard(context, status, source, fromWatchdog = true)
            if (reason == "no_video_track") return null
            val classes = listOfNotNull(playbackAnalyticsDiagnostics.lastLoadErrorClass)
            val seconds = (elapsedMs / 1_000L).toInt()
            when (PlayerErrorCauses.forLoadFailure(classes, playbackAnalyticsDiagnostics.bytesLoadedSoFar)) {
                PlayerErrorCause.ServerUnreachable -> unreachableServerCard(context, source, fromWatchdog = true)
                PlayerErrorCause.ServerSilent -> silentServerCard(context, seconds, source, fromWatchdog = true)
                else -> null
            }
        }
        else -> null
    }
}

private fun Player.firstVideoTrackFormat(): Format? =
    currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.length > 0 }?.getTrackFormat(0)

internal fun PlayerRuntimeController.openSourcesFromErrorCard() {
    if (_uiState.value.showReportPanel) closeReportPanel()
    val state = _uiState.value
    val card = state.errorCard.matching(state.error) ?: return
    _uiState.update { it.copy(error = null, errorCard = null, errorCardBeforeSources = card) }
    showSourcesPanel()
}

/** Leaving the source list without picking one brings the card back, so the screen is never left empty. */
internal fun PlayerRuntimeController.restoreErrorCardAfterSources() {
    _uiState.update {
        val held = it.errorCardBeforeSources
        if (held != null && it.error == null) {
            it.copy(error = held.message, errorCard = held, errorCardBeforeSources = null)
        } else {
            it.copy(errorCardBeforeSources = null)
        }
    }
}
