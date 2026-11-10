package com.nuvio.tv.ui.screens.stream

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.focusable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.nuvio.tv.ui.components.NuvioScrollDefaults
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.plugin.PluginRunState
import com.nuvio.tv.core.plugin.PluginSearchSummary
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.screens.plugin.pluginFocusedFill
import com.nuvio.tv.ui.screens.plugin.pluginOutcomeLabel
import com.nuvio.tv.ui.screens.settings.SettingsDialogActionButton
import com.nuvio.tv.ui.screens.settings.SettingsDialogActionRow
import com.nuvio.tv.ui.screens.settings.isV2Settings
import com.nuvio.tv.ui.screens.settings.settingsItemFocus
import com.nuvio.tv.ui.screens.settings.settingsItemShape
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
internal fun pluginSummaryCounts(summary: PluginSearchSummary): String {
    val failed = summary.count(PluginRunState.DOWN) + summary.count(PluginRunState.CANT_RUN)
    val running = (summary.total - summary.outcomes.size).coerceAtLeast(0)
    return listOfNotNull(
        stringResource(R.string.plugin_summary_found, summary.count(PluginRunState.FOUND)),
        stringResource(R.string.plugin_summary_none, summary.count(PluginRunState.NO_RESULTS)),
        stringResource(R.string.plugin_summary_failed, failed).takeIf { failed > 0 },
        summary.count(PluginRunState.STOPPED).takeIf { it > 0 }?.let { stringResource(R.string.plugin_summary_stopped, it) },
        running.takeIf { it > 0 }?.let { stringResource(R.string.plugin_summary_running, it) }
    ).joinToString(", ")
}

@Composable
internal fun PluginSearchSummaryRow(summary: PluginSearchSummary, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    StreamResultSurface(
        onClick = onClick,
        focused = focused,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(NuvioTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)
        ) {
            Text(
                text = stringResource(R.string.plugin_summary_title, summary.total),
                style = MaterialTheme.typography.titleSmall,
                color = NuvioTheme.colors.TextPrimary
            )
            Text(
                text = pluginSummaryCounts(summary),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private val BREAKDOWN_ORDER = listOf(
    PluginRunState.FOUND,
    PluginRunState.DOWN,
    PluginRunState.CANT_RUN,
    PluginRunState.STOPPED,
    PluginRunState.NO_RESULTS
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PluginSearchBreakdownDialog(
    summary: PluginSearchSummary,
    names: Map<String, String>,
    onDismiss: () -> Unit
) {
    val firstRowFocus = remember { FocusRequester() }
    val closeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (summary.outcomes.isEmpty()) closeFocus.requestFocusAfterFrames() else firstRowFocus.requestFocusAfterFrames()
    }
    val rows = remember(summary, names) {
        summary.outcomes
            .sortedWith(compareBy({ BREAKDOWN_ORDER.indexOf(it.state) }, { names[it.scraperId] ?: it.scraperId }))
    }
    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.plugin_breakdown_title),
        subtitle = pluginSummaryCounts(summary),
        width = 640.dp
    ) {
        CompositionLocalProvider(LocalBringIntoViewSpec provides NuvioScrollDefaults.smoothScrollSpec) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .background(NuvioTheme.colors.Surface, settingsItemShape(NuvioTheme.radii.md)),
            contentPadding = PaddingValues(NuvioTheme.spacing.xs),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)
        ) {
            itemsIndexed(rows, key = { _, it -> it.scraperId }) { index, outcome ->
                val (label, color) = pluginOutcomeLabel(outcome)
                var rowFocused by remember { mutableStateOf(false) }
                val rowShape = settingsItemShape(NuvioTheme.radii.sm)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (index == 0) Modifier.focusRequester(firstRowFocus) else Modifier)
                        .settingsItemFocus(rowFocused)
                        .onFocusChanged { rowFocused = it.isFocused }
                        .focusable()
                        .background(if (rowFocused) pluginFocusedFill() else Color.Transparent, rowShape)
                        .then(
                            if (rowFocused && !isV2Settings()) {
                                Modifier.border(NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), rowShape)
                            } else {
                                Modifier
                            }
                        )
                        .padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
                ) {
                    Text(
                        text = names[outcome.scraperId] ?: outcome.scraperId.substringAfterLast(':'),
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioTheme.colors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodySmall,
                        color = color,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1.4f)
                    )
                }
            }
        }
        }
        SettingsDialogActionRow {
            SettingsDialogActionButton(
                text = stringResource(R.string.action_close),
                onClick = onDismiss,
                primary = true,
                modifier = Modifier.focusRequester(closeFocus)
            )
        }
    }
}
