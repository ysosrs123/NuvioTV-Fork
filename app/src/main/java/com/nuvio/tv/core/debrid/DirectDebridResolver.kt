package com.nuvio.tv.core.debrid

import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.domain.model.StreamBehaviorHints
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.core.health.AddonHealthStore
import com.nuvio.tv.core.health.HealthOutcome
import com.nuvio.tv.domain.model.StreamClientResolve
import com.nuvio.tv.domain.model.StreamDebridCacheState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DirectDebridResolver @Inject constructor(
    private val dataStore: DebridSettingsDataStore,
    private val torboxResolver: TorboxDirectDebridResolver,
    private val realDebridResolver: RealDebridDirectDebridResolver,
    private val allDebridResolver: AllDebridDirectDebridResolver,
    private val premiumizeResolver: PremiumizeDirectDebridResolver,
    private val localDebridService: LocalDebridService,
    private val healthStore: AddonHealthStore,
    private val thumbnailCopies: ThumbnailCopyAdded,
    private val linkStore: ResolvedLinkStore,
    private val accounts: DebridAccounts,
    private val torboxCopies: ThumbnailCopyDebrid
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val resolvedCache = mutableMapOf<String, CachedDirectDebridResolve>()
    private val inFlightResolves = mutableMapOf<String, Deferred<DirectDebridResolveResult>>()

    /**
     * [mayAddToAccount] false keeps Real-Debrid, AllDebrid and TorBox to titles already in the account. Premiumize
     * adds nothing to the account either way. Only a lookup that may add claims a TorBox thumbnail copy of the same
     * title for playback.
     */
    suspend fun resolve(
        stream: Stream,
        season: Int?,
        episode: Int?,
        mayAddToAccount: Boolean = true
    ): DirectDebridResolveResult {
        if (mayAddToAccount) thumbnailCopies.claimAndWait(stream.getEffectiveInfoHash())
        val t0 = android.os.SystemClock.elapsedRealtime()
        val result = resolveInternal(stream, season, episode, mayAddToAccount)
        recordResolverHealth(stream, result, android.os.SystemClock.elapsedRealtime() - t0)
        return result
    }

    private suspend fun resolveInternal(
        stream: Stream,
        season: Int?,
        episode: Int?,
        mayAddToAccount: Boolean
    ): DirectDebridResolveResult {
        if (!shouldResolveToPlayableStream(stream)) {
            return DirectDebridResolveResult.Stale
        }
        val cacheKey = stream.directDebridResolveCacheKey(season, episode)
        if (cacheKey == null) {
            return resolveUncached(stream, season, episode, mayAddToAccount)
        }
        getCachedResult(cacheKey)?.let {
            return it
        }
        linkStore.get(cacheKey)?.let { stored ->
            mutex.withLock {
                resolvedCache[cacheKey] = CachedDirectDebridResolve(stored.result, stored.savedAtMs)
            }
            return stored.result
        }

        val flightKey = if (mayAddToAccount) cacheKey else "$cacheKey|account"
        var ownsResolve = false
        val newResolve = scope.async(start = CoroutineStart.LAZY) {
            resolveUncached(stream, season, episode, mayAddToAccount)
        }
        val activeResolve = mutex.withLock {
            getCachedResultLocked(cacheKey)?.let { cached ->
                return@withLock null to cached
            }
            val existing = inFlightResolves[flightKey]
            if (existing != null) {
                existing to null
            } else {
                inFlightResolves[flightKey] = newResolve
                ownsResolve = true
                newResolve to null
            }
        }
        activeResolve.second?.let {
            newResolve.cancel()
            return it
        }
        val deferred = activeResolve.first ?: return DirectDebridResolveResult.Error
        if (!ownsResolve) newResolve.cancel()
        if (ownsResolve) deferred.start()

        return try {
            val result = deferred.await()
            if (ownsResolve && result is DirectDebridResolveResult.Success) {
                mutex.withLock {
                    resolvedCache[cacheKey] = CachedDirectDebridResolve(
                        result = result,
                        cachedAtMs = System.currentTimeMillis()
                    )
                }
                linkStore.put(cacheKey, result)
            }
            result
        } finally {
            if (ownsResolve) {
                mutex.withLock {
                    if (inFlightResolves[flightKey] === deferred) {
                        inFlightResolves.remove(flightKey)
                    }
                }
            }
        }
    }

    suspend fun cachedPlayableStream(stream: Stream, season: Int?, episode: Int?): Stream? {
        if (!shouldResolveToPlayableStream(stream)) return null
        val cacheKey = stream.directDebridResolveCacheKey(season, episode) ?: return null
        return getCachedResult(cacheKey)?.let { result -> stream.withResolvedDebridUrl(result) }
    }

    suspend fun resolveToPlayableStream(
        stream: Stream,
        season: Int?,
        episode: Int?,
        mayAddToAccount: Boolean = true
    ): DirectDebridPlayableResult {
        if (!shouldResolveToPlayableStream(stream)) {
            return DirectDebridPlayableResult.Success(stream)
        }
        return when (val result = resolve(stream, season, episode, mayAddToAccount)) {
            is DirectDebridResolveResult.Success -> DirectDebridPlayableResult.Success(stream.withResolvedDebridUrl(result))
            DirectDebridResolveResult.MissingApiKey -> DirectDebridPlayableResult.MissingApiKey
            DirectDebridResolveResult.NotCached -> DirectDebridPlayableResult.NotCached
            DirectDebridResolveResult.Stale, is DirectDebridResolveResult.Busy -> DirectDebridPlayableResult.Stale
            DirectDebridResolveResult.Error -> DirectDebridPlayableResult.Error
        }
    }

    suspend fun shouldResolveToPlayableStream(stream: Stream): Boolean {
        val settings = dataStore.settings.first()
        if (!settings.canResolvePlayableLinks) return false
        if (stream.needsLocalDebridResolve()) {
            return DebridProviders.plainTorrentServices(settings).isNotEmpty()
        }
        if (!stream.isDirectDebrid() || stream.getStreamUrl() != null) return false
        val providerId = DebridProviders.byId(stream.clientResolve?.service)?.id ?: return false
        return DebridProviders.isVisible(providerId) && settings.apiKeyFor(providerId).isNotBlank()
    }

    private suspend fun getCachedResult(cacheKey: String): DirectDebridResolveResult.Success? =
        mutex.withLock { getCachedResultLocked(cacheKey) }

    private fun getCachedResultLocked(cacheKey: String): DirectDebridResolveResult.Success? {
        val cached = resolvedCache[cacheKey] ?: return null
        val age = System.currentTimeMillis() - cached.cachedAtMs
        return if (age in 0..DIRECT_DEBRID_RESOLVE_CACHE_TTL_MS) {
            cached.result
        } else {
            resolvedCache.remove(cacheKey)
            null
        }
    }

    private fun recordResolverHealth(
        stream: Stream,
        result: DirectDebridResolveResult,
        latencyMs: Long
    ) {
        val providerId = DebridProviders.byId(stream.clientResolve?.service)?.id ?: return
        val outcome = when (result) {
            is DirectDebridResolveResult.Success -> HealthOutcome.SUCCESS
            DirectDebridResolveResult.NotCached -> HealthOutcome.EMPTY
            DirectDebridResolveResult.Error -> HealthOutcome.FAILURE
            else -> return
        }
        scope.launch {
            healthStore.record(AddonHealthStore.resolverKey(providerId), outcome, latencyMs)
        }
    }

    private suspend fun resolveUncached(
        stream: Stream,
        season: Int?,
        episode: Int?,
        mayAddToAccount: Boolean
    ): DirectDebridResolveResult {
        if (stream.needsLocalDebridResolve()) {
            return resolveLocalTorrentStream(stream, season, episode, mayAddToAccount)
        }
        return resolveWithProvider(stream.clientResolve?.service, stream, season, episode, mayAddToAccount)
    }

    private suspend fun resolveWithProvider(
        service: String?,
        stream: Stream,
        season: Int?,
        episode: Int?,
        mayAddToAccount: Boolean
    ): DirectDebridResolveResult =
        when (DebridProviders.byId(service)?.id) {
            DebridProviders.TORBOX_ID ->
                if (mayAddToAccount || torboxCopies.inTorboxList(stream) == true) {
                    torboxResolver.resolve(stream, season, episode)
                } else {
                    DirectDebridResolveResult.NotCached
                }
            DebridProviders.PREMIUMIZE_ID -> premiumizeResolver.resolve(stream, season, episode)
            DebridProviders.REAL_DEBRID_ID -> realDebridResolver.resolve(stream, season, episode, mayAddToAccount)
            DebridProviders.ALL_DEBRID_ID -> allDebridResolver.resolve(stream, season, episode, mayAddToAccount)
            else -> DirectDebridResolveResult.Error
        }

    /**
     * Gets the link for [stream] only when the title is already in the Real-Debrid or AllDebrid account, so
     * nothing new is added. Other services are left alone.
     */
    suspend fun prepareFromAccount(stream: Stream, season: Int?, episode: Int?): Boolean {
        if (!shouldResolveToPlayableStream(stream)) return false
        if (!DebridAccounts.supportsAccountList(resolverProviderId(stream))) return false
        return resolve(stream, season, episode, mayAddToAccount = false) is DirectDebridResolveResult.Success
    }

    private suspend fun resolverProviderId(stream: Stream): String? =
        if (stream.needsLocalDebridResolve()) {
            plainTorrentCredential(dataStore.settings.first(), stream)?.provider?.id
        } else {
            DebridProviders.byId(stream.clientResolve?.service)?.id
        }

    fun warmUp() {
        accounts.warmUp()
    }

    private suspend fun Stream.directDebridResolveCacheKey(season: Int?, episode: Int?): String? {
        if (needsLocalDebridResolve()) {
            val settings = dataStore.settings.first()
            val account = plainTorrentCredential(settings, this) ?: return null
            val apiKey = account.apiKey.trim().takeIf { it.isNotBlank() } ?: return null
            val identity = getEffectiveInfoHash() ?: torrentMagnetUri() ?: behaviorHints?.filename ?: return null
            return listOf(
                account.provider.id,
                apiKey.stableFingerprint(),
                identity.trim().lowercase(),
                getEffectiveFileIdx()?.toString().orEmpty(),
                behaviorHints?.filename.orEmpty().trim().lowercase(),
                season?.toString().orEmpty(),
                episode?.toString().orEmpty()
            ).joinToString("|")
        }
        val resolve = clientResolve ?: return null
        val providerId = DebridProviders.byId(resolve.service)?.id ?: return null
        val settings = dataStore.settings.first()
        if (!settings.canResolvePlayableLinks || !DebridProviders.isVisible(providerId)) return null
        val apiKey = settings.apiKeyFor(providerId).trim().takeIf { it.isNotBlank() } ?: return null
        val identity = resolve.infoHash
            ?: resolve.magnetUri
            ?: resolve.torrentName
            ?: resolve.filename
            ?: return null

        return listOf(
            providerId,
            apiKey.stableFingerprint(),
            identity.trim().lowercase(),
            resolve.fileIdx?.toString().orEmpty(),
            (resolve.filename ?: behaviorHints?.filename).orEmpty().trim().lowercase(),
            (season ?: resolve.season)?.toString().orEmpty(),
            (episode ?: resolve.episode)?.toString().orEmpty()
        ).joinToString("|")
    }

    private suspend fun resolveLocalTorrentStream(
        stream: Stream,
        season: Int?,
        episode: Int?,
        mayAddToAccount: Boolean
    ): DirectDebridResolveResult {
        val settings = dataStore.settings.first()
        val services = DebridProviders.plainTorrentServices(settings)
        if (services.isEmpty()) return DirectDebridResolveResult.MissingApiKey
        val hash = stream.getEffectiveInfoHash()?.trim()?.lowercase()
        if (stream.debridCacheStatus?.state == StreamDebridCacheState.NOT_CACHED) {
            return DirectDebridResolveResult.NotCached
        }
        val account = plainTorrentCredential(settings, stream)
            ?: firstAccountWithCopy(services, hash)
            ?: return DirectDebridResolveResult.NotCached

        val magnet = DebridMagnetBuilder.fromStream(stream)
            ?: return DirectDebridResolveResult.Stale
        val resolveStream = stream.copy(
            clientResolve = StreamClientResolve(
                type = "torrent",
                infoHash = stream.getEffectiveInfoHash(),
                fileIdx = stream.getEffectiveFileIdx(),
                magnetUri = magnet,
                sources = stream.sources,
                torrentName = null,
                filename = stream.behaviorHints?.filename,
                mediaType = null,
                mediaId = null,
                mediaOnlyId = null,
                title = stream.title ?: stream.name,
                season = season,
                episode = episode,
                service = account.provider.id,
                serviceIndex = null,
                serviceExtension = null,
                isCached = stream.debridCacheStatus?.state == StreamDebridCacheState.CACHED,
                stream = null
            )
        )

        return resolveWithProvider(account.provider.id, resolveStream, season, episode, mayAddToAccount)
    }

    /**
     * The account a plain torrent goes to: the one the list marked it cached on, or, when nothing was marked,
     * the preferred account if it cannot be asked; null when the accounts still have to be asked.
     */
    private fun plainTorrentCredential(
        settings: com.nuvio.tv.domain.model.DebridSettings,
        stream: Stream
    ): DebridServiceCredential? {
        val services = DebridProviders.plainTorrentServices(settings)
        val status = stream.debridCacheStatus
        if (status?.state == StreamDebridCacheState.CACHED) {
            services.firstOrNull { it.provider.id == DebridProviders.byId(status.providerId)?.id }?.let { return it }
        }
        return services.firstOrNull()?.takeIf { services.size == 1 }
    }

    /** The first account, in the preferred order, that has [hash] cached; the first one when none can say. */
    private suspend fun firstAccountWithCopy(
        services: List<DebridServiceCredential>,
        hash: String?
    ): DebridServiceCredential? {
        if (hash.isNullOrBlank()) return services.firstOrNull()
        var unknown: DebridServiceCredential? = null
        for (account in services) {
            if (!account.provider.supports(DebridProviderCapability.LocalTorrentCacheCheck)) return account
            when (localDebridService.isCached(account, hash)) {
                true -> return account
                null -> if (unknown == null) unknown = account
                false -> Unit
            }
        }
        return unknown
    }
}

