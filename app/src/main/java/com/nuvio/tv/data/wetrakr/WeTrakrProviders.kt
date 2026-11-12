package com.nuvio.tv.data.wetrakr

import android.util.Log
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TRACKING_SCROBBLE_DIAGNOSTIC_TAG
import com.nuvio.tv.core.tracking.TrackingCapability
import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.core.tracking.TrackingHistoryItem
import com.nuvio.tv.core.tracking.TrackingHistoryWriter
import com.nuvio.tv.core.tracking.TrackingLibraryProvider
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingMembershipRemovalConfirmation
import com.nuvio.tv.core.tracking.TrackingMembershipRemovalImpact
import com.nuvio.tv.core.tracking.TrackingMutationResult
import com.nuvio.tv.core.tracking.TrackingProgressProvider
import com.nuvio.tv.core.tracking.TrackingProvider
import com.nuvio.tv.core.tracking.TrackingProviderDescriptor
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.core.tracking.TrackingScrobbleAction
import com.nuvio.tv.core.tracking.TrackingScrobbleEvent
import com.nuvio.tv.core.tracking.TrackingScrobbler
import com.nuvio.tv.core.tracking.parseTrackingExternalIds
import com.nuvio.tv.core.tracking.scrobbleDiagnosticSummary
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.LibraryListPrivacy
import com.nuvio.tv.domain.model.LibraryListTab
import com.nuvio.tv.domain.model.ListMembershipChanges
import com.nuvio.tv.domain.model.ListMembershipSnapshot
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

@Singleton
class WeTrakrTrackingProvider @Inject constructor(
    auth: WeTrakrAuthStore,
    profiles: ProfileManager,
    override val scrobbler: WeTrakrTrackingScrobbler
) : TrackingProvider {
    override val descriptor = TrackingProviderDescriptor(
        TrackingProviderId.WETRAKR,
        "WeTrakr",
        setOf(
            TrackingCapability.AUTHENTICATION, TrackingCapability.LIBRARY_READ, TrackingCapability.LIBRARY_WRITE,
            TrackingCapability.WATCHED_READ, TrackingCapability.WATCHED_WRITE, TrackingCapability.PROGRESS_READ,
            TrackingCapability.PROGRESS_WRITE, TrackingCapability.SCROBBLE, TrackingCapability.RECOMMENDATIONS
        )
    )
    override val isAuthenticated = combine(auth.state, profiles.activeProfileId) { authorization, profileId ->
        authorization.isAuthenticated && authorization.scope.profileId == profileId
    }.stateIn(
        CoroutineScope(SupervisorJob() + Dispatchers.IO), SharingStarted.Eagerly,
        auth.state.value.isAuthenticated && auth.scope().profileId == profiles.activeProfileId.value
    )
}

@Singleton
class WeTrakrTrackingScrobbler @Inject constructor(
    private val auth: WeTrakrAuthStore,
    private val sync: WeTrakrSyncRepository,
    private val service: WeTrakrScrobbleService
) : TrackingScrobbler {
    override val providerId = TrackingProviderId.WETRAKR

    override suspend fun scrobble(action: TrackingScrobbleAction, event: TrackingScrobbleEvent) {
        if (!auth.state.value.isAuthenticated) return
        try {
            sync.ensureLoaded()
            service.scrobble(sync.currentScope(), action, event)
        } catch (error: WeTrakrAccountChangedException) {
            Log.d(TRACKING_SCROBBLE_DIAGNOSTIC_TAG, "wetrakr adapter skipped action=${action.wireValue} reason=account_changed")
        } catch (error: Throwable) {
            Log.w(TRACKING_SCROBBLE_DIAGNOSTIC_TAG, "wetrakr adapter failed action=${action.wireValue} error=${error.javaClass.simpleName} ${event.scrobbleDiagnosticSummary()}")
            throw error
        }
    }
}

