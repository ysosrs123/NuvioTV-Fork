package com.nuvio.tv.data.trailer

import com.nuvio.tv.core.tmdb.TmdbService
import java.time.Instant
import kotlinx.coroutines.CancellationException

internal const val TRAILER_PREVIEW_RETRY_WINDOW_MS = 10 * 60_000L
internal const val TRAILER_PREVIEW_SHORT_MISS_WINDOW_MS = 2 * 60_000L
private const val TRAILER_PREVIEW_MAX_YT_IDS = 3
private const val TRAILER_PREVIEW_LOOKUP_HEAD_START_MS = 2_200L
private const val TRAILER_PREVIEW_LOOKUP_MIN_SETTLE_MS = 350L
private const val TRAILER_PREVIEW_LOOKUP_MAX_SETTLE_MS = 1_100L

/**
 * How long a title stays focused before its trailer is looked up. The trailer can only show after
 * [showAfterMs], so the lookup starts as late as still leaves it time to be ready by then.
 */
internal fun trailerPreviewLookupSettleMs(showAfterMs: Long): Long =
    (showAfterMs - TRAILER_PREVIEW_LOOKUP_HEAD_START_MS)
        .coerceIn(TRAILER_PREVIEW_LOOKUP_MIN_SETTLE_MS, TRAILER_PREVIEW_LOOKUP_MAX_SETTLE_MS)

/** [rememberMiss] is set when nothing exists to try or YouTube refused it, never for timeouts or rate limits. */
internal data class TrailerPreviewLookup(
    val source: TrailerPlaybackSource?,
    val rememberMiss: Boolean = false
)

internal fun trailerPreviewYtIds(ids: List<String>): List<String> =
    ids.mapNotNull(::youTubeVideoIdOf).distinct().take(TRAILER_PREVIEW_MAX_YT_IDS)

internal fun isTrailerPreviewMissFresh(missedAtMs: Long, nowMs: Long): Boolean =
    nowMs - missedAtMs < TRAILER_PREVIEW_RETRY_WINDOW_MS

internal fun isTrailerPreviewLinkExpired(videoUrl: String, audioUrl: String?, now: Instant): Boolean =
    isTrailerUrlExpired(videoUrl, now) || (audioUrl != null && isTrailerUrlExpired(audioUrl, now))

/** The time to store for a miss: one that may pass (timeout, failed request) is tried again after two minutes. */
internal fun trailerPreviewMissTimestamp(definite: Boolean, nowMs: Long): Long =
    if (definite) nowMs else nowMs - (TRAILER_PREVIEW_RETRY_WINDOW_MS - TRAILER_PREVIEW_SHORT_MISS_WINDOW_MS)

/** A link that fails in the player is looked up once more; a second failure inside the window is a miss. */
internal fun shouldRetryTrailerPreviewAfterFailure(lastRetryAtMs: Long?, nowMs: Long): Boolean =
    lastRetryAtMs == null || nowMs - lastRetryAtMs >= TRAILER_PREVIEW_RETRY_WINDOW_MS

/** The trailer lookup behind every card preview: TMDB (or IMDb) first, then the item's own trailer ids. */
internal suspend fun lookupTrailerPreview(
    trailerService: TrailerService,
    tmdbService: TmdbService,
    itemId: String,
    apiType: String,
    title: String,
    year: String?,
    imdbId: String?,
    ytIds: List<String>
): TrailerPreviewLookup {
    val nativeImdbId = imdbId?.takeIf { it.matches(Regex("tt\\d+")) }
        ?: itemId.takeIf { it.matches(Regex("tt\\d+")) }
    val tmdbId = nativeImdbId ?: try {
        tmdbService.ensureTmdbId(itemId, apiType, fallbackImdbId = imdbId)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    val lookup = trailerService.lookupTrailer(
        title = title,
        year = year,
        tmdbId = tmdbId,
        type = apiType
    )
    if (!lookup.source?.videoUrl.isNullOrBlank()) return TrailerPreviewLookup(lookup.source)

    var ownIdsRefused = true
    val ownIds = if (trailerService.isYouTubeResting()) emptyList() else ytIds
    val fallbackSource = ownIds.firstNotNullOfOrNull { ytId ->
        val own = trailerService.lookupYouTubeTrailer(
            youtubeUrl = "https://www.youtube.com/watch?v=$ytId",
            title = title,
            year = year
        )
        if (own.unplayable == null) ownIdsRefused = false
        own.source?.takeIf { it.videoUrl.isNotBlank() }
    }
    val nothingToTry = lookup.definiteMiss && ytIds.isEmpty()
    val allRefused = (lookup.refused || lookup.definiteMiss) && ownIdsRefused &&
        (lookup.refused || ytIds.isNotEmpty())
    return TrailerPreviewLookup(
        source = fallbackSource,
        rememberMiss = fallbackSource == null && (nothingToTry || allRefused)
    )
}
