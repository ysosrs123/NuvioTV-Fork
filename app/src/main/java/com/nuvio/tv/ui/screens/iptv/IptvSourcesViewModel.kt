package com.nuvio.tv.ui.screens.iptv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.iptv.*
import com.nuvio.tv.core.iptv.RefreshDecision
import com.nuvio.tv.core.iptv.StalkerPortal
import com.nuvio.tv.core.iptv.XtreamGuideReference
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class IptvSourceForm(val guide: Boolean, val source: IptvSourceRef? = null, val feed: IptvGuideRef? = null,
    val label: String = "", val endpoint: String = "", val kind: IptvSourceKind = IptvSourceKind.M3U,
    val username: String = "", val password: String = "") {
    override fun toString() = "IptvSourceForm(values withheld)"
}
data class IptvSourcesState(val profileId: Int = 0, val revision: Long = 0, val ready: Boolean = false,
    val sources: List<IptvSource> = emptyList(), val feeds: List<IptvGuideFeed> = emptyList(),
    val selected: IptvSourceRef? = null, val linked: Set<String> = emptySet(), val linkedOrder: List<String> = emptyList(),
    val busy: Boolean = false, val message: Int? = null, val form: IptvSourceForm? = null,
    val refresh: Map<String, IptvRefreshStatus> = emptyMap(), val counts: Map<String, Int> = emptyMap(),
    val automatic: Set<String> = emptySet())