@Singleton
class WeTrakrTrackingHistoryWriter @Inject constructor(
    private val sync: WeTrakrSyncRepository,
    private val service: WeTrakrHistoryService
) : TrackingHistoryWriter {
    override val providerId = TrackingProviderId.WETRAKR

    override suspend fun addToHistory(profileId: Int, items: Collection<TrackingHistoryItem>): TrackingMutationResult {
        val scope = sync.currentScope()
        if (scope.profileId != profileId) return TrackingMutationResult(0)
        val pushable = items.filterNot(TrackingHistoryItem::isWholeSeriesMark)
        if (pushable.isEmpty()) return TrackingMutationResult(0)
        return service.add(scope, pushable)
    }

    override suspend fun removeFromHistory(profileId: Int, items: Collection<TrackingMediaReference>): TrackingMutationResult {
        val scope = sync.currentScope()
        if (scope.profileId != profileId) return TrackingMutationResult(0)
        return service.remove(scope, items.filterNot { it.episode == null && it.kind != TrackingMediaKind.MOVIE })
    }
}

@Singleton
class WeTrakrTrackingProgressProvider @Inject constructor(
    private val sync: WeTrakrSyncRepository,
    private val scrobbles: WeTrakrScrobbleService,
    private val settings: WeTrakrSettingsDataStore,
    auth: WeTrakrAuthStore,
    profiles: ProfileManager,
    layout: LayoutPreferenceDataStore
) : TrackingProgressProvider {
    override val providerId = TrackingProviderId.WETRAKR
    override val isAuthenticated = combine(auth.state, profiles.activeProfileId) { authorization, profileId ->
        authorization.isAuthenticated && authorization.scope.profileId == profileId
    }.distinctUntilChanged()
    private val projection = combine(sync.projectionState, auth.state, profiles.activeProfileId) { state, authorization, profileId ->
        if (authorization.isAuthenticated && authorization.scope.profileId == profileId && state.scope == authorization.scope) {
            state.projection
        } else {
            WeTrakrProgressProjection.Empty
        }
    }.distinctUntilChanged()
    override val allProgress = projection.map { it.progress }.loadOnStart()
    override val watchedItems: Flow<List<WatchedItem>> = projection.map { it.watchedItems }.loadOnStart()
    override val watchedMovieIds = projection.map { it.watchedMovieIds }.loadOnStart()
    override val nextUpSeeds = combine(projection, layout.nextUpFromFurthestEpisode) { projection, furthest ->
        projection.nextUp(furthest)
    }.loadOnStart()
    override val remoteProgressLoaded = combine(sync.state, auth.state, profiles.activeProfileId) { state, authorization, profileId ->
        state.hasLoaded && state.scope == authorization.scope && authorization.scope.profileId == profileId && authorization.isAuthenticated
    }.distinctUntilChanged()

    override fun episodeProgress(contentId: String): Flow<Map<Pair<Int, Int>, WatchProgress>> =
        projection.map { it.episodeProgress(contentId) }.loadOnStart()

    override fun airedEpisodeOrder(contentId: String): Flow<List<Pair<Int, Int>>> = flowOf(emptyList())

    override fun isWatched(contentId: String, videoId: String?, season: Int?, episode: Int?): Flow<Boolean> =
        projection.map { it.isWatched(contentId, season, episode) }.loadOnStart()

    override suspend fun watchedShowEpisodes(): Map<String, Set<Pair<Int, Int>>> {
        sync.refresh(TrackingRefreshIntent.AUTOMATIC)
        return sync.currentProjection().watchedShowEpisodes
    }

    override suspend fun showIdSiblings(): Map<String, Set<String>> {
        sync.ensureLoaded()
        return sync.currentProjection().showIdSiblings
    }

    override fun isWatchedByVideoId(videoId: String, episode: Int): Boolean? {
        val parts = videoId.split(':')
        if (parts.size < 3 || parts.last().toIntOrNull() != episode) return null
        val season = parts[parts.lastIndex - 1].toIntOrNull() ?: return null
        val parent = parts.dropLast(2).joinToString(":")
        if (retainsLocalProgress(parent)) return null
        return sync.currentProjection().isWatched(parent, season, episode)
    }

    override suspend fun refresh(intent: TrackingRefreshIntent) = sync.refresh(intent)

    override suspend fun removeProgress(contentId: String, season: Int?, episode: Int?) =
        scrobbles.clear(sync.currentScope(), contentId, season, episode)

    override fun applyOptimisticProgress(progress: WatchProgress, quiet: Boolean) = Unit
    override fun applyOptimisticRemoval(contentId: String, season: Int?, episode: Int?) = Unit
    override fun clearOptimistic() = Unit
    override fun isHiddenFromProgress(contentId: String): Boolean = sync.currentProjection().isHidden(contentId)
    override suspend fun prepareNextUpSeed(progress: WatchProgress): WatchProgress = progress

    override fun continueWatchingCutoffEpochMs(daysCap: Int, nowEpochMs: Long): Long? =
        settings.continueWatchingDays.value.takeIf { it > 0 }?.let { nowEpochMs - it * DAY_MS }

    override fun retainsLocalProgress(contentId: String): Boolean =
        parseTrackingExternalIds(contentId).toWeTrakrIds().isEmpty

    override fun retainsLocalWatchedEpisode(item: WatchedItem): Boolean = retainsLocalProgress(item.contentId)

    private fun <T> Flow<T>.loadOnStart(): Flow<T> = onStart {
        sync.ensureLoaded()
        sync.refreshAsync(TrackingRefreshIntent.AUTOMATIC)
    }.distinctUntilChanged()

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1_000
    }
}

