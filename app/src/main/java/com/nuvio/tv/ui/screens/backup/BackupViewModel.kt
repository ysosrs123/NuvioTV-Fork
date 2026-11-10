package com.nuvio.tv.ui.screens.backup

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.backup.BackupDocument
import com.nuvio.tv.core.backup.BackupManager
import com.nuvio.tv.core.backup.BackupPart
import com.nuvio.tv.core.backup.BackupSaved
import com.nuvio.tv.core.backup.BackupSection
import com.nuvio.tv.core.backup.BackupServer
import com.nuvio.tv.core.backup.SavedBackup
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.core.server.DeviceIpAddress
import com.nuvio.tv.core.util.readAppLogoBytes
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class BackupListItem(val file: SavedBackup, val document: BackupDocument?)

sealed interface BackupDialog {
    data object WhereFrom : BackupDialog
    data class Saved(val saved: BackupSaved) : BackupDialog
    data class Choose(val items: List<BackupListItem>) : BackupDialog
    data class Phone(val saving: Boolean, val qr: Bitmap?, val url: String) : BackupDialog
    data class Restore(val document: BackupDocument) : BackupDialog
    data class Message(val text: String) : BackupDialog
}

sealed interface BackupPinStep {
    data object Create : BackupPinStep
    data class Confirm(val first: String) : BackupPinStep
    data class Restore(val document: BackupDocument, val parts: Set<BackupPart>, val sections: Set<String>) : BackupPinStep
}

data class BackupUiState(
    val includeSecrets: Boolean = true,
    val lastBackupMs: Long = 0L,
    val backups: List<SavedBackup> = emptyList(),
    val dialog: BackupDialog? = null,
    val pin: BackupPinStep? = null,
    val pinError: String? = null,
    val busy: Boolean = false,
    val needsPermission: Boolean = false
)

