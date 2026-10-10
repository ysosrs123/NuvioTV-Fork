package com.nuvio.tv.core.iptv

import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class RecordingStatus {
    SCHEDULED, RECORDING, DONE, PARTIAL, FAILED, CANCELLED;
    val finished: Boolean get() = this != SCHEDULED && this != RECORDING
    val holdsConnection: Boolean get() = this == SCHEDULED || this == RECORDING
}

enum class RecordingFailure {
    NO_CONNECTION, DEVICE_BUSY, SOURCE_UNAVAILABLE, CHANNEL_UNAVAILABLE, UNSUPPORTED_STREAM, ENCRYPTED_STREAM,
    NETWORK, LOW_STORAGE, STORAGE_ERROR, TIME_LIMIT, INTERRUPTED, START_BLOCKED, MISSED, STORAGE_MISSING, STORAGE_REMOVED,
}

enum class RecordingStop { USER, ENDED, TIME_LIMIT, INTERRUPTED, REMOVED }

data class RecordingOutcome(val status: RecordingStatus, val failure: RecordingFailure?)

object RecordingTransitions {
    fun allowed(from: RecordingStatus, to: RecordingStatus): Boolean = when (from) {
        RecordingStatus.SCHEDULED -> to == RecordingStatus.RECORDING || to == RecordingStatus.CANCELLED || to == RecordingStatus.FAILED
        RecordingStatus.RECORDING -> to == RecordingStatus.DONE || to == RecordingStatus.PARTIAL ||
            to == RecordingStatus.FAILED || to == RecordingStatus.CANCELLED
        else -> false
    }

    fun outcome(bytes: Long, failure: RecordingFailure?, gaps: Int, stop: RecordingStop?): RecordingOutcome {
        require(bytes >= 0 && gaps >= 0)
        val reason = failure ?: when (stop) {
            RecordingStop.TIME_LIMIT -> RecordingFailure.TIME_LIMIT
            RecordingStop.INTERRUPTED -> RecordingFailure.INTERRUPTED
            else -> null
        }
        return when {
            bytes == 0L && reason == null && stop != null -> RecordingOutcome(RecordingStatus.CANCELLED, null)
            bytes == 0L -> RecordingOutcome(RecordingStatus.FAILED, reason ?: RecordingFailure.NETWORK)
            reason != null -> RecordingOutcome(RecordingStatus.PARTIAL, reason)
            gaps > 0 -> RecordingOutcome(RecordingStatus.PARTIAL, RecordingFailure.NETWORK)
            else -> RecordingOutcome(RecordingStatus.DONE, null)
        }
    }

    fun interrupted(bytes: Long): RecordingOutcome =
        if (bytes > 0) RecordingOutcome(RecordingStatus.PARTIAL, RecordingFailure.INTERRUPTED)
        else RecordingOutcome(RecordingStatus.FAILED, RecordingFailure.INTERRUPTED)
}

data class RecordingWindow(val startMillis: Long, val stopMillis: Long) {
    init { require(stopMillis > startMillis) }
}

object RecordingPlan {
    const val MAX_DURATION_MILLIS = 6 * 60 * 60 * 1000L
    const val PRE_ROLL_MILLIS = 60 * 1000L
    const val POST_ROLL_MILLIS = 2 * 60 * 1000L
    const val DEFAULT_DURATION_MILLIS = 60 * 60 * 1000L

    fun now(nowMillis: Long, programmeStopMillis: Long? = null, durationMillis: Long = DEFAULT_DURATION_MILLIS,
        postRollMillis: Long = POST_ROLL_MILLIS): RecordingWindow? {
        require(durationMillis > 0 && postRollMillis >= 0)
        val stop = programmeStopMillis?.let { if (it <= nowMillis) return null; it + postRollMillis } ?: (nowMillis + durationMillis)
        return RecordingWindow(nowMillis, minOf(stop, nowMillis + MAX_DURATION_MILLIS))
    }

