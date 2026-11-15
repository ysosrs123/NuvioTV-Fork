package com.nuvio.tv.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** [settled] statuses are no longer asked about; "Not caused by Arc" is, in case it later proves to be Arc's. */
enum class ReportStatus(val key: String, val settled: Boolean) {
    Sent("sent", false),
    Read("read", false),
    CauseFound("cause_found", false),
    Fixed("fixed", true),
    Outside("outside", false),
    Closed("closed", true),
    Gone("gone", true);

    companion object {
        fun of(key: String?): ReportStatus? = entries.firstOrNull { it.key == key }
    }
}

data class SentReport(
    val code: String,
    val kind: String,
    val timeMs: Long,
    val token: String? = null,
    val status: ReportStatus = ReportStatus.Sent,
    val fixedIn: String? = null,
    val seenAt: Map<ReportStatus, Long> = emptyMap(),
    val viewed: ReportStatus = ReportStatus.Sent,
    val announced: ReportStatus = ReportStatus.Sent
) {
    val followed: Boolean get() = token != null
    val isNew: Boolean get() = status != viewed && status != ReportStatus.Sent && status != ReportStatus.Gone
    val toAnnounce: Boolean get() = isNew && status != announced
}

data class ReportStatusResult(val code: String, val status: ReportStatus, val fixedIn: String? = null)

internal object SentReportsList {
    const val MAX = 20

    private const val FIELD = '\t'

    fun encode(reports: List<SentReport>): String =
        reports.joinToString("\n") { r ->
            listOf(
                r.code, r.kind, r.timeMs, r.token.orEmpty(), r.status.key, r.fixedIn.orEmpty(),
                r.seenAt.entries.joinToString(",") { "${it.key.key}:${it.value}" }, r.viewed.key, r.announced.key
            ).joinToString(FIELD.toString())
        }

    fun decode(text: String?): List<SentReport> =
        text.orEmpty().lineSequence().mapNotNull { line ->
            val parts = line.split(FIELD)
            val timeMs = parts.getOrNull(2)?.toLongOrNull()
            if (parts.size < 3 || parts[0].isBlank() || timeMs == null) return@mapNotNull null
            SentReport(
                code = parts[0],
                kind = parts[1],
                timeMs = timeMs,
                token = parts.getOrNull(3)?.takeIf { it.isNotBlank() },
                status = ReportStatus.of(parts.getOrNull(4)) ?: ReportStatus.Sent,
                fixedIn = parts.getOrNull(5)?.takeIf { it.isNotBlank() },
                seenAt = parts.getOrNull(6).orEmpty().split(',').mapNotNull { pair ->
                    val status = ReportStatus.of(pair.substringBefore(':')) ?: return@mapNotNull null
                    val at = pair.substringAfter(':', "").toLongOrNull() ?: return@mapNotNull null
                    status to at
                }.toMap(),
                viewed = ReportStatus.of(parts.getOrNull(7)) ?: ReportStatus.Sent,
                announced = ReportStatus.of(parts.getOrNull(8)) ?: ReportStatus.Sent
            )
        }.take(MAX).toList()

    fun added(reports: List<SentReport>, report: SentReport): List<SentReport> {
        val clean = report.copy(code = report.code.plain(), kind = report.kind.plain(), token = report.token?.let(::cleanToken))
        return (listOf(clean) + reports.filterNot { it.code == clean.code }).take(MAX)
    }

    private fun String.plain(): String = filterNot { it.isWhitespace() }.take(24)
}

private val TOKEN = Regex("""[A-Za-z0-9]{6,64}""")
private val FOLLOW_TOKEN = Regex("""[?&]t=([A-Za-z0-9]{6,64})(?:&|$)""")
private val VERSION = Regex("""[0-9A-Za-z.+-]{1,32}""")

internal fun cleanToken(token: String): String? = token.trim().takeIf { TOKEN.matches(it) }

/** The per-report key from the relay's follow link; it lets Arc ask for that one report's status. */
internal fun followToken(followUrl: String?): String? = followUrl?.let { FOLLOW_TOKEN.find(it)?.groupValues?.get(1) }

internal fun cleanVersion(version: String?): String? = version?.trim()?.takeIf { VERSION.matches(it) }

/** The rules for following sent reports, kept apart from Android so they can be tested. */
internal object ReportFollow {
    const val KEEP_MS = 90L * 24 * 60 * 60 * 1000
    const val CHECK_EVERY_MS = 20L * 60 * 60 * 1000

