package com.nuvio.tv.core.iptv

import java.io.Reader
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

data class VodCategory(val id: String, val name: String, val kind: VodKind)

data class VodMovie(
    val providerId: String, val name: String, val year: Int? = null, val categoryId: String? = null, val poster: String? = null,
    val rating: Double? = null, val addedSeconds: Long? = null, val extension: String? = null, val tmdbId: String? = null, val imdbId: String? = null,
) {
    override fun toString(): String = "VodMovie(id=$providerId)"
}

data class VodSeries(
    val providerId: String, val name: String, val year: Int? = null, val categoryId: String? = null, val cover: String? = null,
    val rating: Double? = null, val addedSeconds: Long? = null, val tmdbId: String? = null, val imdbId: String? = null,
) {
    override fun toString(): String = "VodSeries(id=$providerId)"
}

data class VodEpisode(
    val providerId: String, val season: Int, val episode: Int, val title: String? = null, val extension: String? = null,
    val durationSeconds: Int? = null, val plot: String? = null, val still: String? = null, val tmdbId: String? = null,
) {
    override fun toString(): String = "VodEpisode(id=$providerId, season=$season, episode=$episode)"
}

data class VodDetails(
    val plot: String? = null, val cast: String? = null, val director: String? = null, val genre: String? = null, val rating: Double? = null,
    val poster: String? = null, val backdrop: String? = null, val year: Int? = null, val durationSeconds: Int? = null,
)

data class VodMovieInfo(
    val tmdbId: String?, val imdbId: String?, val year: Int?, val durationSeconds: Int?, val plot: String?, val poster: String?,
    val extension: String?, val details: VodDetails = VodDetails(),
) {
    override fun toString(): String = "VodMovieInfo(tmdb=${tmdbId != null}, imdb=${imdbId != null})"
}

data class VodSeriesInfo(val series: VodSeries?, val episodes: List<VodEpisode>, val invalidRows: Int = 0, val details: VodDetails = VodDetails()) {
    override fun toString(): String = "VodSeriesInfo(episodes=${episodes.size}, invalid=$invalidRows)"
}

data class VodParseCount(val accepted: Int, val invalid: Int)

object XtreamVodParser {
    const val MAX_TITLES = 200_000
    const val MAX_CATEGORIES = 10_000
    const val MAX_EPISODES = 20_000

    fun categories(input: Reader, kind: VodKind, max: Int = MAX_CATEGORIES): List<VodCategory> {
        val result = LinkedHashMap<String, VodCategory>()
        VodJsonReader(input).forEachRow { row ->
            val map = row as? Map<*, *> ?: return@forEachRow
            val id = categoryId(map["category_id"]) ?: return@forEachRow
            val name = text(map["category_name"])?.take(240) ?: return@forEachRow
            require(result.size < max) { "Category count limit" }
            result.putIfAbsent(id, VodCategory(id, name, kind))
        }
        return result.values.toList()
    }

    fun movies(input: Reader, max: Int = MAX_TITLES, checkCancellation: () -> Unit = {}, sink: (VodMovie) -> Unit): VodParseCount {
        var accepted = 0; var invalid = 0
        val seen = HashSet<String>()
        VodJsonReader(input).forEachRow(checkCancellation) { row ->
            val movie = (row as? Map<*, *>)?.let(::movie)
            if (movie == null || !seen.add(movie.providerId)) { invalid++; return@forEachRow }
            require(++accepted <= max) { "Title count limit" }
            sink(movie)
        }
        return VodParseCount(accepted, invalid)
    }

    fun series(input: Reader, max: Int = MAX_TITLES, checkCancellation: () -> Unit = {}, sink: (VodSeries) -> Unit): VodParseCount {
        var accepted = 0; var invalid = 0
        val seen = HashSet<String>()
        VodJsonReader(input).forEachRow(checkCancellation) { row ->
            val series = (row as? Map<*, *>)?.let { seriesRow(it, null) }
            if (series == null || !seen.add(series.providerId)) { invalid++; return@forEachRow }
            require(++accepted <= max) { "Title count limit" }
            sink(series)
        }
        return VodParseCount(accepted, invalid)
    }

