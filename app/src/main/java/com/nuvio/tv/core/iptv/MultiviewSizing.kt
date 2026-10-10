package com.nuvio.tv.core.iptv

import kotlin.math.roundToInt

enum class MultiviewQuality { AUTO, SHARPEST, LIGHTEST }
enum class MultiviewLayout { GRID, FOCUS, SIDE_BY_SIDE, ONE_OVER_TWO }

val MULTIVIEW_RUNGS = intArrayOf(360, 540, 720, 1080)
const val MULTIVIEW_FRAME_RATE = 50
const val CODEC_DEFAULT_INSTANCES = 32

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

data class MultiviewDecode(val avc: Long?, val hevc: Long? = null) {
    fun budget(hevcStream: Boolean): Long? = if (hevcStream) listOfNotNull(avc, hevc).maxOrNull() else avc
}

data class MultiviewTileLoad(val physicalHeight: Int, val pixelRate: Long? = null, val frameRate: Int = MULTIVIEW_FRAME_RATE,
    val hevc: Boolean = false, val adjustable: Boolean = true) {
    fun at(height: Int): Long = pixelRate?.takeIf { !adjustable } ?: multiviewPixelRate(height, frameRate)
    fun describe(height: Int): String = "${pixelRate?.let(::multiviewMegapixels) ?: "starting"}${if (hevc) " hevc" else " avc"}@${frameRate}" +
        if (adjustable) " ->${height}p" else " fixed"
}

fun multiviewShare(tiles: List<MultiviewTileLoad>, heights: List<Int>, decode: MultiviewDecode): Double =
    tiles.indices.sumOf { i -> decode.budget(tiles[i].hevc)?.let { tiles[i].at(heights[i]).toDouble() / it } ?: 0.0 }

fun multiviewHeights(tiles: List<MultiviewTileLoad>, focused: Int, quality: MultiviewQuality, decode: MultiviewDecode): List<Int> {
    val heights = tiles.map { multiviewRung(it.physicalHeight, quality) }.toMutableList()
    while (multiviewShare(tiles, heights, decode) > 1.0) {
        val candidates = heights.indices.filter { tiles[it].adjustable && heights[it] > MULTIVIEW_RUNGS.first() }
        if (candidates.isEmpty()) break
        val others = candidates.filter { it != focused }
        val pick = (others.ifEmpty { candidates }).maxWith(compareBy<Int>({ heights[it] }, { -it }))
        heights[pick] = MULTIVIEW_RUNGS[MULTIVIEW_RUNGS.indexOf(heights[pick]) - 1]
    }
    return heights
}

fun multiviewFits(tiles: List<MultiviewTileLoad>, decode: MultiviewDecode): Boolean =
    multiviewShare(tiles, List(tiles.size) { MULTIVIEW_RUNGS.first() }, decode) <= 1.0

fun multiviewBudgetLine(tiles: List<MultiviewTileLoad>, heights: List<Int>, decode: MultiviewDecode, action: String): String =
    "multiview budget=avc:${multiviewMegapixels(decode.avc)} hevc:${multiviewMegapixels(decode.hevc)} " +
        "used=${(multiviewShare(tiles, heights, decode) * 100).roundToInt()}% " +
        "tiles=[${tiles.indices.joinToString { tiles[it].describe(heights.getOrElse(it) { MULTIVIEW_RUNGS.first() }) }}] $action"

fun multiviewMegapixels(rate: Long?): String = rate?.let { "${it / 1_000_000}M" } ?: "none"

fun multiviewHasRoom(currentPixelRates: List<Long>, budgetPixelsPerSecond: Long?): Boolean =
    budgetPixelsPerSecond == null || currentPixelRates.sum() + multiviewPixelRate(MULTIVIEW_RUNGS.first()) <= budgetPixelsPerSecond

private val DECODE_POINTS = listOf(Triple(7680, 4320, 60), Triple(7680, 4320, 30), Triple(3840, 2160, 120), Triple(1920, 1080, 240),
    Triple(3840, 2160, 60), Triple(3840, 2160, 50), Triple(3840, 2160, 30), Triple(1920, 1080, 120), Triple(2560, 1440, 60),
    Triple(1920, 1080, 60), Triple(1920, 1080, 50), Triple(1920, 1080, 30)).sortedByDescending { (w, h, f) -> w.toLong() * h * f }

fun multiviewDecodeBudget(covers: (width: Int, height: Int, frameRate: Int) -> Boolean): Long? =
    DECODE_POINTS.firstOrNull { (w, h, f) -> covers(w, h, f) }?.let { (w, h, f) -> w.toLong() * h * f }

fun multiviewDecodeCapacity(guaranteed: Long?, claimed: Long?, instances: Int?): Long? {
    val single = listOfNotNull(guaranteed, claimed).maxOrNull() ?: return null
    val sessions = instances?.takeIf { it in 2 until CODEC_DEFAULT_INSTANCES }?.coerceAtMost(4) ?: return single
    return maxOf(single, sessions * minOf(single, multiviewPixelRate(1080)))
}
