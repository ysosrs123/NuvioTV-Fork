package com.nuvio.tv.core.player.thumbnail

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Process
import android.util.Log
import java.io.File

/** Decode cost classes: resident memory and address space one software decode takes. */
internal enum class DecodeTier(val rssMb: Int, val addressSpaceMb: Int) {
    SW_UP_TO_1080P(rssMb = 35, addressSpaceMb = 45),
    SW_4K(rssMb = 80, addressSpaceMb = 85),
}

internal data class GovernorVerdict(val allowed: Boolean, val reason: String)

/** Live-headroom gate in front of every decode: thumbnails only take memory the box can spare right now. */
internal object MemoryGovernor {
    private const val TAG = "ThumbMem"
    private const val LOW_RAM_TOTAL_BYTES = 2_300L * 1024 * 1024   // matches MemoryBudget's 2.3 GB tier edge
    private const val FLOOR_LOW_RAM_MB = 200L
    private const val FLOOR_DEFAULT_MB = 300L
    /** lmkd reports "swap is low" at about 37 MB on the Fire TV Stick. */
    private const val SWAP_FLOOR_MB = 64L
    /**
     * Floors while playback's read-ahead is held below its budget for a decode. Fire OS refills freed memory with
     * cached services (MemAvailable stays at 167-265 MB on the Fire TV Stick), so the normal floor would never pass.
     */
    private const val FUNDED_FLOOR_LOW_RAM_MB = 100L
    private const val FUNDED_FLOOR_DEFAULT_MB = 150L
    /** 2.5 GiB: boxes sold as 3 GB report about 2.7-2.9 GB, 2 GB boxes stay below. */
    private const val STRONG_BOX_TOTAL_BYTES = 2_560L * 1024 * 1024
    /** 32-bit only: ceiling for VmSize plus the decode's cost, keeps about 700 MB of the 4 GB address space spare. */
    private const val VA_CEILING_32BIT_MB = 3_300L

    @Volatile private var lowRam = true
    @Volatile private var is64Bit = false
    @Volatile private var totalMemBytes = 0L

    /** 64-bit process on a 3 GB or bigger box: 4K thumbnails may also decode during playback. */
    val isStrongBox: Boolean get() = is64Bit && totalMemBytes >= STRONG_BOX_TOTAL_BYTES
    @Volatile private var registered = false

    /** Listeners told to drop decoder contexts / caches (the active session registers itself). */
    private val trimListeners = java.util.concurrent.CopyOnWriteArraySet<(Int) -> Unit>()