internal data class WeTrakrTabDefinition(
    val key: String,
    val lists: Set<WeTrakrList>,
    val destination: WeTrakrList?,
    val supportedContentTypes: Set<String>,
    val watchlist: Boolean = false
)

internal const val WETRAKR_STATUS_GROUP = "wetrakr:status"
internal const val WETRAKR_CUSTOM_LIST_PREFIX = "wetrakr:list:"

internal val weTrakrTabDefinitions = listOf(
    WeTrakrTabDefinition("wetrakr:status:planning", setOf(WeTrakrList.PLANNING), WeTrakrList.PLANNING, setOf("movie", "series"), watchlist = true),
    WeTrakrTabDefinition("wetrakr:status:watching", setOf(WeTrakrList.WATCHING, WeTrakrList.WAITING), WeTrakrList.WATCHING, setOf("series")),
    WeTrakrTabDefinition("wetrakr:status:watched", setOf(WeTrakrList.WATCHED), null, setOf("movie", "series")),
    WeTrakrTabDefinition("wetrakr:status:hold", setOf(WeTrakrList.HOLD), WeTrakrList.HOLD, setOf("series")),
    WeTrakrTabDefinition("wetrakr:status:dropped", setOf(WeTrakrList.DROPPED), WeTrakrList.DROPPED, setOf("movie", "series"))
)

internal fun WeTrakrCustomList.tab() = LibraryListTab(
    key = "$WETRAKR_CUSTOM_LIST_PREFIX$id",
    title = name,
    type = LibraryListTab.Type.PERSONAL,
    description = description,
    privacy = if (isPrivate) LibraryListPrivacy.PRIVATE else LibraryListPrivacy.PUBLIC,
    trackingProviderId = TrackingProviderId.WETRAKR.storageId,
    supportedContentTypes = setOf("movie", "series"),
    isMembershipDestination = false
)

