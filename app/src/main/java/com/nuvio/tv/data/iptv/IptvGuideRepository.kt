package com.nuvio.tv.data.iptv

import android.database.sqlite.SQLiteFullException
import com.nuvio.tv.core.iptv.GuideParseLimits
import com.nuvio.tv.core.iptv.RefreshDecision
import com.nuvio.tv.core.iptv.XtreamGuideReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

sealed interface IptvGuideRefresh {
    data object Unchanged : IptvGuideRefresh
    data object StorageFull : IptvGuideRefresh
    data class Guide(val decision: RefreshDecision) : IptvGuideRefresh
}

class IptvGuideRepository(private val store: IptvGuideStore, private val client: IptvGuideClient = IptvGuideClient(),
    private val limits: GuideParseLimits = GuideParseLimits(),
    private val openDocument: ((String) -> java.io.InputStream)? = null,
    private val xtreamConnection: ((IptvSourceRef) -> IptvSourceConnection)? = null) {
    suspend fun refresh(ref: IptvGuideRef, window: IptvGuideWindow): IptvGuideRefresh = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        try {
            val request = store.prepareRefresh(ref, window)
            if (request.endpoint.startsWith("content:")) {
                val check = currentCoroutineContext()
                val opener = openDocument ?: throw MetadataException(MetadataFailure.INVALID_ADDRESS)
                return@withContext try {
                    opener(request.endpoint).use { input ->
                        IptvGuideRefresh.Guide(store.importGuide(request.ticket, input, window, IptvCacheValidators(), limits) { check.ensureActive() })
                    }
                } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                catch (_: SQLiteFullException) { IptvGuideRefresh.StorageFull }
                catch (_: Exception) { throw MetadataException(MetadataFailure.INVALID_RESPONSE) }
            }
            val address = XtreamGuideReference.sourceId(request.endpoint)?.let { sourceId ->
                val resolve = xtreamConnection ?: throw MetadataException(MetadataFailure.INVALID_ADDRESS)
                IptvXtreamClient.guideUrl(resolve(IptvSourceRef(ref.profileId, sourceId)))
            } ?: request.endpoint
            when (val result = client.fetch(address, request.validators?.let { CatalogueValidators(it.etag, it.lastModified) }) { input, validators, check ->
                try {
                    IptvGuideRefresh.Guide(store.importGuide(request.ticket, input, window,
                        IptvCacheValidators(validators.etag, validators.lastModified), limits, check))
                } catch (_: SQLiteFullException) { IptvGuideRefresh.StorageFull }
            }) {
                GuideDownload.NotModified -> {
                    currentCoroutineContext().ensureActive()
                    if (store.acceptNotModified(request.ticket, window)) IptvGuideRefresh.Unchanged else IptvGuideRefresh.Guide(RefreshDecision.STALE)
                }
                is GuideDownload.Imported -> result.result
            }
        } catch (_: SQLiteFullException) { IptvGuideRefresh.StorageFull }
    }
}
