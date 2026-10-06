package com.nuvio.tv.core.iptv

data class GuideGridWindow(val startMillis: Long, val endMillis: Long) {
    init { require(endMillis > startMillis && endMillis - startMillis <= MAX_SPAN_MILLIS) }
    val spanMillis: Long get() = endMillis - startMillis
    fun fractionAt(timeMillis: Long): Float = ((timeMillis.coerceIn(startMillis, endMillis) - startMillis).toDouble() / spanMillis).toFloat()
    fun shifted(byMillis: Long): GuideGridWindow = GuideGridWindow(Math.addExact(startMillis, byMillis), Math.addExact(endMillis, byMillis))

    companion object {
        const val SLOT_MILLIS = 30L * 60 * 1000
        const val MAX_SPAN_MILLIS = 24L * 60 * 60 * 1000

        fun around(nowMillis: Long, spanMillis: Long = 3 * 60 * 60 * 1000L, slotMillis: Long = SLOT_MILLIS): GuideGridWindow {
            require(slotMillis > 0 && spanMillis >= slotMillis && spanMillis % slotMillis == 0L)
            val start = Math.floorDiv(nowMillis, slotMillis) * slotMillis
            return GuideGridWindow(start, Math.addExact(start, spanMillis))
        }
    }
}

sealed interface GuideGridCell {
    val startMillis: Long
    val endMillis: Long
}

data class GuideProgrammeCell(
    val programme: GuideProgramme,
    override val startMillis: Long,
    override val endMillis: Long,
    val continuesBefore: Boolean,
    val continuesAfter: Boolean,
    val openEnded: Boolean,
) : GuideGridCell

data class GuideGapCell(override val startMillis: Long, override val endMillis: Long) : GuideGridCell

data class GuideGridRow(val cells: List<GuideGridCell>, val unplaceable: Int, val overlapped: Int) {
    fun indexAt(timeMillis: Long): Int {
        if (cells.isEmpty()) return -1
        val inside = cells.indexOfFirst { timeMillis >= it.startMillis && timeMillis < it.endMillis }
        if (inside >= 0) return inside
        return if (timeMillis < cells.first().startMillis) 0 else cells.lastIndex
    }

    fun anchorFor(index: Int, previousAnchorMillis: Long): Long {
        val cell = cells[index]
        return previousAnchorMillis.coerceIn(cell.startMillis, cell.endMillis - 1)
    }
}

fun layoutGuideRow(programmes: List<GuideProgramme>, window: GuideGridWindow, minCellMillis: Long = 60_000): GuideGridRow {
    require(minCellMillis in 1 until window.spanMillis)
    val placeable = programmes.filter { it.start.precise && (it.stop == null || it.stop.precise) }
        .sortedWith(compareBy<GuideProgramme>({ it.start.epochMillis }, { it.stop?.epochMillis ?: Long.MAX_VALUE }))
    val unplaceable = programmes.size - placeable.size
    var overlapped = 0
    val spans = mutableListOf<GuideSpan>()
    for ((index, programme) in placeable.withIndex()) {
        val nextStart = (index + 1 until placeable.size).asSequence().map { placeable[it].start.epochMillis }
            .firstOrNull { it > programme.start.epochMillis }
        val naturalEnd = programme.stop?.epochMillis ?: (nextStart ?: maxOf(window.endMillis, programme.start.epochMillis + minCellMillis))
        val start = maxOf(programme.start.epochMillis, spans.lastOrNull()?.end ?: Long.MIN_VALUE)
        if (start > programme.start.epochMillis) overlapped++
        if (naturalEnd <= start) continue
        spans += GuideSpan(programme, start, naturalEnd, programme.stop == null)
    }
    val cells = mutableListOf<GuideGridCell>()
    var cursor = window.startMillis
    for ((programme, rawStart, rawEnd, openEnded) in spans) {
        if (rawEnd <= window.startMillis || rawStart >= window.endMillis) continue
        val start = maxOf(rawStart, window.startMillis)
        val end = minOf(rawEnd, window.endMillis)
        if (start > cursor) {
            if (start - cursor >= minCellMillis || cells.isEmpty()) cells += GuideGapCell(cursor, start)
            else cells[cells.lastIndex] = extend(cells.last(), start)
        }
        val continuesBefore = (rawStart < window.startMillis) || (programme.start.epochMillis < rawStart)
        val continuesAfter = rawEnd > window.endMillis
        cells += GuideProgrammeCell(programme, start, end, continuesBefore, continuesAfter, openEnded)
        cursor = end
    }
    if (cursor < window.endMillis) {
        if (window.endMillis - cursor >= minCellMillis || cells.isEmpty()) cells += GuideGapCell(cursor, window.endMillis)
        else cells[cells.lastIndex] = extend(cells.last(), window.endMillis)
    }
    return GuideGridRow(cells, unplaceable, overlapped)
}

private data class GuideSpan(val programme: GuideProgramme, val start: Long, val end: Long, val openEnded: Boolean)

private fun extend(cell: GuideGridCell, endMillis: Long): GuideGridCell = when (cell) {
    is GuideProgrammeCell -> cell.copy(endMillis = endMillis)
    is GuideGapCell -> cell.copy(endMillis = endMillis)
}
