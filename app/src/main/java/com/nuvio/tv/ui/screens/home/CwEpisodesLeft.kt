package com.nuvio.tv.ui.screens.home

import androidx.lifecycle.viewModelScope
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.WatchProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun Collection<Pair<Int, Int>>.episodesLeftFrom(season: Int, episode: Int): Int? =
    count { (s, e) -> s > season || (s == season && e >= episode) }.takeIf { it > 0 }

internal fun CwMetaSummary.episodeKeys(): Set<Pair<Int, Int>> =
    watchableEpisodes().mapNotNullTo(mutableSetOf()) { video ->
        val s = video.season ?: return@mapNotNullTo null
        val e = video.episode ?: return@mapNotNullTo null
        s to e
    }

private fun Meta.episodeKeys(): Set<Pair<Int, Int>> =
    watchableEpisodes().mapNotNullTo(mutableSetOf()) { video ->
        val s = video.season ?: return@mapNotNullTo null
        val e = video.episode ?: return@mapNotNullTo null
        s to e
    }

internal fun CwMetaSummary.episodesLeftFrom(season: Int, episode: Int): Int? =
    episodeKeys().episodesLeftFrom(season, episode)

internal fun HomeViewModel.cwSummaryFor(type: String, id: String): CwMetaSummary? =
    cwMetaCache["$type:$id"] ?: cwMetaCache["series:$id"] ?: cwMetaCache["tv:$id"]

private fun ContinueWatchingItem.countsEpisodesLeft(): Boolean = !shufflePlayback && when (this) {
    is ContinueWatchingItem.InProgress ->
        progress.season != null && progress.episode != null && isSeriesType(progress.contentType)
    is ContinueWatchingItem.NextUp -> isSeriesType(info.contentType)
}

/**
 * Episode lists for the shows on Continue watching, kept apart from the shared meta cache. That cache holds 64 shows
 * and Next up checks fill it with many more, so the shown shows' lists were gone by the time the row was drawn.
 */
internal fun keptEpisodeLists(
    current: Map<String, Set<Pair<Int, Int>>>,
    items: List<ContinueWatchingItem>,
    known: (type: String, id: String) -> Set<Pair<Int, Int>>?
): Map<String, Set<Pair<Int, Int>>> {
    val shown = items.filter { it.countsEpisodesLeft() }
    val next = LinkedHashMap<String, Set<Pair<Int, Int>>>()
    shown.forEach { item ->
        val id = item.contentId()
        val list = known(item.contentType(), id) ?: current[id]
        if (list != null) next[id] = list
    }
    return if (next == current) current else next
}

internal fun List<ContinueWatchingItem>.missingEpisodeLists(
    lists: Map<String, Set<Pair<Int, Int>>>
): List<ContinueWatchingItem> =
    filter { it.countsEpisodesLeft() && it.contentId() !in lists }.distinctBy { it.contentId() }

internal fun List<ContinueWatchingItem>.withEpisodesLeft(
    lists: Map<String, Set<Pair<Int, Int>>>
): List<ContinueWatchingItem> = withEpisodesLeft { _, id -> lists[id] }

internal fun List<ContinueWatchingItem>.withEpisodesLeft(
    episodesFor: (type: String, id: String) -> Collection<Pair<Int, Int>>?
): List<ContinueWatchingItem> = map { item ->
    if (!item.countsEpisodesLeft()) return@map item
    when (item) {
        is ContinueWatchingItem.InProgress -> {
            val progress = item.progress
            val left = episodesFor(progress.contentType, progress.contentId)
                ?.episodesLeftFrom(progress.season!!, progress.episode!!)
            if (left == item.episodesLeft) item else item.copy(episodesLeft = left)
        }
        is ContinueWatchingItem.NextUp -> {
            val info = item.info
            val left = episodesFor(info.contentType, info.contentId)?.episodesLeftFrom(info.season, info.episode)
            if (left == item.episodesLeft) item else item.copy(episodesLeft = left)
        }
    }
}

private fun HomeViewModel.knownEpisodeKeys(type: String, id: String): Set<Pair<Int, Int>>? =
    synchronized(cwMetaCache) { cwSummaryFor(type, id) }?.episodeKeys()
        ?: cwMetaTypeCandidates(type).firstNotNullOfOrNull { metaRepository.getCachedMeta(it, id) }?.episodeKeys()

private fun ContinueWatchingItem.lookupProgress(): WatchProgress = when (this) {
    is ContinueWatchingItem.InProgress -> progress
    is ContinueWatchingItem.NextUp -> WatchProgress(
        contentId = info.contentId,
        contentType = info.contentType,
        name = info.name,
        poster = info.poster,
        backdrop = info.backdrop,
        logo = info.logo,
        videoId = info.videoId,
        season = info.season,
        episode = info.episode,
        episodeTitle = info.episodeTitle,
        position = 1L,
        duration = 1L,
        lastWatched = info.lastWatched
    )
}

/** Fills [HomeViewModel.cwEpisodeLists] for the shows on the row, asking for details only when no cache has them. */
internal fun HomeViewModel.observeCwEpisodeListsPipeline() {
    viewModelScope.launch {
        uiState
            .map { it.continueWatchingItems }
            .distinctUntilChanged()
            .combine(layoutPreferenceDataStore.cwEpisodesLeftEnabled) { items, enabled -> items to enabled }
            .distinctUntilChanged()
            .collectLatest { (items, enabled) ->
                if (!enabled) return@collectLatest
                withContext(Dispatchers.Default) {
                    cwEpisodeLists.update { current ->
                        keptEpisodeLists(current, items) { type, id -> knownEpisodeKeys(type, id) }
                    }
                }
                items.missingEpisodeLists(cwEpisodeLists.value).forEach { item ->
                    val summary = withContext(Dispatchers.IO) { resolveCwMeta(item.lookupProgress()) } ?: return@forEach
                    val keys = summary.episodeKeys()
                    cwEpisodeLists.update { it + (item.contentId() to keys) }
                }
            }
    }
}
