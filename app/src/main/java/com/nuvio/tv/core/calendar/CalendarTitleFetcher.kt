package com.nuvio.tv.core.calendar

import android.util.Log
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

interface CalendarRemoteSources {
    suspend fun tvMazeLookup(ids: CalendarIds): TvMazeLookup
    suspend fun tvMazeShow(id: Int, now: Instant): CalendarShowDates
    /** Null when Trakt does not know the show or cannot be reached. */
    suspend fun traktNextEpisode(ids: CalendarIds): CalendarShowDates?
    suspend fun tmdbFind(imdb: String, type: CalendarTitleType): Long?
    suspend fun tmdbShow(tmdbId: Long): CalendarShowDates
    suspend fun tmdbSeason(tmdbId: Long, season: Int): List<CalendarEntry>
    suspend fun tmdbFilm(tmdbId: Long, region: String): CalendarShowDates
    /** Null when TheTVDB is off or has no regular time for the show. */
    suspend fun tvdbAirs(ids: CalendarIds): TvdbAirs? = null
}

@Singleton
class DefaultCalendarRemoteSources @Inject constructor(
    private val tvMaze: TvMazeCalendarClient,
    private val trakt: TraktCalendarClient,
    private val tmdb: TmdbCalendarClient,
    private val tvdb: TvdbCalendarClient
) : CalendarRemoteSources {
    override suspend fun tvMazeLookup(ids: CalendarIds) = tvMaze.lookup(ids)
    override suspend fun tvMazeShow(id: Int, now: Instant) = tvMaze.show(id, now)
    override suspend fun traktNextEpisode(ids: CalendarIds) = trakt.nextEpisode(ids)
    override suspend fun tmdbFind(imdb: String, type: CalendarTitleType) = tmdb.findTmdbId(imdb, type)
    override suspend fun tmdbShow(tmdbId: Long) = tmdb.show(tmdbId)
    override suspend fun tmdbSeason(tmdbId: Long, season: Int) = tmdb.season(tmdbId, season)
    override suspend fun tmdbFilm(tmdbId: Long, region: String) = tmdb.film(tmdbId, region)
    override suspend fun tvdbAirs(ids: CalendarIds) = tvdb.airs(ids)
}

data class CalendarFetchOutcome(
    val record: CalendarTitleRecord,
    val tvMazeIds: Map<String, CalendarTvMazeId> = emptyMap()
)

/**
 * Shows: TVmaze first (exact times), then Trakt's next episode for shows TVmaze does not know, then TMDB's plain dates;
 * TMDB's season adds finale marks when a TMDB id is known. Dates without a time (most streaming shows) take TheTVDB's
 * regular air time, and when that does not fit, Trakt's next episode gives the time. Films: TMDB release dates. Any
 * failure keeps the old dates and counts towards the back-off.
 */
