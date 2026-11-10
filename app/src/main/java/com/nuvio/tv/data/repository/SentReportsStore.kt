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

data class SentReport(val code: String, val kind: String, val timeMs: Long)

internal object SentReportsList {
    const val MAX = 20

    private const val FIELD = '\t'

    fun encode(reports: List<SentReport>): String =
        reports.joinToString("\n") { listOf(it.code, it.kind, it.timeMs).joinToString(FIELD.toString()) }

    fun decode(text: String?): List<SentReport> =
        text.orEmpty().lineSequence().mapNotNull { line ->
            val parts = line.split(FIELD)
            val timeMs = parts.getOrNull(2)?.toLongOrNull()
            if (parts.size == 3 && parts[0].isNotBlank() && timeMs != null) SentReport(parts[0], parts[1], timeMs) else null
        }.take(MAX).toList()

    fun added(reports: List<SentReport>, report: SentReport): List<SentReport> {
        val clean = report.copy(code = report.code.plain(), kind = report.kind.plain())
        return (listOf(clean) + reports.filterNot { it.code == clean.code }).take(MAX)
    }

    private fun String.plain(): String = filterNot { it.isWhitespace() }.take(24)
}

/** The codes of reports the relay accepted from this box, newest first. Code, kind and time only. */
@Singleton
class SentReportsStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val prefs by lazy { context.applicationContext.getSharedPreferences("issue_reports", Context.MODE_PRIVATE) }
    private val mutex = Mutex()
    private val _reports = MutableStateFlow<List<SentReport>>(emptyList())
    private var loaded = false

    val reports: StateFlow<List<SentReport>> = _reports.asStateFlow()

    suspend fun load() {
        mutex.withLock { ensureLoaded() }
    }

    suspend fun add(code: String, kind: String, timeMs: Long = System.currentTimeMillis()) {
        mutex.withLock {
            ensureLoaded()
            val updated = SentReportsList.added(_reports.value, SentReport(code, kind, timeMs))
            _reports.value = updated
            prefs.edit().putString(KEY, SentReportsList.encode(updated)).apply()
        }
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        _reports.value = withContext(Dispatchers.IO) { SentReportsList.decode(prefs.getString(KEY, null)) }
        loaded = true
    }

    private companion object {
        const val KEY = "sent_reports"
    }
}
