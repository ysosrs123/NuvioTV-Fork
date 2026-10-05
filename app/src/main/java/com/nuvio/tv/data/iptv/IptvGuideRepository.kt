package com.nuvio.tv.data.iptv

import android.database.sqlite.SQLiteFullException
import com.nuvio.tv.core.iptv.GuideParseLimits
import com.nuvio.tv.core.iptv.RefreshDecision
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
    private val limits: GuideParseLimits = GuideParseLimits()) {
    suspend fun refresh(ref: IptvGuideRef, window: IptvGuideWindow): IptvGuideRefresh = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        try {
            val request = store.prepareRefresh(ref, window)
            when (val result = client.fetch(request.endpoint, request.validators?.let { CatalogueValidators(it.etag, it.lastModified) }) { input, validators, check ->
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
