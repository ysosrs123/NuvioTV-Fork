package com.nuvio.tv.data.repository

import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.sync.WatchProgressSyncService
import com.nuvio.tv.core.sync.WatchStateMutationStore
import com.nuvio.tv.core.sync.WatchedItemsSyncService
import android.os.SystemClock
import android.util.Log
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.local.WatchProgressSource
import com.nuvio.tv.data.local.watchProgressMetadataType
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.data.mediaserver.ServerItemRef
import com.nuvio.tv.data.mediaserver.ServerWatchMark
import com.nuvio.tv.data.mediaserver.ServerWatched
import com.nuvio.tv.data.mediaserver.catalogIds
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import com.nuvio.tv.domain.model.WatchedMutationKey
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.core.tracking.TrackingCatalogReference
import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.core.tracking.TrackingHistoryItem
import com.nuvio.tv.core.tracking.TrackingHistoryWriter
import com.nuvio.tv.core.tracking.TrackingHistoryWriterRegistry
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingProgressProvider
import com.nuvio.tv.core.tracking.TrackingProgressProviderRegistry
import com.nuvio.tv.core.tracking.mergeProgressProjectionWithRetainedLocal
import com.nuvio.tv.core.tracking.mergeWatchedEpisodeProjection
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.core.tracking.buildTrackingMediaReference
import com.nuvio.tv.core.tracking.trackingMediaKind
import com.nuvio.tv.core.tracking.effectiveWatchProgressSource
import com.nuvio.tv.core.tracking.providerId
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.flow
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.async
import javax.inject.Inject
import javax.inject.Singleton

private const val REMOTE_PROGRESS_WRITE_DEDUP_WINDOW_MS = 5_000L

private data class RemoteProgressWriteKey(
    val profileId: Int,
    val progressKey: String
)

private data class RemoteProgressWrite(
    val progress: WatchProgress,
    val sentAtMs: Long
)

internal class RemoteProgressWriteDeduplicator(
    private val windowMs: Long = REMOTE_PROGRESS_WRITE_DEDUP_WINDOW_MS
) {
    private val lock = Any()
    private val recentWrites = mutableMapOf<RemoteProgressWriteKey, RemoteProgressWrite>()

    fun shouldSend(
        profileId: Int,
        progressKey: String,
        progress: WatchProgress,
        nowMs: Long
    ): Boolean = synchronized(lock) {
        recentWrites.entries.removeAll { (_, write) ->
            val elapsedMs = nowMs - write.sentAtMs
            elapsedMs < 0L || elapsedMs >= windowMs
        }
        val key = RemoteProgressWriteKey(profileId, progressKey)
        val normalizedProgress = progress.copy(lastWatched = 0L)
        if (recentWrites[key]?.progress == normalizedProgress) {
            return@synchronized false
        }
        recentWrites[key] = RemoteProgressWrite(normalizedProgress, nowMs)
        true
    }
}

