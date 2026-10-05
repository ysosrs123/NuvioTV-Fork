package com.nuvio.tv.ui.screens.iptv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.iptv.*
import com.nuvio.tv.core.iptv.RefreshDecision
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
    val selected: IptvSourceRef? = null, val linked: Set<String> = emptySet(),
    val busy: Boolean = false, val message: Int? = null, val form: IptvSourceForm? = null)

@HiltViewModel
class IptvSourcesViewModel @Inject constructor(private val catalogue: IptvCatalogueStore,
    private val guides: IptvGuideStore, private val access: IptvProfileAccess, profiles: ProfileManager) : ViewModel() {
    private val mutable = MutableStateFlow(IptvSourcesState())
    val state = mutable.asStateFlow()
    private var session: IptvProfileAccess.Session? = null
    private var operation: Job? = null
    private val playlists = IptvPlaylistRepository(catalogue)
    private val guideRepository = IptvGuideRepository(guides)
    init {
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
            catch (error: Exception) { if (session === current) mutable.update { it.copy(message =
                if (error is MetadataException && error.failure == MetadataFailure.AUTHENTICATION) R.string.iptv_xtream_auth_failed else R.string.iptv_setup_failed) } }
            finally { if (session === current) mutable.update { it.copy(busy = false, ready = true) } }
        }
    }
    private suspend fun reload(current: IptvProfileAccess.Session) {
        val oldSelected = mutable.value.selected
        val loaded = withContext(Dispatchers.IO) { access.use(current) {
            val sources = catalogue.sources(current.profileId)
            val selected = oldSelected?.takeIf { ref -> sources.any { it.ref == ref } } ?: sources.firstOrNull()?.ref
            Triple(sources, guides.feeds(current.profileId, limit = 200), selected?.let(catalogue::guideAssociations))
        } }
        if (session === current) mutable.update { it.copy(sources = loaded.first, feeds = loaded.second,
            selected = oldSelected?.takeIf { ref -> loaded.first.any { it.ref == ref } } ?: loaded.first.firstOrNull()?.ref,
            linked = loaded.third?.feedIds?.toSet().orEmpty(), ready = true) }
    }
    fun add(guide: Boolean, kind: IptvSourceKind = IptvSourceKind.M3U) { if (!mutable.value.busy && session != null) mutable.update { it.copy(form = IptvSourceForm(guide, kind = kind), message = null) } }
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
        withContext(Dispatchers.IO) { access.use(this@runOperation) {
            if (form.guide) {
                if (form.feed == null) guides.createFeed(profileId, label.trim(), endpoint.trim())
                else { require(form.feed.profileId == profileId); guides.editFeed(form.feed, label.trim(), endpoint.trim()) }
            } else {
                val connection = IptvSourceConnection(endpoint.trim(), username.takeIf { form.kind == IptvSourceKind.XTREAM }, password.takeIf { form.kind == IptvSourceKind.XTREAM })
                if (form.source == null) catalogue.createSource(profileId, label.trim(), form.kind, "shared-default", connection)
                else {
                    require(form.source.profileId == profileId)
                    val source = catalogue.sources(profileId).single { it.ref == form.source }
                    require(source.kind == form.kind)
                    catalogue.editSource(source.ref, label.trim(), source.kind, source.accountId, connection)
                }
            }
        } }
        if (session === this) mutable.update { it.copy(form = null, message = R.string.iptv_setup_saved) }
        reload(this)
    }
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
    fun refresh(source: IptvSource) = runOperation {
        require(source.ref.profileId == profileId)
        withContext(Dispatchers.IO) { access.use(this@runOperation) { catalogue.connection(source.ref) } }
        val result = playlists.refresh(source.ref)
        val message = when (result) {
            IptvPlaylistRefresh.Unchanged -> R.string.iptv_setup_refreshed
            is IptvPlaylistRefresh.Catalogue -> if (result.decision == RefreshDecision.PUBLISH) R.string.iptv_setup_refreshed else R.string.iptv_setup_kept_previous
            else -> R.string.iptv_setup_unsupported
        }
        if (session === this) mutable.update { it.copy(message = message) }
        reload(this)
    }
    fun refresh(feed: IptvGuideFeed) = runOperation {
        require(feed.ref.profileId == profileId)
        withContext(Dispatchers.IO) { access.use(this@runOperation) { guides.feed(feed.ref) } }
        val day = Math.floorDiv(System.currentTimeMillis(), 86_400_000L) * 86_400_000L
        val result = guideRepository.refresh(feed.ref, IptvGuideWindow(day - 86_400_000, day + 7 * 86_400_000))
        val message = when (result) {
            IptvGuideRefresh.Unchanged -> R.string.iptv_setup_refreshed
            IptvGuideRefresh.StorageFull -> R.string.iptv_setup_storage_full
            is IptvGuideRefresh.Guide -> if (result.decision == RefreshDecision.PUBLISH) R.string.iptv_setup_refreshed else R.string.iptv_setup_kept_previous
        }
        if (session === this) mutable.update { it.copy(message = message) }
        reload(this)
    }
}
