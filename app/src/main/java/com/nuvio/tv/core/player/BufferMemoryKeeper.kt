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
    /** MB of address space the process uses on a 32-bit box ([BufferSizing.addressSpaceUsedMbOn32Bit]); null on 64-bit. */
    private val addressSpaceUsedMb: () -> Long? = { null },
    private val addressSpaceCeilingMb: () -> Long = { BufferSizing.addressSpaceCeiling32BitMb },
    private val addressSpaceLimitMb: () -> Long = { addressSpaceCeilingMb() + BufferSizing.ADDRESS_SPACE_SPARE_32BIT_MB },
    private val addressMemo: AddressSpaceMemo = AddressSpaceMemo(),
    /** The least a pressure cut or the lending leaves; [BufferSizing.sizedFloorMb]. */
    floorBytes: Long = BufferSizing.MIN_SIZED_BUFFER_MB * MB,
    /** A 1 GB box ([BufferSizing.isOneGbBox]): Android's last warning always counts. */
    private val oneGbBox: Boolean = false,
    private val onLastWarning: () -> Unit = {},
    /** True when the level reaches critical, false when it leaves it or the keeper is detached. Runs on the worker. */
    private val onCritical: (Boolean) -> Unit = {},
    /** Android warned at [ComponentCallbacks2.TRIM_MEMORY_BACKGROUND] or above. Runs on the worker. */
    private val onMemoryWarning: () -> Unit = {},
    /** The buffer limit before and after a warning cut. Runs on the worker. */
    private val onWarningCut: (Long, Long) -> Unit = { _, _ -> },
    private val inFront: () -> Boolean = { MemoryPressure.inFront },
    /** Fire OS sends RUNNING_CRITICAL to every app with hundreds of MB free; there only the box's numbers count. */
    private val fireOs: Boolean = false,
) {
    interface Worker {
        fun run(task: () -> Unit)
        fun runLater(delayMs: Long, task: () -> Unit)
    }

    @Volatile var pressureLevel: Int = LEVEL_NONE
        private set
    @Volatile private var lastPressureAtMs = 0L
    @Volatile private var lastCheckLogAtMs = Long.MIN_VALUE / 2
    @Volatile private var levelSetAtMs = 0L
    /** When [pressureLevel] last changed, on [clock]; 0 while it never has. */
    val levelChangedAtMs: Long get() = levelSetAtMs
    @Volatile private var lastSeekAtMs = 0L
    @Volatile private var seekTrimQueued = false
    @Volatile private var detached = false
    private var criticalTold = false
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
    /** Where loading stops while paused with little room; -1 while playing or with room. */
    @Volatile var pausedCapBytes: Long = -1L
        private set
    @Volatile var floorBytes: Long = floorBytes.coerceAtLeast(0L)
        private set
    /** Lowered a quarter by each background-level warning; -1 while none applies. */
    @Volatile var warningCapBytes: Long = -1L
        private set
    @Volatile private var lastWarningAtMs = Long.MIN_VALUE / 2
    /** Seconds of film at the stream's bitrate that warnings alone never cut below; [warningFloorBytes]. */
    @Volatile var warningFloorBytes: Long = MIN_WARNING_FLOOR_BYTES
        private set
    @Volatile var playing: Boolean = false
        private set
    /** 32-bit only: what is held plus the address space left under the ceiling; -1 while there is room for the budget. */
    @Volatile var roomCapBytes: Long = -1L
        private set

    private val trimListener: (Int) -> Unit = ::onTrimMemory

    fun attach() {
        MemoryPressure.addListener(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW, trimListener)
        worker.run { tendAddressSpace() }
    }

    fun detach() {
        detached = true
        MemoryPressure.removeListener(trimListener)
        worker.run { tellCritical() }
    }

    private fun tellCritical() {
        val critical = !detached && pressureLevel == LEVEL_CRITICAL
        if (critical == criticalTold) return
        criticalTold = critical
        runCatching { onCritical(critical) }
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
            if (oneGbLastWarning(level, oneGbBox)) {
                raise(LEVEL_CRITICAL, "trim:$level oneGb")
                runCatching(onLastWarning)
                return@run
            }
            val background = countsAsWarning(level)
            val warning = cutsOnWarning(level, fireOs)
            val cuts = warning && playing && runCatching(inFront).getOrDefault(false)
            if (background || cuts) runCatching(onMemoryWarning)
            if (cuts) stepDownForWarning(level)
            val free = freeMemory()
            val swap = swapFreeMb()
            if (free == null || warningStands(free, swap)) {
                raise(wanted, "trim:$level")
                return@run
            }
            if (cuts && budgetManaged) return@run
            val freed = trimIdle(null)
            Log.i(TAG, "BUFFER_MEMORY: trim:$level set aside availMb=${free.availMb} thresholdMb=${free.thresholdMb} " +
                "swapFreeMb=$swap freedMb=${freed / MB}")
        }
    }

    /**
     * A warning while a film plays in front counts whatever the free memory reads: the buffer gives a quarter back
     * (one cut per burst, never below the warning floor) and stays smaller for [WARNING_COOL_DOWN_MS].
     */
    private fun stepDownForWarning(level: Int) {
        val now = clock()
        val inBurst = warningInBurst(now, lastWarningAtMs)
        lastWarningAtMs = now
        lastPressureAtMs = now
        if (grownBytes > 0L) {
            grownBytes = 0L
            control.setGrowthBytes(0L)
        }
        if (!budgetManaged) return
        if (inBurst) {
            trimIdle(null)
            return
        }
        val budget = control.uncappedBudgetBytes
        val before = effectiveCapBytes().takeIf { it >= 0L } ?: budget
        warningCapBytes = warningStepDown(warningCapBytes, budget, maxOf(floorBytes, warningFloorBytes))
        applyTarget()
        val after = effectiveCapBytes().takeIf { it >= 0L } ?: budget
        runCatching { onWarningCut(before, after) }
        val freed = trimIdle(null)
        Log.i(TAG, "BUFFER_MEMORY: warning trim:$level capMb=${capOrBudgetMb()} budgetMb=${budget / MB} " +
            "floorMb=${warningFloorBytes / MB} freedMb=${freed / MB}")
    }

    /** Playing or paused, as the player last said. */
    fun onPlayingChanged(isPlaying: Boolean) {
        playing = isPlaying
    }

    /** The warning floor for the stream's bitrate; 50 MB while it is unknown (0). */
    fun setStreamBitrate(bitrateBps: Long) {
        if (detached) return
        val floor = warningFloorBytes(bitrateBps)
        worker.run {
            if (floor == warningFloorBytes) return@run
            warningFloorBytes = floor
            if (warningCapBytes < 0L) return@run
            val raised = warningCapBytes.coerceAtLeast(minOf(floor, control.uncappedBudgetBytes))
            if (raised != warningCapBytes) {
                warningCapBytes = raised
                applyTarget()
            }
        }
    }

    /** After the cool-down the warning cap lifts a quarter of the budget per check while the box has room for it. */
    private fun tendWarningCap(now: Long, free: BufferSizing.FreeMemory?): Boolean {
        if (warningCapBytes < 0L || !warningCoolDownOver(now, lastWarningAtMs)) return false
        val budget = control.uncappedBudgetBytes
        val next = warningStepUp(warningCapBytes, budget)
        val addedMb = ((if (next < 0L) budget else next) - warningCapBytes).coerceAtLeast(0L) / MB
        val roomMb = free?.let { it.availMb - it.thresholdMb - BufferSizing.SYSTEM_MARGIN_MB } ?: return false
        if (roomMb < addedMb) return false
        warningCapBytes = next
        applyTarget()
        Log.i(TAG, "BUFFER_MEMORY: warning cap eased capMb=${capOrBudgetMb()} availMb=${free.availMb}")
        return true
    }

    fun setFloorBytes(bytes: Long) {
        if (detached) return
        val floor = bytes.coerceAtLeast(0L)
        worker.run {
            if (floor == floorBytes) return@run
            floorBytes = floor
            if (warningCapBytes >= 0L) warningCapBytes = warningCapBytes.coerceAtLeast(minOf(floor, control.uncappedBudgetBytes))
            if (pressureLevel != LEVEL_NONE || lentBytes > 0L || warningCapBytes >= 0L) applyTarget()
        }
    }

    fun setBudgetManaged(enabled: Boolean) {
        if (detached || enabled == budgetManaged) return
        budgetManaged = enabled
        worker.run {
            if (!budgetManaged && warningCapBytes >= 0L) {
                warningCapBytes = -1L
                applyTarget()
            }
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

    /** About every 10 s, playing or paused: Android 14 and later no longer sends RUNNING_LOW to visible apps. */
    fun onTick(playing: Boolean = true) {
        if (detached) return
        this.playing = playing
        worker.run { check(playing) }
    }

    internal fun check(playing: Boolean = true) {
        if (detached) return
        val free = freeMemory()
        val now = clock()
        if (now - lastCheckLogAtMs >= CHECK_LOG_EVERY_MS) {
            lastCheckLogAtMs = now
            Log.i(TAG, "BUFFER_MEMORY: check availMb=${free?.availMb} thresholdMb=${free?.thresholdMb} " +
                "budgetMb=${control.uncappedBudgetBytes / MB} heldMb=${control.bufferAllocator.totalBytesAllocated / MB} " +
                "level=$pressureLevel playing=$playing")
        }
        tendPausedCap(playing, free)
        tendAddressSpace()
        if (free != null && free.lowMemory) {
            val level = if (pressureLevel == LEVEL_LOW && now - levelSetAtMs >= ESCALATE_AFTER_MS) LEVEL_CRITICAL
                else maxOf(pressureLevel, LEVEL_LOW)
            raise(level, "lowMemory availMb=${free.availMb} thresholdMb=${free.thresholdMb}")
            return
        }
        val swap = swapFreeMb()
        if (free != null && swapRunsOut(free, swap)) {
            // Swap used up is the last stop before Android closes the app on a small box, so it steps up like lowMemory does.
            // A Fire Stick plays for minutes with swap used up and 70 to 90 MB above its line, so only the last
            // stretch above the line steps up.
            val nearLine = free.availMb - free.thresholdMb < SWAP_ESCALATE_ROOM_MB
            val level = if (nearLine && pressureLevel == LEVEL_LOW && now - levelSetAtMs >= ESCALATE_AFTER_MS) LEVEL_CRITICAL
                else maxOf(pressureLevel, LEVEL_LOW)
            raise(level, "swap availMb=${free.availMb} thresholdMb=${free.thresholdMb} swapFreeMb=$swap")
            return
        }
        val warningEased = tendWarningCap(now, free)
        if (pressureLevel == LEVEL_NONE) {
            if (playing && now - lastPressureAtMs >= RESTORE_QUIET_MS && warningCoolDownOver(now, lastWarningAtMs) &&
                warningCapBytes < 0L && !warningEased
            ) tendGrowth(free)
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
        tellCritical()
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
        // Budget that is not filled yet still comes out of the box's free memory once the buffer fills, on a pause for instance.
        val unfilledMb = (control.uncappedBudgetBytes - control.bufferAllocator.totalBytesAllocated).coerceAtLeast(0L) / MB
        if (gap <= 0L || roomMb - GROW_RESERVE_MB - unfilledMb < GROW_STEP_MB) return
        val swap = swapFreeMb()
        if (swap in 0 until GROW_MIN_SWAP_MB && roomMb < GROW_ROOM_BESIDE_SWAP_MB) return
        grownBytes += minOf(GROW_STEP_MB * MB, gap)
        control.setGrowthBytes(grownBytes)
        applyTarget()
        Log.i(TAG, "BUFFER_MEMORY: budget grew to ${control.uncappedBudgetBytes / MB} MB (built ${base / MB}, box ${ceilingBytes / MB}) " +
            "availMb=${free.availMb} thresholdMb=${free.thresholdMb} swapFreeMb=$swap")
    }

    /**
     * A 32-bit process has 3 or 4 GB of address space for everything; the buffer may only take what is left under
     * [BufferSizing.addressSpaceCeiling32BitMb], whatever the box's RAM, plus what it gave back and can fill again.
     */
    internal fun tendAddressSpace() {
        if (detached) return
        val usedMb = addressSpaceUsedMb() ?: return
        val ceilingMb = addressSpaceCeilingMb()
        val budget = control.uncappedBudgetBytes.coerceAtLeast(0L)
        val held = control.bufferAllocator.totalBytesAllocated.toLong()
        val reusable = addressMemo.reusableBytes(held, usedMb)
        val roomMb = ceilingMb - usedMb
        val hardRoomMb = addressSpaceLimitMb() - ADDRESS_SPACE_HARD_SPARE_MB - usedMb
        val cap = minOf(held + reusable + roomMb * MB, held + hardRoomMb * MB)
            .coerceIn(minOf(budget, floorBytes), budget)
        val next = if (cap >= budget) -1L else cap
        if (next == roomCapBytes) return
        if (next >= 0L && roomCapBytes >= 0L && kotlin.math.abs(next - roomCapBytes) < ROOM_STEP_BYTES) return
        roomCapBytes = next
        applyTarget()
        val freed = if (next >= 0L && held > next) trimIdle(null) else 0L
        Log.i(TAG, "BUFFER_MEMORY: address space usedMb=$usedMb ceilingMb=$ceilingMb reusableMb=${reusable / MB} " +
            "roomCapMb=${if (next < 0L) "none" else (next / MB).toString()} budgetMb=${budget / MB} freedMb=${freed / MB}")
    }

    /**
     * A paused film keeps loading until the budget is full, and the next app started in front (a launcher, a
     * screensaver) needs room the buffer would take. While paused with less than [PAUSED_ROOM_MB] spare, loading
     * stops at what is held; playing again lifts that.
     */
    private fun tendPausedCap(playing: Boolean, free: BufferSizing.FreeMemory?) {
        if (playing) {
            if (pausedCapBytes >= 0L) {
                pausedCapBytes = -1L
                applyTarget()
                Log.i(TAG, "BUFFER_MEMORY: paused cap lifted capMb=${capOrBudgetMb()}")
            }
            return
        }
        if (pausedCapBytes >= 0L || free == null) return
        val roomMb = free.availMb - free.thresholdMb - BufferSizing.SYSTEM_MARGIN_MB
        if (roomMb >= PAUSED_ROOM_MB) return
        val budget = control.uncappedBudgetBytes
        val floor = minOf(budget, floorBytes)
        pausedCapBytes = control.bufferAllocator.totalBytesAllocated.toLong().coerceIn(floor, budget.coerceAtLeast(floor))
        applyTarget()
        trimIdle(null)
        Log.i(TAG, "BUFFER_MEMORY: paused with little room, loading stops capMb=${capOrBudgetMb()} " +
            "availMb=${free.availMb} thresholdMb=${free.thresholdMb}")
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
            tellCritical()
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
        val floor = minOf(budget, floorBytes)
        val back = control.backBufferBytes.coerceIn(0L, budget.coerceAtLeast(0L))
        return (back + (budget - back) * share / 100).coerceAtLeast(floor)
    }

    private fun capOrBudgetMb(): Long =
        effectiveCapBytes().takeIf { it >= 0L }?.let { it / MB } ?: (control.uncappedBudgetBytes / MB)

    /** The pressure cap, lowered further by the paused cap and what the extra downloads hold; -1 when none applies. */
    internal fun effectiveCapBytes(): Long {
        val pressureCap = if (pressureLevel == LEVEL_NONE) -1L else capBytes(pressureLevel)
        val level = when {
            warningCapBytes < 0L -> pressureCap
            pressureCap < 0L -> warningCapBytes
            else -> minOf(pressureCap, warningCapBytes)
        }
        val paused = when {
            pausedCapBytes < 0L -> level
            level < 0L -> pausedCapBytes
            else -> minOf(level, pausedCapBytes)
        }
        val pressure = when {
            roomCapBytes < 0L -> paused
            paused < 0L -> roomCapBytes
            else -> minOf(paused, roomCapBytes)
        }
        val budget = control.uncappedBudgetBytes
        if (lentBytes <= 0L || lendingHeld || budget <= 0L) return pressure
        val floor = minOf(budget, maxOf(floorBytes, lendFloorBytes))
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

        /** onLowMemory (level 80) on a 1 GB box: Android is about to close the app, whatever the free memory reads. */
        fun oneGbLastWarning(level: Int, oneGbBox: Boolean): Boolean =
            oneGbBox && level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE

        /** Background and above: Android has put the app on the list it closes first. UI hidden alone does not count. */
        fun countsAsWarning(level: Int): Boolean = level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND

        /** A quarter off the current cap (the budget when none), never below the floor or above the budget. */
        fun warningStepDown(currentCapBytes: Long, budgetBytes: Long, floorBytes: Long): Long {
            val budget = budgetBytes.coerceAtLeast(0L)
            val base = if (currentCapBytes < 0L) budget else minOf(currentCapBytes, budget)
            return (base - base / 4).coerceAtLeast(minOf(budget, floorBytes.coerceAtLeast(0L)))
        }

        /** A quarter of the budget back; -1 once the whole budget is back. */
        fun warningStepUp(currentCapBytes: Long, budgetBytes: Long): Long {
            if (currentCapBytes < 0L) return -1L
            val next = currentCapBytes + budgetBytes.coerceAtLeast(0L) / 4
            return if (next >= budgetBytes) -1L else next
        }

        fun warningCoolDownOver(nowMs: Long, lastWarningAtMs: Long): Boolean =
            nowMs - lastWarningAtMs >= WARNING_COOL_DOWN_MS

        /**
         * Warnings that cut while a film plays in front: background and above, and RUNNING_CRITICAL, which is what a
         * foreground app gets. RUNNING_LOW stays with the box's numbers, and so does RUNNING_CRITICAL on Fire OS.
         */
        fun cutsOnWarning(level: Int, fireOs: Boolean): Boolean =
            countsAsWarning(level) ||
                (!fireOs && level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL &&
                    level < ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)

        /** Android often sends 40, 60 and 80 within seconds of each other; a warning within 5 s of the last one counts as the same cut. */
        fun warningInBurst(nowMs: Long, lastWarningAtMs: Long): Boolean = nowMs - lastWarningAtMs < WARNING_BURST_MS

        /** [WARNING_FLOOR_SECONDS] of film at [bitrateBps], at least [MIN_WARNING_FLOOR_BYTES]. */
        fun warningFloorBytes(bitrateBps: Long): Long =
            maxOf(MIN_WARNING_FLOOR_BYTES, bitrateBps.coerceAtLeast(0L) / 8L * WARNING_FLOOR_SECONDS)

        const val WARNING_BURST_MS = 5_000L
        const val WARNING_FLOOR_SECONDS = 20L
        const val MIN_WARNING_FLOOR_BYTES = BufferSizing.MIN_SIZED_BUFFER_MB * 1024L * 1024L

        /** No growth and no lift of the warning cap this long after a background-level warning. */
        const val WARNING_COOL_DOWN_MS = 10 * 60_000L

        /** The same line as the extra downloads' swap rule. */
        const val WARNING_SWAP_MB = 50L

        /**
         * Swap nearly gone with little room above the low-memory line: the box's memory killer is about to act even
         * though Android has not called it low memory, and some boxes never send a warning. Read every tick.
         */
        fun swapRunsOut(free: BufferSizing.FreeMemory, swapFreeMb: Long): Boolean =
            swapFreeMb in 0 until WARNING_SWAP_MB &&
                free.availMb - free.thresholdMb < BufferSizing.SYSTEM_MARGIN_MB + GROW_RESERVE_MB

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
        // One status line a minute, so the check can be seen in a log while the film is paused.
        const val CHECK_LOG_EVERY_MS = 60_000L
        /** Still short of memory this long after the first cut: cut further. */
        const val ESCALATE_AFTER_MS = 30_000L
        /** With swap gone, the cut steps up only this close to the low-memory line. */
        const val SWAP_ESCALATE_ROOM_MB = 60L
        /** One growth step per check (about every 10 s while playing). */
        const val GROW_STEP_MB = 32L
        /** Room the box keeps above its margin after a step, so a software thumbnail decode (25-35 MB) still fits. */
        const val GROW_RESERVE_MB = 64L
        /** No growth beside a nearly full swap unless RAM is plentiful: the box's memory killer watches swap too. */
        const val GROW_MIN_SWAP_MB = 96L
        const val GROW_ROOM_BESIDE_SWAP_MB = 300L
        /** Spare room a paused film leaves for the next app in front; a launcher with artwork takes about this much. */
        const val PAUSED_ROOM_MB = 300L
        /** Smaller changes of the address-space cap are left alone. */
        const val ROOM_STEP_BYTES = 16L * 1024 * 1024
        const val KEEP_IDLE_BYTES = 2 * 1024 * 1024
        /** 256 allocations of 64 KB per step. */
        const val TRIM_STEP_BYTES = 16 * 1024 * 1024
        private const val MAX_TRIM_STEPS = 256
        /** Thread stacks and decoders always keep this much under the real limit, whatever the buffer gave back. */
        internal const val ADDRESS_SPACE_HARD_SPARE_MB = 300L
    }
}

/**
 * Buffer memory given back to the allocator may keep its address range, so the address space does not shrink after a
 * seek. Remembers, for one player, the most the buffer held and the address space used then: what the buffer gave back
 * since is room it can fill again without the address space growing. Whatever the address space dropped by since the
 * peak was really returned, so it is never counted, and growth elsewhere after the peak cannot pass as kept room.
 */
class AddressSpaceMemo {
    private var peakHeldBytes = 0L
    private var usedAtPeakMb = 0L
    private var lowestUsedSincePeakMb = 0L

    @Synchronized
    fun reusableBytes(heldBytes: Long, usedMb: Long): Long {
        if (heldBytes >= peakHeldBytes) {
            peakHeldBytes = heldBytes
            usedAtPeakMb = usedMb
            lowestUsedSincePeakMb = usedMb
            return 0L
        }
        lowestUsedSincePeakMb = minOf(lowestUsedSincePeakMb, usedMb)
        val otherAtPeakMb = usedAtPeakMb - peakHeldBytes / MB
        val keptMb = usedMb - otherAtPeakMb - heldBytes / MB
        val returnedMb = usedAtPeakMb - lowestUsedSincePeakMb
        val stillKeptMb = (peakHeldBytes - heldBytes) / MB - returnedMb
        return (minOf(keptMb, stillKeptMb) * MB).coerceIn(0L, peakHeldBytes - heldBytes)
    }

    private companion object {
        const val MB = 1024L * 1024L
    }
}
