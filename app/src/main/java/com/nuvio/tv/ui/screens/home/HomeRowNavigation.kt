package com.nuvio.tv.ui.screens.home

/** Preserve row identity when a focused card has transformed visual bounds. */
internal fun adjacentHomeRowIndex(current: Int, count: Int, down: Boolean): Int? {
    if (current !in 0 until count) return null
    return (current + if (down) 1 else -1).takeIf { it in 0 until count }
}
