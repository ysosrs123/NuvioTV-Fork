package com.nuvio.tv.core.player.thumbnail

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.nuvio.tv.core.player.PlayerSessionTracker
import com.nuvio.tv.data.local.ImagePerformancePreferences
import com.nuvio.tv.data.mediaserver.ServerThumbnailSource
import com.nuvio.tv.ui.screens.player.ParallelRangeDataSource
import com.nuvio.tv.ui.screens.player.SlowStartServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** What the player hands the engine (ExoPlayer only). */
internal class ThumbSource(
    val url: String,
    /** Playback's request headers; never logged. */
    val headers: Map<String, String>,
    /** videoId (imdb:season:episode for episodes), else contentId or title. */
    val identity: String,
    /** File size if the stream states it; lets a saved store be opened before any network access. */
    val fileSizeHint: Long? = null,
    /** False for an autoplayed next episode: it is never held before play. */
    val holdBeforePlay: Boolean = true,
    /** Connections playback opens to the stream (the Parallel connections setting, 1 without it). */
    val playbackConnections: Int = 2,
    /** [identity] is the videoId, so it names this exact title or episode; other releases are reused only then. */
    val identityExact: Boolean = false,
    /** The player's source list, for a 4K or Usenet title's thumbnails from a copy; null where there is none. */
    val copySources: CopySources? = null,
    /** The stream is an AIOStreams Usenet stream (its link or its add-on says so). */
    val usenet: Boolean = false,
    /** A media server's stream (Silo, Jellyfin, Emby) without thumbnails of its own; see [MediaServerThumbs]. */
    val mediaServer: Boolean = false,
    /** A torrent: thumbnails only from an HTTP or Debrid copy of the title, the torrent's own pieces are left to playback. */
    val torrent: Boolean = false,
    /** Where the stream comes from and the player settings, for the "gave up" line; no link, host or name. */
    val kindNote: () -> String = { "addon HTTP" },
    val settingsNote: () -> String = { "" },
)

/** Seek thumbnails: one keyframe per slot, fetched by range request, decoded with the in-APK FFmpeg, kept per title. */
object SeekThumbnails {
    private const val TAG = "ThumbWorker"
    private const val DECODE_TAG = "ThumbDecode"
    /** One slot per 10 s, each served by the nearest real keyframe. */
    const val SPACING_MS = 10_000L
    private const val STARTUP_HOUSEKEEPING_DELAY_MS = 60_000L
    /** The player asks [takeDeferredOpen] within this long of the stream pick; later it opens the film as usual. */
    private const val DEFER_OPEN_WINDOW_MS = 4_000L
    /** Error cards and placeholders: a stream this short gets no thumbnails. */
    private const val SHORT_STREAM_US = 121_000_000L

    @Volatile private var deferOpenAskedAt = 0L
    @Volatile private var openDeferred = false

    /**
     * Player side, once, just before it would open the film: true = leave it unopened, the thumbnails screen comes up
     * from the index read early and the film opens when it ends ([loadAgainAfterPrepare]). A 4K film opened only to be
     * let go again costs seconds and, with Dolby Vision, two mode switches on the TV.
     */
    fun takeDeferredOpen(): Boolean {
        val asked = deferOpenAskedAt
        deferOpenAskedAt = 0L
        if (asked == 0L || SystemClock.elapsedRealtime() - asked > DEFER_OPEN_WINDOW_MS) return false
        openDeferred = true
        ConnectionLedger.app.noteNothingPlays(true)
        return true
    }

    /** Player side: the film is being opened now. */
    fun deferredOpenEnded() {
        openDeferred = false
        ConnectionLedger.app.noteNothingPlays(false)
    }

    val isOpenDeferred: Boolean get() = openDeferred
    private const val SPOOL_DIR = "spool"
    private const val MB = 1024L * 1024L

    /** Bumped whenever a bitmap lands in memory; the pane keys recomposition on it. */
    val tick = mutableIntStateOf(0)

    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    @Volatile private var session: Session? = null
    /** The media server's own thumbnails; while set, every picture call goes to it and no own session runs. */
    @Volatile private var server: ServerThumbSet? = null

    private val sessionLock = Any()

    /** [handBackPlayer] is false when the player screen is closing: never resume a player that is going away. */
    fun stopSession(handBackPlayer: Boolean = true) {
        stopServer(keep = true)
        val s = synchronized(sessionLock) { session.also { session = null } }
        if (s != null) ending = s
        s?.stop(handBackPlayer)
    }

    @Volatile private var ending: Session? = null

    /** Waits (bounded) for an ended session's worker. Reads are cancelled repeatedly: an index read is several in a row. */
    private suspend fun awaitStoppedSession() {
        val s = ending ?: return
        val j = s.workerJob
        val c = s.copyJob
        if (j != null || c != null) {
            withTimeoutOrNull(10_000L) {
                while (j?.isCompleted == false || c?.isCompleted == false) {
                    s.cancelReads()
                    delay(200L)
                }
            }
        }
        if (ending === s && (j == null || j.isCompleted)) ending = null
    }

    /** "Clear saved thumbnails": no session may write while the files are deleted. */
    internal suspend fun stopForClear() {
        stopSession()
        awaitStoppedSession()
    }

    /** The player screen is resumed; playback is never restarted while it is in the background. */
    @Volatile var hostResumed = true
        set(value) {
            if (field && !value) {
                hostPausedAt = SystemClock.elapsedRealtime()
                session?.notePrepareLeft()
            }
            field = value
        }

    /** When the player screen last went to the background (elapsedRealtime), 0 = never. */
    @Volatile var hostPausedAt = 0L
        private set

    /** Player screen closed: an unfinished 4K coverage pass carries on for a few minutes, anything else stops. */
    fun playerClosed() {
        prepareUi.value = PrepareUi.Hidden
        dropEarlyRow()
        stopServer(keep = false)
        ServerThumbSet.dropRetained()
        val s = session ?: return
        if (!s.finishAfterPlayback()) stopSession(handBackPlayer = false)
    }

    private fun stopServer(keep: Boolean) {
        val s = synchronized(sessionLock) { server.also { server = null } } ?: return
        s.stop(keep)
        bumpTick()
    }

    /**
     * A media-server title: uses the server's own thumbnails when it has them for this file version (HLS transcodes
     * included). False when it has none, so the caller starts our own engine. Never writes to the store.
     */
    internal suspend fun startServerWhenEligible(
        context: Context,
        source: ServerThumbnailSource,
        playerProvider: () -> ExoPlayer?,
    ): Boolean {
        val set = withContext(Dispatchers.IO) {
            MemoryGovernor.init(context)
            val rgb565 = runCatching { ImagePerformancePreferences(context).rgb565Enabled }.getOrDefault(true)
            ServerThumbSet.open(source, rgb565, playerProvider, ::bumpTick)
        } ?: return false
        currentCoroutineContext().ensureActive()
        stopSession()
        synchronized(sessionLock) { server = set }
        set.start()
        bumpTick()
        return true
    }

    /** Thumbnails are off or this stream gets none: memory kept for a restart of a server title is let go. */
    fun dropServerMemory() {
        ServerThumbSet.dropRetained()
    }

    private fun sessionEnded(s: Session) {
        ending = s
        synchronized(sessionLock) { if (session === s) session = null }
    }

    /**
     * Starts a session for [source] once the player has a format and duration (waits up to 30 s).
     * [skipReason] is asked once the duration is known; non-null means this stream gets no thumbnails.
     */
    internal suspend fun startWhenEligible(
        context: Context,
        source: ThumbSource,
        playerProvider: () -> ExoPlayer?,
        reserveProvider: () -> PlaybackBufferReserve? = { null },
        pauseForPrepare: () -> Boolean? = { false },
        letGoForPrepare: () -> Boolean = { false },
        loadAgainAfterPrepare: (play: Boolean) -> Unit = {},
        skipReason: () -> String? = { null },
    ) {
        ServerThumbSet.dropRetained()
        if (!isThumbnailSource(source.url)) {
            Log.i(TAG, "skip: not a single-file stream")
            CopyPrePick.drop(source.identity)
            return
        }
        // Marks the title as starting, so a next-episode pass cannot begin writing the same store meanwhile.
        pendingIdentity = source.identity
        try {
            startWhenEligibleInner(context, source, playerProvider, reserveProvider, pauseForPrepare, letGoForPrepare,
                loadAgainAfterPrepare, skipReason)
        } finally {
            if (pendingIdentity == source.identity) pendingIdentity = null
        }
    }

    @Volatile private var pendingIdentity: String? = null

    private val screenGuard = PrepareScreenGuard()
    private var screenGuardJob: Job? = null

    /** What on the generating screen counts as progress; null when it is not shown. */
    private fun progressKey(ui: PrepareUi): Any? = when (ui) {
        is PrepareUi.Hidden -> null
        is PrepareUi.Checking -> "checking"
        is PrepareUi.Generating -> ui.done
        is PrepareUi.Ready -> "ready"
    }

    /** Runs while a session runs or the screen is up: a screen left without a session or without progress ends. */
    private fun watchPrepareScreen() {
        mainScope.launch {
            if (screenGuardJob?.isActive == true) return@launch
            screenGuard.reset()
            screenGuardJob = mainScope.launch {
                while (true) {
                    val ui = prepareUi.value
                    val owned = session != null || pendingIdentity != null
                    if (ui is PrepareUi.Hidden && !owned) break
                    val now = SystemClock.elapsedRealtime()
                    val end = screenGuard.note(now, progressKey(ui), owned, hostResumed)
                    val stillMs = screenGuard.stillForMs(now)
                    when (end) {
                        PrepareScreenGuard.End.NO_SESSION -> {
                            Log.w(TAG, "prepare: generating screen left without a thumbnails session - ended, the film shows")
                            earlyScreenFor = null
                            prepareUi.value = PrepareUi.Hidden
                            screenGuard.reset()
                        }
                        PrepareScreenGuard.End.NO_PROGRESS -> {
                            Log.w(TAG, "prepare: generating screen without progress for ${stillMs / 1000} s - ended, the film shows")
                            startWatchingNow()
                            prepareUi.value = PrepareUi.Hidden
                            screenGuard.reset()
                        }
                        null -> Unit
                    }
                    delay(PrepareScreenGuard.POLL_MS)
                }
                screenGuard.reset()
            }
        }
    }

    private suspend fun startWhenEligibleInner(
        context: Context,
        source: ThumbSource,
        playerProvider: () -> ExoPlayer?,
        reserveProvider: () -> PlaybackBufferReserve?,
        pauseForPrepare: () -> Boolean?,
        letGoForPrepare: () -> Boolean,
        loadAgainAfterPrepare: (play: Boolean) -> Unit,
        skipReason: () -> String?,
    ) {
        NextEpisodeThumbs.stopAndJoinFor(source.identity)
        HardwareThumbSwitch.refresh(context)
        val early = showsScreenEarly(context, source)
        val prefetch = if (early) prefetchIndex(context, source) else null
        if (prefetch != null) deferOpenAskedAt = SystemClock.elapsedRealtime()
        // A title with thumbnails saved but not finished gets its screen later: its copy is opened at the pick all the same.
        val head = if (early || screenLikelyLater(context, source)) {
            copyHeadStart(context, source, playerProvider)
        } else {
            CopyPrePick.drop(source.identity)
            null
        }
        var started = false
        try {
            started = startWhenEligibleAfterEarly(context, source, playerProvider, reserveProvider, pauseForPrepare,
                letGoForPrepare, loadAgainAfterPrepare, skipReason, early, prefetch, head)
        } finally {
            deferOpenAskedAt = 0L
            if (!started && openDeferred) withContext(NonCancellable + Dispatchers.Main) {
                Log.i(TAG, "prepare: no thumbnails session - the film opens now")
                runCatching { loadAgainAfterPrepare(true) }
            }
            if (!started) {
                head?.release()
                prefetch?.let { p -> prefetchScope.launch { runCatching { p.await()?.reader?.close() }; p.cancel() } }
                if (early) withContext(NonCancellable) { dropEarlyScreen() }
            }
        }
    }

    /** Main thread state: the screen shown before the session could decide, and "start now" pressed meanwhile. */
    @Volatile private var earlyScreenFor: String? = null
    @Volatile private var prepareDeclinedFor: String? = null
    private val prefetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * The generating screen at once, while the player still opens the stream, when this title will most likely get
     * it: before-play on, all titles, a held start, not a media server away from home, nothing saved for it yet.
     */
    private suspend fun showsScreenEarly(context: Context, source: ThumbSource): Boolean {
        val shows = withContext(Dispatchers.IO) {
            runCatching {
                SeekThumbnailPreferences.prepareMode(context) != ThumbPrepareMode.OFF &&
                    SeekThumbnailPreferences.mode(context) == SeekThumbMode.ALL &&
                    source.holdBeforePlay &&
                    !MediaServerThumbs.onDemandOnly(source.mediaServer, source.url) &&
                    !RateLimitedHosts.appSession.contains(source.url) &&
                    source.fileSizeHint?.takeIf { it > 0 }?.let {
                        ThumbStore.isMarkedComplete(context, source.identity, it) ||
                            ThumbStore.exists(context, source.identity, it)
                    } != true
            }.getOrDefault(false)
        }
        prepareDeclinedFor = null
        if (!shows) return false
        withContext(Dispatchers.Main) {
            earlyScreenFor = source.identity
            if (session == null) prepareUi.value = PrepareUi.Checking
            watchPrepareScreen()
        }
        Log.i(TAG, "prepare: screen shown while the player opens the stream")
        return true
    }

