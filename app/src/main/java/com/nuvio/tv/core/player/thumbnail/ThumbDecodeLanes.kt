package com.nuvio.tv.core.player.thumbnail

/**
 * How many keyframes are decoded at once, each lane with its own decoder. A lane is added while the median decode
 * time at the current count stays within [GAIN_RATIO] of the median at one lane fewer, and taken away when it does
 * not, when the box runs hot or when memory runs short. Worker thread only.
 */
internal class ThumbDecodeLanes(
    private val strongBox: Boolean,
    private val fourK: Boolean,
    processors: Int,
    phase: FetchPhase,
) {
    class Change(val from: Int, val to: Int, val reason: String, val medianMs: Long)

    companion object {
        const val MAX = 6
        const val MAX_4K = 4
        const val WINDOW = 6
        const val GAIN_RATIO = 1.25
        /** Decodes before a lane count that brought no gain, or was given up for memory, is tried again. */
        const val RETRY_AFTER_DECODES = 60

        fun median(values: Collection<Long>): Long {
            if (values.isEmpty()) return 0L
            val s = values.sorted()
            val m = s.size / 2
            return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
        }
    }

    private val processors = processors.coerceAtLeast(1)
    var phase = phase
        private set
    var lanes = startLanes().coerceAtMost(ceiling())
        private set
    /** Recent decode times by the number of decodes running when each started. */
    private val windows = Array(MAX + 1) { ArrayDeque<Long>() }
    private var freshAtLanes = 0
    private var decodes = 0
    private var blockedFrom = Int.MAX_VALUE
    private var blockedUntil = 0

    fun ceiling(p: FetchPhase = phase): Int {
        val spare = (processors - 1).coerceAtLeast(1)
        return when (p) {
            FetchPhase.PLAYBACK -> minOf(spare, if (strongBox || !fourK) 2 else 1)
            else -> minOf(spare, if (!strongBox && fourK) 1 else if (fourK) MAX_4K else MAX)
        }
    }

    private fun startLanes(): Int = if (!strongBox && fourK) 1 else 2

    fun onPhase(p: FetchPhase): Change? {
        if (p == phase) return null
        phase = p
        if (lanes <= ceiling()) return null
        val why = when (p) {
            FetchPhase.PLAYBACK -> "playback"
            FetchPhase.GENERATING -> "generating"
            FetchPhase.PLAYER_CLOSED -> "player closed"
        }
        return set(ceiling(), why)
    }

    /**
     * A finished decode: [ms] is its decode plus render time, [concurrent] the decodes running when it started (itself
     * included). [held]: the box is hot or its CPU clock is capped, so no lane is added.
     */
    fun onDecode(ms: Long, concurrent: Int, held: Boolean): Change? {
        decodes++
        val c = concurrent.coerceIn(1, MAX)
        val w = windows[c]
        w.addLast(ms.coerceAtLeast(0L))
        while (w.size > WINDOW) w.removeFirst()
        if (c != lanes) return null
        freshAtLanes++
        if (freshAtLanes < WINDOW) return null
        val now = median(w)
        val below = if (lanes > 1) windows[lanes - 1].takeIf { it.size >= WINDOW }?.let { median(it) } else null
        if (below != null && now > below * GAIN_RATIO) {
            block(lanes)
            return set(lanes - 1, "no gain over ${lanes - 1} at ${below}ms")
        }
        if (held || lanes >= ceiling()) return null
        if (lanes + 1 >= blockedFrom && decodes < blockedUntil) return null
        return set(lanes + 1, if (below != null) "gain over ${lanes - 1} at ${below}ms" else "steady")
    }

    fun onClockCap(): Change? = set(lanes - 1, "clock capped")

    fun onMemoryTrim(): Change? = lower("memory trim")

    fun onMemoryRefused(): Change? = lower("memory refused")

    private fun lower(why: String): Change? {
        block(lanes)
        return set(lanes - 1, why)
    }

    private fun block(from: Int) {
        blockedFrom = from
        blockedUntil = decodes + RETRY_AFTER_DECODES
    }

    private fun set(to: Int, why: String): Change? {
        val t = to.coerceIn(1, ceiling())
        if (t == lanes) return null
        val change = Change(lanes, t, why, median(windows[lanes]))
        if (t < lanes) windows[lanes].clear()
        lanes = t
        freshAtLanes = 0
        return change
    }
}
