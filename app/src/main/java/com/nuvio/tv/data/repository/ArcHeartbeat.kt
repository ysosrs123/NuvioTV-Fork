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
 * write, so the next report reads what the last minute looked like. One file per run, overwritten; three runs kept.
 */
object ArcHeartbeat {
    private const val FILE_NAME = "arc-heartbeat.txt"
    private const val PREVIOUS_NAME = "arc-heartbeat-previous.txt"
    private const val OLDER_NAME = "arc-heartbeat-older.txt"
    private const val EVERY_S = 60L
    private const val FIRST_AFTER_S = 15L
    private const val SAME_RUN_MAX_MS = 12L * 60 * 60 * 1000
    private const val TRIMS_KEPT = 10
    private const val POOLS_SHOWN = 10
    private const val MATCH_BEFORE_MS = 150_000L
    private const val MATCH_AFTER_MS = 10_000L
    internal const val HEADER = "--- Arc last minute before the exit ---"
    private val RUNNING_NUMBER = Regex("[-_ #]?\\d+$")

    @Volatile private var files: List<File> = emptyList()
    private val trims = ArrayDeque<String>(TRIMS_KEPT)
    private var lastGcCount = -1L
    private var lastGcMs = -1L

    @Volatile private var playing: () -> Boolean = { false }

    fun start(context: Context, isPlaying: () -> Boolean) {
        if (files.isNotEmpty()) return
        playing = isPlaying
        val rotated = RunFiles.rotate(context.noBackupFilesDir, FILE_NAME, PREVIOUS_NAME, OLDER_NAME)
        files = rotated
        val target = rotated.first()
        val app = context.applicationContext
        ArcSetupSize.attach(runCatching { File(app.applicationInfo.dataDir) }.getOrNull())
        MemoryPressure.register(app)
        MemoryPressure.addListener(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE) { level -> noteTrim(level) }
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "arc-heartbeat").apply { isDaemon = true } }
            .scheduleWithFixedDelay({ runCatching { write(app, target) } }, FIRST_AFTER_S, EVERY_S, TimeUnit.SECONDS)
    }

    /**
     * The last snapshot of the run that ended at [exitMs]: the one written by process [exitPid] when Android named it,
     * else one written within the last couple of minutes before the exit.
     */
    fun linesFor(exitMs: Long, exitPid: Int = 0): List<String>? = linesFor(files, exitMs, exitPid)

    internal fun linesFor(targets: List<File>, exitMs: Long, exitPid: Int = 0): List<String>? {
        val read = targets.map { target -> runCatching { if (target.exists()) target.readLines() else null }.getOrNull() }
        val times = read.map { lines -> lines?.firstOrNull()?.removePrefix("time=")?.toLongOrNull() }
        val samePid = RunFiles.byPid(read.map { it?.let(RunFiles::pidOf) }, exitPid)
        val index = samePid.takeIf { it >= 0 && times[it]?.let { at -> at - exitMs <= MATCH_AFTER_MS && exitMs - at <= SAME_RUN_MAX_MS } == true }
            ?: times.indexOfFirst { at -> at != null && (exitMs - at in 0..MATCH_BEFORE_MS || at - exitMs in 0..MATCH_AFTER_MS) }
        if (index < 0) return null
        val lines = read[index] ?: return null
        return listOf(HEADER) + lines.drop(1).filterNot { it.startsWith(RunFiles.PID_PREFIX) }
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
            add("${RunFiles.PID_PREFIX}${android.os.Process.myPid()}")
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
            runCatching { NativeMemoryItems.line(context, names) }.getOrNull()?.let(::add)
            OpenFilesGuard.line()?.let(::add)
            val isPlaying = runCatching(playing).getOrDefault(false)
            add("Playing: ${if (isPlaying) "yes" else "no"}")
            if (isPlaying) addAll(runCatching { PlaybackMemoryFacts.lines() }.getOrDefault(emptyList()))
            add("Threads: ${names.size} (${pools(names)})")
            addAll(ArcSetupSize.lines())
            synchronized(trims) { if (trims.isNotEmpty()) add("Memory warnings: ${trims.joinToString("; ")}") }
            add("Recent: ${ArcBreadcrumbs.recent().joinToString("; ")}")
        }
        val temp = File(target.parentFile, "$FILE_NAME.tmp")
        temp.writeText(lines.joinToString("\n"))
        temp.renameTo(target)
    }
}
