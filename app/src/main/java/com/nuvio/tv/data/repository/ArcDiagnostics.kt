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

/** Slow frames counted per minute and screen, kept in memory so a report sent minutes later still has them. */
object ArcSlowFrames {
    private const val CAPACITY = 40
    private const val LONG_MS = 100L

    private class Bucket(val minute: Long, val screen: String) {
        var count = 0
        var long = 0
        var worstMs = 0L
    }

    private val buckets = ArrayDeque<Bucket>(CAPACITY)

    fun note(durationMs: Long, screen: String?, nowMs: Long = System.currentTimeMillis()) {
        val minute = nowMs / 60_000L
        val name = screen?.takeIf { it.isNotBlank() } ?: "unknown"
        synchronized(buckets) {
            val last = buckets.peekLast()
            val bucket = if (last != null && last.minute == minute && last.screen == name) last else {
                if (buckets.size == CAPACITY) buckets.removeFirst()
                Bucket(minute, name).also { buckets.addLast(it) }
            }
            bucket.count++
            if (durationMs > LONG_MS) bucket.long++
            if (durationMs > bucket.worstMs) bucket.worstMs = durationMs
        }
    }

    fun lines(): List<String> = synchronized(buckets) {
        val clock = SimpleDateFormat("HH:mm", Locale.US)
        buckets.map {
            "${clock.format(Date(it.minute * 60_000L))} ${it.screen}: ${it.count} slow frames, ${it.long} over $LONG_MS ms, worst ${it.worstMs} ms"
        }
    }

    internal fun clear() = synchronized(buckets) { buckets.clear() }
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
