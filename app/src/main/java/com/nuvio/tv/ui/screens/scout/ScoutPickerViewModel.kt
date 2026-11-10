package com.nuvio.tv.ui.screens.scout

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.scout.SCOUT_PINNED_ART
import com.nuvio.tv.core.scout.ScoutCatalogProvider
import com.nuvio.tv.core.scout.ScoutEngine
import com.nuvio.tv.core.scout.ScoutSlice
import com.nuvio.tv.core.scout.ScoutSliceGroup
import com.nuvio.tv.core.scout.ScoutSlices
import com.nuvio.tv.core.tmdb.TmdbCollectionSourceResolver
import com.nuvio.tv.core.util.withAppLocale
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.TmdbCollectionSource
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject

data class ScoutPickerTile(val id: String, val title: String, val cover: String?, val showTitle: Boolean = true, val fallback: String? = null)

data class ScoutPickerState(
    val group: ScoutSliceGroup = ScoutSliceGroup.MOOD,
    val tiles: List<ScoutPickerTile> = emptyList()
)

private const val COVER_REQUESTS_AT_ONCE = 2
private const val TMDB_ORIGINAL = "https://image.tmdb.org/t/p/original"

@HiltViewModel
class ScoutPickerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val provider: ScoutCatalogProvider,
    private val resolver: TmdbCollectionSourceResolver
) : ViewModel() {
    private val covers = HashMap<String, String?>()
    private val _state = MutableStateFlow(ScoutPickerState())
    val state: StateFlow<ScoutPickerState> = _state.asStateFlow()
    private var coverJob: Job? = null

    /** The tile that was opened, so Back lands on it again. */
    var openedTile: String? = null
    private val gate = Semaphore(COVER_REQUESTS_AT_ONCE)
    init {
        show(ScoutSliceGroup.MOOD)
    }

    fun select(group: ScoutSliceGroup) {
        if (group != _state.value.group) show(group)
    }

    private fun show(group: ScoutSliceGroup) {
        val slices = ScoutSlices.inGroup(group).filter(provider::offered)
        _state.value = ScoutPickerState(group, slices.map(::tile))
        coverJob?.cancel()
        coverJob = viewModelScope.launch {
            slices.filterNot { covers.containsKey(it.id) || provider.bundledCover(it) != null }.forEach { slice ->
                launch {
                    gate.withPermit {
                        covers[slice.id] = cover(slice)
                        _state.update { s ->
                            if (s.group == group) s.copy(tiles = slices.map(::tile)) else s
                        }
                    }
                }
            }
        }
    }

    private fun tile(slice: ScoutSlice): ScoutPickerTile {
        val bundled = provider.bundledCover(slice)
        val otherwise = bundled ?: covers[slice.id]
        val pinned = SCOUT_PINNED_ART[slice.id]?.let { TMDB_ORIGINAL + it }
        return ScoutPickerTile(
            id = slice.id,
            title = slice.title { res, args -> context.withAppLocale().getString(res, *args) },
            cover = pinned ?: otherwise,
            showTitle = !(bundled != null && slice.group == ScoutSliceGroup.DECADE),
            fallback = otherwise.takeIf { pinned != null }
        )
    }

    private suspend fun cover(slice: ScoutSlice): String? {
        val source = provider.folderFor(slice, null).sources.firstOrNull() as? TmdbCollectionSource ?: return null
        val result = resolver.resolve(source).first { it !is NetworkResult.Loading }
        val items: List<MetaPreview> = (result as? NetworkResult.Success)?.data?.items.orEmpty()
        return items.firstNotNullOfOrNull { it.background }
    }
}
