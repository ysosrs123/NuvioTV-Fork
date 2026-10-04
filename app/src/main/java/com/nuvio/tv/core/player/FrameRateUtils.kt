package com.nuvio.tv.core.player

import android.app.Activity
import com.nuvio.tv.core.logging.redactedUrlForLog
import android.content.Context
import android.media.MediaExtractor
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.Display
import androidx.media3.common.MimeTypes
import io.github.anilbeesetti.nextlib.mediainfo.MediaInfo
import io.github.anilbeesetti.nextlib.mediainfo.MediaInfoBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Auto frame rate matching utility.
 * Switches the display refresh rate to match the video frame rate for judder-free playback.
 */
object FrameRateUtils {

    private const val TAG = "FrameRateUtils"
    private const val SWITCH_TIMEOUT_MS = 4000L
    private const val REFRESH_MATCH_MIN_TOLERANCE_HZ = 0.08f
    private const val NTSC_FILM_FPS = 24000f / 1001f
    private const val CINEMA_24_FPS = 24f
    private const val MIN_VALID_VIDEO_FPS = 10f
    private const val MAX_VALID_VIDEO_FPS = 120f
    private val NEXTLIB_HTTP_SCHEMES = setOf("http", "https")
    private val LIVE_STREAM_EXTENSIONS = listOf(".mpd", ".ism/manifest")
    private const val MKV_EXTENSION = ".mkv"
    private const val SWITCH_POLL_INTERVAL_MS = 60L
    private const val SWITCH_REQUIRED_STABLE_POLLS = 2
    /** Below this the reported rate is not real content; never switch the panel for it. */
    private const val MIN_AFR_SWITCH_FPS = 20f
    private const val RESOLUTION_MATCH_MIN_SHORT_SIDE = 720

    data class DisplayModeSwitchResult(
        val appliedMode: Display.Mode
    )

    private var originalModeId: Int? = null

