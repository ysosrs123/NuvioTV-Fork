@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.data.mediaserver.ServerConnection
import com.nuvio.tv.data.mediaserver.ServerFailure
import com.nuvio.tv.data.mediaserver.ServerSignInIssue
import com.nuvio.tv.data.mediaserver.silo.SiloProfile
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.account.InputField
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private enum class SiloMethod { NATIVE, JELLYFIN }

/**
 * Adding Silo: the address, then Silo's own sign-in (the default) or the Jellyfin-compatible address.
 * With [replacing], the connection it names is replaced once signed in.
 */
@Composable
internal fun SiloSetupDialog(
    viewModel: MediaServersViewModel,
    initialAddress: String = "",
    replacing: String? = null,
    title: String = stringResource(R.string.silo_setup_title),
    onJellyfinCompatible: ((address: String) -> Unit)? = null,
    onConnected: (ServerConnection) -> Unit,
    onDismiss: () -> Unit
) {
    var address by remember { mutableStateOf(initialAddress) }
    var method by remember { mutableStateOf(SiloMethod.NATIVE) }
    var signingIn by remember { mutableStateOf(false) }
    val dismiss = {
        viewModel.silo.close()
        onDismiss()
    }

    if (signingIn) {
        SiloSignInDialog(
            viewModel = viewModel,
            onConnected = onConnected,
            onBack = {
                viewModel.silo.close()
                signingIn = false
            },
            onDismiss = dismiss
        )
        return
    }

    val next = {
        if (address.isNotBlank()) {
            if (method == SiloMethod.JELLYFIN && onJellyfinCompatible != null) {
                onJellyfinCompatible(address.trim())
            } else {
                signingIn = true
                viewModel.silo.start(address.trim(), replacing, onConnected)
            }
        }
    }
    val addressFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { addressFocus.requestFocusAfterFrames() }

    NuvioDialog(
        onDismiss = dismiss,
        title = title,
        subtitle = stringResource(R.string.silo_setup_subtitle),
        width = 640.dp,
        backgroundContent = { SiloDialogBackground() }
    ) {
        SiloField(label = stringResource(R.string.servers_address)) {
            InputField(
                value = address,
                onValueChange = { address = it },
                placeholder = SILO_ADDRESS_HINT,
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Next,
                onImeAction = next,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(addressFocus)
            )
        }
        if (onJellyfinCompatible != null) {
            Text(
                text = stringResource(R.string.silo_setup_how),
                style = MaterialTheme.typography.labelMedium,
                color = NuvioTheme.colors.TextSecondary
            )
            SiloMethodRow(
                title = stringResource(R.string.silo_method_native_recommended),
                description = stringResource(R.string.silo_method_native_description),
                selected = method == SiloMethod.NATIVE,
                onClick = { method = SiloMethod.NATIVE }
            )
            SiloMethodRow(
                title = stringResource(R.string.silo_method_jellyfin),
                description = stringResource(R.string.silo_method_jellyfin_description),
                selected = method == SiloMethod.JELLYFIN,
                onClick = { method = SiloMethod.JELLYFIN }
            )
        }
        SettingsDialogActionRow {
            SettingsDialogActionButton(text = stringResource(R.string.action_cancel), onClick = dismiss)
            SettingsDialogActionButton(
                text = stringResource(R.string.silo_setup_next),
                onClick = next,
                primary = true,
                enabled = address.isNotBlank()
            )
        }
    }
}

@Composable
private fun SiloMethodRow(title: String, description: String, selected: Boolean, onClick: () -> Unit) {
    SettingsActionRow(
        title = title,
        subtitle = description,
        onClick = onClick,
        leadingIcon = if (selected) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
        trailingIcon = null,
        subtitleContent = { _, alpha ->
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextSecondary.copy(alpha = alpha)
            )
        }
    )
}

