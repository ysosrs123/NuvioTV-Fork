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
import com.nuvio.tv.data.mdblist.MDBLIST_DYNAMIC_LIST_KEY_PREFIX
import com.nuvio.tv.data.mdblist.MDBLIST_EXTERNAL_LIST_KEY_PREFIX
import com.nuvio.tv.data.mdblist.MDBLIST_LIKED_LIST_KEY_PREFIX
import com.nuvio.tv.data.mdblist.MDBLIST_WATCHLIST_KEY
import com.nuvio.tv.data.mdblist.MdbListIds
import com.nuvio.tv.data.mdblist.MdbListItemType
import com.nuvio.tv.data.mdblist.MdbListSyncRepository
import com.nuvio.tv.data.repository.TraktLibraryService
import com.nuvio.tv.data.repository.TraktProgressService
import com.nuvio.tv.domain.model.LibrarySourceMode
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.data.simkl.SimklListStatus
import com.nuvio.tv.data.simkl.SimklSyncRepository
import com.nuvio.tv.data.simkl.idValue
import com.nuvio.tv.data.simkl.simklIdValue
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
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
    val cachedArt: Map<String, CalendarArt> = emptyMap(),
    /** Ids found this time for content ids the Calendar cannot read, so they are known after a restart too. */
    val resolvedIds: Map<String, CalendarIds> = emptyMap(),
    /** Content ids the Calendar cannot read and found no ids for; their titles are left out this time. */
    val unresolved: List<Pair<CalendarTitleType, String>> = emptyList(),
    /** Names of the [unresolved] titles, by content id, for the log. */
    val unresolvedNames: Map<String, String> = emptyMap()
) {
    val complete: Boolean get() = notReady.isEmpty()
}

/** The episode Next Up points at and the day the add-on has it coming out. */
data class CalendarNextUpMark(val date: LocalDate, val season: Int? = null, val episode: Int? = null, val name: String? = null) {
    /** The same season and episode, unless the names differ (TVmaze can give that number to a short). */
    fun isEpisode(season: Int?, episode: Int?, name: String?): Boolean {
        if (season == null || episode == null || this.season != season || this.episode != episode) return false
        val mine = this.name?.let(::episodeNameKey)?.takeIf { it.length >= 4 } ?: return true
        val theirs = name?.let(::episodeNameKey)?.takeIf { it.length >= 4 } ?: return true
        return mine == theirs || mine.endsWith(theirs) || theirs.endsWith(mine)
    }
}

private fun laterNextUp(a: CalendarNextUpMark, b: CalendarNextUpMark) = if (b.date > a.date) b else a

/** An episode as Nuvio's own episode list numbers it. */
data class CalendarKnownEpisode(val season: Int, val episode: Int, val name: String?, val date: LocalDate)

/**
 * What has been watched, by season and episode. Sources such as TVmaze can number a season differently from Nuvio's
 * episode list (a short or special counted as an episode), so an episode is first matched to Nuvio's list by air date
 * and name. A title whose Next Up has not aired yet has nothing older left to watch, but the Next Up episode itself
 * stays unwatched when the Calendar has it out earlier than the add-on does.
 */
