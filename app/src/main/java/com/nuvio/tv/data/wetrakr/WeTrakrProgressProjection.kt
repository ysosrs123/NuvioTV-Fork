package com.nuvio.tv.data.wetrakr

import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.selectPreferredTrackingNextUpSeeds
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem

internal class WeTrakrProgressProjection private constructor(
    private val titlesByAlias: Map<String, WeTrakrTitle>,
    private val droppedAliases: Set<String>,
    val watchedMovieIds: Set<String>,
    val watchedShowEpisodes: Map<String, Set<Pair<Int, Int>>>,
    val showIdSiblings: Map<String, Set<String>>,
    val watchedItems: List<WatchedItem>,
    val progress: List<WatchProgress>,
    private val episodeProgressById: Map<String, List<WatchProgress>>,
    private val seeds: List<WatchProgress>
) {
    fun nextUp(preferFurthest: Boolean): List<WatchProgress> = selectPreferredTrackingNextUpSeeds(seeds, preferFurthest)

    fun isHidden(contentId: String): Boolean = contentId in droppedAliases

    fun isWatched(contentId: String, season: Int?, episode: Int?): Boolean =
        if (season != null && episode != null) season to episode in watchedShowEpisodes[contentId].orEmpty()
        else contentId in watchedMovieIds

    fun episodeProgress(contentId: String): Map<Pair<Int, Int>, WatchProgress> =
        episodeProgressById[contentId].orEmpty().associateBy { it.season!! to it.episode!! }

    fun title(alias: String): WeTrakrTitle? = titlesByAlias[alias]

    companion object {
        const val COMPLETION_PERCENT = 90f
        val Empty = WeTrakrProgressProjection(WeTrakrSnapshot(0))

        operator fun invoke(snapshot: WeTrakrSnapshot): WeTrakrProgressProjection {
            val titles = HashMap<String, WeTrakrTitle>()
            fun index(title: WeTrakrTitle) {
                val key = title.type.name
                val merged = title.ids.aliases().mapNotNull { titles["$key|$it"] }.fold(title, WeTrakrTitle::merged)
                merged.ids.aliases().forEach { titles["$key|$it"] = merged }
            }
            snapshot.lists.values.forEach { entries -> entries.forEach { index(it.title) } }
            snapshot.playback.forEach { index(it.title) }
            fun resolve(type: WeTrakrType, ids: WeTrakrIds): WeTrakrTitle =
                ids.aliases().firstNotNullOfOrNull { titles["${type.name}|$it"] }?.let { it.copy(ids = it.ids.merged(ids)) }
                    ?: WeTrakrTitle(type, ids)

            val dropped = snapshot.list(WeTrakrList.DROPPED)
                .filter { it.title.type == WeTrakrType.SHOW }
                .flatMapTo(hashSetOf()) { resolve(WeTrakrType.SHOW, it.title.ids).ids.aliases() }

            val movies = snapshot.watchedMovies.map { it to resolve(WeTrakrType.MOVIE, it.ids) }
            val watchedMovieIds = movies.flatMapTo(linkedSetOf()) { (_, title) -> title.ids.aliases() }

            val episodes = snapshot.watchedEpisodes.map { episode ->
                episode to resolve(WeTrakrType.SHOW, episode.showIds.merged(WeTrakrIds(wetrakr = episode.showId)))
            }
            val siblings = HashMap<String, Set<String>>()
            val watchedShowEpisodes = HashMap<String, MutableSet<Pair<Int, Int>>>()
            episodes.forEach { (episode, show) ->
                val aliases = show.ids.aliases()
                aliases.forEach { alias ->
                    siblings[alias] = aliases
                    watchedShowEpisodes.getOrPut(alias) { linkedSetOf() } += episode.season to episode.number
                }
            }
            snapshot.playback.filter { it.type == WeTrakrType.EPISODE }.forEach { session ->
                val aliases = resolve(WeTrakrType.SHOW, session.title.ids).ids.aliases()
                aliases.forEach { siblings.putIfAbsent(it, aliases) }
            }

            val watchedItems = (movies.map { (movie, title) ->
                WatchedItem(
                    contentId = title.ids.contentId,
                    contentType = "movie",
                    title = title.title ?: title.ids.contentId,
                    watchedAt = weTrakrTimestamp(movie.lastWatchedAt),
                    poster = title.poster,
                    releaseInfo = title.year?.toString(),
                    trackingProviderId = TrackingProviderId.WETRAKR.storageId,
                    trackingProviderItemId = title.ids.wetrakr?.let { "wetrakr:$it" },
                    trackingSourceUrl = title.pageUrl()
                )
            } + episodes.map { (episode, show) ->
                WatchedItem(
                    contentId = show.ids.contentId,
                    contentType = "series",
                    title = show.title ?: show.ids.contentId,
                    season = episode.season,
                    episode = episode.number,
                    watchedAt = weTrakrTimestamp(episode.lastWatchedAt),
                    poster = show.poster,
                    releaseInfo = show.year?.toString(),
                    trackingProviderId = TrackingProviderId.WETRAKR.storageId,
                    trackingProviderItemId = show.ids.wetrakr?.let { "wetrakr:$it" },
                    trackingSourceUrl = show.pageUrl()
                )
            }).sortedByDescending(WatchedItem::watchedAt)

            val watchedAtByKey = HashMap<String, Long>()
            movies.forEach { (movie, title) -> watchedAtByKey["movie:${title.ids.contentId}"] = weTrakrTimestamp(movie.lastWatchedAt) }
            episodes.forEach { (episode, show) ->
                watchedAtByKey["episode:${show.ids.contentId}:${episode.season}:${episode.number}"] = weTrakrTimestamp(episode.lastWatchedAt)
            }

            val progress = snapshot.playback.mapNotNull { session ->
                val title = resolve(session.title.type, session.title.ids).merged(session.title)
                val contentId = title.ids.contentId
                if (session.type == WeTrakrType.EPISODE && contentId in dropped) return@mapNotNull null
                if (session.progress >= COMPLETION_PERCENT) return@mapNotNull null
                val watchedKey = if (session.type == WeTrakrType.MOVIE) "movie:$contentId"
                else "episode:$contentId:${session.season}:${session.number}"
                val updatedAt = weTrakrTimestamp(session.updatedAt)
                if ((watchedAtByKey[watchedKey] ?: Long.MIN_VALUE) >= updatedAt) return@mapNotNull null
                progressOf(
                    title, session.type, session.season, session.number, session.episodeTitle, session.progress,
                    updatedAt, session.runtimeSeconds?.times(1_000L), WatchProgress.SOURCE_REMOTE_PLAYBACK
                )
            }.groupBy { Triple(it.contentId, it.season, it.episode) }
                .map { (_, entries) -> entries.maxBy(WatchProgress::lastWatched) }
                .sortedByDescending(WatchProgress::lastWatched)

            val episodeProgressById = progress.filter { it.season != null && it.episode != null }
                .flatMap { item -> (siblings[item.contentId] ?: setOf(item.contentId)).map { it to item } }
                .groupBy({ it.first }, { it.second })

            val seeds = episodes.filter { (episode, show) -> episode.season > 0 && show.ids.contentId !in dropped }
                .map { (episode, show) ->
                    progressOf(
                        show, WeTrakrType.EPISODE, episode.season, episode.number, null, 100f,
                        weTrakrTimestamp(episode.lastWatchedAt), null, WatchProgress.SOURCE_REMOTE_HISTORY
                    )
                }

            return WeTrakrProgressProjection(
                titlesByAlias = titles,
                droppedAliases = dropped,
                watchedMovieIds = watchedMovieIds,
                watchedShowEpisodes = watchedShowEpisodes,
                showIdSiblings = siblings,
                watchedItems = watchedItems,
                progress = progress,
                episodeProgressById = episodeProgressById,
                seeds = seeds
            )
        }

        private fun progressOf(
            title: WeTrakrTitle,
            type: WeTrakrType,
            season: Int?,
            episode: Int?,
            episodeTitle: String?,
            percent: Float,
            lastWatched: Long,
            durationMs: Long?,
            source: String
        ): WatchProgress {
            val contentId = title.ids.contentId
            return WatchProgress(
                contentId = contentId,
                contentType = if (type == WeTrakrType.MOVIE) "movie" else "series",
                name = title.title ?: contentId,
                poster = title.poster,
                backdrop = title.backdrop,
                logo = null,
                videoId = if (type == WeTrakrType.MOVIE) contentId else "$contentId:$season:$episode",
                season = season,
                episode = episode,
                episodeTitle = episodeTitle,
                position = 0,
                duration = durationMs ?: ((title.runtimeMinutes ?: 0).toLong() * 60_000L),
                lastWatched = lastWatched,
                progressPercent = percent,
                source = source,
                trackingProviderId = TrackingProviderId.WETRAKR.storageId,
                trackingProviderItemId = title.ids.wetrakr?.let { "wetrakr:$it" },
                trackingSourceUrl = title.pageUrl(),
                completionThresholdOverride = COMPLETION_PERCENT / 100f
            )
        }
    }
}
