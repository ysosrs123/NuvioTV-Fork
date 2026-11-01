package com.nuvio.tv.core.player.thumbnail

/**
 * 4K on the hardware decoder of a small box whose decoder takes its pictures from app memory (no readable video memory
 * pool: a Fire TV Stick's decoder takes about 210 MB of ordinary RAM). Only behind the generating screen once the film
 * has let go, charged the decoder's real cost, closed at the first shortage, and closed before the film loads again.
 * [ON_BY_DEFAULT] unless `adb shell setprop debug.nuvio.thumb.hw4k 0` (or `1`) says otherwise.
 */
internal object AppMemoryHardware {
    const val PROPERTY = "debug.nuvio.thumb.hw4k"
    /**
     * The default for boxes whose decoder takes its pictures from app memory; the property overrides it. On after
     * measurements on a 2 GB Fire TV Stick 4K Max: with the swap rules the decoder opens only when it can hold, and a
     * refusal costs nothing (the software set starts at once).
     */
    const val ON_BY_DEFAULT = true
    /** `0` keeps the decoder's input buffer at [HardwareThumbDecoder.MAX_INPUT_BYTES]. */
    const val SMALL_INPUT_PROPERTY = "debug.nuvio.thumb.hw4k_input"
    /** MB the open must leave after the decoder's cost, [MIN_OPEN_FLOOR_MB] to [MAX_OPEN_FLOOR_MB]; unset = [OPEN_FLOOR_MB]. */
    const val OPEN_FLOOR_PROPERTY = "debug.nuvio.thumb.hw4k_floor"
    /** `0` opens the decoder without first giving back what the thumbnails hold themselves. */
    const val RELEASE_PROPERTY = "debug.nuvio.thumb.hw4k_release"

    val on: Boolean get() = onFor(systemProperty(PROPERTY))
    val smallInput: Boolean get() = smallInputFor(systemProperty(SMALL_INPUT_PROPERTY))
    val openFloorMb: Long get() = openFloorFor(systemProperty(OPEN_FLOOR_PROPERTY))
    val releasesFirst: Boolean get() = releaseFor(systemProperty(RELEASE_PROPERTY))

    fun onFor(property: String?): Boolean = when (property?.trim()) {
        "1" -> true
        "0" -> false
        else -> ON_BY_DEFAULT
    }
    fun smallInputFor(property: String?): Boolean = property?.trim() != "0"
    fun releaseFor(property: String?): Boolean = property?.trim() != "0"

    /** Never under the keep floor plus a margin, or a decoder would close right after it opened. */
    fun openFloorFor(property: String?): Long =
        property?.trim()?.toLongOrNull()?.takeIf { it in MIN_OPEN_FLOOR_MB..MAX_OPEN_FLOOR_MB } ?: OPEN_FLOOR_MB

    /** What an open is charged until the box has measured its own decoder (a Fire TV Stick 4K Max measured 208 to 218). */
    const val DEFAULT_COST_MB = 250
    const val MIN_COST_MB = 150
    const val MAX_COST_MB = 600
    /** A drop outside this range is not the decoder's own: it closed before its picture, or another app moved memory. */
    const val MIN_SEEN_MB = 40L
    const val MAX_SEEN_MB = 900L

    /** MemAvailable after the decoder's cost, and SwapFree, a decoder needs to open. */
    const val OPEN_FLOOR_MB = 150L
    const val MIN_OPEN_FLOOR_MB = 130L
    const val MAX_OPEN_FLOOR_MB = 200L
    /**
     * Opening the decoder pushes 60 to 120 MB of other pages into swap over the next seconds. On a Fire TV Stick 4K Max
     * opens with 107 to 116 MB of swap free closed within 22 s; with 171 MB or more the decoder held through the set.
     */
    const val OPEN_SWAP_MB = 150L
    /** An open decoder keeps going down to these; below, it closes and the title finishes on software. */
    const val KEEP_FLOOR_MB = 120L
    /** The swap line while MemAvailable is at least [KEEP_SWAP_AVAIL_MB]; [KEEP_SWAP_TIGHT_MB] below that. */
    const val KEEP_SWAP_MB = 30L
    const val KEEP_SWAP_TIGHT_MB = 48L
    const val KEEP_SWAP_AVAIL_MB = 150L

