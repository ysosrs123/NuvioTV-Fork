package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import kotlinx.coroutines.flow.update

internal fun PlayerRuntimeController.errorCardFor(error: PlaybackException): PlayerErrorCard? {
    heldFailoverCard = null
    return withSendRule(plainErrorCardFor(error))
}

private fun PlayerRuntimeController.plainErrorCardFor(error: PlaybackException): PlayerErrorCard? =
    runCatching {
        error.errorCard(
            context = context,
            source = reportSourceLine(),
            lastLoadErrorClasses = playbackAnalyticsDiagnostics.lastLoadErrorClasses,
            lastLoadErrorMs = playbackAnalyticsDiagnostics.lastLoadErrorDurationMs,
            bytesLoaded = playbackAnalyticsDiagnostics.bytesLoadedSoFar,
        )
    }.getOrNull()

internal fun PlayerRuntimeController.withSendRule(card: PlayerErrorCard?): PlayerErrorCard? =
    card?.applySendRule(currentStreamUrl, OutsideFailureStreak.shared)

/** Keeps the card for this player error, for the error screen a search for another source may end on. */
internal fun PlayerRuntimeController.holdFailoverCard(error: PlaybackException, detailedError: String) {
    holdFailoverCard(plainErrorCardFor(error), detailedError)
}

internal fun PlayerRuntimeController.holdFailoverCard(card: PlayerErrorCard?, detailedError: String) {
    heldFailoverCard = card?.let { HeldFailoverCard.hold(currentStreamUrl, detailedError, it, OutsideFailureStreak.shared) }
}

/** The held card, with the Send rule, when the search for another source ends on the same link and error. */
internal fun PlayerRuntimeController.takeHeldFailoverCard(detailedError: String): PlayerErrorCard? {
    val held = heldFailoverCard ?: return null
    heldFailoverCard = null
    return held.forEnd(currentStreamUrl, detailedError, OutsideFailureStreak.shared)
}

/** The card for a start-up watchdog reason, or null to keep the general text when Arc cannot prove the cause. */
internal fun PlayerRuntimeController.watchdogErrorCard(
    reason: String,
    elapsedMs: Long,
    player: Player?,
    bufferedAheadMs: Long = 0L
): PlayerErrorCard? {
    val source = reportSourceLine()
    return when {
        reason == "video_track_unsupported" || reason == "video_never_decoded" ->
            boxCannotDecodeCard(context, player?.firstVideoTrackFormat(), source, fromWatchdog = true)
        reason == "video_track_unreadable" -> pictureUnreadableCard(context, source)
        watchdogChecksLoad(reason, bufferedAheadMs) -> {
            val status = playbackAnalyticsDiagnostics.lastLoadErrorHttpStatus
            if (status != null) return httpErrorCard(context, status, source, fromWatchdog = true)
            if (reason == "no_video_track") return null
            val classes = playbackAnalyticsDiagnostics.lastLoadErrorClasses
            val seconds = (elapsedMs / 1_000L).toInt()
            val bytes = playbackAnalyticsDiagnostics.bytesLoadedSoFar
            val filmRate = PlayerErrorCauses.filmBitsPerSecond(
                PlaybackByteCounter.contentLengthFor(currentStreamUrl),
                expectedRuntimeMinutes
            )
            when (PlayerErrorCauses.forLoadFailure(classes, bytes)) {
                PlayerErrorCause.ServerUnreachable -> unreachableServerCard(context, source, fromWatchdog = true)
                PlayerErrorCause.ServerSilent -> silentServerCard(context, seconds, source, fromWatchdog = true, bytesLoaded = bytes)
                else -> filmRate?.takeIf { PlayerErrorCauses.isSlowSource(bytes, elapsedMs, it) }
                    ?.let { slowSourceCard(context, bytes, elapsedMs, it, source) }
            }
        }
        else -> null
    }
}

