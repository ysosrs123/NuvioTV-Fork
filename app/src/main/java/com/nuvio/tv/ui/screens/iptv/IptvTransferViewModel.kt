package com.nuvio.tv.ui.screens.iptv

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.R
import com.nuvio.tv.core.iptv.SetupBundle
import com.nuvio.tv.core.iptv.SetupBundleSummary
import com.nuvio.tv.core.iptv.SetupBundles
import com.nuvio.tv.core.iptv.SetupCryptoException
import com.nuvio.tv.core.iptv.SetupImportMode
import com.nuvio.tv.core.iptv.SetupInputException
import com.nuvio.tv.core.iptv.SetupLan
import com.nuvio.tv.core.iptv.SetupTransferCrypto
import com.nuvio.tv.core.iptv.SetupVault
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.server.IptvSetupAddress
import com.nuvio.tv.core.server.IptvSetupTransferClient
import com.nuvio.tv.core.server.IptvSetupTransferDiscovery
import com.nuvio.tv.core.server.IptvSetupTransferServer
import com.nuvio.tv.core.server.IptvTransferPeer
import com.nuvio.tv.data.iptv.IptvCatalogueStore
import com.nuvio.tv.data.iptv.IptvGuideStore
import com.nuvio.tv.data.iptv.IptvLivePreferences
import com.nuvio.tv.data.iptv.IptvLog
import com.nuvio.tv.data.iptv.IptvProfileAccess
import com.nuvio.tv.data.iptv.IptvSetupBundles
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class IptvTransferMode { SEND, RECEIVE, BACKUP, RESTORE }

data class IptvBackupFile(val name: String, val path: String, val bytes: Long, val modifiedMillis: Long, val usb: Boolean) {
    override fun toString() = "IptvBackupFile(values withheld)"
}

data class IptvTransferReview(val summary: SetupBundleSummary, val reused: Int, val created: Int, val fromFile: Boolean)

data class IptvTransferState(
    val mode: IptvTransferMode? = null,
    val busy: Boolean = false,
    @StringRes val message: Int? = null,
    val messageArg: String = "",
    val peers: List<IptvTransferPeer> = emptyList(),
    val code: String? = null,
    val address: String? = null,
    val review: IptvTransferReview? = null,
    val files: List<IptvBackupFile>? = null,
    val folders: List<String> = emptyList(),
    val usb: Boolean = false,
    val chosen: String? = null,
    val finished: Boolean = false,
) {
    override fun toString() = "IptvTransferState(mode=$mode, busy=$busy)"
}

