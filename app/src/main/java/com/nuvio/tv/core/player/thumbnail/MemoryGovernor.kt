package com.nuvio.tv.core.player.thumbnail

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Process
import android.util.Log
import com.nuvio.tv.core.player.MemoryPressure
import java.io.File

/** Decode cost classes: resident memory and address space one software decode takes. */
internal enum class DecodeTier(val rssMb: Int, val addressSpaceMb: Int) {
    SW_UP_TO_1080P(rssMb = 35, addressSpaceMb = 45),
    SW_4K(rssMb = 80, addressSpaceMb = 85),
    /** Two strip decoders of about 10 MB each (StripDecode). */
    SW_4K_STRIPS(rssMb = 25, addressSpaceMb = 30),
    /** A hardware decoder: its pictures sit in the video memory pool, the app keeps a small GPU target and one bitmap. */
    HW(rssMb = 8, addressSpaceMb = 16),
}

/** [swapFreeMb] >= 0 when the refusal was for swap. */
internal data class GovernorVerdict(val allowed: Boolean, val reason: String, val swapFreeMb: Long = -1L) {
    val swapLow: Boolean get() = !allowed && swapFreeMb >= 0
}

/**
 * Whether a session's coverage pass stops for memory: at once after an OutOfMemoryError, or once the box has stayed
 * short of memory for [stopAfterMs] (asked at most every [checkEveryMs]). Once stopped it stays stopped: nothing is
 * retried for the title. Not thread-safe.
 */
internal class MemoryStop(private val checkEveryMs: Long = 5_000L, private val stopAfterMs: Long = 30_000L) {
    var stopped = false
        private set
    var reason: String? = null
        private set
    private var checkedAt = Long.MIN_VALUE
    private var shortSince = -1L

    /** Tapped keyframes and a copy pass stop with the coverage pass. */
    val allowsSideWork: Boolean get() = !stopped

    /** True the first time only. */
    fun outOfMemory(why: String): Boolean {
        if (stopped) return false
        stopped = true
        reason = why
        return true
    }

    /** [pressure] says why memory is short now, or null. */
    fun check(now: Long, pressure: () -> String?): Boolean {
        if (stopped) return true
        if (checkedAt != Long.MIN_VALUE && now - checkedAt < checkEveryMs) return false
        checkedAt = now
        val why = pressure()
        if (why == null) {
            shortSince = -1L
            return false
        }
        if (shortSince < 0) shortSince = now
        if (now - shortSince < stopAfterMs) return false
        stopped = true
        reason = why
        return true
    }
}

/** Live-headroom gate in front of every decode: thumbnails only take memory the box can spare right now. */
internal object MemoryGovernor {
    private const val TAG = "ThumbMem"
    private const val MB = 1024L * 1024L
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
    /** Funded decodes still stop just above lmkd's swap-low line (about 40 MB on the Fire TV Stick). */
    private const val FUNDED_SWAP_FLOOR_MB = 48L
    /**
     * On a 3 GB or bigger box the swap floors only count once MemAvailable is within this much of the decode floor: a
     * Shield with full swap still had 583-794 MB available, and lmkd does not kill on swap alone while that much is free.
     */
    private const val STRONG_SWAP_ROOM_MB = 200L

