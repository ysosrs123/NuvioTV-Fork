package com.nuvio.tv.ui.screens.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.scout.ScoutMixCatalogProvider
import com.nuvio.tv.core.scout.ScoutOccasion
import com.nuvio.tv.core.scout.ScoutReadyMixes
import com.nuvio.tv.data.local.ScoutMixStore
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.components.PanelActionRow
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/** A mix card on Home that a long press can act on: one the viewer saved, or the holiday in season. */
sealed interface HomeMixTarget {
    val folderId: String

    data class Saved(override val folderId: String, val name: String) : HomeMixTarget
    data class Season(override val folderId: String, val occasion: ScoutOccasion) : HomeMixTarget
}

@HiltViewModel
class HomeMixMenuViewModel @Inject constructor(private val store: ScoutMixStore) : ViewModel() {

    /** More mixes and anything outside the Mixes row get no menu. */
    suspend fun target(collectionId: String, folderId: String): HomeMixTarget? {
        if (collectionId != ScoutMixCatalogProvider.COLLECTION_ID || folderId == ScoutMixCatalogProvider.FOLDER_MORE) return null
        store.mix(folderId)?.let { return HomeMixTarget.Saved(folderId, it.name) }
        return ScoutReadyMixes.byId(folderId)?.occasion?.let { HomeMixTarget.Season(folderId, it) }
    }

    fun remove(target: HomeMixTarget.Saved) {
        viewModelScope.launch { store.delete(target.folderId) }
    }

    fun hide(target: HomeMixTarget.Season) {
        viewModelScope.launch { store.hideSeason(target.occasion.seasonKey(LocalDate.now())) }
    }
}

@Composable
internal fun HomeMixMenu(
    target: HomeMixTarget,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onRemove: (HomeMixTarget.Saved) -> Unit,
    onHide: (HomeMixTarget.Season) -> Unit
) {
    var confirming by remember(target) { mutableStateOf(false) }
    val name = when (target) {
        is HomeMixTarget.Saved -> target.name
        is HomeMixTarget.Season -> stringResource(target.occasion.nameRes)
    }
    if (confirming && target is HomeMixTarget.Saved) {
        val cancelFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { cancelFocus.requestFocusAfterFrames() }
        NuvioDialog(
            onDismiss = { confirming = false },
            title = stringResource(R.string.scout_mixes_delete_title, name),
            subtitle = stringResource(R.string.scout_mixes_delete_detail)
        ) {
            PanelActionRow(label = stringResource(R.string.scout_mixes_cancel), onClick = { confirming = false }, focusRequester = cancelFocus)
            PanelActionRow(label = stringResource(R.string.scout_mixes_delete_confirm), onClick = { onRemove(target) })
        }
        return
    }
    val openFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { openFocus.requestFocusAfterFrames() }
    NuvioDialog(onDismiss = onDismiss, title = name, subtitle = stringResource(R.string.scout_mixes_menu_subtitle)) {
        PanelActionRow(label = stringResource(R.string.scout_mixes_menu_open), onClick = onOpen, focusRequester = openFocus)
        when (target) {
            is HomeMixTarget.Saved -> PanelActionRow(label = stringResource(R.string.scout_mixes_menu_remove), onClick = { confirming = true })
            is HomeMixTarget.Season -> PanelActionRow(label = stringResource(R.string.scout_mixes_menu_hide), onClick = { onHide(target) })
        }
    }
}
