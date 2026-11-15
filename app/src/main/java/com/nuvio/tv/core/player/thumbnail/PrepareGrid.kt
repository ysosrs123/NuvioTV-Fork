package com.nuvio.tv.core.player.thumbnail

/**
 * The before-play grid: the coverage slots the generating screen counts, per the Thumbnails before play choice.
 * Strides are in 10 s slots (3 = 30 s, 6 = 1 min, 30 = 5 min).
 */
internal object PrepareGrid {
    /** Coverage strides in slots, coarse first: 5 min, 60 s, 30 s, 10 s. */
    val LATTICE_1080P = listOf(30, 6, 3, 1)
    /** 4K WEB-DL (~0.6 MB per keyframe) every 10 s, 30 s on small boxes; a remux (~1 MB) every 30 s, 60 s on small. */
    val LATTICE_4K_WEB = listOf(30, 6, 3, 1)
    val LATTICE_4K_WEB_SMALL = listOf(30, 6, 3)
    val LATTICE_4K_REMUX = listOf(30, 12, 6)
    val LATTICE_4K_REMUX_STRONG = listOf(30, 12, 6, 3)
    /** A 1080p remux (~0.5 to 1 MB per keyframe) stops at 30 s. */
    val LATTICE_1080P_HEAVY = listOf(30, 6, 3)

    /** The pass's strides for this file on this box. */
    fun base(fourK: Boolean, remux: Boolean, strongBox: Boolean, heavy1080p: Boolean): List<Int> = when {
        fourK && !remux && strongBox -> LATTICE_4K_WEB
        fourK && !remux -> LATTICE_4K_WEB_SMALL
        fourK && strongBox -> LATTICE_4K_REMUX_STRONG
        fourK -> LATTICE_4K_REMUX
        heavy1080p -> LATTICE_1080P_HEAVY
        else -> LATTICE_1080P
    }

    /** Full: every 30 s, a 4K remux every 60 s, never finer than the pass itself goes on this box. */
    fun fullStride(fourK: Boolean, remux: Boolean, finest: Int): Int = maxOf(finest, if (fourK && remux) 6 else 3)

    /** Before play the screen covers every other slot of the full grid; the rest follow while the title plays. */
    fun screenStride(fourK: Boolean, remux: Boolean, finest: Int): Int =
        fullStride(fourK, remux, finest) * BEFORE_PLAY_GRID_TIMES

    const val BEFORE_PLAY_GRID_TIMES = 2

    /**
     * The pass's strides with the screen's grid among them. The first sweep stays a multiple of the grid, 5 min where
     * that fits, so nothing is made before play that the screen does not count.
     */
    fun strides(base: List<Int>, screen: Int): List<Int> {
        val first = base.first()
        val coarse = if (first % screen == 0) first else (first + screen - 1) / screen * screen
        return (listOf(coarse) + base.drop(1) + screen).distinct().sortedDescending()
    }
}
