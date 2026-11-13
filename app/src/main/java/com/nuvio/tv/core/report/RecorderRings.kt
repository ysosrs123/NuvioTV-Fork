package com.nuvio.tv.core.report

internal const val RECORDER_UNKNOWN = -1

internal const val RECORDER_SAMPLE_CAPACITY = 300
internal const val RECORDER_EVENT_CAPACITY = 500
internal const val RECORDER_EVENT_DETAIL_CHARS = 240
internal const val FRAME_BUCKET_COUNT = 6

private const val LONGEST_COUNTED_GAP_MS = 3_000L
private const val RECORDER_KEPT_MINUTES_MAX = 5

internal enum class RecorderEvent(val label: String) {
    OPEN("open"),
    PLAY("play"),
    PAUSE("pause"),
    SEEK("seek"),
    TRACK("track"),
    SOUND_ROUTE("sound_route"),
    DISPLAY_MODE("display_mode"),
    NETWORK("network"),
    MEMORY_TRIM("memory_trim"),
    ERROR("error"),
    STALL("stall"),
    MARK("mark"),
    DROPPED("dropped_frames"),
    SKIPPED("skipped_frames"),
    CLOCK_JUMP("audio_clock_jump"),
    FILLER("filler"),
    REBUFFER("rebuffer"),
    TUNNEL("tunnel"),
    AUDIO_PATH("audio_path"),
    HDMI("hdmi"),
    AUDIO_DELAY("audio_delay"),
    SURROUND("surround_format"),
    UNDERRUN("audio_underrun"),
    AUDIO_CLOCK("audio_clock"),
    WRITE_STALL("audio_write_stall"),
    AUDIO_CLOCK_GAP("audio_clock_gap"),
    DISPLAY_CHANGE("display_change"),
    HDMI_PLUG("hdmi_plug"),
    AUDIO_OUTPUT("audio_output")
}

/** Columns run oldest to newest. Every Int column uses [RECORDER_UNKNOWN] for a value that was not measured. */
class RecorderSamples internal constructor(
    val count: Int,
    val coveredMs: Long,
    val ageMs: LongArray,
    val readyAheadMs: IntArray,
    val droppedFrames: IntArray,
    val audioUnderruns: IntArray,
    val ownCpuTenths: IntArray,
    val rssKb: IntArray,
    val javaHeapKb: IntArray,
    val nativeHeapKb: IntArray,
    val memAvailableKb: IntArray,
    val bufferCapKb: IntArray,
    val thermalStatus: ByteArray,
    val loaderActive: ByteArray,
    val tunnelled: ByteArray,
    val playing: ByteArray,
    val skippedFrames: IntArray = IntArray(count) { RECORDER_UNKNOWN },
    val frameLeadUs: IntArray = IntArray(count) { RECORDER_UNKNOWN },
    val audioJitterAvgMs: IntArray = IntArray(count) { RECORDER_UNKNOWN },
    val audioJitterMaxMs: IntArray = IntArray(count) { RECORDER_UNKNOWN },
    val fillerCount: IntArray = IntArray(count) { RECORDER_UNKNOWN },
    val audioPositionMs: IntArray = IntArray(count) { RECORDER_UNKNOWN },
    val speedPercent: IntArray = IntArray(count) { RECORDER_UNKNOWN },
    val connections: IntArray = IntArray(count) { RECORDER_UNKNOWN },
    val watchedMs: IntArray = IntArray(count) { RECORDER_UNKNOWN },
    val swapFreeKb: IntArray = IntArray(count) { RECORDER_UNKNOWN },
    val gcCount: IntArray = IntArray(count) { RECORDER_UNKNOWN },
    val gcMs: IntArray = IntArray(count) { RECORDER_UNKNOWN },
    val gcBlockingMs: IntArray = IntArray(count) { RECORDER_UNKNOWN },
    val audioQueueMs: IntArray = IntArray(count) { RECORDER_UNKNOWN },
    val busyFlags: IntArray = IntArray(count) { RECORDER_UNKNOWN },
    val subtitleCues: IntArray = IntArray(count) { RECORDER_UNKNOWN },
    val subtitlePictureCues: IntArray = IntArray(count) { RECORDER_UNKNOWN },
    val audioHeadMs: IntArray = IntArray(count) { RECORDER_UNKNOWN },
    val audioLatencyMs: IntArray = IntArray(count) { RECORDER_UNKNOWN },
    val audioClockSource: IntArray = IntArray(count) { RECORDER_UNKNOWN },
    val audioWriteStalls: IntArray = IntArray(count) { RECORDER_UNKNOWN },
    /** [FRAME_BUCKET_COUNT] counts per sample, flat; null outside a recording. */
    val frameBuckets: IntArray? = null
) {
    /** The length recorded no longer ago than [maxAgeMs]: gaps between samples are not counted. */
    fun coveredMsWithin(maxAgeMs: Long): Long {
        var covered = 0L
        for (i in 1 until ageMs.size) {
            if (ageMs[i - 1] > maxAgeMs) continue
            val gap = ageMs[i - 1] - ageMs[i]
            if (gap in 1..LONGEST_COUNTED_GAP_MS) covered += gap
        }
        return covered
    }
}

