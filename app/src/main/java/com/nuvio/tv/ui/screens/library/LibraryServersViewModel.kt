package com.nuvio.tv.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.poster.CustomPosterScreen
import com.nuvio.tv.core.poster.patternForScreen
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.mediaserver.ServerCatalog
import com.nuvio.tv.data.mediaserver.ServerFailure
import com.nuvio.tv.data.mediaserver.ServerLibraryRef
import com.nuvio.tv.data.mediaserver.ServerRepository
import com.nuvio.tv.data.mediaserver.ServersUiState
import com.nuvio.tv.data.mediaserver.serverFailure
import com.nuvio.tv.domain.model.CatalogRow
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LibraryServerShelf(
    val key: String,
    val title: String,
    val row: CatalogRow? = null,
    val failure: ServerFailure? = null
)

@HiltViewModel
class LibraryServersViewModel @Inject constructor(
    private val serverCatalog: ServerCatalog,
    repository: ServerRepository,
    layoutPreferenceDataStore: LayoutPreferenceDataStore
) : ViewModel() {
    val servers: StateFlow<ServersUiState> = repository.uiState
    private val _shelves = MutableStateFlow<List<LibraryServerShelf>?>(null)
    val shelves: StateFlow<List<LibraryServerShelf>?> = _shelves.asStateFlow()
    private var loadJob: Job? = null
    private var loadedRevision: Int? = null
    var focusedShelfKey: String? = null
        private set
    val focusedIndexes = mutableMapOf<String, Int>()
    private val posterPattern = combine(
        layoutPreferenceDataStore.customPosterUrlPattern,
        layoutPreferenceDataStore.customPosterEnabledScreens
    ) { pattern, screens -> patternForScreen(pattern, CustomPosterScreen.LIBRARY, screens) }
        .distinctUntilChanged()

    init {
        viewModelScope.launch {
            posterPattern.drop(1).collect {
                if (_shelves.value != null) {
                    loadedRevision = null
                    load()
                }
            }
        }
    }

    fun onItemFocused(shelfKey: String, index: Int) {
        focusedShelfKey = shelfKey
        focusedIndexes[shelfKey] = index
    }

    fun load() {
        val revision = servers.value.revision
        if (loadedRevision == revision && _shelves.value != null) return
        loadedRevision = revision
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val pattern = posterPattern.first()
            val shelves = coroutineScope {
                serverCatalog.libraries().map { ref -> async { shelf(ref, pattern) } }.awaitAll()
            }
            _shelves.value = shelves
            shelves.forEach { shelf ->
                val row = shelf.row ?: return@forEach
                launch {
                    val updated = serverCatalog.settledArtwork(row, pattern) ?: return@launch
                    _shelves.update { current ->
                        current?.map { if (it.key == shelf.key && it.row === row) it.copy(row = updated) else it }
                    }
                }
            }
        }
    }

    private suspend fun shelf(ref: ServerLibraryRef, posterPattern: String): LibraryServerShelf {
        val key = "${ref.connection.id}:${ref.library.id}"
        return try {
            LibraryServerShelf(key, ref.title, row = serverCatalog.row(ref.connection.id, ref.library.id, posterPattern))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            LibraryServerShelf(key, ref.title, failure = error.serverFailure())
        }
    }
}
