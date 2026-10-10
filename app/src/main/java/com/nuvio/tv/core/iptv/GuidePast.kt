package com.nuvio.tv.core.iptv

object GuidePast {
    const val DAY_MILLIS = 24L * 60 * 60 * 1000
    const val STEP_MILLIS = 6L * 60 * 60 * 1000
    const val MARGIN_MILLIS = 2L * 60 * 60 * 1000
    const val SPAN_MILLIS = 24L * 60 * 60 * 1000
    const val LEAVE_MILLIS = 2L * 60 * 60 * 1000
    const val JUMP_MILLIS = 3L * 60 * 60 * 1000
    private const val SLOT = GuideGridWindow.SLOT_MILLIS

    fun archiveDays(attributes: Map<String, String>): Int? =
        (attributes["archive-days"] ?: attributes["catchup-days"])?.trim()?.toIntOrNull()?.takeIf { it > 0 }

    fun reachDays(archives: List<Int?>, pastDays: Int): Int {
        val cap = pastDays.coerceAtLeast(1)
        if (archives.isEmpty()) return 1
        if (archives.any { it == null }) return cap
        return archives.maxOf { it ?: 1 }.coerceIn(1, cap)
    }

    fun earliest(nowMillis: Long, days: Int): Long = Math.floorDiv(nowMillis, SLOT) * SLOT - days.coerceAtLeast(1) * DAY_MILLIS

    fun reaches(archive: Boolean, days: Int?, startMillis: Long, nowMillis: Long): Boolean =
        archive && startMillis < nowMillis && (days == null || days <= 0 || nowMillis - startMillis <= days * DAY_MILLIS)

    fun follow(home: GuideGridWindow, past: GuideGridWindow?, fromMillis: Long, untilMillis: Long, earliest: Long?): GuideGridWindow? {
        if (earliest == null || earliest >= home.startMillis) return null
        if (past == null && fromMillis > home.startMillis) return null
        if (past != null && fromMillis >= home.startMillis + LEAVE_MILLIS) return null
        val wantFrom = maxOf(earliest, fromMillis - MARGIN_MILLIS)
        val wantUntil = minOf(home.endMillis, maxOf(untilMillis, fromMillis + SLOT) + MARGIN_MILLIS)
        if (past != null && past.startMillis <= wantFrom && past.endMillis >= wantUntil) return past
        val start = maxOf(earliest, Math.floorDiv(fromMillis - STEP_MILLIS, SLOT) * SLOT).coerceAtMost(home.startMillis - SLOT)
        return GuideGridWindow(start, minOf(home.endMillis, start + SPAN_MILLIS))
    }

    fun jump(cursorMillis: Long, direction: Int, earliest: Long, latest: Long): Long =
        (cursorMillis + direction.coerceIn(-1, 1) * JUMP_MILLIS).coerceIn(earliest, maxOf(earliest, latest))

    fun viewStart(targetMillis: Long, earliest: Long): Long = maxOf(Math.floorDiv(earliest, SLOT) * SLOT, Math.floorDiv(targetMillis, SLOT) * SLOT - SLOT)

    fun away(viewStart: Long, nowMillis: Long): Boolean {
        val slot = Math.floorDiv(nowMillis, SLOT) * SLOT
        return viewStart < slot - SLOT || viewStart > slot
    }

    fun earlierLoadable(firstCellStart: Long?, firstIsProgramme: Boolean, earliest: Long?): Boolean =
        earliest != null && firstCellStart != null && firstIsProgramme && firstCellStart > earliest
}
