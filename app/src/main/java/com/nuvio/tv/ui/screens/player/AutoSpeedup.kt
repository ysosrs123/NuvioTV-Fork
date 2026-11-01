package com.nuvio.tv.ui.screens.player

import android.util.Log
import com.nuvio.tv.core.player.BufferSizing
import com.nuvio.tv.core.player.thumbnail.ConnectionLedger
import com.nuvio.tv.core.player.thumbnail.LedgerKey
import com.nuvio.tv.core.player.thumbnail.MemoryGovernor
import com.nuvio.tv.core.player.thumbnail.PlaybackLine
import com.nuvio.tv.core.player.thumbnail.SeekThumbnails
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The Automatic speed-up for the stream that is playing: the policy, the bytes the player has received, and the
 * limits for this box. The memory the extra downloads hold is taken off the buffer budget while they run.
 */
internal object AutoSpeedup {
    private const val TAG = "AutoSpeedup"
    private const val MB = 1024L * 1024L
    private const val ROOM_READ_MS = 2_000L
    private const val SWAP_MIN_MB = 50L
    private const val MAX_FILE_BITRATE = 400_000_000L

    private const val QUICK_FIRST_BYTE_MS = 600L

    /**
     * What the extra downloads leave free above Android's low-memory line. Smaller than the buffer's margin: Android
     * closes idle apps to keep that line.
     */
    const val MARGIN_BYTES = 25L * MB
    private const val FIRST_BYTE_SAMPLES = 2

    /**
     * [quickHoldBytes] = the piece used instead of [holdBytes] when the server sends its first byte quickly: pieces
     * in flight are taken off the buffer, so the smallest piece that still keeps the downloads busy leaves the most
     * film in memory. [smallBox] = a 2 GB box: a start that holds no more than two 16 MB downloads.
     * [readAheadMaxBytes] = the most the read-ahead may hold on this box. [pieceBudgetBytes] = the most the downloads
     * and their pieces may hold together ([AutoSpeedupPolicy.lentBytes]) when Automatic picks the piece size; 0 keeps
     * the size picked at the start.
     */
    data class Limits(
        val maxDownloads: Int,
        val holdBytes: Long,
        val quickHoldBytes: Long = holdBytes,
        val smallBox: Boolean = false,
        val readAheadMaxBytes: Long = AutoSpeedupPolicy.READ_AHEAD_MAX_BYTES,
        val pieceBudgetBytes: Long = 0L
    )

    /** Bytes received from the network for the stream that is playing, on either path. */
    val received = AtomicLong()

    /** Readers on the chunk session now; above 0 the extra downloads are really running. */
    val multiOpen = AtomicInteger()

    /** The chunk session's piece requests really running, downloads and read-ahead together. */
    val requests = RequestMeter()

    /** Requests the stream has running now: the chunk session's, else its single download. */
    fun requestsNow(): Int = if (multiOpen.get() > 0) requests.running() else if (policy != null) 1 else 0

    @Volatile
    private var url: String? = null

    @Volatile
    private var policy: AutoSpeedupPolicy? = null

    @Volatile
    var limits: Limits = limitsFor(0L)
        private set

    val downloads: Int get() = policy?.downloads ?: 1

    /** Pieces fetched beyond one per download while memory allows, see [AutoSpeedupPolicy.readAheadPieces]. */
    @Volatile
    var readAhead: Int = 0
        private set

    private var readAheadRoomAtMs = Long.MIN_VALUE

    /**
     * Pieces the chunk session may have under way or waiting: one per download plus the read-ahead, never more than
     * [MAX_IN_FLIGHT] requests at once, where debrid servers started answering "too many requests".
     */
    fun depth(): Int = minOf(
        ConnectionLedger.app.playbackRoom(ledgerUrl, wantedDepth(), requests.running()),
        policy?.requestLimit ?: Int.MAX_VALUE
    )

    /** Requests playback would like at once, before the server's shared budget ([ConnectionLedger.playbackRoom]). */
    private fun wantedDepth(extra: Boolean = true): Int = minOf(
        downloads + if (downloads > 1) readAhead + if (extra) lazyExtra() else 0 else 0,
        maxOf(downloads, MAX_IN_FLIGHT)
    )

    /**
     * Pieces take memory only as their bytes arrive (the chunk session caps what they hold at [heldLimitBytes]), so a
     * request still waiting for its first byte costs nothing: one more runs in the same memory. Never the one that
     * would reach [MAX_IN_FLIGHT]: the start's side requests (link look-up, the resume point) still need a place.
     */
    @Volatile
    var lazyMemory: Boolean = false

