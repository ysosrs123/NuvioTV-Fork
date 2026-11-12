@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.mediaserver.ServerConnection
import com.nuvio.tv.data.mediaserver.ServerFailure
import com.nuvio.tv.data.mediaserver.ServerSignInIssue
import com.nuvio.tv.data.mediaserver.plex.PlexAccountClient
import com.nuvio.tv.data.mediaserver.plex.PlexHomeUser
import com.nuvio.tv.data.mediaserver.plex.PlexReach
import com.nuvio.tv.data.mediaserver.plex.PlexServer
import com.nuvio.tv.data.mediaserver.serverLogoRes
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.theme.NuvioTheme

/** Adding Plex: a code at plex.tv/link, then a server, then a Plex Home user (each step only when there is a choice). */
@Composable
internal fun PlexSignInDialog(
    viewModel: MediaServersViewModel,
    onConnected: (ServerConnection) -> Unit,
    onDismiss: () -> Unit,
    replacing: String? = null
) {
    val flow = viewModel.plex
    val state by flow.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { flow.start(replacing, onConnected) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> flow.pauseCode()
                Lifecycle.Event.ON_START -> flow.resumeCode()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val dismiss = {
        flow.close()
        onDismiss()
    }
    val busy = state.busy || state.step == PlexSignInStep.SIGNING_IN
    val title = when (state.step) {
        PlexSignInStep.SERVERS -> stringResource(R.string.plex_servers_title)
        PlexSignInStep.USERS -> stringResource(R.string.silo_profiles_title)
        PlexSignInStep.PIN -> stringResource(R.string.silo_pin_title, state.pinFor?.name.orEmpty())
        else -> stringResource(R.string.servers_sign_in_title, "Plex")
    }
    val subtitle = when (state.step) {
        PlexSignInStep.CODE -> stringResource(R.string.plex_sign_in_subtitle)
        PlexSignInStep.SERVERS -> state.account?.let { stringResource(R.string.plex_servers_subtitle, it) }
        PlexSignInStep.USERS -> stringResource(R.string.plex_users_subtitle, state.server?.name.orEmpty())
        else -> state.server?.name
    }

    NuvioDialog(
        onDismiss = { if (!busy) dismiss() },
        title = title,
        subtitle = subtitle,
        width = 700.dp,
        backgroundContent = { SiloDialogBackground() }
    ) {
        when (state.step) {
            PlexSignInStep.CODE -> PlexCodePanel(state, onNewCode = flow::newCode, onCancel = dismiss)
            PlexSignInStep.SERVERS -> PlexServersPanel(state, onPick = flow::pickServer, onCancel = dismiss)
            PlexSignInStep.USERS -> PlexUsersPanel(
                users = state.users,
                enabled = !busy,
                onPick = flow::pickUser,
                onBack = flow::backToServers,
                current = null
            )
            PlexSignInStep.PIN -> SiloPinPanel(
                wrong = state.pinWrong,
                busy = busy,
                error = state.error,
                canGoBack = true,
                onSubmit = flow::submitPin,
                onBack = flow::backToUsers,
                onCancel = dismiss,
                otherText = stringResource(if (state.users.size > 1) R.string.plex_other_user else R.string.action_back)
            )
            PlexSignInStep.SIGNING_IN -> SiloLine(stringResource(R.string.silo_signing_in), centred = true)
            PlexSignInStep.FAILED -> {
                PlexError(state.error, state.issue)
                SettingsDialogActionRow {
                    SettingsDialogActionButton(text = stringResource(R.string.action_cancel), onClick = dismiss)
                    SettingsDialogActionButton(
                        text = stringResource(R.string.servers_quick_connect_new_code),
                        onClick = flow::newCode,
                        primary = true
                    )
                }
            }
        }
    }
}

@Composable
private fun PlexCodePanel(state: PlexSignInState, onNewCode: () -> Unit, onCancel: () -> Unit) {
    val pin = state.pin
    val focus = remember { FocusRequester() }
    LaunchedEffect(state.codePhase) { focus.requestFocusAfterFrames() }
    when (state.codePhase) {
        PlexCodePhase.STARTING -> SiloLine(stringResource(R.string.servers_quick_connect_starting), centred = true)
        PlexCodePhase.WAITING -> if (pin != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xl),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SiloQrCode("https://${PlexAccountClient.LINK_PAGE}/?pin=${pin.code}", stringResource(R.string.plex_code_qr))
                Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
                    Text(
                        text = pin.code.toCharArray().joinToString(" "),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = NuvioTheme.colors.TextPrimary
                    )
                    SiloLine("1. " + stringResource(R.string.plex_code_step_open, PlexAccountClient.LINK_PAGE))
                    SiloLine("2. " + stringResource(R.string.plex_code_step_sign_in))
                    SiloLine("3. " + stringResource(R.string.plex_code_step_link))
                }
            }
            SiloLine(stringResource(R.string.plex_code_waiting), centred = true)
        }
        PlexCodePhase.EXPIRED -> SiloLine(stringResource(R.string.plex_code_expired), centred = true)
        PlexCodePhase.FAILED -> {
            SiloLine(stringResource(R.string.plex_code_failed), centred = true, color = NuvioTheme.colors.Error)
            PlexError(state.error, state.issue)
        }
    }
    SettingsDialogActionRow {
        SettingsDialogActionButton(
            text = stringResource(R.string.action_cancel),
            onClick = onCancel,
            modifier = if (state.codePhase == PlexCodePhase.WAITING || state.codePhase == PlexCodePhase.STARTING) Modifier.focusRequester(focus) else Modifier
        )
        SettingsDialogActionButton(
            text = stringResource(R.string.servers_quick_connect_new_code),
            onClick = onNewCode,
            primary = true,
            modifier = if (state.codePhase == PlexCodePhase.EXPIRED || state.codePhase == PlexCodePhase.FAILED) Modifier.focusRequester(focus) else Modifier
        )
    }
}