/**
 * Whether a watchdog reason is looked at as a loading problem (no answer, silent, slow). A decoder that never
 * answered with little buffered usually means the source is slow, not the decoder.
 */
internal fun watchdogChecksLoad(reason: String, bufferedAheadMs: Long): Boolean = when (reason) {
    "tracks_not_read", "no_video_track", "no_data_buffered" -> true
    "decoder_unresponsive" -> bufferedAheadMs < StartupWatchdogDecision.WELL_AHEAD_MS
    else -> false
}

/** What an MPV start card may use: only a fresh HTTP status, and no log line when the start simply stalled. */
internal fun mpvStartCardEvidence(httpCode: Int?, httpFresh: Boolean, logLine: String?, source: String): Pair<Int?, String?> =
    httpCode?.takeIf { httpFresh } to logLine?.takeIf { source != "startup_stall" && source != "startup_absolute" }

/** The card for an MPV start that failed, from MPV's own answer: an HTTP status or a refused connection in its log. */
internal fun PlayerRuntimeController.mpvStartErrorCard(httpCode: Int?, logLine: String?): PlayerErrorCard? {
    heldFailoverCard = null
    return withSendRule(plainMpvStartErrorCard(httpCode, logLine))
}

internal fun PlayerRuntimeController.plainMpvStartErrorCard(httpCode: Int?, logLine: String?): PlayerErrorCard? {
    val source = reportSourceLine()
    if (httpCode != null) return httpErrorCard(context, httpCode, source)
    return when (PlayerErrorCauses.forMpvLoadFailure(logLine)) {
        PlayerErrorCause.ServerUnreachable -> unreachableServerCard(context, source, fromWatchdog = false)
        else -> null
    }
}

private fun Player.firstVideoTrackFormat(): Format? =
    currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.length > 0 }?.getTrackFormat(0)

internal fun PlayerRuntimeController.openSourcesFromErrorCard() {
    if (_uiState.value.showReportPanel) closeReportPanel()
    val state = _uiState.value
    val card = state.errorCard.matching(state.error) ?: return
    cancelStartupWatchdog()
    _uiState.update { it.copy(error = null, errorCard = null, errorCardBeforeSources = card) }
    showSourcesPanel()
}

/** Leaving the source list without picking one brings the card back, so the screen is never left empty. */
internal fun PlayerRuntimeController.restoreErrorCardAfterSources() {
    val watchdogMessages = startupWatchdogMessages(context)
    _uiState.update {
        val fromWatchdog = it.errorCard.matching(it.error)?.fromWatchdog == true || it.error in watchdogMessages
        it.withHeldErrorCardRestored(errorFromWatchdog = fromWatchdog)
    }
}

/** A player error that comes after the start-up watchdog's card for the same link must not replace or retry it. */
internal fun PlayerUiState.showsStartupWatchdogCard(watchdogCardUrl: String?, streamUrl: String): Boolean =
    watchdogCardUrl != null && watchdogCardUrl == streamUrl && error != null

/** The start-up watchdog writes only over an empty screen, never while a card waits behind the source list. */
internal fun PlayerUiState.startupWatchdogMayWrite(): Boolean = error == null && errorCardBeforeSources == null

/** A new stream start ends the wait behind the source list: the held card belongs to the stream that failed. */
internal fun PlayerUiState.withHeldErrorCardDropped(): PlayerUiState =
    if (errorCardBeforeSources == null) this else copy(errorCardBeforeSources = null)

/** The held card comes back over an empty screen or a start-up watchdog message; any other error stays. */
internal fun PlayerUiState.withHeldErrorCardRestored(errorFromWatchdog: Boolean): PlayerUiState {
    val held = errorCardBeforeSources ?: return this
    return if (error == null || errorFromWatchdog) {
        copy(error = held.message, errorCard = held, errorCardBeforeSources = null)
    } else {
        copy(errorCardBeforeSources = null)
    }
}
