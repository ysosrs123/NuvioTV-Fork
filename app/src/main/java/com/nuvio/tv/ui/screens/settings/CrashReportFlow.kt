@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.nuvio.tv.R
import com.nuvio.tv.data.repository.AppReportChoices
import com.nuvio.tv.data.repository.AppReportKind
import com.nuvio.tv.data.repository.AppReportRepository
import com.nuvio.tv.data.repository.ArcExitReports
import com.nuvio.tv.data.repository.UnexpectedExit
import com.nuvio.tv.data.repository.UnexpectedExitKind
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.player.PlaybackIssueReportStatus
import com.nuvio.tv.ui.screens.player.ReadyToSendStep
import com.nuvio.tv.ui.screens.player.ReportEyebrow
import com.nuvio.tv.ui.screens.player.ReportHeading
import com.nuvio.tv.ui.screens.player.ReportNoteDialog
import com.nuvio.tv.ui.screens.player.ReportSentDialog
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal enum class CrashCheckAnswer { NothingFound, AndroidCannotTell }

internal fun ReportSendState.isFinished(): Boolean =
    status == PlaybackIssueReportStatus.Saved || (status == PlaybackIssueReportStatus.Sent && !code.isNullOrBlank())

@HiltViewModel
class CrashReportViewModel @Inject constructor(
    private val exits: ArcExitReports,
    private val reports: AppReportRepository
) : ViewModel() {
    private val _exit = MutableStateFlow<UnexpectedExit?>(null)
    val exit: StateFlow<UnexpectedExit?> = _exit.asStateFlow()

    private val _send = MutableStateFlow(ReportSendState())
    val send: StateFlow<ReportSendState> = _send.asStateFlow()

    private val _answer = MutableStateFlow<CrashCheckAnswer?>(null)
    internal val answer: StateFlow<CrashCheckAnswer?> = _answer.asStateFlow()

    val promptEnabled: StateFlow<Boolean> = exits.promptEnabled

    private var nothingToOffer = false

    init {
        viewModelScope.launch { exits.load() }
    }

    suspend fun prepareOffer(): UnexpectedExit? {
        if (nothingToOffer) return null
        return exits.exitToOffer().also { if (it == null) nothingToOffer = true }
    }

    fun show(exit: UnexpectedExit) {
        if (_exit.value != null) return
        exits.markOffered(exit)
        nothingToOffer = true
        _send.value = ReportSendState()
        _exit.value = exit
    }

    fun openFromSettings() {
        viewModelScope.launch {
            val found = exits.unreportedExit()
            when {
                found != null -> show(found)
                exits.androidTellsExits -> _answer.value = CrashCheckAnswer.NothingFound
                else -> _answer.value = CrashCheckAnswer.AndroidCannotTell
            }
        }
    }

    fun send(note: String, includeTrace: Boolean) {
        val exit = _exit.value ?: return
        if (_send.value.status == PlaybackIssueReportStatus.Sending) return
        _send.value = ReportSendState(PlaybackIssueReportStatus.Sending)
        viewModelScope.launch {
            val result = reports.submit(
                AppReportChoices(
                    kind = AppReportKind.Crash,
                    note = note.takeIf { it.isNotBlank() },
                    includeLog = includeTrace,
                    crash = exits.details(exit, includeTrace)
                )
            )
            result.onSuccess { exits.markReported(exit) }
            _send.value = result.fold(
                onSuccess = { it.toSendState() },
                onFailure = { ReportSendState(PlaybackIssueReportStatus.Failed, error = it.message ?: "Unable to send report") }
            )
        }
    }

    fun setPromptEnabled(enabled: Boolean) = exits.setPromptEnabled(enabled)

    fun dontAskAgain() {
        exits.setPromptEnabled(false)
        close()
    }

    fun close() {
        _exit.value = null
        _answer.value = null
        _send.value = ReportSendState()
    }
}

@StringRes
internal fun exitReasonText(kind: UnexpectedExitKind): Int = when (kind) {
    UnexpectedExitKind.Crash -> R.string.report_exit_crash
    UnexpectedExitKind.NativeCrash -> R.string.report_exit_native
    UnexpectedExitKind.NotResponding -> R.string.report_exit_not_responding
    UnexpectedExitKind.LowMemory -> R.string.report_exit_low_memory
}

