package com.nuvio.tv.core.calendar

import android.util.Log
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingProviderRegistry
import com.nuvio.tv.core.tracking.effectiveLibrarySourceMode
import com.nuvio.tv.core.tracking.effectiveWatchProgressSource
import com.nuvio.tv.core.tracking.providerId
import com.nuvio.tv.data.local.ContinueWatchingEnrichmentCache
import com.nuvio.tv.data.local.LibraryPreferences
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.data.local.WatchProgressSource
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.data.mdblist.MDBLIST_WATCHLIST_KEY
import com.nuvio.tv.data.mdblist.MdbListIds
import com.nuvio.tv.data.mdblist.MdbListItemType
import com.nuvio.tv.data.mdblist.MdbListSyncRepository
import com.nuvio.tv.data.mdblist.isMdbListReadOnlyListKey
import com.nuvio.tv.data.repository.TraktLibraryService
import com.nuvio.tv.data.repository.TraktProgressService
import com.nuvio.tv.domain.model.LibrarySourceMode
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.data.simkl.SimklListStatus
import com.nuvio.tv.data.simkl.SimklSyncRepository
import com.nuvio.tv.data.simkl.idValue
import com.nuvio.tv.data.simkl.simklIdValue
import java.time.Instant
import java.time.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

data class CalendarFollowedSnapshot(
    val candidates: List<CalendarTitleCandidate> = emptyList(),
    val removedFromContinueWatching: Set<String> = emptySet(),
    val addonEntries: List<Pair<CalendarIds, CalendarEntry>> = emptyList(),
    val watched: CalendarWatchedIndex = CalendarWatchedIndex(),
    /** False while Trakt is followed but its watched shows are not loaded yet: their 12-month check cannot be made. */
    val traktWatchedLoaded: Boolean = true,
    /** Whether Trakt's own calendar adds shows (Trakt is followed for progress); its dates are used either way. */
    val traktFollowed: Boolean = true,
    /** Posters the app already holds for titles their sources left without one, by id alias. */
    val cachedPosters: Map<String, String> = emptyMap(),
    /** Sources that could not be read in full this time, with why: "timed out", "failed" or "not loaded yet". */
    val notReady: Map<String, String> = emptyMap(),
    /** Origins those sources would have given; titles the Calendar holds from them are kept until they can be read. */
    val unreadOrigins: Set<CalendarOrigin> = emptySet(),
    /** Backdrops the app already holds, by id alias. */
    val cachedArt: Map<String, CalendarArt> = emptyMap()
) {
    val complete: Boolean get() = notReady.isEmpty()
}

class CalendarWatchedIndex(private val keys: Set<String> = emptySet()) {
    fun isWatched(ids: CalendarIds, season: Int?, episode: Int?): Boolean {
        if (season == null || episode == null) return false
        return ids.aliases().any { "$it|$season|$episode" in keys }
    }

    fun isWatched(title: CalendarTitleRecord, entry: CalendarEntry): Boolean =
        entry.kind == CalendarEntryKind.EPISODE && isWatched(title.ids, entry.season, entry.episode)

    class Builder {
        private val keys = HashSet<String>()

        fun add(contentId: String, season: Int?, episode: Int?) = apply {
            if (season == null || episode == null) return@apply
            CalendarIds.fromContentId(contentId).aliases().forEach { keys += "$it|$season|$episode" }
        }

        fun addAll(byContent: Map<String, Set<Pair<Int, Int>>>) = apply {
            byContent.forEach { (contentId, episodes) -> episodes.forEach { (s, e) -> add(contentId, s, e) } }
        }

        fun build() = CalendarWatchedIndex(keys)
    }
}

/**
 * Which services the Calendar follows. By default the library source (the Nuvio library when that is the source) for
 * saved titles and the progress source for started ones; [followOtherServices] reads every signed-in service.
 */
data class CalendarSourcePlan(
    val nuvioLibrary: Boolean,
    val libraryFrom: Set<TrackingProviderId>,
    val progressFrom: Set<TrackingProviderId>
) {
    fun reads(provider: TrackingProviderId) = provider in libraryFrom || provider in progressFrom
}

