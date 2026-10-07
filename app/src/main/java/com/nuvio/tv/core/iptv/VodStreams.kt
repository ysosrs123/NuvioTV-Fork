package com.nuvio.tv.core.iptv

import java.util.Locale

data class VodStreamRequest(val kind: VodKind, val imdbId: String?, val tmdbId: String?, val season: Int?, val episode: Int?)

data class VodStreamText(val title: String, val description: String?, val filename: String?)

object VodStreams {
    const val MAX_TITLES_PER_SOURCE = 6

    fun request(type: String, videoId: String, season: Int? = null, episode: Int? = null): VodStreamRequest? {
        val kind = when (type.trim().lowercase(Locale.ROOT)) {
            "movie", "film" -> VodKind.MOVIE
            "series", "tv", "show", "tvshow" -> VodKind.SERIES
            else -> return null
        }
        val parts = videoId.trim().split(':')
        val head = parts.firstOrNull().orEmpty()
        var imdb: String? = null
        var tmdb: String? = null
        val rest = when {
            IMDB.matches(head) -> { imdb = head.lowercase(Locale.ROOT); parts.drop(1) }
            head.equals("tmdb", ignoreCase = true) -> {
                val tail = parts.drop(1).let { if (it.firstOrNull()?.lowercase(Locale.ROOT) in TMDB_TYPES) it.drop(1) else it }
                tmdb = tail.firstOrNull()?.takeIf { TMDB.matches(it) } ?: return null
                tail.drop(1)
            }
            else -> return null
        }
        if (kind == VodKind.MOVIE) return VodStreamRequest(kind, imdb, tmdb, null, null)
        val s = season ?: rest.getOrNull(0)?.toIntOrNull()
        val e = episode ?: rest.getOrNull(1)?.toIntOrNull()
        if (s == null || e == null || s !in 0..300 || e !in 0..5000) return null
        return VodStreamRequest(kind, imdb, tmdb, s, e)
    }

    fun tags(raw: String): List<String> {
        val found = LinkedHashSet<String>()
        for (word in raw.split(TAG_SPLIT)) {
            val upper = word.uppercase(Locale.ROOT).trim('.', ':', '*')
            val whole = TAGS[upper]
            if (whole != null) found += whole else upper.split('-').forEach { part -> TAGS[part]?.let(found::add) }
        }
        return found.toList()
    }

    fun extension(value: String?): String? =
        value?.trim()?.removePrefix(".")?.lowercase(Locale.ROOT)?.takeIf { EXTENSION.matches(it) }

    fun extensionOfUrl(url: String?): String? {
        val path = url?.substringBefore('#')?.substringBefore('?')?.substringAfterLast('/') ?: return null
        if (!path.contains('.')) return null
        return extension(path.substringAfterLast('.'))
    }

    fun filename(title: String, year: Int?, extension: String?): String? {
        val ext = extension(extension) ?: return null
        val base = title.replace(UNSAFE, " ").replace(SPACES, " ").trim().take(120).ifEmpty { return null }
        return if (year != null && !base.endsWith("($year)")) "$base ($year).$ext" else "$base.$ext"
    }

    fun episodeCode(season: Int, episode: Int): String = String.format(Locale.ROOT, "S%02dE%02d", season, episode)

    fun movie(providerName: String, year: Int?, extension: String?): VodStreamText {
        val clean = VodTitles.parse(providerName, year)
        return VodStreamText(providerName.trim(), details(providerName, extension), filename(clean.display, clean.year, extension))
    }

    fun episode(seriesName: String, season: Int, episode: Int, episodeTitle: String?, extension: String?): VodStreamText {
        val code = episodeCode(season, episode)
        val name = episodeTitle?.trim()?.takeIf { it.isNotEmpty() && !it.equals(seriesName.trim(), ignoreCase = true) }
        val title = listOfNotNull(seriesName.trim(), code, name).joinToString(" · ")
        val clean = VodTitles.parse(seriesName).display
        return VodStreamText(title, details("$seriesName ${episodeTitle.orEmpty()}", extension), filename("$clean $code", null, extension))
    }

    fun details(raw: String, extension: String?): String? =
        (tags(raw) + listOfNotNull(extension(extension)?.uppercase(Locale.ROOT))).distinct().joinToString(" • ").ifEmpty { null }

    fun connectionsBusy(inUse: Int, maxStreams: Int): Boolean = inUse >= maxStreams.coerceAtLeast(1)

    fun providerRefusal(httpStatus: Int): Boolean = httpStatus in REFUSAL_STATUSES

    fun revision(enabled: Boolean, parts: List<String>): String =
        if (!enabled) "off" else "on:" + parts.sorted().joinToString(",").hashCode().toString(16)

    private val IMDB = Regex("(?i)tt[0-9]{5,12}")
    private val TMDB = Regex("[0-9]{1,10}")
    private val TMDB_TYPES = setOf("movie", "series", "tv")
    private val EXTENSION = Regex("[a-z0-9]{2,5}")
    private val UNSAFE = Regex("[\\\\/:*?\"<>|\\p{Cc}]")
    private val SPACES = Regex("\\s+")
    private val TAG_SPLIT = Regex("[\\s()\\[\\]{}|/,_]+")
    private val REFUSAL_STATUSES = setOf(403, 429, 456, 458, 503, 509)
    private val TAGS = linkedMapOf(
        "8K" to "8K", "4K" to "4K", "UHD" to "4K", "2160P" to "4K", "1080P" to "1080p", "FHD" to "1080p", "720P" to "720p", "HD" to "HD",
        "SD" to "SD", "480P" to "SD", "576P" to "SD", "HDR" to "HDR", "HDR10" to "HDR10", "HDR10+" to "HDR10+", "DV" to "DV",
        "HEVC" to "HEVC", "H265" to "HEVC", "X265" to "HEVC", "H.265" to "HEVC", "H264" to "AVC", "X264" to "AVC", "H.264" to "AVC",
        "AVC" to "AVC", "ATMOS" to "Atmos", "REMUX" to "Remux", "BLURAY" to "BluRay", "WEBRIP" to "WEBRip", "WEB-DL" to "WEB-DL",
        "WEBDL" to "WEB-DL", "MULTI" to "Multi", "MULTISUB" to "Multi-sub", "3D" to "3D",
    )
}
