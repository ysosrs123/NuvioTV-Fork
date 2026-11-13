package com.nuvio.tv.core.report

import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import androidx.annotation.RequiresApi
import com.nuvio.tv.core.player.MemoryPressure
import com.nuvio.tv.data.repository.ReportText
import java.io.FileInputStream
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class RecorderSnapshot internal constructor(
    val enabled: Boolean,
    val samples: RecorderSamples?,
    val events: List<RecorderEventLine>,
    val marks: List<RecorderMark> = emptyList(),
    val recording: RecordingInfo? = null
)

/** The cheap extras a sample can carry; every field is [RECORDER_UNKNOWN] when it was not read. */
internal class SampleExtras(
    val skippedFrames: Int = RECORDER_UNKNOWN,
    val frameLeadUs: Int = RECORDER_UNKNOWN,
    val audioJitterAvgMs: Int = RECORDER_UNKNOWN,
    val audioJitterMaxMs: Int = RECORDER_UNKNOWN,
    val fillerCount: Int = RECORDER_UNKNOWN,
    val audioPositionMs: Int = RECORDER_UNKNOWN,
    val speedPercent: Int = RECORDER_UNKNOWN,
    val connections: Int = RECORDER_UNKNOWN,
    val audioQueueMs: Int = RECORDER_UNKNOWN,
    val busyFlags: Int = RECORDER_UNKNOWN,
    val audioHeadMs: Int = RECORDER_UNKNOWN,
    val audioLatencyMs: Int = RECORDER_UNKNOWN,
    val audioClockSource: Int = RECORDER_UNKNOWN,
    val audioWriteStalls: Int = RECORDER_UNKNOWN
)

data class RecordingStatus(
    val active: Boolean = false,
    val marks: Int = 0,
    val startedAtElapsedMs: Long = 0L,
    val finished: Boolean = false
)

data class RecordingSummary(val lengthMs: Long, val marks: Int)

private class RecordingRun(
    val startedAtElapsedMs: Long,
    val startedAtWallMs: Long
) {
    val samples = SampleRing(RECORDING_SAMPLE_CAPACITY, withFrameBuckets = true)
    val events = EventRing(RECORDING_EVENT_CAPACITY)
    val marks = RecordingMarks()
    var endedAtElapsedMs = 0L
    var end: RecordingEnd? = null
    var lastFreeCheckMs = 0L
}

/**
 * Keeps the last five minutes of playback in memory, for a report the viewer chooses to send. The player's progress
 * tick feeds it; the file reads run on the shared IO pool, one at a time, so a slow read skips a sample.
 * A recording the viewer starts keeps up to thirty minutes of the same numbers, plus their marks.
 */
internal object PlaybackRecorder {
    const val ENGINE_EXO = 1
    const val ENGINE_MPV = 2

    private const val MIN_SAMPLE_GAP_MS = 950L
    private const val LONGEST_CPU_INTERVAL_MS = 5_000L
    private const val SEEK_MERGE_MS = 2_000L
    private const val FREE_CHECK_EVERY_MS = 10_000L
    private const val LONGEST_WATCHED_GAP_MS = 3_000L
    private const val CLOCK_JUMP_MIN_MS = 30
    private val NO_EXTRAS = SampleExtras()

    private val lock = Any()
    private var samples: SampleRing? = null
    private var events: EventRing? = null
    private var appContext: Context? = null
    private var recording: RecordingRun? = null
    private var finished: RecordingRun? = null

    @Volatile private var enabled = false
    @Volatile private var listening = false
    @Volatile private var recordingOn = false
    @Volatile private var lastPositionMs = -1L
    @Volatile private var autoStop: RecordingEnd? = null
    private var lastSampleAtMs = 0L
    private var lastEngine = 0
    private var mpvPlaying: Boolean? = null
    private var mpvStalled = false
    private var seekFromMs = 0L
    @Volatile private var lastNetwork: String? = null
    @Volatile private var beganAtMs = 0L

    private var lastTunnelled: Boolean? = null
    private var lastSkipped = RECORDER_UNKNOWN
    private var lastFiller = RECORDER_UNKNOWN
    private var lastUnderruns = RECORDER_UNKNOWN
    private var lastClockSource = RECORDER_UNKNOWN
    private var lastWriteStalls = RECORDER_UNKNOWN
    private var watchedMs = 0L
    private var lastWatchedAtMs = 0L