internal fun WeTrakrSnapshot.libraryEntries(): List<LibraryEntry> {
    val fallback = checkedAtEpochMs ?: 0L
    val tabs = weTrakrTabDefinitions.map { tab -> tab.key to tab.lists.flatMap { list(it) } } +
        customLists.map { list -> list.tab().key to list.items }
    return tabs.flatMap { (tabKey, entries) ->
        entries.mapIndexed { index, entry ->
            val title = entry.title
            LibraryEntry(
                id = title.ids.contentId,
                type = if (title.type == WeTrakrType.MOVIE) "movie" else "series",
                name = title.title ?: title.ids.contentId,
                poster = title.poster,
                posterShape = PosterShape.POSTER,
                background = title.backdrop,
                logo = null,
                description = null,
                releaseInfo = title.year?.toString(),
                imdbRating = null,
                genres = emptyList(),
                addonBaseUrl = null,
                listKeys = setOf(tabKey),
                listRanks = if (tabKey.startsWith(WETRAKR_CUSTOM_LIST_PREFIX)) mapOf(tabKey to index) else emptyMap(),
                listedAt = weTrakrTimestamp(entry.listedAt).takeIf { it > 0L } ?: (fallback - index),
                imdbId = title.ids.imdb,
                tmdbId = title.ids.tmdb?.toInt(),
                rawPosterUrl = title.poster,
                trackingProviderId = TrackingProviderId.WETRAKR.storageId,
                trackingProviderItemId = title.ids.wetrakr?.let { "wetrakr:$it" },
                trackingSourceUrl = title.pageUrl()
            )
        }
    }.groupBy { "${it.type}:${it.id}" }
        .map { (_, entries) -> entries.reduce { a, b -> a.copy(listKeys = a.listKeys + b.listKeys, listRanks = a.listRanks + b.listRanks, listedAt = maxOf(a.listedAt, b.listedAt)) } }
        .sortedByDescending(LibraryEntry::listedAt)
}

/**
 * A transfer only ever writes to Plan to watch, so a title that's only on a custom list isn't there yet:
 * counting it as already present would skip the copy, and a Move would then remove it from the source.
 */
internal fun List<LibraryEntry>.withoutWeTrakrCustomListOnly(): List<LibraryEntry> =
    filter { entry -> entry.listKeys.any { !it.startsWith(WETRAKR_CUSTOM_LIST_PREFIX) } }

internal fun WeTrakrSnapshot.listsOf(contentId: String, contentType: String): Set<WeTrakrList> {
    val type = if (contentType.lowercase() in setOf("movie", "film")) WeTrakrType.MOVIE else WeTrakrType.SHOW
    return lists.filterValues { entries ->
        entries.any { it.title.type == type && contentId in it.title.ids.aliases() }
    }.keys
}

