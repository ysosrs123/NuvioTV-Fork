package com.nuvio.tv.ui.screens.player

import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.R
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.core.report.recordingClock
import com.nuvio.tv.data.repository.PlaybackIssueViewerChoices
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.components.NuvioScrollDefaults
import com.nuvio.tv.ui.screens.account.InputField
import com.nuvio.tv.ui.screens.settings.SettingsActionRow
import com.nuvio.tv.ui.screens.settings.SettingsDialogActionButton
import com.nuvio.tv.ui.screens.settings.SettingsDialogActionRow
import com.nuvio.tv.ui.theme.NuvioTheme
import java.util.Locale

internal enum class ReportProblem(val key: String, @StringRes val label: Int, @StringRes val hint: Int? = null) {
    WontStart("wont_start", R.string.report_problem_wont_start),
    Stutter("stutter", R.string.report_problem_stutter),
    Buffering("buffering", R.string.report_problem_buffering),
    NoSound("no_sound", R.string.report_problem_no_sound),
    OutOfSync("sound_out_of_sync", R.string.report_problem_sync),
    SoundCuts("sound_cuts_out", R.string.report_problem_sound_cuts),
    Picture("picture_wrong", R.string.report_problem_picture, R.string.report_problem_picture_hint),
    Other("something_else", R.string.report_problem_other);

    companion object {
        fun offered(started: Boolean): List<ReportProblem> = if (started) entries - WontStart else listOf(WontStart, Buffering, Other)
    }
}

internal enum class ReportWhen(val key: String, @StringRes val label: Int) {
    Now("just_now", R.string.report_when_now),
    Minute("about_a_minute_ago", R.string.report_when_minute),
    Minutes("a_few_minutes_ago", R.string.report_when_minutes);

    fun next(): ReportWhen = entries[(ordinal + 1) % entries.size]
}

private enum class ReportStep { Check, What, Ready }

/** "HTTP 429" is the relay's daily limit, the one failure a viewer can do something about. */
internal fun isReportLimitError(error: String?): Boolean = error?.contains("HTTP 429") == true

@Composable
internal fun ReportProblemPanel(
    started: Boolean,
    titleLabel: String?,
    keptMinutes: Int?,
    status: PlaybackIssueReportStatus,
    reportId: String?,
    followUrl: String?,
    error: String?,
    onSend: (PlaybackIssueViewerChoices) -> Unit,
    onClose: () -> Unit,
    afterRecording: Boolean = false,
    recordingMs: Long? = null,
    recordingMarks: Int = 0,
    onRecord: (() -> Unit)? = null,
    fileName: String? = null,
    source: String? = null,
    check: PlayerErrorCard? = null,
    onTryOtherSource: (() -> Unit)? = null
) {
    if (status == PlaybackIssueReportStatus.Saved || (status == PlaybackIssueReportStatus.Sent && !reportId.isNullOrBlank())) {
        ReportSentDialog(reportId.takeUnless { status == PlaybackIssueReportStatus.Saved }, followUrl, started, onClose)
        return
    }
    val showCheck = check != null && !afterRecording && onTryOtherSource != null
    var step by remember {
        mutableStateOf(
            when {
                afterRecording -> ReportStep.Ready
                showCheck -> ReportStep.Check
                else -> ReportStep.What
            }
        )
    }
    var backFromReady by remember { mutableStateOf(false) }
    val ticked = remember { mutableStateListOf<ReportProblem>().apply { if (!started) add(ReportProblem.WontStart) } }
    var happened by remember { mutableStateOf(ReportWhen.Now) }
    var note by remember { mutableStateOf("") }
    var noteOpen by remember { mutableStateOf(false) }
    val sending = status == PlaybackIssueReportStatus.Sending

    NuvioDialog(
        onDismiss = dismiss@{
            if (sending) return@dismiss
            if (step == ReportStep.Ready) {
                backFromReady = true
                step = ReportStep.What
            } else {
                onClose()
            }
        },
        title = "",
        width = 560.dp,
        usePlatformDefaultWidth = false,
        solidWithoutBlur = true
    ) {
        ReportEyebrow()
        when (step) {
            ReportStep.Check -> WhatArcFoundStep(
                card = check!!,
                onTryOtherSource = { onTryOtherSource?.invoke() },
                onSendAnyway = { step = ReportStep.What },
                onCancel = onClose
            )
            ReportStep.What -> WhatHappenedStep(
                hint = keptMinutes?.let { pluralStringResource(R.plurals.report_panel_what_hint_kept, it, it) }
                    ?: stringResource(R.string.report_panel_what_hint),
                offered = ReportProblem.offered(started),
                ticked = ticked,
                onToggle = { if (it in ticked) ticked.remove(it) else ticked.add(it) },
                happened = happened.takeIf { started },
                onStepWhen = { happened = happened.next() },
                focusNext = backFromReady,
                onRecord = onRecord,
                onNext = { step = ReportStep.Ready }
            )
            ReportStep.Ready -> ReadyToSendStep(
                playbackRowTitle = recordingMs?.let { stringResource(R.string.report_item_recording, recordingClock(it)) }
                    ?: keptMinutes?.let { pluralStringResource(R.plurals.report_item_playback_kept, it, it) }
                    ?: stringResource(R.string.report_item_playback),
                playbackRowHint = if (recordingMs != null) {
                    pluralStringResource(R.plurals.report_item_recording_hint, recordingMarks, recordingMarks)
                } else {
                    stringResource(R.string.report_item_playback_hint)
                },
                titleLabel = titleLabel,
                fileName = fileName,
                source = source,
                note = note,
                noteHint = stringResource(R.string.report_item_note_hint),
                onOpenNote = { noteOpen = true },
                sending = sending,
                failed = status == PlaybackIssueReportStatus.Failed,
                error = error,
                onSend = {
                    if (!sending) {
                        onSend(
                            PlaybackIssueViewerChoices(
                                problems = ticked.map { it.key },
                                whenHappened = happened.key.takeIf { started },
                                note = note.takeIf { it.isNotBlank() },
                                includeLog = true,
                                checkShown = check?.cause?.key?.takeIf { showCheck }
                            )
                        )
                    }
                },
                onCancel = onClose
            )
        }
    }

    if (noteOpen) {
        ReportNoteDialog(
            current = note,
            onSave = {
                note = it
                noteOpen = false
            },
            onDismiss = { noteOpen = false }
        )
    }
}

