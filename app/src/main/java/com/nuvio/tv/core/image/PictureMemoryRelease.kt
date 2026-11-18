package com.nuvio.tv.core.image

import android.content.Context
import android.util.Log
import coil3.SingletonImageLoader
import com.nuvio.tv.core.player.BufferSizing
import com.nuvio.tv.ui.brand.ArcBrandArt
import com.nuvio.tv.ui.components.releaseBadgeImageMemory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
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
    /** Home's pictures go a little after the first frame, once the start of the film has settled. */
    private const val DURING_FILM_DELAY_MS = 5_000L
    /** Boxes sold as 3 GB or less; 4 GB boxes report well above this. */
    private const val THREE_GB_BOX_RAM_BYTES = 3_072L * MB

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun releasesBeforePlayback(totalRamBytes: Long): Boolean = BufferSizing.isSmallBox(totalRamBytes)

    suspend fun releaseBeforePlayback(context: Context, totalRamBytes: Long) {
        if (!releasesBeforePlayback(totalRamBytes)) return
        val app = context.applicationContext
        val work = scope.async { release(app) }
        withTimeoutOrNull(WAIT_MS) { work.await() }
    }

    /** Once a film plays, boxes of 3 GB or less, and any box Android has warned, keep none of Home's pictures in memory. */
    fun releasesDuringFilm(totalRamBytes: Long, warned: Boolean): Boolean =
        warned || totalRamBytes <= THREE_GB_BOX_RAM_BYTES

    @Volatile private var duringFilm: Job? = null

    fun releaseDuringFilm(context: Context, totalRamBytes: Long) {
        if (!releasesDuringFilm(totalRamBytes, warned = false)) return
        val app = context.applicationContext
        scheduleDuringFilm(scope) { release(app, "during film") }
    }

    internal fun scheduleDuringFilm(scope: CoroutineScope, action: () -> Unit) {
        duringFilm?.cancel()
        duringFilm = scope.launch {
            delay(DURING_FILM_DELAY_MS)
            action()
        }
    }

    /** The player closed before the release ran: Home is back on screen and keeps its pictures. */
    fun cancelDuringFilm() {
        duringFilm?.cancel()
        duringFilm = null
    }

    /** Android sends warnings in bursts; one release covers every warning within this of the first. */
    private const val WARNING_RELEASE_GAP_MS = 5_000L
    @Volatile private var lastWarningReleaseMs = Long.MIN_VALUE / 2

    internal fun warningReleaseDue(nowMs: Long, lastReleaseMs: Long): Boolean =
        nowMs - lastReleaseMs >= WARNING_RELEASE_GAP_MS

    /** A background-level memory warning while a film is open: the same release on any box, once per burst. */
    fun releaseOnWarning(context: Context) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (!warningReleaseDue(now, lastWarningReleaseMs)) return
        lastWarningReleaseMs = now
        val app = context.applicationContext
        scope.launch { release(app, "on memory warning") }
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