@HiltViewModel
class BackupViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val manager: BackupManager
) : ViewModel() {
    private val _state = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    val sections: List<BackupSection> get() = manager.sections

    val isSignedIn: Boolean get() = manager.isSignedIn

    private var server: BackupServer? = null
    private val logoBytes: ByteArray? by lazy { context.readAppLogoBytes() }

    init {
        viewModelScope.launch { manager.includeSecrets.collect { value -> _state.update { it.copy(includeSecrets = value) } } }
        viewModelScope.launch { manager.lastBackupMs.collect { value -> _state.update { it.copy(lastBackupMs = value) } } }
        viewModelScope.launch {
            manager.received.collect { document ->
                if (document != null && (_state.value.dialog as? BackupDialog.Phone)?.saving == false) {
                    val finished = server
                    server = null
                    _state.update { it.copy(dialog = BackupDialog.Restore(document)) }
                    delay(1000)
                    finished?.stop()
                }
            }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { manager.files.list() }
            _state.update { it.copy(backups = list) }
        }
    }

    fun setIncludeSecrets(include: Boolean) = manager.setIncludeSecrets(include)

    fun backUpNow() {
        if (manager.files.needsStoragePermission()) {
            _state.update { it.copy(needsPermission = true) }
            return
        }
        if (_state.value.includeSecrets) {
            _state.update { it.copy(pin = BackupPinStep.Create, pinError = null) }
        } else {
            runBackup(null)
        }
    }

    fun permissionResult(granted: Boolean) {
        _state.update { it.copy(needsPermission = false) }
        if (granted) backUpNow() else showMessage(context.getString(R.string.backup_permission_needed))
    }

    fun pinEntered(pin: String) {
        when (val step = _state.value.pin) {
            BackupPinStep.Create -> _state.update { it.copy(pin = BackupPinStep.Confirm(pin), pinError = null) }
            is BackupPinStep.Confirm -> if (step.first == pin) {
                _state.update { it.copy(pin = null, pinError = null) }
                runBackup(pin)
            } else {
                _state.update { it.copy(pin = BackupPinStep.Create, pinError = context.getString(R.string.backup_pin_mismatch)) }
            }
            is BackupPinStep.Restore -> viewModelScope.launch {
                _state.update { it.copy(busy = true, pinError = null) }
                val opens = withContext(Dispatchers.Default) { manager.opens(step.document, pin) }
                if (opens) {
                    runRestore(step.document, step.parts, step.sections, pin)
                } else {
                    _state.update { it.copy(busy = false, pinError = context.getString(R.string.backup_pin_wrong)) }
                }
            }
            null -> Unit
        }
    }

    /** Back on the PIN screen: back up without passwords, or restore everything else. */
    fun pinSkipped() {
        when (val step = _state.value.pin) {
            BackupPinStep.Create, is BackupPinStep.Confirm -> {
                _state.update { it.copy(pin = null, pinError = null) }
                runBackup(null)
            }
            is BackupPinStep.Restore -> {
                _state.update { it.copy(pin = null, pinError = null) }
                val rest = step.parts - BackupPart.SECRETS
                if (rest.isNotEmpty() || step.sections.isNotEmpty()) runRestore(step.document, rest, step.sections, null)
            }
            null -> Unit
        }
    }

    fun clearPinError() = _state.update { it.copy(pinError = null) }

    private fun runBackup(pin: String?) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val result = runCatching { manager.backUp(pin) }
            _state.update { it.copy(busy = false) }
            result.onSuccess { saved ->
                refresh()
                _state.update { it.copy(dialog = BackupDialog.Saved(saved)) }
            }.onFailure { showMessage(context.getString(R.string.backup_error_save)) }
        }
    }

    fun openWhereFrom() = _state.update { it.copy(dialog = BackupDialog.WhereFrom) }

    fun openChoose() {
        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) {
                manager.files.list().map { BackupListItem(it, manager.read(it.id)) }
            }
            _state.update { it.copy(dialog = BackupDialog.Choose(items)) }
        }
    }

    fun pick(item: BackupListItem) {
        val document = item.document
        if (document == null) showMessage(context.getString(R.string.backup_error_read))
        else _state.update { it.copy(dialog = BackupDialog.Restore(document)) }
    }

    fun openPhone(saving: Boolean) {
        val ip = DeviceIpAddress.get(context)
        if (ip == null) {
            showMessage(context.getString(R.string.error_network_required))
            return
        }
        stopServer()
        manager.clearReceived()
        val started = BackupServer.startOnAvailablePort(context, manager, saving, { logoBytes })
        if (started == null) {
            showMessage(context.getString(R.string.error_server_ports_unavailable))
            return
        }
        server = started
        val url = "http://$ip:${started.listeningPort}/?k=${started.key}"
        _state.update { it.copy(dialog = BackupDialog.Phone(saving, QrCodeGenerator.generate(url, 512), url)) }
    }

    fun restore(document: BackupDocument, parts: Set<BackupPart>, sections: Set<String>) {
        if (parts.isEmpty() && sections.isEmpty()) return
        val wantsSealed = BackupPart.SECRETS in parts || sections.any { document.header.sections[it] == true }
        if (wantsSealed && document.sealedSecrets != null) {
            _state.update { it.copy(dialog = null, pin = BackupPinStep.Restore(document, parts, sections), pinError = null) }
        } else {
            runRestore(document, parts - BackupPart.SECRETS, sections, null)
        }
    }

    private fun runRestore(document: BackupDocument, parts: Set<BackupPart>, sections: Set<String>, pin: String?) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, dialog = null) }
            val result = runCatching { manager.restore(document, parts, sections, pin) }
            result.onSuccess { warningShown ->
                _state.update { it.copy(busy = false, pin = null) }
                if (!warningShown) showMessage(context.getString(R.string.backup_restored_reopen))
            }
            result.onFailure {
                _state.update { it.copy(busy = false, pin = null) }
                showMessage(context.getString(R.string.backup_error_restore))
            }
        }
    }

    fun dismissDialog() {
        if (_state.value.dialog is BackupDialog.Phone) stopServer()
        manager.clearReceived()
        _state.update { it.copy(dialog = null) }
        refresh()
    }

    fun dismissPin() = _state.update { it.copy(pin = null, pinError = null) }

    private fun showMessage(text: String) = _state.update { it.copy(dialog = BackupDialog.Message(text)) }

    private fun stopServer() {
        server?.stop()
        server = null
    }

    override fun onCleared() {
        stopServer()
        super.onCleared()
    }
}
