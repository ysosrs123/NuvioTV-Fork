@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
package com.nuvio.tv.ui.screens.iptv

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.iptv.SetupPhone
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.settings.SettingsActionRow
import com.nuvio.tv.ui.screens.settings.SettingsToggleRow
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance
import com.nuvio.tv.ui.v2.appearance.V2Atmosphere
import com.nuvio.tv.ui.v2.components.NuvioActionPill
import java.text.DateFormat
import java.util.Date

@Composable
fun IptvSetupScreen(onBack: () -> Unit, viewModel: IptvSetupViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val first = remember { FocusRequester() }
    var showPhones by remember { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.start()
                Lifecycle.Event.ON_STOP -> viewModel.stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.stop()
        }
    }
    val stopped = state.phase !in setOf(IptvSetupPhase.STARTING, IptvSetupPhase.RUNNING)
    LaunchedEffect(stopped, state.pending == null) {
        if (state.pending == null) { withFrameNanos { }; runCatching { first.requestFocus() } }
    }
    Box(Modifier.fillMaxSize().background(NuvioTheme.colors.Background)) {
        if (!LocalIptvAppearance.current.plainBackground) LocalV2Appearance.current?.let { V2Atmosphere(rich = false, background = it.settingsBackground) }
        Row(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Column(Modifier.width(340.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.iptv_remote_title), style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold, color = NuvioTheme.colors.TextPrimary)
                Text(stringResource(R.string.iptv_remote_description), style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextSecondary)
                Spacer(Modifier.height(18.dp))
                if (stopped) SettingsActionRow(title = stringResource(R.string.iptv_remote_start_again),
                    subtitle = stringResource(R.string.iptv_remote_start_again_subtitle), onClick = viewModel::restart,
                    leadingIcon = Icons.Filled.Refresh, modifier = Modifier.focusRequester(first))
                SettingsActionRow(title = stringResource(R.string.iptv_remote_done),
                    subtitle = stringResource(if (state.keep) R.string.iptv_phone_done_kept else R.string.iptv_remote_done_subtitle),
                    onClick = onBack, leadingIcon = Icons.Filled.Check, modifier = if (stopped) Modifier else Modifier.focusRequester(first))
                SettingsToggleRow(title = stringResource(R.string.iptv_phone_keep), subtitle = stringResource(R.string.iptv_phone_keep_subtitle),
                    checked = state.keep, onToggle = viewModel::toggleKeep)
                if (state.keep) SettingsActionRow(title = stringResource(R.string.iptv_phone_paired),
                    subtitle = if (state.phones.isEmpty()) stringResource(R.string.iptv_phone_paired_none)
                        else pluralStringResource(R.plurals.iptv_phone_paired_count, state.phones.size, state.phones.size),
                    onClick = { showPhones = true }, leadingIcon = Icons.Filled.Smartphone)
                Spacer(Modifier.weight(1f))
                Note(Icons.Filled.Lock, stringResource(R.string.iptv_remote_home_network))
                Note(null, stringResource(R.string.iptv_remote_confirm_note))
                Note(null, stringResource(if (state.keep) R.string.iptv_phone_keep_note else R.string.iptv_remote_idle_note))
                state.editingProfile?.let { Note(null, stringResource(R.string.iptv_remote_editing_profile, it)) }
                state.message?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(it), color = NuvioTheme.colors.TextPrimary, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight().iptvPanel().padding(32.dp), contentAlignment = Alignment.Center) {
                when (state.phase) {
                    IptvSetupPhase.RUNNING -> Pairing(state)
                    IptvSetupPhase.STARTING -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        LoadingIndicator(Modifier.size(40.dp))
                        Text(stringResource(R.string.iptv_remote_starting), color = NuvioTheme.colors.TextSecondary, style = MaterialTheme.typography.bodyLarge)
                    }
                    else -> Stopped(state.phase)
                }
            }
        }
    }
    state.pending?.let { pending -> PendingDialog(pending, onSave = viewModel::confirm, onReject = viewModel::reject) }
    if (showPhones && state.keep && state.pending == null) PhonesDialog(state.phones, onRemove = viewModel::removePhone,
        onRemoveAll = viewModel::removeAllPhones, onClose = { showPhones = false })
}

@Composable
fun IptvPhoneRequestPrompt() {
    val context = LocalContext.current
    val live by IptvSetupHost.live.collectAsState()
    val keep = remember { IptvSetupHost.keepOn(context) }
    if (!live && !keep) return
    val viewModel: IptvPhoneRequestViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (!state.visible) state.pending?.let { pending -> PendingDialog(pending, onSave = viewModel::confirm, onReject = viewModel::reject) }
}