    private fun lazyExtra(): Int = extraRequest(lazyMemory, downloads, readAhead)

    /** Boxes of 3 GB or more; 2 GB boxes keep whole pieces until their memory warnings are understood. */
    internal fun lazyMemoryFor(limits: Limits): Boolean = !limits.smallBox && limits.pieceBudgetBytes > 0L

    internal fun extraRequest(on: Boolean, downloads: Int, readAhead: Int): Int =
        if (on && downloads > 1 && downloads + readAhead + 1 < MAX_IN_FLIGHT) 1 else 0

    /** What the pieces may hold together when memory follows the bytes: what they could hold before, extra request aside. */
    fun heldLimitBytes(): Long =
        heldLimitFor(wantedDepth(extra = false), policy?.requestLimit ?: Int.MAX_VALUE, maxOf(pieceBytes, activePieceBytes))

    internal fun heldLimitFor(depth: Int, requestLimit: Int, pieceBytes: Long): Long =
        (minOf(depth, requestLimit) + 1).toLong() * pieceBytes

    private fun noteRequests() = ConnectionLedger.app.notePlaybackDownloads(url, wantedDepth())

    /** The most pieces the chunk session is ever asked to hold for this piece size. */
    fun maxDepth(pieceBytes: Long): Int =
        maxOf(limits.maxDownloads + 1, (limits.readAheadMaxBytes / pieceBytes.coerceAtLeast(1L)).toInt())

    @Volatile
    private var hintUrl: String? = null

    @Volatile
    private var hintBps = 0L

    /** The bitrate [guessBitrate] found for [streamUrl], used by [begin] before the first byte arrives. */
    fun startHint(streamUrl: String, bitrateBps: Long) {
        hintUrl = streamUrl
        hintBps = bitrateBps
    }

    /**
     * A film's bitrate in bits per second known before its first byte: the add-on's size over the title's runtime,
     * else 80 Mbit/s for a name that says remux, or 4K with lossless audio outside a web release; 0 otherwise.
     */
    fun guessBitrate(sizeBytes: Long?, runtimeMinutes: Int?, vararg names: String?): Long {
        if (sizeBytes != null && sizeBytes > 0L && runtimeMinutes != null && runtimeMinutes >= MIN_GUESS_RUNTIME_MIN) {
            val bps = sizeBytes * 8L / (runtimeMinutes * 60L)
            if (bps in 1..MAX_FILE_BITRATE) return bps
        }
        val text = names.filterNotNull().joinToString(" ")
        if (REMUX.containsMatchIn(text)) return GUESSED_REMUX_BPS
        if (UHD.containsMatchIn(text) && LOSSLESS_AUDIO.containsMatchIn(text) && !WEB.containsMatchIn(text)) {
            return GUESSED_REMUX_BPS
        }
        return 0L
    }

    private const val MIN_GUESS_RUNTIME_MIN = 20
    const val MAX_IN_FLIGHT = 8
    private const val GUESSED_REMUX_BPS = 80_000_000L
    private val REMUX = Regex("remux", RegexOption.IGNORE_CASE)
    private val UHD = Regex("""2160p|\buhd\b|\b4k\b""", RegexOption.IGNORE_CASE)
    private val LOSSLESS_AUDIO = Regex("""truehd|dts[-. ]?hd[-. ]?ma|dts[-. ]?x\b""", RegexOption.IGNORE_CASE)
    private val WEB = Regex("web[-. ]?(dl|rip)", RegexOption.IGNORE_CASE)

    /**
     * Recomputed only on a fresh memory reading, so one reading never counts twice. While seek thumbnails are
     * generating it also leaves them the MemAvailable they need ([thumbFloorBytes]).
     */
    fun updateReadAhead(roomBytes: Long, thumbFloorBytes: () -> Long = { SeekThumbnails.memoryFloorBytes() }) {
        if (roomReadAtMs == readAheadRoomAtMs && downloads > 1) return
        readAheadRoomAtMs = roomReadAtMs
        val floor = if (downloads > 1) thumbFloorBytes() else 0L
        val room = if (floor > 0L) {
            val avail = MemoryGovernor.readMeminfo()?.availableMb?.let { it * MB } ?: -1L
            AutoSpeedupPolicy.readAheadRoom(roomBytes, avail, floor)
        } else {
            roomBytes
        }
        val next = if (limits.smallBox) {
            AutoSpeedupPolicy.lentReadAhead(downloads, pieceBytes, limits.pieceBudgetBytes, room)
        } else {
            AutoSpeedupPolicy.readAheadPieces(readAhead, room, pieceBytes, downloads, limits.readAheadMaxBytes)
        }
        if (next != readAhead) {
            val thumbs = if (floor > 0L) " thumbFloorMb=${floor / MB}" else ""
            val plan = if (limits.smallBox) " plan=lent pieceMb=${pieceBytes / MB} lentMb=" +
                "${AutoSpeedupPolicy.lentBytes(downloads, pieceBytes) / MB}+${next * pieceBytes / MB}" else ""
            Log.i(TAG, "AUTO_SPEEDUP: read-ahead $readAhead->$next downloads=$downloads roomMb=${room / MB}$thumbs$plan")
            readAhead = next
            noteRequests()
        }
    }

