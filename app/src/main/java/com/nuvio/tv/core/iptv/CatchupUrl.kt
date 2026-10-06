package com.nuvio.tv.core.iptv

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

enum class CatchupType { XTREAM, DEFAULT, APPEND, SHIFT, FLUSSONIC }

data class CatchupWindow(val startMillis: Long, val endMillis: Long, val nowMillis: Long, val offsetMinutes: Int = 0) {
    init { require(endMillis > startMillis && nowMillis > startMillis && offsetMinutes in -1440..1440) }
    val durationSeconds: Long get() = (endMillis - startMillis) / 1000
}

fun catchupType(value: String?, source: String?): CatchupType? = when (value?.trim()?.lowercase()) {
    "xc", "xtream", "xtreamcodes", "xtream-codes" -> CatchupType.XTREAM
    "default", "vod" -> CatchupType.DEFAULT
    "append" -> CatchupType.APPEND
    "shift", "timeshift", "siptv" -> CatchupType.SHIFT
    "flussonic", "flussonic-hls", "flussonic-ts", "fs" -> CatchupType.FLUSSONIC
    null, "" -> if (!source.isNullOrBlank()) CatchupType.DEFAULT else null
    else -> null
}

fun catchupUrl(type: CatchupType, liveUrl: String, source: String?, window: CatchupWindow): String? = when (type) {
    CatchupType.DEFAULT -> source?.takeIf(String::isNotBlank)?.let { expandCatchupTemplate(it, window) }
    CatchupType.APPEND -> source?.takeIf(String::isNotBlank)?.let { liveUrl + expandCatchupTemplate(it, window) }
    CatchupType.SHIFT -> liveUrl + (if ('?' in liveUrl) "&" else "?") + "utc=${window.startMillis / 1000}&lutc=${window.nowMillis / 1000}"
    CatchupType.FLUSSONIC -> {
        val query = liveUrl.substringAfter('?', "").let { if (it.isEmpty()) "" else "?$it" }
        val path = liveUrl.substringBefore('?')
        val directory = path.substringBeforeLast('/')
        val start = window.startMillis / 1000
        if (path.endsWith(".m3u8")) "$directory/archive-$start-${window.durationSeconds}.m3u8$query"
        else "$directory/timeshift_abs-$start.ts$query"
    }
    CatchupType.XTREAM -> null
}

private val placeholder = Regex("\\$?\\{([a-zA-Z-]+)(?::([^}]*))?\\}")

fun expandCatchupTemplate(template: String, window: CatchupWindow): String = placeholder.replace(template) { match ->
    val name = match.groupValues[1].lowercase()
    val argument = match.groupValues[2]
    val start = window.startMillis / 1000
    val end = window.endMillis / 1000
    val now = window.nowMillis / 1000
    when (name) {
        "utc", "start", "timestamp" -> if (argument.isEmpty()) "$start" else formatCatchupTime(argument, window.startMillis, window.offsetMinutes)
        "utcend", "end" -> if (argument.isEmpty()) "$end" else formatCatchupTime(argument, window.endMillis, window.offsetMinutes)
        "lutc", "now" -> if (argument.isEmpty()) "$now" else formatCatchupTime(argument, window.nowMillis, window.offsetMinutes)
        "duration" -> "${window.durationSeconds / (argument.toLongOrNull()?.takeIf { it > 0 } ?: 1)}"
        "offset" -> "${(now - start) / (argument.toLongOrNull()?.takeIf { it > 0 } ?: 1)}"
        "y", "m", "d", "h", "s" -> formatCatchupTime(match.groupValues[1], window.startMillis, window.offsetMinutes)
        else -> match.value
    }
}

fun formatCatchupTime(pattern: String, millis: Long, offsetMinutes: Int): String {
    val time = Instant.ofEpochMilli(millis).atOffset(ZoneOffset.ofTotalSeconds(offsetMinutes * 60))
    return buildString {
        for (char in pattern.removePrefix("%").replace("%", "")) append(when (char) {
            'Y' -> "%04d".format(time.year)
            'm' -> "%02d".format(time.monthValue)
            'd' -> "%02d".format(time.dayOfMonth)
            'H' -> "%02d".format(time.hour)
            'M' -> "%02d".format(time.minute)
            'S' -> "%02d".format(time.second)
            else -> char.toString()
        })
    }
}

fun xtreamTimeshiftPath(window: CatchupWindow): Pair<Long, String> {
    val minutes = ((window.endMillis - window.startMillis + 59_999) / 60_000).coerceIn(1, 1440)
    val start = Instant.ofEpochMilli(window.startMillis).atOffset(ZoneOffset.ofTotalSeconds(window.offsetMinutes * 60))
    return minutes to DateTimeFormatter.ofPattern("yyyy-MM-dd:HH-mm").format(start)
}