@Composable
private fun SiloSignInDialog(
    viewModel: MediaServersViewModel,
    onConnected: (ServerConnection) -> Unit,
    onBack: () -> Unit,
    onDismiss: () -> Unit
) {
    val state by viewModel.silo.state.collectAsStateWithLifecycle()
    val server = state.server
    val busy = state.busy || state.step == SiloSignInStep.SIGNING_IN
    val title = when (state.step) {
        SiloSignInStep.PROFILES -> stringResource(R.string.silo_profiles_title)
        SiloSignInStep.PIN -> stringResource(R.string.silo_pin_title, state.pinFor?.name.orEmpty())
        else -> stringResource(R.string.servers_sign_in_title, "Silo")
    }
    val subtitle = when (state.step) {
        SiloSignInStep.PROFILES -> stringResource(
            R.string.silo_profiles_subtitle,
            state.account?.takeIf { it.isNotBlank() } ?: server?.name.orEmpty()
        )
        else -> server?.let { "${it.name} · ${it.address}" }
    }

    NuvioDialog(
        onDismiss = { if (!busy) onDismiss() },
        title = title,
        subtitle = subtitle,
        width = 680.dp,
        backgroundContent = { SiloDialogBackground() }
    ) {
        state.certificate?.let { certificate ->
            SiloLine(stringResource(R.string.servers_certificate_intro, certificate.host))
            SiloLine(stringResource(R.string.servers_certificate_advice))
            SettingsDialogActionRow {
                SettingsDialogActionButton(text = stringResource(R.string.action_back), onClick = onBack)
                SettingsDialogActionButton(
                    text = stringResource(R.string.servers_certificate_trust),
                    onClick = viewModel.silo::trustCertificate,
                    primary = true
                )
            }
            return@NuvioDialog
        }
        when (state.step) {
            SiloSignInStep.FINDING -> SiloLine(stringResource(R.string.servers_quick_connect_starting), centred = true)
            SiloSignInStep.SIGNING_IN -> SiloLine(stringResource(R.string.silo_signing_in), centred = true)
            SiloSignInStep.CODE -> SiloCodePanel(state, viewModel, onBack)
            SiloSignInStep.PASSWORD -> SiloPasswordPanel(state, viewModel, onBack)
            SiloSignInStep.PROFILES -> SiloProfilesPanel(
                profiles = state.profiles,
                enabled = !busy,
                onPick = viewModel.silo::pickProfile,
                onCancel = onDismiss
            )
            SiloSignInStep.PIN -> SiloPinPanel(
                wrong = state.pinWrong,
                busy = busy,
                error = state.error,
                canGoBack = state.profiles.size > 1,
                onSubmit = viewModel.silo::submitPin,
                onBack = viewModel.silo::backToProfiles,
                onCancel = onDismiss
            )
            SiloSignInStep.FAILED -> {
                SiloError(state)
                SettingsDialogActionRow {
                    SettingsDialogActionButton(text = stringResource(R.string.action_back), onClick = onBack, primary = true)
                }
            }
        }
    }
}

