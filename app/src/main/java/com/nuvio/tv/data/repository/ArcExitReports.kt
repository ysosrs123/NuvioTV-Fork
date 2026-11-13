package com.nuvio.tv.data.repository

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.build.IssueReports
import com.nuvio.tv.data.remote.dto.AppReportCrashDto
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.InputStream
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

enum class UnexpectedExitKind(val key: String) {
    Crash("crash"),
    NativeCrash("native_crash"),
    NotResponding("anr"),
    LowMemory("low_memory")
}

data class UnexpectedExit(
    val kind: UnexpectedExitKind,
    val reasonCode: Int,
    val importance: Int,
    val status: Int,
    val description: String?,
    val timestampMs: Long,
    val hasTrace: Boolean = false,
    val pid: Int = 0
)

internal data class ExitRecord(
    val reason: Int,
    val importance: Int,
    val status: Int,
    val description: String?,
    val timestampMs: Long,
    val pid: Int = 0
)

internal object ExitRules {
    const val RECENT_MS = 7L * 24 * 60 * 60 * 1000

    fun unexpected(record: ExitRecord): UnexpectedExit? {
        if (record.importance != ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) return null
        val kind = when (record.reason) {
            ApplicationExitInfo.REASON_CRASH -> UnexpectedExitKind.Crash
            ApplicationExitInfo.REASON_CRASH_NATIVE -> UnexpectedExitKind.NativeCrash
            ApplicationExitInfo.REASON_ANR -> UnexpectedExitKind.NotResponding
            ApplicationExitInfo.REASON_LOW_MEMORY -> UnexpectedExitKind.LowMemory
            else -> return null
        }
        return UnexpectedExit(
            kind, record.reason, record.importance, record.status, record.description, record.timestampMs, pid = record.pid
        )
    }

    /**
     * What an exit report carries when the viewer includes details: Arc's own capture first, then the facts, the last
     * snapshot and Android's trace. The facts always go, so a freeze Android kept no trace for still says which build,
     * which box and what happened in the minute before.
     */
    fun traceLines(own: List<String>, facts: List<String>, heartbeat: List<String>, android: List<String>): List<String> =
        ExitTrace.capped((own + facts + heartbeat + android).asSequence())

    /** Only Arc's very last exit is offered, and each one once. */
    fun forPrompt(records: List<ExitRecord>, handledMs: Long): UnexpectedExit? =
        records.maxByOrNull { it.timestampMs }?.let(::unexpected)?.takeIf { it.timestampMs > handledMs }

    /** Android 10 and older keep no exit records, so Arc's own crash file stands in for one. */
    fun savedCrash(savedAtMs: Long?, handledMs: Long): UnexpectedExit? =
        savedAtMs?.takeIf { it > handledMs }?.let {
            UnexpectedExit(
                UnexpectedExitKind.Crash, REASON_CRASH, ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND,
                status = 0, description = null, timestampMs = it, hasTrace = true
            )
        }

    private const val REASON_CRASH = 4

    fun forSettings(records: List<ExitRecord>, reportedMs: Long, nowMs: Long): UnexpectedExit? =
        records.sortedByDescending { it.timestampMs }
            .firstNotNullOfOrNull(::unexpected)
            ?.takeIf { it.timestampMs > reportedMs && nowMs - it.timestampMs <= RECENT_MS }
}

/** What Android kept about how Arc last ended. Nothing is read until the app asks, and nothing leaves the box without Send. */
@Singleton
class ArcExitReports @Inject constructor(@ApplicationContext private val context: Context) {
    private val prefs by lazy { context.applicationContext.getSharedPreferences("issue_reports", Context.MODE_PRIVATE) }
    private val _promptEnabled = MutableStateFlow(true)
    val promptEnabled: StateFlow<Boolean> = _promptEnabled.asStateFlow()

    val androidTellsExits: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    suspend fun load() = withContext(Dispatchers.IO) {
        _promptEnabled.value = prefs.getBoolean(KEY_PROMPT_ENABLED, true)
    }