    fun movie(row: Map<*, *>): VodMovie? {
        val id = identifier(row["stream_id"]) ?: return null
        val name = text(row["name"])?.take(1024) ?: text(row["title"])?.take(1024) ?: return null
        return VodMovie(id, name, year(row, name), categoryId(row["category_id"]), channelLogoUrl(text(row["stream_icon"]) ?: text(row["cover"])),
            rating(row), seconds(row["added"]), extension(row["container_extension"]), tmdb(row), imdb(row))
    }

    private fun seriesRow(row: Map<*, *>, fallbackId: String?): VodSeries? {
        val id = identifier(row["series_id"]) ?: fallbackId ?: return null
        val name = text(row["name"])?.take(1024) ?: text(row["title"])?.take(1024) ?: return null
        return VodSeries(id, name, year(row, name), categoryId(row["category_id"]), channelLogoUrl(text(row["cover"]) ?: text(row["cover_big"]) ?: text(row["stream_icon"])),
            rating(row), seconds(row["last_modified"]) ?: seconds(row["added"]), tmdb(row), imdb(row))
    }

    fun movieInfo(input: Reader): VodMovieInfo? {
        val root = VodJsonReader(input).readDocument() as? Map<*, *> ?: return null
        val info = root["info"] as? Map<*, *> ?: emptyMap<Any, Any>()
        val data = root["movie_data"] as? Map<*, *> ?: emptyMap<Any, Any>()
        if (info.isEmpty() && data.isEmpty()) return null
        val name = text(info["name"]) ?: text(data["name"])
        return VodMovieInfo(tmdb(info) ?: tmdb(data), imdb(info) ?: imdb(data), year(info, name) ?: year(data, null),
            duration(info), (text(info["plot"]) ?: text(info["description"]))?.take(4000),
            channelLogoUrl(text(info["movie_image"]) ?: text(info["cover_big"])), extension(data["container_extension"]), details(info, name))
    }

    fun details(row: Map<*, *>, name: String?): VodDetails = VodDetails(
        (text(row["plot"]) ?: text(row["description"]) ?: text(row["overview"]))?.take(4000), people(row["cast"]) ?: people(row["actors"]),
        people(row["director"]), people(row["genre"]), rating(row),
        channelLogoUrl(text(row["movie_image"]) ?: text(row["cover_big"]) ?: text(row["cover"])),
        backdrop(row["backdrop_path"]) ?: backdrop(row["backdrop"]), year(row, name), duration(row))

    private fun people(value: Any?): String? = when (value) {
        is List<*> -> value.mapNotNull(::text).joinToString(", ")
        else -> text(value)
    }.let { it?.replace(SPACES, " ")?.trim()?.trim(',')?.trim()?.takeIf(String::isNotEmpty)?.take(600) }

    private fun backdrop(value: Any?): String? = when (value) {
        is List<*> -> value.firstNotNullOfOrNull { channelLogoUrl(text(it)) }
        else -> channelLogoUrl(text(value))
    }

    fun seriesInfo(input: Reader, seriesId: String, max: Int = MAX_EPISODES): VodSeriesInfo {
        val root = VodJsonReader(input).readDocument() as? Map<*, *> ?: return VodSeriesInfo(null, emptyList())
        val infoRow = (root["info"] as? Map<*, *>)?.takeIf { it.isNotEmpty() }
        val series = infoRow?.let { seriesRow(it, seriesId) }
        val episodes = LinkedHashMap<String, VodEpisode>()
        var invalid = 0
        fun add(value: Any?, season: Int?) {
            val row = value as? Map<*, *>
            val episode = row?.let { episode(it, season) }
            if (episode == null || episodes.containsKey(episode.providerId)) { invalid++; return }
            require(episodes.size < max) { "Episode count limit" }
            episodes[episode.providerId] = episode
        }
        fun group(value: Any?, season: Int?) {
            when (value) {
                is List<*> -> value.forEach { item -> if (item is List<*>) item.forEach { add(it, season) } else add(item, season) }
                is Map<*, *> -> if (value.containsKey("id") && !value.values.any { it is List<*> }) add(value, season)
                    else value.forEach { (key, item) -> group(item, number(key) ?: season) }
                else -> Unit
            }
        }
        group(root["episodes"], null)
        val sorted = episodes.values.sortedWith(compareBy({ it.season }, { it.episode }, { it.providerId }))
        return VodSeriesInfo(series, sorted, invalid, infoRow?.let { details(it, series?.name) } ?: VodDetails())
    }