private const val DIRECT_DEBRID_RESOLVE_CACHE_TTL_MS = 15L * 60L * 1000L

private data class CachedDirectDebridResolve(
    val result: DirectDebridResolveResult.Success,
    val cachedAtMs: Long
)

sealed class DirectDebridPlayableResult {
    data class Success(val stream: Stream) : DirectDebridPlayableResult()
    data object MissingApiKey : DirectDebridPlayableResult()
    data object NotCached : DirectDebridPlayableResult()
    data object Stale : DirectDebridPlayableResult()
    data object Error : DirectDebridPlayableResult()
}

private fun String.stableFingerprint(): String {
    val hash = fold(1125899906842597L) { acc, char -> (acc * 31L) + char.code }
    return hash.toULong().toString(16)
}

private fun Stream.withResolvedDebridUrl(result: DirectDebridResolveResult.Success): Stream =
    copy(
        url = result.url,
        externalUrl = null,
        behaviorHints = behaviorHints.mergeResolvedDebridHints(result)
    )

private fun StreamBehaviorHints?.mergeResolvedDebridHints(
    result: DirectDebridResolveResult.Success
): StreamBehaviorHints {
    val current = this
    if (current != null) {
        return current.copy(
            filename = result.filename ?: current.filename,
            videoSize = result.videoSize ?: current.videoSize
        )
    }
    return StreamBehaviorHints(
        notWebReady = null,
        bingeGroup = null,
        countryWhitelist = null,
        proxyHeaders = null,
        filename = result.filename,
        videoSize = result.videoSize
    )
}
