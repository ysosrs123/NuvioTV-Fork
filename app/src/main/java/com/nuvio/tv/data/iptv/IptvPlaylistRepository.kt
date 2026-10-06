package com.nuvio.tv.data.iptv

import com.nuvio.tv.core.iptv.ChannelCandidate
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
    data class Catalogue(val decision: RefreshDecision, val guide: IptvGuideRef? = null) : IptvPlaylistRefresh
}

class IptvPlaylistRepository(private val store: IptvCatalogueStore, private val metadata: IptvMetadataClient = IptvMetadataClient(),
    private val xtream: IptvXtreamClient = IptvXtreamClient(), private val xtreamGuides: IptvXtreamGuides? = null,
    private val stalker: IptvStalkerClient = IptvStalkerClient()) {
    suspend fun refresh(ref: IptvSourceRef): IptvPlaylistRefresh = withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        context.ensureActive()
        val request = store.prepareRefresh(ref)
        if (request.kind == IptvSourceKind.XTREAM) {
            val download = xtream.catalogue(request.connection)
            context.ensureActive()
            val decision = store.commitCatalogue(ref, request.ticket, download.records, download.canPublish) { context.ensureActive() }
            val guide = if (decision == RefreshDecision.PUBLISH) xtreamGuides?.ensure(ref) else null
            return@withContext IptvPlaylistRefresh.Catalogue(decision, guide)
        }
        if (request.kind == IptvSourceKind.STALKER) {
            val download = stalker.catalogue(request.connection)
            context.ensureActive()
            return@withContext IptvPlaylistRefresh.Catalogue(store.commitCatalogue(ref, request.ticket,
                download.records, download.canPublish) { context.ensureActive() })
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
                val records = download.catalogue.channels.map { row ->
                    IptvCatalogueRecord(ChannelCandidate(row.name, row.locator, guideId = row.guideId), row.attributes)
                }
                IptvPlaylistRefresh.Catalogue(store.commitCatalogue(ref, request.ticket, records, download.catalogue.canPublish,
                    IptvCacheValidators(download.validators.etag, download.validators.lastModified)) { context.ensureActive() })
            }
        }
    }
}
