package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.SubtitleParser
import com.nuvio.tv.NuvioApplication
import com.nuvio.tv.core.network.IPv4FirstDns
import com.nuvio.tv.core.player.VodCacheSizing
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.VodCacheSizeMode
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

internal class PlayerMediaSourceFactory(private val context: Context) {
    private var customExtractorsFactory: ExtractorsFactory? = null
    private var customSubtitleParserFactory: SubtitleParser.Factory? = null
    private val loadErrorHandlingPolicy = PlayerLoadErrorHandlingPolicy()

    @Volatile private var currentVodCacheUrl: String? = null
    @Volatile private var currentVodCacheResolvedUrl: String? = null
    @Volatile private var currentVodCacheActive: Boolean = false
    private val parallelStartupPrefetchUnlocked = AtomicBoolean(true)

    fun unlockStartupPrefetch() {
        parallelStartupPrefetchUnlocked.set(true)
    }

    var useParallelConnections: Boolean = PlayerSettings.DEFAULT_USE_PARALLEL_CONNECTIONS
    var parallelConnectionCount: Int = PlayerSettings.DEFAULT_PARALLEL_CONNECTION_COUNT
    var parallelChunkSizeKb: Int = PlayerSettings.DEFAULT_PARALLEL_CHUNK_SIZE_KB
    var nuvioPerformanceModeEnabled: Boolean = PlayerSettings.DEFAULT_NUVIO_PERFORMANCE_MODE_ENABLED

    /**
     * How many chunks ParallelRangeDataSource may keep in flight ahead of the read
     * cursor. The pre-nt-tier2 code pinned this to connections+1, which throttled the
     * scheduler to ~1-2 effective concurrent downloads on a remux (measured: 4
     * connections summed to ~139 Mbit/s while a single connection to the same CDN edge
     * already did ~125, and two PC connections cleanly doubled to ~256 — the fetcher,
     * not the link or the CDN, was the ceiling). A deeper window bursts more chunks to
     * the executor at once (maybeSchedulePrefetch schedules the whole window via
     * computeIfAbsent), so the connections actually saturate and the pipeline can race
     * ahead of playback to build reserve; ExoPlayer's own load control then throttles
     * by gating reads once its SampleQueue is full, so no second control loop is needed.
     *
     * The chunk pool is NATIVE memory (allocateDirect / DefaultAllocatorNative), bounded
     * by device RAM, not the Java heap — so the budget is the canonical device-RAM figure
     * getSafeNativeMemoryLimitMb (250 MB on ~2 GB, 500 on ~3 GB, 1000 on ~4 GB), NOT
     * MemoryBudget.budgetMb (which is heap-tiered and wrong for a native pool). We spend
     * the safe native budget minus the SampleQueue's own target-buffer bytes on chunks,
     * divide by chunk size, and clamp to [2*connections, connections*4] so the window is
     * always at least deep enough to saturate the connections and never absurdly deep.
     * On a 2 GB device this still yields a useful window; on 4 GB it opens up fully.
     */
    private fun computePrefetchDepthChunks(
        connections: Int,
        chunkBytes: Long,
        mp4SessionMode: Boolean
    ): Int {
        // MP4 session mode is deliberately single-connection; leave its 1+1 behaviour.
        if (mp4SessionMode || !nuvioPerformanceModeEnabled) return connections + 1
        val chunkMb = (chunkBytes / (1024L * 1024L)).toInt().coerceAtLeast(1)
        val safeNativeMb = NuvioExoPlayerPerformanceHelper.getSafeNativeMemoryLimitMb(context)
        // Reserve the SampleQueue's target-buffer bytes (its own pool, set from settings)
        // so the two together stay inside the safe envelope; give the remainder to chunks.
        val reserveMb = NuvioExoPlayerPerformanceHelper.targetBufferSizeMb.coerceAtLeast(0)
        // nt-exact: delegate to the shared single-source-of-truth function so the runtime
        // window and the settings screen's displayed estimate can never drift apart.
        return com.nuvio.tv.ui.screens.settings.MemoryBudget.prefetchDepthChunks(
            connections, chunkMb, safeNativeMb, reserveMb
        )
    }
    var vodCacheEnabled: Boolean = PlayerSettings.DEFAULT_VOD_CACHE_ENABLED
    var vodCacheSizeMode: VodCacheSizeMode = PlayerSettings.DEFAULT_VOD_CACHE_SIZE_MODE
    var vodCacheSizeMb: Int = PlayerSettings.DEFAULT_VOD_CACHE_SIZE_MB

    // OkHttp client used only by the opt-in parallel-connections path.
    // Renamed from `playbackHttpClient` (NEW-14): the old name silently
    // shadowed PlayerPlaybackNetworking.playbackHttpClient inside this file
    // and cost two retractions in one session.
    private val chunkSessionHttpClient by lazy {
        PlayerPlaybackNetworking.playbackHttpClient.newBuilder()
            .cookieJar(NuvioApplication.extensionCookieJar)
            .let { NuvioExoPlayerPerformanceHelper.applyNetworkOptimizations(it) }
            .build()
    }

    fun configureSubtitleParsing(
        extractorsFactory: ExtractorsFactory?,
        subtitleParserFactory: SubtitleParser.Factory?
    ) {
        customExtractorsFactory = extractorsFactory
        customSubtitleParserFactory = subtitleParserFactory
    }

