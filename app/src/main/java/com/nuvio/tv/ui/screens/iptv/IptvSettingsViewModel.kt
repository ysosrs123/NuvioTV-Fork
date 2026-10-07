package com.nuvio.tv.ui.screens.iptv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.iptv.IptvDeviceProfile
import com.nuvio.tv.core.iptv.MultiviewLayout
import com.nuvio.tv.core.iptv.MultiviewQuality
import com.nuvio.tv.core.iptv.RecordingFileSystem
import com.nuvio.tv.core.iptv.RecordingLocations
import com.nuvio.tv.core.iptv.RecordingShareAddress
import com.nuvio.tv.core.iptv.RecordingShareProtocol
import com.nuvio.tv.core.iptv.RecordingShareTarget
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.recording.IptvRecorder
import com.nuvio.tv.core.recording.IptvRecordingTargets
import com.nuvio.tv.core.recording.IptvVolumeCheck
import com.nuvio.tv.data.iptv.IptvAppearance
import com.nuvio.tv.data.iptv.IptvLivePreferences
import com.nuvio.tv.data.iptv.IptvLog
import com.nuvio.tv.data.iptv.IptvShareError
import com.nuvio.tv.data.iptv.IptvShareProbe
import com.nuvio.tv.domain.model.AppTheme
import com.nuvio.tv.data.iptv.IptvStartView
import com.nuvio.tv.data.iptv.IptvStreamFormat
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class IptvSettingsState(val format: IptvStreamFormat = IptvStreamFormat.AUTO, val timeshift: Boolean = true,
    val stats: Boolean = false, val startView: IptvStartView = IptvStartView.LAST, val sport: Boolean = true,
    val hiddenCategories: Int = 0, val multiview: Boolean = false, val layout: MultiviewLayout = MultiviewLayout.GRID,
    val quality: MultiviewQuality = MultiviewQuality.AUTO, val recordEarly: Int = 1, val recordLate: Int = 2,
    val preview: Boolean = true, val appearance: IptvAppearance = IptvAppearance(), val location: IptvLocationSummary = IptvLocationSummary())

data class IptvLocationSummary(val kind: IptvLocationKind = IptvLocationKind.DEVICE, val label: String? = null, val available: Boolean = true,
    val value: String = RecordingLocations.INTERNAL)

enum class IptvLocationKind { DEVICE, DRIVE, SHARE }

data class IptvLocationOption(val value: String, val kind: IptvLocationKind, val label: String?, val freeBytes: Long?, val fileSystem: RecordingFileSystem?)

class IptvShareForm(val server: String, val share: String, val folder: String, val username: String, val password: String, val domain: String,
    val guest: Boolean, val protocol: RecordingShareProtocol = RecordingShareProtocol.SMB, val secure: Boolean = false) {
    val target: RecordingShareTarget? get() = when (protocol) {
        RecordingShareProtocol.SMB -> RecordingShareAddress.parse(server, share, folder)
        RecordingShareProtocol.WEBDAV -> RecordingShareAddress.webDav(server, folder)
        RecordingShareProtocol.FTP -> RecordingShareAddress.ftp(server, folder, secure)
    }
    val anonymous: Boolean get() = if (protocol == RecordingShareProtocol.SMB) guest else username.isBlank()
    val plain: Boolean get() = protocol != RecordingShareProtocol.SMB && password.isNotEmpty() && target?.secure == false
    override fun toString(): String = "IptvShareForm(withheld)"
}

data class IptvShareStatus(val busy: Boolean = false, val message: Int? = null, val freeBytes: Long? = null, val ok: Boolean = false,
    val fingerprint: String? = null) {
    override fun toString(): String = "IptvShareStatus(withheld)"
}