    fun programme(nowMillis: Long, startMillis: Long, stopMillis: Long?, preRollMillis: Long = PRE_ROLL_MILLIS,
        postRollMillis: Long = POST_ROLL_MILLIS): RecordingWindow? {
        require(preRollMillis >= 0 && postRollMillis >= 0)
        val end = stopMillis ?: (startMillis + DEFAULT_DURATION_MILLIS)
        if (end <= startMillis || end <= nowMillis) return null
        val start = maxOf(startMillis - preRollMillis, nowMillis)
        return RecordingWindow(start, minOf(end + postRollMillis, start + MAX_DURATION_MILLIS))
    }
}

data class RecordingSlot(val accountId: String, val startMillis: Long, val stopMillis: Long)

fun recordingPeak(existing: List<RecordingSlot>, candidate: RecordingSlot): Int {
    val events = existing.asSequence()
        .filter { it.accountId == candidate.accountId && it.startMillis < candidate.stopMillis && it.stopMillis > candidate.startMillis }
        .flatMap { sequenceOf(maxOf(it.startMillis, candidate.startMillis) to 1, minOf(it.stopMillis, candidate.stopMillis) to -1) }
        .sortedWith(compareBy<Pair<Long, Int>>({ it.first }, { it.second }))
    var current = 0
    var peak = 0
    for ((_, step) in events) { current += step; peak = maxOf(peak, current) }
    return peak
}

fun recordingConflicts(existing: List<RecordingSlot>, candidate: RecordingSlot, maxStreams: Int): Boolean {
    require(maxStreams > 0)
    return recordingPeak(existing, candidate) + 1 > maxStreams
}

data class RecordingSpan(val startMillis: Long, val stopMillis: Long, val coreStartMillis: Long, val coreStopMillis: Long) {
    init { require(startMillis <= coreStartMillis && coreStartMillis < coreStopMillis && coreStopMillis <= stopMillis) }

    companion object {
        fun of(startMillis: Long, stopMillis: Long, programmeStartMillis: Long?, programmeStopMillis: Long?): RecordingSpan {
            val coreStart = maxOf(startMillis, programmeStartMillis ?: startMillis).coerceAtMost(stopMillis - 1)
            val coreStop = minOf(stopMillis, programmeStopMillis ?: stopMillis).coerceAtLeast(coreStart + 1)
            return RecordingSpan(startMillis, stopMillis, coreStart, coreStop)
        }
    }
}

data class RecordingTrim(val candidate: RecordingSpan, val neighbours: List<RecordingSpan>)

fun trimRecordingPadding(candidate: RecordingSpan, neighbours: List<RecordingSpan>): RecordingTrim {
    var start = candidate.startMillis
    var stop = candidate.stopMillis
    val trimmed = neighbours.map { neighbour ->
        when {
            neighbour.coreStopMillis <= candidate.coreStartMillis && neighbour.stopMillis > start -> {
                val boundary = maxOf(candidate.coreStartMillis, neighbour.coreStopMillis)
                start = maxOf(start, boundary)
                neighbour.copy(stopMillis = minOf(neighbour.stopMillis, boundary))
            }
            neighbour.coreStartMillis >= candidate.coreStopMillis && neighbour.startMillis < stop -> {
                val boundary = neighbour.coreStartMillis
                stop = minOf(stop, boundary)
                neighbour.copy(startMillis = maxOf(neighbour.startMillis, boundary))
            }
            else -> neighbour
        }
    }
    return RecordingTrim(candidate.copy(startMillis = start, stopMillis = stop), trimmed)
}

enum class RecordingAlarmAction { ARM, START_NOW, MISSED }

fun recordingAlarmAction(window: RecordingWindow, nowMillis: Long, earlyMillis: Long = 0): RecordingAlarmAction = when {
    window.stopMillis <= nowMillis -> RecordingAlarmAction.MISSED
    window.startMillis - earlyMillis <= nowMillis -> RecordingAlarmAction.START_NOW
    else -> RecordingAlarmAction.ARM
}

object RecordingRetry {
    private val DELAYS = longArrayOf(1_000, 2_000, 4_000, 8_000, 15_000, 30_000)
    const val ATTEMPTS_WITHOUT_DATA = 6
    const val ADMISSION_GRACE_MILLIS = 45_000L
    const val ADMISSION_RETRY_MILLIS = 3_000L
    fun delayMillis(attempt: Int): Long = DELAYS[attempt.coerceIn(0, DELAYS.size - 1)]
    fun giveUp(attempt: Int, everReceived: Boolean): Boolean = !everReceived && attempt >= ATTEMPTS_WITHOUT_DATA
}