    fun toAsk(reports: List<SentReport>, nowMs: Long): List<SentReport> =
        reports.filter { it.followed && !it.status.settled && nowMs - it.timeMs < KEEP_MS }

    fun due(enabled: Boolean, reports: List<SentReport>, nowMs: Long, lastCheckMs: Long): Boolean =
        enabled && (lastCheckMs <= 0L || nowMs - lastCheckMs >= CHECK_EVERY_MS || nowMs < lastCheckMs) &&
            toAsk(reports, nowMs).isNotEmpty()

    fun applied(reports: List<SentReport>, results: List<ReportStatusResult>, nowMs: Long): List<SentReport> {
        val byCode = results.associateBy { it.code }
        return reports.map { report ->
            val result = byCode[report.code] ?: return@map report
            if (!report.followed || report.status.settled) return@map report
            val fixedIn = cleanVersion(result.fixedIn) ?: report.fixedIn
            if (result.status == report.status) return@map report.copy(fixedIn = fixedIn)
            report.copy(
                status = result.status,
                fixedIn = fixedIn,
                seenAt = if (result.status in report.seenAt) report.seenAt else report.seenAt + (result.status to nowMs)
            )
        }
    }

    fun newCount(reports: List<SentReport>): Int = reports.count { it.isNew }
}

/** Reports the relay accepted from this box, newest first: code, kind, time, the report's own key and its status. */
@Singleton
class SentReportsStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val prefs by lazy { context.applicationContext.getSharedPreferences("issue_reports", Context.MODE_PRIVATE) }
    private val mutex = Mutex()
    private val _reports = MutableStateFlow<List<SentReport>>(emptyList())
    private val _checkEnabled = MutableStateFlow(true)
    private var loaded = false

    val reports: StateFlow<List<SentReport>> = _reports.asStateFlow()
    val checkEnabled: StateFlow<Boolean> = _checkEnabled.asStateFlow()

    suspend fun load() {
        mutex.withLock { ensureLoaded() }
    }

    suspend fun add(code: String, kind: String, token: String? = null, timeMs: Long = System.currentTimeMillis()) {
        update { SentReportsList.added(it, SentReport(code, kind, timeMs, token)) }
    }

    suspend fun applyStatuses(results: List<ReportStatusResult>, nowMs: Long = System.currentTimeMillis()) {
        update { ReportFollow.applied(it, results, nowMs) }
        withContext(Dispatchers.IO) { prefs.edit().putLong(KEY_LAST_CHECK, nowMs).apply() }
    }

    suspend fun markViewed(code: String) {
        update { list -> list.map { if (it.code == code && it.viewed != it.status) it.copy(viewed = it.status, announced = it.status) else it } }
    }

    suspend fun markAnnounced(codes: Set<String>) {
        update { list -> list.map { if (it.code in codes) it.copy(announced = it.status) else it } }
    }

    suspend fun lastCheckMs(): Long = withContext(Dispatchers.IO) { prefs.getLong(KEY_LAST_CHECK, 0L) }

    suspend fun setCheckEnabled(enabled: Boolean) {
        mutex.withLock {
            ensureLoaded()
            _checkEnabled.value = enabled
            withContext(Dispatchers.IO) { prefs.edit().putBoolean(KEY_CHECK, enabled).apply() }
        }
    }

    private suspend fun update(change: (List<SentReport>) -> List<SentReport>) {
        mutex.withLock {
            ensureLoaded()
            val updated = change(_reports.value)
            if (updated == _reports.value) return
            _reports.value = updated
            withContext(Dispatchers.IO) { prefs.edit().putString(KEY, SentReportsList.encode(updated)).apply() }
        }
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        withContext(Dispatchers.IO) {
            _reports.value = SentReportsList.decode(prefs.getString(KEY, null))
            _checkEnabled.value = prefs.getBoolean(KEY_CHECK, true)
        }
        loaded = true
    }

    internal companion object {
        const val KEY = "sent_reports"
        const val KEY_CHECK = "check_my_reports"
        const val KEY_LAST_CHECK = "reports_checked_at"

        fun checkEnabledNow(context: Context): Boolean =
            context.applicationContext.getSharedPreferences("issue_reports", Context.MODE_PRIVATE).getBoolean(KEY_CHECK, true)
    }
}
