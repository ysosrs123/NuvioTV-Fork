package com.nuvio.tv.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.repository.LibraryTransferMode
import com.nuvio.tv.domain.model.LibrarySourceMode
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.components.PanelActionRow

private enum class TransferStep { PICK_SOURCE, PICK_DEST, REVIEW }

/**
 * The library transfer flow: pick a source, pick a destination, review a dry-run
 * plan (nothing is written yet), then confirm Copy or Move. Reuses the existing
 * settings pickers and dialog; the engine is [LibraryTransferViewModel].
 */
@Composable
fun LibraryTransferFlow(
    availableModes: List<LibrarySourceMode>,
    onDismiss: () -> Unit,
    viewModel: LibraryTransferViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var step by remember { mutableStateOf(TransferStep.PICK_SOURCE) }

    val cancel: () -> Unit = {
        viewModel.reset()
        onDismiss()
    }

    when (step) {
        TransferStep.PICK_SOURCE -> SettingsSingleChoiceDialog(
            title = stringResource(R.string.library_transfer_source_title),
            subtitle = stringResource(R.string.library_transfer_source_subtitle),
            options = availableModes.map { SettingsPickerOption(it, libraryModeLabel(it)) },
            selectedValue = state.source,
            onOptionSelected = { mode ->
                viewModel.setSource(mode)
                step = TransferStep.PICK_DEST
            },
            onDismiss = cancel,
            width = 620.dp,
            maxHeight = 340.dp
        )

        TransferStep.PICK_DEST -> SettingsSingleChoiceDialog(
            title = stringResource(R.string.library_transfer_dest_title),
            subtitle = stringResource(R.string.library_transfer_dest_subtitle),
            options = availableModes
                .filter { it != state.source }
                .map { SettingsPickerOption(it, libraryModeLabel(it)) },
            selectedValue = state.destination,
            onOptionSelected = { mode ->
                viewModel.setDestination(mode)
                viewModel.preview()
                step = TransferStep.REVIEW
            },
            onDismiss = cancel,
            width = 620.dp,
            maxHeight = 340.dp
        )

        TransferStep.REVIEW -> LibraryTransferReviewDialog(
            state = state,
            sourceLabel = libraryModeLabel(state.source),
            destinationLabel = libraryModeLabel(state.destination),
            onCopy = { viewModel.execute(LibraryTransferMode.COPY) },
            onMove = { viewModel.execute(LibraryTransferMode.MOVE) },
            onClose = cancel
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LibraryTransferReviewDialog(
    state: LibraryTransferUiState,
    sourceLabel: String,
    destinationLabel: String,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onClose: () -> Unit
) {
    val plan = state.plan
    val result = state.result
    NuvioDialog(
        onDismiss = onClose,
        title = stringResource(R.string.library_transfer_title),
        subtitle = "$sourceLabel  \u2192  $destinationLabel"
    ) {
        when {
            state.busy -> Text(
                text = stringResource(R.string.library_transfer_working),
                style = MaterialTheme.typography.bodyMedium
            )

            result != null -> {
                Text(
                    text = stringResource(
                        R.string.library_transfer_result,
                        result.copied, result.alreadyPresent, result.unmatched
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
                if (result.removedFromSource > 0) {
                    Text(
                        text = stringResource(R.string.library_transfer_removed, result.removedFromSource),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (result.keptAtSource > 0) {
                    Text(
                        text = stringResource(R.string.library_transfer_kept, result.keptAtSource),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                PanelActionRow(label = stringResource(R.string.action_close), onClick = onClose)
            }

            plan != null -> {
                Text(
                    text = stringResource(
                        R.string.library_transfer_plan,
                        plan.willWrite, plan.alreadyPresent, plan.unmatched
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = stringResource(R.string.library_transfer_linked_warning),
                    style = MaterialTheme.typography.bodySmall
                )
                PanelActionRow(
                    label = stringResource(R.string.library_transfer_copy),
                    onClick = onCopy,
                    enabled = plan.willWrite > 0
                )
                PanelActionRow(
                    label = stringResource(R.string.library_transfer_move),
                    onClick = onMove,
                    enabled = plan.willWrite > 0
                )
                PanelActionRow(label = stringResource(R.string.action_cancel), onClick = onClose)
            }

            else -> {
                Text(
                    text = stringResource(R.string.library_transfer_none),
                    style = MaterialTheme.typography.bodyMedium
                )
                PanelActionRow(label = stringResource(R.string.action_close), onClick = onClose)
            }
        }
    }
}

@Composable
private fun libraryModeLabel(mode: LibrarySourceMode): String = when (mode) {
    LibrarySourceMode.LOCAL -> stringResource(R.string.trakt_library_source_nuvio)
    LibrarySourceMode.TRAKT -> stringResource(R.string.trakt_name)
    LibrarySourceMode.SIMKL -> stringResource(R.string.simkl_name)
    LibrarySourceMode.MDBLIST -> stringResource(R.string.mdblist_name)
}