@Composable
private fun SiloCodePanel(state: SiloSignInState, viewModel: MediaServersViewModel, onBack: () -> Unit) {
    val server = state.server ?: return
    val code = state.code
    val network = server.network
    val primaryFocus = remember { FocusRequester() }
    LaunchedEffect(network != null, state.codePhase) { primaryFocus.requestFocusAfterFrames() }

    if (network != null) {
        SiloLine(stringResource(R.string.silo_network_known), centred = true)
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            SettingsDialogActionButton(
                text = stringResource(R.string.silo_network_continue, network.name.ifBlank { server.name }),
                onClick = viewModel.silo::continueWithNetwork,
                primary = true,
                enabled = !state.busy,
                modifier = Modifier.focusRequester(primaryFocus)
            )
        }
        SiloError(state)
        if (code != null && state.codePhase == SiloCodePhase.WAITING) {
            SiloLine(stringResource(R.string.silo_network_or_code, code.shownCode, code.link.shortLink()), centred = true)
        }
    } else {
        when (state.codePhase) {
            SiloCodePhase.STARTING -> SiloLine(stringResource(R.string.servers_quick_connect_starting), centred = true)
            SiloCodePhase.WAITING -> if (code != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xl),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SiloQrCode(code.linkWithCode)
                    Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
                        Text(
                            text = code.shownCode,
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = NuvioTheme.colors.TextPrimary
                        )
                        SiloLine("1. " + stringResource(R.string.silo_code_step_open, code.link.shortLink()))
                        SiloLine("2. " + stringResource(R.string.silo_code_step_sign_in))
                        SiloLine("3. " + stringResource(R.string.silo_code_step_approve))
                        if (state.codeOpened) {
                            SiloLine(stringResource(R.string.silo_code_opened), color = NuvioTheme.colors.TextPrimary)
                        } else {
                            SiloCountdown(state.codeExpiresAtMs)
                        }
                    }
                }
            }
            SiloCodePhase.EXPIRED -> SiloLine(stringResource(R.string.servers_quick_connect_expired), centred = true)
            SiloCodePhase.DENIED -> SiloLine(stringResource(R.string.silo_code_denied), centred = true)
            SiloCodePhase.UNAVAILABLE -> SiloLine(stringResource(R.string.silo_code_unavailable), centred = true)
            SiloCodePhase.FAILED -> SiloLine(
                stringResource(R.string.silo_code_failed) + " " + (state.error ?: ServerFailure.FAILED).siloMessage(),
                centred = true,
                color = NuvioTheme.colors.Error
            )
        }
    }
    val renewable = server.codeSignIn && state.codePhase in setOf(SiloCodePhase.EXPIRED, SiloCodePhase.DENIED, SiloCodePhase.FAILED)
    SettingsDialogActionRow {
        SettingsDialogActionButton(text = stringResource(R.string.action_back), onClick = onBack)
        if (server.passwordSignIn) {
            SettingsDialogActionButton(
                text = stringResource(R.string.servers_quick_connect_use_password),
                onClick = viewModel.silo::usePassword,
                enabled = !state.busy,
                modifier = if (network == null && !renewable) Modifier.focusRequester(primaryFocus) else Modifier
            )
        }
        if (renewable) {
            SettingsDialogActionButton(
                text = stringResource(R.string.servers_quick_connect_new_code),
                onClick = viewModel.silo::newCode,
                primary = true,
                modifier = if (network == null) Modifier.focusRequester(primaryFocus) else Modifier
            )
        }
    }
}

@Composable
private fun SiloPasswordPanel(state: SiloSignInState, viewModel: MediaServersViewModel, onBack: () -> Unit) {
    val server = state.server ?: return
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val usernameFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { usernameFocus.requestFocusAfterFrames() }
    val submit = { viewModel.silo.signInWithPassword(username, password) }

    SiloField(label = stringResource(R.string.servers_username)) {
        InputField(
            value = username,
            onValueChange = { username = it },
            placeholder = stringResource(R.string.servers_username),
            imeAction = ImeAction.Next,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(usernameFocus)
        )
    }
    SiloField(label = stringResource(R.string.servers_password)) {
        InputField(
            value = password,
            onValueChange = { password = it },
            placeholder = stringResource(R.string.servers_password),
            keyboardType = KeyboardType.Password,
            isPassword = true,
            imeAction = ImeAction.Done,
            onImeAction = submit,
            modifier = Modifier.fillMaxWidth()
        )
    }
    SiloError(state)
    SettingsDialogActionRow {
        SettingsDialogActionButton(text = stringResource(R.string.action_back), onClick = onBack, enabled = !state.busy)
        if (server.codeSignIn) {
            SettingsDialogActionButton(
                text = stringResource(R.string.servers_quick_connect),
                onClick = viewModel.silo::useCode,
                enabled = !state.busy
            )
        }
        SettingsDialogActionButton(
            text = stringResource(if (state.busy) R.string.servers_connecting else R.string.servers_connect),
            onClick = submit,
            primary = true,
            enabled = !state.busy && username.isNotBlank() && password.isNotEmpty()
        )
    }
}

@Composable
internal fun SiloProfilesPanel(
    profiles: List<SiloProfile>,
    enabled: Boolean,
    onPick: (SiloProfile) -> Unit,
    onCancel: () -> Unit,
    current: String? = null
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(profiles) { if (profiles.isNotEmpty()) firstFocus.requestFocusAfterFrames() }
    val focusIndex = profiles.indexOfFirst { it.id == current }.coerceAtLeast(0)
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg, Alignment.CenterHorizontally)
    ) {
        itemsIndexed(profiles, key = { _, profile -> profile.id }) { index, profile ->
            SiloProfileTile(
                profile = profile,
                selected = profile.id == current,
                enabled = enabled,
                onClick = { onPick(profile) },
                modifier = if (index == focusIndex) Modifier.focusRequester(firstFocus) else Modifier
            )
        }
    }
    SettingsDialogActionRow {
        SettingsDialogActionButton(text = stringResource(R.string.action_cancel), onClick = onCancel)
    }
}

