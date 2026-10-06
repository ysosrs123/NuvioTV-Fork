@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
package com.nuvio.tv.ui.screens.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.iptv.IptvSourceKind
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.screens.settings.SettingsGroupCard

@Composable
fun IptvSourcesScreen(onBack: () -> Unit, onLive: () -> Unit = {}, viewModel: IptvSourcesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val first = remember { FocusRequester() }
    var initiallyFocused by remember(state.profileId, state.revision) { mutableStateOf(false) }
    LaunchedEffect(state.ready, state.busy, state.revision) {
        if (state.ready && !state.busy && !initiallyFocused) { first.requestFocus(); initiallyFocused = true }
    }
    Column(Modifier.fillMaxSize().background(NuvioTheme.colors.Background).padding(32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.iptv_sources_title), style = MaterialTheme.typography.headlineMedium, color = NuvioTheme.colors.TextPrimary)
        Text(stringResource(R.string.iptv_sources_description), style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onLive, enabled = state.sources.isNotEmpty() && !state.busy) { Text(stringResource(R.string.iptv_live_title)) }
            Button(onClick = onBack) { Text(stringResource(R.string.iptv_setup_back)) }
            Button(onClick = { viewModel.add(false) }, enabled = state.ready && !state.busy, modifier = Modifier.focusRequester(first)) { Text(stringResource(R.string.iptv_add_playlist)) }
            Button(onClick = { viewModel.add(false, IptvSourceKind.XTREAM) }, enabled = state.ready && !state.busy) { Text(stringResource(R.string.iptv_add_xtream)) }
            Button(onClick = { viewModel.add(true) }, enabled = state.ready && !state.busy) { Text(stringResource(R.string.iptv_add_guide)) }
        }
        if (state.busy) Text(stringResource(R.string.iptv_setup_working), color = NuvioTheme.colors.TextSecondary)
        state.message?.let { Text(stringResource(it), color = NuvioTheme.colors.TextSecondary) }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (state.ready && state.sources.isEmpty()) item { Text(stringResource(R.string.iptv_sources_empty), color = NuvioTheme.colors.TextSecondary) }
            items(state.sources, key = { "source-${it.ref.sourceId}" }) { source ->
                SettingsGroupCard(Modifier.fillMaxWidth()) {
                    Text(source.label, style = MaterialTheme.typography.titleMedium, color = NuvioTheme.colors.TextPrimary)
                    val status = state.refresh[IptvRefreshCoordinator.key(source.ref)]
                    RefreshLine(status, source.refreshedAtMillis, source.playbackEligible)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { viewModel.refresh(source) }, enabled = status?.running != true) { Text(stringResource(R.string.iptv_setup_refresh)) }
                        Button(onClick = { viewModel.edit(source) }, enabled = !state.busy) { Text(stringResource(R.string.iptv_setup_edit)) }
                        Button(onClick = { viewModel.select(source.ref) }, enabled = !state.busy) { Text(stringResource(if (state.selected == source.ref) R.string.iptv_guides_selected else R.string.iptv_choose_guides)) }
                    }
                }
            }
            item {
                val name = state.sources.firstOrNull { it.ref == state.selected }?.label
                Text(if (name == null) stringResource(R.string.iptv_guides_title) else stringResource(R.string.iptv_guides_for, name), style = MaterialTheme.typography.titleLarge, color = NuvioTheme.colors.TextPrimary)
            }
            items(state.feeds, key = { "feed-${it.ref.feedId}" }) { feed ->
                SettingsGroupCard(Modifier.fillMaxWidth()) {
                    Text(feed.label, style = MaterialTheme.typography.titleMedium, color = NuvioTheme.colors.TextPrimary)
                    val status = state.refresh[IptvRefreshCoordinator.key(feed.ref)]
                    RefreshLine(status, feed.refreshedAtMillis, feed.activeGeneration != null)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { viewModel.refresh(feed) }, enabled = status?.running != true) { Text(stringResource(R.string.iptv_setup_refresh)) }
                        Button(onClick = { viewModel.edit(feed) }, enabled = !state.busy) { Text(stringResource(R.string.iptv_setup_edit)) }
                        Button(onClick = { viewModel.link(feed) }, enabled = !state.busy && state.selected != null) { Text(stringResource(if (feed.ref.feedId in state.linked) R.string.iptv_guide_unlink else R.string.iptv_guide_link)) }
                    }
                }
            }
        }
    }
    state.form?.let { form -> key(state.profileId, state.revision, form) {
        var label by remember { mutableStateOf(form.label) }
        var endpoint by remember { mutableStateOf(form.endpoint) }
        var username by remember { mutableStateOf(form.username) }
        var password by remember { mutableStateOf(form.password) }
        val picker = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null && uri.scheme == "content") endpoint = uri.toString()
        }
        val xtream = !form.guide && form.kind == IptvSourceKind.XTREAM
        NuvioDialog(onDismiss = viewModel::dismiss, title = stringResource(if (form.guide) R.string.iptv_guide_form else if (xtream) R.string.iptv_xtream_form else R.string.iptv_playlist_form)) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SourceField(stringResource(R.string.iptv_setup_name), label, { label = it.take(240) })
                SourceField(stringResource(if (xtream) R.string.iptv_xtream_server else R.string.iptv_setup_url), endpoint, { endpoint = it.take(16384) },
                    keyboardType = KeyboardType.Uri, last = !xtream)
                if (form.guide) {
                    Button(onClick = {
                        try { picker.launch(arrayOf("*/*")) }
                        catch (_: android.content.ActivityNotFoundException) { viewModel.documentUnavailable() }
                    }, enabled = !state.busy) { Text(stringResource(R.string.iptv_guide_choose_file)) }
                    Text(stringResource(if (endpoint.startsWith("content:")) R.string.iptv_guide_file_selected else R.string.iptv_guide_file_help), color = NuvioTheme.colors.TextSecondary)
                }
                if (xtream) {
                    SourceField(stringResource(R.string.iptv_xtream_username), username, { username = it.take(4096) }, keyboardType = KeyboardType.Ascii)
                    var showPassword by remember { mutableStateOf(false) }
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SourceField(stringResource(R.string.iptv_xtream_password), password, { password = it.take(4096) },
                            keyboardType = KeyboardType.Password, masked = !showPassword, last = true, modifier = Modifier.weight(1f))
                        Button(onClick = { showPassword = !showPassword }) {
                            Text(stringResource(if (showPassword) R.string.iptv_password_hide else R.string.iptv_password_show))
                        }
                    }
                }
            }
            state.message?.let { Text(stringResource(it), color = NuvioTheme.colors.TextSecondary) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { viewModel.save(form, label, endpoint, username, password) }, enabled = !state.busy && label.isNotBlank() && endpoint.isNotBlank() && (!xtream || (username.isNotEmpty() && password.isNotEmpty()))) { Text(stringResource(R.string.iptv_setup_save)) }
                Button(onClick = viewModel::dismiss, enabled = !state.busy) { Text(stringResource(R.string.iptv_setup_cancel)) }
            }
        }
    } }
}

