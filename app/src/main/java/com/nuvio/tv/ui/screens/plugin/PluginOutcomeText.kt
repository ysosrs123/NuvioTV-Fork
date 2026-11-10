package com.nuvio.tv.ui.screens.plugin

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.core.plugin.PluginRunOutcome
import com.nuvio.tv.core.plugin.PluginRunReason
import com.nuvio.tv.core.plugin.PluginRunState
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
internal fun pluginOutcomeLabel(outcome: PluginRunOutcome?): Pair<String, Color> {
    if (outcome == null) return stringResource(R.string.plugin_status_not_searched) to NuvioTheme.colors.TextTertiary
    return when (outcome.state) {
        PluginRunState.FOUND -> stringResource(R.string.plugin_status_found, outcome.streams) to NuvioTheme.colors.Success
        PluginRunState.NO_RESULTS -> stringResource(R.string.plugin_status_no_results) to NuvioTheme.colors.TextSecondary
        PluginRunState.STOPPED -> stringResource(R.string.plugin_status_stopped) to NuvioTheme.colors.TextSecondary
        PluginRunState.CANT_RUN -> when (outcome.reason) {
            PluginRunReason.DID_NOT_LOAD -> stringResource(R.string.plugin_status_not_loaded)
            else -> stringResource(R.string.plugin_status_newer)
        } to NuvioTheme.colors.Warning
        PluginRunState.DOWN -> when (outcome.reason) {
            PluginRunReason.TIMEOUT -> stringResource(R.string.plugin_status_timeout)
            PluginRunReason.HTTP -> stringResource(R.string.plugin_status_http, outcome.httpCode ?: 0)
            PluginRunReason.UNREACHABLE -> stringResource(R.string.plugin_status_unreachable)
            else -> stringResource(R.string.plugin_status_error)
        } to NuvioTheme.colors.Error
    }
}