/** Whole minutes for the viewer, never rounded up past what is kept; anything under a minute reads as one. */
internal fun recorderKeptMinutes(coveredMs: Long): Int? =
    if (coveredMs <= 0L) null else (coveredMs / 60_000L).toInt().coerceIn(1, RECORDER_KEPT_MINUTES_MAX)

internal class SampleRing(val capacity: Int = RECORDER_SAMPLE_CAPACITY, withFrameBuckets: Boolean = false) {
    private val skippedFrames = IntArray(capacity)
    private val frameLeadUs = IntArray(capacity)
    private val audioJitterAvgMs = IntArray(capacity)
    private val audioJitterMaxMs = IntArray(capacity)
    private val fillerCount = IntArray(capacity)
    private val audioPositionMs = IntArray(capacity)
    private val speedPercent = IntArray(capacity)
    private val connections = IntArray(capacity)
    private val watchedMs = IntArray(capacity)
    private val swapFreeKb = IntArray(capacity)
    private val gcCount = IntArray(capacity)
    private val gcMs = IntArray(capacity)
    private val gcBlockingMs = IntArray(capacity)
    private val audioQueueMs = IntArray(capacity)
    private val busyFlags = IntArray(capacity)
    private val subtitleCues = IntArray(capacity)
    private val subtitlePictureCues = IntArray(capacity)
    private val audioHeadMs = IntArray(capacity)
    private val audioLatencyMs = IntArray(capacity)
    private val audioClockSource = IntArray(capacity)
    private val audioWriteStalls = IntArray(capacity)
    private val frameBuckets = if (withFrameBuckets) IntArray(capacity * FRAME_BUCKET_COUNT) else null
    private val timeMs = LongArray(capacity)
    private val readyAheadMs = IntArray(capacity)
    private val droppedFrames = IntArray(capacity)
    private val audioUnderruns = IntArray(capacity)
    private val ownCpuTenths = IntArray(capacity)
    private val rssKb = IntArray(capacity)
    private val javaHeapKb = IntArray(capacity)
    private val nativeHeapKb = IntArray(capacity)
    private val memAvailableKb = IntArray(capacity)
    private val bufferCapKb = IntArray(capacity)
    private val thermalStatus = ByteArray(capacity)
    private val loaderActive = ByteArray(capacity)
    private val tunnelled = ByteArray(capacity)
    private val playing = ByteArray(capacity)
    private var next = 0
    private var count = 0

