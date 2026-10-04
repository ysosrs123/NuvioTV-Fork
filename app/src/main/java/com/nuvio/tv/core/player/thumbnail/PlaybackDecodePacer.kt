package com.nuvio.tv.core.player.thumbnail

/**
 * Paces 4K thumbnail decodes during playback so playback comes first. Dropped frames near a decode double the pause
 * between pictures and finally stop the coverage pass, and a stall near a decode stops decoding (one retry). Every window counts only drops since the last pace change.
 */
internal class PlaybackDecodePacer(private val basePaceMs: Long = 4_000L) {
    enum class Event { NONE, SLOWER, FASTER, STOPPED_DROPS, STOPPED_STALL, RESUMED_AFTER_STALL }

    companion object {
        const val MAX_LEVEL = 2
        const val BURST_WINDOW_MS = 20_000L
        const val BURST_DROPS = 3
        const val SUSTAINED_WINDOW_MS = 60_000L
        const val SUSTAINED_DROPS = 5
        const val STOP_DROPS_PER_MIN = 10
        const val ATTRIBUTION_MS = 20_000L
        const val RECOVER_MS = 120_000L
        const val STALL_RETRY_QUIET_MS = 5L * 60 * 1000
        const val MAX_STALL_STOPS = 2
        private const val MAX_DECODE_WINDOWS = 8
    }

    /** 0 = [basePaceMs], each level doubles it. */
    var level = 0
        private set
    /** Pause after each picture. */
    val paceMs: Long get() = basePaceMs shl level
    val levelPaceMs: Long get() = basePaceMs shl level
    var stoppedByDrops = false
        private set
    var stoppedByStall = false
        private set
    var stallStops = 0
        private set
    var countedDrops = 0
        private set
    var ignoredDrops = 0
        private set
    /** Reason for the last event, for the log. */
    var lastNote = ""
        private set

    private var baseline = -1
    private var levelChangedAt = 0L
    private var lastCountedDropAt = 0L
    /** (time, drops) within the last [SUSTAINED_WINDOW_MS]. */
    private val drops = ArrayDeque<LongArray>()
    /** [start, end], end = -1 while decoding. */
    private val decodes = ArrayDeque<LongArray>()
    private var lastStallSeen = 0L
    private var stallStoppedAt = 0L

    /** [settleTarget]: the frame the user settled on, drops do not stop it. */
    fun allows(settleTarget: Boolean): Boolean = !stoppedByStall && (settleTarget || !stoppedByDrops)

    fun decodeStarted(nowMs: Long) {
        decodes.addLast(longArrayOf(nowMs, -1L))
        while (decodes.size > MAX_DECODE_WINDOWS) decodes.removeFirst()
    }

    fun decodeEnded(nowMs: Long) {
        decodes.lastOrNull()?.takeIf { it[1] < 0 }?.set(1, nowMs)
    }

    private fun nearDecode(t: Long): Boolean = decodes.any { w ->
        t >= w[0] && (w[1] < 0 || t - w[1] <= ATTRIBUTION_MS)
    }

    /**
     * [dropped]: the player's cumulative counter, < 0 = unknown. [excluded]: not playing, buffering, or just after a
     * seek or resume.
     */
    fun observeDrops(nowMs: Long, dropped: Int, excluded: Boolean): Event {
        if (dropped < 0) {
            baseline = -1
            return recover(nowMs)
        }
        // The counter restarts from 0 when the video renderer is re-enabled.
        if (baseline < 0 || dropped < baseline) {
            baseline = dropped
            return recover(nowMs)
        }
        val d = dropped - baseline
        baseline = dropped
        if (d > 0) {
            if (!excluded && nearDecode(nowMs)) {
                drops.addLast(longArrayOf(nowMs, d.toLong()))
                countedDrops += d
                lastCountedDropAt = nowMs
            } else {
                ignoredDrops += d
            }
        }
        while (drops.isNotEmpty() && nowMs - drops.first()[0] > SUSTAINED_WINDOW_MS) drops.removeFirst()
        if (stoppedByDrops) return Event.NONE
        val perMinute = drops.filter { it[0] > levelChangedAt }.sumOf { it[1] }
        if (perMinute > STOP_DROPS_PER_MIN) {
            stoppedByDrops = true
            lastNote = "$perMinute dropped frames in ${SUSTAINED_WINDOW_MS / 1000} s"
            return Event.STOPPED_DROPS
        }
        val burst = drops.filter { it[0] > levelChangedAt && nowMs - it[0] <= BURST_WINDOW_MS }.sumOf { it[1] }
        val sustained = drops.filter { it[0] > levelChangedAt }.sumOf { it[1] }
        if (burst > BURST_DROPS || sustained > SUSTAINED_DROPS) {
            val why = if (burst > BURST_DROPS) "$burst dropped frames in ${BURST_WINDOW_MS / 1000} s"
            else "$sustained dropped frames in ${SUSTAINED_WINDOW_MS / 1000} s"
            if (level >= MAX_LEVEL) {
                stoppedByDrops = true
                lastNote = "$why at ${paceMs / 1000} s"
                return Event.STOPPED_DROPS
            }
            level++
            levelChangedAt = nowMs
            lastNote = why
            return Event.SLOWER
        }
        return recover(nowMs)
    }

    private fun recover(nowMs: Long): Event {
        if (stoppedByDrops || level == 0) return Event.NONE
        if (nowMs - maxOf(lastCountedDropAt, levelChangedAt) < RECOVER_MS) return Event.NONE
        level--
        levelChangedAt = nowMs
        lastNote = "${RECOVER_MS / 1000} s without dropped frames"
        return Event.FASTER
    }

    /** [stallAtMs]: start of the most recent stall, <= 0 = none. Each new stall is judged once. */
    fun observeStall(nowMs: Long, stallAtMs: Long): Event {
        if (stallAtMs > lastStallSeen) {
            lastStallSeen = stallAtMs
            if (!stoppedByStall && stallStops < MAX_STALL_STOPS && nearDecode(stallAtMs)) {
                stallStops++
                stoppedByStall = true
                stallStoppedAt = nowMs
                lastNote = "stall during or within ${ATTRIBUTION_MS / 1000} s after a decode ($stallStops of $MAX_STALL_STOPS)"
                return Event.STOPPED_STALL
            }
        }
        if (stoppedByStall && stallStops < MAX_STALL_STOPS &&
            nowMs - maxOf(stallStoppedAt, lastStallSeen) >= STALL_RETRY_QUIET_MS
        ) {
            stoppedByStall = false
            level = MAX_LEVEL
            levelChangedAt = nowMs
            lastNote = "${STALL_RETRY_QUIET_MS / 60_000} min without a stall - retrying at the slowest pace"
            return Event.RESUMED_AFTER_STALL
        }
        return Event.NONE
    }
}
