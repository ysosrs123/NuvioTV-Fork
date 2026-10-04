package com.nuvio.tv.core.player.thumbnail

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.nuvio.tv.data.local.ImagePerformancePreferences
import com.nuvio.tv.ui.screens.player.ParallelRangeDataSource
import com.nuvio.tv.ui.screens.player.PlayerPlaybackNetworking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Makes the next episode's coverage thumbnails in the last minutes of the current one, into the store its own
 * session opens later. Big boxes do this for any title, smaller boxes only up to 1080p.
 *
 * Playback comes first: it decodes only while the current playback has a healthy buffer (or is paused or gone),
 * never while playback is rate limited, at background priority and for at most [MAX_RUN_MS]. A playback of the
 * same title stops it first, so a store has one writer.
 */
internal object NextEpisodeThumbs {
    private const val TAG = "ThumbNext"
    private const val SPACING_MS = SeekThumbnails.SPACING_MS
    private val STRIDES_1080P = listOf(30, 6, 3)
    private val STRIDES_4K_WEB = listOf(30, 6, 3)
    private val STRIDES_4K_REMUX = listOf(30, 12, 6)
    private const val REMUX_MIN_BITS_PER_SECOND = 35_000_000L
    private const val MAX_BYTES_1080P = 150L * 1024 * 1024
    private const val MAX_BYTES_4K = 250L * 1024 * 1024
    private const val MAX_RUN_MS = 10L * 60 * 1000
    private const val HEALTHY_AHEAD_MS = 14_000L
    /** A 4K keyframe is about 1.5 MB, so a 4K pass wants a deep read-ahead and a slower pace. */
    private const val HEALTHY_AHEAD_4K_MS = 60_000L
    private const val PACE_SMALL_MS = 3_000L
    private const val PACE_STRONG_MS = 500L
    private const val PACE_STRONG_4K_MS = 2_000L
    private const val PLAYBACK_429_QUIET_MS = 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    @Volatile private var job: Job? = null
    @Volatile private var reader: HttpRangeReader? = null
    @Volatile private var runningFor: String? = null
    @Volatile private var jobFor: String? = null
    /** One try per next episode in an app run. */
    private val attempted = java.util.Collections.synchronizedSet(HashSet<String>())

    fun attempted(identity: String): Boolean = attempted.contains(identity)

    fun forgetAttempts() = attempted.clear()

    /** Starts the pass for [source] unless one ran or runs for it. [playerProvider] is the current player. */
    fun start(context: Context, source: ThumbSource, allow4K: Boolean, playerProvider: () -> ExoPlayer?) {
        if (suspended || !SeekThumbnails.hostResumed) return
        // The title's own session makes them from here.
        if (SeekThumbnails.isCurrentOrStarting(source.identity)) return
        if (!attempted.add(source.identity)) return
        Log.i(TAG, "background pass for ${source.identity} starting")
        val app = context.applicationContext
        val previous = job
        jobFor = source.identity
        job = scope.launch {
            previous?.let { runCatching { it.cancel() } }
            runningFor = source.identity
            try {
                run(app, source, allow4K, playerProvider)
            } catch (ce: CancellationException) {
                Log.i(TAG, "stopped (${source.identity})")
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG, "ended: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                runningFor = null
                reader = null
            }
        }
    }

    /** A playback of [identity] is starting: stop a pass on that title and wait until it has ended. */
    suspend fun stopAndJoinFor(identity: String) {
        if (jobFor != identity) return
        stopAndJoinNow()
    }

    suspend fun stopAndJoinAll() = stopAndJoinNow()

    private suspend fun stopAndJoinNow() {
        val j = job ?: return
        j.cancel()
        withTimeoutOrNull(30_000L) {
            while (!j.isCompleted) {
                // An index read is several blocking reads without a suspension point.
                reader?.cancel()
                delay(200L)
            }
        }
        // A start() during the wait must stay reachable for cancel().
        if (job === j) {
            job = null
            jobFor = null
        }
    }

    @Volatile private var suspended = false

    fun resume() {
        suspended = false
    }

    /** The player screen went to the background or closed. The next lookahead may try again. */
    fun cancel() {
        suspended = true
        val j = job ?: return
        if (!j.isActive) return
        j.cancel()
        reader?.cancel()
        jobFor?.let { attempted.remove(it) }
    }