    val active: Boolean get() = policy != null

    /**
     * A 2 GB box may hold 64 MB in pieces: six 8 MB downloads, four 12 MB or three 16 MB. Larger boxes as many
     * downloads of the bigger piece as the read-ahead may hold.
     */
    fun limitsFor(totalRamBytes: Long): Limits = when {
        totalRamBytes in 1 until 2_560L * MB ->
            Limits(maxDownloads = 6, holdBytes = 16L * MB, quickHoldBytes = 8L * MB, smallBox = true,
                pieceBudgetBytes = 64L * MB)
        totalRamBytes in 1 until 3_584L * MB ->
            Limits(maxDownloads = 8, holdBytes = 16L * MB, quickHoldBytes = 8L * MB, readAheadMaxBytes = 192L * MB,
                pieceBudgetBytes = 144L * MB)
        totalRamBytes > 0L ->
            Limits(maxDownloads = 10, holdBytes = 24L * MB, quickHoldBytes = 8L * MB, readAheadMaxBytes = 256L * MB,
                pieceBudgetBytes = 256L * MB)
        else -> Limits(maxDownloads = 1, holdBytes = 16L * MB)
    }

    /** A slow first byte costs a share of every piece, so smaller pieces only pay off on a quick server. */
    fun pieceFor(limits: Limits, firstByteMs: Long): Long =
        if (firstByteMs in 0 until QUICK_FIRST_BYTE_MS) limits.quickHoldBytes else limits.holdBytes

    /**
     * The piece size Automatic chose for this stream. It is open until the first requests were timed; later the
     * chunk session follows it between pieces ([AutoSpeedupPolicy] decides, [AutoSpeedupDataSource] changes over).
     */
    @Volatile
    var pieceBytes: Long = limits.holdBytes
        private set

    /** The piece size of the chunk session reading now, 0 while none is. */
    @Volatile
    var activePieceBytes: Long = 0L
        private set

    /** The chunk session now reads [now] pieces; the size it read before is no longer used and its spare buffers go. */
    fun onPieceInUse(now: Long, retire: (Long) -> Unit = ParallelRangeDataSource::retireChunkSize) {
        val old = activePieceBytes
        activePieceBytes = now
        if (old > 0L && old != now) {
            retire(old)
            Log.i(TAG, "AUTO_SPEEDUP: piece in use ${old / MB}->${now / MB}MB")
        }
    }

    private val pieceLock = Any()
    private var pieceFixed = true
    private var firstByteMs = -1L
    private var firstByteSamples = 0

    /** How long a request on the single download took to answer, while the piece size is still open. */
    fun onFirstByte(ms: Long) {
        synchronized(pieceLock) {
            if (policy == null || ms < 0L) return
            firstByteMs = if (firstByteMs < 0L || firstByteSamples == 0) ms else minOf(firstByteMs, ms)
            policy?.firstByteMs = firstByteMs
            if (pieceFixed) {
                firstByteSamples++
                return
            }
            pieceBytes = planPiece() ?: pieceFor(limits, firstByteMs)
            policy?.pieceBytes = pieceBytes
            if (++firstByteSamples >= FIRST_BYTE_SAMPLES) fixPieceLocked()
        }
    }

    /** A piece request finished on the chunk session. */
    fun onPiece(t: PieceTiming) {
        val p = policy ?: return
        p.onPiece(t)
        Log.d(TAG, "AUTO_SPEEDUP: piece ttfbMs=${t.ttfbMs} transferMs=${t.transferMs} " +
            "kbps=${if (t.transferMs > 0L) t.bytes * 8 / t.transferMs else 0L} reused=${t.reused} redirect=${t.redirected}")
    }

    /** Fixes the piece size (the larger one while nothing was measured) and returns it. */
    fun fixPiece(): Long = synchronized(pieceLock) {
        if (!pieceFixed) fixPieceLocked()
        pieceBytes
    }

    private fun fixPieceLocked() {
        pieceFixed = true
        logOn()
    }

