package com.nuvio.tv.core.stream

import com.nuvio.tv.domain.model.AddonStreams

/**
 * The stream screen's finished source list, handed to the player opened from it so the player's own list is ready at
 * once; [Handed.partial] when an automatic pick came before every source answered. Taken once, for the same profile, title, season and episode ([StreamPrefetchCache.keyOf]), while at most
 * [maxAgeMs] old; otherwise the player loads its list as usual.
 */
class StreamListHandOver(private val maxAgeMs: Long = MAX_AGE_MS) {
    class Handed(val key: String, val streams: List<AddonStreams>, val atMs: Long, val partial: Boolean = false)

    private val lock = Any()
    private var entry: Handed? = null

    fun put(key: String, streams: List<AddonStreams>, nowMs: Long, partial: Boolean = false) {
        synchronized(lock) {
            entry = if (key.isBlank() || streams.isEmpty()) null else Handed(key, streams, nowMs, partial)
        }
    }

    fun take(key: String, nowMs: Long): Handed? {
        synchronized(lock) {
            val held = entry ?: return null
            if (nowMs - held.atMs !in 0..maxAgeMs) {
                entry = null
                return null
            }
            if (held.key != key) return null
            entry = null
            return held
        }
    }

    /** A list taken but not used to the end (its load was cancelled) is there again, unless a newer one came. */
    fun giveBack(handed: Handed) {
        synchronized(lock) {
            if (entry == null) entry = handed
        }
    }

    companion object {
        const val MAX_AGE_MS = StreamPrefetchCache.PLAYER_REUSE_MS

        /** [live] with the groups of a partial list handed over that [live] has not brought yet. */
        fun withHanded(handed: List<AddonStreams>, live: List<AddonStreams>): List<AddonStreams> {
            val names = live.mapTo(HashSet()) { it.addonName }
            return live + handed.filter { it.addonName !in names }
        }

        val shared = StreamListHandOver()
    }
}
