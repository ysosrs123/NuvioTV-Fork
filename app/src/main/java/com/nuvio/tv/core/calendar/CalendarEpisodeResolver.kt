package com.nuvio.tv.core.calendar

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.local.CachedNextUpItem
import com.nuvio.tv.data.local.ContinueWatchingEnrichmentCache
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.repository.MetaRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Where Watch now goes. Without a [videoId] the show's page opens on that episode instead. */
data class CalendarWatchTarget(
    val contentId: String,
    val contentType: String,
    val name: String,
    val poster: String?,
    val backdrop: String?,
    val logo: String?,
    val videoId: String?,
    val season: Int?,
    val episode: Int?,
    val episodeTitle: String?
)

/**
 * The stream list wants the addon's own id for the episode. It comes from the show's details already in memory,
 * then from Continue Watching, and only then from the addons.
 */
@Singleton
class CalendarEpisodeResolver @Inject constructor(
    private val metaRepository: MetaRepository,
    private val enrichmentCache: ContinueWatchingEnrichmentCache
) {
    suspend fun resolve(reminder: CalendarReminder): CalendarWatchTarget? {
        val title = reminder.title
        val ids = calendarContentIds(title)
        val contentId = ids.firstOrNull() ?: return null
        val type = if (title.type == CalendarTitleType.MOVIE) "movie" else "series"
        val entry = reminder.entry
        val base = CalendarWatchTarget(
            contentId = contentId,
            contentType = type,
            name = title.name.orEmpty(),
            poster = title.poster,
            backdrop = null,
            logo = null,
            videoId = null,
            season = entry.season.takeIf { entry.kind == CalendarEntryKind.EPISODE },
            episode = entry.episode.takeIf { entry.kind == CalendarEntryKind.EPISODE },
            episodeTitle = entry.shownTitle
        )
        if (title.type == CalendarTitleType.MOVIE) {
            val meta = ids.firstNotNullOfOrNull { metaRepository.getCachedMeta(type, it) }
            return base.withMeta(meta).copy(videoId = meta?.id ?: contentId)
        }

        ids.firstNotNullOfOrNull { metaRepository.getCachedMeta(type, it) }
            ?.let { meta -> calendarEpisodeTarget(base, meta)?.let { return it } }
        val nextUp = runCatching { enrichmentCache.getNextUpSnapshot() }.getOrDefault(emptyList())
        calendarNextUpTarget(base, title, nextUp)?.let { return it }
        val fetched = fetchMeta(type, ids)
        return fetched?.let { calendarEpisodeTarget(base, it) } ?: base.withMeta(fetched)
    }

    /** One budget for the whole lookup, however many ids are tried. */
    private suspend fun fetchMeta(type: String, ids: List<String>): Meta? = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
        for (id in ids) {
            val result = try {
                metaRepository.getMetaFromAllAddons(type, id).first { it !is NetworkResult.Loading }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (result is NetworkResult.Success) return@withTimeoutOrNull result.data
        }
        null
    }

    private companion object {
        const val FETCH_TIMEOUT_MS = 8_000L
    }
}

internal fun CalendarWatchTarget.withMeta(meta: Meta?): CalendarWatchTarget = if (meta == null) this else copy(
    contentId = meta.id.takeIf(String::isNotBlank) ?: contentId,
    name = name.ifBlank { meta.name },
    poster = poster ?: meta.poster,
    backdrop = meta.background,
    logo = meta.logo
)

internal fun calendarEpisodeTarget(base: CalendarWatchTarget, meta: Meta): CalendarWatchTarget? {
    val video = meta.videos.firstOrNull { it.season == base.season && it.episode == base.episode && base.season != null }
        ?: return null
    return base.withMeta(meta).copy(videoId = video.id, episodeTitle = video.title.takeIf(String::isNotBlank) ?: base.episodeTitle)
}

internal fun calendarNextUpTarget(
    base: CalendarWatchTarget,
    title: CalendarTitleRecord,
    nextUp: List<CachedNextUpItem>
): CalendarWatchTarget? {
    val aliases = title.ids.aliases()
    val match = nextUp.firstOrNull { item ->
        item.season == base.season && item.episode == base.episode && item.videoId.isNotBlank() &&
            CalendarIds.fromContentId(item.contentId).aliases().any { it in aliases }
    } ?: return null
    return base.copy(
        contentId = match.contentId,
        name = base.name.ifBlank { match.name },
        poster = base.poster ?: match.poster,
        backdrop = match.backdrop,
        logo = match.logo,
        videoId = match.videoId,
        episodeTitle = match.episodeTitle ?: base.episodeTitle
    )
}