@Singleton
class WeTrakrTrackingLibraryProvider @Inject constructor(
    private val sync: WeTrakrSyncRepository,
    private val api: WeTrakrApiClient,
    tracking: WeTrakrTrackingProvider
) : TrackingLibraryProvider {
    override val providerId = TrackingProviderId.WETRAKR
    override val isAuthenticated = tracking.isAuthenticated
    override val isRefreshing = sync.state.map { it.isLoading }.distinctUntilChanged()
    override val items = sync.state.map { state -> state.snapshot?.libraryEntries().orEmpty() }
        .onStart { sync.refreshAsync(TrackingRefreshIntent.AUTOMATIC) }
        .distinctUntilChanged()
    override val tabs = combine(
        isAuthenticated,
        sync.state.map { state -> state.snapshot?.customLists.orEmpty().map(WeTrakrCustomList::tab) }.distinctUntilChanged()
    ) { authenticated, customTabs ->
        if (!authenticated) emptyList() else weTrakrTabDefinitions.map { tab ->
            LibraryListTab(
                key = tab.key,
                title = tab.key.substringAfterLast(':'),
                type = if (tab.watchlist) LibraryListTab.Type.WATCHLIST else LibraryListTab.Type.STATUS,
                trackingProviderId = TrackingProviderId.WETRAKR.storageId,
                selectionGroup = WETRAKR_STATUS_GROUP,
                supportedContentTypes = tab.supportedContentTypes,
                isMembershipDestination = tab.destination != null
            )
        } + customTabs
    }.distinctUntilChanged()

    override fun recognizesListKey(key: String): Boolean =
        weTrakrTabDefinitions.any { it.key == key } || key.startsWith(WETRAKR_CUSTOM_LIST_PREFIX)

    override fun countsAsInLibrary(listKey: String): Boolean = listKey != "wetrakr:status:watched"

    override fun observeMembership(itemId: String, itemType: String): Flow<Set<String>> = sync.state.map { state ->
        val lists = state.snapshot?.listsOf(itemId, itemType).orEmpty()
        weTrakrTabDefinitions.filter { tab -> tab.lists.any(lists::contains) }.mapTo(linkedSetOf()) { it.key }
    }.onStart { sync.refreshAsync(TrackingRefreshIntent.AUTOMATIC) }.distinctUntilChanged()

    override fun toggledDefaultMembership(currentMembership: Map<String, Boolean>): Map<String, Boolean> =
        currentMembership.mapValues { false }.toMutableMap().apply {
            if (currentMembership.values.none { it }) this["wetrakr:status:planning"] = true
        }

    override suspend fun getMembershipSnapshot(item: LibraryEntryInput): ListMembershipSnapshot {
        sync.ensureLoaded()
        val lists = sync.currentSnapshot()?.listsOf(item.itemId, item.itemType).orEmpty()
        return ListMembershipSnapshot(weTrakrTabDefinitions.associate { tab -> tab.key to tab.lists.any(lists::contains) })
    }

    override suspend fun membershipRemovalConfirmation(
        item: LibraryEntryInput,
        changes: ListMembershipChanges
    ): TrackingMembershipRemovalConfirmation? = null

    override suspend fun applyMembershipChanges(
        item: LibraryEntryInput,
        changes: ListMembershipChanges,
        destructiveRemovalConfirmed: Boolean
    ) {
        sync.ensureLoaded()
        val scope = sync.currentScope()
        val snapshot = sync.currentSnapshot() ?: return
        val current = snapshot.listsOf(item.itemId, item.itemType)
        val desired = weTrakrTabDefinitions.filter { changes.desiredMembership[it.key] == true }.mapNotNull { it.destination }
        require(desired.size <= 1) { "A WeTrakr title sits in one list at a time" }
        val destination = desired.singleOrNull()
        val removable = current - WeTrakrList.WATCHED
        if (destination != null && weTrakrTabDefinitions.any { it.destination == destination && it.lists.any(current::contains) }) return
        if (destination == null && removable.isEmpty()) return
        val target = item.toWeTrakrTarget() ?: error("WeTrakr cannot match this title")
        val removed = if (destination != null) {
            val response = api.post("/sync/tracking", trackingBody(listOf(target), destination.wireValue).toString(), scope)
            check(notFoundCount(response.body) == 0) { "WeTrakr could not match this title" }
            WeTrakrList.entries.toSet()
        } else {
            removable.filterTo(linkedSetOf()) { list ->
                val response = api.post("/sync/tracking/remove", trackingBody(listOf(target), list.wireValue).toString(), scope)
                notFoundCount(response.body) == 0
            }
        }
        if (removed.isNotEmpty()) sync.mutate(scope) { next ->
            val type = target.type
            val matches = { entry: WeTrakrListEntry -> entry.title.type == type && item.itemId in entry.title.ids.aliases() }
            val kept = next.lists.mapValues { (list, entries) -> if (list in removed) entries.filterNot(matches) else entries }
            val updated = if (destination != null) {
                val title = next.lists.values.flatten().firstOrNull(matches)?.title
                    ?: WeTrakrTitle(type, target.ids, item.title, item.year, item.poster)
                kept + (destination to listOf(WeTrakrListEntry(title, java.time.Instant.now().toString())) + kept[destination].orEmpty())
            } else {
                kept
            }
            next.copy(lists = updated) to Unit
        }
        check(destination != null || removed.size == removable.size) { "WeTrakr could not remove this title" }
    }

    override suspend fun refresh(intent: TrackingRefreshIntent) = sync.refresh(intent)
}

internal fun LibraryEntryInput.toWeTrakrTarget(): WeTrakrTarget? {
    val ids = parseTrackingExternalIds(itemId).mergeMissing(
        TrackingExternalIds(imdb = imdbId, tmdb = tmdbId?.toLong(), simkl = simklId, trakt = traktId?.toLong())
    )
    val requestIds = ids.toWeTrakrRequestIds() ?: return null
    val movie = itemType.lowercase() in setOf("movie", "film")
    return WeTrakrTarget(
        type = if (movie) WeTrakrType.MOVIE else WeTrakrType.SHOW,
        requestIds = requestIds,
        ids = ids.toWeTrakrIds(),
        title = title,
        year = year
    )
}