    fun setPromptEnabled(enabled: Boolean) {
        _promptEnabled.value = enabled
        prefs.edit().putBoolean(KEY_PROMPT_ENABLED, enabled).apply()
    }

    suspend fun exitToOffer(): UnexpectedExit? = withContext(Dispatchers.IO) {
        if (!IssueReports.available || !prefs.getBoolean(KEY_PROMPT_ENABLED, true)) return@withContext null
        val handled = maxOf(prefs.getLong(KEY_OFFERED_MS, 0L), prefs.getLong(KEY_REPORTED_MS, 0L))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@withContext ExitRules.savedCrash(ArcCrashFile.savedAtMs(), handled)
        ExitRules.forPrompt(exitInfos().map { it.toRecord() }, handled)?.withTraceFlag()
    }

    suspend fun unreportedExit(): UnexpectedExit? = withContext(Dispatchers.IO) {
        if (!IssueReports.available) return@withContext null
        val reported = prefs.getLong(KEY_REPORTED_MS, 0L)
        val now = System.currentTimeMillis()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return@withContext ExitRules.savedCrash(ArcCrashFile.savedAtMs(), reported)?.takeIf { now - it.timestampMs <= ExitRules.RECENT_MS }
        }
        ExitRules.forSettings(exitInfos().map { it.toRecord() }, reported, now)?.withTraceFlag()
    }

    fun markOffered(exit: UnexpectedExit) = raise(KEY_OFFERED_MS, exit.timestampMs)

    fun markReported(exit: UnexpectedExit) = raise(KEY_REPORTED_MS, exit.timestampMs)

    suspend fun details(exit: UnexpectedExit, includeTrace: Boolean): AppReportCrashDto = withContext(Dispatchers.IO) {
        AppReportCrashDto(
            reason = exit.kind.key,
            reasonCode = exit.reasonCode,
            importance = exit.importance,
            status = exit.status,
            description = ReportText.clean(exit.description, EXIT_DESCRIPTION_MAX),
            exitedAtMs = exit.timestampMs,
            traceLines = if (includeTrace && exit.hasTrace) readTrace(exit) else null
        )
    }

    private fun raise(key: String, timestampMs: Long) {
        if (timestampMs > prefs.getLong(key, 0L)) prefs.edit().putLong(key, timestampMs).apply()
    }

    private fun exitInfos(): List<ApplicationExitInfo> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        return runCatching {
            context.getSystemService(ActivityManager::class.java)
                ?.getHistoricalProcessExitReasons(context.packageName, 0, EXIT_HISTORY)
                .orEmpty()
                .filter { it.processName == context.packageName }
        }.getOrDefault(emptyList())
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun ApplicationExitInfo.toRecord() = ExitRecord(reason, importance, status, description, timestamp, pid)

    /** The facts can always be sent, so every exit offers its details, whether or not a trace was kept. */
    private fun UnexpectedExit.withTraceFlag(): UnexpectedExit = copy(hasTrace = true)

    private fun readTrace(exit: UnexpectedExit): List<String>? {
        val facts = ExitFacts.lines(
            exit, BuildConfig.GIT_COMMIT, Build.FINGERPRINT, Build.VERSION.SECURITY_PATCH, recentExits(),
            ended = ArcBuildLog.at(exit.timestampMs),
            sender = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        )
        val heartbeat = ArcHeartbeat.linesFor(exit.timestampMs, exit.pid).orEmpty()
        val own = when (exit.kind) {
            UnexpectedExitKind.Crash -> ArcCrashFile.linesFor(exit.timestampMs).orEmpty()
            UnexpectedExitKind.NotResponding -> ArcMainWatchdog.linesFor(exit.timestampMs, exit.pid).orEmpty()
            else -> emptyList()
        }
        val android = if (exit.kind == UnexpectedExitKind.NotResponding || exit.kind == UnexpectedExitKind.NativeCrash) {
            androidTrace(exit).orEmpty()
        } else emptyList()
        return ExitRules.traceLines(own, facts, heartbeat, android).takeIf { it.isNotEmpty() }
    }

    private fun androidTrace(exit: UnexpectedExit): List<String>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val info = exitInfos().firstOrNull { it.timestamp == exit.timestampMs } ?: return null
        return runCatching {
            info.traceInputStream?.use { stream ->
                if (exit.kind == UnexpectedExitKind.NativeCrash) ExitTrace.nativeLines(stream) else ExitTrace.textLines(stream)
            }
        }.getOrNull()
    }

    private fun recentExits(): Map<UnexpectedExitKind, Int> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyMap()
        val since = System.currentTimeMillis() - ExitRules.RECENT_MS
        return exitInfos().map { it.toRecord() }.filter { it.timestampMs >= since }
            .mapNotNull(ExitRules::unexpected).groupingBy { it.kind }.eachCount()
    }

    private companion object {
        const val KEY_PROMPT_ENABLED = "exit_prompt_enabled"
        const val KEY_OFFERED_MS = "exit_offered_ms"
        const val KEY_REPORTED_MS = "exit_reported_ms"
        const val EXIT_HISTORY = 16
        const val EXIT_DESCRIPTION_MAX = 500
    }
}

