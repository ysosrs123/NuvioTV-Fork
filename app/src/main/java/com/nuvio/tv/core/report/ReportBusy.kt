package com.nuvio.tv.core.report

import java.util.concurrent.atomic.AtomicInteger

/** Background work that can compete with playback, as one bit each, read once a second by the recorder. */
object ReportBusy {
    const val THUMBNAILS = 1
    const val SYNC = 2
    const val SUBTITLE_SYNC = 4

    private val sync = AtomicInteger(0)
    private val subtitleSync = AtomicInteger(0)

    suspend fun <T> during(kind: Int, block: suspend () -> T): T {
        val counter = counterFor(kind) ?: return block()
        counter.incrementAndGet()
        try {
            return block()
        } finally {
            counter.decrementAndGet()
        }
    }

    fun flags(thumbnailsWorking: Boolean): Int = flagsOf(thumbnailsWorking, sync.get(), subtitleSync.get())

    internal fun flagsOf(thumbnailsWorking: Boolean, syncRunning: Int, subtitleSyncRunning: Int): Int {
        var flags = 0
        if (thumbnailsWorking) flags = flags or THUMBNAILS
        if (syncRunning > 0) flags = flags or SYNC
        if (subtitleSyncRunning > 0) flags = flags or SUBTITLE_SYNC
        return flags
    }

    private fun counterFor(kind: Int): AtomicInteger? = when (kind) {
        SYNC -> sync
        SUBTITLE_SYNC -> subtitleSync
        else -> null
    }
}