    fun add(
        timeMs: Long,
        readyAheadMs: Int,
        droppedFrames: Int,
        audioUnderruns: Int,
        ownCpuTenths: Int,
        rssKb: Int,
        javaHeapKb: Int,
        nativeHeapKb: Int,
        memAvailableKb: Int,
        bufferCapKb: Int,
        thermalStatus: Int,
        loaderActive: Int,
        tunnelled: Int,
        playing: Int,
        skippedFrames: Int = RECORDER_UNKNOWN,
        frameLeadUs: Int = RECORDER_UNKNOWN,
        audioJitterAvgMs: Int = RECORDER_UNKNOWN,
        audioJitterMaxMs: Int = RECORDER_UNKNOWN,
        fillerCount: Int = RECORDER_UNKNOWN,
        audioPositionMs: Int = RECORDER_UNKNOWN,
        speedPercent: Int = RECORDER_UNKNOWN,
        connections: Int = RECORDER_UNKNOWN,
        watchedMs: Int = RECORDER_UNKNOWN,
        swapFreeKb: Int = RECORDER_UNKNOWN,
        gcCount: Int = RECORDER_UNKNOWN,
        gcMs: Int = RECORDER_UNKNOWN,
        gcBlockingMs: Int = RECORDER_UNKNOWN,
        audioQueueMs: Int = RECORDER_UNKNOWN,
        busyFlags: Int = RECORDER_UNKNOWN,
        subtitleCues: Int = RECORDER_UNKNOWN,
        subtitlePictureCues: Int = RECORDER_UNKNOWN,
        audioHeadMs: Int = RECORDER_UNKNOWN,
        audioLatencyMs: Int = RECORDER_UNKNOWN,
        audioClockSource: Int = RECORDER_UNKNOWN,
        audioWriteStalls: Int = RECORDER_UNKNOWN,
        frameBuckets: IntArray? = null
    ) {
        val slot = next
        this.audioHeadMs[slot] = audioHeadMs
        this.audioLatencyMs[slot] = audioLatencyMs
        this.audioClockSource[slot] = audioClockSource
        this.audioWriteStalls[slot] = audioWriteStalls
        this.skippedFrames[slot] = skippedFrames
        this.frameLeadUs[slot] = frameLeadUs
        this.audioJitterAvgMs[slot] = audioJitterAvgMs
        this.audioJitterMaxMs[slot] = audioJitterMaxMs
        this.fillerCount[slot] = fillerCount
        this.audioPositionMs[slot] = audioPositionMs
        this.speedPercent[slot] = speedPercent
        this.connections[slot] = connections
        this.watchedMs[slot] = watchedMs
        this.swapFreeKb[slot] = swapFreeKb
        this.gcCount[slot] = gcCount
        this.gcMs[slot] = gcMs
        this.gcBlockingMs[slot] = gcBlockingMs
        this.audioQueueMs[slot] = audioQueueMs
        this.busyFlags[slot] = busyFlags
        this.subtitleCues[slot] = subtitleCues
        this.subtitlePictureCues[slot] = subtitlePictureCues
        this.frameBuckets?.let { store ->
            for (i in 0 until FRAME_BUCKET_COUNT) {
                store[slot * FRAME_BUCKET_COUNT + i] = frameBuckets?.getOrNull(i) ?: 0
            }
        }
        this.timeMs[slot] = timeMs
        this.readyAheadMs[slot] = readyAheadMs
        this.droppedFrames[slot] = droppedFrames
        this.audioUnderruns[slot] = audioUnderruns
        this.ownCpuTenths[slot] = ownCpuTenths
        this.rssKb[slot] = rssKb
        this.javaHeapKb[slot] = javaHeapKb
        this.nativeHeapKb[slot] = nativeHeapKb
        this.memAvailableKb[slot] = memAvailableKb
        this.bufferCapKb[slot] = bufferCapKb
        this.thermalStatus[slot] = thermalStatus.toByte()
        this.loaderActive[slot] = loaderActive.toByte()
        this.tunnelled[slot] = tunnelled.toByte()
        this.playing[slot] = playing.toByte()
        next = (slot + 1) % capacity
        if (count < capacity) count += 1
    }