object RecordingStorage {
    const val RESERVE_BYTES = 500L * 1024 * 1024
    const val START_MARGIN_BYTES = 100L * 1024 * 1024
    const val CHECK_INTERVAL_BYTES = 8L * 1024 * 1024
    const val ESTIMATED_BYTES_PER_HOUR = 2_500L * 1024 * 1024
    fun canStart(freeBytes: Long, reserveBytes: Long = RESERVE_BYTES): Boolean = freeBytes >= reserveBytes + START_MARGIN_BYTES
    fun estimatedBytes(durationMillis: Long): Long =
        durationMillis.coerceIn(0, RecordingPlan.MAX_DURATION_MILLIS) / 1000 * ESTIMATED_BYTES_PER_HOUR / 3600
    fun hasRoomFor(freeBytes: Long, durationMillis: Long, committedBytes: Long = 0, reserveBytes: Long = RESERVE_BYTES): Boolean =
        freeBytes >= reserveBytes + START_MARGIN_BYTES + estimatedBytes(durationMillis) + committedBytes
    fun canContinue(freeBytes: Long, reserveBytes: Long = RESERVE_BYTES): Boolean = freeBytes >= reserveBytes
}

object RecordingFiles {
    const val EXTENSION = ".ts"
    const val PARTIAL_SUFFIX = ".part"
    const val MAX_NAME_CHARS = 120
    const val MAX_NAME_BYTES = 220
    private val STAMP = DateTimeFormatter.ofPattern("dd-MMM-yy HHmm", Locale.ENGLISH)
    private val RESERVED = setOf("CON", "PRN", "AUX", "NUL", "CONIN$", "CONOUT$") + (1..9).flatMap { listOf("COM$it", "LPT$it") }
    private const val KEPT = "-_'&.,()!+"

    fun name(channel: String, title: String?, startMillis: Long, zone: ZoneId, taken: (String) -> Boolean = { false }): String {
        val stamp = STAMP.format(Instant.ofEpochMilli(startMillis).atZone(zone))
        return (1..99).asSequence().map { copy -> named(channel, title, if (copy == 1) stamp else "$stamp ($copy)") }.firstOrNull { !taken(it) }
            ?: named(channel, title, "$stamp (${startMillis % 100_000})")
    }

    private fun named(channel: String, title: String?, stamp: String): String {
        val tail = stamp + EXTENSION
        var first = clean(channel, 60)
        var second = title?.let { clean(it, 100) }.orEmpty()
        fun join() = listOf(first, second).filter { it.isNotEmpty() }.joinToString("") { "$it - " } + tail
        fun over() = join().let { it.length > MAX_NAME_CHARS || it.toByteArray(Charsets.UTF_8).size > MAX_NAME_BYTES }
        while (over() && second.length > 24) second = shorten(second)
        while (over() && first.length > 24) first = shorten(first)
        while (over() && second.isNotEmpty()) second = shorten(second)
        while (over() && first.isNotEmpty()) first = shorten(first)
        return safe(join())
    }

    fun partial(name: String): String = name + PARTIAL_SUFFIX

    fun safe(name: String): String {
        val stem = name.substringBefore('.').trimEnd(' ').uppercase(Locale.ROOT)
        return if (stem in RESERVED) "_$name" else name
    }

    private fun shorten(value: String): String = whole(value.dropLast(1)).trimEnd(' ', '.', '-', '_', ',')

    private fun whole(value: String): String = if (value.lastOrNull()?.isHighSurrogate() == true) value.dropLast(1) else value

    private fun clean(value: String, limit: Int): String = whole(RecordingText.plain(value, KEPT).take(limit)).trim(' ', '.', '-', '_', ',')
}

