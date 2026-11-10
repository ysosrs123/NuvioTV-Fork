package com.nuvio.tv.ui.screens.player

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.HttpUtil
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import java.io.InterruptedIOException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import com.nuvio.tv.core.player.thumbnail.ConnectionLedger
import com.nuvio.tv.core.player.thumbnail.LedgerKey
import com.nuvio.tv.data.local.PlayerSettings
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import androidx.media3.exoplayer.upstream.Allocation
import androidx.media3.exoplayer.upstream.DefaultAllocatorNative

import java.nio.ByteBuffer

/**
 * A DataSource that downloads progressive files using multiple parallel HTTP range requests.
 *
 * Each individual TCP connection may be limited to ~100 Mbps (due to CDN per-connection limits
 * or Java/Okio networking overhead). By downloading different byte ranges in parallel across
 * multiple connections, we can multiply the effective throughput (e.g., 3 connections ≈ 300 Mbps).
 *
 * Uses a buffer pool to reuse ByteArrays or native ByteBuffers and avoid GC churn from large object allocations.
 *
 * Only used for progressive downloads (MKV, MP4). HLS/DASH already handle chunked parallel downloads.
 */
@UnstableApi
internal class ParallelRangeDataSource(
    private val upstreamFactory: OkHttpDataSource.Factory,
    private val parallelConnections: Int = PlayerSettings.DEFAULT_PARALLEL_CONNECTION_COUNT,
    private val chunkSize: Long = PlayerSettings.DEFAULT_PARALLEL_CHUNK_SIZE_KB.toLong() * 1024,
    private val useNativeMemory: Boolean = false,
    // How many chunks may be in flight ahead of the read cursor. Computed from
    // the device-RAM native budget in PlayerMediaSourceFactory. The default is
    // connections+1 for any caller that does not set it.
    private val prefetchDepthChunks: Int = parallelConnections + 1,
    private val shouldAllowBackgroundPrefetch: () -> Boolean = { true },
    private val onResolvedUri: (Uri?) -> Unit = {},
    private val consumeBootstrapCache: (DataSpec) -> BootstrapCacheEntry? = { null },
    private val updateBootstrapCache: (BootstrapCacheEntry?) -> Unit = {},
    // Allows a warm reopen into an un-fetched region to be served by a bounded
    // single-connection GET instead of a whole aligned chunk. Off for MP4
    // session mode, whose scatter-read cursors rely on retained whole chunks to
    // make repeat visits free.
    private val allowContinuationReopen: Boolean = true,
    private val defaultRequestHeaders: Map<String, String> = emptyMap(),
    private val isolateSession: Boolean = false,
    // Live limit on downloads at once (Automatic speed-up); null keeps the configured depth.
    private val depthLimit: (() -> Int)? = null,
    // Counts bytes as they arrive from the network, before the reader gets to them.
    private val receivedBytes: java.util.concurrent.atomic.AtomicLong? = null,
    // Each finished piece request: its wait for the first byte and how fast the rest came.
    private val onPiece: ((PieceTiming) -> Unit)? = null,
    // Counts the piece requests really running.
    private val requestMeter: RequestMeter? = null,
    // Closes idle pooled connections the server may have dropped during a long pause.
    private val dropStaleConnections: (() -> Unit)? = null,
    // A piece request answered "too many requests": the server and the requests running there with it.
    private val onRateLimited: ((Uri?, Int) -> Unit)? = null,
    // A server refused an extra download while another kept delivering: the server and the requests running there.
    private val onExtraRefused: ((Uri?, Int) -> Unit)? = null,
    // A download failed while no other to the server delivered: the server and what ran there.
    private val onAllFailed: ((Uri?, AllDownloadsFailed) -> Unit)? = null,
    // Set: piece memory is taken in parts as bytes arrive, and what the pieces hold together stays under this.
    private val heldLimit: (() -> Long)? = null
) : DataSource, androidx.media3.common.ByteBufferDataReader {

    companion object {
        private const val TAG = "ParallelRangeDS"
        private const val READ_BUFFER_SIZE = 64 * 1024 // 64KB read buffer for chunk downloads
        // This window is read SYNCHRONOUSLY inside
        // open() before it returns, on a cold connection still in TCP slow-start.
        // The Matroska head sniff consumed 11,209 bytes before seeking to the
        // tail, and the full-open path then discards the window -- the same bytes
        // arrive again in chunk 0, which is already downloading in the background.
        // 256 KB keeps ~23x headroom over the observed sniff; over-run is safe by
        // construction (an exhausted window falls through to the chunk path).
        private const val BOOTSTRAP_READ_BYTES = 256L * 1024L

        // Backstop for the in-flight wait, not a schedule. Cold TTFB tops out
        // near 1 s and response headers near 1.3 s, so a download that has
        // produced nothing after three seconds is not merely slow to start;
        // exceeding the cap falls back to the blocking path, which has its own
        // 60 s bound.
        private const val IN_FLIGHT_WAIT_CAP_MS = 3_000L
        private const val IN_FLIGHT_POLL_MS = 2L

        // Body-stall watchdog. A chunk whose delivery RATE over
        // HEDGE_WINDOW_MS falls below HEDGE_STALL_RATE_BPS, after
        // HEDGE_MIN_OPEN_MS since open, is abandoned and re-fetched on a
        // fresh connection (same slot -> connection budget unchanged), up
        // to HEDGE_MAX_RESTARTS, then one final watchdog-disabled attempt.
        private const val HEDGE_MIN_OPEN_MS = 1_500L
        private const val HEDGE_WINDOW_MS = 2_000L
        private const val HEDGE_STALL_RATE_BPS = 256L * 1024L
        private const val HEDGE_MAX_RESTARTS = 3
        // Source-aware hedge. A single-window trip at 256 KB/s false-fires on
        // Usenet streams: the NNTP engine delivers in bursts (a ~1 MB rush then a
        // fetch-the-next-articles pause), so a lone sub-256 window is normal
        // cadence, not a stall. Those false fires sit at 211-261 KB/s, so Usenet
        // uses a lower rate floor AND requires two consecutive stalled windows
        // before restarting. CDN/debrid keeps the single-window 256 KB/s rule.
        private const val HEDGE_STALL_RATE_CDN = 256L * 1024L
        private const val HEDGE_STALL_RATE_USENET = 128L * 1024L
        private const val HEDGE_WINDOWS_CDN = 1
        private const val HEDGE_WINDOWS_USENET = 2

        private const val RETAINED_SESSION_TTL_MS = 45_000L
        // Covers one look-up of the link: connect (6 s per address) plus the first bytes (15 s).
        private const val LOOKUP_WAIT_CAP_MS = 30_000L
        private const val LOOKUP_POLL_MS = 100L
        // Earned prefetch: sequential bytes an open must serve before
        // lookahead prefetch is granted.
        private const val EARNED_PREFETCH_BYTES = 1L * 1024L * 1024L
        // Never evict a chunk touched in the last 2 s: an overlapping old
        // instance may still be reading it and would have to fetch it again.
        private const val EVICTION_TOUCH_GUARD_MS = 2_000L
        private const val TOUCH_RESOLUTION_MS = 100L
        private const val DELIVERED_NOTE_INTERVAL_MS = 100L
        // A conforming DataSource blocks
        // rather than returning 0 for a positive-length read; tolerate a few
        // zero-progress reads, then fail the chunk instead of spinning forever.
        private const val MAX_CONSECUTIVE_ZERO_READS = 3
        /** readOnce: the chunk was freed before it could be copied, read again. */
        private const val LEASE_LOST = Int.MIN_VALUE
        // Reader-blocked chunk escalation. Fire once the
        // reader has waited this long on a chunk whose in-flight watermark has
        // not moved (a hung request, invisible to the body-rate watchdog);
        // after firing, extend the in-flight poll so the duplicate's first
        // bytes can serve the reader progressively.
        private const val ESCALATE_AFTER_MS = 2_000L
        private const val ESCALATE_POLL_EXTENSION_MS = 3_000L

        // HTTP 429/503 is server-side rate-limiting, not a stalled socket. The
        // session's rate gate holds every piece while the server refuses, and the
        // prefetch depth adapts multiplicatively down / additively up (AIMD).
        private const val RATE_LIMIT_SLEEP_SLICE_MS = 100L
        /** TorBox refuses past a number running at once and takes the next request once one ends: try again soon. */
        internal const val TORBOX_REFUSAL_RETRY_MS = 500L

        // Additive-recovery probe cadence: +1 depth per quiet
        // interval; the interval doubles on every re-trip during the climb
        // (gentler probing of a strict provider) and resets once the cap
        // fully clears.
        private const val RATE_LIMIT_DEPTH_STEP_BASE_MS = 10_000L
        private const val RATE_LIMIT_DEPTH_STEP_MAX_MS = 60_000L
        private const val RATE_LIMIT_ESCALATION_MAX = 5

        internal const val SEGMENT_BYTES = 1024 * 1024
        private const val SEGMENT_POOL_MIN = 8
        /** A waiting piece looks again after this long even when nothing told it the share changed. */
        @VisibleForTesting
        internal var heldWaitMs = 100L
        private val EMPTY_BUFFER: ByteBuffer = ByteBuffer.allocate(0)


        private val playbackResources = Resources()
        var hudClampLatched: Boolean
            get() = playbackResources.hudClampLatched
            set(value) { playbackResources.hudClampLatched = value }
        var hudClampTrips: Int
            get() = playbackResources.hudClampTrips
            set(value) { playbackResources.hudClampTrips = value }
        var hudClampLastHitAtMs: Long
            get() = playbackResources.hudClampLastHitAtMs
            set(value) { playbackResources.hudClampLastHitAtMs = value }
        var hudDepthCap: Int
            get() = playbackResources.hudDepthCap
            set(value) { playbackResources.hudDepthCap = value }
        var hudDepthConfigured: Int
            get() = playbackResources.hudDepthConfigured
            set(value) { playbackResources.hudDepthConfigured = value }
        var hudNextStepAtMs: Long
            get() = playbackResources.hudNextStepAtMs
            set(value) { playbackResources.hudNextStepAtMs = value }
        var hudHedgeRestarts: Int
            get() = playbackResources.hudHedgeRestarts
            set(value) { playbackResources.hudHedgeRestarts = value }
        var hudHedgeExhausted: Boolean
            get() = playbackResources.hudHedgeExhausted
            set(value) { playbackResources.hudHedgeExhausted = value }
        fun hudClampCooldownRemainingMs(nowUptimeMs: Long): Long = playbackResources.hudClampCooldownRemainingMs(nowUptimeMs)
        /** A new playback: an earlier title's rate-limit clamp no longer applies, whatever source this one uses. */
        fun resetRateLimitMirrors() = playbackResources.resetClampMirrors()
        internal fun releaseRetainedSession() = playbackResources.releaseRetainedSession()
        /** Frees the retained session when no playback instance still reads from it. */
        internal fun releaseRetainedSessionIfIdle() = playbackResources.releaseRetainedSessionIfIdle()
        /** Drops retained pieces outside reader..reader+[keepAhead] and the idle buffers behind them. */
        internal fun trimRetainedSession(keepAhead: Int) = playbackResources.trimRetainedSession(keepAhead)
        internal fun drainIdleBuffers(chunkSize: Long) = playbackResources.drainIdleBuffers(chunkSize)
        /** A piece size no session uses any more: its idle buffers are freed now and late ones are never pooled. */
        internal fun retireChunkSize(chunkSize: Long) = playbackResources.retireChunkSize(chunkSize)
        /** A pre-started session for another link (the next episode) is waiting; a new live session would drop it. */
        internal fun otherPendingSession(uri: Uri): Boolean = playbackResources.otherPendingSession(uri)
        /** Drops a pre-started session for [uri] that is no longer wanted; one for another link stays. */
        internal fun dropPendingSession(uri: Uri) = playbackResources.dropPendingSession(uri)

        // Most downloads an isolated test session runs at once.
        internal const val MAX_DIAGNOSTIC_DOWNLOADS = 32

        /** Idle this long, a CDN has likely closed the pooled connections (keep-alive is often 60 to 75 s). */
        internal const val STALE_IDLE_MS = 60_000L

        @Volatile private var userSeekAtMs = 0L
        private const val USER_SEEK_OPEN_MS = 3_000L

        /** The player seeks on the user's word: the next open far from the reader drops the pieces left behind. */
        internal fun noteUserSeek() {
            userSeekAtMs = SystemClock.uptimeMillis()
        }

        private fun takeUserSeek(): Boolean {
            val at = userSeekAtMs
            if (at == 0L || SystemClock.uptimeMillis() - at > USER_SEEK_OPEN_MS) return false
            userSeekAtMs = 0L
            return true
        }

        /**
         * New pieces a speed-up session may start beside the requests already [running], wherever they are: pieces
         * that left the window or belong to another reader of the same file still hold a connection.
         */
        internal fun speedupRoom(depth: Int, running: Int): Int = depth - running.coerceAtLeast(0)

        /**
         * The pieces of [held] a long seek leaves behind: all outside [target]..[target]+[window] when the seek lands
         * outside the reader's own window ([reader]-1..[reader]+[window]+1); null for a short seek or no reader yet.
         */
        internal fun leftBySeek(held: Collection<Long>, reader: Long, target: Long, window: Int): List<Long>? {
            if (reader < 0L || target in (reader - 1)..(reader + window + 1)) return null
            return held.filter { it < target || it > target + window }
        }

        /** Chunks in flight ahead of the reader, never below connections+1 (what it takes to saturate the connections). */
        internal fun prefetchDepth(prefetchDepthChunks: Int, parallelConnections: Int): Int =
            prefetchDepthChunks.coerceAtLeast(parallelConnections + 1)

        private val heapChunkLogged = java.util.concurrent.atomic.AtomicBoolean(false)

        @VisibleForTesting
        internal var allocateChunk: (Int) -> Allocation? = { DefaultAllocatorNative.createAllocation(it) }
        @VisibleForTesting
        internal var freeChunk: (Allocation) -> Unit = { DefaultAllocatorNative.freeAllocation(it) }
    }

    /** Playback retains its existing shared owner; each diagnostic gets a private one. */
    private class Resources(val isolated: Boolean = false, val workerCount: Int = 1) {
        @Volatile var released = false
        val sharedExecutor: ExecutorService get() = if (!isolated) executorDelegate.value else synchronized(this) {
            check(!released) { "Diagnostic session is closed" }
            executorDelegate.value
        }
        fun closeDiagnostic() {
            check(isolated)
            synchronized(this) { released = true }
            try { releaseRetainedSession() } finally {
                clearGlobalPool()
                if (executorDelegate.isInitialized()) executorDelegate.value.shutdownNow()
            }
        }

        val readBufferLocal = object : ThreadLocal<ByteArray>() {
            override fun initialValue(): ByteArray = ByteArray(READ_BUFFER_SIZE)
        }

        // A single, shared, lazy cached thread pool with bounded max threads to prevent OOM/pthread_create failure
        val executorDelegate = lazy {
            val threadFactory = ThreadFactory { runnable ->
                Thread(runnable, if (isolated) "parallel-test-worker" else "parallel-ds-worker").apply {
                    priority = Thread.NORM_PRIORITY
                    isDaemon = true
                }
            }
            ThreadPoolExecutor(
                if (isolated) workerCount else 32, if (isolated) workerCount else 64, 60L, TimeUnit.SECONDS,
                java.util.concurrent.LinkedBlockingQueue<Runnable>(if (isolated) 64 else Int.MAX_VALUE),
                threadFactory,
                if (isolated) ThreadPoolExecutor.AbortPolicy() else ThreadPoolExecutor.DiscardPolicy()
            ).apply {
                allowCoreThreadTimeOut(true)
            }
        }

        val activeInstances = java.util.concurrent.atomic.AtomicInteger(0)
        val globalBufferPool = ConcurrentHashMap<Long, ConcurrentLinkedDeque<PooledBuffer>>()
        val retiredChunkSizes: MutableSet<Long> = ConcurrentHashMap.newKeySet()

        fun retireChunkSize(chunkSize: Long) {
            retiredChunkSizes.add(chunkSize)
            drainIdleBuffers(chunkSize)
        }

        fun otherPendingSession(uri: Uri): Boolean = synchronized(sessionLock) {
            val pending = pendingChunkSession
            pending != null && !pending.abandoned.get() && pending.requestUri != uri
        }

        fun dropPendingSession(uri: Uri) {
            synchronized(sessionLock) {
                val pending = pendingChunkSession ?: return
                if (pending.requestUri != uri) return
                teardownSessionLocked(pending, poolCap = 0)
                pendingChunkSession = null
            }
        }

        fun freeDirectBuffer(buffer: ByteBuffer) {
            if (!buffer.isDirect) return
            try {
                val cleanerMethod = buffer.javaClass.getMethod("cleaner")
                cleanerMethod.isAccessible = true
                val cleaner = cleanerMethod.invoke(buffer)
                if (cleaner != null) {
                    val cleanMethod = cleaner.javaClass.getMethod("clean")
                    cleanMethod.isAccessible = true
                    cleanMethod.invoke(cleaner)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to explicitly free direct buffer: ${e.message}")
            }
        }

        // Session-owned chunk downloads. ExoPlayer creates a new instance of
        // this class for every seek, so downloads (done and in flight) belong
        // to a companion-level session and instances are thin readers over
        // it. Eviction is touch-LRU under a memory-tiered cap; teardown happens
        // on stream change, idle TTL or player shutdown.
        //
        // HUD mirror of the rate-limit clamp, written by the download/read
        // paths and read by the stats overlay. Reset where a fresh session is
        // created.
        @Volatile var hudClampLatched: Boolean = false
        @Volatile var hudClampTrips: Int = 0
        @Volatile var hudClampLastHitAtMs: Long = 0L
        // AIMD state for the HUD row: current depth cap vs the
        // configured depth ("depth 2/5"), and the uptime when the next +1
        // step becomes eligible. Written only by ChunkSession's rate-limit
        // methods; zeroed with the other mirrors on fresh-session creation.
        @Volatile var hudDepthCap: Int = 0
        @Volatile var hudDepthConfigured: Int = 0
        @Volatile var hudNextStepAtMs: Long = 0L
        // HUD mirror of the body-stall hedge. hudHedgeRestarts counts
        // fresh-connection restart attempts this session; hudHedgeExhausted latches if
        // any chunk hit the restart cap. Written only by downloadChunkWithStallRestart;
        // zeroed on fresh-session creation with the other mirrors.
        @Volatile var hudHedgeRestarts: Int = 0
        @Volatile var hudHedgeExhausted: Boolean = false

        // Logs the watchdog settings once per process, even when nothing stalls.
        val obsAnnounced = AtomicBoolean(false)

        fun resetClampMirrors() {
            hudClampLatched = false
            hudClampTrips = 0
            hudClampLastHitAtMs = 0L
            hudDepthCap = 0
            hudDepthConfigured = 0
            hudNextStepAtMs = 0L
        }

        /** Time until the next +1 depth step; 0 when not capped. HUD read only. */
        fun hudClampCooldownRemainingMs(nowUptimeMs: Long): Long {
            if (!hudClampLatched) return 0L
            return (hudNextStepAtMs - nowUptimeMs).coerceAtLeast(0L)
        }

        inner class ChunkSession(
            val requestUri: Uri,
            // Identity only, never used to build a request. Settable so a
            // session adopted from the pre-start can take the open-time headers;
            // otherwise every subsequent open would see a key mismatch.
            @Volatile var requestHeaders: Map<String, String>,
            val chunkSize: Long,
            val chunkCap: Int,
            // Size of the live prefetch window (effectivePrefetchDepth).
            // Chunks in reader+1..reader+prefetchWindow are imminent and are
            // excluded from eviction so the reader never re-downloads them.
            val prefetchWindow: Int
        ) {
            @Volatile var resolvedUri: Uri? = null
            @Volatile var totalLength: Long = -1L

            /** When each piece last received bytes, for telling a refused extra download from an outage. */
            val deliveredAtMs = ConcurrentHashMap<Long, Long>()

            fun noteDelivered(chunkIndex: Long, nowMs: Long) {
                deliveredAtMs[chunkIndex] = nowMs
                if (deliveredAtMs.size > 64) deliveredAtMs.entries.removeIf { nowMs - it.value > 10_000L }
            }

            fun requestTarget(): Uri = resolvedUri ?: requestUri

            /**
             * Drops a redirect target on another host that could not be reached or refused the
             * file, so the next request asks the original link again. A target on the link's own
             * host is kept: asking that host again cannot give a different answer.
             */
            fun forgetResolvedUri(failed: Uri): Boolean = synchronized(this) {
                val resolved = resolvedUri ?: return false
                if (resolved != failed || resolved.host == requestUri.host) return false
                resolvedUri = null
                addressDropped = true
                lookUp = null
                true
            }

            fun adoptResolvedUri(resolved: Uri?): Boolean {
                if (resolved == null) return false
                return synchronized(this) {
                    if (resolvedUri == null) {
                        resolvedUri = resolved
                        addressDropped = false
                        true
                    } else {
                        false
                    }
                }
            }

            private var addressDropped = false
            private var lookUp: CompletableFuture<Uri>? = null

            /**
             * After an address was dropped, one download asks the link again ([LookUpTurn.asks])
             * and every other one waits for its answer. Null when no look-up is needed.
             */
            fun lookUpTurn(): LookUpTurn? = synchronized(this) {
                if (!addressDropped || resolvedUri != null) return null
                lookUp?.let { if (!it.isDone) return LookUpTurn(it, asks = false) }
                val fresh = CompletableFuture<Uri>()
                lookUp = fresh
                LookUpTurn(fresh, asks = true)
            }

            fun finishLookUp(turn: CompletableFuture<Uri>, error: Throwable?) {
                synchronized(this) { if (lookUp === turn) lookUp = null }
                if (error == null) turn.complete(requestTarget())
                else turn.completeExceptionally(error)
            }

            val futures = ConcurrentHashMap<Long, CompletableFuture<DownloadedChunk>>()
            val lastTouch = ConcurrentHashMap<Long, Long>()
            val abandoned = AtomicBoolean(false)
            // Chunks already escalated by the reader-blocked path (once per chunk).
            val escalatedChunks: MutableSet<Long> = ConcurrentHashMap.newKeySet()
            // AIMD rate-limit state. rateLimitDepthCap is the
            // multiplicative-decrease ceiling on prefetch depth
            // (Int.MAX_VALUE = uncapped): halved (never below 1) once per
            // rate-limited episode, stepped +1 per quiet probe interval, and
            // cleared once it climbs past the configured depth again.
            // lastRateLimitAtMs slides on EVERY observed 429/503 so quiet
            // time is measured from the last hit. rateLimitEscalation
            // persists across backoff cycles: the per-attempt ladder alone
            // resets every time the outer retry machinery re-enters it,
            // which is exactly how a hard limiter produces an indefinite
            // fixed-period hammer.
            val rateLimitDepthCap = AtomicInteger(Int.MAX_VALUE)
            @Volatile var lastRateLimitAtMs: Long = 0L
            @Volatile var lastDepthHalveAtMs: Long = 0L
            @Volatile var lastDepthStepAtMs: Long = 0L
            @Volatile var depthStepIntervalMs: Long = RATE_LIMIT_DEPTH_STEP_BASE_MS
            val rateLimitEscalation = AtomicInteger(0)
            val rateGate = ParallelRangeRateGate({ SystemClock.uptimeMillis() })
            val activeSources: MutableSet<DataSource> = java.util.concurrent.ConcurrentHashMap.newKeySet()
            // Progressive reads: live views of downloads in
            // flight, keyed like futures. Entries are owned by the
            // download attempt that registered them (two-arg remove).
            val inFlight = ConcurrentHashMap<Long, InFlightChunk>()
            // Piece memory taken in parts ([SegmentedBuffer]) that this session's pieces hold now.
            val heldBytes = java.util.concurrent.atomic.AtomicLong(0L)
            @Volatile var lastUsedAtMs: Long = SystemClock.uptimeMillis()

            @Volatile private var touchedChunk = -1L
            @Volatile private var touchedAtMs = 0L

            fun touch(chunkIndex: Long) {
                val now = SystemClock.uptimeMillis()
                lastUsedAtMs = now
                // Reads of one piece follow each other closely; the eviction guards work in seconds.
                val sinceMs = now - touchedAtMs
                if (chunkIndex == touchedChunk && sinceMs >= 0L && sinceMs < TOUCH_RESOLUTION_MS) return
                lastTouch[chunkIndex] = now
                touchedChunk = chunkIndex
                touchedAtMs = now
            }

            fun forgetTouch(chunkIndex: Long) {
                lastTouch.remove(chunkIndex)
                if (touchedChunk == chunkIndex) touchedChunk = -1L
            }

            fun forgetTouches() {
                lastTouch.clear()
                touchedChunk = -1L
            }

            // Chunk index most recently SERVED to a reader. Creation-time
            // touches in ensureChunkScheduled deliberately do not update this;
            // only the read paths do, so eviction can distinguish "behind the
            // cursor" from "prefetched ahead". Last-write-wins on purpose: a
            // transient side-cursor read may move it for one read and the main
            // cursor restores it immediately after; the 2 s touch guard covers
            // that window.
            @Volatile var lastReadChunkIndex: Long = -1L

            fun noteRead(chunkIndex: Long) {
                touch(chunkIndex)
                if (lastReadChunkIndex != chunkIndex) {
                    lastReadChunkIndex = chunkIndex
                    signalHeld()
                }
            }

            // Pieces waiting for room in [heldBytes] sleep on [heldChanged] until parts are given back, the reader
            // moves to another piece or the session ends.
            val heldLock = java.util.concurrent.locks.ReentrantLock()
            val heldChanged: java.util.concurrent.locks.Condition = heldLock.newCondition()

            fun signalHeld() {
                heldLock.lock()
                try { heldChanged.signalAll() } finally { heldLock.unlock() }
            }

            /** Stamp an observed 429/503 so quiet time restarts. */
            fun noteRateLimitHit() {
                val now = SystemClock.uptimeMillis()
                lastRateLimitAtMs = now
                hudClampLastHitAtMs = now
                if (hudClampLatched) hudNextStepAtMs = now + depthStepIntervalMs
            }

            /**
             * Record the start of one rate-limited episode:
             * stamp, bump the wait escalation, and apply ONE multiplicative
             * depth decrease (guarded so a burst of concurrent 429s across
             * download threads counts as a single congestion event, not a
             * cascade to 1). Returns the escalation level this episode's
             * waits should use (the pre-bump value, so a first-ever episode
             * still starts from the short ladder).
             */
            fun beginRateLimitEpisode(configuredDepth: Int): Int {
                val now = SystemClock.uptimeMillis()
                lastRateLimitAtMs = now
                hudClampLastHitAtMs = now
                val escalation = rateLimitEscalation.getAndUpdate {
                    (it + 1).coerceAtMost(RATE_LIMIT_ESCALATION_MAX)
                }
                if (now - lastDepthHalveAtMs >= 1_000L) {
                    val alreadyCapped = rateLimitDepthCap.get() < configuredDepth
                    val effective = rateLimitDepthCap.get().coerceAtMost(configuredDepth)
                    val halved = (effective / 2).coerceAtLeast(1)
                    if (halved < effective) {
                        rateLimitDepthCap.set(halved)
                        lastDepthHalveAtMs = now
                        // Gentler probing only when the provider pushes back
                        // AGAIN during the climb: the first trip keeps the base
                        // cadence, a re-trip doubles the interval.
                        if (alreadyCapped) {
                            depthStepIntervalMs =
                                (depthStepIntervalMs * 2).coerceAtMost(RATE_LIMIT_DEPTH_STEP_MAX_MS)
                        }
                        hudClampLatched = true
                        hudClampTrips += 1
                        hudDepthCap = halved
                        hudDepthConfigured = configuredDepth
                        hudNextStepAtMs = now + depthStepIntervalMs
                        Log.w(TAG, "Rate-limited; prefetch depth halved to " +
                            "$halved/$configuredDepth (probe interval ${depthStepIntervalMs}ms)")
                    }
                }
                return escalation
            }

            /**
             * Current allowed prefetch depth. Uncapped sessions
             * pay nothing. A capped session steps +1 after each probe
             * interval of quiet (no 429/503 observed) and clears the cap
             * (resetting the probe interval and decaying the wait escalation)
             * once it climbs past the configured depth again.
             */
            fun currentAllowedDepth(configuredDepth: Int): Int {
                val cap = rateLimitDepthCap.get()
                if (cap == Int.MAX_VALUE) return configuredDepth
                val now = SystemClock.uptimeMillis()
                if (now - lastRateLimitAtMs >= depthStepIntervalMs &&
                    now - lastDepthStepAtMs >= depthStepIntervalMs) {
                    val stepped = cap + 1
                    if (rateLimitDepthCap.compareAndSet(cap, stepped)) {
                        lastDepthStepAtMs = now
                        rateLimitEscalation.updateAndGet { (it - 1).coerceAtLeast(0) }
                        if (stepped >= configuredDepth) {
                            rateLimitDepthCap.set(Int.MAX_VALUE)
                            depthStepIntervalMs = RATE_LIMIT_DEPTH_STEP_BASE_MS
                            hudClampLatched = false
                            hudDepthCap = 0
                            hudNextStepAtMs = 0L
                            Log.i(TAG, "Rate-limit depth cap cleared; parallel prefetch fully restored")
                        } else {
                            hudDepthCap = stepped
                            hudNextStepAtMs = now + depthStepIntervalMs
                            Log.i(TAG, "Rate-limit quiet; prefetch depth stepped to $stepped/$configuredDepth")
                        }
                    }
                }
                return rateLimitDepthCap.get().coerceAtMost(configuredDepth)
            }
        }

        val sessionLock = Any()
        var currentChunkSession: ChunkSession? = null
        // A session created speculatively at stream-resolve time, holding a
        // chunk-0 download that is already in flight before the player exists.
        // Kept in its own slot on purpose: on a transition the OUTGOING stream is
        // still playing and still owns currentChunkSession, so a pre-start must
        // never tear that down. The old session dies at adoption instead -- the
        // moment the player commits to the new source.
        var pendingChunkSession: ChunkSession? = null

        /** Release one session buffer: recycle to the pool, or free directly on teardown. */
        fun releaseSessionBuffer(buffer: PooledBuffer, chunkSz: Long, poolCap: Int) {
            buffer.release { recycleBuffer(it, chunkSz, poolCap) }
        }

        @Synchronized private fun recycleBuffer(buffer: PooledBuffer, chunkSz: Long, poolCap: Int) {
            if (buffer is SegmentedBuffer) {
                buffer.freeSegments()
                return
            }
            if (poolCap > 0 && !released && chunkSz !in retiredChunkSizes) {
                val pool = globalBufferPool.computeIfAbsent(chunkSz) { ConcurrentLinkedDeque() }
                if (pool.size < poolCap) {
                    pool.offerLast(buffer)
                    return
                }
            }
            freeBuffer(buffer)
        }

        private fun freeBuffer(buffer: PooledBuffer) {
            if (buffer is SegmentedBuffer) {
                buffer.freeSegments()
            } else if (buffer.allocation != null) {
                freeChunk(buffer.allocation)
            } else if (buffer.byteBuffer.isDirect) {
                freeDirectBuffer(buffer.byteBuffer)
            }
        }

        /**
         * Evict one future from a session. Handles the complete-vs-cancel race:
         * if cancel() loses because the download just completed, the buffer is
         * released via the completed value; if cancel() wins, the download
         * loop's cancellation checks release the buffer on its own thread.
         */
        fun evictFuture(
            session: ChunkSession,
            chunkIndex: Long,
            poolCap: Int
        ) {
            val future = session.futures.remove(chunkIndex) ?: return
            session.forgetTouch(chunkIndex)
            if (!future.cancel(true) && future.isDone && !future.isCancelled) {
                try {
                    releaseSessionBuffer(future.get().buffer, session.chunkSize, poolCap)
                } catch (_: Exception) {
                }
            }
        }

        fun teardownSessionLocked(session: ChunkSession, poolCap: Int) {
            session.abandoned.set(true)
            session.signalHeld()
            session.activeSources.forEach { ds ->
                try { ds.close() } catch (_: Exception) {}
            }
            session.activeSources.clear()
            val indices = session.futures.keys.toList()
            for (index in indices) {
                evictFuture(session, index, poolCap)
            }
            session.futures.clear()
            session.forgetTouches()
            session.inFlight.clear()
        }

        /**
         * Get the shared session for this request URI, creating (and tearing
         * down any stale/mismatched predecessor) as needed.
         */
        fun obtainSession(
            requestUri: Uri,
            requestHeaders: Map<String, String>,
            chunkSz: Long,
            chunkCap: Int,
            poolCap: Int,
            prefetchWindow: Int
        ): ChunkSession {
            synchronized(sessionLock) {
                check(!released) { "Diagnostic session is closed" }
                retiredChunkSizes.remove(chunkSz)
                val existing = currentChunkSession
                if (existing != null) {
                    val fresh = SystemClock.uptimeMillis() - existing.lastUsedAtMs <= RETAINED_SESSION_TTL_MS
                    if (fresh && !existing.abandoned.get() &&
                        existing.requestUri == requestUri && existing.chunkSize == chunkSz &&
                        existing.requestHeaders == requestHeaders
                    ) {
                        existing.lastUsedAtMs = SystemClock.uptimeMillis()
                        return existing
                    }
                    teardownSessionLocked(existing, poolCap)
                    currentChunkSession = null
                }
                // Adopt a pre-started session when the player opens the very
                // URI it was created for. Chunk 0 is already downloading (or done),
                // so this open skips the wait that would otherwise start here.
                // Geometry is part of the key: a shape mismatch means the pre-start
                // derived differently from createMediaSource, and adopting would be
                // worse than starting clean.
                val pending = pendingChunkSession
                if (pending != null) {
                    val pendingFresh = SystemClock.uptimeMillis() - pending.lastUsedAtMs <= RETAINED_SESSION_TTL_MS
                    // The DataSpec header map is deliberately NOT part of this
                    // test. media3 adds Icy-MetaData when ProgressiveMediaSource
                    // builds the spec, so the pre-start (which has no DataSpec)
                    // can never predict it, and that one header would reject every
                    // adoption. Excluding it is safe because the field
                    // is identity only: chunk downloads build their own DataSpec from
                    // the URI and a byte range, and the real request headers come from
                    // the OkHttp factory both sides share. URI and chunk geometry
                    // still gate adoption.
                    val pendingMatches = pendingFresh && !pending.abandoned.get() &&
                        pending.requestUri == requestUri && pending.chunkSize == chunkSz
                    pendingChunkSession = null
                    if (pendingMatches) {
                        Log.i(
                            TAG,
                            "PRESTART: adopted pre-started session, chunk(s) held=${pending.futures.size} " +
                                "headerRekey=${pending.requestHeaders.keys.sorted()}->${requestHeaders.keys.sorted()}"
                        )
                        // Take the open-time headers so the ordinary session-identity
                        // check keeps matching on every later open (seeks included).
                        pending.requestHeaders = requestHeaders
                        pending.lastUsedAtMs = SystemClock.uptimeMillis()
                        currentChunkSession = pending
                        return pending
                    }
                    Log.i(
                        TAG,
                        "PRESTART: pre-started session discarded (no match at open) " +
                            "uriMatch=${pending.requestUri == requestUri} " +
                            "chunkMatch=${pending.chunkSize == chunkSz} " +
                            "headerMatch=${pending.requestHeaders == requestHeaders} " +
                            "fresh=$pendingFresh abandoned=${pending.abandoned.get()} " +
                            "pendingChunk=${pending.chunkSize} openChunk=$chunkSz " +
                            "pendingHeaderKeys=${pending.requestHeaders.keys.sorted()} " +
                            "openHeaderKeys=${requestHeaders.keys.sorted()} " +
                            "pendingHost=${pending.requestUri.host} openHost=${requestUri.host} " +
                            "pendingScheme=${pending.requestUri.scheme} openScheme=${requestUri.scheme} " +
                            "pendingPathLen=${pending.requestUri.path?.length ?: -1} " +
                            "openPathLen=${requestUri.path?.length ?: -1} " +
                            "pendingQueryLen=${pending.requestUri.query?.length ?: -1} " +
                            "openQueryLen=${requestUri.query?.length ?: -1} " +
                            "pendingUriLen=${pending.requestUri.toString().length} " +
                            "openUriLen=${requestUri.toString().length}"
                    )
                    teardownSessionLocked(pending, poolCap)
                }
                // Fresh session, fresh clamp state for the HUD.
                resetClampMirrors()
                hudHedgeRestarts = 0
                hudHedgeExhausted = false
                val created = ChunkSession(requestUri, requestHeaders, chunkSz, chunkCap, prefetchWindow)
                currentChunkSession = created
                return created
            }
        }

        /**
         * Explicit teardown, wired into PlayerMediaSourceFactory.shutdown() so
         * chunk buffers and downloads never outlive the player. Buffers are
         * freed directly (poolCap = 0), playback is over.
         */
        /** Like [releaseRetainedSession] for the live session only; a pre-started one stays. */
        internal fun releaseRetainedSessionIfIdle() {
            synchronized(sessionLock) {
                if (activeInstances.get() > 0) return
                currentChunkSession?.let { teardownSessionLocked(it, poolCap = 0) }
                currentChunkSession = null
            }
            clearGlobalPool()
        }

        internal fun trimRetainedSession(keepAhead: Int) {
            val chunkSz = synchronized(sessionLock) {
                val session = currentChunkSession ?: return
                val reader = session.lastReadChunkIndex
                if (reader < 0L) return
                synchronized(session) {
                    session.futures.keys.filter { it < reader || it > reader + keepAhead }
                        .forEach { evictFuture(session, it, poolCap = 0) }
                }
                session.chunkSize
            }
            drainIdleBuffers(chunkSz)
        }

        internal fun releaseRetainedSession() {
            synchronized(sessionLock) {
                currentChunkSession?.let { teardownSessionLocked(it, poolCap = 0) }
                currentChunkSession = null
                // A pre-start that was never adopted must not outlive the player.
                pendingChunkSession?.let { teardownSessionLocked(it, poolCap = 0) }
                pendingChunkSession = null
            }
            clearGlobalPool()
        }

        /**
         * Create the pending session for [requestUri] if one is not already
         * usable. Returns null when a pre-start would be pointless (the live session
         * already serves this URI) or unsafe (a pending session for this URI exists).
         */
        fun obtainPendingSession(
            requestUri: Uri,
            requestHeaders: Map<String, String>,
            chunkSz: Long,
            chunkCap: Int,
            poolCap: Int,
            prefetchWindow: Int
        ): ChunkSession? {
            synchronized(sessionLock) {
                check(!released) { "Diagnostic session is closed" }
                retiredChunkSizes.remove(chunkSz)
                val live = currentChunkSession
                if (live != null && !live.abandoned.get() && live.requestUri == requestUri &&
                    live.chunkSize == chunkSz && live.requestHeaders == requestHeaders
                ) {
                    return null
                }
                val existingPending = pendingChunkSession
                if (existingPending != null) {
                    if (!existingPending.abandoned.get() && existingPending.requestUri == requestUri &&
                        existingPending.chunkSize == chunkSz && existingPending.requestHeaders == requestHeaders
                    ) {
                        return null
                    }
                    teardownSessionLocked(existingPending, poolCap)
                }
                val created = ChunkSession(requestUri, requestHeaders, chunkSz, chunkCap, prefetchWindow)
                pendingChunkSession = created
                return created
            }
        }

        /**
         * Free every IDLE
         * recycled buffer pooled for [chunkSize]. Called when a chunk-buffer
         * allocation OOMs (relieve native pressure so the process survives) and
         * when a sweep cell fails (so a dead cell's recycled buffers never
         * carry into the next cell). Idle buffers only: in-flight buffers are
         * owned by their session's futures and are torn down by the session.
         */
        internal fun drainIdleBuffers(chunkSize: Long) {
            for (size in listOf(chunkSize, SEGMENT_BYTES.toLong())) {
                val pool = globalBufferPool[size] ?: continue
                while (true) {
                    freeBuffer(pool.pollLast() ?: break)
                }
            }
        }

        fun takeSegment(): PooledBuffer {
            globalBufferPool[SEGMENT_BYTES.toLong()]?.pollLast()?.let { idle ->
                idle.byteBuffer.clear()
                return PooledBuffer(idle.allocation, idle.byteBuffer)
            }
            val allocation = allocateChunk(SEGMENT_BYTES)
            val allocBuffer = allocation?.buffer
            return if (allocation != null && allocBuffer != null) PooledBuffer(allocation, allocBuffer)
            else PooledBuffer(null, ByteBuffer.allocate(SEGMENT_BYTES))
        }

        @Synchronized fun giveSegment(segment: PooledBuffer, cap: Int) {
            val pool = globalBufferPool.computeIfAbsent(SEGMENT_BYTES.toLong()) { ConcurrentLinkedDeque() }
            if (!released && pool.size < cap) pool.offerLast(segment) else freeBuffer(segment)
        }

        /**
         * Enforce the session's chunk cap with touch-LRU eviction. Never
         * evicts [protectIndex] (the chunk being read) or anything touched in
         * the last EVICTION_TOUCH_GUARD_MS.
         */
        fun enforceSessionCap(session: ChunkSession, protectIndex: Long, poolCap: Int, cap: Int = session.chunkCap) {
            if (session.futures.size <= cap) return
            synchronized(session) {
                while (session.futures.size > cap) {
                    val now = SystemClock.uptimeMillis()
                    // The 2 s touch guard makes the cap soft: when every
                    // candidate is recently touched the loop bails and an active
                    // file holds ~cap+2-3 chunks (~50% overshoot on low-RAM
                    // tiers). Beyond cap+2 the ceiling is hard: evict the
                    // oldest-touched candidate regardless of the guard.
                    val hardOver = session.futures.size > cap + 2
                    // Position-aware victim selection. Touch-LRU alone would
                    // systematically evict PREFETCHED chunks: ahead-of-reader
                    // entries are touched only at creation, so once the reader
                    // is a few chunks past that moment they are always the
                    // oldest-touched entries, evicted seconds before the
                    // reader arrives and then re-downloaded in full. Prefer
                    // chunks BEHIND the read cursor
                    // (touch-LRU among them); only when none are eligible fall
                    // back to the FARTHEST-ahead chunk, which is needed latest.
                    val readerIdx = session.lastReadChunkIndex
                    val eligible = session.futures.keys
                        .filter { it != protectIndex }
                        .filter { hardOver || now - (session.lastTouch[it] ?: 0L) >= EVICTION_TOUCH_GUARD_MS }
                    // The ENTIRE in-flight prefetch window reader+1..reader+prefetchWindow
                    // is about to be read within seconds, so it is excluded from
                    // eviction, and there is no last-ditch fallback that ignores the
                    // exclusion: when only in-window chunks remain, bail (as
                    // the soft-cap does) and let the pool sit transiently at cap+2 rather
                    // than evict-then-refetch. Behind and beyond-window chunks stay
                    // evictable (hardOver drops the touch guard for them), so the pool
                    // stays bounded; beyond-window chunks (stale prefetch after a
                    // backward seek) are the farthest-ahead evictable and go first.
                    val nearAheadFloor = if (readerIdx >= 0L) readerIdx else Long.MIN_VALUE
                    val nearAheadCeil = if (readerIdx >= 0L) readerIdx + session.prefetchWindow else Long.MIN_VALUE
                    val evictable = eligible.filter { it < nearAheadFloor || it > nearAheadCeil }
                    val victim = evictable
                        .filter { readerIdx >= 0L && it < readerIdx }
                        .minByOrNull { session.lastTouch[it] ?: 0L }
                        ?: evictable.maxOrNull()
                        ?: return
                    evictFuture(session, victim, poolCap)
                }
            }
        }

        @Synchronized fun clearGlobalPool() {
            globalBufferPool.values.forEach { pool ->
                while (true) {
                    freeBuffer(pool.pollFirst() ?: break)
                }
            }
            globalBufferPool.clear()
            Log.d(TAG, if (isolated) "Cleared diagnostic buffer pool" else "Cleared idle playback buffer pool")
        }
    
    }

    private val resources = if (isolateSession) {
        Resources(true, prefetchDepth(prefetchDepthChunks, parallelConnections).coerceIn(1, MAX_DIAGNOSTIC_DOWNLOADS))
    } else {
        playbackResources
    }

    internal val diagnosticClampTrips: Int get() { check(isolateSession); return resources.hudClampTrips }
    internal val diagnosticWorkersStopped: Boolean get() {
        check(isolateSession)
        return resources.released && (!resources.executorDelegate.isInitialized() || resources.executorDelegate.value.isTerminated)
    }


    init {
        resources.activeInstances.incrementAndGet()
    }

    /** Chunk memory; readers lease it per copy and it is recycled once, after release and the last lease. */
    private open class PooledBuffer(
        val allocation: Allocation?,
        val byteBuffer: ByteBuffer
    ) {
        // Bit 0 is the owner's reference, every lease adds 2.
        private val refs = AtomicInteger(1)
        private val recycler = java.util.concurrent.atomic.AtomicReference<((PooledBuffer) -> Unit)?>(null)

        fun lease(): Boolean {
            while (true) {
                val current = refs.get()
                if ((current and 1) == 0) return false
                if (refs.compareAndSet(current, current + 2)) return true
            }
        }

        fun endLease() {
            if (refs.addAndGet(-2) == 0) recycler.get()?.invoke(this)
        }

        fun release(recycle: (PooledBuffer) -> Unit) {
            if (!recycler.compareAndSet(null, recycle)) return
            if (refs.decrementAndGet() == 0) recycle(this)
        }

        inline fun <T> leased(block: (PooledBuffer) -> T): T? {
            if (!lease()) return null
            try {
                return block(this)
            } finally {
                endLease()
            }
        }

        // A reader takes the spare view for the length of one copy; a second reader at the same time makes its own.
        private val spareView = java.util.concurrent.atomic.AtomicReference<ByteBuffer?>(null)

        private inline fun <T> readView(block: (ByteBuffer) -> T): T {
            val view = spareView.getAndSet(null) ?: byteBuffer.duplicate()
            try {
                view.limit(view.capacity())
                return block(view)
            } finally {
                spareView.set(view)
            }
        }

        open fun copyOut(offset: Int, dst: ByteArray, dstOffset: Int, length: Int) {
            readView { view ->
                view.position(offset)
                view.get(dst, dstOffset, length)
            }
        }

        open fun copyOut(offset: Int, dst: ByteBuffer, length: Int) {
            readView { view ->
                view.position(offset)
                view.limit(offset + length)
                dst.put(view)
            }
        }
    }

    /** Piece memory taken in [SEGMENT_BYTES] parts as its bytes arrive; [held] counts what the session's pieces hold. */
    private class SegmentedBuffer(
        val capacityBytes: Int,
        private val take: () -> PooledBuffer,
        private val give: (PooledBuffer) -> Unit,
        private val held: java.util.concurrent.atomic.AtomicLong,
        private val onFreed: () -> Unit = {}
    ) : PooledBuffer(null, EMPTY_BUFFER) {
        private val segments = java.util.concurrent.atomic.AtomicReferenceArray<PooledBuffer?>(
            ((capacityBytes + SEGMENT_BYTES - 1) / SEGMENT_BYTES).coerceAtLeast(1)
        )

        fun hasSegment(offset: Int): Boolean = segments.get(offset / SEGMENT_BYTES) != null

        /** Writer only: the part holding [offset], taken now when missing, positioned at [offset]. */
        fun writeView(offset: Int): ByteBuffer {
            val index = offset / SEGMENT_BYTES
            val segment = segments.get(index) ?: take().also {
                held.addAndGet(it.byteBuffer.capacity().toLong())
                segments.set(index, it)
            }
            return segment.byteBuffer.also { it.position(offset - index * SEGMENT_BYTES) }
        }

        override fun copyOut(offset: Int, dst: ByteArray, dstOffset: Int, length: Int) {
            var at = offset
            var to = dstOffset
            var left = length
            while (left > 0) {
                val inPart = at % SEGMENT_BYTES
                val n = minOf(left, SEGMENT_BYTES - inPart)
                segments.get(at / SEGMENT_BYTES)!!.copyOut(inPart, dst, to, n)
                at += n
                to += n
                left -= n
            }
        }

        override fun copyOut(offset: Int, dst: ByteBuffer, length: Int) {
            var at = offset
            var left = length
            while (left > 0) {
                val inPart = at % SEGMENT_BYTES
                val n = minOf(left, SEGMENT_BYTES - inPart)
                segments.get(at / SEGMENT_BYTES)!!.copyOut(inPart, dst, n)
                at += n
                left -= n
            }
        }

        fun freeSegments() {
            var freed = false
            for (i in 0 until segments.length()) {
                val segment = segments.getAndSet(i, null) ?: continue
                held.addAndGet(-segment.byteBuffer.capacity().toLong())
                give(segment)
                freed = true
            }
            if (freed) onFreed()
        }
    }

    private class DownloadedChunk(
        val buffer: PooledBuffer,
        val size: Int,
        val firstByteAtMs: Long = -1L,
        val firstReadBytes: Int = 0,
        val doneAtMs: Long = -1L,
        // Waited while the pieces held their share: its timing says nothing about the server.
        val paused: Boolean = false
    )

    private class LookUpTurn(val result: CompletableFuture<Uri>, val asks: Boolean)

    /**
     * Live view of a chunk download in flight (progressive reads).
     * [watermark] is volatile and written AFTER the bytes below it have
     * landed, so a reader that loads it may safely read [0, watermark)
     * through a duplicate() view, under a lease on [buffer].
     * Success never releases here -- the buffer graduates into the
     * completed DownloadedChunk and follows the session lifecycle.
     * Bytes below the watermark are identical across retry attempts
     * (same HTTP range of the same file), so a reader that consumed
     * from a failed attempt has still served correct data.
     */
    private class InFlightChunk(buffer: PooledBuffer) {
        @Volatile var buffer: PooledBuffer? = buffer
        @Volatile var watermark: Int = 0
    }

    internal data class BootstrapCacheEntry(
        val requestUri: Uri,
        val startPosition: Long,
        val resolvedUri: Uri?,
        val openLength: Long,
        val totalFileLength: Long,
        val bootstrapData: ByteArray,
        val bootstrapSize: Int,
        val createdAtUptimeMs: Long,
        val prewarmIdentity: PlaybackPrewarmIdentity? = null
    )

    private var resolvedUri: Uri? = null
    private var originalDataSpec: DataSpec? = null
    private var totalFileLength: Long = C.LENGTH_UNSET.toLong()
    private var position: Long = 0
    private var bytesRemaining: Long = C.LENGTH_UNSET.toLong()
    private val closed = AtomicBoolean(false)

    // The in-flight window. All three caps below derive from this single
    // value so they cannot drift out of step.
    private val effectivePrefetchDepth: Int = prefetchDepth(prefetchDepthChunks, parallelConnections)

    // Buffer pool limit. Idle-buffer recycling headroom above the in-flight window.
    private val maxPoolSize = effectivePrefetchDepth + 2

    // Parts are kept for reuse up to what the pool keeps of whole pieces, so memory is not taken and given back
    // 1 MB at a time while a film plays.
    private val segmentPoolCap: Int =
        (maxPoolSize.toLong() * chunkSize / SEGMENT_BYTES).toInt().coerceAtLeast(SEGMENT_POOL_MIN)

    // Current chunk being served to ExoPlayer
    private var currentChunk: DownloadedChunk? = null
    private var currentChunkIndex: Long = -1
    private var currentChunkReadOffset: Int = 0
    private var bootstrapPrefetchDeferred: Boolean = false
    private var bootstrapChunk: DownloadedChunk? = null
    private var bootstrapStartPosition: Long = C.TIME_UNSET
    private var continuationSource: OkHttpDataSource? = null
    private var continuationEndPositionExclusive: Long = C.TIME_UNSET
    // Set on a warm reopen whose target chunk is NOT already held, so the
    // first read serves from a bounded single-connection GET rather than blocking
    // on a whole aligned chunk. Instance-local; never shared across instances.
    private var pendingContinuationOpen: Boolean = false

    private val transferListeners = mutableListOf<TransferListener>()

    // Fallback: if parallel mode fails, use a single upstream DataSource
    private var fallbackSource: OkHttpDataSource? = null
    private val ledgerListener = LedgerTransferListener()

    // Shared download session (null on subtitle/fallback paths).
    private var session: Resources.ChunkSession? = null
    // Earned prefetch: lookahead is granted only after this open has
    // demonstrated sequential consumption, so side-cursor opens (tiny reads,
    // then reopen) never trigger the connections+1 chunk prefetch fan-out.
    private var bytesServedThisOpen: Long = 0L
    private var inFlightServeLogged: Boolean = false
    // Memory-tiered chunk cap: low-RAM devices keep a ceiling of
    // connections + 2; high-RAM gets two extra chunks of LRU headroom.
    private val sessionChunkCap: Int = effectivePrefetchDepth +
        if (com.nuvio.tv.ui.screens.settings.MemoryBudget.isLowRamTier) 2 else 4

    override fun open(dataSpec: DataSpec): Long {
        if (resources.released) throw IOException("Diagnostic session is closed")
        val isSubtitle = dataSpec.uri.getQueryParameter("nuvio_type") == "subtitle"
        // Construction owns the first reservation. Every close releases it;
        // reopening this same DataSource must reacquire it before either path.
        val wasClosed = closed.getAndSet(false)
        if (wasClosed) resources.activeInstances.incrementAndGet()
        if (isSubtitle) {
            resetLocalReadState()
            
            // Clean the custom query parameter from the subtitle URL before requesting
            val cleanedUri = dataSpec.uri.buildUpon().clearQuery().let { builder ->
                dataSpec.uri.queryParameterNames.forEach { name ->
                    if (name != "nuvio_type") {
                        dataSpec.uri.getQueryParameters(name).forEach { value ->
                            builder.appendQueryParameter(name, value)
                        }
                    }
                }
                builder.build()
            }
            val cleanedDataSpec = dataSpec.withUri(cleanedUri)
            
            val probeSource = upstreamFactory.createDataSource()
            transferListeners.forEach { probeSource.addTransferListener(it) }
            probeSource.addTransferListener(ledgerListener)
            fallbackSource = probeSource
            val openLength = probeSource.open(cleanedDataSpec)
            
            totalFileLength = openLength
            bytesRemaining = openLength
            position = dataSpec.position
            
            Log.d(TAG, "Subtitle request detected. Bypassing parallel mode for single-connection download: ${cleanedUri.host}")
            return openLength
        }

        val isReopen = !wasClosed && 
                       fallbackSource == null &&
                       originalDataSpec != null && 
                       originalDataSpec?.uri == dataSpec.uri && 
                       position == dataSpec.position &&
                       totalFileLength != C.LENGTH_UNSET.toLong()

        if (isReopen) {
            position = dataSpec.position
            bytesRemaining = (totalFileLength - position).coerceAtLeast(0L)
            bootstrapPrefetchDeferred = true
            Log.d(TAG, "Reusing active ParallelRangeDataSource for reopen at $position, file=${totalFileLength / 1024 / 1024}MB")
            return bytesRemaining
        }

        val prewarmIdentity = if (!isolateSession && dataSpec.httpMethod == DataSpec.HTTP_METHOD_GET) {
            PlaybackPrewarmIdentity.from(dataSpec.uri.toString(), defaultRequestHeaders, dataSpec.httpRequestHeaders)
        } else null
        originalDataSpec = dataSpec
        position = dataSpec.position
        bootstrapPrefetchDeferred = false
        bootstrapChunk = null
        bootstrapStartPosition = C.TIME_UNSET
        continuationSource?.close()
        continuationSource = null
        continuationEndPositionExclusive = C.TIME_UNSET
        pendingContinuationOpen = false
        // A fresh open must not inherit fallback/length state from a previous
        // open on this instance; every path below re-establishes both fields.
        fallbackSource?.close()
        fallbackSource = null
        totalFileLength = C.LENGTH_UNSET.toLong()
        bytesRemaining = C.LENGTH_UNSET.toLong()

        resetLocalReadState()
        bytesServedThisOpen = 0L

        // Attach to the shared download session for this URI. Downloads
        // (done AND in-flight) belong to the session and survive the
        // close/open cycle ExoPlayer performs on every seek. When the session
        // is warm (length + resolved URI known) the probe request is skipped.
        // If an adopted CDN URL has expired, chunk downloads fail, ExoPlayer
        // re-opens, the failed futures are gone, and downloads retry against
        // the session's URI, with the full probe as the eventual fallback via
        // session teardown on TTL.
        val attachedSession = resources.obtainSession(dataSpec.uri, dataSpec.httpRequestHeaders, chunkSize, sessionChunkCap, maxPoolSize, effectivePrefetchDepth)
        session = attachedSession
        if (!isolateSession && takeUserSeek()) dropLeftBySeek(attachedSession, dataSpec.position / chunkSize)
        val warmLength = attachedSession.totalLength
        if (warmLength > 0L && dataSpec.position in 0 until warmLength) {
            resolvedUri = attachedSession.requestTarget()
            onResolvedUri(resolvedUri)
            totalFileLength = warmLength
            val remaining = (totalFileLength - position).coerceAtLeast(0L)
            bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                minOf(dataSpec.length, remaining)
            } else {
                remaining
            }
            bootstrapPrefetchDeferred = true
            // The tail seek may land inside the prefetched Cues
            // window. Serving it through the existing bootstrap-window
            // machinery (bootstrapChunk at an arbitrary start position)
            // replaces the bounded continuation GET (often 700-1,050 ms)
            // with a heap read. A miss takes the normal path.
            val cachedTail = if (isolateSession) null else PrefetchWindowStore.peekTail(
                dataSpec.uri, position, prewarmIdentity, totalFileLength
            )
            if (cachedTail != null) {
                bootstrapChunk = DownloadedChunk(
                    PooledBuffer(null, ByteBuffer.wrap(cachedTail.bootstrapData)),
                    cachedTail.bootstrapSize
                )
                bootstrapStartPosition = cachedTail.startPosition
                pendingContinuationOpen = false
            } else {
                // Only when the target chunk is not already held. A reopen INTO a
                // held chunk (the fill reopen after the tail seek) keeps the
                // instant path, which is what preserves its ~600 ms buffered head
                // start at first frame.
                pendingContinuationOpen = allowContinuationReopen &&
                    attachedSession.futures[position / chunkSize] == null
            }
            Log.d(
                TAG,
                "Attached to warm session for reopen at $position, " +
                    "file=${totalFileLength / 1024 / 1024}MB, held=${attachedSession.futures.size} chunk(s) (probe skipped)"
            )
            return bytesRemaining
        }

        // The prefetch-time prewarm may have captured this exact
        // window (same URI, position 0, 256 KiB) plus the total length --
        // in which case the probe round trip is skipped entirely.
        (if (isolateSession) null else (consumeBootstrapCache(dataSpec) ?: PrefetchWindowStore.consumeHead(dataSpec, prewarmIdentity)))?.let { cached ->
            resolvedUri = cached.resolvedUri
            onResolvedUri(resolvedUri)
            totalFileLength = cached.totalFileLength
            bytesRemaining = cached.openLength
            bootstrapChunk = DownloadedChunk(PooledBuffer(null, ByteBuffer.wrap(cached.bootstrapData)), cached.bootstrapSize)
            bootstrapStartPosition = cached.startPosition
            bootstrapPrefetchDeferred = true
            // Publish to the session so the next reopen is warm.
            attachedSession.resolvedUri = resolvedUri
            attachedSession.totalLength = totalFileLength
            Log.d(
                TAG,
                "Reusing bootstrap window for immediate reopen at ${cached.startPosition}, " +
                    "file=${totalFileLength / 1024 / 1024}MB, resolved=${resolvedUri?.host}"
            )
            return cached.openLength
        }

        // Open first connection to determine total length and capture the resolved (redirected) URL
        val probeSource: OkHttpDataSource = upstreamFactory.createDataSource()
        transferListeners.forEach { probeSource.addTransferListener(it) }
        probeSource.addTransferListener(ledgerListener)

        // Attributes the cost of this open. Logging only.
        val diagOpenStartMs = SystemClock.uptimeMillis()
        var diagProbeOpenMs = -1L
        var diagBootstrapMs = -1L

        // Bounded probe. ExoPlayer's initial spec is position=0/length=UNSET, which
        // OkHttpDataSource sends with NO Range header: a full-file 200.
        // Closing that with ~1 GB unread DISCARDS the socket, so the tail
        // continuation and chunk 0 would each pay a fresh cold connect.
        // Requesting exactly the bootstrap window instead makes the body
        // fully consumable, so close() returns the connection to the shared
        // pool; the total length comes from the 206's Content-Range. A server
        // that ignores Range (200: no Content-Range) gets one fresh unbounded
        // reopen and then the usual sniff + single-connection fallback.
        var openLength: Long
        var boundedRangeHonoured = false
        val boundedProbeLength = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            minOf(dataSpec.length, BOOTSTRAP_READ_BYTES)
        } else {
            BOOTSTRAP_READ_BYTES
        }
        try {
            val boundedSpec = dataSpec.buildUpon().setLength(boundedProbeLength).build()
            probeSource.open(boundedSpec)
            diagProbeOpenMs = SystemClock.uptimeMillis() - diagOpenStartMs
            resolvedUri = probeSource.uri // Final URL after redirects (CDN URL)
            onResolvedUri(resolvedUri)
            val probeTotal = ParallelRangeProbeResponse.validatedTotal(
                boundedSpec, probeSource.responseCode, probeSource.responseHeaders
            )
            if (probeTotal != C.LENGTH_UNSET.toLong()) {
                boundedRangeHonoured = true
                val remaining = (probeTotal - dataSpec.position).coerceAtLeast(0L)
                openLength = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                    minOf(dataSpec.length, remaining)
                } else {
                    remaining
                }
            } else {
                // Range not honoured: fall back to a single unbounded
                // open so the sniff below sees the whole response.
                Log.w(TAG, "Bounded probe got no Content-Range; reopening unbounded")
                try { probeSource.close() } catch (_: Exception) {}
                openLength = probeSource.open(dataSpec)
                // This reopen remains single-connection. If it is a 206, its
                // bytes must still match the original request before any read.
                ParallelRangeProbeResponse.validatedTotal(dataSpec, probeSource.responseCode, probeSource.responseHeaders)
                diagProbeOpenMs = SystemClock.uptimeMillis() - diagOpenStartMs
            }
        } catch (e: Exception) {
            // Preserve the failure that rejected this startup attempt, even if
            // closing its local response also fails (as on bootstrap errors).
            try {
                probeSource.close()
            } catch (closeFailure: Throwable) {
                if (closeFailure !== e) e.addSuppressed(closeFailure)
            }
            throw e
        }

        // Only the actual bounded 206 proves range support. An ignored Range
        // stays single even when the ordinary response advertises Accept-Ranges.
        if (openLength == C.LENGTH_UNSET.toLong() || !boundedRangeHonoured) {
            Log.w(TAG, "Falling back to single connection (length=$openLength, rangeHonoured=$boundedRangeHonoured)")
            fallbackSource = probeSource
            // Keep state consistent with the subtitle fallback path (position is
            // already set above): known length gives a real total, unknown stays unset.
            totalFileLength = if (openLength != C.LENGTH_UNSET.toLong()) {
                position + openLength
            } else {
                C.LENGTH_UNSET.toLong()
            }
            bytesRemaining = openLength
            return openLength
        }

        totalFileLength = position + openLength
        bytesRemaining = openLength

        // Publish to the session so every subsequent reopen is warm.
        attachedSession.resolvedUri = resolvedUri
        attachedSession.totalLength = totalFileLength

        Log.d(TAG, "Parallel mode: ${parallelConnections} connections, ${chunkSize / 1024 / 1024}MB chunks, " +
                "file=${totalFileLength / 1024 / 1024}MB, resolved=${resolvedUri?.host}")

        // This probe stays local on the parallel path. Outer close() only owns
        // fallback/continuation sources, so failed bootstrap reads or cache publication
        // must close it here. Keep the original failure if cleanup also fails.
        var probeCloseAttempted = false
        var bootstrapFailure: Throwable? = null
        try {
            // Reuse a small probe window immediately for both startup and large seek reopens.
            val firstChunkIndex = position / chunkSize
            if (openLength > 0L) {
                val bootstrapBytes = minOf(minOf(chunkSize, BOOTSTRAP_READ_BYTES), openLength).toInt()
                val diagReadStartMs = SystemClock.uptimeMillis()
                val chunk = readBootstrapChunk(probeSource, bootstrapBytes)
                diagBootstrapMs = SystemClock.uptimeMillis() - diagReadStartMs
                bootstrapChunk = chunk
                bootstrapStartPosition = position
                // Avoid startup churn from immediate background fetches during repeated startup opens,
                // but do not redownload the active seek chunk from its start.
                bootstrapPrefetchDeferred = true
                if (position == 0L) {
                    if (!isolateSession) updateBootstrapCache(
                        BootstrapCacheEntry(
                            requestUri = dataSpec.uri,
                            startPosition = dataSpec.position,
                            resolvedUri = resolvedUri,
                            openLength = openLength,
                            totalFileLength = totalFileLength,
                            bootstrapData = chunk.buffer.byteBuffer.array(),
                            bootstrapSize = chunk.size,
                            createdAtUptimeMs = SystemClock.uptimeMillis()
                        )
                    )
                }
                val diagCloseStartMs = SystemClock.uptimeMillis()
                probeCloseAttempted = true
                probeSource.close()
                Log.i(
                    TAG,
                    "OPEN_SPLIT pos=$position probeOpen=${diagProbeOpenMs}ms " +
                        "bootstrapRead=${diagBootstrapMs}ms bootstrapBytes=${chunk.size} " +
                        "close=${SystemClock.uptimeMillis() - diagCloseStartMs}ms " +
                        "total=${SystemClock.uptimeMillis() - diagOpenStartMs}ms"
                )
            } else {
                val diagCloseStartMs = SystemClock.uptimeMillis()
                probeCloseAttempted = true
                probeSource.close()
                Log.i(
                    TAG,
                    "OPEN_SPLIT pos=$position probeOpen=${diagProbeOpenMs}ms bootstrapRead=n/a " +
                        "close=${SystemClock.uptimeMillis() - diagCloseStartMs}ms " +
                        "total=${SystemClock.uptimeMillis() - diagOpenStartMs}ms"
                )
            }

        } catch (failure: Throwable) {
            bootstrapFailure = failure
            throw failure
        } finally {
            if (!probeCloseAttempted) {
                val failure = bootstrapFailure
                if (failure == null) {
                    probeSource.close()
                } else {
                    try {
                        probeSource.close()
                    } catch (closeFailure: Throwable) {
                        if (closeFailure !== failure) failure.addSuppressed(closeFailure)
                    }
                }
            }
        }

        return openLength
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        while (true) {
            val read = readOnce(buffer, offset, length)
            if (read != LEASE_LOST) return read
        }
    }

    private fun readOnce(buffer: ByteArray, offset: Int, length: Int): Int {
        com.nuvio.tv.core.util.TtffTrace.sourceReads++
        // Fallback mode: delegate to single upstream
        fallbackSource?.let { source ->
            val read = source.read(buffer, offset, length)
            if (read > 0) {
                position += read
                bytesRemaining = (bytesRemaining - read).coerceAtLeast(0L)
            }
            return read
        }

        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val toRead = minOf(length.toLong(), bytesRemaining).toInt()

        val chunkIndex = position / chunkSize
        val bootstrap = bootstrapChunk
        if (currentChunk == null &&
            bootstrap != null &&
            position >= bootstrapStartPosition &&
            position < bootstrapStartPosition + bootstrap.size
        ) {
            currentChunk = bootstrap
            currentChunkIndex = chunkIndex
            currentChunkReadOffset = (position - bootstrapStartPosition).toInt()
        }

        // Materialise BEFORE the deferred schedule below. Placed after it,
        // scheduleChunks() would still see a null continuation and schedule the
        // whole aligned chunk -- paying the amplified fetch while merely hiding
        // it from the reader.
        if (pendingContinuationOpen && currentChunk == null && continuationSource == null) {
            materialisePendingContinuation()
        }

        if (bootstrapPrefetchDeferred && shouldAllowBackgroundPrefetch()) {
            bootstrapPrefetchDeferred = false
            scheduleChunks()
        }

        continuationSource?.let { source ->
            if (position < continuationEndPositionExclusive &&
                bytesRemaining > 0L &&
                (bootstrap == null || position >= bootstrapStartPosition + bootstrap.size)
            ) {
                val read = source.read(buffer, offset, toRead)
                if (read > 0) {
                    position += read
                    bytesRemaining -= read
                    if (position >= continuationEndPositionExclusive) {
                        source.close()
                        continuationSource = null
                        continuationEndPositionExclusive = C.TIME_UNSET
                        scheduleChunks()
                    }
                    return read
                }
                if (read == C.RESULT_END_OF_INPUT || position >= continuationEndPositionExclusive) {
                    source.close()
                    continuationSource = null
                    continuationEndPositionExclusive = C.TIME_UNSET
                    scheduleChunks()
                }
            } else if (position >= continuationEndPositionExclusive || bytesRemaining <= 0L) {
                source.close()
                continuationSource = null
                continuationEndPositionExclusive = C.TIME_UNSET
            }
        }

        // Load the chunk for the current position
        if (currentChunkIndex != chunkIndex || currentChunk == null) {
            val activeSession = session ?: return C.RESULT_END_OF_INPUT
            ensureChunkScheduled(chunkIndex)
            // Speed-up downloads start with the piece they follow, not when it completes: a reader waiting on
            // that piece would otherwise only ever have one download running.
            if (depthLimit != null) scheduleChunks()
            val future = activeSession.futures[chunkIndex] ?: return C.RESULT_END_OF_INPUT
            activeSession.noteRead(chunkIndex)
            // Progressive reads: blocking on chunk 0's LAST byte would hold
            // read() back while the bytes it needs have long been on the
            // device. Serve below the in-flight watermark instead; when nothing
            // is available yet, fall through to the blocking path
            // (RS_CHUNK_WAIT still prices it).
            if (!future.isDone) {
                val served = awaitServeFromInFlight(activeSession, chunkIndex, future, buffer, offset, toRead)
                if (served > 0) return served
            }
            try {
                // RS_CHUNK_WAIT: read() blocks on the WHOLE chunk future, so
                // ExoPlayer sees nothing of an 8 MB chunk until all 8 MB have
                // landed. The count and highest pos logged before
                // first_frame_rendered show whether this gates the first frame.
                // preDone separates a real stall from an already-complete chunk.
                // site= distinguishes the two read() overloads.
                val blockT0 = SystemClock.elapsedRealtime()
                val preDone = future.isDone
                currentChunk = future.get(60, TimeUnit.SECONDS)
                Log.i(
                    TAG,
                    "RS_CHUNK_WAIT site=bytearray pos=$position chunk=$chunkIndex " +
                        "waitMs=${SystemClock.elapsedRealtime() - blockT0} preDone=$preDone"
                )
            } catch (e: Exception) {
                if (closed.get()) return C.RESULT_END_OF_INPUT
                // A failed download is not retryable by waiting: drop
                // the future so the next attempt schedules a fresh one.
                // Cancel before dropping: an orphaned in-flight
                // download otherwise completes into a pooled native buffer
                // nothing will ever release. Ownership-gated on the two-arg
                // remove so a future already evicted/replaced by another
                // thread is never double-released (evictFuture's pattern).
                if (activeSession.futures.remove(chunkIndex, future)) {
                    activeSession.forgetTouch(chunkIndex)
                    if (!future.cancel(true) && future.isDone && !future.isCancelled) {
                        try {
                            resources.releaseSessionBuffer(future.get().buffer, activeSession.chunkSize, maxPoolSize)
                        } catch (_: Exception) {
                        }
                    }
                }
                throw PlaybackNetworkFailure.chunkFailure("Failed to download chunk $chunkIndex", e, originalDataSpec)
            }
            currentChunkIndex = chunkIndex
            currentChunkReadOffset = (position % chunkSize).toInt()

            // LRU cap enforcement lives in ensureChunkScheduled; behind-
            // chunks are not eagerly released (they serve the backward
            // cursors on scatter-read files).
            scheduleChunks()
        }

        val chunk = currentChunk ?: return C.RESULT_END_OF_INPUT
        val available = chunk.size - currentChunkReadOffset
        if (available <= 0) {
            val wasBootstrap = chunk === bootstrapChunk
            if (wasBootstrap) {
                bootstrapChunk = null
                bootstrapStartPosition = C.TIME_UNSET
            }
            currentChunk = null
            if (!wasBootstrap && position / chunkSize == currentChunkIndex) return C.RESULT_END_OF_INPUT
            return read(buffer, offset, length)
        }

        val readSize = minOf(toRead, available)
        // Session chunks are shared across instances:
        // mutating the shared buffer's position races concurrent readers of
        // the same chunk. Read through a duplicate, as the ByteBuffer path does.
        val copied = chunk.buffer.leased { it.copyOut(currentChunkReadOffset, buffer, offset, readSize) }
        if (copied == null) {
            // Evicted since it was taken: fetch it again through the session.
            currentChunk = null
            currentChunkIndex = -1
            return LEASE_LOST
        }
        currentChunkReadOffset += readSize
        position += readSize
        bytesRemaining -= readSize
        bytesServedThisOpen += readSize
        session?.noteRead(chunkIndex)

        return readSize
    }

    /**
     * Serve a warm reopen into an un-fetched region from a plain
     * bounded GET instead of a whole aligned chunk. On a Matroska tail seek the
     * reader's first needed byte can sit 7 MB into an 8 MB chunk, so the chunk
     * path would fetch the whole chunk to deliver the last 1-2 MB. Bounded to
     * the chunk boundary so scheduleChunks() takes the file from there; on any
     * failure the flag is already cleared and the chunk path serves the read.
     */
    private fun materialisePendingContinuation() {
        pendingContinuationOpen = false
        if (bytesRemaining <= 0L) return
        val activeSession = session ?: return
        val boundary = ((position / chunkSize) + 1L) * chunkSize
        val end = minOf(boundary, position + bytesRemaining)
        val length = end - position
        if (length <= 0L) return
        val source = upstreamFactory.createDataSource()
        transferListeners.forEach { source.addTransferListener(it) }
        source.addTransferListener(ledgerListener)
        val target = activeSession.requestTarget()
        try {
            openTarget(
                activeSession,
                source,
                DataSpec.Builder()
                    .setUri(target)
                    .setPosition(position)
                    .setLength(length)
                    .build(),
                activeSession.totalLength
            ) { closed.get() || activeSession.abandoned.get() }
        } catch (e: Exception) {
            try { source.close() } catch (_: Exception) {}
            // Another try on an address that could not be reached only adds another full wait,
            // so the load fails now unless the address was just dropped for a fresh look-up.
            if (PlaybackNetworkFailure.isConnectFailure(e) && activeSession.requestTarget() == target) {
                throw PlaybackNetworkFailure.chunkFailure("Continuation connect failed at $position", e, originalDataSpec)
            }
            if (e.isTransientInterruption()) throw InterruptedIOException("Continuation open interrupted at $position")
            Log.w(TAG, "Continuation open failed at $position; using chunk path: ${e.message}")
            return
        }
        continuationSource = source
        continuationEndPositionExclusive = end
        // Keep the eviction cursor with the reader while the continuation runs.
        activeSession.noteRead(position / chunkSize)
        Log.d(TAG, "Continuation open at $position, $length bytes to boundary $end")
    }

    private fun releaseInFlightBuffer(
        activeSession: Resources.ChunkSession,
        chunkIndex: Long,
        inFlight: InFlightChunk,
        buffer: PooledBuffer
    ) {
        inFlight.buffer = null
        activeSession.inFlight.remove(chunkIndex, inFlight)
        releaseBuffer(buffer, activeSession)
    }

    // Reader-blocked chunk escalation. A stagnant
    // in-flight watermark past the threshold means the chunk's request is
    // hung (dead pooled connection / pre-body stall), the one shape the
    // body-rate watchdog structurally cannot see, because it samples inside
    // the body-read loop. Race ONE duplicate on a fresh connection into the
    // SAME future. The ownership-gated complete()/releaseBuffer() race
    // (ensureChunkScheduled's pattern) keeps the loser's buffer safe; the
    // duplicate NEVER completes the future exceptionally, so failure
    // semantics stay with the original attempt. Skipped while the
    // rate-limit clamp is latched; at most once per chunk per session.
    // OutOfMemoryError is contained exactly as in ensureChunkScheduled:
    // drain idle buffers, log, never escape the executor thread.
    private fun escalateReaderBlockedChunk(
        activeSession: Resources.ChunkSession,
        chunkIndex: Long,
        future: CompletableFuture<*>,
        waitedMs: Long,
        watermark: Int
    ) {
        if (future.isDone || future.isCancelled || activeSession.abandoned.get()) {
            Log.i(TAG, "RS_ESCALATE skip chunk=$chunkIndex reason=done")
            return
        }
        if (resources.hudClampLatched || activeSession.rateGate.blocking) {
            Log.i(TAG, "RS_ESCALATE skip chunk=$chunkIndex reason=clamp")
            return
        }
        val typed = activeSession.futures[chunkIndex]
        if (typed == null || typed !== future) {
            Log.i(TAG, "RS_ESCALATE skip chunk=$chunkIndex reason=stale")
            return
        }
        if (!activeSession.escalatedChunks.add(chunkIndex)) {
            Log.i(TAG, "RS_ESCALATE skip chunk=$chunkIndex reason=already")
            return
        }
        Log.w(TAG, "RS_ESCALATE fired chunk=$chunkIndex waitMs=$waitedMs watermark=$watermark")
        val t0 = SystemClock.elapsedRealtime()
        resources.sharedExecutor.execute {
            try {
                if (typed.isDone || typed.isCancelled || activeSession.abandoned.get()) return@execute
                val result = downloadChunkOnce(activeSession, chunkIndex, typed, allowStallRestart = false)
                if (typed.complete(result)) {
                    Log.w(TAG, "RS_ESCALATE won chunk=$chunkIndex ms=${SystemClock.elapsedRealtime() - t0}")
                } else {
                    releaseBuffer(result.buffer, activeSession)
                    Log.i(TAG, "RS_ESCALATE lost chunk=$chunkIndex")
                }
            } catch (e: Exception) {
                // Never completeExceptionally: the original path owns failure.
                Log.w(TAG, "RS_ESCALATE failed chunk=$chunkIndex: ${e.message}")
            } catch (e: OutOfMemoryError) {
                resources.drainIdleBuffers(activeSession.chunkSize)
                Log.w(TAG, "RS_ESCALATE failed chunk=$chunkIndex: chunk buffer OOM (contained)")
            }
        }
    }

    /**
     * Serve player bytes from a chunk still downloading, WAITING for
     * the first bytes rather than giving up when none have landed yet.
     *
     * Without the wait, a reader that arrives before the response headers
     * (for example when the probe is skipped on a prefetched press) finds
     * watermark=0, falls through, and blocks on the WHOLE 8 MB chunk. With
     * it, the reader is released the moment the first bytes land. The wait
     * ends early on completion, on failure (both surface as future.isDone,
     * so the caller's existing handling runs), and on close. duplicate()
     * keeps the download thread's position mutations unshared; the
     * copy-out runs under a lease on the attempt's buffer.
     */
    private fun awaitServeFromInFlight(
        activeSession: Resources.ChunkSession,
        chunkIndex: Long,
        future: CompletableFuture<*>,
        target: ByteArray,
        targetOffset: Int,
        maxLength: Int
    ): Int {
        val offsetInChunk = (position % chunkSize).toInt()
        val waitT0 = SystemClock.elapsedRealtime()
        // Reader-blocked escalation state for this wait.
        var escalatedThisWait = false
        var baselineWatermark = Int.MIN_VALUE
        while (true) {
            // Completion is the caller's business: its future.get() is then
            // instant, and a FAILED download completes here too, so its
            // existing retire-and-rethrow path runs unchanged.
            if (closed.get() || future.isDone) return 0
            // Re-read every pass: a retry attempt registers a fresh entry,
            // and the first attempt may not have registered one yet.
            val inFlight = activeSession.inFlight[chunkIndex]
            if (inFlight != null) {
                val available = inFlight.watermark - offsetInChunk
                if (available > 0) {
                    val toCopy = minOf(maxLength, available)
                    val buf = inFlight.buffer ?: return 0
                    buf.leased { it.copyOut(offsetInChunk, target, targetOffset, toCopy) } ?: return 0
                    val waitedMs = SystemClock.elapsedRealtime() - waitT0
                    if (!inFlightServeLogged) {
                        inFlightServeLogged = true
                        Log.i(
                            TAG,
                            "RS_INFLIGHT pos=$position chunk=$chunkIndex " +
                                "watermark=${inFlight.watermark} served=$toCopy waitMs=$waitedMs"
                        )
                    }
                    position += toCopy
                    bytesRemaining -= toCopy
                    bytesServedThisOpen += toCopy
                    return toCopy
                }
            }
            // Stagnant watermark past the threshold = hung request.
            // Progressing-but-slow chunks (watermark moving) belong to the
            // body-stall watchdog and are never escalated here.
            val wmNow = inFlight?.watermark ?: -1
            // The reader may arrive before the body registers its watermark.
            // Adopt its first zero baseline so a stationary registered body
            // still gets the existing hedge; positive progress stays with the watchdog.
            if (baselineWatermark == Int.MIN_VALUE || (baselineWatermark < 0 && wmNow == 0)) {
                baselineWatermark = wmNow
            }
            if (!escalatedThisWait &&
                wmNow == baselineWatermark &&
                SystemClock.elapsedRealtime() - waitT0 >=
                    minOf(ESCALATE_AFTER_MS, IN_FLIGHT_WAIT_CAP_MS.toLong())
            ) {
                escalateReaderBlockedChunk(
                    activeSession, chunkIndex, future,
                    SystemClock.elapsedRealtime() - waitT0, wmNow
                )
                escalatedThisWait = true
            }
            if (SystemClock.elapsedRealtime() - waitT0 >=
                IN_FLIGHT_WAIT_CAP_MS.toLong() +
                    (if (escalatedThisWait) ESCALATE_POLL_EXTENSION_MS else 0L)
            ) {
                Log.i(
                    TAG,
                    "RS_INFLIGHT_GIVEUP pos=$position chunk=$chunkIndex " +
                        "waitMs=${SystemClock.elapsedRealtime() - waitT0}"
                )
                return 0
            }
            try {
                Thread.sleep(IN_FLIGHT_POLL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return 0
            }
        }
    }

    private fun scheduleChunks() {
        if (!shouldAllowBackgroundPrefetch()) return
        // Nothing left to serve on this open. The continuation-exhaustion path
        // can otherwise schedule a chunk past the reader's last byte.
        if (bytesRemaining == 0L) return
        val currentChunkIdx =
            if (continuationSource != null && continuationEndPositionExclusive != C.TIME_UNSET && position < continuationEndPositionExclusive) {
                (continuationEndPositionExclusive + chunkSize - 1L) / chunkSize
            } else {
                position / chunkSize
            }
        // Earned prefetch: lookahead only after this open has served a
        // meaningful sequential run. Side cursors (a few bytes per open on
        // scatter-read files) fetch only the chunk they actually need, instead
        // of fanning out connections+1 chunks of dead prefetch per visit.
        val earnedAhead =
            if (depthLimit != null || bytesServedThisOpen >= EARNED_PREFETCH_BYTES) effectivePrefetchDepth else 1
        // AIMD: a rate-limited session halves its depth cap and
        // recovers +1 per quiet probe interval (Resources.ChunkSession.currentAllowedDepth),
        // so a provider that 429s converges just under its actual request
        // budget instead of latching to a single connection.
        val maxAhead = (session?.currentAllowedDepth(minOf(effectivePrefetchDepth, liveDepthLimit()))
            ?.coerceAtMost(earnedAhead)
            ?: earnedAhead).coerceAtMost(liveDepthLimit())

        // A manual setting keeps its read-ahead, but servers with a known total (TorBox) never get more requests at
        // once than it leaves, thumbnails' included. Automatic sizes its own requests against the same total and never
        // runs more at once than its live depth, pieces outside this window included.
        val activeSession = session
        var room = if (depthLimit == null && activeSession != null) {
            val running = activeSession.futures.values.count { !it.isDone }
            ConnectionLedger.app.playbackRoom(activeSession.requestTarget().toString(), Int.MAX_VALUE, running) - running
        } else if (activeSession != null) {
            val waiting = activeSession.futures.values.count { !it.isDone }
            speedupRoom(liveDepthLimit(), maxOf(requestMeter?.running() ?: 0, waiting))
        } else Int.MAX_VALUE

        for (i in 0 until maxAhead) {
            val ci = currentChunkIdx + i
            if (totalFileLength != C.LENGTH_UNSET.toLong() && ci * chunkSize >= totalFileLength) break
            val scheduled = activeSession?.futures?.containsKey(ci) == true
            if (!scheduled && room <= 0) break
            ensureChunkScheduled(ci)
            if (!scheduled) room--
        }
    }

    /** A long seek: the old position's pieces stop downloading and give their memory back now. */
    private fun dropLeftBySeek(activeSession: Resources.ChunkSession, target: Long) {
        synchronized(activeSession) {
            val victims = leftBySeek(activeSession.futures.keys.toList(), activeSession.lastReadChunkIndex, target,
                activeSession.prefetchWindow) ?: return
            if (victims.isEmpty()) return
            val running = victims.count { activeSession.futures[it]?.isDone == false }
            victims.forEach { resources.evictFuture(activeSession, it, maxPoolSize) }
            Log.i(TAG, "SEEK: dropped ${victims.size} pieces left behind (running=$running) " +
                "from=${activeSession.lastReadChunkIndex} to=$target")
        }
        resources.drainIdleBuffers(activeSession.chunkSize)
    }

    private fun liveDepthLimit(): Int = depthLimit?.invoke()?.coerceAtLeast(1) ?: Int.MAX_VALUE

    private fun ensureChunkScheduled(chunkIndex: Long) {
        val activeSession = session ?: return
        // Make room under the memory-tiered cap before growing the map.
        val cap = if (depthLimit == null) activeSession.chunkCap
            else minOf(activeSession.chunkCap, liveDepthLimit() + 1)
        resources.enforceSessionCap(activeSession, protectIndex = chunkIndex, poolCap = maxPoolSize, cap = cap)
        activeSession.futures.computeIfAbsent(chunkIndex) {
            val future = CompletableFuture<DownloadedChunk>()
            activeSession.touch(chunkIndex)
            Log.d(TAG, "Scheduling chunk $chunkIndex")
            resources.sharedExecutor.execute {
                try {
                    if (!future.isCancelled && !activeSession.abandoned.get()) {
                        val result = downloadChunk(activeSession, chunkIndex, future)
                        if (!future.complete(result)) {
                            releaseBuffer(result.buffer, activeSession)
                        }
                    } else if (future.isCancelled) {
                        // no-op: never started
                    } else {
                        future.completeExceptionally(IOException("Session abandoned"))
                    }
                } catch (e: Exception) {
                    future.completeExceptionally(e)
                } catch (e: OutOfMemoryError) {
                    // Chunk-buffer allocation (acquireBuffer)
                    // can throw OutOfMemoryError, an Error the Exception catch above
                    // never sees; escaping this worker thread would kill the process.
                    // Contain it:
                    // free every idle pooled buffer of this chunk size to relieve
                    // pressure, then fail the CHUNK (wrapped as IOException so every
                    // downstream Exception handler keeps working), never the process.
                    resources.drainIdleBuffers(activeSession.chunkSize)
                    future.completeExceptionally(
                        IOException("Native chunk buffer allocation failed (out of memory)", e)
                    )
                }
            }
            future
        }
    }

    private fun downloadChunk(activeSession: Resources.ChunkSession, chunkIndex: Long, future: CompletableFuture<*>): DownloadedChunk {
        try {
            return downloadChunkGated(activeSession, chunkIndex, future)
        } finally {
            activeSession.rateGate.finished(chunkIndex)
        }
    }

    private fun downloadChunkGated(activeSession: Resources.ChunkSession, chunkIndex: Long, future: CompletableFuture<*>): DownloadedChunk {
        var lastException: Exception? = null
        for (attempt in 0..1) {
            // isDone => a racing completer (reader-blocked escalation) already
            // won; a retry here would fetch a full chunk only to lose the
            // ownership race and release it.
            if (future.isDone || activeSession.abandoned.get()) throw IOException("Cancelled")
            if (!awaitRateGate(activeSession, chunkIndex, future, lastException)) throw IOException("Cancelled")
            val target = activeSession.requestTarget()
            val startedAtMs = activeSession.rateGate.now()
            val sentAtMs = SystemClock.elapsedRealtime()
            val beside = requestMeter?.running() ?: 0
            try {
                return downloadChunkOnce(activeSession, chunkIndex, future, startedAtMs = startedAtMs)
            } catch (e: Exception) {
                // Downloads belong to the session, not the instance:
                // future completion, cancellation or session teardown retires them.
                if (activeSession.abandoned.get() || future.isDone) throw IOException("Session abandoned or cancelled")
                lastException = e
                if (e !is StalledChunkException && e.findRateLimitException() == null) {
                    noteExtraRefused(activeSession, chunkIndex, e, sentAtMs, beside)
                }
                if (PlaybackNetworkFailure.isConnectFailure(e) && activeSession.requestTarget() == target) {
                    throw IOException("Failed to download chunk $chunkIndex: server unreachable", e)
                }
                // 429/503 is server rate-limiting, not a stalled socket.
                // Hand off to a bounded backoff loop rather than retrying now.
                // A body stall (not a 429) is abandoned and re-fetched
                // on a fresh connection, sequentially, within budget.
                if (e is StalledChunkException) {
                    return downloadChunkWithStallRestart(activeSession, chunkIndex, future, e)
                }
                val rlError = e.findRateLimitException()
                if (rlError != null) {
                    return downloadChunkWithRateLimitBackoff(activeSession, chunkIndex, future, rlError, startedAtMs)
                }
                if (attempt == 0) {
                    if (e.isTransientInterruption()) {
                        Log.d(TAG, "Chunk $chunkIndex interrupted during prefetch (attempt 1), retrying")
                        try {
                            Thread.sleep(50)
                        } catch (_: InterruptedException) {
                        }
                    } else {
                        Log.w(TAG, "Chunk $chunkIndex download failed (attempt 1), retrying: ${e.message}")
                    }
                }
            }
        }
        throw IOException("Failed to download chunk $chunkIndex after 2 attempts", lastException)
    }

    private fun downloadChunkOnce(
        activeSession: Resources.ChunkSession,
        chunkIndex: Long,
        future: CompletableFuture<*>,
        allowStallRestart: Boolean = true,
        startedAtMs: Long = activeSession.rateGate.now()
    ): DownloadedChunk {
        val sessionLength = activeSession.totalLength
        val start = chunkIndex * chunkSize
        val end = if (sessionLength > 0L) {
            minOf(start + chunkSize, sessionLength)
        } else {
            start + chunkSize
        }

        val ds = upstreamFactory.createDataSource()
        transferListeners.forEach { ds.addTransferListener(it) }
        activeSession.activeSources.add(ds)
        val slot = runCatching { ConnectionLedger.app.playback(activeSession.requestTarget().toString()) }.getOrNull()
        if (dropStaleConnections != null && requestMeter?.takeStale(STALE_IDLE_MS) == true) {
            runCatching { dropStaleConnections?.invoke() }
            Log.i(TAG, "POOL: idle ${STALE_IDLE_MS / 1000} s or more, pooled connections dropped before the next piece")
        }
        requestMeter?.started()
        try {
            val uri = activeSession.requestTarget()
            val spec = DataSpec.Builder()
                .setUri(uri)
                .setPosition(start)
                .setLength(end - start)
                .build()

            if (future.isDone || activeSession.abandoned.get()) throw IOException("Cancelled")
            Log.d(TAG, "Starting chunk download: idx=$chunkIndex, range=$start-$end")
            val sent = openTarget(activeSession, ds, spec, sessionLength) { future.isDone || activeSession.abandoned.get() }
            slot?.moveTo(runCatching { ds.uri?.toString() }.getOrNull())
            if (activeSession.rateGate.answered(startedAtMs)) {
                Log.i(TAG, "RATE_GATE open chunk=$chunkIndex: the server answers again")
            }
            // Short-chunk rejection: with a known session length the
            // requested range is exact, so a chunk that comes back short must
            // fail (and retry) rather than be cached as if complete.
            val expectedBytes = if (sessionLength > 0L) end - start else -1L
            val chunk = readIntoChunk(activeSession, chunkIndex, ds, future, expectedBytes, allowStallRestart)
            Log.d(TAG, "Successfully downloaded chunk $chunkIndex, size=${chunk.size} bytes")
            reportPiece(sent, chunk, ds.uri)
            return chunk
        } catch (e: Exception) {
            slot?.failed(e)
            throw e
        } finally {
            requestMeter?.ended()
            slot?.close()
            activeSession.activeSources.remove(ds)
            try { ds.close() } catch (_: Exception) {}
        }
    }

    private class Sent(val spec: DataSpec, val atMs: Long)

    private fun reportPiece(sent: Sent, chunk: DownloadedChunk, served: Uri?) {
        val report = onPiece ?: return
        val spec = sent.spec
        if (chunk.paused || chunk.firstByteAtMs < 0L || chunk.doneAtMs < chunk.firstByteAtMs) return
        val reused = runCatching {
            val url = spec.uri.toString().toHttpUrlOrNull()?.toString()
            val range = HttpUtil.buildRangeRequestHeader(spec.position, spec.length)
            if (url != null && range != null) PlaybackConnectionEvents.takePiece(url, range) else null
        }.getOrNull()
        val timing = PieceTiming(
            atMs = chunk.doneAtMs,
            ttfbMs = chunk.firstByteAtMs - sent.atMs,
            transferMs = chunk.doneAtMs - chunk.firstByteAtMs,
            bytes = (chunk.size - chunk.firstReadBytes).toLong(),
            reused = reused,
            redirected = served != null && served != spec.uri
        )
        runCatching { report(timing) }
    }

    /**
     * Opens [spec] on the session's current address. When that address cannot be reached or
     * refuses the file, it is forgotten so the next try asks the original link. A response from
     * a fresh look-up is used only if it describes the same file.
     */
    private fun openTarget(
        activeSession: Resources.ChunkSession,
        ds: OkHttpDataSource,
        requested: DataSpec,
        sessionLength: Long,
        cancelled: () -> Boolean
    ): Sent {
        var spec = requested
        val turn = if (spec.uri == activeSession.requestUri) activeSession.lookUpTurn() else null
        if (turn != null && !turn.asks) {
            val capMs = if (SlowStartServer.matches(activeSession.requestUri.toString())) {
                SlowStartServer.LOOKUP_WAIT_CAP_MS
            } else {
                LOOKUP_WAIT_CAP_MS
            }
            awaitLookUp(turn.result, capMs, cancelled)
        }
        if (turn?.asks != true && spec.uri == activeSession.requestUri) {
            spec = spec.buildUpon().setUri(activeSession.requestTarget()).build()
        }
        val asking = turn?.takeIf { it.asks }?.result
        var failure: Throwable? = null
        val sent = Sent(spec, SystemClock.elapsedRealtime())
        try {
            try {
                ds.open(spec)
            } catch (e: Exception) {
                if (PlaybackNetworkFailure.isConnectFailure(e) || PlaybackNetworkFailure.isRefusedLink(e)) {
                    if (activeSession.forgetResolvedUri(spec.uri)) {
                        Log.w(TAG, "LINK_LOOKUP forget host=${spec.uri.host}: ${e.message}")
                    }
                }
                throw e
            }
            // Checked against the address this request used, not the session's current one: a
            // parallel download may adopt its own fresh answer while this one is still opening.
            if (sessionLength <= 0L || spec.uri != activeSession.requestUri || spec.uri == activeSession.resolvedUri) return sent
            val served = parseContentRangeTotal(ds.responseHeaders)
            if (served != C.LENGTH_UNSET.toLong() && served != sessionLength) {
                throw IOException("Fresh link serves a different file ($served of $sessionLength bytes)")
            }
            val fresh = ds.uri ?: return sent
            if (activeSession.adoptResolvedUri(fresh) && fresh.host != activeSession.requestUri.host) {
                Log.i(TAG, "LINK_LOOKUP adopt host=${fresh.host}")
            }
            return sent
        } catch (e: Throwable) {
            failure = e
            throw e
        } finally {
            if (asking != null) {
                activeSession.finishLookUp(
                    asking,
                    failure ?: if (activeSession.resolvedUri == null) IOException("Link look-up gave no address") else null
                )
            }
        }
    }

    private fun awaitLookUp(result: CompletableFuture<Uri>, capMs: Long, cancelled: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + capMs
        while (true) {
            if (cancelled()) throw IOException("Cancelled while waiting for the link look-up")
            try {
                result.get(LOOKUP_POLL_MS, TimeUnit.MILLISECONDS)
                return
            } catch (_: java.util.concurrent.TimeoutException) {
            } catch (e: java.util.concurrent.ExecutionException) {
                throw IOException("Link look-up failed", e.cause ?: e)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("Interrupted while waiting for the link look-up")
            }
            if (SystemClock.uptimeMillis() >= deadline) throw IOException("Link look-up still running")
        }
    }

    /**
     * A stalled body is abandoned and the chunk re-fetched on a FRESH
     * connection, immediately (no backoff -- the point is speed) and
     * SEQUENTIALLY, so the stalled connection's slot is reused and the
     * user's configured connection count is never exceeded (works at 1, 2
     * or N connections identically). Up to HEDGE_MAX_RESTARTS fresh tries;
     * if the origin stalls every one, a final watchdog-disabled attempt
     * lets the chunk complete slowly rather than failing playback. A
     * fresh connection that returns a non-stall error (e.g. a 429) is
     * surfaced so the existing rate-limit / retry paths handle it.
     */
    private fun downloadChunkWithStallRestart(
        activeSession: Resources.ChunkSession,
        chunkIndex: Long,
        future: CompletableFuture<*>,
        firstStall: StalledChunkException
    ): DownloadedChunk {
        var lastStall = firstStall
        var attempt = 0
        while (attempt < HEDGE_MAX_RESTARTS) {
            if (future.isDone || activeSession.abandoned.get()) throw IOException("Cancelled")
            resources.hudHedgeRestarts++
            Log.w(TAG, "HEDGE_RESTART chunk=$chunkIndex attempt=${attempt + 1}/$HEDGE_MAX_RESTARTS " +
                "prevRateBps=${lastStall.rateBps} atWatermark=${lastStall.watermark}")
            try {
                return downloadChunkOnce(activeSession, chunkIndex, future)
            } catch (e: Exception) {
                if (activeSession.abandoned.get() || future.isDone) throw IOException("Session abandoned or cancelled")
                if (e !is StalledChunkException) throw e
                lastStall = e
                attempt++
            }
        }
        if (future.isDone || activeSession.abandoned.get()) throw IOException("Chunk already retired")
        // Origin stalling every connection: complete slowly rather than fail.
        resources.hudHedgeExhausted = true
        Log.w(TAG, "HEDGE_RESTART chunk=$chunkIndex exhausted after $HEDGE_MAX_RESTARTS; " +
            "final attempt with watchdog disabled")
        return downloadChunkOnce(activeSession, chunkIndex, future, allowStallRestart = false)
    }

    /** Thrown by readIntoChunk when a chunk's body rate collapses; an
     *  IOException subtype so it never matches the 429 or transient-retry
     *  predicates and flows only to the stall-restart path. */
    private class StalledChunkException(
        val chunkIndex: Long,
        val watermark: Int,
        val rateBps: Long
    ) : IOException("chunk $chunkIndex body stalled at ${rateBps}B/s (watermark=$watermark)")

    private fun Exception.isTransientInterruption(): Boolean {
        if (this is InterruptedIOException || this is InterruptedException) return true
        val cause = cause
        return cause is InterruptedIOException || cause is InterruptedException
    }

    /**
     * Walk the cause chain for an HTTP 429/503 response. Returns the
     * exception (so the caller can read its Retry-After header) or null for any
     * other failure, which keeps the immediate-retry stall handling.
     */
    private fun Throwable.findRateLimitException(): HttpDataSource.InvalidResponseCodeException? {
        var cause: Throwable? = this
        var depth = 0
        while (cause != null && depth < 6) {
            val c = cause
            if (c is HttpDataSource.InvalidResponseCodeException &&
                (c.responseCode == 429 || c.responseCode == 503)) {
                return c
            }
            cause = c.cause
            depth++
        }
        return null
    }

    /**
     * A refused or cut-off piece counts as an extra download refused only while another download to the server keeps
     * delivering; with none delivering, every download failed together ([onAllFailed]).
     */
    private fun noteExtraRefused(
        activeSession: Resources.ChunkSession,
        chunkIndex: Long,
        error: Exception,
        sentAtMs: Long,
        beside: Int
    ) {
        val running = requestMeter?.running() ?: return
        val nowMs = SystemClock.elapsedRealtime()
        if (!ExtraDownloadRefusal.othersDelivering(activeSession.deliveredAtMs, chunkIndex, nowMs)) {
            val all = onAllFailed ?: return
            val failed = AllDownloadsFailed(
                running = running + 1,
                served = ExtraDownloadRefusal.servedRecently(activeSession.deliveredAtMs, nowMs),
                ageMs = (nowMs - sentAtMs).coerceAtLeast(0L),
                beside = beside,
                block = ExtraDownloadRefusal.isBlock(error)
            )
            Log.i(TAG, "ALL_FAILED chunk=$chunkIndex running=${failed.running} served=${failed.served} " +
                "block=${failed.block}: ${error.javaClass.simpleName}")
            runCatching { all(activeSession.requestTarget(), failed) }
            return
        }
        val callback = onExtraRefused ?: return
        if (running < 1 || !ExtraDownloadRefusal.isRefusal(error)) return
        Log.i(TAG, "EXTRA_REFUSED chunk=$chunkIndex running=${running + 1}: ${error.javaClass.simpleName}")
        runCatching { callback(activeSession.requestTarget(), running + 1) }
    }

    /** The refused request has ended already, so the requests running at the refusal are those left plus it. */
    private fun noteRateLimited(activeSession: Resources.ChunkSession) {
        val callback = onRateLimited ?: return
        val running = requestMeter?.running() ?: return
        runCatching { callback(activeSession.requestTarget(), running + 1) }
    }

    /**
     * A rate-limited piece waits for the session's [ParallelRangeRateGate] instead of retrying on its own: the
     * pieces refused together count once, nothing is sent while the wait runs, then the piece nearest the player
     * tries alone. The piece fails only after [ParallelRangeRateGate.GIVE_UP_MS] without any request answered.
     */
    private fun downloadChunkWithRateLimitBackoff(
        activeSession: Resources.ChunkSession,
        chunkIndex: Long,
        future: CompletableFuture<*>,
        firstError: HttpDataSource.InvalidResponseCodeException,
        firstStartedAtMs: Long
    ): DownloadedChunk {
        val gate = activeSession.rateGate
        var rl: HttpDataSource.InvalidResponseCodeException = firstError
        var startedAtMs = firstStartedAtMs
        var lastException: Exception = firstError
        if (future.isDone || activeSession.abandoned.get()) throw IOException("Chunk already retired")
        while (true) {
            // TorBox sends no Retry-After and lets the next request in once one of ours ends, so no long wait or
            // halved read-ahead there; the cap on its requests at once keeps a refusal rare.
            val torbox = LedgerKey.of(activeSession.requestTarget().toString()) == LedgerKey.TORBOX
            val retryAfter = retryAfterMs(rl) ?: TORBOX_REFUSAL_RETRY_MS.takeIf { torbox }
            val refusal = gate.refused(chunkIndex, startedAtMs, retryAfter)
            if (refusal > 0) {
                noteRateLimited(activeSession)
                // Only the burst that opened the spell says too many ran at once; a lone try refused later does not.
                if (refusal == 1 && !torbox) activeSession.beginRateLimitEpisode(effectivePrefetchDepth)
                else activeSession.noteRateLimitHit()
                Log.w(TAG, "RATE_GATE closed chunk=$chunkIndex HTTP ${rl.responseCode} refusals=$refusal " +
                    "waitMs=${gate.waitLeftMs()} waiting=${gate.waitingCount()} running=${requestMeter?.running() ?: -1}")
            } else {
                activeSession.noteRateLimitHit()
            }
            if (!awaitRateGate(activeSession, chunkIndex, future, lastException)) {
                throw IOException("Cancelled during rate-limit backoff")
            }
            startedAtMs = gate.now()
            try {
                return downloadChunkOnce(activeSession, chunkIndex, future, startedAtMs = startedAtMs)
            } catch (e: Exception) {
                if (activeSession.abandoned.get() || future.isDone) throw IOException("Session abandoned or cancelled")
                lastException = e
                // A non-rate-limit error after a 429 is a different failure: surface it.
                rl = e.findRateLimitException() ?: throw e
            }
        }
    }

    /**
     * Waits until the session's rate gate lets piece [chunkIndex] send. False when the piece was retired meanwhile;
     * throws once the server has refused everything for [ParallelRangeRateGate.GIVE_UP_MS].
     */
    private fun awaitRateGate(
        activeSession: Resources.ChunkSession,
        chunkIndex: Long,
        future: CompletableFuture<*>,
        cause: Exception?
    ): Boolean {
        val gate = activeSession.rateGate
        var started = false
        try {
            while (true) {
                if (future.isDone || activeSession.abandoned.get()) return false
                if (gate.tryStart(chunkIndex)) {
                    started = true
                    return true
                }
                if (gate.spellMs() >= ParallelRangeRateGate.GIVE_UP_MS) {
                    throw IOException("Chunk $chunkIndex still rate-limited after ${gate.spellMs() / 1000} s", cause)
                }
                try {
                    Thread.sleep(RATE_LIMIT_SLEEP_SLICE_MS)
                } catch (_: InterruptedException) {
                    return false
                }
            }
        } finally {
            if (!started) gate.leave(chunkIndex)
        }
    }

    private fun retryAfterMs(rl: HttpDataSource.InvalidResponseCodeException): Long? {
        val header = rl.headerFields.entries
            .firstOrNull { it.key?.equals("Retry-After", ignoreCase = true) == true }
            ?.value?.firstOrNull()?.trim()
        return ParallelRangeRetryAfter.parseHeaderMs(header)
    }

    /** Read from an already-opened DataSource into a pooled chunk buffer. */
    private fun readIntoChunk(
        activeSession: Resources.ChunkSession,
        chunkIndex: Long,
        ds: DataSource,
        future: CompletableFuture<*>,
        expectedBytes: Long,
        allowStallRestart: Boolean = true
    ): DownloadedChunk {
        val buffer = acquireBuffer(activeSession)
        val segmented = buffer as? SegmentedBuffer
        var paused = false
        // Progressive reads: publish this attempt. Registered
        // AFTER acquireBuffer so an allocation OOM never leaves a
        // dangling entry; a retry attempt overwrites its predecessor.
        val inFlight = InFlightChunk(buffer)
        activeSession.inFlight[chunkIndex] = inFlight
        val tempArray = resources.readBufferLocal.get()!!
        var totalRead = 0
        var consecutiveZeroReads = 0
        // Log the watchdog settings on the first chunk read, once.
        if (resources.obsAnnounced.compareAndSet(false, true)) {
            Log.w(TAG, "OBS_ACTIVE build=hedge-3a minOpenMs=$HEDGE_MIN_OPEN_MS " +
                "windowMs=$HEDGE_WINDOW_MS stallRateBps=$HEDGE_STALL_RATE_BPS " +
                "maxRestarts=$HEDGE_MAX_RESTARTS")
        }
        // In-loop body-stall watchdog state (per download attempt).
        val hedgeChunkT0 = SystemClock.elapsedRealtime()
        var hedgeLastCheckT0 = hedgeChunkT0
        var deliveredNotedAtMs = -DELIVERED_NOTE_INTERVAL_MS
        var hedgeLastCheckBytes = 0
        // Source-aware: pick the stall profile once per attempt from the
        // resolved (post-redirect) URL. AIOStreams native Usenet resolves through
        // .../api/v1/usenet/stream/...; anything else (debrid CDN, Emby, direct)
        // is treated as CDN and keeps the single-window behaviour.
        val hedgeIsUsenet = (activeSession.resolvedUri ?: ds.uri)?.path?.contains("/usenet/") == true
        val hedgeStallRateBps = if (hedgeIsUsenet) HEDGE_STALL_RATE_USENET else HEDGE_STALL_RATE_CDN
        val hedgeWindowsRequired = if (hedgeIsUsenet) HEDGE_WINDOWS_USENET else HEDGE_WINDOWS_CDN
        var hedgeConsecutiveStalled = 0
        var firstByteAtMs = -1L
        var firstReadBytes = 0
        try {
            val byteBufferReader = if (isEffectiveNative && ds is androidx.media3.common.ByteBufferDataReader && ds.supportsByteBufferRead()) {
                ds
            } else {
                null
            }

            // The loop does not watch the instance's closed flag:
            // downloads run to completion across ExoPlayer's seek reopens and
            // abort only on future cancellation or session teardown.
            while (!activeSession.abandoned.get()) {
                if (future.isCancelled) {
                    throw IOException("Chunk download cancelled")
                }
                var maxRead = minOf((segmented?.capacityBytes ?: buffer.byteBuffer.capacity()) - totalRead, READ_BUFFER_SIZE)
                if (maxRead <= 0) break
                if (segmented != null) {
                    maxRead = minOf(maxRead, SEGMENT_BYTES - totalRead % SEGMENT_BYTES)
                    if (!segmented.hasSegment(totalRead) && awaitHeldRoom(activeSession, chunkIndex, future)) {
                        paused = true
                        hedgeLastCheckT0 = SystemClock.elapsedRealtime()
                        hedgeLastCheckBytes = totalRead
                    }
                }

                val read = if (byteBufferReader != null) {
                    val target = segmented?.writeView(totalRead) ?: buffer.byteBuffer.also { it.position(totalRead) }
                    byteBufferReader.read(target, maxRead)
                } else {
                    val r = ds.read(tempArray, 0, maxRead)
                    if (r != C.RESULT_END_OF_INPUT) {
                        val target = segmented?.writeView(totalRead) ?: buffer.byteBuffer.also { it.position(totalRead) }
                        target.put(tempArray, 0, r)
                    }
                    r
                }

                if (read == C.RESULT_END_OF_INPUT) break
                // No-progress guard: a positive-length read returning
                // 0 violates the DataSource contract; bail after a few rather
                // than busy-spinning until cancellation.
                if (read == 0) {
                    if (++consecutiveZeroReads >= MAX_CONSECUTIVE_ZERO_READS) {
                        throw IOException(
                            "No read progress after $MAX_CONSECUTIVE_ZERO_READS attempts " +
                                "(read $totalRead of $expectedBytes bytes)"
                        )
                    }
                } else {
                    consecutiveZeroReads = 0
                }
                if (firstByteAtMs < 0L && read > 0) {
                    firstByteAtMs = SystemClock.elapsedRealtime()
                    firstReadBytes = read
                }
                totalRead += read
                receivedBytes?.addAndGet(read.toLong())
                // Volatile store orders every buffer write above it.
                inFlight.watermark = totalRead
                // Rate-based body-stall sample, emitted every window
                // whether or not it trips, so the decision is always
                // visible. Only chunks taking longer than the window emit.
                val hedgeNowMs = SystemClock.elapsedRealtime()
                val sinceNotedMs = hedgeNowMs - deliveredNotedAtMs
                if (sinceNotedMs < 0L || sinceNotedMs >= DELIVERED_NOTE_INTERVAL_MS) {
                    activeSession.noteDelivered(chunkIndex, hedgeNowMs)
                    deliveredNotedAtMs = hedgeNowMs
                }
                if (hedgeNowMs - hedgeChunkT0 >= HEDGE_MIN_OPEN_MS &&
                    hedgeNowMs - hedgeLastCheckT0 >= HEDGE_WINDOW_MS) {
                    val hedgeWindowMs = hedgeNowMs - hedgeLastCheckT0
                    val hedgeWindowBytes = totalRead - hedgeLastCheckBytes
                    val hedgeRateBps = if (hedgeWindowMs > 0L)
                        hedgeWindowBytes.toLong() * 1000L / hedgeWindowMs else Long.MAX_VALUE
                    val hedgeStalled = hedgeRateBps < hedgeStallRateBps
                    if (hedgeStalled) hedgeConsecutiveStalled++ else hedgeConsecutiveStalled = 0
                    Log.w(TAG, "HEDGE_SAMPLE chunk=$chunkIndex watermark=$totalRead " +
                        "windowBytes=$hedgeWindowBytes windowMs=$hedgeWindowMs " +
                        "rateBps=$hedgeRateBps stalled=$hedgeStalled " +
                        "consec=$hedgeConsecutiveStalled/$hedgeWindowsRequired " +
                        "usenet=$hedgeIsUsenet clamp=$resources.hudClampLatched " +
                        "restartable=$allowStallRestart")
                    if (hedgeConsecutiveStalled >= hedgeWindowsRequired &&
                        allowStallRestart && !resources.hudClampLatched) {
                        // Abandon this connection; downloadChunk routes the
                        // throw to a fresh-connection restart within budget.
                        throw StalledChunkException(chunkIndex, totalRead, hedgeRateBps)
                    }
                    hedgeLastCheckT0 = hedgeNowMs
                    hedgeLastCheckBytes = totalRead
                }
            }
            // Short-chunk rejection: a premature EOF inside a known
            // range must not produce a cached "complete" chunk; a short
            // non-final chunk otherwise dead-ends both read paths at the
            // phantom chunk boundary.
            if (expectedBytes > 0L && totalRead < expectedBytes && !activeSession.abandoned.get()) {
                throw IOException("Short chunk: read $totalRead of $expectedBytes bytes")
            }
        } catch (e: Throwable) {
            releaseInFlightBuffer(activeSession, chunkIndex, inFlight, buffer)
            if (e is Exception && activeSession.abandoned.get()) throw IOException("Session abandoned")
            throw e
        }
        if (activeSession.abandoned.get()) {
            releaseInFlightBuffer(activeSession, chunkIndex, inFlight, buffer)
            throw IOException("Session abandoned")
        }
        // Success: the buffer graduates into the completed chunk; only
        // the in-flight view is retired (ownership-gated).
        activeSession.inFlight.remove(chunkIndex, inFlight)
        if (segmented == null) buffer.byteBuffer.flip()
        return DownloadedChunk(buffer, totalRead, firstByteAtMs, firstReadBytes, SystemClock.elapsedRealtime(), paused)
    }

    /**
     * Waits while the session's pieces hold their whole share ([heldLimit]), unless this is the piece being read or
     * the session has no reader yet. True when it waited.
     */
    private fun awaitHeldRoom(activeSession: Resources.ChunkSession, chunkIndex: Long, future: CompletableFuture<*>): Boolean {
        val limit = heldLimit ?: return false
        var waited = false
        while (true) {
            if (future.isCancelled) throw IOException("Chunk download cancelled")
            val limitBytes = limit()
            activeSession.heldLock.lock()
            val full = try {
                val full = !activeSession.abandoned.get() &&
                    activeSession.lastReadChunkIndex in 0 until chunkIndex &&
                    activeSession.heldBytes.get() + SEGMENT_BYTES > limitBytes
                if (full && waited) activeSession.heldChanged.await(heldWaitMs, TimeUnit.MILLISECONDS)
                full
            } catch (_: InterruptedException) {
                throw InterruptedIOException("Interrupted while the pieces hold their share")
            } finally {
                activeSession.heldLock.unlock()
            }
            if (!full) return waited
            if (!waited) {
                waited = true
                Log.d(TAG, "HELD: chunk $chunkIndex waits, pieces hold ${activeSession.heldBytes.get() / SEGMENT_BYTES} MB " +
                    "of ${limitBytes / SEGMENT_BYTES} MB")
            }
        }
    }

    /** Read only a small startup window from an already-opened DataSource. */
    /**
     * Total file size from a 206's Content-Range ("bytes 0-262143/N").
     * Returns C.LENGTH_UNSET when the header is absent or the total is
     * opaque (an asterisk), which the caller treats as "Range not honoured".
     */
    private fun parseContentRangeTotal(headers: Map<String, List<String>>): Long {
        val value = headers.entries
            .firstOrNull { it.key.equals("Content-Range", ignoreCase = true) }
            ?.value?.firstOrNull()
            ?: return C.LENGTH_UNSET.toLong()
        val totalPart = value.substringAfterLast('/', missingDelimiterValue = "").trim()
        if (totalPart.isEmpty() || totalPart == "*") return C.LENGTH_UNSET.toLong()
        return totalPart.toLongOrNull() ?: C.LENGTH_UNSET.toLong()
    }

    private fun readBootstrapChunk(ds: DataSource, maxBytes: Int): DownloadedChunk {
        val buffer = ByteArray(maxBytes)
        var totalRead = 0
        var consecutiveZeroReads = 0
        try {
            while (!closed.get() && totalRead < buffer.size) {
                val maxRead = minOf(buffer.size - totalRead, READ_BUFFER_SIZE)
                if (maxRead <= 0) break
                val read = ds.read(buffer, totalRead, maxRead)
                if (read == C.RESULT_END_OF_INPUT) break
                // Match chunk downloads: a broken reader must not spin forever
                // during startup, but transient stalls recover after progress.
                if (read == 0) {
                    if (++consecutiveZeroReads >= MAX_CONSECUTIVE_ZERO_READS) {
                        throw IOException("No bootstrap read progress after $MAX_CONSECUTIVE_ZERO_READS attempts")
                    }
                } else {
                    consecutiveZeroReads = 0
                }
                totalRead += read
            }
        } catch (e: Exception) {
            if (closed.get()) throw IOException("DataSource closed")
            throw e
        }
        if (closed.get()) {
            throw IOException("DataSource closed")
        }
        val wrapped = ByteBuffer.wrap(buffer, 0, totalRead)
        return DownloadedChunk(PooledBuffer(null, wrapped), totalRead)
    }

    private val isEffectiveNative: Boolean
        get() = useNativeMemory || androidx.media3.common.NuvioEngineConfig.get().isNativeAllocationEnabled()

    private fun acquireBuffer(activeSession: Resources.ChunkSession): PooledBuffer {
        if (resources.released) throw IOException("Diagnostic session is closed")
        if (heldLimit != null) {
            return SegmentedBuffer(
                chunkSize.toInt(), resources::takeSegment, { resources.giveSegment(it, segmentPoolCap) }, activeSession.heldBytes,
                activeSession::signalHeld
            )
        }
        val pool = resources.globalBufferPool.computeIfAbsent(chunkSize) { ConcurrentLinkedDeque() }
        pool.pollLast()?.let { idle ->
            idle.byteBuffer.clear()
            return PooledBuffer(idle.allocation, idle.byteBuffer)
        }
        // Native memory in every engine mode. Chunks on the Java heap fill small heaps and stall
        // playback on blocking GC; Android's allocateDirect is heap memory too.
        val allocation = allocateChunk(chunkSize.toInt())
        val allocBuffer = allocation?.buffer
        return if (allocation != null && allocBuffer != null) {
            PooledBuffer(allocation, allocBuffer)
        } else {
            if (heapChunkLogged.compareAndSet(false, true)) {
                Log.w(TAG, "chunk buffers on the Java heap: native allocation failed (${chunkSize / 1024 / 1024} MB chunks)")
            }
            PooledBuffer(null, ByteBuffer.allocate(chunkSize.toInt()))
        }
    }

    /**
     *   maxPoolSize in releaseBuffer only caps how many idle/recycled buffers are kept in the pool.
     *   If the pool is full, the released buffer is GC'd instead of recycled.
     */
    private fun releaseBuffer(buffer: PooledBuffer, owner: Resources.ChunkSession? = null) {
        // A download that outlived its session (new stream or playback ended) frees its chunk instead of pooling it.
        val poolCap = if (owner?.abandoned?.get() == true) 0 else maxPoolSize
        resources.releaseSessionBuffer(buffer, chunkSize, poolCap)
    }

    /**
     * Detach instance-local read state. Session chunks (and in-flight
     * downloads) are untouched: they belong to the shared session and their
     * buffers are owned by the session's futures. Releasing anything here
     * would double-free; eviction and teardown are the session's job.
     */
    private fun resetLocalReadState() {
        currentChunk = null
        currentChunkIndex = -1
        currentChunkReadOffset = 0
        bootstrapChunk = null
        bootstrapStartPosition = C.TIME_UNSET
        inFlightServeLogged = false
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try {
                fallbackSource?.close()
                continuationSource?.close()
            } finally {
                fallbackSource = null
                continuationSource = null
                continuationEndPositionExclusive = C.TIME_UNSET
                pendingContinuationOpen = false
                resetLocalReadState()
                session = null
                val active = resources.activeInstances.decrementAndGet()
                if (isolateSession) resources.closeDiagnostic()
                else if (active <= 0) resources.clearGlobalPool()
            }
        }
    }

    override fun addTransferListener(transferListener: TransferListener) {
        transferListeners.add(transferListener)
    }

    override fun getUri(): Uri? = resolvedUri ?: fallbackSource?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        fallbackSource?.responseHeaders ?: emptyMap()

    override fun supportsByteBufferRead(): Boolean = true

    override fun read(buffer: ByteBuffer, length: Int): Int {
        while (true) {
            val read = readOnce(buffer, length)
            if (read != LEASE_LOST) return read
        }
    }

    private fun readOnce(buffer: ByteBuffer, length: Int): Int {
        com.nuvio.tv.core.util.TtffTrace.sourceReads++
        fallbackSource?.let { source ->
            val temp = resources.readBufferLocal.get()!!
            val read = source.read(temp, 0, minOf(length, READ_BUFFER_SIZE))
            if (read > 0) {
                buffer.put(temp, 0, read)
                position += read
                bytesRemaining = (bytesRemaining - read).coerceAtLeast(0L)
            }
            return read
        }

        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val toRead = minOf(length.toLong(), bytesRemaining).toInt()

        val chunkIndex = position / chunkSize
        val bootstrap = bootstrapChunk
        if (currentChunk == null &&
            bootstrap != null &&
            position >= bootstrapStartPosition &&
            position < bootstrapStartPosition + bootstrap.size
        ) {
            currentChunk = bootstrap
            currentChunkIndex = chunkIndex
            currentChunkReadOffset = (position - bootstrapStartPosition).toInt()
        }

        // Materialise BEFORE the deferred schedule below. Placed after it,
        // scheduleChunks() would still see a null continuation and schedule the
        // whole aligned chunk -- paying the amplified fetch while merely hiding
        // it from the reader.
        if (pendingContinuationOpen && currentChunk == null && continuationSource == null) {
            materialisePendingContinuation()
        }

        if (bootstrapPrefetchDeferred && shouldAllowBackgroundPrefetch()) {
            bootstrapPrefetchDeferred = false
            scheduleChunks()
        }

        continuationSource?.let { source ->
            if (position < continuationEndPositionExclusive &&
                bytesRemaining > 0L &&
                (bootstrap == null || position >= bootstrapStartPosition + bootstrap.size)
            ) {
                val temp = resources.readBufferLocal.get()!!
                val read = source.read(temp, 0, minOf(toRead, READ_BUFFER_SIZE))
                if (read > 0) {
                    buffer.put(temp, 0, read)
                    position += read
                    bytesRemaining -= read
                    if (position >= continuationEndPositionExclusive) {
                        source.close()
                        continuationSource = null
                        continuationEndPositionExclusive = C.TIME_UNSET
                        scheduleChunks()
                    }
                    return read
                }
                if (read == C.RESULT_END_OF_INPUT || position >= continuationEndPositionExclusive) {
                    source.close()
                    continuationSource = null
                    continuationEndPositionExclusive = C.TIME_UNSET
                    scheduleChunks()
                }
            } else if (position >= continuationEndPositionExclusive || bytesRemaining <= 0L) {
                source.close()
                continuationSource = null
                continuationEndPositionExclusive = C.TIME_UNSET
            }
        }

        if (currentChunkIndex != chunkIndex || currentChunk == null) {
            val activeSession = session ?: return C.RESULT_END_OF_INPUT
            ensureChunkScheduled(chunkIndex)
            // Speed-up downloads start with the piece they follow, not when it completes: a reader waiting on
            // that piece would otherwise only ever have one download running.
            if (depthLimit != null) scheduleChunks()
            val future = activeSession.futures[chunkIndex] ?: return C.RESULT_END_OF_INPUT
            activeSession.noteRead(chunkIndex)
            try {
                // RS_CHUNK_WAIT: read() blocks on the WHOLE chunk future, so
                // ExoPlayer sees nothing of an 8 MB chunk until all 8 MB have
                // landed. The count and highest pos logged before
                // first_frame_rendered show whether this gates the first frame.
                // preDone separates a real stall from an already-complete chunk.
                // site= distinguishes the two read() overloads.
                val blockT0 = SystemClock.elapsedRealtime()
                val preDone = future.isDone
                currentChunk = future.get(60, TimeUnit.SECONDS)
                Log.i(
                    TAG,
                    "RS_CHUNK_WAIT site=bytebuffer pos=$position chunk=$chunkIndex " +
                        "waitMs=${SystemClock.elapsedRealtime() - blockT0} preDone=$preDone"
                )
            } catch (e: Exception) {
                if (closed.get()) return C.RESULT_END_OF_INPUT
                // Mirror of the byte[] path: cancel before dropping,
                // ownership-gated release if the download won the race.
                if (activeSession.futures.remove(chunkIndex, future)) {
                    activeSession.forgetTouch(chunkIndex)
                    if (!future.cancel(true) && future.isDone && !future.isCancelled) {
                        try {
                            resources.releaseSessionBuffer(future.get().buffer, activeSession.chunkSize, maxPoolSize)
                        } catch (_: Exception) {
                        }
                    }
                }
                throw PlaybackNetworkFailure.chunkFailure("Failed to download chunk $chunkIndex", e, originalDataSpec)
            }
            currentChunkIndex = chunkIndex
            currentChunkReadOffset = (position % chunkSize).toInt()

            scheduleChunks()
        }

        val chunk = currentChunk ?: return C.RESULT_END_OF_INPUT
        val available = chunk.size - currentChunkReadOffset
        if (available <= 0) {
            val wasBootstrap = chunk === bootstrapChunk
            if (wasBootstrap) {
                bootstrapChunk = null
                bootstrapStartPosition = C.TIME_UNSET
            }
            currentChunk = null
            if (!wasBootstrap && position / chunkSize == currentChunkIndex) return C.RESULT_END_OF_INPUT
            return read(buffer, length)
        }

        val readSize = minOf(toRead, available)
        val copied = chunk.buffer.leased { it.copyOut(currentChunkReadOffset, buffer, readSize) }
        if (copied == null) {
            currentChunk = null
            currentChunkIndex = -1
            return LEASE_LOST
        }

        currentChunkReadOffset += readSize
        position += readSize
        bytesRemaining -= readSize
        bytesServedThisOpen += readSize
        session?.noteRead(chunkIndex)

        return readSize
    }

    /**
     * Schedule chunk 0 for [uri] onto a pending session, before the player
     * exists. Otherwise chunk 0 starts one to three seconds after the stream URL
     * is final, and the whole probe-and-build prefix is dead time from
     * chunk 0's point of view. Downloading needs neither the probe nor the file
     * length: downloadChunkOnce ranges against requestUri and, with no session
     * length yet, takes start + chunkSize.
     *
     * This instance is a throwaway scheduler: it borrows its own session field so
     * ensureChunkScheduled can run, then drops it. Ownership of the download sits
     * with the session, which outlives every instance.
     */
    internal fun prestartChunk0(uri: Uri) {
        val pending = resources.obtainPendingSession(
            uri, emptyMap(), chunkSize, sessionChunkCap, maxPoolSize, effectivePrefetchDepth
        ) ?: return
        session = pending
        try {
            ensureChunkScheduled(0L)
            Log.i(
                TAG,
                "PRESTART: scheduled chunk 0 ahead of player build " +
                    "chunkSize=${chunkSize / 1024L}KB host=${uri.host} " +
                    "pathLen=${uri.path?.length ?: -1} queryLen=${uri.query?.length ?: -1} " +
                    "uriLen=${uri.toString().length}"
            )
        } finally {
            session = null
        }
    }

    /**
     * Schedules the piece that starts at [position] onto a pending session, so a chunk session with another piece size
     * can take over there without waiting. False when another link's pre-start is waiting and must be kept.
     */
    internal fun prestartChunkAt(uri: Uri, position: Long): Boolean {
        try {
            if (resources.otherPendingSession(uri)) return false
            val pending = resources.obtainPendingSession(
                uri, emptyMap(), chunkSize, sessionChunkCap, maxPoolSize, effectivePrefetchDepth
            ) ?: return true
            session = pending
            try {
                ensureChunkScheduled(position / chunkSize)
            } finally {
                session = null
            }
            Log.i(TAG, "PRESTART: scheduled chunk ${position / chunkSize} for a piece size change " +
                "chunkSize=${chunkSize / 1024L}KB host=${uri.host}")
            return true
        } finally {
            close()
        }
    }

    /**
     * Factory for creating ParallelRangeDataSource instances.
     */
    class Factory(
        private val upstreamFactory: OkHttpDataSource.Factory,
        private val parallelConnections: Int = PlayerSettings.DEFAULT_PARALLEL_CONNECTION_COUNT,
        private val chunkSize: Long = PlayerSettings.DEFAULT_PARALLEL_CHUNK_SIZE_KB.toLong() * 1024,
        private val useNativeMemory: Boolean = false,
        private val prefetchDepthChunks: Int = parallelConnections + 1,
        private val shouldAllowBackgroundPrefetch: () -> Boolean = { true },
        private val onResolvedUri: (Uri?) -> Unit = {},
        private val allowContinuationReopen: Boolean = true,
        private val defaultRequestHeaders: Map<String, String> = emptyMap(),
        private val depthLimit: (() -> Int)? = null,
        private val receivedBytes: java.util.concurrent.atomic.AtomicLong? = null,
        private val onPiece: ((PieceTiming) -> Unit)? = null,
        private val requestMeter: RequestMeter? = null,
        private val dropStaleConnections: (() -> Unit)? = null,
        private val onRateLimited: ((Uri?, Int) -> Unit)? = null,
        private val onExtraRefused: ((Uri?, Int) -> Unit)? = null,
        private val onAllFailed: ((Uri?, AllDownloadsFailed) -> Unit)? = null,
        private val heldLimit: (() -> Long)? = null
    ) : DataSource.Factory {
        @Volatile
        private var startupBootstrapCache: BootstrapCacheEntry? = null

        /**
         * Pre-start chunk 0 through a throwaway instance of this factory, so
         * the session geometry is derived by exactly the code that will later open
         * the stream.
         */
        fun prestartChunk0(uri: Uri) {
            (createDataSource() as ParallelRangeDataSource).prestartChunk0(uri)
        }

        fun prestartAt(uri: Uri, position: Long): Boolean =
            (createDataSource() as ParallelRangeDataSource).prestartChunkAt(uri, position)

        override fun createDataSource(): DataSource {
            return ParallelRangeDataSource(
                upstreamFactory = upstreamFactory,
                parallelConnections = parallelConnections,
                chunkSize = chunkSize,
                useNativeMemory = useNativeMemory,
                prefetchDepthChunks = prefetchDepthChunks,
                shouldAllowBackgroundPrefetch = shouldAllowBackgroundPrefetch,
                onResolvedUri = onResolvedUri,
                allowContinuationReopen = allowContinuationReopen,
                defaultRequestHeaders = defaultRequestHeaders,
                depthLimit = depthLimit,
                receivedBytes = receivedBytes,
                onPiece = onPiece,
                requestMeter = requestMeter,
                dropStaleConnections = dropStaleConnections,
                onRateLimited = onRateLimited,
                onExtraRefused = onExtraRefused,
                onAllFailed = onAllFailed,
                heldLimit = heldLimit,
                consumeBootstrapCache = { dataSpec ->
                    val cached = startupBootstrapCache ?: return@ParallelRangeDataSource null
                    val isFresh = SystemClock.uptimeMillis() - cached.createdAtUptimeMs <= 15_000L
                    if (!isFresh) {
                        startupBootstrapCache = null
                        return@ParallelRangeDataSource null
                    }
                    if (cached.startPosition != 0L || dataSpec.position != 0L) return@ParallelRangeDataSource null
                    if (dataSpec.position != cached.startPosition) return@ParallelRangeDataSource null
                    if (dataSpec.uri != cached.requestUri) return@ParallelRangeDataSource null
                    cached
                },
                updateBootstrapCache = { entry ->
                    startupBootstrapCache = entry
                }
            )
        }
    }
}