internal object ExitTrace {
    const val MAX_BYTES = 200 * 1024
    private const val LINE_MAX = 2_000
    private const val RAW_TEXT_MAX = 2L * 1024 * 1024

    fun textLines(input: InputStream): List<String> =
        capped(ordered(LimitedStream(input, RAW_TEXT_MAX).bufferedReader(Charsets.UTF_8).readLines()).asSequence())

    private val THREAD_HEADER = Regex("""^"[^"]*".* tid=\d+""")
    private val IDLE_STATE = Regex(""" tid=\d+ (Waiting|TimedWaiting|Sleeping|Native|WaitingInMainSignalCatcherLoop)(\s|$)""")

    /**
     * An ANR dump with the main thread first, then threads in Arc's own code, then other busy threads, and last only
     * the header line of threads that were just waiting, so the size cap keeps what matters.
     */
    fun ordered(lines: List<String>): List<String> {
        val firstThread = lines.indexOfFirst { THREAD_HEADER.containsMatchIn(it) }
        if (firstThread < 0) return lines
        val blocks = ArrayList<List<String>>()
        var start = firstThread
        for (i in firstThread + 1..lines.size) {
            if (i == lines.size || THREAD_HEADER.containsMatchIn(lines[i])) {
                blocks += lines.subList(start, i)
                start = i
            }
        }
        val main = blocks.filter { it.first().startsWith("\"main\"") }
        val rest = blocks - main.toSet()
        val app = rest.filter { block -> block.any { "com.nuvio." in it } }
        val others = rest - app.toSet()
        val idle = others.filter { IDLE_STATE.containsMatchIn(it.first()) }
        val busy = others - idle.toSet()
        return lines.subList(0, firstThread) + (main + app + busy).flatten() + idle.map { it.first() }
    }

    /** Android 12 and newer keeps a native crash as a binary record, so only its named fields are read, never its memory. */
    fun nativeLines(input: InputStream): List<String> = capped(TombstoneRecord.lines(input.buffered()).asSequence())

    fun capped(lines: Sequence<String>): List<String> {
        val kept = ArrayList<String>()
        var used = 0
        for (line in lines) {
            val clean = cleanLine(line) ?: continue
            val size = clean.toByteArray(Charsets.UTF_8).size + 1
            if (used + size > MAX_BYTES) break
            used += size
            kept += clean
        }
        return kept
    }

    /**
     * Every line is cleaned like a recorded event, so Arc's own log lines lose media-server names too. A thread's own
     * name is kept as it is: names such as DefaultDispatcher-worker-12 looked like keys to the cleaner.
     */
    fun cleanLine(line: String): String? {
        val end = if (line.startsWith('"')) line.indexOf('"', 1) else -1
        val name = if (end > 0) line.substring(0, end + 1) else null
        if (name == null || "://" in name || '@' in name) return ReportText.eventLine(line, LINE_MAX)
        val rest = ReportText.eventLine(line.substring(end + 1), LINE_MAX).orEmpty()
        return (if (rest.isEmpty()) name else "$name $rest").take(LINE_MAX)
    }