    /**
     * The same for smaller boxes, with a wider margin: Google TV keeps a 2 GB Xiaomi's swap nearly full (62 MB free) while
     * 658 MB is still available, and swap alone then stopped every thumbnail.
     */
    private const val SMALL_SWAP_ROOM_MB = 300L
    /** Below this a small box never decodes, whatever is available: lmkd's swap-low line is about 40 MB on a Fire TV Stick. */
    private const val SMALL_SWAP_ROOM_MIN_SWAP_MB = 48L
    /** 2.5 GiB: boxes sold as 3 GB report about 2.7-2.9 GB, 2 GB boxes stay below. */
    private const val STRONG_BOX_TOTAL_BYTES = 2_560L * 1024 * 1024
    /** The smallest Java heap limit (dalvik.vm.heapsize) the strong 4K path has run on. */
    private const val STRONG_MIN_HEAP_BYTES = 384L * 1024 * 1024
    /**
     * Java heap still free at session start: the strong path keeps up to 32 MB of saved pictures, the playback tap's
     * 8 MB buffer and a few keyframe-sized copies of tapped samples on the heap.
     */
    private const val STRONG_MIN_HEAP_FREE_BYTES = 64L * 1024 * 1024
    /**
     * MemAvailable at session start: the decode floor (300 MB), two 4K decoders (2 x 80 MB) and the 4K read-ahead still
     * to fill (about 140 MB).
     */
    private const val STRONG_MIN_AVAIL_MB = 600L
    private const val STRONG_MIN_SWAP_FREE_MB = 2 * SWAP_FLOOR_MB
    /** 32-bit only: ceiling for VmSize plus the decode's cost, keeps about 700 MB of the 4 GB address space spare. */
    private const val VA_CEILING_32BIT_MB = 3_300L

    @Volatile private var lowRam = true
    @Volatile private var is64Bit = false
    @Volatile private var totalMemBytes = 0L
    @Volatile private var strongBox = false
    @Volatile private var activityManager: ActivityManager? = null

    /**
     * 64-bit process on a 3 GB or bigger box with a big enough Java heap, and memory to spare when the session started:
     * 4K thumbnails may also decode during playback. Otherwise the protected path (playback monitor, pacer) runs.
     */
    val isStrongBox: Boolean get() = strongBox

    /** Under 2.3 GB, or Android's own low-RAM flag. */
    val isLowRam: Boolean get() = lowRam

    /**
     * The box class alone (64-bit, RAM, Java heap limit), without the memory free at the moment: for media-server
     * thumbnails, the disk budget and the settings figure, which have no session of their own to decide in.
     */
    val isStrongHardware: Boolean get() = strongHardware

    /** Total RAM in MB, 0 when not known. */
    val totalMemMb: Long get() = totalMemBytes / MB
    @Volatile private var strongHardware = false
    @Volatile private var registered = false

