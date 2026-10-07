package com.nuvio.tv.core.iptv

enum class LocalTimeshiftLength(val minutes: Int?) { MINUTES_15(15), MINUTES_30(30), MINUTES_60(60), AUTOMATIC(null) }

enum class LocalTimeshiftBlock { DISABLED, NOT_FULLSCREEN, NOT_LIVE, NOT_TS, MULTIVIEW, RECORDING, NO_STORAGE }

enum class LocalTimeshiftFailure { START, NETWORK, ENDED, STORAGE, NO_SPACE, STALLED }

sealed interface LocalTimeshiftAction {
    data object Direct : LocalTimeshiftAction
    data object Oldest : LocalTimeshiftAction
    data object Live : LocalTimeshiftAction
}

sealed interface LocalTimeshiftStep {
    data object Live : LocalTimeshiftStep
    data class Local(val atMillis: Long) : LocalTimeshiftStep
    data object Archive : LocalTimeshiftStep
}

object LocalTimeshiftSizing {
    const val PACKET = 188
    const val MEASURE_MILLIS = 10_000L
    const val MIN_BITS = 1_000_000L
    const val MAX_BITS = 40_000_000L
    const val MIN_MINUTES = 5
    const val AUTO_MINUTES = 30
    const val SAFETY_BYTES = 512L * 1024 * 1024
    const val ABSOLUTE_CAP = 4L * 1024 * 1024 * 1024 - 64L * 1024 * 1024
    private const val HEADROOM_PERCENT = 15

    fun alignDown(bytes: Long): Long = if (bytes <= 0) 0 else bytes / PACKET * PACKET
    fun alignUp(bytes: Long): Long = if (bytes <= 0) 0 else Math.addExact(bytes, PACKET - 1L) / PACKET * PACKET

    fun bitrate(bytes: Long, millis: Long): Long? {
        if (bytes <= 0 || millis < MEASURE_MILLIS / 2) return null
        return (bytes.toDouble() * 8_000 / millis).toLong().coerceIn(MIN_BITS, MAX_BITS)
    }

    fun bytesFor(bitsPerSecond: Long, minutes: Int): Long {
        require(bitsPerSecond > 0 && minutes > 0)
        return alignUp(bitsPerSecond.coerceAtMost(MAX_BITS) / 8 * minutes * 60 * (100 + HEADROOM_PERCENT) / 100)
    }

    fun room(usableBytes: Long): Long = alignDown(minOf(usableBytes - SAFETY_BYTES, ABSOLUTE_CAP))

    fun provisional(usableBytes: Long): Long? = room(usableBytes).takeIf { it >= bytesFor(MAX_BITS, 1) }

    fun capacity(length: LocalTimeshiftLength, bitsPerSecond: Long, usableBytes: Long): Long? {
        val wanted = bytesFor(bitsPerSecond, length.minutes ?: AUTO_MINUTES)
        return minOf(wanted, room(usableBytes)).takeIf { it >= bytesFor(bitsPerSecond, MIN_MINUTES) }
    }

    fun minutes(capacityBytes: Long, bitsPerSecond: Long): Int =
        if (capacityBytes <= 0 || bitsPerSecond <= 0) 0 else (capacityBytes * 8 / bitsPerSecond / 60).toInt()
}

object LocalTimeshiftPolicy {
    const val LIVE_EDGE_MILLIS = 10_000L
    const val LIVE_PREROLL_MILLIS = 3_000L
    const val MAX_BEHIND_JUMPS = 3

    fun block(enabled: Boolean, fullscreen: Boolean, live: Boolean, transportStream: Boolean, multiview: Boolean,
        recordingChannel: Boolean, storage: Boolean): LocalTimeshiftBlock? = when {
        !enabled -> LocalTimeshiftBlock.DISABLED
        multiview -> LocalTimeshiftBlock.MULTIVIEW
        !fullscreen -> LocalTimeshiftBlock.NOT_FULLSCREEN
        !live -> LocalTimeshiftBlock.NOT_LIVE
        !transportStream -> LocalTimeshiftBlock.NOT_TS
        recordingChannel -> LocalTimeshiftBlock.RECORDING
        !storage -> LocalTimeshiftBlock.NO_STORAGE
        else -> null
    }

    fun onWriterFailure(failure: LocalTimeshiftFailure): LocalTimeshiftAction = LocalTimeshiftAction.Direct

    fun onReaderBehind(jumps: Int): LocalTimeshiftAction = if (jumps < MAX_BEHIND_JUMPS) LocalTimeshiftAction.Oldest else LocalTimeshiftAction.Direct

    fun onReaderStalled(writerRunning: Boolean): LocalTimeshiftAction = if (writerRunning) LocalTimeshiftAction.Live else LocalTimeshiftAction.Direct

    fun step(targetMillis: Long, nowMillis: Long, oldestMillis: Long?, archive: Boolean): LocalTimeshiftStep = when {
        targetMillis >= nowMillis - LIVE_EDGE_MILLIS -> LocalTimeshiftStep.Live
        oldestMillis != null && targetMillis >= oldestMillis -> LocalTimeshiftStep.Local(targetMillis)
        archive -> LocalTimeshiftStep.Archive
        oldestMillis != null && oldestMillis < nowMillis - LIVE_EDGE_MILLIS -> LocalTimeshiftStep.Local(oldestMillis)
        else -> LocalTimeshiftStep.Live
    }

    fun behind(positionMillis: Long?, nowMillis: Long): Boolean = positionMillis != null && positionMillis < nowMillis - LIVE_EDGE_MILLIS
}
