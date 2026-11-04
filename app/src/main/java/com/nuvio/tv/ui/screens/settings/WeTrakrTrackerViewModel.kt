package com.nuvio.tv.ui.screens.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.data.wetrakr.WeTrakrApiClient
import com.nuvio.tv.data.wetrakr.WeTrakrAuthError
import com.nuvio.tv.data.wetrakr.WeTrakrAuthException
import com.nuvio.tv.data.wetrakr.WeTrakrAuthRepository
import com.nuvio.tv.data.wetrakr.WeTrakrAuthScope
import com.nuvio.tv.data.wetrakr.WeTrakrAuthStore
import com.nuvio.tv.data.wetrakr.WeTrakrDevicePollResult
import com.nuvio.tv.data.wetrakr.WeTrakrDeviceSession
import com.nuvio.tv.data.wetrakr.WeTrakrHistoryUpload
import com.nuvio.tv.data.wetrakr.WeTrakrUploadPlan
import com.nuvio.tv.data.wetrakr.WeTrakrUploadResult
import com.nuvio.tv.data.wetrakr.WeTrakrList
import com.nuvio.tv.data.wetrakr.WeTrakrQuota
import com.nuvio.tv.data.wetrakr.WeTrakrSettingsDataStore
import com.nuvio.tv.data.wetrakr.WeTrakrSyncError
import com.nuvio.tv.data.wetrakr.WeTrakrSyncRepository
import com.nuvio.tv.data.wetrakr.toWeTrakrSyncError
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class WeTrakrTrackerUiState(
    val isConnected: Boolean = false,
    val username: String? = null,
    val session: WeTrakrDeviceSession? = null,
    val credentialsConfigured: Boolean = true,
    val isLoading: Boolean = false,
    val isPolling: Boolean = false,
    val isSyncing: Boolean = false,
    val quota: WeTrakrQuota? = null,
    val lastSyncedAtEpochMs: Long? = null,
    val pausedUntilEpochMs: Long? = null,
    val continueWatchingDays: Int = WeTrakrSettingsDataStore.ALL_HISTORY,
    val statusMessage: String? = null,
    val errorMessage: String? = null
)

sealed interface WeTrakrUploadUiState {
    data object Hidden : WeTrakrUploadUiState
    data object Counting : WeTrakrUploadUiState
    data class Ready(val plan: WeTrakrUploadPlan) : WeTrakrUploadUiState
    data object Sending : WeTrakrUploadUiState
    data class Done(val result: WeTrakrUploadResult) : WeTrakrUploadUiState
    data class Failed(val message: String) : WeTrakrUploadUiState
}

