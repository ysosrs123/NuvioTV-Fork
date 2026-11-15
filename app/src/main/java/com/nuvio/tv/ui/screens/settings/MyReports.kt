@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import android.content.Context
import android.text.format.DateUtils
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.R
import com.nuvio.tv.data.repository.AppReportKind
import com.nuvio.tv.data.repository.ReportStatus
import com.nuvio.tv.data.repository.SentReport
import com.nuvio.tv.data.repository.SentReportsStore
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.components.NuvioScrollDefaults
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.updater.VersionUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal data class ReportStepLine(val status: ReportStatus, val atMs: Long?, val reached: Boolean)

/** The steps one report shows: the usual path while it is open, the path it took once it has an outcome. */
internal fun reportSteps(report: SentReport): List<ReportStepLine> {
    val sent = ReportStepLine(ReportStatus.Sent, report.timeMs, true)
    fun seen(status: ReportStatus) = ReportStepLine(status, report.seenAt[status], true)
    return when (report.status) {
        ReportStatus.Sent, ReportStatus.Read, ReportStatus.CauseFound -> {
            val path = listOf(ReportStatus.Read, ReportStatus.CauseFound, ReportStatus.Fixed)
            listOf(sent) + path.map { status ->
                val reached = path.indexOf(status) <= path.indexOf(report.status)
                ReportStepLine(status, report.seenAt[status].takeIf { reached }, reached)
            }
        }
        ReportStatus.Fixed -> listOf(sent, seen(ReportStatus.Read), seen(ReportStatus.CauseFound), seen(ReportStatus.Fixed))
        ReportStatus.Outside, ReportStatus.Closed -> listOfNotNull(
            sent,
            seen(ReportStatus.Read),
            seen(ReportStatus.CauseFound).takeIf { ReportStatus.CauseFound in report.seenAt },
            seen(report.status)
        )
        ReportStatus.Gone -> listOf(sent) +
            listOf(ReportStatus.Read, ReportStatus.CauseFound, ReportStatus.Fixed, ReportStatus.Outside, ReportStatus.Closed)
                .filter { it in report.seenAt }
                .map(::seen) + seen(ReportStatus.Gone)
    }
}

/** True when the report was fixed in a version newer than the one installed. */
internal fun fixComesWithUpdate(report: SentReport, installed: String = BuildConfig.VERSION_NAME): Boolean =
    report.status == ReportStatus.Fixed && report.fixedIn != null && VersionUtils.isRemoteNewer(report.fixedIn, installed)

@Composable
internal fun reportStatusText(status: ReportStatus, fixedIn: String?): String = when (status) {
    ReportStatus.Sent -> stringResource(R.string.report_status_sent)
    ReportStatus.Read -> stringResource(R.string.report_status_read)
    ReportStatus.CauseFound -> stringResource(R.string.report_status_cause_found)
    ReportStatus.Fixed -> fixedIn?.let { stringResource(R.string.report_status_fixed_in, it) }
        ?: stringResource(R.string.report_status_fixed)
    ReportStatus.Outside -> stringResource(R.string.report_status_outside)
    ReportStatus.Closed -> stringResource(R.string.report_status_closed)
    ReportStatus.Gone -> stringResource(R.string.report_status_gone)
}

@StringRes
internal fun sentKindLabel(kind: String): Int? = when (kind) {
    "playback" -> R.string.report_sent_kind_playback
    AppReportKind.Bug.key -> R.string.report_sent_kind_bug
    AppReportKind.Idea.key -> R.string.report_sent_kind_idea
    AppReportKind.Crash.key -> R.string.report_row_crash
    else -> null
}