    private class LimitedStream(private val source: InputStream, private var left: Long) : InputStream() {
        override fun read(): Int {
            if (left <= 0) return -1
            return source.read().also { if (it >= 0) left -= 1 }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (left <= 0) return -1
            return source.read(buffer, offset, minOf(length.toLong(), left).toInt()).also { if (it > 0) left -= it }
        }
    }
}

/** Reads tombstone.proto fields one at a time from the stream, so a large record is skipped over and never held in memory. */
internal object TombstoneRecord {
    private const val FIELD_TID = 6
    private const val FIELD_SIGNAL = 10
    private const val FIELD_ABORT = 14
    private const val FIELD_THREADS = 16
    private const val FIELD_MAX_BYTES = 512 * 1024
    private const val FRAMES_MAX = 96

    fun lines(input: InputStream): List<String> {
        val out = ArrayList<String>()
        val reader = Proto(input)
        var tid = 0L
        var thread: List<String>? = null
        runCatching {
            while (true) {
                val (field, wire) = reader.tag() ?: break
                when {
                    field == FIELD_TID && wire == Proto.VARINT -> tid = reader.varint()
                    field == FIELD_SIGNAL && wire == Proto.BYTES -> reader.message()?.let { signalLine(it) }?.let(out::add)
                    field == FIELD_ABORT && wire == Proto.BYTES -> reader.message()?.let { "Abort: ${String(it, Charsets.UTF_8)}" }?.let(out::add)
                    field == FIELD_THREADS && wire == Proto.BYTES && thread == null -> reader.message()?.let { threadLines(it, tid) }?.let { thread = it }
                    else -> reader.skip(wire)
                }
            }
        }
        thread?.let(out::addAll)
        return out
    }

    private fun signalLine(bytes: ByteArray): String {
        var number = 0L
        var name = ""
        var code = 0L
        var codeName = ""
        each(bytes) { reader, field, wire ->
            when {
                field == 1 && wire == Proto.VARINT -> number = reader.varint()
                field == 2 && wire == Proto.BYTES -> name = reader.text()
                field == 3 && wire == Proto.VARINT -> code = reader.varint()
                field == 4 && wire == Proto.BYTES -> codeName = reader.text()
                else -> reader.skip(wire)
            }
        }
        return "Signal $number ($name), code $code ($codeName)"
    }

    private fun threadLines(entry: ByteArray, tid: Long): List<String>? {
        var key = 0L
        var body: ByteArray? = null
        each(entry) { reader, field, wire ->
            when {
                field == 1 && wire == Proto.VARINT -> key = reader.varint()
                field == 2 && wire == Proto.BYTES -> body = reader.message()
                else -> reader.skip(wire)
            }
        }
        if (key != tid) return null
        val lines = ArrayList<String>()
        var name = ""
        val frames = ArrayList<String>()
        body?.let { thread ->
            each(thread) { reader, field, wire ->
                when {
                    field == 2 && wire == Proto.BYTES -> name = reader.text()
                    field == 4 && wire == Proto.BYTES && frames.size < FRAMES_MAX ->
                        reader.message()?.let { frames += frameLine(frames.size, it) }
                    else -> reader.skip(wire)
                }
            }
        }
        lines += "Thread: $name"
        lines += frames
        return lines
    }

    private fun frameLine(index: Int, bytes: ByteArray): String {
        var relPc = 0L
        var function = ""
        var offset = 0L
        var file = ""
        each(bytes) { reader, field, wire ->
            when {
                field == 1 && wire == Proto.VARINT -> relPc = reader.varint()
                field == 4 && wire == Proto.BYTES -> function = reader.text()
                field == 5 && wire == Proto.VARINT -> offset = reader.varint()
                field == 6 && wire == Proto.BYTES -> file = reader.text()
                else -> reader.skip(wire)
            }
        }
        val named = if (function.isBlank()) "" else " ($function+$offset)"
        return String.format(Locale.ROOT, "#%02d pc %016x  %s%s", index, relPc, file.substringAfterLast('/'), named)
    }

