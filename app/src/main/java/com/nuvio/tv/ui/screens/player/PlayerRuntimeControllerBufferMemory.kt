package com.nuvio.tv.ui.screens.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.nuvio.tv.core.image.PictureMemoryRelease
import com.nuvio.tv.core.player.BufferBudgetControl
import com.nuvio.tv.core.player.BufferMemoryKeeper
import com.nuvio.tv.core.player.BufferSizing
import com.nuvio.tv.core.player.DrawingMemory
import com.nuvio.tv.core.player.MemoryPressure
import com.nuvio.tv.core.player.PlaybackMemoryFacts

/** One keeper per load control; null (stock buffers, or no player) detaches it. */
@OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.attachBufferMemoryKeeper(
    control: BufferBudgetControl?,
    budgetManaged: Boolean = true,
    /** The box's own budget in MB when the built one was sized down to the memory free at the start. */
    ceilingMb: Int = 0,
    /** The least a memory cut leaves; [BufferSizing.sizedFloorMb]. */
    floorMb: Int = BufferSizing.MIN_SIZED_BUFFER_MB,
    oneGbBox: Boolean = false,
) {
    val current = bufferMemoryKeeper
    if (current != null && current.control === control) {
        current.setBudgetManaged(budgetManaged)
        current.setFloorBytes(floorMb.toLong() * 1024L * 1024L)
        PlaybackMemoryFacts.onKeeper(current)
        return
    }
    current?.detach()
    bufferMemoryKeeper = control?.let { budgetControl ->
        val appContext = context.applicationContext
        MemoryPressure.register(appContext)
        BufferMemoryKeeper(
            budgetControl,
            { BufferSizing.read(appContext) },
            budgetManaged = budgetManaged,
            ceilingBytes = ceilingMb.toLong() * 1024L * 1024L,
            swapFreeMb = {
                com.nuvio.tv.core.player.thumbnail.MemoryGovernor.readMeminfo()
                    ?.takeIf { it.swapTotalMb > 0L }?.swapFreeMb ?: -1L
            },
            floorBytes = floorMb.toLong() * 1024L * 1024L,
            oneGbBox = oneGbBox,
            onLastWarning = {
                PictureMemoryRelease.releaseOnLastWarning(appContext)
                DrawingMemory.releaseForFilm(
                    appContext, NuvioExoPlayerPerformanceHelper.getDevicePhysicalRamBytes(appContext), "lowMemory"
                )
            },
        ).also { it.attach() }
    }
    PlaybackMemoryFacts.onKeeper(bufferMemoryKeeper)
}