    private fun episode(row: Map<*, *>, season: Int?): VodEpisode? {
        val id = identifier(row["id"]) ?: identifier(row["stream_id"]) ?: return null
        val info = row["info"] as? Map<*, *> ?: emptyMap<Any, Any>()
        val title = text(row["title"]) ?: text(info["name"])
        val marker = title?.let(VodTitles::episode)
        val seasonNumber = number(row["season"]) ?: number(info["season"]) ?: season ?: marker?.season ?: return null
        val episodeNumber = number(row["episode_num"]) ?: number(row["episode"]) ?: number(info["episode_num"]) ?: marker?.episode ?: return null
        if (seasonNumber !in 0..300 || episodeNumber !in 0..5000) return null
        val cleanTitle = (marker?.title ?: title)?.take(512)
        return VodEpisode(id, seasonNumber, episodeNumber, cleanTitle, extension(row["container_extension"]), duration(info),
            (text(info["plot"]) ?: text(info["overview"]) ?: text(info["description"]))?.take(4000),
            channelLogoUrl(text(info["movie_image"]) ?: text(info["cover_big"]) ?: text(info["still_path"])), tmdb(info))
    }

    fun identifier(value: Any?): String? = when (value) {
        is String -> value.trim().takeIf { it.isNotEmpty() && it.length <= 20 && it.all { char -> char in '0'..'9' } }
        is VodJsonNumber -> value.raw.takeIf { it.length <= 20 && it.all { char -> char in '0'..'9' } }
        else -> null
    }

    fun text(value: Any?): String? = when (value) {
        is String -> value.trim().takeIf { it.isNotEmpty() && it != "null" }
        is VodJsonNumber -> value.raw
        else -> null
    }

    fun number(value: Any?): Int? = text(value)?.let { it.toIntOrNull() ?: it.toDoubleOrNull()?.takeIf { d -> d.isFinite() && d >= 0 && d <= Int.MAX_VALUE && d == Math.floor(d) }?.toInt() }

    private fun categoryId(value: Any?): String? = text(value)?.takeIf { it.length <= 64 && it.matches(SAFE_ID) }
    private fun seconds(value: Any?): Long? = text(value)?.toLongOrNull()?.takeIf { it in 1..4_102_444_800L }
    private fun extension(value: Any?): String? = text(value)?.lowercase(Locale.ROOT)?.removePrefix(".")?.takeIf { it.matches(EXTENSION) }

