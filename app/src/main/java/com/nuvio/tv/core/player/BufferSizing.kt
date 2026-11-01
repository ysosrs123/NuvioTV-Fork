package com.nuvio.tv.core.player

import android.app.ActivityManager
import android.content.Context

/**
 * Automatic playback buffer size: one size per box class, lowered at each player build to what the box has free.
 */
object BufferSizing {
    private const val MB = 1024L * 1024L
    private const val GB = 1024L * MB

    // Automatic buffer per box, by the total memory Android reports. The 1 GB and 1.5 GB sizes are scaled from 2 GB.
    const val BUFFER_1GB_BOX_MB = 100
    const val BUFFER_1_5GB_BOX_MB = 125
    const val BUFFER_2GB_BOX_MB = 200
    const val BUFFER_3GB_BOX_MB = 500
    const val BUFFER_4GB_BOX_MB = 1000
    const val BUFFER_6GB_BOX_MB = 1600
    const val BUFFER_8GB_BOX_MB = 2000

    /** Same edge as the 2 GB size above: boxes sold as 2 GB or less. */
    val SMALL_BOX_RAM_BYTES: Long = (2.3 * GB).toLong()

    const val SMALL_BOX_CONNECTIONS = 2
    const val NATIVE_MEMORY_CONNECTIONS = 4

    // Kept free above Android's low-memory line, so the box does not start killing apps and swapping.
    const val SYSTEM_MARGIN_MB = 100
    // Kept free when the buffer is sized at the start: Android closes idle apps to hold its own line, and the
    // memory keeper trims the buffer if the box really runs short later.
    const val SIZING_MARGIN_MB = 28
    // Still to come once the player is built: the video decoder and its pictures, about 140 MB on 2 GB boxes.
    // The native allocator's 32 MB arena is not reserved: it holds the first 32 MB of the buffer itself.
    const val VIDEO_OUTPUT_RESERVE_MB = 140
    // A 4K decoder whose pictures live in app memory (a Fire TV Stick 4K Max: no video memory pool the app can
    // read) took 195 to 240 MB at the first frame.
    const val VIDEO_OUTPUT_RESERVE_4K_MB = 220
    /** A bitrate only a 4K file carries; 1080p disc remuxes stay under 40 Mbit/s. */
    const val FOUR_K_BITRATE_BPS = 50_000_000L
    /** About 5 s of a 4K remux at 80 Mbit/s. */
    const val MIN_SIZED_BUFFER_MB = 50

    /** The bitrate the back buffer is sized for while the film's is unknown, a 4K remux at about 80 Mbit/s. */
    const val DEFAULT_BACK_BUFFER_BYTES_PER_SECOND = 10L * MB
    /** 8 Mbit/s: a wrong file size cannot make the back buffer crowd out the buffer ahead. */
    private const val MIN_BACK_BUFFER_BYTES_PER_SECOND = 1L * MB
    /** Buffer ahead that the back buffer always leaves room for. */
    const val MIN_AHEAD_MS = 5_000L

    fun autoBufferMbForRam(totalMem: Long): Int = when {
        totalMem <= 0L -> BUFFER_2GB_BOX_MB
        totalMem < 1.15 * GB -> BUFFER_1GB_BOX_MB
        totalMem < 1.45 * GB -> BUFFER_1_5GB_BOX_MB
        totalMem < 2.3 * GB -> BUFFER_2GB_BOX_MB
        totalMem < 3.2 * GB -> BUFFER_3GB_BOX_MB
        totalMem < 4.8 * GB -> BUFFER_4GB_BOX_MB
        totalMem < 6.8 * GB -> BUFFER_6GB_BOX_MB
        else -> BUFFER_8GB_BOX_MB
    }

    fun isSmallBox(totalMem: Long): Boolean = totalMem <= 0L || totalMem < SMALL_BOX_RAM_BYTES

    /** What the sizing keeps for the video decoder and its pictures. */
    fun videoOutputReserveMb(fourKInAppMemory: Boolean): Int =
        if (fourKInAppMemory) VIDEO_OUTPUT_RESERVE_4K_MB else VIDEO_OUTPUT_RESERVE_MB

    /** Whether the film looks like 4K before its first byte: a name that says so, or a bitrate only 4K carries. */
    fun looksFourK(bitrateBps: Long, vararg names: String?): Boolean =
        bitrateBps >= FOUR_K_BITRATE_BPS || names.any { it != null && UHD.containsMatchIn(it) }

    private val UHD = Regex("""2160p|\buhd\b|\b4k\b""", RegexOption.IGNORE_CASE)

    /** Parallel connections when the user has not picked a number. */
    fun defaultConnectionCount(totalMem: Long, nativeMemory: Boolean, fallback: Int): Int = when {
        !nativeMemory -> fallback
        isSmallBox(totalMem) -> SMALL_BOX_CONNECTIONS
        else -> NATIVE_MEMORY_CONNECTIONS
    }

    data class FreeMemory(val availBytes: Long, val thresholdBytes: Long, val lowMemory: Boolean) {
        val availMb: Long get() = availBytes / MB
        val thresholdMb: Long get() = thresholdBytes / MB
    }

