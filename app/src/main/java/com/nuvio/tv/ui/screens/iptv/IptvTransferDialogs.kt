@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
package com.nuvio.tv.ui.screens.iptv

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Usb
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.iptv.SetupImportMode
import com.nuvio.tv.core.iptv.SetupVault
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.settings.SettingsActionRow
import com.nuvio.tv.ui.screens.settings.SettingsToggleRow
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.v2.components.NuvioActionPill

@Composable
fun IptvTransferDialog(mode: IptvTransferMode, onClose: () -> Unit, viewModel: IptvTransferViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val close = { viewModel.close(); onClose() }
    LaunchedEffect(mode) { viewModel.open(mode) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && (mode == IptvTransferMode.SEND || mode == IptvTransferMode.RECEIVE)) close()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.close()
        }
    }
    val review = state.review
    when {
        review != null -> ImportReviewDialog(review, state.busy, state.message, onImport = viewModel::import, onCancel = viewModel::declineImport)
        state.finished -> Finished(mode, state, close)
        mode == IptvTransferMode.SEND -> SendDialog(state, viewModel, close)
        mode == IptvTransferMode.RECEIVE -> ReceiveDialog(state, close)
        mode == IptvTransferMode.BACKUP -> BackupDialog(state, viewModel, close)
        mode == IptvTransferMode.RESTORE -> RestoreDialog(state, viewModel, close)
    }
}

@Composable
private fun transferTitle(mode: IptvTransferMode): String = stringResource(when (mode) {
    IptvTransferMode.SEND -> R.string.iptv_copy_send_title
    IptvTransferMode.RECEIVE -> R.string.iptv_copy_receive_title
    IptvTransferMode.BACKUP -> R.string.iptv_backup_title
    IptvTransferMode.RESTORE -> R.string.iptv_restore_title
})

@Composable
private fun FirstFocus(requester: FocusRequester, key: Any? = Unit) {
    LaunchedEffect(key) { withFrameNanos { }; runCatching { requester.requestFocus() } }
}

@Composable
private fun Message(state: IptvTransferState, error: Boolean = true) {
    state.message?.let {
        Text(stringResource(it, state.messageArg), color = if (error) NuvioTheme.colors.Error else NuvioTheme.colors.TextSecondary,
            style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Buttons(busy: Boolean, content: @Composable () -> Unit) {
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        content()
        if (busy) LoadingIndicator(Modifier.size(24.dp))
    }
}

@Composable
private fun Finished(mode: IptvTransferMode, state: IptvTransferState, close: () -> Unit) {
    val done = remember { FocusRequester() }
    FirstFocus(done)
    NuvioDialog(onDismiss = close, title = transferTitle(mode), width = 560.dp) {
        Message(state, error = false)
        NuvioActionPill(close, Modifier.focusRequester(done)) { Text(stringResource(R.string.iptv_remote_done)) }
    }
}

@Composable
private fun SendDialog(state: IptvTransferState, viewModel: IptvTransferViewModel, close: () -> Unit) {
    var logins by remember { mutableStateOf(true) }
    var target by remember { mutableStateOf<String?>(null) }
    var manual by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    val first = remember { FocusRequester() }
    FirstFocus(first, target == null)
    NuvioDialog(onDismiss = close, title = transferTitle(IptvTransferMode.SEND), subtitle = stringResource(R.string.iptv_copy_send_subtitle), width = 640.dp) {
        if (target == null) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SettingsToggleRow(title = stringResource(R.string.iptv_transfer_logins), subtitle = stringResource(R.string.iptv_transfer_logins_subtitle),
                    checked = logins, onToggle = { logins = !logins }, modifier = Modifier.focusRequester(first))
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.iptv_copy_found), color = NuvioTheme.colors.TextSecondary, style = MaterialTheme.typography.labelLarge)
                if (state.peers.isEmpty()) Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LoadingIndicator(Modifier.size(18.dp))
                    Text(stringResource(R.string.iptv_copy_searching), color = NuvioTheme.colors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                }
                state.peers.forEach { peer ->
                    SettingsActionRow(title = peer.name, subtitle = peer.address, onClick = { manual = false; target = peer.address; viewModel.clearMessage() },
                        leadingIcon = Icons.Filled.Tv)
                }
                SettingsActionRow(title = stringResource(R.string.iptv_copy_enter_address), subtitle = stringResource(R.string.iptv_copy_enter_address_subtitle),
                    onClick = { manual = true; target = ""; viewModel.clearMessage() }, leadingIcon = Icons.Filled.Edit)
            }
            Buttons(false) { NuvioActionPill(close) { Text(stringResource(R.string.iptv_setup_cancel)) } }
        } else {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (manual) SourceField(stringResource(R.string.iptv_copy_address), address, { address = it.trim().take(64) },
                    hint = stringResource(R.string.iptv_copy_address_hint), keyboardType = KeyboardType.Uri, modifier = Modifier.focusRequester(first))
                else Text(stringResource(R.string.iptv_copy_sending_to, target.orEmpty()), color = NuvioTheme.colors.TextPrimary, style = MaterialTheme.typography.bodyLarge)
                SourceField(stringResource(R.string.iptv_copy_code), code, { code = it.filter(Char::isDigit).take(6) }, hint = stringResource(R.string.iptv_copy_code_hint),
                    keyboardType = KeyboardType.NumberPassword, last = true, modifier = if (manual) Modifier else Modifier.focusRequester(first))
                Message(state)
                Buttons(state.busy) {
                    NuvioActionPill({ viewModel.send(if (manual) address else target.orEmpty(), code, logins) }, enabled = !state.busy && code.length == 6) {
                        Text(stringResource(R.string.iptv_copy_send))
                    }
                    NuvioActionPill({ target = null; code = ""; viewModel.clearMessage() }, enabled = !state.busy) { Text(stringResource(R.string.iptv_transfer_back)) }
                }
            }
        }
    }
}

