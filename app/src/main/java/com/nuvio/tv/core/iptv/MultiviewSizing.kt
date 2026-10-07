package com.nuvio.tv.core.iptv

enum class MultiviewQuality { AUTO, SHARPEST, LIGHTEST }
enum class MultiviewLayout { GRID, FOCUS, SIDE_BY_SIDE, ONE_OVER_TWO }

val MULTIVIEW_RUNGS = intArrayOf(360, 540, 720, 1080)
const val MULTIVIEW_FRAME_RATE = 50

fun multiviewRung(physicalHeight: Int, quality: MultiviewQuality): Int {
    val auto = MULTIVIEW_RUNGS.indexOfFirst { it * 10 >= physicalHeight * 9 }.let { if (it < 0) MULTIVIEW_RUNGS.lastIndex else it }
    val index = when (quality) {
        MultiviewQuality.AUTO -> auto
        MultiviewQuality.SHARPEST -> auto + 1
        MultiviewQuality.LIGHTEST -> auto - 1
    }.coerceIn(0, MULTIVIEW_RUNGS.lastIndex)
    return MULTIVIEW_RUNGS[index]
}

fun multiviewPixelRate(height: Int, frameRate: Int = MULTIVIEW_FRAME_RATE): Long = height.toLong() * 16 / 9 * height * frameRate

fun multiviewHeights(physicalHeights: List<Int>, focused: Int, quality: MultiviewQuality, budgetPixelsPerSecond: Long?): List<Int> {
    val heights = physicalHeights.map { multiviewRung(it, quality) }.toMutableList()
    val budget = budgetPixelsPerSecond ?: return heights
    while (heights.sumOf { multiviewPixelRate(it) } > budget) {
        val candidates = heights.indices.filter { heights[it] > MULTIVIEW_RUNGS.first() }
        if (candidates.isEmpty()) break
        val others = candidates.filter { it != focused }
        val pick = (others.ifEmpty { candidates }).maxWith(compareBy<Int>({ heights[it] }, { -it }))
        heights[pick] = MULTIVIEW_RUNGS[MULTIVIEW_RUNGS.indexOf(heights[pick]) - 1]
    }
    return heights
}

fun multiviewHasRoom(currentPixelRates: List<Long>, budgetPixelsPerSecond: Long?): Boolean =
    budgetPixelsPerSecond == null || currentPixelRates.sum() + multiviewPixelRate(MULTIVIEW_RUNGS.first()) <= budgetPixelsPerSecond
