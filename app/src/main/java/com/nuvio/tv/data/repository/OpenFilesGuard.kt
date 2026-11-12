package com.nuvio.tv.data.repository

import android.os.SystemClock
import android.system.Os
import java.io.File

/**
 * Counts what this process holds open (files, connections, picture buffers). Some TV makers load a library into
 * every app that fails above 1,024, so work that only fills caches steps back well before that.
 */
object OpenFilesGuard {
    const val HIGH = 700
    private const val DESCRIBE_FROM = 600
    private const val REFRESH_MS = 5_000L
    private const val KINDS_SHOWN = 5

    @Volatile private var lastCount = 0
    @Volatile private var lastAtMs = 0L

    fun count(): Int {
        val now = SystemClock.elapsedRealtime()
        if (lastAtMs != 0L && now - lastAtMs < REFRESH_MS) return lastCount
        lastCount = runCatching { File("/proc/self/fd").list()?.size }.getOrNull() ?: 0
        lastAtMs = now
        return lastCount
    }

    fun isHigh(): Boolean = count() >= HIGH

    /** One line for the crash snapshot once the count is worth explaining, null below that. */
    fun line(): String? {
        val names = runCatching { File("/proc/self/fd").list() }.getOrNull() ?: return null
        if (names.size < DESCRIBE_FROM) return null
        val targets = names.mapNotNull { runCatching { Os.readlink("/proc/self/fd/$it") }.getOrNull() }
        return "Open file kinds: ${names.size} (${kinds(targets)})"
    }

    internal fun kinds(targets: List<String>): String =
        targets.groupingBy(::kindOf).eachCount().entries.sortedByDescending { it.value }.take(KINDS_SHOWN)
            .joinToString(", ") { "${it.key} ${it.value}" }

    internal fun kindOf(target: String): String = when {
        target.startsWith("socket:") -> "connection"
        target.contains("dmabuf") || target.contains("dma_buf") -> "picture buffer"
        target.startsWith("anon_inode:") -> target.removePrefix("anon_inode:").trim('[', ']')
        target.startsWith("pipe:") -> "pipe"
        target.startsWith("/dev/ashmem") || target.contains("memfd:") -> "shared memory"
        target.startsWith("/dev/") -> "device"
        target.startsWith("/data/") -> "app file"
        target.startsWith("/") -> "system file"
        else -> "other"
    }
}

/** Where this process's memory sits, for the crash snapshot: the two heap lines alone leave graphics and swap out. */
internal object MemoryDetail {
    fun line(): String? = runCatching {
        val info = android.os.Debug.MemoryInfo()
        android.os.Debug.getMemoryInfo(info)
        fun mb(stat: String) = (info.getMemoryStat(stat)?.toLongOrNull() ?: -1L).let { if (it < 0) "?" else (it / 1024).toString() }
        val address = File("/proc/self/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("VmSize:") }?.filter(Char::isDigit)?.toLongOrNull()?.div(1024)
        }
        "Memory detail: graphics ${mb("summary.graphics")} MB, native ${mb("summary.native-heap")} MB, code ${mb("summary.code")} MB, " +
            "total ${mb("summary.total-pss")} MB, swapped ${mb("summary.total-swap")} MB, address space ${address ?: "?"} MB"
    }.getOrNull()
}
