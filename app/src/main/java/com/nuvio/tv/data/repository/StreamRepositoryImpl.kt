package com.nuvio.tv.data.repository

import android.content.Context
import android.util.Log
import com.nuvio.tv.R
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.network.safeAddonApiCall
import com.nuvio.tv.core.debrid.DebridStreamPresentation
import com.nuvio.tv.core.debrid.LocalDebridAvailabilityService
import com.nuvio.tv.core.plugin.PluginManager
import com.nuvio.tv.core.plugin.resolvePluginSeasonEpisode
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.mapper.toDomain
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.DebridSettings
import com.nuvio.tv.domain.model.LocalScraperResult
import com.nuvio.tv.domain.model.PluginRepository
import com.nuvio.tv.domain.model.ProxyHeaders
import com.nuvio.tv.domain.model.ScraperInfo
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.StreamRepository
import com.nuvio.tv.core.health.AddonHealthStore
import com.nuvio.tv.core.health.HealthOutcome
import com.nuvio.tv.core.util.canonicalizeAddonUrl
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withTimeout
import java.net.URLEncoder
import java.security.MessageDigest
import javax.inject.Inject

private const val TAG = "StreamRepositoryImpl"

/**
 * Per-addon deadline for a single stream fetch.
 *
 * Without this each addon inherits only the shared client's connect(30s) +
 * read(60s) budget, so one unresponsive source can hold its slot for ~90s
 * while every other addon has long since answered. 15s is deliberately
 * generous: a cold scrape on a large title legitimately takes ten-plus
 * seconds, and cutting those off would trade a latency win for lost results.
 * Results already stream out as each addon lands, so a slow addon that beats
 * the deadline still contributes.
 */
private const val ADDON_STREAM_FETCH_TIMEOUT_MS = 15_000L

class StreamRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: AddonApi,
    private val addonRepository: AddonRepository,
    private val pluginManager: PluginManager,
    private val profileManager: ProfileManager,
    private val debridSettingsDataStore: DebridSettingsDataStore,
    private val tmdbService: TmdbService,
    private val debridStreamPresentation: DebridStreamPresentation,
    private val localDebridAvailabilityService: LocalDebridAvailabilityService,
    private val healthStore: AddonHealthStore
) : StreamRepository {

    // Detached scope for passive health writes so recording a stream
    // outcome never adds latency to the fetch or delays the channel close.
    private val healthScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Fire-and-forget a passive health sample for one addon stream fetch.
     * Maps the ADDON_MS outcome vocabulary onto HealthOutcome; cancelled
     * and any unknown outcome are dropped (an external cap-cancel is not
     * the addon's fault). Keyed by canonical base URL, matching the store.
     */
    private fun recordAddonHealth(baseUrl: String, outcome: String, latencyMs: Long) {
        val mapped = when (outcome) {
            "ok", "ok_inline" -> HealthOutcome.SUCCESS
            "empty" -> HealthOutcome.EMPTY
            "timeout", "error" -> HealthOutcome.FAILURE
            else -> return
        }
        healthScope.launch {
            healthStore.record(
                AddonHealthStore.addonKey(canonicalizeAddonUrl(baseUrl)),
                mapped,
                latencyMs
            )
        }
    }

    private val streamSearchSessions = StreamSearchSessionCache()
    private val localPluginSearchPaused = MutableStateFlow(false)

    override fun setLocalPluginSearchPaused(paused: Boolean) {
        localPluginSearchPaused.value = paused
    }

    private enum class StreamFailureKind {
        MISSING,
        REQUEST_FAILED
    }

    private data class StreamAttemptFailure(
        val addonName: String,
        val kind: StreamFailureKind,
        val detail: String
    )

    private data class StreamSourceConfigurationSnapshot(
        val profileId: Int,
        val addons: List<Addon>,
        val pluginsEnabled: Boolean,
        val enabledScrapers: List<ScraperInfo>,
        val groupPluginsByRepository: Boolean,
        val pluginRepositories: List<PluginRepository>,
        val debridSettings: DebridSettings
    )

    override fun getStreamsFromAllAddons(
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?,
        forceRefresh: Boolean
    ): Flow<NetworkResult<List<AddonStreams>>> = flow {
        val sourceConfiguration = captureSourceConfiguration()
        val requestKey = StreamSearchRequestKey(
            profileId = sourceConfiguration.profileId,
            type = type.lowercase(),
            videoId = videoId,
            season = season,
            episode = episode,
            sourceConfiguration = buildSourceConfigurationKey(
                addons = sourceConfiguration.addons,
                pluginsEnabled = sourceConfiguration.pluginsEnabled,
                enabledScrapers = sourceConfiguration.enabledScrapers,
                groupPluginsByRepository = sourceConfiguration.groupPluginsByRepository,
                pluginRepositories = sourceConfiguration.pluginRepositories,
                debridPresentationConfiguration = sourceConfiguration.debridSettings
                    .withoutRawCredentials()
                    .toString()
            )
        )

        emitAll(
            streamSearchSessions.observe(
                key = requestKey,
                forceRefresh = forceRefresh
            ) {
                fetchStreamsFromAllSources(
                    type = type,
                    videoId = videoId,
                    season = season,
                    episode = episode,
                    addons = sourceConfiguration.addons,
                    debridSettings = sourceConfiguration.debridSettings,
                    hasCompatiblePlugins = sourceConfiguration.pluginsEnabled &&
                        sourceConfiguration.enabledScrapers.any { scraper -> scraper.supportsType(type) }
                )
            }
        )
    }

    private suspend fun captureSourceConfiguration(): StreamSourceConfigurationSnapshot {
        while (true) {
            val profileId = profileManager.activeProfileId.value
            val addons = addonRepository.getInstalledAddons().first().enabledAddons()
            val pluginsEnabled = pluginManager.pluginsEnabled.first()
            val enabledScrapers = if (pluginsEnabled) pluginManager.enabledScrapers.first() else emptyList()
            val groupPluginsByRepository = pluginsEnabled && pluginManager.groupStreamsByRepository.first()
            val pluginRepositories = if (groupPluginsByRepository) pluginManager.repositories.first() else emptyList()
            val debridSettings = debridSettingsDataStore.settings.first()

            if (profileManager.activeProfileId.value != profileId) continue

            return StreamSourceConfigurationSnapshot(
                profileId = profileId,
                addons = addons,
                pluginsEnabled = pluginsEnabled,
                enabledScrapers = enabledScrapers,
                groupPluginsByRepository = groupPluginsByRepository,
                pluginRepositories = pluginRepositories,
                debridSettings = debridSettings
            )
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun fetchStreamsFromAllSources(
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?,
        addons: List<Addon>,
        debridSettings: DebridSettings,
        hasCompatiblePlugins: Boolean
    ): Flow<NetworkResult<List<AddonStreams>>> = flow {
        emit(NetworkResult.Loading)
        // P0 instrument: whole-scrape wall time. Grep anchor: SCRAPE_TOTAL.
        val scrapeT0 = android.os.SystemClock.elapsedRealtime()

        try {
            // Filter addons that support streams for this type and id
            val streamAddons = addons.filter { addon ->
                addon.supportsStreamResource(type, videoId)
            }

            val attemptedAddonNames = streamAddons.map { it.displayName }
            val attemptedFailures = java.util.Collections.synchronizedList(
                mutableListOf<StreamAttemptFailure>()
            )

            // Accumulate results as they arrive
            val accumulatedResults = mutableListOf<AddonStreams>()

            coroutineScope {
                // Channel to receive results as they complete
                val resultChannel = Channel<AddonStreams>(Channel.UNLIMITED)
                
                // Track number of pending jobs
                val totalJobs = streamAddons.size + 1
                val completedJobs = java.util.concurrent.atomic.AtomicInteger(0)

                // Launch addon jobs
                streamAddons.forEach { addon ->
                    launch {
                        // P0 instrument: per-addon wall time, from job launch to
                        // completion, timeout, error or cancellation. Grep anchor:
                        // ADDON_MS. Logged in the finally so every exit path,
                        // including the rethrown cancellation, produces a line.
                        val addonT0 = android.os.SystemClock.elapsedRealtime()
                        var addonOutcome = "cancelled"
                        var addonStreamCount = 0
                        try {
                          withTimeout(ADDON_STREAM_FETCH_TIMEOUT_MS) {
                            val streamsResult = getStreamsFromAddon(addon, type, videoId)
                            when (streamsResult) {
                                is NetworkResult.Success -> {
                                    if (streamsResult.data.isNotEmpty()) {
                                        val namedStreams = streamsResult.data.map {
                                            it.copy(addonName = addon.displayName, addonLogo = addon.logo)
                                        }
                                        addonOutcome = "ok"
                                        addonStreamCount = namedStreams.size
                                        resultChannel.send(
                                            AddonStreams(
                                                addonName = addon.displayName,
                                                addonLogo = addon.logo,
                                                streams = namedStreams
                                            )
                                        )
                                    } else {
                                        // Stream endpoint returned empty - try inline
                                        // streams from meta response as fallback.
                                        val inlineStreams = fetchInlineStreamsFromMeta(
                                            addon, type, videoId
                                        )
                                        if (inlineStreams.isNotEmpty()) {
                                            addonOutcome = "ok_inline"
                                            addonStreamCount = inlineStreams.size
                                            resultChannel.send(
                                                AddonStreams(
                                                    addonName = addon.displayName,
                                                    addonLogo = addon.logo,
                                                    streams = inlineStreams
                                                )
                                            )
                                        } else {
                                            addonOutcome = "empty"
                                            attemptedFailures += buildMissingStreamFailure(addon)
                                        }
                                    }
                                }
                                is NetworkResult.Error -> {
                                    addonOutcome = "error"
                                    attemptedFailures += buildAddonFailure(addon, streamsResult)
                                }
                                NetworkResult.Loading -> Unit
                            }
                          }
                        } catch (e: TimeoutCancellationException) {
                            // MUST precede the broad catch below: TimeoutCancellationException
                            // is a CancellationException, and rethrowing it would cancel the
                            // enclosing coroutineScope and every sibling addon job with it.
                            //
                            // Distinguish this addon's OWN 15s deadline from an EXTERNAL
                            // cancellation. The P2 prefetch cap wraps the collect in
                            // withTimeoutOrNull, whose TimeoutCancellationException propagates
                            // in here when the cap fires (e.g. at 3s) - which previously
                            // mislabelled a 3s cap-cancel as "exceeded 15000ms / timeout" and
                            // made a genuinely dead addon indistinguishable from one that was
                            // simply cut short. On a real own-timeout only the inner withTimeout
                            // scope expired, so this coroutine is still active; on an external
                            // cancel the coroutine itself is cancelled.
                            if (!isActive) {
                                addonOutcome = "cancelled"
                                throw e
                            }
                            addonOutcome = "timeout"
                            Log.w(TAG, "Addon ${addon.name} exceeded ${ADDON_STREAM_FETCH_TIMEOUT_MS}ms - abandoning")
                            attemptedFailures += StreamAttemptFailure(
                                addonName = addon.displayName,
                                kind = StreamFailureKind.REQUEST_FAILED,
                                detail = context.getString(com.nuvio.tv.R.string.stream_error_detail_addon_timeout)
                            )
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            addonOutcome = "error"
                            Log.e(TAG, "Addon ${addon.name} failed: ${e.message}")
                            attemptedFailures += StreamAttemptFailure(
                                addonName = addon.displayName,
                                kind = StreamFailureKind.REQUEST_FAILED,
                                detail = e.message ?: context.getString(com.nuvio.tv.R.string.stream_error_detail_addon_request_failed)
                            )
                        } finally {
                            val addonMs = android.os.SystemClock.elapsedRealtime() - addonT0
                            Log.i(
                                TAG,
                                "ADDON_MS name=${addon.displayName} " +
                                    "ms=$addonMs " +
                                    "streams=$addonStreamCount outcome=$addonOutcome"
                            )
                            recordAddonHealth(addon.baseUrl, addonOutcome, addonMs)
                            if (completedJobs.incrementAndGet() >= totalJobs) {
                                resultChannel.close()
                            }
                        }
                    }
                }

                launch {
                    // P0 instrument: the plugin job shares the ADDON_MS anchor so
                    // one grep captures every fan-out participant. streams is not
                    // tracked at this layer (scrapers send individually); -1 marks
                    // it as unmeasured rather than zero.
                    val pluginT0 = android.os.SystemClock.elapsedRealtime()
                    var pluginOutcome = "done"
                    try {
                        if (!hasCompatiblePlugins) return@launch

                        val tmdbId = tmdbService.ensureTmdbId(videoId, type)
                        Log.d(TAG, "Video ID: $videoId -> TMDB ID: $tmdbId (type: $type)")
                        val pluginRequest = buildPluginRequest(tmdbId, type, videoId)
                            ?: return@launch
                        val (pluginSeason, pluginEpisode) = resolvePluginSeasonEpisode(
                            videoId = videoId,
                            season = season,
                            episode = episode
                        )
                        localPluginSearchPaused
                            .transformLatest { paused ->
                                if (!paused) {
                                    streamLocalPlugins(
                                        pluginId = pluginRequest.id,
                                        mediaType = pluginRequest.mediaType,
                                        pluginSource = pluginRequest.source,
                                        season = pluginSeason,
                                        episode = pluginEpisode,
                                        resultChannel = resultChannel
                                    )
                                    emit(Unit)
                                }
                            }
                            .first()
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        pluginOutcome = "error"
                        Log.e(TAG, "Plugin execution failed: ${e.message}")
                    } finally {
                        Log.i(
                            TAG,
                            "ADDON_MS name=plugins " +
                                "ms=${android.os.SystemClock.elapsedRealtime() - pluginT0} " +
                                "streams=-1 outcome=$pluginOutcome"
                        )
                        if (completedJobs.incrementAndGet() >= totalJobs) {
                            resultChannel.close()
                        }
                    }
                }

                // Emit results as they arrive. Main review F3: greedily drain
                // whatever has already queued before annotating, so all groups
                // that arrived while the previous batch's cache check was in
                // flight share ONE checkCached call (previously one API call
                // per addon group, and the same hash returned by two addons
                // was checked twice). tryReceive never blocks, so a lone
                // arrival is annotated immediately — no added latency.
                for (result in resultChannel) {
                    val batch = mutableListOf(result)
                    while (true) {
                        val more = resultChannel.tryReceive().getOrNull() ?: break
                        batch += more
                    }
                    // P0 instrument: the debrid cache check sits between an
                    // addon's arrival and its visibility to every consumer, so
                    // its cost is inside every scrape figure. Grep anchor:
                    // CACHECHECK_MS. Memoised hashes make repeat batches cheap;
                    // this line shows the real cost per batch either way.
                    val checkT0 = android.os.SystemClock.elapsedRealtime()
                    val checkingBatch = localDebridAvailabilityService.markChecking(batch)
                    val checkedBatch = localDebridAvailabilityService.annotateCachedAvailability(checkingBatch)
                    Log.i(
                        TAG,
                        "CACHECHECK_MS ms=${android.os.SystemClock.elapsedRealtime() - checkT0} " +
                            "batch_groups=${batch.size} " +
                            "batch_streams=${batch.sumOf { it.streams.size }}"
                    )
                    val finalBatch = if (checkedBatch.size == batch.size) checkedBatch else batch
                    finalBatch.forEach { checkedResult ->
                        mergePresentedResult(accumulatedResults, checkedResult, debridSettings)
                    }
                    emit(NetworkResult.Success(accumulatedResults.toList()))
                    Log.d(TAG, "Emitted ${accumulatedResults.size} addon(s), latest batch of ${finalBatch.size}: ${finalBatch.joinToString { "${it.addonName}(${it.streams.size})" }}")
                }
            }

            Log.i(
                TAG,
                "SCRAPE_TOTAL ms=${android.os.SystemClock.elapsedRealtime() - scrapeT0} " +
                    "groups=${accumulatedResults.size} " +
                    "streams=${accumulatedResults.sumOf { it.streams.size }}"
            )

            // Emit final result (even if empty)
            if (accumulatedResults.isEmpty()) {
                val errorMessage = buildAggregateFailureMessage(
                    type = type,
                    id = videoId,
                    attemptedAddonNames = attemptedAddonNames,
                    failures = attemptedFailures.toList()
                )
                if (errorMessage != null) {
                    emit(NetworkResult.Error(errorMessage))
                } else {
                    emit(NetworkResult.Success(emptyList()))
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Failed to fetch streams: ${e.message}", e)
            emit(NetworkResult.Error(e.message ?: context.getString(com.nuvio.tv.R.string.stream_error_fetch_failed)))
        }
    }

    private fun buildSourceConfigurationKey(
        addons: List<Addon>,
        pluginsEnabled: Boolean,
        enabledScrapers: List<ScraperInfo>,
        groupPluginsByRepository: Boolean,
        pluginRepositories: List<PluginRepository>,
        debridPresentationConfiguration: String
    ): String = buildString {
        append("addons:")
        addons.forEach { addon ->
            append("|addon:").append(addon)
        }
        append("plugins:").append(pluginsEnabled)
        append("|grouped:").append(groupPluginsByRepository)
        enabledScrapers.forEach { scraper ->
            append("|scraper:").append(scraper)
        }
        if (groupPluginsByRepository) {
            pluginRepositories.forEach { repository ->
                append("|repo:").append(repository)
            }
        }
        append("|debrid:").append(debridPresentationConfiguration)
    }.sha256()

    private fun DebridSettings.withoutRawCredentials(): DebridSettings = copy(
        torboxApiKey = torboxApiKey.sha256(),
        premiumizeApiKey = premiumizeApiKey.sha256(),
        realDebridApiKey = realDebridApiKey.sha256()
    )

    private fun String.sha256(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private data class PluginRequest(
        val id: String,
        val mediaType: String,
        val source: String
    )

    private fun buildPluginRequest(tmdbId: String?, type: String, videoId: String): PluginRequest? {
        if (tmdbId != null) {
            return PluginRequest(
                id = tmdbId,
                mediaType = normalizeTmdbPluginType(type),
                source = "TMDB"
            )
        }

        if (!videoId.canRunLocalPlugins()) return null

        return PluginRequest(
            id = if (videoId.startsWith("kitsu:", ignoreCase = true)) {
                cleanKitsuPluginId(videoId)
            } else {
                videoId
            },
            mediaType = type.lowercase(),
            source = videoId.substringBefore(":").uppercase()
        )
    }

    private fun normalizeTmdbPluginType(type: String): String {
        return when (type.lowercase()) {
            "series", "tv", "show" -> "tv"
            else -> type.lowercase()
        }
    }

    private fun cleanKitsuPluginId(videoId: String): String {
        val parts = videoId.split(":")
        return if (parts.size > 2 && parts.last().toIntOrNull() != null) {
            parts.dropLast(1).joinToString(":")
        } else {
            videoId
        }
    }

    private suspend fun mergePresentedResult(
        accumulatedResults: MutableList<AddonStreams>,
        result: AddonStreams,
        debridSettings: DebridSettings
    ) {
        val existingIndex = accumulatedResults.indexOfFirst { it.addonName == result.addonName }
        if (existingIndex >= 0) {
            val existing = accumulatedResults[existingIndex]
            val merged = existing.copy(
                streams = mergeStreams(existing.streams, result.streams)
            )
            accumulatedResults[existingIndex] = presentStreams(merged, debridSettings)
        } else {
            accumulatedResults.add(presentStreams(result, debridSettings))
        }
    }

    private fun presentStreams(result: AddonStreams, debridSettings: DebridSettings): AddonStreams {
        return debridStreamPresentation.apply(
            groups = listOf(result),
            settings = debridSettings,
            includeBadgeMatches = false
        ).firstOrNull() ?: result
    }

    private fun mergeStreams(existing: List<Stream>, incoming: List<Stream>): List<Stream> {
        val streamsByKey = LinkedHashMap<String, Stream>()
        existing.forEach { stream -> streamsByKey[stream.dedupKey()] = stream }
        incoming.forEach { stream ->
            val key = stream.dedupKey()
            val prior = streamsByKey[key]
            streamsByKey[key] = if (prior == null) stream else mergeDuplicateStreams(prior, stream)
        }
        return streamsByKey.values.toList()
    }

    /**
     * Main review F8: last-write-wins dropped the richer duplicate — the same
     * torrent arriving twice (e.g. via the meta-inline fallback) could lose an
     * earlier copy's behaviorHints (filename, videoSize, bingeGroup) and its
     * already-resolved debrid cache badge. Incoming stays authoritative;
     * fields it lacks are backfilled from the prior copy.
     */
    private fun mergeDuplicateStreams(prior: Stream, incoming: Stream): Stream {
        val mergedHints = when {
            incoming.behaviorHints == null -> prior.behaviorHints
            prior.behaviorHints == null -> incoming.behaviorHints
            else -> incoming.behaviorHints.copy(
                notWebReady = incoming.behaviorHints.notWebReady ?: prior.behaviorHints.notWebReady,
                bingeGroup = incoming.behaviorHints.bingeGroup ?: prior.behaviorHints.bingeGroup,
                countryWhitelist = incoming.behaviorHints.countryWhitelist ?: prior.behaviorHints.countryWhitelist,
                proxyHeaders = incoming.behaviorHints.proxyHeaders ?: prior.behaviorHints.proxyHeaders,
                videoHash = incoming.behaviorHints.videoHash ?: prior.behaviorHints.videoHash,
                videoSize = incoming.behaviorHints.videoSize ?: prior.behaviorHints.videoSize,
                filename = incoming.behaviorHints.filename ?: prior.behaviorHints.filename
            )
        }
        // A resolved cache state beats an unresolved one regardless of arrival order.
        val priorCacheResolved = prior.debridCacheStatus?.state in FINAL_DEBRID_STATES
        val incomingCacheResolved = incoming.debridCacheStatus?.state in FINAL_DEBRID_STATES
        val mergedCacheStatus = when {
            incomingCacheResolved -> incoming.debridCacheStatus
            priorCacheResolved -> prior.debridCacheStatus
            else -> incoming.debridCacheStatus ?: prior.debridCacheStatus
        }
        return incoming.copy(
            title = incoming.title ?: prior.title,
            description = incoming.description ?: prior.description,
            fileIdx = incoming.fileIdx ?: prior.fileIdx,
            behaviorHints = mergedHints,
            sources = incoming.sources ?: prior.sources,
            quality = incoming.quality ?: prior.quality,
            qualityValue = if (incoming.qualityValue >= 0) incoming.qualityValue else prior.qualityValue,
            clientResolve = incoming.clientResolve ?: prior.clientResolve,
            debridCacheStatus = mergedCacheStatus,
            badges = incoming.badges.ifEmpty { prior.badges }
        )
    }

    /**
     * Stream local plugin results - each scraper sends results individually
     */
    private fun String.canRunLocalPlugins(): Boolean {
        return startsWith("kitsu:", ignoreCase = true) ||
            startsWith("anilist:", ignoreCase = true) ||
            startsWith("mal:", ignoreCase = true)
    }

    private suspend fun streamLocalPlugins(
        pluginId: String,
        mediaType: String,
        pluginSource: String,
        season: Int?,
        episode: Int?,
        resultChannel: Channel<AddonStreams>
    ) {
        // Check if plugins are enabled
        if (!pluginManager.pluginsEnabled.first()) {
            Log.d(TAG, "Plugins are disabled")
            return
        }

        Log.d(TAG, "Streaming plugins for $pluginSource: $pluginId, type: $mediaType")

        try {
            val groupByRepository = pluginManager.groupStreamsByRepository.first()
            val repositoriesById = if (groupByRepository) {
                pluginManager.repositories.first().associateBy { it.id }
            } else {
                emptyMap()
            }

            // Collect streaming results from each scraper
            pluginManager.executeScrapersStreaming(
                tmdbId = pluginId,
                mediaType = mediaType,
                season = season,
                episode = episode
            ).collect { (scraper, results) ->
                if (results.isNotEmpty()) {
                    val addonName = scraper.pluginAddonName(groupByRepository, repositoriesById)
                    val addonStreams = AddonStreams(
                        addonName = addonName,
                        addonLogo = null,
                        streams = results.map { result -> result.toPluginStream(scraper, addonName) }
                    )
                    resultChannel.send(addonStreams)
                    Log.d(TAG, "Streamed ${results.size} results from ${scraper.name}")
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Failed to stream plugins: ${e.message}", e)
        }
    }

    private fun ScraperInfo.pluginAddonName(
        groupByRepository: Boolean,
        repositoriesById: Map<String, PluginRepository>
    ): String {
        if (!groupByRepository) return name
        return repositoriesById[repositoryId]?.name?.takeIf { it.isNotBlank() } ?: name
    }

    private fun LocalScraperResult.toPluginStream(scraper: ScraperInfo, addonName: String): Stream {
        val baseTitle = title.takeIf { it.isNotBlank() }
        val baseName = name?.takeIf { it.isNotBlank() }
        val quality = quality?.takeIf { it.isNotBlank() }
        val qualityLabel = quality ?: context.getString(com.nuvio.tv.R.string.stream_quality_unknown)
        val displayName = buildString {
            append(baseName ?: baseTitle ?: scraper.name)
            if (!toString().contains(qualityLabel)) {
                append(" - ").append(qualityLabel)
            }
        }.takeIf { it.isNotBlank() }
        val displayTitle = (baseTitle ?: baseName ?: scraper.name).takeIf { it.isNotBlank() }

        return Stream(
            name = displayName,
            title = displayTitle,
            url = url,
            addonName = addonName,
            addonLogo = null,
            description = buildDescription(this),
            behaviorHints = headers?.let { headers ->
                StreamBehaviorHints(
                    notWebReady = null,
                    bingeGroup = null,
                    countryWhitelist = null,
                    proxyHeaders = ProxyHeaders(request = headers, response = null)
                )
            },
            infoHash = infoHash,
            fileIdx = null,
            ytId = null,
            externalUrl = null,
            quality = quality,
            qualityValue = parseQualityValue(quality),
            subtitles = subtitles
        )
    }

    private fun Stream.dedupKey(): String =
        infoHash?.lowercase()?.let { hash -> "$hash:${fileIdx ?: ""}" }
            ?: clientResolve?.infoHash?.lowercase()?.let { hash -> "$hash:${clientResolve.fileIdx}" }
            ?: url
            ?: externalUrl
            ?: ytId
            ?: "${addonName}:${name}:${title}"

    /**
     * Build a description string from scraper result
     */
    private fun buildDescription(result: com.nuvio.tv.domain.model.LocalScraperResult): String? {
        // Quality is shown in the stream name — only show size/language in description
        val parts = mutableListOf<String>()
        result.size?.let { parts.add(it) }
        result.language?.let { parts.add(it) }
        return if (parts.isNotEmpty()) parts.joinToString(" • ") else null
    }

    private fun parseQualityValue(quality: String?): Int {
        if (quality == null) return -1
        val lower = quality.lowercase()
        return when {
            lower.contains("4k") || lower.contains("2160") -> 2160
            lower.contains("1080") -> 1080
            lower.contains("800") -> 800
            lower.contains("720") -> 720
            lower.contains("480") -> 480
            lower.contains("360") -> 360
            else -> -1
        }
    }

    override suspend fun getStreamsFromAddon(
        addon: Addon,
        type: String,
        videoId: String
    ): NetworkResult<List<Stream>> {
        val cleanBaseUrl = addon.baseUrl.trimEnd('/')
        val queryStart = cleanBaseUrl.indexOf('?')
        val basePath = if (queryStart >= 0) cleanBaseUrl.substring(0, queryStart).trimEnd('/') else cleanBaseUrl
        val baseQuery = if (queryStart >= 0) cleanBaseUrl.substring(queryStart) else ""
        val encodedType = encodePathSegment(type)
        val encodedVideoId = encodePathSegment(videoId)
        val streamUrl = "$basePath/stream/$encodedType/$encodedVideoId.json$baseQuery"
        Log.d(TAG, "Fetching streams type=$type videoId=$videoId url=$streamUrl")

        // Display info comes from the installed addon the caller already holds. Calling
        // addonRepository.fetchAddon() here caused an unconditional manifest GET ahead of every
        // queried addon's stream request: fetchAddon is the low-level fetch that does not consult
        // the cache, so this sidestepped the manifest-cache policy in AddonRepositoryImpl.
        val resolvedAddonName = addon.displayName
        val addonLogo = addon.logo

        return when (val result = safeAddonApiCall(context) { api.getStreams(streamUrl) }) {
            is NetworkResult.Success -> {
                val streams = result.data.streams?.map { 
                    it.toDomain(resolvedAddonName, addonLogo) 
                } ?: emptyList()
                Log.d(TAG, "Streams success addon=$resolvedAddonName count=${streams.size} url=$streamUrl")
                NetworkResult.Success(streams)
            }
            is NetworkResult.Error -> {
                Log.w(
                    TAG,
                    "Streams failed addon=$resolvedAddonName code=${result.code} message=${result.message} url=$streamUrl"
                )
                result
            }
            NetworkResult.Loading -> NetworkResult.Loading
        }
    }

    /**
     * Check if addon supports stream resource for the given type and video id.
     * Respects the resource-level idPrefixes declared in the addon manifest,
     * falling back to the top-level addon idPrefixes if the resource doesn't
     * declare its own.
     */
    private fun Addon.supportsStreamResource(type: String, videoId: String): Boolean {
        return resources.any { resource ->
            resource.name == "stream" &&
            (resource.types.isEmpty() || resource.types.contains(type)) &&
            run {
                val prefixes = resource.idPrefixes?.takeIf { it.isNotEmpty() }
                    ?: idPrefixes.takeIf { it.isNotEmpty() }
                prefixes == null || prefixes.any { prefix -> videoId.startsWith(prefix) }
            }
        }
    }

    /**
     * Fetch meta for the given content and extract inline streams from the
     * matching video entry.  Returns an empty list when the addon doesn't
     * support meta or the video has no inline streams.
     */
    private suspend fun fetchInlineStreamsFromMeta(
        addon: Addon,
        type: String,
        videoId: String
    ): List<Stream> {
        // For inline streams the meta is fetched using the content-level ID
        // (everything before the video-specific suffix).  For "other" type
        // the videoId IS the content ID; for series it is contentId:S:E.
        // Video ID formats:
        //   tt1234567:1:5      → metaId = tt1234567
        //   mal:63375:1:5      → metaId = mal:63375
        //   kitsu:12345:2      → metaId = kitsu:12345
        // Strategy: drop up to 2 trailing numeric segments (season, episode)
        // but never reduce below 2 segments for prefixed IDs (mal:X, kitsu:X).
        val metaId = run {
            val parts = videoId.split(":")
            if (parts.size <= 1) return@run videoId
            // Count trailing numeric segments
            val trailingNumericCount = parts.reversed().takeWhile { it.toIntOrNull() != null }.size
            // Keep at least 2 segments for prefixed IDs (e.g. "mal:63375"),
            // or 1 segment for IMDB-style IDs (e.g. "tt1234567")
            val firstSegment = parts.first()
            val minSegments = if (firstSegment.startsWith("tt") || firstSegment.toIntOrNull() != null) 1 else 2
            val segmentsToDrop = trailingNumericCount.coerceAtMost((parts.size - minSegments).coerceAtLeast(0))
            if (segmentsToDrop > 0) {
                parts.dropLast(segmentsToDrop).joinToString(":")
            } else {
                videoId
            }
        }
        val cleanBaseUrl = addon.baseUrl.trimEnd('/')
        val queryStart = cleanBaseUrl.indexOf('?')
        val basePath = if (queryStart >= 0) cleanBaseUrl.substring(0, queryStart).trimEnd('/') else cleanBaseUrl
        val baseQuery = if (queryStart >= 0) cleanBaseUrl.substring(queryStart) else ""
        val encodedType = encodePathSegment(type)
        val encodedMetaId = encodePathSegment(metaId)
        val metaUrl = "$basePath/meta/$encodedType/$encodedMetaId.json$baseQuery"
        Log.d(TAG, "Fetching inline streams via meta type=$type metaId=$metaId videoId=$videoId url=$metaUrl")
        return try {
            when (val result = safeAddonApiCall(context) { api.getMeta(metaUrl) }) {
                is NetworkResult.Success -> {
                    val metaDto = result.data.meta ?: return emptyList()
                    val matchingVideo = metaDto.videos?.firstOrNull { it.id == videoId }
                    val streams = matchingVideo?.streams
                        ?.mapNotNull { it.toDomain(addon.displayName, addon.logo) }
                        ?: emptyList()
                    Log.d(TAG, "Inline streams from meta: addon=${addon.displayName} videoId=$videoId found=${streams.size}")
                    streams
                }
                else -> emptyList()
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Failed to fetch inline streams from meta for ${addon.displayName}: ${e.message}")
            emptyList()
        }
    }

    private fun buildMissingStreamFailure(addon: Addon): StreamAttemptFailure {
        return StreamAttemptFailure(
            addonName = addon.displayName,
            kind = StreamFailureKind.MISSING,
            detail = context.getString(com.nuvio.tv.R.string.stream_error_detail_no_streams_for_id)
        )
    }

    private fun buildAddonFailure(addon: Addon, error: NetworkResult.Error): StreamAttemptFailure {
        if (error.code == 404 || error.message.equals("Not Found", ignoreCase = true)) {
            return buildMissingStreamFailure(addon)
        }
        val normalizedReason = when {
            error.message.contains("Unable to resolve host", ignoreCase = true) ->
                context.getString(com.nuvio.tv.R.string.stream_error_detail_addon_unreachable)
            error.message.contains("Failed to connect", ignoreCase = true) ->
                context.getString(com.nuvio.tv.R.string.stream_error_detail_addon_connection_failed)
            error.message.contains("timeout", ignoreCase = true) ->
                context.getString(com.nuvio.tv.R.string.stream_error_detail_addon_timeout)
            error.message.contains("CLEARTEXT communication", ignoreCase = true) ->
                context.getString(com.nuvio.tv.R.string.stream_error_detail_addon_cleartext_blocked)
            error.message.isBlank() ->
                context.getString(com.nuvio.tv.R.string.stream_error_detail_addon_request_failed)
            else -> error.message.replaceFirstChar { char ->
                if (char.isLowerCase()) char.titlecase() else char.toString()
            }
        }
        val httpSuffix = error.code?.let { " (HTTP $it)" } ?: ""
        return StreamAttemptFailure(
            addonName = addon.displayName,
            kind = StreamFailureKind.REQUEST_FAILED,
            detail = "$normalizedReason$httpSuffix"
        )
    }

    private fun buildAggregateFailureMessage(
        type: String,
        id: String,
        attemptedAddonNames: List<String>,
        failures: List<StreamAttemptFailure>
    ): String? {
        if (attemptedAddonNames.isEmpty()) {
            return context.getString(R.string.error_stream_no_supported_addon, type)
        }

        val triedAddons = attemptedAddonNames.joinToString(", ")
        val missingOnly = failures.isNotEmpty() && failures.all { it.kind == StreamFailureKind.MISSING }
        if (failures.isEmpty() || missingOnly) {
            return context.getString(R.string.error_stream_tried_none, triedAddons, id, type)
        }

        val issueSummary = failures
            .filter { it.kind == StreamFailureKind.REQUEST_FAILED }
            .distinctBy { it.addonName to it.detail }
            .take(3)
            .joinToString("; ") { "${it.addonName}: ${it.detail}" }

        return if (issueSummary.isBlank()) {
            context.getString(R.string.error_stream_tried_generic, triedAddons, id, type)
        } else {
            context.getString(R.string.error_stream_tried_issues, triedAddons, id, type, issueSummary)
        }
    }

    private fun encodePathSegment(value: String): String {
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }
}

private val FINAL_DEBRID_STATES = setOf(
    com.nuvio.tv.domain.model.StreamDebridCacheState.CACHED,
    com.nuvio.tv.domain.model.StreamDebridCacheState.NOT_CACHED
)