@Composable
private fun ColumnScope.PlexServersPanel(state: PlexSignInState, onPick: (PlexServer) -> Unit, onCancel: () -> Unit) {
    val first = remember { FocusRequester() }
    val servers = state.servers
    val focusIndex = servers.indexOfFirst { it.reach != PlexReach.OFFLINE }
    LaunchedEffect(state.serversLoading, servers.size) {
        if (focusIndex >= 0 && !state.serversLoading) first.requestFocusAfterFrames()
    }
    when {
        state.serversLoading && servers.isEmpty() -> SiloLine(stringResource(R.string.plex_finding_servers), centred = true)
        servers.isEmpty() -> SiloLine(stringResource(R.string.plex_servers_none), centred = true)
        else -> Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
        ) {
            servers.forEachIndexed { index, server ->
                SettingsActionRow(
                    title = server.name,
                    subtitle = server.description(state.serversLoading),
                    value = if (state.serversLoading) null else server.reach.label(),
                    onClick = { onPick(server) },
                    enabled = !state.busy && !state.serversLoading && server.reach != PlexReach.OFFLINE,
                    leadingIcon = Icons.Default.Dns.takeIf { serverLogoRes("plex") == null },
                    leadingRawIconRes = serverLogoRes("plex"),
                    valueColor = if (server.reach == PlexReach.RELAY) NuvioTheme.colors.Warning else NuvioTheme.colors.TextSecondary,
                    modifier = if (index == focusIndex) Modifier.focusRequester(first) else Modifier
                )
            }
            if (!state.serversLoading) SiloLine(stringResource(R.string.plex_servers_later), centred = true)
        }
    }
    PlexError(state.error, state.issue)
    SettingsDialogActionRow {
        SettingsDialogActionButton(text = stringResource(R.string.action_cancel), onClick = onCancel)
    }
}

@Composable
private fun PlexServer.description(checking: Boolean): String {
    val owner = stringResource(if (owned) R.string.plex_server_owned else R.string.plex_server_shared)
    if (checking) return owner
    val reachText = stringResource(
        when (reach) {
            PlexReach.LOCAL -> R.string.plex_reach_local_detail
            PlexReach.REMOTE -> R.string.plex_reach_remote_detail
            PlexReach.RELAY -> R.string.plex_reach_relay_detail
            PlexReach.OFFLINE -> R.string.plex_reach_offline_detail
        }
    )
    return "$owner · $reachText"
}

@Composable
private fun PlexReach.label(): String = stringResource(
    when (this) {
        PlexReach.LOCAL -> R.string.plex_reach_local
        PlexReach.REMOTE -> R.string.plex_reach_remote
        PlexReach.RELAY -> R.string.plex_reach_relay
        PlexReach.OFFLINE -> R.string.plex_reach_offline
    }
)