@HiltViewModel
class IptvSettingsViewModel @Inject constructor(private val preferences: IptvLivePreferences, private val profiles: ProfileManager,
    private val device: IptvDeviceProfile, private val targets: IptvRecordingTargets, private val recorder: IptvRecorder) : ViewModel() {
    private val mutable = MutableStateFlow(IptvSettingsState())
    val state = mutable.asStateFlow()
    private val locationOptions = MutableStateFlow<List<IptvLocationOption>?>(null)
    val locations = locationOptions.asStateFlow()
    private val notices = MutableStateFlow<Int?>(null)
    val notice = notices.asStateFlow()
    private val forms = MutableStateFlow<IptvShareForm?>(null)
    val shareForm = forms.asStateFlow()
    private val shareStatuses = MutableStateFlow(IptvShareStatus())
    val shareStatus = shareStatuses.asStateFlow()
    private var shareJob: Job? = null
    private var offered: Pair<RecordingShareTarget, String>? = null
    private var trusted: Pair<RecordingShareTarget, String>? = null

    init { reload() }

    fun reload() {
        viewModelScope.launch {
            val profile = profiles.activeProfileId.value
            mutable.value = withContext(Dispatchers.IO) {
                IptvSettingsState(preferences.defaultFormat, preferences.timeshift, preferences.showStats, preferences.startView, preferences.sport,
                    preferences.hiddenCategoryCount(profile), device.maxTiles >= 2, preferences.multiviewLayout, preferences.multiviewQuality,
                    preferences.recordEarlyMinutes, preferences.recordLateMinutes, preferences.autoPreview, preferences.currentAppearance, summary())
            }
        }
    }

    fun setFormat(value: IptvStreamFormat) { preferences.defaultFormat = value; reload() }
    fun toggleTimeshift() { preferences.timeshift = !preferences.timeshift; reload() }
    fun toggleStats() { preferences.showStats = !preferences.showStats; reload() }
    fun setStartView(value: IptvStartView) { preferences.startView = value; reload() }
    fun toggleSport() { preferences.sport = !preferences.sport; reload() }
    fun setLayout(value: MultiviewLayout) { preferences.multiviewLayout = value; reload() }
    fun setQuality(value: MultiviewQuality) { preferences.multiviewQuality = value; reload() }
    fun setRecordEarly(minutes: Int) { preferences.recordEarlyMinutes = minutes; reload() }
    fun setRecordLate(minutes: Int) { preferences.recordLateMinutes = minutes; reload() }
    fun togglePreview() { preferences.autoPreview = !preferences.autoPreview; reload() }
    val density = MutableStateFlow(preferences.guideDensity)
    fun setDensity(value: com.nuvio.tv.core.iptv.GuideDensity) { preferences.guideDensity = value; density.value = value }
    fun setTheme(theme: AppTheme?) { preferences.updateAppearance { it.copy(theme = theme?.name) }; reload() }
    fun toggleBlack() { preferences.updateAppearance { it.copy(black = !it.black) }; reload() }
    fun toggleSolid() { preferences.updateAppearance { it.copy(solidPanels = !it.solidPanels) }; reload() }
    fun toggleArtwork() { preferences.updateAppearance { it.copy(plainBackground = !it.plainBackground) }; reload() }
    fun openLocations() {
        viewModelScope.launch {
            locationOptions.value = withContext(Dispatchers.IO) {
                val internal = IptvLocationOption(RecordingLocations.INTERNAL, IptvLocationKind.DEVICE, null,
                    runCatching { targets.internalDirectory().usableSpace }.getOrNull(), null)
                val drives = targets.volumes().map { IptvLocationOption(RecordingLocations.volume(it.id), IptvLocationKind.DRIVE, it.label, it.freeBytes, it.fileSystem) }
                val share = IptvLocationOption(RecordingLocations.SHARE, IptvLocationKind.SHARE, targets.shares.settings()?.label, preferences.shareFreeBytes, null)
                listOf(internal) + drives + share
            }
        }
    }

    fun closeLocations() { locationOptions.value = null }

    fun chooseLocation(option: IptvLocationOption) {
        when (option.kind) {
            IptvLocationKind.DEVICE -> { preferences.recordLocation = RecordingLocations.INTERNAL; locationOptions.value = null; reload() }
            IptvLocationKind.SHARE -> { locationOptions.value = null; openShare() }
            IptvLocationKind.DRIVE -> viewModelScope.launch {
                val id = RecordingLocations.volumeId(option.value) ?: return@launch
                val check = withContext(Dispatchers.IO) { targets.volumes().firstOrNull { it.id == id }?.let(targets::check) ?: IptvVolumeCheck.MISSING }
                locationOptions.value = null
                if (check == IptvVolumeCheck.OK) {
                    preferences.recordLocation = option.value
                    notices.value = if (option.fileSystem == RecordingFileSystem.FAT32 || option.fileSystem == RecordingFileSystem.UNKNOWN) R.string.iptv_location_parts_notice else null
                } else notices.value = when (check) {
                    IptvVolumeCheck.READ_ONLY -> R.string.iptv_location_read_only
                    IptvVolumeCheck.MISSING -> R.string.iptv_location_missing
                    else -> R.string.iptv_location_write_failed
                }
                reload()
            }
        }
    }

    fun dismissNotice() { notices.value = null }

    fun openShare() {
        viewModelScope.launch {
            shareStatuses.value = IptvShareStatus()
            offered = null
            trusted = null
            forms.value = withContext(Dispatchers.IO) {
                val settings = targets.shares.settings()
                val password = settings?.let { runCatching { targets.shares.password() }.getOrNull() }.orEmpty()
                settings?.let { IptvShareForm(RecordingShareAddress.address(it.target), if (it.target.protocol == RecordingShareProtocol.SMB) it.target.share else "",
                    it.target.folder, it.username, password, it.domain, it.guest, it.target.protocol, it.target.secure) }
                    ?: IptvShareForm("", "", "", "", "", "", false)
            }
        }
    }

    fun closeShare() {
        shareJob?.cancel()
        forms.value = null
    }

    fun testShare(form: IptvShareForm) {
        val target = form.target ?: run { shareStatuses.value = IptvShareStatus(message = invalidMessage(form)); return }
        shareJob?.cancel()
        shareStatuses.value = IptvShareStatus(busy = true)
        shareJob = viewModelScope.launch {
            val (result, certificate) = withContext(Dispatchers.IO) {
                val connector = targets.connector(target, form.username, domain(form), form.anonymous, form.password, pin(target))
                IptvShareProbe.run(connector, target.folder) to connector.certificate
            }
            result.freeBytes?.let { preferences.shareFreeBytes = it }
            offered = if (result.error == IptvShareError.CERTIFICATE && certificate != null) target to certificate else null
            shareStatuses.value = IptvShareStatus(message = shareMessage(result.error, target.protocol), freeBytes = result.freeBytes, ok = result.error == null,
                fingerprint = offered?.second)
        }
    }

    fun trustCertificate(form: IptvShareForm) {
        val certificate = offered ?: return
        if (form.target != certificate.first) return
        trusted = certificate
        offered = null
        testShare(form)
    }

    fun saveShare(form: IptvShareForm) {
        val target = form.target ?: run { shareStatuses.value = IptvShareStatus(message = invalidMessage(form)); return }
        if (form.protocol == RecordingShareProtocol.SMB && !form.guest && form.username.isBlank()) {
            shareStatuses.value = IptvShareStatus(message = R.string.iptv_share_username_needed)
            return
        }
        shareJob?.cancel()
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                try { targets.shares.save(target, form.username, domain(form), form.anonymous, form.password, pin(target)); true } catch (error: Exception) {
                    IptvLog.failure("share settings save", error)
                    false
                }
            }
            if (!saved) { shareStatuses.value = IptvShareStatus(message = R.string.iptv_share_save_failed); return@launch }
            preferences.recordLocation = RecordingLocations.SHARE
            forms.value = null
            recorder.retryUploads()
            reload()
        }
    }

    private fun pin(target: RecordingShareTarget): String? =
        trusted?.takeIf { it.first == target }?.second ?: targets.shares.settings()?.takeIf { it.target == target }?.pin

    private fun domain(form: IptvShareForm): String = if (form.protocol == RecordingShareProtocol.SMB) form.domain else ""

    private fun invalidMessage(form: IptvShareForm): Int =
        if (form.protocol == RecordingShareProtocol.SMB) R.string.iptv_share_invalid else R.string.iptv_network_invalid

    private fun summary(): IptvLocationSummary {
        val choice = preferences.recordLocation
        RecordingLocations.volumeId(choice)?.let { id ->
            val volume = targets.volumes().firstOrNull { it.id == id }
            return IptvLocationSummary(IptvLocationKind.DRIVE, volume?.label, volume?.mounted == true, choice)
        }
        if (choice == RecordingLocations.SHARE) return targets.shares.settings().let { IptvLocationSummary(IptvLocationKind.SHARE, it?.label, it != null, choice) }
        return IptvLocationSummary()
    }

    fun unhideCategories() {
        val profile = profiles.activeProfileId.value
        viewModelScope.launch { withContext(Dispatchers.IO) { preferences.unhideCategories(profile) }; reload() }
    }
}

