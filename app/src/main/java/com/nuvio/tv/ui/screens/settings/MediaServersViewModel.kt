package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.network.ServerCertificateInfo
import com.nuvio.tv.core.sync.MediaServerSyncService
import com.nuvio.tv.core.network.ServerCertificateProbe
import com.nuvio.tv.core.network.ServerTrust
import com.nuvio.tv.core.network.normalizeHost
import com.nuvio.tv.data.mediaserver.AlternateAddressResult
import com.nuvio.tv.data.mediaserver.mediabrowser.normalizeServerAddress
import com.nuvio.tv.data.mediaserver.ServerConnection
import com.nuvio.tv.data.mediaserver.ServerException
import com.nuvio.tv.data.mediaserver.ServerFailure
import com.nuvio.tv.data.mediaserver.ServerProvider
import com.nuvio.tv.data.mediaserver.ServerQuickConnect
import com.nuvio.tv.data.mediaserver.ServerRepository
import com.nuvio.tv.data.mediaserver.ServersUiState
import com.nuvio.tv.data.mediaserver.serverFailure
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class ServerSignInState(
    val connecting: Boolean = false,
    val error: ServerFailure? = null,
    val certificate: ServerCertificateInfo? = null,
    val certificateForCode: Boolean = false
)

enum class QuickConnectPhase {
    IDLE,
    STARTING,
    WAITING,
    SIGNING_IN,
    DISABLED,
    EXPIRED,
    FAILED
}

data class QuickConnectState(
    val phase: QuickConnectPhase = QuickConnectPhase.IDLE,
    val code: String? = null,
    val serverName: String? = null,
    val expiresAtMs: Long = 0L,
    val error: ServerFailure? = null
)

