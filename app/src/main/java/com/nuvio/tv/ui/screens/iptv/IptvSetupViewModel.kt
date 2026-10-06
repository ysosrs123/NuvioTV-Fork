package com.nuvio.tv.ui.screens.iptv

import android.content.Context
import android.graphics.Bitmap
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.R
import com.nuvio.tv.core.iptv.SetupChangeBook
import com.nuvio.tv.core.iptv.SetupConnection
import com.nuvio.tv.core.iptv.SetupDraft
import com.nuvio.tv.core.iptv.SetupKind
import com.nuvio.tv.core.iptv.SetupLan
import com.nuvio.tv.core.iptv.SetupListing
import com.nuvio.tv.core.iptv.SetupListingItem
import com.nuvio.tv.core.iptv.SetupText
import com.nuvio.tv.core.iptv.XtreamGuideReference
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.core.server.DeviceIpAddress
import com.nuvio.tv.core.server.IptvSetupServer
import com.nuvio.tv.data.iptv.IptvCatalogueStore
import com.nuvio.tv.data.iptv.IptvGuideFeed
import com.nuvio.tv.data.iptv.IptvGuideRef
import com.nuvio.tv.data.iptv.IptvGuideStore
import com.nuvio.tv.data.iptv.IptvProfileAccess
import com.nuvio.tv.data.iptv.IptvSource
import com.nuvio.tv.data.iptv.IptvSourceConnection
import com.nuvio.tv.data.iptv.IptvSourceKind
import com.nuvio.tv.data.iptv.MetadataException
import com.nuvio.tv.data.iptv.MetadataFailure
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class IptvSetupPhase { STARTING, RUNNING, NO_NETWORK, NOT_HOME_NETWORK, PORTS_BUSY, IDLE_STOPPED, PAUSED }

data class IptvSetupLine(@StringRes val label: Int, val value: String = "", @StringRes val valueRes: Int? = null)

class IptvSetupPending(val id: String, val draft: SetupDraft, val previousLabel: String?, val lines: List<IptvSetupLine>, val applying: Boolean = false) {
    fun applying() = IptvSetupPending(id, draft, previousLabel, lines, true)
    override fun toString() = "IptvSetupPending(values withheld)"
}

data class IptvSetupState(
    val phase: IptvSetupPhase = IptvSetupPhase.STARTING,
    val address: String? = null,
    val qr: Bitmap? = null,
    val code: String? = null,
    val devices: Int = 0,
    val pending: IptvSetupPending? = null,
    @StringRes val message: Int? = null,
) {
    override fun toString() = "IptvSetupState(phase=$phase, devices=$devices)"
}

