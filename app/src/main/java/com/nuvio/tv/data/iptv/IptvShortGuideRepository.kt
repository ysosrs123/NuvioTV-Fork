package com.nuvio.tv.data.iptv

import com.nuvio.tv.core.iptv.GuideProgramme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class IptvShortGuideTarget(val connection: IptvSourceConnection, val streamId: String) {
    override fun toString() = "IptvShortGuideTarget(stream=$streamId)"
}

class IptvShortGuideRepository(
    private val target: (IptvSourceRef, String) -> IptvShortGuideTarget?,
    private val fetch: suspend (IptvSourceConnection, String) -> List<GuideProgramme>,
    private val now: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val cacheMillis: Long = 5 * 60_000L,
    private val requestGapMillis: Long = 1_000L,
    private val maxEntries: Int = 256,
) {
    constructor(catalogue: IptvCatalogueStore, client: IptvXtreamClient = IptvXtreamClient()) : this(
        target = { ref, channelId ->
            val xtream = catalogue.sources(ref.profileId).any { it.ref == ref && it.kind == IptvSourceKind.XTREAM }
            catalogue.playbackItem(ref, channelId)?.channel?.data?.providerId?.takeIf { xtream }
                ?.let { IptvShortGuideTarget(catalogue.connection(ref), it) }
        },
        fetch = { connection, streamId -> client.shortGuide(connection, streamId) },
    )

    init { require(cacheMillis > 0 && requestGapMillis >= 0 && maxEntries > 0) }

    private class Entry(val programmes: List<GuideProgramme>, val fetchedAt: Long)
    private val lock = Mutex()
    private val cache = LinkedHashMap<String, Entry>()
    private var lastRequestAt: Long? = null

    suspend fun nowNext(ref: IptvSourceRef, channelId: String, limit: Int = 2): List<GuideProgramme> = withContext(Dispatchers.IO) {
        require(limit in 1..10 && channelId.isNotBlank())
        lock.withLock {
            val key = "${ref.profileId}:${ref.sourceId}:$channelId"
            val cached = cache[key]?.takeIf { now() - it.fetchedAt in 0 until cacheMillis }
            val programmes = cached?.programmes ?: load(ref, channelId).also { result ->
                cache.remove(key)
                cache[key] = Entry(result, now())
                while (cache.size > maxEntries) cache.remove(cache.keys.first())
            }
            val at = now()
            programmes.filter { (it.stop?.epochMillis ?: Long.MAX_VALUE) > at }.take(limit)
        }
    }

    suspend fun clear() = lock.withLock { cache.clear() }

    private suspend fun load(ref: IptvSourceRef, channelId: String): List<GuideProgramme> {
        val target = try { target(ref, channelId) } catch (_: IllegalArgumentException) { null } ?: return emptyList()
        lastRequestAt?.let { last -> (requestGapMillis - (now() - last)).takeIf { it > 0 }?.let { pause(it) } }
        lastRequestAt = now()
        return try { fetch(target.connection, target.streamId) }
        catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { IptvLog.failure("short guide", error); emptyList() }
    }
}
