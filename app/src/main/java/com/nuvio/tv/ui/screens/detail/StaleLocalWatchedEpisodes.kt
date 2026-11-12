package com.nuvio.tv.ui.screens.detail

import com.nuvio.tv.domain.model.WatchProgress

/**
 * Local watched ticks the tracker no longer has. [loadedProgress] is the tracker's full answer for the
 * show; specials are kept because that answer leaves them out.
 */
internal fun staleLocalWatchedEpisodes(
    localWatched: Set<Pair<Int, Int>>,
    providerProgress: Map<Pair<Int, Int>, WatchProgress>,
    loadedProgress: Map<Pair<Int, Int>, WatchProgress>
): List<Pair<Int, Int>> = localWatched.filter { key ->
    key.first != 0 &&
        providerProgress[key]?.isCompleted() != true &&
        loadedProgress[key]?.isCompleted() != true
}