@Composable
internal fun CrashPromptHost(
    canShow: Boolean,
    lastInteractionAt: StateFlow<Long>,
    viewModel: CrashReportViewModel = hiltViewModel()
) {
    val view = LocalView.current
    LaunchedEffect(canShow) {
        if (!canShow) return@LaunchedEffect
        val exit = viewModel.prepareOffer() ?: return@LaunchedEffect
        delay(HOME_SETTLE_MS)
        while (!view.hasWindowFocus() || SystemClock.elapsedRealtime() - lastInteractionAt.value < HOME_IDLE_MS) {
            delay(HOME_IDLE_POLL_MS)
        }
        viewModel.show(exit)
    }
    CrashReportDialogs(viewModel)
}

@Composable
internal fun CrashReportDialogs(viewModel: CrashReportViewModel) {
    val exit by viewModel.exit.collectAsStateWithLifecycle()
    val send by viewModel.send.collectAsStateWithLifecycle()
    exit?.let { current ->
        CrashReportFlow(
            exit = current,
            send = send,
            onSend = viewModel::send,
            onDontAsk = viewModel::dontAskAgain,
            onClose = viewModel::close
        )
    }
}

private enum class CrashStep { Offer, Ready }

@Composable
private fun CrashReportFlow(
    exit: UnexpectedExit,
    send: ReportSendState,
    onSend: (String, Boolean) -> Unit,
    onDontAsk: () -> Unit,
    onClose: () -> Unit
) {
    if (send.isFinished()) {
        ReportSentDialog(send.code.takeUnless { send.status == PlaybackIssueReportStatus.Saved }, send.followUrl, started = false, onClose = onClose)
        return
    }
    var step by remember(exit) { mutableStateOf(CrashStep.Offer) }
    var note by remember(exit) { mutableStateOf("") }
    val includeTrace = exit.hasTrace
    var noteOpen by remember(exit) { mutableStateOf(false) }
    val sending = send.status == PlaybackIssueReportStatus.Sending

    NuvioDialog(
        onDismiss = dismiss@{
            if (sending) return@dismiss
            if (step == CrashStep.Ready) step = CrashStep.Offer else onClose()
        },
        title = "",
        width = 640.dp,
        usePlatformDefaultWidth = false,
        solidWithoutBlur = true
    ) {
        when (step) {
            CrashStep.Offer -> CrashOfferStep(exit, onReport = { step = CrashStep.Ready }, onNotNow = onClose, onDontAsk = onDontAsk)
            CrashStep.Ready -> {
                ReportEyebrow()
                ReadyToSendStep(
                    playbackRowTitle = stringResource(R.string.report_exit_item),
                    playbackRowHint = stringResource(R.string.report_exit_item_hint),
                    titleLabel = null,
                    showLogRow = exit.hasTrace,
                    logTitle = stringResource(R.string.report_exit_trace),
                    logHint = stringResource(R.string.report_exit_trace_hint),
                    note = note,
                    noteHint = stringResource(R.string.report_item_note_hint),
                    onOpenNote = { noteOpen = true },
                    sending = sending,
                    failed = send.status == PlaybackIssueReportStatus.Failed,
                    error = send.error,
                    onSend = { onSend(note, includeTrace) },
                    onCancel = onClose
                )
            }
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
private fun CrashOfferStep(
    exit: UnexpectedExit,
    onReport: () -> Unit,
    onNotNow: () -> Unit,
    onDontAsk: () -> Unit
) {
    val sendButton = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { sendButton.requestFocus() }
    }
    ReportHeading(
        stringResource(R.string.report_exit_title),
        "${stringResource(exitReasonText(exit.kind))} ${stringResource(R.string.report_exit_follow)}"
    )
    SettingsDialogActionRow {
        SettingsDialogActionButton(
            text = stringResource(R.string.report_exit_send),
            onClick = onReport,
            primary = true,
            modifier = Modifier.focusRequester(sendButton)
        )
        SettingsDialogActionButton(text = stringResource(R.string.report_exit_not_now), onClick = onNotNow)
        SettingsDialogActionButton(text = stringResource(R.string.report_exit_dont_ask), onClick = onDontAsk)
    }
}

private const val HOME_SETTLE_MS = 5_000L
private const val HOME_IDLE_MS = 4_000L
private const val HOME_IDLE_POLL_MS = 500L
