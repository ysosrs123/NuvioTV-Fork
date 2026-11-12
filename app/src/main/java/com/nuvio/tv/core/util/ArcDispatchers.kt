package com.nuvio.tv.core.util

import android.app.ActivityManager
import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** How many threads background work may hold. A 32-bit process runs out of address space for stacks long before memory. */
data class ThreadBudget(val ioParallelism: Int?, val backgroundLanes: Int)

/** One shared lane for big background jobs (Scout rebuilds, slide artwork), so they cannot fill the IO pool. */
object ArcDispatchers {
    private const val IO_PARALLELISM_PROPERTY = "kotlinx.coroutines.io.parallelism"
    private const val SMALL_BOX_MB = 2_560L

    @Volatile private var lanes = 16

    val background: CoroutineDispatcher by lazy { Dispatchers.IO.limitedParallelism(lanes) }

    fun budgetFor(is64Bit: Boolean, totalMb: Long, lowRam: Boolean = false): ThreadBudget {
        val small = !is64Bit || lowRam || totalMb in 1..SMALL_BOX_MB
        return if (small) ThreadBudget(ioParallelism = 24, backgroundLanes = 8) else ThreadBudget(null, 16)
    }

    /** Must run before anything touches Dispatchers.IO: the IO pool reads its size once. */
    fun configure(context: Context) {
        val am = runCatching { context.getSystemService(ActivityManager::class.java) }.getOrNull()
        val totalMb = runCatching { ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }.totalMem shr 20 }.getOrDefault(0L)
        val budget = budgetFor(android.os.Process.is64Bit(), totalMb, am?.isLowRamDevice == true)
        budget.ioParallelism?.let { if (System.getProperty(IO_PARALLELISM_PROPERTY) == null) System.setProperty(IO_PARALLELISM_PROPERTY, it.toString()) }
        lanes = budget.backgroundLanes
    }
}