    private fun rating(row: Map<*, *>): Double? =
        text(row["rating"])?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 && it <= 10 }
            ?: text(row["rating_5based"])?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 && it <= 5 }?.let { it * 2 }

    private fun year(row: Map<*, *>, name: String?): Int? =
        number(row["year"])?.takeIf { it in 1888..2100 }
            ?: listOf("releaseDate", "releasedate", "release_date", "air_date").firstNotNullOfOrNull { VodTitles.yearOf(text(row[it])?.take(32)) }
            ?: name?.let { VodTitles.parse(it).year }

    private fun duration(info: Map<*, *>): Int? = number(info["duration_secs"])?.takeIf { it in 1..172_800 }
        ?: text(info["duration"])?.let { value ->
            CLOCK.matchEntire(value)?.groupValues?.let { (it[1].toInt() * 3600 + it[2].toInt() * 60 + it[3].toInt()).takeIf { s -> s in 1..172_800 } }
                ?: value.toIntOrNull()?.takeIf { it in 1..1_000 }?.let { it * 60 }
        }
        ?: number(info["episode_run_time"])?.takeIf { it in 1..1_000 }?.let { it * 60 }

    fun tmdb(row: Map<*, *>): String? = listOf("tmdb", "tmdb_id", "tmdbId").firstNotNullOfOrNull { key -> tmdbId(text(row[key])) }

    fun tmdbId(value: String?): String? {
        val text = value?.trim()?.takeIf { it.isNotEmpty() && it.length <= 200 } ?: return null
        val digits = if (text.all(Char::isDigit)) text else TMDB_URL.find(text)?.groupValues?.get(1) ?: return null
        return digits.trimStart('0').takeIf { it.isNotEmpty() && it.length <= 12 }
    }

    fun imdb(row: Map<*, *>): String? = listOf("imdb_id", "imdb", "imdbId").firstNotNullOfOrNull { key -> imdbId(text(row[key])) }

    fun imdbId(value: String?): String? = value?.let { IMDB.find(it)?.value?.lowercase(Locale.ROOT) }

    private val SAFE_ID = Regex("[A-Za-z0-9_-]+")
    private val SPACES = Regex("\\s+")
    private val EXTENSION = Regex("[a-z0-9]{1,8}")
    private val CLOCK = Regex("([0-9]{1,3}):([0-5][0-9]):([0-5][0-9])")
    private val TMDB_URL = Regex("(?:movie|tv)/([0-9]{1,12})")
    private val IMDB = Regex("(?i)\\btt[0-9]{5,10}\\b")
}

data class PlaylistVodEntry(
    val name: String, val locator: String, val kind: VodKind, val group: String?, val logo: String?,
    val headers: Map<String, String> = emptyMap(), val tmdbId: String? = null, val imdbId: String? = null,
) {
    override fun toString(): String = "PlaylistVodEntry(kind=$kind)"
}

data class PlaylistVodMovie(val movie: VodMovie, val locator: String, val headers: Map<String, String>) {
    override fun toString(): String = "PlaylistVodMovie(id=${movie.providerId})"
}

data class PlaylistVodEpisode(val seriesId: String, val episode: VodEpisode, val locator: String, val headers: Map<String, String>) {
    override fun toString(): String = "PlaylistVodEpisode(series=$seriesId, id=${episode.providerId})"
}

data class PlaylistVodCatalogue(
    val categories: List<VodCategory>, val movies: List<PlaylistVodMovie>, val series: List<VodSeries>, val episodes: List<PlaylistVodEpisode>,
) {
    val isEmpty: Boolean get() = movies.isEmpty() && series.isEmpty()
    override fun toString(): String = "PlaylistVodCatalogue(movies=${movies.size}, series=${series.size}, episodes=${episodes.size})"
}

object PlaylistVod {
    fun classify(name: String, locator: String, attributes: Map<String, String>): VodKind? {
        when (attributes["tvg-type"]?.trim()?.lowercase(Locale.ROOT)) {
            "movie", "movies", "vod", "film" -> return VodKind.MOVIE
            "series", "serie", "tvshow", "tv-show", "episode" -> return VodKind.SERIES
            "live", "channel", "radio" -> return null
        }
        val segments = pathSegments(locator)
        if (segments.size > 1) {
            val folders = segments.dropLast(1).map { it.lowercase(Locale.ROOT) }
            if ("movie" in folders || "movies" in folders) return VodKind.MOVIE
            if ("series" in folders) return VodKind.SERIES
        }
        val group = attributes["group-title"]?.let(VodTitles::fold) ?: return null
        val file = segments.lastOrNull()?.substringAfterLast('.', "")?.lowercase(Locale.ROOT) in VIDEO_FILES
        val episode = VodTitles.episode(name) != null
        return when {
            SERIES_GROUP.containsMatchIn(group) && (file || episode) -> VodKind.SERIES
            MOVIE_GROUP.containsMatchIn(group) && episode && file -> VodKind.SERIES
            MOVIE_GROUP.containsMatchIn(group) && (file || VodTitles.parse(name).year != null) -> VodKind.MOVIE
            else -> null
        }
    }

