package com.nuvio.tv.data.iptv

import com.nuvio.tv.core.iptv.CatalogueReview
import com.nuvio.tv.core.iptv.ChannelCandidate
import com.nuvio.tv.core.iptv.HeldCatalogue
import com.nuvio.tv.core.iptv.PlaylistKind
import com.nuvio.tv.core.iptv.RefreshDecision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

sealed interface IptvPlaylistRefresh {
    data object Unchanged : IptvPlaylistRefresh
    data object HlsPlaybackInput : IptvPlaylistRefresh
    data object UnsupportedSourceKind : IptvPlaylistRefresh
    data class Catalogue(val decision: RefreshDecision, val guide: IptvGuideRef? = null,
        val guides: List<IptvGuideRef> = listOfNotNull(guide)) : IptvPlaylistRefresh
}

class IptvPlaylistRepository(private val store: IptvCatalogueStore, private val metadata: IptvMetadataClient = IptvMetadataClient(),
    private val xtream: IptvXtreamClient = IptvXtreamClient(), private val xtreamGuides: IptvXtreamGuides? = null,
    private val stalker: IptvStalkerClient = IptvStalkerClient(), private val connections: IptvSourceConnections? = null,
    private val now: () -> Long = System::currentTimeMillis, private val vod: IptvVodRepository? = null) {
    suspend fun refresh(ref: IptvSourceRef, onSaving: () -> Unit = {}): IptvPlaylistRefresh = withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        context.ensureActive()
        val request = store.prepareRefresh(ref)
        val firstLoad = store.sources(ref.profileId).firstOrNull { it.ref == ref }?.activeGeneration == null
        if (request.kind == IptvSourceKind.XTREAM) {
            val started = System.currentTimeMillis()
            val download = xtream.catalogue(request.connection)
            context.ensureActive()
            IptvLog.info("xtream download channels=${download.records.size} complete=${download.canPublish} ms=${System.currentTimeMillis() - started}")
            onSaving()
            val saving = System.currentTimeMillis()
            val decision = store.commitCatalogue(ref, request.ticket, download.records, download.canPublish) { context.ensureActive() }
            IptvLog.info("catalogue commit decision=$decision ms=${System.currentTimeMillis() - saving}")
            settle(ref, decision, download.records, IptvCacheValidators(), emptyList())
            connections?.let { runCatching { it.reported(ref, download.account.advertisedConnections) }.onFailure { error -> IptvLog.failure("source connections", error) } }
            val guide = if (decision != RefreshDecision.PUBLISH) null else if (firstLoad) xtreamGuides?.ensure(ref) else xtreamGuides?.linked(ref)
            if (decision == RefreshDecision.PUBLISH) applyImport(ref)
            return@withContext IptvPlaylistRefresh.Catalogue(decision, guide)
        }
        if (request.kind == IptvSourceKind.STALKER) {
            val download = stalker.catalogue(request.connection)
            context.ensureActive()
            onSaving()
            val decision = store.commitCatalogue(ref, request.ticket, download.records, download.canPublish) { context.ensureActive() }
            settle(ref, decision, download.records, IptvCacheValidators(), emptyList())
            if (decision == RefreshDecision.PUBLISH) applyImport(ref)
            return@withContext IptvPlaylistRefresh.Catalogue(decision)
        }
        val validators = request.validators?.let { CatalogueValidators(it.etag, it.lastModified) }
        when (val download = metadata.playlist(request.connection.endpoint, validators)) {
            PlaylistDownload.NotModified -> {
                context.ensureActive()
                if (store.acceptNotModified(ref, request.ticket)) IptvPlaylistRefresh.Unchanged else IptvPlaylistRefresh.Catalogue(RefreshDecision.STALE)
            }
            is PlaylistDownload.Candidate -> {
                context.ensureActive()
                if (download.catalogue.kind == PlaylistKind.HLS) return@withContext IptvPlaylistRefresh.HlsPlaybackInput
                onSaving()
                val records = download.catalogue.channels.map { row ->
                    IptvCatalogueRecord(ChannelCandidate(row.name, row.locator, guideId = row.guideId), row.attributes)
                }
                val cache = IptvCacheValidators(download.validators.etag, download.validators.lastModified)
                val moved = download.catalogue.vod.size
                if (moved > 0) IptvLog.info("playlist vod entries=$moved live=${records.size}")
                val previous = if (moved > 0) store.channelCounts(ref.profileId)[ref.sourceId] ?: 0 else 0
                val explained = moved > 0 && previous > 0 && vod?.detected(ref) == false && (records.size.toLong() + moved) * 2 >= previous
                val decision = store.commitCatalogue(ref, request.ticket, records, download.catalogue.canPublish, cache, acceptedLargeChange = explained) { context.ensureActive() }
                vod?.let { repository -> runCatching { repository.savePlaylist(ref, download.catalogue.vod) }.onFailure { IptvLog.failure("playlist vod", it) } }
                settle(ref, decision, records, cache, download.catalogue.guideUrls)
                val guides = playlistGuides(ref, decision, firstLoad, download.catalogue.guideUrls)
                if (guides.isNotEmpty()) IptvLog.info("playlist guides linked=${guides.size}")
                if (decision == RefreshDecision.PUBLISH) applyImport(ref)
                IptvPlaylistRefresh.Catalogue(decision, guides.firstOrNull(), guides)
            }
        }
    }

    fun held(ref: IptvSourceRef): HeldCatalogue? = store.sources(ref.profileId).firstOrNull { it.ref == ref }?.let { store.pending.held(ref, it.configurationVersion) }

    fun keepCurrent(ref: IptvSourceRef) = store.pending.dropHeld(ref)

    suspend fun acceptHeld(ref: IptvSourceRef): IptvPlaylistRefresh = withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        val source = store.sources(ref.profileId).single { it.ref == ref }
        store.pending.held(ref, source.configurationVersion) ?: return@withContext IptvPlaylistRefresh.Catalogue(RefreshDecision.STALE)
        val content = store.pending.heldContent(ref) ?: run { store.pending.dropHeld(ref); return@withContext IptvPlaylistRefresh.Catalogue(RefreshDecision.STALE) }
        val firstLoad = source.activeGeneration == null
        val request = store.prepareRefresh(ref)
        if (request.kind != source.kind || request.ticket.configurationVersion != source.configurationVersion) return@withContext IptvPlaylistRefresh.Catalogue(RefreshDecision.STALE)
        val records = content.records.map { IptvCatalogueRecord(it.channel, it.attributes) }
        val decision = store.commitCatalogue(ref, request.ticket, records, true, IptvCacheValidators(content.etag, content.lastModified),
            acceptedLargeChange = true) { context.ensureActive() }
        IptvLog.info("catalogue review accepted decision=$decision")
        if (decision != RefreshDecision.PUBLISH) return@withContext IptvPlaylistRefresh.Catalogue(decision)
        store.pending.dropHeld(ref)
        val guides = when (source.kind) {
            IptvSourceKind.XTREAM -> listOfNotNull(if (firstLoad) xtreamGuides?.ensure(ref) else xtreamGuides?.linked(ref))
            IptvSourceKind.M3U -> playlistGuides(ref, decision, firstLoad, content.guideUrls)
            IptvSourceKind.STALKER -> emptyList()
        }
        applyImport(ref)
        IptvPlaylistRefresh.Catalogue(decision, guides.firstOrNull(), guides)
    }

    private fun playlistGuides(ref: IptvSourceRef, decision: RefreshDecision, firstLoad: Boolean, urls: List<String>): List<IptvGuideRef> =
        if (decision != RefreshDecision.PUBLISH || urls.isEmpty()) emptyList()
        else if (firstLoad) xtreamGuides?.ensurePlaylist(ref, urls).orEmpty()
        else xtreamGuides?.linkedPlaylist(ref, urls).orEmpty()

    private fun settle(ref: IptvSourceRef, decision: RefreshDecision, records: List<IptvCatalogueRecord>, validators: IptvCacheValidators, guideUrls: List<String>) {
        runCatching {
            if (CatalogueReview.clearsHeld(decision)) { store.pending.dropHeld(ref); return }
            if (decision != RefreshDecision.SHRINK_REQUIRES_REVIEW && decision != RefreshDecision.EMPTY_REQUIRES_REVIEW) return
            val source = store.sources(ref.profileId).single { it.ref == ref }
            val previous = store.channelCounts(ref.profileId)[ref.sourceId] ?: 0
            val candidate = records.distinct().size
            if (!CatalogueReview.holds(decision, previous)) return
            val held = store.pending.hold(ref, HeldCatalogue(decision, previous, candidate, source.configurationVersion, now()), records.distinct(), validators, guideUrls)
            IptvLog.info("catalogue review waiting held=$held previous=$previous candidate=$candidate")
        }.onFailure { IptvLog.failure("catalogue review", it) }
    }

    private fun applyImport(ref: IptvSourceRef) {
        runCatching {
            val pending = store.pending.imported(ref) ?: return
            IptvSetupOverlays(store).apply(ref, pending, xtreamGuides?.linked(ref))
            store.pending.dropImport(ref)
        }.onFailure { IptvLog.failure("setup import overlays", it) }
    }
}