    private suspend fun run(context: Context, source: ThumbSource, allow4K: Boolean, playerProvider: () -> ExoPlayer?) {
        val t0 = SystemClock.elapsedRealtime()
        if (!withContext(Dispatchers.IO) { ThumbNative.available }) return
        MemoryGovernor.init(context)
        val rgb565 = runCatching { ImagePerformancePreferences(context).rgb565Enabled }.getOrDefault(true)
        val r = HttpRangeReader(source.url, source.headers, PlayerPlaybackNetworking.createHttpClient(source.headers))
        reader = r
        if (!awaitHealthy(playerProvider, small4KCurrentBlocks = true, aheadMs = HEALTHY_AHEAD_MS, deadline = t0 + MAX_RUN_MS)) {
            Log.i(TAG, "gave up waiting (${source.identity})")
            return
        }
        val head = r.read(0, MkvIndexReader.HEAD_BYTES)
        val fileLength = r.totalLength
        val store = ThumbStore.open(context, source.identity, fileLength, rgb565)
        if (store.isMemoryOnly) {
            Log.i(TAG, "skip ${source.identity}: storage nearly full (thumbnails would not be kept)")
            return
        }
        ThumbStore.markBusy(store.key)
        try {
            pass(context, source, allow4K, playerProvider, r, head, fileLength, store, rgb565, t0)
        } finally {
            ThumbStore.unmarkBusy(store.key)
        }
    }

    private suspend fun pass(
        context: Context, source: ThumbSource, allow4K: Boolean, playerProvider: () -> ExoPlayer?,
        r: HttpRangeReader, head: ByteArray, fileLength: Long, store: ThumbStore, rgb565: Boolean, t0: Long,
    ) {
        val cached = store.cachedKeyframeIndex()?.let(KeyframeIndexCodec::decode)?.takeIf { it.fileLength == fileLength }
        val idx = cached ?: when {
            MkvIndexReader.looksLikeMatroska(head) -> MkvIndexReader.read(r, head)
            AviIndexReader.looksLikeAvi(head) -> AviIndexReader.read(r, head)
            Mp4IndexReader.looksLikeMp4(head) -> Mp4IndexReader.read(r, head)
            else -> throw UnsupportedMediaException("container not supported")
        }.also { store.setKeyframeIndex(KeyframeIndexCodec.encode(it)) }
        val tier = MemoryGovernor.tierFor(idx.video.width, idx.video.height)
        val fourK = tier == DecodeTier.SW_4K
        if (fourK && (!allow4K || !MemoryGovernor.isStrongBox)) {
            Log.i(TAG, "skip ${source.identity}: ${idx.video.width}x${idx.video.height} " +
                (if (!allow4K) "(setting up to 1080p)" else "(4K on a smaller box)"))
            store.flush()
            return
        }
        val durationMs = idx.durationUs / 1000L
        if (durationMs <= 0L) return
        val bps = fileLength * 8_000L / durationMs
        val strides = when {
            !fourK -> STRIDES_1080P
            bps >= REMUX_MIN_BITS_PER_SECOND -> STRIDES_4K_REMUX
            else -> STRIDES_4K_WEB
        }
        val slotCount = ((durationMs - 1) / SPACING_MS).toInt() + 1
        val slotKf = IntArray(slotCount) { idx.keyframes.nearest(it * SPACING_MS * 1000L + SPACING_MS * 500L) }
        val kfSlots = HashMap<Int, MutableList<Int>>()
        slotKf.forEachIndexed { s, kf -> if (kf >= 0) kfSlots.getOrPut(kf) { ArrayList() }.add(s) }
        val lattice = SeekGrid.latticeOrder(slotCount, strides, 0, 0, (20L * 60 * 1000 / SPACING_MS).toInt())
        val maxBytes = if (fourK) MAX_BYTES_4K else MAX_BYTES_1080P
        val aheadMs = if (fourK) HEALTHY_AHEAD_4K_MS else HEALTHY_AHEAD_MS
        val paceMs = when {
            !MemoryGovernor.isStrongBox -> PACE_SMALL_MS
            fourK -> PACE_STRONG_4K_MS
            else -> PACE_STRONG_MS
        }
        Log.i(TAG, "start ${source.identity}: ${idx.video.codec} ${idx.video.width}x${idx.video.height} " +
            "${bps / 1_000_000} Mb/s lattice=${strides.last() * SPACING_MS / 1000}s slots=${lattice.size} " +
            "stored=${store.slots().size} index=${if (cached != null) "cached" else "read"}")
        val extractor = KeyframeExtractor(idx, r)
        val decoder = FfmpegThumbDecoder()
        var made = 0
        val attempts = HashMap<Int, Int>()
        try {
            for (slot in lattice) {
                if (SystemClock.elapsedRealtime() - t0 > MAX_RUN_MS || extractor.bytesRead >= maxBytes) {
                    Log.i(TAG, "limit reached (${made} made, ${extractor.bytesRead / (1024 * 1024)} MB)")
                    break
                }
                val kf = slotKf[slot]
                if (kf < 0 || store.has(slot.toLong() * SPACING_MS * 1000L)) continue
                if ((attempts[kf] ?: 0) >= 2) continue
                if (!awaitHealthy(playerProvider, small4KCurrentBlocks = true, aheadMs = aheadMs, deadline = t0 + MAX_RUN_MS)) break
                if (SeekThumbnails.isCurrentOrStarting(source.identity)) {
                    Log.i(TAG, "its own session started - handing over")
                    break
                }
                // A full remux read-ahead can leave little memory; wait for it until the run's deadline.
                var verdict = MemoryGovernor.allow(tier, decoderWarm = decoder.warm)
                var loggedWait = false
                while (!verdict.allowed && SystemClock.elapsedRealtime() - t0 < MAX_RUN_MS) {
                    if (!loggedWait) {
                        Log.d(TAG, "waiting for memory (${verdict.reason})")
                        loggedWait = true
                    }
                    decoder.trim()
                    delay(5_000L)
                    if (!awaitHealthy(playerProvider, small4KCurrentBlocks = true, aheadMs = aheadMs, deadline = t0 + MAX_RUN_MS)) break
                    if (SeekThumbnails.isCurrentOrStarting(source.identity)) break
                    verdict = MemoryGovernor.allow(tier, decoderWarm = decoder.warm)
                }
                if (!verdict.allowed) {
                    Log.i(TAG, "memory stays short (${verdict.reason}) - stopping")
                    break
                }
                attempts[kf] = (attempts[kf] ?: 0) + 1
                val au = try {
                    extractor.extract(kf)
                } catch (e: RateLimitedException) {
                    RateLimitedHosts.appSession.note(e)
                    Log.i(TAG, "rate limited - stopping")
                    break
                } ?: continue
                val frame = atBackground { decoder.decode(au, idx.video, threads = 1) } ?: continue
                if (idx.video.dolbyVision?.profile == 5 && !frame.colour.doviIpt) {
                    Log.i(TAG, "Dolby Vision 5 without decoder metadata - stopping")
                    break
                }
                val decision = ColourClassifier.classify(idx.video.colour, frame.colour, idx.video.dolbyVision)
                val size = ThumbGeometry.outputSize(frame.width, frame.height, frame.sarNum, frame.sarDen, idx.video.displayAspect)
                val bmp = atBackground { decoder.render(frame, decision, size[0], size[1], rgb565) } ?: continue
                val slots = kfSlots[kf] ?: mutableListOf(slot)
                store.put(LongArray(slots.size) { slots[it].toLong() * SPACING_MS * 1000L }, idx.keyframes.ptsUs[kf], bmp)
                made++
                delay(paceMs)
            }
        } finally {
            runCatching { decoder.close() }
            store.flush()
        }
        // Same completeness rule as a session's coverage pass.
        if (lattice.count { store.has(it.toLong() * SPACING_MS * 1000L) } >= lattice.size * 97 / 100) store.markComplete()
        Log.i(TAG, "done ${source.identity}: $made made in ${(SystemClock.elapsedRealtime() - t0) / 1000} s, " +
            "stored=${store.slots().size}, ${r.bytes / (1024 * 1024)} MB")
    }