    /**
     * nt13: the chunk-session shape for one stream -- resolved mime, whether the
     * chunk-session source engages at all, and the connection/chunk geometry.
     *
     * Extracted so the chunk-0 pre-start path derives it through exactly the same
     * code createMediaSource does. The companion session store keys on the request
     * URI *and* the chunk size, so a second derivation that drifted by even one
     * branch would create a session the player then declines to adopt -- silently
     * paying for a chunk nobody reads. One function, two callers, no drift.
     */
    private data class ChunkSessionShape(
        val resolvedMimeType: String?,
        val isHls: Boolean,
        val isDash: Boolean,
        val mp4SessionMode: Boolean,
        val useChunkSessionSource: Boolean,
        val effectiveConnections: Int,
        val effectiveChunkBytes: Long
    )

    private fun resolveChunkSessionShape(
        url: String,
        filename: String?,
        responseHeaders: Map<String, String>,
        mimeTypeOverride: String?
    ): ChunkSessionShape {
        val resolvedMimeType = mimeTypeOverride ?: inferMimeType(
            url = url,
            filename = filename,
            responseHeaders = responseHeaders
        )
        val isHls = resolvedMimeType == MimeTypes.APPLICATION_M3U8
        val isDash = resolvedMimeType == MimeTypes.APPLICATION_MPD
        val mp4SessionMode = !useParallelConnections && !isHls && !isDash &&
            resolvedMimeType == MimeTypes.VIDEO_MP4
        val useChunkSessionSource = (useParallelConnections || mp4SessionMode) && !isHls && !isDash
        return ChunkSessionShape(
            resolvedMimeType = resolvedMimeType,
            isHls = isHls,
            isDash = isDash,
            mp4SessionMode = mp4SessionMode,
            useChunkSessionSource = useChunkSessionSource,
            effectiveConnections = if (mp4SessionMode) 1 else parallelConnectionCount,
            effectiveChunkBytes =
                if (mp4SessionMode) MP4_SESSION_CHUNK_BYTES else parallelChunkSizeKb.toLong() * 1024L
        )
    }

    /**
     * nt13: schedule chunk 0 for [url] before the player is built. No-op unless the
     * chunk-session source would actually engage for this stream, and no-op in MP4
     * session mode -- that shape depends on the resolved mime type, which is firmer
     * at createMediaSource time than it is here, and a geometry mismatch costs a
     * wasted chunk. Safe to call more than once for the same URL.
     */
    fun prestartChunk0(
        url: String,
        headers: Map<String, String>,
        filename: String? = null,
        responseHeaders: Map<String, String> = emptyMap(),
        mimeTypeOverride: String? = null
    ) {
        val shape = resolveChunkSessionShape(
            url = url,
            filename = filename,
            responseHeaders = responseHeaders,
            mimeTypeOverride = mimeTypeOverride
        )
        if (!shape.useChunkSessionSource || shape.mp4SessionMode) return
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
        val okHttpFactory = OkHttpDataSource.Factory(chunkSessionHttpClient).apply {
            setDefaultRequestProperties(sanitizeHeaders(headers))
            setUserAgent(DEFAULT_USER_AGENT)
        }
        ParallelRangeDataSource.Factory(
            okHttpFactory,
            shape.effectiveConnections,
            shape.effectiveChunkBytes,
            useNativeMemory = nuvioPerformanceModeEnabled,
            prefetchDepthChunks = computePrefetchDepthChunks(
                shape.effectiveConnections,
                shape.effectiveChunkBytes,
                shape.mp4SessionMode
            )
        ).prestartChunk0(uri)
    }