@Composable
private fun ReceiveDialog(state: IptvTransferState, close: () -> Unit) {
    val cancel = remember { FocusRequester() }
    FirstFocus(cancel)
    NuvioDialog(onDismiss = close, title = transferTitle(IptvTransferMode.RECEIVE), subtitle = stringResource(R.string.iptv_copy_receive_subtitle), width = 600.dp) {
        val code = state.code
        if (code != null) {
            Text(stringResource(R.string.iptv_copy_code), color = NuvioTheme.colors.TextSecondary, style = MaterialTheme.typography.labelLarge)
            Text(code.chunked(3).joinToString(" "), color = NuvioTheme.colors.TextPrimary, style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold, letterSpacing = 6.sp, maxLines = 1)
            Text(stringResource(R.string.iptv_copy_this_address, state.address.orEmpty()), color = NuvioTheme.colors.TextSecondary,
                style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LoadingIndicator(Modifier.size(18.dp))
                Text(stringResource(R.string.iptv_copy_waiting), color = NuvioTheme.colors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
            }
            Text(stringResource(R.string.iptv_copy_receive_note), color = NuvioTheme.colors.TextTertiary, style = MaterialTheme.typography.bodySmall)
        }
        Message(state)
        NuvioActionPill(close, Modifier.focusRequester(cancel)) { Text(stringResource(R.string.iptv_setup_cancel)) }
    }
}

@Composable
private fun ImportReviewDialog(review: IptvTransferReview, busy: Boolean, message: Int?, onImport: (SetupImportMode) -> Unit, onCancel: () -> Unit) {
    var confirmReplace by remember { mutableStateOf(false) }
    val merge = remember { FocusRequester() }
    FirstFocus(merge, confirmReplace)
    val summary = review.summary
    if (!confirmReplace) NuvioDialog(onDismiss = { if (!busy) onCancel() }, title = stringResource(if (review.fromFile) R.string.iptv_import_title_file else R.string.iptv_import_title_tv),
        subtitle = stringResource(R.string.iptv_import_subtitle), width = 640.dp) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ReviewLine(stringResource(R.string.iptv_import_sources), pluralStringResource(R.plurals.iptv_import_count_sources, summary.sources, summary.sources))
            if (summary.needLogin > 0) ReviewLine(stringResource(R.string.iptv_import_left_out),
                pluralStringResource(R.plurals.iptv_import_need_login, summary.needLogin, summary.needLogin))
            ReviewLine(stringResource(R.string.iptv_import_guides), summary.guides.toString())
            if (summary.groups > 0) ReviewLine(stringResource(R.string.iptv_import_groups), summary.groups.toString())
            ReviewLine(stringResource(R.string.iptv_import_channels), summary.overlays.toString())
            ReviewLine(stringResource(R.string.iptv_import_settings), stringResource(if (summary.settings) R.string.iptv_import_included else R.string.iptv_import_not_included))
            ReviewLine(stringResource(R.string.iptv_import_logins), stringResource(if (summary.logins) R.string.iptv_import_included else R.string.iptv_import_not_included))
            Text(pluralStringResource(R.plurals.iptv_import_merge_note, review.reused, review.reused, review.created), color = NuvioTheme.colors.TextTertiary,
                style = MaterialTheme.typography.bodySmall)
            message?.let { Text(stringResource(it, ""), color = NuvioTheme.colors.Error, style = MaterialTheme.typography.bodyMedium) }
        }
        Buttons(busy) {
            NuvioActionPill({ onImport(SetupImportMode.MERGE) }, Modifier.focusRequester(merge), enabled = !busy) { Text(stringResource(R.string.iptv_import_merge)) }
            NuvioActionPill({ confirmReplace = true }, enabled = !busy) { Text(stringResource(R.string.iptv_import_replace)) }
            NuvioActionPill(onCancel, enabled = !busy) { Text(stringResource(R.string.iptv_setup_cancel)) }
        }
    } else NuvioDialog(onDismiss = { confirmReplace = false }, title = stringResource(R.string.iptv_import_replace_title),
        subtitle = stringResource(R.string.iptv_import_replace_description), width = 560.dp) {
        Buttons(busy) {
            NuvioActionPill({ confirmReplace = false }, Modifier.focusRequester(merge), enabled = !busy) { Text(stringResource(R.string.iptv_setup_cancel)) }
            NuvioActionPill({ onImport(SetupImportMode.REPLACE) }, enabled = !busy) { Text(stringResource(R.string.iptv_import_replace), color = NuvioTheme.colors.Error) }
        }
    }
}

