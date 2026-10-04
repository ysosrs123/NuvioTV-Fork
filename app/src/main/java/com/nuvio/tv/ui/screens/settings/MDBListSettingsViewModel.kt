package com.nuvio.tv.ui.screens.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.MDBListSettingsDataStore
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.local.WatchProgressSource
import com.nuvio.tv.data.mdblist.MdbListApiClient
import com.nuvio.tv.data.mdblist.MdbListAuthException
import com.nuvio.tv.data.mdblist.MdbListAuthStore
import com.nuvio.tv.data.remote.api.MDBListApi
import com.nuvio.tv.domain.model.MDBListSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.nuvio.tv.core.tracking.TrackingSourceController
import javax.inject.Inject

private const val TAG = "MDBListSettings"

@HiltViewModel
class MDBListSettingsViewModel @Inject constructor(
    private val dataStore: MDBListSettingsDataStore,
    private val mdbListApi: MDBListApi,
    authStore: MdbListAuthStore,
    private val mdbListAccountApi: MdbListApiClient,
    private val traktSettingsDataStore: TraktSettingsDataStore,
    private val trackingSourceController: TrackingSourceController
) : ViewModel() {

    private val _uiState = MutableStateFlow(MDBListSettingsUiState())
    val uiState: StateFlow<MDBListSettingsUiState> = _uiState.asStateFlow()

    private val _validating = MutableStateFlow(false)
    val validating: StateFlow<Boolean> = _validating.asStateFlow()

    private val _validationError = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val validationError: SharedFlow<Unit> = _validationError.asSharedFlow()

    init {
        viewModelScope.launch {
            combine(dataStore.settings, authStore.state) { settings, auth ->
                settings to auth.isAuthenticated
            }.collectLatest { (settings, isConnected) ->
                _uiState.update { it.copy(isConnected = isConnected).fromSettings(settings) }
            }
        }
        // The Watch Progress source is one setting with two entry points -
        // this screen and the Trakt screen - so it is read from and written
        // to the same store rather than duplicated here.
        viewModelScope.launch {
            traktSettingsDataStore.watchProgressSource.collectLatest { source ->
                _uiState.update { it.copy(watchProgressSource = source) }
            }
        }
    }

    /**
     * Reads the account summary. Costs one request, so it is driven by the
     * screen rather than polled. The signed-in account wins; otherwise the
     * ratings key is used, and nothing is read without either.
     */
    fun refreshAccount() {
        val state = _uiState.value
        if (state.isConnected) {
            viewModelScope.launch {
                val user = try {
                    mdbListAccountApi.refreshUser()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "account summary failed: ${(e as? MdbListAuthException)?.error ?: e.javaClass.simpleName}")
                    null
                }
                val limit = user?.rateLimit
                val remaining = user?.rateLimitRemaining
                var used = user?.apiRequestsCount
                    ?: if (limit != null && remaining != null) (limit - remaining).coerceAtLeast(0) else null
                var plan = user?.plan
                var requestsLimit = limit
                val username = user?.username
                // The key's summary fills what the account reply left out.
                if ((plan == null || used == null || requestsLimit == null) && state.enabled && state.apiKey.isNotBlank()) {
                    val keyUser = try {
                        mdbListApi.getUser(state.apiKey.trim()).body()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                    if (keyUser != null) {
                        plan = plan ?: keyUser.plan
                        used = used ?: keyUser.apiRequestsCount
                        requestsLimit = requestsLimit ?: keyUser.rateLimit
                    }
                }
                _uiState.update {
                    it.copy(
                        username = username,
                        plan = plan,
                        isSupporter = if (plan == null && user != null) user.isSupporter else null,
                        requestsUsed = used,
                        requestsLimit = requestsLimit
                    )
                }
            }
            return
        }
        if (!state.enabled || state.apiKey.isBlank()) {
            _uiState.update { it.copy(username = null, plan = null, isSupporter = null, requestsUsed = null, requestsLimit = null) }
            return
        }
        viewModelScope.launch {
            val user = try {
                mdbListApi.getUser(state.apiKey.trim()).body()
            } catch (e: Exception) {
                null
            }
            _uiState.update {
                it.copy(
                    username = user?.username,
                    plan = user?.plan,
                    isSupporter = null,
                    requestsUsed = user?.apiRequestsCount,
                    requestsLimit = user?.rateLimit
                )
            }
        }
    }

    fun onWatchProgressSourceSelected(source: WatchProgressSource) {
        // Routed through the shared controller so this mirror of the picker is
        // behaviourally identical to the one on the Tracking screen: the CW
        // enrichment caches are cleared and the newly selected source is
        // refreshed. Writing the datastore directly would skip those side effects.
        viewModelScope.launch { trackingSourceController.selectWatchProgressSource(source) }
    }

    fun onEvent(event: MDBListSettingsEvent) {
        when (event) {
            is MDBListSettingsEvent.ToggleEnabled -> update { dataStore.setEnabled(event.enabled) }
            is MDBListSettingsEvent.ToggleTrakt -> update { dataStore.setShowTrakt(event.enabled) }
            is MDBListSettingsEvent.ToggleImdb -> update { dataStore.setShowImdb(event.enabled) }
            is MDBListSettingsEvent.ToggleTmdb -> update { dataStore.setShowTmdb(event.enabled) }
            is MDBListSettingsEvent.ToggleLetterboxd -> update { dataStore.setShowLetterboxd(event.enabled) }
            is MDBListSettingsEvent.ToggleTomatoes -> update { dataStore.setShowTomatoes(event.enabled) }
            is MDBListSettingsEvent.ToggleAudience -> update { dataStore.setShowAudience(event.enabled) }
            is MDBListSettingsEvent.ToggleMetacritic -> update { dataStore.setShowMetacritic(event.enabled) }
            is MDBListSettingsEvent.ToggleMal -> update { dataStore.setShowMal(event.enabled) }
            is MDBListSettingsEvent.ToggleShowOnHero -> update { dataStore.setShowOnHero(event.enabled) }
            is MDBListSettingsEvent.MoveRatingUp -> moveRating(event.provider, -1)
            is MDBListSettingsEvent.MoveRatingDown -> moveRating(event.provider, 1)
        }
    }

    fun validateAndSaveApiKey(value: String, onSuccess: () -> Unit) {
        val trimmed = value.trim()
        if (trimmed.isBlank()) {
            viewModelScope.launch { dataStore.setApiKey("") }
            onSuccess()
            return
        }
        viewModelScope.launch {
            _validating.value = true
            val valid = try {
                mdbListApi.getUser(trimmed).isSuccessful
            } catch (e: Exception) { false }
            _validating.value = false
            if (valid) {
                dataStore.setApiKey(trimmed)
                refreshAccount()
                onSuccess()
            } else {
                _validationError.tryEmit(Unit)
            }
        }
    }

    private fun update(action: suspend () -> Unit) {
        viewModelScope.launch { action() }
    }

    private fun moveRating(provider: String, direction: Int) {
        val currentOrder = _uiState.value.ratingOrder.toMutableList()
        val index = currentOrder.indexOf(provider)
        if (index < 0) return
        val targetIndex = (index + direction).coerceIn(0, currentOrder.lastIndex)
        if (targetIndex == index) return
        currentOrder.removeAt(index)
        currentOrder.add(targetIndex, provider)
        update { dataStore.setRatingOrder(currentOrder) }
    }
}

