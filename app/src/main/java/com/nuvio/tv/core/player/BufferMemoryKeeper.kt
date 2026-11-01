package com.nuvio.tv.core.player

import android.content.ComponentCallbacks2
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.util.UnstableApi
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Gives idle buffer memory back to the box and lowers the buffer budget while the box is short of memory. A budget
 * that was sized down to the memory free at the start grows back towards [ceilingBytes], a step at a time, once the
 * box has room again. Growth only changes how far ahead the player may read; nothing buffered is dropped.
 * All work runs on its own thread, never on the playback thread.
 */
@UnstableApi
class BufferMemoryKeeper(
    val control: BufferBudgetControl,
    private val freeMemory: () -> BufferSizing.FreeMemory?,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val worker: Worker = SharedWorker,
    budgetManaged: Boolean = true,
    /** The box's own size for this budget; 0 or no more than the built budget means no growth. */
    ceilingBytes: Long = 0L,
    private val swapFreeMb: () -> Long = { -1L },
) {
    interface Worker {
        fun run(task: () -> Unit)
        fun runLater(delayMs: Long, task: () -> Unit)
    }

    @Volatile var pressureLevel: Int = LEVEL_NONE
        private set
    @Volatile private var lastPressureAtMs = 0L
    @Volatile private var levelSetAtMs = 0L
    @Volatile private var lastSeekAtMs = 0L
    @Volatile private var seekTrimQueued = false
    @Volatile private var detached = false
    @Volatile var lentBytes: Long = 0L
        private set
    /** What the lending may never take the buffer below (seconds of film), on top of the sized floor. */
    @Volatile var lendFloorBytes: Long = 0L
        private set
    /** Lending waits until the film has its first picture and its start cushion. */
    @Volatile var lendingHeld: Boolean = false
        private set
    /** Off: a size the user set is only cut when memory is critical; idle memory is still given back. */
    @Volatile var budgetManaged: Boolean = budgetManaged
        private set
    @Volatile var ceilingBytes: Long = ceilingBytes.coerceAtLeast(0L)
        private set
    /** Budget added above the built size so far. */
    @Volatile var grownBytes: Long = 0L
        private set

    private val trimListener: (Int) -> Unit = ::onTrimMemory

    fun attach() {
        MemoryPressure.addListener(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW, trimListener)
    }

    fun detach() {
        detached = true
        MemoryPressure.removeListener(trimListener)
    }

    fun onTrimMemory(level: Int) {
        if (detached) return
        val wanted = when {
            level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> LEVEL_NONE
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> LEVEL_CRITICAL
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> LEVEL_LOW
            else -> LEVEL_NONE
        }
        worker.run {
            if (wanted == LEVEL_NONE) {
                trimIdle("trim:$level")
                return@run
            }
            val free = freeMemory()
            val swap = swapFreeMb()
            if (free == null || warningStands(free, swap)) {
                raise(wanted, "trim:$level")
                return@run
            }
            val freed = trimIdle(null)
            Log.i(TAG, "BUFFER_MEMORY: trim:$level set aside availMb=${free.availMb} thresholdMb=${free.thresholdMb} " +
                "swapFreeMb=$swap freedMb=${freed / MB}")
        }
    }

    fun setBudgetManaged(enabled: Boolean) {
        if (detached || enabled == budgetManaged) return
        budgetManaged = enabled
        worker.run {
            if (!budgetManaged && pressureLevel == LEVEL_LOW) {
                pressureLevel = LEVEL_NONE
                applyTarget()
                Log.i(TAG, "BUFFER_MEMORY: pressure lifted level=0 capMb=${capOrBudgetMb()} managed=false")
            }
        }
    }

    /** A seek drops the buffer it leaves; what the new position does not refill is given back. */
    fun onSeek() {
        if (detached) return
        lastSeekAtMs = clock()
        if (seekTrimQueued) return
        seekTrimQueued = true
        worker.runLater(SEEK_SETTLE_MS) { seekTrim() }
    }

    private fun seekTrim() {
        val sinceSeek = clock() - lastSeekAtMs
        if (sinceSeek < SEEK_SETTLE_MS) {
            worker.runLater(SEEK_SETTLE_MS - sinceSeek) { seekTrim() }
            return
        }
        seekTrimQueued = false
        trimIdle("seek")
    }

    /** The budget went down (for example DV7 conversion): the allocator's pool follows and growth stops there. */
    fun onBudgetLowered() {
        if (detached) return
        worker.run {
            if (grownBytes > 0L) {
                grownBytes = 0L
                control.setGrowthBytes(0L)
            }
            ceilingBytes = minOf(ceilingBytes, control.uncappedBudgetBytes)
            applyTarget()
            trimIdle("budget")
        }
    }

    /** Memory the extra downloads of the Automatic speed-up hold; the buffer gives the same amount up. */
    fun setLentBytes(bytes: Long) {
        if (detached) return
        val lent = bytes.coerceAtLeast(0L)
        worker.run {
            if (lent == lentBytes) return@run
            val returned = lent < lentBytes
            lentBytes = lent
            applyTarget()
            if (!returned) trimIdle("lent")
            Log.i(TAG, "BUFFER_MEMORY: lent to downloads mb=${lent / MB} capMb=${capOrBudgetMb()}")
        }
    }

    fun setLendFloorBytes(bytes: Long) {
        if (detached) return
        val floor = bytes.coerceAtLeast(0L)
        worker.run {
            if (floor == lendFloorBytes) return@run
            lendFloorBytes = floor
            if (lentBytes > 0L && !lendingHeld) applyTarget()
        }
    }

    fun setLendingHeld(held: Boolean) {
        if (detached) return
        worker.run {
            if (held == lendingHeld) return@run
            lendingHeld = held
            if (lentBytes <= 0L) return@run
            applyTarget()
            if (!held) trimIdle("lent")
            Log.i(TAG, "BUFFER_MEMORY: lending ${if (held) "held for the start" else "resumed"} mb=${lentBytes / MB} capMb=${capOrBudgetMb()}")
        }
    }

    /** About every 10 s while playing: Android 14 and later no longer sends RUNNING_LOW to visible apps. */
    fun onTick() {
        if (detached) return
        worker.run { check() }
    }

    internal fun check() {
        if (detached) return
        val free = freeMemory()
        val now = clock()
        if (free != null && free.lowMemory) {
            val level = if (pressureLevel == LEVEL_LOW && now - levelSetAtMs >= ESCALATE_AFTER_MS) LEVEL_CRITICAL
                else maxOf(pressureLevel, LEVEL_LOW)
            raise(level, "lowMemory availMb=${free.availMb} thresholdMb=${free.thresholdMb}")
            return
        }
        if (pressureLevel == LEVEL_NONE) {
            if (now - lastPressureAtMs >= RESTORE_QUIET_MS) tendGrowth(free)
            return
        }
        if (now - lastPressureAtMs < RESTORE_QUIET_MS) {
            trimIdle("pressure")
            return
        }
        val next = if (budgetManaged) pressureLevel - 1 else LEVEL_NONE
        val addedMb = (capBytes(next) - capBytes(pressureLevel)) / MB
        val roomMb = free?.let { it.availMb - it.thresholdMb - BufferSizing.SYSTEM_MARGIN_MB } ?: 0L
        if (free == null || (budgetManaged && roomMb < addedMb)) {
            lastPressureAtMs = now
            return
        }
        pressureLevel = next
        lastPressureAtMs = now
        levelSetAtMs = now
        applyTarget()
        Log.i(TAG, "BUFFER_MEMORY: pressure eased level=$next capMb=${capOrBudgetMb()} availMb=${free.availMb}")
    }

    /**
     * No pressure for a quiet minute: the budget grows a step towards the ceiling while the box keeps
     * [GROW_RESERVE_MB] beyond its margin, and gives a step back when the room is gone.
     */
    private fun tendGrowth(free: BufferSizing.FreeMemory?) {
        if (free == null || !budgetManaged) return
        val base = control.uncappedBudgetBytes - grownBytes
        val gap = ceilingBytes - base - grownBytes
        val roomMb = free.availMb - free.thresholdMb - BufferSizing.SYSTEM_MARGIN_MB
        if (grownBytes > 0L && roomMb < 0L) {
            grownBytes = (grownBytes - GROW_STEP_MB * MB).coerceAtLeast(0L)
            control.setGrowthBytes(grownBytes)
            applyTarget()
            val freed = trimIdle(null)
            Log.i(TAG, "BUFFER_MEMORY: budget back a step: room gone availMb=${free.availMb} thresholdMb=${free.thresholdMb} " +
                "budgetMb=${control.uncappedBudgetBytes / MB} freedMb=${freed / MB}")
            return
        }
        if (gap <= 0L || roomMb - GROW_RESERVE_MB < GROW_STEP_MB) return
        val swap = swapFreeMb()
        if (swap in 0 until GROW_MIN_SWAP_MB && roomMb < GROW_ROOM_BESIDE_SWAP_MB) return
        grownBytes += minOf(GROW_STEP_MB * MB, gap)
        control.setGrowthBytes(grownBytes)
        applyTarget()
        Log.i(TAG, "BUFFER_MEMORY: budget grew to ${control.uncappedBudgetBytes / MB} MB (built ${base / MB}, box ${ceilingBytes / MB}) " +
            "availMb=${free.availMb} thresholdMb=${free.thresholdMb} swapFreeMb=$swap")
    }

    private fun raise(level: Int, reason: String) {
        if (detached) return
        lastPressureAtMs = clock()
        if (grownBytes > 0L) {
            grownBytes = 0L
            control.setGrowthBytes(0L)
            Log.i(TAG, "BUFFER_MEMORY: growth given back reason=$reason budgetMb=${control.uncappedBudgetBytes / MB}")
        }
        val changed = level > pressureLevel && (budgetManaged || level == LEVEL_CRITICAL)
        if (changed) {
            pressureLevel = level
            levelSetAtMs = lastPressureAtMs
            applyTarget()
        }
        val freed = trimIdle(null)
        if (changed || freed > 0) {
            Log.i(TAG, "BUFFER_MEMORY: pressure level=$pressureLevel reason=$reason capMb=${capOrBudgetMb()} " +
                "uncappedMb=${control.uncappedBudgetBytes / MB} freedMb=${freed / MB} managed=$budgetManaged")
        }
    }

    internal fun capBytes(level: Int): Long {
        val budget = control.uncappedBudgetBytes
        val share = when (level) {
            LEVEL_LOW -> LOW_SHARE_PERCENT
            LEVEL_CRITICAL -> CRITICAL_SHARE_PERCENT
            else -> return budget
        }
        val floor = minOf(budget, BufferSizing.MIN_SIZED_BUFFER_MB * MB)
        val back = control.backBufferBytes.coerceIn(0L, budget.coerceAtLeast(0L))
        return (back + (budget - back) * share / 100).coerceAtLeast(floor)
    }

    private fun capOrBudgetMb(): Long =
        effectiveCapBytes().takeIf { it >= 0L }?.let { it / MB } ?: (control.uncappedBudgetBytes / MB)

    /** The pressure cap, lowered further by what the extra downloads hold; -1 when neither applies. */
    internal fun effectiveCapBytes(): Long {
        val pressure = if (pressureLevel == LEVEL_NONE) -1L else capBytes(pressureLevel)
        val budget = control.uncappedBudgetBytes
        if (lentBytes <= 0L || lendingHeld || budget <= 0L) return pressure
        val floor = minOf(budget, maxOf(BufferSizing.MIN_SIZED_BUFFER_MB * MB, lendFloorBytes))
        val lent = (budget - lentBytes).coerceAtLeast(floor)
        return if (pressure < 0L) lent else minOf(pressure, lent)
    }

    private fun applyTarget() {
        control.setPressureCapBytes(effectiveCapBytes())
        control.bufferAllocator.setTargetBufferSize(control.allocatorTargetBytes())
    }

    /** Frees idle allocations beyond [KEEP_IDLE_BYTES], a step at a time so the loader never waits long. */
    internal fun trimIdle(reason: String?): Long {
        if (detached) return 0L
        val allocator = control.bufferAllocator
        var freed = 0L
        for (step in 0 until MAX_TRIM_STEPS) {
            val dropped = synchronized(allocator) {
                val idle = allocator.availableBytes
                if (idle <= KEEP_IDLE_BYTES) return@synchronized 0
                val drop = minOf(idle - KEEP_IDLE_BYTES, TRIM_STEP_BYTES)
                val before = allocator.memoryFootprint
                allocator.setTargetBufferSize(allocator.totalBytesAllocated + idle - drop)
                allocator.trim()
                allocator.setTargetBufferSize(control.allocatorTargetBytes())
                before - allocator.memoryFootprint
            }
            if (dropped <= 0) break
            freed += dropped
        }
        if (reason != null && freed > 0) {
            Log.i(TAG, "BUFFER_MEMORY: gave back idle buffer reason=$reason freedMb=${freed / MB} " +
                "heldMb=${allocator.totalBytesAllocated / MB}")
        }
        return freed
    }

    private object SharedWorker : Worker {
        private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "buffer-memory").apply { isDaemon = true }
        }

        override fun run(task: () -> Unit) {
            executor.execute { runCatching(task) }
        }

        override fun runLater(delayMs: Long, task: () -> Unit) {
            executor.schedule({ runCatching(task) }, delayMs, TimeUnit.MILLISECONDS)
        }
    }

    companion object {
        /** Only a Target buffer size set by hand counts as unmanaged. */
        fun budgetManaged(customBuffers: Boolean, budgetManaged: Boolean): Boolean = !customBuffers || budgetManaged

        /** [LEND_FLOOR_SECONDS] of film at [bitrateBps] plus one piece; 0 while the bitrate is not known. */
        fun lendFloorBytes(bitrateBps: Long, pieceBytes: Long): Long =
            if (bitrateBps <= 0L) 0L else bitrateBps / 8L * LEND_FLOOR_SECONDS + pieceBytes.coerceAtLeast(0L)

        /** A heavy film's start waits for six seconds of buffer; the lending leaves two more. */
        const val LEND_FLOOR_SECONDS = 8L

        /**
         * Android's memory warning counts once the box's own numbers agree: past its low-memory line, within the
         * system margin of it, or nearly out of swap. Fire OS sends RUNNING_LOW and RUNNING_CRITICAL to every app
         * with hundreds of MB free and closes idle apps itself; the extra downloads already judge them this way.
         */
        fun warningStands(free: BufferSizing.FreeMemory, swapFreeMb: Long): Boolean =
            free.lowMemory || free.availMb - free.thresholdMb < BufferSizing.SYSTEM_MARGIN_MB ||
                swapFreeMb in 0 until WARNING_SWAP_MB

        /** The same line as the extra downloads' swap rule. */
        const val WARNING_SWAP_MB = 50L

        private const val TAG = "BufferMemory"
        private const val MB = 1024L * 1024L
        const val LEVEL_NONE = 0
        const val LEVEL_LOW = 1
        const val LEVEL_CRITICAL = 2
        const val LOW_SHARE_PERCENT = 60L
        const val CRITICAL_SHARE_PERCENT = 35L
        const val SEEK_SETTLE_MS = 2_000L
        /** After a quiet minute pressure eases one level if the box has room, or straight off for a hand-set size. */
        const val RESTORE_QUIET_MS = 60_000L
        /** Still short of memory this long after the first cut: cut further. */
        const val ESCALATE_AFTER_MS = 30_000L
        /** One growth step per check (about every 10 s while playing). */
        const val GROW_STEP_MB = 32L
        /** Room the box keeps above its margin after a step, so a software thumbnail decode (25-35 MB) still fits. */
        const val GROW_RESERVE_MB = 64L
        /** No growth beside a nearly full swap unless RAM is plentiful: the box's memory killer watches swap too. */
        const val GROW_MIN_SWAP_MB = 96L
        const val GROW_ROOM_BESIDE_SWAP_MB = 300L
        const val KEEP_IDLE_BYTES = 2 * 1024 * 1024
        /** 256 allocations of 64 KB per step. */
        const val TRIM_STEP_BYTES = 16 * 1024 * 1024
        private const val MAX_TRIM_STEPS = 256
    }
}