class CalendarTitleFetcher(
    private val remote: CalendarRemoteSources,
    private val region: String
) {
    suspend fun refresh(
        record: CalendarTitleRecord,
        tvMazeIds: Map<String, CalendarTvMazeId>,
        coveredByTrakt: Boolean,
        now: Instant
    ): CalendarFetchOutcome {
        val learned = mutableMapOf<String, CalendarTvMazeId>()
        val refreshed = try {
            when {
                record.type == CalendarTitleType.MOVIE -> refreshFilm(record, now)
                coveredByTrakt && !record.needsPoster(now) -> record
                else -> refreshShow(record, tvMazeIds, learned, now).withPosterChecked(now)
            }.copy(failures = 0, fetchedAtMs = now.toEpochMilli())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "${record.key}: ${error.javaClass.simpleName} ${error.message}")
            return CalendarFetchOutcome(failed(record, now))
        }
        return CalendarFetchOutcome(refreshed.copy(nextRefreshAtMs = nextRefreshAt(refreshed, now).toEpochMilli()), learned)
    }

    fun failed(record: CalendarTitleRecord, now: Instant): CalendarTitleRecord {
        val counted = record.copy(failures = record.failures + 1)
        return counted.copy(nextRefreshAtMs = nextRefreshAt(counted, now).toEpochMilli())
    }

    private suspend fun refreshFilm(record: CalendarTitleRecord, now: Instant): CalendarTitleRecord {
        val withTmdb = withTmdbId(record)
        val tmdbId = withTmdb.ids.tmdb ?: return withTmdb
        return withTmdb.withSource(CalendarSource.TMDB, remote.tmdbFilm(tmdbId, region).entries, now)
    }

    private suspend fun refreshShow(
        record: CalendarTitleRecord,
        known: Map<String, CalendarTvMazeId>,
        learned: MutableMap<String, CalendarTvMazeId>,
        now: Instant
    ): CalendarTitleRecord {
        val tvMazeId = record.tvmazeId ?: resolveTvMazeId(record.ids, known, learned, now)
        if (tvMazeId != null) {
            val dates = remote.tvMazeShow(tvMazeId, now)
            val updated = record.copy(
                tvmazeId = tvMazeId,
                ids = record.ids.merged(dates.ids),
                name = record.name ?: dates.name,
                poster = record.poster ?: dates.poster,
                network = dates.network ?: record.network,
                networkZone = dates.networkZone ?: record.networkZone,
                status = dates.status
            ).withSource(CalendarSource.TVMAZE, dates.entries, now)
            return withFinales(withTraktTime(withTvdbTimes(updated, CalendarSource.TVMAZE, now), now), now)
        }
        val trakt = try {
            remote.traktNextEpisode(record.ids)
        } catch (unreachable: TraktUnreachable) {
            if (!record.sources[CalendarSource.TRAKT_NEXT].isNullOrEmpty()) return record
            null
        }
        if (trakt != null && trakt.entries.isNotEmpty()) return record.withSource(CalendarSource.TRAKT_NEXT, trakt.entries, now)
        val withTmdb = withTmdbId(if (trakt != null) record.withoutSources(CalendarSource.TRAKT_NEXT) else record)
        val tmdbId = withTmdb.ids.tmdb ?: return withTmdb
        val dates = remote.tmdbShow(tmdbId)
        val updated = withTmdb.copy(
            name = withTmdb.name ?: dates.name,
            poster = withTmdb.poster ?: dates.poster,
            network = dates.network ?: withTmdb.network,
            status = dates.status
        ).withSource(CalendarSource.TMDB, dates.entries, now)
        return withTvdbTimes(updated, CalendarSource.TMDB, now)
    }

    /** TheTVDB's client keeps its own answers for six hours; a failed ask keeps the times found before. */
    private suspend fun withTvdbTimes(record: CalendarTitleRecord, base: CalendarSource, now: Instant): CalendarTitleRecord {
        val dateOnly = record.sources[base].orEmpty().filter { it.kind == CalendarEntryKind.EPISODE && !it.timeKnown && !it.isOut(now) }
        if (dateOnly.isEmpty()) return record.withoutSources(CalendarSource.TVDB)
        val airs = try {
            remote.tvdbAirs(record.ids)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return record
        } ?: return record.withoutSources(CalendarSource.TVDB)
        val timed = calendarTvdbTimes(dateOnly, airs, now)
        return if (timed.isEmpty()) record.withoutSources(CalendarSource.TVDB) else record.withSource(CalendarSource.TVDB, timed, now)
    }

    /** Trakt's answer is kept for [TRAKT_TIME_RECHECK]; a failed ask keeps the old one. */
    private suspend fun withTraktTime(record: CalendarTitleRecord, now: Instant): CalendarTitleRecord {
        val next = record.sources[CalendarSource.TVMAZE].orEmpty()
            .filter { it.kind == CalendarEntryKind.EPISODE && !it.isOut(now) }
            .minByOrNull { it.sortInstant() ?: Instant.MAX }
        if (next == null || next.timeKnown || next.airDate == null) return record.withoutSources(CalendarSource.TRAKT_NEXT)
        if (record.sources[CalendarSource.TVDB].orEmpty().any { it.slot == next.slot }) return record.withoutSources(CalendarSource.TRAKT_NEXT)
        val askedAt = record.sourceFetchedAtMs[CalendarSource.TRAKT_NEXT]
        val kept = record.sources[CalendarSource.TRAKT_NEXT].orEmpty()
        if (askedAt != null && now.toEpochMilli() - askedAt < TRAKT_TIME_RECHECK.toMillis() && kept.any { it.slot == next.slot }) {
            return record
        }
        val trakt = try {
            remote.traktNextEpisode(record.ids)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return record
        }
        val timed = trakt?.entries?.let { calendarTraktTimeFor(next, it) } ?: return record.withoutSources(CalendarSource.TRAKT_NEXT)
        return record.withSource(CalendarSource.TRAKT_NEXT, listOf(timed), now)
    }

    /** One TMDB season call marks the finales of the season with the next release, when that is within 60 days. */
    private suspend fun withFinales(record: CalendarTitleRecord, now: Instant): CalendarTitleRecord {
        val tmdbId = record.ids.tmdb ?: return record
        val next = record.entries.firstOrNull { entry ->
            entry.kind == CalendarEntryKind.EPISODE &&
                entry.sortInstant()?.let { !it.isBefore(now) && it.isBefore(now.plus(FINALE_LOOKAHEAD)) } == true
        } ?: return record.withSource(CalendarSource.TMDB, emptyList(), now)
        val season = next.season ?: return record
        val finales = try {
            remote.tmdbSeason(tmdbId, season).filter { it.label != CalendarEpisodeLabel.NONE }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return record
        }
        return record.withSource(CalendarSource.TMDB, finales, now)
    }

    private suspend fun resolveTvMazeId(
        ids: CalendarIds,
        known: Map<String, CalendarTvMazeId>,
        learned: MutableMap<String, CalendarTvMazeId>,
        now: Instant
    ): Int? {
        val aliases = tvMazeAliases(ids)
        if (aliases.isEmpty()) return null
        aliases.firstNotNullOfOrNull { known[it]?.id }?.let { return it }
        val missingUntil = now.minus(NOT_FOUND_RECHECK).toEpochMilli()
        if (aliases.all { alias -> known[alias]?.let { it.id == null && it.checkedAtMs > missingUntil } == true }) return null
        val stamp = now.toEpochMilli()
        return when (val lookup = remote.tvMazeLookup(ids)) {
            is TvMazeLookup.Found -> {
                tvMazeAliases(ids.merged(lookup.ids)).forEach { learned[it] = CalendarTvMazeId(lookup.id, stamp) }
                lookup.id
            }
            TvMazeLookup.NotFound -> {
                aliases.forEach { learned[it] = CalendarTvMazeId(null, stamp) }
                null
            }
        }
    }

    /** A show the Trakt calendar covers is still read once a month while it has no poster, for TVmaze's or TMDB's. */
    private fun CalendarTitleRecord.needsPoster(now: Instant): Boolean =
        poster.isNullOrBlank() && now.toEpochMilli() - posterCheckedAtMs >= NOT_FOUND_RECHECK.toMillis()

    private fun CalendarTitleRecord.withPosterChecked(now: Instant): CalendarTitleRecord =
        if (poster.isNullOrBlank()) copy(posterCheckedAtMs = now.toEpochMilli()) else this

    private fun tvMazeAliases(ids: CalendarIds): List<String> =
        listOfNotNull(ids.imdb?.let { "imdb:$it" }, ids.tvdb?.let { "tvdb:$it" })

    private suspend fun withTmdbId(record: CalendarTitleRecord): CalendarTitleRecord {
        if (record.ids.tmdb != null) return record
        val imdb = record.ids.imdb ?: return record
        val tmdbId = remote.tmdbFind(imdb, record.type) ?: return record
        return record.copy(ids = record.ids.copy(tmdb = tmdbId))
    }

    private companion object {
        const val TAG = "CalendarFetch"
        val NOT_FOUND_RECHECK: Duration = Duration.ofDays(30)
        val FINALE_LOOKAHEAD: Duration = Duration.ofDays(60)
        val TRAKT_TIME_RECHECK: Duration = Duration.ofHours(6)
    }
}

/**
 * Trakt's timed release for an episode TVmaze only dates: the same episode, or the one out within a day of that date
 * (TVmaze may number it differently), under TVmaze's number so the two merge into one release.
 */
fun calendarTraktTimeFor(dateOnly: CalendarEntry, trakt: List<CalendarEntry>): CalendarEntry? {
    val date = dateOnly.airDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
    val near = trakt.filter { entry ->
        val at = entry.airInstantMs ?: return@filter false
        entry.kind == CalendarEntryKind.EPISODE &&
            Math.abs(ChronoUnit.DAYS.between(date, Instant.ofEpochMilli(at).atZone(ZoneOffset.UTC).toLocalDate())) <= 1
    }
    val match = near.firstOrNull { it.slot == dateOnly.slot } ?: near.singleOrNull() ?: return null
    return match.copy(season = dateOnly.season, episode = dateOnly.episode, source = CalendarSource.TRAKT_NEXT)
}