class CalendarWatchedIndex(
    private val keys: Set<String> = emptySet(),
    private val episodes: Map<String, List<CalendarKnownEpisode>> = emptyMap(),
    private val nextUpNotAired: Map<String, CalendarNextUpMark> = emptyMap()
) {
    fun isWatched(ids: CalendarIds, season: Int?, episode: Int?): Boolean {
        if (season == null || episode == null) return false
        return ids.aliases().any { "$it|$season|$episode" in keys }
    }

    fun isWatched(title: CalendarTitleRecord, entry: CalendarEntry): Boolean {
        if (entry.kind != CalendarEntryKind.EPISODE) return false
        val aliases = title.ids.aliases()
        val date = entry.airDateOrNull()
        val known = date?.let { matchByDateAndName(aliases, entry.shownTitle, it) }
        val season = known?.season ?: entry.season
        val episode = known?.episode ?: entry.episode
        if (date != null && aliases.any { alias ->
                nextUpNotAired[alias]?.let { date < it.date && !it.isEpisode(season, episode, entry.shownTitle) } == true
            }
        ) return true
        return isWatched(title.ids, season, episode)
    }

    private fun matchByDateAndName(aliases: Set<String>, name: String?, date: LocalDate): CalendarKnownEpisode? {
        val wanted = name?.let(::episodeNameKey)?.takeIf { it.length >= MIN_NAME } ?: return null
        val list = aliases.firstNotNullOfOrNull { episodes[it] } ?: return null
        return list.firstOrNull { known ->
            val have = known.name?.let(::episodeNameKey)?.takeIf { it.length >= MIN_NAME } ?: return@firstOrNull false
            Math.abs(java.time.temporal.ChronoUnit.DAYS.between(known.date, date)) <= 1 &&
                (have == wanted || wanted.endsWith(have) || have.endsWith(wanted))
        }
    }

    class Builder {
        private val keys = HashSet<String>()
        private val episodes = HashMap<String, List<CalendarKnownEpisode>>()
        private val nextUpNotAired = HashMap<String, CalendarNextUpMark>()
        private val unreadable = HashMap<String, MutableSet<Pair<Int, Int>>>()
        private val unreadableNextUp = HashMap<String, CalendarNextUpMark>()

        fun add(contentId: String, season: Int?, episode: Int?) = apply {
            if (season == null || episode == null) return@apply
            val aliases = CalendarIds.fromContentId(contentId).aliases()
            if (aliases.isEmpty()) unreadable.getOrPut(contentId, ::HashSet) += season to episode
            aliases.forEach { keys += "$it|$season|$episode" }
        }

        /** What was recorded under an id the Calendar cannot read (such as kitsu:) counts for [ids] too. */
        fun alias(contentId: String, ids: CalendarIds) = apply {
            unreadable[contentId]?.forEach { (s, e) -> ids.aliases().forEach { keys += "$it|$s|$e" } }
            unreadableNextUp[contentId]?.let { mark -> ids.aliases().forEach { nextUpNotAired.merge(it, mark, ::laterNextUp) } }
        }

        fun addAll(byContent: Map<String, Set<Pair<Int, Int>>>) = apply {
            byContent.forEach { (contentId, episodes) -> episodes.forEach { (s, e) -> add(contentId, s, e) } }
        }

        /** Nuvio's episode list for a show, from its cached details. */
        fun episodes(ids: CalendarIds, list: List<CalendarKnownEpisode>) = apply {
            if (list.isEmpty()) return@apply
            ids.aliases().forEach { episodes.putIfAbsent(it, list) }
        }

        /** Next Up points at an episode that comes out on [date]: everything out before it is watched. */
        fun nextUpNotAired(contentId: String, date: LocalDate, season: Int? = null, episode: Int? = null, name: String? = null) = apply {
            val mark = CalendarNextUpMark(date, season, episode, name)
            val aliases = CalendarIds.fromContentId(contentId).aliases()
            if (aliases.isEmpty()) unreadableNextUp.merge(contentId, mark, ::laterNextUp)
            aliases.forEach { alias ->
                nextUpNotAired.merge(alias, mark, ::laterNextUp)
            }
        }

        fun build() = CalendarWatchedIndex(keys, episodes, nextUpNotAired)
    }

    private companion object {
        const val MIN_NAME = 4
    }
}

/** Lower case letters and digits only, so "Beavis and Butt-Head in Pleasure Washer" ends with "pleasurewasher". */
internal fun episodeNameKey(name: String): String = name.lowercase().filter { it.isLetterOrDigit() }

private fun CalendarEntry.airDateOrNull(): LocalDate? =
    airDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        ?: airInstantMs?.let { Instant.ofEpochMilli(it).atZone(java.time.ZoneOffset.UTC).toLocalDate() }

