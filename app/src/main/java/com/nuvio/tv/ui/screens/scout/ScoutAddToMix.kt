package com.nuvio.tv.ui.screens.scout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.screens.collection.NuvioTextField
import com.nuvio.tv.core.scout.ScoutMixLibrary
import com.nuvio.tv.data.local.ScoutMixStore
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.ScoutMix
import com.nuvio.tv.domain.model.ScoutMixShow
import com.nuvio.tv.domain.model.ScoutMixes
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.components.PanelActionRow
import com.nuvio.tv.ui.screens.home.ContinueWatchingItem
import com.nuvio.tv.ui.theme.NuvioTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ScoutAddToMixViewModel @Inject constructor(
    private val store: ScoutMixStore,
    private val library: ScoutMixLibrary
) : ViewModel() {
    val mixes = store.mixes.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** A mix that already holds the show gives it up instead. */
    fun toggle(mix: ScoutMix, item: MetaPreview) {
        viewModelScope.launch {
            val show = library.showFrom(item)
            store.update(mix.id) {
                library.withSuggestedName(if (it.holds(show)) it.removing(show) else it.adding(show), mixes.value)
            }
        }
    }

    fun holds(mix: ScoutMix, item: MetaPreview): Boolean = mix.holds(
        ScoutMixShow(id = item.id, name = item.name, imdbId = item.imdbId)
    )

    /** The show's own name is enough for a new mix, so this needs no lookup. */
    fun suggestedName(item: MetaPreview): String = library.suggestedName(
        listOf(ScoutMixShow(id = item.id, name = item.name)),
        mixes.value.map { it.name }
    )

    /** Keeping the suggestion lets the name follow the shows; typing one fixes it. */
    fun create(name: String, suggestion: String, item: MetaPreview) {
        viewModelScope.launch {
            store.create(name, library.showFrom(item), autoName = name.trim() == suggestion)
        }
    }
}

internal fun scoutMixPreview(item: ContinueWatchingItem): MetaPreview? = when (item) {
    is ContinueWatchingItem.InProgress -> with(item.progress) { showPreview(contentId, contentType, name, poster, backdrop, logo) }
    is ContinueWatchingItem.NextUp -> with(item.info) { showPreview(contentId, contentType, name, poster, backdrop, logo) }
}

private fun showPreview(id: String, type: String, name: String, poster: String?, backdrop: String?, logo: String?): MetaPreview? {
    if (!ScoutMixes.canHold(type)) return null
    return MetaPreview(
        id = id,
        type = ContentType.fromString(type),
        rawType = type,
        name = name,
        poster = poster,
        posterShape = PosterShape.POSTER,
        background = backdrop,
        logo = logo,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList(),
        imdbId = id.takeIf { it.startsWith("tt") }
    )
}

/** Call from inside a dialog: another dialog opened from it shows alone, without this one behind it. */
@Composable
internal fun HideDialogWindowWhile(hidden: Boolean) {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    DisposableEffect(window, hidden) {
        if (!hidden || window == null) return@DisposableEffect onDispose {}
        val dim = window.attributes.dimAmount
        window.decorView.alpha = 0f
        window.setDimAmount(0f)
        onDispose {
            window.decorView.alpha = 1f
            window.setDimAmount(dim)
        }
    }
}

/** The long-press menu's line, for shows only. */
@Composable
fun ScoutAddToMixLine(item: MetaPreview, onDone: () -> Unit) {
    if (!ScoutMixes.available || !ScoutMixes.canHold(item.apiType)) return
    var choosing by remember { mutableStateOf(false) }
    PanelActionRow(label = stringResource(R.string.scout_mixes_add), onClick = { choosing = true })
    HideDialogWindowWhile(choosing)
    if (choosing) {
        ScoutAddToMixDialog(
            item = item,
            onDismiss = { choosing = false },
            onDone = {
                choosing = false
                onDone()
            }
        )
    }
}

@Composable
private fun ScoutAddToMixDialog(
    item: MetaPreview,
    onDismiss: () -> Unit,
    onDone: () -> Unit,
    viewModel: ScoutAddToMixViewModel = hiltViewModel()
) {
    val mixes by viewModel.mixes.collectAsStateWithLifecycle()
    var naming by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(naming) { if (!naming) focus.requestFocusAfterFrames() }
    if (naming) {
        val suggestion = remember { viewModel.suggestedName(item) }
        ScoutMixNameDialog(
            initial = suggestion,
            onSave = {
                viewModel.create(it, suggestion, item)
                onDone()
            },
            onDismiss = { naming = false }
        )
        return
    }
    NuvioDialog(onDismiss = onDismiss, title = stringResource(R.string.scout_mixes_add_title, item.name)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (mixes.isNotEmpty()) {
                LazyColumn(modifier = Modifier.heightIn(max = 240.dp), contentPadding = PaddingValues(vertical = NuvioTheme.spacing.sm), verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
                    itemsIndexed(mixes) { index, mix ->
                        val holds = viewModel.holds(mix, item)
                        val detail = if (holds) stringResource(R.string.scout_mixes_already_in)
                        else pluralStringResource(R.plurals.scout_mixes_shows, mix.shows.size, mix.shows.size)
                        PanelActionRow(
                            label = "${mix.name} • $detail",
                            focusRequester = if (index == 0) focus else null,
                            onClick = {
                                viewModel.toggle(mix, item)
                                onDone()
                            }
                        )
                    }
                }
            }
            PanelActionRow(
                label = stringResource(R.string.scout_mixes_new),
                icon = Icons.Default.Add,
                focusRequester = if (mixes.isEmpty()) focus else null,
                onClick = { naming = true }
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.itemsIndexed(
    mixes: List<ScoutMix>,
    content: @Composable (Int, ScoutMix) -> Unit
) {
    items(mixes.withIndex().toList(), key = { it.value.id }) { (index, mix) -> content(index, mix) }
}

@Composable
internal fun ScoutMixNameDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val saveFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { saveFocus.requestFocusAfterFrames() }
    NuvioDialog(onDismiss = onDismiss, title = stringResource(R.string.scout_mixes_name_title)) {
        NuvioTextField(
            value = name,
            onValueChange = { name = it.take(ScoutMixes.NAME_LIMIT) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = stringResource(R.string.scout_mixes_name_label)
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PanelActionRow(
                label = stringResource(R.string.scout_mixes_save),
                modifier = Modifier.weight(1f),
                enabled = name.isNotBlank(),
                focusRequester = saveFocus,
                onClick = { onSave(name) }
            )
            PanelActionRow(
                label = stringResource(R.string.scout_mixes_cancel),
                modifier = Modifier.weight(1f),
                onClick = onDismiss
            )
        }
    }
}

