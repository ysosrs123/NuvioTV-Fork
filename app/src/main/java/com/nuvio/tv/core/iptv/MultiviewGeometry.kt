package com.nuvio.tv.core.iptv

data class TileRect(val x: Float, val y: Float, val width: Float, val height: Float) {
    val right: Float get() = x + width
    val bottom: Float get() = y + height
}

fun multiviewMaxTiles(layout: MultiviewLayout, deviceTiles: Int): Int = if (layout == MultiviewLayout.SIDE_BY_SIDE) minOf(2, deviceTiles) else deviceTiles

fun multiviewEffectiveLayout(layout: MultiviewLayout, tiles: Int): MultiviewLayout = when {
    layout == MultiviewLayout.SIDE_BY_SIDE && tiles > 2 -> MultiviewLayout.GRID
    (layout == MultiviewLayout.FOCUS || layout == MultiviewLayout.ONE_OVER_TWO) && tiles < 2 -> MultiviewLayout.GRID
    else -> layout
}

fun multiviewGeometry(layout: MultiviewLayout, slots: Int, main: Int, width: Float, height: Float, gap: Float): List<TileRect> {
    require(slots in 1..4 && width > 0f && height > 0f && gap >= 0f)
    val big = main.coerceIn(0, slots - 1)
    val order = listOf(big) + (0 until slots).filter { it != big }
    val rects = arrayOfNulls<TileRect>(slots)
    fun fit(maxWidth: Float, maxHeight: Float) = minOf(maxWidth, maxHeight * 16f / 9f).coerceAtLeast(0f)
    when (if (slots == 1) MultiviewLayout.GRID else layout) {
        MultiviewLayout.SIDE_BY_SIDE -> if (slots > 2) return multiviewGeometry(MultiviewLayout.GRID, slots, main, width, height, gap) else {
            val cell = (width - gap) / 2f
            for (i in 0 until slots) rects[i] = TileRect(i * (cell + gap), 0f, cell, height)
        }
        MultiviewLayout.FOCUS -> {
            val count = slots - 1
            val bigWidth = fit((width - gap) * 2f / 3f, height)
            val smallWidth = fit(width - bigWidth - gap, (height - gap * (count - 1)) / count.coerceAtLeast(3))
            val left = (width - bigWidth - gap - smallWidth) / 2f
            rects[big] = TileRect(left, (height - bigWidth * 9f / 16f) / 2f, bigWidth, bigWidth * 9f / 16f)
            val columnHeight = count * smallWidth * 9f / 16f + (count - 1) * gap
            order.drop(1).forEachIndexed { position, index ->
                rects[index] = TileRect(left + bigWidth + gap, (height - columnHeight) / 2f + position * (smallWidth * 9f / 16f + gap), smallWidth, smallWidth * 9f / 16f)
            }
        }
        MultiviewLayout.ONE_OVER_TWO -> {
            val count = slots - 1
            val columns = count.coerceAtLeast(2)
            val smallWidth = fit((width - gap * (columns - 1)) / columns, (height - gap) / 3f)
            val smallHeight = smallWidth * 9f / 16f
            val bigWidth = fit(width, height - gap - smallHeight)
            val bigHeight = bigWidth * 9f / 16f
            val top = (height - bigHeight - gap - smallHeight) / 2f
            rects[big] = TileRect((width - bigWidth) / 2f, top, bigWidth, bigHeight)
            val rowWidth = count * smallWidth + (count - 1) * gap
            order.drop(1).forEachIndexed { position, index ->
                rects[index] = TileRect((width - rowWidth) / 2f + position * (smallWidth + gap), top + bigHeight + gap, smallWidth, smallHeight)
            }
        }
        MultiviewLayout.GRID -> {
            val rows = if (slots <= 2) 1 else 2
            val columns = if (slots == 1) 1 else 2
            val cell = fit((width - gap * (columns - 1)) / columns, (height - gap * (rows - 1)) / rows)
            val cellHeight = cell * 9f / 16f
            val left = (width - columns * cell - (columns - 1) * gap) / 2f
            val top = (height - rows * cellHeight - (rows - 1) * gap) / 2f
            for (i in 0 until slots) rects[i] = TileRect(left + (i % columns) * (cell + gap), top + (i / columns) * (cellHeight + gap), cell, cellHeight)
        }
    }
    return rects.map { requireNotNull(it) }
}
