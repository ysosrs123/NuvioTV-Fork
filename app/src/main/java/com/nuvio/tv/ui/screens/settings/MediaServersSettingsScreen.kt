@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Dns
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.data.mediaserver.ServerConnection
import com.nuvio.tv.data.mediaserver.ServerFailure
import com.nuvio.tv.data.mediaserver.serverLogoRes
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
internal fun MediaServersSettingsContent(
    initialFocusRequester: FocusRequester?,
    onOpenServer: (String) -> Unit,
    viewModel: MediaServersViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var signIn by remember { mutableStateOf<ServerSignInRequest?>(null) }
    var confirmRemoveFromAccount by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val connections = uiState.connections
    val focusModifier = initialFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SettingsDetailHeader(
            title = stringResource(R.string.settings_media_servers),
            subtitle = stringResource(R.string.settings_media_servers_subtitle)
        )
        SettingsGroupCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            val listState = rememberLazyListState()
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = NuvioTheme.spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(if (isV2Settings()) 4.dp else 10.dp)
                ) {
                    item(key = "servers_label") { ServerSectionLabel(stringResource(R.string.servers_section_connected)) }
                    if (connections.isEmpty()) {
                        item(key = "servers_empty") {
                            Text(
                                text = stringResource(R.string.servers_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = NuvioTheme.colors.TextSecondary,
                                modifier = Modifier.padding(horizontal = NuvioTheme.spacing.sm)
                            )
                        }
                    }
                    items(connections, key = { "server_${it.id}" }) { connection ->
                        SettingsActionRow(
                            title = connection.name,
                            subtitle = connection.statusText(
                                providerName = viewModel.provider(connection)?.displayName ?: connection.providerId,
                                failure = uiState.failures[connection.id]
                            ),
                            onClick = { onOpenServer(connection.id) },
                            leadingIcon = Icons.Default.Dns.takeIf { serverLogoRes(connection.providerId) == null },
                            leadingRawIconRes = serverLogoRes(connection.providerId),
                            modifier = if (connection == connections.first()) focusModifier else Modifier
                        )
                    }
                    item(key = "servers_add_label") { ServerSectionLabel(stringResource(R.string.servers_section_add)) }
                    items(viewModel.providers, key = { "provider_${it.id}" }) { provider ->
                        SettingsActionRow(
                            title = provider.displayName,
                            subtitle = stringResource(R.string.servers_add_description, provider.displayName),
                            onClick = { signIn = ServerSignInRequest(provider) },
                            leadingIcon = Icons.Default.Dns.takeIf { serverLogoRes(provider.id) == null },
                            leadingRawIconRes = serverLogoRes(provider.id),
                            modifier = if (connections.isEmpty() && provider == viewModel.providers.first()) focusModifier else Modifier
                        )
                    }
                    item(key = "servers_account_label") { ServerSectionLabel(stringResource(R.string.servers_section_account)) }
                    item(key = "servers_account_sync") {
                        SettingsToggleRow(
                            title = stringResource(R.string.servers_account_sync),
                            subtitle = stringResource(R.string.servers_account_sync_description),
                            checked = uiState.syncEnabled,
                            onToggle = { viewModel.setSyncEnabled(!uiState.syncEnabled) }
                        )
                    }
                    item(key = "servers_account_remove") {
                        SettingsActionRow(
                            title = stringResource(R.string.servers_account_remove),
                            subtitle = stringResource(R.string.servers_account_remove_description),
                            leadingIcon = Icons.Default.CloudOff,
                            onClick = { confirmRemoveFromAccount = true }
                        )
                    }
                }
                SettingsVerticalScrollIndicators(state = listState)
            }
        }
    }

    if (confirmRemoveFromAccount) {
        NuvioDialog(
            onDismiss = { confirmRemoveFromAccount = false },
            title = stringResource(R.string.servers_account_remove_confirm_title),
            subtitle = stringResource(R.string.servers_account_remove_description)
        ) {
            SettingsDialogActionRow {
                SettingsDialogActionButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = { confirmRemoveFromAccount = false }
                )
                SettingsDialogActionButton(
                    text = stringResource(R.string.servers_account_remove_confirm),
                    onClick = {
                        confirmRemoveFromAccount = false
                        viewModel.removeFromAccount { removed ->
                            val message = if (removed) R.string.servers_account_removed else R.string.servers_account_remove_failed
                            Toast.makeText(context, context.getString(message), Toast.LENGTH_SHORT).show()
                        }
                    },
                    primary = true
                )
            }
        }
    }

    signIn?.let { request ->
        ServerSignInDialog(
            request = request,
            viewModel = viewModel,
            onConnected = { connection ->
                signIn = null
                onOpenServer(connection.id)
            },
            onDismiss = { signIn = null }
        )
    }
}

@Composable
internal fun ServerSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        letterSpacing = 1.4.sp,
        color = NuvioTheme.colors.TextTertiary,
        modifier = Modifier.padding(start = 14.dp, top = NuvioTheme.spacing.sm)
    )
}

@Composable
internal fun ServerConnection.statusText(providerName: String, failure: ServerFailure?): String {
    val identity = stringResource(R.string.servers_signed_in_as, providerName, userName)
    return listOfNotNull(identity, statusLabel(failure)).joinToString(" · ")
}

@Composable
internal fun ServerConnection.statusLabel(failure: ServerFailure?): String? = when {
    !enabled -> stringResource(R.string.servers_status_disabled)
    failure == ServerFailure.AUTH_REQUIRED -> stringResource(R.string.servers_status_auth)
    failure == ServerFailure.UNREACHABLE -> stringResource(R.string.servers_status_unreachable)
    else -> null
}