    fun createMediaSource(
        context: Context,
        url: String,
        headers: Map<String, String>,
        subtitleConfigurations: List<MediaItem.SubtitleConfiguration> = emptyList(),
        filename: String? = null,
        responseHeaders: Map<String, String> = emptyMap(),
        mimeTypeOverride: String? = null,
        audioDelayUsProvider: (() -> Long)? = null,
        mediaMetadata: androidx.media3.common.MediaMetadata? = null
    ): MediaSource {
        val sanitizedHeaders = sanitizeHeaders(headers)
        val httpDataSourceFactory = PlayerPlaybackNetworking.createDataSourceFactory(context, sanitizedHeaders)

        val chunkSessionShape = resolveChunkSessionShape(
            url = url,
            filename = filename,
            responseHeaders = responseHeaders,
            mimeTypeOverride = mimeTypeOverride
        )
        val resolvedMimeType = chunkSessionShape.resolvedMimeType
        val isHls = chunkSessionShape.isHls
        val isDash = chunkSessionShape.isDash

        val mediaItemBuilder = MediaItem.Builder().setUri(url)
        resolvedMimeType?.let(mediaItemBuilder::setMimeType)
        filename?.takeIf { it.isNotBlank() }?.let(mediaItemBuilder::setMediaId)
        mediaMetadata?.let(mediaItemBuilder::setMediaMetadata)

        if (subtitleConfigurations.isNotEmpty()) {
            mediaItemBuilder.setSubtitleConfigurations(subtitleConfigurations)
        }

        val mediaItem = mediaItemBuilder.build()

        // 1. Chunk-session source: parallel connections (opt-in), or MP4 session
        // mode. Non-faststart / poorly interleaved MP4s force scatter reads, and
        // ExoPlayer recreates the data source on every seek; the session-owned
        // chunks in ParallelRangeDataSource are what survive that boundary. That
        // fix used to be reachable only behind the parallel-connections opt-in,
        // so progressive MP4 now engages the session source even with parallel
        // off — pinned to a single connection and an 8 MB chunk, which holds
        // request concurrency and retained memory at plain-path levels (earned
        // prefetch caps lookahead at two chunks; side cursors fetch only the
        // chunk they touch). HLS/DASH, non-MP4 progressive, and parallel-on
        // behaviour are unchanged.
        val mp4SessionMode = chunkSessionShape.mp4SessionMode
        val useChunkSessionSource = chunkSessionShape.useChunkSessionSource
        parallelStartupPrefetchUnlocked.set(!useChunkSessionSource)
        val progressiveUpstreamFactory: DataSource.Factory = if (useChunkSessionSource) {
            if (mp4SessionMode) {
                Log.i(
                    "PlayerMediaSourceFactory",
                    "MP4_SESSION engaged: single-connection chunk session " +
                        "(${MP4_SESSION_CHUNK_BYTES / (1024L * 1024L)} MB chunks) " +
                        "for progressive MP4 with parallel connections off"
                )
            }
            val okHttpFactory = OkHttpDataSource.Factory(chunkSessionHttpClient).apply {
                setDefaultRequestProperties(sanitizedHeaders)
                setUserAgent(DEFAULT_USER_AGENT)
            }
            run {
                val effectiveConnections = chunkSessionShape.effectiveConnections
                val effectiveChunkBytes = chunkSessionShape.effectiveChunkBytes
                ParallelRangeDataSource.Factory(
                    okHttpFactory,
                    effectiveConnections,
                    effectiveChunkBytes,
                    useNativeMemory = nuvioPerformanceModeEnabled,
                    prefetchDepthChunks = computePrefetchDepthChunks(
                        effectiveConnections,
                        effectiveChunkBytes,
                        mp4SessionMode
                    ),
                    shouldAllowBackgroundPrefetch = { true },
                    // S1: MP4 session mode keeps whole-chunk retention -- its
                    // scatter-read cursors revisit regions, and a retained chunk
                    // makes every repeat visit free. Unmeasured there; gate it off.
                    allowContinuationReopen = !mp4SessionMode,
                    onResolvedUri = { resolved -> currentVodCacheResolvedUrl = resolved?.toString() }
                )
            }
        } else {
            httpDataSourceFactory
        }

        // 2. VOD disk cache (opt-in).
        val useVodCache = ENABLE_VOD_CACHE && vodCacheEnabled && !isHls && !isDash && shouldUseVodCache(url)
        val previousVodCacheActive = currentVodCacheActive
        currentVodCacheUrl = url
        currentVodCacheResolvedUrl = null
        // Size the cache only when used; 0 means off or not enough free space (skip, stream direct).
        val vodCacheMaxBytes = if (useVodCache && !isVodCacheDisabled) resolveVodCacheMaxBytes() else 0L
        val vodCacheActive = vodCacheMaxBytes > 0L

        if (vodCacheActive) {
            maybeApplyLiveVodCacheCapIncrease(context, vodCacheMaxBytes, !previousVodCacheActive)
        }

        val progressiveFactory: DataSource.Factory = if (vodCacheActive) {
            val cache = getReadySimpleCache(vodCacheMaxBytes) ?: getAnySimpleCache()
            if (cache != null) {
                currentVodCacheActive = true
                buildVodCacheDataSourceFactory(progressiveUpstreamFactory, cache)
            } else {
                currentVodCacheActive = false
                progressiveUpstreamFactory
            }
        } else {
            currentVodCacheActive = false
            progressiveUpstreamFactory
        }

        val extractorsFactory = customExtractorsFactory ?: DefaultExtractorsFactory()
        // CountingDataSourceFactory attaches PlaybackByteCounter to every DataSource
        // the player creates. media3's bandwidth meter only reports bytes when a
        // transfer ends, and a plain progressive load holds one transfer open for the
        // whole file, so the HUD has to count for itself. See PlaybackByteCounter.
        val defaultFactory = DefaultMediaSourceFactory(
            CountingDataSourceFactory(LoggingDataSourceFactory(progressiveFactory, "PMSF")),
            extractorsFactory
        ).apply {
            setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
            customSubtitleParserFactory?.let { parserFactory ->
                setSubtitleParserFactory(parserFactory)
            }
        }
        val forceDefaultFactory = customExtractorsFactory != null || customSubtitleParserFactory != null

        // Sidecar subtitles are more reliable through DefaultMediaSourceFactory.
        if (subtitleConfigurations.isNotEmpty()) {
            return wrapAudioDelay(
                mediaSource = defaultFactory.createMediaSource(mediaItem),
                audioDelayUsProvider = audioDelayUsProvider
            )
        }

        val mediaSource = when {
            isHls && !forceDefaultFactory -> HlsMediaSource.Factory(httpDataSourceFactory)
                .setAllowChunklessPreparation(true)
                .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
                .createMediaSource(mediaItem)
            isDash && !forceDefaultFactory -> DashMediaSource.Factory(httpDataSourceFactory)
                .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
                .createMediaSource(mediaItem)
            else -> defaultFactory.createMediaSource(mediaItem)
        }
        return wrapAudioDelay(mediaSource = mediaSource, audioDelayUsProvider = audioDelayUsProvider)
    }