data class MDBListSettingsUiState(
    val enabled: Boolean = false,
    val apiKey: String = "",
    val isConnected: Boolean = false,
    val showTrakt: Boolean = true,
    val showImdb: Boolean = true,
    val showTmdb: Boolean = true,
    val showLetterboxd: Boolean = true,
    val showTomatoes: Boolean = true,
    val showAudience: Boolean = true,
    val showMetacritic: Boolean = true,
    val showMal: Boolean = true,
    val watchProgressSource: WatchProgressSource = WatchProgressSource.NUVIO_SYNC,
    val username: String? = null,
    val plan: String? = null,
    val isSupporter: Boolean? = null,
    val requestsUsed: Int? = null,
    val requestsLimit: Int? = null,
    val showOnHero: Boolean = false,
    val ratingOrder: List<String> = com.nuvio.tv.domain.model.MDBListSettings.DEFAULT_RATING_ORDER
) {
    fun fromSettings(settings: MDBListSettings): MDBListSettingsUiState = copy(
        enabled = settings.enabled,
        apiKey = settings.apiKey,
        showTrakt = settings.showTrakt,
        showImdb = settings.showImdb,
        showTmdb = settings.showTmdb,
        showLetterboxd = settings.showLetterboxd,
        showTomatoes = settings.showTomatoes,
        showAudience = settings.showAudience,
        showMetacritic = settings.showMetacritic,
        showMal = settings.showMal,
        showOnHero = settings.showOnHero,
        ratingOrder = settings.ratingOrder
    )
}

sealed class MDBListSettingsEvent {
    data class ToggleEnabled(val enabled: Boolean) : MDBListSettingsEvent()
    data class ToggleTrakt(val enabled: Boolean) : MDBListSettingsEvent()
    data class ToggleImdb(val enabled: Boolean) : MDBListSettingsEvent()
    data class ToggleTmdb(val enabled: Boolean) : MDBListSettingsEvent()
    data class ToggleLetterboxd(val enabled: Boolean) : MDBListSettingsEvent()
    data class ToggleTomatoes(val enabled: Boolean) : MDBListSettingsEvent()
    data class ToggleAudience(val enabled: Boolean) : MDBListSettingsEvent()
    data class ToggleMetacritic(val enabled: Boolean) : MDBListSettingsEvent()
    data class ToggleMal(val enabled: Boolean) : MDBListSettingsEvent()
    data class ToggleShowOnHero(val enabled: Boolean) : MDBListSettingsEvent()
    data class MoveRatingUp(val provider: String) : MDBListSettingsEvent()
    data class MoveRatingDown(val provider: String) : MDBListSettingsEvent()
}