object RecordingText {
    private const val DISPLAY_KEPT = "-_'&.,()!+:;?/\"#%@\$[]"
    private val SMALL = mapOf(0x1D00 to 'a', 0x0299 to 'b', 0x1D03 to 'b', 0x1D04 to 'c', 0x1D05 to 'd', 0x1D07 to 'e', 0xA730 to 'f', 0x0262 to 'g',
        0x029C to 'h', 0x026A to 'i', 0x1D0A to 'j', 0x1D0B to 'k', 0x029F to 'l', 0x1D0C to 'l', 0x1D0D to 'm', 0x0274 to 'n', 0x1D0F to 'o',
        0x1D18 to 'p', 0xA7AF to 'q', 0x0280 to 'r', 0xA731 to 's', 0x1D1B to 't', 0x1D1C to 'u', 0x1D20 to 'v', 0x1D21 to 'w', 0x028F to 'y',
        0x1D22 to 'z', 0x1D01 to 'æ', 0x0276 to 'œ', 0x2018 to '\'', 0x2019 to '\'', 0x201B to '\'', 0x02BC to '\'', 0x201C to '"', 0x201D to '"',
        0x201E to '"', 0x2010 to '-', 0x2011 to '-', 0x2012 to '-', 0x2013 to '-', 0x2014 to '-', 0x2015 to '-', 0x2212 to '-')

    fun display(value: String): String = plain(value, DISPLAY_KEPT)

    fun plain(value: String, kept: String): String {
        val out = StringBuilder(value.length)
        var raised: Boolean? = null
        var letter = false
        fun gap() { if (out.isNotEmpty() && out[out.length - 1] != ' ') out.append(' '); letter = false }
        val composed = Normalizer.normalize(value, Normalizer.Form.NFC)
        var index = 0
        while (index < composed.length) {
            val point = composed.codePointAt(index)
            index += Character.charCount(point)
            if (dropped(point)) continue
            val high = superscript(point)
            val mapped = squared(point)?.toString() ?: Normalizer.normalize(String(Character.toChars(point)), Normalizer.Form.NFKC)
            var at = 0
            while (at < mapped.length) {
                val raw = mapped.codePointAt(at)
                at += Character.charCount(raw)
                val code = SMALL[raw]?.code ?: raw
                val type = Character.getType(code)
                when {
                    Character.isLetterOrDigit(code) -> {
                        if (letter && raised != null && raised != high) out.append(' ')
                        out.appendCodePoint(code); letter = true; raised = high
                    }
                    type == Character.NON_SPACING_MARK.toInt() || type == Character.COMBINING_SPACING_MARK.toInt() ->
                        if (letter && !decorativeMark(code)) out.appendCodePoint(code)
                    type == Character.ENCLOSING_MARK.toInt() || type == Character.FORMAT.toInt() -> Unit
                    code < 0x80 && code.toChar() in kept -> { out.appendCodePoint(code); letter = false }
                    else -> gap()
                }
            }
        }
        return out.split(' ').filter { it.isNotEmpty() }.joinToString(" ")
    }

    private fun dropped(point: Int): Boolean = point in 0xFE00..0xFE0F || point in 0xE0100..0xE01EF || point in 0xE0000..0xE007F ||
        point == 0x200D || point in 0x1F1E6..0x1F1FF || point in 0x1F3FB..0x1F3FF

    private fun superscript(point: Int): Boolean = point in 0x02B0..0x02FF || point in 0x1D2C..0x1D6A || point == 0x1D78 || point in 0x1D9B..0x1DBF ||
        point in 0x2070..0x209F || point == 0x00B9 || point == 0x00B2 || point == 0x00B3 || point in 0xA770..0xA771 || point in 0xA7F8..0xA7F9

    private fun squared(point: Int): Char? = when (point) {
        in 0x1F150..0x1F169 -> 'A' + (point - 0x1F150)
        in 0x1F170..0x1F189 -> 'A' + (point - 0x1F170)
        in 0x1F130..0x1F149 -> 'A' + (point - 0x1F130)
        in 0x24B6..0x24CF -> 'A' + (point - 0x24B6)
        in 0x24D0..0x24E9 -> 'a' + (point - 0x24D0)
        else -> null
    }

    private fun decorativeMark(point: Int): Boolean = point in 0x0300..0x036F || point in 0x1AB0..0x1AFF || point in 0x1DC0..0x1DFF ||
        point in 0x20D0..0x20FF || point in 0xFE20..0xFE2F
}
