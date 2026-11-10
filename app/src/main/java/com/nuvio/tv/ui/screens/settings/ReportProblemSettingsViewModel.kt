package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.repository.AppReportChoices
import com.nuvio.tv.data.repository.AppReportRepository
import com.nuvio.tv.data.repository.PlaybackIssueReceipt
import com.nuvio.tv.data.repository.SentReport
import com.nuvio.tv.data.repository.SentReportsStore
import com.nuvio.tv.ui.screens.player.PlaybackIssueReportStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ReportSendState(
    val status: PlaybackIssueReportStatus = PlaybackIssueReportStatus.Idle,
    val code: String? = null,
    val followUrl: String? = null,
    val error: String? = null
)

internal fun PlaybackIssueReceipt.toSendState(): ReportSendState =
    if (saved) ReportSendState(PlaybackIssueReportStatus.Saved) else ReportSendState(PlaybackIssueReportStatus.Sent, id, followUrl)

@HiltViewModel
class ReportProblemSettingsViewModel @Inject constructor(
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val appReportRepository: AppReportRepository,
    sentReportsStore: SentReportsStore
) : ViewModel() {
    val recorderEnabled: StateFlow<Boolean> = playerSettingsDataStore.playerSettings
        .map { it.reportRecorderEnabled }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), true)

    val sentReports: StateFlow<List<SentReport>> = sentReportsStore.reports

    private val _send = MutableStateFlow(ReportSendState())
    val send: StateFlow<ReportSendState> = _send.asStateFlow()
    private var sendRequest = 0

    init {
        viewModelScope.launch { sentReportsStore.load() }
    }

    fun setRecorderEnabled(enabled: Boolean) {
        viewModelScope.launch { playerSettingsDataStore.setReportRecorderEnabled(enabled) }
    }

    fun resetSend() {
        sendRequest += 1
        _send.value = ReportSendState()
    }

    fun send(choices: AppReportChoices) {
        if (_send.value.status == PlaybackIssueReportStatus.Sending) return
        sendRequest += 1
        val request = sendRequest
        _send.value = ReportSendState(PlaybackIssueReportStatus.Sending)
        viewModelScope.launch {
            val result = appReportRepository.submit(choices)
            if (request != sendRequest) return@launch
            _send.value = result.fold(
                onSuccess = { it.toSendState() },
                onFailure = { ReportSendState(PlaybackIssueReportStatus.Failed, error = it.message ?: "Unable to send report") }
            )
        }
    }
}
