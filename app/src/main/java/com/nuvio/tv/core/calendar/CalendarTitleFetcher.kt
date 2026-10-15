package com.nuvio.tv.core.calendar

import android.util.Log
import java.time.Duration
import java.time.Instant
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
}

@Singleton
class DefaultCalendarRemoteSources @Inject constructor(
    private val tvMaze: TvMazeCalendarClient,
    private val trakt: TraktCalendarClient,
    private val tmdb: TmdbCalendarClient
) : CalendarRemoteSources {
    override suspend fun tvMazeLookup(ids: CalendarIds) = tvMaze.lookup(ids)
    override suspend fun tvMazeShow(id: Int, now: Instant) = tvMaze.show(id, now)
    override suspend fun traktNextEpisode(ids: CalendarIds) = trakt.nextEpisode(ids)
    override suspend fun tmdbFind(imdb: String, type: CalendarTitleType) = tmdb.findTmdbId(imdb, type)
    override suspend fun tmdbShow(tmdbId: Long) = tmdb.show(tmdbId)
    override suspend fun tmdbSeason(tmdbId: Long, season: Int) = tmdb.season(tmdbId, season)
    override suspend fun tmdbFilm(tmdbId: Long, region: String) = tmdb.film(tmdbId, region)
}

data class CalendarFetchOutcome(
    val record: CalendarTitleRecord,
    val tvMazeIds: Map<String, CalendarTvMazeId> = emptyMap()
)

/**
 * Shows: TVmaze first (exact times), then Trakt's next episode for shows TVmaze does not know, then TMDB's plain dates;
 * TMDB's season adds finale marks when a TMDB id is known. Films: TMDB release dates. Any failure keeps the old dates
 * and counts towards the back-off.
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
                status = dates.status
            ).withSource(CalendarSource.TVMAZE, dates.entries, now).withoutSources(CalendarSource.TRAKT_NEXT)
            return withFinales(updated, now)
        }
        val trakt = remote.traktNextEpisode(record.ids)
        if (trakt != null && trakt.entries.isNotEmpty()) return record.withSource(CalendarSource.TRAKT_NEXT, trakt.entries, now)
        val withTmdb = withTmdbId(if (trakt != null) record.withoutSources(CalendarSource.TRAKT_NEXT) else record)
        val tmdbId = withTmdb.ids.tmdb ?: return withTmdb
        val dates = remote.tmdbShow(tmdbId)
        return withTmdb.copy(
            name = withTmdb.name ?: dates.name,
            poster = withTmdb.poster ?: dates.poster,
            network = dates.network ?: withTmdb.network,
            status = dates.status
        ).withSource(CalendarSource.TMDB, dates.entries, now)
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
    }
}