    fun shutdown() {
        // nt6: free any chunk buffers retained across seek reopens so native
        // allocations never outlive the player.
        ParallelRangeDataSource.releaseRetainedSession()
    }

    private fun buildVodCacheDataSourceFactory(upstreamFactory: DataSource.Factory, cache: SimpleCache): DataSource.Factory {
        val dataSinkFactory = CacheDataSink.Factory().setCache(cache).setFragmentSize(2L * 1024L * 1024L)
        return CacheDataSource.Factory()
            .setCache(cache)
            .setCacheWriteDataSinkFactory(dataSinkFactory)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    private fun shouldUseVodCache(url: String): Boolean {
        val scheme = Uri.parse(url).scheme?.lowercase()
        return scheme == "https" || scheme == "http"
    }

    // Maths extracted to core.player.VodCacheSizing (shared with the Device
    // Assessment). Free space is read ONCE here and passed in; the inline
    // original read it twice in quick succession, which is equivalent for any
    // stable value - the single read just removes a benign race.
    private fun resolveVodCacheMaxBytes(): Long =
        VodCacheSizing.resolveMaxBytes(
            freeSpaceBytes = context.cacheDir.usableSpace,
            mode = vodCacheSizeMode,
            manualSizeMb = vodCacheSizeMb
        )

    companion object {
        private const val MIME_VIDEO_QUICK_TIME = "video/quicktime"
        // MP4 session mode: fixed 8 MB chunk, independent of the user's parallel
        // chunk-size setting — small enough that scatter-read side cursors waste
        // little per touch, and the retained set (session cap 3 chunks on the
        // low-RAM tier, 5 otherwise) stays a few tens of MB.
        private const val MP4_SESSION_CHUNK_BYTES = 8L * 1024L * 1024L
        private const val ENABLE_VOD_CACHE = true
        internal const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        private const val MIME_PROBE_CACHE_SIZE = 64

        data class StreamProbeInfo(
            val contentLength: Long,
            val acceptsRanges: Boolean
        )

        private val probeInfoCache = object : LinkedHashMap<String, StreamProbeInfo>(MIME_PROBE_CACHE_SIZE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, StreamProbeInfo>?): Boolean {
                return size > MIME_PROBE_CACHE_SIZE
            }
        }

        @JvmStatic
        fun getProbeInfo(url: String, headers: Map<String, String>): StreamProbeInfo? {
            val sanitizedHeaders = sanitizeHeaders(headers)
            val cacheKey = buildMimeProbeCacheKey(url, sanitizedHeaders)
            return synchronized(probeInfoCache) {
                probeInfoCache[cacheKey]
            }
        }

        private fun cacheProbeInfo(url: String, headers: Map<String, String>, contentLength: Long, acceptsRanges: Boolean) {
            val sanitizedHeaders = sanitizeHeaders(headers)
            val cacheKey = buildMimeProbeCacheKey(url, sanitizedHeaders)
            synchronized(probeInfoCache) {
                probeInfoCache[cacheKey] = StreamProbeInfo(contentLength, acceptsRanges)
            }
        }

        private fun buildMimeProbeCacheKey(url: String, headers: Map<String, String>): String {
            if (headers.isEmpty()) return url
            return buildString {
                append(url)
                headers.toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (key, value) ->
                    append('|')
                    append(key)
                    append('=')
                    append(value)
                }
            }
        }

        data class NormalizedPlaybackRequest(
            val url: String,
            val headers: Map<String, String>
        )

        @Volatile private var sharedSimpleCache: SimpleCache? = null
        @Volatile private var configuredVodCacheMaxBytes: Long = -1L
        @Volatile private var isVodCacheDisabled: Boolean = false

        fun sanitizeHeaders(headers: Map<String, String>?): Map<String, String> {
            val raw: Map<*, *> = headers ?: return emptyMap()
            if (raw.isEmpty()) return emptyMap()

            val sanitized = LinkedHashMap<String, String>(raw.size)
            raw.forEach { (rawKey, rawValue) ->
                val key = (rawKey as? String)?.trim().orEmpty()
                val value = (rawValue as? String)?.trim().orEmpty()
                if (key.isEmpty() || value.isEmpty()) return@forEach
                if (key.equals("Range", ignoreCase = true)) return@forEach
                sanitized[key] = value
            }
            return sanitized
        }

        fun normalizePlaybackRequest(
            url: String,
            headers: Map<String, String>?
        ): NormalizedPlaybackRequest {
            val sanitizedHeaders = sanitizeHeaders(headers)
            val (cleanUrl, mergedHeaders) = extractUserInfoAuth(url, sanitizedHeaders)
            return NormalizedPlaybackRequest(
                url = cleanUrl,
                headers = sanitizeHeaders(mergedHeaders)
            )
        }

        fun parseHeaders(headers: String?): Map<String, String> {
            if (headers.isNullOrEmpty()) return emptyMap()

            return try {
                // Try JSON format first (new)
                if (headers.trimStart().startsWith("{")) {
                    val json = org.json.JSONObject(headers)
                    val result = LinkedHashMap<String, String>()
                    json.keys().forEach { key ->
                        val value = json.optString(key, "")
                        if (key.isNotEmpty() && value.isNotEmpty()) {
                            result[key] = value
                        }
                    }
                    return sanitizeHeaders(result)
                }

                // Legacy key=value&key=value format (backward compat)
                val parsed = headers.split("&").associate { pair ->
                    val parts = pair.split("=", limit = 2)
                    if (parts.size == 2) {
                        URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8")
                    } else {
                        "" to ""
                    }
                }.filterKeys { it.isNotEmpty() }
                sanitizeHeaders(parsed)
            } catch (_: Exception) {
                emptyMap()
            }
        }