    private val jumps = ClockJumpQueue()
    private val frameBuckets = FrameBucketCounter()
    private val frameLead = FrameLeadTracker()
    private val bucketScratch = IntArray(FRAME_BUCKET_COUNT)
    private val statusFlow = MutableStateFlow(RecordingStatus())
    val status: StateFlow<RecordingStatus> = statusFlow

    @Volatile private var thermalStatus = RECORDER_UNKNOWN
    private var thermalListener: Any? = null
    private val trimListener: (Int) -> Unit = { level -> event(RecorderEvent.MEMORY_TRIM, "level=$level") }

    private val executor = Dispatchers.IO.asExecutor()
    private val busy = AtomicBoolean(false)
    private val statBuffer = ByteArray(1024)
    private val memBuffer = ByteArray(1024)
    private val statValues = LongArray(2)
    private var lastCpuTicks = -1L
    private var lastCpuAtMs = 0L

    private var pendingTimeMs = 0L
    private var pendingReadyAheadMs = 0
    private var pendingDropped = 0
    private var pendingUnderruns = 0
    private var pendingBufferCapKb = 0
    private var pendingLoader = 0
    private var pendingTunnelled = 0
    private var pendingPlaying = 0
    private var pendingExtras = NO_EXTRAS
    private var pendingWatchedMs = 0
    private var pendingCues = RECORDER_UNKNOWN
    private var pendingPictureCues = RECORDER_UNKNOWN
    private val cues = java.util.concurrent.atomic.AtomicInteger(0)
    private val pictureCues = java.util.concurrent.atomic.AtomicInteger(0)