    fun snapshot(nowMs: Long): RecorderSamples {
        val first = (next - count + capacity) % capacity
        val ages = LongArray(count) { (nowMs - timeMs[(first + it) % capacity]).coerceAtLeast(0L) }
        var covered = 0L
        for (i in 1 until count) {
            val gap = ages[i - 1] - ages[i]
            if (gap in 1..LONGEST_COUNTED_GAP_MS) covered += gap
        }
        fun ints(source: IntArray) = IntArray(count) { source[(first + it) % capacity] }
        fun bytes(source: ByteArray) = ByteArray(count) { source[(first + it) % capacity] }
        return RecorderSamples(
            count = count,
            coveredMs = covered,
            ageMs = ages,
            readyAheadMs = ints(readyAheadMs),
            droppedFrames = ints(droppedFrames),
            audioUnderruns = ints(audioUnderruns),
            ownCpuTenths = ints(ownCpuTenths),
            rssKb = ints(rssKb),
            javaHeapKb = ints(javaHeapKb),
            nativeHeapKb = ints(nativeHeapKb),
            memAvailableKb = ints(memAvailableKb),
            bufferCapKb = ints(bufferCapKb),
            thermalStatus = bytes(thermalStatus),
            loaderActive = bytes(loaderActive),
            tunnelled = bytes(tunnelled),
            playing = bytes(playing),
            skippedFrames = ints(skippedFrames),
            frameLeadUs = ints(frameLeadUs),
            audioJitterAvgMs = ints(audioJitterAvgMs),
            audioJitterMaxMs = ints(audioJitterMaxMs),
            fillerCount = ints(fillerCount),
            audioPositionMs = ints(audioPositionMs),
            speedPercent = ints(speedPercent),
            connections = ints(connections),
            watchedMs = ints(watchedMs),
            swapFreeKb = ints(swapFreeKb),
            gcCount = ints(gcCount),
            gcMs = ints(gcMs),
            gcBlockingMs = ints(gcBlockingMs),
            audioQueueMs = ints(audioQueueMs),
            busyFlags = ints(busyFlags),
            subtitleCues = ints(subtitleCues),
            subtitlePictureCues = ints(subtitlePictureCues),
            audioHeadMs = ints(audioHeadMs),
            audioLatencyMs = ints(audioLatencyMs),
            audioClockSource = ints(audioClockSource),
            audioWriteStalls = ints(audioWriteStalls),
            frameBuckets = frameBuckets?.let { store ->
                IntArray(count * FRAME_BUCKET_COUNT) { store[((first + it / FRAME_BUCKET_COUNT) % capacity) * FRAME_BUCKET_COUNT + it % FRAME_BUCKET_COUNT] }
            }
        )
    }
}

/** [wallMs] and [positionMs] are the clock time and the film position when the line was written; either may be unknown. */
class RecorderEventLine internal constructor(
    val ageMs: Long,
    val label: String,
    val detail: String,
    val wallMs: Long = 0L,
    val positionMs: Long = RECORDER_UNKNOWN.toLong()
) {
    val text: String
        get() = buildString {
            append('-').append(ageMs).append("ms ").append(label)
            if (wallMs > 0L) append(" wall=").append(wallMs)
            if (positionMs >= 0L) append(" pos=").append(positionMs)
            if (detail.isNotEmpty()) append(' ').append(detail)
        }
}

internal class EventRing(val capacity: Int = RECORDER_EVENT_CAPACITY) {
    private val timeMs = LongArray(capacity)
    private val wallMs = LongArray(capacity)
    private val positionMs = LongArray(capacity)
    private val kinds = arrayOfNulls<RecorderEvent>(capacity)
    private val details = arrayOfNulls<String>(capacity)
    private var next = 0
    private var count = 0

    fun add(
        timeMs: Long,
        kind: RecorderEvent,
        detail: String,
        wallMs: Long = 0L,
        positionMs: Long = RECORDER_UNKNOWN.toLong()
    ) {
        val slot = next
        this.timeMs[slot] = timeMs
        this.wallMs[slot] = wallMs
        this.positionMs[slot] = positionMs
        kinds[slot] = kind
        details[slot] = detail.take(RECORDER_EVENT_DETAIL_CHARS)
        next = (slot + 1) % capacity
        if (count < capacity) count += 1
    }

    /** Elapsed time of the newest line when it is of [kind], else null. */
    fun lastTimeOf(kind: RecorderEvent): Long? {
        if (count == 0) return null
        val slot = (next - 1 + capacity) % capacity
        return if (kinds[slot] == kind) timeMs[slot] else null
    }

    fun replaceLast(timeMs: Long, detail: String, wallMs: Long = 0L, positionMs: Long = RECORDER_UNKNOWN.toLong()) {
        if (count == 0) return
        val slot = (next - 1 + capacity) % capacity
        this.timeMs[slot] = timeMs
        this.wallMs[slot] = wallMs
        this.positionMs[slot] = positionMs
        details[slot] = detail.take(RECORDER_EVENT_DETAIL_CHARS)
    }

