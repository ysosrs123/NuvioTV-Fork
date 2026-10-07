package com.nuvio.tv.core.iptv

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class RecordingStatus {
    SCHEDULED, RECORDING, DONE, PARTIAL, FAILED, CANCELLED;
    val finished: Boolean get() = this != SCHEDULED && this != RECORDING
    val holdsConnection: Boolean get() = this == SCHEDULED || this == RECORDING
}

enum class RecordingFailure {
    NO_CONNECTION, DEVICE_BUSY, SOURCE_UNAVAILABLE, CHANNEL_UNAVAILABLE, UNSUPPORTED_STREAM, ENCRYPTED_STREAM,
    NETWORK, LOW_STORAGE, STORAGE_ERROR, TIME_LIMIT, INTERRUPTED, START_BLOCKED, MISSED,
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
    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HHmm")

    fun name(channel: String, title: String?, startMillis: Long, id: String, zone: ZoneId): String {
        require(id.matches(Regex("[A-Za-z0-9-]{8,64}")))
        val parts = listOfNotNull(clean(channel, 60), title?.let { clean(it, 80) }, STAMP.format(Instant.ofEpochMilli(startMillis).atZone(zone)))
            .filter { it.isNotEmpty() }
        return (parts + id.replace("-", "").take(8)).joinToString(" - ") + EXTENSION
    }

    fun partial(name: String): String = name + PARTIAL_SUFFIX

    private fun clean(value: String, limit: Int): String {
        val mapped = buildString {
            for (char in value) append(if (char.isLetterOrDigit() || char == '-' || char == '_' || char == '\'' || char == '&') char else ' ')
        }
        return mapped.split(' ').filter { it.isNotEmpty() }.joinToString(" ").take(limit).trim().trim('-', '_')
    }
}
