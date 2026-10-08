package com.nuvio.tv.data.iptv

import android.database.sqlite.SQLiteFullException
import com.nuvio.tv.core.iptv.GuideImportFilter
import com.nuvio.tv.core.iptv.GuideImportIssue
import com.nuvio.tv.core.iptv.GuideParseLimits
import com.nuvio.tv.core.iptv.RefreshDecision
import com.nuvio.tv.core.iptv.XtreamGuideReference
import com.nuvio.tv.core.iptv.guideFailureIssue
import com.nuvio.tv.core.iptv.guideStorageCaps
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

sealed interface IptvGuideRefresh {
    data object Unchanged : IptvGuideRefresh
    data object StorageFull : IptvGuideRefresh
    data class Guide(val decision: RefreshDecision, val issue: GuideImportIssue? = null) : IptvGuideRefresh
}

class IptvGuideRepository(private val store: IptvGuideStore, private val client: IptvGuideClient = IptvGuideClient(),
    private val limits: GuideParseLimits = GuideParseLimits(),
    private val openDocument: ((String) -> InputStream)? = null,
    private val xtreamConnection: ((IptvSourceRef) -> IptvSourceConnection)? = null,
    private val openLocal: ((String) -> InputStream)? = null,
    private val guideFilter: ((IptvGuideRef) -> GuideImportFilter?)? = null,
    private val downloads: File? = null) {
    private val swept = AtomicBoolean(false)

    suspend fun refresh(ref: IptvGuideRef, window: IptvGuideWindow, onlyIfChangedWithinMillis: Long? = null): IptvGuideRefresh = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        val filter = guideFilter?.invoke(ref)
        try {
            if (onlyIfChangedWithinMillis != null && store.upToDate(ref, window, filter?.fingerprint, onlyIfChangedWithinMillis)) return@withContext IptvGuideRefresh.Unchanged
            val request = store.prepareRefresh(ref, window, filter?.fingerprint)
            val check = currentCoroutineContext()
            if (request.endpoint.startsWith("content:") || request.endpoint.startsWith("file:")) {
                val opener = (if (request.endpoint.startsWith("file:")) openLocal else openDocument)
                    ?: throw MetadataException(MetadataFailure.INVALID_ADDRESS)
                return@withContext importInput(request, window, IptvCacheValidators(), filter, { check.ensureActive() }) {
                    try { opener(request.endpoint) } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                    catch (error: Exception) { IptvLog.failure("guide file", error); throw MetadataException(MetadataFailure.INVALID_RESPONSE) }
                }
            }
            val provider = XtreamGuideReference.sourceId(request.endpoint)?.let { IptvSourceRef(ref.profileId, it) }
            val address = provider?.let { source ->
                val resolve = xtreamConnection ?: throw MetadataException(MetadataFailure.INVALID_ADDRESS)
                IptvXtreamClient.guideUrl(resolve(source))
            } ?: request.endpoint
            val previous = request.validators?.let { CatalogueValidators(it.etag, it.lastModified) }
            val folder = downloads
            if (folder == null) {
                return@withContext when (val result = client.fetch(address, previous) { input, validators, checkBody ->
                    try {
                        store.importGuideResult(request.ticket, input, window, IptvCacheValidators(validators.etag, validators.lastModified), limits, filter, checkBody,
                            guideStorageCaps(window.fromMillis, window.untilMillis))
                            .let { IptvGuideRefresh.Guide(it.decision, it.issue) }
                    } catch (_: SQLiteFullException) { IptvGuideRefresh.StorageFull }
                    catch (error: IllegalArgumentException) { guideFailureIssue(error)?.let { IptvGuideRefresh.Guide(RefreshDecision.INVALID, it) } ?: throw error }
                }) {
                    GuideDownload.NotModified -> notModified(request, window)
                    is GuideDownload.Imported -> result.result
                }
            }
            folder.mkdirs()
            if (swept.compareAndSet(false, true)) folder.listFiles()?.filter { it.name.startsWith("guide-") && it.lastModified() < System.currentTimeMillis() - STALE_DOWNLOAD_MILLIS }?.forEach { it.delete() }
            val file = File.createTempFile("guide-", ".part", folder)
            try {
                when (val result = client.fetch(address, previous) { input, validators, _ ->
                    file.outputStream().use { output -> input.copyTo(output, COPY_BUFFER) }
                    validators
                }) {
                    GuideDownload.NotModified -> notModified(request, window)
                    is GuideDownload.Imported -> importInput(request, window, IptvCacheValidators(result.result.etag, result.result.lastModified), filter,
                        { check.ensureActive() }) { file.inputStream().buffered(COPY_BUFFER) }
                }
            } finally { file.delete() }
        } catch (_: SQLiteFullException) { IptvGuideRefresh.StorageFull }
    }

    private suspend fun notModified(request: IptvGuideRefreshRequest, window: IptvGuideWindow): IptvGuideRefresh {
        currentCoroutineContext().ensureActive()
        return if (store.acceptNotModified(request.ticket, window)) IptvGuideRefresh.Unchanged else IptvGuideRefresh.Guide(RefreshDecision.STALE)
    }

    private fun importInput(request: IptvGuideRefreshRequest, window: IptvGuideWindow, validators: IptvCacheValidators, filter: GuideImportFilter?,
        checkCancellation: () -> Unit, open: () -> InputStream): IptvGuideRefresh {
        var ticket = request.ticket
        var retried = false
        while (true) {
            try {
                return open().use { input ->
                    store.importGuideResult(ticket, input, window, validators, limits, filter, checkCancellation, guideStorageCaps(window.fromMillis, window.untilMillis))
                        .let { IptvGuideRefresh.Guide(it.decision, it.issue) }
                }
            } catch (_: SQLiteFullException) {
                if (retried) return IptvGuideRefresh.StorageFull
                retried = true
                IptvLog.info("guide storage full, retrying without the previous copy")
                store.dropActive(ticket.ref)
                ticket = store.beginRefresh(ticket.ref)
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException || error is MetadataException || error is java.io.InterruptedIOException) throw error
                val issue = guideFailureIssue(error)
                IptvLog.failure("guide import", error)
                if (issue != null) return IptvGuideRefresh.Guide(RefreshDecision.INVALID, issue)
                throw MetadataException(MetadataFailure.INVALID_RESPONSE)
            }
        }
    }

    private companion object {
        const val COPY_BUFFER = 64 * 1024
        const val STALE_DOWNLOAD_MILLIS = 24 * 60 * 60 * 1000L
    }
}