/**
 * A title saved under an id the Calendar cannot read (an anime add-on's kitsu:, for example) takes the IMDb or TMDB id
 * from the add-on's cached details, or else the ids found for it before ([known]), instead of being left out.
 */
fun calendarResolveUnreadableIds(
    candidates: List<CalendarTitleCandidate>,
    known: Map<String, CalendarIds> = emptyMap(),
    cachedMeta: (type: String, id: String) -> com.nuvio.tv.domain.model.Meta?
): List<CalendarTitleCandidate> = candidates.map { candidate ->
    if (!candidate.ids.isEmpty) return@map candidate
    val contentId = candidate.contentId?.takeIf(String::isNotBlank) ?: return@map candidate
    val type = if (candidate.type == CalendarTitleType.MOVIE) "movie" else "series"
    val ids = cachedMeta(type, contentId)?.let(::calendarIdsOf)?.takeIf { !it.isEmpty }
        ?: known[contentId]?.takeIf { !it.isEmpty }
        ?: return@map candidate
    candidate.copy(ids = ids)
}

/** The ids the Calendar can read from a title's details: its IMDb id, its own id, and its IMDb or TMDB page link. */
fun calendarIdsOf(meta: com.nuvio.tv.domain.model.Meta): CalendarIds =
    CalendarIds.fromContentId(meta.imdbId?.takeIf { it.startsWith("tt") })
        .merged(CalendarIds.fromContentId(meta.id))
        .merged(calendarIdsFromLinks(meta.links.map { it.url }, movie = meta.type == com.nuvio.tv.domain.model.ContentType.MOVIE))

private val IMDB_TITLE_LINK = Regex("""imdb\.com/title/(tt\d+)""", RegexOption.IGNORE_CASE)
private val TMDB_TITLE_LINK = Regex("""themoviedb\.org/(tv|movie)/(\d+)""", RegexOption.IGNORE_CASE)

/** An IMDb id from an IMDb title page link, and a TMDB id from a TMDB page link of the same kind of title. */
fun calendarIdsFromLinks(urls: List<String>, movie: Boolean): CalendarIds {
    val tmdbKind = if (movie) "movie" else "tv"
    return CalendarIds(
        imdb = urls.firstNotNullOfOrNull { IMDB_TITLE_LINK.find(it)?.groupValues?.get(1) },
        tmdb = urls.firstNotNullOfOrNull { url ->
            TMDB_TITLE_LINK.find(url)?.takeIf { it.groupValues[1].equals(tmdbKind, ignoreCase = true) }
                ?.groupValues?.get(2)?.toLongOrNull()
        }
    )
}

private val CALENDAR_SIMKL_SOURCES = setOf("kitsu", "mal", "anilist", "anidb")

/** The source and id Simkl can map for an anime id such as kitsu:1376 or mal:21; null for any other id. */
fun calendarSimklLookup(contentId: String): Pair<String, String>? {
    val prefix = contentId.substringBefore(':', "").trim().lowercase()
    if (prefix !in CALENDAR_SIMKL_SOURCES) return null
    val id = contentId.substringAfter(':').substringBefore(':').trim()
    return if (id.isNotEmpty() && id.all(Char::isDigit)) prefix to id else null
}

/** Simkl's ids for a title, empty unless one of them can be used to fetch dates (IMDb, TMDB or TheTVDB). */
fun calendarIdsFromSimkl(imdb: String?, tmdb: String?, tvdb: String?, simkl: Long?): CalendarIds {
    val ids = CalendarIds(
        imdb = imdb?.trim()?.takeIf { it.startsWith("tt") },
        tmdb = tmdb?.trim()?.toLongOrNull(),
        tvdb = tvdb?.trim()?.toLongOrNull()
    )
    return if (ids.isEmpty) ids else ids.copy(simkl = simkl)
}

/**
 * What a lookup found: the add-ons' ids when they have usable ones, else Simkl's. Empty when a source answered without
 * ids, null when neither could be read.
 */
