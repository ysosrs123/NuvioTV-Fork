package com.nuvio.tv.core.image

import coil3.annotation.ExperimentalCoilApi
import coil3.annotation.InternalCoilApi
import coil3.disk.DiskCache
import coil3.network.CacheNetworkResponse
import coil3.network.CacheStrategy
import coil3.network.NetworkHeaders
import coil3.network.NetworkRequest
import coil3.network.NetworkResponse
import coil3.network.cachecontrol.CacheControlCacheStrategy
import coil3.request.Options
import okio.FileSystem

/** Shortest time between online checks of a saved picture, whatever the server's own rule says. */
const val IMAGE_FRESHNESS_FLOOR_MS = 7L * 24 * 60 * 60 * 1000

/**
 * Artwork hosts that give a changed picture a new address. Subdomains count too.
 * They keep the week whatever is chosen.
 */
internal val IMMUTABLE_ARTWORK_HOSTS = listOf(
    "image.tmdb.org",
    "metahub.space",
    "assets.fanart.tv",
    "artworks.thetvdb.com",
    "m.media-amazon.com",
)

/** Host of a web address, or of a cache key that contains one. */
internal fun imageHost(value: String): String? {
    val scheme = value.indexOf("://").takeIf { it > 0 } ?: return null
    if (!value.regionMatches(scheme - 4, "http", 0, 4, ignoreCase = true) &&
        !value.regionMatches(scheme - 5, "https", 0, 5, ignoreCase = true)
    ) return null
    val start = scheme + 3
    val authority = value.substring(start).takeWhile { it != '/' && it != '?' && it != '#' }
    val host = authority.substringAfterLast('@').substringBefore(':').lowercase()
    return host.takeIf { it.isNotEmpty() }
}

internal fun isImmutableArtworkUrl(url: String): Boolean {
    val host = imageHost(url) ?: return false
    return IMMUTABLE_ARTWORK_HOSTS.any { host == it || host.endsWith(".$it") }
}

private const val DAY_MS = 24L * 60 * 60 * 1000

/** How often saved pictures from hosts outside [IMMUTABLE_ARTWORK_HOSTS] are checked online for a new one. */
enum class PosterUpdateInterval(val storageValue: String, val intervalMs: Long) {
    EVERY_START("every_start", 0L),
    DAILY("daily", DAY_MS),
    WEEKLY("weekly", IMAGE_FRESHNESS_FLOOR_MS),
    MONTHLY("monthly", 30 * DAY_MS);

    companion object {
        val DEFAULT = WEEKLY
        fun fromStorage(value: String?): PosterUpdateInterval = entries.firstOrNull { it.storageValue == value } ?: DEFAULT
    }
}

/** Coming back to Arc after this long away counts as a new start for Every time Arc starts. */
const val POSTER_UPDATES_AWAY_RESTART_MS = 60L * 60 * 1000

/**
 * The chosen [PosterUpdateInterval] and when this use of Arc started, read by the image loader on every load. A start
 * is the app process starting or a return to Arc after [POSTER_UPDATES_AWAY_RESTART_MS] away.
 */
open class PosterUpdateState {
    @Volatile var interval: PosterUpdateInterval = PosterUpdateInterval.DEFAULT
    @Volatile var startMillis: Long = System.currentTimeMillis()
        private set
    @Volatile var startElapsedMs: Long = Long.MIN_VALUE
        private set
    @Volatile private var awayAtMillis = 0L

    fun started(nowMillis: Long, nowElapsedMs: Long) {
        startMillis = nowMillis
        startElapsedMs = nowElapsedMs
        awayAtMillis = 0L
    }

    fun wentAway(nowMillis: Long) {
        awayAtMillis = nowMillis
    }

    fun cameBack(nowMillis: Long, nowElapsedMs: Long) {
        val away = awayAtMillis
        awayAtMillis = 0L
        if (away > 0 && nowMillis - away >= POSTER_UPDATES_AWAY_RESTART_MS) started(nowMillis, nowElapsedMs)
    }

    /** Pictures put in memory before this (elapsed clock) are looked at again; only Every time Arc starts uses it. */
    fun memoryStoredBefore(): Long =
        if (interval == PosterUpdateInterval.EVERY_START) startElapsedMs else Long.MIN_VALUE
}

object PosterUpdates : PosterUpdateState()

internal enum class SavedImageDecision { Fresh, Check, ServerRule }

/**
 * Artwork hosts and Weekly keep the week and then the server's own rule. Daily and Monthly check once their time has
 * passed, Every start checks a copy saved before this use of Arc started.
 */