@HiltViewModel
class IptvTransferViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    catalogue: IptvCatalogueStore,
    private val guides: IptvGuideStore,
    private val access: IptvProfileAccess,
    private val profiles: ProfileManager,
    private val refresher: IptvRefreshCoordinator,
    livePreferences: IptvLivePreferences,
) : ViewModel() {
    private val mutable = MutableStateFlow(IptvTransferState())
    val state = mutable.asStateFlow()
    private val bundles = IptvSetupBundles(catalogue, guides, livePreferences)
    private val catalogueStore = catalogue
    private val discovery = IptvSetupTransferDiscovery(context)
    private val client = IptvSetupTransferClient()
    private var server: IptvSetupTransferServer? = null
    private var monitor: Job? = null
    private var work: Job? = null
    @Volatile private var incoming: SetupBundle? = null
    private var chosenFile: File? = null
    private var chosenUri: Uri? = null

    private fun session(): IptvProfileAccess.Session? =
        if (profiles.activeProfileReady.value) access.open(profiles.activeProfileId.value) else null

    fun open(mode: IptvTransferMode) {
        close()
        if (!BuildConfig.FEATURE_IPTV_ENABLED) return
        mutable.value = IptvTransferState(mode = mode)
        when (mode) {
            IptvTransferMode.SEND -> discovery.discover(IptvSetupAddress.get(context)?.let { "$it:${IptvSetupTransferServer.START_PORT}" }) { peers ->
                mutable.update { it.copy(peers = peers) }
            }
            IptvTransferMode.RECEIVE -> startReceiving()
            IptvTransferMode.BACKUP -> mutable.update { it.copy(folders = folders().map { folder -> folder.path }, usb = folders().size > 1) }
            IptvTransferMode.RESTORE -> listBackups()
        }
    }

    fun close() {
        work?.cancel()
        work = null
        stopReceiving()
        discovery.stopDiscovery()
        incoming = null
        chosenFile = null
        chosenUri = null
        mutable.value = IptvTransferState()
    }

    fun send(address: String, code: String, logins: Boolean) {
        val current = session() ?: return
        val digits = code.filter(Char::isDigit)
        if (IptvSetupTransferClient.base(address) == null) { mutable.update { it.copy(message = R.string.iptv_copy_address_invalid) }; return }
        if (!SetupTransferCrypto.validCode(digits)) { mutable.update { it.copy(message = R.string.iptv_copy_code_invalid) }; return }
        launchWork {
            val payload = withContext(Dispatchers.IO) { access.use(current) { SetupBundles.encode(bundles.export(current.profileId, logins)).toByteArray(Charsets.UTF_8) } }
            val result = withContext(Dispatchers.IO) { client.send(address, digits, payload) }
            IptvLog.info("setup copy result=${result.javaClass.simpleName}")
            when (result) {
                IptvSetupTransferClient.Result.Sent -> mutable.update { it.copy(message = R.string.iptv_copy_sent, finished = true) }
                is IptvSetupTransferClient.Result.WrongCode -> mutable.update { it.copy(message = R.string.iptv_copy_wrong_code) }
                IptvSetupTransferClient.Result.Renewed -> mutable.update { it.copy(message = R.string.iptv_copy_code_renewed) }
                IptvSetupTransferClient.Result.Busy -> mutable.update { it.copy(message = R.string.iptv_copy_busy) }
                IptvSetupTransferClient.Result.Unreachable -> mutable.update { it.copy(message = R.string.iptv_copy_unreachable) }
                IptvSetupTransferClient.Result.Refused -> mutable.update { it.copy(message = R.string.iptv_copy_refused) }
                IptvSetupTransferClient.Result.TooLarge -> mutable.update { it.copy(message = R.string.iptv_copy_too_large) }
            }
        }
    }

    private fun startReceiving() {
        val ip = IptvSetupAddress.get(context)
        if (ip == null || !SetupLan.isLanAddress(ip)) { mutable.update { it.copy(message = R.string.iptv_remote_no_network) }; return }
        val started = IptvSetupTransferServer.start(ip, Build.MODEL.orEmpty().take(60).ifBlank { "TV" }, onPayload = ::received)
        if (started == null) { mutable.update { it.copy(message = R.string.iptv_remote_ports_busy) }; return }
        server = started
        discovery.register("Nuvio " + Build.MODEL.orEmpty().take(40), started.port, started.address)
        mutable.update { it.copy(code = started.code, address = started.address) }
        monitor = viewModelScope.launch {
            while (isActive && server === started) {
                if (started.idleExpired) {
                    stopReceiving()
                    mutable.update { it.copy(code = null, address = null, message = R.string.iptv_copy_receive_stopped) }
                    return@launch
                }
                mutable.update { it.copy(code = started.code) }
                delay(1_000)
            }
        }
    }

    private fun stopReceiving() {
        monitor?.cancel()
        monitor = null
        server?.stop()
        server = null
        discovery.unregister()
    }

    private fun received(payload: ByteArray): Boolean {
        if (incoming != null || mutable.value.review != null || mutable.value.busy) return false
        val bundle = try { SetupBundles.decode(String(payload, Charsets.UTF_8)) } catch (_: SetupInputException) { return false }
        incoming = bundle
        viewModelScope.launch { review(bundle, fromFile = false) }
        return true
    }

    private suspend fun review(bundle: SetupBundle, fromFile: Boolean) {
        val current = session() ?: return
        val plan = runCatching { withContext(Dispatchers.IO) { access.use(current) { bundles.plan(current.profileId, bundle, SetupImportMode.MERGE) } } }
            .getOrElse { if (it is CancellationException) throw it; null }
        if (plan == null) { incoming = null; mutable.update { it.copy(message = R.string.iptv_setup_failed) }; return }
        mutable.update { it.copy(busy = false, review = IptvTransferReview(bundle.summary(), plan.reuseSources.size, plan.createSources.size, fromFile)) }
    }

    fun import(mode: SetupImportMode) {
        val bundle = incoming ?: return
        val current = session() ?: return
        launchWork {
            val result = withContext(Dispatchers.IO) { access.use(current) { bundles.import(current.profileId, bundle, mode) } }
            incoming = null
            stopReceiving()
            mutable.update { it.copy(review = null, code = null, address = null, message = R.string.iptv_import_done, finished = true) }
            val (sources, feeds) = withContext(Dispatchers.IO) { access.use(current) {
                catalogueStore.sources(current.profileId).filter { it.ref in result.created } to result.feeds.map(guides::feed)
            } }
            sources.forEach { refresher.refresh(current, it) }
            feeds.forEach { refresher.refresh(current, it) }
        }
    }

    fun declineImport() {
        incoming = null
        mutable.update { it.copy(review = null, message = R.string.iptv_import_declined) }
        if (mutable.value.mode == IptvTransferMode.RECEIVE && server == null) startReceiving()
    }

    fun backup(passphrase: String, confirm: String, logins: Boolean, usb: Boolean, uri: Uri? = null) {
        val current = session() ?: return
        if (passphrase != confirm) { mutable.update { it.copy(message = R.string.iptv_backup_mismatch) }; return }
        if (!SetupVault.acceptable(passphrase.toCharArray())) { mutable.update { it.copy(message = R.string.iptv_backup_weak) }; return }
        launchWork {
            val plain = withContext(Dispatchers.IO) { access.use(current) { SetupBundles.encode(bundles.export(current.profileId, logins)).toByteArray(Charsets.UTF_8) } }
            val sealed = withContext(Dispatchers.Default) { SetupVault.seal(plain, passphrase.toCharArray()) }
            plain.fill(0)
            val name = withContext(Dispatchers.IO) {
                if (uri != null) {
                    requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { it.write(sealed) }
                    null
                } else {
                    val folder = folders().getOrNull(if (usb) 1 else 0) ?: throw java.io.IOException("No backup folder")
                    folder.mkdirs()
                    val file = File(folder, "nuvio-live-tv-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date()) + "." + SetupVault.EXTENSION)
                    val temporary = File(folder, file.name + ".tmp")
                    temporary.outputStream().use { it.write(sealed); it.fd.sync() }
                    if (!temporary.renameTo(file)) { temporary.delete(); throw java.io.IOException("Backup not saved") }
                    file.name
                }
            }
            IptvLog.info("setup backup saved logins=$logins bytes=${sealed.size}")
            mutable.update { it.copy(message = if (name == null) R.string.iptv_backup_saved_choice else R.string.iptv_backup_saved, messageArg = name.orEmpty(), finished = true) }
        }
    }

    fun listBackups() {
        launchWork {
            val roots = folders()
            val files = withContext(Dispatchers.IO) {
                roots.withIndex().flatMap { (index, root) ->
                    root.listFiles()?.filter { it.isFile && it.name.endsWith("." + SetupVault.EXTENSION) && it.length() in 1..SetupVault.MAX_FILE_BYTES.toLong() }.orEmpty()
                        .map { IptvBackupFile(it.name, it.path, it.length(), it.lastModified(), index > 0) }
                }.sortedByDescending { it.modifiedMillis }.take(50)
            }
            mutable.update { it.copy(files = files, folders = roots.map { root -> root.path }) }
        }
    }

    fun choose(file: IptvBackupFile) {
        val roots = folders()
        val target = File(file.path)
        if (roots.none { root -> target.parentFile?.canonicalPath == root.canonicalPath }) return
        chosenFile = target
        chosenUri = null
        mutable.update { it.copy(chosen = file.name, message = null) }
    }

    fun choose(uri: Uri) {
        chosenUri = uri
        chosenFile = null
        mutable.update { it.copy(chosen = uri.lastPathSegment?.substringAfterLast('/')?.take(80) ?: "", message = null) }
    }

    fun unlock(passphrase: String) {
        val file = chosenFile
        val uri = chosenUri
        if (file == null && uri == null) return
        launchWork {
            val data = withContext(Dispatchers.IO) {
                if (file != null) { if (file.length() > SetupVault.MAX_FILE_BYTES) throw SetupCryptoException(SetupCryptoException.Reason.TOO_LARGE); file.readBytes() }
                else requireNotNull(context.contentResolver.openInputStream(uri!!)).use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        out.write(buffer, 0, count)
                        if (out.size() > SetupVault.MAX_FILE_BYTES) throw SetupCryptoException(SetupCryptoException.Reason.TOO_LARGE)
                    }
                    out.toByteArray()
                }
            }
            val plain = withContext(Dispatchers.Default) { SetupVault.open(data, passphrase.toCharArray()) }
            val bundle = try { SetupBundles.decode(String(plain, Charsets.UTF_8)) } finally { plain.fill(0) }
            incoming = bundle
            review(bundle, fromFile = true)
        }
    }

    fun checkPassphrase(passphrase: String, confirm: String): Boolean {
        val message = when {
            passphrase != confirm -> R.string.iptv_backup_mismatch
            !SetupVault.acceptable(passphrase.toCharArray()) -> R.string.iptv_backup_weak
            else -> null
        }
        mutable.update { it.copy(message = message) }
        return message == null
    }

    fun clearChoice() {
        chosenFile = null
        chosenUri = null
        mutable.update { it.copy(chosen = null, message = null) }
    }

    fun clearMessage() { mutable.update { it.copy(message = null, messageArg = "") } }

    fun pickerUnavailable() { mutable.update { it.copy(message = R.string.iptv_guide_picker_unavailable) } }

    private fun folders(): List<File> = context.getExternalFilesDirs("iptv-backups").withIndex().mapNotNull { (index, dir) ->
        dir?.takeIf { index == 0 || runCatching { Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED }.getOrDefault(false) }
    }.take(2)

    private fun launchWork(block: suspend () -> Unit) {
        if (work?.isActive == true) return
        mutable.update { it.copy(busy = true, message = null) }
        work = viewModelScope.launch {
            try { block() }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: SetupCryptoException) {
                mutable.update { it.copy(message = when (error.reason) {
                    SetupCryptoException.Reason.LOCKED -> R.string.iptv_restore_wrong_passphrase
                    SetupCryptoException.Reason.NOT_BACKUP -> R.string.iptv_restore_not_backup
                    SetupCryptoException.Reason.UNSUPPORTED -> R.string.iptv_restore_unsupported
                    SetupCryptoException.Reason.TOO_LARGE, SetupCryptoException.Reason.DAMAGED -> R.string.iptv_restore_damaged
                }) }
            }
            catch (_: SetupInputException) { mutable.update { it.copy(message = R.string.iptv_restore_damaged) } }
            catch (error: Exception) { IptvLog.failure("setup transfer", error); mutable.update { it.copy(message = R.string.iptv_setup_failed) } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }

    override fun onCleared() {
        super.onCleared()
        server?.stop()
        server = null
        discovery.close()
    }
}
