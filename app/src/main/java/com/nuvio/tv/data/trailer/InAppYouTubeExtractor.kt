package com.nuvio.tv.data.trailer

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.gson.Gson
import com.nuvio.tv.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resumeWithException

private const val TAG = "InAppYouTubeExtractor"
private const val EXTRACTOR_TIMEOUT_MS = 30_000L
private const val PROBES_AT_ONCE = 2
private const val PROBE_ALTERNATE_HEAD_START_MS = 400L
private const val DEFAULT_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"
private const val FALLBACK_API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"

private val VIDEO_ID_REGEX = Regex("^[a-zA-Z0-9_-]{11}$")
private val API_KEY_REGEX = Regex("\"INNERTUBE_API_KEY\":\"([^\"]+)\"")
private val VISITOR_DATA_REGEX = Regex("\"VISITOR_DATA\":\"([^\"]+)\"")
private val QUALITY_LABEL_REGEX = Regex("(\\d{2,4})p")
private val BOT_CHECK_REGEX = Regex("\\bbot\\b", RegexOption.IGNORE_CASE)
private const val HTTP_TOO_MANY_REQUESTS = 429

/** YouTube is refusing requests from this device; see [YouTubeRateLimitGate]. */
private class RateLimitedException(message: String) : Exception(message)

private data class YouTubeClient(
    val key: String,
    val id: String,
    val version: String,
    val userAgent: String,
    val context: Map<String, Any>,
    val priority: Int
)

private data class WatchConfig(
    val apiKey: String?,
    val visitorData: String?
)

internal data class StreamCandidate(
    val client: String,
    val priority: Int,
    val url: String,
    val score: Double,
    val hasN: Boolean,
    val itag: String,
    val height: Int,
    val fps: Int,
    val ext: String,
    // Only meaningful for audio candidates: false means this format is an
    // alternate-language dub track, not the video's original/default audio.
    // Always true for video/progressive candidates, so it never affects them.
    val isDefaultAudioTrack: Boolean = true,
    val width: Int = 0,
    val codec: TrailerVideoCodec? = null,
    // Adaptive video only: the format's codecs string (e.g. "av01.0.12M.10"), null when unknown.
    val codecs: String? = null,
    val isHdr: Boolean = false
)

internal enum class PlaybackSourceKind {
    ADAPTIVE,
    HLS_MANIFEST,
    PROGRESSIVE
}

private data class ExtractionAttempt(
    val source: TrailerPlaybackSource?,
    val unplayable: YouTubeUnplayableReason? = null,
    // Every client asked to sign in, which a fresh visitor id from the watch page can fix.
    val staleConfig: Boolean = false
)

private data class ManifestBestVariant(
    val url: String,
    val width: Int,
    val height: Int,
    val bandwidth: Long
)

private data class ManifestCandidate(
    val client: String,
    val priority: Int,
    val manifestUrl: String,
    val selectedVariantUrl: String,
    val height: Int,
    val bandwidth: Long
)

private val DEFAULT_HEADERS = mapOf(
    "accept-language" to "en-US,en;q=0.9",
    "user-agent" to DEFAULT_USER_AGENT
)

/**
 * The app identities YouTube is asked as, taken from yt-dlp's INNERTUBE_CLIENTS table. Check them
 * against that table at each release: an outdated identity is refused. The first one needs no
 * extra token and is asked alone; the others are asked only when it has nothing playable.
 */
private val CLIENTS = listOf(
    YouTubeClient(
        key = "visionos",
        id = "101",
        version = "1.02",
        userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 " +
            "(KHTML, like Gecko) Version/26.0 Safari/605.1.15",
        context = mapOf(
            "clientName" to "VISIONOS",
            "clientVersion" to "1.02",
            "deviceMake" to "Apple",
            "deviceModel" to "RealityDevice17,1",
            "osName" to "visionOS",
            "osVersion" to "26.5.23O471",
            "hl" to "en",
            "gl" to "US"
        ),
        priority = 0
    ),
    YouTubeClient(
        key = "android",
        id = "3",
        version = "21.26.364",
        userAgent = "com.google.android.youtube/21.26.364 (Linux; U; Android 11) gzip",
        context = mapOf(
            "clientName" to "ANDROID",
            "clientVersion" to "21.26.364",
            "osName" to "Android",
            "osVersion" to "11",
            "platform" to "MOBILE",
            "androidSdkVersion" to 30,
            "hl" to "en",
            "gl" to "US"
        ),
        priority = 1
    ),
    YouTubeClient(
        key = "ios",
        id = "5",
        version = "21.26.4",
        userAgent = "com.google.ios.youtube/21.26.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
        context = mapOf(
            "clientName" to "IOS",
            "clientVersion" to "21.26.4",
            "deviceMake" to "Apple",
            "deviceModel" to "iPhone16,2",
            "osName" to "iPhone",
            "osVersion" to "18.3.2.22D82",
            "platform" to "MOBILE",
            "hl" to "en",
            "gl" to "US"
        ),
        priority = 2
    )
)

