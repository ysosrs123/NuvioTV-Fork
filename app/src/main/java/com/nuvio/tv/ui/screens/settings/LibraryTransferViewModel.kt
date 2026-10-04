package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.repository.LibraryTransferMode
import com.nuvio.tv.data.repository.LibraryTransferPlan
import com.nuvio.tv.data.repository.LibraryTransferResult
import com.nuvio.tv.data.repository.LibraryTransferService
import com.nuvio.tv.domain.model.LibrarySourceMode
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LibraryTransferUiState(
    val source: LibrarySourceMode = LibrarySourceMode.LOCAL,
    val destination: LibrarySourceMode = LibrarySourceMode.MDBLIST,
    val plan: LibraryTransferPlan? = null,
    val result: LibraryTransferResult? = null,
    val busy: Boolean = false
)

@HiltViewModel
class LibraryTransferViewModel @Inject constructor(
    private val transferService: LibraryTransferService
) : ViewModel() {

    private val _state = MutableStateFlow(LibraryTransferUiState())
    val state: StateFlow<LibraryTransferUiState> = _state.asStateFlow()

    fun setSource(mode: LibrarySourceMode) = _state.update {
        it.copy(source = mode, plan = null, result = null)
    }

    fun setDestination(mode: LibrarySourceMode) = _state.update {
        it.copy(destination = mode, plan = null, result = null)
    }

    fun preview() {
        val current = _state.value
        if (current.source == current.destination) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, plan = null, result = null) }
            val plan = runCatching {
                transferService.preview(current.source, current.destination)
            }.getOrNull()
            _state.update { it.copy(busy = false, plan = plan) }
        }
    }

    fun execute(mode: LibraryTransferMode) {
        val current = _state.value
        if (current.source == current.destination) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val result = runCatching {
                transferService.execute(current.source, current.destination, mode)
            }.getOrNull()
            _state.update { it.copy(busy = false, result = result, plan = null) }
        }
    }

    fun reset() {
        _state.value = LibraryTransferUiState()
    }
}