/** Selected-playback byte windows. One head/tail pair, outside the chunk/native budget. */
internal object PrefetchWindowStore {
    private const val TAG = "ParallelRangeDS"
    private const val TTL_MS = 300_000L
    const val TAIL_WINDOW_BYTES = 4_194_304L

    // Selection changes and explicit playback exit clear both windows. Held payload <= 4.25 MiB;
    // active callbacks and a data source that has adopted bytes are additional owners.
    private const val STORE_CAP = 1

    fun clear() {
        synchronized(headEntries) {
            headEntries.clear()
            headTotals.clear()
        }
        synchronized(tailEntries) { tailEntries.clear() }
    }

    // Total of the last published head per URI, kept after the head is consumed so a
    // late tail from a different file is still caught. Guarded by headEntries.
    private val headTotals = object : LinkedHashMap<Uri, Long>(STORE_CAP, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Uri, Long>?): Boolean {
            return size > STORE_CAP
        }
    }

    private val headEntries = object : LinkedHashMap<Uri, ParallelRangeDataSource.BootstrapCacheEntry>(STORE_CAP, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Uri, ParallelRangeDataSource.BootstrapCacheEntry>?): Boolean {
            return size > STORE_CAP
        }
    }

    private val tailEntries = object : LinkedHashMap<Uri, ParallelRangeDataSource.BootstrapCacheEntry>(STORE_CAP, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Uri, ParallelRangeDataSource.BootstrapCacheEntry>?): Boolean {
            return size > STORE_CAP
        }
    }

    /** False when a tail for the same URI describes a file of a different length; both are dropped. */
    fun putHead(entry: ParallelRangeDataSource.BootstrapCacheEntry): Boolean {
        val tailTotal = synchronized(headEntries) {
            synchronized(tailEntries) {
                val tail = tailEntries[entry.requestUri]
                headTotals[entry.requestUri] = entry.totalFileLength
                if (tail != null && tail.totalFileLength != entry.totalFileLength) {
                    tailEntries.remove(entry.requestUri)
                    headEntries.remove(entry.requestUri)
                    tail.totalFileLength
                } else {
                    headEntries[entry.requestUri] = entry
                    null
                }
            }
        }
        if (tailTotal != null) {
            Log.w(TAG, "PREFETCH_WINDOW pair dropped head total=${entry.totalFileLength} tail total=$tailTotal")
            return false
        }
        Log.i(
            TAG,
            "PREFETCH_WINDOW put head bytes=${entry.bootstrapSize} " +
                "total=${entry.totalFileLength} host=${entry.resolvedUri?.host}"
        )
        return true
    }

    /**
     * False when the suffix answer was a whole file (start 0: smaller than the window, so not
     * the film's end) or when the head already seen for this URI has a different total.
     */
    fun putTail(entry: ParallelRangeDataSource.BootstrapCacheEntry): Boolean {
        if (entry.startPosition <= 0L) {
            Log.w(TAG, "PREFETCH_WINDOW tail declined whole file total=${entry.totalFileLength}")
            return false
        }
        val headTotal = synchronized(headEntries) {
            synchronized(tailEntries) {
                val known = headEntries[entry.requestUri]?.totalFileLength ?: headTotals[entry.requestUri]
                if (known != null && known != entry.totalFileLength) {
                    headEntries.remove(entry.requestUri)
                    tailEntries.remove(entry.requestUri)
                    known
                } else {
                    tailEntries[entry.requestUri] = entry
                    null
                }
            }
        }
        if (headTotal != null) {
            Log.w(TAG, "PREFETCH_WINDOW pair dropped head total=$headTotal tail total=${entry.totalFileLength}")
            return false
        }
        Log.i(TAG, "PREFETCH_WINDOW put tail start=${entry.startPosition} bytes=${entry.bootstrapSize}")
        return true
    }

    fun consumeHead(dataSpec: DataSpec, identity: PlaybackPrewarmIdentity?): ParallelRangeDataSource.BootstrapCacheEntry? {
        if (dataSpec.position != 0L) return null
        if (dataSpec.length != C.LENGTH_UNSET.toLong()) return null
        val cached = synchronized(headEntries) {
            val entry = headEntries[dataSpec.uri] ?: return null
            if (identity == null || entry.prewarmIdentity != identity) return null
            if (!PlayerPlaybackNetworking.canReusePrewarmFor(
                    entry.requestUri.toString(), entry.resolvedUri?.toString() ?: entry.requestUri.toString()
                )) return null
            if (SystemClock.uptimeMillis() - entry.createdAtUptimeMs > TTL_MS) {
                headEntries.remove(dataSpec.uri)
                return null
            }
            if (entry.startPosition != 0L) return null
            // One-shot: remove on hit.
            headEntries.remove(dataSpec.uri)
            entry
        }
        Log.i(TAG, "PREFETCH_WINDOW head hit bytes=${cached.bootstrapSize} total=${cached.totalFileLength}")
        return cached
    }

    /** [fileLength] is the total of the file being played; a window from any other length is never served. */
    fun peekTail(
        uri: Uri,
        position: Long,
        identity: PlaybackPrewarmIdentity?,
        fileLength: Long
    ): ParallelRangeDataSource.BootstrapCacheEntry? {
        val cached = synchronized(tailEntries) {
            val entry = tailEntries[uri] ?: return null
            if (identity == null || entry.prewarmIdentity != identity) return null
            if (entry.totalFileLength != fileLength) {
                tailEntries.remove(uri)
                Log.w(TAG, "PREFETCH_WINDOW tail dropped total=${entry.totalFileLength} file=$fileLength")
                return null
            }
            if (!PlayerPlaybackNetworking.canReusePrewarmFor(
                    entry.requestUri.toString(), entry.resolvedUri?.toString() ?: entry.requestUri.toString()
                )) return null
            if (SystemClock.uptimeMillis() - entry.createdAtUptimeMs > TTL_MS) {
                tailEntries.remove(uri)
                return null
            }
            if (position < entry.startPosition || position >= entry.startPosition + entry.bootstrapSize) return null
            // Non-clearing: leave the entry so re-seeks into the Cues stay free.
            entry
        }
        Log.i(TAG, "PREFETCH_WINDOW tail hit pos=$position start=${cached.startPosition}")
        return cached
    }

    /**
     * Non-consuming peek of the warmed head bytes for [uri], for the AFR
     * preflight to parse a video frame rate from the first 256 KiB the prewarm
     * already holds. TTL-checked and non-clearing -- the subsequent open() still
     * consumes the head via consumeHead(). Returns null on miss/expiry so the
     * preflight falls through to the post-prepare track-format AFR.
     */
    fun peekHead(uri: Uri, identity: PlaybackPrewarmIdentity?): ByteArray? {
        return synchronized(headEntries) {
            val entry = headEntries[uri] ?: return null
            if (identity == null || entry.prewarmIdentity != identity) return null
            if (!PlayerPlaybackNetworking.canReusePrewarmFor(
                    entry.requestUri.toString(), entry.resolvedUri?.toString() ?: entry.requestUri.toString()
                )) return null
            if (SystemClock.uptimeMillis() - entry.createdAtUptimeMs > TTL_MS) {
                headEntries.remove(uri)
                return null
            }
            entry.bootstrapData
        }
    }
}
