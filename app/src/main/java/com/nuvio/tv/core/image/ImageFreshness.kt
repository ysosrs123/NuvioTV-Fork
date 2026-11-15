package com.nuvio.tv.core.image

import coil3.annotation.ExperimentalCoilApi
import coil3.annotation.InternalCoilApi
import coil3.disk.DiskCache
import coil3.network.CacheNetworkResponse
import coil3.network.CacheStrategy
import coil3.network.NetworkRequest
import coil3.network.NetworkResponse
import coil3.network.cachecontrol.CacheControlCacheStrategy
import coil3.request.Options
import okio.FileSystem

/** Shortest time between online checks of a saved picture, whatever the server's own rule says. */
const val IMAGE_FRESHNESS_FLOOR_MS = 7L * 24 * 60 * 60 * 1000

/** Artwork hosts that give a changed picture a new address. Subdomains count too. */
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

/** A saved copy younger than [floorMs] needs no online check; a server rule allowing longer still applies. */
internal fun withinFreshnessFloor(responseMillis: Long, nowMillis: Long, floorMs: Long = IMAGE_FRESHNESS_FLOOR_MS): Boolean =
    responseMillis > 0 && nowMillis - responseMillis < floorMs

private fun revalidationRequested(cacheControl: String?): Boolean {
    val value = cacheControl?.lowercase() ?: return false
    return "no-cache" in value || "max-age=0" in value
}

/** Every saved picture gets a week without online checks; a server rule allowing longer still applies after that. */
@OptIn(ExperimentalCoilApi::class)
class FreshnessFloorCacheStrategy(
    private val delegate: CacheStrategy = CacheControlCacheStrategy(),
    private val floorMs: Long = IMAGE_FRESHNESS_FLOOR_MS,
    private val now: () -> Long = System::currentTimeMillis,
) : CacheStrategy {
    override suspend fun read(
        cacheResponse: NetworkResponse,
        networkRequest: NetworkRequest,
        options: Options,
    ): CacheStrategy.ReadResult {
        if (withinFreshnessFloor(cacheResponse.responseMillis, now(), floorMs) &&
            !revalidationRequested(networkRequest.headers["Cache-Control"])
        ) {
            return CacheStrategy.ReadResult(cacheResponse)
        }
        return delegate.read(cacheResponse, networkRequest, options)
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