    /** The film's decoder counts as gone this long after the let-go, or once MemAvailable rose by [FILM_DECODER_BACK_MB]. */
    const val LET_GO_SETTLE_MS = 2_000L
    const val FILM_DECODER_BACK_MB = 150L

    /** Before the film loads again: the longest wait for a running decode to end and close its decoder ... */
    const val CLOSE_WAIT_MS = 3_500L
    /** ... and then for MemAvailable to come back within [BACK_WITHIN_MB] of its value before the decoder opened. */
    const val BACK_WAIT_MS = 1_500L
    const val BACK_WITHIN_MB = 60L
    const val POLL_MS = 100L

    const val OFF_TEXT = "4K on a small box whose decoder takes its pictures from app memory (about 210 MB on a Fire TV Stick)"

    /** Null when this 4K title may go to the hardware decoder now, else why it stays on software. */
    fun softwareBecause(
        on: Boolean,
        screenUp: Boolean,
        playerLetGo: Boolean,
        sinceLetGoMs: Long,
        availNowMb: () -> Long,
        availAtLetGoMb: Long,
    ): String? = when {
        !on -> OFF_TEXT
        !screenUp || !playerLetGo -> "$OFF_TEXT, only behind the generating screen with the film let go"
        !filmLetGo(sinceLetGoMs, availNowMb, availAtLetGoMb) -> "waiting for the film's decoder to let go"
        else -> null
    }

    fun filmLetGo(sinceLetGoMs: Long, availNowMb: () -> Long, availAtLetGoMb: Long): Boolean {
        if (sinceLetGoMs >= LET_GO_SETTLE_MS) return true
        if (availAtLetGoMb < 0) return false
        val now = availNowMb()
        return now >= 0 && now - availAtLetGoMb >= FILM_DECODER_BACK_MB
    }

    fun costMb(learnedMb: Int?): Int = learnedMb?.coerceIn(MIN_COST_MB, MAX_COST_MB) ?: DEFAULT_COST_MB

    /** The cost to remember after a measured drop of [seenMb], or null when the drop is not a usable measurement. */
    fun learn(oldMb: Int?, seenMb: Long): Int? {
        if (seenMb !in MIN_SEEN_MB..MAX_SEEN_MB) return null
        val next = if (oldMb == null) seenMb.toInt() else (oldMb + seenMb.toInt() + 1) / 2
        return next.coerceIn(MIN_COST_MB, MAX_COST_MB)
    }

    /** A cold decoder is charged [costMb] against the open floors; an open one is held to the keep floors. */
    fun verdict(mem: MemoryGovernor.Meminfo, warm: Boolean, costMb: Int, openFloorMb: Long = OPEN_FLOOR_MB): GovernorVerdict {
        val charge = if (warm) 0L else costMb.toLong()
        val floor = if (warm) KEEP_FLOOR_MB else openFloorMb
        val swapFloor = if (!warm) OPEN_SWAP_MB else keepSwapMb(mem.availableMb)
        if (mem.availableMb - charge < floor) {
            return GovernorVerdict(false, "MemAvailable ${mem.availableMb} MB - $charge < floor $floor (4K hardware)")
        }
        if (mem.swapTotalMb > 0 && mem.swapFreeMb < swapFloor) {
            return GovernorVerdict(false, "SwapFree ${mem.swapFreeMb} MB < $swapFloor (4K hardware)", mem.swapFreeMb)
        }
        return GovernorVerdict(true, "ok avail=${mem.availableMb} swapFree=${mem.swapFreeMb} 4K hardware" +
            if (warm) "" else " cost $costMb")
    }

    /** The swap line for an open decoder: lower while the box still has room, so swap noise alone does not close it. */
    fun keepSwapMb(availableMb: Long): Long = if (availableMb >= KEEP_SWAP_AVAIL_MB) KEEP_SWAP_MB else KEEP_SWAP_TIGHT_MB