internal fun savedImageDecision(
    url: String,
    responseMillis: Long,
    nowMillis: Long,
    interval: PosterUpdateInterval,
    startMillis: Long,
): SavedImageDecision {
    if (interval == PosterUpdateInterval.WEEKLY || isImmutableArtworkUrl(url)) {
        val fresh = withinFreshnessFloor(responseMillis, nowMillis)
        return if (fresh) SavedImageDecision.Fresh else SavedImageDecision.ServerRule
    }
    val fresh = if (interval == PosterUpdateInterval.EVERY_START) {
        responseMillis > 0 && responseMillis >= startMillis
    } else {
        withinFreshnessFloor(responseMillis, nowMillis, interval.intervalMs)
    }
    return if (fresh) SavedImageDecision.Fresh else SavedImageDecision.Check
}

/** How long a picture from a host that may change it stays in memory before the disk rules decide again. */
internal fun posterMemoryLimitMs(interval: PosterUpdateInterval): Long =
    if (interval == PosterUpdateInterval.EVERY_START) DAY_MS else interval.intervalMs

/** The request asks the server whether the saved picture changed, with its ETag and Last-Modified when it has them. */
internal fun conditionalRequest(request: NetworkRequest, saved: NetworkHeaders): NetworkRequest {
    val headers = request.headers.newBuilder()
    saved["ETag"]?.takeIf { it.isNotBlank() }?.let { headers.set("If-None-Match", it) }
    saved["Last-Modified"]?.takeIf { it.isNotBlank() }?.let { headers.set("If-Modified-Since", it) }
    return request.copy(headers = headers.build())
}

/** A saved copy younger than [floorMs] needs no online check; a server rule allowing longer still applies. */
internal fun withinFreshnessFloor(responseMillis: Long, nowMillis: Long, floorMs: Long = IMAGE_FRESHNESS_FLOOR_MS): Boolean =
    responseMillis > 0 && nowMillis - responseMillis < floorMs

private fun revalidationRequested(cacheControl: String?): Boolean {
    val value = cacheControl?.lowercase() ?: return false
    return "no-cache" in value || "max-age=0" in value
}

/**
 * Every saved picture gets a week without online checks on Weekly, and artwork hosts always do; a server rule allowing
 * longer still applies after that. Other hosts follow [PosterUpdates] for the other choices.
 */
@OptIn(ExperimentalCoilApi::class)
class FreshnessFloorCacheStrategy(
    private val delegate: CacheStrategy = CacheControlCacheStrategy(),
    private val interval: () -> PosterUpdateInterval = { PosterUpdates.interval },
    private val startMillis: () -> Long = { PosterUpdates.startMillis },
    private val now: () -> Long = System::currentTimeMillis,
) : CacheStrategy {
    override suspend fun read(
        cacheResponse: NetworkResponse,
        networkRequest: NetworkRequest,
        options: Options,
    ): CacheStrategy.ReadResult {
        if (revalidationRequested(networkRequest.headers["Cache-Control"])) {
            return delegate.read(cacheResponse, networkRequest, options)
        }
        val decision = savedImageDecision(
            networkRequest.url, cacheResponse.responseMillis, now(), interval(), startMillis(),
        )
        return when (decision) {
            SavedImageDecision.Fresh -> CacheStrategy.ReadResult(cacheResponse)
            SavedImageDecision.Check -> CacheStrategy.ReadResult(conditionalRequest(networkRequest, cacheResponse.headers))
            SavedImageDecision.ServerRule -> delegate.read(cacheResponse, networkRequest, options)
        }
    }

    override suspend fun write(
        cacheResponse: NetworkResponse?,
        networkRequest: NetworkRequest,
        networkResponse: NetworkResponse,
        options: Options,
    ): CacheStrategy.WriteResult = delegate.write(cacheResponse, networkRequest, networkResponse, options)
}

val imageCacheStrategy: CacheStrategy = FreshnessFloorCacheStrategy()

/**
 * Mirrors the fetcher's disk path: only a copy it would show without asking the server is local work.
 * An unreadable record counts as not fresh, so a change in Coil's record format only costs a download lane.
 */
@OptIn(ExperimentalCoilApi::class, InternalCoilApi::class)
internal suspend fun savedImageIsFresh(
    snapshot: DiskCache.Snapshot,
    fileSystem: FileSystem,
    url: String,
    options: Options,
    strategy: CacheStrategy = imageCacheStrategy,
): Boolean {
    val saved = try {
        if (fileSystem.metadata(snapshot.metadata).size == 0L) return true
        fileSystem.read(snapshot.metadata) { CacheNetworkResponse.readFrom(this) }
    } catch (e: Exception) {
        return false
    }
    return strategy.read(saved, NetworkRequest(url), options).response != null
}