@Composable
private fun RefreshLine(status: IptvRefreshStatus?, refreshedAt: Long?, loaded: Boolean) {
    val text = when (status?.phase) {
        IptvRefreshPhase.QUEUED -> stringResource(R.string.iptv_refresh_queued)
        IptvRefreshPhase.DOWNLOADING -> stringResource(R.string.iptv_refresh_downloading)
        IptvRefreshPhase.SAVING -> stringResource(R.string.iptv_refresh_saving)
        IptvRefreshPhase.GUIDE -> stringResource(R.string.iptv_refresh_guide)
        IptvRefreshPhase.FAILED -> status.message?.let { stringResource(it) } ?: stringResource(R.string.iptv_setup_failed)
        else -> when {
            refreshedAt != null -> stringResource(R.string.iptv_refresh_updated, android.text.format.DateUtils.getRelativeTimeSpanString(
                refreshedAt, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS).toString())
            loaded -> stringResource(R.string.iptv_setup_imported)
            else -> stringResource(R.string.iptv_setup_refresh_needed)
        }
    }
    Text(text, color = if (status?.phase == IptvRefreshPhase.FAILED) NuvioTheme.colors.Error else NuvioTheme.colors.TextSecondary)
}

@Composable
private fun SourceField(label: String, value: String, onChange: (String) -> Unit, keyboardType: KeyboardType = KeyboardType.Text,
    masked: Boolean = false, last: Boolean = false, modifier: Modifier = Modifier) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = NuvioTheme.colors.TextSecondary)
        BasicTextField(value, onChange, singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(color = NuvioTheme.colors.TextPrimary),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType,
                autoCorrectEnabled = keyboardType == KeyboardType.Text, imeAction = if (last) ImeAction.Done else ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Next) },
                onDone = { keyboard?.hide(); focus.moveFocus(FocusDirection.Next) }),
            visualTransformation = if (masked) PasswordVisualTransformation() else VisualTransformation.None,
            cursorBrush = SolidColor(NuvioTheme.colors.TextPrimary),
            modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                    Key.DirectionDown -> focus.moveFocus(FocusDirection.Next)
                    Key.DirectionUp -> focus.moveFocus(FocusDirection.Previous)
                    else -> false
                }
            }.border(1.dp, NuvioTheme.colors.TextSecondary).padding(12.dp))
    }
}