@Composable
private fun SiloProfileTile(
    profile: SiloProfile,
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
                    text = profile.name.trim().take(1).uppercase(),
                    style = MaterialTheme.typography.headlineSmall,
                    color = NuvioTheme.colors.TextPrimary
                )
            }
            Text(
                text = profile.name,
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val tags = listOfNotNull(
                stringResource(R.string.silo_profile_pin).takeIf { profile.hasPin },
                stringResource(R.string.silo_profile_child).takeIf { profile.isChild }
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
internal fun SiloPinPanel(
    wrong: Boolean,
    busy: Boolean,
    error: ServerFailure?,
    canGoBack: Boolean,
    onSubmit: (String) -> Unit,
    onBack: () -> Unit,
    onCancel: () -> Unit,
    otherText: String = stringResource(R.string.silo_other_profile)
) {
    var pin by remember { mutableStateOf("") }
    val pinFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { pinFocus.requestFocusAfterFrames() }
    LaunchedEffect(wrong) { if (wrong) pin = "" }
    SiloField(label = stringResource(R.string.silo_profile_pin)) {
        InputField(
            value = pin,
            onValueChange = { value -> pin = value.filter(Char::isDigit).take(PIN_MAX_DIGITS) },
            placeholder = stringResource(R.string.silo_profile_pin),
            keyboardType = KeyboardType.NumberPassword,
            isPassword = true,
            imeAction = ImeAction.Done,
            onImeAction = { onSubmit(pin) },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(pinFocus)
        )
    }
    when {
        wrong -> SiloLine(stringResource(R.string.silo_pin_wrong), color = NuvioTheme.colors.Error)
        error != null -> SiloLine(error.siloMessage(), color = NuvioTheme.colors.Error)
    }
    SettingsDialogActionRow {
        SettingsDialogActionButton(
            text = if (canGoBack) otherText else stringResource(R.string.action_cancel),
            onClick = if (canGoBack) onBack else onCancel,
            enabled = !busy
        )
        SettingsDialogActionButton(
            text = stringResource(if (busy) R.string.servers_connecting else R.string.servers_connect),
            onClick = { onSubmit(pin) },
            primary = true,
            enabled = !busy && pin.isNotBlank()
        )
    }
}

@Composable
internal fun SiloQrCode(link: String, description: String = stringResource(R.string.silo_code_qr)) {
    val bitmap = remember(link) { runCatching { QrCodeGenerator.generate(link, size = 360, margin = 1) }.getOrNull() }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = description,
            modifier = Modifier
                .size(168.dp)
                .background(Color.White, RoundedCornerShape(8.dp))
                .padding(6.dp)
        )
    }
}

@Composable
internal fun SiloCountdown(expiresAtMs: Long) {
    var remainingMs by remember(expiresAtMs) { mutableLongStateOf(expiresAtMs - System.currentTimeMillis()) }
    LaunchedEffect(expiresAtMs) {
        while (remainingMs > 0L) {
            delay(1_000L)
            remainingMs = expiresAtMs - System.currentTimeMillis()
        }
    }
    val seconds = (remainingMs.coerceAtLeast(0L) / 1_000L).toInt()
    SiloLine(stringResource(R.string.silo_code_waiting, "%d:%02d".format(seconds / 60, seconds % 60)))
}

@Composable
private fun SiloError(state: SiloSignInState) {
    val failure = state.error ?: return
    val text = when (state.issue) {
        ServerSignInIssue.SERVER_MESSAGE -> state.detail?.takeIf { it.isNotBlank() } ?: failure.siloMessage()
        null -> failure.siloMessage()
        else -> state.issue.siloIssueMessage()
    }
    SiloLine(text, color = NuvioTheme.colors.Error)
}

