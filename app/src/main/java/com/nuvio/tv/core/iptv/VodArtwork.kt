package com.nuvio.tv.core.iptv

import java.util.Locale
import org.json.JSONObject

data class VodArt(
    val tmdbId: String? = null, val imdbId: String? = null, val title: String? = null, val poster: String? = null, val backdrop: String? = null,
    val overview: String? = null, val year: Int? = null, val rating: Double? = null,
) {
    val matched: Boolean get() = tmdbId != null || imdbId != null
    override fun toString(): String = "VodArt(tmdb=${tmdbId != null}, imdb=${imdbId != null})"
}

data class VodArtCandidate(val tmdbId: String, val title: String, val originalTitle: String?, val year: Int?)

data class VodDetailTarget(val itemId: String, val itemType: String)

object VodArtwork {
    fun key(language: String, kind: VodKind, tmdbId: String?, imdbId: String?, title: String, year: Int?): String? {
        require(kind != VodKind.EPISODE)
        val lang = language.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() || it == '-' }.take(12).ifEmpty { "en" }
        val id = XtreamVodParser.tmdbId(tmdbId)?.let { "tmdb:$it" } ?: XtreamVodParser.imdbId(imdbId)?.let { "imdb:$it" }
            ?: VodTitles.parse(title, year).let { parsed -> parsed.year?.let { y -> parsed.matchKey.takeIf { it.isNotEmpty() }?.let { "title:${it.take(120)}:$y" } } }
            ?: return null
        return "$lang:${kind.wire}:$id"
    }

    fun pick(title: String, year: Int?, candidates: List<VodArtCandidate>): VodArtCandidate? {
        val parsed = VodTitles.parse(title, year)
        val wanted = parsed.matchKey
        val wantedYear = parsed.year ?: return null
        if (wanted.isEmpty()) return null
        val same = candidates.filter { candidate ->
            listOfNotNull(candidate.title, candidate.originalTitle).any { VodTitles.parse(it).matchKey == wanted }
        }.distinctBy { it.tmdbId }
        val exact = same.filter { it.year == wantedYear }
        if (exact.size == 1) return exact.single()
        if (exact.isNotEmpty()) return null
        return same.filter { it.year != null && kotlin.math.abs(it.year - wantedYear) == 1 }.singleOrNull()
    }

    fun target(kind: VodKind, tmdbId: String?, imdbId: String?): VodDetailTarget? {
        val type = when (kind) { VodKind.MOVIE -> "movie"; VodKind.SERIES -> "series"; VodKind.EPISODE -> return null }
        XtreamVodParser.imdbId(imdbId)?.let { return VodDetailTarget(it, type) }
        return XtreamVodParser.tmdbId(tmdbId)?.let { VodDetailTarget("tmdb:$it", type) }
    }

    fun encode(art: VodArt?): String = JSONObject().apply {
        if (art == null) { put("none", true); return@apply }
        art.tmdbId?.let { put("tmdb", it) }; art.imdbId?.let { put("imdb", it) }; art.title?.let { put("title", it) }
        art.poster?.let { put("poster", it) }; art.backdrop?.let { put("backdrop", it) }; art.overview?.let { put("overview", it) }
        art.year?.let { put("year", it) }; art.rating?.let { put("rating", it) }
    }.toString()

    fun decode(text: String): Result<VodArt?> = runCatching {
        val json = JSONObject(text)
        if (json.optBoolean("none")) return@runCatching null
        fun string(name: String, max: Int) = if (json.has(name) && !json.isNull(name)) json.getString(name).take(max).takeIf { it.isNotBlank() } else null
        VodArt(XtreamVodParser.tmdbId(string("tmdb", 20)), XtreamVodParser.imdbId(string("imdb", 20)), string("title", 512),
            string("poster", 2048)?.let(::imageUrl), string("backdrop", 2048)?.let(::imageUrl), string("overview", 4000),
            if (json.has("year")) json.optInt("year").takeIf { it in 1888..2100 } else null,
            if (json.has("rating")) json.optDouble("rating").takeIf { it.isFinite() && it > 0 && it <= 10 } else null)
    }

    fun imageUrl(value: String?): String? = value?.trim()?.takeIf { it.length <= 2048 && it.startsWith("https://") && it.none(Char::isWhitespace) }
}

class VodArtCache(private val max: Int, private val positiveMillis: Long, private val negativeMillis: Long, private val now: () -> Long) {
    private class Entry(val art: VodArt?, val savedAt: Long)
    private val entries = object : LinkedHashMap<String, Entry>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean = size > max
    }

    init { require(max > 0 && positiveMillis > 0 && negativeMillis > 0) }

    val size: Int @Synchronized get() = entries.size

    @Synchronized fun contains(key: String): Boolean = fresh(key) != null

    @Synchronized fun get(key: String): VodArt? = fresh(key)?.art

    @Synchronized fun put(key: String, art: VodArt?) { entries[key] = Entry(art, now()) }

    @Synchronized fun lines(): List<String> = entries.entries.filter { alive(it.value) }.map { (key, entry) ->
        JSONObject().put("k", key).put("t", entry.savedAt).put("v", VodArtwork.encode(entry.art)).toString()
    }

    @Synchronized fun load(lines: Sequence<String>): Int {
        var loaded = 0
        for (line in lines) {
            val json = runCatching { JSONObject(line) }.getOrNull() ?: continue
            val key = json.optString("k").takeIf { it.isNotEmpty() && it.length <= 200 } ?: continue
            val saved = json.optLong("t", -1L).takeIf { it in 1..now() } ?: continue
            val decoded = VodArtwork.decode(json.optString("v"))
            if (decoded.isFailure) continue
            val entry = Entry(decoded.getOrNull(), saved)
            if (alive(entry)) { entries[key] = entry; loaded++ }
        }
        return loaded
    }

    private fun fresh(key: String): Entry? {
        val entry = entries[key] ?: return null
        if (alive(entry)) return entry
        entries.remove(key)
        return null
    }

    private fun alive(entry: Entry): Boolean = now() - entry.savedAt in 0 until if (entry.art == null) negativeMillis else positiveMillis
}

object VodResume {
    const val MIN_POSITION = 30_000L
    const val FINISHED = .92

    fun keep(positionMillis: Long, durationMillis: Long): Boolean =
        positionMillis >= MIN_POSITION && !finished(positionMillis, durationMillis)

    fun finished(positionMillis: Long, durationMillis: Long): Boolean =
        durationMillis > 0 && positionMillis >= durationMillis * FINISHED

    fun fraction(positionMillis: Long, durationMillis: Long): Float? =
        if (durationMillis <= 0) null else (positionMillis.toDouble() / durationMillis).toFloat().coerceIn(0f, 1f)
}
