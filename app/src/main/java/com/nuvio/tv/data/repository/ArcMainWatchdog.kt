package com.nuvio.tv.data.repository

import android.content.Context
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File

/**
 * Android's own dump of a freeze is taken seconds late, often after the screen thread has moved on. This checks the
 * screen thread every second and, once it has not answered for [STALL_MS], saves where it is stuck while the freeze
 * is still on, so a freeze report can show it even though Android ends the app without warning.
 */
object ArcMainWatchdog {
    private const val FILE_NAME = "arc-stalls.txt"
    private const val PREVIOUS_NAME = "arc-stalls-previous.txt"
    private const val CHECK_MS = 1_000L
    private const val STALL_MS = 2_000L
    private const val LOG_AFTER_MS = 4_000L
    private const val MAX_FRAMES = 40
    private const val KEPT = 3
    private const val LOG_LINES = 60
    private const val LOG_WAIT_MS = 1_000L
    private const val MATCH_BEFORE_MS = 60_000L
    private const val MATCH_AFTER_MS = 10_000L

    internal data class Stall(val startedAtMs: Long, val stuckMs: Long, val frames: List<String>, val log: List<String> = emptyList())

    @Volatile private var file: File? = null
    @Volatile private var previous: File? = null
    @Volatile private var answeredAt = 0L
    private val kept = ArrayList<Stall>()

    fun start(context: Context) {
        if (file != null) return
        val target = File(context.noBackupFilesDir, FILE_NAME)
        val earlier = File(context.noBackupFilesDir, PREVIOUS_NAME)
        if (target.exists()) runCatching { earlier.delete(); target.renameTo(earlier) }
        previous = earlier
        file = target
        val main = Handler(Looper.getMainLooper())
        val answer = Runnable { answeredAt = SystemClock.uptimeMillis() }
        Thread({ watch(target, main, answer) }, "arc-main-watchdog").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    /** Stalls saved within a minute before a freeze Android recorded at [exitMs], worst first. */
    fun linesFor(exitMs: Long): List<String>? = linesFor(listOfNotNull(previous, file), exitMs)

    internal fun linesFor(targets: List<File>, exitMs: Long): List<String>? {
        val text = targets.mapNotNull { runCatching { if (it.exists()) it.readText() else null }.getOrNull() }
            .joinToString("\n")
        val stalls = parse(text).filter { exitMs - it.startedAtMs <= MATCH_BEFORE_MS && it.startedAtMs - exitMs <= MATCH_AFTER_MS }
        if (stalls.isEmpty()) return null
        return stalls.sortedByDescending { it.stuckMs }.flatMapIndexed { index, stall ->
            buildList {
                add("--- Arc watchdog: screen thread stuck ${stall.stuckMs} ms (${index + 1} of ${stalls.size}) ---")
                addAll(stall.frames)
                if (stall.log.isNotEmpty()) {
                    add(ArcCrashFile.LOG_HEADER)
                    addAll(stall.log)
                }
            }
        }
    }

    internal fun format(stalls: List<Stall>): String = buildString {
        stalls.forEach { stall ->
            append("stall=").append(stall.startedAtMs).append(' ').append(stall.stuckMs).append('\n')
            stall.frames.forEach { append("f ").append(it).append('\n') }
            stall.log.forEach { append("l ").append(it).append('\n') }
        }
    }

    internal fun parse(text: String): List<Stall> {
        val stalls = ArrayList<Stall>()
        var head: Pair<Long, Long>? = null
        val frames = ArrayList<String>()
        val log = ArrayList<String>()
        fun flush() {
            head?.let { (at, ms) -> stalls += Stall(at, ms, frames.toList(), log.toList()) }
            frames.clear()
            log.clear()
        }
        text.lineSequence().forEach { line ->
            when {
                line.startsWith("stall=") -> {
                    flush()
                    val parts = line.removePrefix("stall=").split(' ')
                    head = parts.getOrNull(0)?.toLongOrNull()?.let { at -> at to (parts.getOrNull(1)?.toLongOrNull() ?: 0L) }
                }
                line.startsWith("f ") -> frames += line.substring(2)
                line.startsWith("l ") -> log += line.substring(2)
            }
        }
        flush()
        return stalls
    }

    /** Keeps the worst [KEPT] stalls of this run; a stall still going on replaces its own earlier capture. */
    internal fun keep(list: MutableList<Stall>, stall: Stall) {
        list.removeAll { it.startedAtMs == stall.startedAtMs }
        list += stall
        list.sortByDescending { it.stuckMs }
        while (list.size > KEPT) list.removeAt(list.lastIndex)
    }

    private fun watch(target: File, main: Handler, answer: Runnable) {
        while (true) {
            val askedAt = SystemClock.uptimeMillis()
            main.postAtFrontOfQueue(answer)
            var startedWallMs = 0L
            var log: List<String> = emptyList()
            while (true) {
                runCatching { Thread.sleep(CHECK_MS) }
                if (answeredAt >= askedAt) break
                val stuckMs = SystemClock.uptimeMillis() - askedAt
                if (stuckMs < STALL_MS || Debug.isDebuggerConnected()) continue
                if (startedWallMs == 0L) startedWallMs = System.currentTimeMillis() - stuckMs
                if (log.isEmpty() && stuckMs >= LOG_AFTER_MS) {
                    log = runCatching { ArcLogTail.read(LOG_LINES, LOG_WAIT_MS) }.getOrDefault(emptyList())
                }
                val frames = Looper.getMainLooper().thread.stackTrace.take(MAX_FRAMES).map { "at $it" }
                runCatching {
                    synchronized(kept) {
                        keep(kept, Stall(startedWallMs, stuckMs, frames, log))
                        val temp = File(target.parentFile, "$FILE_NAME.tmp")
                        temp.writeText(format(kept))
                        temp.renameTo(target)
                    }
                }
            }
        }
    }
}