        private fun getReadySimpleCache(expectedMaxBytes: Long): SimpleCache? {
            val cache = sharedSimpleCache ?: return null
            return if (configuredVodCacheMaxBytes == expectedMaxBytes) cache else null
        }

        private fun getAnySimpleCache(): SimpleCache? = sharedSimpleCache

        private fun maybeApplyLiveVodCacheCapIncrease(
            context: Context,
            requestedMaxBytes: Long,
            allowLiveReconfigure: Boolean
        ) {
            // Live cache reconfiguration is not yet implemented; the shared cache is
            // created lazily elsewhere. Kept as the integration point for the VOD cache.
        }

        private fun inferAdaptiveMimeTypeFromPath(path: String?): String? {
            val normalized = path?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotBlank() } ?: return null
            val pathWithoutFragment = normalized.substringBefore('#')
            val pathPart = pathWithoutFragment.substringBefore('?')
            val fileName = pathPart.substringAfterLast('/')
            val extension = fileName.substringAfterLast('.', missingDelimiterValue = "")
            return when (extension) {
                "m3u8", "m3u" -> MimeTypes.APPLICATION_M3U8
                "mpd" -> MimeTypes.APPLICATION_MPD
                "ism", "isml" -> MimeTypes.APPLICATION_SS
                else -> null
            }
        }

        internal fun inferMimeType(
            url: String,
            filename: String?,
            responseHeaders: Map<String, String>? = null
        ): String? {
            val adaptiveMime = inferAdaptiveMimeTypeFromPath(filename)
                ?: inferAdaptiveMimeTypeFromPath(url)
            if (adaptiveMime != null) {
                return adaptiveMime
            }

            return inferMimeTypeFromResponseHeaders(responseHeaders)
                ?: inferMimeTypeFromPath(filename)
                ?: inferMimeTypeFromPath(url)
        }

        internal fun normalizeMimeType(contentType: String?): String? {
            val normalized = contentType
                ?.substringBefore(';')
                ?.trim()
                ?.lowercase(Locale.US)
                ?: return null

            return when (normalized) {
                "application/vnd.apple.mpegurl",
                "application/mpegurl",
                "application/x-mpegurl",
                "audio/mpegurl",
                "audio/x-mpegurl",
                "application/m3u8" -> MimeTypes.APPLICATION_M3U8

                "application/dash+xml",
                "video/vnd.mpeg.dash.mpd" -> MimeTypes.APPLICATION_MPD

                "application/vnd.ms-sstr+xml" -> MimeTypes.APPLICATION_SS

                "video/mp4",
                "application/mp4",
                "video/x-m4v" -> MimeTypes.VIDEO_MP4

                "video/webm",
                "audio/webm" -> MimeTypes.VIDEO_WEBM

                "video/x-matroska",
                "audio/x-matroska",
                "video/mkv",
                "audio/mkv" -> MimeTypes.VIDEO_MATROSKA
                else -> null
            }
        }

        internal fun sniffManifestMimeType(snippet: String?): String? {
            val normalized = snippet
                ?.trimStart()
                ?.lowercase(Locale.US)
                ?: return null

            return when {
                normalized.startsWith("#extm3u") -> MimeTypes.APPLICATION_M3U8
                normalized.startsWith("<?xml") && normalized.contains("<mpd") -> MimeTypes.APPLICATION_MPD
                normalized.startsWith("<mpd") -> MimeTypes.APPLICATION_MPD
                else -> null
            }
        }

        suspend fun probeMimeType(
            url: String,
            headers: Map<String, String>,
            filename: String? = null,
            responseHeaders: Map<String, String>? = null
        ): String? {
            return inferMimeType(
                url = url,
                filename = filename,
                responseHeaders = responseHeaders
            )
        }

        suspend fun probeNetworkMimeType(
            url: String,
            headers: Map<String, String> = emptyMap()
        ): String? = withContext(Dispatchers.IO) {
            if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
                return@withContext null
            }
            val sanitizedHeaders = sanitizeHeaders(headers)
            val methods = listOf("HEAD", "GET")
            for (method in methods) {
                runCatching {
                    val requestBuilder = Request.Builder().url(url)
                    if (method == "GET") {
                        requestBuilder.header("Range", "bytes=0-2048")
                    }
                    sanitizedHeaders.forEach { (key, value) ->
                        if (!key.equals("Range", ignoreCase = true)) {
                            requestBuilder.header(key, value)
                        }
                    }
                    if (sanitizedHeaders.none { it.key.equals("User-Agent", ignoreCase = true) }) {
                        requestBuilder.header("User-Agent", DEFAULT_USER_AGENT)
                    }

                    PlayerPlaybackNetworking.playbackHttpClient.newCall(requestBuilder.build()).execute().use { response ->
                        if (!response.isSuccessful && response.code !in 200..308) {
                            return@use null
                        }

                        val finalUrl = response.request.url.toString()
                        inferAdaptiveMimeTypeFromPath(finalUrl)?.let { return@withContext it }

                        val contentType = response.header("Content-Type")
                        normalizeMimeType(contentType)?.let { return@withContext it }

                        val responseHeadersMap = response.headers.names().associateWith { response.header(it).orEmpty() }
                        inferMimeTypeFromResponseHeaders(responseHeadersMap)?.let { return@withContext it }

                        if (method == "GET") {
                            val snippet = response.body?.byteStream()?.use { stream ->
                                val bytes = ByteArray(512)
                                val read = stream.read(bytes)
                                if (read > 0) String(bytes, 0, read, Charsets.UTF_8) else null
                            }
                            sniffManifestMimeType(snippet)?.let { return@withContext it }
                        }

                        inferMimeTypeFromPath(finalUrl)?.let { return@withContext it }
                    }
                }.getOrNull()?.let { return@withContext it }
            }
            null
        }

        private fun inferMimeTypeFromResponseHeaders(headers: Map<String, String>?): String? {
            if (headers.isNullOrEmpty()) return null

            val contentType = headers.entries
                .firstOrNull { (key, _) -> key.equals("Content-Type", ignoreCase = true) }
                ?.value
            normalizeMimeType(contentType)?.let { return it }

            val contentDisposition = headers.entries
                .firstOrNull { (key, _) -> key.equals("Content-Disposition", ignoreCase = true) }
                ?.value
                ?: return null

            val filename = contentDisposition
                .substringAfter("filename*=", missingDelimiterValue = "")
                .substringAfterLast("''", missingDelimiterValue = "")
                .ifBlank {
                    contentDisposition.substringAfter("filename=", missingDelimiterValue = "")
                }
                .trim()
                .trim('"', '\'')
                .takeIf { it.isNotBlank() }

            return inferMimeTypeFromPath(filename)
        }

        private fun inferMimeTypeFromPath(path: String?): String? {
            val normalized = path?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotBlank() } ?: return null
            val pathWithoutFragment = normalized.substringBefore('#')
            val pathPart = pathWithoutFragment.substringBefore('?')
            val queryPart = pathWithoutFragment.substringAfter('?', missingDelimiterValue = "")
            val fileName = pathPart.substringAfterLast('/')
            val extension = fileName.substringAfterLast('.', missingDelimiterValue = "")

            return when {
                extension == "m3u8" || extension == "m3u" -> MimeTypes.APPLICATION_M3U8
                extension == "mpd" -> MimeTypes.APPLICATION_MPD
                extension == "ism" || extension == "isml" -> MimeTypes.APPLICATION_SS
                extension == "mkv" -> MimeTypes.VIDEO_MATROSKA
                extension == "webm" -> MimeTypes.VIDEO_WEBM
                extension == "mp4" || extension == "m4v" -> MimeTypes.VIDEO_MP4
                extension == "ts" || extension == "mts" || extension == "m2ts" -> MimeTypes.VIDEO_MP2T
                extension == "mov" -> MIME_VIDEO_QUICK_TIME
                extension == "avi" -> MimeTypes.VIDEO_AVI
                extension == "mpeg" || extension == "mpg" -> MimeTypes.VIDEO_MPEG
                else -> inferMimeTypeFromQuery(queryPart)
                    ?: inferMimeTypeFromDelimitedToken(pathPart)
                    ?: inferMimeTypeFromDelimitedToken(queryPart)
            }
        }

        private fun inferMimeTypeFromQuery(query: String): String? {
            if (query.isBlank()) return null

            query.split('&').forEach { parameter ->
                val key = parameter.substringBefore('=', missingDelimiterValue = "").trim()
                val value = parameter.substringAfter('=', missingDelimiterValue = "").trim()
                if (key.isBlank() || value.isBlank()) return@forEach

                when (key) {
                    "format",
                    "mime",
                    "mime_type",
                    "contenttype",
                    "content_type",
                    "type",
                    "ext",
                    "extension",
                    "output",
                    "protocol",
                    "mode",
                    "stream",
                    "service" -> {
                        when (value.substringAfterLast('/').substringAfterLast('.')) {
                            "m3u8", "m3u" -> return MimeTypes.APPLICATION_M3U8
                            "mpd" -> return MimeTypes.APPLICATION_MPD
                            "ism", "isml" -> return MimeTypes.APPLICATION_SS
                            "mkv" -> return MimeTypes.VIDEO_MATROSKA
                            "webm" -> return MimeTypes.VIDEO_WEBM
                            "mp4", "m4v" -> return MimeTypes.VIDEO_MP4
                            "ts", "mts", "m2ts" -> return MimeTypes.VIDEO_MP2T
                            "mov" -> return MIME_VIDEO_QUICK_TIME
                            "avi" -> return MimeTypes.VIDEO_AVI
                            "mpeg", "mpg" -> return MimeTypes.VIDEO_MPEG
                        }
                    }
                }

                when (value) {
                    "application/vnd.apple.mpegurl",
                    "application/mpegurl",
                    "application/x-mpegurl",
                    "audio/mpegurl",
                    "audio/x-mpegurl",
                    "application/m3u8",
                    "m3u8",
                    "m3u",
                    "hls" -> return MimeTypes.APPLICATION_M3U8
                    "application/dash+xml",
                    "video/vnd.mpeg.dash.mpd",
                    "dash" -> return MimeTypes.APPLICATION_MPD
                    "application/vnd.ms-sstr+xml",
                    "smoothstreaming",
                    "ss" -> return MimeTypes.APPLICATION_SS
                }
            }

            return null
        }

        private fun inferMimeTypeFromDelimitedToken(value: String): String? {
            if (value.isBlank()) return null

            return when {
                DELIMITED_M3U8_PATTERN.containsMatchIn(value) -> MimeTypes.APPLICATION_M3U8
                PLAYLIST_HLS_PATTERN.containsMatchIn(value) -> MimeTypes.APPLICATION_M3U8
                DELIMITED_MPD_PATTERN.containsMatchIn(value) -> MimeTypes.APPLICATION_MPD
                DELIMITED_SS_PATTERN.containsMatchIn(value) -> MimeTypes.APPLICATION_SS
                else -> null
            }
        }


        private fun wrapAudioDelay(
            mediaSource: MediaSource,
            audioDelayUsProvider: (() -> Long)?
        ): MediaSource {
            return if (audioDelayUsProvider == null) {
                mediaSource
            } else {
                AudioDelayMediaSource(
                    mediaSource = mediaSource,
                    audioDelayUsProvider = audioDelayUsProvider
                )
            }
        }

        private val DELIMITED_M3U8_PATTERN = Regex("(^|[=/_.?&-])(m3u8|m3u)($|[=/_.?&-])")
        private val PLAYLIST_HLS_PATTERN = Regex("/(playlist|hls|manifest|master|vs)/(?!stream$|list$|info$|details$)[a-zA-Z0-9_/-]+$")
        private val DELIMITED_MPD_PATTERN = Regex("(^|[=/_.?&-])mpd($|[=/_.?&-])")
        private val DELIMITED_SS_PATTERN = Regex("(^|[=/_.?&-])(ism|isml)($|[=/_.?&-])")

        /**
         * Extracts `user:pass` from a URL's userinfo component and converts it
         * to a Basic Auth header. Returns the cleaned URL (without userinfo) and
         * merged headers. If the URL has no userinfo, returns the original URL and headers unchanged.
         *
         * The returned URL has no userinfo, and the returned headers carry Basic auth.
         */
        fun extractUserInfoAuth(
            url: String,
            headers: Map<String, String>
        ): Pair<String, Map<String, String>> {
            if (url.isBlank()) return url to headers
            val uri = try { java.net.URI(url) } catch (_: Exception) { return url to headers }
            val rawUserInfo = uri.rawAuthority
                ?.substringBeforeLast('@', missingDelimiterValue = "")
                ?.takeIf { it.isNotBlank() }
            val userInfo = uri.userInfo ?: rawUserInfo?.let(::decodeRawUserInfo) ?: return url to headers
            if (userInfo.isBlank()) return url to headers
            val cleanUrl = stripRawUserInfo(uri) ?: return url to headers
            val mergedHeaders = LinkedHashMap(headers)
            if (headers.none { it.key.equals("Authorization", ignoreCase = true) }) {
                val encoded = Base64.getEncoder().encodeToString(userInfo.toByteArray(Charsets.UTF_8))
                mergedHeaders["Authorization"] = "Basic $encoded"
            }
            return cleanUrl to mergedHeaders
        }

        private fun stripRawUserInfo(uri: java.net.URI): String? {
            val scheme = uri.scheme?.takeIf { it.isNotBlank() } ?: return null
            val rawAuthority = uri.rawAuthority?.takeIf { it.isNotBlank() } ?: return null
            val cleanAuthority = rawAuthority.substringAfterLast('@', missingDelimiterValue = rawAuthority)
                .takeIf { it != rawAuthority && it.isNotBlank() }
                ?: return null
            return buildString {
                append(scheme)
                append("://")
                append(cleanAuthority)
                append(uri.rawPath.orEmpty())
                uri.rawQuery?.let {
                    append('?')
                    append(it)
                }
                uri.rawFragment?.let {
                    append('#')
                    append(it)
                }
            }
        }

        private fun decodeRawUserInfo(value: String): String? =
            runCatching {
                URLDecoder.decode(value.replace("+", "%2B"), Charsets.UTF_8.name())
            }.getOrNull()
    }
}

