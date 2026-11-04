package com.nuvio.tv.ui.screens.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.floppy.FloppyAccountChangedException
import com.nuvio.tv.data.floppy.FloppyAuthError
import com.nuvio.tv.data.floppy.FloppyAuthException
import com.nuvio.tv.data.floppy.FloppyAuthRepository
import com.nuvio.tv.data.floppy.FloppyAuthScope
import com.nuvio.tv.data.floppy.FloppyAuthStore
import com.nuvio.tv.data.floppy.FloppyDevicePollResult
import com.nuvio.tv.data.floppy.FloppyDeviceSession
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class FloppySignInStep { ADDRESS, CODE, TOKEN }

data class FloppyTrackerUiState(
    val isConnected: Boolean = false,
    val serverLabel: String? = null,
    val lastServerUrl: String? = null,
    val session: FloppyDeviceSession? = null,
    val step: FloppySignInStep = FloppySignInStep.ADDRESS,
    val isLoading: Boolean = false,
    val isPolling: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null
)

private data class FloppyTrackerAction(
    val scope: FloppyAuthScope? = null,
    val step: FloppySignInStep = FloppySignInStep.ADDRESS,
    val loading: Boolean = false,
    val polling: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class FloppyTrackerViewModel @Inject constructor(
    private val auth: FloppyAuthRepository,
    private val authStore: FloppyAuthStore,
    private val profiles: ProfileManager,
    @param:ApplicationContext private val context: Context
) : ViewModel() {
    private val action = MutableStateFlow(FloppyTrackerAction())
    private var connectionJob: Job? = null
    private var disconnectJob: Job? = null

    val uiState = combine(authStore.state, action, profiles.activeProfileId) { authorization, action, profileId ->
        val current = authorization.scope.profileId == profileId
        val transient = action.takeIf { it.scope == authorization.scope && current } ?: FloppyTrackerAction()
        val session = authorization.session.takeIf { current }
        FloppyTrackerUiState(
            isConnected = current && authorization.isAuthenticated,
            serverLabel = authorization.serverUrl?.takeIf { current }?.let(::floppyServerLabel),
            lastServerUrl = authorization.lastServerUrl.takeIf { current },
            session = session,
            step = if (session != null && transient.step == FloppySignInStep.ADDRESS) FloppySignInStep.CODE else transient.step,
            isLoading = transient.loading,
            isPolling = transient.polling,
            statusMessage = if (session != null) context.getString(R.string.floppy_status_waiting) else null,
            errorMessage = transient.error ?: authorization.error?.takeIf { current }?.message()
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, FloppyTrackerUiState())

    init {
        var previousProfile = profiles.activeProfileId.value
        viewModelScope.launch {
            profiles.activeProfileId.collect { profileId ->
                if (profileId != previousProfile) {
                    connectionJob?.cancel()
                    action.value = FloppyTrackerAction()
                    previousProfile = profileId
                }
            }
        }
    }

    fun onConnect(address: String) {
        if (connectionJob?.isActive == true || disconnectJob?.isActive == true) return
        val requestedScope = authStore.scope()
        if (requestedScope.profileId != profiles.activeProfileId.value || authStore.state.value.isAuthenticated) return
        action.value = FloppyTrackerAction(requestedScope, FloppySignInStep.CODE, loading = true)
        connectionJob = viewModelScope.launch(Dispatchers.IO) {
            var scope = requestedScope
            try {
                val session = authStore.state.value.session
                if (session == null || session.expiresAtEpochMs <= System.currentTimeMillis()) {
                    auth.startDeviceAuthorization(address, scope)
                }
                scope = authStore.scope()
                if (scope.profileId != requestedScope.profileId || scope.profileId != profiles.activeProfileId.value) return@launch
                action.value = FloppyTrackerAction(scope, FloppySignInStep.CODE, polling = true)
                while (isActive && authStore.scope() == scope && scope.profileId == profiles.activeProfileId.value) {
                    val pending = authStore.state.value.session ?: break
                    delay((pending.nextPollAtEpochMs - System.currentTimeMillis()).coerceAtLeast(0L))
                    when (auth.pollDeviceAuthorization(scope)) {
                        FloppyDevicePollResult.Pending -> Unit
                        FloppyDevicePollResult.Authorized -> {
                            action.value = FloppyTrackerAction(authStore.scope())
                            break
                        }
                        FloppyDevicePollResult.Expired, FloppyDevicePollResult.Denied -> {
                            action.value = FloppyTrackerAction(authStore.scope())
                            break
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: FloppyAccountChangedException) {
            } catch (error: Exception) {
                val now = authStore.scope()
                if (now.profileId == scope.profileId) {
                    val step = if (authStore.state.value.session != null) FloppySignInStep.CODE else FloppySignInStep.ADDRESS
                    action.value = FloppyTrackerAction(now, step, error = error.messageForUser())
                }
            } finally {
                val current = action.value
                if (current.scope == scope) action.value = current.copy(loading = false, polling = false)
            }
        }
    }

    fun onConnectWithToken(address: String, token: String) {
        if (connectionJob?.isActive == true || disconnectJob?.isActive == true) return
        val scope = authStore.scope()
        if (scope.profileId != profiles.activeProfileId.value || authStore.state.value.isAuthenticated) return
        action.value = FloppyTrackerAction(scope, FloppySignInStep.TOKEN, loading = true)
        connectionJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                auth.connectWithToken(address, token, scope)
                action.value = FloppyTrackerAction(authStore.scope())
            } catch (error: CancellationException) {
                throw error
            } catch (_: FloppyAccountChangedException) {
            } catch (error: Exception) {
                if (authStore.scope() == scope) {
                    action.value = FloppyTrackerAction(scope, FloppySignInStep.TOKEN, error = error.messageForUser())
                }
            }
        }
    }

    fun onShowStep(step: FloppySignInStep) {
        connectionJob?.cancel()
        val scope = authStore.scope()
        if (step != FloppySignInStep.CODE && authStore.state.value.session != null) authStore.cancelSession(scope = scope)
        action.value = FloppyTrackerAction(authStore.scope(), step)
    }

    fun onCancel() {
        connectionJob?.cancel()
        val scope = authStore.scope()
        action.value = FloppyTrackerAction()
        viewModelScope.launch(Dispatchers.IO) { authStore.cancelSession(scope = scope) }
    }

    fun onDisconnect() {
        if (disconnectJob?.isActive == true) return
        connectionJob?.cancel()
        val profileId = profiles.activeProfileId.value
        val requestedScope = authStore.scope()
        if (requestedScope.profileId != profileId) return
        action.value = FloppyTrackerAction(requestedScope, loading = true)
        disconnectJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                auth.disconnect(requestedScope)
                if (profiles.activeProfileId.value == profileId) action.value = FloppyTrackerAction(authStore.scope())
            } catch (error: CancellationException) {
                throw error
            } catch (_: FloppyAccountChangedException) {
            } catch (error: Exception) {
                if (profiles.activeProfileId.value == profileId) {
                    action.value = FloppyTrackerAction(authStore.scope(), error = error.messageForUser())
                }
            }
        }
    }

    private fun Exception.messageForUser(): String =
        (this as? FloppyAuthException)?.error?.message() ?: context.getString(R.string.floppy_error_unreachable)

    private fun FloppyAuthError.message(): String = context.getString(when (this) {
        FloppyAuthError.INVALID_ADDRESS -> R.string.floppy_error_address
        FloppyAuthError.UNREACHABLE -> R.string.floppy_error_unreachable
        FloppyAuthError.NOT_FLOPPY -> R.string.floppy_error_not_floppy
        FloppyAuthError.NO_CODE_SIGN_IN -> R.string.floppy_error_no_code_sign_in
        FloppyAuthError.TOKEN_REJECTED -> R.string.floppy_error_rejected
        FloppyAuthError.INVALID_RESPONSE -> R.string.floppy_error_not_floppy
        FloppyAuthError.CODE_EXPIRED -> R.string.floppy_error_code_expired
        FloppyAuthError.ACCESS_DENIED -> R.string.floppy_error_denied
        FloppyAuthError.AUTHORIZATION_REVOKED -> R.string.floppy_error_revoked
    })
}

internal fun floppyServerLabel(serverUrl: String): String {
    val url = serverUrl.toHttpUrlOrNull() ?: return serverUrl
    val port = if (url.port == okhttp3.HttpUrl.defaultPort(url.scheme)) "" else ":${url.port}"
    val path = url.encodedPath.trimEnd('/')
    return url.host + port + path
}