    private inline fun each(bytes: ByteArray, onField: (Proto, Int, Int) -> Unit) {
        val reader = Proto(ByteArrayInputStream(bytes))
        try {
            while (true) {
                val (field, wire) = reader.tag() ?: break
                onField(reader, field, wire)
            }
        } catch (_: EOFException) {
        }
    }

    private class Proto(private val input: InputStream) {
        fun tag(): Pair<Int, Int>? {
            val first = input.read()
            if (first < 0) return null
            val value = varint(first)
            return (value ushr 3).toInt() to (value and 7).toInt()
        }

        fun varint(): Long = varint(input.read())

        private fun varint(firstByte: Int): Long {
            var byte = firstByte
            var shift = 0
            var value = 0L
            while (true) {
                if (byte < 0 || shift > 63) throw EOFException()
                value = value or ((byte and 0x7f).toLong() shl shift)
                if (byte and 0x80 == 0) return value
                shift += 7
                byte = input.read()
            }
        }

        fun message(): ByteArray? {
            val length = varint()
            if (length < 0 || length > FIELD_MAX_BYTES) {
                drop(length)
                return null
            }
            val bytes = ByteArray(length.toInt())
            var read = 0
            while (read < bytes.size) {
                val count = input.read(bytes, read, bytes.size - read)
                if (count < 0) throw EOFException()
                read += count
            }
            return bytes
        }

        fun text(): String = message()?.let { String(it, Charsets.UTF_8) }.orEmpty()

        fun skip(wire: Int) {
            when (wire) {
                VARINT -> varint()
                FIXED64 -> drop(8)
                BYTES -> drop(varint())
                FIXED32 -> drop(4)
                else -> throw EOFException()
            }
        }

        private fun drop(count: Long) {
            if (count < 0) throw EOFException()
            var left = count
            while (left > 0) {
                val skipped = input.skip(left)
                if (skipped > 0) {
                    left -= skipped
                } else {
                    if (input.read() < 0) throw EOFException()
                    left -= 1
                }
            }
        }

        companion object {
            const val VARINT = 0
            const val FIXED64 = 1
            const val BYTES = 2
            const val FIXED32 = 5
        }
    }
}

/** A few lines at the top of every crash and freeze report: which build, which Android, how often Arc ended lately. */
internal object ExitFacts {
    private val SIGNALS = mapOf(
        4 to "SIGILL", 5 to "SIGTRAP", 6 to "SIGABRT", 7 to "SIGBUS", 8 to "SIGFPE", 9 to "SIGKILL", 11 to "SIGSEGV", 13 to "SIGPIPE"
    )

    fun lines(
        exit: UnexpectedExit,
        commit: String,
        fingerprint: String?,
        securityPatch: String?,
        recent: Map<UnexpectedExitKind, Int>,
        ended: ArcBuildLog.Entry? = null,
        sender: String = ""
    ): List<String> = buildList {
        add("--- Arc facts ---")
        val other = ended?.takeIf { it.commit != commit || (sender.isNotEmpty() && it.label != sender) }
        add("Build commit: ${(other?.commit ?: commit).ifBlank { "unknown" }}")
        if (other != null) add("Ended on: ${other.label}, report sent from $sender")
        add("Android build: ${fingerprint ?: "unknown"}, security patch ${securityPatch ?: "unknown"}")
        if (exit.kind == UnexpectedExitKind.NativeCrash) {
            add("Signal: ${SIGNALS[exit.status] ?: "unknown"} (${exit.status})")
        }
        if (recent.isNotEmpty()) {
            add("Unexpected exits in the last 7 days: " + UnexpectedExitKind.entries.filter { (recent[it] ?: 0) > 0 }
                .joinToString(", ") { "${it.key} ${recent[it]}" })
        }
    }
}
