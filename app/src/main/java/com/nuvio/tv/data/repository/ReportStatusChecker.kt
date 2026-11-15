package com.nuvio.tv.data.repository

import com.nuvio.tv.core.build.IssueReports
import com.nuvio.tv.core.player.ScreensaverController
import com.nuvio.tv.data.remote.api.ReportRelayApi
import com.nuvio.tv.data.remote.dto.ReportStatusAskDto
import com.nuvio.tv.data.remote.dto.ReportStatusRequestDto
import com.nuvio.tv.data.remote.dto.ReportStatusResponseDto
import dagger.Lazy
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal fun reportStatusResults(body: ReportStatusResponseDto?): List<ReportStatusResult> =
    body?.reports.orEmpty().mapNotNull { item ->
        val code = item.code?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val status = ReportStatus.of(item.state) ?: return@mapNotNull null
        ReportStatusResult(code, status, item.version)
    }

/**
 * Once a day, at start-up and never while something plays, asks the relay for the status of reports that are still
 * open. Only the report codes and their own keys are sent.
 */
@Singleton
class ReportStatusChecker @Inject constructor(
    private val api: Lazy<ReportRelayApi>,
    private val store: SentReportsStore,
    private val screensaverController: ScreensaverController
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        if (!IssueReports.available) return
        scope.launch {
            delay(START_DELAY_MS)
            screensaverController.playbackActive.first { !it }
            checkIfDue()
        }
    }

    internal suspend fun checkIfDue(nowMs: Long = System.currentTimeMillis()): Boolean {
        store.load()
        val reports = store.reports.value
        if (!ReportFollow.due(store.checkEnabled.value, reports, nowMs, store.lastCheckMs())) return false
        val ask = ReportFollow.toAsk(reports, nowMs).mapNotNull { report -> report.token?.let { ReportStatusAskDto(report.code, it) } }
        val results = fetch(ask) ?: return false
        store.applyStatuses(results, nowMs)
        return true
    }

    private suspend fun fetch(ask: List<ReportStatusAskDto>): List<ReportStatusResult>? = try {
        val response = api.get().status(ReportStatusRequestDto(ask))
        if (response.isSuccessful) reportStatusResults(response.body()) else null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val START_DELAY_MS = 45_000L
    }
}