@Composable
private fun ColumnScope.WhatArcFoundStep(
    card: PlayerErrorCard,
    onTryOtherSource: () -> Unit,
    onSendAnyway: () -> Unit,
    onCancel: () -> Unit
) {
    val tryRow = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { tryRow.requestFocus() }
    }
    val hint = when {
        card.sendAllowed -> stringResource(R.string.report_check_hint)
        card.cause == PlayerErrorCause.ProviderNotice -> stringResource(R.string.report_check_blocked_hint_notice)
        else -> stringResource(R.string.report_check_blocked_hint)
    }
    ReportHeading(stringResource(R.string.report_check_title), hint)
    Column(
        modifier = Modifier.padding(vertical = NuvioTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
    ) {
        ReportFact(
            title = card.title,
            detail = listOfNotNull(card.message, card.facts).joinToString("\n"),
            detailLines = REPORT_CHECK_DETAIL_LINES
        )
        SettingsActionRow(
            title = stringResource(R.string.report_check_try_source),
            subtitle = if (card.cause == PlayerErrorCause.ProviderNotice) stringResource(R.string.report_check_try_source_notice) else null,
            trailingIcon = null,
            jumpAnchor = false,
            onClick = onTryOtherSource,
            modifier = Modifier.focusRequester(tryRow)
        )
    }
    SettingsDialogActionRow {
        if (card.sendAllowed) {
            SettingsDialogActionButton(text = stringResource(R.string.action_cancel), onClick = onCancel)
            SettingsDialogActionButton(text = stringResource(R.string.report_check_send_anyway), onClick = onSendAnyway)
        } else {
            SettingsDialogActionButton(text = stringResource(R.string.action_close), onClick = onCancel)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ColumnScope.WhatHappenedStep(
    hint: String,
    offered: List<ReportProblem>,
    ticked: List<ReportProblem>,
    onToggle: (ReportProblem) -> Unit,
    happened: ReportWhen?,
    onStepWhen: () -> Unit,
    focusNext: Boolean,
    onRecord: (() -> Unit)?,
    onNext: () -> Unit
) {
    val firstRow = remember { FocusRequester() }
    val nextButton = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { (if (focusNext) nextButton else firstRow).requestFocus() }
    }
    ReportHeading(stringResource(R.string.report_panel_what), hint)
    CompositionLocalProvider(LocalBringIntoViewSpec provides NuvioScrollDefaults.smoothScrollSpec) {
        LazyColumn(
            modifier = Modifier.weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs),
            contentPadding = PaddingValues(vertical = NuvioTheme.spacing.sm)
        ) {
            items(offered, key = { it.key }) { problem ->
                SettingsActionRow(
                    title = stringResource(problem.label),
                    subtitle = problem.hint?.let { stringResource(it) },
                    trailingIcon = if (problem in ticked) Icons.Default.Check else null,
                    jumpAnchor = false,
                    onClick = { onToggle(problem) },
                    modifier = if (problem == offered.first()) Modifier.focusRequester(firstRow) else Modifier
                )
            }
            if (onRecord != null) {
                item(key = "record") {
                    SettingsActionRow(
                        title = stringResource(R.string.report_row_record),
                        subtitle = stringResource(R.string.report_row_record_hint),
                        trailingIcon = null,
                        jumpAnchor = false,
                        onClick = onRecord
                    )
                }
            }
            if (happened != null) {
                item(key = "when") {
                    SettingsActionRow(
                        title = stringResource(R.string.report_when),
                        subtitle = null,
                        value = stringResource(happened.label),
                        trailingIcon = null,
                        jumpAnchor = false,
                        onClick = onStepWhen
                    )
                }
            }
        }
    }
    SettingsDialogActionRow {
        SettingsDialogActionButton(
            text = stringResource(R.string.report_next),
            onClick = onNext,
            primary = true,
            modifier = Modifier.focusRequester(nextButton)
        )
    }
}

internal fun reportSourceNote(provider: String?, sourceDetail: String?): String? =
    provider?.trim()?.takeIf { it.isNotEmpty() }
        ?: sourceDetail?.substringBefore('\n')?.trim()?.takeIf { it.isNotEmpty() }

internal fun reportTitleDetail(titleLabel: String?, fileName: String?, source: String?): String =
    listOfNotNull(titleLabel, listOfNotNull(fileName, source).joinToString(" · ").takeIf { it.isNotEmpty() })
        .joinToString("\n")

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ColumnScope.ReadyToSendStep(
    playbackRowTitle: String?,
    titleLabel: String?,
    fileName: String? = null,
    source: String? = null,
    note: String,
    noteHint: String,
    focusNote: Boolean = false,
    onOpenNote: () -> Unit,
    sending: Boolean,
    failed: Boolean,
    error: String?,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    playbackRowHint: String = stringResource(R.string.report_item_playback_hint),
    showLogRow: Boolean = true,
    logTitle: String = stringResource(R.string.report_item_log),
    logHint: String = stringResource(R.string.report_item_log_hint),
    logIncluded: Boolean = true,
    onToggleLog: (() -> Unit)? = null
) {
    val sendButton = remember { FocusRequester() }
    val noteRow = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { (if (focusNote) noteRow else sendButton).requestFocus() }
    }
    ReportHeading(stringResource(R.string.report_ready_title), stringResource(R.string.report_ready_hint))
    CompositionLocalProvider(LocalBringIntoViewSpec provides NuvioScrollDefaults.smoothScrollSpec) {
        LazyColumn(
            modifier = Modifier.weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs),
            contentPadding = PaddingValues(vertical = NuvioTheme.spacing.sm)
        ) {
            item(key = "box") {
                ReportFact(
                    stringResource(R.string.report_item_box),
                    "${Build.MODEL} · Android ${Build.VERSION.RELEASE} · Arc ${BuildConfig.VERSION_NAME}"
                )
            }
            if (playbackRowTitle != null) {
                item(key = "player") {
                    ReportFact(playbackRowTitle, playbackRowHint)
                }
            }
            if (showLogRow) {
                item(key = "log") {
                    if (onToggleLog != null) {
                        SettingsActionRow(
                            title = logTitle,
                            subtitle = logHint,
                            trailingIcon = if (logIncluded) Icons.Default.Check else null,
                            jumpAnchor = false,
                            onClick = onToggleLog
                        )
                    } else {
                        ReportFact(logTitle, logHint)
                    }
                }
            }
            if (titleLabel != null) {
                item(key = "title") {
                    ReportFact(stringResource(R.string.report_item_title), reportTitleDetail(titleLabel, fileName, source))
                }
            }
            item(key = "note") {
                SettingsActionRow(
                    title = stringResource(R.string.report_item_note),
                    subtitle = note.takeIf { it.isNotBlank() } ?: noteHint,
                    jumpAnchor = false,
                    onClick = onOpenNote,
                    modifier = Modifier.focusRequester(noteRow)
                )
            }
        }
    }
    Text(
        text = stringResource(R.string.report_never_sent),
        style = MaterialTheme.typography.bodySmall,
        color = NuvioTheme.extendedColors.textSecondary
    )
    if (failed) {
        Text(
            text = stringResource(if (isReportLimitError(error)) R.string.report_failed_limit else R.string.player_report_issue_failed),
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.Error
        )
    }
    SettingsDialogActionRow {
        SettingsDialogActionButton(text = stringResource(R.string.action_cancel), onClick = { if (!sending) onCancel() })
        SettingsDialogActionButton(
            text = stringResource(
                when {
                    sending -> R.string.player_report_issue_sending_button
                    failed -> R.string.action_retry
                    else -> R.string.report_send
                }
            ),
            onClick = onSend,
            primary = true,
            modifier = Modifier.focusRequester(sendButton)
        )
    }
}