private data class WeTrakrTrackerAction(
    val scope: WeTrakrAuthScope? = null,
    val loading: Boolean = false,
    val polling: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class WeTrakrTrackerViewModel @Inject constructor(
    private val auth: WeTrakrAuthRepository,
    private val authStore: WeTrakrAuthStore,
    private val sync: WeTrakrSyncRepository,
    private val settings: WeTrakrSettingsDataStore,
    private val upload: WeTrakrHistoryUpload,
    api: WeTrakrApiClient,
    private val profiles: ProfileManager,
    @param:ApplicationContext private val context: Context
) : ViewModel() {
    private val action = MutableStateFlow(WeTrakrTrackerAction())
    private var connectionJob: Job? = null
    private var disconnectJob: Job? = null
    private var uploadJob: Job? = null
    val uploadState = MutableStateFlow<WeTrakrUploadUiState>(WeTrakrUploadUiState.Hidden)

    val uiState = combine(
        combine(authStore.state, sync.state, action, profiles.activeProfileId) { a, b, c, d -> Quad(a, b, c, d) },
        api.quota,
        settings.continueWatchingDays
    ) { (authorization, syncState, action, profileId), quota, days ->
        val current = authorization.scope.profileId == profileId
        val transient = action.takeIf { it.scope == authorization.scope && current } ?: WeTrakrTrackerAction()
        val currentSync = syncState.takeIf { it.scope == authorization.scope && current }
        val connected = current && authorization.isAuthenticated
        val now = System.currentTimeMillis()
        val pausedUntil = (quota?.blockedUntilEpochMs ?: currentSync?.retryAtEpochMs?.takeIf {
            currentSync.error == WeTrakrSyncError.DAILY_LIMIT
        })?.takeIf { it > now }
        val snapshot = currentSync?.snapshot
        WeTrakrTrackerUiState(
            isConnected = connected,
            username = authorization.user?.label.takeIf { current },
            session = authorization.session.takeIf { current },
            credentialsConfigured = auth.hasRequiredCredentials(),
            isLoading = transient.loading,
            isPolling = transient.polling,
            isSyncing = currentSync?.isLoading == true,
            quota = quota.takeIf { connected },
            lastSyncedAtEpochMs = snapshot?.checkedAtEpochMs,
            pausedUntilEpochMs = pausedUntil.takeIf { connected },
            continueWatchingDays = days,
            statusMessage = when {
                currentSync?.isLoading == true -> context.getString(R.string.wetrakr_status_syncing)
                connected && snapshot?.isInitialized == true -> context.getString(
                    R.string.wetrakr_synced_summary,
                    snapshot.watchedMovies.size + snapshot.watchedEpisodes.size,
                    snapshot.playback.size,
                    snapshot.list(WeTrakrList.PLANNING).size
                )
                current && authorization.session != null -> context.getString(R.string.wetrakr_status_enter_code)
                else -> null
            },
            errorMessage = transient.error ?: authorization.error?.takeIf { current }?.message()
                ?: pausedUntil?.let { context.getString(R.string.wetrakr_limit_message, formatTime(it)) }
                ?: currentSync?.error?.message()
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, WeTrakrTrackerUiState(credentialsConfigured = auth.hasRequiredCredentials()))

    init {
        var previousProfile = profiles.activeProfileId.value
        viewModelScope.launch {
            profiles.activeProfileId.collect { profileId ->
                if (profileId != previousProfile) {
                    connectionJob?.cancel()
                    action.value = WeTrakrTrackerAction()
                    previousProfile = profileId
                }
            }
        }
    }

    fun onConnect() {
        if (connectionJob?.isActive == true || disconnectJob?.isActive == true) return
        val requestedScope = authStore.scope()
        if (requestedScope.profileId != profiles.activeProfileId.value || authStore.state.value.isAuthenticated) return
        action.value = WeTrakrTrackerAction(requestedScope, loading = true)
        connectionJob = viewModelScope.launch(Dispatchers.IO) {
            var scope = requestedScope
            try {
                authStore.checkScope(scope)
                val session = authStore.state.value.session
                if (session == null || session.expiresAtEpochMs <= System.currentTimeMillis()) auth.startDeviceAuthorization(scope)
                scope = authStore.scope()
                if (scope.profileId != requestedScope.profileId || scope.profileId != profiles.activeProfileId.value) return@launch
                action.value = WeTrakrTrackerAction(scope, polling = true)
                while (isActive && authStore.isCurrent(scope) && scope.profileId == profiles.activeProfileId.value) {
                    val pending = authStore.state.value.session ?: break
                    delay((pending.nextPollAtEpochMs - System.currentTimeMillis()).coerceAtLeast(0L))
                    if (scope.profileId != profiles.activeProfileId.value) break
                    when (auth.pollDeviceAuthorization(scope)) {
                        WeTrakrDevicePollResult.Pending -> Unit
                        WeTrakrDevicePollResult.Authorized -> {
                            action.value = WeTrakrTrackerAction(authStore.scope())
                            sync.refresh(TrackingRefreshIntent.USER_INITIATED)
                            break
                        }
                        WeTrakrDevicePollResult.Expired, WeTrakrDevicePollResult.Denied -> break
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (authStore.isCurrent(scope)) action.value = WeTrakrTrackerAction(scope, error = error.messageForUser())
            } finally {
                val current = action.value
                if (current.scope == scope) action.value = current.copy(loading = false, polling = false)
            }
        }
    }

    fun onRetryPolling() = onConnect()

    fun onCancel() {
        connectionJob?.cancel()
        val scope = authStore.scope()
        action.value = WeTrakrTrackerAction()
        viewModelScope.launch(Dispatchers.IO) { authStore.cancelSession(scope = scope) }
    }

    fun onDisconnect() {
        if (disconnectJob?.isActive == true) return
        connectionJob?.cancel()
        val profileId = profiles.activeProfileId.value
        val requestedScope = authStore.scope()
        if (requestedScope.profileId != profileId) return
        action.value = WeTrakrTrackerAction(requestedScope, loading = true)
        disconnectJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val revoked = auth.disconnect(requestedScope)
                sync.ensureLoaded()
                if (profiles.activeProfileId.value == profileId) action.value = WeTrakrTrackerAction(
                    authStore.scope(), error = if (revoked) null else context.getString(R.string.wetrakr_revoke_failed)
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (profiles.activeProfileId.value == profileId) {
                    action.value = WeTrakrTrackerAction(authStore.scope(), error = error.messageForUser())
                }
            }
        }
    }

    fun onSyncNow() = sync.refreshAsync(TrackingRefreshIntent.USER_INITIATED)
    fun onAccountOpened() = sync.refreshAsync(TrackingRefreshIntent.AUTOMATIC)

    fun onUploadOpened() {
        uploadJob?.cancel()
        uploadState.value = WeTrakrUploadUiState.Counting
        uploadJob = viewModelScope.launch(Dispatchers.IO) {
            uploadState.value = try {
                WeTrakrUploadUiState.Ready(upload.plan())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                WeTrakrUploadUiState.Failed(error.messageForUser())
            }
        }
    }

    fun onUploadConfirmed() {
        val ready = uploadState.value as? WeTrakrUploadUiState.Ready ?: return
        uploadState.value = WeTrakrUploadUiState.Sending
        uploadJob = viewModelScope.launch(Dispatchers.IO) {
            uploadState.value = try {
                WeTrakrUploadUiState.Done(upload.send(ready.plan))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                WeTrakrUploadUiState.Failed(error.messageForUser())
            }
        }
    }

    fun onUploadDismissed() {
        if (uploadState.value == WeTrakrUploadUiState.Sending) return
        uploadJob?.cancel()
        uploadState.value = WeTrakrUploadUiState.Hidden
    }

    fun onContinueWatchingDays(days: Int) {
        viewModelScope.launch(Dispatchers.IO) { settings.setContinueWatchingDays(days) }
    }

    fun formatTime(epochMs: Long): String =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(epochMs))

    private fun Exception.messageForUser(): String =
        if (this is WeTrakrAuthException) error.message() else toWeTrakrSyncError().message()

    private fun WeTrakrAuthError.message(): String = context.getString(when (this) {
        WeTrakrAuthError.MISSING_CLIENT_ID -> R.string.wetrakr_missing_client
        WeTrakrAuthError.INVALID_CLIENT -> R.string.wetrakr_error_invalid_client
        WeTrakrAuthError.INVALID_RESPONSE -> R.string.wetrakr_error_invalid_response
        WeTrakrAuthError.TOO_MANY_CODES -> R.string.wetrakr_error_too_many_codes
        WeTrakrAuthError.CODE_EXPIRED -> R.string.wetrakr_error_code_expired
        WeTrakrAuthError.ACCESS_DENIED -> R.string.wetrakr_error_denied
        WeTrakrAuthError.AUTHORIZATION_REVOKED -> R.string.wetrakr_error_signin
        WeTrakrAuthError.APP_SUSPENDED -> R.string.wetrakr_error_suspended
    })

    private fun WeTrakrSyncError.message(): String = context.getString(when (this) {
        WeTrakrSyncError.UNAVAILABLE -> R.string.wetrakr_error_unavailable
        WeTrakrSyncError.INVALID_RESPONSE -> R.string.wetrakr_error_invalid_response
        WeTrakrSyncError.RATE_LIMIT, WeTrakrSyncError.DAILY_LIMIT -> R.string.wetrakr_error_busy
        WeTrakrSyncError.AUTHORIZATION_REVOKED -> R.string.wetrakr_error_signin
        WeTrakrSyncError.APP_SUSPENDED -> R.string.wetrakr_error_suspended
    })

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
}