    /** Waits until the current playback can spare the work. False when [deadline] passed first. */
    private suspend fun awaitHealthy(
        playerProvider: () -> ExoPlayer?, small4KCurrentBlocks: Boolean, aheadMs: Long, deadline: Long,
    ): Boolean {
        while (true) {
            if (SystemClock.elapsedRealtime() > deadline) return false
            // A smaller box busy with a 4K title: that title comes first.
            if (small4KCurrentBlocks && !MemoryGovernor.isStrongBox && SeekThumbnails.currentSmall4KBusy()) {
                delay(5_000L)
                continue
            }
            val playback429 = ParallelRangeDataSource.hudClampLatched ||
                (ParallelRangeDataSource.hudClampLastHitAtMs > 0 &&
                    SystemClock.uptimeMillis() - ParallelRangeDataSource.hudClampLastHitAtMs < PLAYBACK_429_QUIET_MS)
            val ok = !playback429 && withContext(Dispatchers.Main) {
                val p = playerProvider() ?: return@withContext true
                if (p.playbackState == Player.STATE_BUFFERING) return@withContext false
                if (!p.isPlaying) return@withContext true
                if (small4KCurrentBlocks && !MemoryGovernor.isStrongBox) {
                    val f = p.videoFormat
                    if (f != null && MemoryGovernor.tierFor(f.width, f.height) == DecodeTier.SW_4K) return@withContext false
                }
                val ahead = p.bufferedPosition - p.currentPosition
                // A read-ahead the player has stopped filling also counts, or a small buffer setting would wait for ever.
                ahead >= aheadMs || (ahead >= HEALTHY_AHEAD_MS && !p.isLoading) ||
                    (p.duration > 0 && p.bufferedPosition >= p.duration - 1_000L)
            }
            if (ok) return true
            delay(2_000L)
        }
    }

    private inline fun <T> atBackground(block: () -> T): T {
        val tid = android.os.Process.myTid()
        val before = runCatching { android.os.Process.getThreadPriority(tid) }.getOrNull()
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
        try {
            return block()
        } finally {
            if (before != null) runCatching { android.os.Process.setThreadPriority(before) }
        }
    }
}