fun calendarLookedUpIds(fromAddons: CalendarIds?, fromSimkl: CalendarIds?): CalendarIds? =
    fromAddons?.takeIf { !it.isEmpty } ?: fromSimkl?.takeIf { !it.isEmpty } ?: fromAddons ?: fromSimkl

/** Nuvio's own numbering of a show's episodes, from an add-on's episode list. */
fun calendarKnownEpisodes(videos: List<com.nuvio.tv.domain.model.Video>): List<CalendarKnownEpisode> =
    videos.mapNotNull { video ->
        val season = video.season ?: return@mapNotNull null
        val episode = video.episode ?: return@mapNotNull null
        val date = video.released?.let(::plainReleaseDate)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: return@mapNotNull null
        CalendarKnownEpisode(season, episode, video.title.takeIf(String::isNotBlank), date)
    }

/**
 * Which services the Calendar follows. By default the Nuvio library and the library source for saved titles and the
 * progress source for started ones; [followOtherServices] reads every signed-in service.
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
    return CalendarSourcePlan(nuvioLibrary = true, libraryFrom = setOfNotNull(library), progressFrom = setOfNotNull(progress))
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

/** Liked and external MDBList lists are other people's lists and are never followed. */
fun calendarFollowsMdbListList(listKey: String): Boolean =
    !listKey.startsWith(MDBLIST_LIKED_LIST_KEY_PREFIX) && !listKey.startsWith(MDBLIST_EXTERNAL_LIST_KEY_PREFIX)

/** A dynamic list follows a filter and can hold thousands of titles, so only its first ones are followed. */
fun calendarMdbListItemLimit(listKey: String): Int =
    if (listKey.startsWith(MDBLIST_DYNAMIC_LIST_KEY_PREFIX)) CALENDAR_DYNAMIC_LIST_LIMIT else Int.MAX_VALUE