@Composable
internal fun ReportEyebrow() {
    Text(
        text = stringResource(R.string.report_panel_title).uppercase(Locale.getDefault()),
        style = MaterialTheme.typography.labelMedium,
        color = NuvioTheme.extendedColors.textSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
internal fun ReportHeading(title: String, hint: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = NuvioTheme.colors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (hint != null) {
            Text(text = hint, style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.extendedColors.textSecondary)
        }
    }
}

@Composable
private fun ReportFact(title: String, detail: String, detailLines: Int = 2) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NuvioTheme.spacing.lg, vertical = NuvioTheme.spacing.xs),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = NuvioTheme.colors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.extendedColors.textSecondary,
            maxLines = detailLines,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun ReportNoteDialog(current: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(current) }
    val saveButton = remember { FocusRequester() }
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { field.requestFocus() }
    }
    NuvioDialog(onDismiss = onDismiss, title = stringResource(R.string.report_item_note), width = 640.dp, solidWithoutBlur = true) {
        InputField(
            value = text,
            onValueChange = { if (it.length <= REPORT_NOTE_MAX) text = it },
            placeholder = stringResource(R.string.report_note_placeholder),
            imeAction = ImeAction.Done,
            onImeAction = { onSave(text.trim()) },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(field)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                        runCatching { saveButton.requestFocus() }
                        true
                    } else {
                        false
                    }
                }
        )
        SettingsDialogActionRow {
            SettingsDialogActionButton(text = stringResource(R.string.action_cancel), onClick = onDismiss)
            SettingsDialogActionButton(
                text = stringResource(R.string.action_save),
                onClick = { onSave(text.trim()) },
                primary = true,
                modifier = Modifier.focusRequester(saveButton)
            )
        }
    }
}