    fun read(context: Context): FreeMemory? = runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        if (info.availMem <= 0L) null else FreeMemory(info.availMem, info.threshold.coerceAtLeast(0L), info.lowMemory)
    }.getOrNull()

    /**
     * MB the buffer can take before the box drops below its low-memory line plus [SIZING_MARGIN_MB].
     * [outsideBudgetMb] is memory the player holds outside this budget (the parallel chunk window);
     * [heldByPlayerMb] is buffer memory already held that the new player takes over or frees;
     * [fourKInAppMemory] keeps [VIDEO_OUTPUT_RESERVE_4K_MB] for the decoder instead of [VIDEO_OUTPUT_RESERVE_MB].
     */
    fun spareMb(free: FreeMemory, outsideBudgetMb: Int, heldByPlayerMb: Int, fourKInAppMemory: Boolean = false): Long =
        free.availMb - free.thresholdMb - SIZING_MARGIN_MB - videoOutputReserveMb(fourKInAppMemory) -
            outsideBudgetMb + heldByPlayerMb

    /** The box's size, lowered to what is free now, never below [MIN_SIZED_BUFFER_MB] (or the box's size if smaller). */
    fun sizedBudgetMb(
        boxMb: Int,
        free: FreeMemory?,
        outsideBudgetMb: Int,
        heldByPlayerMb: Int,
        fourKInAppMemory: Boolean = false
    ): Int {
        if (free == null) return boxMb
        val floor = minOf(MIN_SIZED_BUFFER_MB, boxMb)
        if (free.lowMemory) return floor
        return spareMb(free, outsideBudgetMb, heldByPlayerMb, fourKInAppMemory)
            .coerceIn(floor.toLong(), boxMb.toLong()).toInt()
    }

    /** Bytes per second of the film from its average bitrate in bits per second; the 80 Mbit/s default while unknown. */
    fun backBufferBytesPerSecond(fileBitrateBps: Long): Long =
        if (fileBitrateBps <= 0L) DEFAULT_BACK_BUFFER_BYTES_PER_SECOND
        else (fileBitrateBps / 8L).coerceAtLeast(MIN_BACK_BUFFER_BYTES_PER_SECOND)

    /**
     * Every byte of the back buffer counts against the target, and it moves with playback instead of shrinking.
     * At [bytesPerSecond] it takes at most a third of [targetBytes] and leaves [MIN_AHEAD_MS] ahead.
     * A positive [minBufferMs] holds it to half of that, as the official app does with native memory. With
     * [maxBufferMs] it only gets what is left once the buffer ahead holds what the official app would keep in
     * [referenceBytes] (up to [maxBufferMs]), so a target lowered to the free memory comes out of the back buffer first.
     */
    fun backBufferMsForTarget(
        backBufferMs: Int,
        targetBytes: Long,
        bytesPerSecond: Long = DEFAULT_BACK_BUFFER_BYTES_PER_SECOND,
        minBufferMs: Int = 0,
        maxBufferMs: Int = 0,
        referenceBytes: Long = targetBytes
    ): Int {
        if (backBufferMs <= 0) return 0
        val rate = bytesPerSecond.takeIf { it > 0L } ?: DEFAULT_BACK_BUFFER_BYTES_PER_SECOND
        // Official keeps at most Min/2 behind; that only decides what stays ahead, the setting itself is kept when it fits.
        val officialBackMs = if (minBufferMs > 0) backBufferMs.coerceAtMost(minBufferMs / 2) else backBufferMs
        var aheadBytes = MIN_AHEAD_MS * rate / 1000L
        if (maxBufferMs > 0) {
            val officialAheadBytes = (referenceBytes - officialBackMs.toLong() * rate / 1000L).coerceAtLeast(0L)
            aheadBytes = maxOf(aheadBytes, minOf(officialAheadBytes, maxBufferMs.toLong() * rate / 1000L))
        }
        val backBytes = minOf(targetBytes / 3, targetBytes - aheadBytes).coerceAtLeast(0L)
        val keptMs = backBufferMs.toLong().coerceAtMost(backBytes * 1000L / rate).toInt()
        // Cut by memory to less than one 10 s skip back, it is never used: that memory serves the buffer ahead instead.
        return if (keptMs < backBufferMs && keptMs < MIN_USEFUL_BACK_MS) 0 else keptMs
    }

    const val MIN_USEFUL_BACK_MS = 10_000

    /** Bytes [backBufferMs] holds at [bytesPerSecond]. */
    fun backBufferBytes(backBufferMs: Int, bytesPerSecond: Long): Long {
        val rate = bytesPerSecond.takeIf { it > 0L } ?: DEFAULT_BACK_BUFFER_BYTES_PER_SECOND
        return backBufferMs.coerceAtLeast(0).toLong() * rate / 1000L
    }

    /** The budget the back buffer was fitted to at the last player build, for the settings note. */
    data class PlaybackBudget(val nativeMemory: Boolean, val automatic: Boolean, val budgetMb: Int)

    @Volatile
    var lastPlaybackBudget: PlaybackBudget? = null

    fun describe(
        boxMb: Int,
        sizedMb: Int,
        free: FreeMemory?,
        outsideBudgetMb: Int,
        heldByPlayerMb: Int,
        fourKInAppMemory: Boolean = false
    ): String =
        if (free == null) "boxMb=$boxMb sizedMb=$sizedMb free=unknown"
        else "boxMb=$boxMb sizedMb=$sizedMb availMb=${free.availMb} thresholdMb=${free.thresholdMb} " +
            "lowMemory=${free.lowMemory} windowMb=$outsideBudgetMb heldMb=$heldByPlayerMb " +
            "reserveMb=${videoOutputReserveMb(fourKInAppMemory)}"
}