@Composable
private fun ServerSignInIssue.siloIssueMessage(): String = stringResource(
    when (this) {
        ServerSignInIssue.SILO_NOT_NATIVE -> R.string.servers_error_silo_not_native
        ServerSignInIssue.TEMPORARY_PASSWORD -> R.string.servers_error_temporary_password
        ServerSignInIssue.PASSWORD_SIGN_IN_OFF -> R.string.servers_error_password_sign_in_off
        ServerSignInIssue.TOO_MANY_TRIES -> R.string.servers_error_too_many_tries
        ServerSignInIssue.PIN_WRONG -> R.string.silo_pin_wrong
        else -> R.string.servers_error_failed
    }
)

@Composable
internal fun ServerFailure.siloMessage(): String = when (this) {
    ServerFailure.AUTH_REQUIRED -> stringResource(R.string.servers_error_auth)
    ServerFailure.NOT_FOUND -> stringResource(R.string.servers_error_address)
    ServerFailure.UNREACHABLE -> stringResource(R.string.servers_error_unreachable)
    ServerFailure.FORBIDDEN -> stringResource(R.string.servers_error_forbidden)
    ServerFailure.CERTIFICATE -> stringResource(R.string.servers_certificate_unavailable)
    ServerFailure.UNSUPPORTED -> stringResource(R.string.servers_error_unsupported_server)
    ServerFailure.STREAM_LIMIT,
    ServerFailure.INCOMPLETE,
    ServerFailure.FAILED -> stringResource(R.string.servers_error_failed)
}

@Composable
internal fun SiloLine(text: String, centred: Boolean = false, color: Color = NuvioTheme.colors.TextSecondary) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        textAlign = if (centred) TextAlign.Center else TextAlign.Start,
        modifier = if (centred) Modifier.fillMaxWidth() else Modifier
    )
}

@Composable
private fun SiloField(label: String, field: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
    ) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = NuvioTheme.colors.TextSecondary)
        field()
    }
}

@Composable
internal fun BoxScope.SiloDialogBackground() {
    Box(Modifier.matchParentSize().background(NuvioTheme.colors.BackgroundElevated.copy(alpha = 0.94f)))
}

/** The link as people type it: no scheme, no trailing slash. */
private fun String.shortLink(): String = substringAfter("://").trimEnd('/')

/** Silo's website usually runs on 8090 next to its Jellyfin-compatible port (8096, or 8097 beside a real Jellyfin). */
internal fun siloWebsiteGuess(jellyfinAddress: String): String {
    val url = jellyfinAddress.toHttpUrlOrNull() ?: return jellyfinAddress
    if (url.port != 8096 && url.port != 8097) return jellyfinAddress
    return url.newBuilder().port(SILO_WEBSITE_PORT).build().toString().trimEnd('/')
}

@Composable
internal fun SiloProfileDialog(viewModel: MediaServersViewModel, current: String?) {
    val change by viewModel.siloProfiles.collectAsStateWithLifecycle()
    val state = change ?: return
    val pinFor = state.pinFor
    NuvioDialog(
        onDismiss = { if (!state.busy) viewModel.closeSiloProfiles() },
        title = if (pinFor != null) stringResource(R.string.silo_pin_title, pinFor.name) else stringResource(R.string.silo_profiles_title),
        subtitle = if (pinFor == null) stringResource(R.string.servers_silo_profile_description) else null,
        width = 680.dp,
        backgroundContent = { SiloDialogBackground() }
    ) {
        when {
            state.loading -> SiloLine(stringResource(R.string.servers_quick_connect_starting), centred = true)
            pinFor != null -> SiloPinPanel(
                wrong = state.pinWrong,
                busy = state.busy,
                error = state.error,
                canGoBack = state.profiles.size > 1,
                onSubmit = { pin -> viewModel.pickSiloProfile(pinFor, pin) },
                onBack = viewModel::backToSiloProfiles,
                onCancel = viewModel::closeSiloProfiles
            )
            else -> {
                state.error?.let { SiloLine(it.siloMessage(), color = NuvioTheme.colors.Error) }
                SiloProfilesPanel(
                    profiles = state.profiles,
                    enabled = !state.busy,
                    onPick = { profile -> viewModel.pickSiloProfile(profile) },
                    onCancel = viewModel::closeSiloProfiles,
                    current = current
                )
            }
        }
    }
}

private const val SILO_ADDRESS_HINT = "https://silo.example.com"
private const val SILO_WEBSITE_PORT = 8090
private const val PIN_MAX_DIGITS = 8
