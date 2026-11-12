package com.nuvio.tv.data.repository

import android.content.Context
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import java.io.File

/**
 * Android keeps no stack for a plain crash, so Arc writes its own when one happens and the next report adds it once
 * Android's exit record for that crash turns up. It stays on the box until then.
 */
object ArcCrashFile {
    private const val FILE_NAME = "arc-last-crash.txt"
    private const val MAX_STACK_LINES = 250
    private const val LOG_LINES = 150
    private const val LOG_WAIT_MS = 1_500L
    private const val MATCH_BEFORE_MS = 120_000L
    private const val MATCH_AFTER_MS = 10_000L
    internal const val LOG_HEADER = "--- Arc log, last lines ---"

    @Volatile private var file: File? = null

    fun install(context: Context, playing: () -> Boolean) {
        val target = File(context.noBackupFilesDir, FILE_NAME)
        file = target
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(target, thread, error, runCatching(playing).getOrDefault(false)) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The saved crash written shortly before an exit Android recorded at [exitMs], without its time line. */
    fun linesFor(exitMs: Long): List<String>? = file?.let { linesFor(it, exitMs) }

    /** When the saved crash happened, for boxes where Android keeps no exit records. */
    fun savedAtMs(): Long? = file?.let { savedAtMs(it) }

    internal fun savedAtMs(target: File): Long? =
        runCatching { if (target.exists()) target.bufferedReader().use { it.readLine() } else null }.getOrNull()
            ?.removePrefix("time=")?.toLongOrNull()

    internal fun linesFor(target: File, exitMs: Long): List<String>? {
        val lines = runCatching { if (target.exists()) target.readLines() else null }.getOrNull() ?: return null
        val at = lines.firstOrNull()?.removePrefix("time=")?.toLongOrNull() ?: return null
        if (exitMs - at > MATCH_BEFORE_MS || at - exitMs > MATCH_AFTER_MS) return null
        return lines.drop(1).takeIf { it.isNotEmpty() }
    }

    internal fun lines(
        nowMs: Long,
        thread: String,
        error: Throwable,
        playing: Boolean,
        facts: List<String>,
        recent: List<String> = emptyList(),
        log: List<String> = emptyList()
    ): List<String> = buildList {
        add("time=$nowMs")
        add("Crash in thread: $thread")
        add("Playing: ${if (playing) "yes" else "no"}")
        addAll(facts)
        addAll(error.stackTraceToString().lineSequence().filter { it.isNotBlank() }.take(MAX_STACK_LINES))
        if (recent.isNotEmpty()) {
            add("--- Recent screens and playback ---")
            addAll(recent)
        }
        if (log.isNotEmpty()) {
            add(LOG_HEADER)
            addAll(log)
        }
    }

    internal fun processFacts(): List<String> {
        val runtime = Runtime.getRuntime()
        return listOf(
            "Process up: ${(SystemClock.uptimeMillis() - Process.getStartUptimeMillis()) / 1000} s",
            "Threads: ${processThreadCount()?.let { "$it total" } ?: Thread.activeCount()}",
            "Thread groups: ${threadGroups(allThreadNames())}",
            "Java heap: ${(runtime.totalMemory() - runtime.freeMemory()) shr 20} of ${runtime.maxMemory() shr 20} MB",
            "Native heap: ${Debug.getNativeHeapAllocatedSize() shr 20} MB",
            "64-bit process: ${if (Process.is64Bit()) "yes" else "no"}"
        ) + ArcSetupSize.lines()
    }

    private fun write(target: File, thread: Thread, error: Throwable, playing: Boolean) {
        val lines = lines(
            System.currentTimeMillis(), thread.name, error, playing, processFacts(),
            ArcBreadcrumbs.recent(), runCatching { ArcLogTail.read(LOG_LINES, LOG_WAIT_MS) }.getOrDefault(emptyList())
        )
        val temp = File(target.parentFile, "$FILE_NAME.tmp")
        temp.writeText(lines.joinToString("\n"))
        temp.renameTo(target)
    }

    /** Every thread in the process, native ones included; names are cut to 15 characters by the kernel. */
    internal fun allThreadNames(taskDir: File = File("/proc/self/task")): List<String> =
        taskDir.listFiles().orEmpty().mapNotNull { task ->
            runCatching { File(task, "comm").readText().trim() }.getOrNull()
        }

    internal fun threadGroups(names: List<String>): String =
        if (names.isEmpty()) "unknown" else ArcHeartbeat.pools(names)

    internal fun processThreadCount(): Int? = runCatching {
        File("/proc/self/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("Threads:") }?.substringAfter(':')?.trim()?.toInt()
        }
    }.getOrNull()
}
