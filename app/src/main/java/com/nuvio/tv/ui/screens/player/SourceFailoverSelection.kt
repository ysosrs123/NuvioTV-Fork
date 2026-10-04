package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamDebridCacheState

/**
 * Which list entry the player may switch to on its own when a source fails.
 *
 * Entries with a playable http(s) link qualify, media server entries (their link is made when they
 * are opened), and debrid entries the service reports as cached: those are added only if cached, so
 * no download starts. Plain torrents and magnets are skipped (they would play peer to peer), as are
 * uncached entries and external links (they open a browser).
 */
internal object SourceFailoverSelection {

    /**
     * The server version, the link, or for a debrid entry not prepared yet its torrent and file,
     * so a failed entry is not tried twice.
     */
    fun failoverKey(stream: Stream): String? =
        stream.serverTarget?.key()
            ?: stream.getStreamUrl()?.takeIf { it.isNotBlank() }
            ?: stream.getEffectiveInfoHash()?.let { "$it:${stream.getEffectiveFileIdx() ?: -1}" }

    fun isAutoFailoverPlayable(stream: Stream, season: Int?, episode: Int?): Boolean {
        if (stream.isExternal()) return false
        if (stream.serverTarget != null) return true
        when (stream.debridCacheStatus?.state) {
            null, StreamDebridCacheState.CACHED -> Unit
            StreamDebridCacheState.CHECKING,
            StreamDebridCacheState.NOT_CACHED,
            StreamDebridCacheState.UNKNOWN -> return false
        }
        if (!stream.isDirectDebrid()) {
            if (stream.isTorrent()) return false
            val url = stream.getStreamUrl()?.trim() ?: return false
            if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
                return false
            }
        }
        val resolve = stream.clientResolve
        if (resolve != null) {
            if (season != null && resolve.season != null && resolve.season != season) return false
            if (episode != null && resolve.episode != null && resolve.episode != episode) return false
        }
        return true
    }

    /**
     * The first playable entry after [currentIndex] (or from the top when the playing entry is not
     * in the list), keeping the list's order. Null when the list belongs to another request.
     */
    fun selectNext(
        streams: List<Stream>,
        currentIndex: Int,
        listRequestKey: String?,
        currentRequestKey: String?,
        deadUrls: Set<String>,
        season: Int?,
        episode: Int?
    ): Stream? {
        if (currentRequestKey == null || listRequestKey != currentRequestKey) return null
        val startIndex = if (currentIndex >= 0) currentIndex + 1 else 0
        for (index in startIndex until streams.size) {
            val candidate = streams[index]
            if (!isAutoFailoverPlayable(candidate, season, episode)) continue
            val key = failoverKey(candidate) ?: continue
            if (key in deadUrls) continue
            return candidate
        }
        return null
    }
}