private inline fun <reified T : Throwable> Throwable.findCause(): T? {
    var current: Throwable? = this
    while (current != null) {
        if (current is T) return current
        current = current.cause
    }
    return null
}

private class PlayerLoadErrorHandlingPolicy : DefaultLoadErrorHandlingPolicy(6) {

    // fatal-429 (27 Aug spec, Build 1): monotonic ms of the last 429/503 seen and the
    // start of the current continuous rate-limit streak. Shared across every MediaPeriod
    // that uses this single policy instance; AtomicLong because getRetryDelayMsFor runs on
    // the loader thread and getMinimumLoadableRetryCount on the playback thread. 0L = idle.
    private val rateLimitLastHitMs = java.util.concurrent.atomic.AtomicLong(0L)
    private val rateLimitStreakStartMs = java.util.concurrent.atomic.AtomicLong(0L)

    private companion object {
        // No 429/503 for this long -> the streak is considered ended and resets.
        private const val RATE_LIMIT_STREAK_QUIET_RESET_MS = 10_000L
        // A continuous throttle with zero successful progress longer than this is treated
        // as a dead stream: surface one clean fatal so mid-play source failover can act.
        // NOTE: this is the single tunable. It is longer than today's ~58s count-based
        // crash, so on a genuinely dead stream failover is later than today; the win is
        // surviving every recoverable storm shorter than this. Build 2 (buffer-aware)
        // replaces this fixed ceiling with the actual buffer-ahead trigger.
        private const val RATE_LIMIT_STREAK_CEILING_MS = 120_000L
    }