    /** The piece the plan picks from the server's first byte and rate, for the downloads the film runs on now. */
    private fun planPiece(): Long? {
        val p = policy ?: return null
        val raw = p.rawRateNow() ?: return null
        val n = p.planFor(guessedBps)?.downloads ?: p.downloads
        return AutoSpeedupPolicy.pieceForDownloads(maxOf(n, p.downloads), p.waitMs(), raw,
            AutoSpeedupPolicy.targetBps(guessedBps), limits.pieceBudgetBytes)
    }

    private fun logOn() {
        val firstByte = if (firstByteMs >= 0L) " firstByteMs=$firstByteMs" else ""
        val raw = policy?.rawRateNow()?.let { " rawKbps=${it * 8 / 1000}" } ?: ""
        Log.i(TAG, "AUTO_SPEEDUP: on maxDownloads=${limits.maxDownloads} holdMb=${pieceBytes / MB}$firstByte$raw " +
            "budgetMb=${limits.pieceBudgetBytes / MB}")
    }

    @Volatile
    private var roomReadAtMs = Long.MIN_VALUE

    @Volatile
    private var room = Long.MAX_VALUE

    /** Free memory above Android's low-memory line and [MARGIN_BYTES], read at most every [ROOM_READ_MS]. */
    fun roomBytes(nowMs: Long, read: () -> BufferSizing.FreeMemory?): Long {
        if (roomReadAtMs == Long.MIN_VALUE || nowMs - roomReadAtMs >= ROOM_READ_MS) {
            room = roomAboveFloor(read())
            roomReadAtMs = nowMs
        }
        return room
    }

    @Volatile
    private var swapReadAtMs = Long.MIN_VALUE

    @Volatile
    private var swapLowNow = false

    /** SwapFree under [SWAP_MIN_MB] on a box that has swap, read at most every [ROOM_READ_MS]. */
    fun swapLow(nowMs: Long, read: () -> MemoryGovernor.Meminfo?): Boolean {
        if (swapReadAtMs == Long.MIN_VALUE || nowMs - swapReadAtMs >= ROOM_READ_MS) {
            swapLowNow = read()?.let { swapLow(it.swapTotalMb, it.swapFreeMb) } ?: false
            swapReadAtMs = nowMs
        }
        return swapLowNow
    }

    /** A box without swap, or with unknown swap, is never held back. */
    fun swapLow(swapTotalMb: Long, swapFreeMb: Long): Boolean = swapTotalMb > 0L && swapFreeMb < SWAP_MIN_MB

    /** Unknown free memory never blocks a download. */
    fun roomAboveFloor(free: BufferSizing.FreeMemory?): Long =
        if (free == null) Long.MAX_VALUE
        else free.availBytes - free.thresholdBytes - MARGIN_BYTES

    @Volatile
    private var hostKey: String? = null

    @Volatile
    private var remembered: HostSpeeds.Entry? = null

    /** The server the link really reads from (after an add-on's redirect), once the first request has opened. */
    @Volatile
    private var serverKey: String? = null

    /** The link the requests really read from, the one playback's [ConnectionLedger] entries are kept under. */
    @Volatile
    private var servedUrl: String? = null

    private val ledgerUrl: String? get() = servedUrl ?: url

    @Volatile
    private var guessedBps = 0L

    /**
     * The first request found the server behind the link. An add-on (stremthru, Comet) can hand out files from
     * different services, so what that server delivered before replaces what was learnt under the add-on's address,
     * and a start still being judged is set to it.
     */
    fun onResolved(resolvedUrl: String?) {
        val key = HostSpeeds.keyOf(resolvedUrl) ?: return
        if (policy != null) servedUrl = resolvedUrl
        if (key == serverKey || policy == null) return
        serverKey = key
        if (key == hostKey) return
        val p = policy ?: return
        val limit = HostSpeeds.limitOf(key)
        if (limit != null) p.onRateLimited(limit)
        if (limit != null) HostSpeeds.heldBlock(key)?.let { logBlockHeld(key, limit, it) }
        val known = HostSpeeds.get(key)
        p.stepBoost = known == null && limit == null && LedgerKey.knownTotal(LedgerKey.of(resolvedUrl)) == null
        if (known == null) return
        val start = synchronized(pieceLock) {
            p.knownPerDownloadBps = known.perDownloadBps
            p.knownPieceBytes = known.pieceBytes
            p.knownStartPieceBytes = known.startPieceBytes
            if (p.firstByteMs < 0L && known.firstByteMs >= 0L) p.firstByteMs = known.firstByteMs
            if (known.firstByteMs >= 0L) p.knownFirstByteMs = known.firstByteMs
            val before = p.pieceBytes
            val n = if (guessedBps > 0L) p.startFor(guessedBps) else 0
            if (n > 1) p.pieceBytes = p.rememberedStartPiece(n)
            // The chunk session may already read the old size: the plan's size then follows between pieces.
            if (p.pieceBytes != before) pieceBytes = p.pieceBytes
            n
        }
        val restarted = start > 1 && p.restartStart(start)
        Log.i(TAG, "AUTO_SPEEDUP: server $key behind $hostKey perDownloadKbps=${known.perDownloadBps * 8 / 1000}" +
            " pieceMb=${pieceBytes / MB}" + if (restarted) " start downloads=$start" else "")
        if (restarted) noteRequests()
    }

