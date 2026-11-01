package com.nuvio.tv.core.player

import android.os.SystemClock
import com.nuvio.tv.core.debrid.DirectDebridStreamFilter
import com.nuvio.tv.domain.model.DebridStreamPreferences
import com.nuvio.tv.domain.model.Stream

/**
 * Deterministic quality ranking for auto-select (TRaSH-aligned), using the
 * same fact extraction as the Direct Debrid list (DirectDebridStreamFilter) so
 * one preferences object drives the list and the auto-pick across all sources.
 *
 * Order: resolution -> quality -> preferred release group -> visual tags ->
 * audio tags -> channels -> encode -> size (desc) -> container. Ties keep
 * incoming order (stable sort).
 *
 * Streams matching an excluded preference are dropped first; if that empties
 * the pool, ranking falls back to the unfiltered candidates. Required/floor
 * filters and the user's list sort profile are not applied here.
 */
object StreamQualityRank {

    private val CONTAINER_MKV = Regex("\\.mkv\\b|\\bmkv\\b", RegexOption.IGNORE_CASE)
    private val DEFAULT_PREFERENCES = DebridStreamPreferences()

    /** Stable, deterministic best-first ordering of the given streams. */
    fun rank(
        streams: List<Stream>,
        preferences: DebridStreamPreferences? = null
    ): List<Stream> {
        if (streams.size <= 1) return streams
        val effective = preferences ?: DEFAULT_PREFERENCES
        // Times the three cost centres separately: fact extraction, the exclusion
        // filter, and the sort. Logging only.
        val rankT0 = SystemClock.elapsedRealtime()
        val facts = streams.map { DirectDebridStreamFilter.factsFor(it, effective) }
        val rankFactsMs = SystemClock.elapsedRealtime() - rankT0
        val pool = streams.indices.filter {
            DirectDebridStreamFilter.passesExclusionFilters(facts[it], effective)
        }.ifEmpty { streams.indices.toList() }
        val rankFilterMs = SystemClock.elapsedRealtime() - rankT0 - rankFactsMs
        val containers = IntArray(streams.size)
        pool.forEach { containers[it] = containerScore(streams[it]) }
        val ranked = pool.sortedWith(bestFirst({ facts[it] }, { containers[it] })).map(streams::get)
        val rankSortMs = SystemClock.elapsedRealtime() - rankT0 - rankFactsMs - rankFilterMs
        android.util.Log.i(
            "StreamQualityRank",
            "R1_SPLIT in=${streams.size} pool=${pool.size} " +
                "facts=${rankFactsMs}ms filter=${rankFilterMs}ms sort=${rankSortMs}ms " +
                "total=${SystemClock.elapsedRealtime() - rankT0}ms"
        )
        return ranked
    }

    /**
     * The same order for a whole list, as indices into [streams], with the streams the preferences exclude
     * reported apart instead of dropped.
     */
    fun order(
        streams: List<Stream>,
        preferences: DebridStreamPreferences? = null
    ): StreamOrder {
        val effective = preferences ?: DEFAULT_PREFERENCES
        val facts = streams.map { DirectDebridStreamFilter.factsFor(it, effective) }
        val containers = streams.map(::containerScore)
        val excluded = streams.indices.filterTo(HashSet()) {
            !DirectDebridStreamFilter.passesExclusionFilters(facts[it], effective)
        }
        return StreamOrder(streams.indices.sortedWith(bestFirst({ facts[it] }, { containers[it] })), excluded)
    }

    data class StreamOrder(val bestFirst: List<Int>, val excluded: Set<Int>)

    private fun <T> bestFirst(
        facts: (T) -> DirectDebridStreamFilter.StreamFacts,
        container: (T) -> Int
    ): Comparator<T> =
        compareBy<T> { facts(it).resolutionRank }
            .thenBy { facts(it).qualityRank }
            .thenBy { facts(it).groupRank }
            .thenBy { facts(it).visualRank }
            .thenBy { facts(it).audioRank }
            .thenBy { facts(it).channelRank }
            .thenBy { facts(it).encodeRank }
            .thenByDescending { facts(it).size ?: -1L }
            .thenByDescending { container(it) }

    internal fun containerScore(stream: Stream): Int {
        val text = listOfNotNull(
            stream.behaviorHints?.filename,
            stream.getStreamUrl(),
            stream.name,
            stream.title,
            stream.description
        ).joinToString(" ")
        return if (CONTAINER_MKV.containsMatchIn(text)) 1 else 0
    }
}