@Composable
private fun ReviewLine(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, color = NuvioTheme.colors.TextSecondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(240.dp))
        Text(value, color = NuvioTheme.colors.TextPrimary, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun BackupDialog(state: IptvTransferState, viewModel: IptvTransferViewModel, close: () -> Unit) {
    var logins by remember { mutableStateOf(true) }
    var passphrase by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    val first = remember { FocusRequester() }
    FirstFocus(first)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) viewModel.backup(passphrase, again, logins, usb = false, uri = uri)
    }
    val ready = passphrase.isNotEmpty() && again.isNotEmpty() && !state.busy
    NuvioDialog(onDismiss = { if (!state.busy) close() }, title = transferTitle(IptvTransferMode.BACKUP), subtitle = stringResource(R.string.iptv_backup_subtitle), width = 640.dp) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SettingsToggleRow(title = stringResource(R.string.iptv_transfer_logins), subtitle = stringResource(R.string.iptv_transfer_logins_subtitle),
                checked = logins, onToggle = { logins = !logins }, modifier = Modifier.focusRequester(first))
            SourceField(stringResource(R.string.iptv_backup_passphrase), passphrase, { passphrase = it.take(SetupVault.MAX_PASSPHRASE) },
                hint = stringResource(R.string.iptv_backup_passphrase_hint), keyboardType = KeyboardType.Password, masked = true)
            SourceField(stringResource(R.string.iptv_backup_passphrase_again), again, { again = it.take(SetupVault.MAX_PASSPHRASE) },
                keyboardType = KeyboardType.Password, masked = true, last = true)
            Text(stringResource(R.string.iptv_backup_save_to), color = NuvioTheme.colors.TextSecondary, style = MaterialTheme.typography.labelLarge)
            SettingsActionRow(title = stringResource(R.string.iptv_backup_device), subtitle = state.folders.getOrNull(0), enabled = ready,
                onClick = { viewModel.backup(passphrase, again, logins, usb = false) }, leadingIcon = Icons.Filled.FolderOpen)
            if (state.usb) SettingsActionRow(title = stringResource(R.string.iptv_backup_usb), subtitle = state.folders.getOrNull(1), enabled = ready,
                onClick = { viewModel.backup(passphrase, again, logins, usb = true) }, leadingIcon = Icons.Filled.Usb)
            SettingsActionRow(title = stringResource(R.string.iptv_backup_choose), subtitle = stringResource(R.string.iptv_backup_choose_subtitle), enabled = ready,
                onClick = {
                    if (viewModel.checkPassphrase(passphrase, again)) {
                        try { picker.launch("nuvio-live-tv." + SetupVault.EXTENSION) }
                        catch (_: android.content.ActivityNotFoundException) { viewModel.pickerUnavailable() }
                    }
                }, leadingIcon = Icons.Filled.Description)
            Message(state)
        }
        Buttons(state.busy) { NuvioActionPill(close, enabled = !state.busy) { Text(stringResource(R.string.iptv_setup_cancel)) } }
    }
}