    /**
     * A new stream starts on one download; the same stream again (a subtitle reload) keeps what it learnt. What the
     * same server delivered before ([HostSpeeds]) picks the piece size at once and the downloads a heavy film starts on.
     */
    fun begin(streamUrl: String, totalRamBytes: Long) {
        if (policy != null && streamUrl == url) return
        end()
        blockTrial.reset()
        limits = limitsFor(totalRamBytes)
        url = streamUrl
        hostKey = HostSpeeds.keyOf(streamUrl)
        serverKey = null
        servedUrl = null
        remembered = hostKey?.let { HostSpeeds.get(it) }
        received.set(0L)
        requests.reset()
        roomReadAtMs = Long.MIN_VALUE
        readAheadRoomAtMs = Long.MIN_VALUE
        readAhead = 0
        swapReadAtMs = Long.MIN_VALUE
        swapLowNow = false
        activePieceBytes = 0L
        PlaybackConnectionEvents.trackPieces = true
        synchronized(pieceLock) {
            pieceBytes = limits.holdBytes
            firstByteMs = -1L
            firstByteSamples = 0
            val p = AutoSpeedupPolicy(limits.maxDownloads, pieceBytes, limits.smallBox)
            p.pieceBudgetBytes = limits.pieceBudgetBytes
            p.readAheadMaxBytes = limits.readAheadMaxBytes
            val limit = hostKey?.let { HostSpeeds.limitOf(it) }
            if (limit != null) p.requestLimit = limit
            if (limit != null) hostKey?.let(HostSpeeds::heldBlock)?.let { logBlockHeld(hostKey, limit, it) }
            p.stepBoost = remembered == null && limit == null && LedgerKey.knownTotal(LedgerKey.of(streamUrl)) == null
            policy = p
            pieceFixed = limits.quickHoldBytes == limits.holdBytes
            val known = remembered
            val guessed = if (hintUrl == streamUrl) hintBps else 0L
            guessedBps = guessed
            if (known != null) {
                p.knownPerDownloadBps = known.perDownloadBps
                p.knownPieceBytes = known.pieceBytes
                p.knownStartPieceBytes = known.startPieceBytes
                if (known.firstByteMs >= 0L) {
                    firstByteMs = known.firstByteMs
                    p.firstByteMs = known.firstByteMs
                    p.knownFirstByteMs = known.firstByteMs
                    pieceBytes = (if (guessed > 0L) planPiece() else null) ?: rememberedPiece(known) ?: planPiece()
                        ?: pieceFor(limits, known.firstByteMs)
                    p.pieceBytes = pieceBytes
                    pieceFixed = true
                }
                Log.i(TAG, "AUTO_SPEEDUP: remembered host=$hostKey perDownloadKbps=${known.perDownloadBps * 8 / 1000} " +
                    "downloads=${known.downloads} firstByteMs=${known.firstByteMs} pieceMb=${known.pieceBytes / MB}")
            }
            if (guessed >= AutoSpeedupPolicy.START_THREE_BPS && limits.maxDownloads > 1) {
                if (known == null || known.firstByteMs < 0L) {
                    // No first byte timed yet: the smaller piece, which the slow servers it suits less still carry.
                    pieceBytes = limits.quickHoldBytes
                    p.pieceBytes = pieceBytes
                }
                pieceFixed = true
                val start = p.startFor(guessed)
                var from = ""
                if (limits.pieceBudgetBytes > 0L && start > 1) {
                    val planned = p.pieceBytes
                    p.pieceBytes = AutoSpeedupPolicy.fittingPiece(start, p.rememberedStartPiece(start), limits.pieceBudgetBytes)
                    if (p.pieceBytes > planned) from = " plannedMb=${planned / MB} rememberedMb=${p.knownStartPieceBytes / MB}"
                }
                pieceBytes = p.pieceBytes
                p.preset(start, guessed)
                Log.i(TAG, "AUTO_SPEEDUP: start guessed downloads=$start fileKbps=${guessed / 1000} " +
                    "pieceMb=${pieceBytes / MB}$from")
            }
            if (pieceFixed) logOn()
        }
    }

