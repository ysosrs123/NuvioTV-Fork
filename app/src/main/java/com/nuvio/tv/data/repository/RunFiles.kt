package com.nuvio.tv.data.repository

import java.io.File

/**
 * Snapshot files kept for three runs: this one, the one before and the one before that. A freeze is often followed
 * by a restart Android or the viewer makes before Arc can offer the report, and one spare file was lost to it.
 */
internal object RunFiles {
    const val PID_PREFIX = "pid="

    /** Shifts the older runs' files along at start; returns this run's file first, then the earlier ones. */
    fun rotate(dir: File, name: String, previous: String, older: String): List<File> {
        val current = File(dir, name)
        val earlier = File(dir, previous)
        val oldest = File(dir, older)
        runCatching {
            if (earlier.exists()) {
                oldest.delete()
                earlier.renameTo(oldest)
            }
            if (current.exists()) {
                earlier.delete()
                current.renameTo(earlier)
            }
        }
        return listOf(current, earlier, oldest)
    }

    fun pidOf(lines: List<String>): Int? =
        lines.take(3).firstOrNull { it.startsWith(PID_PREFIX) }?.removePrefix(PID_PREFIX)?.trim()?.toIntOrNull()

    /** The file written by the process that ended, when Android named it; -1 when none was, or no process id is known. */
    fun byPid(pids: List<Int?>, exitPid: Int): Int = if (exitPid <= 0) -1 else pids.indexOfFirst { it == exitPid }
}