internal const val CALENDAR_DYNAMIC_LIST_LIMIT = 150

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
    suspend fun read(
        profileId: Int,
        followOtherServices: Boolean = false,
        knownIds: Map<String, CalendarIds> = emptyMap()
    ): CalendarFollowedSnapshot {
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
            val otherTypes = mutableListOf<String>()
            libraryPreferences.libraryItems.first().forEach { item ->
                val type = calendarTitleType(item.type)
                if (type == null) {
                    otherTypes += item.type
                    return@forEach
                }
                candidates += CalendarTitleCandidate(
                    CalendarIds.fromContentId(item.id), type, CalendarOrigin.LIBRARY,
                    item.addedAt.toInstantOrNull(), item.name, item.id, item.poster
                )
            }
            if (otherTypes.isNotEmpty()) {
                Log.i(TAG, "library: skipped ${otherTypes.size} titles of type ${otherTypes.distinct().take(5).joinToString(", ")}")
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
                items.filter { it.type == MdbListItemType.MOVIE || it.type == MdbListItemType.SHOW }
                    .take(calendarMdbListItemLimit(listKey)).forEach { item ->
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
                val (date, exactMs) = item.released?.let(::calendarAddonRelease) ?: return@mapNotNull null
                CalendarIds.fromContentId(item.contentId) to CalendarEntry(
                    season = item.season,
                    episode = item.episode,
                    title = item.episodeTitle,
                    airDate = date,
                    placeAtMs = exactMs,
                    label = deriveEpisodeLabel(item.season, item.episode),
                    source = CalendarSource.ADDON
                )
            }
        }.orEmpty()
        nextUp.forEach { item ->
            if (item.hasAired) return@forEach
            val date = item.released?.let(::plainReleaseDate)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                ?: return@forEach
            watched.nextUpNotAired(item.contentId, date, item.season, item.episode, item.episodeTitle)
        }
        val resolved = unread.runPart("unreadable ids", counts = false) {
            calendarResolveUnreadableIds(candidates, knownIds) { type, id -> metaRepository.getCachedMeta(type, id) }
        } ?: candidates
        val resolvedIds = HashMap<String, CalendarIds>()
        val unresolved = mutableListOf<Pair<CalendarTitleType, String>>()
        val unresolvedNames = HashMap<String, String>()
        resolved.forEach { candidate ->
            val contentId = candidate.contentId?.takeIf(String::isNotBlank) ?: return@forEach
            if (!CalendarIds.fromContentId(contentId).isEmpty) return@forEach
            if (candidate.ids.isEmpty) {
                unresolved += candidate.type to contentId
                candidate.name?.takeIf(String::isNotBlank)?.let { unresolvedNames.putIfAbsent(contentId, it) }
            } else {
                watched.alias(contentId, candidate.ids)
                resolvedIds[contentId] = candidate.ids
            }
        }
        val usable = resolved.filterNot { it.ids.isEmpty }
        unread.runPart("episode lists", counts = false) {
            val shows = usable.filter { it.type == CalendarTitleType.SERIES } +
                nextUp.map { CalendarTitleCandidate(CalendarIds.fromContentId(it.contentId), CalendarTitleType.SERIES, CalendarOrigin.PROGRESS, contentId = it.contentId) }
            shows.forEach { show ->
                val videos = listOfNotNull(show.contentId, show.ids.imdb, show.ids.tmdb?.let { "tmdb:$it" })
                    .distinct()
                    .firstNotNullOfOrNull { id -> metaRepository.getCachedMeta("series", id)?.videos?.takeIf { it.isNotEmpty() } }
                    ?: return@forEach
                watched.episodes(show.ids, calendarKnownEpisodes(videos))
            }
        }
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
                metaArt = { type, id -> metaRepository.getCachedMeta(type, id)?.let(::calendarArtOf) },
                continueWatchingArt = nextUp.map { it.contentId to CalendarArt(it.backdrop, it.logo) } +
                    inProgress.map { it.contentId to CalendarArt(it.backdrop, it.logo) }
            )
        }.orEmpty()
        return CalendarFollowedSnapshot(
            usable, removed, addon, watched.build(), traktWatchedLoaded, traktProgress, posters,
            unread.reasons, unread.origins, cachedArt = art,
            resolvedIds = resolvedIds, unresolved = unresolved.distinct(), unresolvedNames = unresolvedNames
        )
    }

    private companion object {
        const val TAG = "CalendarSources"
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
    "series", "tv", "show", "tvshow", "anime" -> CalendarTitleType.SERIES
    else -> null
}

private fun MdbListIds.toCalendarIds() =
    CalendarIds(imdb = imdb?.takeIf { it.startsWith("tt") }, tmdb = tmdb, tvdb = tvdb, trakt = trakt)

private fun Long.toInstantOrNull(): Instant? = takeIf { it > 0L }?.let(Instant::ofEpochMilli)

private fun String.toInstantOrNull(): Instant? =
    runCatching { Instant.parse(this) }.getOrNull() ?: runCatching { OffsetDateTime.parse(this).toInstant() }.getOrNull()

private val PLAIN_DATE = Regex("^\\d{4}-\\d{2}-\\d{2}")

/** The date part of an add-on `released` value, as given. */
fun plainReleaseDate(value: String): String? = PLAIN_DATE.find(value.trim())?.value

/**
 * An add-on `released` value: its date as given, and the moment it airs when it carries a real time. Midnight (UTC or
 * the value's own offset) only marks a date, so it gives no moment.
 */
fun calendarAddonRelease(value: String): Pair<String, Long?>? {
    val date = plainReleaseDate(value)?.takeIf { runCatching { LocalDate.parse(it) }.isSuccess } ?: return null
    val exact = runCatching { OffsetDateTime.parse(value.trim()) }.getOrNull()?.takeIf { it.toLocalTime() != LocalTime.MIDNIGHT }
    return date to exact?.toInstant()?.toEpochMilli()
}