    /** The size the server's films ran on before, when the plan cannot pick one (no film bitrate yet). */
    private fun rememberedPiece(known: HostSpeeds.Entry): Long? =
        known.pieceBytes.takeIf { limits.pieceBudgetBytes > 0L && it in AutoSpeedupPolicy.PIECE_STEPS }

    fun end() {
        val p = policy
        val wasOn = p != null
        val keys = listOfNotNull(serverKey, hostKey).distinct()
        if (p != null && keys.isNotEmpty() && p.measuredPerDownloadBps > 0L) {
            val pieceWait = p.pieceTimes.filmTtfbMs()
            val wait = when {
                pieceWait >= 0L -> pieceWait
                firstByteMs >= 0L -> firstByteMs
                else -> remembered?.firstByteMs ?: -1L
            }
            val ran = activePieceBytes.takeIf { it > 0L } ?: pieceBytes
            val entry = HostSpeeds.Entry(p.measuredPerDownloadBps, p.peakKept.coerceAtLeast(1), wait, System.currentTimeMillis(),
                ran, p.pieceToRemember(ran))
            // The real server's entry is what the next film from it starts on; the add-on's is only a first guess.
            keys.forEach { HostSpeeds.record(it, entry) }
            Log.i(TAG, "AUTO_SPEEDUP: saved host=${keys.joinToString("+")} " +
                "perDownloadKbps=${entry.perDownloadBps * 8 / 1000} downloads=${entry.downloads} " +
                "averagedKbps=${(HostSpeeds.get(keys.first())?.perDownloadBps ?: 0L) * 8 / 1000} " +
                "pieceMb=${entry.pieceBytes / MB} averagedPieceMb=${(HostSpeeds.get(keys.first())?.pieceBytes ?: 0L) / MB} " +
                "startPieceMb=${entry.startPieceBytes / MB} nextStartPieceMb=${(HostSpeeds.get(keys.first())?.startPieceBytes ?: 0L) / MB} " +
                "firstByteMs=${entry.firstByteMs} pieceFirstByteMs=$pieceWait")
        }
        if (wasOn) PlaybackConnectionEvents.trackPieces = false
        policy = null
        lazyMemory = false
        activePieceBytes = 0L
        url = null
        hostKey = null
        serverKey = null
        servedUrl = null
        guessedBps = 0L
        remembered = null
        PlaybackLine.clear()
        planLoggedLent = -1L
        if (wasOn) ParallelRangeDataSource.releaseRetainedSessionIfIdle()
    }

    /**
     * The film's average bitrate in bits per second, from the size the server gave (else the add-on's) over the length;
     * 0 while either is unknown, or when the figures cannot both be right.
     */
    fun fileBitrate(serverBytes: Long?, addonBytes: Long?, durationMs: Long): Long {
        val bytes = serverBytes?.takeIf { it > 0L } ?: addonBytes
        val bps = PlayerBitrateEstimator.fileBitrateBps(bytes, durationMs)?.toLong() ?: return 0L
        return if (bps <= MAX_FILE_BITRATE) bps else 0L
    }

    /**
     * Bytes the extra downloads may hold now: each one in flight plus the piece being read, on the bigger of the
     * chosen size and the size still being read while the chunk session changes over.
     */
    fun lentBytes(): Long {
        val piece = maxOf(pieceBytes, activePieceBytes)
        val pieces = AutoSpeedupPolicy.lentBytes(downloads, piece)
        // A 2 GB box has one memory plan: the read-ahead's pieces come out of the buffer like the downloads'.
        return if (limits.smallBox && downloads > 1) pieces + readAhead * piece else pieces
    }

    private var planLoggedAtMs = Long.MIN_VALUE
    private var planLoggedLent = -1L

    /**
     * On a 2 GB box, what the downloads, the read-ahead and the buffer hold together, when the lent memory changes
     * and every [PLAN_LOG_MS], so a capture shows whether the buffer and the read-ahead squeeze each other.
     */
    fun logMemoryPlan(nowMs: Long, bufferUsedBytes: Long, bufferTargetBytes: Long, pressure: Int, roomBytes: Long) {
        if (!limits.smallBox || policy == null) return
        val lent = lentBytes()
        if (lent == planLoggedLent && nowMs - planLoggedAtMs < PLAN_LOG_MS) return
        planLoggedAtMs = nowMs
        planLoggedLent = lent
        Log.i(TAG, "AUTO_SPEEDUP: memory plan downloads=$downloads readAhead=$readAhead pieceMb=${pieceBytes / MB} " +
            "lentMb=${lent / MB} bufferMb=${bufferUsedBytes / MB}/${bufferTargetBytes / MB} pressure=$pressure " +
            "roomMb=${if (roomBytes == Long.MAX_VALUE) -1 else roomBytes / MB} requests=${requestsNow()}")
    }