@Composable
internal fun PlexUsersPanel(
    users: List<PlexHomeUser>,
    enabled: Boolean,
    onPick: (PlexHomeUser) -> Unit,
    onBack: () -> Unit,
    current: String?,
    backText: String = stringResource(R.string.action_back)
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(users) { if (users.isNotEmpty()) first.requestFocusAfterFrames() }
    val focusIndex = users.indexOfFirst { it.uuid == current }.coerceAtLeast(0)
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg, Alignment.CenterHorizontally)
    ) {
        itemsIndexed(users, key = { _, user -> user.uuid }) { index, user ->
            PlexUserTile(
                user = user,
                selected = user.uuid == current,
                enabled = enabled,
                onClick = { onPick(user) },
                modifier = if (index == focusIndex) Modifier.focusRequester(first) else Modifier
            )
        }
    }
    SettingsDialogActionRow {
        SettingsDialogActionButton(text = backText, onClick = onBack, enabled = enabled)
    }
}

@Composable
private fun PlexUserTile(
    user: PlexHomeUser,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    val shape = settingsItemShape()
    Card(
        onClick = { if (enabled) onClick() },
        modifier = modifier
            .width(120.dp)
            .settingsItemFocus(focused)
            .onFocusChanged { focused = it.isFocused },
        colors = settingsRowColors(),
        border = settingsRowBorder(shape = shape),
        shape = CardDefaults.shape(shape),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = NuvioTheme.spacing.md, horizontal = NuvioTheme.spacing.sm),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .background(
                        if (selected) NuvioTheme.colors.Secondary.copy(alpha = 0.55f) else NuvioTheme.colors.TextPrimary.copy(alpha = 0.12f),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = user.name.trim().take(1).uppercase(),
                    style = MaterialTheme.typography.headlineSmall,
                    color = NuvioTheme.colors.TextPrimary
                )
            }
            Text(
                text = user.name,
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val tags = listOfNotNull(
                stringResource(R.string.plex_user_owner).takeIf { user.admin },
                stringResource(R.string.plex_user_managed).takeIf { user.restricted && !user.admin },
                stringResource(R.string.silo_profile_pin).takeIf { user.hasPin }
            )
            Text(
                text = tags.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = NuvioTheme.colors.TextSecondary
            )
        }
    }
}

@Composable
private fun PlexError(error: ServerFailure?, issue: ServerSignInIssue?) {
    val failure = error ?: return
    val text = when (issue) {
        ServerSignInIssue.TOO_MANY_TRIES -> stringResource(R.string.servers_error_too_many_tries)
        ServerSignInIssue.PLEX_REMOTE_PASS -> stringResource(R.string.plex_remote_pass_needed)
        else -> failure.siloMessage()
    }
    SiloLine(text, color = NuvioTheme.colors.Error)
}

/** Changing the Plex Home user of a saved Plex server. */
@Composable
internal fun PlexUserDialog(viewModel: MediaServersViewModel, current: String?) {
    val change by viewModel.plexUsers.collectAsStateWithLifecycle()
    val state = change ?: return
    val pinFor = state.pinFor
    NuvioDialog(
        onDismiss = { if (!state.busy) viewModel.closePlexUsers() },
        title = if (pinFor != null) stringResource(R.string.silo_pin_title, pinFor.name) else stringResource(R.string.plex_home_user),
        subtitle = if (pinFor == null) stringResource(R.string.plex_home_user_description) else null,
        width = 700.dp,
        backgroundContent = { SiloDialogBackground() }
    ) {
        when {
            state.loading -> SiloLine(stringResource(R.string.servers_quick_connect_starting), centred = true)
            pinFor != null -> SiloPinPanel(
                wrong = state.pinWrong,
                busy = state.busy,
                error = state.error,
                canGoBack = state.users.size > 1,
                onSubmit = { pin -> viewModel.pickPlexUser(pinFor, pin) },
                onBack = viewModel::backToPlexUsers,
                onCancel = viewModel::closePlexUsers,
                otherText = stringResource(R.string.plex_other_user)
            )
            else -> {
                state.error?.let { SiloLine(it.siloMessage(), color = NuvioTheme.colors.Error) }
                PlexUsersPanel(
                    users = state.users,
                    enabled = !state.busy,
                    onPick = { user -> viewModel.pickPlexUser(user) },
                    onBack = viewModel::closePlexUsers,
                    current = current,
                    backText = stringResource(R.string.action_cancel)
                )
            }
        }
    }
}