    /** Like [showsScreenEarly], for a title with a saved set that is not complete: the screen comes after the first frame. */
    private suspend fun screenLikelyLater(context: Context, source: ThumbSource): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            SeekThumbnailPreferences.prepareMode(context) != ThumbPrepareMode.OFF &&
                SeekThumbnailPreferences.mode(context) == SeekThumbMode.ALL &&
                source.holdBeforePlay &&
                !MediaServerThumbs.onDemandOnly(source.mediaServer, source.url) &&
                !RateLimitedHosts.appSession.contains(source.url) &&
                source.fileSizeHint?.takeIf { it > 0 }?.let { !ThumbStore.isMarkedComplete(context, source.identity, it) } == true
        }.getOrDefault(false)
    }

    private suspend fun dropEarlyScreen() = withContext(Dispatchers.Main) {
        earlyScreenFor = null
        if (prepareUi.value is PrepareUi.Checking && session?.prepareActive() != true) prepareUi.value = PrepareUi.Hidden
    }

    private class Prefetched(val url: String, val reader: HttpRangeReader, val head: ByteArray, val index: MediaIndex?)

    /**
     * The top copy's link opened at the stream pick, while the player opens the film, when the generating screen will
     * most likely come up; the one opened while the stream list was shown when it fits ([CopyPrePick]). Usenet, torrent
     * and away-server titles keep their own copy paths.
     */
    private suspend fun copyHeadStart(context: Context, source: ThumbSource, playerProvider: () -> ExoPlayer?): CopyHeadStart? {
        val sources = source.copySources
        if (sources == null || !source.identityExact || source.usenet || source.torrent ||
            MediaServerThumbs.onDemandOnly(source.mediaServer, source.url)) {
            CopyPrePick.drop(source.identity)
            return null
        }
        val book = runCatching { CopySourceBooks.app(context) }.getOrNull()
        val startsAt: suspend () -> Long = {
            withContext(Dispatchers.Main) { playerProvider()?.currentPosition?.coerceAtLeast(0L) ?: 0L }
        }
        CopyPrePick.take(source.identity)?.let { held ->
            Log.i(TAG, "copy first: the copy opened while the stream list was shown is used")
            held.earlyFrom(prefetchScope, startsAt)
            return held
        }
        return CopyHeadStart(source.identity).also {
            it.start(prefetchScope, sources, book, source.fileSizeHint, anchorMs = startsAt, early = true) { kf, ptsMs, bmp ->
                offerEarlyPicture(source.identity, kf, ptsMs, bmp)
            }
        }
    }

    /** Header and keyframe index read while the player opens the same stream; strong boxes, nothing saved for it yet. */
    private fun prefetchIndex(context: Context, source: ThumbSource): Deferred<Prefetched?>? {
        MemoryGovernor.init(context)
        if (!MemoryGovernor.isStrongHardware) return null
        val size = source.fileSizeHint?.takeIf { it > 0 }
        if (size != null && runCatching { ThumbStore.exists(context, source.identity, size) }.getOrDefault(true)) return null
        return prefetchScope.async {
            ConnectionLedger.app.attach(PrefsLedgerMemory(context))
            val t0 = SystemClock.elapsedRealtime()
            val r = HttpRangeReader(source.url, source.headers, thumbnailHttpClient(source.headers),
                slotWaitMs = ConnectionLedger.START_SLOT_WAIT_MS)
            runCatching {
                val head = r.read(0, MkvIndexReader.HEAD_BYTES)
                val idx = when {
                    MkvIndexReader.looksLikeMatroska(head) -> MkvIndexReader.read(r, head)
                    AviIndexReader.looksLikeAvi(head) -> AviIndexReader.read(r, head)
                    Mp4IndexReader.looksLikeMp4(head) -> Mp4IndexReader.read(r, head)
                    else -> null
                }
                Log.i(TAG, "index read early in ${SystemClock.elapsedRealtime() - t0} ms (${r.bytes / 1024} KB)")
                Prefetched(source.url, r, head, idx)
            }.onFailure {
                r.close()
                Log.i(TAG, "early index read failed (${it.javaClass.simpleName}) - the session reads it")
            }.getOrNull()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun startWhenEligibleAfterEarly(
        context: Context,
        source: ThumbSource,
        playerProvider: () -> ExoPlayer?,
        reserveProvider: () -> PlaybackBufferReserve?,
        pauseForPrepare: () -> Boolean?,
        letGoForPrepare: () -> Boolean,
        loadAgainAfterPrepare: (play: Boolean) -> Unit,
        skipReason: () -> String?,
        early: Boolean,
        prefetch: Deferred<Prefetched?>?,
        head: CopyHeadStart? = null,
    ): Boolean {
        val t0 = SystemClock.elapsedRealtime()
        var width = 0
        var height = 0
        var video = ""
        var ready = false
        while (SystemClock.elapsedRealtime() - t0 < 30_000L) {
            // The film may wait unopened for the screen: the index read early has what the session needs.
            val indexed = prefetch?.takeIf { it.isCompleted }?.let { runCatching { it.getCompleted() }.getOrNull() }?.index
            if (indexed != null && indexed.video.width > 0 && indexed.durationUs > 0 && playerProvider()?.videoFormat == null) {
                if (indexed.durationUs < SHORT_STREAM_US) {
                    Log.i(TAG, "skip: under 2 minutes (index read early)")
                    return false
                }
                width = indexed.video.width
                height = indexed.video.height
                video = "${indexed.video.width}x${indexed.video.height} ${indexed.video.codec.name}" +
                    (indexed.video.dolbyVision?.let { " Dolby Vision ${it.profile}" } ?: "")
                ready = true
                Log.i(TAG, "session starts from the index read early (${SystemClock.elapsedRealtime() - t0} ms)" +
                    if (openDeferred) ", the film opens when the screen ends" else "")
                break
            }
            val p = playerProvider()
            val fmt = p?.videoFormat
            if (p != null && fmt != null && p.duration != C.TIME_UNSET && p.duration > 0) {
                width = fmt.width
                height = fmt.height
                video = GiveUpNotes.videoNote(fmt.width, fmt.height, fmt.sampleMimeType, fmt.codecs,
                    fmt.colorInfo?.colorTransfer ?: Format.NO_VALUE)
                ready = true
                break
            }
            delay(500L)
        }
        if (!ready) {
            Log.i(TAG, "skip: ExoPlayer format/duration not available in time")
            return false
        }
        fun skipped(): Boolean = skipReason()?.also { Log.i(TAG, "skip: $it") } != null
        if (skipped()) return false
        // Decide on 4K before any network access: a 4K remux's index alone can be several MB.
        val fourK = MemoryGovernor.tierFor(width, height) == DecodeTier.SW_4K
        if (fourK && SeekThumbnailPreferences.mode(context) != SeekThumbMode.ALL) {
            Log.i(TAG, "skip: ${width}x$height and the setting is HD titles only")
            return false
        }
        // Loading the native library takes 150-440 ms: never on the main thread.
        if (!withContext(Dispatchers.IO) { ThumbNative.load() }) {
            Log.i(TAG, "skip: native thumbnail decoder unavailable (${ThumbNative.state()})")
            return false
        }
        stopSession()
        awaitStoppedSession()          // one writer per store
        withContext(Dispatchers.IO) { MemoryGovernor.initForSession(context) }
        // The stream can be rejected while the decoder loads.
        if (skipped()) return false
        val s = Session(context.applicationContext, source, playerProvider, reserveProvider, fourK, pauseForPrepare, video,
            letGoForPrepare, loadAgainAfterPrepare, earlyScreen = early, prefetch = prefetch, headStart = head)
        synchronized(sessionLock) { session = s }
        s.start()
        return true
    }

    fun thumbFor(positionMs: Long): Bitmap? = shownFor(positionMs)?.bitmap

    /** MemAvailable the coverage pass needs while it is generating, else 0. Any thread. */
    fun memoryFloorBytes(): Long = session?.memoryFloorBytes() ?: 0L

    fun pausesPlaybackWhileScrubbing(): Boolean = server != null || session != null

    internal fun isCurrentOrStarting(identity: String): Boolean =
        session?.identity == identity || pendingIdentity == identity

    /** A small box is still working on a 4K title's coverage; the next-episode pass waits for it. */
    internal fun currentSmall4KBusy(): Boolean = session?.small4KBusy() == true

    /** Small boxes, 4K: coverage progress (0-99 %) for the paused hint, else null. */
    fun progressPercent(): Int? = if (server != null) null else session?.progressPercent()

    /** What the player screen shows for "Generate thumbnails before play". Main-thread Compose state. */
    sealed class PrepareUi {
        object Hidden : PrepareUi()
        object Checking : PrepareUi()
        data class Generating(
            val done: Int, val total: Int, val secondsLeft: Int, val anchorMs: Long, val spacingMs: Long,
            /** Positions (ms) of the newest thumbnails, oldest first. */
            val recent: List<Long> = emptyList(),
            /** Coverage slots in time order: made or not. */
            val coverage: List<Boolean> = emptyList(),
            val recentEndSeq: Int = 0,
            /** Small boxes, 4K: the film is not loaded behind the screen and loads when it ends. */
            val filmLoadsAfter: Boolean = false,
        ) : PrepareUi()
        data class Ready(val total: Int) : PrepareUi()
    }

    val prepareUi = mutableStateOf<PrepareUi>(PrepareUi.Hidden)

    /** The copy's pictures made before the session, for the generating screen's row ([CopyEarlyRow]). Main thread. */
    internal val earlyRow = mutableStateOf<CopyEarlyRow.Row<Bitmap>?>(null)
    private var earlyRowJob: Job? = null
    private const val EARLY_RECYCLE_DELAY_MS = 1_000L

    /** The title the generating screen is up for, or about to be. */
    internal fun screenIdentity(): String? = session?.takeIf { it.prepareActive() }?.identity ?: earlyScreenFor

    /** Any thread: [bmp] stays the caller's; a small copy of it goes into the row while the screen checks for [identity]. */
    private fun offerEarlyPicture(identity: String, kf: Int, ptsMs: Long, bmp: Bitmap) {
        if (!CopyEarlyRow.wanted(identity, screenIdentity(), prepareUi.value) || bmp.isRecycled) return
        val size = CopyEarlyRow.scaledSize(bmp.width, bmp.height)
        if (size[0] <= 0) return
        val small = runCatching {
            Bitmap.createBitmap(size[0], size[1], Bitmap.Config.RGB_565).also {
                Canvas(it).drawBitmap(bmp, null, Rect(0, 0, size[0], size[1]), Paint(Paint.FILTER_BITMAP_FLAG))
            }
        }.getOrNull() ?: return
        mainScope.launch {
            if (!CopyEarlyRow.wanted(identity, screenIdentity(), prepareUi.value)) {
                small.recycle()
                return@launch
            }
            val added = CopyEarlyRow.add(earlyRow.value, identity, kf, ptsMs, small)
            earlyRow.value = added.row
            recycleLater(added.dropped)
            if (earlyRowJob?.isActive != true) earlyRowJob = mainScope.launch {
                snapshotFlow { prepareUi.value }.first { CopyEarlyRow.ends(it) }
                dropEarlyRow()
            }
        }
    }

    /** Main thread. */
    private fun dropEarlyRow() {
        val row = earlyRow.value ?: return
        earlyRow.value = null
        recycleLater(row.frames.map { it.picture })
    }

    /** Main thread: recycled once the row has let go of them on screen. */
    private fun recycleLater(bitmaps: List<Bitmap>) {
        if (bitmaps.isEmpty()) return
        mainScope.launch {
            delay(EARLY_RECYCLE_DELAY_MS)
            bitmaps.forEach { if (!it.isRecycled) it.recycle() }
        }
    }

    /** Applied on Main only if [from] is still current and preparing, so a late post cannot re-show a dismissed overlay. */
    private fun postPrepareUi(from: Session, v: PrepareUi) {
        mainScope.launch {
            val current = session
            val stageOk = when (v) {
                is PrepareUi.Generating -> from.prepareStageNow() == 3
                is PrepareUi.Ready -> from.prepareStageNow() == 4
                else -> true
            }
            val ok = if (v is PrepareUi.Hidden) current == null || current === from
            else current === from && from.prepareActive() && stageOk
            if (ok) prepareUi.value = v
        }
    }

    /** Main thread: "Start watching now" (or Back) on the generating screen. */
    fun startWatchingNow() {
        val s = session
        if (s == null || !s.prepareActive() && earlyScreenFor == s.identity) {
            earlyScreenFor?.let { prepareDeclinedFor = it }
            earlyScreenFor = null
            prepareUi.value = PrepareUi.Hidden
            if (s == null) return
        }
        s.startWatchingNow()
    }

    fun exactThumb(positionMs: Long): Bitmap? = shownFor(positionMs)?.takeIf { it.exact }?.bitmap

    /**
     * A picture for the pane. [ptsMs] is its keyframe's time; [exact] is false for a nearby stand-in. [borrowed]: from
     * another release of the title, not confirmed yet.
     */
    class Shown(
        val bitmap: Bitmap, val ptsMs: Long, val exact: Boolean, val crop: ThumbCrop.Bars?, val borrowed: Boolean = false,
    )

    fun shownFor(positionMs: Long): Shown? {
        server?.let { return it.shownFor(positionMs) }
        return session?.shownFor(positionMs)
    }

    /** Spacing of the seek strip's tiles once the index is read, else null (the single picture shows). */
    fun stripSpacingMs(): Long? {
        server?.let { return it.stripSpacingMs() }
        return session?.stripSpacingMs()
    }

    /** Strip tile for [positionMs]: memory only, a disk hit loads async. */
    fun tileFor(positionMs: Long): Shown? {
        server?.let { return it.tileFor(positionMs) }
        return session?.tileFor(positionMs)
    }

    /** Loader thread, called per video sample: should the next samples be copied? Must be cheap. */
    internal fun tapWants(timeUs: Long): Boolean = session?.tapWants(timeUs) ?: false

    /** Loader thread: a keyframe sample playback just downloaded. Written to the spool off-thread. */
    internal fun tapOffer(timeUs: Long, sample: ByteArray) {
        session?.tapOffer(timeUs, sample)
    }

    /** Keyframe time of [targetMs]'s slot, so a committed seek lands on the frame the pane shows. */
    fun landingFor(targetMs: Long): Long? = if (server != null) null else session?.landingFor(targetMs)

    /** The next point of the 10 s grid in the direction of [deltaMs], or null without a session. */
    fun gridStep(baseMs: Long, deltaMs: Long): Long? {
        server?.let { srv ->
            val spacing = srv.stripSpacingMs()
            if (deltaMs == 0L || spacing <= 0L || spacing > abs(deltaMs)) return null
            return SeekGrid.gridPoint(baseMs, deltaMs, spacing)
        }
        val s = session
        if (s?.hasSlots != true || deltaMs == 0L) return null
        return s.stepPastBase(SeekGrid.gridPoint(baseMs, deltaMs, SPACING_MS), baseMs, forward = deltaMs > 0)
    }

    fun displayMemoryChargeMb(context: Context, mode: SeekThumbMode): Int {
        MemoryGovernor.init(context)
        val rgb565 = runCatching { ImagePerformancePreferences(context).rgb565Enabled }.getOrDefault(true)
        return MemoryGovernor.displayChargeMb(rgb565, fourK = mode == SeekThumbMode.ALL)
    }

    /** Bytes of saved thumbnails on disk. Call on an IO thread. */
    fun storedBytes(context: Context): Long = ThumbStore.totalBytes(context)

    /** Retention and budget clean-up of stored thumbnails, delayed so it stays off cold start. */
    fun scheduleStartupHousekeeping(context: Context) {
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            delay(STARTUP_HOUSEKEEPING_DELAY_MS)
            runCatching { ThumbStore.housekeeping(app, activeKey = session?.storeKey) }
            runCatching {   // spools left behind when the process was killed
                val active = session?.spoolDirPath
                File(File(app.cacheDir, ThumbStore.DIR), SPOOL_DIR).listFiles()
                    ?.filter { it.absolutePath != active }?.forEach { it.deleteRecursively() }
            }
                .onFailure { Log.w(TAG, "startup housekeeping failed: ${it.javaClass.simpleName}") }
        }
    }

    /** Width / height of this title's thumbnails while a session runs, else null. Sizes the pane's empty frame. */
    fun frameAspect(): Float? {
        server?.let { return it.frameAspect() }
        return session?.frameAspect()
    }

    fun notePriority(positionMs: Long) {
        server?.notePriority(positionMs)
        session?.notePriority(positionMs)
    }

    /** Store-key pairs whose pictures did not match in this app run. */
    private val differentReleases = ConcurrentHashMap.newKeySet<String>()

    private fun releasePair(a: String, b: String): String = if (a < b) "$a|$b" else "$b|$a"

    private fun bumpTick() {
        mainScope.launch { tick.intValue++ }
    }

    /** Snapshot of the player, sampled on the main thread (ExoPlayer is thread-confined). */
    private class PlayerState(
        val isPlaying: Boolean,
        val buffering: Boolean,
        val positionMs: Long,
        val bufferedMs: Long,
        val durationMs: Long,
        val failed: Boolean = false,
        val playWhenReady: Boolean = isPlaying,
        val loading: Boolean = false,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    private class Session(
        private val context: Context,
        private val source: ThumbSource,
        private val playerProvider: () -> ExoPlayer?,
        private val reserveProvider: () -> PlaybackBufferReserve?,
        private val fourK: Boolean,
        /** Main thread: pauses as a user pause, so start-up autoplay leaves it alone. False = not paused. */
        private val pauseForPrepare: () -> Boolean? = { false },
        private val video: String = "",
        /** Main thread: the held player lets go of its decoders and buffer, keeping the title and position. */
        private val letGoForPrepare: () -> Boolean = { false },
        /** Main thread: a player let go loads again where it was, and plays when asked. */
        private val loadAgainAfterPrepare: (play: Boolean) -> Unit = {},
        /** The generating screen was put up before this session could decide; it takes it down if it does not hold. */
        private val earlyScreen: Boolean = false,
        private val prefetch: Deferred<Prefetched?>? = null,
        /** The top copy's link, opened at the stream pick ([CopyHeadStart]). */
        private val headStart: CopyHeadStart? = null,
    ) {
        private companion object {
            const val HEAD_BYTES = MkvIndexReader.HEAD_BYTES
            const val HEAVY_1080P_MIN_BITS_PER_SECOND = 20_000_000L
            /** Above this average bitrate a 4K file is treated as a remux (WEB-DL 4K is ~15-25 Mb/s). */
            const val REMUX_MIN_BITS_PER_SECOND = 35_000_000L
            const val GATE_BUFFER_AHEAD_MS = 14_000L
            const val PLATEAU_WINDOW_MS = 10_000L
            const val PLATEAU_JITTER_MS = 2_000L
            const val PLATEAU_MIN_AHEAD_MS = 8_000L
            /** Coverage-pass byte budget per session. Scrubbing does not count against it. */
            const val LATTICE_MAX_BYTES = 300L * 1024 * 1024
            /** A 2.5 h 4K WEB-DL needs about 500 MB at 10 s, a remux about 320 MB at 30 s. */
            const val LATTICE_4K_MAX_BYTES = 500L * 1024 * 1024
            const val SCRUB_REQUEST_ALLOWANCE = 250
            const val IDLE_TRIM_MS = 10_000L
            const val MAX_ATTEMPTS_PER_KEYFRAME = 2
            /** Network failures in a row before no new thumbnails are fetched; the ones made keep showing. */
            const val MAX_FETCH_FAILURES = 5
            /** Tries of the header and index reads before the session gives up. */
            const val START_READ_TRIES = 4
            /** Header and index reads that found no free connection before the session gives up. */
            const val START_SLOT_TRIES = 4
            const val INDEX_GATE_AHEAD_MS = 10_000L
            const val DEFER_WAIT_PRIORITY_MS = 2_000L
            const val DEFER_WAIT_LATTICE_MS = 5_000L
            /** Arm the tap this long before a wanted keyframe's pts (decode order runs ahead of it). */
            const val TAP_ARM_LEAD_US = 3_000_000L
            /** A tapped keyframe must match an index keyframe this closely, else the timelines differ. */
            const val TAP_MATCH_US = 50_000L
            const val SPOOL_MAX_BYTES = 64L * 1024 * 1024
            const val SPOOL_MAX_PENDING = 3
            const val DEFER_LOG_INTERVAL_MS = 30_000L
            /** A stand-in is at most [fallbackSlots] away: 30 s, or half the finest stride. Farther shows nothing. */
            const val MIN_FALLBACK_SLOTS = 3
            const val STRIP_RECHECK_MS = 500L
            /** Slots decoded either side of the position once the saved pictures are in memory. */
            const val PRELOAD_WARM_SLOTS = 6
            /** A 4K decode needs about 80 MB. Only taken once the read-ahead is [RESERVE_MIN_AHEAD_MS] ahead. */
            const val RESERVE_MIN_BYTES = 96L * 1024 * 1024
            const val RESERVE_MARGIN_MB = 32L
            const val RESERVE_MIN_AHEAD_MS = 10_000L
            const val RESERVE_MAX_MS = 15L * 60 * 1000
            const val RESERVE_PURGE_MIN_BYTES = 8L * 1024 * 1024
            const val RESERVE_PURGE_INTERVAL_MS = 5_000L
            const val RESERVE_LOG_INTERVAL_MS = 20_000L
            const val RESERVE_NO_GAIN_MS = 60_000L
            const val RESERVE_RETRY_MS = 60_000L
            /** A settle target refused this many times in a row yields to the coverage pass until the next scrub. */
            const val P0_MAX_DEFERRALS = 3
            /** Small boxes, 4K coverage while playing: pause after each picture, doubled on dropped frames. */
            const val LATTICE_4K_PLAYING_PACE_MS = 4_000L
            /** Small boxes: a read-ahead at or below this share of its budget leaves room for a 4K decode. */
            const val NATURAL_FUNDING_PERCENT = 40L
            /** Dropped frames this soon after a seek, a resume or a scrub step are not blamed on thumbnails. */
            const val SEEK_QUIET_MS = 5_000L
            const val SEEK_JUMP_MS = 1_500L
            const val MONITOR_INTERVAL_MS = 1_000L
            const val PACE_LOG_INTERVAL_MS = 60_000L
            const val CLOCK_SAMPLE_MS = 5_000L
            const val CLOCK_HOLD_MS = 30_000L
            /** Highest allowed clock seen per core since the app started, so a later session can start capped. */
            val clockPeakKhz = AtomicLongArray(Runtime.getRuntime().availableProcessors())
            const val PREPARE_READY_MS = 1_600L
            /** The generating screen renews playback's yield this often (it lapses after [PlaybackYield.LEASE_MS]). */
            const val YIELD_RENEW_MS = 500L
            const val PREPARE_SECONDS_REMUX = 1.3
            const val PREPARE_SECONDS_WEB = 1.0
            const val PREPARE_SECONDS_HD = 0.6
            const val PREPARE_SECONDS_STRONG_4K = 0.9
            /** A held seek within this long keeps the pause cap after the coverage is done. */
            const val SCRUB_CAP_WINDOW_MS = 5_000L
            const val REANCHOR_JUMP_MS = 2L * 60 * 1000
            /** The coverage pass starts with this window around the playback position. */
            const val LOCAL_BEFORE_MS = 10L * 60 * 1000
            const val LOCAL_AFTER_MS = 20L * 60 * 1000
            const val AFTER_PLAYBACK_MAX_MS = 10L * 60 * 1000
            const val MAX_DISK_LOADS_IN_FLIGHT = 4
            /** Another release's set is tried only with at least this many pictures. */
            const val MIN_BORROW_PICTURES = 12
            /** The generating screen waits this long on the picture check before it starts generating. */
            const val CHECK_HOLD_MS = 30_000L
            const val ADOPT_CHUNK = 64
            const val FITTED_SHOWN_KEPT = 6
            /** A copy's keyframe (WEB, 3-4 s apart) may sit this far from where the 4K seek lands. */
            const val COPY_WIDE_US = 2_000_000L
            const val COPY_ADOPT_EVERY = 24
            const val COPY_CHECK_REFILL_EVERY = 6
            /** Small boxes, copy decodes (1080p) while playing: pause after each picture, doubled on dropped frames. */
            const val COPY_PLAYING_PACE_MS = 1_000L
            const val COPY_TAG = "ThumbCopy"
            /** Strong boxes: the own 4K pass waits for the copy only while the copy makes progress. */
            const val COPY_STALL_HOLD_MS = 3L * 60 * 1000
            /** A Usenet title's generating screen waits this long for a copy to be confirmed before playing. */
            const val USENET_COPY_CHECK_MS = 40_000L
            /**
             * A media server away from home without thumbnails of its own waits for a copy's: a screen still without a
             * copy picture this long after it came up ends, the copy carries on during play.
             */
            const val AWAY_EMPTY_SCREEN_MS = 25_000L
            const val AWAY_PROBE_MS = 3_000L
            const val AWAY_PROBE_LATE_MS = 5L * 60 * 1000
            const val AWAY_PROBE_POLL_MS = 2_000L
            const val AWAY_REJECT_LOGS = 12
            /** The player's memory reaches MemAvailable a moment after it lets go. */
            const val LET_GO_SETTLE_MS = 1_000L
        }

        private enum class Outcome { DONE, FAILED, POSTPONED }

        private class Fetched(
            val au: ThumbAu?, val error: Exception?, val requests: Int, val bytes: Long, val ms: Long, val startedAt: Long,
        )

        /** [hardware]: made by the hardware decoder. [hardwareMiss]: it missed this picture with several decoders at work. */
        private class Decoded(
            val bitmap: Bitmap?, val decodeMs: Long, val renderMs: Long, val cold: Boolean, val width: Int, val height: Int,
            val reason: String, val error: Throwable? = null, val hardware: Boolean = false, val hardwareMiss: Boolean = false,
        )

        /** A keyframe handed to a decode lane; the worker stores and logs it once [result] is in. */
        private class DecodeJob(
            val idx: MediaIndex, val st: ThumbStore, val slot: Int, val kf: Int, val priority: Int, val lane: DecoderLane,
            val tapped: Boolean, val auBytes: Int, val requests: Int, val bytes: Long, val fetchMs: Long, val waitedMs: Long,
            val lanes: Int, val concurrent: Int, val result: Deferred<Decoded>, val hardware: Boolean = false,
        )

        private var latticeStrides = PrepareGrid.LATTICE_1080P
        /** The before-play grid in slots: what the generating screen and the paused progress hint count. */
        private var screenStride = 3
        private var beforePlay = ThumbsBeforePlay.FASTER
        private var fallbackSlots = MIN_FALLBACK_SLOTS
        /** UI thread: the strip's stride in slots and when it was last worked out. */
        private var stripStride = 0
        private var stripCheckedAt = 0L
        /** 4K keyframes cost about 1 s each on small boxes: fewer settle neighbours. */
        private val priorityRadius = if (fourK) 2 else 3
        /** False while 4K may not decode (playing on a small box); the pane reads it. */
        @Volatile private var decodeOpen = !fourK
        @Volatile private var reserveHeld: PlaybackBufferReserve? = null
        private var reserveBlocked = false
        private var reserveSince = 0L
        private var reservePictures = 0
        private var reserveFreed = 0L
        private var reservePurgedAt = 0L
        private var lastPurgeAt = 0L
        private var lastReserveLogAt = 0L
        private var reserveRetryAt = 0L
        /** The last decode was refused for swap, which a reserve cannot free. */
        @Volatile private var refusedForSwap = false
        private val swapHolds = SwapHolds()
        private var fundedSince = 0L
        private var fundedPictures = 0
        private var p0Deferrals = 0
        private var p0DeferralSlot = -1
        /** Small box, no reserve, read-ahead small enough anyway. Re-checked before every decode. */
        @Volatile private var naturallyFunded = false
        /** Fed on Main by [monitorPlayback], read by the worker: always under synchronized(pacer). */
        private val pacer = PlaybackDecodePacer(LATTICE_4K_PLAYING_PACE_MS)
        /** The same for the copy pass's decodes; under synchronized(copyPacer). */
        private val copyPacer = PlaybackDecodePacer(COPY_PLAYING_PACE_MS)
        @Volatile private var copy: CopyPass? = null
        @Volatile private var copyAllowed = false
        /**
         * Thumbnails from a lighter copy first ([CopyFirst]): every title with a source list except Usenet, torrent and
         * away-server ones, which have their own copy paths.
         */
        @Volatile private var copyFirst = false
        /** When the stream was picked: the head start's time, else this session's. */
        private val pickedAtMs = headStart?.pickedAtMs ?: SystemClock.elapsedRealtime()
        /** The generating screen's wait for the copy is over (confirmed, or the usual way from now on). */
        private var copyScreenDecided = false
        @Volatile private var copyScreenWait = false
        /** Plays from Usenet: thumbnails come from a copy where one fits, and the stream is spared meanwhile. */
        @Volatile private var usenet = source.usenet || source.torrent
        /** Never a keyframe from the stream itself, only from a copy (and the few that check the copy is this film). */
        private val copyOnly = source.torrent
        /** A media server away from the home network. */
        private val awayServer = MediaServerThumbs.onDemandOnly(source.mediaServer, source.url)
        /**
         * Away from home, no copy of this title fits, and the server is not known to refuse many ranges in one request:
         * the full pass and the generating screen, on one connection, until [MediaServerThumbs.REMOTE_DATA_CAP_BYTES]
         * or a refusal.
         */
        @Volatile private var remoteBatched = awayServer && MultiRangeSupport.app(context).allowed(source.url)
        /** Away from home: the stream gives only the spot a seek asks for. */
        @Volatile private var onDemand = awayServer && !remoteBatched
        /** On demand: a copy of this title fits, so the copy makes the full set ([MediaServerThumbs.copyFills]). */
        @Volatile private var awayCopy = false
        @Volatile private var probeJob: Job? = null
        @Volatile private var prewarmJob: Job? = null
        private var awayCheckingSince = 0L
        private var copyTried = false
        private var copyWaitLogged = false
        private var copyStartedAt = 0L
        private var copySetKey: String? = null
        private var copyAdoptedAt = 0
        private var copyChecksAt = 0
        /** Positions (ms) of the newest thumbnails, oldest first. Guarded by itself. */
        private val recentMade = ArrayDeque<Long>()
        /** Guarded by [recentMade]. */
        private var madeSeq = 0
        @Volatile private var prepareLastCoverage: List<Boolean> = emptyList()
        private var monitorJob: Job? = null
        @Volatile private var lastScrubAt = 0L
        /** Where playback jumped to, -1 = no jump pending. */
        @Volatile private var reanchorToMs = -1L
        private var trackPosMs = -1L
        private var trackAtMs = 0L
        private var trackPlaying = false
        @Volatile private var sampledPlaying = true     // true until the first sample, so no early pause cap
        @Volatile private var sampledBuffering = false
        @Volatile private var remux = false
        /** Strips this title's 4K keyframes decode in, see [StripDecode.next]. */
        @Volatile private var stripCount = -1
        /** 0 = not used, 1 = checking (player held), 3 = generating, 4 = ready, 5 = finished. 2 is unused. */
        @Volatile private var prepareStage = 0
        private val prepareLock = Any()
        /** The held reserve is the pause cap: no pacer, no time limit. */
        @Volatile private var reserveIsPauseCap = false
        private var pauseCapRetryAt = 0L
        /** Decoder trim asked for by onTrimMemory; done by the worker, never during a decode. */
        @Volatile private var trimRequested = false
        @Volatile private var stoppedExternally = false
        @Volatile private var preparePausedPlayer = false
        /** Small boxes, 4K: behind the generating screen the player lets go of its memory instead of only pausing. */
        private val letsPlayerGo = fourK && !MemoryGovernor.isStrongHardware
        @Volatile private var playerLetGo = false
        @Volatile private var prepareAnchorMs = 0L
        private var prepareDoneAtStart = -1
        private var prepareShownAtStart = 0
        private var prepareMadeAtStart = -1
        /** Every coverage slot stored, finer ones included, and what the pass still makes before the screen's grid is done. */
        @Volatile private var latticeMade = 0
        @Volatile private var latticeWorkLeft = 0
        private var prepareStartedAt = 0L
        private var prepareReadyAt = 0L
        private var prepareLastDone = -1
        private var prepareLastProgressAt = 0L
        private var prepareTendedAt = 0L
        private var lastPrepareUi: PrepareUi? = null
        @Volatile private var latticeDone = 0
        /** The coverage is really stored, not just the budget spent. */
        @Volatile private var latticeComplete = false
        @Volatile private var latticeTotal = 0
        /** What the generating screen shows: its grid and every slot the pass makes on the way to it. */
        @Volatile private var screenTotal = 0
        @Volatile private var screenDone = 0
        /** The coverage pass finished or ran out of budget. On demand there is none. */
        @Volatile private var latticeFinished = onDemand
        @Volatile private var afterPlayback = false
        private var afterPlaybackSince = 0L
        private var lastSampledPlaying = false

        private val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
        private val rgb565 = runCatching { ImagePerformancePreferences(context).rgb565Enabled }.getOrDefault(true)
        @Volatile private var store: ThumbStore? = null
        @Volatile private var index: MediaIndex? = null
        /** slot -> keyframe index, and keyframe -> all slots it serves (long GOPs). */
        @Volatile private var slotKeyframe: IntArray = IntArray(0)
        private var keyframeSlots: HashMap<Int, IntArray> = HashMap()
        @Volatile private var prioritySlot: Int = -1
        /** Last scrub position (ms), kept even before the index exists; -1 = none. */
        @Volatile private var pendingPriorityMs: Long = -1L
        @Volatile private var lastDurationMs = 0L
        @Volatile private var lastPositionMs = 0L
        @Volatile private var reader: HttpRangeReader? = null
        @Volatile private var makeExtractor: (() -> KeyframeExtractor)? = null
        @Volatile private var batchReader: BatchRangeReader? = null
        /** Extractors not in use; each fetch under way has its own. */
        private val spareExtractors = ConcurrentLinkedQueue<KeyframeExtractor>()
        /** Keyframe -> its fetch, under way or done and not decoded yet, in the order started. Worker only. */
        private val fetches = LinkedHashMap<Int, Deferred<Fetched>>()
        private val fetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(ThumbFetchLanes.MAX))
        /** A rate limit or a failed fetch was seen: one fetch at a time for the rest of the session. */
        private var fetchRateLimited = RateLimitedHosts.appSession.contains(source.url)
        /** The server rate limited this session, or answered 429 to an earlier one recently. */
        @Volatile private var sourceRateLimited = fetchRateLimited
        private val fetchRamp = FetchRamp(limitedFromStart = fetchRateLimited)
        /** Fetch lanes playback's buffer allows while the film plays. Worker only. */
        private val headroom = PlaybackHeadroom().apply { filmFile = true }
        private var lanesLogged = ""
        private var refusalQuietLogged = false
        private val decoderLanes = Array(ThumbDecodeLanes.MAX) { DecoderLane() }
        private val hwSwitch = HardwareThumbSwitch.on
        /** Colour and size from the title's first software decode; hardware decodes wait for it. */
        @Volatile private var hwPlan: HardwarePlan? = null
        /** Why this title went back to software, null while the hardware path may run. */
        @Volatile private var hwOff: String? = null
        private val hwLock = Any()
        private var hwLanes: HardwareLanes? = null
        private var hwLaneKey: String? = null
        private var hwName: String? = null
        private var hwWhyLogged: String? = null
        private val fourKFromAppMemory: Boolean by lazy {
            fourK && MemoryGovernor.isLowRam && VideoPoolReader.read() == null
        }
        private val hw4k = AppMemoryHardware.on
        private val hw4kSmallInput = hw4k && AppMemoryHardware.smallInput
        private val hw4kOpenFloorMb = if (hw4k) AppMemoryHardware.openFloorMb else AppMemoryHardware.OPEN_FLOOR_MB
        private val hw4kReleasesFirst = hw4k && AppMemoryHardware.releasesFirst
        /** The thumbnails' own memory was given back before the first 4K hardware open. Worker only. */
        private var appMemoryReleased = false
        /** This title's 4K hardware decodes take their pictures from app memory and follow [AppMemoryHardware]. */
        private val appMemory4K: Boolean get() = hw4k && fourKFromAppMemory
        /** The 4K hardware decoder closed part way through the set: software carries on without a swap hold. Worker only. */
        private var hwClosedMidSet = false
        private var hwClosedMidSetLogged = false

        /** Software decodes after a mid-set hardware close are not held for the swap that decoder pushed out. */
        private fun afterHwClose(verdict: GovernorVerdict, tier: DecodeTier): GovernorVerdict {
            if (!hwClosedMidSet || !verdict.swapLow) return verdict
            val lifted = MemoryGovernor.afterHardwareClose(verdict, tier)
            if (lifted.allowed && !hwClosedMidSetLogged) {
                hwClosedMidSetLogged = true
                Log.i(TAG, "software carries on after the 4K hardware decoder closed: ${lifted.reason}")
            }
            return lifted
        }
        @Volatile private var hwCostMb = -1
        /** MemAvailable before the latest and the first hardware decoder open, -1 before. */
        @Volatile private var hwOpenAvailMb = -1L
        @Volatile private var hwFirstOpenAvailMb = -1L
        @Volatile private var appMemoryHwUsed = false
        /** When this title's first hardware decode began, 0 before. */
        @Volatile private var hwFirstDecodeAt = 0L
        private var poolReadAt = 0L
        private var poolFree: Int? = null
        /** Lanes whose hardware decode hung and was left behind; they are skipped until it returns. Worker only. */
        private val stuckLanes = HashSet<DecoderLane>()
        private val decodeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(ThumbDecodeLanes.MAX))
        /** Keyframe -> its decode under way or done and not stored yet. Worker only. */
        private val decodeJobs = LinkedHashMap<Int, DecodeJob>()
        private val laneCtl = ThumbDecodeLanes(
            MemoryGovernor.isStrongBox, fourK, Runtime.getRuntime().availableProcessors(), FetchPhase.PLAYBACK,
        )
        /** Raw keyframe reads land here, outside the Java heap; sized to the decode lanes. */
        private val buffers = ThumbBufferPool(ThumbBufferPool.limitFor(laneCtl.lanes, fourK, MemoryGovernor.isStrongBox))
        @Volatile private var laneTrimRequested = false
        private var laneClockAt = 0L
        private var laneHeldUntil = 0L
        private var laneClockLoweredAt = 0L
        private val attempts = HashMap<Int, Int>()
        private var rateLimitedUntil = 0L
        private val fetchFailures = FetchFailureStreak(MAX_FETCH_FAILURES)
        private val giveUp = GiveUpNotes()
        private var fetchesStopped = false
        private val diskLoadsInFlight = HashSet<Pair<String, Int>>()
        /** Last bitmap handed to the pane; shown while the exact frame loads from disk. */
        @Volatile private var lastServed: Bitmap? = null
        @Volatile private var lastServedSlot = -1
        val hasSlots: Boolean get() = !ended && slotKeyframe.isNotEmpty()
        private var lastNotedMs = -1L
        private var lastNotedAt = 0L
        @Volatile private var ended = false
        /** Black bars of this title's pictures; the pane crops them at display time. */
        @Volatile private var crop = ThumbCrop.Estimator()

        // --- playback tap spool, deleted at session end ---
        private val spoolDir = File(File(File(context.cacheDir, ThumbStore.DIR), SPOOL_DIR), "s${System.nanoTime()}")
        val spoolDirPath: String get() = spoolDir.absolutePath
        /** keyframe index -> ready (false while its file is still being written). */
        private val spool = ConcurrentHashMap<Int, Boolean>()
        private val spoolOrder = ConcurrentLinkedDeque<Int>()
        private val spoolBytes = AtomicLong(0)
        private val spoolPending = AtomicInteger(0)
        private val spoolWriter = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
        @Volatile private var tapMismatchLogged = false
        /** The tap copies keyframes only once the index is confirmed for this file. */
        @Volatile private var tapOpen = false
        private var tapHits = 0
        val storeKey: String? get() = store?.key
        private var latticeRequests = 0
        private var latticeBytes = 0L
        private var latticeMaxRequests = 0
        @Volatile private var hardMaxRequests = Int.MAX_VALUE
        private var lastDeferLogAt = 0L
        private var deferredSinceLog = 0
        private val trimListener: (Int) -> Unit = { onTrim() }
        @Volatile private var job: Job? = null
        val workerJob: Job? get() = job
        val copyJob: Job? get() = copy?.workerJob

        fun cancelReads() {
            reader?.close()
            copy?.cancelReads()
        }

        fun start() {
            if (stoppedExternally) return        // stopped between creation and start
            MemoryGovernor.addTrimListener(trimListener)
            if (fourK && !MemoryGovernor.isStrongBox) monitorJob = mainScope.launch { monitorPlayback() }
            job = worker.launch {
                try {
                    run()
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    giveUp.ended(t)
                    Log.w(TAG, "session ended: ${giveUp.endedBy}")
                } finally {
                    withContext(NonCancellable) { finish() }
                }
            }.also { j -> j.invokeOnCompletion { if (ending === this) ending = null } }
            watchJob = worker.launch { watchWhileFetching() }.also { w -> job?.invokeOnCompletion { w.cancel() } }
        }

        @Volatile private var watchJob: Job? = null

        fun stop(handBackPlayer: Boolean = true) {
            stoppedExternally = true
            if (earlyScreen) mainScope.launch { takeDownEarlyScreen() }
            // Stopped while preparing: give the player back before a newer session can pause it (posts are ordered on Main).
            if (synchronized(prepareLock) { (prepareStage in 1..4).also { if (it) prepareStage = 5 } }) {
                mainScope.launch {
                    if (handBackPlayer) runCatching { resumePlayerAfterPrepare() } else {
                        preparePausedPlayer = false
                        playerLetGo = false
                    }
                    if (session == null || session === this@Session) prepareUi.value = PrepareUi.Hidden
                }
            }
            endYield()
            probeJob?.cancel()
            prewarmJob?.cancel()
            headStart?.release()
            reader?.close()
            copy?.stop("the player stopped this title")
            job?.cancel()
            watchJob?.cancel()
            monitorJob?.cancel()
        }

        private suspend fun run() {
            ConnectionLedger.app.attach(PrefsLedgerMemory(context))
            ConnectionLedger.app.notePlaybackConnections(source.playbackConnections)
            ConnectionLedger.app.notePlayingUrl(source.url)
            val first = sample() ?: awaitPlayerBack() ?: return
            lastDurationMs = first.durationMs
            beforePlay = runCatching { SeekThumbnailPreferences.beforePlay(context) }.getOrDefault(ThumbsBeforePlay.FASTER)
            // A saved store shows its thumbnails before any network access; its size is checked against the server's below.
            var known = openKnownStore()
            known?.let {
                applyIndex(it.index, it.store, it.fileLength)
                setUpBorrow(it.index, it.store, confirmedOnly = true)
            }
            if (sourceRateLimited) Log.i(TAG, "source rate limited recently: one thumbnail fetch at a time")
            if (awayServer) {
                ConnectionLedger.app.capThumbnails(source.url, MediaServerThumbs.ON_DEMAND_FETCHES)
                probeAwayCopy()
                Log.i(TAG, "media server away from the home network: " + when {
                    awayCopy -> "thumbnails from a copy of this title, the stream only for the spot a seek asks for " +
                        "and the picture check, ${MediaServerThumbs.ON_DEMAND_FETCHES} request at a time"
                    remoteBatched -> "thumbnails in batches on ${MediaServerThumbs.ON_DEMAND_FETCHES} connection, up to " +
                        "${MediaServerThumbs.REMOTE_DATA_CAP_BYTES / (1024 * 1024)} MB"
                    else -> "only the spot a seek asks for, ${MediaServerThumbs.ON_DEMAND_FETCHES} request at a time"
                })
            }
            run {
                val mode = runCatching { SeekThumbnailPreferences.prepareMode(context) }.getOrDefault(ThumbPrepareMode.OFF)
                val size = source.fileSizeHint?.takeIf { it > 0 }
                // The marker is written at >= 97 %: a few keyframes that never decode must not bring the screen up again.
                val complete = known?.let { coverageStored(it.store) } == true ||
                    (size != null && runCatching { ThumbStore.isMarkedComplete(context, source.identity, size) }
                        .getOrDefault(false))
                giveUp.beforePlay = when {
                    mode == ThumbPrepareMode.OFF -> "off"
                    complete -> "skipped (already complete)"
                    !source.holdBeforePlay -> "skipped (next episode or late start)"
                    sourceRateLimited -> "skipped (rate limited recently)"
                    onDemand && !awayCopy -> "skipped (media server away from home)"
                    else -> "not shown (playback could not be held)"
                }
                if (mode != ThumbPrepareMode.OFF && complete) {
                    Log.i(TAG, "prepare: thumbnails already complete (${if (known != null) "saved" else "marker"}) - playing")
                } else if (mode != ThumbPrepareMode.OFF && !source.holdBeforePlay) {
                    Log.i(TAG, "prepare: autoplayed next episode - not holding")
                } else if (mode != ThumbPrepareMode.OFF && sourceRateLimited) {
                    Log.i(TAG, "prepare: source rate limited recently - not holding")
                } else if (mode != ThumbPrepareMode.OFF && onDemand && !awayCopy) {
                    Log.i(TAG, "prepare: media server away from the home network - not holding")
                } else if (mode != ThumbPrepareMode.OFF && prepareDeclinedFor == source.identity) {
                    prepareDeclinedFor = null
                    giveUp.beforePlay = "skipped (start now pressed before the screen was ready)"
                    Log.i(TAG, "prepare: start now pressed while the player opened the stream - not holding")
                } else if (mode != ThumbPrepareMode.OFF) {
                    if (beginPrepareOnMain()) {
                        giveUp.beforePlay = if (preparePausedPlayer) "shown (${beforePlayName()})"
                            else "shown, playback already paused (${beforePlayName()})"
                        showPrepare(PrepareUi.Checking)
                        Log.i(TAG, "prepare: generating screen up ($mode, $beforePlay)")
                        letGoEarlyForHardware()
                    }
                }
            }
            if (!holdRetrying) withContext(Dispatchers.Main) { takeDownEarlyScreen() }
            if (openDeferred && prepareStage !in 1..4) withContext(Dispatchers.Main) {
                Log.i(TAG, "prepare: not holding - the film opens now")
                runCatching { loadAgainAfterPrepare(true) }
            }
            val pre = prefetch?.let { runCatching { it.await() }.getOrNull() }?.takeIf { it.url == source.url }
            if (pre == null) awaitIndexGate()
            val r = pre?.reader ?: HttpRangeReader(source.url, source.headers, thumbnailHttpClient(source.headers),
                slotWaitMs = ConnectionLedger.START_SLOT_WAIT_MS)
            reader = r
            val tIndex0 = SystemClock.elapsedRealtime()
            val head = pre?.head ?: startRead { r.read(0, HEAD_BYTES) }
            val fileLength = r.totalLength
            ConnectionLedger.app.notePlayingUrl(r.resolvedUrl)
            if (awayServer) ConnectionLedger.app.capThumbnails(r.resolvedUrl, MediaServerThumbs.ON_DEMAND_FETCHES)
            if (!usenet && SlowStartServer.isUsenet(r.resolvedUrl)) usenet = true
            if (source.copySources != null && !onDemand && source.identityExact) {
                copyAllowed = true
                copyFirst = !usenet
                if (usenet) Log.i(COPY_TAG, (if (copyOnly) "torrent" else "Usenet") + " stream: thumbnails from a copy " +
                    "of this title where one fits")
                if (copyFirst) {
                    copyScreenWait = prepareStage in 1..4
                    Log.i(COPY_TAG, "copy first: thumbnails from a lighter copy where one fits" +
                        (if (headStart != null) ", its link opened at the stream pick" else "") +
                        ", the usual way ${CopyFirst.FALLBACK_MS / 1000} s after the pick without its link")
                }
            }
            if (copyOnly && !copyAllowed) {
                Log.i(TAG, "skip: a torrent's thumbnails come only from a copy, and copies are off or not offered")
                releasePrepareHold()
                return
            }
            Log.i(TAG, "connections: up to ${ConnectionLedger.app.thumbnailCap(r.resolvedUrl)} thumbnail requests on this " +
                (if (LedgerKey.of(r.resolvedUrl) == LedgerKey.TORBOX) "TorBox account" else "server") +
                " (${ConnectionLedger.app.inFlightNote(r.resolvedUrl)}, playback setting ${source.playbackConnections})")
            if (!sourceRateLimited && RateLimitedHosts.appSession.contains(r.resolvedUrl)) {
                sourceRateLimited = true
                fetchRamp.limitToOne()
                fetchOneAtATime("source rate limited recently")
            }
            if (known != null && known.fileLength != fileLength) {
                Log.i(TAG, "saved thumbnails opened early were for another file size - reopening")
                slotKeyframe = IntArray(0)
                prioritySlot = -1
                store = null
                index = null
                lastServed = null
                lastServedSlot = -1
                crop = ThumbCrop.Estimator()
                dropBorrow()
                known = null
            }
            // A remux is known before its index is read: cap now, before a paused start fills the read-ahead.
            if (fourK && lastDurationMs > 0) {
                remux = fileLength * 8_000L / lastDurationMs >= REMUX_MIN_BITS_PER_SECOND
                tendReserve()
            }
            val s = known?.store ?: ThumbStore.open(context, source.identity, fileLength, rgb565)
            store = s
            ThumbStore.housekeeping(context, activeKey = s.key)
            val cached = known?.index
                ?: s.cachedKeyframeIndex()?.let(KeyframeIndexCodec::decode)?.takeIf { it.fileLength == fileLength }
            // 4K: only read the index (several MB of Cues on a remux) once 4K may decode, unless another release's
            // saved thumbnails can be shown meanwhile.
            if (cached == null && pre?.index == null && fourK && !reusableSaved(s)) awaitFourKWindow()
            val idx = cached ?: pre?.index?.takeIf { it.fileLength == fileLength } ?: startRead {
                when {
                    MkvIndexReader.looksLikeMatroska(head) -> MkvIndexReader.read(r, head)
                    AviIndexReader.looksLikeAvi(head) -> AviIndexReader.read(r, head)
                    Mp4IndexReader.looksLikeMp4(head) -> Mp4IndexReader.read(r, head)
                    else -> throw UnsupportedMediaException("container not supported")
                }
            }.also { s.setKeyframeIndex(KeyframeIndexCodec.encode(it)) }
            Log.i(TAG, "index ${idx.kind} ${idx.video.codec} ${idx.video.width}x${idx.video.height} " +
                "${idx.video.bitDepth}-bit keyframes=${idx.keyframes.count} cached=${cached != null} " +
                (if (pre?.index != null) "early=true " else "") +
                "requests=${r.requests} kb=${r.bytes / 1024} t=${SystemClock.elapsedRealtime() - tIndex0}ms " +
                "stored=${s.slots().size} dv=${idx.video.dolbyVision?.profile}")
            if (known == null) applyIndex(idx, s, fileLength)
            if (fourK) {
                val durationMs = lastDurationMs.takeIf { it > 0 } ?: (idx.durationUs / 1000L)
                val bps = if (durationMs > 0) fileLength * 8_000L / durationMs else 0L
                Log.i(TAG, "4K session: strongBox=${MemoryGovernor.isStrongBox} " +
                    "bitrate=${bps / 1_000_000} Mb/s lattice=${latticeStrides.last() * SPACING_MS / 1000}s")
            }
            r.slotWaitMs = ConnectionLedger.SLOT_WAIT_MS
            val batch = BatchRangeReader(r, MultiRangeSupport.app(context),
                if (MemoryGovernor.isStrongBox) BatchRangeReader.STRONG_BUDGET else BatchRangeReader.SMALL_BUDGET)
            batchReader = batch
            makeExtractor = { KeyframeExtractor(idx, batch) }
            tapOpen = true
            // Another release's pictures need own ones fetched to be confirmed: on demand only confirmed ones are used.
            setUpBorrow(idx, s, confirmedOnly = onDemand)
            loop(idx)
        }

        /** Header and index reads: a dropped connection or a rate limit is retried a few times before the session ends. */
        private suspend fun <T> startRead(block: () -> T): T {
            val streak = FetchFailureStreak(START_READ_TRIES)
            var noSlot = 0
            while (true) {
                try {
                    return block()
                } catch (e: UnsupportedMediaException) {
                    throw e
                } catch (e: ConnectionLedger.NoSlotException) {
                    // Playback keeps every connection the server allows for now: not a failure, but no reason to hold it.
                    rethrowIfStopping(e)
                    giveUp.noConnectionFree()
                    releasePrepareHold()
                    if (++noSlot >= START_SLOT_TRIES) throw e
                    delay(1_000L)
                } catch (e: IOException) {
                    rethrowIfStopping(e)
                    if (e is RateLimitedException) {
                        giveUp.rateLimited()
                        noteRateLimited(e)
                    } else {
                        giveUp.fetchFailed(e)
                    }
                    val waitMs = maxOf(streak.failed(), (e as? RateLimitedException)?.retryAfterMs ?: 0L)
                    if (streak.gaveUp) throw e
                    Log.w(TAG, "start read failed (${e.javaClass.simpleName}), retry in ${waitMs / 1000}s")
                    releasePrepareHold()
                    delay(waitMs)
                }
            }
        }

        /** A read cancelled by stop() must end the worker, never count as a network failure. */
        private suspend fun rethrowIfStopping(e: IOException) {
            if (stoppedExternally || ended) throw e
            currentCoroutineContext().ensureActive()
        }

        /** Playback is not held on the checking screen while a slow start waits to retry. */
        private suspend fun releasePrepareHold() {
            val stage = prepareStage
            if (stage in 1..4 && advancePrepare(stage, 5)) {
                showPrepare(PrepareUi.Hidden)
                withContext(Dispatchers.Main) { resumePlayerAfterPrepare() }
            }
        }

        private class KnownStore(val store: ThumbStore, val index: MediaIndex, val fileLength: Long)

        private fun openKnownStore(): KnownStore? {
            val size = source.fileSizeHint?.takeIf { it > 0 } ?: return null
            return runCatching {
                if (!ThumbStore.exists(context, source.identity, size)) return null
                val s = ThumbStore.open(context, source.identity, size, rgb565)
                val idx = s.cachedKeyframeIndex()?.let(KeyframeIndexCodec::decode)?.takeIf { it.fileLength == size }
                    ?: return null
                Log.i(TAG, "saved thumbnails opened at once (stored=${s.slots().size})")
                KnownStore(s, idx, size)
            }.getOrNull()
        }

        private fun applyIndex(idx: MediaIndex, st: ThumbStore, fileLength: Long) {
            val durationMs = lastDurationMs.takeIf { it > 0 } ?: (idx.durationUs / 1000L)
            val bps = if (durationMs > 0) fileLength * 8_000L / durationMs else 0L
            if (fourK) remux = bps >= REMUX_MIN_BITS_PER_SECOND
            val base = PrepareGrid.base(fourK, remux, MemoryGovernor.isStrongBox, bps >= HEAVY_1080P_MIN_BITS_PER_SECOND)
            screenStride = PrepareGrid.screenStride(beforePlay, fourK, remux, base.last())
            latticeStrides = PrepareGrid.strides(base, screenStride)
            fallbackSlots = maxOf(MIN_FALLBACK_SLOTS, latticeStrides.last() / 2)
            st.noteDuration(durationMs)
            index = idx
            store = st
            buildSlots(idx, durationMs)
            seedCropFromStore(st)
            preloadSaved(st)
            pendingPriorityMs.takeIf { it >= 0 }?.let { prioritySlot = slotOf(it) }   // a scrub before the index existed
        }

        /** The generating screen counts this grid (the Thumbnails before play choice); finer slots carry on during playback. */
        private fun prepareStride(): Int = screenStride

        private fun coverageStored(st: ThumbStore): Boolean {
            val lattice = SeekGrid.latticeOrder(slotKeyframe.size, latticeStrides, 0, 0, 0)
            return lattice.isNotEmpty() && lattice.none { needs(st, it) }
        }

        private fun inBackground(): Boolean = ForegroundGate.waits(hostResumed, afterPlayback)

        /** Worker only. Away from the player screen nothing is fetched or decoded, and the decoders' memory is given back. */
        private suspend fun awaitForeground() {
            if (!inBackground()) return
            val now0 = SystemClock.elapsedRealtime()
            val since = hostPausedAt.takeIf { it in 1..now0 } ?: now0
            Log.i(TAG, "app not in front: thumbnails wait")
            while (inBackground()) {
                settleDecodes()
                tendReserve()
                closeDecoders()
                delay(1_000L)
            }
            val back = SystemClock.elapsedRealtime()
            prepareStartedAt = ForegroundGate.shifted(prepareStartedAt, since, back)
            prepareLastProgressAt = ForegroundGate.shifted(prepareLastProgressAt, since, back)
            Log.i(TAG, "app back in front after ${(back - since) / 1000} s: thumbnails carry on")
        }

        /**
         * The index read (up to a few MB) waits for a healthy buffer or a pause, unless the user scrubs first. On demand
         * nothing is read before the first scrub, unless a copy of the title makes the thumbnails.
         */
        private suspend fun awaitIndexGate() {
            while (true) {
                awaitForeground()
                if (pendingPriorityMs >= 0) return
                if (onDemand && !awayCopy) {
                    delay(1_000L)
                    continue
                }
                val ps = sample() ?: return
                if (!ps.buffering && (!ps.isPlaying || ps.bufferedMs - ps.positionMs >= INDEX_GATE_AHEAD_MS ||
                        (ps.durationMs > 0 && ps.bufferedMs >= ps.durationMs - 1_000L))) return
                delay(1_000L)
            }
        }

        /** Small boxes: while paused, for the settle target, and for coverage only while the read-ahead leaves room. */
        private fun fourKAllowed(ps: PlayerState, priority: Int = 2): Boolean {
            if (!fourK || MemoryGovernor.isStrongBox || !ps.isPlaying) return true
            // Small box, 4K remux: nothing while playing (whole pictures stutter), unless they decode in strips.
            if (remux && !stripsWhilePlaying()) {
                probeStrips()
                return false
            }
            if (remux && !PlaybackHeadroom.stripFits(PlaybackLine.now())) {
                if (!stripWaitLogged) Log.i(TAG, "strip decode waits: playback's unused pieces do not cover " +
                    "${PlaybackHeadroom.STRIP_BYTES / (1024 * 1024)} MB")
                stripWaitLogged = true
                return false
            }
            stripWaitLogged = false
            if (!synchronized(pacer) { pacer.allows(settleTarget = priority == 0) }) return false
            return priority == 0 || reserveHeld != null || naturallyFunded || reserveEligible()
        }

        private var stripWaitLogged = false

        private fun stripsWhilePlaying(): Boolean = stripCount >= 2 && StripDecode.whilePlaying

        /** Once per title: whether a keyframe playback already copied to the spool would decode in strips. */
        private fun probeStrips() {
            if (stripCount != -1 || !StripDecode.whilePlaying) return
            val idx = index ?: return
            val kf = spool.entries.firstOrNull { it.value }?.key ?: return
            val f = File(spoolDir, "$kf.au")
            if (f.length() > ThumbReads.MAX_READ) return
            val au = runCatching { f.readBytes() }.getOrNull()?.let { tapSampleToAnnexB(it, idx.video) } ?: return
            stripCount = StripDecode.next(stripCount, StripDecode.count(au))
            Log.i(TAG, if (stripCount >= 2) "4K keyframes decode in $stripCount strips: thumbnails while the remux plays"
                else "4K keyframes decode whole: none while the remux plays")
        }

        private fun reserveEligible(): Boolean =
            fourK && !afterPlayback && !playerLetGo && !reserveBlocked && !latticeFinished &&
                SystemClock.elapsedRealtime() >= reserveRetryAt &&
                synchronized(pacer) { pacer.allows(settleTarget = false) } &&
                reserveProvider()?.cancelledByRebuffer == false

        /** A coverage decode was refused for memory: cap playback's read-ahead by the missing amount plus a margin. */
        private suspend fun takeReserve(tier: DecodeTier, reason: String) {
            if (reserveHeld != null || !reserveEligible()) return
            val r = reserveProvider() ?: return
            val ps = sample() ?: return
            val paused = !ps.isPlaying
            if (!paused && ps.bufferedMs - ps.positionMs < RESERVE_MIN_AHEAD_MS) return
            // Small boxes: the read-ahead is small enough already, nothing needs capping. Also while paused.
            if (!MemoryGovernor.isStrongBox && naturalFundingFits(r)) {
                if (!naturallyFunded) {
                    naturallyFunded = true
                    Log.d(TAG, "4K-F natural funding on (read-ahead ${r.footprintBytes / MB} MB of " +
                        "${r.readAheadBudgetBytes / MB} MB, ${(ps.bufferedMs - ps.positionMs) / 1000} s ahead" +
                        (if (paused) ", paused" else "") + ") - $reason")
                }
                return
            }
            // A paused remux is funded by the pause cap (tendPauseCap); the reserve below is for playback.
            if (paused) return
            val shortMb = MemoryGovernor.shortfallMb(tier, decoderLanes[0].decoder.warm, RESERVE_MARGIN_MB) ?: return
            val want = maxOf(RESERVE_MIN_BYTES, shortMb * MB)
            // The player caps a reserve at 60 % of its budget; if that is not enough, tendReserve drops it again.
            val why = when {
                // Strong boxes: a read-ahead already below the cap is not what holds the memory, capping it frees nothing.
                MemoryGovernor.isStrongBox && r.bufferedBytes < r.readAheadBudgetBytes - want ->
                    "read-ahead ${r.bufferedBytes / MB} MB is already below ${(r.readAheadBudgetBytes - want) / MB} MB"
                else -> null
            }
            if (why != null) {
                reserveRetryAt = SystemClock.elapsedRealtime() + RESERVE_RETRY_MS
                Log.d(TAG, "4K-E no reserve: $why - $reason (retry in ${RESERVE_RETRY_MS / 1000} s)")
                return
            }
            holdReserve(r, want, "4K-E reserve on", "${(ps.bufferedMs - ps.positionMs) / 1000} s ahead) - $reason")
        }

        private fun holdReserve(r: PlaybackBufferReserve, want: Long, what: String, detail: String) {
            r.reserve(want)
            if (r.reservedBytes <= 0L) return
            reserveHeld = r
            reserveSince = SystemClock.elapsedRealtime()
            fundedSince = 0L
            reservePictures = 0
            reserveFreed = 0L
            reservePurgedAt = 0L
            Log.d(TAG, "$what: ${r.reservedBytes / MB} MB (wanted ${want / MB}) of ${r.readAheadBudgetBytes / MB} MB read-ahead " +
                "(buffered ${r.bufferedBytes / MB} MB, $detail")
        }

        /** Small box, 4K: while paused the read-ahead is capped like a reserve, which funds the decodes. */
        private fun tendPauseCap() {
            if (!fourK || MemoryGovernor.isStrongBox || ended) return
            if (sampledPlaying || afterPlayback || playerLetGo || inBackground() || (latticeFinished && !scrubbingNow())) {
                if (reserveIsPauseCap && reserveHeld != null) {
                    dropReserve(when {
                        sampledPlaying -> "playback resumed"
                        afterPlayback -> "player closed"
                        playerLetGo -> "player let go"
                        inBackground() -> "app not in front"
                        else -> "coverage done"
                    })
                }
                return
            }
            // Not tied to the pacer or the reserve time limit: pausing always makes thumbnails.
            if (reserveHeld != null || sampledBuffering || SystemClock.elapsedRealtime() < pauseCapRetryAt) return
            val r = reserveProvider() ?: return
            if (r.cancelledByRebuffer) return
            val shortMb = MemoryGovernor.shortfallMb(StripDecode.tier(DecodeTier.SW_4K, stripCount),
                decoderLanes[0].decoder.warm, RESERVE_MARGIN_MB) ?: 0L
            holdReserve(r, maxOf(RESERVE_MIN_BYTES, shortMb * MB), "4K pause cap on", "paused)")
            if (reserveHeld === r) reserveIsPauseCap = true
        }

        private fun dropReserve(why: String, block: Boolean = false) {
            if (block) reserveBlocked = true
            val r = reserveHeld ?: return
            reserveHeld = null
            reserveIsPauseCap = false
            runCatching { r.release() }
            Log.d(TAG, "4K-E reserve off: $why after ${(SystemClock.elapsedRealtime() - reserveSince) / 1000} s, " +
                "$reservePictures pictures, ${reserveFreed / MB} MB given back")
        }

        /** A held seek is under way: its pictures get the pause cap's memory even after the coverage is done. */
        private fun scrubbingNow(): Boolean = SystemClock.elapsedRealtime() - lastScrubAt < SCRUB_CAP_WINDOW_MS

        private fun tendReserve() {
            if (trimRequested) {
                trimRequested = false
                if (appMemory4K && decoderLanes.any { it.hardwareOpen }) {
                    hwName?.let { hardwareOff("memory trim with the 4K hardware decoder open", it, counts = false) }
                    hwClosedMidSet = true
                    closeHardwareLanes()
                }
                trimDecoders()
            }
            tendPauseCap()
            val r = reserveHeld ?: return
            val now0 = SystemClock.elapsedRealtime()
            if (reserveFunding()) {
                if (fundedSince == 0L) {
                    fundedSince = now0
                    fundedPictures = reservePictures
                }
            } else {
                fundedSince = 0L
            }
            when {
                // Not blocked here: the player decides when the reserve may be taken again.
                r.cancelledByRebuffer -> dropReserve("rebuffer (playback first)")
                !reserveIsPauseCap && !synchronized(pacer) { pacer.allows(settleTarget = false) } ->
                    dropReserve("4K-F stopped playback decodes")
                r.reservedBytes <= 0L -> dropReserve("released by the player (lease)")
                reserveProvider() !== r -> dropReserve("player changed")
                // Not blocked: no picture is made meanwhile, which must not read as a reserve that does not help.
                inBackground() -> dropReserve("app not in front")
                fundedSince > 0L && reservePictures == fundedPictures && now0 - fundedSince > RESERVE_NO_GAIN_MS -> {
                    val swap = refusedForSwap
                    if (reserveIsPauseCap) pauseCapRetryAt = now0 + RESERVE_RETRY_MS
                    else if (swap) reserveRetryAt = now0 + RESERVE_RETRY_MS
                    dropReserve("no picture in ${RESERVE_NO_GAIN_MS / 1000} s with the room reserved" +
                        (if (swap) " (swap low)" else ""), block = !reserveIsPauseCap && !swap)
                }
                latticeFinished && !(reserveIsPauseCap && scrubbingNow()) -> dropReserve("coverage done")
                afterPlayback -> dropReserve("player closed")
                playerLetGo -> dropReserve("player let go")
                !reserveIsPauseCap && SystemClock.elapsedRealtime() - reserveSince > RESERVE_MAX_MS ->
                    dropReserve("time limit", block = true)
                else -> {
                    val freed = runCatching { r.giveBack() }.getOrDefault(0L)
                    reserveFreed += freed
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastReserveLogAt >= RESERVE_LOG_INTERVAL_MS) {
                        lastReserveLogAt = now
                        Log.d(TAG, "4K-E reserve: read-ahead ${r.bufferedBytes / MB}/${r.readAheadBudgetBytes / MB} MB " +
                            "(cap ${(r.readAheadBudgetBytes - r.reservedBytes) / MB}, allocator ${r.footprintBytes / MB} MB), " +
                            "given back ${reserveFreed / MB} MB, pictures $reservePictures")
                    }
                    // Freed segments reach MemAvailable only once the allocator returns their pages. M_PURGE walks the heap.
                    if (reserveFreed - reservePurgedAt >= RESERVE_PURGE_MIN_BYTES && now - lastPurgeAt >= RESERVE_PURGE_INTERVAL_MS) {
                        lastPurgeAt = now
                        reservePurgedAt = reserveFreed
                        runCatching { ThumbNative.nativePurge() }
                    }
                }
            }
        }

        private fun reserveFunding(): Boolean {
            val r = reserveHeld ?: return false
            return r.footprintBytes <= r.readAheadBudgetBytes - r.reservedBytes + 8L * MB
        }

        private fun naturalFundingFits(r: PlaybackBufferReserve): Boolean {
            val budget = r.readAheadBudgetBytes
            return budget > 0L && r.footprintBytes <= budget * NATURAL_FUNDING_PERCENT / 100
        }

        private fun naturalFunding(): Boolean {
            if (!naturallyFunded) return false
            val r = reserveProvider()
            val why = when {
                r == null -> "no player"
                afterPlayback -> "player closed"
                playerLetGo -> "player let go"
                reserveHeld != null -> "reserve taken"
                !naturalFundingFits(r) -> "read-ahead ${r.footprintBytes / MB} MB of ${r.readAheadBudgetBytes / MB} MB"
                else -> return true
            }
            naturallyFunded = false
            Log.d(TAG, "4K-F natural funding off ($why)")
            return false
        }

        private fun afterPlaybackExpired(): Boolean =
            afterPlayback &&
                (SystemClock.elapsedRealtime() - afterPlaybackSince > AFTER_PLAYBACK_MAX_MS || memoryShortAfterClose())

        /** True when this session carries on without the player to finish its coverage pass. */
        fun finishAfterPlayback(): Boolean {
            // No index yet is fine: a small box may still be waiting for a pause, and reads it once the player is gone.
            if (!fourK || !MediaServerThumbs.continuesAfterClose(source.mediaServer) || ended || latticeFinished ||
                afterPlayback) return false
            afterPlaybackSince = SystemClock.elapsedRealtime()
            afterPlayback = true
            if (synchronized(prepareLock) { (prepareStage in 1..4).also { if (it) prepareStage = 5 } }) {
                endYield()
                preparePausedPlayer = false
                playerLetGo = false
                prepareUi.value = PrepareUi.Hidden      // called on Main
            }
            prioritySlot = -1
            pendingPriorityMs = -1L
            Log.i(TAG, "4K-C: player closed - finishing the coverage pass (up to ${AFTER_PLAYBACK_MAX_MS / 60_000} min)")
            return true
        }

        private suspend fun awaitFourKWindow() {
            while (true) {
                awaitForeground()
                if (pendingPriorityMs >= 0) {   // scrubbing: the settle target may decode, so read the index
                    decodeOpen = true
                    return
                }
                val ps = sample() ?: return
                if (!ps.buffering && fourKAllowed(ps)) {
                    decodeOpen = true
                    return
                }
                decodeOpen = false
                delay(1_000L)
            }
        }

        private fun buildSlots(idx: MediaIndex, durationMs: Long) {
            val lastSlot = ((durationMs - 1) / SPACING_MS).toInt().coerceAtLeast(0)
            val map = IntArray(lastSlot + 1) { slot ->
                idx.keyframes.nearest(slot * SPACING_MS * 1000L + SPACING_MS * 500L)
            }
            val inverse = HashMap<Int, MutableList<Int>>()
            map.forEachIndexed { slot, kf -> if (kf >= 0) inverse.getOrPut(kf) { ArrayList() }.add(slot) }
            keyframeSlots = HashMap(inverse.mapValues { it.value.toIntArray() })
            slotKeyframe = map
        }

        private fun slotOf(positionMs: Long): Int =
            (positionMs / SPACING_MS).toInt().coerceIn(0, (slotKeyframe.size - 1).coerceAtLeast(0))

        fun notePriority(positionMs: Long) {
            pendingPriorityMs = positionMs
            lastScrubAt = SystemClock.elapsedRealtime()
            if (slotKeyframe.isEmpty()) return
            prioritySlot = slotOf(positionMs)
            // Held scrub: load the frame the next step will land on from disk ahead of time.
            val now = SystemClock.elapsedRealtime()
            val st = store
            if (st != null && lastNotedMs >= 0 && now - lastNotedAt < 1_000L) {
                val next = positionMs + (positionMs - lastNotedMs)
                if (next >= 0) nearestStoredSlot(st, slotOf(next))?.let { requestDiskLoad(st, it) }
            }
            lastNotedMs = positionMs
            lastNotedAt = now
        }

        private suspend fun loop(idx: MediaIndex) {
            val anchorMs = sample()?.positionMs?.takeIf { it > 0 } ?: lastPositionMs
            var anchorSlot = slotOf(anchorMs)
            prepareAnchorMs = anchorSlot * SPACING_MS
            // Full makes the fine detail around the start first; the quicker choices make the screen's grid first
            // while the screen is up, and go back to the detail around the playhead once it is gone.
            fun gridFirst() = if (beforePlay != ThumbsBeforePlay.FULL && prepareStage in 1..4) prepareStride() else 0
            var orderedGridFirst = gridFirst()
            fun order(anchor: Int) = SeekGrid.latticeOrder(slotKeyframe.size, latticeStrides, anchor,
                beforeSlots = (LOCAL_BEFORE_MS / SPACING_MS).toInt(), afterSlots = (LOCAL_AFTER_MS / SPACING_MS).toInt(),
                gridFirst = orderedGridFirst)
            var lattice = order(anchorSlot)
            latticeMaxRequests = lattice.size * 5 / 4 + 10
            hardMaxRequests = (if (onDemand) (reader?.requests ?: 0) else latticeMaxRequests) + SCRUB_REQUEST_ALLOWANCE
            var latticeLogged = false
            var latticePos = 0
            var neighbourCenter = -1
            var neighbours: List<Int> = emptyList()
            var lastWorkAt = SystemClock.elapsedRealtime()
            val st = store ?: return
            laneChange(laneCtl.onPhase(phase()))
            Log.i(TAG, "decode lanes start=${laneCtl.lanes} ceiling=${laneCtl.ceiling()} " +
                "cpus=${Runtime.getRuntime().availableProcessors()}")
            while (true) {
                tendReserve()
                naturalFunding()
                settleFetches(st)
                settleDecodes()
                tendLanes()
                tendCopy()
                tendRemote()
                if (decodeJobs.size >= laneCtl.lanes) {
                    awaitAnyDecode()
                    continue
                }
                // The screen came up late (held after the first frame) or went away: same slots, new order.
                val wantGridFirst = gridFirst()
                if (wantGridFirst != orderedGridFirst && !latticeFinished) {
                    orderedGridFirst = wantGridFirst
                    lattice = order(anchorSlot)
                    latticePos = 0
                }
                if ((fourK && !MemoryGovernor.isStrongBox) || prepareStage in 1..4) {
                    // Without the screen the paused hint counts the whole pass on this box.
                    val grid = if (prepareStage in 1..4) prepareStride() else latticeStrides.last()
                    // Away from home the stream is not read: slots the confirmed copy cannot make are not waited for.
                    val unmade = if (onDemand) copy?.takeIf { c -> borrow?.let { it.copy === c && it.confirmed } == true }
                        ?.ownOnlySlots.orEmpty() else emptySet()
                    val counted = lattice.filter { it % grid == 0 && it !in unmade }
                    latticeTotal = counted.size
                    latticeDone = counted.count { !needs(st, it) }
                    if (prepareStage in 1..4) {
                        latticeMade = lattice.count { !needs(st, it) }
                        latticeWorkLeft = PrepareHandover.workLeft(lattice, prepareStride()) { needs(st, it) }
                        prepareLastCoverage = counted.sorted().map { !needs(st, it) }
                        val waited = PrepareHandover.screenSet(lattice, grid).filter { it !in unmade }
                        screenTotal = waited.size
                        screenDone = waited.count { !needs(st, it) }
                        tendPrepare(latticeTotal, latticeDone)
                    }
                }
                // Playback jumped far: re-centre the pass there. Same slots, new order; needs() skips finished ones.
                val jumpTo = reanchorToMs
                if (jumpTo >= 0L) {
                    reanchorToMs = -1L
                    val target = slotOf(jumpTo)
                    if (!latticeFinished && !afterPlayback && kotlin.math.abs(target - anchorSlot) * SPACING_MS > REANCHOR_JUMP_MS) {
                        Log.d(TAG, "coverage re-centred at ${target * SPACING_MS / 1000} s (was ${anchorSlot * SPACING_MS / 1000} s)")
                        anchorSlot = target
                        prepareAnchorMs = target * SPACING_MS
                        lattice = order(target)
                        latticePos = 0
                    }
                }
                if (afterPlayback && (latticeFinished || afterPlaybackExpired() ||
                        (reader?.let { it.requests >= hardMaxRequests } == true))) {
                    while (decodeJobs.isNotEmpty()) awaitAnyDecode()
                    Log.i(TAG, "4K-C: " + (if (latticeFinished) "coverage pass finished" else "time/request limit") +
                        " after the player closed (stored=${st.slots().size})")
                    return
                }
                val copyHolds = copyHoldsLattice()
                val usenetHold = usenet && (copyHolds || copyOnly)
                // While a Usenet title's copy is tried, only the spot a scrub stops on is read from the stream.
                var landingStop = 0L
                val p0 = when {
                    fetchesStopped || copyOnly -> -1
                    usenetHold -> lastScrubAt.let { at -> usenetLanding(st, at).also { if (it >= 0) landingStop = at } }
                    else -> prioritySlot
                }
                var slot = -1
                var priority = 2
                if (usenetHold) {
                    if (p0 >= 0) {
                        neighbourCenter = -1
                        neighbours = listOf(p0)
                        slot = p0
                        priority = 0
                    }
                } else if (onDemand) {
                    // Only the settle target itself, no neighbours.
                    if (p0 >= 0 && wanted(st, p0)) {
                        neighbourCenter = -1
                        neighbours = listOf(p0)
                        slot = p0
                        priority = 0
                    } else if (p0 >= 0 && prioritySlot == p0) {
                        prioritySlot = -1
                    }
                } else if (p0 >= 0) {
                    // P0: the settle target, then P1: its neighbours, forward first.
                    if (p0 != neighbourCenter) {
                        neighbourCenter = p0
                        neighbours = buildList {
                            add(p0)
                            for (d in 1..priorityRadius) {
                                if (p0 + d < slotKeyframe.size) add(p0 + d)
                                if (p0 - d >= 0) add(p0 - d)
                            }
                        }
                    }
                    val next = neighbours.firstOrNull { wanted(st, it) }
                    if (next != null) {
                        slot = next
                        priority = if (next == p0) 0 else 1
                    } else if (prioritySlot == p0) {
                        prioritySlot = -1
                    }
                }
                if (slot < 0 && !fetchesStopped && (!onDemand || copy != null)) {
                    val check = nextCheckSlot(st)
                    if (check >= 0) slot = check
                }
                if (slot < 0) {
                    // P3: keyframes playback already downloaded, no network needed.
                    val tapped = spooledSlot(st)
                    if (tapped >= 0) {
                        slot = tapped
                        priority = 3
                    }
                }
                if (slot < 0 && !fetchesStopped && !copyHolds && !onDemand && !copyOnly) {
                    while (latticePos < lattice.size && !wanted(st, lattice[latticePos])) latticePos++
                    if (latticePos < lattice.size) slot = lattice[latticePos]
                }
                // A confirmed copy fills the grid: the slots it has no picture close enough for are made meanwhile.
                if (slot < 0 && !fetchesStopped && copyHolds && !onDemand && !copyOnly && !usenet) {
                    val only = copy?.takeIf { c -> borrow?.let { it.copy === c && it.confirmed } == true }?.ownOnlySlots
                    if (!only.isNullOrEmpty()) slot = lattice.firstOrNull { it in only && wanted(st, it) } ?: -1
                }
                if (slot < 0 && decodeJobs.isNotEmpty()) {
                    awaitAnyDecode()
                    continue
                }
                if (slot < 0 && !latticeLogged && latticePos >= lattice.size) {
                    latticeLogged = true
                    latticeFinished = true
                    latticeComplete = lattice.isNotEmpty() &&
                        lattice.count { st.has(it.toLong() * SPACING_MS * 1000L) } >= lattice.size * 97 / 100
                    if (latticeComplete) st.markComplete()
                    rememberHardwareLanes()
                    bumpTick()                            // hides the paused progress hint
                    Log.i(TAG, "lattice done: slots=${lattice.size} requests=$latticeRequests/$latticeMaxRequests " +
                        "kb=${latticeBytes / 1024} stored=${st.slots().size}")
                }
                if (slot < 0) {
                    // Nothing to do. A 4K context (~80 MB) is closed outright, and at once when 4K may not decode.
                    val idle = SystemClock.elapsedRealtime() - lastWorkAt > IDLE_TRIM_MS
                    // The copy waits for these decoders or their memory: nothing to make here, so they go now.
                    val copyWaits = copy?.let { it.filling && it.ownDecoderWaitSince > 0L } == true &&
                        decoderLanes.any { it.decoder.warm || it.hardwareOpen }
                    if (fourK) {
                        val open = sample()?.let(::fourKAllowed) ?: false
                        decodeOpen = open
                        if (!open || idle || copyWaits) {
                            closeDecoders()
                            buffers.releaseIdle()
                        }
                    } else {
                        if (prepareStage in 1..4) sample()
                        if (copyWaits) {
                            closeDecoders()
                            buffers.releaseIdle()
                        } else if (idle) {
                            trimDecoders()
                            buffers.releaseIdle()
                        }
                    }
                    delay(500L)
                    continue
                }
                if (!awaitGates(priority)) continue      // new priority while waiting: re-plan
                if (priority == 2 && latticeBudgetSpent()) {
                    latticePos = lattice.size             // coverage ends for this session; P0/P1 continue
                    latticeFinished = true
                    continue
                }
                topUpFetches(st, slot, priority, if (priority <= 1) neighbours else emptyList(), lattice, latticePos)
                if (priority == 2 && lattice.getOrNull(latticePos) == slot && fetchLimit() > 1) {
                    val ahead = (latticePos until minOf(lattice.size, latticePos + ThumbFetchLanes.MAX * 4))
                        .map { lattice[it] }.filter { it == slot || wanted(st, it) }
                    val ready = ThumbFetchLanes.nextReady(ahead) { s -> fetchState(slotKeyframe[s]) }
                    if (ready < 0) {
                        awaitAnyFetch()
                        continue
                    }
                    slot = ready
                }
                // A postponed slot (memory, rate limit) is retried, never skipped.
                val outcome = produce(idx, st, slot, priority)
                if (outcome == Outcome.POSTPONED) continue
                if (landingStop > 0L) {
                    usenetServedStopAt = landingStop
                    Log.i(COPY_TAG, "Usenet: the scrub stop at ${slot * SPACING_MS / 1000} s read from the stream ($outcome)")
                }
                if (priority == 2 && lattice.getOrNull(latticePos) == slot) latticePos++
                lastWorkAt = SystemClock.elapsedRealtime()
                if (outcome == Outcome.DONE && fourK && !MemoryGovernor.isStrongBox && priority != 0 &&
                    lastSampledPlaying) {
                    val pace = synchronized(pacer) { pacer.paceMs }
                    waitUnlessNewPriority(pace)
                }
            }
        }

        private fun needs(st: ThumbStore, slot: Int): Boolean {
            val kf = slotKeyframe.getOrElse(slot) { -1 }
            if (kf < 0) return false
            if ((attempts[kf] ?: 0) >= MAX_ATTEMPTS_PER_KEYFRAME) return false
            return !st.has(slot.toLong() * SPACING_MS * 1000L)
        }

        /** Needed and not being decoded already. */
        private fun wanted(st: ThumbStore, slot: Int): Boolean =
            needs(st, slot) && slotKeyframe.getOrElse(slot) { -1 } !in decodeJobs

        private fun latticeBudgetSpent(): Boolean =
            latticeRequests >= latticeMaxRequests || latticeBytes >= (if (fourK) LATTICE_4K_MAX_BYTES else LATTICE_MAX_BYTES)

        /** P0 only waits out a rebuffer and rate limits; P1/P2 also need a healthy buffer. False = re-plan. */
        private suspend fun awaitGates(priority: Int): Boolean {
            var anchorAhead = -1L
            var anchorSince = 0L
            val entryPriority = prioritySlot
            while (true) {
                tendReserve()                        // waits here can be long
                settleDecodes()
                tendPrepareWhileWaiting()
                tendCopy()
                if (afterPlaybackExpired()) return false
                awaitForeground()
                val now = SystemClock.elapsedRealtime()
                val r = reader
                if (r != null && r.requests >= hardMaxRequests) {
                    if (afterPlayback) return false  // the loop then ends the session
                    delay(5_000L)
                    continue
                }
                if (now < rateLimitedUntil) {
                    delay(1_000L)
                    continue
                }
                val ps = sample()
                if (ps == null) {
                    delay(500L)                      // no player right now (rebuild / engine switch): don't spin
                    return false
                }
                val playback429 = ThumbRateLimit.waitsForPlayback(
                    latched = ParallelRangeDataSource.hudClampLatched,
                    lastHitAtMs = ParallelRangeDataSource.hudClampLastHitAtMs,
                    nowMs = SystemClock.uptimeMillis(),
                    playing = ps.isPlaying,
                    playerClosed = afterPlayback,
                )
                if (playback429 || (!afterPlayback && ThumbRateLimit.playbackClampedNow())) {
                    fetchOneAtATime("playback was rate limited")
                }
                if (playback429) {
                    delay(1_000L)
                    continue
                }
                val refusalQuietMs = ConnectionLedger.app.refusalQuietMs(reader?.resolvedUrl ?: source.url)
                if (refusalQuietMs > 0L) {
                    if (!refusalQuietLogged) {
                        refusalQuietLogged = true
                        Log.i(TAG, "paused: the server said too many requests - " +
                            "${refusalQuietMs / 1000} s before the next fetch")
                    }
                    delay(1_000L)
                    continue
                }
                refusalQuietLogged = false
                lastPositionMs = ps.positionMs
                lastSampledPlaying = ps.isPlaying
                val small4K = fourK && !MemoryGovernor.isStrongBox
                if (PrepareHandover.waitsForBuffering(ps.buffering, prepareStage in 1..4, ps.playWhenReady, small4K)) {
                    anchorAhead = -1L
                    if (small4K) closeDecoders()
                    delay(500L)
                    continue
                }
                if (fourK && !fourKAllowed(ps, priority)) {
                    decodeOpen = false
                    closeDecoders()
                    delay(500L)
                    if (prioritySlot >= 0 && prioritySlot != entryPriority) return false
                    continue
                }
                decodeOpen = true
                if (priority == 0 || !ps.isPlaying) return true
                if (priority > 0 && prioritySlot >= 0 && prioritySlot != entryPriority) return false
                if (headroom.lanes == 0) {
                    anchorAhead = -1L
                    delay(1_000L)
                    continue
                }
                if (headroom.surplus) return true
                val ahead = ps.bufferedMs - ps.positionMs
                val toEnd = ps.durationMs > 0 && ps.bufferedMs >= ps.durationMs - 1_000L
                if (anchorAhead < 0 || kotlin.math.abs(ahead - anchorAhead) > PLATEAU_JITTER_MS) {
                    anchorAhead = ahead
                    anchorSince = now
                }
                val plateau = ahead >= PLATEAU_MIN_AHEAD_MS && now - anchorSince >= PLATEAU_WINDOW_MS
                if (ahead >= GATE_BUFFER_AHEAD_MS || toEnd || plateau) return true
                delay(1_000L)
            }
        }

        private suspend fun produce(idx: MediaIndex, st: ThumbStore, slot: Int, priority: Int): Outcome {
            val hwChoice = hardwareChoice(idx)
            if (PrepareHandover.letsPlayerGo(letsPlayerGo || hwChoice != null, prepareStage in 1..4, preparePausedPlayer,
                    playerLetGo) || (playerLetGo && !letGoNoted)) {
                letPlayerGo()
            }
            val hw = hwChoice != null && hwPlan != null
            if (!hw && hwLanes != null) closeHardwareLanes()
            val lanes = activeLanes(hw, hwChoice, idx.video.codec)
            val kf = slotKeyframe[slot]
            val lane = freeLane(lanes) ?: run {
                awaitAnyDecode()
                return Outcome.POSTPONED
            }
            val busy = decodeJobs.size
            val tier = if (hw) DecodeTier.HW
                else StripDecode.tier(MemoryGovernor.tierFor(idx.video.width, idx.video.height), stripCount)
            // Evaluate both: naturalFunding() also notices its own end.
            val reserveFunded = reserveFunding()
            val naturalFunded = naturalFunding()
            val screenHolds = prepareStage in 1..4 && preparePausedPlayer && !playerLetGo && !sampledPlaying &&
                reserveProvider()?.yieldHolding == true
            // Playback's held-back read-ahead covers one decode: an extra lane has to fit on its own.
            val funded = MemoryGovernor.funded(busy, tier, reserveFunded, naturalFunded, screenHolds)
            val appMem = hw && appMemory4K
            if (appMem && !lane.decoderFor(true).warm) releaseBeforeAppMemoryOpen()
            val verdict = if (appMem) appMemoryVerdict(idx.video.codec, lane.decoderFor(true).warm)
                else afterHwClose(MemoryGovernor.allow(tier, decoderWarm = lane.decoderFor(hw).warm, funded = funded), tier)
            refusedForSwap = verdict.swapLow
            if (!verdict.allowed) return deferForMemory(verdict, tier, slot, priority, busy, funded, afterFetch = false)
            attempts[kf] = (attempts[kf] ?: 0) + 1
            val make = makeExtractor ?: return Outcome.FAILED
            val t0 = SystemClock.elapsedRealtime()
            val tapped = spooledAccessUnit(kf, idx)
            val fetched = if (tapped != null) null else awaitFetch(kf, make)
            val tWaited = SystemClock.elapsedRealtime() - t0
            if (fetched != null && fetched.error != null) {
                attempts[kf] = (attempts[kf] ?: 1) - 1
                fetchFailed(fetched, st, slot)
                return Outcome.POSTPONED
            }
            val au = tapped ?: fetched?.au
            if (fetched != null) {
                fetchFailures.succeeded()
                fetchRamp.succeeded(fetched.startedAt)
            }
            val tFetch = fetched?.ms ?: tWaited
            val reqs = fetched?.requests ?: 0
            val fetchedBytes = fetched?.bytes ?: 0L
            if (priority == 2) {
                latticeRequests += reqs
                latticeBytes += fetchedBytes
            }
            if (au == null) {
                Log.w(TAG, "slot=$slot kf=$kf: keyframe not located")
                return Outcome.FAILED
            }
            fun keepForLater() {
                attempts[kf] = (attempts[kf] ?: 1) - 1
                if (fetched != null) {
                    fetches[kf] = CompletableDeferred(Fetched(fetched.au, null, 0, 0L, fetched.ms, fetched.startedAt))
                } else {
                    au.release()
                }
            }
            val charge = StripDecode.chargeFor(tier) { StripDecode.count(au.toByteArray()) }
            if (charge != tier) {
                noteStrips(0)
                val wholeFunded = MemoryGovernor.funded(busy, charge, reserveFunded, naturalFunded, screenHolds)
                val whole = afterHwClose(MemoryGovernor.allow(charge, decoderWarm = lane.decoder.warm, funded = wholeFunded), charge)
                if (!whole.allowed) {
                    keepForLater()
                    refusedForSwap = whole.swapLow
                    return deferForMemory(whole, charge, slot, priority, decodeJobs.size, wholeFunded, afterFetch = true)
                }
            }
            // The fetch can take a while: swap is read again before the decode starts. The keyframe is kept for later.
            val late = if (appMem) appMemoryVerdict(idx.video.codec, lane.decoderFor(true).warm)
                else if (MemoryGovernor.isStrongHardware) null else afterHwClose(MemoryGovernor.swapAllows(funded), charge)
            if (late != null && !late.allowed) {
                keepForLater()
                refusedForSwap = true
                return deferForMemory(late, charge, slot, priority, decodeJobs.size, funded, afterFetch = true)
            }
            val concurrent = decodeJobs.values.count { !it.result.isCompleted } + 1
            if (appMem) appMemoryHwUsed = true
            lane.acquire()
            // ATOMIC: the lane is always released, even when the session ends before the decode starts.
            val result = decodeScope.async(start = CoroutineStart.ATOMIC) {
                try {
                    if (hw) decodeOnHardware(lane, au, idx, hwChoice.name, lanes) else decodeOn(lane, au, idx)
                } catch (t: Throwable) {
                    Decoded(null, 0L, 0L, false, 0, 0, "", error = t)
                } finally {
                    lane.release()
                    au.release()
                }
            }
            val job = DecodeJob(idx, st, slot, kf, priority, lane, tapped != null, au.length, reqs, fetchedBytes, tFetch,
                tWaited, lanes, concurrent, result, hardware = hw)
            decodeJobs[kf] = job
            // Every lane busy: wait for one. With one lane this is the decode just started.
            if (decodeJobs.size >= lanes) return awaitAnyDecode(job) ?: Outcome.DONE
            return Outcome.DONE
        }

        /** A decode refused for memory: lanes, reserve, logs and the wait before the slot is tried again. Worker only. */
        private suspend fun deferForMemory(
            verdict: GovernorVerdict, tier: DecodeTier, slot: Int, priority: Int, busy: Int, funded: Boolean,
            afterFetch: Boolean,
        ): Outcome {
            if (tier == DecodeTier.HW && appMemory4K) {
                // Waiting with the decoder open would wait for memory the decoder itself holds.
                hwName?.let { hardwareOff("4K hardware decoder: ${verdict.reason}", it, counts = false) }
                if (verdict.swapLow) hwClosedMidSet = true
                closeHardwareLanes()
                if (busy > 0) awaitAnyDecode()
                return Outcome.POSTPONED
            }
            laneChange(laneCtl.onMemoryRefused())
            val close = MemoryGovernor.closesDecodersFor(verdict)
            if (verdict.swapLow) {
                val paused = !sampledPlaying
                if (swapHolds.refused(verdict, paused, funded, afterFetch, close, SystemClock.elapsedRealtime())) {
                    Log.i(TAG, swapHolds.firstLine(verdict, paused, funded, afterFetch, close))
                }
                // A running decode finishes first; its lane's decoder closes as it ends.
                if (close) closeDecoders()
            }
            if (busy > 0) {
                awaitAnyDecode()
                return Outcome.POSTPONED
            }
            if (fourK) takeReserve(tier, verdict.reason)
            if (priority == 0) {
                // A settle target that keeps being refused must not starve the coverage pass.
                if (p0DeferralSlot != slot) {
                    p0DeferralSlot = slot
                    p0Deferrals = 0
                }
                if (++p0Deferrals >= P0_MAX_DEFERRALS && prioritySlot == slot) prioritySlot = -1
            }
            giveUp.memoryRefused(verdict.reason)
            // Playback first: give our working set back, so the next decode must clear floor + charge again.
            if (!close) trimDecoders()
            buffers.releaseIdle()
            deferredSinceLog++
            val now = SystemClock.elapsedRealtime()
            if (now - lastDeferLogAt >= DEFER_LOG_INTERVAL_MS) {
                Log.d(TAG, "slot=$slot P$priority deferred (x$deferredSinceLog): ${verdict.reason}")
                lastDeferLogAt = now
                deferredSinceLog = 0
            }
            waitUnlessNewPriority(if (priority == 2) DEFER_WAIT_LATTICE_MS else DEFER_WAIT_PRIORITY_MS)
            return Outcome.POSTPONED
        }

        /** Lane thread. Background priority, so playback's threads win the CPU. A 4K decode takes up to 5 s on a Fire TV Stick. */
        private fun decodeOn(lane: DecoderLane, au: ThumbAu, idx: MediaIndex): Decoded {
            val cold = !lane.decoder.warm
            val t1 = SystemClock.elapsedRealtime()
            val frame = pacedDecode {
                atBackgroundPriority { lane.decoder.decode(au, idx.video, threads = 1) }
            } ?: return Decoded(null, SystemClock.elapsedRealtime() - t1, 0L, cold, 0, 0, "")
            val t2 = SystemClock.elapsedRealtime()
            if (fourK) noteStrips(frame.strips)
            if (idx.video.dolbyVision?.profile == 5 && !frame.colour.doviIpt) {
                // The FFmpeg 6.0 fallback cannot parse current profile-5 RPUs: better no thumbnails than green/purple.
                throw UnsupportedMediaException("Dolby Vision profile 5 without decoder DV metadata (fallback FFmpeg)")
            }
            val decision = ColourClassifier.classify(idx.video.colour, frame.colour, idx.video.dolbyVision)
            val size = ThumbGeometry.outputSize(frame.width, frame.height, frame.sarNum, frame.sarDen, idx.video.displayAspect)
            if (hwSwitch && hwPlan == null) {
                if (HardwareRoute.planUsable(decision)) hwPlan = HardwarePlan(decision, size[0], size[1])
                else hwOff = decision.reason
            }
            val bmp = pacedDecode {
                atBackgroundPriority { lane.decoder.render(frame, decision, size[0], size[1]) }
            }
            return Decoded(bmp, t2 - t1, SystemClock.elapsedRealtime() - t2, cold, size[0], size[1], decision.reason)
        }

        /**
         * Lane thread. The hardware decoder makes the thumbnail; a picture it misses is made in software, and a failure
         * sends the rest of the title to software.
         */
        private fun decodeOnHardware(lane: DecoderLane, au: ThumbAu, idx: MediaIndex, codecName: String, lanes: Int): Decoded {
            val plan = hwPlan ?: return decodeOn(lane, au, idx)
            val dec = lane.hardwareFor(codecName, hw4kSmallInput && appMemory4K)
            val cold = !dec.warm
            val t1 = SystemClock.elapsedRealtime()
            if (hwFirstDecodeAt == 0L) hwFirstDecodeAt = t1
            lane.hardwareSince = t1
            val bmp = try {
                atBackgroundPriority { dec.make(au, idx.video, plan) }
            } catch (e: HardwareThumbFailure) {
                lane.hardwareSince = 0L
                val miss = e.missed && lanes > 1
                if (e.keyframeOnly || miss) {
                    Log.d(TAG, "hardware: ${e.why}" + (if (miss) " with $lanes decoders" else "") + " - made in software")
                } else {
                    hardwareOff(e.why, codecName)
                }
                val sw = decodeOn(lane, au, idx)
                return if (!miss) sw else Decoded(sw.bitmap, sw.decodeMs, sw.renderMs, sw.cold, sw.width, sw.height,
                    sw.reason, sw.error, hardwareMiss = true)
            } finally {
                lane.hardwareSince = 0L
            }
            val ms = SystemClock.elapsedRealtime() - t1
            if (cold && appMemory4K) learnAppMemoryCost(idx.video.codec)
            if (HardwarePicture.flat(bmp.width, bmp.height, bmp::getPixel)) {
                // Black or one colour: right for a fade, wrong when the software decoder shows a picture.
                val sw = decodeOn(lane, au, idx)
                val swBmp = sw.bitmap
                if (swBmp != null && !HardwarePicture.flat(swBmp.width, swBmp.height, swBmp::getPixel)) {
                    bmp.recycle()
                    hardwareOff("one-colour picture where software shows one", codecName)
                    return sw
                }
                swBmp?.recycle()
            }
            return Decoded(bmp, ms, 0L, cold, plan.outW, plan.outH, "${plan.decision.reason} hw", hardware = true)
        }

        /** Any thread. Only the title's first failure counts against the box, and only when [counts]. */
        private fun hardwareOff(why: String, codecName: String, counts: Boolean = true) {
            synchronized(hwLock) {
                if (hwOff != null) return
                hwOff = why
            }
            val blocked = counts &&
                runCatching { HardwareFailures.book(context).failed(HardwareFailures.key(codecName)) }.getOrDefault(false)
            Log.w(DECODE_TAG, "back to software: $why" + if (blocked) " ($codecName failed " +
                "${HardwareFailureBook.FAILURES} times on this box: software for ${HardwareFailureBook.BLOCK_DAYS} days)" else "")
        }

        /** Worker only. A cold decoder is charged its learned cost; MemAvailable before it opens is kept. */
        private fun appMemoryVerdict(codec: VideoCodec, warm: Boolean): GovernorVerdict {
            val mem = MemoryGovernor.readMeminfo() ?: return GovernorVerdict(true, "meminfo unavailable")
            val v = AppMemoryHardware.verdict(mem, warm, appMemoryCostMb(codec), hw4kOpenFloorMb)
            if (!warm && v.allowed) {
                hwOpenAvailMb = mem.availableMb
                if (hwFirstOpenAvailMb < 0) hwFirstOpenAvailMb = mem.availableMb
            }
            return v
        }

        private fun appMemoryCostMb(codec: VideoCodec): Int {
            if (hwCostMb < 0) {
                val learned = runCatching {
                    HardwareFailures.costBook(context).get(HardwareCostBook.key(HardwareFailures.box, codec))
                }.getOrNull()
                hwCostMb = AppMemoryHardware.costMb(learned)
                Log.i(DECODE_TAG, "4K hardware decoder from app memory: charged $hwCostMb MB" +
                    (if (learned != null) " (measured on this box)" else " until measured") +
                    ", opens with $hw4kOpenFloorMb MB left, closes below ${AppMemoryHardware.KEEP_FLOOR_MB} MB " +
                    "or SwapFree ${AppMemoryHardware.KEEP_SWAP_MB} MB (${AppMemoryHardware.KEEP_SWAP_TIGHT_MB} under " +
                    "${AppMemoryHardware.KEEP_SWAP_AVAIL_MB} MB free)" + (if (hw4kSmallInput) ", input buffer sized to the keyframes" else "") +
                    if (hw4kReleasesFirst) ", the thumbnails' own memory given back before it opens" else "")
            }
            return hwCostMb
        }

        /**
         * Worker only, once a title, before its first 4K hardware open: the software decoders (the plan's first picture
         * was made in software), the idle thumbnail buffers and the Java heap's garbage go back, so the open is judged
         * on what the box really has. All of it comes back by itself when it is needed again.
         */
        private suspend fun releaseBeforeAppMemoryOpen() {
            if (!hw4kReleasesFirst || appMemoryReleased) return
            appMemoryReleased = true
            fun avail() = MemoryGovernor.readMeminfo()?.availableMb ?: -1L
            val r = AppMemoryRelease(avail())
            val ahead = fetches.values.sumOf { d ->
                if (!d.isCompleted || d.isCancelled) 0 else runCatching { d.getCompleted().au?.length ?: 0 }.getOrDefault(0)
            }
            Log.i(DECODE_TAG, "4K hardware open: MemAvailable ${r.lastMb} MB at the start, " +
                "${decodeJobs.values.count { !it.result.isCompleted }} decodes running, ${ahead / 1024} KB fetched ahead" +
                if (letGoAvailMb >= 0) ", ${letGoAvailMb} MB at the let-go ${(SystemClock.elapsedRealtime() - letGoAt) / 1000} s ago" else "")
            var closed = 0
            for (l in decoderLanes) if (l.closeSoftwareIfIdle()) closed++
            Log.i(DECODE_TAG, r.step("$closed software decoders closed", avail()).line())
            buffers.releaseIdle()
            Log.i(DECODE_TAG, r.step("idle thumbnail buffers given back", avail()).line())
            Runtime.getRuntime().gc()
            Log.i(DECODE_TAG, r.step("Java heap collected", avail()).line())
            val start = SystemClock.elapsedRealtime()
            var best = r.lastMb
            while (SystemClock.elapsedRealtime() - start < AppMemoryRelease.SETTLE_MS) {
                delay(AppMemoryRelease.SETTLE_POLL_MS)
                val now = avail()
                if (now <= best) break
                best = now
            }
            Log.i(DECODE_TAG, r.step("settled ${SystemClock.elapsedRealtime() - start} ms", avail()).line())
            Log.i(DECODE_TAG, r.summary())
        }

        /** Lane thread, after a cold decoder's first picture: what it took off MemAvailable. */
        private fun learnAppMemoryCost(codec: VideoCodec) {
            val before = hwOpenAvailMb.takeIf { it >= 0 } ?: return
            val after = MemoryGovernor.readMeminfo()?.availableMb ?: return
            val seen = before - after
            val next = runCatching {
                HardwareFailures.costBook(context).note(HardwareCostBook.key(HardwareFailures.box, codec), seen)
            }.getOrNull()
            if (next != null) hwCostMb = next
            Log.i(DECODE_TAG, "4K hardware decoder took $seen MB (MemAvailable $before -> $after MB)" +
                if (next != null) ", $next MB charged from now on" else ", not used as a measurement")
        }

        /** The hardware decoder for this title's keyframes now, or null for software. Worker only. */
        private fun hardwareChoice(idx: MediaIndex): HardwareCodecs.Choice? {
            if (!hwSwitch) return null
            val v = idx.video
            val choice = HardwareCodecs.choose(v)
            val blocked = choice != null &&
                runCatching { HardwareFailures.book(context).blocked(HardwareFailures.key(choice.name)) }.getOrDefault(false)
            val phase = phase()
            val appWhy = if (!fourKFromAppMemory) null else AppMemoryHardware.softwareBecause(hw4k, prepareStage in 1..4,
                playerLetGo, SystemClock.elapsedRealtime() - letGoAt, { MemoryGovernor.readMeminfo()?.availableMb ?: -1L },
                letGoAvailMb)
            val why = HardwareRoute.softwareBecause(Build.VERSION.SDK_INT, phase, v.codec, v.bitDepth,
                v.dolbyVision?.profile, hwOff, blocked, choice != null, appWhy != null, appWhy)
            if (why == null) return choice
            if (why != hwWhyLogged && phase != FetchPhase.PLAYBACK && why != hwOff) {
                hwWhyLogged = why
                Log.i(DECODE_TAG, "software for this title: $why")
            }
            return null
        }

        /** Decode lanes for the next keyframe: the hardware decoders' own count while they make the thumbnails. Worker only. */
        private fun activeLanes(hw: Boolean, choice: HardwareCodecs.Choice?, codec: VideoCodec): Int {
            if (!hw || choice == null) return laneCtl.lanes
            val l = hwLanes ?: run {
                val key = HardwareLaneBook.key(HardwareFailures.box, codec, fourK)
                val start = runCatching { HardwareFailures.laneBook(context).start(key) }.getOrDefault(1)
                val pool = VideoPoolReader.read()
                hwLaneKey = key
                hwName = choice.name
                HardwareLanes(choice.maxInstances, start, fourK, MemoryGovernor.totalMemMb) { poolFreeMb() }.also {
                    hwLanes = it
                    Log.i(DECODE_TAG, "hardware decoder: ${choice.name} on Android API ${Build.VERSION.SDK_INT}, " +
                        "${it.lanes} lane${if (it.lanes > 1) "s" else ""} (up to ${it.cap}" +
                        (if (start > 1) ", $start remembered for this box" else "") + ")")
                    Log.i(DECODE_TAG, if (pool == null) {
                        "video memory pool not readable here: the decoder count follows speed and missed pictures"
                    } else {
                        "video memory pool: ${pool.freeMb} of ${pool.totalMb} MB free; one more decoder needs " +
                            (if (fourK) "${VideoPool.ADD_MARGIN_4K_MB} MB left after its ${VideoPool.COST_4K_MB} MB, " +
                                "back to one below ${VideoPool.DROP_4K_TO_ONE_MB} MB"
                            else "${VideoPool.addMarginMb(false)} MB, one fewer below ${VideoPool.DROP_BELOW_MB} MB")
                    })
                    it.oneOnly?.let { why ->
                        Log.i(DECODE_TAG, "hardware decoder: stays at 1 (small box / pool unreadable: $why)")
                    }
                }
            }
            return l.lanes
        }

        /** Free video memory, read at most twice a second. Worker only. */
        private fun poolFreeMb(): Int? {
            val now = SystemClock.elapsedRealtime()
            if (now - poolReadAt >= 500L) {
                poolReadAt = now
                poolFree = VideoPoolReader.read()?.freeMb
            }
            return poolFree
        }

        /** The decoder count that worked, for the next title of this kind. Worker only. */
        private fun rememberHardwareLanes() {
            val l = hwLanes ?: return
            val key = hwLaneKey ?: return
            if (hwOff != null) return
            if (runCatching { HardwareFailures.laneBook(context).store(key, l) }.getOrDefault(false)) {
                Log.i(DECODE_TAG, "hardware decoder: ${l.lanes} remembered for this box")
            }
        }

        /**
         * Worker. A hardware decode that has hung is left behind (its lane waits for it) and the title goes to software;
         * so does a title whose hardware decoder made nothing in its first seconds.
         */
        private fun tendHardwareStall() {
            val now = SystemClock.elapsedRealtime()
            val name = hwName ?: return
            for (job in decodeJobs.values.filter { it.hardware && !it.result.isCompleted }) {
                if (!HardwareStall.stuck(now, job.lane.hardwareSince)) continue
                decodeJobs.remove(job.kf)
                stuckLanes += job.lane
                attempts[job.kf] = ((attempts[job.kf] ?: 1) - 1).coerceAtLeast(0)
                hardwareOff("hardware decode stuck for ${HardwareStall.STUCK_MS / 1000} s", name)
                closeHardwareLanes()
            }
            val l = hwLanes ?: return
            if (hwOff == null && HardwareStall.nothingMade(now, hwFirstDecodeAt, l.pictures)) {
                hardwareOff("no picture from the hardware decoder in ${HardwareStall.STUCK_MS / 1000} s", name, counts = false)
                closeHardwareLanes()
            }
        }

        private fun hardwareLaneChange(c: HardwareLanes.Change?) {
            c ?: return
            if (c.from == c.to) {
                Log.i(DECODE_TAG, "hardware decoder: stays at ${c.to} (${c.reason})")
                return
            }
            val rate = hwLanes?.rate(c.from) ?: 0.0
            Log.i(DECODE_TAG, "hardware decoder: ${c.from} -> ${c.to} lanes (${c.reason}, " +
                "${"%.1f".format(java.util.Locale.ROOT, rate)} a second)")
            for (i in c.to until c.from) decoderLanes[i].closeHardware()
            buffers.limit = ThumbBufferPool.limitFor(maxOf(c.to, laneCtl.lanes), fourK, MemoryGovernor.isStrongBox)
        }

        /** Gives every hardware decoder back, at once or as its decode ends. Any thread. */
        private fun closeHardwareLanes() {
            hwFirstDecodeAt = 0L
            decoderLanes.forEach { it.closeHardware() }
        }

        private fun noteStrips(strips: Int) {
            val before = stripCount
            stripCount = StripDecode.next(before, strips)
            if (stripCount != before) Log.i(TAG, if (stripCount >= 2) "4K keyframes decode in $stripCount strips " +
                "(${DecodeTier.SW_4K_STRIPS.rssMb} MB a decode instead of ${DecodeTier.SW_4K.rssMb})"
                else "4K keyframes decode whole")
        }

        /** Stores every finished decode; returns [of]'s outcome if it was one of them. Worker only. */
        private fun settleDecodes(of: DecodeJob? = null): Outcome? {
            if (decodeJobs.isEmpty()) return null
            var outcome: Outcome? = null
            for (job in decodeJobs.values.filter { it.result.isCompleted }) {
                decodeJobs.remove(job.kf)
                val o = finishDecode(job)
                if (job === of) outcome = o
            }
            return outcome
        }

        private suspend fun awaitAnyDecode(of: DecodeJob? = null): Outcome? {
            val running = decodeJobs.values.map { it.result }
            if (running.isEmpty()) return null
            if (running.none { it.isCompleted }) {
                if (decodeJobs.values.any { it.hardware }) {
                    // A hardware call can hang where its time limit cannot reach: look every second.
                    val joined = withTimeoutOrNull(HardwareStall.LOOK_MS) { select<Unit> { for (d in running) d.onJoin { } } }
                    if (joined == null) {
                        tendHardwareStall()
                        tendPrepareWhileWaiting()
                    }
                } else {
                    select<Unit> { for (d in running) d.onJoin { } }
                }
            }
            return settleDecodes(of)
        }

        private fun finishDecode(job: DecodeJob): Outcome {
            val d = job.result.getCompleted()
            d.error?.let {
                if (it !is OutOfMemoryError) throw it
                stopForMemory("out of memory decoding")
                return Outcome.FAILED
            }
            val bmp = d.bitmap ?: run {
                giveUp.decodeFailed()
                return Outcome.FAILED
            }
            if (!d.hardware) laneChange(laneCtl.onStrips(stripCount >= 2))
            if (d.hardware) hardwareLaneChange(hwLanes?.onPicture(SystemClock.elapsedRealtime()))
            else if (!d.cold) laneChange(laneCtl.onDecode(d.decodeMs + d.renderMs, job.concurrent, laneHeld()))
            if (d.hardwareMiss) hardwareLaneChange(hwLanes?.onMiss())
            measureBars(bmp)
            val kf = job.kf
            val kfPts = job.idx.keyframes.ptsUs[kf]
            val slots = keyframeSlots[kf] ?: intArrayOf(job.slot)
            val checkSlot = if (borrow != null) slots.firstOrNull { it in checkQueue } else null
            job.st.put(LongArray(slots.size) { slots[it].toLong() * SPACING_MS * 1000L }, kfPts, bmp, jpeg = d.hardware)
            if (checkSlot != null) checkBorrow(job.st, checkSlot, bmp)
            synchronized(recentMade) {
                recentMade.addLast(job.slot.toLong() * SPACING_MS)
                madeSeq++
                while (recentMade.size > 9) recentMade.removeFirst()
            }
            bumpTick()
            if (job.tapped) {
                dropSpooled(kf)
                tapHits++
            }
            Log.d(TAG, "slot=${job.slot} P${job.priority} kf@${kfPts / 1000}ms slots=${slots.size} " +
                (if (job.tapped) "src=tap(${job.auBytes / 1024}KB, #$tapHits) " else "") +
                "req=${job.requests} kb=${job.bytes / 1024} fetch=${job.fetchMs}ms " +
                "decode=${d.decodeMs}ms${if (d.cold) " (cold)" else ""} render=${d.renderMs}ms ${d.width}x${d.height} ${d.reason} " +
                "waited=${job.waitedMs}ms lanes=${job.lanes}")
            if (reserveHeld != null) reservePictures++
            // Small box, 4K settle target without funding: close the ~80 MB decoder at once, playback may be running.
            if (fourK && !MemoryGovernor.isStrongBox && job.priority == 0 && reserveHeld == null && !naturallyFunded) {
                job.lane.close()
            }
            return Outcome.DONE
        }

        private fun freeLane(lanes: Int): DecoderLane? {
            if (stuckLanes.isNotEmpty()) stuckLanes.removeAll { !it.inUse }
            val n = if (stuckLanes.isEmpty()) lanes else minOf(ThumbDecodeLanes.MAX, lanes + stuckLanes.size)
            for (i in 0 until n) {
                val l = decoderLanes[i]
                if (l in stuckLanes) continue
                if (decodeJobs.values.none { it.lane === l }) return l
            }
            return null
        }

        private fun closeDecoders() = decoderLanes.forEach { it.close() }

        private fun trimDecoders() = decoderLanes.forEach { it.trim() }

        private fun laneChange(c: ThumbDecodeLanes.Change?) {
            c ?: return
            Log.i(TAG, "decode lanes ${c.from} -> ${c.to} (${c.reason}, medianMs=${c.medianMs})")
            for (i in c.to until c.from) decoderLanes[i].close()
            buffers.limit = ThumbBufferPool.limitFor(c.to, fourK, MemoryGovernor.isStrongBox)
        }

        private fun laneHeld(): Boolean = SystemClock.elapsedRealtime() < laneHeldUntil

        /** Phase ceilings, memory trims and the clock-cap hold of the decode lanes. Worker only. */
        private fun tendLanes() {
            if (hwLanes != null && phase() == FetchPhase.PLAYBACK) closeHardwareLanes()
            if (laneTrimRequested) {
                laneTrimRequested = false
                laneChange(laneCtl.onMemoryTrim())
            }
            laneChange(laneCtl.onPhase(phase()))
            val now = SystemClock.elapsedRealtime()
            if (laneCtl.ceiling() <= 1 || now - laneClockAt < CLOCK_SAMPLE_MS) return
            laneClockAt = now
            if (!clockCapped()) return
            laneHeldUntil = now + CLOCK_HOLD_MS
            if (now - laneClockLoweredAt < CLOCK_HOLD_MS) return
            laneClockLoweredAt = now
            laneChange(laneCtl.onClockCap())
        }

        /** True when a core's highest allowed clock is below the highest seen since the app started. */
        private fun clockCapped(): Boolean {
            var capped = false
            for (cpu in 0 until clockPeakKhz.length()) {
                val khz = runCatching {
                    File("/sys/devices/system/cpu/cpu$cpu/cpufreq/scaling_max_freq").readText().trim().toLong()
                }.getOrNull() ?: continue
                if (khz < clockPeakKhz.accumulateAndGet(cpu, khz) { a, b -> maxOf(a, b) }) capped = true
            }
            return capped
        }

        private fun phase(): FetchPhase = when {
            afterPlayback -> FetchPhase.PLAYER_CLOSED
            prepareStage in 1..4 -> FetchPhase.GENERATING
            else -> FetchPhase.PLAYBACK
        }

        /** Never below 1: with no lanes left by playback's buffer, the gates hold the coverage pass instead. */
        private fun fetchLimit(): Int {
            val url = reader?.resolvedUrl ?: source.url
            val ledger = ConnectionLedger.app
            val phase = phase()
            val n = MediaServerThumbs.fetchLimit(onDemand || remoteBatched, fetchRamp.limit(ThumbFetchLanes.limit(
                phase = phase,
                rateLimited = fetchRateLimited,
                smallBox4K = fourK && !MemoryGovernor.isStrongBox,
                ledgerCap = ledger.thumbnailRoom(url),
                torBox = LedgerKey.of(url) == LedgerKey.TORBOX,
                refused = ledger.refusedLately(url),
                surplus = phase == FetchPhase.PLAYBACK && lastSampledPlaying && headroom.surplus,
            )))
            val bufferLimits = phase == FetchPhase.PLAYBACK && lastSampledPlaying
            val held = bufferLimits && headroom.lanes == 0
            val limit = if (bufferLimits) minOf(n, headroom.lanes.coerceAtLeast(1)) else n
            val key = if (held) "0" else "$limit/$phase"
            if (key != lanesLogged) {
                lanesLogged = key
                Log.i(TAG, if (held) "lanes=0 (paused: ${headroom.why})"
                    else "lanes=$limit ${ledger.budgetNote(url)} (${phase.name.lowercase()}" +
                        (if (bufferLimits) ", ${headroom.why})" else ")"))
            }
            return limit
        }

        /** Remembers the host that answered 429, and lets a held generating screen start playback. */
        private fun noteRateLimited(e: RateLimitedException) {
            sourceRateLimited = true
            RateLimitedHosts.appSession.note(e)
        }

        private fun fetchOneAtATime(why: String) {
            if (fetchRateLimited) return
            fetchRateLimited = true
            Log.i(TAG, "$why: one thumbnail fetch at a time from now on")
        }

        /**
         * Starts fetches for the next keyframes in the worker's own order, up to the lane limit. While playing, a settle
         * target's gate does not cover the others, so only it is started then.
         */
        /**
         * The keyframes the pass fetches next, for [BatchRangeReader]: one request carries many of them. Not while
         * playing, where a batch would take the film's bandwidth in one go; there each keyframe is read on its own.
         */
        private fun planBatches(st: ThumbStore, slot: Int, lattice: List<Int>, latticePos: Int) {
            val b = batchReader ?: return
            val idx = index
            if (idx == null || phase() == FetchPhase.PLAYBACK || fetchesStopped) {
                b.plan(emptyList())
                return
            }
            val kfs = LinkedHashSet<Int>()
            fun add(s: Int) {
                if (s < 0 || !wanted(st, s)) return
                val kf = slotKeyframe[s]
                if (spool[kf] != true) kfs += kf
            }
            add(slot)
            if (!latticeFinished && !latticeBudgetSpent()) {
                for (i in latticePos until minOf(lattice.size, latticePos + BatchRangeReader.PIECES * ThumbFetchLanes.MAX)) {
                    add(lattice[i])
                }
            }
            val k = idx.keyframes
            b.plan(kfs.mapNotNull { kf ->
                if (idx.kind == ContainerKind.MATROSKA) k.relPos[kf].takeIf { it >= 0 }?.let { BatchRangeReader.Planned(k.offset[kf] + it, 0) }
                else k.size[kf].takeIf { it > 0 }?.let { BatchRangeReader.Planned(k.offset[kf], it) }
            })
        }

        private fun topUpFetches(
            st: ThumbStore, slot: Int, priority: Int, neighbours: List<Int>, lattice: List<Int>, latticePos: Int,
        ) {
            val make = makeExtractor ?: return
            planBatches(st, slot, lattice, latticePos)
            val plan = ArrayList<Int>()
            fun want(s: Int) {
                if (!wanted(st, s)) return
                val kf = slotKeyframe[s]
                if (spool[kf] != true) plan.add(kf)
            }
            want(slot)
            val ahead = plan.size
            for (s in neighbours) want(s)
            if (!fetchesStopped && !latticeFinished && !latticeBudgetSpent()) {
                for (i in latticePos until minOf(lattice.size, latticePos + ThumbFetchLanes.MAX * 4)) want(lattice[i])
            }
            // Done fetches the plan has moved away from (an earlier settle target's neighbours) give their lane back.
            fetches.entries.removeAll { (kf, d) ->
                (kf !in plan && d.isCompleted && d.getCompleted().error == null).also { if (it) d.getCompleted().au?.release() }
            }
            val limit = fetchLimit()
            val decoderBehind = lastSampledPlaying && phase() == FetchPhase.PLAYBACK &&
                fetches.values.count { it.isCompleted && it.getCompleted().error == null } >= PlaybackHeadroom.DECODE_AHEAD
            if (limit <= 1 || (priority == 0 && lastSampledPlaying) || decoderBehind) plan.subList(ahead, plan.size).clear()
            for (kf in ThumbFetchLanes.pick(plan, fetches.keys, minOf(limit - fetches.size, buffers.available))) {
                startFetch(kf, make, buffers.tryAcquire() ?: break)
            }
        }

        private fun startFetch(kf: Int, make: () -> KeyframeExtractor, buf: ThumbBuffer): Deferred<Fetched> {
            val ex = spareExtractors.poll() ?: make()
            val startedAt = SystemClock.elapsedRealtime()
            // ATOMIC: the body always runs, so the buffer always goes back, also when the result is never collected.
            val made = AtomicReference<ThumbAu?>()
            val d = fetchScope.async(start = CoroutineStart.ATOMIC) {
                val req0 = ex.requests
                val bytes0 = ex.bytesRead
                val t0 = SystemClock.elapsedRealtime()
                var au: ThumbAu? = null
                try {
                    au = ex.extractAu(kf, buf)
                    made.set(au)
                    Fetched(au, null, ex.requests - req0, ex.bytesRead - bytes0, SystemClock.elapsedRealtime() - t0, startedAt)
                } catch (e: Exception) {
                    Fetched(null, e, ex.requests - req0, ex.bytesRead - bytes0, SystemClock.elapsedRealtime() - t0, startedAt)
                } catch (e: OutOfMemoryError) {
                    Fetched(null, MemoryShortException("out of memory reading a keyframe"), ex.requests - req0,
                        ex.bytesRead - bytes0, SystemClock.elapsedRealtime() - t0, startedAt)
                } finally {
                    if (au == null) buf.release()
                    spareExtractors.add(ex)
                }
            }
            d.invokeOnCompletion { if (it != null) made.get()?.release() }
            fetches[kf] = d
            return d
        }

        /** Drops the latest fetched keyframe other than [kf], to free its buffer. Worker only. */
        private fun dropReadyFetch(kf: Int): Boolean {
            val ready = fetches.entries.lastOrNull { (k, d) -> k != kf && d.isCompleted && d.getCompleted().error == null }
                ?: return false
            fetches.remove(ready.key)
            ready.value.getCompleted().au?.release()
            return true
        }

        private fun fetchState(kf: Int): ThumbFetchLanes.State {
            val d = fetches[kf] ?: return ThumbFetchLanes.State.NOT_STARTED
            if (!d.isCompleted) return ThumbFetchLanes.State.RUNNING
            return if (d.getCompleted().error == null) ThumbFetchLanes.State.READY else ThumbFetchLanes.State.FAILED
        }

        /** Until a running fetch (or decode) finishes. Worker only. */
        private suspend fun awaitAnyFetch() {
            val running = fetches.values.filter { !it.isCompleted }
            val decoding = decodeJobs.values.map { it.result }.filter { !it.isCompleted }
            if (running.isEmpty() && decoding.isEmpty()) return
            select<Unit> {
                for (j in running) j.onJoin { }
                for (d in decoding) d.onJoin { }
            }
        }

        /** This keyframe's fetch; started now, once a lane is free, if it is not under way yet. */
        private suspend fun awaitFetch(kf: Int, make: () -> KeyframeExtractor): Fetched {
            val d = fetches[kf] ?: startFetch(kf, make, awaitBuffer(kf))
            try {
                return d.await()
            } catch (e: CancellationException) {
                d.invokeOnCompletion { if (it == null) d.getCompleted().au?.release() }
                throw e
            } finally {
                fetches.remove(kf)
            }
        }

        /** A buffer for [kf]'s fetch once a fetch lane is free; a fetched keyframe not needed now gives its own up. */
        private suspend fun awaitBuffer(kf: Int): ThumbBuffer {
            val limit = fetchLimit()
            while (true) {
                val running = fetches.values.filter { !it.isCompleted }
                if (running.size < limit) {
                    buffers.tryAcquire()?.let { return it }
                    if (dropReadyFetch(kf)) continue
                    if (running.isEmpty()) {
                        delay(50L)          // the decodes still hold them
                        continue
                    }
                }
                select<Unit> { for (j in running) j.onJoin { } }
            }
        }

        /** Done fetches no longer needed are dropped, failed ones dealt with at once. */
        private suspend fun settleFetches(st: ThumbStore) {
            if (fetches.isEmpty()) return
            for ((kf, d) in fetches.entries.toList()) {
                if (!d.isCompleted) continue
                val f = d.await()
                val unneeded = fetchesStopped || keyframeSlots[kf]?.none { needs(st, it) } != false
                if (f.error == null && !unneeded) continue
                fetches.remove(kf)
                f.au?.release()
                if (f.error != null && !fetchesStopped) fetchFailed(f, st, keyframeSlots[kf]?.firstOrNull() ?: -1)
            }
        }

        private suspend fun fetchFailed(f: Fetched, st: ThumbStore, slot: Int) {
            if (f.startedAt <= gaveWayAtMs && f.error is IOException && f.error !is RateLimitedException) return
            when (val e = f.error) {
                null -> Unit
                is RateLimitedException -> {
                    giveUp.rateLimited()
                    noteRateLimited(e)
                    fetchOneAtATime("rate limited")
                    val now = SystemClock.elapsedRealtime()
                    val waitMs = fetchRamp.rateLimited(e.retryAfterMs, f.startedAt, now) ?: return
                    rateLimitedUntil = maxOf(rateLimitedUntil, now + waitMs)
                    val why = if (e.retryAfterMs != null) "server's Retry-After" else "back-off ${fetchRamp.hitsInRow}"
                    Log.w(TAG, "rate limited: pausing thumbnail fetches ${waitMs / 1000}s ($why)")
                }
                is UnsupportedMediaException -> throw e
                is MemoryShortException -> {
                    stopForMemory(e.message ?: "out of memory")
                    releasePrepareHold()
                }
                is ConnectionLedger.NoSlotException -> {
                    rethrowIfStopping(e)
                    giveUp.noConnectionFree()
                    rateLimitedUntil = maxOf(rateLimitedUntil, SystemClock.elapsedRealtime() + 1_000L)
                }
                is IOException -> {
                    rethrowIfStopping(e)
                    giveUp.fetchFailed(e)
                    fetchOneAtATime("a fetch failed")
                    // Fetches under way together fail together: counted once.
                    val waitMs = fetchFailures.failedFetch(f.startedAt, SystemClock.elapsedRealtime()) ?: return
                    if (fetchFailures.gaveUp) {
                        fetchesStopped = true
                        latticeFinished = true
                        releasePrepareHold()
                        bumpTick()
                        Log.w(TAG, "${fetchFailures.count} fetches failed in a row (${e.javaClass.simpleName}): " +
                            "no new thumbnails this session (stored=${st.slots().size})")
                        return
                    }
                    rateLimitedUntil = maxOf(rateLimitedUntil, SystemClock.elapsedRealtime() + waitMs)
                    Log.w(TAG, "slot=$slot fetch failed (${e.javaClass.simpleName}): retry in ${waitMs / 1000}s")
                }
                else -> throw e
            }
        }

        private val memoryStop = MemoryStop()

        /** After the player closed: the box stays short of memory, so the coverage pass stops for this title. */
        private fun memoryShortAfterClose(): Boolean {
            if (memoryStop.stopped) return true
            if (!memoryStop.check(SystemClock.elapsedRealtime()) { MemoryGovernor.pressure() }) return false
            Log.i(TAG, "4K-C: memory short (${memoryStop.reason}) - stopping the coverage pass for this title")
            return true
        }

        /** Out of memory: no new thumbnails for this title, nothing retried. Worker only. */
        private fun stopForMemory(why: String) {
            giveUp.memoryRefused(why)
            if (memoryStop.outOfMemory(why)) {
                Log.w(TAG, "$why: no new thumbnails for this title (stored=${store?.slots()?.size})")
            }
            fetchesStopped = true
            latticeFinished = true
            tapOpen = false
            copy?.stop("out of memory")
            trimDecoders()
            buffers.releaseIdle()
            bumpTick()
        }

        private inline fun <T> pacedDecode(block: () -> T): T {
            // Only decodes while playing can cost playback frames.
            if (!fourK || MemoryGovernor.isStrongBox || !sampledPlaying) return block()
            synchronized(pacer) { pacer.decodeStarted(SystemClock.elapsedRealtime()) }
            try {
                return block()
            } finally {
                synchronized(pacer) { pacer.decodeEnded(SystemClock.elapsedRealtime()) }
            }
        }

        /** Main thread, once a second: feeds [pacer] dropped frames and stalls, except drops around seeks and pauses. */
        private suspend fun monitorPlayback() {
            var quietUntil = 0L
            var lastPos = -1L
            var lastAt = 0L
            var lastLogAt = SystemClock.elapsedRealtime()
            var countedAtLog = 0
            var ignoredAtLog = 0
            while (!ended) {
                val now = SystemClock.elapsedRealtime()
                val p = if (afterPlayback) null else playerProvider()
                if (p != null && p !== monitoredPlayer) {
                    val rebuilt = monitoredPlayer != null
                    monitoredPlayer = p
                    if (rebuilt) playerRebuiltDuringPrepare()
                }
                if (p != null) {
                    val playing = p.isPlaying
                    val buffering = p.playbackState == Player.STATE_BUFFERING
                    if (playing) {
                        // Playing again by any route. The worker must see it too, or it re-takes the cap from a stale sample.
                        sampledPlaying = true
                        if (prepareActive()) prepareStepAside()
                        if (reserveIsPauseCap) runCatching { reserveHeld?.release() }
                    }
                    val pos = p.currentPosition
                    if (!playing || buffering) {
                        quietUntil = now + SEEK_QUIET_MS
                    } else if (lastPos >= 0) {
                        val expected = lastPos + ((now - lastAt) * p.playbackParameters.speed).toLong()
                        if (kotlin.math.abs(pos - expected) > SEEK_JUMP_MS) quietUntil = now + SEEK_QUIET_MS
                    }
                    if (now - lastScrubAt < SEEK_QUIET_MS) quietUntil = maxOf(quietUntil, lastScrubAt + SEEK_QUIET_MS)
                    lastPos = pos
                    lastAt = now
                    val dropped = runCatching {
                        p.videoDecoderCounters?.let { it.ensureUpdated(); it.droppedBufferCount }
                    }.getOrNull() ?: -1
                    val stallAt = runCatching { reserveProvider()?.lastStallRealtimeMs }.getOrNull() ?: 0L
                    val ticks = synchronized(pacer) {
                        listOf(
                            pacer.observeDrops(now, dropped, excluded = now < quietUntil)
                                .let { PaceTick(it, pacer.lastNote, pacer.paceMs, pacer.levelPaceMs) },
                            pacer.observeStall(now, stallAt).let { PaceTick(it, pacer.lastNote, pacer.paceMs, pacer.levelPaceMs) },
                        )
                    }
                    for (t in ticks) when (t.event) {
                        PlaybackDecodePacer.Event.SLOWER -> Log.d(TAG, "4K pace: ${t.levelPaceMs / 2000} s -> " +
                            "${t.levelPaceMs / 1000} s (${t.note}), now ${t.paceMs / 1000} s")
                        PlaybackDecodePacer.Event.FASTER -> Log.d(TAG, "4K pace: ${t.levelPaceMs * 2 / 1000} s -> " +
                            "${t.levelPaceMs / 1000} s (${t.note}), now ${t.paceMs / 1000} s")
                        PlaybackDecodePacer.Event.STOPPED_DROPS ->
                            Log.i(TAG, "4K playback decodes stopped: drops (${t.note}) - coverage pass only while paused / after exit")
                        PlaybackDecodePacer.Event.STOPPED_STALL ->
                            Log.i(TAG, "4K playback decodes stopped: stall (${t.note})")
                        PlaybackDecodePacer.Event.RESUMED_AFTER_STALL ->
                            Log.i(TAG, "4K playback decodes resumed (${t.note}), pace ${t.paceMs / 1000} s")
                        PlaybackDecodePacer.Event.NONE -> Unit
                    }
                    if (copy != null) {
                        val copyTicks = synchronized(copyPacer) {
                            listOf(
                                copyPacer.observeDrops(now, dropped, excluded = now < quietUntil),
                                copyPacer.observeStall(now, stallAt),
                            ).map { Triple(it, copyPacer.lastNote, copyPacer.paceMs) }
                        }
                        for ((event, note, paceMs) in copyTicks) {
                            if (event != PlaybackDecodePacer.Event.NONE) {
                                Log.i(COPY_TAG, "copy pace: ${event.name.lowercase()} ($note), pace now ${paceMs / 1000.0} s")
                            }
                        }
                    }
                    val pace = ticks.last().paceMs
                    if (now - lastLogAt >= PACE_LOG_INTERVAL_MS) {
                        lastLogAt = now
                        val (counted, ignored, stops) = synchronized(pacer) {
                            Triple(pacer.countedDrops, pacer.ignoredDrops, pacer.stoppedByDrops to pacer.stoppedByStall)
                        }
                        Log.d(TAG, "4K pace status: pace ${pace / 1000} s, drops counted ${counted - countedAtLog} " +
                            "ignored ${ignored - ignoredAtLog} in ${PACE_LOG_INTERVAL_MS / 1000} s (counter $dropped), " +
                            "stopped drops=${stops.first} stall=${stops.second}, natural=$naturallyFunded " +
                            "reserve=${reserveHeld != null}, playing=$playing")
                        countedAtLog = counted
                        ignoredAtLog = ignored
                    }
                } else {
                    lastPos = -1L
                }
                delay(MONITOR_INTERVAL_MS)
            }
        }

        private var monitoredPlayer: Player? = null

        /**
         * Main thread: the player was rebuilt (a sound output that came back, a recovery) while the screen had it let go.
         * The new one lets go too; a 4K hardware decoder from app memory never stays open beside a film that loads.
         */
        private fun playerRebuiltDuringPrepare() {
            if (prepareStage !in 1..4 || !playerLetGo) return
            if (appMemory4K) closeHardwareLanes()
            val again = hostResumed && runCatching { letGoForPrepare() }.getOrDefault(false)
            if (again) noteLetGo()
            else if (appMemory4K) hwName?.let { hardwareOff("the player was rebuilt behind the screen", it, counts = false) }
            Log.i(TAG, "prepare: the player was rebuilt behind the screen - " +
                when {
                    again -> "it lets go again"
                    appMemory4K -> "it keeps its decoders, the 4K thumbnails stay on software"
                    else -> "it keeps its decoders"
                })
        }

        private class PaceTick(val event: PlaybackDecodePacer.Event, val note: String, val paceMs: Long, val levelPaceMs: Long)

        private inline fun <T> atBackgroundPriority(block: () -> T): T {
            val tid = android.os.Process.myTid()
            val before = runCatching { android.os.Process.getThreadPriority(tid) }.getOrNull()
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
            try {
                return block()
            } finally {
                if (before != null) runCatching { android.os.Process.setThreadPriority(before) }
            }
        }

        private suspend fun waitUnlessNewPriority(ms: Long) {
            val entry = prioritySlot
            val until = SystemClock.elapsedRealtime() + ms
            while (SystemClock.elapsedRealtime() < until) {
                if (prioritySlot >= 0 && prioritySlot != entry) return
                if (inBackground()) return
                tendReserve()
                settleDecodes()
                tendPrepareWhileWaiting()
                delay(250L)
            }
        }

        /** Gate and deferral waits can last many seconds: keeps the generating screen up to date from them. */
        private suspend fun tendPrepareWhileWaiting() {
            if (prepareStage !in 1..4 || latticeTotal <= 0) return
            val now = SystemClock.elapsedRealtime()
            if (now - prepareTendedAt < 1_000L) return
            prepareTendedAt = now
            samplePlayer()?.let { sampledPlaying = it.isPlaying }
            tendPrepare(latticeTotal, latticeDone)
        }

        /** True when a keyframe due within [TAP_ARM_LEAD_US] after [timeUs] serves a slot that still needs a thumbnail. */
        fun tapWants(timeUs: Long): Boolean {
            if (ended || !tapOpen) return false
            if (index?.video?.codec?.nal != true) return false   // the tap handles H.264/HEVC only
            val map = slotKeyframe
            val pts = index?.keyframes?.ptsUs ?: return false
            val st = store ?: return false
            if (map.isEmpty() || timeUs < 0) return false
            val holdsUntilPause = fourK && remux && !MemoryGovernor.isStrongBox && !stripsWhilePlaying()
            val s0 = (timeUs / 1000L / SPACING_MS).toInt()
            for (sl in s0..s0 + 1) {
                if (sl !in map.indices) continue
                val kf = map[sl]
                if (kf !in pts.indices) continue
                val due = pts[kf] - timeUs
                if (due in 1..TAP_ARM_LEAD_US && !spool.containsKey(kf) &&
                    TapPolicy.copies(sl, holdsUntilPause, latticeStrides.last(), latticeFinished,
                        spoolFull = spoolBytes.get() >= SPOOL_MAX_BYTES) &&
                    !st.has(sl.toLong() * SPACING_MS * 1000L)
                ) return true
            }
            return false
        }

        fun tapOffer(timeUs: Long, sample: ByteArray) {
            if (ended || !tapOpen) return
            val idx = index ?: return
            val kf = idx.keyframes.nearest(timeUs)
            if (kf < 0) return
            if (kotlin.math.abs(idx.keyframes.ptsUs[kf] - timeUs) > TAP_MATCH_US) {
                if (!tapMismatchLogged) {
                    tapMismatchLogged = true
                    Log.i(TAG, "tap: player time ${timeUs / 1000}ms matches no index keyframe (nearest " +
                        "${idx.keyframes.ptsUs[kf] / 1000}ms) - tap unused for this title")
                }
                return
            }
            if (spool.putIfAbsent(kf, false) != null) return
            if (spoolPending.incrementAndGet() > SPOOL_MAX_PENDING) {
                spoolPending.decrementAndGet()
                spool.remove(kf)
                return
            }
            spoolWriter.launch {
                try {
                    spoolDir.mkdirs()
                    File(spoolDir, "$kf.au").writeBytes(sample)
                    spoolBytes.addAndGet(sample.size.toLong())
                    spoolOrder.addLast(kf)
                    spool[kf] = true
                    while (spoolBytes.get() > SPOOL_MAX_BYTES) {            // FIFO under the cap
                        val old = spoolOrder.pollFirst() ?: break
                        dropSpooled(old)
                    }
                } catch (t: Throwable) {
                    spool.remove(kf)
                } finally {
                    spoolPending.decrementAndGet()
                }
            }
        }

        private fun dropSpooled(kf: Int) {
            if (spool.remove(kf) == null) return
            val f = File(spoolDir, "$kf.au")
            spoolBytes.addAndGet(-f.length())
            f.delete()
        }

        private fun spooledAccessUnit(kf: Int, idx: MediaIndex): ThumbAu? {
            if (spool[kf] != true) return null
            val f = File(spoolDir, "$kf.au")
            if (f.length() > ThumbReads.MAX_READ) return null
            val bytes = runCatching { f.readBytes() }.getOrNull() ?: return null
            return tapSampleToAnnexB(bytes, idx.video)?.let { ThumbAu.of(it) }
        }

        private fun spooledSlot(st: ThumbStore): Int {
            if (!memoryStop.allowsSideWork) return -1
            for ((kf, ready) in spool) {
                if (!ready) continue
                val slot = keyframeSlots[kf]?.firstOrNull { wanted(st, it) }
                if (slot != null) return slot
                if (keyframeSlots[kf]?.none { needs(st, it) } != false) dropSpooled(kf)   // nothing left to serve
            }
            return -1
        }

        /**
         * The finest stride whose sweep is stored, not the finest the pass may still make: the before-play grid is
         * coarser than that, and on small boxes a 4K remux makes nothing while it plays, so the strip had empty frames
         * between the pictures. It gets denser as the pass fills in. Rechecked at most every [STRIP_RECHECK_MS].
         */
        fun stripSpacingMs(): Long? {
            val st = store
            val keyframes = slotKeyframe
            if (index == null || keyframes.isEmpty() || st == null) return null
            val now = SystemClock.elapsedRealtime()
            if (stripStride <= 0 || now - stripCheckedAt >= STRIP_RECHECK_MS) {
                stripStride = SeekGrid.finestStoredStride(keyframes.size, latticeStrides) { slot ->
                    keyframes[slot] < 0 || st.has(slot.toLong() * SPACING_MS * 1000L)
                } ?: return null
                stripCheckedAt = now
            }
            return stripStride * SPACING_MS
        }

        /** Does not touch [lastServed]. A picture only on disk is loaded and shows on the next tick. */
        fun tileFor(positionMs: Long): Shown? {
            val st = store ?: return null
            if (slotKeyframe.isEmpty()) return null
            val target = slotOf(positionMs)
            val slot = nearestStoredSlot(st, target) ?: return null
            val bmp = st.memGet(slot.toLong() * SPACING_MS * 1000L)
            if (bmp == null) {
                requestDiskLoad(st, slot)
                return null
            }
            return Shown(bmp, slotPtsMs(slot), exact = slot == target, crop = crop.current)
        }

        fun shownFor(positionMs: Long): Shown? {
            val st = store ?: return null
            if (slotKeyframe.isEmpty()) return null
            val target = slotOf(positionMs)
            val nearest = nearestStoredSlot(st, target)
            // A borrowed picture only when it is nearer than our own stand-in.
            val ownGap = nearest?.let { kotlin.math.abs(it - target) } ?: Int.MAX_VALUE
            if (ownGap > 0) borrowedFor(target, within = ownGap)?.let { b ->
                lastServed = b.bitmap
                lastServedSlot = target
                return b
            }
            val slot = nearest ?: return null
            val key = slot.toLong() * SPACING_MS * 1000L
            val bmp = st.fullColourGet(key) ?: st.memGet(key)?.also { requestFullColour(st, slot) }
            if (bmp != null) {
                lastServed = bmp
                lastServedSlot = slot
                return Shown(bmp, slotPtsMs(slot), exact = slot == target, crop = crop.current)
            }
            // On disk only: load it, and keep the previous picture until it lands rather than blanking the pane.
            requestDiskLoad(st, slot)
            val prev = lastServed ?: return null
            return Shown(prev, slotPtsMs(lastServedSlot), exact = false, crop = crop.current)
        }

        fun landingFor(targetMs: Long): Long? {
            if (store == null || slotKeyframe.isEmpty()) return null
            // The slot's keyframe even when its picture is not stored yet: a CLOSEST_SYNC seek to the bare grid point
            // can snap back onto the keyframe playback is already on.
            return slotPtsMs(slotOf(targetMs)).takeIf { it >= 0 }
        }

        fun stepPastBase(gridMs: Long, baseMs: Long, forward: Boolean): Long =
            SeekGrid.stepPastBase(gridMs, baseMs, forward, SPACING_MS, slotKeyframe.size, ::slotPtsMs)

        private fun slotPtsMs(slot: Int): Long {
            val kf = slotKeyframe.getOrElse(slot) { -1 }
            val pts = index?.keyframes?.ptsUs ?: return -1L
            return if (kf in pts.indices) pts[kf] / 1000L else -1L
        }

        /** The stored slot (memory or disk) nearest to [slot] within [fallbackSlots], exact slot first. */
        private fun nearestStoredSlot(st: ThumbStore, slot: Int): Int? {
            if (st.has(slot.toLong() * SPACING_MS * 1000L)) return slot
            for (d in 1..fallbackSlots) {
                if (slot - d >= 0 && st.has((slot - d).toLong() * SPACING_MS * 1000L)) return slot - d
                if (slot + d < slotKeyframe.size && st.has((slot + d).toLong() * SPACING_MS * 1000L)) return slot + d
            }
            return null
        }

        fun frameAspect(): Float? {
            if (ended || slotKeyframe.isEmpty()) return null
            // 4K that cannot decode right now and has nothing stored: no empty frame to fill, so no pane.
            if (!decodeOpen && store?.isEmpty() != false && borrow == null) return null
            val v = index?.video ?: return null
            val size = ThumbGeometry.outputSize(v.width, v.height, 1, 1, v.displayAspect)
            // The crop was measured on stored pictures, whose size can differ from this estimate: apply it as fractions.
            val c = crop.current?.takeIf { it.width > 0 && it.height > 0 }
            val fw = if (c == null) 1f else (c.width - c.left - c.right).toFloat() / c.width
            val fh = if (c == null) 1f else (c.height - c.top - c.bottom).toFloat() / c.height
            return (size[0] * fw) / (size[1] * fh).coerceAtLeast(1f)
        }

        private fun measureBars(bmp: Bitmap) {
            if (crop.sampleCount >= ThumbCrop.Estimator.MAX_SAMPLES) return
            val w = bmp.width
            val h = bmp.height
            val px = IntArray(w * h)
            runCatching { bmp.getPixels(px, 0, w, 0, 0, w, h) }.onFailure { return }
            crop.add(w, h, ThumbCrop.measure(w, h, px))
        }

        /** A reopened store: measure the bars on a few stored pictures, so the first scrub is already cropped. */
        private fun seedCropFromStore(st: ThumbStore) {
            val stored = st.slots().sorted()
            if (stored.isEmpty()) return
            val n = minOf(ThumbCrop.Estimator.MIN_SAMPLES + 3, stored.size)
            for (i in 0 until n) {
                val bmp = st.memGet(stored[i * stored.size / n]) ?: st.load(stored[i * stored.size / n]) ?: continue
                measureBars(bmp)
            }
        }

        /** A title with saved pictures: their bytes go to memory, and the ones around the position are decoded. */
        private fun preloadSaved(st: ThumbStore) {
            if (st.isEmpty()) return
            CoroutineScope(Dispatchers.IO).launch {
                val cap = MemoryGovernor.preloadBytes()
                if (!st.preloadCompressed(cap)) return@launch
                val here = slotOf(lastPositionMs.coerceAtLeast(0L))
                for (d in 0..PRELOAD_WARM_SLOTS) {
                    for (s in intArrayOf(here + d, here - d)) {
                        if (s in slotKeyframe.indices) st.load(s.toLong() * SPACING_MS * 1000L)
                    }
                }
                bumpTick()
                Log.d(TAG, "saved thumbnails in memory (${st.slots().size})")
            }
        }

        /**
         * Another release's store (same title and length), read only; shown until confirmed, then copied over. [copy]:
         * a smaller copy's store that pass is still filling. [wideUs]: keyframes farther apart are not copied over.
         */
        private class Borrow(
            val store: ThumbStore, val timing: ReleaseMatch.Timing, val slotCount: Int,
            val copy: CopyPass? = null, val wideUs: Long = ReleaseMatch.WIDE_US,
            val fit: BorrowFit? = null,
        ) {
            /** Borrowed slot -> (its picture, that picture fitted to ours), for the few shown while scrubbing. */
            val fitted = LruCache<Int, Pair<Bitmap, Bitmap>>(FITTED_SHOWN_KEPT)
            @Volatile var confirmed = false
            /** A clear mismatch was seen: hidden until [ReleaseMatch.MATCHES_AFTER_MISMATCH] same-frame pairs match. */
            @Volatile var failed = false
            /** Same-frame matches since the mismatch. Worker only. */
            var matchesSinceMiss = 0

            fun map(slot: Int): Int = timing.mapSlot(slot, slotCount, SPACING_MS)
        }

        /** Another release's pictures are [fromW] x [fromH]: each is scaled to ours and its bars cut or added by [plan]. */
        private class BorrowFit(val plan: CopyFit.Plan, val fromW: Int, val fromH: Int, val toW: Int, val toH: Int)

        private fun fitTo(src: Bitmap, f: BorrowFit): Bitmap {
            val out = Bitmap.createBitmap(f.toW, f.toH, Bitmap.Config.ARGB_8888)
            Canvas(out).apply {
                drawColor(Color.BLACK)
                drawBitmap(src, null, Rect(0, f.plan.top, f.plan.renderW, f.plan.top + f.plan.renderH),
                    Paint(Paint.FILTER_BITMAP_FLAG))
            }
            return out
        }

        @Volatile private var borrow: Borrow? = null
        private var borrowSince = 0L
        /** Own slots whose first decode is compared with the borrowed picture, in order. Worker only. */
        private val checkQueue = ArrayList<Int>()
        /** Check slot -> own to borrowed keyframe gap (us). Worker only. */
        private val checkGap = HashMap<Int, Long>()
        private var checkMatches = 0
        /** Scores of the borderline same-frame pairs of the current check. Worker only. */
        private val checkBorderline = ArrayList<Float>()

        private fun slotUs(slot: Int): Long = slot.toLong() * SPACING_MS * 1000L

        /**
         * An incomplete store looks for another release of the title with the same length. [confirmedOnly]: only one
         * this store confirmed before, copied at once. Worker only.
         */
        private fun setUpBorrow(idx: MediaIndex, st: ThumbStore, confirmedOnly: Boolean) {
            if (!source.identityExact || borrow != null || slotKeyframe.isEmpty() || coverageStored(st)) return
            val ownMs = lastDurationMs.takeIf { it > 0 } ?: (idx.durationUs / 1000L)
            val v = idx.video
            val own = ThumbGeometry.outputSize(v.width, v.height, 1, 1, v.displayAspect)
            val candidates = runCatching { ThumbStore.siblings(context, source.identity, st.key) }.getOrDefault(emptyList())
                .filter {
                    it.pictures >= MIN_BORROW_PICTURES && st.verdictOn(it.key) != false &&
                        (!confirmedOnly || st.verdictOn(it.key) == true) && releasePair(st.key, it.key) !in differentReleases
                }
                .mapNotNull { s -> ReleaseMatch.ratio(ownMs, s.durationMs)?.let { s to it } }
                .sortedWith(compareByDescending<Pair<ThumbStore.Sibling, Double>> { st.verdictOn(it.first.key) == true }
                    .thenByDescending { it.first.pictures })
            for ((sib, ratio) in candidates) {
                ThumbStore.markBusy(sib.key)
                val other = runCatching { ThumbStore.openOther(context, sib.key, rgb565) }.getOrNull()
                val size = other?.let { o -> o.slots().minOrNull()?.let(o::compressed)?.let(::pictureSize) }
                val sameSize = size != null && size[0] == own[0] && size[1] == own[1]
                val plan = if (size == null || sameSize) null else CopyFit.plan(size[0], size[1], own[0], own[1])
                if (other == null || size == null || (!sameSize && plan == null)) {
                    ThumbStore.unmarkBusy(sib.key)
                    if (size != null) Log.i(TAG, "reuse: another release has ${size[0]}x${size[1]} pictures " +
                        "(ours ${own[0]}x${own[1]}) - not used")
                    continue
                }
                val fit = plan?.let { BorrowFit(it, size[0], size[1], own[0], own[1]) }
                if (fit != null) Log.i(TAG, "reuse: another release has ${size[0]}x${size[1]} pictures " +
                    "(ours ${own[0]}x${own[1]}) - fitted to ours")
                val b = Borrow(other, ReleaseMatch.Timing(ratio), ReleaseMatch.slotCount(sib.durationMs, SPACING_MS), fit = fit)
                borrow = b
                borrowSince = SystemClock.elapsedRealtime()
                val timing = b.timing.describe()
                if (st.verdictOn(other.key) == true) {
                    Log.i(TAG, "reuse: another release confirmed earlier ($timing)")
                    adoptBorrow(st, b)
                    return
                }
                val eligible = checkEligible(idx, st, b)
                checkQueue.clear()
                checkGap.clear()
                checkQueue += ReleaseMatch.pickCheckSlots(eligible, slotKeyframe.size)
                for ((s, gap) in eligible) if (s in checkQueue) checkGap[s] = kotlin.math.abs(gap)
                checkMatches = 0
                checkBorderline.clear()
                if (checkQueue.size < ReleaseMatch.CHECKS_NEEDED) {
                    Log.i(TAG, "reuse: too few borrowed pictures at our keyframes' times - not used")
                    dropBorrow()
                    continue
                }
                Log.i(TAG, "reuse: showing ${other.slots().size} thumbnails of another release ($timing, " +
                    "${sib.durationMs / 1000} s against ${ownMs / 1000} s) until ${ReleaseMatch.CHECKS_NEEDED} own pictures " +
                    "confirm them (check at ${checkQueue.map { "${it * SPACING_MS / 1000} s/${checkGap[it]?.div(1000)} ms" }})")
                bumpTick()
                return
            }
        }

        /**
         * Worker only. A media server away from home: looks for a copy of the title that fits, a few seconds before the
         * generating screen is decided, then in the background while the source list is still loading.
         */
        private suspend fun probeAwayCopy() {
            val sources = source.copySources ?: return
            if (!source.identityExact) return
            awayProbeAt = SystemClock.elapsedRealtime()
            val screenLikely = source.holdBeforePlay && runCatching { SeekThumbnailPreferences.prepareMode(context) }
                .getOrDefault(ThumbPrepareMode.OFF) != ThumbPrepareMode.OFF
            if (screenLikely) runCatching { sources.loadNow() }.onFailure { if (it is CancellationException) throw it }
            if (awayCopyDecided(sources, AWAY_PROBE_MS, beforeStart = true)) return
            probeJob = worker.launch { awayCopyDecided(sources, AWAY_PROBE_LATE_MS, beforeStart = false) }
        }

        private var awayProbeAt = 0L
        /** When a copy was chosen to make the thumbnails; cleared once its first thumbnail is in. */
        @Volatile private var awayCopyChosenAt = 0L

        /**
         * True once the source list answered; [awayCopy] then says whether a copy fits. A copy that fits before the
         * start takes the place of the server's batched pass; a later answer takes over while that pass still runs,
         * or helps a stream that gives the spot a seek asks for.
         */
        private suspend fun awayCopyDecided(sources: CopySources, waitMs: Long, beforeStart: Boolean): Boolean {
            val deadline = SystemClock.elapsedRealtime() + waitMs
            while (!ended && !stoppedExternally) {
                if (lastDurationMs <= 0L) sample()?.durationMs?.takeIf { it > 0 }?.let { lastDurationMs = it }
                val ownMs = lastDurationMs
                val offers = if (ownMs > 0L) {
                    runCatching { sources.offers() }.onFailure { if (it is CancellationException) throw it }.getOrNull()
                } else null
                if (offers != null) {
                    val listMs = SystemClock.elapsedRealtime() - awayProbeAt
                    val verdicts = CopyChoice.awayVerdicts(offers, ownMs)
                    val fitting = verdicts.count { it.second == null }
                    val fits = !offers.playingTranscoded && fitting > 0
                    val takesOver = !beforeStart &&
                        MediaServerThumbs.copyTakesOver(fits, remoteBatched, onDemand, latticeFinished)
                    if (takesOver) {
                        onDemand = true
                        latticeFinished = true
                        batchReader?.plan(emptyList())
                    }
                    awayCopy = MediaServerThumbs.copyFills(if (beforeStart) awayServer else onDemand, true,
                        source.identityExact, fits)
                    if (awayCopy && remoteBatched) {
                        remoteBatched = false
                        onDemand = true
                        latticeFinished = true
                    }
                    if (awayCopy) {
                        awayCopyChosenAt = SystemClock.elapsedRealtime()
                        val top = CopyChoice.order(verdicts.filter { it.second == null }.map { it.first }, ownMs = ownMs,
                            awayServer = true) { sources.multiRange(it) }.firstOrNull()
                        if (top != null) prewarmJob = worker.launch {
                            val ms = runCatching { sources.prewarm(top) }
                                .onFailure { if (it is CancellationException) throw it }.getOrNull()
                            if (ms != null) Log.i(COPY_TAG, "media server away from home: link of ${top.label} followed ahead in $ms ms")
                        }
                    }
                    val n = offers.offers.size
                    Log.i(COPY_TAG, "media server away from home: source list after $listMs ms, " + when {
                        awayCopy && takesOver -> "$fitting of $n sources fit - a copy takes over from the server's batched pass"
                        awayCopy -> "$fitting of $n sources fit - a copy makes the thumbnails"
                        offers.playingTranscoded -> "the server transcodes - only the spot a seek asks for"
                        fits && remoteBatched -> "$fitting of $n sources fit, but the server's batched pass already made the thumbnails"
                        fits -> "$fitting of $n sources fit, but they are not used for this stream"
                        remoteBatched -> "no copy of this title among $n sources fits - the server's batched pass makes the thumbnails"
                        else -> "no copy of this title among $n sources fits - only the spot a seek asks for"
                    })
                    if (fitting == 0 && verdicts.isNotEmpty()) {
                        val editions = CopyChoice.editions(offers.playingText).joinToString { it.name.lowercase() }
                        Log.i(COPY_TAG, "media server away from home: not used (own ${ownMs / 1000} s, editions " +
                            "${editions.ifEmpty { "none" }}): " + CopyChoice.reasonTally(verdicts.mapNotNull { it.second }))
                        verdicts.asSequence().filter { it.second != null }.take(AWAY_REJECT_LOGS).forEach { (o, why) ->
                            Log.i(COPY_TAG, "not used: ${o.label} (${o.kind.name.lowercase()}, ${o.height}p) - $why")
                        }
                    }
                    return true
                }
                if (SystemClock.elapsedRealtime() >= deadline) return false
                delay(if (waitMs <= AWAY_PROBE_MS) 500L else AWAY_PROBE_POLL_MS)
            }
            return false
        }

        /** The generating screen's work is over: the coverage pass, or on demand the copy and its check. */
        private fun coverageOver(): Boolean {
            if (!onDemand) return latticeFinished
            return copy?.ended ?: (copyTried || borrow != null)
        }

        /** Another release of the same length has a saved set this title can show while it plays. Worker only. */
        private fun reusableSaved(st: ThumbStore): Boolean {
            if (!source.identityExact || onDemand) return false
            val ownMs = lastDurationMs.takeIf { it > 0 } ?: return false
            val found = runCatching { ThumbStore.siblings(context, source.identity, st.key) }.getOrDefault(emptyList())
                .any {
                    ReleaseMatch.reusable(ownMs, it.durationMs, it.pictures, MIN_BORROW_PICTURES, st.verdictOn(it.key)) &&
                        releasePair(st.key, it.key) !in differentReleases
                }
            if (found) Log.i(TAG, "reuse: another release of the same length has saved thumbnails - index read now")
            return found
        }

        /** Own slots already compared with a smaller copy's pictures. Worker only. */
        private val checkedSlots = HashSet<Int>()

        /**
         * The session's own coverage pass waits while a smaller copy's pictures are being made. A Usenet stream waits
         * while the copy pass runs within [UsenetCopy]'s limits; only the picture check and scrub stops read it.
         */
        private fun copyHoldsLattice(): Boolean {
            val c = copy ?: return copyFirst && prepareStage in 1..4 && copyScreenWait
            if (usenet) return usenetCopyHolds(c)
            if (prepareStage in 1..4) {
                // Copy first behind the screen: the title's own pass waits for the copy, and while a confirmed copy fills.
                return copyFirst && !c.ended &&
                    (copyScreenWait || (borrow?.let { it.copy === c && it.confirmed } == true && c.filling) ||
                        copyTurnHolds(c))
            }
            if (borrow?.failed == true) return false
            val now = SystemClock.elapsedRealtime()
            if (!c.filling) {
                if (copyFirst && !fourK) return false
                val waits = CopyChoice.ownPassWaitsForLook(MemoryGovernor.isStrongBox,
                    c.state == CopyPass.State.LOOKING, now - copyStartedAt,
                    if (copyFirst) CopyFirst.FALLBACK_MS else CopyChoice.LOOK_HOLD_MS)
                if (!waits && c.state == CopyPass.State.LOOKING && !copyLookReleased && !MemoryGovernor.isStrongBox) {
                    copyLookReleased = true
                    Log.i(COPY_TAG, "no copy found in ${CopyChoice.LOOK_HOLD_MS / 1000} s - the 4K pass carries on")
                }
                return waits
            }
            if (c.made != copyHoldMade || copyHoldSince == 0L) {
                copyHoldMade = c.made
                copyHoldSince = now
            }
            if (MemoryGovernor.isStrongBox && now - copyHoldSince > COPY_STALL_HOLD_MS) {
                if (!copyHoldReleased) {
                    copyHoldReleased = true
                    Log.i(COPY_TAG, "no copy progress in ${COPY_STALL_HOLD_MS / 60_000} min - the 4K pass carries on")
                }
                return false
            }
            copyHoldReleased = false
            return true
        }

        /** When the copy's current turn began (elapsed realtime), 0 outside one, and its pictures then. Worker only. */
        private var copyTurnAt = 0L
        private var copyTurnMade = 0

        /**
         * Behind the screen after its wait for the copy: a copy not confirmed yet that keeps waiting for the title's own
         * 4K decoder gets turns, so its pictures and the check go on instead of the title's own grid taking all the
         * screen's time ([CopyFirst.turnStarts]).
         */
        private fun copyTurnHolds(c: CopyPass): Boolean {
            val now = SystemClock.elapsedRealtime()
            val b = borrow
            val unconfirmed = b == null || (b.copy === c && !b.confirmed && !b.failed)
            if (copyTurnAt > 0L) {
                if (!CopyFirst.turnOver(c.filling, unconfirmed, now - copyTurnAt, c.made - copyTurnMade)) return true
                copyTurnAt = 0L
                return false
            }
            val waited = c.ownDecoderWaitSince.takeIf { it > 0L }?.let { now - it } ?: 0L
            if (!CopyFirst.turnStarts(MemoryGovernor.isStrongBox, c.filling, unconfirmed, waited)) return false
            copyTurnAt = now
            copyTurnMade = c.made
            Log.i(COPY_TAG, "copy first: a turn for the copy (${c.made} thumbnails, not confirmed yet) - " +
                "the title's own grid waits up to ${CopyFirst.TURN_MS / 1000} s")
            return true
        }

        private var copyHoldMade = -1
        private var copyHoldSince = 0L
        private var copyHoldReleased = false
        private var copyLookReleased = false
        /** When the Usenet title's copy last made a thumbnail, or started making them; 0 while it makes none. */
        private var copyProgressAt = 0L
        /** The scrub stop (its last step's time) already read from the Usenet stream. */
        @Volatile private var usenetServedStopAt = 0L

        private fun usenetCopyHolds(c: CopyPass): Boolean {
            if (c.ended) return false
            val now = SystemClock.elapsedRealtime()
            val confirmed = borrow?.let { it.copy === c && it.confirmed } == true
            if (!c.filling && !confirmed) {
                copyProgressAt = 0L
            } else if (copyProgressAt == 0L || c.made != copyHoldMade) {
                copyHoldMade = c.made
                copyProgressAt = now
            }
            val why = UsenetCopy.giveUp(confirmed, now - copyStartedAt, copyProgressAt.takeIf { it > 0L }?.let { now - it })
                ?: return true
            Log.i(COPY_TAG, "Usenet: $why - the Usenet stream makes the thumbnails")
            c.stop(why)
            return false
        }

        /** Worker only: the scrub stop to read from the Usenet stream while its copy is tried, -1 for none. */
        private fun usenetLanding(st: ThumbStore, stopAt: Long): Int {
            val s = prioritySlot
            val b = borrow
            val copyHas = s >= 0 && b != null && b.confirmed && !b.failed &&
                b.map(s).let { m -> m >= 0 && b.store.has(slotUs(m)) }
            return UsenetCopy.landingSlot(s, SystemClock.elapsedRealtime(), stopAt, usenetServedStopAt,
                needed = s >= 0 && wanted(st, s) && !copyHas)
        }

        /** Worker only: a remote media server's batched pass falls back to the spot a seek asks for at the cap or a refusal. */
        private suspend fun tendRemote() {
            if (!remoteBatched || onDemand) return
            val r = reader ?: return
            val why = when {
                r.bytes > MediaServerThumbs.REMOTE_DATA_CAP_BYTES -> "${r.bytes / (1024 * 1024)} MB read, the cap"
                fetchRateLimited || ConnectionLedger.app.refusedLately(r.resolvedUrl) -> "the server refused a request"
                batchReader?.refused == true -> "it does not take many ranges in one request"
                else -> return
            }
            onDemand = true
            latticeFinished = true
            batchReader?.plan(emptyList())
            Log.i(TAG, "media server away from the home network: only the spot a seek asks for from now on ($why)")
            releasePrepareHold()
        }

        /** Worker only: starts the copy pass, sets its pictures up for the check, and copies confirmed ones over. */
        private fun tendCopy() {
            val st = store ?: return
            val idx = index ?: return
            val c = copy
            if (c == null) {
                maybeStartCopy(idx, st)
                return
            }
            if (awayCopyChosenAt > 0L && c.made > 0) {
                val now = SystemClock.elapsedRealtime()
                Log.i(COPY_TAG, "media server away from home: first copy thumbnail ${now - awayCopyChosenAt} ms after " +
                    "the copy was chosen, ${now - awayProbeAt} ms after the source list was asked for")
                awayCopyChosenAt = 0L
            }
            val set = c.ready
            borrow?.let { old ->
                if (old.copy === c && !old.confirmed && set?.store?.key != old.store.key) {
                    Log.i(COPY_TAG, "reuse: the copy pass moved on - its earlier copy is no longer shown")
                    dropBorrow()
                }
            }
            if (set != null && borrow == null && set.store.key != copySetKey) setUpCopyBorrow(idx, st, c, set)
            val b = borrow ?: return
            if (b.copy !== c) return
            val made = c.made
            if (b.confirmed) {
                val every = if (prepareStage in 1..4) COPY_CHECK_REFILL_EVERY else COPY_ADOPT_EVERY
                if (made - copyAdoptedAt >= every || (!c.filling && made != copyAdoptedAt)) {
                    copyAdoptedAt = made
                    adoptBorrow(st, b)
                }
            } else if (!b.failed && (made - copyChecksAt >= COPY_CHECK_REFILL_EVERY || (!c.filling && made != copyChecksAt))) {
                copyChecksAt = made
                refillCopyChecks(idx, st, b)
            }
        }

        /**
         * A 4K or Usenet title with an incomplete store, no other release in use and no server thumbnails: try a copy.
         * A Usenet title starts at once, also behind the generating screen.
         */
        private fun maybeStartCopy(idx: MediaIndex, st: ThumbStore) {
            if (!memoryStop.allowsSideWork) return
            val wanted = if (onDemand) awayCopy else copyAllowed
            if (copyTried || !wanted || !source.identityExact) return
            val sources = source.copySources ?: return
            val screenCopy = usenet || onDemand || copyFirst
            if (afterPlayback || ended || (prepareStage in 1..4 && !screenCopy) || borrow != null || slotKeyframe.isEmpty()) return
            if (copyFirst && !CopyFirst.startsNow(prepareStage in 1..4, PlayerSessionTracker.firstFrameShown)) {
                if (!copyWaitLogged) Log.i(COPY_TAG, "copy first: the copy starts at the film's first frame")
                copyWaitLogged = true
                return
            }
            copyTried = true
            if (st.isMemoryOnly || coverageStored(st)) return
            val v = idx.video
            val picture = ThumbGeometry.outputSize(v.width, v.height, 1, 1, v.displayAspect)
            val pts = idx.keyframes.ptsUs
            val landing = LongArray(slotKeyframe.size) { s -> slotKeyframe[s].let { if (it in pts.indices) pts[it] else -1L } }
            val durationMs = lastDurationMs.takeIf { it > 0 } ?: (idx.durationUs / 1000L)
            val own = CopyOwn(source.identity, durationMs, idx.fileLength, picture[0], picture[1], landing, st,
                slotOf(lastPositionMs.coerceAtLeast(0L)), CopyChoice.heightClass(v.width, v.height), usenet,
                screenStride = if (screenCopy && (!copyFirst || prepareStage in 1..4)) prepareStride() else 1,
                awayServer = onDemand, lighterOnly = copyFirst && !fourK, pickedAtMs = pickedAtMs,
                hdr = CopySourceBook.hdrPicture(v.colour.transfer, v.colour.primaries, v.dolbyVision != null),
                keyframesUs = pts, wideUs = COPY_WIDE_US)
            copyStartedAt = SystemClock.elapsedRealtime()
            copy = CopyPass(
                context = context,
                own = own,
                sources = sources,
                playerProvider = playerProvider,
                lastStallAtMs = { runCatching { reserveProvider()?.lastStallRealtimeMs }.getOrNull() ?: 0L },
                ownDecoderBusy = { decoderLanes.any { it.inUse || it.decoder.warm } },
                pacer = if (MemoryGovernor.isStrongBox) null else copyPacer,
                screenUp = { screenCopy && prepareStage in 1..4 },
                playerClosed = { afterPlayback },
                headStart = headStart?.takeIf { copyFirst && it.identity == source.identity },
            ).also { it.start() }
        }

        private fun setUpCopyBorrow(idx: MediaIndex, st: ThumbStore, c: CopyPass, set: CopySet) {
            copySetKey = set.store.key
            copyAdoptedAt = 0
            copyChecksAt = c.made
            if (st.verdictOn(set.store.key) == false || releasePair(st.key, set.store.key) in differentReleases) {
                c.rejected(set.store.key, "its pictures did not match this title before")
                return
            }
            val v = idx.video
            val own = ThumbGeometry.outputSize(v.width, v.height, 1, 1, v.displayAspect)
            val size = set.store.slots().minOrNull()?.let(set.store::compressed)?.let(::pictureSize)
            if (size == null || size[0] != own[0] || size[1] != own[1]) {
                c.rejected(set.store.key, if (size == null) "no picture to measure"
                    else "pictures ${size[0]}x${size[1]} against ${own[0]}x${own[1]}")
                return
            }
            ThumbStore.markBusy(set.store.key)
            val b = Borrow(set.store, set.timing, set.slotCount, copy = c, wideUs = COPY_WIDE_US)
            checkQueue.clear()
            checkGap.clear()
            checkMatches = 0
            checkBorderline.clear()
            checkedSlots.clear()
            borrow = b
            borrowSince = SystemClock.elapsedRealtime()
            bumpTick()
            if (st.verdictOn(set.store.key) == true) {
                Log.i(COPY_TAG, "reuse: ${set.label} was confirmed for this file before")
                copyAdoptedAt = c.made
                adoptBorrow(st, b)
                return
            }
            Log.i(COPY_TAG, "reuse: showing ${set.store.slots().size} thumbnails of ${set.label} until " +
                "${ReleaseMatch.CHECKS_NEEDED} pictures of this file confirm them, " +
                "${SystemClock.elapsedRealtime() - pickedAtMs} ms after the stream pick")
            refillCopyChecks(idx, st, b)
        }

        /**
         * Own pictures already stored are compared with the copy's at once; then own slots still to make whose copy
         * picture exists are queued for the check, up to the number still needed plus spares.
         */
        private fun refillCopyChecks(idx: MediaIndex, st: ThumbStore, b: Borrow) {
            val stored = b.store.slots()
            val ownStored = slotKeyframe.indices.mapNotNull { s ->
                if (s in checkedSlots || !st.has(slotUs(s))) return@mapNotNull null
                keyframeOffset(idx, b, s, stored)?.takeIf { kotlin.math.abs(it) <= ReleaseMatch.WIDE_US }?.let { s to it }
            }
            for (s in ReleaseMatch.pickCheckSlots(ownStored, slotKeyframe.size)) {
                if (borrow !== b || b.confirmed) return
                val gap = kotlin.math.abs(ownStored.first { it.first == s }.second)
                if (b.failed && gap > ReleaseMatch.SAME_FRAME_US) continue
                val bmp = st.load(slotUs(s)) ?: continue
                checkGap[s] = gap
                checkBorrow(st, s, bmp)
            }
            if (borrow !== b || b.confirmed) return
            val want = ReleaseMatch.CHECKS_NEEDED + ReleaseMatch.CHECK_SPARES - checkMatches + checkBorderline.size
            if (checkQueue.size >= want) return
            val eligible = checkEligible(idx, st, b).filter { it.first !in checkQueue && it.first !in checkedSlots }
            for (s in ReleaseMatch.pickCheckSlots(eligible, slotKeyframe.size)) {
                if (checkQueue.size >= want) break
                checkQueue += s
                checkGap[s] = kotlin.math.abs(eligible.first { it.first == s }.second)
            }
        }

        /** Own to borrowed keyframe gap (us) at [slot], on the own timeline; null without both keyframes. */
        private fun keyframeGap(idx: MediaIndex, b: Borrow, slot: Int, stored: Set<Long>): Long? =
            keyframeOffset(idx, b, slot, stored)?.let { kotlin.math.abs(it) }

        /** Own keyframe time minus the borrowed one's (us) at [slot], on the own timeline; null without both keyframes. */
        private fun keyframeOffset(idx: MediaIndex, b: Borrow, slot: Int, stored: Set<Long>): Long? {
            val pts = idx.keyframes.ptsUs
            val kf = slotKeyframe.getOrElse(slot) { -1 }
            val m = b.map(slot)
            if (kf !in pts.indices || m < 0 || slotUs(m) !in stored) return null
            val otherPts = b.store.keyframePtsFor(slotUs(m)) ?: return null
            return pts[kf] - b.timing.toOwnUs(otherPts)
        }

        /**
         * Own slots still to make, with the own keyframe's offset from the borrowed one of the same slot, within
         * [ReleaseMatch.WIDE_US].
         */
        private fun checkEligible(idx: MediaIndex, st: ThumbStore, b: Borrow): List<Pair<Int, Long>> {
            val stored = b.store.slots()
            return slotKeyframe.indices.mapNotNull { s ->
                if (!needs(st, s)) return@mapNotNull null
                keyframeOffset(idx, b, s, stored)?.takeIf { kotlin.math.abs(it) <= ReleaseMatch.WIDE_US }?.let { s to it }
            }
        }

        /** The next check slot to make, at most as many under way as matches are still missing. Worker only. */
        private fun nextCheckSlot(st: ThumbStore): Int {
            val b = borrow ?: return -1
            if (b.confirmed) return -1
            // Check slots are fetched like coverage slots: once that budget is spent the check cannot finish.
            if (latticeBudgetSpent()) {
                Log.i(TAG, "reuse: fetch budget spent before the check finished - another release's thumbnails dropped")
                dropBorrow()
                return -1
            }
            checkQueue.removeAll { !needs(st, it) }
            var pool = if (b.failed) checkQueue.filter { sameFrame(it) } else checkQueue.toList()
            if (pool.isEmpty() && b.copy != null) {
                index?.let { refillCopyChecks(it, st, b) }
                if (borrow !== b || b.confirmed) return -1
                checkQueue.removeAll { !needs(st, it) }
                pool = if (b.failed) checkQueue.filter { sameFrame(it) } else checkQueue.toList()
                // The copy is still being made: more pairs to check come with its pictures.
                if (pool.isEmpty() && b.copy.filling) return -1
            }
            if (pool.isEmpty()) {
                notThisBorrow(b, if (b.failed) "a mismatch not seen twice" else "too few clear pictures")
                return -1
            }
            val window = if (b.failed) 1 else (ReleaseMatch.CHECKS_NEEDED - checkMatches + checkBorderline.size).coerceAtLeast(1)
            return pool.take(window).firstOrNull { wanted(st, it) } ?: -1
        }

        private fun sameFrame(slot: Int): Boolean = (checkGap[slot] ?: Long.MAX_VALUE) <= ReleaseMatch.SAME_FRAME_US

        /** Own picture of check [slot] against the borrowed one and its neighbours. Worker only. */
        private fun checkBorrow(st: ThumbStore, slot: Int, bmp: Bitmap) {
            val b = borrow ?: return
            checkQueue.remove(slot)
            checkedSlots += slot
            val gap = checkGap[slot] ?: Long.MAX_VALUE
            val w = bmp.width
            val h = bmp.height
            val m = b.map(slot)
            val otherSize = b.store.compressed(slotUs(m))?.let(::pictureSize)
            val fit = b.fit?.takeIf { it.toW == w && it.toH == h }
            if (b.fit != null && fit == null) {
                rejectBorrow(st, b, "pictures fitted to ${b.fit.toW}x${b.fit.toH} against ${w}x$h")
                return
            }
            val expectW = fit?.fromW ?: w
            val expectH = fit?.fromH ?: h
            if (otherSize != null && (otherSize[0] != expectW || otherSize[1] != expectH)) {
                rejectBorrow(st, b, "pictures ${otherSize[0]}x${otherSize[1]} against ${expectW}x$expectH")
                return
            }
            val px = IntArray(w * h)
            if (runCatching { bmp.getPixels(px, 0, w, 0, 0, w, h) }.isFailure) return
            val bars = ThumbCrop.measure(w, h, px)
            val own = ReleaseMatch.signature(px, w, h, bars?.left ?: 0, bars?.top ?: 0, bars?.right ?: 0, bars?.bottom ?: 0)
            fun scaled(v: Int?, from: Int, to: Int): Int = ((v ?: 0).toLong() * to / from).toInt()
            fun other(at: Int): Float? {
                if (own == null || at !in 0 until b.slotCount) return null
                val bytes = b.store.compressed(slotUs(at)) ?: return null
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = if (fit != null) 1 else 2
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
                val o = fit?.let { f -> fitTo(raw, f).also { raw.recycle() } } ?: raw
                try {
                    val ow = o.width
                    val oh = o.height
                    val opx = IntArray(ow * oh)
                    o.getPixels(opx, 0, ow, 0, 0, ow, oh)
                    val sig = ReleaseMatch.signature(opx, ow, oh, scaled(bars?.left, w, ow), scaled(bars?.top, h, oh),
                        scaled(bars?.right, w, ow), scaled(bars?.bottom, h, oh)) ?: return null
                    return ReleaseMatch.correlation(own, sig)
                } finally {
                    o.recycle()
                }
            }
            val same = other(m)
            val before = other(m - 1)
            val after = other(m + 1)
            val outcome = ReleaseMatch.outcome(same, before, after, gap)
            Log.i(if (b.copy != null) COPY_TAG else TAG, ReleaseMatch.checkLine(slot * SPACING_MS / 1000, gap, same, before,
                after, outcome))
            when {
                outcome == ReleaseMatch.Outcome.SKIP -> Unit
                b.failed -> when (outcome) {
                    ReleaseMatch.Outcome.MISMATCH -> rejectBorrow(st, b, "two pictures of the same frame differ")
                    ReleaseMatch.Outcome.PASS -> {
                        checkMatches++
                        b.matchesSinceMiss++
                        if (b.matchesSinceMiss >= ReleaseMatch.MATCHES_AFTER_MISMATCH &&
                            ReleaseMatch.tally(checkMatches, checkBorderline) == ReleaseMatch.Tally.MATCHED) {
                            Log.i(TAG, "reuse: $checkMatches matches, one odd picture - confirmed")
                            b.failed = false
                            adoptBorrow(st, b)
                        }
                    }
                    else -> notThisBorrow(b, "a mismatch, then a borderline picture")
                }
                outcome == ReleaseMatch.Outcome.PASS || outcome == ReleaseMatch.Outcome.FAIL -> {
                    if (outcome == ReleaseMatch.Outcome.PASS) checkMatches++ else checkBorderline += same ?: 0f
                    when (ReleaseMatch.tally(checkMatches, checkBorderline)) {
                        ReleaseMatch.Tally.MATCHED -> adoptBorrow(st, b)
                        ReleaseMatch.Tally.NOT_MATCHED -> notThisBorrow(b, "${checkBorderline.size} borderline pictures " +
                            "(average %.2f) against $checkMatches matching".format(checkBorderline.average()))
                        ReleaseMatch.Tally.GOING -> if (outcome == ReleaseMatch.Outcome.FAIL) {
                            Log.i(TAG, "reuse: a borderline picture - more pictures decide")
                        }
                    }
                }
                else -> {
                    b.failed = true
                    bumpTick()
                    Log.i(TAG, "reuse: a picture of the same frame differs - hidden, " +
                        "${ReleaseMatch.MATCHES_AFTER_MISMATCH} more decide")
                }
            }
        }

        /**
         * Copies the borrowed WebP bytes into every slot this store lacks whose keyframe is within
         * [ReleaseMatch.WIDE_US] of the borrowed one; the borrow stays for the other slots. Worker only.
         */
        private fun adoptBorrow(st: ThumbStore, b: Borrow) {
            b.confirmed = true
            st.noteVerdict(b.store.key, true)
            b.copy?.confirmed(b.store.key)
            bumpTick()
            val idx = index ?: return
            if (st.isMemoryOnly) {
                Log.i(TAG, "reuse: confirmed, storage nearly full - shown, not copied")
                return
            }
            val stored = b.store.slots()
            // Slots served by one borrowed picture share one copy.
            val groups = LinkedHashMap<Long, Pair<Long, MutableList<Long>>>()
            var far = 0
            for (s in slotKeyframe.indices) {
                if (st.has(slotUs(s))) continue
                val gap = keyframeGap(idx, b, s, stored) ?: continue
                if (gap > b.wideUs) {
                    far++
                    continue
                }
                val m = b.map(s)
                val otherPts = b.store.keyframePtsFor(slotUs(m)) ?: continue
                groups.getOrPut(otherPts) { slotUs(m) to ArrayList() }.second += slotUs(s)
            }
            var copied = 0
            val fit = b.fit
            if (fit != null) {
                // Pictures of another shape are fitted to ours and stored as our own, one by one.
                for ((otherPts, v) in groups) {
                    val bytes = b.store.compressed(v.first) ?: continue
                    val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
                    val pic = fitTo(raw, fit)
                    raw.recycle()
                    st.put(v.second.toLongArray(), b.timing.toOwnUs(otherPts), pic)
                    copied += v.second.size
                }
                groups.clear()
            }
            for (chunk in groups.entries.chunked(ADOPT_CHUNK)) {
                val items = chunk.mapNotNull { (otherPts, v) ->
                    val bytes = b.store.compressed(v.first) ?: return@mapNotNull null
                    ThumbStore.Compressed(v.second.toLongArray(), b.timing.toOwnUs(otherPts), bytes)
                }
                if (!st.putCompressed(items, indexNow = false)) break
                copied += items.sumOf { it.slotsUs.size }
            }
            st.flush()
            Log.i(if (b.copy != null) COPY_TAG else TAG, "reuse: confirmed - $copied slots copied from " +
                (if (b.copy != null) "the smaller copy" else "another release") + ", $far left for our own " +
                "(keyframes over ${b.wideUs / 1000} ms apart), stored=${st.slots().size}")
        }

        private fun rejectBorrow(st: ThumbStore, b: Borrow, why: String) {
            st.noteVerdict(b.store.key, false)
            differentReleases += releasePair(st.key, b.store.key)
            Log.i(TAG, "reuse: another release's thumbnails do not match ($why) - not used for this file")
            b.copy?.rejected(b.store.key, why)
            dropBorrow()
        }

        /** The check did not confirm [b], nor prove it another film: a copy pass moves on to its next copy. */
        private fun notThisBorrow(b: Borrow, why: String) {
            if (b.copy != null) {
                Log.i(COPY_TAG, "reuse: not confirmed ($why) - the next copy is tried")
                b.copy.rejected(b.store.key, why)
            } else {
                Log.i(TAG, "reuse: not confirmed ($why) - another release's thumbnails dropped for this session")
            }
            dropBorrow()
        }

        private fun dropBorrow() {
            val b = borrow ?: return
            borrow = null
            checkQueue.clear()
            checkGap.clear()
            checkMatches = 0
            checkBorderline.clear()
            checkedSlots.clear()
            if (!b.confirmed) b.copy?.undecided(b.store.key, "see the reuse lines above")
            ThumbStore.unmarkBusy(b.store.key)
            b.store.trimMemory(0)
            bumpTick()
        }

        /**
         * The borrowed picture nearest own [target] slot's mapped one, at most [within] slots away (exclusive), never
         * exact. Its time is the borrowed keyframe's on the own timeline, or the slot's own when it is the mapped one.
         */
        private fun borrowedFor(target: Int, within: Int): Shown? {
            val b = borrow ?: return null
            if (b.failed) return null
            val m = b.map(target)
            if (m < 0) return null
            val other = b.store
            var slot = -1
            for (d in 0..minOf(fallbackSlots, within - 1)) {
                if (m - d >= 0 && other.has(slotUs(m - d))) {
                    slot = m - d
                    break
                }
                if (m + d < b.slotCount && other.has(slotUs(m + d))) {
                    slot = m + d
                    break
                }
            }
            if (slot < 0) return null
            val key = slotUs(slot)
            val raw = other.fullColourGet(key) ?: other.memGet(key)?.also { requestFullColour(other, slot) }
            if (raw == null) {
                requestDiskLoad(other, slot)
                return null
            }
            val bmp = b.fit?.let { f ->
                b.fitted.get(slot)?.takeIf { it.first === raw }?.second
                    ?: fitTo(raw, f).also { b.fitted.put(slot, raw to it) }
            } ?: raw
            val ptsMs = if (slot == m) slotPtsMs(target)
            else other.keyframePtsFor(key)?.let { b.timing.toOwnUs(it) / 1000L } ?: slotPtsMs(target)
            return Shown(bmp, ptsMs, exact = false, crop = crop.current, borrowed = true)
        }

        private fun pictureSize(webp: ByteArray): IntArray? {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(webp, 0, webp.size, o)
            return if (o.outWidth > 0 && o.outHeight > 0) intArrayOf(o.outWidth, o.outHeight) else null
        }

        @Volatile private var fullColourLoadSlot = -1

        /** The centre picture in full colour when memory frames are RGB565: one disk decode at a time. */
        private fun requestFullColour(st: ThumbStore, slot: Int) {
            if (fullColourLoadSlot >= 0) return
            fullColourLoadSlot = slot
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    if (st.loadFullColour(slot.toLong() * SPACING_MS * 1000L) != null) bumpTick()
                } finally {
                    fullColourLoadSlot = -1
                }
            }
        }

        private fun requestDiskLoad(st: ThumbStore, slot: Int) {
            if (synchronized(diskLoadsInFlight) { diskLoadsInFlight.size >= MAX_DISK_LOADS_IN_FLIGHT }) return
            val key = slot.toLong() * SPACING_MS * 1000L
            val token = st.key to slot
            if (!st.has(key) || !synchronized(diskLoadsInFlight) { diskLoadsInFlight.add(token) }) return
            CoroutineScope(Dispatchers.IO).launch {
                val bmp = st.load(key)
                synchronized(diskLoadsInFlight) { diskLoadsInFlight.remove(token) }
                if (bmp != null) {
                    if (st === store) measureBars(bmp)
                    bumpTick()
                }
            }
        }

        private fun onTrim() {
            buffers.releaseIdle()
            store?.trimMemory(4)
            borrow?.store?.trimMemory(0)
            trimRequested = true     // done in tendReserve, never during a decode
            laneTrimRequested = true
        }

        private suspend fun sample(): PlayerState? {
            if (afterPlayback) {
                // The player screen is gone. If a player is still playing anyway, stop: memory is assumed free here.
                val live = samplePlayer()
                if (live != null && (live.isPlaying || live.buffering)) {
                    Log.i(TAG, "4K-C: a player is still active - ending the after-playback phase")
                    throw CancellationException("player still active")
                }
                // Otherwise behave as paused (the gates then only check memory and rate limits).
                ConnectionLedger.app.notePlaybackState(healthy = true, quiet = true)
                return PlayerState(false, false, lastPositionMs, lastPositionMs, lastDurationMs)
            }
            return samplePlayer()?.also(::trackJump)?.also(::noteForLedger)
        }

        /** Worker thread: lets the connection ledger step up only while playback can spare it, and down on trouble. */
        private fun noteForLedger(ps: PlayerState) {
            val ledger = ConnectionLedger.app
            val toEnd = ps.durationMs > 0 && ps.bufferedMs >= ps.durationMs - 1_000L
            ledger.notePlaybackState(
                healthy = ConnectionLedger.healthy(ps.isPlaying, ps.buffering, ps.bufferedMs - ps.positionMs, toEnd),
                quiet = !ps.isPlaying && !ps.buffering,
            )
            headroom.source = reader?.resolvedUrl ?: source.url
            val gaveWay = headroom.gaveWay
            if (headroom.note(SystemClock.elapsedRealtime(), ps.isPlaying, ps.buffering, ps.loading,
                    ps.bufferedMs - ps.positionMs, toEnd)) {
                fetchLimit()
                if (headroom.gaveWay != gaveWay && headroom.surplus) giveWay()
            }
            val playing = reader?.resolvedUrl ?: source.url
            if (ps.failed) ledger.playbackTrouble(SystemClock.elapsedRealtime(), playing)
            val stallAt = runCatching { reserveProvider()?.lastStallRealtimeMs }.getOrNull() ?: 0L
            if (stallAt > ledgerStallSeen) {
                ledgerStallSeen = stallAt
                ledger.playbackTrouble(stallAt, playing)
            }
        }

        private var ledgerStallSeen = 0L
        /** Fetches started before this were cancelled to give way to playback, not failed. Worker only. */
        private var gaveWayAtMs = Long.MIN_VALUE

        /** Worker thread: playback took lanes back, so the reads under way stop now and are fetched again later. */
        private fun giveWay() {
            val running = fetches.values.count { !it.isCompleted }
            if (running == 0) return
            gaveWayAtMs = SystemClock.elapsedRealtime()
            reader?.cancel()
            Log.i(TAG, "gave way: $running thumbnail reads cancelled (${headroom.why})")
        }

        /** Worker thread: while fetches run during playback, playback is looked at every second so lanes give way at once. */
        private suspend fun watchWhileFetching() {
            while (true) {
                delay(1_000L)
                if (ended || afterPlayback || !lastSampledPlaying || phase() != FetchPhase.PLAYBACK) continue
                if (fetches.values.any { !it.isCompleted }) sample()
            }
        }

        /** Worker thread: notes a playback jump of more than [REANCHOR_JUMP_MS] for the coverage pass. */
        private fun trackJump(ps: PlayerState) {
            val now = SystemClock.elapsedRealtime()
            if (trackPosMs >= 0L) {
                val expected = trackPosMs + (if (trackPlaying && ps.isPlaying) now - trackAtMs else 0L)
                if (kotlin.math.abs(ps.positionMs - expected) > REANCHOR_JUMP_MS) reanchorToMs = ps.positionMs
            }
            trackPosMs = ps.positionMs
            trackAtMs = now
            trackPlaying = ps.isPlaying
            sampledPlaying = ps.isPlaying
            sampledBuffering = ps.buffering
        }

        fun prepareActive(): Boolean = prepareStage in 1..4

        fun prepareStageNow(): Int = prepareStage

        private val rateKey: String
            get() = PrepareRateBook.key(fourK, remux, hwName != null || (hwSwitch && hwOff == null))

        /** The speed this box had last time for this kind of title, until the screen has measured its own. */
        private val rememberedPerThumb: Double?
            get() = PrepareRateBook.get(context, rateKey)

        private fun rememberSpeed(made: Int, now: Long) {
            val n = made - prepareMadeAtStart
            if (prepareMadeAtStart < 0 || prepareStartedAt <= 0L || n < PrepareRateBook.MIN_MADE) return
            PrepareRateBook.note(context, rateKey, (now - prepareStartedAt) / 1000.0 / n)
        }

        /** Resumes from a Main timer, so a worker stuck in a gate wait cannot leave the ready screen up. */
        private fun enterReady(from: Int, made: Int, now: Long): Boolean {
            if (!advancePrepare(from, 4)) return false
            prepareReadyAt = now
            showPrepare(PrepareUi.Ready(made))
            mainScope.launch {
                delay(PREPARE_READY_MS)
                if (advancePrepare(4, 5)) {
                    if (session == null || session === this@Session) prepareUi.value = PrepareUi.Hidden
                    resumePlayerAfterPrepare()
                }
            }
            return true
        }

        val identity: String get() = source.identity

        fun small4KBusy(): Boolean = fourK && !MemoryGovernor.isStrongBox && !ended && !latticeFinished

        /** Worker only. */
        private fun showPrepare(ui: PrepareUi) {
            if (ui == lastPrepareUi) return
            lastPrepareUi = ui
            postPrepareUi(this, ui)
        }

        /** Atomic stage step; false when Main changed it meanwhile. */
        private fun advancePrepare(from: Int, to: Int): Boolean = synchronized(prepareLock) {
            if (prepareStage != from) false else {
                prepareStage = to
                true
            }
        }

        /** Main thread: playback is running although we hold it, so something else resumed it. */
        private fun prepareStepAside() {
            val was = synchronized(prepareLock) { prepareStage.also { if (it in 1..4) prepareStage = 5 } }
            if (was !in 1..4) return
            closeHardwareLanes()
            endYield()
            preparePausedPlayer = false
            playerLetGo = false
            prepareUi.value = PrepareUi.Hidden
            Log.i(TAG, "prepare: playback resumed elsewhere - stepping aside (stage $was)")
        }

        /**
         * Worker only. Small boxes, 4K: the held film's decoders and buffer take the memory a decode needs, so the
         * player lets go of them until the screen ends, as it does once a film is closed.
         */
        private suspend fun letPlayerGo() {
            val early = playerLetGo && !letGoNoted
            val gone = early || withContext(Dispatchers.Main) {
                if (prepareStage !in 1..4 || !preparePausedPlayer || playerLetGo || !hostResumed) return@withContext false
                runCatching { letGoForPrepare() }.getOrDefault(false).also { if (it) noteLetGo() }
            }
            if (!gone || letGoNoted) return
            letGoNoted = true
            dropReserve("player let go")
            naturalFunding()
            Log.i(TAG, "prepare: player let go for the ${if (fourK) "4K" else "hardware"} thumbnails - the film loads when the screen ends" +
                when {
                    early && openDeferred -> " (not opened yet)"
                    early -> " (let go as the screen came up)"
                    else -> ""
                })
            val settled = if (early) SystemClock.elapsedRealtime() - letGoAt else 0L
            if (settled < LET_GO_SETTLE_MS) delay(LET_GO_SETTLE_MS - settled)
            if (appMemory4K) Log.i(DECODE_TAG, "4K hardware: MemAvailable $letGoAvailMb MB at the let-go, " +
                "${MemoryGovernor.readMeminfo()?.availableMb ?: -1L} MB ${(SystemClock.elapsedRealtime() - letGoAt) / 1000.0} s later")
        }

        @Volatile private var letGoNoted = false
        @Volatile private var letGoAt = 0L
        @Volatile private var letGoAvailMb = -1L

        /** Main thread. */
        private fun noteLetGo() {
            playerLetGo = true
            letGoAt = SystemClock.elapsedRealtime()
            if (appMemory4K) letGoAvailMb = MemoryGovernor.readMeminfo()?.availableMb ?: -1L
        }

        /**
         * Hardware thumbnails need the decoder the held player keeps: let it go as the screen comes up, so its release
         * overlaps the index read. Only when the player's own format says the hardware path will take this title.
         */
        private suspend fun letGoEarlyForHardware() {
            if (!hwSwitch) return
            withContext(Dispatchers.Main) {
                val fmt = playerProvider()?.videoFormat ?: return@withContext
                if (!HardwareRoute.likelyFor(Build.VERSION.SDK_INT, fmt.sampleMimeType, fmt.codecs)) return@withContext
                if (prepareStage !in 1..4 || !preparePausedPlayer || playerLetGo || !hostResumed) return@withContext
                if (runCatching { letGoForPrepare() }.getOrDefault(false)) noteLetGo()
            }
        }

        @Volatile private var holdRetrying = false

        /**
         * The player can be rebuilt just as the session starts (a sound output that came back, a recovery): the session
         * carries on with the new one instead of ending under the screen.
         */
        private suspend fun awaitPlayerBack(): PlayerState? {
            val start = SystemClock.elapsedRealtime()
            while (!stoppedExternally && SystemClock.elapsedRealtime() - start < PrepareHandover.PLAYER_BACK_WAIT_MS) {
                delay(PrepareHandover.PLAYER_BACK_POLL_MS)
                val ps = sample() ?: continue
                Log.i(TAG, "prepare: the player was rebuilt as the thumbnails started - carrying on with the new one " +
                    "after ${SystemClock.elapsedRealtime() - start} ms")
                return ps
            }
            Log.i(TAG, "prepare: no player ${PrepareHandover.PLAYER_BACK_WAIT_MS / 1000} s after the thumbnails started - ending")
            return null
        }

        /** Main thread. A session that ends before its screen came up leaves no early screen behind. */
        private fun dropEarlyScreenAtEnd() {
            if (session != null || pendingIdentity != null) return
            if (earlyScreenFor == source.identity) earlyScreenFor = null
            if (prepareUi.value is PrepareUi.Checking) {
                prepareUi.value = PrepareUi.Hidden
                Log.i(TAG, "prepare: thumbnails session ended before its screen - screen taken down")
            }
        }

        /** Main thread. */
        private fun takeDownEarlyScreen() {
            if (!earlyScreen || prepareStage in 1..4) return
            if (earlyScreenFor == source.identity) earlyScreenFor = null
            if (prepareUi.value is PrepareUi.Checking && (session == null || session === this)) prepareUi.value = PrepareUi.Hidden
        }

        /** Stage 1 and the pause in one main-thread step, so the monitor never sees stage 1 while still playing. */
        private suspend fun beginPrepareOnMain(): Boolean = withContext(Dispatchers.Main) {
            val p = playerProvider() ?: return@withContext false
            if (!hostResumed) return@withContext false
            synchronized(prepareLock) { if (prepareStage == 0) prepareStage = 1 else return@withContext false }
            // false = the user already paused: leave it alone and never resume it. null = it cannot be held now.
            val held = runCatching { pauseForPrepare() }.getOrDefault(false)
            if (held == null) {
                // Dolby Vision and tunnelled starts can only be held once their first frame is up.
                synchronized(prepareLock) { if (prepareStage == 1) prepareStage = 0 }
                Log.i(TAG, "prepare: playback cannot be held yet - asking again after the first frame")
                holdRetrying = true
                retryHoldAfterFirstFrame(SystemClock.elapsedRealtime(), p.currentPosition)
                return@withContext false
            }
            preparePausedPlayer = held
            if (held && openDeferred) {
                noteLetGo()
                letGoAt = 0L
            }
            watchPrepareScreen()
            startYield()
            // A "playing" sample from before the hold must not read as "the user pressed play" in the next round.
            sampledPlaying = false
            Log.i(TAG, if (held) "prepare: playback held at ${p.currentPosition / 1000} s (before first frame: ${!p.isPlaying})"
                else "prepare: player paused by the user - not holding it")
            true
        }

        /** Main thread. Each refused ask has no side effect, so this is one real ask once the player allows it. */
        private fun retryHoldAfterFirstFrame(refusedAt: Long, refusedAtPosMs: Long) {
            mainScope.launch {
                while (true) {
                    delay(PrepareHandover.HOLD_RETRY_POLL_MS)
                    val p = playerProvider() ?: break
                    val waited = SystemClock.elapsedRealtime() - refusedAt
                    if (stoppedExternally || !hostResumed ||
                        !PrepareHandover.retriesHold(waited, p.currentPosition - refusedAtPosMs)) break
                    val claimed = synchronized(prepareLock) {
                        (!ended && prepareStage == 0).also { if (it) prepareStage = 1 }
                    }
                    if (!claimed) return@launch
                    when (runCatching { pauseForPrepare() }.getOrDefault(false)) {
                        null -> synchronized(prepareLock) { if (prepareStage == 1) prepareStage = 0 }
                        false -> {
                            synchronized(prepareLock) { if (prepareStage == 1) prepareStage = 5 }
                            giveUp.beforePlay = "skipped (paused by the user)"
                            Log.i(TAG, "prepare: paused by the user before the first frame - not holding")
                            takeDownEarlyScreen()
                            return@launch
                        }
                        true -> {
                            preparePausedPlayer = true
                            watchPrepareScreen()
                            startYield()
                            sampledPlaying = false
                            giveUp.beforePlay = "shown after the first frame (${beforePlayName()})"
                            postPrepareUi(this@Session, PrepareUi.Checking)
                            Log.i(TAG, "prepare: playback held after the first frame (${waited} ms after the first ask)")
                            return@launch
                        }
                    }
                }
                if (synchronized(prepareLock) { (prepareStage == 0).also { if (it) prepareStage = 5 } }) {
                    Log.i(TAG, "prepare: playback cannot be held at this start - not holding")
                    takeDownEarlyScreen()
                }
            }
        }

        @Volatile private var yieldJob: Job? = null

        /** Main thread. While the generating screen is up, playback keeps its start buffer and leaves us the connections. */
        private fun startYield() {
            if (yieldJob?.isActive == true) return
            yieldJob = mainScope.launch {
                try {
                    while (prepareStage in 1..4 && !ended && !stoppedExternally) {
                        runCatching { reserveProvider()?.yieldLoading(true) }
                        ConnectionLedger.app.noteYield(true)
                        delay(YIELD_RENEW_MS)
                    }
                } finally {
                    runCatching { reserveProvider()?.yieldLoading(false) }
                    ConnectionLedger.app.noteYield(false)
                }
            }
        }

        /** Playback loads normally again at once. Any thread. */
        private fun endYield() {
            yieldJob?.cancel()
            runCatching { reserveProvider()?.yieldLoading(false) }
            ConnectionLedger.app.noteYield(false)
        }

        /** The app was left while the generating screen was up: on return playback waits for the user. */
        @Volatile private var prepareLeft = false

        fun notePrepareLeft() {
            if (prepareStage in 1..4) prepareLeft = true
        }

        /** Main thread. [userAsked]: the user chose to start, so a left-and-returned screen still plays. */
        private fun resumePlayerAfterPrepare(userAsked: Boolean = false) {
            // The player gets the video decoders and their memory back before it loads.
            closeHardwareLanes()
            endYield()
            if (!preparePausedPlayer) return
            preparePausedPlayer = false
            if (playerLetGo) {
                playerLetGo = false
                val play = hostResumed && (!prepareLeft || userAsked)
                if (appMemoryHwUsed) {
                    // The film's buffer is sized as it loads: the thumbnail decoder's memory must be back first.
                    mainScope.launch {
                        withContext(Dispatchers.IO) { awaitAppMemoryDecoderGone() }
                        loadAgain(play)
                    }
                    return
                }
                loadAgain(play)
                return
            }
            if (!hostResumed) {
                Log.i(TAG, "prepare: done while in the background - leaving playback paused")
                return
            }
            if (prepareLeft && !userAsked) {
                Log.i(TAG, "prepare: done after the app was left - paused until play")
                return
            }
            playerProvider()?.takeIf { !it.playWhenReady }?.let {
                it.play()
                Log.i(TAG, "prepare: playback resumed")
            }
        }

        /** Main thread. */
        private fun loadAgain(play: Boolean) {
            runCatching { loadAgainAfterPrepare(play) }
            Log.i(TAG, "prepare: film loading again at ${(playerProvider()?.currentPosition ?: 0L) / 1000} s" +
                if (play) "" else ", paused until play")
        }

        /** Until every hardware decoder is closed and its memory is back, within [AppMemoryHardware]'s waits. */
        private suspend fun awaitAppMemoryDecoderGone() {
            val start = SystemClock.elapsedRealtime()
            var closedAt = -1L
            var avail: Long
            while (true) {
                val now = SystemClock.elapsedRealtime()
                // A decode the worker began meanwhile closes as it ends.
                closeHardwareLanes()
                if (closedAt < 0 && decoderLanes.none { it.hardwareOpen }) closedAt = now
                avail = MemoryGovernor.readMeminfo()?.availableMb ?: -1L
                val closedFor = if (closedAt < 0) -1L else now - closedAt
                if (AppMemoryHardware.filmMayLoad(now - start, closedFor, avail, hwFirstOpenAvailMb)) break
                delay(AppMemoryHardware.POLL_MS)
            }
            Log.i(TAG, "prepare: 4K hardware decoder " + (if (closedAt < 0) "still open" else "closed") +
                " after ${SystemClock.elapsedRealtime() - start} ms, MemAvailable $avail MB " +
                "($hwFirstOpenAvailMb MB before it opened)")
        }

        fun startWatchingNow() {
            synchronized(prepareLock) { prepareStage = 5 }
            prepareUi.value = PrepareUi.Hidden
            resumePlayerAfterPrepare(userAsked = true)
            Log.i(TAG, "prepare: start_now - playback resumes")
        }

        private suspend fun tendPrepare(total: Int, done: Int) {
            if (inBackground()) return
            val now = SystemClock.elapsedRealtime()
            if (sampledPlaying && prepareStage in 1..4) {
                // A sample taken just before a hold that came after the first frame is stale: ask the player itself.
                val stepped = withContext(Dispatchers.Main) {
                    if (playerProvider()?.playWhenReady == false) false else true.also { prepareStepAside() }
                }
                if (stepped) return
                sampledPlaying = false
            }
            val made = latticeMade
            val perThumb = if (prepareMadeAtStart in 0 until made - 2 && prepareStartedAt > 0L) {
                (now - prepareStartedAt) / 1000.0 / (made - prepareMadeAtStart)
            } else rememberedPerThumb ?: when {
                !fourK -> PREPARE_SECONDS_HD
                MemoryGovernor.isStrongBox -> PREPARE_SECONDS_STRONG_4K
                remux -> PREPARE_SECONDS_REMUX
                else -> PREPARE_SECONDS_WEB
            }
            val (recent, endSeq) = synchronized(recentMade) { recentMade.toList() to madeSeq }
            val left = (maxOf(latticeWorkLeft, total - done, 0) * perThumb).toInt()
            val shownTotal = maxOf(screenTotal, total)
            val shownDone = if (done >= total) shownTotal else minOf(maxOf(screenDone, done), shownTotal)
            when (prepareStage) {
                1 -> when {
                    done >= total -> if (advancePrepare(1, 5)) {
                        showPrepare(PrepareUi.Hidden)
                        withContext(Dispatchers.Main) { resumePlayerAfterPrepare() }
                        Log.i(TAG, "prepare: thumbnails already complete ($done/$total) - playing")
                    }
                    // A media server away from home waits for its copy; without one it plays with seek spots only.
                    onDemand && borrow?.confirmed != true -> {
                        if (awayCheckingSince == 0L) awayCheckingSince = now
                        val c = copy
                        val over = c?.ended ?: copyTried
                        val since = if (c != null) copyStartedAt else awayCheckingSince
                        val empty = (c?.made ?: 0) == 0 && now - awayCheckingSince >= AWAY_EMPTY_SCREEN_MS
                        if ((over || empty || now - since >= USENET_COPY_CHECK_MS) && advancePrepare(1, 5)) {
                            showPrepare(PrepareUi.Hidden)
                            withContext(Dispatchers.Main) { resumePlayerAfterPrepare() }
                            Log.i(TAG, "prepare: " + when {
                                over -> "no copy could be used"
                                empty -> "no copy picture ${AWAY_EMPTY_SCREEN_MS / 1000} s after the screen came up"
                                else -> "no copy confirmed in ${USENET_COPY_CHECK_MS / 1000} s"
                            } + " for the media server away from home - playing" +
                                if (!over) ", the copy carries on" else "")
                        }
                    }
                    // A Usenet title waits for its copy to be confirmed; the stream itself is not read meanwhile.
                    usenet && copy?.ended == false && borrow?.confirmed != true ->
                        if (now - copyStartedAt >= USENET_COPY_CHECK_MS && advancePrepare(1, 5)) {
                            showPrepare(PrepareUi.Hidden)
                            withContext(Dispatchers.Main) { resumePlayerAfterPrepare() }
                            Log.i(TAG, "prepare: no copy confirmed in ${USENET_COPY_CHECK_MS / 1000} s - playing, " +
                                "thumbnails carry on from the copy")
                        }
                    // Copy first: the screen waits for the copy's link, then for the copy to be confirmed.
                    copyFirst && !copyScreenDecided -> if (!copyScreenWaits(now)) {
                        copyScreenDecided = true
                        advancePrepare(1, 3)
                    }
                    // Another release's set may cover it all: the check comes before any generating.
                    borrow?.let { !it.confirmed && !it.failed } == true && now - borrowSince < CHECK_HOLD_MS -> Unit
                    else -> advancePrepare(1, 3)
                }
                3 -> {
                    if (prepareDoneAtStart < 0) {
                        prepareDoneAtStart = done
                        prepareShownAtStart = shownDone
                        prepareMadeAtStart = made
                        prepareStartedAt = now
                        Log.i(TAG, "prepare: generating ($shownDone/$shownTotal, $done/$total on the grid, about $left s" +
                            (if (rememberedPerThumb != null) ", speed remembered" else "") + ")")
                    }
                    if (done != prepareLastDone) {
                        prepareLastDone = done
                        prepareLastProgressAt = now
                    }
                    val handover = PrepareHandover.reason(
                        nowMs = now, startedAtMs = prepareStartedAt, lastProgressAtMs = prepareLastProgressAt, leftS = left,
                        done = done, total = total, finished = coverageOver(), rateLimited = sourceRateLimited,
                    )
                    if (handover != null && advancePrepare(3, 5)) {
                        showPrepare(PrepareUi.Hidden)
                        withContext(Dispatchers.Main) { resumePlayerAfterPrepare() }
                        if (handover == PrepareHandover.Reason.RATE_LIMITED) {
                            Log.i(TAG, "prepare: source rate limited ($done/$total) - playing")
                        } else {
                            Log.i(TAG, "prepare: source too slow ($done/$total, about $left s left, " +
                                "${(now - prepareLastProgressAt) / 1000} s since the last one) - playing")
                        }
                        logGiveUp("generating screen " + when (handover) {
                            PrepareHandover.Reason.RATE_LIMITED -> "rate limited"
                            PrepareHandover.Reason.STALLED -> "stalled"
                            PrepareHandover.Reason.HOPELESS -> "too slow"
                        } + " at $done/$total")
                        return
                    }
                    if (done >= total || coverageOver()) {
                        if (!enterReady(3, shownDone, now)) return
                        rememberSpeed(made, now)
                        Log.i(TAG, "prepare: ready - ${shownDone - prepareShownAtStart} thumbnails in ${(now - prepareStartedAt) / 1000} s")
                    } else {
                        showPrepare(PrepareUi.Generating(shownDone, shownTotal, left, prepareAnchorMs, prepareStride() * SPACING_MS,
                            recent, prepareLastCoverage, endSeq, filmLoadsAfter = (letsPlayerGo || playerLetGo) && preparePausedPlayer))
                    }
                }
                4 -> if (now - prepareReadyAt >= PREPARE_READY_MS && advancePrepare(4, 5)) {
                    showPrepare(PrepareUi.Hidden)
                    withContext(Dispatchers.Main) { resumePlayerAfterPrepare() }
                }
            }
        }

        /** Stage 1 with copy first: whether the screen keeps waiting for the copy. Worker only. */
        private fun copyScreenWaits(now: Long): Boolean {
            val c = copy
            val confirmed = c != null && borrow?.let { it.copy === c && it.confirmed } == true
            val linkOpen = (c?.linkOpenedAt ?: 0L) > 0L || headStart?.opened == true
            val sincePick = now - pickedAtMs
            val ended = c?.ended ?: (copyTried || !copyAllowed)
            val wait = CopyFirst.screenWaits(ended, linkOpen, confirmed, sincePick)
            copyScreenWait = wait
            if (wait) return true
            when {
                confirmed -> Log.i(COPY_TAG, "copy first: confirmed $sincePick ms after the stream pick - the copy makes the thumbnails")
                ended -> Log.i(COPY_TAG, "copy first: no copy could be used ($sincePick ms after the stream pick) - the usual way")
                CopyFirst.fallsBack(linkOpen, sincePick) -> {
                    Log.i(COPY_TAG, "copy first: no copy link open ${CopyFirst.FALLBACK_MS / 1000} s after the stream pick - " +
                        "thumbnails the usual way")
                    c?.stop("no copy link open ${CopyFirst.FALLBACK_MS / 1000} s after the stream pick")
                    headStart?.release()
                }
                else -> Log.i(COPY_TAG, "copy first: not confirmed ${CopyFirst.SCREEN_WAIT_MS / 1000} s after the stream pick - " +
                    "the title's own thumbnails carry on beside the copy")
            }
            return false
        }

        fun memoryFloorBytes(): Long =
            if (ended || stoppedExternally || latticeFinished) 0L else MemoryGovernor.coverageFloorBytes(fourK)

        fun progressPercent(): Int? {
            if (!fourK || MemoryGovernor.isStrongBox || ended || latticeFinished) return null
            val total = latticeTotal
            if (total <= 0) return null
            return (latticeDone * 100 / total).coerceIn(0, 99)
        }

        private suspend fun samplePlayer(): PlayerState? = withContext(Dispatchers.Main) {
            val p = playerProvider() ?: return@withContext null
            PlayerState(
                isPlaying = p.isPlaying,
                buffering = p.playbackState == Player.STATE_BUFFERING,
                positionMs = p.currentPosition,
                bufferedMs = p.bufferedPosition,
                durationMs = if (p.duration == C.TIME_UNSET) 0L else p.duration,
                failed = ConnectionLedger.sourceTrouble(p.playerError?.errorCode),
                playWhenReady = p.playWhenReady,
                loading = p.isLoading,
            )
        }

        /** One summary of what held the thumbnails back, so a log export tells the cause. */
        private fun logGiveUp(event: String) = runCatching {
            giveUp.logged = true
            Log.i(TAG, giveUp.line(
                event = event,
                made = synchronized(recentMade) { madeSeq },
                stored = store?.slots()?.size ?: 0,
                memory = MemoryGovernor.memoryNote(),
                connections = ConnectionLedger.app.inFlightNote(reader?.resolvedUrl ?: source.url),
                decoder = ThumbNative.state(),
                decoderReady = ThumbNative.available,
                source = if (usenet && !source.mediaServer) "Usenet" else runCatching { source.kindNote() }.getOrDefault("unknown"),
                video = video.ifEmpty { "unknown" },
                settings = runCatching { source.settingsNote() }.getOrDefault(""),
            ))
        }

        private fun beforePlayName() = beforePlay.name.lowercase().replaceFirstChar { it.uppercase() }

        private fun finish() {
            val noneMade = synchronized(recentMade) { madeSeq } == 0
            val complete = store?.let { runCatching { coverageStored(it) }.getOrDefault(false) } == true
            if (noneMade && giveUp.anything && !giveUp.logged && !complete) {
                logGiveUp("session ended with none made")
            }
            ended = true
            endYield()
            runCatching { reader?.close() }
            fetchScope.cancel()
            if (synchronized(prepareLock) { (prepareStage in 1..4).also { if (it) prepareStage = 5 } }) {
                postPrepareUi(this, PrepareUi.Hidden)
                // e.g. an unsupported file: never leave it paused (but never touch a newer session's player)
                if (!stoppedExternally) mainScope.launch { resumePlayerAfterPrepare() }
            }
            monitorJob?.cancel()
            dropReserve("session end")
            copy?.stop("the thumbnail session ended")
            dropBorrow()
            if (fourK && !MemoryGovernor.isStrongBox) synchronized(pacer) {
                Log.i(TAG, "4K pace at session end: pace ${pacer.paceMs / 1000} s, drops counted ${pacer.countedDrops} " +
                    "ignored ${pacer.ignoredDrops}, stopped drops=${pacer.stoppedByDrops} stall=${pacer.stoppedByStall} " +
                    "(stall stops ${pacer.stallStops})")
            }
            swapHolds.summary()?.let { Log.i(TAG, it) }
            sessionEnded(this)
            if (earlyScreen) mainScope.launch { dropEarlyScreenAtEnd() }
            MemoryGovernor.removeTrimListener(trimListener)
            runCatching { rememberHardwareLanes() }
            runCatching { closeDecoders() }
            decodeScope.cancel()
            store?.let { st ->
                if (lastDurationMs > 0) st.watchedFraction = (lastPositionMs.toFloat() / lastDurationMs).coerceIn(0f, 1f)
                st.flush()
                runCatching { ThumbStore.housekeeping(context, activeKey = st.key) }
            }
            for (d in fetches.values) d.invokeOnCompletion { if (it == null) d.getCompleted().au?.release() }
            buffers.close()
            worker.cancel()
            spoolWriter.cancel()
            spool.clear()
            runCatching { spoolDir.deleteRecursively() }
        }
    }
}