fun calendarSourcePlan(
    librarySource: LibrarySourceMode,
    progressSource: WatchProgressSource,
    signedIn: Set<TrackingProviderId>,
    followOtherServices: Boolean
): CalendarSourcePlan {
    if (followOtherServices) return CalendarSourcePlan(nuvioLibrary = true, libraryFrom = signedIn, progressFrom = signedIn)
    val library = effectiveLibrarySourceMode(librarySource) { it in signedIn }.providerId
    val progress = effectiveWatchProgressSource(progressSource) { it in signedIn }.providerId
    return CalendarSourcePlan(nuvioLibrary = library == null, libraryFrom = setOfNotNull(library), progressFrom = setOfNotNull(progress))
}

/** Simkl's lists are its library; as progress source only the started ones and Dropped count. */
fun calendarSimklOrigin(status: SimklListStatus?, asLibrary: Boolean): CalendarOrigin? = when (status) {
    SimklListStatus.WATCHING -> CalendarOrigin.SIMKL_WATCHING
    SimklListStatus.COMPLETED -> CalendarOrigin.SIMKL_COMPLETED
    SimklListStatus.ON_HOLD -> CalendarOrigin.SIMKL_ON_HOLD
    SimklListStatus.PLAN_TO_WATCH -> CalendarOrigin.SIMKL_PLAN_TO_WATCH.takeIf { asLibrary }
    SimklListStatus.DROPPED -> CalendarOrigin.DROPPED
    null -> null
}

/** Liked, dynamic and external MDBList lists are other people's lists and are never followed. */
fun calendarFollowsMdbListList(listKey: String): Boolean = !isMdbListReadOnlyListKey(listKey)

