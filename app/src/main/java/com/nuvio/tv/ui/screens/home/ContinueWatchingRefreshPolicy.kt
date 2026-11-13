package com.nuvio.tv.ui.screens.home

internal const val CW_META_MAX_AGE_MS = 24L * 60 * 60 * 1000
internal const val CW_FILM_META_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
internal const val CW_CAUGHT_UP_RECHECK_MS = 24L * 60 * 60 * 1000
internal const val CW_ENDED_SERIES_RECHECK_MS = 7L * 24 * 60 * 60 * 1000

/**
 * When a stored episode list should be fetched again: after [CW_META_MAX_AGE_MS], or sooner
 * once the next episode it knows about has aired.
 */
internal fun cwMetaRefreshDueAtMs(nextEpisodeAirMs: Long?, nowMs: Long): Long {
    val maxAgeMs = nowMs + CW_META_MAX_AGE_MS
    return if (nextEpisodeAirMs != null && nextEpisodeAirMs > nowMs) {
        minOf(maxAgeMs, nextEpisodeAirMs)
    } else {
        maxAgeMs
    }
}

/** How long stored details count as fresh: a film 30 days, an ended show 7 days, a running show until its next episode airs. */
internal fun cwMetaKeepUntilMs(isFilm: Boolean, ended: Boolean, nextEpisodeAirMs: Long?, nowMs: Long): Long = when {
    isFilm -> nowMs + CW_FILM_META_MAX_AGE_MS
    ended -> nowMs + CW_ENDED_SERIES_RECHECK_MS
    else -> cwMetaRefreshDueAtMs(nextEpisodeAirMs, nowMs)
}

/** A stored episode list is fetched again once it is due, but never while the player is open. */
internal fun shouldRefreshCwMeta(refreshAtMs: Long?, nowMs: Long, playerOpen: Boolean): Boolean =
    !playerOpen && (refreshAtMs == null || nowMs >= refreshAtMs)

internal fun isEndedSeriesStatus(status: String?): Boolean =
    when (status?.trim()?.lowercase()) {
        "ended", "canceled", "cancelled" -> true
        else -> false
    }

/**
 * When a caught-up show is checked again: its next known release, else a day later
 * (a week for a show that has ended).
 */
internal fun caughtUpRecheckAtMs(nextReleaseMs: Long?, ended: Boolean, nowMs: Long): Long =
    nextReleaseMs?.takeIf { it > nowMs }
        ?: (nowMs + if (ended) CW_ENDED_SERIES_RECHECK_MS else CW_CAUGHT_UP_RECHECK_MS)

/** Older shows already looked up in this run whose check-again time has passed. */
internal fun olderSeedsDueAgain(
    processedContentIds: Set<String>,
    hasBeenValidated: (String) -> Boolean,
    isValidationFresh: (String) -> Boolean
): Set<String> = processedContentIds.filterTo(mutableSetOf()) { contentId ->
    hasBeenValidated(contentId) && !isValidationFresh(contentId)
}