    private const val FRAME_RATE_CACHE_SIZE = 64
    private val frameRateCache = object : LinkedHashMap<String, FrameRateDetection>(FRAME_RATE_CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FrameRateDetection>?): Boolean {
            return size > FRAME_RATE_CACHE_SIZE
        }
    }

    // Disk persistence for the fps cache, so a cold start can switch the
    // display mode before prepare instead of mid-prepare.
    //  - Inert until initFrameRateCachePersistence is called (unit tests
    //    never call it).
    //  - Only SHA-256(key) is written; keys can embed header tokens.
    //  - Entries expire after 30 days: a stale entry cannot be corrected
    //    within a play.
    //  - Writes are debounced 2 s onto one daemon thread, temp file + rename.
    private const val PERSIST_CAP = 256
    private const val PERSIST_TTL_MS = 30L * 24 * 60 * 60 * 1000
    private const val PERSIST_FLUSH_DELAY_MS = 2000L
    private const val PERSIST_FILE_NAME = "afr_fps_cache_v1.txt"

    private class PersistedDetection(
        val detection: FrameRateDetection,
        val storedAtMs: Long
    )

    @Volatile private var persistFile: java.io.File? = null
    private val persistFlushPending = java.util.concurrent.atomic.AtomicBoolean(false)
    private val persistExecutor: java.util.concurrent.ScheduledExecutorService by lazy {
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "AfrCachePersist").apply { isDaemon = true }
        }
    }

    // SHA-256(cache key) -> detection + store time. Guarded by the
    // frameRateCache lock so reader promotion, writer insertion and flush
    // snapshots cannot interleave.
    private val persistedCache = object : LinkedHashMap<String, PersistedDetection>(PERSIST_CAP, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PersistedDetection>?): Boolean {
            return size > PERSIST_CAP
        }
    }

    /** Idempotent; call once from Application.onCreate. Loads off-thread. */
    fun initFrameRateCachePersistence(context: android.content.Context) {
        if (persistFile != null) return
        val file = java.io.File(context.filesDir, PERSIST_FILE_NAME)
        persistFile = file
        persistExecutor.execute { loadPersistedCache(file) }
    }

    private fun hashKey(key: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(key.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            if (v < 0x10) sb.append('0')
            sb.append(Integer.toHexString(v))
        }
        return sb.toString()
    }

    private fun loadPersistedCache(file: java.io.File) {
        val now = System.currentTimeMillis()
        val loaded = ArrayList<Pair<String, PersistedDetection>>()
        try {
            if (!file.exists()) return
            file.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val parts = line.split('|')
                    if (parts.size != 6) return@forEach
                    val hash = parts[0]
                    val storedAt = parts[1].toLongOrNull() ?: return@forEach
                    if (now - storedAt > PERSIST_TTL_MS) return@forEach
                    val raw = parts[2].toFloatOrNull() ?: return@forEach
                    val snapped = parts[3].toFloatOrNull() ?: return@forEach
                    val w = parts[4].toIntOrNull()
                    val h = parts[5].toIntOrNull()
                    loaded.add(hash to PersistedDetection(FrameRateDetection(raw, snapped, w, h), storedAt))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "AFR fps cache load failed (starting empty): ${e.message}")
            return
        }
        if (loaded.isEmpty()) return
        synchronized(frameRateCache) {
            loaded.forEach { (hash, entry) ->
                if (!persistedCache.containsKey(hash)) persistedCache[hash] = entry
            }
        }
        Log.i(TAG, "AFR fps cache hydrated: ${loaded.size} persisted detection(s)")
    }

    private fun schedulePersistFlush() {
        if (persistFile == null) return
        if (!persistFlushPending.compareAndSet(false, true)) return
        persistExecutor.schedule({
            persistFlushPending.set(false)
            flushPersistedCache()
        }, PERSIST_FLUSH_DELAY_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    private fun flushPersistedCache() {
        val file = persistFile ?: return
        val snapshot: List<Pair<String, PersistedDetection>> = synchronized(frameRateCache) {
            persistedCache.entries.map { it.key to it.value }
        }
        try {
            val tmp = java.io.File(file.parentFile, file.name + ".tmp")
            tmp.bufferedWriter().use { w ->
                snapshot.forEach { (hash, e) ->
                    w.append(hash).append('|')
                    w.append(e.storedAtMs.toString()).append('|')
                    w.append(e.detection.raw.toString()).append('|')
                    w.append(e.detection.snapped.toString()).append('|')
                    w.append(e.detection.videoWidth?.toString() ?: "").append('|')
                    w.append(e.detection.videoHeight?.toString() ?: "")
                    w.newLine()
                }
            }
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (e: Exception) {
            Log.w(TAG, "AFR fps cache flush failed: ${e.message}")
        }
    }

    private fun sanitizeHeaders(headers: Map<String, String>?): Map<String, String> {
        val raw = headers ?: return emptyMap()
        if (raw.isEmpty()) return emptyMap()
        val sanitized = LinkedHashMap<String, String>(raw.size)
        raw.forEach { (key, value) ->
            val k = key.trim()
            val v = value.trim()
            if (k.isNotEmpty() && v.isNotEmpty() && !k.equals("Range", ignoreCase = true)) {
                sanitized[k] = v
            }
        }
        return sanitized
    }

    /**
     * Headers used for NextLib bypass decision and NextLib probe invocation.
     * Range is stripped because it is never meaningful for frame-rate probing.
     */
    internal fun streamHeadersForAfrProbe(headers: Map<String, String>): Map<String, String> {
        return headers.filterKeys { !it.equals("Range", ignoreCase = true) }
    }

    /**
     * Headers for MediaExtractor fallback. Adds Connection: close so the probe
     * connection is torn down promptly after sampling.
     */
    internal fun extractorProbeHeaders(headers: Map<String, String>): Map<String, String> {
        return streamHeadersForAfrProbe(headers).toMutableMap().apply {
            put("Connection", "close")
        }
    }

    /**
     * True when headers contain values NextLib cannot forward (auth tokens, cookies, etc.).
     * Synthetic / always-present headers (Range, User-Agent, Connection) are ignored so public
     * and debrid streams that only inject UA still get NextLib probing.
     */
    internal fun hasNextLibBlockingHeaders(headers: Map<String, String>): Boolean {
        return headers.any { (k, v) ->
            v.isNotBlank() &&
                !k.equals("Range", ignoreCase = true) &&
                !k.equals("User-Agent", ignoreCase = true) &&
                !k.equals("Connection", ignoreCase = true)
        }
    }

    internal fun buildCacheKey(url: String, headers: Map<String, String>, filename: String?): String {
        val sanitized = sanitizeHeaders(headers)
        // The resolved CDN host rotates per debrid resolve (one title can land on
        // several edges), so a key with the host would miss whenever a fresh
        // resolve landed on a different edge.
        // The filename identifies the content; the host is transport. Reader and
        // writer both come through here, so the two sides cannot disagree.
        val baseKey = if (!filename.isNullOrBlank()) {
            "file://$filename"
        } else {
            url.substringBefore('?')
        }

        if (sanitized.isEmpty()) return baseKey
        return buildString {
            append(baseKey)
            // Normalize header names to lowercase so cache hits are stable across casing.
            sanitized.mapKeys { (key, _) -> key.lowercase(Locale.ROOT) }
                .toSortedMap()
                .forEach { (key, value) ->
                    append('|')
                    append(key)
                    append('=')
                    append(value)
                }
        }
    }

    fun getCachedFrameRate(url: String, headers: Map<String, String>, filename: String? = null): FrameRateDetection? {
        val key = buildCacheKey(url, headers, filename)
        synchronized(frameRateCache) {
            val inMemory = frameRateCache[key]
            if (inMemory != null) return inMemory
            if (persistedCache.isEmpty()) return null
            val hash = hashKey(key)
            val persisted = persistedCache[hash] ?: return null
            if (System.currentTimeMillis() - persisted.storedAtMs > PERSIST_TTL_MS) {
                persistedCache.remove(hash)
                return null
            }
            // Promote so LRU ordering and later writes behave exactly as a
            // same-process detection would.
            frameRateCache[key] = persisted.detection
            Log.i(TAG, "AFR fps cache: served from persisted entry")
            return persisted.detection
        }
    }

    fun cacheFrameRate(url: String, headers: Map<String, String>, detection: FrameRateDetection, filename: String? = null) {
        val key = buildCacheKey(url, headers, filename)
        synchronized(frameRateCache) {
            frameRateCache[key] = detection
            if (persistFile != null) {
                persistedCache[hashKey(key)] = PersistedDetection(detection, System.currentTimeMillis())
            }
        }
        schedulePersistFlush()
    }

    /** Test-only: wipe the in-memory FPS cache between unit tests. */
    internal fun clearFrameRateCache() {
        synchronized(frameRateCache) {
            frameRateCache.clear()
            persistedCache.clear()
        }
    }

    data class FrameRateDetection(
        val raw: Float,
        val snapped: Float,
        val videoWidth: Int? = null,
        val videoHeight: Int? = null
    )

    internal data class DisplayModeSpec(
        val modeId: Int,
        val width: Int,
        val height: Int,
        val refreshRate: Float
    )

    private fun Display.Mode.toSpec() = DisplayModeSpec(modeId, physicalWidth, physicalHeight, refreshRate)

    private fun matchesTargetRefresh(refreshRate: Float, target: Float): Boolean {
        val tolerance = max(REFRESH_MATCH_MIN_TOLERANCE_HZ, target * 0.003f)
        return abs(refreshRate - target) <= tolerance
    }

    private fun pickBestForTarget(modes: List<DisplayModeSpec>, target: Float): DisplayModeSpec? {
        if (target <= 0f) return null
        val closest = modes.minByOrNull { abs(it.refreshRate - target) } ?: return null
        return if (matchesTargetRefresh(closest.refreshRate, target)) closest else null
    }

    private fun refreshWeight(refresh: Float, fps: Float): Float {
        if (fps <= 0f || refresh <= 0f) return Float.MAX_VALUE
        val div = refresh / fps
        if (div < 0.5f) return (fps - refresh) / fps

        val candidateRatios = floatArrayOf(1.0f, 2.0f, 2.5f, 3.0f, 4.0f, 5.0f, 6.0f)
        var minCadenceError = Float.MAX_VALUE
        for (m in candidateRatios) {
            val err = abs(div / m - 1f)
            if (err < minCadenceError) {
                minCadenceError = err
            }
        }

        var weight = minCadenceError

        val isCinemaOrNtscContent = fps in 23.5f..24.5f || fps in 29.5f..30.5f || fps in 59.5f..60.5f
        val isPalRefresh = abs(refresh - 25.0f) <= 0.1f || abs(refresh - 50.0f) <= 0.1f
        if (isCinemaOrNtscContent && isPalRefresh) {
            weight += 0.5f
        }

        if (refresh > 60f && div > 1f) {
            weight += div / 10000f
        }
        return weight
    }

    private fun recordOriginalMode(display: Display) {
        if (originalModeId == null) {
            originalModeId = display.mode.modeId
        }
    }

    /**
     * Refine ambiguous cinema rates for the current display capabilities.
     * Useful when probe reports ~24.x but panel supports both 23.976 and 24.000.
     */
    fun refineFrameRateForDisplay(
        activity: Activity,
        detectedFps: Float,
        prefer23976Near24: Boolean = false
    ): Float {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return detectedFps
        if (detectedFps !in 23.5f..24.5f) return detectedFps

        return try {
            val window = activity.window ?: return detectedFps
            val display = window.decorView.display ?: return detectedFps
            val activeMode = display.mode
            val sameSizeModes = display.supportedModes.filter {
                it.physicalWidth == activeMode.physicalWidth &&
                    it.physicalHeight == activeMode.physicalHeight
            }.map { it.toSpec() }
            if (sameSizeModes.isEmpty()) return detectedFps

            val has23976 = pickBestForTarget(sameSizeModes, NTSC_FILM_FPS) != null
            val has24 = pickBestForTarget(sameSizeModes, CINEMA_24_FPS) != null

            when {
                has23976 && has24 -> {
                    if (prefer23976Near24) {
                        NTSC_FILM_FPS
                    } else if (abs(detectedFps - NTSC_FILM_FPS) <= abs(detectedFps - CINEMA_24_FPS)) {
                        NTSC_FILM_FPS
                    } else {
                        CINEMA_24_FPS
                    }
                }
                has23976 -> NTSC_FILM_FPS
                has24 -> CINEMA_24_FPS
                else -> detectedFps
            }
        } catch (_: Exception) {
            detectedFps
        }
    }

    /**
     * How well a chosen mode actually serves the content's frame rate.
     * EXACT and DOUBLE are clean cadences; PULLDOWN is 2:3 judder that merely
     * happens to divide evenly; FALLBACK is the least-bad of a bad set.
     * Callers use it to tell a clean match from a judder-inducing one.
     */
    internal enum class ModeMatchQuality { EXACT, DOUBLE, PULLDOWN, FALLBACK }

    internal data class ModeChoice(val mode: DisplayModeSpec, val quality: ModeMatchQuality)

    /** True for cadences worth giving up a resolution match to obtain. */
    private fun ModeMatchQuality.isCleanCadence() =
        this == ModeMatchQuality.EXACT || this == ModeMatchQuality.DOUBLE

    /** 25 and 30 fps look smoother at 50 and 60 Hz on most TVs: each frame shown twice, same motion. */
    internal fun prefersDoubleRefresh(frameRate: Float): Boolean = frameRate in 24.5f..30.5f

    private fun chooseBestModeForFrameRate(
        activeMode: DisplayModeSpec,
        modes: List<DisplayModeSpec>,
        frameRate: Float
    ): ModeChoice {
        if (prefersDoubleRefresh(frameRate)) {
            pickBestForTarget(modes, frameRate * 2f)?.let {
                return ModeChoice(it, ModeMatchQuality.DOUBLE)
            }
        }
        pickBestForTarget(modes, frameRate)?.let {
            return ModeChoice(it, ModeMatchQuality.EXACT)
        }
        pickBestForTarget(modes, frameRate * 2f)?.let {
            return ModeChoice(it, ModeMatchQuality.DOUBLE)
        }
        pickBestForTarget(modes, frameRate * 2.5f)?.let {
            return ModeChoice(it, ModeMatchQuality.PULLDOWN)
        }
        val modeFallback = modes.minByOrNull { refreshWeight(it.refreshRate, frameRate) }
        return ModeChoice(modeFallback ?: activeMode, ModeMatchQuality.FALLBACK)
    }

    private fun hasValidVideoSize(videoWidth: Int?, videoHeight: Int?): Boolean {
        return (videoWidth ?: 0) > 0 && (videoHeight ?: 0) > 0
    }

    private fun normalizedSize(width: Int, height: Int): Pair<Int, Int> {
        return if (width >= height) width to height else height to width
    }

    internal fun selectResolutionCandidates(
        modeSizes: List<Pair<Int, Int>>,
        videoWidth: Int,
        videoHeight: Int
    ): List<Pair<Int, Int>> = resolutionCandidateGroups(
        modes = modeSizes.mapIndexed { index, (width, height) -> DisplayModeSpec(index, width, height, 0f) },
        videoWidth = videoWidth,
        videoHeight = videoHeight
    ).firstOrNull().orEmpty().map { it.width to it.height }

    private fun floorEligibleModes(modes: List<DisplayModeSpec>): List<DisplayModeSpec> =
        modes.filter { min(it.width, it.height) >= RESOLUTION_MATCH_MIN_SHORT_SIDE }.ifEmpty { modes }

    private fun resolutionCandidateGroups(
        modes: List<DisplayModeSpec>,
        videoWidth: Int,
        videoHeight: Int
    ): List<List<DisplayModeSpec>> {
        if (modes.isEmpty()) return emptyList()
        val (targetWidth, targetHeight) = normalizedSize(videoWidth, videoHeight)

        fun area(mode: DisplayModeSpec): Long {
            val (normalizedWidth, normalizedHeight) = normalizedSize(mode.width, mode.height)
            return normalizedWidth.toLong() * normalizedHeight.toLong()
        }

        fun fits(mode: DisplayModeSpec): Boolean {
            val (modeWidth, modeHeight) = normalizedSize(mode.width, mode.height)
            return modeWidth >= targetWidth && modeHeight >= targetHeight
        }

        // Never below 720p when the display offers 720p or larger.
        val eligible = floorEligibleModes(modes)
        val fitting = eligible.filter { fits(it) }
        if (fitting.isNotEmpty()) {
            return fitting.groupBy { area(it) }.toSortedMap().values.toList()
        }

        val maxArea = eligible.maxOf { area(it) }
        return listOf(eligible.filter { area(it) == maxArea })
    }

    internal fun selectDisplayMode(
        modes: List<DisplayModeSpec>,
        activeMode: DisplayModeSpec,
        frameRate: Float,
        videoWidth: Int?,
        videoHeight: Int?,
        resolutionMatchingEnabled: Boolean
    ): DisplayModeSpec? = planDisplayMode(
        modes = modes,
        activeMode = activeMode,
        frameRate = frameRate,
        videoWidth = videoWidth,
        videoHeight = videoHeight,
        resolutionMatchingEnabled = resolutionMatchingEnabled
    ).choice?.mode

    private class DisplayModePlan(val choice: ModeChoice?, val candidateCount: Int)

    private fun planDisplayMode(
        modes: List<DisplayModeSpec>,
        activeMode: DisplayModeSpec,
        frameRate: Float,
        videoWidth: Int?,
        videoHeight: Int?,
        resolutionMatchingEnabled: Boolean
    ): DisplayModePlan {
        if (resolutionMatchingEnabled && hasValidVideoSize(videoWidth, videoHeight)) {
            val sizeGroups = resolutionCandidateGroups(
                modes = modes,
                videoWidth = videoWidth ?: activeMode.width,
                videoHeight = videoHeight ?: activeMode.height
            )
            if (sizeGroups.isEmpty()) {
                Log.d(
                    TAG,
                    "No candidate display modes; leaving the display at " +
                        "${activeMode.refreshRate}Hz for ${frameRate}fps"
                )
                return DisplayModePlan(null, 0)
            }
            // Frame rate first: the smallest size that shows the rate cleanly (exact or doubled).
            for (group in sizeGroups) {
                val choice = chooseBestModeForFrameRate(activeMode, group, frameRate)
                if (choice.quality.isCleanCadence()) return DisplayModePlan(choice, group.size)
            }
            // An exact or double frame-rate match beats a resolution match; a pulldown or
            // fallback match does not. So when no size that fits the video can serve the
            // cadence cleanly, look at every size above the floor before settling.
            val nearest = chooseBestModeForFrameRate(activeMode, sizeGroups.first(), frameRate)
            val eligible = floorEligibleModes(modes)
            val wider = chooseBestModeForFrameRate(activeMode, eligible, frameRate)
            if (wider.quality.isCleanCadence()) {
                Log.d(
                    TAG,
                    "Resolution-matched modes offer only a ${nearest.quality} match for " +
                        "${frameRate}fps; widening to all display modes for a " +
                        "${wider.quality} match at ${wider.mode.refreshRate}Hz " +
                        "(${wider.mode.width}x${wider.mode.height})"
                )
                return DisplayModePlan(wider, eligible.size)
            }
            return DisplayModePlan(nearest, sizeGroups.first().size)
        }

        val sameSizeModes = modes.filter {
            it.width == activeMode.width && it.height == activeMode.height
        }
        if (sameSizeModes.isEmpty()) {
            Log.d(
                TAG,
                "No candidate display modes; leaving the display at " +
                    "${activeMode.refreshRate}Hz for ${frameRate}fps"
            )
            return DisplayModePlan(null, 0)
        }
        if (!resolutionMatchingEnabled && sameSizeModes.size <= 1) {
            Log.d(
                TAG,
                "Display offers a single mode at ${activeMode.width}x${activeMode.height} " +
                    "(${activeMode.refreshRate}Hz): no app-side frame rate matching is " +
                    "possible for ${frameRate}fps"
            )
            return DisplayModePlan(null, sameSizeModes.size)
        }
        return DisplayModePlan(
            chooseBestModeForFrameRate(activeMode, sameSizeModes, frameRate),
            sameSizeModes.size
        )
    }

    suspend fun matchFrameRateAndWait(
        activity: Activity,
        frameRate: Float,
        videoWidth: Int? = null,
        videoHeight: Int? = null,
        resolutionMatchingEnabled: Boolean = false
    ): DisplayModeSwitchResult? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        if (frameRate <= 0f) return null
        // Sanity floor. A broken source can report an absurd rate (for example a
        // 30 s 1280x720 error stub at frameRate=1.0), and switching the panel for it
        // costs a switch, a settle and an exit blank. snapToStandardRate
        // recognises nothing below 23.90 fps, so anything under this floor cannot be
        // matched meaningfully anyway; leave the display alone.
        if (frameRate < MIN_AFR_SWITCH_FPS) {
            Log.w(TAG, "Refusing display-mode switch for implausible frame rate ${frameRate}fps")
            return null
        }

        val switchPlan = withContext(Dispatchers.Main) {
            val window = activity.window ?: return@withContext null
            val display = window.decorView.display ?: return@withContext null
            val activeMode = display.mode
            val supportedModes = display.supportedModes.toList()

            val plan = planDisplayMode(
                modes = supportedModes.map { it.toSpec() },
                activeMode = activeMode.toSpec(),
                frameRate = frameRate,
                videoWidth = videoWidth,
                videoHeight = videoHeight,
                resolutionMatchingEnabled = resolutionMatchingEnabled
            )
            val candidateCount = plan.candidateCount
            val modeBest = plan.choice?.let { choice ->
                supportedModes.firstOrNull { it.modeId == choice.mode.modeId }
                    ?: activeMode.takeIf { it.modeId == choice.mode.modeId }
            }
            if (modeBest == null) {
                return@withContext Pair<Display.Mode?, DisplayModeSwitchResult?>(
                    null,
                    DisplayModeSwitchResult(activeMode)
                )
            }
            recordOriginalMode(display)
            if (modeBest.modeId == activeMode.modeId) {
                // Not necessarily optimal: very often it is simply the only mode on offer,
                // and calling 60Hz "optimal" for 25fps content is a plain untruth. Say
                // which of the two it is.
                val alternatives = candidateCount - 1
                Log.d(
                    TAG,
                    if (alternatives <= 0) {
                        "No alternative display mode at " +
                            "${activeMode.physicalWidth}x${activeMode.physicalHeight}: staying at " +
                            "${activeMode.refreshRate}Hz for ${frameRate}fps. The panel may still be " +
                            "matching the content on its own. Android cannot report that."
                    } else {
                        "Keeping ${activeMode.refreshRate}Hz for ${frameRate}fps: best of " +
                            "$candidateCount candidate modes"
                    }
                )
                return@withContext Pair<Display.Mode?, DisplayModeSwitchResult?>(
                    null,
                    DisplayModeSwitchResult(activeMode)
                )
            }

            Log.d(
                TAG,
                "Switching display mode: ${activeMode.refreshRate}Hz -> ${modeBest.refreshRate}Hz " +
                    "(video ${frameRate}fps)"
            )

            val layoutParams = window.attributes
            layoutParams.preferredDisplayModeId = modeBest.modeId
            window.attributes = layoutParams
            Pair(modeBest, null)
        } ?: return null

        val immediateResult = switchPlan.second
        if (immediateResult != null) return immediateResult

        val expectedMode = switchPlan.first ?: return null
        var stablePolls = 0
        var lastMode: Display.Mode? = null
        val start = System.currentTimeMillis()

        while (System.currentTimeMillis() - start < SWITCH_TIMEOUT_MS) {
            val mode = withContext(Dispatchers.Main) {
                activity.window?.decorView?.display?.mode
            } ?: break

            lastMode = mode
            val modeStable =
                mode.modeId == expectedMode.modeId ||
                    matchesTargetRefresh(mode.refreshRate, expectedMode.refreshRate)
            if (modeStable) {
                stablePolls += 1
                if (stablePolls >= SWITCH_REQUIRED_STABLE_POLLS) {
                    Log.d(
                        TAG,
                        "Display mode switch stabilized at ${mode.refreshRate}Hz (modeId=${mode.modeId})"
                    )
                    return DisplayModeSwitchResult(mode)
                }
            } else {
                stablePolls = 0
            }
            delay(SWITCH_POLL_INTERVAL_MS)
        }

        val fallbackMode = lastMode ?: expectedMode
        Log.w(
            TAG,
            "Display mode polling timed out after ${SWITCH_TIMEOUT_MS}ms, using ${fallbackMode.refreshRate}Hz"
        )
        return DisplayModeSwitchResult(fallbackMode)
    }

    fun cleanupDisplayListener() {
        // Kept for API compatibility with existing call sites.
    }

    fun clearOriginalDisplayMode() {
        originalModeId = null
    }

    fun restoreOriginalDisplayMode(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        val targetModeId = originalModeId ?: return false

        return try {
            val window = activity.window ?: return false
            val display = window.decorView.display ?: return false
            if (display.mode.modeId == targetModeId) {
                originalModeId = null
                true
            } else {
                cleanupDisplayListener()
                val layoutParams = window.attributes
                layoutParams.preferredDisplayModeId = targetModeId
                window.attributes = layoutParams
                originalModeId = null
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore display mode", e)
            false
        }
    }

    // An explicit proximity gate for the pre-seed. snapToStandardRate is
    // NOT usable for this test -- an input already equal to a ladder value
    // returns unchanged, so "snap changed the value" cannot distinguish an
    // on-ladder rate from an off-ladder one. Only a rate this close to a known
    // standard is trusted enough to switch the panel before prepare; anything
    // else (a torn/misparsed head, an audio DefaultDuration mistaken for video)
    // is left to the post-prepare track-format path.
    private val STANDARD_RATES = floatArrayOf(
        NTSC_FILM_FPS, CINEMA_24_FPS, 25f, 30000f / 1001f, 30f, 50f, 60000f / 1001f, 60f
    )
    private const val STANDARD_RATE_TOLERANCE_FPS = 0.05f

    internal fun isNearStandardRate(fps: Float): Boolean {
        if (!fps.isFinite() || fps < MIN_AFR_SWITCH_FPS) return false
        for (r in STANDARD_RATES) {
            if (abs(fps - r) <= STANDARD_RATE_TOLERANCE_FPS) return true
        }
        return false
    }

    fun snapToStandardRate(formatFrameRate: Float): Float {
        if (formatFrameRate <= 0f) return formatFrameRate
        return when {
            formatFrameRate in 23.90f..23.988f -> NTSC_FILM_FPS
            formatFrameRate in 23.988f..24.1f -> CINEMA_24_FPS
            formatFrameRate in 24.9f..25.1f -> 25f
            formatFrameRate in 29.90f..29.985f -> 30000f / 1001f
            formatFrameRate in 29.985f..30.1f -> 30f
            formatFrameRate in 49.9f..50.1f -> 50f
            formatFrameRate in 59.9f..59.97f -> 60000f / 1001f
            formatFrameRate in 59.97f..60.1f -> 60f
            else -> formatFrameRate
        }
    }

    private fun snapProbeRateByFrameDuration(measuredFps: Float, averageFrameDurationUs: Float): Float {
        if (measuredFps in 23.5f..24.5f) {
            val frameUs23976 = 1_000_000f / NTSC_FILM_FPS
            val frameUs24 = 1_000_000f / CINEMA_24_FPS
            val diff23976 = abs(averageFrameDurationUs - frameUs23976)
            val diff24 = abs(averageFrameDurationUs - frameUs24)
            val nearestCinema = if (diff23976 <= diff24) NTSC_FILM_FPS else CINEMA_24_FPS
            val nearestDiff = min(diff23976, diff24)

            // If probe timing is reasonably close to cinema cadence, trust frame-duration matching.
            if (nearestDiff <= 120f) {
                return nearestCinema
            }
        }
        return snapToStandardRate(measuredFps)
    }

    private fun isValidVideoFrameRate(frameRate: Float): Boolean {
        return frameRate.isFinite() && frameRate in MIN_VALID_VIDEO_FPS..MAX_VALID_VIDEO_FPS
    }

    private val probeHttpClient by lazy {
        com.nuvio.tv.ui.screens.player.PlayerPlaybackNetworking.playbackHttpClient.newBuilder()
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(14, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** Tiny Range used only to pay cold debrid/CDN TTFB before the real head/tail probe. */
    internal const val AFR_CDN_WARMUP_MAX_BYTES = 262_144L // 256 KiB

    // Coroutine cancellation cannot interrupt blocking OkHttp calls; the probe chain polls this
    // between stages and inside download loops so a cancelled preflight stops downloading.
    internal val NEVER_CANCELLED: () -> Boolean = { false }

    /**
     * Issues a small `bytes=0-(N-1)` GET so TLS + debrid origin spin-up happen on a cheap
     * request. The following 4 MB head / moov tail then reuse the warmed connection pool.
     * Failures are ignored — warmup is best-effort.
     */
    internal fun warmHttpOriginForAfrProbe(
        url: String,
        headers: Map<String, String>,
        isCancelled: () -> Boolean = NEVER_CANCELLED
    ): Boolean {
        val scheme = parseUriScheme(url)
        if (scheme != "http" && scheme != "https") return false
        if (isCancelled()) return false

        val requestBuilder = okhttp3.Request.Builder()
            .url(url)
            .header("Range", "bytes=0-${AFR_CDN_WARMUP_MAX_BYTES - 1}")

        if (headers.none { it.key.equals("User-Agent", ignoreCase = true) }) {
            requestBuilder.header(
                "User-Agent",
                com.nuvio.tv.ui.screens.player.PlayerMediaSourceFactory.DEFAULT_USER_AGENT
            )
        }
        headers.forEach { (k, v) ->
            if (v.isNotBlank() && !k.equals("Range", ignoreCase = true)) {
                requestBuilder.header(k, v)
            }
        }

        val call = probeHttpClient.newCall(requestBuilder.build())
        return try {
            call.execute().use { response ->
                if (response.code != 206 && response.code != 200) {
                    Log.d(TAG, "AFR CDN warmup skipped (HTTP ${response.code})")
                    return false
                }
                val body = response.body ?: return false
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (total < AFR_CDN_WARMUP_MAX_BYTES) {
                        if (isCancelled()) {
                            call.cancel()
                            return false
                        }
                        val read = input.read(
                            buffer,
                            0,
                            minOf(buffer.size.toLong(), AFR_CDN_WARMUP_MAX_BYTES - total).toInt()
                        )
                        if (read <= 0) break
                        total += read
                    }
                    val warmed = total > 0L
                    if (warmed) {
                        Log.d(TAG, "AFR CDN warmup drained ${total}B (rangeSatisfied=${response.code == 206})")
                    }
                    warmed
                }
            }
        } catch (e: Exception) {
            call.cancel()
            Log.d(TAG, "AFR CDN warmup failed: ${e.message}")
            false
        }
    }

    fun detectFrameRateFromSource(
        context: Context,
        sourceUrl: String,
        headers: Map<String, String> = emptyMap(),
        mimeType: String? = null,
        filename: String? = null
    ): FrameRateDetection? {
        detectFrameRateWithOkHttpProbe(context, sourceUrl, headers, mimeType, filename)?.let { return it }
        detectFrameRateFromNextLib(context, sourceUrl, headers, mimeType, filename)?.let { return it }
        return detectFrameRateFromExtractor(context, sourceUrl, headers)
    }

    fun detectFrameRateFromNextLib(
        context: Context,
        sourceUrl: String,
        headers: Map<String, String> = emptyMap(),
        mimeType: String? = null,
        filename: String? = null
    ): FrameRateDetection? {
        return detectFrameRateWithNextLib(context, sourceUrl, headers, mimeType, filename)
    }

    fun detectFrameRateFromExtractor(
        context: Context,
        sourceUrl: String,
        headers: Map<String, String> = emptyMap()
    ): FrameRateDetection? {
        if (isResolveProxyUrl(sourceUrl)) {
            val embeddedResolveUrl = extractEmbeddedResolveUrl(sourceUrl)
            if (!embeddedResolveUrl.isNullOrBlank()) {
                detectFrameRateWithExtractor(context, embeddedResolveUrl, headers)?.let { return it }
            }
        }
        return detectFrameRateWithExtractor(context, sourceUrl, headers)
    }

    internal fun isMp4Source(
        sourceUrl: String,
        mimeType: String? = null,
        filename: String? = null
    ): Boolean {
        if (mimeType?.equals(MimeTypes.VIDEO_MP4, ignoreCase = true) == true ||
            mimeType?.equals("video/iso.segment", ignoreCase = true) == true) return true
        if (filename != null) {
            val lower = filename.lowercase(Locale.ROOT)
            if (lower.endsWith(".mp4") || lower.endsWith(".m4v") || lower.endsWith(".mov")) return true
        }
        val normalized = sourceUrl.substringBefore('?').lowercase(Locale.ROOT)
        return normalized.endsWith(".mp4") || normalized.endsWith(".m4v") || normalized.endsWith(".mov")
    }

    internal fun parseContentRangeTotalLength(contentRangeHeader: String?): Long? {
        if (contentRangeHeader.isNullOrBlank()) return null
        val totalStr = contentRangeHeader.substringAfter('/', missingDelimiterValue = "").trim()
        if (totalStr == "*") return null
        return totalStr.toLongOrNull()?.takeIf { it > 0L }
    }


    internal data class HttpRangeFetchResult(
        val success: Boolean,
        val totalContentLength: Long? = null,
        /** True only when the server honored Range with HTTP 206. */
        val rangeSatisfied: Boolean = false
    )

    internal fun patchMdatHeaderForCompactMp4(targetFile: java.io.File, moovOffset: Long) {
        if (!targetFile.exists() || targetFile.length() < 32) return
        runCatching {
            java.io.RandomAccessFile(targetFile, "rw").use { raf ->
                var pos = 0L
                val fileLen = raf.length()
                while (pos + 8 <= fileLen && pos < moovOffset) {
                    raf.seek(pos)
                    val boxSize32 = raf.readInt().toLong() and 0xFFFFFFFFL
                    val type = ByteArray(4)
                    raf.readFully(type)
                    val typeStr = String(type, Charsets.US_ASCII)

                    val is64Bit = boxSize32 == 1L
                    val actualBoxSize = if (is64Bit) {
                        raf.readLong()
                    } else if (boxSize32 == 0L) {
                        fileLen - pos
                    } else {
                        boxSize32
                    }

                    if (typeStr == "mdat") {
                        val newMdatSize = (moovOffset - pos).coerceAtLeast(8L)
                        raf.seek(pos)
                        if (is64Bit) {
                            raf.writeInt(1)
                            raf.write("mdat".toByteArray(Charsets.US_ASCII))
                            raf.writeLong(newMdatSize)
                        } else {
                            raf.writeInt(newMdatSize.toInt())
                        }
                        Log.d(TAG, "Patched mdat box size to $newMdatSize for compact MP4 probe at pos $pos")
                        break
                    }

                    if (actualBoxSize <= 0) break
                    pos += actualBoxSize
                }
            }
        }
    }

    // Box types a valid MP4/MOV/fMP4 stream can start with: ftyp/styp (ISO BMFF / DASH),
    // moov/moof/sidx (header or segment first), free/skip/wide/pnot (QuickTime padding),
    // mdat (raw QuickTime with moov at the end), uuid (camera vendor boxes).
    private val MP4_LEADING_BOX_TYPES = setOf(
        "ftyp", "styp", "moov", "moof", "sidx", "free", "skip", "wide", "mdat", "pnot", "uuid"
    )

    internal fun hasFtypAtom(file: java.io.File): Boolean {
        if (!file.exists() || file.length() < 8) return false
        return runCatching {
            java.io.RandomAccessFile(file, "r").use { raf ->
                val bytes = ByteArray(8)
                raf.readFully(bytes)
                val size32 = ((bytes[0].toLong() and 0xFF) shl 24) or
                    ((bytes[1].toLong() and 0xFF) shl 16) or
                    ((bytes[2].toLong() and 0xFF) shl 8) or
                    (bytes[3].toLong() and 0xFF)
                // 0 = box extends to EOF, 1 = 64-bit size follows, otherwise header-inclusive size.
                val sizePlausible = size32 == 0L || size32 == 1L || size32 >= 8L
                val type = String(bytes, 4, 4, java.nio.charset.StandardCharsets.US_ASCII)
                sizePlausible && type in MP4_LEADING_BOX_TYPES
            }
        }.getOrDefault(false)
    }

    /** EBML / Matroska magic at file start (`1A 45 DF A3`) — used for extensionless MKV detection. */
    internal fun hasEbmlHeader(file: java.io.File): Boolean {
        if (!file.exists() || file.length() < 4) return false
        return runCatching {
            java.io.RandomAccessFile(file, "r").use { raf ->
                val bytes = ByteArray(4)
                raf.readFully(bytes)
                bytes[0] == 0x1A.toByte() &&
                    bytes[1] == 0x45.toByte() &&
                    bytes[2] == 0xDF.toByte() &&
                    bytes[3] == 0xA3.toByte()
            }
        }.getOrDefault(false)
    }

    internal fun hasMoovAtom(file: java.io.File): Boolean {
        if (!file.exists() || file.length() < 8) return false
        val fileLength = file.length()
        return runCatching {
            java.io.RandomAccessFile(file, "r").use { raf ->
                // Check head (first 4 MB)
                val headLen = fileLength.coerceAtMost(4_194_304L).toInt()
                val headBytes = ByteArray(headLen)
                raf.seek(0L)
                raf.readFully(headBytes)
                for (i in 0 until headBytes.size - 3) {
                    if (headBytes[i] == 0x6d.toByte() &&
                        headBytes[i + 1] == 0x6f.toByte() &&
                        headBytes[i + 2] == 0x6f.toByte() &&
                        headBytes[i + 3] == 0x76.toByte()
                    ) {
                        return@use true
                    }
                }

                // Check tail (last 32 MB) if file is larger than 4 MB
                if (fileLength > 4_194_304L) {
                    val tailSize = (fileLength - 4_194_304L).coerceAtMost(33_554_432L).toInt()
                    val tailBytes = ByteArray(tailSize)
                    raf.seek(fileLength - tailSize)
                    raf.readFully(tailBytes)
                    for (i in 0 until tailBytes.size - 3) {
                        if (tailBytes[i] == 0x6d.toByte() &&
                            tailBytes[i + 1] == 0x6f.toByte() &&
                            tailBytes[i + 2] == 0x6f.toByte() &&
                            tailBytes[i + 3] == 0x76.toByte()
                        ) {
                            return@use true
                        }
                    }
                }
                false
            }
        }.getOrDefault(false)
    }

    fun detectFrameRateWithOkHttpProbe(
        context: Context,
        sourceUrl: String,
        headers: Map<String, String> = emptyMap(),
        mimeType: String? = null,
        filename: String? = null,
        isCancelled: () -> Boolean = NEVER_CANCELLED
    ): FrameRateDetection? {
        val scheme = parseUriScheme(sourceUrl)
        if (scheme != "http" && scheme != "https") return null
        if (isLiveStreamUrl(sourceUrl)) return null

        val targetUrl = if (isResolveProxyUrl(sourceUrl)) {
            extractEmbeddedResolveUrl(sourceUrl)?.takeIf { parseUriScheme(it) == "http" || parseUriScheme(it) == "https" } ?: sourceUrl
        } else {
            sourceUrl
        }

        val tempFile = try {
            java.io.File.createTempFile("afr_probe_", ".tmp", context.cacheDir)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create temp file for AFR probe: ${e.message}")
            return null
        }

        try {
            // Pay cold debrid/CDN TTFB on a tiny Range so the real 4 MB head + moov/Tracks tail
            // run on a warmed origin/connection instead of racing the preflight budget.
            warmHttpOriginForAfrProbe(targetUrl, headers, isCancelled)

            // Pass 1: Head Range Probe (first 4 MB - bytes=0-4194303)
            val headMaxBytes = 4_194_304L + 65_536L
            val headResult = fetchHttpRangeToFile(
                targetUrl, headers, "bytes=0-4194303", headMaxBytes, tempFile, fileOffset = 0L,
                isCancelled = isCancelled
            )
            if (isCancelled()) return null
            val isMp4Detected = isMp4Source(targetUrl, mimeType, filename) || hasFtypAtom(tempFile)
            val isMkvDetected = isMkvSource(targetUrl, mimeType, filename) || hasEbmlHeader(tempFile)

            if (headResult.success && tempFile.length() > 0) {
                // For MP4/MKV files where moov/header is in the first 4MB, probe immediately.
                // If moov is not in the first 4MB (Legacy MP4), skip local probing to save ~4.5s wasted parsing stall.
                //
                // Pass 1 uses a truncated head range only — never run MediaExtractor timestamp
                // sampling here. Sampling ~350 frames on an incomplete MKV/MP4 can hang for the
                // full OkHttp preflight budget and starve NextLib/extractor fallbacks (MPV total
                // AFR await is only ~18s). Declared KEY_FRAME_RATE / NextLib on the head chunk is enough.
                if (isMkvDetected) {
                    val mkvDetection = probeMkvFromHeadAndSparseTracks(
                        context = context,
                        targetUrl = targetUrl,
                        headers = headers,
                        tempFile = tempFile,
                        rangeSatisfied = headResult.rangeSatisfied,
                        isCancelled = isCancelled
                    )
                    if (mkvDetection != null) return mkvDetection
                    Log.d(TAG, "MKV OkHttp probe exhausted; aborting for full-URL NextLib/extractor fallback")
                    return null
                }

                if (!isMp4Detected || hasMoovAtom(tempFile)) {
                    val detection = detectFrameRateFromLocalFile(
                        context = context,
                        file = tempFile,
                        allowTimestampSampling = false
                    )
                    if (detection != null) {
                        Log.d(TAG, "OkHttp AFR probe Pass 1 (head 4MB) succeeded: FPS=${detection.snapped}")
                        return detection
                    }
                } else {
                    Log.d(TAG, "Pass 1 head 4MB does not contain 'moov' atom; proceeding directly to Pass 2 tail probe")
                }
            }

            // Pass 2: Sparse File Head (ftyp) + Tail (moov) for Legacy MP4/MOV
            // Writing tail at fileOffset=tailStart via RandomAccessFile preserves exact MP4 atom offsets (stco/co64) without breaking FFmpeg container parser.
            if (isMp4Detected) {
                if (!headResult.rangeSatisfied) {
                    Log.w(TAG, "CDN did not satisfy Range (no HTTP 206); skipping MP4 tail probe")
                    return null
                }
                if (isCancelled()) return null
                val contentLength = headResult.totalContentLength
                    ?: fetchContentLength(targetUrl, headers, isCancelled)
                if (contentLength > 4_194_304L) {
                    val nextBoxOffset = resolveMp4TailStartAfterMdat(
                        targetUrl = targetUrl,
                        headers = headers,
                        tempFile = tempFile,
                        contentLength = contentLength,
                        isCancelled = isCancelled
                    )
                    if (nextBoxOffset == null || nextBoxOffset >= contentLength) {
                        Log.w(TAG, "MP4 box parsing could not resolve mdat end, aborting probe")
                        return null
                    }
                    val tailStart = nextBoxOffset

                    val tailSizeBytes = contentLength - tailStart
                    // Apply a safety limit (e.g. max 128 MB) to prevent excessive network usage
                    val safeTailSizeBytes = tailSizeBytes.coerceAtMost(134_217_728L)
                    val tailRange = "bytes=$tailStart-${tailStart + safeTailSizeBytes - 1}"
                    val tailMaxBytes = safeTailSizeBytes + 65_536L

                    Log.d(TAG, "OkHttp AFR probe Pass 2 fetching range $tailRange (size: $safeTailSizeBytes bytes)")

                    // Strategy A: Sparse offset file (Head at 0, Tail at original tailStart)
                    // Preserves exact co64/stco sample chunk offsets for Android Stagefright native MPEG4Extractor.
                    // On ext4/f2fs, sparse file takes only 14 MB physical disk space despite 23GB+ reported file length.
                    val usableSpace = runCatching { context.cacheDir.usableSpace }.getOrDefault(0L)
                    if (usableSpace > 50_000_000L) { // Requires only 50 MB usable disk space for 14 MB sparse blocks
                        val tailResultSparse = fetchHttpRangeToFile(
                            targetUrl, headers, tailRange, tailMaxBytes, tempFile, fileOffset = tailStart,
                            isCancelled = isCancelled
                        )
                        if (tailResultSparse.success && tailResultSparse.rangeSatisfied && tempFile.length() > 0) {
                            val detection = detectFrameRateFromLocalFile(context, tempFile)
                            if (detection != null) {
                                Log.d(TAG, "OkHttp AFR probe Pass 2 (sparse head+tail file) succeeded for MP4: FPS=${detection.snapped}")
                                return detection
                            }
                        }
                    }

                    // Strategy B: Compact file fallback (Head 4MB + Tail concatenated)
                    if (isCancelled()) return null
                    tempFile.delete()
                    val headResultCompact = fetchHttpRangeToFile(
                        targetUrl, headers, "bytes=0-4194303", headMaxBytes, tempFile, fileOffset = 0L,
                        isCancelled = isCancelled
                    )
                    if (headResultCompact.success && headResultCompact.rangeSatisfied) {
                        val compactOffset = tempFile.length()
                        val tailResultCompact = fetchHttpRangeToFile(
                            targetUrl, headers, tailRange, tailMaxBytes, tempFile, fileOffset = compactOffset,
                            isCancelled = isCancelled
                        )
                        if (tailResultCompact.success && tailResultCompact.rangeSatisfied && tempFile.length() > 0) {
                            patchMdatHeaderForCompactMp4(tempFile, compactOffset)
                            val detection = detectFrameRateFromLocalFile(context, tempFile)
                            if (detection != null) {
                                Log.d(TAG, "OkHttp AFR probe Pass 2 (compact head+tail file) succeeded for MP4: FPS=${detection.snapped}")
                                return detection
                            }
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "OkHttp AFR probe failed: ${e.message}")
        } finally {
            runCatching { tempFile.delete() }
        }
        return null
    }

    /**
     * MKV OkHttp path:
     * 1) Truncate torn EBML at the last complete Segment child, probe locally (no sample loop)
     * 2) If Tracks is missing/incomplete and CDN supports Range, SeekHead-sparse-fetch Tracks
     */
    internal fun probeMkvFromHeadAndSparseTracks(
        context: Context,
        targetUrl: String,
        headers: Map<String, String>,
        tempFile: java.io.File,
        rangeSatisfied: Boolean,
        isCancelled: () -> Boolean = NEVER_CANCELLED
    ): FrameRateDetection? {
        val layout = MatroskaAfrProbe.analyzeHead(tempFile)
        if (layout != null) {
            val before = tempFile.length()
            val truncated = MatroskaAfrProbe.truncateToSafePrefix(tempFile, layout)
            if (truncated != null && truncated < before) {
                Log.d(TAG, "MKV safe EBML truncate: $before -> $truncated bytes")
            }
            // Attachment-heavy releases push the first Cluster tens of MB in, far past the head
            // window, and a Cluster-less head makes demuxers discard the Tracks they just parsed.
            if (truncated != null && layout.tracksCompleteInPrefix && !layout.clusterInPrefix) {
                val patched = MatroskaAfrProbe.appendStubClusterForHeadProbe(
                    file = tempFile,
                    layout = layout,
                    contentEnd = truncated
                )
                if (patched != null) {
                    Log.d(TAG, "MKV head has no Cluster; appended stub Cluster (length=$patched)")
                } else {
                    Log.w(TAG, "MKV head has no Cluster and stub Cluster patch failed")
                }
            }
        }

        detectFrameRateFromLocalFile(
            context = context,
            file = tempFile,
            allowTimestampSampling = allowMkvLocalTimestampSampling(tempFile)
        )?.let { detection ->
            Log.d(TAG, "OkHttp AFR MKV Pass 1 (safe head) succeeded: FPS=${detection.snapped}")
            return detection
        }

        if (!rangeSatisfied) {
            Log.w(TAG, "CDN did not satisfy Range (no HTTP 206); skipping MKV Tracks sparse probe")
            return null
        }

        val tracksOffset = layout?.tracksAbsoluteOffset
        if (tracksOffset == null || tracksOffset < 0L) {
            Log.d(TAG, "MKV SeekHead did not resolve Tracks offset; skipping sparse probe")
            return null
        }

        // Tracks already fully in the safe prefix but NextLib still failed — sparse won't help
        // (likely missing DefaultDuration). Fall through to full-URL fallbacks.
        if (layout.tracksCompleteInPrefix) {
            Log.d(TAG, "MKV Tracks already complete in head but FPS not found; skipping sparse probe")
            return null
        }

        val usableSpace = runCatching { context.cacheDir.usableSpace }.getOrDefault(0L)
        if (usableSpace < 50_000_000L) {
            Log.w(TAG, "Insufficient cache space for MKV sparse Tracks probe")
            return null
        }

        // Peek Tracks header to learn exact element size, then fetch the full element (capped).
        if (isCancelled()) return null
        val peekBytes = 64L
        val peekFile = java.io.File(tempFile.parentFile, "afr_mkv_tracks_peek_${tempFile.name}")
        try {
            val peekResult = fetchHttpRangeToFile(
                url = targetUrl,
                headers = headers,
                rangeHeader = "bytes=$tracksOffset-${tracksOffset + peekBytes - 1}",
                maxBytes = peekBytes,
                targetFile = peekFile,
                fileOffset = 0L,
                isCancelled = isCancelled
            )
            if (!peekResult.success || !peekResult.rangeSatisfied || peekFile.length() < 4L) {
                Log.w(TAG, "MKV Tracks header peek failed")
                return null
            }
            val peekBuf = peekFile.readBytes()
            val tracksHeader = MatroskaAfrProbe.readElementFromBufferStart(peekBuf, tracksOffset)
            if (tracksHeader == null || tracksHeader.id != MatroskaAfrProbe.ID_TRACKS || tracksHeader.unknownSize) {
                Log.w(TAG, "MKV Tracks peek did not yield a sized Tracks element")
                return null
            }
            val tracksTotalSize = tracksHeader.headerSize + tracksHeader.dataSize
            if (tracksTotalSize <= 0L || tracksTotalSize > MatroskaAfrProbe.MAX_TRACKS_FETCH_BYTES) {
                Log.w(TAG, "MKV Tracks element size $tracksTotalSize outside probe limits")
                return null
            }

            // Keep the safe prefix at 0, then append Tracks contiguously at EOF. Writing at the
            // remote absolute offset creates a sparse hole that breaks Matroska demuxers.
            if (layout != null && layout.safePrefixLength > 0L && tempFile.length() > layout.safePrefixLength) {
                MatroskaAfrProbe.truncateToSafePrefix(tempFile, layout)
            }
            val tracksWriteOffset = tempFile.length()

            val tracksRange = "bytes=$tracksOffset-${tracksOffset + tracksTotalSize - 1}"
            Log.d(TAG, "OkHttp AFR MKV Pass 2 fetching Tracks range $tracksRange (append at $tracksWriteOffset)")
            val tracksResult = fetchHttpRangeToFile(
                url = targetUrl,
                headers = headers,
                rangeHeader = tracksRange,
                maxBytes = tracksTotalSize + 4_096L,
                targetFile = tempFile,
                fileOffset = tracksWriteOffset,
                isCancelled = isCancelled
            )
            if (!tracksResult.success || !tracksResult.rangeSatisfied) {
                Log.w(TAG, "MKV Tracks sparse fetch failed")
                return null
            }

            // Same rule as Pass 1: a Cluster-less file makes demuxers discard the Tracks they
            // just parsed, so terminate the sparse file with a stub Cluster + patched Segment size.
            if (!layout.clusterInPrefix) {
                val patched = MatroskaAfrProbe.appendStubClusterForHeadProbe(
                    file = tempFile,
                    layout = layout,
                    contentEnd = tempFile.length()
                )
                if (patched != null) {
                    Log.d(TAG, "MKV sparse Tracks file has no Cluster; appended stub Cluster (length=$patched)")
                } else {
                    Log.w(TAG, "MKV sparse Tracks stub Cluster patch failed")
                }
            }

            detectFrameRateFromLocalFile(
                context = context,
                file = tempFile,
                allowTimestampSampling = allowMkvLocalTimestampSampling(tempFile)
            )?.let { detection ->
                Log.d(TAG, "OkHttp AFR MKV Pass 2 (sparse Tracks) succeeded: FPS=${detection.snapped}")
                return detection
            }
        } finally {
            runCatching { peekFile.delete() }
        }
        return null
    }

    /**
     * Result of walking top-level MP4 boxes looking for the end of 'mdat'.
     * [NeedHeaderAt] means the next box header lies past the bytes currently present in the
     * local probe file (common when a large 'free'/'uuid' precedes 'mdat' and exceeds the
     * 4 MB head window) — caller should Range-peek ~64 bytes at [offset] and resume.
     */
    internal sealed class Mp4MdatWalkResult {
        data class Found(val mdatEnd: Long) : Mp4MdatWalkResult()
        data class NeedHeaderAt(val offset: Long) : Mp4MdatWalkResult()
        data object Failed : Mp4MdatWalkResult()
    }

    /**
     * Resolves the byte offset where the box after 'mdat' begins (typically 'moov').
     * Continues past non-mdat boxes whose payload is not in the local head by header-only skip,
     * peeking remote headers when needed.
     */
    private fun resolveMp4TailStartAfterMdat(
        targetUrl: String,
        headers: Map<String, String>,
        tempFile: java.io.File,
        contentLength: Long,
        isCancelled: () -> Boolean = NEVER_CANCELLED
    ): Long? {
        var startPos = 0L
        var lastPeekAt = -1L
        repeat(12) {
            if (isCancelled()) return null
            when (val walk = walkMp4BoxesForMdatEnd(tempFile, contentLength, startPos)) {
                is Mp4MdatWalkResult.Found -> {
                    Log.d(TAG, "MP4 mdat end resolved at ${walk.mdatEnd}")
                    return walk.mdatEnd
                }
                is Mp4MdatWalkResult.Failed -> {
                    Log.w(TAG, "MP4 box walk failed to resolve mdat end")
                    return null
                }
                is Mp4MdatWalkResult.NeedHeaderAt -> {
                    val peekAt = walk.offset
                    if (peekAt < 0L || peekAt >= contentLength) return null
                    // Same offset after a successful peek means the server returned fewer bytes
                    // than the box header needs; re-requesting would repeat identically.
                    if (peekAt == lastPeekAt) {
                        Log.w(TAG, "MP4 header peek at $peekAt made no progress; aborting")
                        return null
                    }
                    lastPeekAt = peekAt
                    val peekLen = 64L
                    val peekEnd = minOf(peekAt + peekLen - 1L, contentLength - 1L)
                    Log.d(TAG, "MP4 box walk needs header peek at $peekAt")
                    val peekResult = fetchHttpRangeToFile(
                        url = targetUrl,
                        headers = headers,
                        rangeHeader = "bytes=$peekAt-$peekEnd",
                        maxBytes = peekLen,
                        targetFile = tempFile,
                        fileOffset = peekAt,
                        isCancelled = isCancelled
                    )
                    if (!peekResult.success || !peekResult.rangeSatisfied) {
                        Log.w(TAG, "MP4 header peek failed at $peekAt")
                        return null
                    }
                    startPos = peekAt
                }
            }
        }
        Log.w(TAG, "MP4 box walk exceeded peek budget without resolving mdat end")
        return null
    }

    /**
     * Parses top-level MP4 boxes to find the end offset of 'mdat'.
     * Non-mdat boxes may be skipped using only their header size even when the payload is not
     * present locally (e.g. multi-MB 'free' padding larger than the 4 MB head window).
     */
    internal fun walkMp4BoxesForMdatEnd(
        file: java.io.File,
        contentLength: Long,
        startPos: Long = 0L
    ): Mp4MdatWalkResult {
        if (!file.exists() || contentLength <= 0L) return Mp4MdatWalkResult.Failed
        return runCatching {
            java.io.RandomAccessFile(file, "r").use { raf ->
                val fileLen = raf.length()
                var pos = startPos.coerceAtLeast(0L)
                repeat(64) {
                    if (pos >= contentLength) return@use Mp4MdatWalkResult.Failed
                    if (pos + 8L > fileLen) return@use Mp4MdatWalkResult.NeedHeaderAt(pos)

                    raf.seek(pos)
                    val size32 = raf.readInt().toLong() and 0xFFFFFFFFL
                    val typeBytes = ByteArray(4)
                    raf.readFully(typeBytes)
                    val type = String(typeBytes, java.nio.charset.StandardCharsets.US_ASCII)

                    val headerSize: Int
                    val boxSize: Long
                    when {
                        size32 == 1L -> {
                            if (pos + 16L > fileLen) return@use Mp4MdatWalkResult.NeedHeaderAt(pos)
                            val size64 = raf.readLong()
                            headerSize = 16
                            boxSize = size64
                        }
                        size32 == 0L -> {
                            headerSize = 8
                            boxSize = contentLength - pos
                        }
                        else -> {
                            headerSize = 8
                            boxSize = size32
                        }
                    }

                    if (boxSize < headerSize.toLong()) return@use Mp4MdatWalkResult.Failed

                    if (type == "mdat") {
                        val mdatEnd = pos + boxSize
                        return@use if (mdatEnd in (pos + headerSize.toLong())..contentLength) {
                            Mp4MdatWalkResult.Found(mdatEnd)
                        } else {
                            Mp4MdatWalkResult.Failed
                        }
                    }

                    // Header-only skip: payload need not be present in the local probe file.
                    pos += boxSize
                    if (pos < contentLength && pos + 8L > fileLen) {
                        return@use Mp4MdatWalkResult.NeedHeaderAt(pos)
                    }
                }
                Mp4MdatWalkResult.Failed
            }
        }.getOrDefault(Mp4MdatWalkResult.Failed)
    }

    /**
     * Parses top-level MP4 boxes from a local head file to determine the end offset of the 'mdat'
     * box. This allows pinpointing the start of the next box (typically 'moov') for tail range probing.
     * Returns null when the mdat header is not present locally (use [walkMp4BoxesForMdatEnd] + peek).
     */
    internal fun findNextBoxOffsetAfterMdat(file: java.io.File, contentLength: Long): Long? {
        return when (val walk = walkMp4BoxesForMdatEnd(file, contentLength)) {
            is Mp4MdatWalkResult.Found -> walk.mdatEnd
            else -> null
        }
    }

    internal fun fetchHttpRangeToFile(
        url: String,
        headers: Map<String, String>,
        rangeHeader: String,
        maxBytes: Long,
        targetFile: java.io.File,
        fileOffset: Long = 0L,
        isCancelled: () -> Boolean = NEVER_CANCELLED
    ): HttpRangeFetchResult {
        if (isCancelled()) return HttpRangeFetchResult(success = false)
        val requestBuilder = okhttp3.Request.Builder()
            .url(url)
            .header("Range", rangeHeader)

        if (headers.none { it.key.equals("User-Agent", ignoreCase = true) }) {
            requestBuilder.header("User-Agent", com.nuvio.tv.ui.screens.player.PlayerMediaSourceFactory.DEFAULT_USER_AGENT)
        }

        headers.forEach { (k, v) ->
            if (v.isNotBlank() && !k.equals("Range", ignoreCase = true)) {
                requestBuilder.header(k, v)
            }
        }

        val call = probeHttpClient.newCall(requestBuilder.build())
        return try {
            call.execute().use { response ->
                if (response.code != 206 && response.code != 200) {
                    Log.w(TAG, "fetchHttpRangeToFile server did not return 200/206 Content (code=${response.code})")
                    return HttpRangeFetchResult(success = false)
                }
                val totalLength = parseContentRangeTotalLength(response.header("Content-Range"))
                    ?: response.header("Content-Length")?.toLongOrNull()

                val body = response.body ?: return HttpRangeFetchResult(success = false)
                body.byteStream().use { input ->
                    java.io.RandomAccessFile(targetFile, "rw").use { raf ->
                        if (fileOffset > 0L) {
                            raf.seek(fileOffset)
                        }
                        val buffer = ByteArray(8192)
                        var totalRead = 0L
                        while (totalRead < maxBytes) {
                            if (isCancelled()) {
                                call.cancel()
                                return HttpRangeFetchResult(success = false)
                            }
                            val toRead = minOf(buffer.size.toLong(), maxBytes - totalRead).toInt()
                            val bytesRead = input.read(buffer, 0, toRead)
                            if (bytesRead <= 0) break
                            raf.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                        }
                        HttpRangeFetchResult(
                            success = totalRead > 0,
                            totalContentLength = totalLength,
                            rangeSatisfied = response.code == 206
                        )
                    }
                }
            }
        } catch (e: Exception) {
            call.cancel()
            Log.w(TAG, "fetchHttpRangeToFile failed for url=${url.redactedUrlForLog()} range=$rangeHeader: ${e.message}")
            HttpRangeFetchResult(success = false)
        }
    }

    internal fun fetchContentLength(
        url: String,
        headers: Map<String, String>,
        isCancelled: () -> Boolean = NEVER_CANCELLED
    ): Long {
        if (isCancelled()) return -1L
        val requestBuilder = okhttp3.Request.Builder()
            .url(url)
            .head()

        if (headers.none { it.key.equals("User-Agent", ignoreCase = true) }) {
            requestBuilder.header("User-Agent", com.nuvio.tv.ui.screens.player.PlayerMediaSourceFactory.DEFAULT_USER_AGENT)
        }

        headers.forEach { (k, v) ->
            if (v.isNotBlank() && !k.equals("Range", ignoreCase = true)) {
                requestBuilder.header(k, v)
            }
        }

        val call = probeHttpClient.newCall(requestBuilder.build())
        return try {
            call.execute().use { response ->
                if (!response.isSuccessful) return -1L
                response.header("Content-Length")?.toLongOrNull() ?: -1L
            }
        } catch (_: Exception) {
            call.cancel()
            -1L
        }
    }

    private fun allowMkvLocalTimestampSampling(file: java.io.File): Boolean {
        val layout = MatroskaAfrProbe.analyzeHead(file) ?: return false
        return layout.clusterInPrefix
    }

    internal fun detectFrameRateFromLocalFile(
        context: Context,
        file: java.io.File,
        allowTimestampSampling: Boolean = true
    ): FrameRateDetection? {
        detectFrameRateWithNextLibFromLocalFile(context, file)?.let { return it }

        return detectFrameRateWithExtractor(
            context = context,
            sourceUrl = file.absolutePath,
            headers = emptyMap(),
            allowTimestampSampling = allowTimestampSampling
        )
    }

    internal fun detectFrameRateWithNextLibFromLocalFile(
        context: Context,
        file: java.io.File
    ): FrameRateDetection? {
        try {
            val uri = Uri.fromFile(file)
            val mediaInfo = MediaInfoBuilder().from(context = context, uri = uri).build()
            if (mediaInfo != null) {
                try {
                    val video = mediaInfo.videoStream
                    if (video != null) {
                        val measured = video.frameRate.toFloat()
                        if (isValidVideoFrameRate(measured)) {
                            return FrameRateDetection(
                                raw = measured,
                                snapped = snapToStandardRate(measured),
                                videoWidth = video.frameWidth.takeIf { it > 0 },
                                videoHeight = video.frameHeight.takeIf { it > 0 }
                            )
                        }
                    }
                } finally {
                    runCatching { mediaInfo.release() }
                }
            }
        } catch (e: Throwable) {
            Log.d(TAG, "Local NextLib probe failed: ${e.message}")
        }
        return null
    }

    private fun detectFrameRateWithNextLib(
        context: Context,
        sourceUrl: String,
        headers: Map<String, String>,
        mimeType: String? = null,
        filename: String? = null
    ): FrameRateDetection? {
        if (!shouldUseNextLibProbe(sourceUrl, headers, mimeType, filename)) return null

        val embeddedResolveUrl = extractEmbeddedResolveUrl(sourceUrl)
        val shouldPreferEmbedded = isResolveProxyUrl(sourceUrl)
        val candidates = buildList {
            if (shouldPreferEmbedded && !embeddedResolveUrl.isNullOrBlank() && embeddedResolveUrl != sourceUrl) {
                add(embeddedResolveUrl)
                add(sourceUrl)
                return@buildList
            }

            add(sourceUrl)
            if (!embeddedResolveUrl.isNullOrBlank() && embeddedResolveUrl != sourceUrl) {
                add(embeddedResolveUrl)
            }
        }

        candidates.forEach { candidateUrl ->
            var mediaInfo: MediaInfo? = null
            try {
                val uri = Uri.parse(candidateUrl)
                val builder = MediaInfoBuilder().from(context = context, uri = uri)
                mediaInfo = builder.build() ?: return@forEach

                val video = mediaInfo.videoStream ?: return@forEach
                val measured = video.frameRate.toFloat()
                if (!isValidVideoFrameRate(measured)) return@forEach

                return FrameRateDetection(
                    raw = measured,
                    snapped = snapToStandardRate(measured),
                    videoWidth = video.frameWidth.takeIf { it > 0 },
                    videoHeight = video.frameHeight.takeIf { it > 0 }
                )
            } catch (e: Throwable) {
                Log.w(TAG, "NextLib frame rate probe failed: ${e.message}")
            } finally {
                runCatching { mediaInfo?.release() }
            }
        }
        return null
    }

    /** Hard deadline after which a stuck extractor probe is force-released. */
    private const val EXTRACTOR_PROBE_HARD_DEADLINE_MS = 6_000L

    private fun detectFrameRateWithExtractor(
        context: Context,
        sourceUrl: String,
        headers: Map<String, String>,
        allowTimestampSampling: Boolean = true
    ): FrameRateDetection? {
        val safeHeaders = headers
        val extractor = MediaExtractor()
        // MediaExtractor.setDataSource() is a blocking native call
        // that cooperative withTimeoutOrNull cancellation cannot interrupt. On a
        // non-faststart MP4 (moov atom at the tail) it reads toward end-of-file
        // and can block for minutes. Releasing the extractor from a
        // watchdog thread aborts the native open, making the probe budget real.
        val probeFinished = java.util.concurrent.atomic.AtomicBoolean(false)
        val watchdog = Thread({
            try {
                Thread.sleep(EXTRACTOR_PROBE_HARD_DEADLINE_MS)
            } catch (_: InterruptedException) {
                return@Thread
            }
            if (!probeFinished.get()) {
                Log.w(TAG, "AFR extractor probe exceeded ${EXTRACTOR_PROBE_HARD_DEADLINE_MS} ms; force-releasing extractor")
                try {
                    extractor.release()
                } catch (_: Throwable) {
                }
            }
        }, "afr-probe-watchdog").apply {
            isDaemon = true
            start()
        }
        return try {
            val uri = Uri.parse(sourceUrl)
            when (uri.scheme?.lowercase()) {
                "http", "https" -> extractor.setDataSource(sourceUrl, safeHeaders)
                else -> extractor.setDataSource(context, uri, safeHeaders)
            }

            var videoTrackIndex = -1
            var videoFormat: android.media.MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(android.media.MediaFormat.KEY_MIME)
                if (mime?.startsWith("video/") == true) {
                    videoTrackIndex = i
                    videoFormat = format
                    break
                }
            }
            if (videoTrackIndex < 0) return null

            val detectedVideoWidth = videoFormat
                ?.takeIf { it.containsKey(android.media.MediaFormat.KEY_WIDTH) }
                ?.runCatching { getInteger(android.media.MediaFormat.KEY_WIDTH) }
                ?.getOrNull()
                ?.takeIf { it > 0 }
            val detectedVideoHeight = videoFormat
                ?.takeIf { it.containsKey(android.media.MediaFormat.KEY_HEIGHT) }
                ?.runCatching { getInteger(android.media.MediaFormat.KEY_HEIGHT) }
                ?.getOrNull()
                ?.takeIf { it > 0 }

            val declaredFrameRate = videoFormat
                ?.takeIf { it.containsKey(android.media.MediaFormat.KEY_FRAME_RATE) }
                ?.runCatching { getFloat(android.media.MediaFormat.KEY_FRAME_RATE) }
                ?.getOrNull()
            if (declaredFrameRate != null && isValidVideoFrameRate(declaredFrameRate)) {
                return FrameRateDetection(
                    raw = declaredFrameRate,
                    snapped = snapToStandardRate(declaredFrameRate),
                    videoWidth = detectedVideoWidth,
                    videoHeight = detectedVideoHeight
                )
            }

            // Truncated OkHttp head probes must not enter the sample loop — it can block for seconds
            // on incomplete MKV/MP4 containers and exhaust the AFR preflight budget.
            if (!allowTimestampSampling) return null

            extractor.selectTrack(videoTrackIndex)
            val timestamps = ArrayList<Long>(400)
            val ignoreSamples = 3
            val targetSamples = 350 + ignoreSamples

            while (timestamps.size < targetSamples) {
                val ts = extractor.sampleTime
                if (ts < 0) break
                timestamps.add(ts)
                if (!extractor.advance()) break
            }

            if (timestamps.size <= ignoreSamples + 1) return null

            var totalFrameDurationUs = 0L
            for (i in (ignoreSamples + 1) until timestamps.size) {
                totalFrameDurationUs += (timestamps[i] - timestamps[i - 1])
            }

            val sampleCount = (timestamps.size - ignoreSamples - 1).coerceAtLeast(1)
            if (sampleCount < 30) return null

            val averageFrameDurationUs = totalFrameDurationUs.toFloat() / sampleCount.toFloat()
            if (averageFrameDurationUs <= 0f) return null

            val measured = 1_000_000f / averageFrameDurationUs
            if (!isValidVideoFrameRate(measured)) return null

            FrameRateDetection(
                raw = measured,
                snapped = snapProbeRateByFrameDuration(measured, averageFrameDurationUs),
                videoWidth = detectedVideoWidth,
                videoHeight = detectedVideoHeight
            )
        } catch (e: Exception) {
            Log.w(TAG, "Frame rate probe failed: ${e.message}")
            null
        } finally {
            probeFinished.set(true)
            watchdog.interrupt()
            try {
                extractor.release()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Whether NextLib MediaInfo probe should run for this source.
     * Exposed as internal for regression tests (0.7.10 parity + header bypass).
     */
    internal fun shouldUseNextLibProbe(
        sourceUrl: String,
        headers: Map<String, String>,
        mimeType: String? = null,
        filename: String? = null
    ): Boolean {
        if (sourceUrl.isBlank()) return false
        if (isLiveStreamUrl(sourceUrl)) return false

        // Bypass NextLib when auth/custom headers are present: MediaInfoBuilder cannot forward them.
        if (hasNextLibBlockingHeaders(headers)) return false

        if (isMkvSource(sourceUrl, mimeType, filename)) return true

        val scheme = parseUriScheme(sourceUrl)
        return when (scheme) {
            in NEXTLIB_HTTP_SCHEMES -> true
            "file", "content" -> true
            null -> true
            else -> false
        }
    }

    internal fun isLiveStreamUrl(sourceUrl: String): Boolean {
        val normalized = sourceUrl.substringBefore('?').lowercase(Locale.ROOT)
        return LIVE_STREAM_EXTENSIONS.any { ext -> normalized.endsWith(ext) }
    }

    internal fun isMkvSource(
        sourceUrl: String,
        mimeType: String? = null,
        filename: String? = null
    ): Boolean {
        if (mimeType?.equals(MimeTypes.VIDEO_MATROSKA, ignoreCase = true) == true ||
            mimeType?.equals("video/mkv", ignoreCase = true) == true ||
            mimeType?.equals("video/x-matroska", ignoreCase = true) == true) return true
        if (filename?.endsWith(MKV_EXTENSION, ignoreCase = true) == true) return true
        val normalized = sourceUrl.substringBefore('?').lowercase(Locale.ROOT)
        return normalized.endsWith(MKV_EXTENSION)
    }

    /** Scheme extraction that tolerates missing/stub Android Uri in JVM unit tests. */
    private fun parseUriScheme(sourceUrl: String): String? {
        val fromUri = try {
            Uri.parse(sourceUrl).scheme?.lowercase(Locale.ROOT)
        } catch (_: Throwable) {
            null
        }
        if (!fromUri.isNullOrBlank()) return fromUri
        val schemeEnd = sourceUrl.indexOf("://")
        if (schemeEnd <= 0) return null
        return sourceUrl.substring(0, schemeEnd).lowercase(Locale.ROOT)
    }

    /** Host extraction that tolerates missing/stub Android Uri in JVM unit tests. */
    private fun parseUriHost(url: String): String {
        val fromUri = try {
            Uri.parse(url).host
        } catch (_: Throwable) {
            null
        }
        if (!fromUri.isNullOrBlank()) return fromUri
        // https://host/path or https://user@host/path
        val afterScheme = url.substringAfter("://", missingDelimiterValue = "")
        if (afterScheme.isEmpty()) return ""
        val authority = afterScheme.substringBefore('/').substringBefore('?')
        return authority.substringAfter('@')
    }

    private fun isResolveProxyUrl(sourceUrl: String): Boolean {
        val normalized = sourceUrl.substringBefore('?').lowercase(Locale.ROOT)
        return "/resolve/" in normalized
    }

    private fun extractEmbeddedResolveUrl(sourceUrl: String): String? {
        val marker = "/resolve/"
        val markerIndex = sourceUrl.indexOf(marker, ignoreCase = true)
        if (markerIndex < 0) return null

        val afterResolve = sourceUrl.substring(markerIndex + marker.length)
        val nestedEncoded = afterResolve.substringAfter('/', missingDelimiterValue = "")
            .substringAfter('/', missingDelimiterValue = "")
        if (nestedEncoded.isBlank()) return null

        val decoded = runCatching {
            URLDecoder.decode(nestedEncoded, StandardCharsets.UTF_8.name())
        }.getOrNull() ?: return null

        if (decoded.startsWith("http://", ignoreCase = true) ||
            decoded.startsWith("https://", ignoreCase = true)
        ) {
            return decoded
        }
        return null
    }
}