@Composable
internal fun ReportSentDialog(code: String?, followUrl: String?, started: Boolean, onClose: () -> Unit) {
    val closeButton = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { closeButton.requestFocus() }
    }
    val qr = remember(followUrl) {
        followUrl?.takeIf { code != null && it.startsWith("https://") }?.let { url ->
            runCatching { QrCodeGenerator.generate(url, REPORT_QR_PIXELS, margin = 1).asImageBitmap() }.getOrNull()
        }
    }
    NuvioDialog(
        onDismiss = onClose,
        title = stringResource(if (code != null) R.string.report_sent_title else R.string.report_saved_title),
        width = 560.dp,
        solidWithoutBlur = true
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (qr != null) {
                Image(
                    bitmap = qr,
                    contentDescription = stringResource(R.string.report_sent_qr_description),
                    modifier = Modifier.size(REPORT_QR_SIZE)
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
                if (code != null) {
                    Text(text = code, style = MaterialTheme.typography.headlineSmall, color = NuvioTheme.colors.TextPrimary, maxLines = 1)
                } else {
                    Text(text = stringResource(R.string.report_saved), style = MaterialTheme.typography.bodyLarge, color = NuvioTheme.colors.TextPrimary)
                }
                Text(
                    text = stringResource(if (qr != null) R.string.report_sent_phone else R.string.report_sent_no_phone),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.extendedColors.textSecondary
                )
            }
        }
        SettingsDialogActionRow {
            SettingsDialogActionButton(
                text = stringResource(if (started) R.string.report_sent_back else R.string.action_close),
                onClick = onClose,
                primary = true,
                modifier = Modifier.focusRequester(closeButton)
            )
        }
    }
}

private const val REPORT_NOTE_MAX = 1000
private const val REPORT_CHECK_DETAIL_LINES = 7
private const val REPORT_QR_PIXELS = 360
private val REPORT_QR_SIZE = 150.dp