    override fun getFallbackSelectionFor(
        fallbackOptions: LoadErrorHandlingPolicy.FallbackOptions,
        loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo
    ): LoadErrorHandlingPolicy.FallbackSelection? {
        val responseCode = loadErrorInfo.exception
            .findCause<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>()
            ?.responseCode
        if (
            shouldPreferAlternativeHlsTrack(
                responseCode = responseCode,
                dataType = loadErrorInfo.mediaLoadData.dataType,
                alternativeTrackAvailable = fallbackOptions.isFallbackAvailable(
                    LoadErrorHandlingPolicy.FALLBACK_TYPE_TRACK
                )
            )
        ) {
            // A media-segment 404 belongs to the selected rendition. Exclude that
            // rendition first so HLS can continue with another compatible track.
            return LoadErrorHandlingPolicy.FallbackSelection(
                LoadErrorHandlingPolicy.FALLBACK_TYPE_TRACK,
                DefaultLoadErrorHandlingPolicy.DEFAULT_TRACK_EXCLUSION_MS
            )
        }
        return super.getFallbackSelectionFor(fallbackOptions, loadErrorInfo)
    }

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val httpException = loadErrorInfo.exception.findCause<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>()
        if (httpException != null) {
            val code = httpException.responseCode
            if (code == 400 || code == 401 || code == 403 || code == 404 || code == 410) {
                return androidx.media3.common.C.TIME_UNSET
            }
        }
        // fatal-429 (27 Aug spec, Build 1): 429/503 is transient server rate-limiting, not
        // a dead stream. ParallelRangeDataSource already backs off (Retry-After / AIMD depth)
        // and only surfaces to media3 once its own budget is spent; historically that then
        // crossed the loader retry count and became a fatal Source error (crash observed
        // 27 Aug, StremThru path, inflight=0 429s, x2). Instead: track the streak and keep
        // retrying (count uncapped in getMinimumLoadableRetryCount) until the throttle has
        // made zero progress for the ceiling duration, then give up cleanly. The
        // retry delay is set explicitly (NOT via super) because the base policy may treat
        // some response codes (e.g. 503) as permanent -> TIME_UNSET -> instant fatal.
        if (httpException != null &&
            (httpException.responseCode == 429 || httpException.responseCode == 503)) {
            val now = android.os.SystemClock.elapsedRealtime()
            val last = rateLimitLastHitMs.getAndSet(now)
            if (last == 0L || now - last > RATE_LIMIT_STREAK_QUIET_RESET_MS) {
                rateLimitStreakStartMs.set(now)
            }
            val streakMs = now - rateLimitStreakStartMs.get()
            if (streakMs > RATE_LIMIT_STREAK_CEILING_MS) {
                Log.w(
                    "NuvioLoadErrPolicy",
                    "Rate-limit streak ${streakMs}ms > ceiling; giving up (clean fatal for failover)"
                )
                rateLimitLastHitMs.set(0L)
                return androidx.media3.common.C.TIME_UNSET
            }
            return minOf(1000L * loadErrorInfo.errorCount, 5_000L)
        }

