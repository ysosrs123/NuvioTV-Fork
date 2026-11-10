package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.R
import com.nuvio.tv.ui.screens.backup.BackupFlow
import com.nuvio.tv.ui.screens.backup.BackupPhoneOverlay
import com.nuvio.tv.ui.screens.backup.BackupViewModel
import com.nuvio.tv.ui.screens.backup.ReturnFocusWhenClosed
import com.nuvio.tv.ui.screens.backup.dateTime

@Composable
internal fun BackupSettingsContent(
    initialFocusRequester: FocusRequester?,
    viewModel: BackupViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val nowFocus = remember { FocusRequester() }
    val savePhoneFocus = remember { FocusRequester() }
    val restoreBoxFocus = remember { FocusRequester() }
    val restorePhoneFocus = remember { FocusRequester() }
    var opener by remember { mutableStateOf<FocusRequester?>(null) }
    ReturnFocusWhenClosed(state, opener)
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SettingsDetailHeader(
                title = stringResource(R.string.backup_title),
                subtitle = stringResource(R.string.backup_subtitle)
            )
            SettingsGroupCard(title = stringResource(R.string.backup_group_back_up)) {
                SettingsActionRow(
                    title = stringResource(R.string.backup_now),
                    subtitle = stringResource(R.string.backup_now_sub),
                    value = state.lastBackupMs.takeIf { it > 0L }?.let { stringResource(R.string.backup_last, dateTime(context, it)) },
                    onClick = { opener = nowFocus; viewModel.backUpNow() },
                    modifier = Modifier.focusRequester(nowFocus)
                        .then(if (initialFocusRequester != null) Modifier.focusRequester(initialFocusRequester) else Modifier)
                )
                SettingsToggleRow(
                    title = stringResource(R.string.backup_include_secrets),
                    subtitle = stringResource(R.string.backup_include_secrets_sub),
                    checked = state.includeSecrets,
                    onToggle = { viewModel.setIncludeSecrets(!state.includeSecrets) }
                )
                SettingsActionRow(
                    title = stringResource(R.string.backup_save_phone),
                    subtitle = stringResource(R.string.backup_save_phone_sub),
                    onClick = { opener = savePhoneFocus; viewModel.openPhone(saving = true) },
                    modifier = Modifier.focusRequester(savePhoneFocus)
                )
            }
            SettingsGroupCard(title = stringResource(R.string.backup_group_restore)) {
                SettingsActionRow(
                    title = stringResource(R.string.backup_restore_box),
                    subtitle = null,
                    value = state.backups.firstOrNull()?.let { stringResource(R.string.backup_newest, dateTime(context, it.modifiedMs)) },
                    onClick = { opener = restoreBoxFocus; viewModel.openChoose() },
                    modifier = Modifier.focusRequester(restoreBoxFocus)
                )
                SettingsActionRow(
                    title = stringResource(R.string.backup_restore_phone),
                    subtitle = stringResource(R.string.backup_restore_phone_sub),
                    onClick = { opener = restorePhoneFocus; viewModel.openPhone(saving = false) },
                    modifier = Modifier.focusRequester(restorePhoneFocus)
                )
            }
        }
        BackupPhoneOverlay(state, viewModel, Modifier.clip(settingsGroupShape()))
    }
    BackupFlow(state, viewModel)
}
