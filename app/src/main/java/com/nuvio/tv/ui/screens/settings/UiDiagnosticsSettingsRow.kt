@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import android.util.Log
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.local.UiScalePreference
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.v2.diagnostics.deviceUiDiagnosticReport

@Composable
internal fun UiDiagnosticsSettingsRow() {
    val context = LocalContext.current
    val activityView = LocalView.current
    val scale by remember(context) { UiScalePreference.flow(context) }.collectAsState(initial = 100)
    val launchFocus = remember { FocusRequester() }
    val closeFocus = remember { FocusRequester() }
    var report by remember { mutableStateOf<String?>(null) }
    var restoreFocus by remember { mutableStateOf(false) }
    fun capture() {
        report = deviceUiDiagnosticReport(context, activityView, scale)
        Log.i("NuvioUiDiagnostics", report.orEmpty())
    }
    fun dismiss() {
        report = null
        restoreFocus = true
    }
    LaunchedEffect(report != null, restoreFocus) {
        if (report != null) closeFocus.requestFocusAfterFrames()
        else if (restoreFocus) {
            launchFocus.requestFocusAfterFrames()
            restoreFocus = false
        }
    }
    SettingsActionRow(
        title = stringResource(R.string.v2_ui_diagnostics_title),
        subtitle = stringResource(R.string.v2_ui_diagnostics_subtitle),
        onClick = ::capture,
        modifier = Modifier.focusRequester(launchFocus)
    )
    report?.let { text ->
        NuvioDialog(
            title = stringResource(R.string.v2_ui_diagnostics_title),
            onDismiss = ::dismiss,
            width = 680.dp
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState()).focusable()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = ::dismiss, modifier = Modifier.focusRequester(closeFocus)) {
                    Text(stringResource(R.string.action_close))
                }
                Button(onClick = ::capture) {
                    Text(stringResource(R.string.display_mode_refresh))
                }
            }
        }
    }
}
