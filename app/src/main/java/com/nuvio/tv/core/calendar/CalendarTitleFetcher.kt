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
    /** The followed shows ([wanted] as [calendarTvMazeAliases] makes them) in TVmaze's full schedule; throws on failure. */
    suspend fun tvMazeSchedule(wanted: Set<String>): TvMazeSchedule
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
    override suspend fun tvMazeSchedule(wanted: Set<String>) = tvMaze.schedule(wanted)
}

data class CalendarFetchOutcome(
    val record: CalendarTitleRecord,
    val tvMazeIds: Map<String, CalendarTvMazeId> = emptyMap()
)

/**
 * Shows TVmaze's full schedule covers ([applyTvMazeSchedule]) already have their dates; they are only read for TMDB's
 * finale marks while a release is near. Other shows: a show TVmaze has not been asked for yet is read from TMDB first. When TMDB knows it and has no release within
 * [EXACT_TIME_WINDOW], TMDB's plain dates are kept and TVmaze is left alone. Otherwise TVmaze (exact times), then Trakt's
 * next episode for shows TVmaze does not know, then TMDB's plain dates; TMDB's season adds finale marks when a TMDB id
 * is known. Films: TMDB release dates, unless the library dates the film two or more years back. Any failure keeps
 * the old dates and counts towards the back-off.
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
                record.scheduleCheckedAtMs > 0L -> refreshScheduled(record, tvMazeIds, learned, now)
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
        if (record.releasedYearsAgo(now)) return record
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
        val triage = if (record.tvmazeId == null && knownTvMazeId(record.ids, known) == null) tmdbTriage(record, now) else null
        if (triage?.settled == true) return triage.record
        val base = triage?.record ?: record
        val tvMazeId = base.tvmazeId ?: resolveTvMazeId(base.ids, known, learned, now)
        if (tvMazeId != null) {
            val dates = remote.tvMazeShow(tvMazeId, now)
            val updated = base.copy(
                tvmazeId = tvMazeId,
                ids = base.ids.merged(dates.ids),
                name = base.name ?: dates.name,
                poster = base.poster ?: dates.poster,
                network = dates.network ?: base.network,
                status = dates.status
            ).withSource(CalendarSource.TVMAZE, dates.entries, now).withoutSources(CalendarSource.TRAKT_NEXT)
            return withFinales(updated, now)
        }
        val trakt = remote.traktNextEpisode(base.ids)
        if (trakt != null && trakt.entries.isNotEmpty()) return base.withSource(CalendarSource.TRAKT_NEXT, trakt.entries, now)
        val withoutTrakt = if (trakt != null) base.withoutSources(CalendarSource.TRAKT_NEXT) else base
        if (triage?.answered == true) return withoutTrakt
        val withTmdb = withTmdbId(withoutTrakt)
        val tmdbId = withTmdb.ids.tmdb ?: return withTmdb
        return withTmdb.withTmdbShow(remote.tmdbShow(tmdbId), now)
    }

    /**
     * A show the schedule covers. While its TVmaze id is not known it is looked up once: when TVmaze has it, the daily
     * file is enough from then on; when TVmaze cannot match it, it moves to the per-title refresh for good. Otherwise
     * only TMDB's finale marks while a release is near; a failed TMDB call leaves them out until the next day.
     */
    private suspend fun refreshScheduled(
        record: CalendarTitleRecord,
        known: Map<String, CalendarTvMazeId>,
        learned: MutableMap<String, CalendarTvMazeId>,
        now: Instant
    ): CalendarTitleRecord {
        if (record.tvmazeId == null && knownTvMazeId(record.ids, known) == null) {
            val id = resolveTvMazeId(record.ids, known, learned, now)
                ?: return refreshShow(record.copy(scheduleCheckedAtMs = 0L), known + learned, learned, now).withPosterChecked(now)
            return record.copy(tvmazeId = id)
        }
        if (!record.wantsFinales(now)) return record
        val checked = record.copy(finalesCheckedAtMs = now.toEpochMilli())
        val withTmdb = try {
            withTmdbId(checked)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return checked
        }
        return withFinales(withTmdb, now)
    }

    /**
     * [settled] when TMDB knows the show, nothing of it comes out within [EXACT_TIME_WINDOW] and it is not on air.
     * [answered] is false when TMDB could not be read, so it is asked again at the end and a second failure counts.
     */
    private class TmdbTriage(val record: CalendarTitleRecord, val settled: Boolean, val answered: Boolean = true)

    /**
     * One or two TMDB calls instead of three to five TVmaze ones. Most followed shows have ended or are between seasons;
     * they only need TVmaze's exact times once a release comes near. A show whose last episode on TMDB came out within
     * [ON_AIR_WINDOW] is on air: TMDB may not list next week's episode yet, so it goes on to TVmaze as before. A TMDB
     * failure is not the show's failure here: it moves on to TVmaze, and TMDB is asked once more at the end.
     */
    private suspend fun tmdbTriage(record: CalendarTitleRecord, now: Instant): TmdbTriage {
        var withTmdb = record
        val dates = try {
            withTmdb = withTmdbId(record)
            val tmdbId = withTmdb.ids.tmdb ?: return TmdbTriage(withTmdb, settled = false)
            remote.tmdbShow(tmdbId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "${record.key}: TMDB first read failed, asking TVmaze: ${error.message}")
            return TmdbTriage(withTmdb, settled = false, answered = false)
        }
        val updated = withTmdb.withTmdbShow(dates, now)
        if (dates.status == CalendarShowStatus.UNKNOWN && dates.entries.isEmpty()) return TmdbTriage(updated, settled = false)
        val soon = updated.entries.any { entry ->
            entry.kind == CalendarEntryKind.EPISODE &&
                entry.sortInstant()?.let { !it.isBefore(now) && it.isBefore(now.plus(EXACT_TIME_WINDOW)) } == true
        }
        val onAir = dates.entries.any { entry ->
            entry.kind == CalendarEntryKind.EPISODE &&
                entry.sortInstant()?.let { !it.isAfter(now) && it.isAfter(now.minus(ON_AIR_WINDOW)) } == true
        }
        return TmdbTriage(updated, settled = !soon && !onAir)
    }

    private fun CalendarTitleRecord.withTmdbShow(dates: CalendarShowDates, now: Instant): CalendarTitleRecord = copy(
        name = name ?: dates.name,
        poster = poster ?: dates.poster,
        network = dates.network ?: network,
        status = dates.status
    ).withSource(CalendarSource.TMDB, dates.entries, now)

    private fun knownTvMazeId(ids: CalendarIds, known: Map<String, CalendarTvMazeId>): Int? =
        calendarTvMazeAliases(ids).firstNotNullOfOrNull { known[it]?.id }

    /** One TMDB season call marks the finales of the season with the next release, when that is within 60 days. */
    private suspend fun withFinales(record: CalendarTitleRecord, now: Instant): CalendarTitleRecord {
        val tmdbId = record.ids.tmdb ?: return record
        val next = record.entries.firstOrNull { entry ->
            entry.kind == CalendarEntryKind.EPISODE &&
                entry.sortInstant()?.let { !it.isBefore(now) && it.isBefore(now.plus(CALENDAR_FINALE_LOOKAHEAD)) } == true
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
        val aliases = calendarTvMazeAliases(ids)
        if (aliases.isEmpty()) return null
        aliases.firstNotNullOfOrNull { known[it]?.id }?.let { return it }
        val missingUntil = now.minus(NOT_FOUND_RECHECK).toEpochMilli()
        if (aliases.all { alias -> known[alias]?.let { it.id == null && it.checkedAtMs > missingUntil } == true }) return null
        val stamp = now.toEpochMilli()
        return when (val lookup = remote.tvMazeLookup(ids)) {
            is TvMazeLookup.Found -> {
                calendarTvMazeAliases(ids.merged(lookup.ids)).forEach { learned[it] = CalendarTvMazeId(lookup.id, stamp) }
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

    private suspend fun withTmdbId(record: CalendarTitleRecord): CalendarTitleRecord {
        if (record.ids.tmdb != null) return record
        val imdb = record.ids.imdb ?: return record
        val tmdbId = remote.tmdbFind(imdb, record.type) ?: return record
        return record.copy(ids = record.ids.copy(tmdb = tmdbId))
    }

    private companion object {
        const val TAG = "CalendarFetch"
        val NOT_FOUND_RECHECK: Duration = Duration.ofDays(30)
        /** The Calendar's reach; a show with a release within it is read from TVmaze for the exact time and its season. */
        val EXACT_TIME_WINDOW: Duration = Duration.ofDays(60)
        /** A show whose last TMDB episode came out within this long counts as on air. */
        val ON_AIR_WINDOW: Duration = Duration.ofDays(14)
    }
}