internal fun resolveProviderEpisodeProgress(
    contentId: String,
    season: Int,
    episode: Int,
    episodeProgress: Map<Pair<Int, Int>, WatchProgress>,
    allProgress: List<WatchProgress>
): WatchProgress? {
    val liveProgress = allProgress
        .asSequence()
        .filter { progress ->
            progress.contentId.equals(contentId, ignoreCase = true) &&
                progress.season == season &&
                progress.episode == episode
        }
        .maxByOrNull(WatchProgress::lastWatched)
    return listOfNotNull(episodeProgress[season to episode], liveProgress)
        .maxByOrNull(WatchProgress::lastWatched)
}

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class WatchProgressRepositoryImpl @Inject constructor(
    private val watchProgressPreferences: WatchProgressPreferences,
    private val traktSettingsDataStore: TraktSettingsDataStore,
    private val layoutPreferenceDataStore: com.nuvio.tv.data.local.LayoutPreferenceDataStore,
    private val watchProgressSyncService: WatchProgressSyncService,
    private val watchedItemsPreferences: WatchedItemsPreferences,
    private val watchedItemsSyncService: WatchedItemsSyncService,
    private val authManager: AuthManager,
    private val metaRepository: MetaRepository,
    private val tmdbService: TmdbService,
    private val profileManager: com.nuvio.tv.core.profile.ProfileManager,
    private val trackingProgressProviders: TrackingProgressProviderRegistry,
    private val trackingHistoryWriters: TrackingHistoryWriterRegistry,
    private val mutationStore: WatchStateMutationStore,
    private val serverWatched: ServerWatched,
) : WatchProgressRepository {
    companion object {
        private const val TAG = "WatchProgressRepo"
    }

    private data class EpisodeMetadata(
        val title: String?,
        val thumbnail: String?,
        val runtimeMs: Long = 0L
    )

    private data class ContentMetadata(
        val name: String?,
        val poster: String?,
        val backdrop: String?,
        val logo: String?,
        val episodes: Map<Pair<Int, Int>, EpisodeMetadata>,
        val runtimeMs: Long = 0L
    )

    private data class ProgressMetadataKey(val type: String, val contentId: String)
    private data class HydrationWorkKey(val metadata: ProgressMetadataKey, val entry: String?, val videoId: String?)
    private fun metadataKey(progress: WatchProgress) = ProgressMetadataKey(
        watchProgressMetadataType(progress.contentType), progress.contentId
    )
    private fun hydrationKey(progress: WatchProgress, provider: TrackingProgressProvider?) = HydrationWorkKey(
        metadataKey(progress), if (provider == null) progressKey(progress) else null,
        if (provider == null) progress.videoId else null
    )

    private data class RequestedProgressSource(val source: WatchProgressSource, val revision: Long)
    private data class ProgressProviderConnections(val connections: Map<TrackingProviderId, Boolean>, val revision: Long)
    private data class HydrationInputs(val requested: RequestedProgressSource, val connections: ProgressProviderConnections)

    private data class HydrationIdentity(
        val profileId: Int,
        val selection: Long,
        val history: Long,
        val inputs: HydrationInputs,
        val provider: TrackingProgressProvider?
    )

    /** One collector/selection owns optional work; required sync retains its independent scope. */
    private inner class HydrationOwner(val identity: HydrationIdentity) {
        private val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        val metadata = MutableStateFlow<Map<ProgressMetadataKey, ContentMetadata>>(emptyMap())
        private val lock = Any()
        private val attempts = mutableSetOf<HydrationWorkKey>()
        private val jobs = mutableMapOf<HydrationWorkKey, Job>()
        private var visible = emptySet<HydrationWorkKey>()
        private val localAdmission = Semaphore(1)

        fun isCurrent(): Boolean = job.isActive && identity.inputs.requested.revision > 0L &&
            profileManager.activeProfileId.value == identity.profileId &&
            profileManager.profileSelectionRevision.value == identity.selection &&
            profileManager.profileHistoryGenerationChanges.value == identity.history &&
            requestedProgressSourceState.value == identity.inputs.requested &&
            progressProviderConnectionState.value == identity.inputs.connections &&
            activeProgressProviderState.value === identity.provider &&
            effectiveWatchProgressSource(identity.inputs.requested.source) { identity.inputs.connections.connections[it] == true }
                .providerId == identity.provider?.providerId

        fun close() { job.cancel() }

        fun schedule(items: List<WatchProgress>) {
            val provider = identity.provider
            val candidates = items.sortedByDescending(WatchProgress::lastWatched)
                .distinctBy { hydrationKey(it, provider) }
                .take(if (provider != null) metadataHydrationLimit else 10)
            val keys = candidates.mapTo(mutableSetOf()) { hydrationKey(it, provider) }
            val contentKeys = keys.mapTo(mutableSetOf()) { it.metadata }
            synchronized(lock) {
                visible = keys
                jobs.keys.filter { it !in keys }.forEach { key -> jobs.remove(key)?.cancel() }
                attempts.retainAll(keys)
                metadata.update { old -> old.filterKeys { it in contentKeys } }
                for (progress in candidates) {
                    val key = hydrationKey(progress, provider)
                    val missing = if (provider != null) progress.poster == null || progress.backdrop == null ||
                        progress.episodeTitle == null else progress.poster == null && progress.backdrop == null
                    if (!missing || progress.contentId.isBlank() || !attempts.add(key)) continue
                    val operation = scope.launch(start = CoroutineStart.LAZY) {
                        try {
                            val operationContext = currentCoroutineContext()
                            val admitted = { operationContext.isActive && isCurrent() && synchronized(lock) { key in visible } }
                            if (provider != null) {
                                if (!admitted()) return@launch
                                val result = fetchContentMetadata(progress.contentId, progress.contentType, admitted)
                                    ?: return@launch
                                if (admitted()) metadata.update { it + (key.metadata to result) }
                            } else localAdmission.withPermit {
                                if (admitted()) hydrateProgressArtwork(progress, identity.profileId, admitted)
                            }
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            Log.w(TAG, "Optional progress metadata failed for ${progress.contentId}", error)
                        }
                    }
                    jobs[key] = operation
                    operation.invokeOnCompletion { synchronized(lock) { if (jobs[key] === operation) jobs.remove(key) } }
                    operation.start()
                }
            }
        }
    }

    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncJobs = mutableMapOf<Int, Job>()
    private val syncJobsLock = Any()
    private val remoteProgressWriteDeduplicator = RemoteProgressWriteDeduplicator()
    var isSyncingFromRemote = false
    var hasCompletedInitialPull = false
    var hasCompletedInitialWatchedItemsPull = false

    private val optimisticContinueWatchingUpdates = MutableSharedFlow<WatchProgress>(
        replay = 1,
        extraBufferCapacity = 16
    )
    private val optimisticWatchedMovieAdditions = MutableStateFlow<Set<String>>(emptySet())
    private val optimisticWatchedMovieRemovals = MutableStateFlow<Set<String>>(emptySet())
    private val metadataHydrationLimit = 30

    private fun triggerRemoteSync(profileId: Int) {
        if (isSyncingFromRemote) return
        if (!hasCompletedInitialPull) return
        if (!authManager.isAuthenticated) return
        synchronized(syncJobsLock) {
            syncJobs.remove(profileId)?.cancel()
            syncJobs[profileId] = syncScope.launch {
                delay(2000)
                withContext(NonCancellable) {
                    watchProgressSyncService.pushToRemote(profileId)
                }
            }
        }
    }

    private fun triggerWatchedItemsSync(
        items: Collection<WatchedItem>,
        profileId: Int
    ) {
        if (items.isEmpty()) return
        triggerWatchedItemsSync(profileId)
    }

    private fun triggerWatchedItemsSync(profileId: Int) {
        if (isSyncingFromRemote) return
        if (!hasCompletedInitialWatchedItemsPull) return
        if (!authManager.isAuthenticated) return
        syncScope.launch {
            withContext(NonCancellable) {
                watchedItemsSyncService.pushToRemote(profileId)
            }
        }
    }

    private suspend fun fetchContentMetadata(
        contentId: String,
        contentType: String,
        isCurrent: () -> Boolean
    ): ContentMetadata? {
        val typeCandidates = buildList {
            val normalized = contentType.lowercase()
            if (normalized.isNotBlank()) add(normalized)
            if (normalized in listOf("series", "tv")) {
                add("series")
                add("tv")
            } else {
                add("movie")
            }
        }.distinct()

        val idCandidates = buildList {
            add(contentId)
            if (contentId.startsWith("tmdb:")) add(contentId.substringAfter(':'))
            if (contentId.startsWith("trakt:")) add(contentId.substringAfter(':'))
        }.distinct()

        for (type in typeCandidates) {
            for (candidateId in idCandidates) {
                currentCoroutineContext().ensureActive()
                if (!isCurrent()) return null
                val result = withTimeoutOrNull(3500) {
                    metaRepository.getMetaFromPrimaryAddon(type = type, id = candidateId)
                        .first { it !is NetworkResult.Loading }
                } ?: continue

                currentCoroutineContext().ensureActive()
                if (!isCurrent()) return null
                val meta = (result as? NetworkResult.Success)?.data ?: continue
                val episodes = meta.videos
                    .mapNotNull { video ->
                        val season = video.season ?: return@mapNotNull null
                        val episode = video.episode ?: return@mapNotNull null
                        (season to episode) to EpisodeMetadata(
                            title = video.title,
                            thumbnail = video.thumbnail,
                            runtimeMs = (video.runtime ?: 0).toLong() * 60_000L
                        )
                    }
                    .toMap()

                return ContentMetadata(
                    name = meta.name,
                    poster = meta.poster,
                    backdrop = meta.backdropUrl,
                    logo = meta.logo,
                    episodes = episodes,
                    runtimeMs = parseRuntimeToMs(meta.runtime)
                )
            }
        }
        return null
    }

    private fun enrichWithMetadata(
        progress: WatchProgress,
        metadataMap: Map<ProgressMetadataKey, ContentMetadata>
    ): WatchProgress {
        val metadata = metadataMap[metadataKey(progress)] ?: return progress
        val episodeMeta = if (progress.season != null && progress.episode != null) {
            metadata.episodes[progress.season to progress.episode]
        } else {
            null
        }
        val shouldOverrideName = progress.name.isBlank() || progress.name == progress.contentId
        val backdrop = progress.backdrop
            ?: metadata.backdrop
            ?: episodeMeta?.thumbnail

        val episodeRuntimeMs = episodeMeta?.runtimeMs ?: 0L
        val runtimeMs = episodeRuntimeMs.takeIf { it > 0 } ?: metadata.runtimeMs

        return progress.copy(
            name = if (shouldOverrideName) metadata.name ?: progress.name else progress.name,
            poster = progress.poster ?: metadata.poster,
            backdrop = backdrop,
            logo = progress.logo ?: metadata.logo,
            duration = if (progress.duration > 0) progress.duration
                       else if (runtimeMs > 0) runtimeMs
                       else progress.duration,
            episodeTitle = progress.episodeTitle ?: episodeMeta?.title
        )
    }

    @OptIn(FlowPreview::class)
    private val progressProviderConnections = combine(
        trackingProgressProviders.providers().map { provider ->
            provider.isAuthenticated.map { authenticated -> provider.providerId to authenticated }
        }
    ) { states -> states.toMap() }
    private val connectionRevision = AtomicLong()
    private val progressProviderConnectionState = progressProviderConnections.distinctUntilChanged()
        .map { ProgressProviderConnections(it, connectionRevision.incrementAndGet()) }
        .stateIn(syncScope, SharingStarted.Eagerly, ProgressProviderConnections(emptyMap(), 0L))
    @Volatile private var activeProgressProviderId: TrackingProviderId? = null

    private val sourceRevision = AtomicLong()
    // Ready local rows can emit before source observation; revision zero admits no optional lookup.
    private val requestedProgressSourceState: StateFlow<RequestedProgressSource> = traktSettingsDataStore.watchProgressSource
        .map { RequestedProgressSource(it, sourceRevision.incrementAndGet()) }
        .stateIn(syncScope, SharingStarted.Eagerly, RequestedProgressSource(WatchProgressSource.NUVIO_SYNC, 0L))
    private val hydrationInputs = combine(requestedProgressSourceState, progressProviderConnectionState) {
            requested, connections -> HydrationInputs(requested, connections)
    }

    @OptIn(FlowPreview::class)
    private val activeProgressProviderState: StateFlow<TrackingProgressProvider?> = combine(
        requestedProgressSourceState,
        progressProviderConnectionState
    ) { requested, connections ->
        effectiveWatchProgressSource(requested.source) { providerId -> connections.connections[providerId] == true }
            .providerId
            ?.let(trackingProgressProviders::provider)
    }.debounce { provider ->
        if (provider == null) 100L else 0L
    }.distinctUntilChanged()
        .stateIn(syncScope, SharingStarted.Eagerly, null)

    init {
        syncScope.launch {
            activeProgressProviderState.collect { provider ->
                activeProgressProviderId = provider?.providerId
            }
        }
    }

    private fun activeProgressProviderFlow(): Flow<TrackingProgressProvider?> = activeProgressProviderState

    private fun profileProgressProviderFlow(profileId: Int): Flow<TrackingProgressProvider?> =
        profileManager.activeProfileId.flatMapLatest { activeProfileId ->
            if (activeProfileId == profileId) activeProgressProviderFlow() else flowOf(null)
        }

    private suspend fun activeProgressProvider(): TrackingProgressProvider? =
        activeProgressProviderFlow().first()

    private suspend fun connectedProgressProviders(): List<TrackingProgressProvider> =
        trackingProgressProviders.providers().filter { provider -> provider.isAuthenticated.first() }

    override fun observeRemoteProgressLoaded(): Flow<Boolean> {
        return activeProgressProviderFlow().flatMapLatest { provider ->
            provider?.remoteProgressLoaded ?: flowOf(true)
        }.distinctUntilChanged()
    }

    override val allProgress: Flow<List<WatchProgress>>
        get() = combine(
            profileManager.activeProfileId,
            profileManager.profileSelectionRevision,
            profileManager.profileHistoryGenerationChanges,
            hydrationInputs,
            activeProgressProviderState
        ) { profileId, selection, history, inputs, provider ->
            HydrationIdentity(profileId, selection, history, inputs, provider)
        }.distinctUntilChanged().flatMapLatest { identity ->
            flow {
                val owner = HydrationOwner(identity)
                try {
                    val local = watchProgressPreferences.observeAllProgress(identity.profileId)
                    val provider = identity.provider
                    val rows = if (provider != null) combine(provider.allProgress, local, owner.metadata) {
                            items, localItems, metadata ->
                        mergeProgressProjectionWithRetainedLocal(items, localItems, provider::retainsLocalProgress)
                            .map { enrichWithMetadata(it, metadata) }
                            .sortedByDescending(WatchProgress::lastWatched)
                    } else local
                    emitAll(rows.onEach(owner::schedule))
                } finally {
                    // Invalidate/cancel without joining a provider that ignores cancellation.
                    owner.close()
                }
            }
        }

    override val continueWatching: Flow<List<WatchProgress>>
        get() = allProgress.map { list -> list.filter { it.isInProgress() } }

    override val watchedItems: Flow<List<WatchedItem>>
        get() = activeProgressProviderFlow().flatMapLatest { provider ->
            provider?.watchedItems ?: watchedItemsPreferences.allItems
        }.distinctUntilChanged()

    override fun getProgress(contentId: String): Flow<WatchProgress?> {
        return profileManager.activeProfileId.flatMapLatest { profileId ->
            getProgress(contentId, profileId)
        }
    }

    override fun getProgress(contentId: String, profileId: Int): Flow<WatchProgress?> {
        return profileProgressProviderFlow(profileId)
            .flatMapLatest { provider ->
                if (provider != null) {
                    provider.allProgress.map { items ->
                        items
                            .filter { it.contentId.equals(contentId, ignoreCase = true) }
                            .maxByOrNull(WatchProgress::lastWatched)
                    }
                } else {
                    watchProgressPreferences.getProgress(contentId, profileId)
                }
            }
    }

    override fun getEpisodeProgress(contentId: String, season: Int, episode: Int): Flow<WatchProgress?> {
        return profileManager.activeProfileId.flatMapLatest { profileId ->
            getEpisodeProgress(contentId, season, episode, profileId)
        }
    }

    override fun getEpisodeProgress(
        contentId: String,
        season: Int,
        episode: Int,
        profileId: Int
    ): Flow<WatchProgress?> {
        return profileProgressProviderFlow(profileId)
            .flatMapLatest { provider ->
                if (provider != null) {
                    combine(
                        provider.episodeProgress(contentId),
                        provider.allProgress
                    ) { items, allProgress ->
                        resolveProviderEpisodeProgress(
                            contentId = contentId,
                            season = season,
                            episode = episode,
                            episodeProgress = items,
                            allProgress = allProgress
                        )
                    }
                } else {
                    watchProgressPreferences.getEpisodeProgress(contentId, season, episode, profileId)
                }
            }
    }

    override fun getAllEpisodeProgress(contentId: String): Flow<Map<Pair<Int, Int>, WatchProgress>> {
        return profileManager.activeProfileId.flatMapLatest { profileId ->
            getAllEpisodeProgress(contentId, profileId)
        }
    }

    override fun getAllEpisodeProgress(
        contentId: String,
        profileId: Int
    ): Flow<Map<Pair<Int, Int>, WatchProgress>> {
        return profileProgressProviderFlow(profileId)
            .flatMapLatest { provider ->
                if (provider != null) {
                    combine(
                        provider.episodeProgress(contentId).onStart { emit(emptyMap()) },
                        provider.allProgress.map { items ->
                            items.filter {
                                it.contentId.equals(contentId, ignoreCase = true) &&
                                    it.season != null && it.episode != null
                            }
                        }
                    ) { remoteMap, liveEpisodes ->
                        val merged = remoteMap.toMutableMap()
                        liveEpisodes.forEach { episodeProgress ->
                            val seasonNum = episodeProgress.season ?: return@forEach
                            val episodeNum = episodeProgress.episode ?: return@forEach
                            merged[seasonNum to episodeNum] = episodeProgress
                        }
                        merged
                    }.distinctUntilChanged()
                } else {
                    watchProgressPreferences.getAllEpisodeProgress(contentId, profileId)
                }
            }
    }

    override fun getAiredEpisodeOrder(contentId: String): Flow<List<Pair<Int, Int>>> {
        return activeProgressProviderFlow()
            .flatMapLatest { provider ->
                provider?.airedEpisodeOrder(contentId) ?: flowOf(emptyList())
            }
            .distinctUntilChanged()
    }

    override fun observeNextUpSeeds(): Flow<List<WatchProgress>> {
        return activeProgressProviderFlow()
            .flatMapLatest { provider ->
                if (provider != null) {
                    provider.nextUpSeeds
                } else {
                    // Use watched items (fully synced with pagination) to build seeds
                    // instead of watch progress (limited to 1000 entries).
                    combine(
                        watchedItemsPreferences.allItems,
                        layoutPreferenceDataStore.nextUpFromFurthestEpisode
                    ) { items, useFurthest ->
                        items
                            .filter { item ->
                                (item.contentType.equals("series", ignoreCase = true) ||
                                    item.contentType.equals("tv", ignoreCase = true)) &&
                                    item.season != null &&
                                    item.episode != null &&
                                    item.season != 0 &&
                                    !isMalformedNextUpSeedContentId(item.contentId)
                            }
                            .groupBy { it.contentId }
                            .mapNotNull { (_, episodes) ->
                                val latest = episodes.maxWithOrNull(
                                    if (useFurthest) {
                                        compareBy<WatchedItem> { it.season ?: 0 }
                                            .thenBy { it.episode ?: 0 }
                                            .thenBy { it.watchedAt }
                                    } else {
                                        compareBy<WatchedItem> { it.watchedAt }
                                            .thenBy { it.season ?: 0 }
                                            .thenBy { it.episode ?: 0 }
                                    }
                                ) ?: return@mapNotNull null
                                WatchProgress(
                                    contentId = latest.contentId,
                                    contentType = latest.contentType,
                                    name = latest.title,
                                    poster = null,
                                    backdrop = null,
                                    logo = null,
                                    videoId = latest.contentId,
                                    season = latest.season,
                                    episode = latest.episode,
                                    episodeTitle = null,
                                    position = 1L,
                                    duration = 1L,
                                    lastWatched = latest.watchedAt,
                                    progressPercent = 100f
                                )
                            }
                    }.flowOn(Dispatchers.Default)
                }
            }
            .distinctUntilChanged()
    }

    private fun isMalformedNextUpSeedContentId(contentId: String?): Boolean {
        val trimmed = contentId?.trim().orEmpty()
        if (trimmed.isEmpty()) return true
        val lowered = trimmed.lowercase()
        return lowered == "tmdb" ||
            lowered == "imdb" ||
            lowered == "trakt" ||
            lowered == "tmdb:" ||
            lowered == "imdb:" ||
            lowered == "trakt:"
    }

    override fun observeOptimisticContinueWatchingUpdates(): Flow<WatchProgress> {
        return optimisticContinueWatchingUpdates
    }

    override suspend fun prepareNextUpSeed(progress: WatchProgress): WatchProgress {
        return activeProgressProvider()?.prepareNextUpSeed(progress) ?: progress
    }

    @OptIn(FlowPreview::class)
    override fun observeWatchedMovieIds(): Flow<Set<String>> {
        val baseFlow = activeProgressProviderFlow()
            .flatMapLatest { provider ->
                if (provider != null) {
                    provider.watchedMovieIds
                } else {
                    combine(
                        watchProgressPreferences.allProgress,
                        watchedItemsPreferences.allItems
                    ) { progressList, watchedItems ->
                        val completedIds = mutableSetOf<String>()
                        val replayingIds = mutableSetOf<String>()
                        for (progress in progressList) {
                            if (progress.isCompleted()) {
                                completedIds.add(progress.contentId)
                            } else if (progress.position > 0L ||
                                progress.progressPercent?.let { it > 0f } == true
                            ) {
                                replayingIds.add(progress.contentId)
                            }
                        }
                        val watchedItemIds = watchedItems
                            .filter { it.season == null && it.episode == null }
                            .map { it.contentId }
                            .toSet()
                        (completedIds + watchedItemIds) - replayingIds
                    }.debounce(500)
                }
            }
        return combine(
            baseFlow,
            optimisticWatchedMovieAdditions,
            optimisticWatchedMovieRemovals
        ) { base, additions, removals ->
            (base + additions) - removals
        }.distinctUntilChanged()
    }

    override fun applyOptimisticWatchedMovie(ids: Set<String>, add: Boolean) {
        if (add) {
            optimisticWatchedMovieAdditions.update { it + ids }
            optimisticWatchedMovieRemovals.update { it - ids }
        } else {
            optimisticWatchedMovieRemovals.update { it + ids }
            optimisticWatchedMovieAdditions.update { it - ids }
        }
    }

    override fun revertOptimisticWatchedMovie(ids: Set<String>, add: Boolean) {
        if (add) {
            optimisticWatchedMovieAdditions.update { it - ids }
        } else {
            optimisticWatchedMovieRemovals.update { it - ids }
        }
    }

    override suspend fun getWatchedShowEpisodes(): Map<String, Set<Pair<Int, Int>>> {
        val profileId = profileManager.activeProfileId.value
        val watchedGeneration = watchedItemsPreferences.captureGeneration(profileId)
        val provider = activeProgressProvider()
        if (provider != null) {
            val providerEpisodes = provider.watchedShowEpisodes()
            if (profileManager.activeProfileId.value != profileId) return emptyMap()
            val localItems = watchedItemsPreferences.getAllItems(profileId, watchedGeneration)
            if (profileManager.activeProfileId.value != profileId) return emptyMap()
            return mergeWatchedEpisodeProjection(
                providerEpisodes = providerEpisodes,
                localItems = localItems,
                retainsLocalWatchedEpisode = provider::retainsLocalWatchedEpisode
            )
        }
        return watchedItemsPreferences.getAllItems(profileId, watchedGeneration)
            .filter { it.season != null && it.episode != null }
            .groupBy { it.contentId }
            .mapValues { (_, items) -> items.map { it.season!! to it.episode!! }.toSet() }
    }

    override suspend fun getShowIdSiblings(): Map<String, Set<String>> {
        return activeProgressProvider()?.showIdSiblings().orEmpty()
    }

    override fun isWatchedByVideoId(videoId: String, episode: Int): Boolean? {
        val providerId = activeProgressProviderId ?: return null
        return trackingProgressProviders.provider(providerId)?.isWatchedByVideoId(videoId, episode)
    }

    override fun isWatched(contentId: String, videoId: String?, season: Int?, episode: Int?): Flow<Boolean> {
        return activeProgressProviderFlow()
            .flatMapLatest { provider ->
                if (provider == null) {
                    val progressFlow = if (season != null && episode != null) {
                        watchProgressPreferences.getEpisodeProgress(contentId, season, episode)
                    } else {
                        watchProgressPreferences.getProgress(contentId)
                    }
                    return@flatMapLatest combine(
                        progressFlow,
                        watchedItemsPreferences.isWatched(contentId, season, episode)
                    ) { progressEntry, itemWatched ->
                        val hasStartedReplay = progressEntry?.let { entry ->
                            !entry.isCompleted() &&
                                (entry.position > 0L || entry.progressPercent?.let { it > 0f } == true)
                        } == true

                        if (hasStartedReplay) {
                            false
                        } else {
                            (progressEntry?.isCompleted() == true) || itemWatched
                        }
                    }
                }

                provider.isWatched(contentId, videoId, season, episode)
            }
    }

    override suspend fun saveProgress(progress: WatchProgress, syncRemote: Boolean) {
        val profileId = profileManager.activeProfileId.value
        saveProgress(progress, profileId, syncRemote)
    }

    override suspend fun saveProgress(
        progress: WatchProgress,
        profileId: Int,
        syncRemote: Boolean
    ) {
        val watchedGeneration = watchedItemsPreferences.captureGeneration(profileId)
        if (progress.contentType.equals("series", ignoreCase = true) ||
            progress.contentType.equals("tv", ignoreCase = true)) {
            traktSettingsDataStore.removeDismissedNextUpKeysForContent(progress.contentId, profileId)
        }
        val progressKey = progressKey(progress)
        val shouldPushRemote = syncRemote &&
            authManager.isAuthenticated &&
            remoteProgressWriteDeduplicator.shouldSend(
                profileId = profileId,
                progressKey = progressKey,
                progress = progress,
                nowMs = SystemClock.elapsedRealtime()
            )
        if (syncRemote) {
            mutationStore.queueProgressUpserts(mapOf(progressKey to progress), profileId)
        }
        if (profileManager.activeProfileId.value == profileId) {
            activeProgressProvider()?.applyOptimisticProgress(progress, quiet = !syncRemote)
        }
        watchProgressPreferences.saveProgress(progress, profileId = profileId)

        if (shouldPushRemote) {
            syncScope.launch(NonCancellable) {
                watchProgressSyncService.pushSingleToRemote(progressKey, progress, profileId)
                    .onFailure { error ->
                        Log.w(TAG, "Failed single progress push; pending mutation retained", error)
                        triggerRemoteSync(profileId)
                    }
            }
        } else if (syncRemote && authManager.isAuthenticated) {
            triggerRemoteSync(profileId)
        }

        if (progress.isCompleted()) {
            val watchedItem = progress.toWatchedItem()
            watchedItemsPreferences.markAsWatched(watchedItem, profileId = profileId, generation = watchedGeneration)
            if (syncRemote) {
                mutationStore.queueWatchedUpserts(listOf(watchedItem), profileId)
            }
            if (syncRemote && authManager.isAuthenticated) {
                triggerWatchedItemsSync(listOf(watchedItem), profileId = profileId)
            }
            if (profileManager.activeProfileId.value == profileId) {
                optimisticContinueWatchingUpdates.tryEmit(progress)
            }
        }
    }

    override suspend fun saveProgressBatch(progressList: List<WatchProgress>, syncRemote: Boolean) {
        saveProgressBatch(progressList, profileManager.activeProfileId.value, syncRemote)
    }

    override suspend fun saveProgressBatch(
        progressList: List<WatchProgress>,
        profileId: Int,
        syncRemote: Boolean
    ) {
        if (progressList.isEmpty()) return
        val watchedGeneration = watchedItemsPreferences.captureGeneration(profileId)
        if (syncRemote) {
            mutationStore.queueProgressUpserts(
                progressList.associateBy(::progressKey),
                profileId
            )
        }
        if (syncRemote) {
            if (profileManager.activeProfileId.value == profileId) {
                activeProgressProvider()?.let { provider ->
                    progressList.forEach { progress -> provider.applyOptimisticProgress(progress, quiet = false) }
                }
            }
        }
        watchProgressPreferences.saveProgressBatch(progressList, profileId = profileId)

        if (syncRemote && authManager.isAuthenticated) {
            triggerRemoteSync(profileId = profileId)
        }

        val completedWatchedItems = progressList
            .filter { it.isCompleted() }
            .map { progress -> progress.toWatchedItem() }
        if (completedWatchedItems.isNotEmpty()) {
            watchedItemsPreferences.markAsWatchedBatch(completedWatchedItems, profileId = profileId, generation = watchedGeneration)
            if (syncRemote) {
                mutationStore.queueWatchedUpserts(completedWatchedItems, profileId)
            }
            if (syncRemote && authManager.isAuthenticated) {
                triggerWatchedItemsSync(completedWatchedItems, profileId = profileId)
            }
        }
    }

    override suspend fun removeProgress(contentId: String, season: Int?, episode: Int?) {
        val profileId = profileManager.activeProfileId.value
        val remoteDeleteKeys = resolveRemoteDeleteKeys(contentId, season, episode, profileId = profileId)
        mutationStore.queueProgressDeletes(remoteDeleteKeys, profileId)
        supervisorScope {
            connectedProgressProviders().map { provider ->
                async {
                    provider.applyOptimisticRemoval(contentId, season, episode)
                    runCatching { provider.removeProgress(contentId, season, episode) }
                        .onFailure { error ->
                            Log.w(TAG, "Failed ${provider.providerId.storageId} progress removal", error)
                        }
                }
            }.forEach { operation -> operation.await() }
        }
        watchProgressPreferences.removeProgress(contentId, season, episode, profileId)
        if (authManager.isAuthenticated && remoteDeleteKeys.isNotEmpty()) {
            watchProgressSyncService.deleteFromRemote(remoteDeleteKeys, profileId)
                .onFailure { error ->
                    Log.w(TAG, "removeProgress remote delete failed; pending mutation retained", error)
                }
        }
        triggerRemoteSync(profileId = profileId)
    }

    override suspend fun removeFromHistory(contentId: String, videoId: String?, season: Int?, episode: Int?) {
        val profileId = profileManager.activeProfileId.value
        val watchedGeneration = watchedItemsPreferences.captureGeneration(profileId)
        val remoteDeleteKeys = resolveRemoteDeleteKeys(contentId, season, episode, profileId = profileId)
        val watchedDeleteKey = WatchedMutationKey(contentId, season, episode)
        mutationStore.queueProgressDeletes(remoteDeleteKeys, profileId)
        mutationStore.queueWatchedDeletes(listOf(watchedDeleteKey), profileId)
        val media = buildTrackingMediaReference(
            contentType = if (season != null || episode != null) "series" else "movie",
            parentMetaId = contentId,
            videoId = videoId,
            seasonNumber = season,
            episodeNumber = episode
        )
        connectedProgressProviders().forEach { provider ->
            provider.applyOptimisticRemoval(contentId, season, episode)
        }
        broadcastHistoryRemoval(profileId, listOf(media))
        syncServerWatched(
            listOf(ServerWatchMark(contentId, if (season != null || episode != null) "series" else "movie", videoId, season, episode)),
            played = false,
            profileId = profileId
        )
        watchProgressPreferences.removeProgress(contentId, season, episode, profileId)
        watchedItemsPreferences.unmarkAsWatched(contentId, season, episode, profileId = profileId, generation = watchedGeneration)
        if (authManager.isAuthenticated && remoteDeleteKeys.isNotEmpty()) {
            watchProgressSyncService.deleteFromRemote(remoteDeleteKeys, profileId)
                .onFailure { error ->
                    Log.w(TAG, "removeFromHistory remote delete failed; pending mutation retained", error)
                }
        }
        if (authManager.isAuthenticated) {
            watchedItemsSyncService.deleteFromRemote(contentId, season, episode, profileId = profileId)
                .onFailure { error ->
                    Log.w(TAG, "removeFromHistory watched item remote delete failed; pending mutation retained", error)
                    triggerWatchedItemsSync(profileId)
                }
        }
        triggerRemoteSync(profileId = profileId)
    }

    override suspend fun removeFromHistoryBatch(
        contentId: String,
        videoId: String?,
        episodes: List<Triple<Int, Int, String?>>
    ) {
        if (episodes.isEmpty()) return
        val profileId = profileManager.activeProfileId.value
        val watchedGeneration = watchedItemsPreferences.captureGeneration(profileId)
        val episodePairs = episodes.map { (season, episode, _) -> season to episode }
        val remoteDeleteKeys = episodes.map { (season, episode, _) ->
            "${contentId}_s${season}e${episode}"
        } + contentId
        val watchedDeleteKeys = episodePairs.map { (season, episode) ->
            WatchedMutationKey(contentId, season, episode)
        }
        mutationStore.queueProgressDeletes(remoteDeleteKeys, profileId)
        mutationStore.queueWatchedDeletes(watchedDeleteKeys, profileId)
        watchProgressPreferences.removeProgressBatch(contentId, episodePairs, profileId)
        watchedItemsPreferences.unmarkAsWatchedBatch(contentId, episodePairs, profileId = profileId, generation = watchedGeneration)
        connectedProgressProviders().forEach { provider ->
            episodes.forEach { (season, episode, _) ->
                provider.applyOptimisticRemoval(contentId, season, episode)
            }
        }
        val media = episodes.map { (season, episode, epVideoId) ->
            buildTrackingMediaReference(
                contentType = "series",
                parentMetaId = contentId,
                videoId = epVideoId ?: videoId,
                seasonNumber = season,
                episodeNumber = episode
            )
        }
        broadcastHistoryRemoval(profileId, media)
        syncServerWatched(
            episodes.map { (season, episode, epVideoId) ->
                ServerWatchMark(contentId, "series", epVideoId ?: videoId, season, episode)
            },
            played = false,
            profileId = profileId
        )
        if (authManager.isAuthenticated) {
            watchProgressSyncService.deleteFromRemote(remoteDeleteKeys.distinct(), profileId)
                .onFailure { error -> Log.w(TAG, "removeFromHistoryBatch remote delete failed", error) }
            watchedItemsSyncService.deleteFromRemoteBatch(contentId, episodePairs, profileId = profileId)
                .onFailure { error ->
                    Log.w(TAG, "removeFromHistoryBatch watched item remote delete failed; pending mutation retained", error)
                    triggerWatchedItemsSync(profileId)
                }
        }
        triggerRemoteSync(profileId = profileId)
    }

    override suspend fun markAsCompleted(progress: WatchProgress, broadcastTrackingHistory: Boolean) {
        val profileId = profileManager.activeProfileId.value
        markAsCompleted(progress, profileId, broadcastTrackingHistory)
    }

    override suspend fun markAsCompleted(
        progress: WatchProgress,
        profileId: Int,
        broadcastTrackingHistory: Boolean
    ) {
        val watchedGeneration = watchedItemsPreferences.captureGeneration(profileId)
        if (progress.contentType.equals("series", ignoreCase = true) ||
            progress.contentType.equals("tv", ignoreCase = true)) {
            traktSettingsDataStore.removeDismissedNextUpKeysForContent(progress.contentId, profileId)
        }
        val now = System.currentTimeMillis()
        val progressKey = progressKey(progress)
        val duration = progress.duration.takeIf { it > 1L }
            ?: watchProgressPreferences.getAllRawEntries(profileId)[progressKey]
                ?.duration
                ?.takeIf { it > 1L }
            ?: 1L
        val completed = progress.copy(
            position = duration,
            duration = duration,
            progressPercent = 100f,
            lastWatched = now
        )
        mutationStore.queueProgressUpserts(mapOf(progressKey to completed), profileId)
        if (profileManager.activeProfileId.value == profileId) {
            optimisticContinueWatchingUpdates.tryEmit(completed)
            activeProgressProvider()?.applyOptimisticProgress(completed, quiet = false)
        }
        watchProgressPreferences.saveProgress(completed, profileId = profileId)
        val watchedItem = completed.toWatchedItem(watchedAt = now)
        watchedItemsPreferences.markAsWatched(watchedItem, profileId = profileId, generation = watchedGeneration)
        mutationStore.queueWatchedUpserts(listOf(watchedItem), profileId)
        if (broadcastTrackingHistory) {
            broadcastHistoryAdd(profileId, listOf(completed.toTrackingHistoryItem(now)))
            syncServerWatched(listOf(completed.toServerWatchMark()), played = true, profileId = profileId)
        }
        triggerRemoteSync(profileId = profileId)
        triggerWatchedItemsSync(listOf(watchedItem), profileId = profileId)
    }

    override suspend fun markAsCompletedBatch(progressList: List<WatchProgress>) {
        if (progressList.isEmpty()) return
        val profileId = profileManager.activeProfileId.value
        val watchedGeneration = watchedItemsPreferences.captureGeneration(profileId)
        val firstProgress = progressList.first()
        if (firstProgress.contentType.equals("series", ignoreCase = true) ||
            firstProgress.contentType.equals("tv", ignoreCase = true)) {
            traktSettingsDataStore.removeDismissedNextUpKeysForContent(firstProgress.contentId, profileId)
        }
        val now = System.currentTimeMillis()
        val rawEntries = watchProgressPreferences.getAllRawEntries(profileId)

        val completedList = progressList.map { progress ->
            val duration = progress.duration.takeIf { it > 1L }
                ?: rawEntries[progressKey(progress)]?.duration?.takeIf { it > 1L }
                ?: 1L
            progress.copy(
                position = duration,
                duration = duration,
                progressPercent = 100f,
                lastWatched = now
            )
        }

        mutationStore.queueProgressUpserts(completedList.associateBy(::progressKey), profileId)
        if (profileManager.activeProfileId.value == profileId) {
            activeProgressProvider()?.let { provider ->
                completedList.forEach { progress ->
                    optimisticContinueWatchingUpdates.tryEmit(progress)
                    provider.applyOptimisticProgress(progress, quiet = false)
                }
            }
        }
        watchProgressPreferences.saveProgressBatch(completedList, profileId = profileId)
        val watchedItems = completedList.map { progress -> progress.toWatchedItem(watchedAt = now) }
        watchedItemsPreferences.markAsWatchedBatch(watchedItems, profileId = profileId, generation = watchedGeneration)
        mutationStore.queueWatchedUpserts(watchedItems, profileId)
        broadcastHistoryAdd(profileId, completedList.map { it.toTrackingHistoryItem(now) })
        syncServerWatched(completedList.map { it.toServerWatchMark() }, played = true, profileId = profileId)
        triggerRemoteSync(profileId = profileId)
        triggerWatchedItemsSync(watchedItems, profileId = profileId)
    }

    private fun syncServerWatched(marks: List<ServerWatchMark>, played: Boolean, profileId: Int) {
        serverWatched.apply(marks, played) { failed -> restoreServerWatched(failed, played, profileId) }
        serverWatched.mirror(marks, played)
    }

    private suspend fun restoreServerWatched(failed: List<ServerWatchMark>, played: Boolean, profileId: Int) {
        if (!played) {
            watchedItemsPreferences.markAsWatchedBatch(
                failed.map { WatchedItem(it.contentId, it.contentType, "", it.season, it.episode, System.currentTimeMillis()) },
                profileId = profileId
            )
            return
        }
        failed.forEach { mark ->
            watchedItemsPreferences.unmarkAsWatched(mark.contentId, mark.season, mark.episode, profileId = profileId)
            watchProgressPreferences.removeProgress(mark.contentId, mark.season, mark.episode, profileId)
        }
    }

    private fun WatchProgress.toServerWatchMark() = ServerWatchMark(contentId, contentType, videoId, season, episode)

    private fun WatchProgress.toWatchedItem(watchedAt: Long = System.currentTimeMillis()): WatchedItem =
        WatchedItem(
            contentId = contentId,
            contentType = contentType,
            title = name,
            season = season,
            episode = episode,
            watchedAt = watchedAt
        )

    private fun WatchProgress.toTrackingHistoryItem(watchedAt: Long): TrackingHistoryItem =
        TrackingHistoryItem(
            media = buildTrackingMediaReference(
                contentType = contentType,
                parentMetaId = contentId,
                videoId = videoId,
                title = name,
                seasonNumber = season,
                episodeNumber = episode,
                episodeTitle = episodeTitle
            ),
            watchedAtEpochMs = watchedAt
        )

    private suspend fun connectedHistoryWriters(): List<TrackingHistoryWriter> {
        val connectedIds = connectedProgressProviders().mapTo(mutableSetOf()) { it.providerId }
        return trackingHistoryWriters.writers().filter { writer -> writer.providerId in connectedIds }
    }

    /** Server titles go to trackers under their IMDb, TMDB or TVDB id; one without any is left out. */
    private suspend fun <T> Collection<T>.withServerTrackerIds(
        media: (T) -> TrackingMediaReference,
        replace: (T, TrackingMediaReference) -> T
    ): List<T> {
        val resolved = mutableMapOf<String, TrackingExternalIds?>()
        return mapNotNull { item ->
            val reference = media(item)
            val catalog = reference.catalog
            if (catalog == null || !ServerItemRef.isServerId(catalog.contentId)) return@mapNotNull item
            val ids = if (catalog.contentId in resolved) {
                resolved[catalog.contentId]
            } else {
                serverTrackerIds(catalog).also { resolved[catalog.contentId] = it }
            }
            ids?.let {
                replace(
                    item,
                    reference.copy(
                        kind = trackingMediaKind(catalog.contentType, it),
                        ids = it,
                        catalog = catalog.copy(contentId = it.catalogIds().first(), videoId = null)
                    )
                )
            }
        }
    }

    private suspend fun serverTrackerIds(catalog: TrackingCatalogReference): TrackingExternalIds? =
        metaRepository.getCachedMeta(catalog.contentType, catalog.contentId)
            ?.imdbId
            ?.takeIf { it.startsWith("tt") }
            ?.let { TrackingExternalIds(imdb = it) }
            ?: serverWatched.trackerIds(catalog.contentId)

    private suspend fun broadcastHistoryAdd(
        profileId: Int,
        items: Collection<TrackingHistoryItem>
    ) {
        if (items.isEmpty()) return
        val writers = connectedHistoryWriters()
        if (writers.isEmpty()) return
        val items = items.withServerTrackerIds({ it.media }) { item, media -> item.copy(media = media) }
        if (items.isEmpty()) return
        supervisorScope {
            writers
                .map { writer ->
                    async {
                        runCatching { writer.addToHistory(profileId, items) }
                            .onFailure { error ->
                                Log.w(TAG, "Failed ${writer.providerId.storageId} history add", error)
                            }
                    }
                }
                .forEach { operation -> operation.await() }
        }
    }

    private suspend fun broadcastHistoryRemoval(
        profileId: Int,
        items: Collection<TrackingMediaReference>
    ) {
        if (items.isEmpty()) return
        val writers = connectedHistoryWriters()
        if (writers.isEmpty()) return
        val items = items.withServerTrackerIds({ it }) { _, media -> media }
        if (items.isEmpty()) return
        supervisorScope {
            writers
                .map { writer ->
                    async {
                        runCatching { writer.removeFromHistory(profileId, items) }
                            .onFailure { error ->
                                Log.w(TAG, "Failed ${writer.providerId.storageId} history removal", error)
                            }
                    }
                }
                .forEach { operation -> operation.await() }
        }
    }

    override suspend fun clearAll() {
        val profileId = profileManager.activeProfileId.value
        val provider = activeProgressProvider()
        if (provider != null) {
            provider.clearOptimistic()
            watchProgressPreferences.clearAllPreservingNonTraktIds(
                profileId = profileId,
                isNonTraktId = provider::retainsLocalProgress
            )
            return
        }
        watchProgressPreferences.clearAll(profileId)
    }

    override fun isDroppedShow(contentId: String): Boolean {
        return activeProgressProviderId
            ?.let(trackingProgressProviders::provider)
            ?.isHiddenFromProgress(contentId) == true
    }

    override fun hasActiveTrackingProgressProvider(): Boolean =
        activeProgressProviderId != null

    override fun activeProviderOwnsCompletedHistoryProjection(): Boolean =
        activeProgressProviderId
            ?.let(trackingProgressProviders::provider)
            ?.ownsCompletedHistoryProjection == true

    override fun activeProviderContinueWatchingCutoffEpochMs(
        daysCap: Int,
        nowEpochMs: Long
    ): Long? = activeProgressProviderId
        ?.let(trackingProgressProviders::provider)
        ?.continueWatchingCutoffEpochMs(daysCap, nowEpochMs)

    override fun shouldUseAsNextUpSeed(progress: WatchProgress, nowEpochMs: Long): Boolean =
        activeProgressProviderId
            ?.let(trackingProgressProviders::provider)
            ?.shouldUseAsNextUpSeed(progress, nowEpochMs)
            ?: progress.isCompleted()

    override suspend fun normalizeParentContentId(parentContentId: String, videoId: String?): String =
        activeProgressProvider()?.normalizeParentContentId(parentContentId, videoId) ?: parentContentId

    override suspend fun normalizeParentContentId(
        parentContentId: String,
        videoId: String?,
        profileId: Int
    ): String {
        if (profileManager.activeProfileId.value != profileId) return parentContentId
        return activeProgressProvider()?.normalizeParentContentId(parentContentId, videoId)
            ?: parentContentId
    }

    private suspend fun hydrateProgressArtwork(
        progress: WatchProgress,
        profileId: Int,
        isCurrent: () -> Boolean
    ) {
        if (!isCurrent()) return
        val storage = watchProgressPreferences.captureArtworkStorage(profileId)
        val metadata = fetchContentMetadata(progress.contentId, progress.contentType, isCurrent) ?: return
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) return
        val episode = if (progress.season != null && progress.episode != null)
            metadata.episodes[progress.season to progress.episode] else null
        val duration = episode?.runtimeMs?.takeIf { it > 0 } ?: metadata.runtimeMs
        var backdrop = metadata.backdrop
        var poster = metadata.poster
        if (backdrop == null && poster == null) {
            if (!isCurrent()) return
            val images = tmdbService.fetchImdbImages(progress.contentId, progress.contentType)
            currentCoroutineContext().ensureActive()
            if (!isCurrent()) return
            backdrop = images?.backdropUrl
            poster = images?.posterUrl
        }
        if (poster != null || backdrop != null || metadata.logo != null || duration > 0 || episode?.title != null) {
            watchProgressPreferences.updateArtworkIfPresent(
                progress.copy(poster = poster, backdrop = backdrop, logo = metadata.logo,
                    name = metadata.name ?: progress.name, duration = duration,
                    episodeTitle = episode?.title), storage, isCurrent
            )
        }
    }

    private fun parseRuntimeToMs(raw: String?): Long {
        val minutes = raw?.trim()?.toLongOrNull() ?: return 0L
        return minutes * 60_000L
    }

    private fun progressKey(progress: WatchProgress): String {
        return if (progress.season != null && progress.episode != null) {
            "${progress.contentId}_s${progress.season}e${progress.episode}"
        } else {
            progress.contentId
        }
    }

    private suspend fun resolveRemoteDeleteKeys(
        contentId: String,
        season: Int?,
        episode: Int?,
        profileId: Int = profileManager.activeProfileId.value
    ): List<String> {
        val rawEntries = watchProgressPreferences.getAllRawEntries(profileId)
        val keys = if (season != null && episode != null) {
            listOf("${contentId}_s${season}e${episode}", contentId)
        } else {
            val matchingLocalKeys = rawEntries
                .keys
                .filter { key ->
                    key == contentId || key.startsWith("${contentId}_")
                }
            matchingLocalKeys + contentId
        }
        val resolvedKeys = keys
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        return resolvedKeys
    }

}