    private const val PLAN_LOG_MS = 30_000L

    /** For the stats HUD: requests now, the server's budget, what one request carries and its wait. */
    data class Hud(val requests: Int, val budget: Int, val perRequestBps: Long, val firstByteMs: Long)

    fun hud(): Hud? {
        val p = policy ?: return null
        val budget = LedgerKey.knownTotal(LedgerKey.of(url)) ?: MAX_IN_FLIGHT
        return Hud(requestsNow(), budget, p.rawRateNow() ?: p.measuredPerDownloadBps, p.waitMs())
    }

    /**
     * The seconds of film the buffer may hold: the Max buffer, or less where its memory ([targetBytes]) runs out first
     * at the film's average rate; 0 when neither is known.
     */
    fun bufferTargetMs(maxBufferMs: Long, targetBytes: Long, fileBitrate: Long): Long {
        val byMemory = if (targetBytes > 0L && fileBitrate > 0L) targetBytes * 8_000L / fileBitrate else 0L
        return when {
            maxBufferMs > 0L && byMemory > 0L -> minOf(maxBufferMs, byMemory)
            else -> maxOf(maxBufferMs, byMemory)
        }
    }

    /**
     * A piece request was answered "too many requests" with [inFlight] requests running on that server: it is held
     * to the count that worked (the usual start at least), here and for its next films ([HostSpeeds.noteLimit]).
     */
    fun onRateLimited(url: String?, inFlight: Int) {
        val p = policy ?: return
        val limit = minOf(p.limitAfterRefusal(inFlight), p.requestLimit)
        val lower = limit < p.requestLimit
        p.onRateLimited(limit)
        if (!lower) return
        listOfNotNull(HostSpeeds.keyOf(url), serverKey, hostKey).distinct().forEach { HostSpeeds.noteLimit(it, limit) }
        Log.i(TAG, "AUTO_SPEEDUP: too many requests inFlight=$inFlight limit=$limit server=${HostSpeeds.keyOf(url)}")
    }

    private val extraStrikes = ExtraDownloadStrikes()

    /**
     * A server refused an extra download while another kept delivering, with [running] requests running there: held
     * to one fewer, or to one at a second strike, here and for its next films. May go below the usual start.
     */
    fun onExtraDownloadRefused(url: String?, running: Int) {
        val p = policy ?: return
        val keys = listOfNotNull(HostSpeeds.keyOf(url), serverKey, hostKey).distinct()
        val key = keys.firstOrNull() ?: return
        val limit = minOf(extraStrikes.limitAfter(key, running, System.currentTimeMillis()), p.requestLimit)
        p.onRateLimited(limit)
        keys.forEach { HostSpeeds.noteLimit(it, limit) }
        Log.i(TAG, "AUTO_SPEEDUP: extra download refused running=$running limit=$limit server=$key")
    }

    private val blockTrial = ParallelBlockTrial()

    @Volatile
    private var limitBeforeTrial = Int.MAX_VALUE

    private fun logBlockHeld(key: String?, limit: Int, block: HostSpeeds.Limit) {
        val leftH = (block.atMs + HostSpeeds.blockHoldMs(block.strikes) - System.currentTimeMillis()) / (60 * 60 * 1000)
        Log.i(TAG, "AUTO_SPEEDUP: server $key blocks parallel downloads, held to $limit " +
            "strike=${block.strikes} hoursLeft=$leftH")
    }

    /**
     * Every download to the server failed while none delivered ([ParallelBlockTrial.failed]). The first such failure
     * after downloads ran side by side holds this film to one download to see whether the server serves one.
     */
    fun onAllDownloadsFailed(url: String?, f: AllDownloadsFailed) {
        val p = policy ?: return
        val now = System.currentTimeMillis()
        when (blockTrial.failed(f.running, f.served, now - f.ageMs, f.beside, f.block, received.get(), now)) {
            ParallelBlockTrial.Verdict.TRYING -> {
                limitBeforeTrial = p.requestLimit
                p.requestLimit = 1
                Log.i(TAG, "AUTO_SPEEDUP: every download failed together running=${f.running}, trying one " +
                    "server=${HostSpeeds.keyOf(url)}")
            }
            ParallelBlockTrial.Verdict.CLEARED -> endTrial(p, learnt = false)
            else -> Unit
        }
    }