fun shareMessage(error: IptvShareError?, protocol: RecordingShareProtocol = RecordingShareProtocol.SMB): Int = when (error) {
    null -> R.string.iptv_share_ok
    IptvShareError.SHARE_NOT_FOUND -> if (protocol == RecordingShareProtocol.SMB) R.string.iptv_share_not_found else R.string.iptv_network_address_not_found
    IptvShareError.CERTIFICATE -> R.string.iptv_network_certificate_untrusted
    IptvShareError.NO_TLS -> R.string.iptv_network_no_tls
    IptvShareError.TLS_REUSE -> R.string.iptv_network_tls_reuse
    IptvShareError.UNREACHABLE -> R.string.iptv_share_unreachable
    IptvShareError.TIMEOUT -> R.string.iptv_share_timeout
    IptvShareError.LOGIN_REFUSED -> R.string.iptv_share_login_refused
    IptvShareError.GUEST_REFUSED -> R.string.iptv_share_guest_refused
    IptvShareError.FOLDER_NOT_FOUND -> R.string.iptv_share_folder_not_found
    IptvShareError.ACCESS_DENIED -> R.string.iptv_share_access_denied
    IptvShareError.READ_ONLY -> R.string.iptv_share_read_only
    IptvShareError.FULL -> R.string.iptv_share_full
    IptvShareError.SMB1_ONLY -> R.string.iptv_share_smb1
    IptvShareError.DISCONNECTED, IptvShareError.LOST, IptvShareError.OTHER -> R.string.iptv_share_failed
}
