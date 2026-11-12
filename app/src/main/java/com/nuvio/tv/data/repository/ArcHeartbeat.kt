package com.nuvio.tv.data.repository

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import com.nuvio.tv.core.player.MemoryPressure
import com.nuvio.tv.core.player.PlaybackMemoryFacts
import com.nuvio.tv.core.player.thumbnail.MemoryGovernor
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A small snapshot written once a minute: an app ended by Android for a freeze or for memory gets no last chance to
 * write, so the next report reads what the last minute looked like. One file, overwritten.
 */
object ArcHeartbeat {
    private const val FILE_NAME = "arc-heartbeat.txt"
    private const val PREVIOUS_NAME = "arc-heartbeat-previous.txt"
    private const val EVERY_S = 60L
    private const val TRIMS_KEPT = 10
    private const val POOLS_SHOWN = 10
    private const val MATCH_BEFORE_MS = 150_000L
    private const val MATCH_AFTER_MS = 10_000L
    internal const val HEADER = "--- Arc last minute before the exit ---"
    private val RUNNING_NUMBER = Regex("[-_ #]?\\d+$")

    @Volatile private var file: File? = null
    @Volatile private var previous: File? = null
    private val trims = ArrayDeque<String>(TRIMS_KEPT)
    private var lastGcCount = -1L
    private var lastGcMs = -1L

    @Volatile private var playing: () -> Boolean = { false }

    fun start(context: Context, isPlaying: () -> Boolean) {
        if (file != null) return
        playing = isPlaying
        val dir = context.noBackupFilesDir
        val target = File(dir, FILE_NAME)
        val earlier = File(dir, PREVIOUS_NAME)
        if (target.exists()) runCatching { earlier.delete(); target.renameTo(earlier) }
        previous = earlier
        file = target
        val app = context.applicationContext
        ArcSetupSize.attach(runCatching { File(app.applicationInfo.dataDir) }.getOrNull())
        MemoryPressure.register(app)
        MemoryPressure.addListener(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE) { level -> noteTrim(level) }
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "arc-heartbeat").apply { isDaemon = true } }
            .scheduleWithFixedDelay({ runCatching { write(app, target) } }, EVERY_S, EVERY_S, TimeUnit.SECONDS)
    }

    /** The snapshot written within the last couple of minutes before an exit Android recorded at [exitMs]. */
    fun linesFor(exitMs: Long): List<String>? = linesFor(listOfNotNull(file, previous), exitMs)

    internal fun linesFor(targets: List<File>, exitMs: Long): List<String>? {
        for (target in targets) {
            val lines = runCatching { if (target.exists()) target.readLines() else null }.getOrNull() ?: continue
            val at = lines.firstOrNull()?.removePrefix("time=")?.toLongOrNull() ?: continue
            if (exitMs - at in 0..MATCH_BEFORE_MS || at - exitMs in 0..MATCH_AFTER_MS) {
                return listOf(HEADER) + lines.drop(1)
            }
        }
        return null
    }

    private fun noteTrim(level: Int) {
        val line = "${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())} level=$level"
        synchronized(trims) {
            if (trims.size == TRIMS_KEPT) trims.removeFirst()
            trims.addLast(line)
        }
    }

    /** Thread names without their running numbers, largest groups first. */
    internal fun pools(names: List<String>): String =
        names.groupingBy { it.replace(RUNNING_NUMBER, "").ifBlank { "unnamed" } }.eachCount()
            .entries.sortedByDescending { it.value }.take(POOLS_SHOWN)
            .joinToString(", ") { "${it.key} ${it.value}" }

    private fun threadNames(): List<String> {
        var group = Thread.currentThread().threadGroup
        while (group?.parent != null) group = group.parent
        val root = group ?: return emptyList()
        val all = arrayOfNulls<Thread>(root.activeCount() * 2 + 16)
        val n = root.enumerate(all, true)
        return all.take(n).mapNotNull { it?.name }
    }

    private fun write(context: Context, target: File) {
        val runtime = Runtime.getRuntime()
        val gcCount = Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull() ?: -1L
        val gcMs = Debug.getRuntimeStat("art.gc.gc-time")?.toLongOrNull() ?: -1L
        val gcLine = if (lastGcCount >= 0 && gcCount >= 0) {
            "GC last minute: ${gcCount - lastGcCount} runs, ${gcMs - lastGcMs} ms"
        } else {
            "GC so far: $gcCount runs, $gcMs ms"
        }
        lastGcCount = gcCount
        lastGcMs = gcMs
        val am = context.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        val swap = MemoryGovernor.readMeminfo()
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.getSystemService(PowerManager::class.java)?.currentThermalStatus?.toString() ?: "unknown"
        } else "unknown"
        val names = threadNames()
        val lines = buildList {
            add("time=${System.currentTimeMillis()}")
            add("Written: ${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())}")
            add("Process up: ${(SystemClock.uptimeMillis() - android.os.Process.getStartUptimeMillis()) / 1000} s")
            add("Threads in process: ${names.size} Java, ${ArcCrashFile.processThreadCount() ?: "unknown"} total")
            add("Java heap: ${(runtime.totalMemory() - runtime.freeMemory()) shr 20} of ${runtime.maxMemory() shr 20} MB")
            add("Native heap: ${Debug.getNativeHeapAllocatedSize() shr 20} MB")
            com.nuvio.tv.core.player.PlayerStrays.line()?.let(::add)
            add(gcLine)
            add("Box memory: ${mem.availMem shr 20} MB free, low-memory line ${mem.threshold shr 20} MB, low=${mem.lowMemory}" +
                (swap?.let { ", swap free ${it.swapFreeMb} of ${it.swapTotalMb} MB" } ?: ""))
            add("Thermal status: $thermal")
            MemoryDetail.line()?.let(::add)
            OpenFilesGuard.line()?.let(::add)
            val isPlaying = runCatching(playing).getOrDefault(false)
            add("Playing: ${if (isPlaying) "yes" else "no"}")
            if (isPlaying) addAll(runCatching { PlaybackMemoryFacts.lines() }.getOrDefault(emptyList()))
            add("Threads: ${names.size} (${pools(names)})")
            addAll(ArcSetupSize.lines())
            synchronized(trims) { if (trims.isNotEmpty()) add("Memory warnings: ${trims.joinToString("; ")}") }
            add("Recent: ${ArcBreadcrumbs.recent().takeLast(4).joinToString("; ")}")
        }
        val temp = File(target.parentFile, "$FILE_NAME.tmp")
        temp.writeText(lines.joinToString("\n"))
        temp.renameTo(target)
    }
}
