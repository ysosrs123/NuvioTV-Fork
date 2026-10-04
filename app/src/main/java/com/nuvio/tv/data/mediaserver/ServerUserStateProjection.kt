package com.nuvio.tv.data.mediaserver

import android.os.SystemClock
import android.util.Log
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import com.nuvio.tv.domain.repository.WatchProgressRepository
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class ServerUserStateProjection internal constructor(
    private val catalog: ServerCatalog,
    private val repository: ServerRepository,
    private val watchProgressRepository: WatchProgressRepository,
    private val watchProgressPreferences: WatchProgressPreferences,
    private val watchedItemsPreferences: WatchedItemsPreferences,
    private val profileManager: ProfileManager,
    private val resumeImports: ServerResumeImports,
    private val clock: () -> Long
) {
    @Inject
    constructor(
        catalog: ServerCatalog,
        repository: ServerRepository,
        watchProgressRepository: WatchProgressRepository,
        watchProgressPreferences: WatchProgressPreferences,
        watchedItemsPreferences: WatchedItemsPreferences,
        profileManager: ProfileManager,
        resumeImports: ServerResumeImports
    ) : this(
        catalog = catalog,
        repository = repository,
        watchProgressRepository = watchProgressRepository,
        watchProgressPreferences = watchProgressPreferences,
        watchedItemsPreferences = watchedItemsPreferences,
        profileManager = profileManager,
        resumeImports = resumeImports,
        clock = SystemClock::elapsedRealtime
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val resumeMutex = Mutex()
    private val lastResumeImportAt = ConcurrentHashMap<String, Long>()
    private val externalIdCache = ConcurrentHashMap<String, TrackingExternalIds>()

    fun start() {
        scope.launch {
            catalog.detailsLoaded.collect { details ->
                guarded("apply server user state") { apply(details) }
            }
        }
        scope.launch {
            repository.uiState
                .map { state -> state.enabledConnections.filter { it.importContinueWatching }.map { it.id to it.address } }
                .distinctUntilChanged()
                .collect { connections ->
                    connections.forEach { (connectionId, _) ->
                        guarded("import server resume items") { importResume(connectionId, force = true) }
                    }
                }
        }
        scope.launch {
            catalog.libraryLoaded.collect { connectionId ->
                guarded("import server resume items") { importResume(connectionId) }
            }
        }
        scope.launch {
            watchProgressPreferences.remoteProgressMerged.collectLatest { profileId ->
                delay(ACCOUNT_MERGE_SETTLE_MS)
                if (profileId != repository.currentProfileId) return@collectLatest
                repository.enabledConnections().filter { it.importContinueWatching }.forEach { connection ->
                    guarded("import server resume items") { importResume(connection.id, force = true) }
                }
            }
        }
    }

    suspend fun apply(details: ServerItemDetails) {
        val meta = details.meta
        val ref = ServerItemRef.parse(meta.id) ?: return
        val connection = repository.connection(ref.connectionId) ?: return
        val isSeries = meta.type == ContentType.SERIES
        val profileId = profileManager.activeProfileId.value

        if (connection.importContinueWatching) {
            val progress = details.userStates
                .filter { it.isInProgress() }
                .map { it.toProgress(meta, isSeries, ServerCatalog.baseUrl(connection.id)) }
                .filter { isNewer(it, isSeries, profileId) }
            watchProgressRepository.saveProgressBatch(progress, profileId, syncRemote = false)
        }

        val states = details.userStates.filter { !isSeries || (it.season != null && it.episode != null) }
        val watchedEpisodes = if (isSeries) watchedItemsPreferences.getWatchedEpisodesForContent(meta.id, profileId).first() else emptySet()
        val movieWatched = !isSeries && watchedItemsPreferences.observeAllItems(profileId).first()
            .any { it.contentId == meta.id && it.season == null && it.episode == null }
        fun ServerUserState.isWatchedLocally(): Boolean =
            if (isSeries) (season!! to episode!!) in watchedEpisodes else movieWatched

        val (played, unplayed) = states.partition { it.played }
        watchedItemsPreferences.markAsWatchedBatch(
            played.filterNot { it.isWatchedLocally() }.map { it.toWatchedItem(meta, isSeries) },
            profileId
        )
        val unwatched = unplayed.filter { it.isWatchedLocally() }
        if (isSeries) {
            watchedItemsPreferences.unmarkAsWatchedBatch(meta.id, unwatched.map { it.season!! to it.episode!! }, profileId)
        } else if (unwatched.isNotEmpty()) {
            watchedItemsPreferences.unmarkAsWatched(meta.id, profileId = profileId)
        }
    }

    suspend fun importResume(connectionId: String, force: Boolean = false) = resumeMutex.withLock {
        val connection = repository.connection(connectionId)?.takeIf { it.enabled && it.importContinueWatching } ?: return@withLock
        val provider = repository.provider(connection) ?: return@withLock
        if (!provider.supports(ServerCapability.USER_STATE_READ)) return@withLock
        val now = clock()
        val last = lastResumeImportAt[connectionId]
        if (!force && last != null && now - last < RESUME_IMPORT_INTERVAL_MS) return@withLock
        lastResumeImportAt[connectionId] = now

        val profileId = repository.currentProfileId
        if (profileManager.activeProfileId.value != profileId) return@withLock
        val entries = repository.call(connectionId) { serverProvider, session -> serverProvider.resumeItems(session, RESUME_LIMIT) }
            .filter { it.state.isInProgress() && it.hasEpisodeNumbersIfSeries() }
        if (profileManager.activeProfileId.value != profileId || repository.currentProfileId != profileId) return@withLock

        val imported = resumeImports.read(profileId, connectionId)
        val addonBaseUrl = ServerCatalog.baseUrl(connectionId)
        val catalogUpdates = mutableListOf<WatchProgress>()
        val progress = mutableListOf<WatchProgress>()
        for (entry in entries) {
            val tracked = if (connection.useCatalogMetadata) catalogProgress(connection, entry, profileId) else null
            if (tracked != null) {
                entry.updated(tracked)?.let(catalogUpdates::add)
                continue
            }
            val own = entry.toProgress(addonBaseUrl)
            val existing = stored(own, own.season != null && own.episode != null, profileId)
            val alreadyImported = imported[entry.state.videoId] == entry.state.lastPlayedEpochMs
            if (existing == null && alreadyImported) continue
            if (existing == null || existing.lastWatched < own.lastWatched) progress += own
        }
        watchProgressRepository.saveProgressBatch(progress + catalogUpdates, profileId, syncRemote = false)
        resumeImports.write(
            profileId,
            connectionId,
            entries.associate { it.state.videoId to (it.state.lastPlayedEpochMs ?: 0L) }
        )
    }

    private suspend fun catalogProgress(connection: ServerConnection, entry: ServerResumeEntry, profileId: Int): WatchProgress? {
        val contentRef = ServerItemRef.parse(entry.title.preview.id) ?: return null
        val ids = entry.title.externalIds.takeIf { it.hasAny }
            ?: externalIdCache[entry.title.preview.id]
            ?: runCatching {
                repository.call(connection.id) { provider, session -> provider.externalIds(session, contentRef.itemId) }
            }.onFailure { if (it is CancellationException) throw it }
                .getOrNull()
                ?.also { externalIdCache[entry.title.preview.id] = it }
            ?: return null
        return ids.catalogIds().firstNotNullOfOrNull { id -> watchProgressPreferences.getProgress(id, profileId).first() }
    }

    /** The tracked title moved to the server's newer position, or null when Nuvio's own entry is newer. */
    private fun ServerResumeEntry.updated(tracked: WatchProgress): WatchProgress? {
        val lastPlayed = state.lastPlayedEpochMs ?: return null
        if (lastPlayed <= tracked.lastWatched) return null
        val season = state.season
        val episode = state.episode
        val sameVideo = season == null || (season == tracked.season && episode == tracked.episode)
        return tracked.copy(
            videoId = if (sameVideo) tracked.videoId else "${tracked.contentId}:$season:$episode",
            season = if (sameVideo) tracked.season else season,
            episode = if (sameVideo) tracked.episode else episode,
            episodeTitle = if (sameVideo) tracked.episodeTitle else state.title,
            position = state.positionMs,
            duration = state.durationMs,
            lastWatched = lastPlayed,
            progressPercent = null
        )
    }

    private suspend fun isNewer(progress: WatchProgress, isSeries: Boolean, profileId: Int): Boolean {
        val existing = stored(progress, isSeries, profileId)
        return existing == null || existing.lastWatched < progress.lastWatched
    }

    private suspend fun stored(progress: WatchProgress, isSeries: Boolean, profileId: Int): WatchProgress? =
        if (isSeries) {
            watchProgressPreferences.getEpisodeProgress(progress.contentId, progress.season ?: 0, progress.episode ?: 0, profileId).first()
        } else {
            watchProgressPreferences.getProgress(progress.contentId, profileId).first()
        }

    private suspend fun guarded(action: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (error: CancellationException) {
            currentCoroutineContext().ensureActive()
            Log.w(TAG, "Unable to $action: server settings changed")
        } catch (error: Exception) {
            Log.w(TAG, "Unable to $action: ${error.serverFailure()}")
        }
    }

    private fun ServerUserState.isInProgress(): Boolean =
        !played && positionMs > 0L && durationMs > 0L && lastPlayedEpochMs != null

    private fun ServerResumeEntry.hasEpisodeNumbersIfSeries(): Boolean =
        title.preview.type != ContentType.SERIES || (state.season != null && state.episode != null)

    private fun ServerResumeEntry.toProgress(addonBaseUrl: String): WatchProgress {
        val preview = title.preview
        val isEpisode = preview.type == ContentType.SERIES && state.season != null && state.episode != null
        return WatchProgress(
            contentId = preview.id,
            contentType = preview.apiType,
            name = preview.name,
            poster = preview.poster,
            backdrop = preview.background,
            logo = preview.logo,
            videoId = state.videoId,
            season = state.season.takeIf { isEpisode },
            episode = state.episode.takeIf { isEpisode },
            episodeTitle = state.title.takeIf { isEpisode },
            position = state.positionMs,
            duration = state.durationMs,
            lastWatched = state.lastPlayedEpochMs ?: 0L,
            addonBaseUrl = addonBaseUrl
        )
    }

    private fun ServerUserState.toProgress(meta: Meta, isSeries: Boolean, addonBaseUrl: String) = WatchProgress(
        contentId = meta.id,
        contentType = meta.apiType,
        name = meta.name,
        poster = meta.poster,
        backdrop = meta.background,
        logo = meta.logo,
        videoId = videoId,
        season = season.takeIf { isSeries },
        episode = episode.takeIf { isSeries },
        episodeTitle = title.takeIf { isSeries },
        position = positionMs,
        duration = durationMs,
        lastWatched = lastPlayedEpochMs ?: 0L,
        addonBaseUrl = addonBaseUrl
    )

    private fun ServerUserState.toWatchedItem(meta: Meta, isSeries: Boolean) = WatchedItem(
        contentId = meta.id,
        contentType = meta.apiType,
        title = meta.name,
        season = season.takeIf { isSeries },
        episode = episode.takeIf { isSeries },
        watchedAt = lastPlayedEpochMs ?: System.currentTimeMillis(),
        poster = meta.poster
    )

    private companion object {
        const val TAG = "ServerUserState"
        const val RESUME_LIMIT = 30
        const val RESUME_IMPORT_INTERVAL_MS = 60_000L
        const val ACCOUNT_MERGE_SETTLE_MS = 1_000L
    }
}
