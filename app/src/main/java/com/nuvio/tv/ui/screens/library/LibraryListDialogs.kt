package com.nuvio.tv.ui.screens.library

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.domain.model.LibraryListTab
import com.nuvio.tv.domain.model.LibraryListPrivacy
import kotlinx.coroutines.delay
import com.nuvio.tv.domain.model.localizedTitle
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.components.PanelActionRow
import com.nuvio.tv.ui.components.PlayerPanelRow
import com.nuvio.tv.ui.theme.NuvioTheme
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import com.nuvio.tv.R
import com.nuvio.tv.core.tracking.TrackingListManagementCapabilities

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun ManageListsDialog(
    tabs: List<LibraryListTab>,
    capabilities: TrackingListManagementCapabilities,
    selectedKey: String?,
    errorMessage: String?,
    pending: Boolean,
    onSelect: (String) -> Unit,
    onCreate: () -> Unit,
    onEdit: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val personalTabs = remember(tabs) { tabs.filter { it.type == LibraryListTab.Type.PERSONAL } }
    val firstFocusRequester = remember { FocusRequester() }

    LaunchedEffect(personalTabs.size, pending) {
        if (pending) return@LaunchedEffect
        val target = firstFocusRequester
        val focused = runCatching { target.requestFocus() }.isSuccess
        if (!focused) {
            delay(16)
            runCatching { target.requestFocus() }
        }
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.library_manage_lists),
        width = 620.dp
    ) {
        if (errorMessage != null) {
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFFFB6B6)
            )
        }

        if (personalTabs.isEmpty()) {
            Text(
                text = stringResource(R.string.library_no_lists),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.extendedColors.textSecondary
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(personalTabs, key = { it.key }) { tab ->
                    PlayerPanelRow(
                        title = tab.localizedTitle(),
                        selected = tab.key == selectedKey,
                        onClick = { if (!pending) onSelect(tab.key) },
                        focusRequester = if (tab.key == personalTabs.firstOrNull()?.key) firstFocusRequester else null
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PanelActionRow(
                label = stringResource(R.string.library_list_create),
                onClick = onCreate,
                enabled = !pending,
                modifier = Modifier.weight(1f),
                focusRequester = if (personalTabs.isEmpty()) firstFocusRequester else null
            )
            PanelActionRow(
                label = stringResource(R.string.library_list_edit),
                onClick = onEdit,
                enabled = !pending && selectedKey != null,
                modifier = Modifier.weight(1f)
            )
            if (capabilities.supportsReordering) {
                PanelActionRow(
                    label = stringResource(R.string.library_list_move_up),
                    onClick = onMoveUp,
                    enabled = !pending && selectedKey != null,
                    modifier = Modifier.weight(1f)
                )
                PanelActionRow(
                    label = stringResource(R.string.library_list_move_down),
                    onClick = onMoveDown,
                    enabled = !pending && selectedKey != null,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PanelActionRow(
                label = stringResource(R.string.library_list_delete),
                onClick = onDelete,
                enabled = !pending && selectedKey != null,
                modifier = Modifier.weight(1f)
            )
            PanelActionRow(
                label = stringResource(R.string.library_list_close),
                onClick = onDismiss,
                enabled = !pending,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun ListEditorDialog(
    state: LibraryListEditorState,
    capabilities: TrackingListManagementCapabilities,
    pending: Boolean,
    onNameChanged: (String) -> Unit,
    onDescriptionChanged: (String) -> Unit,
    onPrivacyChanged: (LibraryListPrivacy) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    val nameFocusRequester = remember { FocusRequester() }
    val descriptionFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var nameEditing by remember { mutableStateOf(false) }
    var descriptionEditing by remember { mutableStateOf(false) }

    fun isSelectKey(keyCode: Int): Boolean {
        return keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
            keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
    }

    LaunchedEffect(Unit) {
        nameFocusRequester.requestFocus()
    }

    NuvioDialog(
        onDismiss = onCancel,
        title = if (state.mode == LibraryListEditorState.Mode.CREATE) stringResource(R.string.library_list_create_dialog_title) else stringResource(R.string.library_list_edit_dialog_title),
        width = 560.dp
    ) {
        androidx.compose.material3.OutlinedTextField(
            value = state.name,
            onValueChange = onNameChanged,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(nameFocusRequester)
                .onFocusChanged {
                    if (!it.isFocused) {
                        nameEditing = false
                    }
                }
                .onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    if (native.action == AndroidKeyEvent.ACTION_DOWN && isSelectKey(native.keyCode)) {
                        nameEditing = true
                        descriptionEditing = false
                        keyboardController?.show()
                    }
                    false
                },
            enabled = !pending,
            readOnly = !nameEditing,
            singleLine = true,
            maxLines = 1,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    nameEditing = false
                    keyboardController?.hide()
                }
            ),
            label = { androidx.compose.material3.Text(stringResource(R.string.library_list_name_label)) },
            textStyle = androidx.compose.material3.LocalTextStyle.current.copy(
                textDirection = TextDirection.Content
            ),
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedTextColor = NuvioTheme.colors.TextPrimary,
                unfocusedTextColor = NuvioTheme.colors.TextPrimary,
                focusedContainerColor = NuvioTheme.colors.BackgroundCard,
                unfocusedContainerColor = NuvioTheme.colors.BackgroundCard,
                focusedBorderColor = NuvioTheme.colors.FocusRing,
                unfocusedBorderColor = NuvioTheme.colors.Border,
                focusedLabelColor = NuvioTheme.colors.TextSecondary,
                unfocusedLabelColor = NuvioTheme.colors.TextTertiary,
                cursorColor = NuvioTheme.colors.FocusRing,
            )
        )

        if (capabilities.supportsDescription) {
            androidx.compose.material3.OutlinedTextField(
                value = state.description,
                onValueChange = onDescriptionChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(descriptionFocusRequester)
                    .onFocusChanged {
                        if (!it.isFocused) {
                            descriptionEditing = false
                        }
                    }
                    .onPreviewKeyEvent { event ->
                        val native = event.nativeKeyEvent
                        if (native.action == AndroidKeyEvent.ACTION_DOWN && isSelectKey(native.keyCode)) {
                            descriptionEditing = true
                            nameEditing = false
                            keyboardController?.show()
                        }
                        false
                    },
                enabled = !pending,
                readOnly = !descriptionEditing,
                minLines = 3,
                maxLines = 5,
                label = { androidx.compose.material3.Text(stringResource(R.string.library_list_description_label)) },
                textStyle = androidx.compose.material3.LocalTextStyle.current.copy(
                    textDirection = TextDirection.Content
                ),
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedTextColor = NuvioTheme.colors.TextPrimary,
                    unfocusedTextColor = NuvioTheme.colors.TextPrimary,
                    focusedContainerColor = NuvioTheme.colors.BackgroundCard,
                    unfocusedContainerColor = NuvioTheme.colors.BackgroundCard,
                    focusedBorderColor = NuvioTheme.colors.FocusRing,
                    unfocusedBorderColor = NuvioTheme.colors.Border,
                    focusedLabelColor = NuvioTheme.colors.TextSecondary,
                    unfocusedLabelColor = NuvioTheme.colors.TextTertiary,
                    cursorColor = NuvioTheme.colors.FocusRing,
                )
            )
        }

        Text(
            text = stringResource(R.string.library_list_privacy),
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.extendedColors.textSecondary
        )

        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(capabilities.privacyOptions, key = { it.name }) { privacy ->
                val selected = privacy == state.privacy
                Button(
                    onClick = { onPrivacyChanged(privacy) },
                    enabled = !pending,
                    colors = ButtonDefaults.colors(
                        containerColor = if (selected) NuvioTheme.colors.FocusBackground else NuvioTheme.colors.BackgroundCard,
                        contentColor = NuvioTheme.colors.TextPrimary
                    )
                ) {
                    Text(privacy.apiValue.replaceFirstChar { it.uppercase() })
                }
            }
        }

        PanelActionRow(
            label = if (pending) stringResource(R.string.action_saving) else stringResource(R.string.action_save),
            onClick = onSave,
            enabled = !pending
        )
    }
}
