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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.network.displayFingerprint
import com.nuvio.tv.data.mediaserver.ServerCapability
import com.nuvio.tv.data.mediaserver.messageRes
import com.nuvio.tv.data.mediaserver.plex.PlexProvider
import com.nuvio.tv.data.mediaserver.silo.SiloNativeProvider
import com.nuvio.tv.data.mediaserver.silo.SiloProvider
import com.nuvio.tv.data.mediaserver.supports
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
internal fun MediaServerSettingsContent(
    connectionId: String,
    initialFocusRequester: FocusRequester?,
    onOpenServer: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: MediaServersViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val connection = uiState.connections.firstOrNull { it.id == connectionId }
    if (connection == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val context = LocalContext.current
    val provider = viewModel.provider(connection)
    val providerName = provider?.displayName ?: connection.providerId
    var signIn by remember { mutableStateOf<ServerSignInRequest?>(null) }
    var siloSignIn by remember { mutableStateOf<SiloSignInRequest?>(null) }
    val siloNative = connection.providerId == SiloNativeProvider.ID
    val plex = connection.providerId == PlexProvider.ID
    var plexSignIn by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var editAlternate by remember { mutableStateOf(false) }
    var editName by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SettingsDetailHeader(
            title = connection.shownName,
            subtitle = "${connection.statusText(connection.kindLabel(viewModel), uiState.failures[connection.id], connection.userLabel(viewModel))} · ${connection.address}"
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
                    item(key = "server_display_name") {
                        SettingsActionRow(
                            title = stringResource(R.string.servers_display_name),
                            subtitle = if (connection.isRenamed) {
                                stringResource(R.string.servers_display_name_server_name, connection.name)
                            } else {
                                stringResource(R.string.servers_display_name_description)
                            },
                            value = connection.shownName,
                            leadingIcon = Icons.Default.Edit,
                            onClick = { editName = true },
                            modifier = initialFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier
                        )
                    }
                    if (siloNative) {
                        item(key = "server_silo_connected_with") {
                            SettingsActionRow(
                                title = stringResource(R.string.servers_silo_connected_with),
                                subtitle = stringResource(R.string.silo_method_native),
                                value = connection.userName,
                                leadingIcon = Icons.Default.VpnKey,
                                trailingIcon = null,
                                onClick = {}
                            )
                        }
                        item(key = "server_silo_profile") {
                            SettingsActionRow(
                                title = stringResource(R.string.servers_silo_profile),
                                subtitle = stringResource(R.string.servers_silo_profile_description),
                                value = viewModel.siloProfileName(connection),
                                leadingIcon = Icons.Default.AccountCircle,
                                enabled = connection.enabled,
                                onClick = { viewModel.openSiloProfiles(connection.id) }
                            )
                        }
                    }
                    if (siloNative) {
                        item(key = "server_silo_home_rows") {
                            SettingsToggleRow(
                                title = stringResource(R.string.servers_silo_home_rows),
                                subtitle = stringResource(R.string.servers_silo_home_rows_description),
                                checked = connection.showHomeRows,
                                enabled = connection.enabled,
                                onToggle = { viewModel.setShowHomeRows(connection.id, !connection.showHomeRows) }
                            )
                        }
                    }
                    if (plex) {
                        item(key = "server_plex_connected_with") {
                            SettingsActionRow(
                                title = stringResource(R.string.servers_silo_connected_with),
                                subtitle = stringResource(R.string.plex_connected_with_value),
                                value = connection.userName,
                                leadingIcon = Icons.Default.VpnKey,
                                trailingIcon = null,
                                onClick = {}
                            )
                        }
                        item(key = "server_plex_user") {
                            SettingsActionRow(
                                title = stringResource(R.string.plex_home_user),
                                subtitle = stringResource(R.string.plex_home_user_description),
                                value = viewModel.plexUserName(connection),
                                leadingIcon = Icons.Default.AccountCircle,
                                enabled = connection.enabled,
                                onClick = { viewModel.openPlexUsers(connection.id) }
                            )
                        }
                        item(key = "server_plex_home_rows") {
                            SettingsToggleRow(
                                title = stringResource(R.string.plex_home_rows),
                                subtitle = stringResource(R.string.plex_home_rows_description),
                                checked = connection.showHomeRows,
                                enabled = connection.enabled,
                                onToggle = { viewModel.setShowHomeRows(connection.id, !connection.showHomeRows) }
                            )
                        }
                    }
                    if (connection.providerId == SiloProvider.ID) {
                        item(key = "server_silo_switch") {
                            SettingsActionRow(
                                title = stringResource(R.string.servers_silo_switch),
                                subtitle = stringResource(R.string.servers_silo_switch_description),
                                leadingIcon = Icons.Default.SwapHoriz,
                                onClick = {
                                    siloSignIn = SiloSignInRequest(siloWebsiteGuess(connection.address), replacing = connection.id)
                                }
                            )
                        }
                    }
                    item(key = "server_enabled") {
                        SettingsToggleRow(
                            title = stringResource(R.string.servers_enabled),
                            subtitle = stringResource(R.string.servers_enabled_description),
                            checked = connection.enabled,
                            onToggle = { viewModel.setEnabled(connection.id, !connection.enabled) }
                        )
                    }
                    if (provider?.supports(ServerCapability.EXTERNAL_ID_LOOKUP) == true) {
                        item(key = "server_catalog_metadata") {
                            SettingsToggleRow(
                                title = stringResource(R.string.servers_catalog_metadata),
                                subtitle = stringResource(R.string.servers_catalog_metadata_description, providerName),
                                checked = connection.useCatalogMetadata,
                                enabled = connection.enabled,
                                onToggle = { viewModel.setCatalogMetadata(connection.id, !connection.useCatalogMetadata) }
                            )
                        }
                    }
                    if (provider?.supports(ServerCapability.USER_STATE_READ) == true) {
                        item(key = "server_import_continue_watching") {
                            SettingsToggleRow(
                                title = stringResource(R.string.servers_import_continue_watching),
                                subtitle = stringResource(R.string.servers_import_continue_watching_description, providerName),
                                checked = connection.importContinueWatching,
                                enabled = connection.enabled,
                                onToggle = { viewModel.setImportContinueWatching(connection.id, !connection.importContinueWatching) }
                            )
                        }
                    }
                    item(key = "server_libraries_label") {
                        Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)) {
                            ServerSectionLabel(stringResource(R.string.servers_libraries))
                            Text(
                                text = stringResource(
                                    if (connection.libraries.isEmpty()) R.string.servers_no_libraries else R.string.servers_libraries_home_description
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = NuvioTheme.colors.TextSecondary,
                                modifier = Modifier.padding(horizontal = NuvioTheme.spacing.sm)
                            )
                        }
                    }
                    items(connection.libraries, key = { "library_${it.id}" }) { library ->
                        SettingsToggleRow(
                            title = library.name,
                            subtitle = null,
                            checked = library.selected,
                            enabled = connection.enabled,
                            onToggle = { viewModel.setLibrarySelected(connection.id, library.id, !library.selected) }
                        )
                    }
                    item(key = "server_refresh") {
                        SettingsActionRow(
                            title = stringResource(R.string.servers_refresh_libraries),
                            subtitle = null,
                            value = if (refreshing == connection.id) "…" else null,
                            enabled = connection.enabled && refreshing == null,
                            leadingIcon = Icons.Default.Sync,
                            onClick = {
                                viewModel.refreshLibraries(connection.id) { failure ->
                                    Toast.makeText(context, context.getString(failure.messageRes()), Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }
                    item(key = "server_second_address") {
                        val alternate = connection.alternateAddress
                        SettingsActionRow(
                            title = stringResource(R.string.servers_second_address),
                            subtitle = alternate ?: stringResource(R.string.servers_second_address_description),
                            value = when {
                                alternate == null -> stringResource(R.string.servers_second_address_not_set)
                                viewModel.activeAddress(connection) == alternate -> stringResource(R.string.servers_second_address_in_use)
                                else -> null
                            },
                            enabled = provider != null,
                            leadingIcon = Icons.Default.Language,
                            onClick = { editAlternate = true }
                        )
                    }
                    connection.tlsPins.values.firstOrNull()?.let { fingerprint ->
                        item(key = "server_certificate") {
                            SettingsActionRow(
                                title = stringResource(R.string.servers_trusted_certificate),
                                subtitle = displayFingerprint(fingerprint).take(CERTIFICATE_PREVIEW_CHARS) + "…",
                                value = stringResource(R.string.servers_trusted_certificate_forget),
                                leadingIcon = Icons.Default.VerifiedUser,
                                onClick = { viewModel.forgetCertificates(connection.id) }
                            )
                        }
                    }
                    if (provider != null) {
                        item(key = "server_sign_in") {
                            SettingsActionRow(
                                title = stringResource(R.string.servers_sign_in_again),
                                subtitle = null,
                                leadingIcon = Icons.Default.Lock,
                                onClick = {
                                    if (siloNative) {
                                        siloSignIn = SiloSignInRequest(connection.address)
                                    } else if (plex) {
                                        plexSignIn = true
                                    } else {
                                        signIn = ServerSignInRequest(provider, address = connection.address, username = connection.userName)
                                    }
                                }
                            )
                        }
                    }
                    item(key = "server_remove") {
                        SettingsActionRow(
                            title = stringResource(R.string.servers_remove),
                            subtitle = null,
                            leadingIcon = Icons.Default.Delete,
                            onClick = { confirmRemove = true }
                        )
                    }
                }
                SettingsVerticalScrollIndicators(state = listState)
            }
        }
    }

    signIn?.let { request ->
        ServerSignInDialog(
            request = request,
            viewModel = viewModel,
            onConnected = { connected ->
                signIn = null
                if (connected.id != connection.id) onOpenServer(connected.id)
            },
            onDismiss = { signIn = null }
        )
    }

    siloSignIn?.let { request ->
        SiloSetupDialog(
            viewModel = viewModel,
            initialAddress = request.address,
            replacing = request.replacing,
            title = stringResource(if (request.replacing != null) R.string.servers_silo_switch else R.string.servers_sign_in_again),
            onConnected = { connected ->
                siloSignIn = null
                if (connected.id != connection.id) onOpenServer(connected.id)
            },
            onDismiss = { siloSignIn = null }
        )
    }

    if (siloNative) SiloProfileDialog(viewModel, current = viewModel.siloProfileId(connection))

    if (plex) PlexUserDialog(viewModel, current = viewModel.plexUserId(connection))

    if (plexSignIn) {
        PlexSignInDialog(
            viewModel = viewModel,
            onConnected = { connected ->
                plexSignIn = false
                if (connected.id != connection.id) onOpenServer(connected.id)
            },
            onDismiss = { plexSignIn = false },
            replacing = connection.id
        )
    }

    if (editAlternate) {
        AlternateAddressDialog(
            current = connection.alternateAddress.orEmpty(),
            onSave = { address, onResult -> viewModel.setAlternateAddress(connection.id, address, onResult) },
            onDismiss = { editAlternate = false }
        )
    }

    if (editName) {
        ServerDisplayNameDialog(
            connection = connection,
            onSave = { name -> viewModel.setDisplayName(connection.id, name) },
            onDismiss = { editName = false }
        )
    }

    if (confirmRemove) {
        NuvioDialog(
            onDismiss = { confirmRemove = false },
            title = stringResource(R.string.servers_remove_confirm_title),
            subtitle = stringResource(R.string.servers_remove_confirm_message, connection.shownName)
        ) {
            SettingsDialogActionRow {
                SettingsDialogActionButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = { confirmRemove = false }
                )
                SettingsDialogActionButton(
                    text = stringResource(R.string.servers_remove),
                    onClick = {
                        confirmRemove = false
                        viewModel.remove(connection.id)
                    },
                    primary = true
                )
            }
        }
    }
}

private data class SiloSignInRequest(val address: String, val replacing: String? = null)

private const val CERTIFICATE_PREVIEW_CHARS = 23
