@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
package com.nuvio.tv.ui.screens.iptv

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.iptv.RecordingShareProtocol
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.settings.SettingsToggleRow
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.v2.components.NuvioActionPill

@Composable
internal fun IptvShareDialog(form: IptvShareForm, status: IptvShareStatus, viewModel: IptvSettingsViewModel) {
    val context = LocalContext.current
    var protocol by remember { mutableStateOf(form.protocol) }
    var server by remember { mutableStateOf(form.server) }
    var share by remember { mutableStateOf(form.share) }
    var folder by remember { mutableStateOf(form.folder) }
    var username by remember { mutableStateOf(form.username) }
    var password by remember { mutableStateOf(form.password) }
    var domain by remember { mutableStateOf(form.domain) }
    var guest by remember { mutableStateOf(form.guest) }
    var secure by remember { mutableStateOf(form.secure) }
    var showPassword by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
    fun current() = IptvShareForm(server, share, folder, username, password, domain, guest, protocol, secure)
    val smb = protocol == RecordingShareProtocol.SMB
    val credentials = !smb || !guest
    NuvioDialog(onDismiss = viewModel::closeShare, title = stringResource(R.string.iptv_network_location),
        subtitle = stringResource(R.string.iptv_network_subtitle), width = 640.dp) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.iptv_network_protocol), color = NuvioTheme.colors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RecordingShareProtocol.entries.forEach { option ->
                    NuvioActionPill({ protocol = option }, if (option == form.protocol) Modifier.focusRequester(first) else Modifier) {
                        if (option == protocol) {
                            Icon(Icons.Filled.Check, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(stringResource(protocolLabel(option)))
                    }
                }
            }
            when (protocol) {
                RecordingShareProtocol.SMB -> {
                    SourceField(stringResource(R.string.iptv_share_server), server, { server = it.take(300) }, hint = stringResource(R.string.iptv_share_server_hint),
                        keyboardType = KeyboardType.Uri)
                    SourceField(stringResource(R.string.iptv_share_name), share, { share = it.take(80) }, hint = stringResource(R.string.iptv_share_name_hint),
                        keyboardType = KeyboardType.Uri)
                }
                RecordingShareProtocol.WEBDAV -> SourceField(stringResource(R.string.iptv_network_webdav_address), server, { server = it.take(500) },
                    hint = stringResource(R.string.iptv_network_webdav_address_hint), keyboardType = KeyboardType.Uri)
                RecordingShareProtocol.FTP -> SourceField(stringResource(R.string.iptv_share_server), server, { server = it.take(300) },
                    hint = stringResource(R.string.iptv_network_ftp_server_hint), keyboardType = KeyboardType.Uri)
            }
            SourceField(stringResource(R.string.iptv_share_folder), folder, { folder = it.take(200) }, hint = stringResource(when (protocol) {
                RecordingShareProtocol.SMB -> R.string.iptv_share_folder_hint
                RecordingShareProtocol.WEBDAV -> R.string.iptv_network_webdav_folder_hint
                RecordingShareProtocol.FTP -> R.string.iptv_network_ftp_folder_hint
            }), keyboardType = KeyboardType.Uri, last = !credentials)
            if (smb) SettingsToggleRow(title = stringResource(R.string.iptv_share_guest), subtitle = stringResource(R.string.iptv_share_guest_subtitle),
                checked = guest, onToggle = { guest = !guest })
            if (protocol == RecordingShareProtocol.FTP) SettingsToggleRow(title = stringResource(R.string.iptv_network_ftp_secure),
                subtitle = stringResource(R.string.iptv_network_ftp_secure_subtitle), checked = secure, onToggle = { secure = !secure })
            if (credentials) {
                SourceField(stringResource(R.string.iptv_share_username), username, { username = it.take(256) },
                    hint = if (smb) null else stringResource(R.string.iptv_network_username_hint), keyboardType = KeyboardType.Ascii)
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SourceField(stringResource(R.string.iptv_share_password), password, { password = it.take(256) },
                        keyboardType = KeyboardType.Password, masked = !showPassword, modifier = Modifier.weight(1f), last = !smb)
                    NuvioActionPill({ showPassword = !showPassword }) {
                        Icon(if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(if (showPassword) R.string.iptv_password_hide else R.string.iptv_password_show))
                    }
                }
                if (smb) SourceField(stringResource(R.string.iptv_share_domain), domain, { domain = it.take(256) },
                    hint = stringResource(R.string.iptv_share_domain_hint), keyboardType = KeyboardType.Ascii, last = true)
            }
            if (current().plain) Text(stringResource(R.string.iptv_network_plain_warning), color = NuvioTheme.colors.Warning,
                style = MaterialTheme.typography.bodyMedium)
            when {
                status.busy -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LoadingIndicator(Modifier.size(24.dp))
                    Text(stringResource(R.string.iptv_share_testing), color = NuvioTheme.colors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                }
                status.message != null -> Text(
                    if (status.ok && status.freeBytes != null && status.freeBytes != Long.MAX_VALUE)
                        stringResource(R.string.iptv_share_ok_free, Formatter.formatShortFileSize(context, status.freeBytes))
                    else stringResource(status.message),
                    color = if (status.ok) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.Error, style = MaterialTheme.typography.bodyMedium)
            }
            val fingerprint = status.fingerprint
            if (!status.busy && fingerprint != null) {
                Text(stringResource(R.string.iptv_network_certificate_fingerprint, fingerprint), color = NuvioTheme.colors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall)
                NuvioActionPill({ viewModel.trustCertificate(current()) }) { Text(stringResource(R.string.iptv_network_trust)) }
            }
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NuvioActionPill({ viewModel.testShare(current()) }, enabled = !status.busy && server.isNotBlank()) { Text(stringResource(R.string.iptv_share_test)) }
                NuvioActionPill({ viewModel.saveShare(current()) }, enabled = server.isNotBlank()) { Text(stringResource(R.string.iptv_setup_save)) }
                NuvioActionPill(viewModel::closeShare) { Text(stringResource(R.string.iptv_setup_cancel)) }
            }
        }
    }
}

private fun protocolLabel(protocol: RecordingShareProtocol): Int = when (protocol) {
    RecordingShareProtocol.SMB -> R.string.iptv_network_smb
    RecordingShareProtocol.WEBDAV -> R.string.iptv_network_webdav
    RecordingShareProtocol.FTP -> R.string.iptv_network_ftp
}
