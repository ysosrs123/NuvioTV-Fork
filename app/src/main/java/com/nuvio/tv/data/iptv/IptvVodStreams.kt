package com.nuvio.tv.data.iptv

import com.nuvio.tv.core.iptv.AcquisitionKey
import com.nuvio.tv.core.iptv.ConsumerReservation
import com.nuvio.tv.core.iptv.LiveAdmissionResult
import com.nuvio.tv.core.iptv.LiveConsumerLease
import com.nuvio.tv.core.iptv.LiveConsumerRole
import com.nuvio.tv.core.iptv.LiveSessionAdmission
import com.nuvio.tv.core.iptv.VodKind
import com.nuvio.tv.core.iptv.VodRef
import com.nuvio.tv.core.iptv.VodStreamRequest
import com.nuvio.tv.core.iptv.VodStreamText
import com.nuvio.tv.core.iptv.VodStreams
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class IptvVodStreamItem(val source: IptvSource, val ref: VodRef, val text: VodStreamText) {
    override fun toString(): String = "IptvVodStreamItem(ref=$ref)"
}

sealed interface IptvVodResolution {
    data class Ready(val playback: IptvVodPlayback, val lease: IptvVodLease? = null) : IptvVodResolution
    data class Busy(val sourceLabel: String) : IptvVodResolution
    data object Unavailable : IptvVodResolution
}

class IptvVodStreams(private val repository: IptvVodRepository, private val catalogue: IptvCatalogueStore, private val store: IptvVodStore) {
    fun sources(profileId: Int, kind: VodKind): List<IptvSource> {
        val states = store.states(profileId).associateBy { it.ref.sourceId }
        return catalogue.sources(profileId).filter { source ->
            val state = states[source.ref.sourceId] ?: return@filter false
            IptvVodRepository.enabled(source.kind, state) && (if (kind == VodKind.MOVIE) state.movies else state.series) > 0
        }
    }

    fun revision(profileId: Int): List<String> =
        store.states(profileId).map { "${it.ref.sourceId}:${it.enabled}:${it.refreshedAtMillis}:${it.movies}:${it.series}" } +
            catalogue.sources(profileId).map { "${it.ref.sourceId}=${it.label}" }

    fun direct(profileId: Int, kind: VodKind, tmdbId: String?, imdbId: String?): List<IptvVodTitle> =
        if (tmdbId == null && imdbId == null) emptyList() else repository.find(profileId, kind, tmdbId, imdbId, null, null)

    fun byTitles(profileId: Int, kind: VodKind, titles: List<String>, year: Int?, tmdbId: String?): List<IptvVodTitle> =
        titles.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            .flatMap { repository.byTitle(profileId, kind, it, year, tmdbId) }.distinctBy { it.ref }

    suspend fun items(source: IptvSource, request: VodStreamRequest, titles: List<IptvVodTitle>): List<IptvVodStreamItem> {
        if (request.kind == VodKind.MOVIE) return titles.map { title ->
            IptvVodStreamItem(source, title.ref, VodStreams.movie(title.name, title.year, extension(source, title.ref, title.extension)))
        }
        val season = request.season ?: return emptyList()
        val number = request.episode ?: return emptyList()
        var failure: Exception? = null
        val items = titles.mapNotNull { series ->
            try {
                val episode = repository.episode(series.ref, season, number) ?: return@mapNotNull null
                IptvVodStreamItem(source, episode.ref,
                    VodStreams.episode(series.name, season, number, episode.title, extension(source, episode.ref, episode.extension)))
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                if (failure == null) failure = error
                null
            }
        }
        failure?.let { if (items.isEmpty()) throw it else IptvLog.failure("vod streams episode", it) }
        return items
    }

    private suspend fun extension(source: IptvSource, ref: VodRef, stored: String?): String? {
        VodStreams.extension(stored)?.let { return it }
        if (source.kind != IptvSourceKind.M3U) return null
        return try { VodStreams.extensionOfUrl(repository.playback(ref).url) } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { null }
    }
}

class IptvVodLease internal constructor(private val admission: LiveSessionAdmission, private val lease: LiveConsumerLease) : Closeable {
    private val closed = AtomicBoolean()
    val active: Boolean get() = !closed.get()
    override fun close() { if (closed.compareAndSet(false, true)) admission.release(lease)?.let(admission::completeClose) }
    override fun toString(): String = "IptvVodLease(active=$active)"
}

class IptvVodResolver(private val repository: IptvVodRepository, private val catalogue: IptvCatalogueStore, private val admission: LiveSessionAdmission) {
    suspend fun resolve(ref: VodRef): IptvVodResolution = open(ref, null, false)

    suspend fun acquire(ref: VodRef, known: IptvVodPlayback? = null): IptvVodResolution = open(ref, known?.takeIf { it.ref == ref }, true)

    private suspend fun open(ref: VodRef, known: IptvVodPlayback?, lease: Boolean): IptvVodResolution = withContext(Dispatchers.IO) {
        var held: IptvVodLease? = null
        try {
            val source = catalogue.sources(ref.profileId).firstOrNull { it.ref.sourceId == ref.sourceId } ?: return@withContext IptvVodResolution.Unavailable
            val limit = (catalogue.accounts(ref.profileId).firstOrNull { it.id == source.accountId }?.maxStreams ?: 1).coerceAtLeast(1)
            val account = admissionAccount(ref.profileId, source.accountId)
            if (lease) {
                admission.setAccountLimit(account, limit)
                val key = AcquisitionKey(account, "vod:" + UUID.randomUUID(), ref.kind.wire, source.activeGeneration ?: 0)
                val result = admission.acquire(key, 0, ConsumerReservation(LiveConsumerRole.VOD, 0, 0))
                if (result !is LiveAdmissionResult.Admitted) {
                    IptvLog.info("vod play refused limit=$limit reason=${(result as LiveAdmissionResult.Denied).reason}")
                    return@withContext IptvVodResolution.Busy(source.label)
                }
                held = IptvVodLease(admission, result.lease)
            } else {
                val inUse = admission.snapshot().upstreamsByAccount[account] ?: 0
                if (VodStreams.connectionsBusy(inUse, limit)) {
                    IptvLog.info("vod play refused in_use=$inUse limit=$limit")
                    return@withContext IptvVodResolution.Busy(source.label)
                }
            }
            val playback = known ?: repository.playback(ref)
            if (known == null) IptvLog.info("vod play kind=${ref.kind.wire} source=${source.kind} lease=$lease")
            IptvVodResolution.Ready(playback, held).also { held = null }
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) {
            IptvLog.failure("vod play", error)
            IptvVodResolution.Unavailable
        } finally { held?.close() }
    }
}
