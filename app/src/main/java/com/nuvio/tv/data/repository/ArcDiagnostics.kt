package com.nuvio.tv.data.repository

import android.os.Process
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** The last few screens and playback changes, kept in memory for a crash or stall report. */
object ArcBreadcrumbs {
    private const val CAPACITY = 12
    private val entries = ArrayDeque<String>(CAPACITY)

    fun note(text: String) {
        val line = "${clock()} $text"
        synchronized(entries) {
            if (entries.size == CAPACITY) entries.removeFirst()
            entries.addLast(line)
        }
    }

    fun recent(): List<String> = synchronized(entries) { entries.toList() }

    private fun clock(): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
}

/** Arc's own log lines, newest last. Android lets an app read only its own process's lines. */
object ArcLogTail {
    fun read(maxLines: Int, timeoutMs: Long): List<String> {
        val lines = ArrayList<String>()
        val process = runCatching {
            ProcessBuilder("logcat", "-d", "-v", "time", "-t", (maxLines * 2).toString(), "--pid=${Process.myPid()}")
                .redirectErrorStream(true)
                .start()
        }.getOrNull() ?: return emptyList()
        val reader = Thread {
            runCatching {
                process.inputStream.bufferedReader().useLines { seq -> seq.forEach { synchronized(lines) { lines += it } } }
            }
        }.apply { isDaemon = true; start() }
        reader.join(timeoutMs)
        process.destroy()
        return synchronized(lines) { lines.filter { it.isNotBlank() && !it.startsWith("---------") }.takeLast(maxLines) }
    }
}
