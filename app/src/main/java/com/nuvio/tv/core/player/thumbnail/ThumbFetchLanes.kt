package com.nuvio.tv.core.player.thumbnail

/** Where a session is when it plans its keyframe fetches. */
internal enum class FetchPhase { GENERATING, PLAYBACK, PLAYER_CLOSED }

/** How many keyframes are fetched at once. Decoding has its own lanes ([ThumbDecodeLanes]). */
internal object ThumbFetchLanes {
    const val MAX = 8
    private const val PLAYBACK_MAX = 3
    private const val SMALL_BOX_4K_MAX = 2
    /** Connections TorBox allows one user; playback's own come off it. */
    private const val CAPPED_SOURCE_CONNECTIONS = 4

    /**
     * Sources with a per-user connection limit: TorBox links and add-on links that resolve through TorBox. Also asked
     * of the address a link redirects to, since an add-on link only shows TorBox there.
     */
    fun isConnectionCapped(url: String): Boolean {
        val lower = url.lowercase()
        val host = lower.substringAfter("://").substringBefore('/')
        return host.startsWith("tb-cdn.") || ".tb-cdn." in host || host.contains("torbox") || "/torbox/" in lower
    }

    /**
     * [playbackConnections] is what the player opens to the stream (1 without parallel connections).
     * A rate limit or dropped connection seen in the session means one at a time from then on.
     */
    fun limit(
        phase: FetchPhase,
        rateLimited: Boolean,
        playbackConnections: Int,
        smallBox4K: Boolean,
        connectionCapped: Boolean = false
    ): Int {
        if (rateLimited) return 1
        val playerConnections = playbackConnections.coerceAtLeast(1)
        val lanes = when {
            phase == FetchPhase.PLAYER_CLOSED -> if (connectionCapped) CAPPED_SOURCE_CONNECTIONS else MAX
            connectionCapped -> CAPPED_SOURCE_CONNECTIONS - playerConnections
            phase == FetchPhase.GENERATING -> MAX
            // Small boxes pace 4K decodes while playing: a second fetch would only wait.
            smallBox4K -> 1
            else -> PLAYBACK_MAX
        }
        return (if (smallBox4K) minOf(lanes, SMALL_BOX_4K_MAX) else lanes).coerceIn(1, MAX)
    }

    /** The next keyframes to start, in [wanted]'s order: each once, none already under way, at most [room]. */
    fun pick(wanted: List<Int>, underWay: Set<Int>, room: Int): List<Int> {
        if (room <= 0) return emptyList()
        val out = LinkedHashSet<Int>()
        for (kf in wanted) {
            if (kf < 0 || kf in underWay) continue
            out.add(kf)
            if (out.size >= room) break
        }
        return out.toList()
    }
}