    fun snapshot(nowMs: Long): List<RecorderEventLine> {
        val first = (next - count + capacity) % capacity
        return List(count) { index ->
            val slot = (first + index) % capacity
            RecorderEventLine(
                (nowMs - timeMs[slot]).coerceAtLeast(0L),
                kinds[slot]!!.label,
                details[slot].orEmpty(),
                wallMs[slot],
                positionMs[slot]
            )
        }
    }
}

/** Byte-level parsers for procfs, so a sample allocates no strings. */
internal object ProcParsers {
    private const val UTIME_TOKEN = 11
    private const val STIME_TOKEN = 12
    private const val RSS_TOKEN = 21
    private val MEM_AVAILABLE = "MemAvailable:".toByteArray(Charsets.US_ASCII)
    private val SWAP_FREE = "SwapFree:".toByteArray(Charsets.US_ASCII)

    /** Fills [out] with total CPU ticks (user plus system) and resident pages; false if the text does not parse. */
    fun parseStat(buffer: ByteArray, length: Int, out: LongArray): Boolean {
        var position = lastIndexOf(buffer, length, ')'.code.toByte())
        if (position < 0) return false
        position += 1
        var utime = -1L
        var stime = -1L
        var rss = -1L
        var token = -1
        while (position < length && token < RSS_TOKEN) {
            while (position < length && buffer[position] == SPACE) position += 1
            if (position >= length) break
            token += 1
            val start = position
            while (position < length && buffer[position] != SPACE && buffer[position] != NEWLINE) position += 1
            when (token) {
                UTIME_TOKEN -> utime = digits(buffer, start, position)
                STIME_TOKEN -> stime = digits(buffer, start, position)
                RSS_TOKEN -> rss = digits(buffer, start, position)
            }
        }
        if (utime < 0 || stime < 0 || rss < 0) return false
        out[0] = utime + stime
        out[1] = rss
        return true
    }

    fun parseMemAvailableKb(buffer: ByteArray, length: Int): Int = parseMeminfoKb(buffer, length, MEM_AVAILABLE)

    fun parseSwapFreeKb(buffer: ByteArray, length: Int): Int = parseMeminfoKb(buffer, length, SWAP_FREE)

    private fun parseMeminfoKb(buffer: ByteArray, length: Int, name: ByteArray): Int {
        var found = -1
        var i = 0
        val last = length - name.size
        while (i <= last && found < 0) {
            var matched = 0
            while (matched < name.size && buffer[i + matched] == name[matched]) matched += 1
            if (matched == name.size) found = i + name.size
            i += 1
        }
        if (found < 0) return RECORDER_UNKNOWN
        var position = found
        while (position < length && buffer[position] == SPACE) position += 1
        val start = position
        while (position < length && buffer[position] in ZERO..NINE) position += 1
        val value = digits(buffer, start, position)
        return if (value < 0 || value > Int.MAX_VALUE) RECORDER_UNKNOWN else value.toInt()
    }

    /** Own CPU in tenths of a percent of one core over the interval; unknown when the interval is unusable. */
    fun cpuTenths(deltaTicks: Long, clockTicksPerSecond: Long, elapsedMs: Long): Int {
        if (deltaTicks < 0L || clockTicksPerSecond <= 0L || elapsedMs <= 0L) return RECORDER_UNKNOWN
        return (deltaTicks * 1_000_000L / (clockTicksPerSecond * elapsedMs)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun digits(buffer: ByteArray, start: Int, end: Int): Long {
        if (start >= end) return -1L
        var value = 0L
        for (i in start until end) {
            val byte = buffer[i]
            if (byte !in ZERO..NINE) return -1L
            value = value * 10L + (byte - ZERO)
        }
        return value
    }

    private fun lastIndexOf(buffer: ByteArray, length: Int, byte: Byte): Int {
        for (i in length - 1 downTo 0) if (buffer[i] == byte) return i
        return -1
    }

    private const val SPACE = ' '.code.toByte()
    private const val NEWLINE = '\n'.code.toByte()
    private const val ZERO = '0'.code.toByte()
    private const val NINE = '9'.code.toByte()
}