@HiltViewModel
class MediaServersViewModel @Inject constructor(
    private val repository: ServerRepository,
    private val syncService: MediaServerSyncService
) : ViewModel() {
    val uiState: StateFlow<ServersUiState> = repository.uiState

    val providers: List<ServerProvider>
        get() = repository.providers

    private val mutableSignIn = MutableStateFlow(ServerSignInState())
    val signIn: StateFlow<ServerSignInState> = mutableSignIn.asStateFlow()

    private val mutableQuickConnect = MutableStateFlow(QuickConnectState())
    val quickConnect: StateFlow<QuickConnectState> = mutableQuickConnect.asStateFlow()
    private var quickConnectJob: Job? = null
    private var acceptedCertificate: ServerCertificateInfo? = null

    private fun hostOf(address: String): String? =
        normalizeServerAddress(address)?.toHttpUrlOrNull()?.host?.let(::normalizeHost)

    private val mutableRefreshing = MutableStateFlow<String?>(null)
    val refreshing: StateFlow<String?> = mutableRefreshing.asStateFlow()

    init {
        repository.ensureLoaded()
    }

    fun provider(connection: ServerConnection): ServerProvider? = repository.provider(connection)

    fun resetSignIn() {
        mutableSignIn.value = ServerSignInState()
        acceptedCertificate = null
    }

    fun connect(
        provider: ServerProvider,
        address: String,
        username: String,
        password: String,
        trusted: ServerCertificateInfo? = null,
        onConnected: (ServerConnection) -> Unit
    ) {
        if (mutableSignIn.value.connecting) return
        val accepted = trusted?.takeIf { it.host == hostOf(address) }
        if (accepted != null) acceptedCertificate = accepted
        mutableSignIn.value = ServerSignInState(connecting = true)
        viewModelScope.launch {
            try {
                val connection = repository.connect(
                    provider,
                    address,
                    username.trim(),
                    password,
                    acceptedCertificate = accepted?.let { it.host to it.fingerprint }
                )
                mutableSignIn.value = ServerSignInState()
                onConnected(connection)
            } catch (error: CancellationException) {
                mutableSignIn.value = ServerSignInState()
                throw error
            } catch (error: ServerException) {
                val certificate = if (error.failure == ServerFailure.CERTIFICATE) {
                    normalizeServerAddress(address)?.let { ServerCertificateProbe.probe(it) }
                        ?.takeIf { it.fingerprint != accepted?.fingerprint }
                } else {
                    null
                }
                mutableSignIn.value = ServerSignInState(error = error.failure, certificate = certificate)
            } catch (_: Exception) {
                mutableSignIn.value = ServerSignInState(error = ServerFailure.FAILED)
            }
        }
    }

    fun startQuickConnect(
        provider: ServerProvider,
        address: String,
        trusted: ServerCertificateInfo? = null,
        onConnected: (ServerConnection) -> Unit
    ) {
        trusted?.takeIf { it.host == hostOf(address) }?.let { acceptedCertificate = it }
        val accepted = acceptedCertificate?.takeIf { it.host == hostOf(address) }
        val previous = quickConnectJob
        previous?.cancel()
        mutableSignIn.value = mutableSignIn.value.copy(certificate = null, certificateForCode = false)
        mutableQuickConnect.value = QuickConnectState(phase = QuickConnectPhase.STARTING)
        quickConnectJob = viewModelScope.launch {
            previous?.join()
            try {
                accepted?.let { ServerTrust.allow(it.host, it.fingerprint) }
                runQuickConnect(provider, address, accepted, onConnected)
            } finally {
                if (accepted != null) ServerTrust.clearPending()
            }
        }
    }

    private suspend fun runQuickConnect(
        provider: ServerProvider,
        address: String,
        accepted: ServerCertificateInfo?,
        onConnected: (ServerConnection) -> Unit
    ) {
        val ticket = try {
            provider.quickConnectStart(address)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val certificate = if (error.serverFailure() == ServerFailure.CERTIFICATE) {
                normalizeServerAddress(address)?.let { ServerCertificateProbe.probe(it) }
                    ?.takeIf { it.fingerprint != accepted?.fingerprint }
            } else {
                null
            }
            if (certificate != null) {
                mutableQuickConnect.value = QuickConnectState()
                mutableSignIn.value = ServerSignInState(certificate = certificate, certificateForCode = true)
            } else {
                mutableQuickConnect.value = QuickConnectState(phase = QuickConnectPhase.FAILED, error = error.serverFailure())
            }
            return
        }
        if (ticket == null) {
            mutableQuickConnect.value = QuickConnectState(phase = QuickConnectPhase.DISABLED)
            return
        }
        mutableQuickConnect.value = QuickConnectState(
            phase = QuickConnectPhase.WAITING,
            code = ticket.code,
            serverName = ticket.serverName,
            expiresAtMs = ticket.expiresAtMs
        )
        if (!awaitApproval(provider, ticket)) return
        mutableQuickConnect.value = mutableQuickConnect.value.copy(phase = QuickConnectPhase.SIGNING_IN)
        try {
            val trustedForTicket = accepted?.takeIf { it.host == hostOf(ticket.address) }
            val connection = repository.connectWithQuickConnect(
                provider,
                ticket,
                trustedForTicket?.let { it.host to it.fingerprint }
            )
            mutableQuickConnect.value = QuickConnectState()
            onConnected(connection)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            mutableQuickConnect.value = if (error.serverFailure() == ServerFailure.NOT_FOUND) {
                QuickConnectState(phase = QuickConnectPhase.EXPIRED)
            } else {
                QuickConnectState(phase = QuickConnectPhase.FAILED, error = error.serverFailure())
            }
        }
    }

    private suspend fun awaitApproval(provider: ServerProvider, ticket: ServerQuickConnect): Boolean {
        val startedAt = System.currentTimeMillis()
        var misses = 0
        while (System.currentTimeMillis() < ticket.expiresAtMs) {
            val elapsed = System.currentTimeMillis() - startedAt
            delay(if (elapsed < QUICK_CONNECT_FAST_POLL_FOR_MS) QUICK_CONNECT_POLL_MS else QUICK_CONNECT_SLOW_POLL_MS)
            val approved = try {
                provider.quickConnectApproved(ticket).also { misses = 0 }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (error.serverFailure() == ServerFailure.NOT_FOUND) break
                if (++misses >= QUICK_CONNECT_MAX_MISSES) {
                    mutableQuickConnect.value = QuickConnectState(phase = QuickConnectPhase.FAILED, error = error.serverFailure())
                    return false
                }
                false
            }
            if (approved) return true
        }
        mutableQuickConnect.value = QuickConnectState(phase = QuickConnectPhase.EXPIRED)
        return false
    }

    fun dismissCertificate() {
        mutableSignIn.value = mutableSignIn.value.copy(certificate = null)
    }

    fun forgetCertificates(connectionId: String) = repository.forgetCertificates(connectionId)

    fun cancelQuickConnect() {
        quickConnectJob?.cancel()
        quickConnectJob = null
        mutableQuickConnect.value = QuickConnectState()
    }

    fun refreshLibraries(connectionId: String, onFailure: (ServerFailure) -> Unit) {
        if (mutableRefreshing.value != null) return
        mutableRefreshing.value = connectionId
        viewModelScope.launch {
            try {
                repository.refreshLibraries(connectionId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                onFailure(error.serverFailure())
            } finally {
                mutableRefreshing.value = null
            }
        }
    }

    fun setEnabled(connectionId: String, enabled: Boolean) = repository.setEnabled(connectionId, enabled)

    fun setCatalogMetadata(connectionId: String, enabled: Boolean) = repository.setCatalogMetadata(connectionId, enabled)

    fun setImportContinueWatching(connectionId: String, enabled: Boolean) =
        repository.setImportContinueWatching(connectionId, enabled)

    fun setLibrarySelected(connectionId: String, libraryId: String, selected: Boolean) =
        repository.setLibrarySelected(connectionId, libraryId, selected)

    fun remove(connectionId: String) = repository.remove(connectionId)

    fun setSyncEnabled(enabled: Boolean) = repository.setSyncEnabled(enabled)

    fun activeAddress(connection: ServerConnection): String = repository.activeAddress(connection)

    fun removeFromAccount(onResult: (Boolean) -> Unit) {
        viewModelScope.launch { onResult(syncService.removeFromAccount().isSuccess) }
    }

    fun setAlternateAddress(connectionId: String, address: String, onResult: (AlternateAddressResult) -> Unit) {
        viewModelScope.launch {
            onResult(
                try {
                    repository.setAlternateAddress(connectionId, address)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    AlternateAddressResult.UNREACHABLE
                }
            )
        }
    }

    private companion object {
        const val QUICK_CONNECT_POLL_MS = 3_000L
        const val QUICK_CONNECT_SLOW_POLL_MS = 5_000L
        const val QUICK_CONNECT_FAST_POLL_FOR_MS = 2 * 60_000L
        const val QUICK_CONNECT_MAX_MISSES = 3
    }
}
