package com.nuvio.tv.ui.screens.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.util.rememberRawSvgPainter
import java.text.NumberFormat

private const val WETRAKR_WEBSITE_URL = "https://wetrakr.com"

@Composable
internal fun WeTrakrAccountDialog(
    state: WeTrakrTrackerUiState,
    formatTime: (Long) -> String,
    onStartConnection: () -> Unit,
    onRetryPolling: () -> Unit,
    onSync: () -> Unit,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val logo = rememberRawSvgPainter(R.raw.wetrakr_logo, 160.dp)
    if (state.isConnected) {
        val glyph = rememberRawSvgPainter(R.raw.wetrakr_we_white, 150.dp)
        ConnectedTrackingAccountDialog(TrackingDialogBrand.WETRAKR, glyph, onDismiss) {
            ConnectedTrackingAccountContent(
                brand = TrackingDialogBrand.WETRAKR,
                logo = logo,
                logoContentDescription = stringResource(R.string.wetrakr_name),
                connectedLabel = stringResource(
                    R.string.wetrakr_connected_as,
                    state.username ?: stringResource(R.string.wetrakr_account_fallback)
                ),
                connectedDescription = stringResource(R.string.wetrakr_tracking_description),
                statusMessage = state.statusMessage,
                errorMessage = state.errorMessage,
                isLoading = state.isLoading || state.isSyncing,
                onDisconnect = onDisconnect,
                onSync = onSync,
                onVisit = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(WETRAKR_WEBSITE_URL))) } },
                visitLabel = stringResource(R.string.wetrakr_visit),
                footnote = stringResource(R.string.wetrakr_credit) + " · wetrakr.com",
                extraContent = { WeTrakrUsageStrip(state, formatTime) }
            )
        }
    } else {
        NuvioDialog(onDismiss = onDismiss, title = "", width = 720.dp, titleTextAlign = TextAlign.Center, suppressFirstKeyUp = false) {
            TrackingDeviceAuthContent(
                providerName = stringResource(R.string.wetrakr_name),
                logo = logo,
                logoContentDescription = stringResource(R.string.wetrakr_name),
                qrContentDescription = stringResource(R.string.wetrakr_qr_description),
                instruction = stringResource(R.string.mdblist_awaiting_instruction),
                userCode = state.session?.userCode,
                displayUrl = state.session?.verificationUri?.removePrefix("https://"),
                qrUrl = state.session?.verificationUriComplete,
                expiresAtEpochMs = state.session?.expiresAtEpochMs,
                isLoading = state.isLoading,
                isPolling = state.isPolling,
                credentialsConfigured = state.credentialsConfigured,
                statusMessage = state.statusMessage,
                errorMessage = state.errorMessage,
                missingCredentialsMessage = stringResource(R.string.wetrakr_missing_client),
                onStartConnection = onStartConnection,
                onRetryPolling = onRetryPolling,
                onDismiss = onDismiss
            )
        }
    }
}

@Composable
private fun WeTrakrUsageStrip(state: WeTrakrTrackerUiState, formatTime: (Long) -> String) {
    val numbers = NumberFormat.getIntegerInstance()
    val quota = state.quota
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        WeTrakrUsageBox(
            value = quota?.let {
                stringResource(R.string.wetrakr_requests_used, numbers.format(it.used), numbers.format(it.limit))
            } ?: "–",
            label = stringResource(R.string.wetrakr_requests_used_label),
            modifier = Modifier.weight(1f)
        )
        val paused = state.pausedUntilEpochMs
        WeTrakrUsageBox(
            value = when {
                paused != null -> formatTime(paused)
                state.lastSyncedAtEpochMs != null -> formatTime(state.lastSyncedAtEpochMs)
                else -> stringResource(R.string.wetrakr_never_synced)
            },
            label = stringResource(if (paused != null) R.string.wetrakr_resumes_label else R.string.wetrakr_last_synced_label),
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun WeTrakrUsageBox(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(Color.White.copy(alpha = 0.07f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.65f))
    }
}

@Composable
internal fun WeTrakrUploadDialog(
    state: WeTrakrUploadUiState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    if (state == WeTrakrUploadUiState.Hidden) return
    val numbers = NumberFormat.getIntegerInstance()
    val message = when (state) {
        WeTrakrUploadUiState.Counting -> stringResource(R.string.wetrakr_upload_counting)
        is WeTrakrUploadUiState.Ready -> if (state.plan.watchedCount + state.plan.libraryCount == 0) {
            stringResource(R.string.wetrakr_upload_nothing)
        } else {
            stringResource(R.string.wetrakr_upload_confirm, numbers.format(state.plan.watchedCount), numbers.format(state.plan.libraryCount))
        }
        WeTrakrUploadUiState.Sending -> stringResource(R.string.wetrakr_upload_sending)
        is WeTrakrUploadUiState.Done -> stringResource(
            R.string.wetrakr_upload_done, numbers.format(state.result.added), numbers.format(state.result.alreadyThere + state.result.notFound)
        )
        is WeTrakrUploadUiState.Failed -> state.message
        WeTrakrUploadUiState.Hidden -> ""
    }
    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.wetrakr_upload_title),
        subtitle = stringResource(R.string.wetrakr_upload_subtitle),
        width = 620.dp,
        suppressFirstKeyUp = false
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = if (state is WeTrakrUploadUiState.Failed) com.nuvio.tv.ui.theme.NuvioTheme.colors.Error
            else com.nuvio.tv.ui.theme.NuvioTheme.colors.TextSecondary
        )
        val canSend = state is WeTrakrUploadUiState.Ready && state.plan.watchedCount + state.plan.libraryCount > 0
        SettingsDialogActionRow {
            SettingsDialogActionButton(
                text = stringResource(if (canSend) R.string.action_cancel else R.string.wetrakr_upload_close),
                onClick = onDismiss,
                enabled = state != WeTrakrUploadUiState.Sending
            )
            if (canSend) {
                SettingsDialogActionButton(text = stringResource(R.string.wetrakr_upload_send), onClick = onConfirm, primary = true)
            }
        }
    }
}