@HiltViewModel
class IptvSetupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val catalogue: IptvCatalogueStore,
    private val guides: IptvGuideStore,
    private val access: IptvProfileAccess,
    profiles: ProfileManager,
    private val refresher: IptvRefreshCoordinator,
) : ViewModel() {
    private val mutable = MutableStateFlow(IptvSetupState())
    val state = mutable.asStateFlow()
    private var session: IptvProfileAccess.Session? = null
    @Volatile private var listing = SetupListing()
    private var server: IptvSetupServer? = null
    private var monitor: Job? = null
    private var visible = false

    init {
        viewModelScope.launch {
            combine(profiles.activeProfileId, profiles.activeProfileReady, profiles.profileSelectionRevision) { id, ready, revision -> Triple(id, ready, revision) }
                .collect { (id, ready, _) ->
                    stopServer()
                    session = null
                    listing = SetupListing()
                    if (ready) {
                        val current = withContext(Dispatchers.IO) { access.open(id) }
                        session = current
                        runCatching { reload(current) }.onFailure { if (it is CancellationException) throw it }
                        if (visible && mutable.value.phase != IptvSetupPhase.IDLE_STOPPED) startServer()
                    }
                }
        }
    }

    fun start() {
        visible = true
        if (server == null && mutable.value.phase != IptvSetupPhase.IDLE_STOPPED) startServer()
    }

    fun stop() {
        visible = false
        val wasRunning = server != null
        stopServer()
        if (wasRunning) mutable.update { it.copy(phase = IptvSetupPhase.PAUSED) }
    }

    fun restart() {
        if (visible) startServer()
    }

    private fun startServer() {
        stopServer()
        if (!BuildConfig.FEATURE_IPTV_ENABLED) return
        val current = session
        if (current == null) {
            mutable.update { it.copy(phase = IptvSetupPhase.STARTING) }
            return
        }
        val ip = DeviceIpAddress.get(context)
        val failure = when {
            ip == null -> IptvSetupPhase.NO_NETWORK
            !SetupLan.isLanAddress(ip) -> IptvSetupPhase.NOT_HOME_NETWORK
            else -> null
        }
        if (failure != null || ip == null) {
            mutable.update { it.copy(phase = failure ?: IptvSetupPhase.NO_NETWORK, address = null, qr = null, code = null, devices = 0) }
            return
        }
        val started = IptvSetupServer.start(
            host = ip,
            listing = { listing },
            onChangeProposed = { id, draft, from -> viewModelScope.launch { propose(current, id, draft, from) } }
        )
        if (started == null) {
            mutable.update { it.copy(phase = IptvSetupPhase.PORTS_BUSY, address = null, qr = null, code = null, devices = 0) }
            return
        }
        server = started
        mutable.update { it.copy(phase = IptvSetupPhase.RUNNING, address = null, qr = null, code = started.code, devices = 0, message = null) }
        monitor = viewModelScope.launch { watch(started) }
    }

    private suspend fun watch(active: IptvSetupServer) {
        var shownAddress: String? = null
        while (viewModelScope.isActive && server === active) {
            if (active.idleExpired) {
                stopServer()
                mutable.update { it.copy(phase = IptvSetupPhase.IDLE_STOPPED) }
                return
            }
            val address = active.address
            if (address != shownAddress) {
                val qr = withContext(Dispatchers.Default) { QrCodeGenerator.generate(address, 512) }
                if (server !== active) return
                shownAddress = address
                mutable.update { it.copy(address = address, qr = qr) }
            }
            mutable.update { it.copy(code = active.code, devices = active.pairedDevices) }
            delay(1_000)
        }
    }

    private fun stopServer() {
        monitor?.cancel()
        monitor = null
        server?.stop()
        server = null
        mutable.update { it.copy(address = null, qr = null, code = null, devices = 0, pending = null) }
    }

    private fun propose(current: IptvProfileAccess.Session, id: String, draft: SetupDraft, from: String) {
        val active = server ?: return
        if (session !== current || mutable.value.pending != null) {
            active.resolve(id, SetupChangeBook.Status.REJECTED)
            return
        }
        val target = draft.targetId?.let { listing.find(draft.kind, it) }
        if (draft.edit && target == null) {
            active.resolve(id, SetupChangeBook.Status.FAILED)
            return
        }
        mutable.update { it.copy(pending = IptvSetupPending(id, draft, target?.label, lines(draft, target, from)), message = null) }
    }

    fun confirm() {
        val pending = mutable.value.pending ?: return
        val current = session ?: return
        if (pending.applying) return
        val active = server
        mutable.update { it.copy(pending = pending.applying()) }
        viewModelScope.launch {
            val message = try {
                val saved = withContext(Dispatchers.IO) { access.use(current) { save(current.profileId, pending.draft) } }
                active?.resolve(pending.id, SetupChangeBook.Status.SAVED)
                saved.source?.let { refresher.refresh(current, it) }
                saved.feed?.let { refresher.refresh(current, it) }
                if (pending.draft.kind.guide) R.string.iptv_remote_guide_saved else R.string.iptv_remote_saved
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                active?.resolve(pending.id, SetupChangeBook.Status.FAILED)
                IptvRefreshCoordinator.failureMessage(error)
            }
            if (session === current) mutable.update { it.copy(pending = null, message = message) }
            runCatching { reload(current) }.onFailure { if (it is CancellationException) throw it }
        }
    }

    fun reject() {
        val pending = mutable.value.pending ?: return
        if (pending.applying) return
        server?.resolve(pending.id, SetupChangeBook.Status.REJECTED)
        mutable.update { it.copy(pending = null, message = R.string.iptv_remote_rejected) }
    }

    private fun save(profileId: Int, draft: SetupDraft): SavedEntry {
        if (draft.kind.guide) {
            val ref = draft.targetId?.let { IptvGuideRef(profileId, it) }
            val stored = ref?.let { guides.endpoint(it) }
            if (stored != null && XtreamGuideReference.sourceId(stored) != null) throw IllegalArgumentException("Automatic guide")
            val endpoint = draft.connection(stored?.let { SetupConnection(it) }).endpoint
            val feed = ref?.also { guides.editFeed(it, draft.label, endpoint) } ?: guides.createFeed(profileId, draft.label, endpoint)
            return SavedEntry(feed = guides.feed(feed))
        }
        val kind = IptvSourceKind.valueOf(draft.kind.name)
        val existing = draft.targetId?.let { id -> catalogue.sources(profileId).single { it.ref.sourceId == id } }
        if (existing != null && existing.kind != kind) throw IllegalArgumentException("Kind changed")
        val stored = existing?.let { catalogue.connection(it.ref) }
        val merged = draft.connection(stored?.let { SetupConnection(it.endpoint, it.username, it.password) })
        val uri = runCatching { java.net.URI(merged.endpoint) }.getOrNull()
        if (uri == null || uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.rawUserInfo != null)
            throw MetadataException(MetadataFailure.INVALID_ADDRESS)
        val connection = when (kind) {
            IptvSourceKind.XTREAM -> IptvSourceConnection(merged.endpoint, merged.username, merged.password)
            IptvSourceKind.STALKER -> IptvSourceConnection(merged.endpoint, merged.username)
            IptvSourceKind.M3U -> IptvSourceConnection(merged.endpoint)
        }
        return SavedEntry(source = if (existing == null) catalogue.createSource(profileId, draft.label, kind, "shared-default", connection).let { created ->
                val account = "src-" + created.ref.sourceId.take(76)
                catalogue.saveAccount(profileId, account, created.label, 1)
                catalogue.assignAccount(created.ref, account)
                catalogue.sources(profileId).single { it.ref == created.ref }
            }
            else catalogue.editSource(existing.ref, draft.label, existing.kind, existing.accountId, connection))
    }

    private suspend fun reload(current: IptvProfileAccess.Session) {
        val loaded = withContext(Dispatchers.IO) { access.use(current) {
            val sources = catalogue.sources(current.profileId).map { source ->
                val endpoint = catalogue.connection(source.ref).endpoint
                SetupListingItem(source.ref.sourceId, source.label, SetupKind.valueOf(source.kind.name),
                    SetupText.host(endpoint), editable = true, origin = SetupText.origin(endpoint))
            }
            val feeds = guides.feeds(current.profileId, limit = 200).map { feed ->
                val endpoint = guides.endpoint(feed.ref)
                val automatic = XtreamGuideReference.sourceId(endpoint) != null
                SetupListingItem(feed.ref.feedId, feed.label, SetupKind.GUIDE, if (automatic) null else SetupText.host(endpoint), editable = !automatic,
                    origin = SetupText.origin(endpoint))
            }
            SetupListing(sources, feeds)
        } }
        if (session === current) listing = loaded
    }

    private fun lines(draft: SetupDraft, target: SetupListingItem?, from: String): List<IptvSetupLine> = buildList {
        add(IptvSetupLine(R.string.iptv_remote_field_device, from))
        add(IptvSetupLine(R.string.iptv_remote_field_type, valueRes = kindLabel(draft.kind)))
        if (target == null) add(IptvSetupLine(R.string.iptv_remote_field_name, draft.label))
        else if (target.label != draft.label) add(IptvSetupLine(R.string.iptv_remote_field_name,
            context.getString(R.string.iptv_remote_field_renamed, target.label, draft.label)))
        if (target != null && draft.movesServer(target.origin)) {
            val next = SetupText.server(draft.address).orEmpty()
            val previous = SetupText.server(target.origin)
            add(IptvSetupLine(R.string.iptv_remote_field_server, if (previous == null) context.getString(R.string.iptv_remote_server_new, next)
                else context.getString(R.string.iptv_remote_server_changes, previous, next)))
        }
        if (draft.address.isNotEmpty()) add(IptvSetupLine(R.string.iptv_remote_field_address, SetupText.displayAddress(draft.address)))
        when (draft.kind) {
            SetupKind.XTREAM -> {
                if (draft.username.isNotEmpty()) add(IptvSetupLine(R.string.iptv_remote_field_username, draft.username))
                if (draft.password.isNotEmpty()) add(IptvSetupLine(R.string.iptv_remote_field_password,
                    valueRes = if (draft.edit) R.string.iptv_remote_password_new else R.string.iptv_remote_password_set))
            }
            SetupKind.STALKER -> if (draft.username.isNotEmpty()) add(IptvSetupLine(R.string.iptv_remote_field_mac, draft.username))
            else -> Unit
        }
    }

    override fun onCleared() {
        super.onCleared()
        server?.stop()
        server = null
    }

    private class SavedEntry(val source: IptvSource? = null, val feed: IptvGuideFeed? = null)

    companion object {
        @StringRes fun kindLabel(kind: SetupKind): Int = when (kind) {
            SetupKind.M3U -> R.string.iptv_kind_m3u
            SetupKind.XTREAM -> R.string.iptv_kind_xtream
            SetupKind.STALKER -> R.string.iptv_kind_stalker
            SetupKind.GUIDE -> R.string.iptv_guide_xmltv
        }
    }
}
