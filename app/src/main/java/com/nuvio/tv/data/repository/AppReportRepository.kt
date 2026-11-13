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
internal const val APP_REPORT_LOG_LINES = 400
internal const val APP_REPORT_LOG_MAX_CHARS = 60_000
private const val APP_REPORT_LOG_WAIT_MS = 1_500L
internal const val APP_REPORT_RECENT_HEADER = "--- Recent screens and playback ---"
internal const val APP_REPORT_SLOW_HEADER = "--- Slow frames by minute and screen ---"
private const val APP_REPORT_LOG_READ_LINES = 2000

/** Lines the system writes under Arc's process that say nothing about Arc and crowd out what does. */
internal fun withoutLogNoise(lines: List<String>): List<String> =
    lines.filterNot { "AmazonKeyEventLogging" in it || "Accessing hidden " in it }

/** Player events first, then the last screens and Arc's own log lines, which is all a report from Settings has when nothing was played. */
internal fun appReportLogLines(
    events: List<RecorderEventLine>,
    recent: List<String>,
    log: List<String>,
    slowFrames: List<String> = emptyList()
): List<String> = buildList {
    addAll(newestWithinBudget(events.mapNotNull { ReportText.eventLine(it.text, RECORDER_LINE_MAX_CHARS) }))
    val screens = recent.mapNotNull { ReportText.eventLine(it, RECORDER_LINE_MAX_CHARS) }
    if (screens.isNotEmpty()) {
        add(APP_REPORT_RECENT_HEADER)
        addAll(screens)
    }
    val slow = slowFrames.mapNotNull { ReportText.eventLine(it, RECORDER_LINE_MAX_CHARS) }
    if (slow.isNotEmpty()) {
        add(APP_REPORT_SLOW_HEADER)
        addAll(slow)
    }
    val own = newestWithinBudget(
        withoutLogNoise(log).takeLast(APP_REPORT_LOG_LINES).mapNotNull { ReportText.eventLine(it, RECORDER_LINE_MAX_CHARS) },
        APP_REPORT_LOG_MAX_CHARS
    )
    if (own.isNotEmpty()) {
        add(ArcCrashFile.LOG_HEADER)
        addAll(own)
    }
}

internal fun buildAppReport(
    choices: AppReportChoices,
    createdAtMs: Long,
    app: PlaybackIssueAppDto,
    device: PlaybackIssueDeviceDto,
    network: PlaybackIssueNetworkDto?,
    box: PlaybackIssueBoxDto?,
    events: List<RecorderEventLine>,
    extra: Map<String, String>? = null,
    recent: List<String> = emptyList(),
    log: List<String> = emptyList(),
    slowFrames: List<String> = emptyList()
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
        appReportLogLines(events, recent, log, slowFrames)
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
            val withLog = choices.includeLog && choices.kind == AppReportKind.Bug
            val body = buildAppReport(
                choices = choices,
                createdAtMs = System.currentTimeMillis(),
                app = reportAppDto(),
                device = reportDeviceDto(),
                network = PlaybackReportFacts.network(context),
                box = PlaybackReportFacts.box(context),
                events = if (choices.includeLog && choices.kind != AppReportKind.Crash) PlaybackRecorder.snapshot().events else emptyList(),
                extra = reportFacts.collect(),
                recent = if (withLog) ArcBreadcrumbs.recent() else emptyList(),
                log = if (withLog) runCatching { ArcLogTail.read(APP_REPORT_LOG_READ_LINES, APP_REPORT_LOG_WAIT_MS) }.getOrDefault(emptyList()) else emptyList(),
                slowFrames = if (withLog) ArcSlowFrames.lines() else emptyList()
            )
            moshi.adapter(AppReportRequestDto::class.java).toJson(body).toByteArray(Charsets.UTF_8)
        }
        outbox.deliver(ReportRoute.App, choices.kind.key, json)
    }
}