    fun entry(name: String, locator: String, attributes: Map<String, String>): PlaylistVodEntry? {
        val kind = classify(name, locator, attributes) ?: return null
        val tvgId = attributes["tvg-id"]
        return PlaylistVodEntry(name, locator, kind, attributes["group-title"]?.trim()?.takeIf { it.isNotEmpty() }?.take(240),
            attributes[CHANNEL_LOGO_ATTRIBUTE], attributes.filterKeys { it in StreamHeaders.attributes },
            XtreamVodParser.tmdbId(attributes["tvg-tmdb"] ?: attributes["tmdb"] ?: attributes["tmdb-id"]),
            XtreamVodParser.imdbId(attributes["tvg-imdb"] ?: attributes["imdb"] ?: tvgId?.takeIf { it.trim().startsWith("tt", ignoreCase = true) }))
    }

    fun catalogue(entries: List<PlaylistVodEntry>): PlaylistVodCatalogue {
        val categories = LinkedHashMap<Pair<VodKind, String>, VodCategory>()
        fun category(kind: VodKind, group: String?): String? = group?.let { name ->
            categories.getOrPut(kind to name) { VodCategory(hash("${kind.wire}:$name", 12), name, kind) }.id
        }
        val movies = LinkedHashMap<String, PlaylistVodMovie>()
        val series = LinkedHashMap<String, VodSeries>()
        val episodes = LinkedHashMap<String, PlaylistVodEpisode>()
        for (entry in entries) {
            val marker = if (entry.kind == VodKind.SERIES) VodTitles.episode(entry.name) else null
            val extension = pathSegments(entry.locator).lastOrNull()?.substringAfterLast('.', "")?.lowercase(Locale.ROOT)?.takeIf { it.matches(EXTENSION) }
            if (marker == null) {
                val title = VodTitles.parse(entry.name)
                val id = "m" + hash(entry.locator, 20)
                movies.putIfAbsent(id, PlaylistVodMovie(VodMovie(id, entry.name.take(1024), title.year, category(VodKind.MOVIE, entry.group),
                    channelLogoUrl(entry.logo), extension = extension, tmdbId = entry.tmdbId, imdbId = entry.imdbId), entry.locator, entry.headers))
                continue
            }
            val title = VodTitles.parse(marker.series)
            val seriesId = "s" + hash(title.matchKey.ifEmpty { marker.series } + ":" + (title.year ?: ""), 20)
            series.getOrPut(seriesId) {
                VodSeries(seriesId, marker.series.take(1024), title.year, category(VodKind.SERIES, entry.group), channelLogoUrl(entry.logo), tmdbId = entry.tmdbId, imdbId = entry.imdbId)
            }
            val id = "e" + hash(entry.locator, 20)
            episodes.putIfAbsent(id, PlaylistVodEpisode(seriesId, VodEpisode(id, marker.season, marker.episode, marker.title?.take(512), extension), entry.locator, entry.headers))
        }
        return PlaylistVodCatalogue(categories.values.toList(), movies.values.toList(), series.values.toList(),
            episodes.values.sortedWith(compareBy({ it.seriesId }, { it.episode.season }, { it.episode.episode })))
    }

    fun hash(value: String, length: Int): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(length)

    private fun pathSegments(locator: String): List<String> =
        runCatching { URI(locator).rawPath.orEmpty().split('/').filter { it.isNotEmpty() } }.getOrDefault(emptyList())

    private val EXTENSION = Regex("[a-z0-9]{1,8}")
    private val VIDEO_FILES = setOf("mkv", "mp4", "avi", "m4v", "mov", "wmv", "mpg", "mpeg", "webm", "flv", "divx", "ogv", "3gp")
    private val MOVIE_GROUP = Regex("(?<![\\p{L}\\p{N}])(vod|movies?|films?|filmes?|cinema|peliculas?|kino|boxoffice|box office)(?![\\p{L}\\p{N}])")
    private val SERIES_GROUP = Regex("(?<![\\p{L}\\p{N}])(series|serie|seriale?|tv ?shows?|shows|serien|seriados?)(?![\\p{L}\\p{N}])")
}
