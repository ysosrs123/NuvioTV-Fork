package com.nuvio.tv.ui.screens.iptv

import android.content.Context
import android.graphics.Bitmap
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.R
import com.nuvio.tv.core.iptv.GuideKey
import com.nuvio.tv.core.iptv.IptvDeviceProfile
import com.nuvio.tv.core.iptv.MultiviewLayout
import com.nuvio.tv.core.iptv.MultiviewQuality
import com.nuvio.tv.core.iptv.SetupAssignments
import com.nuvio.tv.core.iptv.SetupChange
import com.nuvio.tv.core.iptv.SetupChangeBook
import com.nuvio.tv.core.iptv.SetupChannel
import com.nuvio.tv.core.iptv.SetupChannelGuide
import com.nuvio.tv.core.iptv.SetupConnection
import com.nuvio.tv.core.iptv.SetupDraft
import com.nuvio.tv.core.iptv.SetupGuideChannel
import com.nuvio.tv.core.iptv.SetupGuideLinks
import com.nuvio.tv.core.iptv.SetupKind
import com.nuvio.tv.core.iptv.SetupLan
import com.nuvio.tv.core.iptv.SetupListing
import com.nuvio.tv.core.iptv.SetupListingItem
import com.nuvio.tv.core.iptv.SetupLookup
import com.nuvio.tv.core.iptv.SetupPhone
import com.nuvio.tv.core.iptv.SetupPhoneAccess
import com.nuvio.tv.core.iptv.SetupPhones
import com.nuvio.tv.core.iptv.SetupProfile
import com.nuvio.tv.core.iptv.SetupProfileChoice
import com.nuvio.tv.core.iptv.SetupSetting
import com.nuvio.tv.core.iptv.SetupSettings
import com.nuvio.tv.core.iptv.SetupSettingsChange
import com.nuvio.tv.core.iptv.SetupText
import com.nuvio.tv.core.iptv.XtreamGuideReference
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.core.recording.IptvRecorder
import com.nuvio.tv.core.server.IptvSetupAddress
import com.nuvio.tv.core.server.IptvSetupServer
import com.nuvio.tv.core.server.SetupRecorderSource
import com.nuvio.tv.data.iptv.IptvBrowseQuery
import com.nuvio.tv.data.iptv.IptvCatalogueItem
import com.nuvio.tv.data.iptv.IptvCatalogueStore
import com.nuvio.tv.data.iptv.IptvGuideFeed
import com.nuvio.tv.data.iptv.IptvGuideRef
import com.nuvio.tv.data.iptv.IptvGuideStore
import com.nuvio.tv.data.iptv.IptvLivePreferences
import com.nuvio.tv.data.iptv.IptvPhoneAccessStore
import com.nuvio.tv.data.iptv.IptvProfileAccess
import com.nuvio.tv.data.iptv.IptvSource
import com.nuvio.tv.data.iptv.IptvSourceConnection
import com.nuvio.tv.data.iptv.IptvSourceKind
import com.nuvio.tv.data.iptv.IptvSourceRef
import com.nuvio.tv.data.iptv.IptvStartView
import com.nuvio.tv.data.iptv.IptvStreamFormat
import com.nuvio.tv.data.iptv.IptvXtreamGuides
import com.nuvio.tv.data.iptv.MetadataException
import com.nuvio.tv.data.iptv.MetadataFailure
import com.nuvio.tv.data.local.ProfileLockStateDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class IptvSetupPhase { STARTING, RUNNING, NO_NETWORK, NOT_HOME_NETWORK, PORTS_BUSY, IDLE_STOPPED, PAUSED }

data class IptvSetupLine(@StringRes val label: Int, val value: String = "", @StringRes val valueRes: Int? = null) {
    override fun toString() = "IptvSetupLine(value withheld)"
}

