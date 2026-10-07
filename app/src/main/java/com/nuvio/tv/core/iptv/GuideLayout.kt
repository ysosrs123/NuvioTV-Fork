package com.nuvio.tv.core.iptv

enum class GuideDensity { COMFORTABLE, COMPACT }

fun guideStickyOffsetMillis(cellStartMillis: Long, cellEndMillis: Long, viewStartMillis: Long, minVisibleMillis: Long): Long {
    if (cellEndMillis <= cellStartMillis || viewStartMillis <= cellStartMillis) return 0L
    val room = (cellEndMillis - cellStartMillis - minVisibleMillis.coerceAtLeast(0L)).coerceAtLeast(0L)
    return minOf(viewStartMillis - cellStartMillis, room)
}

fun guideVisibleRows(availableHeight: Float, rowHeight: Float, gap: Float): Int =
    if (rowHeight <= 0f) 0 else ((availableHeight + gap) / (rowHeight + gap)).toInt().coerceAtLeast(0)
