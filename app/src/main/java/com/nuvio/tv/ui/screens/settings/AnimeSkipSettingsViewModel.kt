package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.AnimeSkipSettingsDataStore
import com.nuvio.tv.data.remote.api.AnimeSkipApi
import com.nuvio.tv.data.remote.api.AnimeSkipRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AnimeSkipSettingsViewModel @Inject constructor(
    private val dataStore: AnimeSkipSettingsDataStore,
    private val animeSkipApi: AnimeSkipApi
) : ViewModel() {

    private val _clientId = MutableStateFlow("")
    val clientId: StateFlow<String> = _clientId.asStateFlow()

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _validating = MutableStateFlow(false)
    val validating: StateFlow<Boolean> = _validating.asStateFlow()

    private val _validationError = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val validationError: SharedFlow<Unit> = _validationError.asSharedFlow()

    init {
        viewModelScope.launch {
            dataStore.clientId.collectLatest { _clientId.update { _ -> it } }
        }
        viewModelScope.launch {
            dataStore.enabled.collectLatest { _enabled.update { _ -> it } }
        }
    }

    fun setEnabled(value: Boolean) {
        viewModelScope.launch { dataStore.setEnabled(value) }
    }

    private var validationJob: Job? = null

    fun validateAndSave(value: String, onSuccess: () -> Unit) {
        validationJob?.cancel()
        validationJob = viewModelScope.launch {
            val profileId = dataStore.snapshot().profileId
            val trimmed = value.trim()
            _validating.value = trimmed.isNotBlank()
            try {
                val valid = if (trimmed.isBlank()) true else try {
                    val response = animeSkipApi.query(
                        clientId = trimmed,
                        body = AnimeSkipRequest(query = "{ findShowsByExternalId(service: ANILIST, serviceId: \"1\") { id } }")
                    )
                    response.isSuccessful && response.body()?.data != null
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) { false }
                if (valid) {
                    // Validation may outlive a profile switch: never save into the new profile.
                    dataStore.setClientId(trimmed, profileId)
                    onSuccess()
                } else {
                    _validationError.tryEmit(Unit)
                }
            } finally {
                _validating.value = false
            }
        }
    }
}
