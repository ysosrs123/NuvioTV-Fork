package com.nuvio.tv.data.repository

import android.content.Context
import com.nuvio.tv.core.build.IssueReports
import com.nuvio.tv.core.report.PlaybackRecorder
import com.nuvio.tv.core.report.RecorderEventLine
import com.nuvio.tv.core.report.ReportFacts
import com.nuvio.tv.data.remote.dto.AppReportCrashDto
import com.nuvio.tv.data.remote.dto.AppReportRequestDto
import com.nuvio.tv.data.remote.dto.PlaybackIssueAppDto
import com.nuvio.tv.data.remote.dto.PlaybackIssueBoxDto
import com.nuvio.tv.data.remote.dto.PlaybackIssueDeviceDto
import com.nuvio.tv.data.remote.dto.PlaybackIssueNetworkDto
import com.squareup.moshi.Moshi
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class AppReportKind(val key: String) { Bug("bug"), Idea("idea"), Crash("crash") }

data class AppReportChoices(
    val kind: AppReportKind,
    val where: String? = null,
    val note: String? = null,
    val includeLog: Boolean = false,
    val crash: AppReportCrashDto? = null
)

internal const val APP_REPORT_NOTE_MAX = 1000

internal fun buildAppReport(
    choices: AppReportChoices,
    createdAtMs: Long,
    app: PlaybackIssueAppDto,
    device: PlaybackIssueDeviceDto,
    network: PlaybackIssueNetworkDto?,
    box: PlaybackIssueBoxDto?,
    events: List<RecorderEventLine>,
    extra: Map<String, String>? = null
): AppReportRequestDto = AppReportRequestDto(
    schemaVersion = 1,
    createdAtMs = createdAtMs,
    app = app,
    device = device,
    network = network,
    box = box,
    kind = choices.kind.key,
    where = choices.where.takeIf { choices.kind == AppReportKind.Bug },
    note = ReportText.clean(choices.note, APP_REPORT_NOTE_MAX),
    logLines = if (choices.includeLog && choices.kind != AppReportKind.Crash) {
        newestWithinBudget(events.mapNotNull { ReportText.eventLine(it.text, RECORDER_LINE_MAX_CHARS) })
    } else {
        null
    },
    crash = choices.crash.takeIf { choices.kind == AppReportKind.Crash },
    extra = extra
)

@Singleton
class AppReportRepository @Inject constructor(
    private val outbox: ReportOutbox,
    private val reportFacts: ReportFacts,
    private val moshi: Moshi,
    @ApplicationContext private val context: Context
) {
    suspend fun submit(choices: AppReportChoices): Result<PlaybackIssueReceipt> = runCatching {
        if (!IssueReports.available) error("App report endpoint is not configured")
        val json = withContext(Dispatchers.IO) {
            val body = buildAppReport(
                choices = choices,
                createdAtMs = System.currentTimeMillis(),
                app = reportAppDto(),
                device = reportDeviceDto(),
                network = PlaybackReportFacts.network(context),
                box = PlaybackReportFacts.box(context),
                events = if (choices.includeLog && choices.kind != AppReportKind.Crash) PlaybackRecorder.snapshot().events else emptyList(),
                extra = reportFacts.collect()
            )
            moshi.adapter(AppReportRequestDto::class.java).toJson(body).toByteArray(Charsets.UTF_8)
        }
        outbox.deliver(ReportRoute.App, choices.kind.key, json)
    }
}