    fun memoryBack(availNowMb: Long, availBeforeOpenMb: Long): Boolean =
        availBeforeOpenMb < 0 || (availNowMb >= 0 && availNowMb >= availBeforeOpenMb - BACK_WITHIN_MB)

    /**
     * Whether the film may load again. [closedForMs] < 0 while a thumbnail decoder is still open: it is waited for up to
     * [CLOSE_WAIT_MS] in all; once closed, MemAvailable is waited for up to [BACK_WAIT_MS].
     */
    fun filmMayLoad(waitedMs: Long, closedForMs: Long, availNowMb: Long, availBeforeOpenMb: Long): Boolean =
        if (closedForMs < 0) waitedMs >= CLOSE_WAIT_MS
        else closedForMs >= BACK_WAIT_MS || memoryBack(availNowMb, availBeforeOpenMb)

    private fun systemProperty(name: String): String? = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as? String
    }.getOrNull()
}

/**
 * Before the first 4K hardware open: what the thumbnails hold themselves is given back step by step, each step with
 * MemAvailable before and after it, so one box run shows what each is worth.
 */
internal class AppMemoryRelease(private val startMb: Long) {
    class Step(val what: String, val beforeMb: Long, val afterMb: Long) {
        val gainedMb: Long get() = if (beforeMb < 0 || afterMb < 0) 0L else afterMb - beforeMb
        fun line(): String = "4K hardware open, $what: MemAvailable $beforeMb -> $afterMb MB"
    }

    companion object {
        /** Freed pages show in MemAvailable a moment later; the wait ends early once it stops rising. */
        const val SETTLE_MS = 800L
        const val SETTLE_POLL_MS = 100L
    }

    private val done = ArrayList<Step>()
    var lastMb: Long = startMb
        private set

    fun step(what: String, afterMb: Long): Step = Step(what, lastMb, afterMb).also {
        done += it
        if (afterMb >= 0) lastMb = afterMb
    }

    val steps: List<Step> get() = done

    fun summary(): String {
        val total = if (startMb < 0 || lastMb < 0) 0L else lastMb - startMb
        return "4K hardware open: MemAvailable $startMb -> $lastMb MB after giving back the thumbnails' own memory " +
            "(${if (total >= 0) "+" else ""}$total MB: " +
            done.joinToString { "${it.what} ${if (it.gainedMb >= 0) "+" else ""}${it.gainedMb}" } + ")"
    }
}

/**
 * The input buffer the hardware decoder is asked for: the keyframe plus a quarter, at least [MIN_BYTES], rounded up to
 * a MB. A bigger keyframe reopens the decoder at twice the size or more, never above the decoder's maximum.
 */
internal object HardwareInputSize {
    const val MIN_BYTES = 1 shl 20

    /** [current]: the size the decoder has now, 0 before it opened. */
    fun wanted(keyframeBytes: Int, current: Int, max: Int): Int {
        if (current >= keyframeBytes && current > 0) return current
        val quarterMore = keyframeBytes.toLong() + keyframeBytes / 4
        val rounded = (quarterMore + MIN_BYTES - 1) / MIN_BYTES * MIN_BYTES
        val grown = maxOf(rounded, MIN_BYTES.toLong(), 2L * current)
        return minOf(grown, max.toLong()).toInt()
    }
}

/** The 4K hardware decoder's measured cost per box and format, in MB. */
internal class HardwareCostBook(private val memory: HardwareFailureMemory) {
    companion object {
        fun key(box: String, codec: VideoCodec): String = "cost|$box|${codec.name}|4k"
    }

    fun get(key: String): Int? = memory.get(key)?.toIntOrNull()

    /** The cost now remembered, or null when [seenMb] was not used. */
    fun note(key: String, seenMb: Long): Int? {
        val next = AppMemoryHardware.learn(get(key), seenMb) ?: return null
        memory.put(key, next.toString())
        return next
    }
}
