package com.nuvio.tv.data.trailer

import android.content.Context
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@UnstableApi
@Singleton
class TrailerMediaCache @Inject constructor(@ApplicationContext private val context: Context) {
    private val initialization = Mutex()
    private val traffic = TrailerPreloadGate()
    @Volatile private var cache: SimpleCache? = null
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "TrailerPreload").apply { isDaemon = true } }

    suspend fun initialize() = withContext(Dispatchers.IO) {
        initialization.withLock {
            if (cache == null) {
                try {
                    cache = SimpleCache(File(context.cacheDir, "trailer-startup"),
                        LeastRecentlyUsedCacheEvictor(64L * 1024 * 1024), StandaloneDatabaseProvider(context))
                } catch (e: Exception) {
                    Log.w("TrailerMediaCache", "Cache unavailable; using network playback", e)
                }
            }
        }
    }

    private fun httpFactory(timeoutMs: Int = 15_000) = DefaultHttpDataSource.Factory()
        .setConnectTimeoutMs(timeoutMs).setReadTimeoutMs(timeoutMs).setAllowCrossProtocolRedirects(true)

    fun playbackFactory(): DataSource.Factory = DataSource.Factory {
        val upstream = DefaultDataSource.Factory(context, httpFactory())
        val ready = cache
        val downstream = if (ready == null) upstream.createDataSource() else
            CacheDataSource.Factory().setCache(ready).setUpstreamDataSourceFactory(upstream)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                .setEventListener(object : CacheDataSource.EventListener {
                    override fun onCacheIgnored(reason: Int) = Unit
                    override fun onCachedBytesRead(cacheSizeBytes: Long, cachedBytesRead: Long) {
                        Log.d("TrailerPlayback", "cachedBytesRead=$cachedBytesRead")
                    }
                }).createDataSource()
        object : DataSource by downstream {
            private var activeUrl: String? = null
            private fun endPlayback() {
                activeUrl?.let(traffic::endPlayback)
                activeUrl = null
            }
            override fun open(dataSpec: DataSpec): Long {
                val url = dataSpec.uri.toString()
                activeUrl = url
                traffic.beginPlayback(url)
                return try { downstream.open(dataSpec) }
                    catch (error: Exception) { endPlayback(); throw error }
            }
            override fun close() {
                try { downstream.close() } finally { endPlayback() }
            }
        }
    }

    suspend fun preload(source: TrailerPlaybackSource) {
        if (!isImdbMp4(source) || !TrailerSourceExpiry.isUsable(source)) return
        initialize()
        val ready = cache ?: return
        val dataSource = CacheDataSource.Factory().setCache(ready)
            .setUpstreamDataSourceFactory(httpFactory(3_000)).setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .createDataSource()
        val spec = DataSpec.Builder().setUri(source.videoUrl).setLength(2L * 1024 * 1024).build()
        val writer = CacheWriter(dataSource, spec, null, null)
        if (!traffic.beginWarm(source.videoUrl, writer) { writer.cancel() }) return
        try { withTimeoutOrNull(4_000L) {
            suspendCancellableCoroutine<Unit> { continuation ->
                continuation.invokeOnCancellation { writer.cancel() }
                worker.execute {
                    if (!continuation.isActive) return@execute
                    try { writer.cache() }
                    catch (_: Exception) { /* Warming is best effort; playback has its own retry path. */ }
                    finally { if (continuation.isActive) continuation.resume(Unit) }
                }
            }
        } } finally { traffic.endWarm(source.videoUrl, writer) }
    }

    companion object {
        fun isImdbMp4(source: TrailerPlaybackSource): Boolean = runCatching {
            val uri = java.net.URI(source.videoUrl)
            source.audioUrl == null && uri.host?.endsWith(".media-imdb.com") == true &&
                uri.path.contains(".mp4", ignoreCase = true)
        }.getOrDefault(false)
    }
}