    /** A 2 GB class box (or a 32-bit app or a small Java heap): 4K films load after the generating screen. */
    fun isSmallHardware(context: Context): Boolean {
        init(context)
        return !strongHardware
    }

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
        activityManager = am
        strongHardware = strongHardware(is64Bit, totalMemBytes, Runtime.getRuntime().maxMemory())
        strongBox = decideStrongBox(info).allowed
        MemoryPressure.register(app)
        // RUNNING_LOW and above while visible, or anything once in the background.
        MemoryPressure.addListener(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) { level ->
            Log.i(TAG, "onTrimMemory level=$level: trimming thumbnail memory")
            trimListeners.forEach { runCatching { it(level) } }
        }
        registered = true
        Log.i(TAG, "init lowRam=$lowRam process64=$is64Bit strongBox=$isStrongBox strongHardware=$strongHardware totalMb=${totalMemBytes / (1024 * 1024)}")
    }

    /**
     * Before each thumbnail session, off the main thread: decides the strong path again from the memory the box has
     * right now.
     */
    fun initForSession(context: Context) {
        init(context)
        val info = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(info)
        val v = decideStrongBox(info) { Runtime.getRuntime().gc() }
        strongBox = v.allowed
        Log.i(TAG, "session strongBox=${v.allowed}: ${v.reason}")
    }

    private fun decideStrongBox(info: ActivityManager.MemoryInfo, collect: () -> Unit = {}): GovernorVerdict {
        val mem = readMeminfo()
        val rt = Runtime.getRuntime()
        return verdictAfterCollect({ rt.totalMemory() - rt.freeMemory() }, collect) { used ->
            strongBoxVerdict(
                is64Bit = is64Bit,
                totalMemBytes = totalMemBytes,
                heapMaxBytes = rt.maxMemory(),
                heapUsedBytes = used,
                lowMemory = info.lowMemory,
                availMb = if (info.totalMem > 0) info.availMem / MB else -1L,
                swapTotalMb = mem?.swapTotalMb ?: 0L,
                swapFreeMb = mem?.swapFreeMb ?: 0L,
            )
        }
    }

    /**
     * The heap in use counts garbage not collected yet: when the free-heap rule is the only one that fails, [collect]
     * runs once and the heap is measured again.
     */
    internal fun verdictAfterCollect(
        heapUsed: () -> Long, collect: () -> Unit, verdict: (Long) -> GovernorVerdict,
    ): GovernorVerdict {
        val first = verdict(heapUsed())
        if (first.allowed || !verdict(0L).allowed) return first
        collect()
        val again = verdict(heapUsed())
        return if (again.allowed) again.copy(reason = again.reason + " (after GC)") else again
    }

    internal fun strongHardware(is64Bit: Boolean, totalMemBytes: Long, heapMaxBytes: Long): Boolean =
        is64Bit && totalMemBytes >= STRONG_BOX_TOTAL_BYTES && heapMaxBytes >= STRONG_MIN_HEAP_BYTES

    /** [availMb] < 0: not known. Swap is only checked where the box has some. */
    internal fun strongBoxVerdict(
        is64Bit: Boolean, totalMemBytes: Long, heapMaxBytes: Long, heapUsedBytes: Long, lowMemory: Boolean,
        availMb: Long, swapTotalMb: Long, swapFreeMb: Long,
    ): GovernorVerdict = when {
        !is64Bit -> GovernorVerdict(false, "32-bit process")
        totalMemBytes < STRONG_BOX_TOTAL_BYTES -> GovernorVerdict(false, "RAM ${totalMemBytes / MB} MB")
        heapMaxBytes < STRONG_MIN_HEAP_BYTES ->
            GovernorVerdict(false, "Java heap limit ${heapMaxBytes / MB} MB < ${STRONG_MIN_HEAP_BYTES / MB}")
        heapMaxBytes - heapUsedBytes < STRONG_MIN_HEAP_FREE_BYTES ->
            GovernorVerdict(false, "Java heap free ${(heapMaxBytes - heapUsedBytes) / MB} MB < " +
                "${STRONG_MIN_HEAP_FREE_BYTES / MB}")
        lowMemory -> GovernorVerdict(false, "system low on memory")
        availMb in 0 until STRONG_MIN_AVAIL_MB -> GovernorVerdict(false, "available $availMb MB < $STRONG_MIN_AVAIL_MB")
        swapTotalMb > 0 && swapFreeMb < STRONG_MIN_SWAP_FREE_MB ->
            GovernorVerdict(false, "SwapFree $swapFreeMb MB < $STRONG_MIN_SWAP_FREE_MB")
        else -> GovernorVerdict(true, "RAM ${totalMemBytes / MB} MB, heap ${heapUsedBytes / MB}/${heapMaxBytes / MB} MB, " +
            "available ${if (availMb >= 0) "$availMb MB" else "unknown"}, " +
            "SwapFree ${if (swapTotalMb > 0) "$swapFreeMb MB" else "no swap"}")
    }

    /** Why the box is short of memory right now (the low-memory flag, MemAvailable or swap under its floor), or null. */
    fun pressure(): String? {
        val info = ActivityManager.MemoryInfo()
        val am = activityManager
        am?.getMemoryInfo(info)
        val mem = readMeminfo()
        return pressureReason(am != null && info.lowMemory, mem?.availableMb ?: -1L, mem?.swapTotalMb ?: 0L,
            mem?.swapFreeMb ?: 0L, if (lowRam) FLOOR_LOW_RAM_MB else FLOOR_DEFAULT_MB, strongHardware)
    }

    internal fun pressureReason(
        lowMemory: Boolean, availMb: Long, swapTotalMb: Long, swapFreeMb: Long, floorMb: Long,
        strongHardware: Boolean = false,
    ): String? =
        when {
            lowMemory -> "system low on memory"
            availMb in 0 until floorMb -> "MemAvailable $availMb MB < $floorMb"
            swapTotalMb > 0 && swapFreeMb < SWAP_FLOOR_MB && !roomBesideSwap(availMb, floorMb, strongHardware, swapFreeMb) ->
                "SwapFree $swapFreeMb MB < $SWAP_FLOOR_MB"
            else -> null
        }

    /** A box with MemAvailable well above [floorMb] (after the decode's charge) is not held back by swap. */
    internal fun roomBesideSwap(availAfterMb: Long, floorMb: Long, strongHardware: Boolean, swapFreeMb: Long = Long.MAX_VALUE): Boolean =
        if (strongHardware) availAfterMb >= floorMb + STRONG_SWAP_ROOM_MB
        else swapFreeMb >= SMALL_SWAP_ROOM_MIN_SWAP_MB && availAfterMb >= floorMb + SMALL_SWAP_ROOM_MB

    private const val PRELOAD_STRONG_BYTES = 32L * 1024 * 1024
    private const val PRELOAD_SMALL_BYTES = 6L * 1024 * 1024

    /** Saved pictures held compressed in memory at play start. */
    fun preloadBytes(): Long = if (isStrongBox) PRELOAD_STRONG_BYTES else PRELOAD_SMALL_BYTES

    /**
     * Worst case shown in settings, for this box class: the memory LRU at 4:3, the full-colour centre picture, the
     * saved pictures preloaded and the largest permitted decode tier; or the server thumbnails' caps when larger.
     * Display only.
     */
    fun displayChargeMb(rgb565: Boolean, fourK: Boolean): Int =
        displayChargeMb(rgb565, fourK, isStrongHardware)

    internal fun displayChargeMb(rgb565: Boolean, fourK: Boolean, strong: Boolean): Int {
        val tier = if (fourK) DecodeTier.SW_4K else DecodeTier.SW_UP_TO_1080P
        val pixels = ThumbGeometry.MAX_WIDTH.toLong() * (ThumbGeometry.MAX_WIDTH * 3 / 4)
        val frames = if (strong) ThumbStore.MEM_FRAMES_STRONG else ThumbStore.MEM_FRAMES_SMALL
        val preload = if (strong) PRELOAD_STRONG_BYTES else PRELOAD_SMALL_BYTES
        val own = tier.rssMb * MB + frames * pixels * (if (rgb565) 2 else 4) + pixels * 4 + preload
        val server = if (strong) {
            ServerThumbSet.STRONG_CAP_BYTES + ServerThumbSet.STRONG_DECODED_BYTES
        } else {
            ServerThumbSet.SMALL_CAP_BYTES + ServerThumbSet.SMALL_DECODED_BYTES
        }
        return ((maxOf(own, server) + MB - 1) / MB).toInt()
    }

    fun addTrimListener(l: (Int) -> Unit) = trimListeners.add(l)
    fun removeTrimListener(l: (Int) -> Unit) = trimListeners.remove(l)

    fun tierFor(frameWidth: Int, frameHeight: Int): DecodeTier =
        if (frameWidth > 1920 || frameHeight > 1088) DecodeTier.SW_4K else DecodeTier.SW_UP_TO_1080P

    /**
     * [decoderWarm]: the working set is already inside MemAvailable, so only a cold decoder is charged the tier's RSS.
     * [funded]: playback's read-ahead is held back for this decode, so the lower funded floors apply. [addressSpace]: a
     * second decoder next to the session's also checks the 32-bit address space.
     */
    fun allow(
        tier: DecodeTier, decoderWarm: Boolean = false, funded: Boolean = false,
        addressSpace: Boolean = tier != DecodeTier.SW_UP_TO_1080P && tier != DecodeTier.HW,
    ): GovernorVerdict {
        // When 4K may decode is the scheduler's rule, this only checks that it is affordable.
        if (addressSpace && !is64Bit && !decoderWarm) {
            val vm = readVmSizeMb()
            if (vm != null && vm + tier.addressSpaceMb > VA_CEILING_32BIT_MB) {
                return GovernorVerdict(false, "32-bit address space ${vm} MB + ${tier.addressSpaceMb} > $VA_CEILING_32BIT_MB")
            }
        }
        val mem = readMeminfo() ?: return GovernorVerdict(true, "meminfo unavailable")
        return decodeVerdict(mem, if (decoderWarm) 0L else tier.rssMb.toLong(), funded, lowRam, strongHardware)
    }

    /**
     * Whether a decode counts as funded: only the first lane, and only while playback's read-ahead is held back for it
     * (a 4K reserve or natural funding) or, outside low-RAM boxes, an HD decode while the generating screen holds
     * playback paused and its loading stopped.
     */
    fun funded(
        busy: Int, tier: DecodeTier, reserveFunded: Boolean, naturalFunded: Boolean, screenHolds: Boolean,
    ): Boolean = fundedDecode(busy, tier, reserveFunded, naturalFunded, screenHolds, lowRam)

    internal fun fundedDecode(
        busy: Int, tier: DecodeTier, reserveFunded: Boolean, naturalFunded: Boolean, screenHolds: Boolean, lowRam: Boolean,
    ): Boolean = busy == 0 &&
        (reserveFunded || naturalFunded ||
            (screenHolds && (tier == DecodeTier.SW_UP_TO_1080P || tier == DecodeTier.HW) && !lowRam))

    /** Swap is only checked where the box has some. */
    internal fun decodeVerdict(
        mem: Meminfo, chargeMb: Long, funded: Boolean, lowRam: Boolean, strongHardware: Boolean = false,
    ): GovernorVerdict {
        val floor = decodeFloorMb(funded, lowRam)
        val availAfter = mem.availableMb - chargeMb
        if (availAfter < floor) return GovernorVerdict(false, "MemAvailable ${mem.availableMb} MB - $chargeMb < floor $floor")
        swapVerdict(mem, funded, lowRam, strongHardware, chargeMb)?.let { return it }
        return GovernorVerdict(true, "ok avail=${mem.availableMb} swapFree=${mem.swapFreeMb}${if (funded) " funded" else ""}")
    }

    /**
     * MemAvailable a coverage pass needs for its next decode: the floor it is held to plus a cold decoder's charge.
     * A small box decodes 4K only against a playback reserve, so the funded floor applies there.
     */
    fun coverageFloorBytes(fourK: Boolean): Long = coverageFloorMb(fourK, lowRam, isStrongBox) * MB

    internal fun coverageFloorMb(fourK: Boolean, lowRam: Boolean, strongBox: Boolean): Long {
        val tier = if (fourK) DecodeTier.SW_4K else DecodeTier.SW_UP_TO_1080P
        return decodeFloorMb(funded = fourK && !strongBox, lowRam = lowRam) + tier.rssMb
    }

    private fun decodeFloorMb(funded: Boolean, lowRam: Boolean): Long = when {
        funded -> if (lowRam) FUNDED_FLOOR_LOW_RAM_MB else FUNDED_FLOOR_DEFAULT_MB
        else -> if (lowRam) FLOOR_LOW_RAM_MB else FLOOR_DEFAULT_MB
    }

    /** The swap part of [decodeVerdict] alone: a refusal, or null. */
    internal fun swapVerdict(
        mem: Meminfo, funded: Boolean, lowRam: Boolean = true, strongHardware: Boolean = false, chargeMb: Long = 0L,
    ): GovernorVerdict? {
        val swapFloor = if (funded) FUNDED_SWAP_FLOOR_MB else SWAP_FLOOR_MB
        if (mem.swapTotalMb > 0 && mem.swapFreeMb < swapFloor &&
            !roomBesideSwap(mem.availableMb - chargeMb, decodeFloorMb(funded, lowRam), strongHardware, mem.swapFreeMb)
        ) {
            return GovernorVerdict(false, "SwapFree ${mem.swapFreeMb} MB < $swapFloor", mem.swapFreeMb)
        }
        return null
    }

    /** SwapFree read again just before a decode starts, after its keyframe was fetched. */
    fun swapAllows(funded: Boolean): GovernorVerdict {
        val mem = readMeminfo() ?: return GovernorVerdict(true, "meminfo unavailable")
        return swapVerdict(mem, funded, lowRam, strongHardware) ?: GovernorVerdict(true, "ok swapFree=${mem.swapFreeMb}")
    }

    /** Below this SwapFree a smaller box closes its decoders rather than trimming them. */
    private const val CLOSE_DECODERS_SWAP_MB = FUNDED_SWAP_FLOOR_MB - 16

    /**
     * MemAvailable above the decode floor (after the decode's own size) that lets software carry on under the swap
     * floor once the 4K hardware decoder has closed: its 200 MB come back at once, the swap it pushed out comes back
     * over tens of seconds, and a hold for that swap cost a Fire TV Stick 20 to 50 s per set.
     */
    private const val AFTER_HARDWARE_CLOSE_ROOM_MB = 100L

    /** A swap refusal after the 4K hardware decoder closed is lifted while the box has [AFTER_HARDWARE_CLOSE_ROOM_MB] of room. */
    fun afterHardwareClose(verdict: GovernorVerdict, tier: DecodeTier): GovernorVerdict {
        if (!verdict.swapLow) return verdict
        val mem = readMeminfo() ?: return verdict
        return afterHardwareClose(verdict, mem, tier, lowRam)
    }

    internal fun afterHardwareClose(verdict: GovernorVerdict, mem: Meminfo, tier: DecodeTier, lowRam: Boolean): GovernorVerdict {
        if (!verdict.swapLow) return verdict
        if (mem.swapTotalMb > 0 && mem.swapFreeMb < CLOSE_DECODERS_SWAP_MB) return verdict
        val room = decodeFloorMb(funded = false, lowRam = lowRam) + AFTER_HARDWARE_CLOSE_ROOM_MB
        val after = mem.availableMb - tier.rssMb
        if (mem.availableMb < 0 || after < room) return verdict
        return GovernorVerdict(true, "${verdict.reason}, carrying on with $after MB free after the 4K hardware decoder closed")
    }

    /**
     * A swap refusal well under the floor on a smaller box closes the decoders, so their working set goes back until
     * swap recovers. Just under the floor they are trimmed, so a SwapFree hovering there does not reopen them each time.
     */
    fun closesDecodersFor(verdict: GovernorVerdict): Boolean = closesDecodersFor(verdict, strongHardware)

    internal fun closesDecodersFor(verdict: GovernorVerdict, strongHardware: Boolean): Boolean =
        verdict.swapLow && !strongHardware && verdict.swapFreeMb < CLOSE_DECODERS_SWAP_MB

    /** For the log: MemAvailable and SwapFree against the decode floors. */
    fun memoryNote(): String {
        val mem = readMeminfo()
        return memoryNote(mem?.availableMb ?: -1L, mem?.swapTotalMb ?: 0L, mem?.swapFreeMb ?: 0L, lowRam)
    }

    internal fun memoryNote(availMb: Long, swapTotalMb: Long, swapFreeMb: Long, lowRam: Boolean): String {
        val floor = if (lowRam) FLOOR_LOW_RAM_MB else FLOOR_DEFAULT_MB
        val funded = if (lowRam) FUNDED_FLOOR_LOW_RAM_MB else FUNDED_FLOOR_DEFAULT_MB
        return "MemAvailable ${if (availMb >= 0) "$availMb MB" else "unknown"} (floor $floor, funded $funded), " +
            "SwapFree ${if (swapTotalMb > 0) "$swapFreeMb MB" else "no swap"}${if (lowRam) ", low-RAM box" else ""}"
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

    internal fun readMeminfo(): Meminfo? = runCatching { parseMeminfo(File("/proc/meminfo").readText()) }.getOrNull()

    internal fun parseVmSizeMb(status: String): Long? =
        status.lineSequence().firstOrNull { it.startsWith("VmSize:") }
            ?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLongOrNull()?.let { it / 1024 }

    private fun readVmSizeMb(): Long? = runCatching { parseVmSizeMb(File("/proc/self/status").readText()) }.getOrNull()
}