    private val clockTicks: Long by lazy { runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }.getOrDefault(100L) }
    private val pageKb: Long by lazy { runCatching { Os.sysconf(OsConstants._SC_PAGESIZE) / 1024L }.getOrDefault(4L) }

    private val sampleTask = Runnable {
        try {
            collectPending()
        } catch (_: Exception) {
        } finally {
            busy.set(false)
        }
    }

    fun begin(context: Context, isEnabled: Boolean) {
        appContext = context.applicationContext
        beganAtMs = SystemClock.elapsedRealtime()
        lastTunnelled = null
        lastSkipped = RECORDER_UNKNOWN
        lastFiller = RECORDER_UNKNOWN
        lastUnderruns = RECORDER_UNKNOWN
        lastClockSource = RECORDER_UNKNOWN
        lastWriteStalls = RECORDER_UNKNOWN
        watchedMs = 0L
        lastWatchedAtMs = 0L
        frameLead.reset()
        syncEnabled(isEnabled)
    }

    /** Minutes held for the playback open now, not an earlier film still in the ring; null with nothing held. */
    fun keptMinutes(): Int? {
        val now = SystemClock.elapsedRealtime()
        val held = synchronized(lock) { samples?.snapshot(now) } ?: return null
        return recorderKeptMinutes(held.coveredMsWithin(now - beganAtMs))
    }

    fun end() {
        stopListening()
        lastEngine = 0
        mpvPlaying = null
        mpvStalled = false
    }

    /** The player screen is gone: nothing recorded here can be sent from anywhere else. */
    fun playerClosed() {
        if (recordingOn) stopRecording(RecordingEnd.PLAYER_CLOSED)
        discardRecording()
    }

    /** Cheap gate for the tick, and the place a changed off-switch takes effect. */
    fun isSampleDue(isEnabled: Boolean): Boolean {
        if (!syncEnabled(isEnabled)) return false
        return SystemClock.elapsedRealtime() - lastSampleAtMs >= MIN_SAMPLE_GAP_MS
    }

    /** The film position at the last progress tick; event lines carry it. */
    fun notePosition(positionMs: Long) {
        lastPositionMs = positionMs
    }

    val isRecording: Boolean get() = recordingOn

    fun recordingElapsedMs(): Long {
        val run = synchronized(lock) { recording } ?: return 0L
        return (SystemClock.elapsedRealtime() - run.startedAtElapsedMs).coerceAtLeast(0L)
    }

    /** False when the box is already short of storage. */
    fun startRecording(): Boolean {
        val context = appContext ?: return false
        if (recordingOn) return true
        val free = runCatching { context.cacheDir.usableSpace }.getOrDefault(-1L)
        if (recordingStopReason(0L, free) != null) return false
        discardRecording()
        val run = RecordingRun(SystemClock.elapsedRealtime(), System.currentTimeMillis())
        synchronized(lock) { recording = run }
        frameBuckets.restart()
        autoStop = null
        recordingOn = true
        syncEnabled(true)
        statusFlow.value = RecordingStatus(active = true, marks = 0, startedAtElapsedMs = run.startedAtElapsedMs)
        return true
    }

    /** Adds a mark and returns how many there are now; null when nothing is recording. */
    fun mark(positionMs: Long): Int? {
        val run = synchronized(lock) { recording } ?: return null
        val now = SystemClock.elapsedRealtime()
        val wall = System.currentTimeMillis()
        val position = if (positionMs >= 0L) positionMs else lastPositionMs
        val added = synchronized(lock) { run.marks.add(wall, position, now - run.startedAtElapsedMs) }
        if (added != null) eventAt(RecorderEvent.MARK, "n=${run.marks.size}", wall, position)
        statusFlow.value = statusFlow.value.copy(marks = run.marks.size)
        return run.marks.size
    }

    fun stopRecording(end: RecordingEnd = RecordingEnd.STOPPED) {
        val run = synchronized(lock) {
            val current = recording ?: return
            recording = null
            current.end = end
            current.endedAtElapsedMs = SystemClock.elapsedRealtime()
            finished = current
            current
        }
        recordingOn = false
        statusFlow.value = RecordingStatus(active = false, marks = run.marks.size, finished = true)
    }

    /** Forgets a finished recording, after it was sent or given up. */
    fun discardRecording() {
        synchronized(lock) { finished = null }
        statusFlow.value = RecordingStatus()
    }

    /** Length and mark count of a stopped recording that is waiting to be sent. */
    fun finishedSummary(): RecordingSummary? = synchronized(lock) {
        finished?.let { RecordingSummary((it.endedAtElapsedMs - it.startedAtElapsedMs).coerceAtLeast(0L), it.marks.size) }
    }

    /** The reason a recording ended without the viewer asking, once. */
    fun takeAutoStop(): RecordingEnd? = autoStop.also { autoStop = null }

    fun frameLeadUs(totalUs: Long, count: Int): Int = frameLead.update(totalUs, count)

    /** Called for each rendered frame while a recording runs; folds the gap into a count. */
    fun onFrame() {
        frameBuckets.onFrame(System.nanoTime())
    }

    /** A subtitle change reached the screen; [picture] for picture subtitles such as PGS. */
    fun noteCues(shown: Int, picture: Boolean) {
        if (shown <= 0 || (!enabled && !recordingOn)) return
        cues.incrementAndGet()
        if (picture) pictureCues.incrementAndGet()
    }

    /** Playback thread: the audio clock moved by [sizeMs] more or less than the wall clock allowed. */
    fun noteClockJump(sizeMs: Long) {
        if (!enabled && !recordingOn) return
        jumps.offer(sizeMs.coerceIn(-100_000L, 100_000L).toInt(), System.currentTimeMillis(), lastPositionMs)
    }

    fun sample(
        engine: Int,
        readyAheadMs: Long,
        loaderActive: Int,
        droppedFrames: Int,
        audioUnderruns: Int,
        bufferCapBytes: Long,
        tunnelled: Boolean,
        playing: Boolean,
        extras: SampleExtras = NO_EXTRAS
    ) {
        if (engine != lastEngine) {
            lastEngine = engine
            event(RecorderEvent.OPEN, if (engine == ENGINE_EXO) "engine=EXO" else "engine=MPV")
        }
        noteSampleChanges(tunnelled, extras)
        noteUnderruns(audioUnderruns, extras.audioQueueMs)
        if (!busy.compareAndSet(false, true)) return
        val now = SystemClock.elapsedRealtime()
        if (playing && lastWatchedAtMs != 0L && now - lastWatchedAtMs in 1L..LONGEST_WATCHED_GAP_MS) {
            watchedMs += now - lastWatchedAtMs
        }
        lastWatchedAtMs = if (playing) now else 0L
        lastSampleAtMs = now
        pendingTimeMs = now
        pendingReadyAheadMs = readyAheadMs.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        pendingLoader = loaderActive
        pendingDropped = droppedFrames
        pendingUnderruns = audioUnderruns
        pendingBufferCapKb = if (bufferCapBytes < 0L) RECORDER_UNKNOWN else (bufferCapBytes / 1024L).toInt()
        pendingTunnelled = if (tunnelled) 1 else 0
        pendingPlaying = if (playing) 1 else 0
        pendingExtras = extras
        pendingWatchedMs = watchedMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        pendingCues = if (engine == ENGINE_EXO) cues.getAndSet(0) else RECORDER_UNKNOWN
        pendingPictureCues = if (engine == ENGINE_EXO) pictureCues.getAndSet(0) else RECORDER_UNKNOWN
        try {
            executor.execute(sampleTask)
        } catch (_: RuntimeException) {
            busy.set(false)
        }
    }

    private fun noteSampleChanges(tunnelled: Boolean, extras: SampleExtras) {
        if (!enabled && !recordingOn) return
        val previousTunnel = lastTunnelled
        lastTunnelled = tunnelled
        if (previousTunnel != null && previousTunnel != tunnelled) event(RecorderEvent.TUNNEL, "tunnelled=$tunnelled")
        if (extras.skippedFrames >= 0) {
            if (lastSkipped >= 0 && extras.skippedFrames > lastSkipped) {
                event(RecorderEvent.SKIPPED, "count=${extras.skippedFrames - lastSkipped} total=${extras.skippedFrames}")
            }
            lastSkipped = extras.skippedFrames
        }
        if (extras.fillerCount >= 0) {
            if (lastFiller >= 0 && extras.fillerCount > lastFiller) {
                event(RecorderEvent.FILLER, "count=${extras.fillerCount - lastFiller} total=${extras.fillerCount}")
            }
            lastFiller = extras.fillerCount
        }
        clockSourceChange(lastClockSource, extras.audioClockSource)?.let { event(RecorderEvent.AUDIO_CLOCK, "source=$it") }
        if (clockSourceLabel(extras.audioClockSource) != null) lastClockSource = extras.audioClockSource
        if (extras.audioWriteStalls >= 0) {
            val added = underrunsAdded(lastWriteStalls, extras.audioWriteStalls)
            lastWriteStalls = extras.audioWriteStalls
            if (added > 0) event(RecorderEvent.WRITE_STALL, "count=$added total=${extras.audioWriteStalls}")
        }
        jumps.drain { sizeMs, wallMs, positionMs ->
            if (abs(sizeMs) >= CLOCK_JUMP_MIN_MS) eventAt(RecorderEvent.CLOCK_JUMP, "ms=$sizeMs", wallMs, positionMs)
        }
    }

    /** A count that went down belongs to a new output and starts over without a line. */
    private fun noteUnderruns(total: Int, queueLowMs: Int) {
        if (!enabled && !recordingOn) return
        if (total < 0) return
        val added = underrunsAdded(lastUnderruns, total)
        lastUnderruns = total
        if (added > 0) {
            event(RecorderEvent.UNDERRUN, "count=$added total=$total" + if (queueLowMs >= 0) " queueLowMs=$queueLowMs" else "")
        }
    }

    /** Cheap when nothing records; the storage read happens at most every ten seconds. */
    fun checkRecordingLimits() {
        val run = synchronized(lock) { recording } ?: return
        val now = SystemClock.elapsedRealtime()
        var free = -1L
        if (now - run.lastFreeCheckMs >= FREE_CHECK_EVERY_MS) {
            run.lastFreeCheckMs = now
            free = runCatching { appContext?.cacheDir?.usableSpace ?: -1L }.getOrDefault(-1L)
        }
        val reason = recordingStopReason(now - run.startedAtElapsedMs, free) ?: return
        autoStop = reason
        stopRecording(reason)
    }

    fun event(kind: RecorderEvent, detail: String = "") {
        eventAt(kind, detail, System.currentTimeMillis(), lastPositionMs)
    }

    private fun eventAt(kind: RecorderEvent, detail: String, wallMs: Long, positionMs: Long) {
        if (!enabled && !recordingOn) return
        val line = ReportText.withoutServerNames(detail)
        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            events?.add(now, kind, line, wallMs, positionMs)
            val run = recording ?: return@synchronized
            run.events.add(now, kind, line, wallMs, positionMs)
        }
    }

    /** Holding the seek button sends one seek per step; those become one line. */
    fun seek(fromMs: Long, toMs: Long) {
        if (!enabled && !recordingOn) return
        val now = SystemClock.elapsedRealtime()
        val wall = System.currentTimeMillis()
        synchronized(lock) {
            val primary = events ?: recording?.events ?: return
            val previous = primary.lastTimeOf(RecorderEvent.SEEK)
            val merge = previous != null && now - previous <= SEEK_MERGE_MS
            if (!merge) seekFromMs = fromMs
            val detail = "fromMs=${if (merge) seekFromMs else fromMs} toMs=$toMs"
            for (ring in listOfNotNull(events, recording?.events)) {
                if (merge && ring.lastTimeOf(RecorderEvent.SEEK) != null) {
                    ring.replaceLast(now, detail, wall, toMs)
                } else {
                    ring.add(now, RecorderEvent.SEEK, detail, wall, toMs)
                }
            }
        }
    }

    fun noteMpvState(playing: Boolean, stalled: Boolean) {
        val previous = mpvPlaying
        mpvPlaying = playing
        if (previous != null && previous != playing) event(if (playing) RecorderEvent.PLAY else RecorderEvent.PAUSE)
        if (stalled != mpvStalled) {
            mpvStalled = stalled
            event(RecorderEvent.STALL, if (stalled) "cache_wait_start" else "cache_wait_end")
        }
    }

    fun noteNetwork(kind: String, validated: Boolean) {
        if (!listening) return
        val key = "kind=$kind validated=$validated"
        if (key == lastNetwork) return
        lastNetwork = key
        event(RecorderEvent.NETWORK, key)
    }

    fun snapshot(): RecorderSnapshot {
        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            val run = recording ?: finished
            if (run != null) {
                val ended = run.endedAtElapsedMs.takeIf { run.end != null } ?: now
                return RecorderSnapshot(
                    enabled = true,
                    samples = run.samples.snapshot(now),
                    events = run.events.snapshot(now),
                    marks = run.marks.snapshot(),
                    recording = RecordingInfo(
                        startedAtWallMs = run.startedAtWallMs,
                        lengthMs = (ended - run.startedAtElapsedMs).coerceAtLeast(0L),
                        end = run.end
                    )
                )
            }
            return RecorderSnapshot(
                enabled = enabled,
                samples = samples?.snapshot(now),
                events = events?.snapshot(now).orEmpty()
            )
        }
    }

    private fun syncEnabled(isEnabled: Boolean): Boolean {
        val want = isEnabled || recordingOn
        if (want != enabled) {
            enabled = want
            if (!want) {
                stopListening()
                synchronized(lock) {
                    samples = null
                    events = null
                }
                lastCpuTicks = -1L
            }
        }
        if (want && !listening) startListening()
        return want
    }

    private fun startListening() {
        val context = appContext ?: return
        synchronized(lock) {
            if (samples == null) samples = SampleRing()
            if (events == null) events = EventRing()
        }
        listening = true
        MemoryPressure.register(context)
        MemoryPressure.addListener(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE, trimListener)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) runCatching { registerThermal(context) }
        PlaybackOutputWatch.start(context)
    }

    private fun stopListening() {
        if (!listening) return
        listening = false
        lastNetwork = null
        MemoryPressure.removeListener(trimListener)
        PlaybackOutputWatch.stop()
        val context = appContext
        if (context != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) runCatching { unregisterThermal(context) }
        thermalStatus = RECORDER_UNKNOWN
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun registerThermal(context: Context) {
        val power = context.getSystemService(PowerManager::class.java) ?: return
        val listener = PowerManager.OnThermalStatusChangedListener { status -> thermalStatus = status }
        power.addThermalStatusListener(Executor { it.run() }, listener)
        thermalListener = listener
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun unregisterThermal(context: Context) {
        val listener = thermalListener as? PowerManager.OnThermalStatusChangedListener ?: return
        thermalListener = null
        context.getSystemService(PowerManager::class.java)?.removeThermalStatusListener(listener)
    }

    private fun collectPending() {
        val now = pendingTimeMs
        var cpu = RECORDER_UNKNOWN
        var rssKb = RECORDER_UNKNOWN
        val statLength = readInto("/proc/self/stat", statBuffer)
        if (statLength > 0 && ProcParsers.parseStat(statBuffer, statLength, statValues)) {
            if (lastCpuTicks >= 0L && now - lastCpuAtMs <= LONGEST_CPU_INTERVAL_MS) {
                cpu = ProcParsers.cpuTenths(statValues[0] - lastCpuTicks, clockTicks, now - lastCpuAtMs)
            }
            lastCpuTicks = statValues[0]
            lastCpuAtMs = now
            rssKb = (statValues[1] * pageKb).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        val runtime = Runtime.getRuntime()
        val javaKb = ((runtime.totalMemory() - runtime.freeMemory()) / 1024L).toInt()
        val nativeKb = (Debug.getNativeHeapAllocatedSize() / 1024L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val memLength = readInto("/proc/meminfo", memBuffer)
        val memAvailableKb = if (memLength > 0) ProcParsers.parseMemAvailableKb(memBuffer, memLength) else RECORDER_UNKNOWN
        val swapFreeKb = if (memLength > 0) ProcParsers.parseSwapFreeKb(memBuffer, memLength) else RECORDER_UNKNOWN
        val gcCount = runtimeStat("art.gc.gc-count")
        val gcMs = runtimeStat("art.gc.gc-time")
        val gcBlockingMs = runtimeStat("art.gc.blocking-gc-time")
        val extras = pendingExtras
        synchronized(lock) {
            samples?.add(
                timeMs = now,
                readyAheadMs = pendingReadyAheadMs,
                droppedFrames = pendingDropped,
                audioUnderruns = pendingUnderruns,
                ownCpuTenths = cpu,
                rssKb = rssKb,
                javaHeapKb = javaKb,
                nativeHeapKb = nativeKb,
                memAvailableKb = memAvailableKb,
                bufferCapKb = pendingBufferCapKb,
                thermalStatus = thermalStatus,
                loaderActive = pendingLoader,
                tunnelled = pendingTunnelled,
                playing = pendingPlaying,
                skippedFrames = extras.skippedFrames,
                frameLeadUs = extras.frameLeadUs,
                audioJitterAvgMs = extras.audioJitterAvgMs,
                audioJitterMaxMs = extras.audioJitterMaxMs,
                fillerCount = extras.fillerCount,
                audioPositionMs = extras.audioPositionMs,
                speedPercent = extras.speedPercent,
                connections = extras.connections,
                watchedMs = pendingWatchedMs,
                swapFreeKb = swapFreeKb,
                gcCount = gcCount,
                gcMs = gcMs,
                gcBlockingMs = gcBlockingMs,
                audioQueueMs = extras.audioQueueMs,
                busyFlags = extras.busyFlags,
                subtitleCues = pendingCues,
                subtitlePictureCues = pendingPictureCues,
                audioHeadMs = extras.audioHeadMs,
                audioLatencyMs = extras.audioLatencyMs,
                audioClockSource = extras.audioClockSource,
                audioWriteStalls = extras.audioWriteStalls
            )
            recording?.let { current ->
                frameBuckets.drainInto(bucketScratch)
                current.samples.add(
                    timeMs = now,
                    readyAheadMs = pendingReadyAheadMs,
                    droppedFrames = pendingDropped,
                    audioUnderruns = pendingUnderruns,
                    ownCpuTenths = cpu,
                    rssKb = rssKb,
                    javaHeapKb = javaKb,
                    nativeHeapKb = nativeKb,
                    memAvailableKb = memAvailableKb,
                    bufferCapKb = pendingBufferCapKb,
                    thermalStatus = thermalStatus,
                    loaderActive = pendingLoader,
                    tunnelled = pendingTunnelled,
                    playing = pendingPlaying,
                    skippedFrames = extras.skippedFrames,
                    frameLeadUs = extras.frameLeadUs,
                    audioJitterAvgMs = extras.audioJitterAvgMs,
                    audioJitterMaxMs = extras.audioJitterMaxMs,
                    fillerCount = extras.fillerCount,
                    audioPositionMs = extras.audioPositionMs,
                    speedPercent = extras.speedPercent,
                    connections = extras.connections,
                    watchedMs = pendingWatchedMs,
                    swapFreeKb = swapFreeKb,
                    gcCount = gcCount,
                    gcMs = gcMs,
                    gcBlockingMs = gcBlockingMs,
                    audioQueueMs = extras.audioQueueMs,
                    busyFlags = extras.busyFlags,
                    subtitleCues = pendingCues,
                    subtitlePictureCues = pendingPictureCues,
                    audioHeadMs = extras.audioHeadMs,
                    audioLatencyMs = extras.audioLatencyMs,
                    audioClockSource = extras.audioClockSource,
                    audioWriteStalls = extras.audioWriteStalls,
                    frameBuckets = bucketScratch
                )
            }
        }
    }

    private fun runtimeStat(name: String): Int =
        runCatching { Debug.getRuntimeStat(name)?.toLongOrNull() }.getOrNull()
            ?.coerceIn(0L, Int.MAX_VALUE.toLong())?.toInt() ?: RECORDER_UNKNOWN

    private fun readInto(path: String, buffer: ByteArray): Int = try {
        FileInputStream(path).use { it.read(buffer) }
    } catch (_: Exception) {
        -1
    }
}