internal fun reportAge(timeMs: Long): String =
    DateUtils.getRelativeTimeSpanString(timeMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

internal fun reportDay(context: Context, timeMs: Long): String =
    DateUtils.formatDateTime(context, timeMs, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MyReportsDialog(
    reports: List<SentReport>,
    checkEnabled: Boolean,
    onOpenReport: (SentReport) -> Unit,
    onClose: () -> Unit
) {
    var open by remember { mutableStateOf<SentReport?>(null) }
    open?.let { shown ->
        ReportStepsDialog(reports.firstOrNull { it.code == shown.code } ?: shown, onClose = { open = null })
        return
    }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { firstFocus.requestFocus() }
    }
    val focusCode = reports.firstOrNull { it.isNew }?.code ?: reports.firstOrNull()?.code
    NuvioDialog(
        onDismiss = onClose,
        title = stringResource(R.string.report_sent_list_title),
        subtitle = stringResource(R.string.report_sent_list_empty).takeIf { reports.isEmpty() },
        width = 640.dp,
        usePlatformDefaultWidth = false,
        solidWithoutBlur = true
    ) {
        if (reports.isNotEmpty()) {
            CompositionLocalProvider(LocalBringIntoViewSpec provides NuvioScrollDefaults.smoothScrollSpec) {
                LazyColumn(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs),
                    contentPadding = PaddingValues(vertical = NuvioTheme.spacing.sm)
                ) {
                    items(reports, key = { it.code }) { report ->
                        val kind = sentKindLabel(report.kind)?.let { stringResource(it) }
                        SettingsActionRow(
                            title = report.code,
                            subtitle = listOfNotNull(kind, reportAge(report.timeMs)).joinToString(" · "),
                            value = if (report.followed) reportStatusText(report.status, report.fixedIn) else null,
                            valueColor = if (report.isNew) NuvioTheme.colors.Secondary else NuvioTheme.colors.TextSecondary,
                            titleTrailingIcon = if (report.isNew) Icons.Filled.FiberManualRecord else null,
                            titleTrailingIconTint = NuvioTheme.colors.Secondary,
                            trailingIcon = null,
                            jumpAnchor = false,
                            onClick = {
                                onOpenReport(report)
                                open = report
                            },
                            modifier = if (report.code == focusCode) Modifier.focusRequester(firstFocus) else Modifier
                        )
                    }
                }
            }
            Text(
                text = stringResource(if (checkEnabled) R.string.report_my_reports_hint else R.string.report_my_reports_hint_off),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.extendedColors.textSecondary
            )
        }
        SettingsDialogActionRow {
            SettingsDialogActionButton(
                text = stringResource(R.string.action_close),
                onClick = onClose,
                primary = true,
                modifier = if (reports.isEmpty()) Modifier.focusRequester(firstFocus) else Modifier
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReportStepsDialog(report: SentReport, onClose: () -> Unit) {
    val context = LocalContext.current
    val closeButton = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { closeButton.requestFocus() }
    }
    val kind = sentKindLabel(report.kind)?.let { stringResource(it) } ?: report.code
    val hint = when {
        !report.followed -> stringResource(R.string.report_steps_not_followed)
        report.status == ReportStatus.Outside -> stringResource(R.string.report_steps_outside)
        report.status == ReportStatus.Gone -> stringResource(R.string.report_steps_gone)
        fixComesWithUpdate(report) -> stringResource(R.string.report_steps_update, BuildConfig.VERSION_NAME)
        else -> null
    }
    NuvioDialog(
        onDismiss = onClose,
        title = report.code,
        subtitle = stringResource(R.string.report_steps_sent_on, kind, reportDay(context, report.timeMs)),
        width = 640.dp,
        usePlatformDefaultWidth = false,
        solidWithoutBlur = true
    ) {
        if (report.followed) {
            CompositionLocalProvider(LocalBringIntoViewSpec provides NuvioScrollDefaults.smoothScrollSpec) {
                LazyColumn(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs),
                    contentPadding = PaddingValues(vertical = NuvioTheme.spacing.sm)
                ) {
                    items(reportSteps(report), key = { it.status.key }) { step ->
                        SettingsActionRow(
                            title = reportStatusText(step.status, report.fixedIn.takeIf { step.status == ReportStatus.Fixed }),
                            subtitle = null,
                            value = step.atMs?.let { reportDay(context, it) },
                            trailingIcon = if (step.reached) Icons.Filled.Check else null,
                            jumpAnchor = false,
                            enabled = step.reached,
                            onClick = {}
                        )
                    }
                }
            }
        }
        if (hint != null) {
            Text(text = hint, style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.extendedColors.textSecondary)
        }
        SettingsDialogActionRow {
            SettingsDialogActionButton(
                text = stringResource(R.string.action_close),
                onClick = onClose,
                primary = true,
                modifier = Modifier.focusRequester(closeButton)
            )
        }
    }
}

/** The quiet notice on Home after a status changed: one report by name, several as a count. */
@Composable
internal fun ReportStatusNotice(changed: List<SentReport>, onOpen: () -> Unit, onLater: () -> Unit) {
    val context = LocalContext.current
    val openButton = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { openButton.requestFocus() }
    }
    val single = changed.singleOrNull()
    val title = if (single != null) {
        stringResource(R.string.report_notice_title)
    } else {
        pluralStringResource(R.plurals.report_notice_many_title, changed.size, changed.size)
    }
    val message = if (single != null) {
        listOfNotNull(
            stringResource(
                R.string.report_notice_one,
                single.code,
                reportDay(context, single.timeMs),
                reportStatusText(single.status, single.fixedIn)
            ),
            stringResource(R.string.report_steps_update, BuildConfig.VERSION_NAME).takeIf { fixComesWithUpdate(single) }
        ).joinToString(" ")
    } else {
        stringResource(R.string.report_notice_many)
    }
    NuvioDialog(onDismiss = onLater, title = title, subtitle = message, solidWithoutBlur = true) {
        SettingsDialogActionRow {
            SettingsDialogActionButton(text = stringResource(R.string.report_notice_later), onClick = onLater)
            SettingsDialogActionButton(
                text = stringResource(R.string.report_notice_open),
                onClick = onOpen,
                primary = true,
                modifier = Modifier.focusRequester(openButton)
            )
        }
    }
}

@HiltViewModel
class ReportStatusNoticeViewModel @Inject constructor(private val store: SentReportsStore) : ViewModel() {
    val reports: StateFlow<List<SentReport>> = store.reports
    val checkEnabled: StateFlow<Boolean> = store.checkEnabled

    val changed: StateFlow<List<SentReport>> = combine(store.reports, store.checkEnabled) { reports, enabled ->
        if (enabled) reports.filter { it.toAnnounce } else emptyList()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _notice = MutableStateFlow<List<SentReport>?>(null)
    val notice: StateFlow<List<SentReport>?> = _notice.asStateFlow()

    private val _listOpen = MutableStateFlow(false)
    val listOpen: StateFlow<Boolean> = _listOpen.asStateFlow()

    init {
        viewModelScope.launch { store.load() }
    }

    fun show() {
        val list = changed.value
        if (list.isEmpty() || _notice.value != null) return
        _notice.value = list
        viewModelScope.launch { store.markAnnounced(list.map { it.code }.toSet()) }
    }

    fun later() {
        _notice.value = null
    }

    fun openList() {
        _notice.value = null
        _listOpen.value = true
    }

    fun closeList() {
        _listOpen.value = false
    }

    fun markViewed(code: String) {
        viewModelScope.launch { store.markViewed(code) }
    }
}
