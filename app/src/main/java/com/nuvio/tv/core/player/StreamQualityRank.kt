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
        // filter, and a nine-level sort whose every key lookup is a deep structural
        // hash of a Stream. Logging only.
        val rankT0 = SystemClock.elapsedRealtime()
        val factsByStream = streams.associateWith { DirectDebridStreamFilter.factsFor(it, effective) }
        val rankFactsMs = SystemClock.elapsedRealtime() - rankT0
        val pool = streams.filter {
            DirectDebridStreamFilter.passesExclusionFilters(factsByStream.getValue(it), effective)
        }.ifEmpty { streams }
        val rankFilterMs = SystemClock.elapsedRealtime() - rankT0 - rankFactsMs
        val ranked = pool.sortedWith(
            compareBy<Stream> { factsByStream.getValue(it).resolutionRank }
                .thenBy { factsByStream.getValue(it).qualityRank }
                .thenBy { factsByStream.getValue(it).groupRank }
                .thenBy { factsByStream.getValue(it).visualRank }
                .thenBy { factsByStream.getValue(it).audioRank }
                .thenBy { factsByStream.getValue(it).channelRank }
                .thenBy { factsByStream.getValue(it).encodeRank }
                .thenByDescending { factsByStream.getValue(it).size ?: -1L }
                .thenByDescending { containerScore(it) }
        )
        val rankSortMs = SystemClock.elapsedRealtime() - rankT0 - rankFactsMs - rankFilterMs
        android.util.Log.i(
            "StreamQualityRank",
            "R1_SPLIT in=${streams.size} distinct=${factsByStream.size} pool=${pool.size} " +
                "facts=${rankFactsMs}ms filter=${rankFilterMs}ms sort=${rankSortMs}ms " +
                "total=${SystemClock.elapsedRealtime() - rankT0}ms"
        )
        return ranked
    }

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
