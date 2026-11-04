@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.account.InputField
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.rememberRawSvgPainter

@Composable
internal fun FloppyAccountDialog(
    state: FloppyTrackerUiState,
    onConnect: (String) -> Unit,
    onConnectWithToken: (String, String) -> Unit,
    onShowStep: (FloppySignInStep) -> Unit,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit
) {
    val logo = rememberRawSvgPainter(R.raw.floppy_logo, 150.dp)
    if (state.isConnected) {
        ConnectedTrackingAccountDialog(TrackingDialogBrand.FLOPPY, logo, onDismiss) {
            ConnectedTrackingAccountContent(
                brand = TrackingDialogBrand.FLOPPY,
                logo = logo,
                logoContentDescription = stringResource(R.string.floppy_name),
                connectedLabel = stringResource(R.string.floppy_connected_to, state.serverLabel.orEmpty()),
                connectedDescription = stringResource(R.string.floppy_connected_description),
                statusMessage = null,
                errorMessage = state.errorMessage,
                isLoading = state.isLoading,
                onDisconnect = onDisconnect
            )
        }
        return
    }
    var address by rememberSaveable(state.lastServerUrl) { mutableStateOf(state.lastServerUrl.orEmpty()) }
    var token by rememberSaveable { mutableStateOf("") }
    NuvioDialog(onDismiss = onDismiss, title = "", width = 720.dp, titleTextAlign = TextAlign.Center, suppressFirstKeyUp = false) {
        when (state.step) {
            FloppySignInStep.CODE -> {
                val session = state.session
                TrackingDeviceAuthContent(
                    providerName = stringResource(R.string.floppy_name),
                    logo = logo,
                    logoContentDescription = stringResource(R.string.floppy_name),
                    logoLabel = stringResource(R.string.floppy_name),
                    qrContentDescription = stringResource(R.string.floppy_qr_description),
                    instruction = stringResource(R.string.mdblist_awaiting_instruction),
                    userCode = session?.userCode,
                    displayUrl = session?.verificationUri,
                    qrUrl = session?.verificationUriComplete,
                    expiresAtEpochMs = session?.expiresAtEpochMs,
                    isLoading = state.isLoading,
                    isPolling = state.isPolling,
                    credentialsConfigured = true,
                    statusMessage = state.statusMessage,
                    errorMessage = state.errorMessage,
                    missingCredentialsMessage = "",
                    onStartConnection = { onConnect(address) },
                    onRetryPolling = { onConnect(session?.serverUrl ?: address) },
                    onDismiss = onDismiss
                )
            }
            FloppySignInStep.ADDRESS, FloppySignInStep.TOKEN -> FloppySignInForm(
                showToken = state.step == FloppySignInStep.TOKEN,
                address = address,
                onAddressChange = { address = it },
                token = token,
                onTokenChange = { token = it },
                isLoading = state.isLoading,
                errorMessage = state.errorMessage,
                onNext = { onConnect(address) },
                onUseToken = { onShowStep(FloppySignInStep.TOKEN) },
                onConnectWithToken = { onConnectWithToken(address, token) },
                onBack = { onShowStep(FloppySignInStep.ADDRESS) },
                onDismiss = onDismiss,
                logo = logo
            )
        }
    }
}

@Composable
private fun FloppySignInForm(
    showToken: Boolean,
    address: String,
    onAddressChange: (String) -> Unit,
    token: String,
    onTokenChange: (String) -> Unit,
    isLoading: Boolean,
    errorMessage: String?,
    onNext: () -> Unit,
    onUseToken: () -> Unit,
    onConnectWithToken: () -> Unit,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
    logo: Painter
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        TrackingProviderWordmark(logo, stringResource(R.string.floppy_name), stringResource(R.string.floppy_name))
        Text(
            text = stringResource(if (showToken) R.string.floppy_token_instruction else R.string.floppy_address_instruction),
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        InputField(
            value = address,
            onValueChange = onAddressChange,
            placeholder = stringResource(R.string.floppy_address_placeholder),
            keyboardType = KeyboardType.Uri,
            imeAction = if (showToken) ImeAction.Next else ImeAction.Done,
            onImeAction = { if (!showToken && address.isNotBlank()) onNext() },
            modifier = Modifier.fillMaxWidth()
        )
        if (showToken) {
            InputField(
                value = token,
                onValueChange = onTokenChange,
                placeholder = stringResource(R.string.floppy_token_placeholder),
                isPassword = true,
                onImeAction = { if (address.isNotBlank() && token.isNotBlank()) onConnectWithToken() },
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (!errorMessage.isNullOrBlank()) {
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.Error,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
        SettingsDialogActionRow(horizontalAlignment = Alignment.CenterHorizontally) {
            if (showToken) {
                SettingsDialogActionButton(text = stringResource(R.string.floppy_back), onClick = onBack)
                SettingsDialogActionButton(
                    text = stringResource(R.string.floppy_connect),
                    onClick = onConnectWithToken,
                    primary = true,
                    enabled = !isLoading && address.isNotBlank() && token.isNotBlank()
                )
            } else {
                SettingsDialogActionButton(text = stringResource(R.string.action_cancel), onClick = onDismiss)
                SettingsDialogActionButton(text = stringResource(R.string.floppy_use_token), onClick = onUseToken)
                SettingsDialogActionButton(
                    text = stringResource(R.string.floppy_next),
                    onClick = onNext,
                    primary = true,
                    enabled = !isLoading && address.isNotBlank()
                )
            }
        }
    }
}
