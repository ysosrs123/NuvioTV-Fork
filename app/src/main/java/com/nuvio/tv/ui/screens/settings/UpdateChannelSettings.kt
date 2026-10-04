package com.nuvio.tv.ui.screens.settings

import androidx.activity.ComponentActivity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.R
import com.nuvio.tv.updater.UpdateViewModel

@Composable
internal fun UpdateChannelSettings(initialFocusRequester: FocusRequester?, modifier: Modifier = Modifier) {
    val viewModel: UpdateViewModel = hiltViewModel(LocalContext.current as ComponentActivity)
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SettingsToggleRow(
        title = stringResource(R.string.about_update_banner_title),
        subtitle = stringResource(R.string.about_fork_updates_subtitle),
        checked = state.updateBannerEnabled,
        onToggle = { viewModel.setUpdateBannerEnabled(!state.updateBannerEnabled) },
        modifier = modifier.then(if (initialFocusRequester != null)
            Modifier.focusRequester(initialFocusRequester) else Modifier)
    )
    SettingsActionRow(
        title = stringResource(R.string.about_check_updates),
        subtitle = stringResource(R.string.about_check_updates_subtitle),
        trailingIcon = Icons.AutoMirrored.Filled.OpenInNew,
        onClick = { viewModel.checkForUpdates(force = true, showNoUpdateFeedback = true) }
    )
}
