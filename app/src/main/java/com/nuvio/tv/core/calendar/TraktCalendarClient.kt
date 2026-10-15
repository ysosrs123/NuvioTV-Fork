package com.nuvio.tv.core.calendar

import com.nuvio.tv.data.remote.api.TraktApi
import com.nuvio.tv.data.remote.dto.trakt.TraktCalendarEpisodeDto
import com.nuvio.tv.data.remote.dto.trakt.TraktCalendarShowDto
import com.nuvio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nuvio.tv.data.repository.TraktAuthService
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton

data class TraktCalendarItem(
    val ids: CalendarIds,
    val showTitle: String?,
    val status: CalendarShowStatus,
    val entry: CalendarEntry
)

@Singleton
class TraktCalendarClient @Inject constructor(
    private val traktApi: TraktApi,
    private val traktAuthService: TraktAuthService
) {
    /** Today - 7 to today + 58 in two calls (Trakt allows 33 days per call); null when a call failed. */
    suspend fun myShows(today: LocalDate): List<TraktCalendarItem>? {
        val rows = mutableListOf<TraktCalendarShowDto>()
        for ((start, days) in traktCalendarRanges(today)) {
            val response = traktAuthService.executeAuthorizedRequest { authorization ->
                traktApi.getMyShowsCalendar(authorization, start.toString(), days)
            } ?: return null
            if (!response.isSuccessful) return null
            rows += response.body().orEmpty()
        }
        return traktCalendarItems(rows)
    }

    /** Null when Trakt does not know the show or cannot be reached, so the caller moves on to TMDB. */
    suspend fun nextEpisode(ids: CalendarIds): CalendarShowDates? {
        val id = ids.trakt?.toString() ?: ids.imdb ?: return null
        val response = traktAuthService.executePublicRequest { traktApi.getShowNextEpisode(id) } ?: return null
        if (!response.isSuccessful) return null
        return CalendarShowDates(
            entries = listOfNotNull(response.body()?.let { traktEpisodeEntry(it, null, CalendarSource.TRAKT_NEXT) })
        )
    }
}

fun traktCalendarRanges(today: LocalDate): List<Pair<LocalDate, Int>> =
    listOf(today.minusDays(7) to TRAKT_CALENDAR_DAYS, today.plusDays(26) to TRAKT_CALENDAR_DAYS)

private const val TRAKT_CALENDAR_DAYS = 33

fun traktCalendarItems(rows: List<TraktCalendarShowDto>): List<TraktCalendarItem> = rows.mapNotNull { row ->
    val ids = row.show?.ids?.toCalendarIds() ?: return@mapNotNull null
    if (ids.isEmpty) return@mapNotNull null
    val entry = row.episode?.let { traktEpisodeEntry(it, row.firstAired) } ?: return@mapNotNull null
    TraktCalendarItem(ids, row.show.title, traktShowStatus(row.show.status), entry)
}.distinctBy { it.ids.primaryAlias() + "|" + it.entry.slot }

fun traktEpisodeEntry(
    episode: TraktCalendarEpisodeDto,
    rowFirstAired: String?,
    source: CalendarSource = CalendarSource.TRAKT
): CalendarEntry? {
    val season = episode.season ?: return null
    val number = episode.number ?: return null
    val instant = (episode.firstAired ?: rowFirstAired)?.let(::parseTraktInstant) ?: return null
    return CalendarEntry(
        season = season,
        episode = number,
        title = episode.title?.takeIf(String::isNotBlank),
        airInstantMs = instant.toEpochMilli(),
        label = traktEpisodeLabel(episode.episodeType, season, number),
        source = source
    )
}

fun traktShowStatus(status: String?): CalendarShowStatus = when (status?.trim()?.lowercase()) {
    "returning series", "continuing" -> CalendarShowStatus.RUNNING
    "in production", "planned", "upcoming", "pilot" -> CalendarShowStatus.IN_PRODUCTION
    "ended" -> CalendarShowStatus.ENDED
    "canceled", "cancelled" -> CalendarShowStatus.CANCELLED
    else -> CalendarShowStatus.UNKNOWN
}

private fun parseTraktInstant(value: String): Instant? =
    runCatching { Instant.parse(value) }.getOrNull() ?: runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()

private fun TraktIdsDto.toCalendarIds() = CalendarIds(
    imdb = imdb?.takeIf { it.startsWith("tt") },
    tmdb = tmdb?.toLong(),
    tvdb = tvdb?.toLong(),
    trakt = trakt?.toLong()
)

fun traktCalendarCandidates(items: List<TraktCalendarItem>): List<CalendarTitleCandidate> =
    items.distinctBy { it.ids.primaryAlias() }.map { item ->
        CalendarTitleCandidate(item.ids, CalendarTitleType.SERIES, CalendarOrigin.TRAKT_CALENDAR, name = item.showTitle)
    }

/**
 * The calendar's releases replace every show's [CalendarSource.TRAKT] dates. Trakt's next episode, asked for a show
 * TVmaze does not know, is kept apart as [CalendarSource.TRAKT_NEXT] and is not touched.
 */
fun applyTraktCalendar(
    records: List<CalendarTitleRecord>,
    items: List<TraktCalendarItem>,
    now: Instant
): List<CalendarTitleRecord> = records.map { record ->
    if (record.type != CalendarTitleType.SERIES) return@map record
    val mine = items.filter { it.ids.sharesIdWith(record.ids) }
    if (mine.isEmpty()) return@map record.withoutSources(CalendarSource.TRAKT)
    val status = if (record.status == CalendarShowStatus.UNKNOWN) mine.first().status else record.status
    record.copy(ids = mine.fold(record.ids) { ids, item -> ids.merged(item.ids) }, status = status)
        .withSource(CalendarSource.TRAKT, mine.map(TraktCalendarItem::entry), now)
}

fun CalendarTitleRecord.isCoveredByTrakt(traktCalendarFetchedAtMs: Long, now: Instant): Boolean =
    now.toEpochMilli() - traktCalendarFetchedAtMs < CALENDAR_TRAKT_REFRESH.toMillis() * 2 &&
        sources[CalendarSource.TRAKT].orEmpty().any { it.sortInstant()?.isAfter(now) == true }
