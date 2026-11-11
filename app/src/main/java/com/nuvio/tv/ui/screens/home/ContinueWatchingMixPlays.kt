package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.data.local.mixPlayKey
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem

/** Progress, local or from a tracker, without the episodes last played in a Mix. */
internal fun withoutMixPlays(items: List<WatchProgress>, mixPlayed: Set<String>): List<WatchProgress> =
    if (mixPlayed.isEmpty()) items
    else items.filterNot { mixPlayKey(it.contentId, it.season, it.episode) in mixPlayed }

/**
 * A show's Next up starts from the episodes watched outside a Mix, so an episode played in a Mix doesn't move it on
 * and the show keeps its tile. A show watched only in a Mix has no seed.
 */
internal fun withoutMixPlaySeeds(
    seeds: List<WatchProgress>,
    mixPlayed: Set<String>,
    watched: List<WatchedItem>,
    fromFurthest: Boolean
): List<WatchProgress> {
    if (mixPlayed.isEmpty()) return seeds
    val order = if (fromFurthest) {
        compareBy<WatchedItem>({ it.season ?: 0 }, { it.episode ?: 0 }, { it.watchedAt })
    } else {
        compareBy<WatchedItem>({ it.watchedAt }, { it.season ?: 0 }, { it.episode ?: 0 })
    }
    val byShow by lazy(LazyThreadSafetyMode.NONE) {
        watched.asSequence()
            .filter { it.season != null && it.episode != null && it.season != 0 }
            .filterNot { mixPlayKey(it.contentId, it.season, it.episode) in mixPlayed }
            .groupBy { it.contentId }
    }
    return seeds.mapNotNull { seed ->
        if (mixPlayKey(seed.contentId, seed.season, seed.episode) !in mixPlayed) return@mapNotNull seed
        val episode = byShow[seed.contentId]?.maxWithOrNull(order) ?: return@mapNotNull null
        seed.copy(
            videoId = seed.contentId,
            season = episode.season,
            episode = episode.episode,
            episodeTitle = null,
            position = 1L,
            duration = 1L,
            lastWatched = episode.watchedAt,
            progressPercent = 100f,
            traktEpisodeId = null
        )
    }
}