@Composable
private fun RestoreDialog(state: IptvTransferState, viewModel: IptvTransferViewModel, close: () -> Unit) {
    var passphrase by remember { mutableStateOf("") }
    val first = remember { FocusRequester() }
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null && uri.scheme == "content") viewModel.choose(uri) }
    val files = state.files
    FirstFocus(first, state.chosen to (files == null))
    NuvioDialog(onDismiss = { if (!state.busy) close() }, title = transferTitle(IptvTransferMode.RESTORE), subtitle = stringResource(R.string.iptv_restore_subtitle), width = 640.dp) {
        val chosen = state.chosen
        if (chosen == null) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                when {
                    files == null -> LoadingIndicator(Modifier.size(32.dp))
                    files.isEmpty() -> Text(stringResource(R.string.iptv_restore_empty, state.folders.joinToString("\n")), color = NuvioTheme.colors.TextSecondary,
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp))
                    else -> files.forEachIndexed { index, file ->
                        val modified = android.text.format.DateUtils.getRelativeTimeSpanString(file.modifiedMillis, System.currentTimeMillis(),
                            android.text.format.DateUtils.MINUTE_IN_MILLIS).toString()
                        SettingsActionRow(title = file.name, subtitle = listOf(android.text.format.Formatter.formatShortFileSize(context, file.bytes), modified).joinToString(" · "),
                            value = stringResource(if (file.usb) R.string.iptv_guide_folder_usb else R.string.iptv_guide_folder_device),
                            onClick = { passphrase = ""; viewModel.choose(file) }, leadingIcon = Icons.Filled.Description, trailingIcon = null,
                            modifier = if (index == 0) Modifier.focusRequester(first) else Modifier)
                    }
                }
                SettingsActionRow(title = stringResource(R.string.iptv_restore_choose), subtitle = stringResource(R.string.iptv_backup_choose_subtitle),
                    onClick = {
                        try { picker.launch(arrayOf("*/*")) }
                        catch (_: android.content.ActivityNotFoundException) { viewModel.pickerUnavailable() }
                    }, leadingIcon = Icons.Filled.FolderOpen, modifier = if (files.isNullOrEmpty()) Modifier.focusRequester(first) else Modifier)
                Message(state)
            }
            Buttons(false) { NuvioActionPill(close) { Text(stringResource(R.string.iptv_setup_cancel)) } }
        } else {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(stringResource(R.string.iptv_restore_file, chosen), color = NuvioTheme.colors.TextPrimary, style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                SourceField(stringResource(R.string.iptv_backup_passphrase), passphrase, { passphrase = it.take(SetupVault.MAX_PASSPHRASE) },
                    keyboardType = KeyboardType.Password, masked = true, last = true, modifier = Modifier.focusRequester(first))
                Message(state)
                Buttons(state.busy) {
                    NuvioActionPill({ viewModel.unlock(passphrase) }, enabled = !state.busy && passphrase.isNotEmpty()) { Text(stringResource(R.string.iptv_restore_open)) }
                    NuvioActionPill({ passphrase = ""; viewModel.clearChoice() }, enabled = !state.busy) { Text(stringResource(R.string.iptv_transfer_back)) }
                }
            }
        }
    }
}
