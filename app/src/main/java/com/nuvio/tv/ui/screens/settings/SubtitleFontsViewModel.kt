package com.nuvio.tv.ui.screens.settings

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.player.ImportedSubtitleFont
import com.nuvio.tv.core.player.ImportedSubtitleFontStore
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.core.server.DeviceIpAddress
import com.nuvio.tv.core.server.SubtitleFontUploadServer
import com.nuvio.tv.core.util.readAppLogoBytes
import com.nuvio.tv.core.util.withAppLocale
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.domain.repository.WatchProgressRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class SubtitleFontImportState(
    val isActive: Boolean = false,
    val qrCodeBitmap: Bitmap? = null,
    val serverUrl: String? = null,
    val error: String? = null,
    val addedName: String? = null
)

@HiltViewModel
class SubtitleFontsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    watchProgressRepository: WatchProgressRepository
) : ViewModel() {

    private val store = ImportedSubtitleFontStore.get(context)
    private val logoBytes: ByteArray? by lazy { context.readAppLogoBytes() }
    val fonts: StateFlow<List<ImportedSubtitleFont>> = store.fonts

    /** Backdrop of the title watched most recently, behind the Settings preview. */
    val previewBackdrop: StateFlow<String?> = watchProgressRepository.allProgress
        .map { list -> list.filter { !it.backdrop.isNullOrBlank() }.maxByOrNull { it.lastWatched }?.backdrop }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val mutableImportState = MutableStateFlow(SubtitleFontImportState())
    val importState: StateFlow<SubtitleFontImportState> = mutableImportState.asStateFlow()
    private var server: SubtitleFontUploadServer? = null
    private var idleJob: Job? = null

    fun remove(font: ImportedSubtitleFont) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.remove(font.id) }
            playerSettingsDataStore.forgetSubtitleImportedFont(font.id)
        }
    }

    fun startImport() {
        stopServer()
        val ip = DeviceIpAddress.get(context)
        if (ip == null) {
            showImportError(R.string.error_network_required)
            return
        }
        val startedServer = SubtitleFontUploadServer.startOnAvailablePort(
            context = context,
            onFontUploaded = ::importUploadedFont,
            logoProvider = { logoBytes }
        )
        if (startedServer == null) {
            showImportError(R.string.error_server_ports_unavailable)
            return
        }
        server = startedServer
        val url = "http://$ip:${startedServer.listeningPort}/?k=${startedServer.key}"
        mutableImportState.value = SubtitleFontImportState(
            isActive = true,
            qrCodeBitmap = QrCodeGenerator.generate(url, 512),
            serverUrl = url
        )
        idleJob = viewModelScope.launch {
            while (true) {
                delay(IDLE_CHECK_MS)
                val running = server ?: break
                if (System.currentTimeMillis() - running.lastActivityAt >= IDLE_TIMEOUT_MS) {
                    stopImport()
                    break
                }
            }
        }
    }

    fun stopImport() {
        stopServer()
        mutableImportState.value = SubtitleFontImportState()
    }

    private fun importUploadedFont(fileName: String, bytes: ByteArray): Boolean {
        val font = runCatching { store.import(fileName, bytes) }.getOrNull() ?: return false
        viewModelScope.launch {
            playerSettingsDataStore.setSubtitleImportedFont(font.id)
            mutableImportState.value = mutableImportState.value.copy(addedName = font.name)
            delay(IMPORT_CLOSE_DELAY_MS)
            stopImport()
        }
        return true
    }

    private fun showImportError(message: Int) {
        mutableImportState.value = SubtitleFontImportState(
            isActive = true,
            error = context.withAppLocale().getString(message)
        )
    }

    private fun stopServer() {
        idleJob?.cancel()
        idleJob = null
        server?.stop()
        server = null
    }

    override fun onCleared() {
        stopServer()
        super.onCleared()
    }

    private companion object {
        const val IMPORT_CLOSE_DELAY_MS = 1_200L
        const val IDLE_TIMEOUT_MS = 5 * 60_000L
        const val IDLE_CHECK_MS = 15_000L
    }
}