        // NuvioTV fork: a malformed-container error (a Usenet zero-fill hole the
        // extractor resync could not clear) will not un-malform on retry - the same
        // bytes fail identically. Surface it immediately (no backoff retries) so the
        // player's mid-play failover can switch sources, matching the permanent-HTTP
        // handling above. Recoverable holes never reach here (the extractor swallows
        // them), so only genuinely unrecoverable corruption is short-circuited.
        val malformed = loadErrorInfo.exception.findCause<androidx.media3.common.ParserException>() != null ||
            loadErrorInfo.exception.findCause<IllegalStateException>()?.message?.contains("varint") == true
        if (malformed) {
            return androidx.media3.common.C.TIME_UNSET
        }
        val timeout = loadErrorInfo.exception.findCause<SocketTimeoutException>() != null
        return if (timeout) {
            when (loadErrorInfo.errorCount) {
                1 -> 750L
                2 -> 1500L
                else -> 3000L
            }
        } else super.getRetryDelayMsFor(loadErrorInfo)
    }

    override fun getMinimumLoadableRetryCount(dataType: Int): Int {
        val last = rateLimitLastHitMs.get()
        if (last != 0L) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - last <= RATE_LIMIT_STREAK_QUIET_RESET_MS &&
                now - rateLimitStreakStartMs.get() <= RATE_LIMIT_STREAK_CEILING_MS
            ) {
                // Active throttle streak within budget: never fatal on the retry count.
                // getRetryDelayMsFor owns the give-up (ceiling -> C.TIME_UNSET).
                return Int.MAX_VALUE
            }
        }
        return super.getMinimumLoadableRetryCount(dataType)
    }
}

internal fun shouldPreferAlternativeHlsTrack(
    responseCode: Int?,
    dataType: Int,
    alternativeTrackAvailable: Boolean
): Boolean =
    responseCode == 404 &&
        dataType == C.DATA_TYPE_MEDIA &&
        alternativeTrackAvailable
