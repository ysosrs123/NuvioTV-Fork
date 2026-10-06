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
class IptvSourcesViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val catalogue: IptvCatalogueStore,
    private val guides: IptvGuideStore, private val access: IptvProfileAccess, profiles: ProfileManager) : ViewModel() {
    private val mutable = MutableStateFlow(IptvSourcesState())
    val state = mutable.asStateFlow()
    private var session: IptvProfileAccess.Session? = null
    private var operation: Job? = null
    private var background: Job? = null
    private val playlists = IptvPlaylistRepository(catalogue, xtreamGuides = IptvXtreamGuides(catalogue, guides))
    private val guideRepository = IptvGuideRepository(guides, openDocument = { address ->
        requireNotNull(context.contentResolver.openInputStream(android.net.Uri.parse(address)))
    }, xtreamConnection = catalogue::connection, openLocal = { localGuides.open(it) })
    val localGuides = com.nuvio.tv.core.iptv.LocalGuideFiles({ context.getExternalFilesDirs("iptv-guides").filterNotNull() })
    init {
        viewModelScope.launch {
            combine(profiles.activeProfileId, profiles.activeProfileReady, profiles.profileSelectionRevision) { id, ready, revision -> Triple(id, ready, revision) }
                .collect { (id, ready, revision) ->
                    session = null
                    mutable.value = IptvSourcesState(profileId = id, revision = revision)
                    operation?.cancelAndJoin()
                    background?.cancelAndJoin()
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
            catch (error: Exception) { if (session === current) mutable.update { it.copy(message = failureMessage(error)) } }
            finally { if (session === current) mutable.update { it.copy(busy = false, ready = true) } }
        }
    }
    private fun failureMessage(error: Exception): Int = when ((error as? MetadataException)?.failure) {
        MetadataFailure.AUTHENTICATION -> R.string.iptv_xtream_auth_failed
        MetadataFailure.INVALID_ADDRESS -> R.string.iptv_error_address
        MetadataFailure.NETWORK -> R.string.iptv_error_network
        MetadataFailure.HTTP_STATUS -> R.string.iptv_error_http
        MetadataFailure.REDIRECT_REQUIRES_REVIEW, MetadataFailure.REDIRECT_LIMIT -> R.string.iptv_error_redirect
        MetadataFailure.BODY_LIMIT -> R.string.iptv_error_too_large
        MetadataFailure.INVALID_RESPONSE -> R.string.iptv_error_response
        null -> R.string.iptv_setup_failed
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
        val created = withContext(Dispatchers.IO) { access.use(this@runOperation) {
            if (form.guide) {
                if (endpoint.trim().startsWith("content:")) {
                    context.contentResolver.takePersistableUriPermission(android.net.Uri.parse(endpoint.trim()), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                if (form.feed == null) guides.createFeed(profileId, label.trim(), endpoint.trim())
                else { require(form.feed.profileId == profileId); guides.editFeed(form.feed, label.trim(), endpoint.trim()) }
            } else {
                val address = endpoint.trim().let { if ("://" in it) it else "http://$it" }
                val uri = runCatching { java.net.URI(address) }.getOrNull()
                if (uri == null || uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.rawUserInfo != null)
                    throw MetadataException(MetadataFailure.INVALID_ADDRESS)
                val connection = IptvSourceConnection(address, username.trim().takeIf { form.kind == IptvSourceKind.XTREAM },
                    password.trim().takeIf { form.kind == IptvSourceKind.XTREAM })
                if (form.source == null) return@use catalogue.createSource(profileId, label.trim(), form.kind, "shared-default", connection)
                else {
                    require(form.source.profileId == profileId)
                    val source = catalogue.sources(profileId).single { it.ref == form.source }
                    require(source.kind == form.kind)
                    catalogue.editSource(source.ref, label.trim(), source.kind, source.accountId, connection)
                }
            }
            null
        } }
        if (session === this) mutable.update { it.copy(form = null, message = R.string.iptv_setup_saved) }
        reload(this)
        if (created != null) refreshSource(this, created)
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
    fun refresh(source: IptvSource) = runOperation { refreshSource(this, source) }
    private suspend fun refreshSource(current: IptvProfileAccess.Session, source: IptvSource) = with(current) {
        require(source.ref.profileId == profileId)
        withContext(Dispatchers.IO) { access.use(current) { catalogue.connection(source.ref) } }
        val result = playlists.refresh(source.ref)
        (result as? IptvPlaylistRefresh.Catalogue)?.guide?.let { feed ->
            if (background?.isActive != true) background = viewModelScope.launch {
                val day = Math.floorDiv(System.currentTimeMillis(), 86_400_000L) * 86_400_000L
                runCatching { guideRepository.refresh(feed, IptvGuideWindow(day - 86_400_000, day + 7 * 86_400_000)) }
                    .onFailure { if (it is CancellationException) throw it }
                if (session === current && operation?.isActive != true) reload(current)
            }
        }
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