@Singleton
class InAppYouTubeExtractor internal constructor(
    videoLimits: () -> TrailerVideoLimits = { TrailerVideoLimits() }
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this({ deviceTrailerVideoLimits(context) })

    private val gson = Gson()
    private val youTubeTlsTest by lazy { com.nuvio.tv.core.runtime.PluginRuntimeHooks.youTubeTlsTest() }
    private val trailerVideoLimits by lazy(videoLimits)

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .dns(com.nuvio.tv.core.network.IPv4FirstDns())
            .socketFactory(com.nuvio.tv.core.network.NoDelaySocketFactory)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .apply { youTubeTlsTest?.let { (factory, trust) -> sslSocketFactory(factory, trust) } }
            .build()
    }

    // --- Cached watch config (api key + visitor data) ---
    private data class CachedConfig(
        val apiKey: String,
        val visitorData: String?,
        val fetchedAt: Long = System.currentTimeMillis(),
        val ttlMs: Long = CONFIG_TTL_MS
    )

    private val cachedConfig = AtomicReference<CachedConfig?>(null)
    private val configMutex = Mutex()
    private val unplayableReasons = ConcurrentHashMap<String, YouTubeUnplayableReason>()
    private val rateLimitGate = YouTubeRateLimitGate()

    companion object {
        /** How long cached visitor_data stays valid before a proactive refresh. */
        private const val CONFIG_TTL_MS = 3 * 60 * 60 * 1000L // 3 hours

        /** How long the fallback key is used before the watch page is tried again. */
        private const val FALLBACK_CONFIG_TTL_MS = 10 * 60 * 1000L // 10 minutes
    }

    /**
     * True while YouTube is refusing requests. Extraction returns null without touching the
     * network until the pause ends, so callers should not take a null as "no trailer" then.
     */
    fun isRateLimited(): Boolean = rateLimitGate.isBlocked()

    /**
     * Returns cached watch config, fetching from watch page only if:
     *  - No cached config exists yet (first call)
     *  - Cache is older than CONFIG_TTL_MS
     *  - [forceRefresh] is true (e.g. after LOGIN_REQUIRED)
     */
    private suspend fun ensureWatchConfig(forceRefresh: Boolean = false): CachedConfig {
        // Fast path: return valid cache without locking
        if (!forceRefresh) {
            val current = cachedConfig.get()
            if (current != null && !isConfigStale(current)) {
                return current
            }
        }

        // Slow path: fetch new config under mutex (only one fetch at a time)
        return configMutex.withLock {
            // Double-check after acquiring lock
            if (!forceRefresh) {
                val current = cachedConfig.get()
                if (current != null && !isConfigStale(current)) {
                    return@withLock current
                }
            }

            Log.d(TAG, "Fetching watch page for visitor_data (forceRefresh=$forceRefresh)")
            val watchUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ&hl=en"
            val watchResponse = performRequest(
                url = watchUrl,
                method = "GET",
                headers = DEFAULT_HEADERS
            )
            if (watchResponse.status == HTTP_TOO_MANY_REQUESTS) {
                throw RateLimitedException("watch page returned 429")
            }
            if (!watchResponse.ok) {
                // If we have a stale config, prefer it over failing
                val stale = cachedConfig.get()
                if (stale != null) {
                    Log.w(TAG, "Watch page failed (${watchResponse.status}), using stale config")
                    return@withLock stale
                }
                // The watch page can be replaced by a consent or bot-check page while the player
                // API still answers, so continue with the fallback key. It is cached only briefly,
                // so the watch page is tried again soon, but not on every extraction.
                Log.w(TAG, "Watch page failed (${watchResponse.status}), using the fallback key")
                val fallback = CachedConfig(
                    apiKey = FALLBACK_API_KEY,
                    visitorData = null,
                    ttlMs = FALLBACK_CONFIG_TTL_MS
                )
                cachedConfig.set(fallback)
                return@withLock fallback
            }

            val parsed = getWatchConfig(watchResponse.body)
            val apiKey = parsed.apiKey ?: FALLBACK_API_KEY
            val newConfig = CachedConfig(
                apiKey = apiKey,
                visitorData = parsed.visitorData
            )
            cachedConfig.set(newConfig)
            Log.d(TAG, "Watch config cached (visitor=${!parsed.visitorData.isNullOrBlank()})")
            newConfig
        }
    }

    private fun isConfigStale(config: CachedConfig): Boolean {
        return System.currentTimeMillis() - config.fetchedAt > config.ttlMs
    }

    /** Invalidate cached config so next extraction re-fetches watch page. */
    fun invalidateConfig() {
        cachedConfig.set(null)
        Log.d(TAG, "Watch config invalidated")
    }

    suspend fun extractPlaybackSource(youtubeUrl: String): TrailerPlaybackSource? =
        extract(youtubeUrl, singleUrl = false)

    /** Why the last extraction of this video found nothing, when YouTube itself refused it. */
    fun unplayableReason(youtubeUrl: String): YouTubeUnplayableReason? =
        extractVideoId(youtubeUrl)?.let(unplayableReasons::get)

    /**
     * Returns one URL that carries both video and audio, for players that take a single URL
     * (the main player and external players). Prefers the HLS master playlist, which covers
     * every quality and lists the audio as its own rendition, then a progressive file.
     */
    suspend fun extractSingleUrl(youtubeUrl: String): String? =
        extract(youtubeUrl, singleUrl = true)?.videoUrl

    private suspend fun extract(
        youtubeUrl: String,
        singleUrl: Boolean
    ): TrailerPlaybackSource? = withContext(Dispatchers.IO) {
        if (youtubeUrl.isBlank()) return@withContext null
        if (rateLimitGate.isBlocked()) {
            Log.w(
                TAG,
                "YouTube is rate limiting, skipping extraction for another " +
                    "${rateLimitGate.remainingMs() / 1000}s: ${summarizeUrl(youtubeUrl)}"
            )
            return@withContext null
        }

        Log.d(TAG, "Starting Kotlin extraction for ${summarizeUrl(youtubeUrl)}")
        var attempt: ExtractionAttempt? = null
        try {
            attempt = withTimeout(EXTRACTOR_TIMEOUT_MS) {
                extractPlaybackSourceInternal(youtubeUrl, forceRefreshConfig = false, singleUrl = singleUrl, clients = CLIENTS.take(1))
            }
            // Only a stale visitor id is fixed by a fresh watch page. Other failures would fail the
            // same way again, and every extra request counts toward YouTube's limit.
            if (attempt?.source == null && attempt?.staleConfig == true) {
                Log.d(TAG, "Every client asked to sign in, retrying with a fresh watch config")
                attempt = withTimeout(EXTRACTOR_TIMEOUT_MS) {
                    extractPlaybackSourceInternal(youtubeUrl, forceRefreshConfig = true, singleUrl = singleUrl, clients = CLIENTS.take(1))
                }
            }
            // A sign-in demand is about this device or address, so the other identities get the same answer.
            if (attempt?.source == null && attempt?.unplayable != YouTubeUnplayableReason.SIGN_IN_REQUIRED) {
                val others = withTimeout(EXTRACTOR_TIMEOUT_MS) {
                    extractPlaybackSourceInternal(youtubeUrl, forceRefreshConfig = false, singleUrl = singleUrl, clients = CLIENTS.drop(1))
                }
                if (others.source != null || attempt?.unplayable == null) attempt = others
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            // A timeout is a failed attempt, not a cancellation of the caller.
            Log.w(TAG, "Kotlin extractor timed out for $youtubeUrl")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: RateLimitedException) {
            val cooldownMs = rateLimitGate.onRateLimited()
            Log.w(TAG, "YouTube is rate limiting (${e.message}), pausing extraction for ${cooldownMs / 1000}s")
        } catch (error: Exception) {
            Log.w(TAG, "Kotlin extractor failed for $youtubeUrl: ${error.message}")
        }

        val source = attempt?.source
        val unplayable = attempt?.unplayable
        extractVideoId(youtubeUrl)?.let { videoId ->
            if (source == null && unplayable != null) {
                unplayableReasons[videoId] = unplayable
            } else {
                unplayableReasons.remove(videoId)
            }
        }

        if (source == null) {
            Log.w(
                TAG,
                "Kotlin extraction returned no playable source for ${summarizeUrl(youtubeUrl)}" +
                    (unplayable?.let { " ($it)" } ?: "")
            )
        } else {
            rateLimitGate.onSuccess()
            Log.d(
                TAG,
                "Kotlin extraction success for ${summarizeUrl(youtubeUrl)} " +
                    "(video=${summarizeUrl(source.videoUrl)}, audioPresent=${!source.audioUrl.isNullOrBlank()})"
            )
        }

        source
    }

    private suspend fun extractPlaybackSourceInternal(
        youtubeUrl: String,
        forceRefreshConfig: Boolean,
        singleUrl: Boolean,
        clients: List<YouTubeClient>
    ): ExtractionAttempt {
        val videoId = extractVideoId(youtubeUrl) ?: return ExtractionAttempt(null)

        // Use cached config instead of fetching watch page every time
        val config = ensureWatchConfig(forceRefresh = forceRefreshConfig)
        Log.d(TAG, "Using config: apiKey=${config.apiKey.take(10)}... visitor=${!config.visitorData.isNullOrBlank()}")

        val progressive = mutableListOf<StreamCandidate>()
        val adaptiveVideo = mutableListOf<StreamCandidate>()
        val adaptiveAudio = mutableListOf<StreamCandidate>()
        val manifestUrls = mutableListOf<Triple<String, Int, String>>()
        var loginRequiredCount = 0
        var botCheckCount = 0
        var playableCount = 0
        val refusals = mutableListOf<YouTubeUnplayableReason>()

        for (client in clients) {
            kotlinx.coroutines.yield()
            try {
                val playerResponse = fetchPlayerResponse(
                    apiKey = config.apiKey,
                    videoId = videoId,
                    client = client,
                    visitorData = config.visitorData,
                    cookieHeader = null
                )

                // Check for LOGIN_REQUIRED which means visitor_data is stale
                val playabilityStatus = playerResponse.mapValue("playabilityStatus")
                val status = playabilityStatus?.stringValue("status")
                youTubeUnplayableReasonOf(status, playabilityStatus?.stringValue("reason"))?.let { refusals += it }
                if (status == "LOGIN_REQUIRED") {
                    val reason = playabilityStatus?.stringValue("reason").orEmpty()
                    if (BOT_CHECK_REGEX.containsMatchIn(reason)) {
                        // "Sign in to confirm you're not a bot": YouTube is throttling this device.
                        botCheckCount++
                        Log.w(TAG, "Client ${client.key}: bot check ($reason)")
                    } else {
                        loginRequiredCount++
                        Log.w(
                            TAG,
                            "Client ${client.key}: LOGIN_REQUIRED (${reason.take(120)}) " +
                                "visitor=${!config.visitorData.isNullOrBlank()} freshConfig=$forceRefreshConfig"
                        )
                    }
                    continue
                }
                if (status != null && status != "OK") {
                    continue
                }
                playableCount++

                val streamingData = playerResponse.mapValue("streamingData") ?: continue
                val hlsManifestUrl = streamingData.stringValue("hlsManifestUrl")
                if (!hlsManifestUrl.isNullOrBlank()) {
                    manifestUrls += Triple(client.key, client.priority, hlsManifestUrl)
                }

                for (format in streamingData.listMapValue("formats")) {
                    val url = format.stringValue("url") ?: continue
                    val mimeType = format.stringValue("mimeType").orEmpty()
                    if (!mimeType.contains("video/") && mimeType.isNotBlank()) continue

                    val height = (format.numberValue("height")
                        ?: parseQualityLabel(format.stringValue("qualityLabel"))?.toDouble()
                        ?: 0.0).toInt()
                    val fps = (format.numberValue("fps") ?: 0.0).toInt()
                    val bitrate = format.numberValue("bitrate")
                        ?: format.numberValue("averageBitrate")
                        ?: 0.0

                    progressive += StreamCandidate(
                        client = client.key,
                        priority = client.priority,
                        url = url,
                        score = videoScore(height, fps, bitrate),
                        hasN = hasNParam(url),
                        itag = format.stringValue("itag").orEmpty(),
                        height = height,
                        fps = fps,
                        ext = if (mimeType.contains("webm")) "webm" else "mp4"
                    )
                }

                for (format in streamingData.listMapValue("adaptiveFormats")) {
                    val url = format.stringValue("url") ?: continue
                    val mimeType = format.stringValue("mimeType").orEmpty()
                    val hasVideo = mimeType.contains("video/")
                    val hasAudio = mimeType.contains("audio/") || mimeType.startsWith("audio/")

                    if (hasVideo) {
                        val height = (format.numberValue("height")
                            ?: parseQualityLabel(format.stringValue("qualityLabel"))?.toDouble()
                            ?: 0.0).toInt()
                        val fps = (format.numberValue("fps") ?: 0.0).toInt()
                        val bitrate = format.numberValue("bitrate")
                            ?: format.numberValue("averageBitrate")
                            ?: 0.0

                        adaptiveVideo += StreamCandidate(
                            client = client.key,
                            priority = client.priority,
                            url = url,
                            score = videoScore(height, fps, bitrate),
                            hasN = hasNParam(url),
                            itag = format.stringValue("itag").orEmpty(),
                            height = height,
                            fps = fps,
                            ext = if (mimeType.contains("webm")) "webm" else "mp4",
                            width = (format.numberValue("width") ?: 0.0).toInt(),
                            codec = trailerVideoCodecOf(mimeType),
                            codecs = videoCodecsParam(mimeType),
                            isHdr = isHdrTrailerFormat(
                                mimeType = mimeType,
                                qualityLabel = format.stringValue("qualityLabel"),
                                transferCharacteristics = format.mapValue("colorInfo")
                                    ?.stringValue("transferCharacteristics")
                            )
                        )
                    } else if (hasAudio) {
                        val bitrate = format.numberValue("bitrate")
                            ?: format.numberValue("averageBitrate")
                            ?: 0.0
                        val asr = format.numberValue("audioSampleRate") ?: 0.0
                        // Multi-language uploads (common for major-studio trailers)
                        // expose each dub as a separate adaptiveFormats entry with an
                        // audioTrack.audioIsDefault flag. Formats with no audioTrack
                        // are the only audio for that video, so treat them as default.
                        val isDefaultAudioTrack = format.mapValue("audioTrack")
                            ?.booleanValue("audioIsDefault") ?: true

                        adaptiveAudio += StreamCandidate(
                            client = client.key,
                            priority = client.priority,
                            url = url,
                            score = audioScore(bitrate, asr),
                            hasN = hasNParam(url),
                            itag = format.stringValue("itag").orEmpty(),
                            height = 0,
                            fps = 0,
                            ext = if (mimeType.contains("webm")) "webm" else "m4a",
                            isDefaultAudioTrack = isDefaultAudioTrack
                        )
                    }
                }
            } catch (e: RateLimitedException) {
                throw e
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (error: Exception) {
                if (BuildConfig.DEBUG) {
                    Log.w(TAG, "Client ${client.key} failed: ${error.message}")
                }
            }

        }

        // If all clients returned LOGIN_REQUIRED, invalidate config for next attempt
        if (loginRequiredCount == clients.size) {
            Log.w(TAG, "All ${clients.size} clients returned LOGIN_REQUIRED, invalidating config")
            invalidateConfig()
            return ExtractionAttempt(null, combineYouTubeUnplayableReasons(refusals), staleConfig = true)
        }

        if (manifestUrls.isEmpty() && progressive.isEmpty() && adaptiveVideo.isEmpty() && adaptiveAudio.isEmpty()) {
            if (botCheckCount > 0) {
                throw RateLimitedException("$botCheckCount of ${clients.size} clients hit the bot check")
            }
            return ExtractionAttempt(
                null,
                combineYouTubeUnplayableReasons(refusals).takeIf { playableCount == 0 }
            )
        }

        val bestProgressive = sortCandidates(progressive).firstOrNull()

        var videoOnly: TrailerPlaybackSource? = null
        for (kind in sourcePreference(singleUrl)) {
            kotlinx.coroutines.yield()
            val source = when (kind) {
                // Probe every client's top candidates in parallel and take the best reachable
                // pair. Token-free clients (visionos) win here even when gated clients score
                // higher, because gated URLs fail the reachability probe.
                PlaybackSourceKind.ADAPTIVE -> {
                    val chosenVideo = probeBestPerClient(
                        trailerVideoCandidates(adaptiveVideo, trailerVideoLimits)
                    )
                    val chosenAudio = if (chosenVideo != null) {
                        probeBestPerClient(adaptiveAudio, preferClient = chosenVideo.client)
                    } else null
                    chosenVideo?.let {
                        Log.d(TAG, "Trailer adaptive: ${it.client}/${it.height}p/${it.codec} " +
                            "audio=${chosenAudio?.client ?: "none"}")
                        val adaptive = TrailerPlaybackSource(videoUrl = it.url, audioUrl = chosenAudio?.url)
                        if (chosenAudio == null) videoOnly = adaptive
                        adaptive.takeIf { chosenAudio != null }
                    }
                }
                // HLS manifest (1080p, always works for COPPA/kids content). The manifests are
                // only downloaded here, once the adaptive streams have failed.
                PlaybackSourceKind.HLS_MANIFEST ->
                    pickBestManifest(manifestUrls)
                        ?.let { TrailerPlaybackSource(videoUrl = it.manifestUrl, audioUrl = null) }
                // Progressive (combined video+audio, usually low quality)
                PlaybackSourceKind.PROGRESSIVE ->
                    bestProgressive?.url?.let { resolveReachableUrl(it) }
                        ?.let { TrailerPlaybackSource(videoUrl = it, audioUrl = null) }
            }
            if (source != null) return ExtractionAttempt(source)
        }
        videoOnly?.let { return ExtractionAttempt(it) }

        return ExtractionAttempt(null)
    }

    private suspend fun pickBestManifest(manifestUrls: List<Triple<String, Int, String>>): ManifestCandidate? {
        var bestManifest: ManifestCandidate? = null
        for ((clientKey, priority, manifestUrl) in manifestUrls) {
            try {
                val variant = parseHlsManifest(manifestUrl) ?: continue
                val candidate = ManifestCandidate(
                    client = clientKey,
                    priority = priority,
                    manifestUrl = manifestUrl,
                    selectedVariantUrl = variant.url,
                    height = variant.height,
                    bandwidth = variant.bandwidth
                )
                if (
                    bestManifest == null ||
                    candidate.height > bestManifest.height ||
                    (candidate.height == bestManifest.height && candidate.bandwidth > bestManifest.bandwidth)
                ) {
                    bestManifest = candidate
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (error: Exception) {
                if (BuildConfig.DEBUG) {
                    Log.w(TAG, "Manifest parse failed: ${error.message}")
                }
            }
        }
        return bestManifest
    }

    private fun extractVideoId(input: String): String? {
        val trimmed = input.trim()
        if (VIDEO_ID_REGEX.matches(trimmed)) return trimmed

        val normalized = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }

        return runCatching {
            val uri = Uri.parse(normalized)
            val host = uri.host?.lowercase().orEmpty()
            if (host.endsWith("youtu.be")) {
                val id = uri.pathSegments.firstOrNull()
                if (!id.isNullOrBlank() && VIDEO_ID_REGEX.matches(id)) {
                    return id
                }
            }

            val queryId = uri.getQueryParameter("v")
            if (!queryId.isNullOrBlank() && VIDEO_ID_REGEX.matches(queryId)) {
                return queryId
            }

            val segments = uri.pathSegments
            if (segments.size >= 2) {
                val first = segments[0]
                val second = segments[1]
                if ((first == "embed" || first == "shorts" || first == "live") && VIDEO_ID_REGEX.matches(second)) {
                    return second
                }
            }

            null
        }.getOrNull()
    }

    private fun getWatchConfig(html: String): WatchConfig {
        val apiKey = API_KEY_REGEX.find(html)?.groupValues?.getOrNull(1)
        val visitorData = VISITOR_DATA_REGEX.find(html)?.groupValues?.getOrNull(1)
        return WatchConfig(apiKey = apiKey, visitorData = visitorData)
    }

    private suspend fun fetchPlayerResponse(
        apiKey: String,
        videoId: String,
        client: YouTubeClient,
        visitorData: String?,
        cookieHeader: String?
    ): Map<*, *> {
        val endpoint = "https://www.youtube.com/youtubei/v1/player?key=${Uri.encode(apiKey)}"

        val headers = buildMap {
            putAll(DEFAULT_HEADERS)
            put("content-type", "application/json")
            put("origin", "https://www.youtube.com")
            put("x-youtube-client-name", client.id)
            put("x-youtube-client-version", client.version)
            put("user-agent", client.userAgent)
            if (!visitorData.isNullOrBlank()) put("x-goog-visitor-id", visitorData)
            if (!cookieHeader.isNullOrBlank()) put("cookie", cookieHeader)
        }

        val payload = buildMap<String, Any> {
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
            put("context", mapOf("client" to client.context))
            put("playbackContext", mapOf(
                "contentPlaybackContext" to mapOf("html5Preference" to "HTML5_PREF_WANTS")
            ))
        }

        val response = performRequest(
            url = endpoint,
            method = "POST",
            headers = headers,
            body = gson.toJson(payload)
        )
        if (response.status == HTTP_TOO_MANY_REQUESTS) {
            throw RateLimitedException("player API ${client.key} returned 429")
        }
        if (!response.ok) {
            throw IllegalStateException("player API ${client.key} failed (${response.status})")
        }

        val parsed = gson.fromJson(response.body, Map::class.java)
        return parsed ?: emptyMap<String, Any>()
    }

    private suspend fun parseHlsManifest(manifestUrl: String): ManifestBestVariant? {
        val response = performRequest(
            url = manifestUrl,
            method = "GET",
            headers = DEFAULT_HEADERS
        )
        if (!response.ok) {
            throw IllegalStateException("Failed to fetch HLS manifest (${response.status})")
        }

        val lines = response.body
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()

        var bestVariant: ManifestBestVariant? = null

        for (i in lines.indices) {
            val line = lines[i]
            if (!line.startsWith("#EXT-X-STREAM-INF:")) continue

            val attrs = parseHlsAttributeList(line)
            val nextLine = lines.getOrNull(i + 1) ?: continue
            if (nextLine.startsWith("#")) continue

            val resolution = attrs["RESOLUTION"].orEmpty()
            val (width, height) = parseResolution(resolution)
            val bandwidth = attrs["BANDWIDTH"]?.toLongOrNull() ?: 0L

            val candidate = ManifestBestVariant(
                url = absolutizeUrl(manifestUrl, nextLine),
                width = width,
                height = height,
                bandwidth = bandwidth
            )

            if (
                bestVariant == null ||
                candidate.height > bestVariant.height ||
                (candidate.height == bestVariant.height && candidate.bandwidth > bestVariant.bandwidth) ||
                (
                    candidate.height == bestVariant.height &&
                        candidate.bandwidth == bestVariant.bandwidth &&
                        candidate.width > bestVariant.width
                    )
            ) {
                bestVariant = candidate
            }
        }

        return bestVariant
    }

    private fun parseHlsAttributeList(line: String): Map<String, String> {
        val index = line.indexOf(':')
        if (index == -1) return emptyMap()

        val raw = line.substring(index + 1)
        val out = LinkedHashMap<String, String>()
        val key = StringBuilder()
        val value = StringBuilder()
        var inKey = true
        var inQuote = false

        for (ch in raw) {
            if (inKey) {
                if (ch == '=') {
                    inKey = false
                } else {
                    key.append(ch)
                }
                continue
            }

            if (ch == '"') {
                inQuote = !inQuote
                continue
            }

            if (ch == ',' && !inQuote) {
                val k = key.toString().trim()
                if (k.isNotEmpty()) {
                    out[k] = value.toString().trim()
                }
                key.clear()
                value.clear()
                inKey = true
                continue
            }

            value.append(ch)
        }

        val lastKey = key.toString().trim()
        if (lastKey.isNotEmpty()) {
            out[lastKey] = value.toString().trim()
        }

        return out
    }

    private fun parseResolution(raw: String): Pair<Int, Int> {
        val parts = raw.split('x')
        if (parts.size != 2) return 0 to 0
        val width = parts[0].toIntOrNull() ?: 0
        val height = parts[1].toIntOrNull() ?: 0
        return width to height
    }

    private fun parseQualityLabel(label: String?): Int? {
        if (label.isNullOrBlank()) return null
        val match = QUALITY_LABEL_REGEX.find(label) ?: return null
        return match.groupValues.getOrNull(1)?.toIntOrNull()
    }

    private fun hasNParam(url: String): Boolean {
        return runCatching {
            !Uri.parse(url).getQueryParameter("n").isNullOrBlank()
        }.getOrDefault(false)
    }

    private fun videoScore(height: Int, fps: Int, bitrate: Double): Double {
        return height * 1_000_000_000.0 + fps * 1_000_000.0 + bitrate
    }

    private fun audioScore(bitrate: Double, audioSampleRate: Double): Double {
        return bitrate * 1_000_000.0 + audioSampleRate
    }

    /**
     * The order in which source kinds are tried. A single-URL source can't use the adaptive
     * formats, because their video and audio are separate files.
     */
    internal fun sourcePreference(singleUrl: Boolean): List<PlaybackSourceKind> =
        if (singleUrl) {
            listOf(PlaybackSourceKind.HLS_MANIFEST, PlaybackSourceKind.PROGRESSIVE)
        } else {
            listOf(PlaybackSourceKind.ADAPTIVE, PlaybackSourceKind.HLS_MANIFEST, PlaybackSourceKind.PROGRESSIVE)
        }

    internal fun sortCandidates(items: List<StreamCandidate>): List<StreamCandidate> {
        return items.sortedWith(
            compareBy<StreamCandidate> { if (it.isDefaultAudioTrack) 0 else 1 }
                .thenByDescending { it.height }
                .thenBy { trailerVideoCodecRank(it.codec) }
                .thenByDescending { it.score }
                .thenBy { if (it.hasN) 1 else 0 }
                .thenBy { containerPreference(it.ext) }
                .thenBy { it.priority }
        )
    }

    private fun containerPreference(ext: String): Int {
        return when (ext.lowercase()) {
            "mp4", "m4a" -> 0
            "webm" -> 1
            else -> 2
        }
    }

    private suspend fun probeBestPerClient(
        candidates: List<StreamCandidate>,
        preferClient: String? = null,
        perClientDepth: Int = 2
    ): StreamCandidate? {
        val toProbe = probeOrder(candidates, preferClient, perClientDepth)
        return firstAcceptedInOrder(toProbe, PROBES_AT_ONCE, 2_500L) { cand ->
            resolveReachableUrl(cand.url)?.let { cand.copy(url = it) }
        }
    }

    /** Each client's best candidates, the preferred client's first, then by quality. */
    internal fun probeOrder(
        candidates: List<StreamCandidate>,
        preferClient: String?,
        perClientDepth: Int
    ): List<StreamCandidate> {
        val perClient = candidates.groupBy { it.client }
            .flatMap { (_, list) -> sortCandidates(list).take(perClientDepth) }
        if (preferClient == null) return sortCandidates(perClient)
        val (preferred, others) = perClient.partition { it.client == preferClient }
        return sortCandidates(preferred) + sortCandidates(others)
    }

    /**
     * The googlevideo URL itself when it answers, otherwise the same file on another server
     * named in its `mn` parameter. Null when none answers (all return 403/timeout).
     */
    private suspend fun resolveReachableUrl(url: String): String? {
        if (!url.contains("googlevideo.com")) return url
        val uri = Uri.parse(url)
        val mnParam = uri.getQueryParameter("mn") ?: return url
        val servers = mnParam.split(",").map { it.trim() }.filter { it.isNotBlank() }
        if (servers.size < 2) return url

        val alternates = mutableListOf<String>()
        for (server in servers) {
            val mviIndex = servers.indexOf(server)
            val altHost = uri.host?.replaceFirst(
                Regex("^rr\\d+---"),
                "rr${mviIndex + 1}---"
            )?.replaceFirst(
                Regex("sn-[a-z0-9]+-[a-z0-9]+"),
                server
            ) ?: continue
            if (altHost == uri.host) continue
            alternates += url.replace(uri.host!!, altHost)
        }

        return withTimeoutOrNull(2_000L) {
            coroutineScope {
                val result = CompletableDeferred<String?>()
                val own = launch { if (isUrlReachable(url)) result.complete(url) }
                launch {
                    // The other servers are asked only when the listed one fails or is slow.
                    withTimeoutOrNull(PROBE_ALTERNATE_HEAD_START_MS) { own.join() }
                    if (!result.isCompleted) {
                        alternates.map { alternate ->
                            launch { if (isUrlReachable(alternate)) result.complete(alternate) }
                        }.joinAll()
                    }
                    own.join()
                    result.complete(null)
                }
                result.await().also { coroutineContext.job.cancelChildren() }
            }
        }
    }

    private val probeClient by lazy {
        OkHttpClient.Builder()
            .dns(com.nuvio.tv.core.network.IPv4FirstDns())
            .socketFactory(com.nuvio.tv.core.network.NoDelaySocketFactory)
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .apply { youTubeTlsTest?.let { (factory, trust) -> sslSocketFactory(factory, trust) } }
            .build()
    }

    private suspend fun isUrlReachable(url: String): Boolean {
        val request = runCatching {
            Request.Builder()
                .url(url)
                .get()
                .header("Range", "bytes=0-0")
                .headers(buildHeaders(DEFAULT_HEADERS))
                .build()
        }.getOrNull() ?: return false
        return try {
            probeClient.newCall(request).awaitResponse().use { response ->
                response.code == 200 || response.code == 206
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    /** Runs the call off the caller's thread and cancels it when the caller is cancelled. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { response.close() }
            }
        })
    }

    private fun absolutizeUrl(baseUrl: String, maybeRelative: String): String {
        return runCatching {
            URL(URL(baseUrl), maybeRelative).toString()
        }.getOrElse { maybeRelative }
    }

    private fun summarizeUrl(url: String): String {
        return runCatching {
            val parsed = URL(url)
            val host = parsed.host ?: "unknown-host"
            val path = parsed.path ?: "/"
            "$host$path"
        }.getOrDefault(url.take(80))
    }

    private suspend fun performRequest(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String? = null
    ): RequestResponse {
        val requestBuilder = Request.Builder()
            .url(url)
            .headers(buildHeaders(headers))

        when (method.uppercase()) {
            "POST" -> requestBuilder.post((body ?: "").toRequestBody())
            "PUT" -> requestBuilder.put((body ?: "").toRequestBody())
            "DELETE" -> requestBuilder.delete()
            else -> requestBuilder.get()
        }

        httpClient.newCall(requestBuilder.build()).awaitResponse().use { response ->
            return RequestResponse(
                ok = response.isSuccessful,
                status = response.code,
                statusText = response.message,
                url = response.request.url.toString(),
                body = response.body?.string().orEmpty()
            )
        }
    }

    private fun buildHeaders(source: Map<String, String>): Headers {
        val headers = Headers.Builder()
        source.forEach { (name, value) ->
            if (!name.equals("Accept-Encoding", ignoreCase = true)) {
                headers.add(name, value)
            }
        }
        if (source.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            headers.add("User-Agent", DEFAULT_USER_AGENT)
        }
        return headers.build()
    }
}

private data class RequestResponse(
    val ok: Boolean,
    val status: Int,
    val statusText: String,
    val url: String,
    val body: String
)

private fun Map<*, *>.mapValue(key: String): Map<*, *>? {
    return this[key] as? Map<*, *>
}

private fun Map<*, *>.listMapValue(key: String): List<Map<*, *>> {
    val raw = this[key] as? List<*> ?: return emptyList()
    return raw.mapNotNull { it as? Map<*, *> }
}

private fun Map<*, *>.stringValue(key: String): String? {
    val value = this[key] ?: return null
    return value.toString()
}

private fun Map<*, *>.booleanValue(key: String): Boolean? {
    return this[key] as? Boolean
}

private fun Map<*, *>.numberValue(key: String): Double? {
    val value = this[key] ?: return null
    return when (value) {
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull()
        else -> null
    }
}