@Composable
private fun PhonesDialog(phones: List<SetupPhone>, onRemove: (String) -> Unit, onRemoveAll: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val close = remember { FocusRequester() }
    val format = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    LaunchedEffect(phones.size) { withFrameNanos { }; runCatching { close.requestFocus() } }
    NuvioDialog(onDismiss = onClose, title = stringResource(R.string.iptv_phone_paired), subtitle = stringResource(R.string.iptv_phone_paired_subtitle), width = 640.dp) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            if (phones.isEmpty()) Text(stringResource(R.string.iptv_phone_paired_none), color = NuvioTheme.colors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
            phones.forEach { phone ->
                SettingsActionRow(title = IptvSetupHost.phoneName(context, phone),
                    subtitle = stringResource(R.string.iptv_phone_paired_on, format.format(Date(phone.pairedAt))),
                    value = stringResource(R.string.iptv_remove), onClick = { onRemove(phone.id) }, leadingIcon = Icons.Filled.Smartphone, trailingIcon = null)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NuvioActionPill(onClose, Modifier.focusRequester(close)) { Text(stringResource(R.string.action_close)) }
            if (phones.isNotEmpty()) NuvioActionPill(onRemoveAll) { Text(stringResource(R.string.iptv_phone_remove_all)) }
        }
    }
}

@Composable
private fun Pairing(state: IptvSetupState) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(40.dp)) {
        Box(Modifier.size(280.dp), contentAlignment = Alignment.Center) {
            val qr = state.qr
            if (qr != null) Image(bitmap = qr.asImageBitmap(), contentDescription = stringResource(R.string.iptv_remote_qr_description),
                modifier = Modifier.size(280.dp), contentScale = ContentScale.Fit)
            else LoadingIndicator(Modifier.size(32.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.iptv_remote_code), color = NuvioTheme.colors.TextSecondary, style = MaterialTheme.typography.labelLarge)
            Text(state.code?.chunked(3)?.joinToString(" ").orEmpty(), color = NuvioTheme.colors.TextPrimary,
                style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold, letterSpacing = 6.sp, maxLines = 1)
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.iptv_remote_address), color = NuvioTheme.colors.TextSecondary, style = MaterialTheme.typography.labelLarge)
            Text(state.address.orEmpty(), color = NuvioTheme.colors.TextPrimary, style = MaterialTheme.typography.bodyLarge, maxLines = 3,
                overflow = TextOverflow.Ellipsis)
            state.phoneAddress?.let { phone ->
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.iptv_phone_address), color = NuvioTheme.colors.TextSecondary, style = MaterialTheme.typography.labelLarge)
                Text(phone, color = NuvioTheme.colors.TextPrimary, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(if (state.devices > 0) NuvioTheme.colors.Secondary else NuvioTheme.colors.TextTertiary))
                Text(if (state.devices > 0) pluralStringResource(R.plurals.iptv_remote_connected, state.devices, state.devices)
                    else stringResource(R.string.iptv_remote_waiting), color = NuvioTheme.colors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun Stopped(phase: IptvSetupPhase) {
    val message = when (phase) {
        IptvSetupPhase.NO_NETWORK -> R.string.iptv_remote_no_network
        IptvSetupPhase.NOT_HOME_NETWORK -> R.string.iptv_remote_not_home_network
        IptvSetupPhase.PORTS_BUSY -> R.string.iptv_remote_ports_busy
        IptvSetupPhase.IDLE_STOPPED -> R.string.iptv_remote_idle_stopped
        else -> R.string.iptv_remote_paused
    }
    val icon = if (phase == IptvSetupPhase.NO_NETWORK || phase == IptvSetupPhase.NOT_HOME_NETWORK) Icons.Filled.WifiOff else Icons.Filled.Smartphone
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(icon, null, Modifier.size(48.dp), tint = NuvioTheme.colors.TextSecondary)
        Text(stringResource(message), color = NuvioTheme.colors.TextPrimary, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Note(icon: ImageVector?, text: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (icon != null) Icon(icon, null, Modifier.size(16.dp).padding(top = 2.dp), tint = NuvioTheme.colors.TextTertiary)
        Text(text, color = NuvioTheme.colors.TextTertiary, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PendingDialog(pending: IptvSetupPending, onSave: () -> Unit, onReject: () -> Unit) {
    val reject = remember { FocusRequester() }
    LaunchedEffect(pending.id) { withFrameNanos { }; runCatching { reject.requestFocus() } }
    val title = pending.title ?: if (pending.settings) stringResource(R.string.iptv_remote_confirm_settings_title)
        else pending.previousLabel?.let { stringResource(R.string.iptv_remote_confirm_edit_title, it) } ?: stringResource(R.string.iptv_remote_confirm_add_title)
    NuvioDialog(onDismiss = onReject, title = title, subtitle = stringResource(if (pending.allow) R.string.iptv_remote_confirm_profile_subtitle else R.string.iptv_remote_confirm_subtitle), width = 640.dp) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            pending.lines.forEach { line ->
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(line.label), color = NuvioTheme.colors.TextSecondary, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.width(if (pending.settings) 220.dp else 150.dp))
                    Text(line.valueRes?.let { stringResource(it) } ?: line.value, color = NuvioTheme.colors.TextPrimary,
                        style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NuvioActionPill(onReject, Modifier.focusRequester(reject), enabled = !pending.applying) { Text(stringResource(R.string.iptv_remote_reject)) }
            NuvioActionPill(onSave, enabled = !pending.applying) { Text(stringResource(if (pending.allow) R.string.iptv_remote_allow else R.string.iptv_remote_save)) }
            if (pending.applying) LoadingIndicator(Modifier.size(24.dp))
        }
    }
}