    fun init(context: Context) {
        if (registered) return
        val app = context.applicationContext
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val info = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(info)
        lowRam = am == null || am.isLowRamDevice || info.totalMem in 1 until LOW_RAM_TOTAL_BYTES
        is64Bit = Process.is64Bit()
        totalMemBytes = info.totalMem
        app.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                // RUNNING_LOW and above while visible, or anything once in the background.
                if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
                    Log.i(TAG, "onTrimMemory level=$level: trimming thumbnail memory")
                    trimListeners.forEach { runCatching { it(level) } }
                }
            }

            override fun onConfigurationChanged(newConfig: Configuration) = Unit

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() {
                trimListeners.forEach { runCatching { it(ComponentCallbacks2.TRIM_MEMORY_COMPLETE) } }
            }
        })
        registered = true
        Log.i(TAG, "init lowRam=$lowRam process64=$is64Bit strongBox=$isStrongBox totalMb=${totalMemBytes / (1024 * 1024)}")
    }

    /** Worst case shown in settings: the memory LRU at 320x240 plus the largest permitted decode tier. Display only. */
    fun displayChargeMb(rgb565: Boolean, fourK: Boolean): Int {
        val tier = if (fourK) DecodeTier.SW_4K else DecodeTier.SW_UP_TO_1080P
        val lruBytes = ThumbStore.MEM_FRAMES.toLong() * ThumbGeometry.MAX_WIDTH * 240 * (if (rgb565) 2 else 4)
        return tier.rssMb + ((lruBytes + (1 shl 20) - 1) shr 20).toInt()
    }

    fun addTrimListener(l: (Int) -> Unit) = trimListeners.add(l)
    fun removeTrimListener(l: (Int) -> Unit) = trimListeners.remove(l)

    fun tierFor(frameWidth: Int, frameHeight: Int): DecodeTier =
        if (frameWidth > 1920 || frameHeight > 1088) DecodeTier.SW_4K else DecodeTier.SW_UP_TO_1080P

    /**
     * [decoderWarm]: the working set is already inside MemAvailable, so only a cold decoder is charged the tier's RSS.
     * [funded]: playback's read-ahead is held back for this decode, so the funded floor applies and the swap floor
     * does not (SwapFree sits at 0-141 MB during 4K remux playback on the Fire TV Stick).
     */
    fun allow(tier: DecodeTier, decoderWarm: Boolean = false, funded: Boolean = false): GovernorVerdict {
        // When 4K may decode is the scheduler's rule, this only checks that it is affordable.
        if (tier == DecodeTier.SW_4K && !is64Bit && !decoderWarm) {
            val vm = readVmSizeMb()
            if (vm != null && vm + tier.addressSpaceMb > VA_CEILING_32BIT_MB) {
                return GovernorVerdict(false, "32-bit address space ${vm} MB + ${tier.addressSpaceMb} > $VA_CEILING_32BIT_MB")
            }
        }
        val mem = readMeminfo() ?: return GovernorVerdict(true, "meminfo unavailable")
        val floor = when {
            funded -> if (lowRam) FUNDED_FLOOR_LOW_RAM_MB else FUNDED_FLOOR_DEFAULT_MB
            else -> if (lowRam) FLOOR_LOW_RAM_MB else FLOOR_DEFAULT_MB
        }
        val charge = if (decoderWarm) 0 else tier.rssMb
        val availAfter = mem.availableMb - charge
        if (availAfter < floor) return GovernorVerdict(false, "MemAvailable ${mem.availableMb} MB - $charge < floor $floor")
        if (!funded && mem.swapTotalMb > 0 && mem.swapFreeMb < SWAP_FLOOR_MB) {
            return GovernorVerdict(false, "SwapFree ${mem.swapFreeMb} MB < $SWAP_FLOOR_MB")
        }
        return GovernorVerdict(true, "ok avail=${mem.availableMb} swapFree=${mem.swapFreeMb}${if (funded) " funded" else ""}")
    }

    /** MB of MemAvailable a [tier] decode lacks to clear the floor plus [marginMb], or null without /proc/meminfo. */
    fun shortfallMb(tier: DecodeTier, decoderWarm: Boolean, marginMb: Long): Long? {
        val mem = readMeminfo() ?: return null
        val floor = if (lowRam) FLOOR_LOW_RAM_MB else FLOOR_DEFAULT_MB
        val charge = if (decoderWarm) 0 else tier.rssMb
        return (floor + charge + marginMb - mem.availableMb).coerceAtLeast(0L)
    }

    internal class Meminfo(val availableMb: Long, val swapTotalMb: Long, val swapFreeMb: Long)

    internal fun parseMeminfo(text: String): Meminfo? {
        var avail = -1L
        var swapTotal = 0L
        var swapFree = 0L
        for (line in text.lineSequence()) {
            val kb = line.substringAfter(':', "").trim().substringBefore(' ').toLongOrNull() ?: continue
            when {
                line.startsWith("MemAvailable:") -> avail = kb / 1024
                line.startsWith("SwapTotal:") -> swapTotal = kb / 1024
                line.startsWith("SwapFree:") -> swapFree = kb / 1024
            }
        }
        return if (avail >= 0) Meminfo(avail, swapTotal, swapFree) else null
    }

    private fun readMeminfo(): Meminfo? = runCatching { parseMeminfo(File("/proc/meminfo").readText()) }.getOrNull()

    internal fun parseVmSizeMb(status: String): Long? =
        status.lineSequence().firstOrNull { it.startsWith("VmSize:") }
            ?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLongOrNull()?.let { it / 1024 }

    private fun readVmSizeMb(): Long? = runCatching { parseVmSizeMb(File("/proc/self/status").readText()) }.getOrNull()
}