class IptvSetupPending(val id: String, val change: SetupChange, val previousLabel: String?, val lines: List<IptvSetupLine>, val applying: Boolean = false,
    val title: String? = null) {
    val settings: Boolean get() = change is SetupSettingsChange || change is SetupGuideLinks || change is SetupChannelGuide
    val allow: Boolean get() = change is SetupProfileChoice
    fun applying() = IptvSetupPending(id, change, previousLabel, lines, true, title)
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
    val editingProfile: String? = null,
    val visible: Boolean = false,
    val keep: Boolean = false,
    val phones: List<SetupPhone> = emptyList(),
    val phoneAddress: String? = null,
) {
    override fun toString() = "IptvSetupState(phase=$phase, devices=$devices, keep=$keep, phones=${phones.size})"
}

@Singleton
class IptvSetupHost @Inject constructor(
    @ApplicationContext private val context: Context,
    private val catalogue: IptvCatalogueStore,
    private val guides: IptvGuideStore,
    private val access: IptvProfileAccess,
    private val profiles: ProfileManager,
    private val refresher: IptvRefreshCoordinator,
    private val livePreferences: IptvLivePreferences,
    private val device: IptvDeviceProfile,
    private val locks: ProfileLockStateDataStore,
    private val recorder: IptvRecorder,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = IptvPhoneAccessStore(context)
    private val paired = SetupPhones().also { it.load(store.phones) }
    @Volatile private var keep = store.enabled
    private val mutable = MutableStateFlow(IptvSetupState(keep = keep, phones = paired.phones))
    val state = mutable.asStateFlow()
    @Volatile private var session: IptvProfileAccess.Session? = null
    @Volatile private var listing = SetupListing()
    @Volatile private var pinLocked: Set<Int>? = null
    private var server: IptvSetupServer? = null
    private var monitor: Job? = null
    private var keeper: Job? = null
    private var visible = false
    @Volatile private var activeProfile = -1
    private val lookup = object : SetupLookup {
        override fun channels(sourceId: String, query: String): List<SetupChannel>? = withSession { current ->
            catalogue.page(IptvSourceRef(current.profileId, sourceId), IptvBrowseQuery(search = query), limit = SetupAssignments.MAX_RESULTS).items.map { setupChannel(it) }
        }
        override fun channel(sourceId: String, channelId: String): SetupChannel? = withSession { current ->
            catalogue.playbackItem(IptvSourceRef(current.profileId, sourceId), channelId)?.let { setupChannel(it) }
        }
        override fun guideChannels(feedId: String, query: String): List<SetupGuideChannel>? = withSession { current ->
            guides.searchChannels(IptvGuideRef(current.profileId, feedId), query, 200).map { SetupGuideChannel(it.externalId, it.names.firstOrNull()?.text ?: it.externalId) }
        }
    }
    private val phoneAccess = object : SetupPhoneAccess {
        override val enabled: Boolean get() = keep
        override fun issue(userAgent: String?, profile: Int): String? = if (!keep) null else paired.issue(userAgent, profile).also { savePhones() }
        override fun verify(token: String?): SetupPhone? = if (keep) paired.verify(token) else null
        override fun allowed(phone: SetupPhone): Boolean? {
            val current = session ?: return null
            val shown = listing
            if (shown.profile != current.profileId) return null
            return SetupPhones.canUse(phone, shown.profile, pinLocked)
        }
        override fun forget(token: String?): Boolean = paired.forget(token).also { if (it) savePhones() }
    }

    init {
        live.value = true
        scope.launch { locks.pinEnabled.collect { pins -> pinLocked = pins.filterValues { it }.keys } }
        scope.launch {
            combine(profiles.activeProfileId, profiles.activeProfileReady, profiles.profileSelectionRevision) { id, ready, revision -> Triple(id, ready, revision) }
                .collect { (id, ready, _) ->
                    val kept = server?.takeIf { keep }
                    if (kept != null) {
                        kept.rejectPending()
                        if (kept.pairingOpen) { kept.closePairing(); kept.openPairing() }
                        mutable.update { it.copy(pending = null) }
                    } else stopServer()
                    session = null
                    listing = SetupListing()
                    activeProfile = id
                    mutable.update { it.copy(editingProfile = null) }
                    if (ready) {
                        val current = withContext(Dispatchers.IO) { access.open(id) }
                        session = current
                        runCatching { reload(current) }.onFailure { if (it is CancellationException) throw it }
                        if (server == null && (keep || (visible && mutable.value.phase != IptvSetupPhase.IDLE_STOPPED))) startServer()
                    }
                }
        }
        if (keep) keepRunning()
    }

    fun attach() {
        if (keep) keepRunning()
    }

    fun enter() {
        mutable.update { it.copy(message = null, phase = if (it.phase == IptvSetupPhase.IDLE_STOPPED) IptvSetupPhase.STARTING else it.phase) }
    }

    fun start() {
        visible = true
        mutable.update { it.copy(visible = true) }
        val active = server
        if (active == null) {
            if (mutable.value.phase != IptvSetupPhase.IDLE_STOPPED) startServer()
            return
        }
        active.openPairing()
        mutable.update { it.copy(phase = IptvSetupPhase.RUNNING, code = active.code, message = null) }
        watchActive(active)
    }

    fun stop() {
        visible = false
        mutable.update { it.copy(visible = false) }
        val active = server
        if (active != null && keep) {
            active.closePairing()
            mutable.update { it.copy(address = null, qr = null, code = null, devices = 0) }
            watchActive(active)
            return
        }
        val wasRunning = active != null
        stopServer()
        if (wasRunning) mutable.update { it.copy(phase = IptvSetupPhase.PAUSED) }
    }

    fun restart() {
        if (visible) startServer()
    }

    fun setKeep(on: Boolean) {
        if (on == keep) return
        keep = on
        store.enabled = on
        if (on) keepRunning() else {
            keeper?.cancel()
            keeper = null
            paired.clear()
            savePhones()
            if (!visible) stopServer() else server?.touch()
        }
        mutable.update { it.copy(keep = on, phoneAddress = if (on) server?.phoneAddress else null) }
    }

    fun removePhone(id: String) {
        if (paired.remove(id)) savePhones()
    }

    fun removeAllPhones() {
        paired.clear()
        savePhones()
    }

    @Synchronized private fun savePhones() {
        store.phones = paired.encode()
        mutable.update { it.copy(phones = paired.phones) }
    }

    private fun keepRunning() {
        if (keeper?.isActive == true) return
        keeper = scope.launch {
            while (isActive && keep) {
                if (server == null && session != null) startServer()
                delay(KEEP_RETRY_MILLIS)
            }
        }
    }

    private fun startServer() {
        stopServer()
        if (!BuildConfig.FEATURE_IPTV_ENABLED) return
        val current = session
        if (current == null) {
            mutable.update { it.copy(phase = IptvSetupPhase.STARTING) }
            return
        }
        val ip = IptvSetupAddress.get(context)
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
            settings = ::settings,
            lookup = lookup,
            onChangeProposed = { origin, id, change, from, profile, phone -> scope.launch { propose(origin, id, change, sender(from, phone), profile) } },
            recordings = SetupRecorderSource(recorder),
            phones = phoneAccess,
            pairingOpen = visible,
            preferredPort = store.port,
        )
        if (started == null) {
            mutable.update { it.copy(phase = IptvSetupPhase.PORTS_BUSY, address = null, qr = null, code = null, devices = 0) }
            return
        }
        server = started
        if (store.port != started.port) store.port = started.port
        mutable.update { it.copy(phase = IptvSetupPhase.RUNNING, address = null, qr = null, code = if (visible) started.code else null, devices = 0, message = null,
            phoneAddress = if (keep) started.phoneAddress else null) }
        watchActive(started)
    }

    private fun watchActive(active: IptvSetupServer) {
        monitor?.cancel()
        monitor = scope.launch { watch(active) }
    }

    private suspend fun watch(active: IptvSetupServer) {
        var shownAddress: String? = null
        var waiting: Pair<String, Long>? = null
        var checked = System.currentTimeMillis()
        while (scope.isActive && server === active) {
            val now = System.currentTimeMillis()
            if (!keep && active.idleExpired) {
                stopServer()
                mutable.update { it.copy(phase = IptvSetupPhase.IDLE_STOPPED) }
                return
            }
            val pending = mutable.value.pending
            val since = waiting?.takeIf { it.first == pending?.id }?.second
            waiting = when {
                pending == null || pending.applying -> null
                since == null -> pending.id to now
                now - since >= PENDING_MILLIS -> {
                    active.resolve(pending.id, SetupChangeBook.Status.EXPIRED)
                    mutable.update { if (it.pending?.id == pending.id) it.copy(pending = null) else it }
                    null
                }
                else -> waiting
            }
            if (keep && now - checked >= ADDRESS_CHECK_MILLIS) {
                checked = now
                val ip = withContext(Dispatchers.IO) { IptvSetupAddress.get(context) }
                if (server !== active) return
                if (ip != active.boundHost) {
                    startServer()
                    return
                }
            }
            if (visible && active.pairingOpen) {
                val address = active.address
                if (address != shownAddress) {
                    val qr = withContext(Dispatchers.Default) { QrCodeGenerator.generate(address, 512) }
                    if (server !== active) return
                    shownAddress = address
                    mutable.update { it.copy(address = address, qr = qr) }
                }
                mutable.update { it.copy(code = active.code, devices = active.pairedDevices) }
            } else shownAddress = null
            delay(if (visible) 1_000 else HIDDEN_TICK_MILLIS)
        }
    }

    private fun stopServer() {
        monitor?.cancel()
        monitor = null
        server?.stop()
        server = null
        mutable.update { it.copy(address = null, qr = null, code = null, devices = 0, pending = null, phoneAddress = null) }
    }

    private fun sender(from: String, phone: SetupPhone?): String =
        phone?.let { context.getString(R.string.iptv_phone_sent_from, phoneName(context, it), from) } ?: from

    private suspend fun propose(origin: IptvSetupServer, id: String, change: SetupChange, from: String, profile: Int) {
        val active = server
        val current = session
        if (active !== origin || current == null || current.profileId != profile || mutable.value.pending != null) {
            origin.resolve(id, SetupChangeBook.Status.REJECTED)
            return
        }
        val draft = when (change) {
            is SetupDraft -> change
            is SetupSettingsChange -> {
                val lines = settingLines(change, settings(), from)
                if (lines.size < 2) origin.resolve(id, SetupChangeBook.Status.FAILED)
                else mutable.update { it.copy(pending = IptvSetupPending(id, change, null, lines), message = null) }
                return
            }
            is SetupGuideLinks, is SetupChannelGuide, is SetupProfileChoice -> {
                val pending = runCatching { withContext(Dispatchers.IO) { assignment(current, id, change, from) } }
                    .getOrElse { if (it is CancellationException) throw it; null }
                if (pending == null || server !== origin || session !== current || mutable.value.pending != null) origin.resolve(id, SetupChangeBook.Status.FAILED)
                else mutable.update { it.copy(pending = pending, message = null) }
                return
            }
        }
        val target = draft.targetId?.let { listing.find(draft.kind, it) }
        if (draft.edit && target == null) {
            origin.resolve(id, SetupChangeBook.Status.FAILED)
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
        scope.launch {
            val message = try {
                when (val change = pending.change) {
                    is SetupSettingsChange -> {
                        withContext(Dispatchers.IO) { applySettings(change) }
                        active?.resolve(pending.id, SetupChangeBook.Status.SAVED)
                        R.string.iptv_remote_settings_saved
                    }
                    is SetupGuideLinks -> {
                        val previous = withContext(Dispatchers.IO) { access.use(current) {
                            val ref = IptvSourceRef(current.profileId, change.sourceId)
                            val feeds = change.feeds.map { IptvGuideRef(current.profileId, it).also { feed -> guides.feed(feed) } }
                            catalogue.guideAssociations(ref).feedIds.map { IptvGuideRef(current.profileId, it) }.also { catalogue.setGuideFeeds(ref, feeds, feeds) }
                        } }
                        (previous + change.feeds.map { IptvGuideRef(current.profileId, it) }).distinct().forEach { refresher.refreshIfChanged(current, it) }
                        active?.resolve(pending.id, SetupChangeBook.Status.SAVED)
                        R.string.iptv_remote_links_saved
                    }
                    is SetupChannelGuide -> {
                        withContext(Dispatchers.IO) { access.use(current) {
                            val ref = IptvSourceRef(current.profileId, change.sourceId)
                            val item = requireNotNull(catalogue.playbackItem(ref, change.channelId))
                            val key = change.feedId?.let { GuideKey(it, requireNotNull(change.guideId)) }
                            require(key == null || key.feedId in catalogue.guideAssociations(ref).feedIds)
                            catalogue.setOverlay(ref, item.channel.id, item.overlay.copy(manualGuide = key))
                        } }
                        change.feedId?.let { refresher.refreshIfChanged(current, IptvGuideRef(current.profileId, it)) }
                        active?.resolve(pending.id, SetupChangeBook.Status.SAVED)
                        R.string.iptv_remote_channel_saved
                    }
                    is SetupProfileChoice -> {
                        val choice = withContext(Dispatchers.IO) { profileChoices() }.firstOrNull { it.id == change.profileId }
                        require(choice != null && !choice.locked)
                        val next = withContext(Dispatchers.IO) { access.open(choice.id) }
                        session = next
                        mutable.update { it.copy(editingProfile = choice.name.takeIf { choice.id != activeProfile }) }
                        reload(next)
                        active?.resolve(pending.id, SetupChangeBook.Status.SAVED)
                        R.string.iptv_remote_profile_switched
                    }
                    is SetupDraft -> {
                        val saved = withContext(Dispatchers.IO) { access.use(current) { save(current.profileId, change) } }
                        active?.resolve(pending.id, SetupChangeBook.Status.SAVED)
                        saved.source?.let { refresher.refresh(current, it) }
                        saved.feed?.let { refresher.refresh(current, it) }
                        if (change.kind.guide) R.string.iptv_remote_guide_saved else R.string.iptv_remote_saved
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                active?.resolve(pending.id, SetupChangeBook.Status.FAILED)
                IptvRefreshCoordinator.failureMessage(error)
            }
            mutable.update { it.copy(pending = null, message = message) }
            session?.let { latest -> runCatching { reload(latest) }.onFailure { if (it is CancellationException) throw it } }
        }
    }

    fun reject() {
        val pending = mutable.value.pending ?: return
        if (pending.applying) return
        server?.resolve(pending.id, SetupChangeBook.Status.REJECTED)
        mutable.update { it.copy(pending = null, message = R.string.iptv_remote_rejected) }
    }

    private fun settings(): SetupSettings = SetupSettings(SetupSettings.wire(livePreferences.defaultFormat), livePreferences.timeshift,
        livePreferences.sport, SetupSettings.wire(livePreferences.startView), SetupSettings.wire(livePreferences.multiviewLayout),
        SetupSettings.wire(livePreferences.multiviewQuality), livePreferences.recordEarlyMinutes, livePreferences.recordLateMinutes, device.maxTiles >= 2)

    private fun applySettings(change: SetupSettingsChange) {
        change.format?.let { livePreferences.defaultFormat = SetupSettings.choice(IptvStreamFormat.entries, it) }
        change.timeshift?.let { livePreferences.timeshift = it }
        change.sport?.let { livePreferences.sport = it }
        change.startView?.let { livePreferences.startView = SetupSettings.choice(IptvStartView.entries, it) }
        change.layout?.let { livePreferences.multiviewLayout = SetupSettings.choice(MultiviewLayout.entries, it) }
        change.quality?.let { livePreferences.multiviewQuality = SetupSettings.choice(MultiviewQuality.entries, it) }
        change.recordEarly?.let { livePreferences.recordEarlyMinutes = it }
        change.recordLate?.let { livePreferences.recordLateMinutes = it }
    }

    private fun settingLines(change: SetupSettingsChange, current: SetupSettings, from: String): List<IptvSetupLine> {
        val next = change.applied(current)
        return listOf(IptvSetupLine(R.string.iptv_remote_field_device, from)) + change.changes(current).map { setting ->
            when (setting) {
                SetupSetting.FORMAT -> line(R.string.iptv_live_format_title, formatName(current.format), formatName(next.format))
                SetupSetting.TIMESHIFT -> line(R.string.iptv_settings_timeshift, onOff(current.timeshift), onOff(next.timeshift))
                SetupSetting.SPORT -> line(R.string.iptv_settings_sport, onOff(current.sport), onOff(next.sport))
                SetupSetting.START_VIEW -> line(R.string.iptv_remote_setting_start, startName(current.startView), startName(next.startView))
                SetupSetting.LAYOUT -> line(R.string.iptv_remote_setting_layout, layoutName(current.layout), layoutName(next.layout))
                SetupSetting.QUALITY -> line(R.string.iptv_remote_setting_quality, qualityName(current.quality), qualityName(next.quality))
                SetupSetting.RECORD_EARLY -> line(R.string.iptv_settings_record_early, minutes(current.recordEarly), minutes(next.recordEarly))
                SetupSetting.RECORD_LATE -> line(R.string.iptv_settings_record_late, minutes(current.recordLate), minutes(next.recordLate))
            }
        }
    }

    private fun line(@StringRes label: Int, before: String, after: String) =
        IptvSetupLine(label, context.getString(R.string.iptv_remote_field_renamed, before, after))

    private fun onOff(value: Boolean): String = context.getString(if (value) R.string.iptv_remote_on else R.string.iptv_remote_off)

    private fun minutes(value: Int): String =
        if (value == 0) context.getString(R.string.iptv_settings_none) else context.resources.getQuantityString(R.plurals.iptv_settings_minutes, value, value)

    private fun formatName(wire: String): String = context.getString(when (SetupSettings.choice(IptvStreamFormat.entries, wire)) {
        IptvStreamFormat.AUTO -> R.string.iptv_live_format_auto
        IptvStreamFormat.HLS -> R.string.iptv_live_format_hls
        IptvStreamFormat.MPEG_TS -> R.string.iptv_live_format_ts
    })

    private fun startName(wire: String): String = context.getString(when (SetupSettings.choice(IptvStartView.entries, wire)) {
        IptvStartView.LAST -> R.string.iptv_settings_start_last
        IptvStartView.ALL -> R.string.iptv_live_all
        IptvStartView.FAVOURITES -> R.string.iptv_live_favourites
        IptvStartView.SPORT -> R.string.iptv_live_sports
    })

    private fun layoutName(wire: String): String = context.getString(when (SetupSettings.choice(MultiviewLayout.entries, wire)) {
        MultiviewLayout.GRID -> R.string.iptv_multiview_layout_grid
        MultiviewLayout.FOCUS -> R.string.iptv_multiview_layout_focus
        MultiviewLayout.SIDE_BY_SIDE -> R.string.iptv_multiview_layout_side
        MultiviewLayout.ONE_OVER_TWO -> R.string.iptv_multiview_layout_one_over_two
    })

    private fun qualityName(wire: String): String = context.getString(when (SetupSettings.choice(MultiviewQuality.entries, wire)) {
        MultiviewQuality.AUTO -> R.string.iptv_multiview_quality_auto
        MultiviewQuality.SHARPEST -> R.string.iptv_multiview_quality_sharpest
        MultiviewQuality.LIGHTEST -> R.string.iptv_multiview_quality_lightest
    })

    private fun save(profileId: Int, draft: SetupDraft): SavedEntry {
        if (draft.kind.guide) {
            val ref = draft.targetId?.let { IptvGuideRef(profileId, it) }
            val stored = ref?.let { guides.endpoint(it) }
            if (stored != null && XtreamGuideReference.sourceId(stored) != null) throw IllegalArgumentException("Automatic guide")
            val endpoint = draft.connection(stored?.let { SetupConnection(it) }).endpoint
            val feed = ref?.also { guides.editFeed(it, draft.label, endpoint) }
                ?: guides.createFeed(profileId, draft.label, endpoint).also { IptvXtreamGuides(catalogue, guides).linkNew(it, draft.sourceId?.let { id -> IptvSourceRef(profileId, id) }) }
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

    private fun <T> withSession(block: (IptvProfileAccess.Session) -> T): T? {
        val current = session ?: return null
        return runCatching { access.use(current) { block(current) } }.getOrNull()
    }

    private fun setupChannel(item: IptvCatalogueItem) = SetupChannel(item.channel.id, item.overlay.customName ?: item.channel.data.name,
        item.overlay.manualGuide?.feedId, item.overlay.manualGuide?.externalId)

    private suspend fun profileChoices(): List<SetupProfile> {
        val pins = runCatching { locks.pinEnabled.first() }.getOrDefault(emptyMap())
        return profiles.profiles.value.map { SetupProfile(it.id, it.name.take(80), it.id != activeProfile && pins[it.id] == true) }
    }

    private suspend fun assignment(current: IptvProfileAccess.Session, id: String, change: SetupChange, from: String): IptvSetupPending? = access.use(current) {
        val lines = mutableListOf(IptvSetupLine(R.string.iptv_remote_field_device, from))
        when (change) {
            is SetupGuideLinks -> {
                val ref = IptvSourceRef(current.profileId, change.sourceId)
                val source = catalogue.sources(current.profileId).firstOrNull { it.ref == ref } ?: return@use null
                val links = catalogue.guideAssociations(ref)
                fun names(ids: List<String>) = ids.joinToString(", ") { feedId -> runCatching { guides.feed(IptvGuideRef(current.profileId, feedId)).label }.getOrDefault("?") }
                    .ifEmpty { context.getString(R.string.iptv_settings_none) }
                lines += IptvSetupLine(R.string.iptv_remote_field_source, source.label)
                lines += line(R.string.iptv_remote_field_guides, names((links.priority + links.feedIds).distinct()), names(change.feeds))
                IptvSetupPending(id, change, null, profileLine(lines), title = context.getString(R.string.iptv_remote_confirm_guides_title, source.label))
            }
            is SetupChannelGuide -> {
                val ref = IptvSourceRef(current.profileId, change.sourceId)
                val source = catalogue.sources(current.profileId).firstOrNull { it.ref == ref } ?: return@use null
                val item = catalogue.playbackItem(ref, change.channelId) ?: return@use null
                fun describe(key: GuideKey?, name: String?) = if (key == null) context.getString(R.string.iptv_remote_guide_automatic)
                    else (runCatching { guides.feed(IptvGuideRef(current.profileId, key.feedId)).label }.getOrDefault("?") + " · " + (name ?: key.externalId))
                val next = change.feedId?.let { GuideKey(it, requireNotNull(change.guideId)) }
                lines += IptvSetupLine(R.string.iptv_remote_field_source, source.label)
                lines += IptvSetupLine(R.string.iptv_remote_field_channel, item.overlay.customName ?: item.channel.data.name)
                lines += line(R.string.iptv_remote_field_guide, describe(item.overlay.manualGuide, null), describe(next, change.guideName))
                IptvSetupPending(id, change, null, profileLine(lines),
                    title = context.getString(R.string.iptv_remote_confirm_channel_title, item.overlay.customName ?: item.channel.data.name))
            }
            is SetupProfileChoice -> null
            else -> null
        }
    } ?: if (change is SetupProfileChoice) profilePending(current, id, change, from) else null

    private suspend fun profilePending(current: IptvProfileAccess.Session, id: String, change: SetupProfileChoice, from: String): IptvSetupPending? {
        val choices = profileChoices()
        val target = choices.firstOrNull { it.id == change.profileId }?.takeIf { !it.locked && it.id != current.profileId } ?: return null
        val now = choices.firstOrNull { it.id == current.profileId }?.name ?: return null
        return IptvSetupPending(id, change, null, listOf(IptvSetupLine(R.string.iptv_remote_field_device, from), line(R.string.iptv_remote_field_profile, now, target.name)),
            title = context.getString(R.string.iptv_remote_confirm_profile_title))
    }

    private fun profileLine(lines: MutableList<IptvSetupLine>): List<IptvSetupLine> {
        mutable.value.editingProfile?.let { lines.add(1, IptvSetupLine(R.string.iptv_remote_field_profile, it)) }
        return lines
    }

    private suspend fun reload(current: IptvProfileAccess.Session) {
        val choices = profileChoices()
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
            SetupListing(sources, feeds, current.profileId, choices,
                sources.associate { item -> item.id to catalogue.guideAssociations(IptvSourceRef(current.profileId, item.id)).let { (it.priority + it.feedIds).distinct() } })
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
        draft.sourceId?.let { id -> listing.find(SetupKind.M3U, id) }?.let { add(IptvSetupLine(R.string.iptv_remote_field_source, it.label)) }
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

    private class SavedEntry(val source: IptvSource? = null, val feed: IptvGuideFeed? = null)

    companion object {
        private const val KEEP_RETRY_MILLIS = 60_000L
        private const val ADDRESS_CHECK_MILLIS = 30_000L
        private const val HIDDEN_TICK_MILLIS = 10_000L
        private const val PENDING_MILLIS = 10 * 60_000L
        val live = MutableStateFlow(false)

        suspend fun keepOn(context: Context): Boolean = withContext(Dispatchers.IO) { IptvPhoneAccessStore(context).enabled }

        fun phoneName(context: Context, phone: SetupPhone): String {
            val browser = phone.browser
            val platform = phone.platform
            return when {
                browser != null && platform != null -> context.getString(R.string.iptv_phone_name, browser, platform)
                browser != null -> browser
                platform != null -> platform
                else -> context.getString(R.string.iptv_phone_unknown)
            }
        }

        @StringRes fun kindLabel(kind: SetupKind): Int = when (kind) {
            SetupKind.M3U -> R.string.iptv_kind_m3u
            SetupKind.XTREAM -> R.string.iptv_kind_xtream
            SetupKind.STALKER -> R.string.iptv_kind_stalker
            SetupKind.GUIDE -> R.string.iptv_guide_xmltv
        }
    }
}

@HiltViewModel
class IptvSetupViewModel @Inject constructor(private val host: IptvSetupHost) : ViewModel() {
    val state = host.state

    init { host.enter() }

    fun start() = host.start()
    fun stop() = host.stop()
    fun restart() = host.restart()
    fun confirm() = host.confirm()
    fun reject() = host.reject()
    fun toggleKeep() = host.setKeep(!host.state.value.keep)
    fun removePhone(id: String) = host.removePhone(id)
    fun removeAllPhones() = host.removeAllPhones()
}

@HiltViewModel
class IptvPhoneRequestViewModel @Inject constructor(private val host: IptvSetupHost) : ViewModel() {
    val state = host.state

    fun confirm() = host.confirm()
    fun reject() = host.reject()
}
