package com.nuvio.tv.ui.screens.iptv

import android.content.Context
import com.nuvio.tv.R
import com.nuvio.tv.core.iptv.LocalGuideFiles
import com.nuvio.tv.core.iptv.RefreshDecision
import com.nuvio.tv.core.iptv.XtreamGuideReference
import com.nuvio.tv.data.iptv.*
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class IptvRefreshPhase { QUEUED, DOWNLOADING, SAVING, GUIDE, DONE, FAILED }
data class IptvRefreshStatus(val phase: IptvRefreshPhase, val message: Int? = null, val finishedAtMillis: Long? = null) {
    val running: Boolean get() = phase != IptvRefreshPhase.DONE && phase != IptvRefreshPhase.FAILED
}

@Singleton
class IptvRefreshCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val catalogue: IptvCatalogueStore,
    private val guides: IptvGuideStore,
    private val access: IptvProfileAccess,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val heavy = Mutex()
    private val jobs = HashMap<String, Job>()
    private val attempts = HashMap<String, Long>()
    private val mutable = MutableStateFlow<Map<String, IptvRefreshStatus>>(emptyMap())
    val status: StateFlow<Map<String, IptvRefreshStatus>> = mutable.asStateFlow()

    val localGuides = LocalGuideFiles({ context.getExternalFilesDirs("iptv-guides").filterNotNull() })
    private val playlists = IptvPlaylistRepository(catalogue, xtreamGuides = IptvXtreamGuides(catalogue, guides))
    private val guideRepository = IptvGuideRepository(guides, openDocument = { address ->
        requireNotNull(context.contentResolver.openInputStream(android.net.Uri.parse(address)))
    }, xtreamConnection = catalogue::connection, openLocal = { localGuides.open(it) }, providerChannels = catalogue::guideChannelIds)

    fun refresh(session: IptvProfileAccess.Session, source: IptvSource): Boolean = start(key(source.ref)) {
        require(source.ref.profileId == session.profileId)
        withContext(Dispatchers.IO) { access.use(session) { catalogue.connection(source.ref) } }
        set(key(source.ref), IptvRefreshStatus(IptvRefreshPhase.DOWNLOADING))
        val result = playlists.refresh(source.ref) { set(key(source.ref), IptvRefreshStatus(IptvRefreshPhase.SAVING)) }
        val message = when (result) {
            IptvPlaylistRefresh.Unchanged -> R.string.iptv_setup_refreshed
            is IptvPlaylistRefresh.Catalogue -> if (result.decision == RefreshDecision.PUBLISH) R.string.iptv_setup_refreshed else R.string.iptv_setup_kept_previous
            else -> R.string.iptv_setup_unsupported
        }
        IptvLog.info("source refresh result=${result.javaClass.simpleName}${(result as? IptvPlaylistRefresh.Catalogue)?.let { " decision=${it.decision}" }.orEmpty()}")
        finish(key(source.ref), IptvRefreshPhase.DONE, message)
        (result as? IptvPlaylistRefresh.Catalogue)?.guide?.let { feed -> refreshGuide(session, feed) }
    }

    fun refresh(session: IptvProfileAccess.Session, feed: IptvGuideFeed): Boolean = start(key(feed.ref)) {
        refreshGuide(session, feed.ref)
    }

    fun refreshStale(session: IptvProfileAccess.Session, now: Long = System.currentTimeMillis()) {
        scope.launch {
            val (sources, feeds) = runCatching {
                withContext(Dispatchers.IO) { access.use(session) { catalogue.sources(session.profileId) to guides.feeds(session.profileId, limit = 200) } }
            }.getOrNull() ?: return@launch
            val provided = runCatching { withContext(Dispatchers.IO) { access.use(session) {
                feeds.filter { XtreamGuideReference.sourceId(guides.endpoint(it.ref)) != null }.map { it.ref }.toSet()
            } } }.getOrDefault(emptySet())
            for (source in sources) if (due(key(source.ref), source.refreshedAtMillis, SOURCE_INTERVAL, now)) refresh(session, source)
            for (feed in feeds) if (feed.ref !in provided && due(key(feed.ref), feed.refreshedAtMillis, GUIDE_INTERVAL, now)) refresh(session, feed)
        }
    }

    private suspend fun refreshGuide(session: IptvProfileAccess.Session, feed: IptvGuideRef) {
        set(key(feed), IptvRefreshStatus(IptvRefreshPhase.GUIDE))
        try {
            withContext(Dispatchers.IO) { access.use(session) { guides.feed(feed) } }
            val day = Math.floorDiv(System.currentTimeMillis(), DAY) * DAY
            val message = when (val result = guideRepository.refresh(feed, IptvGuideWindow(day - DAY, day + 7 * DAY))) {
                IptvGuideRefresh.Unchanged -> R.string.iptv_setup_refreshed
                IptvGuideRefresh.StorageFull -> R.string.iptv_setup_storage_full
                is IptvGuideRefresh.Guide -> if (result.decision == RefreshDecision.PUBLISH) R.string.iptv_setup_refreshed else R.string.iptv_setup_kept_previous
            }
            IptvLog.info("guide refresh done")
            finish(key(feed), IptvRefreshPhase.DONE, message)
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { IptvLog.failure("guide refresh", error); finish(key(feed), IptvRefreshPhase.FAILED, failureMessage(error)) }
    }

    suspend fun cancel(ref: IptvSourceRef) = cancel(key(ref))
    suspend fun cancel(ref: IptvGuideRef) = cancel(key(ref))
    private suspend fun cancel(key: String) {
        val job = synchronized(jobs) { jobs.remove(key) }
        job?.cancelAndJoin()
        mutable.update { it - key }
    }

    private fun start(key: String, block: suspend () -> Unit): Boolean = synchronized(jobs) {
        if (jobs[key]?.isActive == true) return false
        attempts[key] = System.currentTimeMillis()
        set(key, IptvRefreshStatus(IptvRefreshPhase.QUEUED))
        jobs[key] = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { heavy.withLock { block() } }
            catch (cancel: CancellationException) { finish(key, IptvRefreshPhase.FAILED, null); throw cancel }
            catch (error: Exception) { IptvLog.failure("refresh", error); finish(key, IptvRefreshPhase.FAILED, failureMessage(error)) }
        }
        true
    }

    private fun due(key: String, refreshedAt: Long?, interval: Long, now: Long): Boolean = synchronized(jobs) {
        jobs[key]?.isActive != true && (refreshedAt == null || now - refreshedAt >= interval) &&
            attempts[key]?.let { now - it >= RETRY_INTERVAL } != false
    }
    private fun set(key: String, value: IptvRefreshStatus) = mutable.update { it + (key to value) }
    private fun finish(key: String, phase: IptvRefreshPhase, message: Int?) =
        set(key, IptvRefreshStatus(phase, message, System.currentTimeMillis()))

    companion object {
        fun key(ref: IptvSourceRef) = "source:${ref.profileId}:${ref.sourceId}"
        fun key(ref: IptvGuideRef) = "feed:${ref.profileId}:${ref.feedId}"
        fun failureMessage(error: Exception): Int = when ((error as? MetadataException)?.failure) {
            MetadataFailure.AUTHENTICATION -> R.string.iptv_xtream_auth_failed
            MetadataFailure.INVALID_ADDRESS -> R.string.iptv_error_address
            MetadataFailure.NETWORK -> R.string.iptv_error_network
            MetadataFailure.HTTP_STATUS -> R.string.iptv_error_http
            MetadataFailure.REDIRECT_REQUIRES_REVIEW, MetadataFailure.REDIRECT_LIMIT -> R.string.iptv_error_redirect
            MetadataFailure.BODY_LIMIT -> R.string.iptv_error_too_large
            MetadataFailure.INVALID_RESPONSE -> R.string.iptv_error_response
            null -> R.string.iptv_setup_failed
        }
        private const val DAY = 86_400_000L
        private const val SOURCE_INTERVAL = 12 * 60 * 60 * 1000L
        private const val GUIDE_INTERVAL = 6 * 60 * 60 * 1000L
        private const val RETRY_INTERVAL = 30 * 60 * 1000L
    }
}
