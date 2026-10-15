package com.nuvio.tv.core.calendar

import android.os.SystemClock
import com.nuvio.tv.data.remote.api.TvMazeApi
import com.nuvio.tv.data.remote.dto.TvMazeEpisodeDto
import com.nuvio.tv.data.remote.dto.TvMazeShowDto
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton
import retrofit2.Response

class CalendarFetchException(message: String) : IOException(message)

sealed interface TvMazeLookup {
    data class Found(val id: Int, val ids: CalendarIds) : TvMazeLookup
    data object NotFound : TvMazeLookup
}

data class CalendarShowDates(
    val status: CalendarShowStatus = CalendarShowStatus.UNKNOWN,
    val entries: List<CalendarEntry> = emptyList(),
    val name: String? = null,
    val network: String? = null,
    val ids: CalendarIds = CalendarIds(),
    val poster: String? = null
)

@Singleton
class TvMazeCalendarClient internal constructor(
    private val api: TvMazeApi,
    private val limiter: CalendarRateLimiter
) {
    @Inject
    constructor(api: TvMazeApi) : this(api, CalendarRateLimiter(nowMs = SystemClock::elapsedRealtime))

    suspend fun lookup(ids: CalendarIds): TvMazeLookup {
        ids.imdb?.let { imdb -> call(LOOKUP_PERMITS) { api.lookupByImdb(imdb) }.foundOrNull()?.let { return it.toLookup() } }
        ids.tvdb?.let { tvdb -> call(LOOKUP_PERMITS) { api.lookupByTvdb(tvdb) }.foundOrNull()?.let { return it.toLookup() } }
        return TvMazeLookup.NotFound
    }

    /** When the next episode is within [SEASON_FETCH_AHEAD], its whole season is read too. */
    suspend fun show(id: Int, now: Instant): CalendarShowDates {
        val show = call { api.getShow(id) }.foundOrNull() ?: return CalendarShowDates()
        val next = show.embedded?.nextEpisode
        val nextAt = next?.let(::tvMazeEpisodeEntry)?.sortInstant()
        val seasonEpisodes = if (next?.season != null && nextAt != null && nextAt.isBefore(now.plus(SEASON_FETCH_AHEAD))) {
            val seasonId = show.embedded.seasons?.firstOrNull { it.number == next.season }?.id
                ?: call { api.getSeasons(id) }.foundOrNull()?.firstOrNull { it.number == next.season }?.id
            seasonId?.let { call { api.getSeasonEpisodes(it) }.foundOrNull() }.orEmpty()
        } else {
            emptyList()
        }
        return tvMazeShowDates(show, seasonEpisodes)
    }

    private suspend fun <T> call(permits: Int = 1, block: suspend () -> Response<T>): Response<T> {
        repeat(MAX_ATTEMPTS) {
            limiter.acquire(permits)
            val response = block()
            if (response.code() != 429) return response
            limiter.pauseFor(parseRetryAfterMs(response.headers()["Retry-After"]) ?: DEFAULT_RETRY_MS)
        }
        throw CalendarFetchException("TVmaze rate limit")
    }

    private fun <T> Response<T>.foundOrNull(): T? = when {
        isSuccessful -> body()
        code() == 404 -> null
        else -> throw CalendarFetchException("TVmaze ${code()}")
    }

    private fun TvMazeShowDto.toLookup() = TvMazeLookup.Found(
        id,
        CalendarIds(imdb = externals?.imdb?.takeIf { it.startsWith("tt") }, tvdb = externals?.thetvdb)
    )

    internal companion object {
        /** A lookup answers with a redirect to the show, which is a second request. */
        const val LOOKUP_PERMITS = 2
        const val MAX_ATTEMPTS = 3
        const val DEFAULT_RETRY_MS = 10_000L
        val SEASON_FETCH_AHEAD: Duration = Duration.ofDays(60)
    }
}

fun tvMazeShowDates(show: TvMazeShowDto, seasonEpisodes: List<TvMazeEpisodeDto>): CalendarShowDates {
    val episodes = listOfNotNull(show.embedded?.previousEpisode, show.embedded?.nextEpisode) + seasonEpisodes
    return CalendarShowDates(
        status = tvMazeStatus(show.status),
        entries = episodes.mapNotNull(::tvMazeEpisodeEntry).distinctBy(CalendarEntry::slot),
        name = show.name,
        network = show.network?.name ?: show.webChannel?.name,
        ids = CalendarIds(imdb = show.externals?.imdb?.takeIf { it.startsWith("tt") }, tvdb = show.externals?.thetvdb),
        poster = show.image?.let { it.medium ?: it.original }?.takeIf(String::isNotBlank)?.replaceFirst("http://", "https://")
    )
}

/** An episode with an air time keeps its exact `airstamp`; without one only the plain date is kept. */
fun tvMazeEpisodeEntry(episode: TvMazeEpisodeDto): CalendarEntry? {
    val season = episode.season ?: return null
    val number = episode.number ?: return null
    if (episode.type.equals("insignificant_special", ignoreCase = true)) return null
    val instant = episode.airstamp
        ?.takeIf { !episode.airtime.isNullOrBlank() }
        ?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }
    val date = episode.airdate?.takeIf { runCatching { LocalDate.parse(it) }.isSuccess }
    if (instant == null && date == null) return null
    return CalendarEntry(
        season = season,
        episode = number,
        title = episode.name?.takeIf(String::isNotBlank),
        airInstantMs = instant,
        airDate = if (instant == null) date else null,
        label = deriveEpisodeLabel(season, number),
        source = CalendarSource.TVMAZE
    )
}

fun tvMazeStatus(status: String?): CalendarShowStatus = when (status?.trim()?.lowercase()) {
    "running" -> CalendarShowStatus.RUNNING
    "in development" -> CalendarShowStatus.IN_PRODUCTION
    "ended" -> CalendarShowStatus.ENDED
    else -> CalendarShowStatus.UNKNOWN
}
