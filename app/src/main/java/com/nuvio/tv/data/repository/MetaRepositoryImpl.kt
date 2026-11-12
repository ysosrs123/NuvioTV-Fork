package com.nuvio.tv.data.repository

import android.content.Context
import android.util.Log
import com.nuvio.tv.core.network.ADDON_REQUEST_TIMEOUT_MS
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.player.PlayerSessionTracker
import com.nuvio.tv.core.logging.redactLinksForLog
import com.nuvio.tv.data.mapper.toDomain
import com.nuvio.tv.data.mediaserver.ServerCatalog
import com.nuvio.tv.data.mediaserver.ServerItemRef
import com.nuvio.tv.data.mediaserver.messageRes
import com.nuvio.tv.data.mediaserver.serverFailure
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.AddonResource
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.core.health.AddonHealthModel
import com.nuvio.tv.core.health.AddonHealthStore
import com.nuvio.tv.core.health.HealthOutcome
import com.nuvio.tv.core.util.canonicalizeAddonUrl
import kotlinx.coroutines.launch
import com.nuvio.tv.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.CacheControl
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MetaRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: AddonApi,
    private val addonRepository: AddonRepository,
    private val healthStore: AddonHealthStore,
    private val serverCatalog: ServerCatalog
) : MetaRepository {
    companion object {
        private const val TAG = "MetaRepository"
        /** Default TTL when addon response has no Cache-Control header (6 hours). */
        private const val DEFAULT_TTL_MS = 6L * 60 * 60 * 1000
        /** Minimum TTL for meta responses even when server says no-cache/no-store (5 minutes).
         *  Prevents excessive re-fetching on every details screen visit for addons
         *  that don't set meaningful Cache-Control headers. */
        private const val MIN_META_TTL_MS = 5L * 60 * 1000
        private const val SERVER_META_TTL_MS = 60_000L
        /**
         * How long a meta lookup waits for the installed addon list to populate.
         *
         * [AddonRepository.getInstalledAddons] is a StateFlow seeded with an empty
         * list, so a cold start reads the seed rather than the addons. Cached
         * manifests make that a few milliseconds. A first-ever launch has nothing
         * cached and waits on live fetches, usually longer than this, so that one
         * start falls back to the requested type and pays the wait as well.
         *
         * Without the wait a lookup resolves against an empty list and reports
         * "no addon supports this" for content that is fetchable a second later.
         * [getMeta] checks its cache first, so a hit never waits.
         */
        private const val INSTALLED_ADDONS_WAIT_MS = 750L
    }

    /** [httpCode] is null when no answer came back. */
    private data class SingleMetaFetch(val meta: Meta?, val httpCode: Int?)

    /** Internal result type for the deferred meta lookup to distinguish
     *  "fetched meta", "nothing found", and "source addon already provides this data". */
    private sealed class MetaLookupResult {
        data class Found(val meta: Meta) : MetaLookupResult()
        /** Carries what was tried, so every caller can report it. The loop runs in a
         *  shared Deferred, so attempts held in one caller's flow would leave the
         *  others reporting nothing. */
        data class NotFound(
            val attemptedAddonNames: List<String> = emptyList(),
            val failures: List<MetaAttemptFailure> = emptyList(),
            /**
             * Every candidate was attempted and every one of them answered "no such item". Set by
             * the lookup loop, which is the only place that knows how many candidates there were,
             * rather than inferred from [failures] by a caller.
             */
            val allAttemptsMissing: Boolean = false
        ) : MetaLookupResult()
        /** The first viable candidate is the same addon that served the catalog,
         *  so the item already has its meta — no request needed. */
        data object SourceSufficient : MetaLookupResult()
    }

    private enum class MetaFailureKind {
        MISSING,
        REQUEST_FAILED
    }

    private data class MetaAttemptFailure(
        val addonName: String,
        val kind: MetaFailureKind,
        val detail: String
    )

    /** Wrapper for cached meta with an expiration timestamp. */
    private data class CachedMeta(
        val meta: Meta,
        val expiresAtMs: Long
    ) {
        fun isExpired(): Boolean = System.currentTimeMillis() >= expiresAtMs
    }

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // In-memory cache: "addonBaseUrl|type:id" -> CachedMeta. Keyed per addon so
    // two addons serving meta for the same content id never overwrite each other.
    // Two TTL layers compose here:
    //  - each entry carries the addon-declared Cache-Control
    //    max-age (CachedMeta.isExpired), so fast-changing meta refreshes without
    //    an app restart;
    //  - the cache itself is a bounded, access-ordered
    //    LRU whose 45-min internal TTL caps any longer declaration -- a series
    //    Meta is heavy (full episode list, cast, links) and an unbounded cache
    //    grows for the whole session, competing with the player's
    //    buffer for RAM on low tiers. The cap also lets a series pick up
    //    newly-aired episodes within a session.
    // Net per-entry lifetime: min(declared max-age or the 24 h default, 45 min).
    private val metaCache = BoundedMetaCache<String, CachedMeta>()
    // Separate cache for full meta fetched from addons (bypasses catalog-level cache)
    private val addonMetaCache = BoundedMetaCache<String, CachedMeta>()
    private val primaryAddonMetaCache = BoundedMetaCache<String, CachedMeta>()
    private val serverMetaCache = BoundedMetaCache<String, CachedMeta>(maxEntries = 32)
    // Negative cache: without it a genuine miss re-runs the full
    // candidate loop on every detail-screen visit / enrichment for that title.
    // The value is the addon set the miss was found with; a different set asks again.
    private val metaMissCache = BoundedMetaCache<String, String>(
        maxEntries = 300, ttlMs = 5 * 60 * 1000L
    )

    /**
     * Minimal thread-safe LRU with per-entry TTL. Access-ordered so hot titles
     * stay resident; stale entries are dropped on read.
     */
    private class BoundedMetaCache<K : Any, V : Any>(
        private val maxEntries: Int = 150,
        private val ttlMs: Long = 45 * 60 * 1000L
    ) {
        private class Entry<V>(val value: V, val storedAtMs: Long)

        private val map = object : LinkedHashMap<K, Entry<V>>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Entry<V>>): Boolean {
                return size > maxEntries
            }
        }

        @Synchronized
        operator fun get(key: K): V? {
            val entry = map[key] ?: return null
            if (System.currentTimeMillis() - entry.storedAtMs > ttlMs) {
                map.remove(key)
                return null
            }
            return entry.value
        }

        @Synchronized
        operator fun set(key: K, value: V) {
            map[key] = Entry(value, System.currentTimeMillis())
        }

        @Synchronized
        fun remove(key: K) {
            map.remove(key)
        }

        @Synchronized
        fun clear() {
            map.clear()
        }
    }

    // In-flight deduplication: prevents concurrent coroutines from firing duplicate requests
    private val inFlightMeta = ConcurrentHashMap<String, Deferred<SingleMetaFetch?>>()
    private val inFlightAddonMeta = ConcurrentHashMap<String, Deferred<MetaLookupResult>>()
    private val inFlightPrimaryMeta = ConcurrentHashMap<String, Deferred<Meta?>>()

    internal val rateLimits = AddonRateLimits()
    internal val metaMisses = AddonMetaMisses()
    private val requestStats = MetaRequestStats()

    private suspend fun currentCaller(): String = currentCoroutineContext()[MetaCaller]?.name ?: "other"

    // Shared lookups that a caller other than Continue Watching has joined.
    private val joinedLookups = MutableStateFlow<Set<Job>>(emptySet())

    private fun noteJoined(lookup: Deferred<*>, caller: String) {
        if (isBackgroundMetaCaller(caller) || !lookup.isActive) return
        joinedLookups.update { it + lookup }
        lookup.invokeOnCompletion { joinedLookups.update { set -> set - lookup } }
    }

    /** Before each attempt, a Continue Watching lookup waits while a film plays, unless someone else needs it. */
    private suspend fun holdWhilePlayerOpen(caller: String) {
        if (!isBackgroundMetaCaller(caller)) return
        val lookup = currentCoroutineContext()[Job]
        combine(PlayerSessionTracker.isSessionOpen, joinedLookups) { open, joined ->
            !open || lookup in joined
        }.first { it }
    }

    private fun noteRequest(caller: String, addonName: String) {
        if (requestStats.request(caller, addonName)) scheduleRequestSummary()
    }

    private fun noteSkipped(caller: String, addonName: String) {
        if (requestStats.skipped(caller, addonName)) scheduleRequestSummary()
    }

    private fun scheduleRequestSummary() {
        val windowMs = requestStats.currentWindowMs()
        repositoryScope.launch {
            delay(windowMs)
            requestStats.drain()?.let { Log.i(TAG, it) }
        }
    }

    private fun noteResponse(host: String, addonName: String, code: Int, retryAfter: String?) {
        if (code != 429) {
            rateLimits.recordSuccess(host)
            return
        }
        requestStats.rateLimited(addonName)
        val wasPaused = rateLimits.isPaused(host)
        val pauseMs = rateLimits.recordRateLimited(host, retryAfter)
        if (!wasPaused) {
            Log.w(TAG, "Meta rate limited host=$host addon=$addonName retryAfter=${retryAfter ?: "-"} pausing ${pauseMs}ms")
        }
    }

    /** The answer's meta, or null when it is an addon's error item, which gives the host a short pause for background lookups. */
    private fun usableMeta(dto: com.nuvio.tv.data.remote.dto.MetaDto?, host: String, addonName: String) =
        dto?.takeUnless { isAddonErrorMetaId(it.id) } ?: run {
            if (dto != null) {
                val pauseMs = rateLimits.recordErrorItem(host)
                Log.w(TAG, "Meta answer is an addon error item host=$host addon=$addonName name=${dto.name} pausing ${pauseMs}ms")
            }
            null
        }

    private fun pausedHostFailure(addon: Addon, host: String) = MetaAttemptFailure(
        addonName = addon.displayName,
        kind = MetaFailureKind.REQUEST_FAILED,
        detail = if (rateLimits.isRateLimited(host)) "HTTP 429"
        else context.getString(R.string.meta_error_detail_addon_request_failed)
    )

    /** Ids an addon never claimed count apart, so they can't stop it being asked for the ones it does. */
    private fun Addon.missKey(id: String): String =
        normalizedAddonKey(baseUrl).let { if (supportsMetaId(id)) it else "$it|unclaimed" }

    private fun noteMetaMissing(addon: Addon, type: String, id: String, fromTitlePage: Boolean) {
        Log.d(TAG, "Meta not found addonId=${addon.id} type=$type id=$id")
        if (fromTitlePage) return
        if (metaMisses.recordMiss(addon.missKey(id))) {
            Log.w(TAG, "Meta: ${addon.name} answered not found for several titles in a row, not asking it for a while")
        }
    }

    override fun getMeta(
        addonBaseUrl: String,
        type: String,
        id: String
    ): Flow<NetworkResult<Meta>> = flow {
        ServerItemRef.parse(id)?.let { ref ->
            emitAll(serverMeta(ref))
            return@flow
        }
        if (ServerCatalog.isServerAddon(addonBaseUrl)) {
            emitAll(getMetaFromAllAddons(type, id))
            return@flow
        }
        val requestedType = type.trim()
        val inferredType = inferCanonicalType(requestedType, id)

        // supportedCandidateType only ever returns one of these two, so every
        // metaCache entry written for this addon and title sits under one of two
        // keys. Check both, in the order it would pick them, before resolving
        // the addon, so a cache hit skips the cold-start wait below. Only metaCache
        // needs this: addonMetaCache is already canonical via metaLookupCacheKey.
        val probeTypes = listOf(requestedType, inferredType)
            .filter { it.isNotBlank() }
            .distinct()
        probeTypes.forEach { probeType ->
            val probeKey = addonMetaCacheKey(addonBaseUrl, probeType, id)
            metaCache[probeKey]?.let { cached ->
                if (!cached.isExpired()) {
                    emit(NetworkResult.Success(cached.meta))
                    return@flow
                }
                metaCache.remove(probeKey)
            }
        }

        // The caller names the addon but not necessarily a type it serves. Nuvio's
        // internal "tv" against a series-only addon is the usual case. Resolve it
        // like the multi-addon path does, so the request and the cached entry line
        // up with the other paths.
        val addon = findAddonByBaseUrl(addonBaseUrl)
        val advertisedType = addon?.supportedCandidateType(requestedType, inferredType)
        if (advertisedType == null) {
            // Both fall back to the requested type, for different reasons. Log which,
            // so a request expected to fail is not confused with an unknown addon.
            if (addon == null) {
                Log.w(
                    TAG,
                    "Addon unresolved (not installed, disabled, or URL not matched), " +
                        "requesting as-is url=${addonBaseUrl.redactLinksForLog()} type=$requestedType id=$id"
                )
            } else {
                Log.w(
                    TAG,
                    "Addon advertises neither type, requesting as-is " +
                        "addonId=${addon.id} requested=$requestedType inferred=$inferredType id=$id"
                )
            }
        }
        val effectiveType = advertisedType ?: requestedType

        val cacheKey = addonMetaCacheKey(addonBaseUrl, effectiveType, id)

        emit(NetworkResult.Loading)

        val healthT0 = android.os.SystemClock.elapsedRealtime()
        val url = buildMetaUrl(addonBaseUrl, effectiveType, id)
        val caller = currentCaller()
        val host = addonHost(addonBaseUrl)
        val addonName = addon?.name ?: host
        val deferred = inFlightMeta.getOrPut(cacheKey) {
            repositoryScope.async {
                try {
                    if (rateLimits.isPaused(host)) {
                        noteSkipped(caller, addonName)
                        return@async null
                    }
                    noteRequest(caller, addonName)
                    // Fork: keep the 20 s addon deadline (SafeApiCall.kt) that a raw
                    // call would drop -- without it a dead addon holds this
                    // coroutine for the shared client's full 60 s read timeout.
                    val response = withTimeoutOrNull(ADDON_REQUEST_TIMEOUT_MS) { api.getMeta(url) }
                        ?: throw java.io.IOException(context.getString(R.string.network_error_addon_timeout))
                    noteResponse(host, addonName, response.code(), response.headers()["Retry-After"])
                    if (response.isSuccessful) {
                        val metaDto = usableMeta(response.body()?.meta, host, addonName)
                            ?: return@async SingleMetaFetch(null, response.code())
                        val meta = metaDto.toDomain(context.getString(R.string.episodes_episode))
                        val ttlMs = parseMaxAgeMs(response.headers()["Cache-Control"])
                        val cached = CachedMeta(meta, System.currentTimeMillis() + ttlMs)
                        metaCache[cacheKey] = cached
                        addonMetaCache[metaLookupCacheKey(requestedType, id)] = cached
                        SingleMetaFetch(meta, response.code())
                    } else {
                        SingleMetaFetch(null, response.code())
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "getMeta failed for $url: ${e.message}".redactLinksForLog())
                    SingleMetaFetch(null, null)
                } finally {
                    inFlightMeta.remove(cacheKey)
                }
            }
        }

        val fetch = deferred.await()
        val meta = fetch?.meta
        if (fetch != null) {
            val healthMs = android.os.SystemClock.elapsedRealtime() - healthT0
            recordAddonHealth(
                addonBaseUrl,
                AddonHealthModel.metaOutcome(fetch.httpCode, hasMeta = meta != null),
                healthMs
            )
        }
        if (meta != null) {
            emit(NetworkResult.Success(meta))
        } else {
            emit(NetworkResult.Error(context.getString(R.string.error_meta_not_found)))
        }
    }

    override fun getMetaFromAllAddons(
        type: String,
        id: String,
        sourceAddonBaseUrl: String?
    ): Flow<NetworkResult<Meta>> = flow {
        ServerItemRef.parse(id)?.let { ref ->
            emitAll(serverMeta(ref))
            return@flow
        }
        val cacheKey = metaLookupCacheKey(type, id)
        addonMetaCache[cacheKey]?.let { cached ->
            if (!cached.isExpired()) {
                emit(NetworkResult.Success(cached.meta))
                return@flow
            }
            addonMetaCache.remove(cacheKey)
        }
        // Fresh negative result for the addons there right now: answer without waiting for them.
        val currentSet = addonSetKey(addonRepository.getInstalledAddons().first().enabledAddons())
        if (metaMissCache[cacheKey] == currentSet) {
            emit(
                NetworkResult.Error(
                    context.getString(R.string.error_meta_not_found),
                    if (currentSet.isEmpty()) null else NetworkResult.META_NOT_FOUND_CODE
                )
            )
            return@flow
        }
        val addons = installedAddonsOrEmpty()
        val addonSet = addonSetKey(addons)
        val fromTitlePage = currentCaller() == TITLE_PAGE_META_CALLER
        val flightKey = "$cacheKey|$addonSet" + if (fromTitlePage) "|$TITLE_PAGE_META_CALLER" else ""

        // Joining a lookup that is already running shares its outcome, failures included.
        // Running the loop again after a shared failure doubled the requests to an addon that
        // was already struggling.
        inFlightAddonMeta[flightKey]?.let { existingDeferred ->
            noteJoined(existingDeferred, currentCaller())
            emit(lookupOutcome(existingDeferred.await(), type.trim(), id))
            return@flow
        }

        emit(NetworkResult.Loading)
        val caller = currentCaller()

        val requestedType = type.trim()
        val inferredType = inferCanonicalType(requestedType, id)
        val attemptedFailures = mutableListOf<MetaAttemptFailure>()
        val attemptedAddonNames = linkedSetOf<String>()
        val metaResourceAddons = addons.filter { addon ->
            addon.resources.any { it.name == "meta" }
        }

        // Priority order:
        // 1) addons that explicitly support requested type AND support the ID prefix
        // 2) addons that support inferred canonical type AND support the ID prefix
        // 3) addons that support the type but have no idPrefixes (accept all IDs)
        // 4) top addon in installed order that exposes meta resource
        val prioritizedCandidates = linkedSetOf<Pair<Addon, String>>()
        // First pass: addons that explicitly match type AND id prefix
        addons.forEach { addon ->
            if (addon.supportsMetaType(requestedType) && addon.supportsMetaId(id)) {
                prioritizedCandidates.add(addon to requestedType)
            }
        }
        if (!inferredType.equals(requestedType, ignoreCase = true)) {
            addons.forEach { addon ->
                if (addon.supportsMetaType(inferredType) && addon.supportsMetaId(id)) {
                    prioritizedCandidates.add(addon to inferredType)
                }
            }
        }
        metaResourceAddons.firstOrNull { it.supportsMetaId(id) }?.let { topMetaAddon ->
            topMetaAddon.supportedCandidateType(requestedType, inferredType)?.let { fallbackType ->
                prioritizedCandidates.add(topMetaAddon to fallbackType)
            }
        }
        // Fallback: if no ID-matching addons found, include addons without idPrefixes
        if (prioritizedCandidates.isEmpty()) {
            addons.forEach { addon ->
                if (addon.supportsMetaType(requestedType) && addon.idPrefixes.isEmpty()) {
                    prioritizedCandidates.add(addon to requestedType)
                }
            }
            metaResourceAddons.firstOrNull { it.idPrefixes.isEmpty() }?.let { topMetaAddon ->
                topMetaAddon.supportedCandidateType(requestedType, inferredType)?.let { fallbackType ->
                    prioritizedCandidates.add(topMetaAddon to fallbackType)
                }
            }
        }

        if (prioritizedCandidates.isEmpty()) {
            // Last resort, in this order: addons with meta whose idPrefixes match, addons with a catalog of this
            // type (a catalog addon can answer meta for its own titles without declaring it, as Bingecat does),
            // then addons whose meta is declared for other ids only. One answering not found title after title
            // rests on its own, like in the main loop: an AIOStreams config that declares meta for its debrid ids
            // only sent hundreds of 404s when it was asked for every title.
            fun Addon.hasMeta() = resources.any { it.name == "meta" }
            fun Addon.listsType() = rawTypes.any { it.equals(requestedType, ignoreCase = true) } ||
                catalogs.any { it.rawType.equals(requestedType, ignoreCase = true) }
            val fallbackAddons = addons.filter { it.listsType() && (it.hasMeta() || it.catalogs.isNotEmpty()) }
                .sortedBy { addon ->
                    when {
                        addon.hasMeta() && addon.supportsMetaId(id) -> 0
                        addon.catalogs.any { it.rawType.equals(requestedType, ignoreCase = true) } -> 1
                        else -> 2
                    }
                }
            var skippedResting = false

            for (addon in fallbackAddons) {
                attemptedAddonNames += addon.displayName
                if (!fromTitlePage && metaMisses.isResting(addon.missKey(id))) {
                    noteSkipped(caller, addon.name)
                    attemptedFailures += buildMissingMetaFailure(addon)
                    skippedResting = true
                    continue
                }
                val host = addonHost(addon.baseUrl)
                if (!fromTitlePage && rateLimits.isPaused(host)) {
                    noteSkipped(caller, addon.name)
                    attemptedFailures += pausedHostFailure(addon, host)
                    continue
                }
                val url = buildMetaUrl(addon.baseUrl, requestedType, id)
                noteRequest(caller, addon.name)
                try {
                    // Fork: keep the 20 s addon deadline (SafeApiCall.kt); a
                    // timeout surfaces through the catch below like any other
                    // request failure.
                    val response = withTimeoutOrNull(ADDON_REQUEST_TIMEOUT_MS) { api.getMeta(url) }
                        ?: throw java.io.IOException(context.getString(R.string.network_error_addon_timeout))
                    noteResponse(host, addon.name, response.code(), response.headers()["Retry-After"])
                    if (response.isSuccessful) {
                        val metaDto = usableMeta(response.body()?.meta, host, addon.name)
                        if (metaDto != null) {
                            if (!fromTitlePage) metaMisses.recordFound(addon.missKey(id))
                            val episodeLabel = context.getString(R.string.episodes_episode)
                            val meta = kotlinx.coroutines.withContext(Dispatchers.Default) { metaDto.toDomain(episodeLabel) }
                            val ttlMs = parseMaxAgeMs(response.headers()["Cache-Control"])
                            val cached = CachedMeta(meta, System.currentTimeMillis() + ttlMs)
                            addonMetaCache[cacheKey] = cached
                            metaCache[addonMetaCacheKey(addon.baseUrl, requestedType, id)] = cached
                            emit(NetworkResult.Success(meta))
                            return@flow
                        } else {
                            attemptedFailures += buildMissingMetaFailure(addon)
                            if (response.body()?.meta == null) noteMetaMissing(addon, requestedType, id, fromTitlePage)
                        }
                    } else {
                        attemptedFailures += MetaAttemptFailure(
                            addonName = addon.displayName,
                            kind = if (response.code() == 404) MetaFailureKind.MISSING else MetaFailureKind.REQUEST_FAILED,
                            detail = response.message() ?: "HTTP ${response.code()}"
                        )
                        if (response.code() == 404) noteMetaMissing(addon, requestedType, id, fromTitlePage)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    attemptedFailures += MetaAttemptFailure(
                        addonName = addon.displayName,
                        kind = MetaFailureKind.REQUEST_FAILED,
                        detail = e.message ?: context.getString(R.string.network_error_unknown)
                    )
                }
            }

            val fallbackMessage = if (fallbackAddons.isEmpty()) {
                context.getString(R.string.error_meta_no_supported_addon, requestedType)
            } else {
                buildAggregateFailureMessage(
                    type = requestedType,
                    id = id,
                    attemptedAddonNames = attemptedAddonNames.toList(),
                    failures = attemptedFailures
                )
            }
            // Classified like the main path below. No addon declaring the type is final, and so is
            // every legacy candidate answering "no such item"; only a request failure is retryable.
            // With no addons loaded yet (start-up, profile switch) nothing is final.
            val fallbackFinal = addons.isNotEmpty() && !skippedResting && (fallbackAddons.isEmpty() ||
                attemptedFailures.all { it.kind == MetaFailureKind.MISSING })
            if (fallbackFinal || addons.isEmpty()) metaMissCache[cacheKey] = addonSet
            emit(
                NetworkResult.Error(
                    fallbackMessage,
                    code = if (fallbackFinal) NetworkResult.META_NOT_FOUND_CODE else null
                )
            )
            return@flow
        }

        var created: Deferred<MetaLookupResult>? = null
        val deferred = inFlightAddonMeta.getOrPut(flightKey) {
            repositoryScope.async {
                try {
                    // Kept here rather than in the enclosing flow's lists: this
                    // Deferred is shared, so only its creator would see those, and
                    // everyone else would report "no addon found" for addons tried.
                    val loopAddonNames = linkedSetOf<String>()
                    val loopFailures = mutableListOf<MetaAttemptFailure>()
                    var attempted = 0
                    var allMissing = true

                    // Normalize source addon URL for comparison so we can detect
                    // when the candidate is the same addon that served the catalog.
                    val normalizedSourceUrl = sourceAddonBaseUrl?.let(::normalizedAddonKey)

                    for ((addon, candidateType) in prioritizedCandidates) {
                        // If this candidate is the same addon that provided the catalog
                        // data for this item, the item already carries its meta —
                        // return immediately without making a request and without
                        // trying further addons.
                        if (normalizedSourceUrl != null) {
                            if (normalizedAddonKey(addon.baseUrl) == normalizedSourceUrl) {
                                Log.d(TAG, "Source addon matched, catalog meta is sufficient addon=${addon.name} type=$candidateType id=$id")
                                return@async MetaLookupResult.SourceSufficient
                            }
                        }

                        metaCache[addonMetaCacheKey(addon.baseUrl, candidateType, id)]?.takeUnless { it.isExpired() }?.let { cached ->
                            addonMetaCache[cacheKey] = cached
                            return@async MetaLookupResult.Found(cached.meta)
                        }

                        holdWhilePlayerOpen(caller)
                        val url = buildMetaUrl(addon.baseUrl, candidateType, id)
                        val host = addonHost(addon.baseUrl)
                        loopAddonNames += addon.displayName
                        if (!fromTitlePage && rateLimits.isPaused(host)) {
                            noteSkipped(caller, addon.name)
                            loopFailures += pausedHostFailure(addon, host)
                            allMissing = false
                            continue
                        }
                        if (!fromTitlePage && metaMisses.isResting(addon.missKey(id))) {
                            noteSkipped(caller, addon.name)
                            loopFailures += buildMissingMetaFailure(addon)
                            allMissing = false
                            continue
                        }
                        Log.d(TAG, "Trying meta addonId=${addon.id} addonName=${addon.name} type=$candidateType id=$id url=${url.redactLinksForLog()}")
                        noteRequest(caller, addon.name)
                        attempted++
                        try {
                            // Fork: keep the 20 s addon deadline (SafeApiCall.kt).
                            val response = withTimeoutOrNull(ADDON_REQUEST_TIMEOUT_MS) { api.getMeta(url) }
                                ?: throw java.io.IOException(context.getString(R.string.network_error_addon_timeout))
                            noteResponse(host, addon.name, response.code(), response.headers()["Retry-After"])
                            if (response.isSuccessful) {
                                val rawMeta = response.body()?.meta
                                val metaDto = usableMeta(rawMeta, host, addon.name)
                                if (rawMeta != null && metaDto == null) allMissing = false
                                if (metaDto != null) {
                                    if (!fromTitlePage) metaMisses.recordFound(addon.missKey(id))
                                    val meta = metaDto.toDomain(context.getString(R.string.episodes_episode))
                                    val ttlMs = parseMaxAgeMs(response.headers()["Cache-Control"])
                                    val cached = CachedMeta(meta, System.currentTimeMillis() + ttlMs)
                                    addonMetaCache[cacheKey] = cached
                                    metaCache[addonMetaCacheKey(addon.baseUrl, candidateType, id)] = cached
                                    Log.d(TAG, "Meta fetch success addonId=${addon.id} type=$candidateType id=$id ttl=${ttlMs}ms")
                                    return@async MetaLookupResult.Found(meta)
                                }
                                Log.d(TAG, "Meta response was null addonId=${addon.id} type=$candidateType id=$id")
                                loopFailures += buildMissingMetaFailure(addon)
                            } else {
                                loopFailures += MetaAttemptFailure(
                                    addonName = addon.displayName,
                                    kind = if (response.code() == 404) MetaFailureKind.MISSING else MetaFailureKind.REQUEST_FAILED,
                                    detail = response.message() ?: "HTTP ${response.code()}"
                                )
                                if (response.code() == 404) noteMetaMissing(addon, candidateType, id, fromTitlePage) else allMissing = false
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.d(TAG, "Meta fetch failed addonId=${addon.id} type=$candidateType id=$id: ${e.message?.redactLinksForLog()}")
                            loopFailures += MetaAttemptFailure(
                                addonName = addon.displayName,
                                kind = MetaFailureKind.REQUEST_FAILED,
                                detail = e.message ?: context.getString(R.string.network_error_unknown)
                            )
                            allMissing = false
                            /* try next */
                        }
                    }
                    val allAttemptsMissing = attempted > 0 &&
                        attempted == prioritizedCandidates.size && allMissing
                    // Cache only a definitive absence. A network/server failure must
                    // remain retryable when focus returns after connectivity recovers.
                    if (allAttemptsMissing) metaMissCache[cacheKey] = addonSet
                    // Comparing against the candidate list makes "every candidate was attempted"
                    // explicit rather than implied by the loop never breaking early.
                    MetaLookupResult.NotFound(
                        attemptedAddonNames = loopAddonNames.toList(),
                        failures = loopFailures,
                        allAttemptsMissing = allAttemptsMissing
                    )
                } finally {
                    inFlightAddonMeta.remove(flightKey)
                }
            }.also { created = it }
        }
        if (deferred !== created) noteJoined(deferred, caller)

        emit(lookupOutcome(deferred.await(), requestedType, id))
    }

    override suspend fun addonClaimsMeta(id: String): Boolean =
        addonRepository.getInstalledAddons().first().enabledAddons().any { addon ->
            val meta = addon.resources.firstOrNull { it.name == "meta" } ?: return@any false
            meta.idPrefixes.orEmpty().ifEmpty { addon.idPrefixes }.any { id.startsWith(it, ignoreCase = true) }
        }

    private fun lookupOutcome(lookupResult: MetaLookupResult, requestedType: String, id: String): NetworkResult<Meta> =
        when (lookupResult) {
            is MetaLookupResult.Found -> NetworkResult.Success(lookupResult.meta)
            is MetaLookupResult.SourceSufficient ->
                NetworkResult.Error("Source addon sufficient", NetworkResult.SOURCE_SUFFICIENT_CODE)
            // Every addon answering "no such item" is a final answer, not a failure. Flag it so
            // callers can cache the outcome instead of asking again on every focus. Only a
            // request failure stays retryable.
            is MetaLookupResult.NotFound -> NetworkResult.Error(
                buildAggregateFailureMessage(
                    type = requestedType,
                    id = id,
                    attemptedAddonNames = lookupResult.attemptedAddonNames,
                    failures = lookupResult.failures
                ),
                code = if (lookupResult.allAttemptsMissing) NetworkResult.META_NOT_FOUND_CODE else null
            )
        }

    override fun getMetaFromPrimaryAddon(
        type: String,
        id: String
    ): Flow<NetworkResult<Meta>> = flow {
        ServerItemRef.parse(id)?.let { ref ->
            emitAll(serverMeta(ref))
            return@flow
        }
        val cacheKey = metaLookupCacheKey(type, id)
        primaryAddonMetaCache[cacheKey]?.let { cached ->
            if (!cached.isExpired()) {
                emit(NetworkResult.Success(cached.meta))
                return@flow
            }
            primaryAddonMetaCache.remove(cacheKey)
        }

        emit(NetworkResult.Loading)

        val addons = installedAddonsOrEmpty()
        val requestedType = type.trim()
        val inferredType = inferCanonicalType(requestedType, id)
        val candidate = selectPrimaryMetaCandidate(
            addons = addons,
            requestedType = requestedType,
            inferredType = inferredType
        )

        if (candidate == null) {
            emit(NetworkResult.Error(context.getString(R.string.error_meta_no_supported_addon, requestedType)))
            return@flow
        }

        val (addon, candidateType) = candidate
        metaCache[addonMetaCacheKey(addon.baseUrl, candidateType, id)]?.takeUnless { it.isExpired() }?.let { cached ->
            primaryAddonMetaCache[cacheKey] = cached
            emit(NetworkResult.Success(cached.meta))
            return@flow
        }
        val url = buildMetaUrl(addon.baseUrl, candidateType, id)
        Log.d(
            TAG,
            "Trying primary meta addonId=${addon.id} addonName=${addon.name} type=$candidateType id=$id url=${url.redactLinksForLog()}"
        )

        val caller = currentCaller()
        val host = addonHost(addon.baseUrl)
        var created: Deferred<Meta?>? = null
        val deferred = inFlightPrimaryMeta.getOrPut(cacheKey) {
            repositoryScope.async {
                try {
                    holdWhilePlayerOpen(caller)
                    if (rateLimits.isPaused(host)) {
                        noteSkipped(caller, addon.name)
                        return@async null
                    }
                    noteRequest(caller, addon.name)
                    // Fork: keep the 20 s addon deadline (SafeApiCall.kt) that a raw
                    // call would drop -- without it a dead addon holds this
                    // coroutine for the shared client's full 60 s read timeout.
                    val response = withTimeoutOrNull(ADDON_REQUEST_TIMEOUT_MS) { api.getMeta(url) }
                        ?: throw java.io.IOException(context.getString(R.string.network_error_addon_timeout))
                    noteResponse(host, addon.name, response.code(), response.headers()["Retry-After"])
                    if (response.isSuccessful) {
                        val metaDto = usableMeta(response.body()?.meta, host, addon.name) ?: return@async null
                        val meta = metaDto.toDomain(context.getString(R.string.episodes_episode))
                        val ttlMs = parseMaxAgeMs(response.headers()["Cache-Control"])
                        val cached = CachedMeta(meta, System.currentTimeMillis() + ttlMs)
                        primaryAddonMetaCache[cacheKey] = cached
                        metaCache[addonMetaCacheKey(addon.baseUrl, candidateType, id)] = cached
                        meta
                    } else {
                        null
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Primary meta fetch failed for $url: ${e.message}".redactLinksForLog())
                    null
                } finally {
                    inFlightPrimaryMeta.remove(cacheKey)
                }
            }.also { created = it }
        }
        if (deferred !== created) noteJoined(deferred, caller)

        val meta = deferred.await()
        if (meta != null) {
            emit(NetworkResult.Success(meta))
        } else {
            emit(NetworkResult.Error(buildAggregateFailureMessage(
                type = requestedType,
                id = id,
                attemptedAddonNames = listOf(addon.displayName),
                failures = listOf(buildMissingMetaFailure(addon))
            )))
        }
    }

    /**
     * Splits an addon base URL into its path (trailing slashes trimmed) and
     * query portions. Shared by URL construction and cache keying so the two
     * always normalize equivalent base URLs identically.
     */
    private fun splitAddonBaseUrl(baseUrl: String): Pair<String, String> {
        val cleanBaseUrl = baseUrl.trimEnd('/')
        val queryStart = cleanBaseUrl.indexOf('?')
        val basePath = if (queryStart >= 0) cleanBaseUrl.substring(0, queryStart).trimEnd('/') else cleanBaseUrl
        val baseQuery = if (queryStart >= 0) cleanBaseUrl.substring(queryStart) else ""
        return basePath to baseQuery
    }

    private fun addonMetaCacheKey(addonBaseUrl: String, type: String, id: String): String {
        val (basePath, baseQuery) = splitAddonBaseUrl(addonBaseUrl)
        return "$basePath$baseQuery|$type:$id"
    }

    private fun recordAddonHealth(baseUrl: String, outcome: HealthOutcome, latencyMs: Long) {
        repositoryScope.launch {
            healthStore.record(AddonHealthStore.addonKey(canonicalizeAddonUrl(baseUrl)), outcome, latencyMs)
        }
    }

    /** Normalized addon base URL, so two spellings of the same one compare equal. */
    private fun normalizedAddonKey(baseUrl: String): String =
        splitAddonBaseUrl(baseUrl).let { (basePath, baseQuery) -> "$basePath$baseQuery" }

    /**
     * Key for the caches that are not per addon. Canonicalized so one title asked
     * for as "tv" and as "series" shares an entry instead of taking two.
     */
    private fun addonSetKey(addons: List<Addon>): String =
        addons.joinToString("|") { normalizedAddonKey(it.baseUrl) }

    private fun metaLookupCacheKey(type: String, id: String): String =
        "${inferCanonicalType(type.trim(), id).lowercase()}:$id"

    /**
     * Installed, enabled addons. Waits up to [INSTALLED_ADDONS_WAIT_MS] for a real
     * list instead of taking the StateFlow's empty seed. Returns empty if nothing
     * arrives in time, leaving callers to use the type as given or raise their
     * own no-addon error.
     */
    private suspend fun installedAddonsOrEmpty(): List<Addon> =
        withTimeoutOrNull(INSTALLED_ADDONS_WAIT_MS) {
            addonRepository.getInstalledAddons().filter { it.isNotEmpty() }.firstOrNull()
        }.orEmpty().enabledAddons()

    /** The installed, enabled addon at [baseUrl], if it is still installed. */
    private suspend fun findAddonByBaseUrl(baseUrl: String): Addon? {
        val target = normalizedAddonKey(baseUrl)
        return installedAddonsOrEmpty().firstOrNull { normalizedAddonKey(it.baseUrl) == target }
    }

    private fun buildMetaUrl(baseUrl: String, type: String, id: String): String {
        val (basePath, baseQuery) = splitAddonBaseUrl(baseUrl)
        val encodedType = encodePathSegment(type)
        val encodedId = encodePathSegment(id)
        return "$basePath/meta/$encodedType/$encodedId.json$baseQuery"
    }

    private fun Addon.supportsMetaType(type: String): Boolean {
        val target = type.trim()
        if (target.isBlank()) return false
        return resources.any { resource ->
            resource.name == "meta" && resource.supportsType(target)
        }
    }

    /**
     * Check if an addon can handle a specific ID based on idPrefixes.
     * Returns true if:
     * - The addon has no idPrefixes (accepts all IDs)
     * - The resource-level idPrefixes match the ID
     * - The addon-level idPrefixes match the ID
     */
    private fun Addon.supportsMetaId(id: String): Boolean {
        // Check resource-level idPrefixes first
        val metaResource = resources.firstOrNull { it.name == "meta" }
        if (metaResource?.idPrefixes != null && metaResource.idPrefixes.isNotEmpty()) {
            return metaResource.idPrefixes.any { prefix -> id.startsWith(prefix, ignoreCase = true) }
        }
        // Fall back to addon-level idPrefixes
        if (idPrefixes.isNotEmpty()) {
            return idPrefixes.any { prefix -> id.startsWith(prefix, ignoreCase = true) }
        }
        // No idPrefixes declared — addon accepts all IDs
        return true
    }

    private fun AddonResource.supportsType(type: String): Boolean {
        if (types.isEmpty()) return true
        return types.any { it.equals(type, ignoreCase = true) }
    }

    private fun inferCanonicalType(type: String, id: String): String {
        val normalizedType = type.trim()
        // "tv" is Nuvio's internal synonym for episodic content. Stremio metadata
        // addons advertise episodic content as "series", so fold it over here rather
        // than requesting a type nothing declares. Live TV is a separate type
        // ("channel") and is left alone.
        if (normalizedType.equals("tv", ignoreCase = true)) return "series"
        val known = setOf("movie", "series", "channel", "anime")
        if (normalizedType.lowercase() in known) return normalizedType

        val normalizedId = id.lowercase()
        return when {
            ":movie:" in normalizedId -> "movie"
            ":series:" in normalizedId -> "series"
            ":tv:" in normalizedId -> "series"
            ":anime:" in normalizedId -> "anime"
            else -> normalizedType
        }
    }

    /**
     * Picks a meta type this addon actually advertises, preferring the requested one.
     * Returns null when it supports neither, so a candidate is never built with a type
     * the addon has already rejected.
     */
    private fun Addon.supportedCandidateType(requestedType: String, inferredType: String): String? = when {
        supportsMetaType(requestedType) -> requestedType
        supportsMetaType(inferredType) -> inferredType
        else -> null
    }

    private fun selectPrimaryMetaCandidate(
        addons: List<Addon>,
        requestedType: String,
        inferredType: String
    ): Pair<Addon, String>? {
        addons.forEach { addon ->
            if (addon.supportsMetaType(requestedType)) {
                return addon to requestedType
            }
        }
        if (!inferredType.equals(requestedType, ignoreCase = true)) {
            addons.forEach { addon ->
                if (addon.supportsMetaType(inferredType)) {
                    return addon to inferredType
                }
            }
        }
        val topMetaAddon = addons.firstOrNull { addon ->
            addon.resources.any { it.name == "meta" }
        } ?: return null
        val fallbackType = topMetaAddon.supportedCandidateType(requestedType, inferredType)
            ?: return null
        return topMetaAddon to fallbackType
    }

    private fun encodePathSegment(value: String): String {
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }

    private fun buildMissingMetaFailure(addon: Addon): MetaAttemptFailure {
        return MetaAttemptFailure(
            addonName = addon.displayName,
            kind = MetaFailureKind.MISSING,
            detail = context.getString(com.nuvio.tv.R.string.meta_error_detail_no_metadata_for_id)
        )
    }

    private fun buildAggregateFailureMessage(
        type: String,
        id: String,
        attemptedAddonNames: List<String>,
        failures: List<MetaAttemptFailure>
    ): String {
        if (attemptedAddonNames.isEmpty()) {
            return context.getString(R.string.error_meta_no_addon_for_id, id, type)
        }

        val triedAddons = attemptedAddonNames.joinToString(", ")
        val missingOnly = failures.isNotEmpty() && failures.all { it.kind == MetaFailureKind.MISSING }

        return if (missingOnly) {
            context.getString(R.string.error_meta_tried_none, triedAddons, id, type)
        } else {
            val issueSummary = failures
                .filter { it.kind == MetaFailureKind.REQUEST_FAILED }
                .distinctBy { it.addonName to it.detail }
                .take(3)
                .joinToString("; ") { "${it.addonName}: ${it.detail}" }
            if (issueSummary.isBlank()) {
                context.getString(R.string.error_meta_tried_generic, triedAddons, id, type)
            } else {
                context.getString(R.string.error_meta_tried_issues, triedAddons, id, type, issueSummary)
            }
        }
    }
    
    /**
     * Parses the max-age directive from a Cache-Control header value.
     * Returns the TTL in milliseconds, or [DEFAULT_TTL_MS] if the header is
     * missing or malformed. Applies [MIN_META_TTL_MS] as a floor so that
     * addons responding with no-cache/no-store/max-age=0 still get a short
     * grace period, preventing re-fetches on every details screen visit.
     */
    private fun parseMaxAgeMs(cacheControl: String?): Long {
        if (cacheControl == null) return DEFAULT_TTL_MS
        val parsed = CacheControl.parse(okhttp3.Headers.headersOf("Cache-Control", cacheControl))
        if (parsed.noStore || parsed.noCache) return MIN_META_TTL_MS
        val maxAgeSec = parsed.maxAgeSeconds
        val ttlMs = if (maxAgeSec >= 0) maxAgeSec * 1000L else DEFAULT_TTL_MS
        return maxOf(ttlMs, MIN_META_TTL_MS)
    }

    override fun clearCache() {
        metaCache.clear()
        addonMetaCache.clear()
        primaryAddonMetaCache.clear()
        metaMissCache.clear()
        serverMetaCache.clear()
        inFlightMeta.clear()
        inFlightAddonMeta.clear()
        inFlightPrimaryMeta.clear()
    }

    private fun serverMeta(ref: ServerItemRef): Flow<NetworkResult<Meta>> = flow {
        val key = ref.encode()
        serverMetaCache[key]?.takeIf { !it.isExpired() }?.let { cached ->
            emit(NetworkResult.Success(cached.meta))
            return@flow
        }
        emit(NetworkResult.Loading)
        val meta = try {
            serverCatalog.details(ref).meta
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            emit(NetworkResult.Error(context.getString(error.serverFailure().messageRes())))
            return@flow
        }
        serverMetaCache[key] = CachedMeta(meta, System.currentTimeMillis() + SERVER_META_TTL_MS)
        emit(NetworkResult.Success(meta))
    }

    override fun getCachedMeta(type: String, id: String): Meta? {
        if (ServerItemRef.isServerId(id)) return serverMetaCache[id]?.takeIf { !it.isExpired() }?.meta
        val cacheKey = metaLookupCacheKey(type, id)
        return addonMetaCache[cacheKey]?.takeIf { !it.isExpired() }?.meta
    }
}