    private fun checkTrial(p: AutoSpeedupPolicy) {
        if (!blockTrial.trying) return
        when (blockTrial.progress(received.get(), requestsNow(), System.currentTimeMillis())) {
            ParallelBlockTrial.Verdict.LEARNT -> endTrial(p, learnt = true)
            ParallelBlockTrial.Verdict.CLEARED -> endTrial(p, learnt = false)
            else -> Unit
        }
    }

    private fun endTrial(p: AutoSpeedupPolicy, learnt: Boolean) {
        val keys = listOfNotNull(HostSpeeds.keyOf(servedUrl), serverKey, hostKey).distinct()
        if (learnt) {
            val kept = keys.map { HostSpeeds.noteLimit(it, 1, blocks = true) }.firstOrNull()
            val strike = kept?.strikes ?: 1
            Log.i(TAG, "AUTO_SPEEDUP: server ${keys.firstOrNull()} blocks parallel downloads, learnt limit=1 " +
                "holdDays=${HostSpeeds.blockHoldMs(strike) / (24 * 60 * 60 * 1000)} strike=$strike")
        } else {
            if (p.requestLimit == 1) p.requestLimit = limitBeforeTrial
            Log.i(TAG, "AUTO_SPEEDUP: one download failed too or the server recovered, nothing learnt " +
                "server=${keys.firstOrNull()} limit=${if (p.requestLimit == Int.MAX_VALUE) -1 else p.requestLimit}")
        }
    }

    /** What thumbnails may use next to the film ([PlaybackLine]). */
    private fun publishLine(p: AutoSpeedupPolicy, sample: AutoSpeedupPolicy.Sample) {
        val running = requestsNow()
        val needed = p.plannedRequests(sample.fileBitrate)
        val served = ledgerUrl
        val key = LedgerKey.of(served)
        val delivered = p.deliveredNow(sample.nowMs)
        PlaybackLine.publish(PlaybackLine.State(
            atMs = sample.nowMs,
            bufferTargetMs = sample.bufferTargetMs,
            filmBps = maxOf(sample.fileBitrate, p.recentFilmBps),
            deliveredBps = delivered?.bps ?: 0L,
            requests = running,
            loaderRested = delivered?.rested == true,
            needed = needed,
            serverLimit = minOf(p.requestLimit, LedgerKey.knownTotal(key) ?: MAX_IN_FLIGHT),
            otherRequests = ConnectionLedger.app.otherPlayback(served, requests.running()),
            filmKey = key,
            linkKey = LedgerKey.of(url),
            filmServer = HostSpeeds.keyOf(served),
            seekAtMs = sample.seekAtMs,
            refusedAtMs = p.refusedAtMs,
            spareBytes = spareBytes(running, needed)
        ))
    }

    /**
     * On a 2 GB box, memory lent out of the buffer to pieces the film does not use now: the downloads' and read-ahead's
     * pieces beyond the ones running or planned, whichever is more, and the one being read.
     */
    fun spareBytes(running: Int, needed: Int): Long {
        if (!limits.smallBox || downloads <= 1) return 0L
        val piece = maxOf(pieceBytes, activePieceBytes).coerceAtLeast(1L)
        val lentPieces = lentBytes() / piece
        return (lentPieces - maxOf(running, needed) - 1).coerceAtLeast(0L) * piece
    }

    /** Returns true when the number of downloads changed. */
    fun onPlayerSample(sample: AutoSpeedupPolicy.Sample): Boolean {
        val p = policy ?: return false
        val before = p.downloads
        checkTrial(p)
        p.roomCap = ConnectionLedger.app.playbackRoom(ledgerUrl, limits.maxDownloads, requests.running())
        p.onSample(sample.copy(bytes = received.get(), multiActive = multiOpen.get() > 0,
            requestMs = requests.requestMs(), requests = requestsNow()))
        p.lastChange?.let {
            Log.i(TAG, "AUTO_SPEEDUP: $it aheadMs=${sample.aheadMs} roomCap=${p.roomCap} requests=${requestsNow()} " +
                p.measureTag())
        }
        p.lastRefill?.let { Log.i(TAG, "AUTO_SPEEDUP: $it") }
        p.lastBoostNote?.let { Log.i(TAG, "AUTO_SPEEDUP: $it") }
        publishLine(p, sample)
        if (p.pieceBytes != pieceBytes) synchronized(pieceLock) { pieceBytes = p.pieceBytes }
        p.lastPieceChange?.let { Log.i(TAG, "AUTO_SPEEDUP: $it aheadMs=${sample.aheadMs} ${p.measureTag()}") }
        if (p.downloads != before) noteRequests()
        return p.downloads != before
    }
}
