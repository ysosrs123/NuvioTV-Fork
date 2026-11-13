package com.nuvio.tv.core.image

import android.content.Context
import android.util.Log
import coil3.SingletonImageLoader
import com.nuvio.tv.core.player.BufferSizing
import com.nuvio.tv.ui.brand.ArcBrandArt
import com.nuvio.tv.ui.components.releaseBadgeImageMemory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * On a 2 GB box a film starts with Home's decoded posters and backdrops still in memory. They are let go before the
 * buffer is sized, so the buffer and the thumbnails get that memory; the screens load them again from the disk cache.
 */
object PictureMemoryRelease {
    private const val TAG = "PictureMemory"
    private const val MB = 1024L * 1024L
    /** The longest the player build waits for the release, so the buffer is sized with the pictures gone; it carries on in the background after that. */
    private const val WAIT_MS = 500L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun releasesBeforePlayback(totalRamBytes: Long): Boolean = BufferSizing.isSmallBox(totalRamBytes)

    suspend fun releaseBeforePlayback(context: Context, totalRamBytes: Long) {
        if (!releasesBeforePlayback(totalRamBytes)) return
        val app = context.applicationContext
        val work = scope.async { release(app) }
        withTimeoutOrNull(WAIT_MS) { work.await() }
    }

    /** Android's last memory warning while a film plays: the same release, in the background. */
    fun releaseOnLastWarning(context: Context) {
        val app = context.applicationContext
        scope.launch { release(app, "on low memory") }
    }

    private fun release(context: Context, reason: String = "before playback") {
        val before = BufferSizing.read(context)
        val held = runCatching {
            val cache = SingletonImageLoader.get(context).memoryCache
            val size = cache?.size ?: 0L
            cache?.clear()
            size
        }.getOrDefault(0L)
        runCatching { releaseBadgeImageMemory() }
        runCatching { ArcBrandArt.release() }
        if (held <= 0L) return
        // The bitmaps' native and graphics memory only goes back once they are collected.
        Runtime.getRuntime().gc()
        val after = BufferSizing.read(context)
        Log.i(TAG, "MEMORY_RELEASE: pictures freed $reason cacheMb=${held / MB} " +
            "beforeAvailMb=${before?.availMb ?: -1} afterAvailMb=${after?.availMb ?: -1}")
    }
}