@HiltViewModel
class IptvSourcesViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val catalogue: IptvCatalogueStore,
    private val guides: IptvGuideStore, private val access: IptvProfileAccess, profiles: ProfileManager,
    private val refresher: IptvRefreshCoordinator) : ViewModel() {
    private val mutable = MutableStateFlow(IptvSourcesState())
    val state = mutable.asStateFlow()
    private var session: IptvProfileAccess.Session? = null
    private var operation: Job? = null
    private val xtreamGuides = IptvXtreamGuides(catalogue, guides)
    val localGuides get() = refresher.localGuides
    init {
        viewModelScope.launch {
            var previous = emptyMap<String, IptvRefreshStatus>()
            refresher.status.collect { statuses ->
                mutable.update { it.copy(refresh = statuses) }
                val changed = statuses.any { (key, value) -> previous[key]?.phase != value.phase && value.phase in RELOAD_PHASES }
                previous = statuses
                val current = session
                if (changed && current != null && operation?.isActive != true) operation = viewModelScope.launch {
                    runCatching { reload(current) }.onFailure { if (it is CancellationException) throw it }
                }
            }
        }
        viewModelScope.launch {
            combine(profiles.activeProfileId, profiles.activeProfileReady, profiles.profileSelectionRevision) { id, ready, revision -> Triple(id, ready, revision) }
                .collect { (id, ready, revision) ->
                    session = null
                    mutable.value = IptvSourcesState(profileId = id, revision = revision)
                    operation?.cancelAndJoin()
                    if (ready) {
                        val current = withContext(Dispatchers.IO) { access.open(id) }
                        session = current
                        runOperation { reload(this) }
                        refresher.refreshStale(current)
                    }
                }
        }
    }
    private fun runOperation(block: suspend IptvProfileAccess.Session.() -> Unit) {
        if (operation?.isActive == true) return
        val current = session ?: return
        mutable.update { it.copy(busy = true, message = null) }
        operation = viewModelScope.launch {
            try { current.block() }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { if (session === current) mutable.update { it.copy(message = IptvRefreshCoordinator.failureMessage(error)) } }
            finally { if (session === current) mutable.update { it.copy(busy = false, ready = true) } }
        }
    }
    private suspend fun reload(current: IptvProfileAccess.Session) {
        val oldSelected = mutable.value.selected
        var automatic = emptySet<String>()
        val (loaded, counts) = withContext(Dispatchers.IO) { access.use(current) {
            val sources = catalogue.sources(current.profileId)
            val selected = oldSelected?.takeIf { ref -> sources.any { it.ref == ref } } ?: sources.firstOrNull()?.ref
            val feeds = guides.feeds(current.profileId, limit = 200)
            automatic = feeds.filter { XtreamGuideReference.sourceId(guides.endpoint(it.ref)) != null }.map { it.ref.feedId }.toSet()
            Triple(sources, feeds, selected?.let(catalogue::guideAssociations)) to catalogue.channelCounts(current.profileId)
        } }
        if (session === current) mutable.update { it.copy(sources = loaded.first, feeds = loaded.second, counts = counts, automatic = automatic,
            selected = oldSelected?.takeIf { ref -> loaded.first.any { it.ref == ref } } ?: loaded.first.firstOrNull()?.ref,
            linked = loaded.third?.feedIds?.toSet().orEmpty(),
            linkedOrder = loaded.third?.let { (it.priority + it.feedIds).distinct() }.orEmpty(), ready = true) }
    }
    fun add(guide: Boolean, kind: IptvSourceKind = IptvSourceKind.M3U) { if (!mutable.value.busy && session != null) mutable.update { it.copy(form = IptvSourceForm(guide, kind = kind), message = null) } }
    fun documentUnavailable() { mutable.update { it.copy(message = R.string.iptv_guide_picker_unavailable) } }
    fun dismiss() { if (!mutable.value.busy) mutable.update { it.copy(form = null) } }
    fun select(ref: IptvSourceRef) = runOperation {
        require(ref.profileId == profileId)
        mutable.update { it.copy(selected = ref) }; reload(this)
    }
    fun edit(source: IptvSource) = runOperation {
        val form = withContext(Dispatchers.IO) { access.use(this@runOperation) {
            require(source.ref.profileId == profileId)
            val connection = catalogue.connection(source.ref)
            IptvSourceForm(false, source = source.ref, label = source.label, endpoint = connection.endpoint,
                kind = source.kind, username = connection.username.orEmpty(), password = connection.password.orEmpty())
        } }
        if (session === this) mutable.update { it.copy(form = form) }
    }
    fun edit(feed: IptvGuideFeed) = runOperation {
        val form = withContext(Dispatchers.IO) { access.use(this@runOperation) {
            require(feed.ref.profileId == profileId)
            IptvSourceForm(true, feed = feed.ref, label = feed.label, endpoint = guides.endpoint(feed.ref))
        } }
        if (session === this) mutable.update { it.copy(form = form) }
    }
    fun save(form: IptvSourceForm, label: String, endpoint: String, username: String = "", password: String = "") = runOperation {
        val saved = withContext(Dispatchers.IO) { access.use(this@runOperation) {
            if (form.guide) {
                if (endpoint.trim().startsWith("content:")) {
                    context.contentResolver.takePersistableUriPermission(android.net.Uri.parse(endpoint.trim()), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val feed = form.feed?.also { require(it.profileId == profileId); guides.editFeed(it, label.trim(), endpoint.trim()) }
                    ?: guides.createFeed(profileId, label.trim(), endpoint.trim())
                SavedEntry(feed = guides.feed(feed))
            } else {
                val address = endpoint.trim().let { if ("://" in it) it else "http://$it" }
                val uri = runCatching { java.net.URI(address) }.getOrNull()
                if (uri == null || uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.rawUserInfo != null)
                    throw MetadataException(MetadataFailure.INVALID_ADDRESS)
                val connection = when (form.kind) {
                    IptvSourceKind.XTREAM -> IptvSourceConnection(address, username.trim(), password.trim())
                    IptvSourceKind.STALKER -> IptvSourceConnection(address,
                        StalkerPortal.normalizeMac(username) ?: throw MetadataException(MetadataFailure.INVALID_ADDRESS))
                    IptvSourceKind.M3U -> IptvSourceConnection(address)
                }
                SavedEntry(source = if (form.source == null) catalogue.createSource(profileId, label.trim(), form.kind, "shared-default", connection)
                else {
                    require(form.source.profileId == profileId)
                    val source = catalogue.sources(profileId).single { it.ref == form.source }
                    require(source.kind == form.kind)
                    catalogue.editSource(source.ref, label.trim(), source.kind, source.accountId, connection)
                })
            }
        } }
        if (session === this) mutable.update { it.copy(form = null, message = R.string.iptv_setup_saved) }
        reload(this)
        saved.source?.let { refresher.refresh(this, it) }
        saved.feed?.let { refresher.refresh(this, it) }
    }
    private class SavedEntry(val source: IptvSource? = null, val feed: IptvGuideFeed? = null)
    fun link(feed: IptvGuideFeed) = runOperation {
        val selected = mutable.value.selected ?: return@runOperation
        withContext(Dispatchers.IO) { access.use(this@runOperation) {
            require(selected.profileId == profileId && feed.ref.profileId == profileId)
            guides.feed(feed.ref)
            val previous = catalogue.guideAssociations(selected)
            val ids = if (feed.ref.feedId in previous.feedIds) previous.feedIds - feed.ref.feedId else previous.feedIds + feed.ref.feedId
            catalogue.setGuideFeeds(selected, ids.map { IptvGuideRef(profileId, it) }, previous.priority.filter { it in ids }.map { IptvGuideRef(profileId, it) })
        } }
        reload(this)
    }
    fun moveGuideUp(feed: IptvGuideFeed) = runOperation {
        val selected = mutable.value.selected ?: return@runOperation
        withContext(Dispatchers.IO) { access.use(this@runOperation) {
            require(selected.profileId == profileId && feed.ref.profileId == profileId)
            val previous = catalogue.guideAssociations(selected)
            val order = (previous.priority + previous.feedIds).distinct().toMutableList()
            val index = order.indexOf(feed.ref.feedId)
            if (index > 0) {
                order.add(index - 1, order.removeAt(index))
                catalogue.setGuideFeeds(selected, previous.feedIds.map { IptvGuideRef(profileId, it) }, order.map { IptvGuideRef(profileId, it) })
            }
        } }
        reload(this)
    }
    fun moveUp(source: IptvSource) = runOperation {
        val index = mutable.value.sources.indexOfFirst { it.ref == source.ref }
        if (index > 0) withContext(Dispatchers.IO) { access.use(this@runOperation) {
            require(source.ref.profileId == profileId)
            catalogue.moveSource(source.ref, index - 1)
        } }
        reload(this)
    }
    fun remove(source: IptvSource) = runOperation {
        withContext(Dispatchers.IO) { access.use(this@runOperation) {
            require(source.ref.profileId == profileId)
            xtreamGuides.removeSource(source.ref)
        } }
        if (session === this) mutable.update { it.copy(message = R.string.iptv_source_removed) }
        reload(this)
    }
    fun remove(feed: IptvGuideFeed) = runOperation {
        withContext(Dispatchers.IO) { access.use(this@runOperation) {
            require(feed.ref.profileId == profileId)
            xtreamGuides.removeFeed(feed.ref)
        } }
        if (session === this) mutable.update { it.copy(message = R.string.iptv_guide_removed) }
        reload(this)
    }
    fun refresh(source: IptvSource) { session?.let { refresher.refresh(it, source) } }
    fun refresh(feed: IptvGuideFeed) { session?.let { refresher.refresh(it, feed) } }
    private companion object {
        val RELOAD_PHASES = setOf(IptvRefreshPhase.GUIDE, IptvRefreshPhase.DONE, IptvRefreshPhase.FAILED)
    }
}
