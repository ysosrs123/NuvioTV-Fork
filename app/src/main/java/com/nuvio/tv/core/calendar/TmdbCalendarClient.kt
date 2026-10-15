package com.nuvio.tv.core.calendar

import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbEpisodeToAir
import com.nuvio.tv.data.remote.api.TmdbMovieReleaseDateCountry
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import retrofit2.Response

@Singleton
class TmdbCalendarClient @Inject constructor(
    private val tmdbApi: TmdbApi,
    private val tmdbService: TmdbService
) {
    private val apiKey: String get() = tmdbService.apiKey()

    suspend fun findTmdbId(imdb: String, type: CalendarTitleType): Long? {
        val body = tmdbApi.findByExternalId(imdb, apiKey).foundOrNull() ?: return null
        val results = if (type == CalendarTitleType.MOVIE) body.movieResults else body.tvResults
        return results?.firstOrNull()?.id?.toLong()
    }

    suspend fun film(tmdbId: Long, region: String): CalendarShowDates {
        val results = tmdbApi.getMovieReleaseDates(tmdbId.toInt(), apiKey).foundOrNull()?.results.orEmpty()
        return CalendarShowDates(entries = tmdbFilmReleaseEntries(results, region))
    }

    suspend fun show(tmdbId: Long): CalendarShowDates {
        val details = tmdbApi.getTvDetails(tmdbId.toInt(), apiKey).foundOrNull() ?: return CalendarShowDates()
        return CalendarShowDates(
            status = tmdbShowStatus(details.status),
            entries = listOfNotNull(details.lastEpisodeToAir, details.nextEpisodeToAir).mapNotNull(::tmdbEpisodeEntry),
            name = details.name,
            network = details.networks?.firstOrNull()?.name,
            poster = tmdbPosterUrl(details.posterPath)
        )
    }

    suspend fun season(tmdbId: Long, season: Int): List<CalendarEntry> {
        val body = tmdbApi.getTvSeasonDetails(tmdbId.toInt(), season, apiKey).foundOrNull() ?: return emptyList()
        return body.episodes.orEmpty().mapNotNull { episode ->
            tmdbEpisodeEntry(
                TmdbEpisodeToAir(
                    name = episode.name,
                    airDate = episode.airDate,
                    seasonNumber = episode.seasonNumber ?: body.seasonNumber ?: season,
                    episodeNumber = episode.episodeNumber,
                    episodeType = episode.episodeType
                )
            )
        }
    }

    private fun <T> Response<T>.foundOrNull(): T? = when {
        isSuccessful -> body()
        code() == 404 -> null
        else -> throw CalendarFetchException("TMDB ${code()}")
    }
}

fun tmdbEpisodeEntry(episode: TmdbEpisodeToAir): CalendarEntry? {
    val season = episode.seasonNumber ?: return null
    val number = episode.episodeNumber ?: return null
    val date = episode.airDate?.trim()?.takeIf { runCatching { LocalDate.parse(it) }.isSuccess } ?: return null
    val finale = tmdbEpisodeLabel(episode.episodeType)
    return CalendarEntry(
        season = season,
        episode = number,
        title = episode.name?.takeIf(String::isNotBlank),
        airDate = date,
        label = if (finale != CalendarEpisodeLabel.NONE) finale else deriveEpisodeLabel(season, number),
        source = CalendarSource.TMDB
    )
}

fun tmdbPosterUrl(path: String?): String? =
    path?.trim()?.takeIf { it.startsWith("/") }?.let { "https://image.tmdb.org/t/p/w185$it" }

fun tmdbShowStatus(status: String?): CalendarShowStatus = when (status?.trim()?.lowercase()) {
    "returning series" -> CalendarShowStatus.RUNNING
    "in production", "planned", "pilot" -> CalendarShowStatus.IN_PRODUCTION
    "ended" -> CalendarShowStatus.ENDED
    "canceled", "cancelled" -> CalendarShowStatus.CANCELLED
    else -> CalendarShowStatus.UNKNOWN
}

/**
 * A film's cinema date (type 3, or 2 when there is no 3) and digital date (type 4), each from [region] when it has
 * one, else from the US. Dates only.
 */
fun tmdbFilmReleaseEntries(results: List<TmdbMovieReleaseDateCountry>, region: String): List<CalendarEntry> {
    fun datesOf(country: String, type: Int): List<String> = results
        .filter { it.iso31661.equals(country, ignoreCase = true) }
        .flatMap { it.releaseDates.orEmpty() }
        .filter { it.type == type }
        .mapNotNull { item -> item.releaseDate?.take(10)?.takeIf { runCatching { LocalDate.parse(it) }.isSuccess } }
        .sorted()

    fun firstDate(vararg types: Int): String? {
        for (country in listOf(region, FALLBACK_REGION).distinct()) {
            for (type in types) datesOf(country, type).firstOrNull()?.let { return it }
        }
        return null
    }

    return listOfNotNull(
        firstDate(TYPE_THEATRICAL, TYPE_THEATRICAL_LIMITED)?.let {
            CalendarEntry(kind = CalendarEntryKind.MOVIE_THEATRICAL, airDate = it, source = CalendarSource.TMDB)
        },
        firstDate(TYPE_DIGITAL)?.let {
            CalendarEntry(kind = CalendarEntryKind.MOVIE_DIGITAL, airDate = it, source = CalendarSource.TMDB)
        }
    )
}

private const val FALLBACK_REGION = "US"
private const val TYPE_THEATRICAL_LIMITED = 2
private const val TYPE_THEATRICAL = 3
private const val TYPE_DIGITAL = 4