/** Reads only what the app already holds; it never starts a sync of its own. */
@Singleton
class CalendarFollowedSourceReader @Inject constructor(
    private val libraryPreferences: LibraryPreferences,
    private val watchProgressPreferences: WatchProgressPreferences,
    private val watchedItemsPreferences: WatchedItemsPreferences,
    private val traktLibraryService: TraktLibraryService,
    private val traktProgressService: TraktProgressService,
    private val simklSyncRepository: SimklSyncRepository,
    private val mdbListSyncRepository: MdbListSyncRepository,
    private val traktSettingsDataStore: TraktSettingsDataStore,
    private val enrichmentCache: ContinueWatchingEnrichmentCache,
    private val trackingProviders: TrackingProviderRegistry,
    private val metaRepository: MetaRepository
) {
    suspend fun read(profileId: Int, followOtherServices: Boolean = false): CalendarFollowedSnapshot {
        val candidates = mutableListOf<CalendarTitleCandidate>()
        val watched = CalendarWatchedIndex.Builder()
        val unread = CalendarSourceParts()
        val signedIn = TrackingProviderId.entries.filterTo(mutableSetOf()) { trackingProviders.isAuthenticated(it) }
        val everything = calendarSourcePlan(LibrarySourceMode.LOCAL, WatchProgressSource.NUVIO_SYNC, signedIn, followOtherServices = true)
        val plan = if (followOtherServices) everything else unread.runPart("sources") {
            calendarSourcePlan(
                traktSettingsDataStore.getLibrarySourceMode(profileId),
                traktSettingsDataStore.getWatchProgressSource(profileId),
                signedIn, followOtherServices = false
            )
        } ?: everything

        if (plan.nuvioLibrary) unread.part("library", CalendarOrigin.LIBRARY) {
            libraryPreferences.libraryItems.first().forEach { item ->
                val type = calendarTitleType(item.type) ?: return@forEach
                candidates += CalendarTitleCandidate(
                    CalendarIds.fromContentId(item.id), type, CalendarOrigin.LIBRARY,
                    item.addedAt.toInstantOrNull(), item.name, item.id, item.poster,
                    releaseYear = calendarReleaseYear(item.releaseInfo)
                )
            }
        }
        unread.part("progress", CalendarOrigin.PROGRESS) {
            watchProgressPreferences.observeAllProgress(profileId).first()
                .filter { calendarTitleType(it.contentType) == CalendarTitleType.SERIES }
                .forEach { progress ->
                    candidates += CalendarTitleCandidate(
                        CalendarIds.fromContentId(progress.contentId), CalendarTitleType.SERIES, CalendarOrigin.PROGRESS,
                        progress.lastWatched.toInstantOrNull(), progress.name, progress.contentId, progress.poster
                    )
                    if (progress.isCompleted()) watched.add(progress.contentId, progress.season, progress.episode)
                }
        }
        unread.part("watched", CalendarOrigin.WATCHED) {
            watchedItemsPreferences.allItems.first()
                .filter { it.season != null || calendarTitleType(it.contentType) == CalendarTitleType.SERIES }
                .groupBy { it.contentId }
                .forEach { (contentId, items) ->
                    items.forEach { watched.add(contentId, it.season, it.episode) }
                    candidates += CalendarTitleCandidate(
                        CalendarIds.fromContentId(contentId), CalendarTitleType.SERIES, CalendarOrigin.WATCHED,
                        items.maxOf { it.watchedAt }.toInstantOrNull(), items.first().title, contentId, items.first().poster
                    )
                }
        }
        val traktProgress = TrackingProviderId.TRAKT in plan.progressFrom
        var traktWatchedLoaded = !traktProgress
        if (traktProgress) unread.part("trakt", *TRAKT_PROGRESS_ORIGINS) {
            val seeds = traktProgressService.currentWatchedShowSeeds()
            traktWatchedLoaded = seeds != null
            if (seeds == null) unread.notLoaded("trakt", *TRAKT_PROGRESS_ORIGINS)
            traktProgressService.currentDroppedShowIds().forEach { contentId ->
                candidates += CalendarTitleCandidate(CalendarIds.fromContentId(contentId), CalendarTitleType.SERIES, CalendarOrigin.DROPPED)
            }
            seeds.orEmpty().forEach { seed ->
                candidates += CalendarTitleCandidate(
                    CalendarIds.fromContentId(seed.contentId).merged(CalendarIds(trakt = seed.traktShowId?.toLong())),
                    CalendarTitleType.SERIES, CalendarOrigin.TRAKT_WATCHED,
                    seed.lastWatched.toInstantOrNull(), seed.name, seed.contentId, seed.poster
                )
            }
            watched.addAll(traktProgressService.getWatchedShowEpisodes())
        }
        if (TrackingProviderId.TRAKT in plan.libraryFrom) unread.part("trakt lists", *TRAKT_LIBRARY_ORIGINS) {
            val listed = traktLibraryService.currentItemsOrNull()
            if (listed == null) unread.notLoaded("trakt lists", *TRAKT_LIBRARY_ORIGINS)
            listed.orEmpty().forEach { entry ->
                val type = calendarTitleType(entry.type) ?: return@forEach
                val ids = CalendarIds.fromContentId(entry.id).merged(
                    CalendarIds(imdb = entry.imdbId, tmdb = entry.tmdbId?.toLong(), trakt = entry.traktId?.toLong())
                )
                val origins = buildList {
                    if (TraktLibraryService.WATCHLIST_KEY in entry.listKeys) add(CalendarOrigin.TRAKT_WATCHLIST)
                    if (entry.listKeys.any { it.startsWith(TraktLibraryService.PERSONAL_KEY_PREFIX) }) add(CalendarOrigin.TRAKT_LIST)
                }
                origins.forEach { origin ->
                    candidates += CalendarTitleCandidate(
                        ids, type, origin, entry.listedAt.toInstantOrNull(), entry.name, entry.id, entry.poster
                    )
                }
            }
        }
        val simklAsLibrary = TrackingProviderId.SIMKL in plan.libraryFrom
        val simklOrigins = if (simklAsLibrary) SIMKL_ORIGINS else SIMKL_ORIGINS.filter { it.started }.toTypedArray()
        if (plan.reads(TrackingProviderId.SIMKL)) unread.part("simkl", *simklOrigins) {
            simklSyncRepository.ensureLoaded()
            simklSyncRepository.state.value.snapshot.entries.forEach { entry ->
                val media = entry.media ?: return@forEach
                val origin = calendarSimklOrigin(entry.status, simklAsLibrary) ?: return@forEach
                val ids = CalendarIds(
                    imdb = media.ids.idValue("imdb")?.takeIf { it.startsWith("tt") },
                    tmdb = media.ids.idValue("tmdb")?.toLongOrNull(),
                    tvdb = media.ids.idValue("tvdb")?.toLongOrNull(),
                    simkl = media.ids.simklIdValue()?.toLongOrNull()
                )
                val type = if (entry.isMovieEntry()) CalendarTitleType.MOVIE else CalendarTitleType.SERIES
                val activity = (entry.lastWatchedAt ?: entry.addedToWatchlistAt)?.toInstantOrNull()
                candidates += CalendarTitleCandidate(ids, type, origin, activity, media.title, ids.imdb, entry.localPosterUrl)
            }
            watched.addAll(simklSyncRepository.projection.value.watchedShowEpisodes)
        }
        val mdbListProgress = TrackingProviderId.MDBLIST in plan.progressFrom
        val mdbListLibrary = TrackingProviderId.MDBLIST in plan.libraryFrom
        val mdbListOrigins = MDBLIST_ORIGINS.filter { if (it.started) mdbListProgress else mdbListLibrary }.toTypedArray()
        if (plan.reads(TrackingProviderId.MDBLIST)) unread.part("mdblist", *mdbListOrigins) {
            val snapshot = mdbListSyncRepository.currentSnapshot()
                ?: return@part unread.notLoaded("mdblist", *mdbListOrigins)
            fun add(ids: MdbListIds, type: MdbListItemType, origin: CalendarOrigin, at: Instant?, name: String?, poster: String? = null) {
                val calendarIds = ids.toCalendarIds()
                candidates += CalendarTitleCandidate(
                    calendarIds,
                    if (type == MdbListItemType.MOVIE) CalendarTitleType.MOVIE else CalendarTitleType.SERIES,
                    origin, at, name, calendarIds.imdb ?: calendarIds.tmdb?.let { "tmdb:$it" }, poster
                )
            }
            if (mdbListProgress) snapshot.watched.filter { it.type != MdbListItemType.MOVIE }
                .groupBy { it.media.ids.toCalendarIds().primaryAlias() }
                .forEach { (alias, records) ->
                    if (alias == null) return@forEach
                    val latest = records.maxBy { it.watchedAt }
                    add(latest.media.ids, MdbListItemType.SHOW, CalendarOrigin.MDBLIST_WATCHED, latest.watchedAt.toInstantOrNull(), latest.media.title)
                }
            if (mdbListProgress) snapshot.playback.filter { it.type != MdbListItemType.MOVIE }.forEach {
                add(it.media.ids, MdbListItemType.SHOW, CalendarOrigin.MDBLIST_PLAYBACK, it.updatedAt.toInstantOrNull(), it.media.title)
            }
            snapshot.dropped.forEach { add(it.ids, MdbListItemType.SHOW, CalendarOrigin.DROPPED, null, null) }
            if (mdbListLibrary) snapshot.library?.visible()?.itemsByList?.forEach { (listKey, items) ->
                if (!calendarFollowsMdbListList(listKey)) return@forEach
                val origin = if (listKey == MDBLIST_WATCHLIST_KEY) CalendarOrigin.MDBLIST_WATCHLIST else CalendarOrigin.MDBLIST_LIST
                items.filter { it.type == MdbListItemType.MOVIE || it.type == MdbListItemType.SHOW }.forEach { item ->
                    add(item.media.ids, item.type, origin, item.listedAt.toInstantOrNull(), item.media.title, item.media.poster)
                }
            }
            watched.addAll(mdbListSyncRepository.currentProjection().watchedShowEpisodes)
        }

        val removed = unread.runPart("hidden") { traktSettingsDataStore.dismissedNextUpKeys.first() }.orEmpty()
        val nextUp = unread.runPart("next up", counts = false) { enrichmentCache.getNextUpSnapshot(profileId) }.orEmpty()
        val addon = unread.runPart("next up dates", counts = false) {
            nextUp.mapNotNull { item ->
                if (item.hasAired) return@mapNotNull null
                val date = item.released?.let(::plainReleaseDate) ?: return@mapNotNull null
                CalendarIds.fromContentId(item.contentId) to CalendarEntry(
                    season = item.season,
                    episode = item.episode,
                    title = item.episodeTitle,
                    airDate = date,
                    label = deriveEpisodeLabel(item.season, item.episode),
                    source = CalendarSource.ADDON
                )
            }
        }.orEmpty()
        val usable = candidates.filterNot { it.ids.isEmpty }
        val posters = unread.runPart("posters", counts = false) {
            calendarCachedPosters(
                usable,
                metaPoster = { type, id -> metaRepository.getCachedMeta(type, id)?.poster },
                nextUpPosters = nextUp.map { it.contentId to it.poster }
            )
        }.orEmpty()
        val art = unread.runPart("backdrops", counts = false) {
            val inProgress = try {
                enrichmentCache.getInProgressSnapshot(profileId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
            calendarCachedArt(
                usable,
                metaArt = { type, id -> metaRepository.getCachedMeta(type, id)?.let { CalendarArt(it.background, it.logo) } },
                continueWatchingArt = nextUp.map { it.contentId to CalendarArt(it.backdrop, it.logo) } +
                    inProgress.map { it.contentId to CalendarArt(it.backdrop, it.logo) }
            )
        }.orEmpty()
        return CalendarFollowedSnapshot(
            usable, removed, addon, watched.build(), traktWatchedLoaded, traktProgress, posters,
            unread.reasons, unread.origins, cachedArt = art
        )
    }

    private companion object {
        val TRAKT_PROGRESS_ORIGINS = arrayOf(CalendarOrigin.TRAKT_WATCHED, CalendarOrigin.TRAKT_CALENDAR)
        val TRAKT_LIBRARY_ORIGINS = arrayOf(CalendarOrigin.TRAKT_WATCHLIST, CalendarOrigin.TRAKT_LIST)
        val SIMKL_ORIGINS = arrayOf(
            CalendarOrigin.SIMKL_WATCHING, CalendarOrigin.SIMKL_COMPLETED, CalendarOrigin.SIMKL_ON_HOLD,
            CalendarOrigin.SIMKL_PLAN_TO_WATCH
        )
        val MDBLIST_ORIGINS = arrayOf(
            CalendarOrigin.MDBLIST_WATCHED, CalendarOrigin.MDBLIST_PLAYBACK, CalendarOrigin.MDBLIST_WATCHLIST,
            CalendarOrigin.MDBLIST_LIST
        )
    }
}

/**
 * Runs each part of a read with a time limit and notes what could not be read: [reasons] by part, and the origins those
 * parts would have given. A part with `counts = false` is only logged; reading it again would keep nothing.
 */
internal class CalendarSourceParts(private val timeoutMs: Long = 5_000L) {
    val reasons = linkedMapOf<String, String>()
    val origins = mutableSetOf<CalendarOrigin>()

    fun notLoaded(name: String, vararg from: CalendarOrigin) {
        reasons.putIfAbsent(name, "not loaded yet")
        origins += from
    }

    suspend fun part(name: String, vararg from: CalendarOrigin, block: suspend () -> Unit) {
        if (runPart(name, block = block) == null) origins += from
    }

    suspend fun <T> runPart(name: String, counts: Boolean = true, block: suspend () -> T): T? {
        var finished = false
        return try {
            withTimeoutOrNull(timeoutMs) { block().also { finished = true } }.also {
                if (!finished) {
                    if (counts) reasons[name] = "timed out" else Log.w(TAG, "Skipped $name: timed out")
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Skipped $name: ${error.message}")
            if (counts) reasons[name] = "failed"
            null
        }
    }

    private companion object {
        const val TAG = "CalendarSources"
    }
}

fun calendarTitleType(contentType: String?): CalendarTitleType? = when (contentType?.trim()?.lowercase()) {
    "movie", "film" -> CalendarTitleType.MOVIE
    "series", "tv", "show", "anime" -> CalendarTitleType.SERIES
    else -> null
}

private fun MdbListIds.toCalendarIds() =
    CalendarIds(imdb = imdb?.takeIf { it.startsWith("tt") }, tmdb = tmdb, tvdb = tvdb, trakt = trakt)

private fun Long.toInstantOrNull(): Instant? = takeIf { it > 0L }?.let(Instant::ofEpochMilli)

private fun String.toInstantOrNull(): Instant? =
    runCatching { Instant.parse(this) }.getOrNull() ?: runCatching { OffsetDateTime.parse(this).toInstant() }.getOrNull()

private val PLAIN_DATE = Regex("^\\d{4}-\\d{2}-\\d{2}")

private val LEADING_YEAR = Regex("^(\\d{4})")

/** The year an add-on's `releaseInfo` starts with ("2019", "2019-2023"); null when it has none. */
fun calendarReleaseYear(releaseInfo: String?): Int? =
    releaseInfo?.trim()?.let { LEADING_YEAR.find(it) }?.groupValues?.get(1)?.toIntOrNull()

/** The date part of an add-on `released` value, as given; the time in it is not a real air time. */
fun plainReleaseDate(value: String): String? = PLAIN_DATE.find(value.trim())?.value
