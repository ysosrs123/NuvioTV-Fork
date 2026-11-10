package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.player.thumbnail.PlaybackLine

/**
 * How many downloads a progressive stream may use at once with Custom network off. One more is tried only while the
 * film runs short, kept only when it raises the delivered rate by [GAIN_PERCENT] % (or the buffer starts to grow),
 * and handed back once the buffer has been comfortable for a while, or as full as the settings and memory let it get
 * with the loader resting. One more is only tried while the box has the memory it would hold free above its low-memory
 * line and its swap is not nearly used up, and one is handed back as soon as free memory drops below that line. A film
 * whose bitrate is known and high first measures one download; only when that stays under [START_SINGLE_ENOUGH_PERCENT]
 * % of the film's rate does it go to [startDownloads], judged like any other try against what the single download
 * managed; if they do not help, the film goes back to one and climbs the usual way if it runs short.
 */
internal class AutoSpeedupPolicy(
    val maxDownloads: Int,
    pieceBytes: Long = 0L,
    private val smallBox: Boolean = maxDownloads <= 4
) {

    @Volatile
    var pieceBytes: Long = pieceBytes

    /** What one download carried from this server before ([HostSpeeds]), 0 when unknown. */
    @Volatile
    var knownPerDownloadBps: Long = 0L

    /** The piece size [knownPerDownloadBps] was carried on, 0 when unknown. */
    @Volatile
    var knownPieceBytes: Long = 0L

    /** The piece size this server's films needed before ([HostSpeeds.Entry.startPieceBytes]), 0 when unknown. */
    @Volatile
    var knownStartPieceBytes: Long = 0L

    /** The piece this server's films run on at least, where it fits [pieceBudgetBytes]: fewer requests for the same film. */
    var preferredPieceBytes: Long = 0L

    private fun atLeastPreferred(downloads: Int, piece: Long): Long {
        val prefer = preferredPieceBytes
        if (prefer <= piece || pieceBudgetBytes <= 0L) return piece
        return maxOf(piece, fittingPiece(downloads, prefer, pieceBudgetBytes))
    }

    /** The wait for the first byte on the start's single request (an add-on's redirect included), -1 while unknown. */
    @Volatile
    var firstByteMs: Long = -1L

    /** The server's wait for a piece request remembered from its films before ([HostSpeeds]), -1 while unknown. */
    @Volatile
    var knownFirstByteMs: Long = -1L

    /** The piece requests of the last [PIECE_SMOOTH_MS]. */
    val pieceTimes = PieceTimes()

    @Volatile
    private var latestMs = 0L

    fun onPiece(t: PieceTiming) {
        pieceTimes.add(t)
        if (t.atMs > latestMs) latestMs = t.atMs
    }

    /**
     * The wait for the first byte the plan counts with: the piece requests of the last [PIECE_SMOOTH_MS] (on reused
     * connections when there are any), else what the server's piece requests waited before, else the start's.
     */
    fun waitMs(): Long {
        val now = pieceTimes.averages(latestMs).ttfbMs
        if (now >= 0L) return now
        return if (knownFirstByteMs >= 0L) knownFirstByteMs else firstByteMs
    }

    /** One download's rate while data flows, from the piece requests of the last [PIECE_SMOOTH_MS]; null with too few. */
    fun measuredRawBps(): Long? =
        pieceTimes.averages(latestMs).takeIf { it.flowPieces >= MIN_FLOW_PIECES && it.flowBps > 0L }?.flowBps

    /** What was measured, for the log line of each decision. */
    fun measureTag(): String {
        val a = pieceTimes.averages(latestMs)
        val delivered = rateBps(latestMs - PIECE_SMOOTH_MS, latestMs)
        return "ttfbMs=${waitMs()} reusedTtfbMs=${a.reusedTtfbMs} newTtfbMs=${a.newTtfbMs} reused=${a.reused}/${a.timed} " +
            "flowKbps=${a.flowBps * 8 / 1000} deliveredKbps=${delivered * 8 / 1000} downloads=$downloads " +
            "pieceMb=${pieceBytes / MB} pieces=${a.pieces}"
    }

    /** Memory the downloads and their pieces may hold together ([lentBytes]); 0 leaves the piece size alone. */
    @Volatile
    var pieceBudgetBytes: Long = 0L

    /** The most the read-ahead may hold on this box, for [heldBytes]. */
    @Volatile
    var readAheadMaxBytes: Long = 0L

    /** The last piece size change, or a held one, for the log; null otherwise. */
    var lastPieceChange: String? = null
        private set

    /** The piece size [measuredPerDownloadBps] was carried on. */
    var measuredPieceBytes: Long = 0L
        private set

    /** Downloads the server's shared request budget leaves playback now (thumbnails take the rest). */
    @Volatile
    var roomCap: Int = Int.MAX_VALUE

    /**
     * The most requests this server takes at once before it answers "too many requests" ([HostSpeeds.limitOf]),
     * [Int.MAX_VALUE] while none was seen. Downloads and refills never go above it.
     */
    @Volatile
    var requestLimit: Int = Int.MAX_VALUE

    /** No server history: a refill climbs [BOOST_STEP] requests at a time instead of going straight to the most. */
    @Volatile
    var stepBoost: Boolean = false

    @Volatile
    private var refusedLimit = Int.MAX_VALUE

    private var rateLimitedAtMs = Long.MIN_VALUE / 2

    /**
     * The server answered "too many requests" ([limit] = requests it is now held to). Applied at the next sample; the
     * back-off that follows is not taken for a line too slow for the film.
     */
    fun onRateLimited(limit: Int) {
        val l = limit.coerceAtLeast(1)
        if (l < requestLimit) requestLimit = l
        if (l < refusedLimit) refusedLimit = l
    }

    /** The requests to hold a server to after it refused [inFlight] at once: the count that worked, the usual start at least. */
    fun limitAfterRefusal(inFlight: Int, fileBitrate: Long = lastFileBitrate): Int {
        val usual = startDownloads(fileBitrate, maxDownloads, pieceBytes, smallBox, knownPerDownloadBps)
        return maxOf(inFlight - 1, usual, 1)
    }

    private var lastFileBitrate = 0L

    private fun rateLimited(nowMs: Long): Boolean = nowMs - rateLimitedAtMs < RATE_LIMIT_QUIET_MS

    /** True when the downloads were lowered to the server's limit. */
    private fun applyRefusal(s: Sample): Boolean {
        val limit = refusedLimit
        if (limit == Int.MAX_VALUE) return false
        refusedLimit = Int.MAX_VALUE
        rateLimitedAtMs = s.nowMs
        boostTo = minOf(boostTo, limit)
        if (downloads <= limit) {
            lastChange = "too many requests limit=$limit downloads=$downloads"
            return false
        }
        trialStartMs = -1L
        if (boostBase > limit) boostBase = limit
        change(limit, s.nowMs, "too many requests limit=$limit")
        return true
    }

    /** The most downloads a try kept for this film. */
    var peakKept: Int = 1
        private set

    /** What one download carried in the last kept try of this film, 0 while nothing was kept. */
    val measuredPerDownloadBps: Long get() = perDownloadBps

    /**
     * One player sample. [bytes] (received from the network) and [rebuffers] are running totals for the stream.
     * [bufferFull] = the buffer holds all the memory it may, so a flat buffer says nothing about the network.
     * [roomBytes] = free memory above the box's low-memory line and margin; below zero the box is short.
     * [idleBytes] = memory the buffer holds but does not use; adding a download shrinks the buffer and frees it.
     * [userBudget] = the user set the buffer's memory target: the downloads are lent out of it, so free memory is not
     * checked to add one or hand one back; only [memoryShort] and [swapLow] still stop them.
     * [swapLow] = the box's swap is nearly used up: no download is added, none is handed back for it.
     * [fileBitrate] = the film's average bitrate in bits per second from its size and length, 0 while unknown.
     * [maxBufferMs] = the most buffer ahead the player loads (the user's Max buffer), 0 while unknown.
     * [requestMs] = the running total of request-milliseconds of the pieces really fetched ([RequestMeter]), -1 when
     * not counted; [requests] = the requests running now, -1 when not counted.
     * [playerBytes] = bytes the player has read so far and [bufferedMs] = the media position buffered to, for the
     * film's recent rate ([FilmRate]); -1 when unknown. [bufferTargetMs] = the seconds of this film the box's buffer may
     * hold (the Max buffer, or less where memory is the limit), 0 when unknown: no refill boost without it.
     * [seekAtMs] = when the user last sought (this clock), 0 for never.
     */
    data class Sample(
        val nowMs: Long,
        val aheadMs: Long,
        val loading: Boolean,
        val rebuffers: Int,
        val bytes: Long,
        val memoryShort: Boolean = false,
        val playing: Boolean = true,
        val bufferFull: Boolean = false,
        val multiActive: Boolean = true,
        val roomBytes: Long = Long.MAX_VALUE,
        val swapLow: Boolean = false,
        val fileBitrate: Long = 0L,
        val idleBytes: Long = 0L,
        val userBudget: Boolean = false,
        val maxBufferMs: Long = 0L,
        val requestMs: Long = -1L,
        val requests: Int = -1,
        val playerBytes: Long = -1L,
        val bufferedMs: Long = -1L,
        val bufferTargetMs: Long = 0L,
        val seekAtMs: Long = 0L
    )

    @Volatile
    var downloads: Int = 1
        private set

    /** What the last change was, or that a wanted one was held back, for the log; null otherwise. */
    var lastChange: String? = null
        private set

    private val samples = ArrayDeque<Sample>()
    private var trialStartMs = -1L
    private var trialFromMs = -1L
    private var trialLengthMs = TRIAL_MS
    private var trialSettleMs = TRIAL_SETTLE_MS
    private var trialNeedsSwitch = false
    private var trialBaselineBps = 0L
    private var trialBackTo = 1
    private var trialNeedRate = 0L
    private var triedAny = false
    private var startJudged = false
    private var firstSampleMs = -1L
    private var nextStepAtMs = 0L
    private var blockedUntilMs = 0L
    private var comfortableSinceMs = -1L
    private var lastDropAtMs = Long.MIN_VALUE / 2
    private var keepForFilm = false
    private var singleRateBps = 0L
    private var trialHeavy = false
    private var presetPending = false
    private var startGuessBps = 0L

    /**
     * The start is set again (the real server behind the link turned out to be known) while it is still the first
     * judged start: true when it changed.
     */
    fun restartStart(start: Int): Boolean {
        val stillFirst = presetPending || (trialHeavy && trialStartMs >= 0L && peakKept <= 1)
        if (!stillFirst) return false
        val to = start.coerceIn(1, minOf(maxDownloads, roomCap, requestLimit))
        if (to == downloads) return false
        downloads = to
        presetPending = true
        triedAny = true
        return true
    }

    /**
     * Starts the stream on [start] downloads before the first byte, judged on the first samples like a remux start.
     * [guessedBps] = the bitrate the start was counted from; once the film's real one is known and higher, the start
     * is counted again ([recountStart]).
     */
    fun preset(start: Int, guessedBps: Long = 0L) {
        if (start <= 1) return
        downloads = start.coerceAtMost(minOf(maxDownloads, requestLimit))
        presetPending = true
        triedAny = true
        startGuessBps = guessedBps
    }

    /**
     * A start counted from a guessed bitrate is counted again, once, when the server's size gives the real one: raised
     * when the film is heavier than guessed (within free memory and the server's budget), never lowered. Only while no
     * other rule has changed the count since.
     */
    private fun recountStart(s: Sample) {
        val guess = startGuessBps
        if (guess <= 0L || s.fileBitrate <= 0L) return
        startGuessBps = 0L
        if (s.fileBitrate <= guess || s.swapLow) return
        var to = minOf(startFor(s.fileBitrate), roomCap, requestLimit)
        while (!s.userBudget && to > downloads && s.roomBytes < lentBytes(to, pieceBytes)) to--
        if (to <= downloads) return
        if (trialStartMs >= 0L) {
            if (trialFromMs >= 0L) trialFromMs = s.nowMs
            trialNeedRate = s.fileBitrate / 8
        }
        change(to, s.nowMs, "start recounted fileKbps=${s.fileBitrate / 1000} guessedKbps=${guess / 1000}")
    }
    private var perDownloadBps = 0L
    private var heldForSwap = false
    private var heldFor: String? = null

    private val filmRate = FilmRate()

    /** The film's rate over its last 30 to 60 s of media in bits per second, 0 until known. */
    val recentFilmBps: Long get() = filmRate.bps()

    /**
     * What the downloads should carry together now, in bytes per second: [TARGET_PERCENT] % of the film's average, or
     * [RECENT_TARGET_PERCENT] % of what its recent scenes ran at when that is more.
     */
    fun targetNow(fileBitrate: Long): Long = maxOf(targetBps(fileBitrate), recentTarget())

    private fun recentTarget(): Long = recentFilmBps / 8 * RECENT_TARGET_PERCENT / 100

    private var boostBase = 0
    private var boostAtMs = -1L
    private var boostAheadAtMs = 0L
    private var boostBlockedUntilMs = 0L
    private var boostEndedAtMs = Long.MIN_VALUE / 2
    private var boostEndedBase = 0
    private var aboveLow = false
    private var seekSeenMs = 0L
    private var refillFromMs = -1L
    private var refillWhy = ""
    private var refillRequestMs = -1L
    private var refillCheckAtMs = -1L

    /** Downloads beyond what the film ran on are only lent for a refill: the count it goes back to, 0 while none. */
    val boostedFrom: Int get() = boostBase

    /** The last refill finished (or given up) at this sample, for the log; null otherwise. */
    var lastRefill: String? = null
        private set

    /** A refill boost left out at this sample and why, for the log; null otherwise. */
    var lastBoostNote: String? = null
        private set

    fun onSample(s: Sample): Int {
        if (s.nowMs > latestMs) latestMs = s.nowMs
        val result = decide(s)
        followPiece(s)
        return result
    }

    /**
     * The downloads a film starts on: never fewer than [startDownloads], raised when [planPieces] (first byte, rate and
     * [fileBitrate] known) wants more. The piece size is set for that count within [pieceBudgetBytes]; the plan's fewer
     * downloads come later, as a comfortable buffer hands downloads back.
     */
    fun startFor(fileBitrate: Long): Int {
        val plan = planFor(fileBitrate, minOf(maxDownloads, roomCap))
            ?: return startDownloads(fileBitrate, maxDownloads, pieceBytes, smallBox, knownPerDownloadBps)
        val usual = startDownloads(fileBitrate, maxDownloads, plan.pieceBytes, smallBox, knownPerDownloadBps)
        val n = maxOf(usual, plan.downloads)
        val raw = rawRateNow()
        pieceBytes = atLeastPreferred(n, if (n == plan.downloads || raw == null) plan.pieceBytes
            else pieceForDownloads(n, waitMs(), raw, targetNow(fileBitrate), pieceBudgetBytes) ?: plan.pieceBytes)
        return n
    }

    /**
     * The piece a film starts on with [downloads]: the size this server's films needed before ([knownStartPieceBytes])
     * when that is bigger than the plan's and fits [pieceBudgetBytes], so the next film skips the climb the last one made.
     */
    fun rememberedStartPiece(downloads: Int): Long {
        val known = knownStartPieceBytes
        val piece = pieceBytes
        if (pieceBudgetBytes <= 0L || known !in PIECE_STEPS || known <= piece) return atLeastPreferred(downloads, piece)
        return atLeastPreferred(downloads, maxOf(piece, fittingPiece(downloads, known, pieceBudgetBytes)))
    }

    /**
     * The piece size to remember for the server at the film's end: the one it ran on ([active]), or the smaller one
     * the rule takes there for the downloads it ran on, so a size the film no longer needed is not carried forward.
     */
    fun pieceToRemember(active: Long): Long {
        val raw = measuredRawBps() ?: rawRateNow() ?: return active
        val n = if (boostBase > 0) boostBase else downloads
        val rule = pieceForDownloads(n, waitMs(), raw, targetNow(lastFileBitrate), pieceBudgetBytes) ?: return active
        return minOf(active, rule)
    }

    /** The plan for this film from what is known about the server, null while the first byte or the rate is not. */
    fun planFor(fileBitrate: Long, most: Int = maxDownloads): PieceChoice? {
        val raw = rawRateNow() ?: return null
        if (fileBitrate <= 0L) return null
        return planPieces(waitMs(), raw, targetNow(fileBitrate), pieceBudgetBytes, most)
    }

    /** One download's rate with the first-byte waits taken out of what it delivered; null when unknown. */
    private fun rateFrom(deliveredBps: Long, atPiece: Long): Long? {
        val wait = waitMs()
        if (wait < 0L || deliveredBps <= 0L) return null
        return rawRate(deliveredBps, if (atPiece > 0L) atPiece else pieceBytes, wait)
    }

    /** One download's rate while data flows: timed on the pieces when they were, else worked back from the delivered. */
    fun rawRateNow(): Long? = measuredRawBps() ?:
        if (perDownloadBps > 0L) rateFrom(perDownloadBps, measuredPieceBytes)
        else rateFrom(knownPerDownloadBps, knownPieceBytes)

    private val sizer = PieceSizer()

    /** The piece size between pieces, see [PieceSizer]. */
    private fun followPiece(s: Sample) {
        lastPieceChange = null
        val budget = pieceBudgetBytes
        val piece = pieceBytes
        if (budget <= 0L || piece <= 0L || downloads <= 1) return
        val raw = if (s.memoryShort || trialStartMs >= 0L) null else measuredRawBps() ?: liveRawRate(s) ?: rawRateNow()
        val step = sizer.next(s.nowMs, piece, downloads, raw, waitMs(), targetNow(s.fileBitrate), budget,
            if (s.userBudget) Long.MAX_VALUE else s.roomBytes, readAheadMaxBytes) ?: return
        lastPieceChange = if (step.held) "piece held ${piece / MB}->${step.to / MB}MB ${step.why}"
            else "piece ${piece / MB}->${step.to / MB}MB ${step.why}"
        if (!step.held) pieceBytes = atLeastPreferred(downloads, step.to)
    }

    /** What the downloads carry now while the loader never rests: the network is the limit then. */
    private fun liveRawRate(s: Sample): Long? {
        if (!s.multiActive || downloads <= 1) return null
        val from = s.nowMs - STARVE_WINDOW_MS
        if (samples.first().nowMs > from || loadingShare(from, s.nowMs) < LOADING_PASS_PERCENT) return null
        if (samples.any { it.nowMs >= from && it.bufferFull }) return null
        val rate = rateBps(from, s.nowMs)
        return if (rate > 0L) rateFrom(perRequestBps(rate, from, s.nowMs), pieceBytes) else null
    }

    /**
     * What one request carried of [bps] between [fromMs] and [toMs]: shared by the requests that really ran then
     * (the read-ahead's pieces too), else by the downloads when they were not counted.
     */
    private fun perRequestBps(bps: Long, fromMs: Long, toMs: Long): Long {
        val running = requestsBetween(fromMs, toMs)
        return if (running != null && running >= 1.0) (bps / running).toLong() else bps / downloads.coerceAtLeast(1)
    }

    /** The requests running on average between [fromMs] and [toMs], null when they were not counted. */
    fun requestsBetween(fromMs: Long, toMs: Long): Double? {
        val a = samples.lastOrNull { it.nowMs <= fromMs } ?: samples.firstOrNull() ?: return null
        val b = samples.lastOrNull { it.nowMs <= toMs } ?: return null
        val ms = b.nowMs - a.nowMs
        if (ms <= 0L || a.requestMs < 0L || b.requestMs < a.requestMs) return null
        return (b.requestMs - a.requestMs).toDouble() / ms
    }

    private fun decide(s: Sample): Int {
        lastChange = null
        // A seek empties the buffer at once; what came before says nothing about the new position.
        val previous = samples.lastOrNull()
        val dropped = previous != null && s.aheadMs < previous.aheadMs - SEEK_DROP_MS
        if (dropped) samples.clear()
        samples.addLast(s)
        if (dropped) filmRate.clear()
        filmRate.note(s.playerBytes, s.bufferedMs)
        while (samples.size > 1 && s.nowMs - samples.first().nowMs > HISTORY_MS) samples.removeFirst()
        if (!s.swapLow) heldForSwap = false
        if (firstSampleMs < 0L) firstSampleMs = s.nowMs
        if (s.fileBitrate > 0L) lastFileBitrate = s.fileBitrate
        if (applyRefusal(s)) return downloads

        if (s.memoryShort) {
            // One at a time: the read-ahead has already gone, and dropping straight to one stalls a remux.
            if (downloads > 1 && s.nowMs >= nextStepAtMs) change(downloads - 1, s.nowMs, "memory short")
            trialStartMs = -1L
            blockedUntilMs = s.nowMs + MEMORY_BLOCK_MS
            return downloads
        }

        if (!s.userBudget && s.roomBytes < 0L && downloads > 1) {
            // Below the margin but above Android's line: never under what the film needs; past the line is memoryShort.
            if (s.nowMs >= nextStepAtMs && downloads > comfortableFloor(s)) {
                trialStartMs = -1L
                blockedUntilMs = maxOf(blockedUntilMs, s.nowMs + MEMORY_BLOCK_MS)
                change(downloads - 1, s.nowMs, "memory low roomMb=${s.roomBytes / MB}")
            }
            return downloads
        }

        recountStart(s)

        if (refill(s, previous, dropped)) return downloads

        if (presetPending) {
            presetPending = false
            trialBaselineBps = 0L
            trialStartMs = s.nowMs
            trialFromMs = s.nowMs
            trialNeedsSwitch = false
            trialBackTo = 1
            trialNeedRate = s.fileBitrate / 8
            trialHeavy = true
            trialLengthMs = TRIAL_MS
            return downloads
        }

        if (trialStartMs >= 0L) {
            if (trialFromMs < 0L) {
                // The extra download only counts from the moment the stream is actually on the chunk session.
                if (!trialNeedsSwitch || s.multiActive) {
                    trialFromMs = s.nowMs
                } else if (s.nowMs - trialStartMs >= SWITCH_WAIT_MS) {
                    trialStartMs = -1L
                    blockedUntilMs = s.nowMs + FAILED_BLOCK_MS
                    change(trialBackTo, s.nowMs, "never switched")
                }
            } else if (s.nowMs - trialFromMs >= trialLengthMs) {
                finishTrial(s)
            }
            return downloads
        }

        if (!triedAny && !startJudged && s.fileBitrate > 0L && downloads == 1 && s.nowMs >= blockedUntilMs && !s.swapLow) {
            var start = startFor(s.fileBitrate)
            while (!s.userBudget && start > 1 && s.roomBytes < lentBytes(start, pieceBytes)) start--
            if (start > 1 && (s.fileBitrate >= START_THREE_BPS || knownPerDownloadBps > 0L)) {
                // One download cannot carry a remux on a TV box; measuring it first only costs the buffer a stop.
                startTrial(s, start, TRIAL_MS, "start heavy fileKbps=${s.fileBitrate / 1000}" + budgetTag(s))
                trialNeedRate = s.fileBitrate / 8
                trialHeavy = true
                return downloads
            }
            if (start > 1 && singleMeasured(s)) {
                val singleBps = rateBps(s.nowMs - START_MEASURE_MS, s.nowMs)
                val loadingPercent = loadingShare(s.nowMs - START_MEASURE_MS, s.nowMs)
                val needBps = s.fileBitrate / 8
                val detail = "fileKbps=${s.fileBitrate / 1000} singleKbps=${singleBps * 8 / 1000}"
                if (loadingPercent < LOADING_PASS_PERCENT || singleBps * 100 >= needBps * START_SINGLE_ENOUGH_PERCENT) {
                    startJudged = true
                    lastChange = "start not needed $detail loading=$loadingPercent%"
                } else {
                    singleRateBps = singleBps
                    startTrial(s, start, TRIAL_MS, "start $detail" + budgetTag(s))
                    trialNeedRate = needBps
                    return downloads
                }
            }
        }

        // A buffer falling while the server makes requests wait after "too many requests" is not a slow line.
        val starving = !rateLimited(s.nowMs) && starving(s)
        if (starving) {
            comfortableSinceMs = -1L
            // Short again after handing one back: straight back to what this film needed, not one at a time.
            val ceiling = minOf(maxDownloads, roomCap, requestLimit)
            val target = if (keepForFilm && peakKept > downloads + 1) peakKept.coerceAtMost(ceiling) else downloads + 1
            val needBytes = lentBytes(target, pieceBytes) - lentBytes(downloads, pieceBytes)
            val idle = s.idleBytes.coerceAtLeast(0L)
            val usable = if (s.userBudget || s.roomBytes > Long.MAX_VALUE - idle) Long.MAX_VALUE else s.roomBytes + idle
            val wanted = downloads < ceiling && s.nowMs >= nextStepAtMs
            val blocked = s.nowMs < blockedUntilMs
            val mayAdd = wanted && !blocked && usable >= needBytes
            if (wanted && !mayAdd) {
                val state = "$downloads " + if (blocked) "blocked" else "room"
                if (state != heldFor) {
                    heldFor = state
                    lastChange = "held downloads=$downloads roomMb=${s.roomBytes / MB} idleMb=${idle / MB} " +
                        "needMb=${needBytes / MB} blockedS=${(blockedUntilMs - s.nowMs).coerceAtLeast(0L) / 1000} " +
                        "userBudget=${s.userBudget}"
                }
            }
            if (mayAdd && s.swapLow) {
                if (!heldForSwap) lastChange = "held downloads=$downloads swap low"
                heldForSwap = true
                return downloads
            }
            if (mayAdd) {
                val baseline = rateBps(s.nowMs - STARVE_WINDOW_MS, s.nowMs)
                val early = !triedAny && s.nowMs - firstSampleMs < EARLY_MS && s.aheadMs < EARLY_LOW_AHEAD_MS
                val length = if (early) EARLY_TRIAL_MS else trialLength(baseline)
                if (s.nowMs - lastDropAtMs < KEEP_AFTER_DROP_MS) keepForFilm = true
                if (downloads == 1 && baseline > 0L) singleRateBps = baseline
                startTrial(s, target, length,
                    "trial baselineKbps=${baseline * 8 / 1000}" + (if (early) " early" else "") + budgetTag(s))
            }
            return downloads
        }

        if (boostBase > 0) {
            if (downloads <= boostBase) {
                endBoost(s.nowMs)
            } else if (comfortable(s)) {
                if (comfortableSinceMs < 0L) comfortableSinceMs = s.nowMs
                if (s.nowMs - comfortableSinceMs >= COMFORTABLE_MS) {
                    comfortableSinceMs = s.nowMs
                    val to = boostBase
                    endBoost(s.nowMs)
                    change(to, s.nowMs, "comfortable after the refill")
                }
            } else {
                comfortableSinceMs = -1L
            }
            return downloads
        }

        if (downloads > comfortableFloor(s) && !keepForFilm && comfortable(s) && recentCarriedBy(downloads - 1)) {
            if (comfortableSinceMs < 0L) comfortableSinceMs = s.nowMs
            if (s.nowMs - comfortableSinceMs >= COMFORTABLE_MS) {
                lastDropAtMs = s.nowMs
                comfortableSinceMs = s.nowMs
                change(downloads - 1, s.nowMs, "comfortable")
            }
        } else {
            comfortableSinceMs = -1L
        }
        return downloads
    }

    /**
     * Refill boost. After a long seek, a stall, or the buffer falling under its low mark ([lowMarkMs]) the film goes
     * at once to the most requests the server's budget and memory allow, or as many as regain the buffer within
     * [REFILL_WANTED_MS] at the rate one request carries, without a try first. Judged after [BOOST_JUDGE_MS]: not grown
     * by then, the line is the limit and the count goes back. Handed back to the count from before once comfortable for
     * [COMFORTABLE_MS]; a buffer falling short again within [KEEP_AFTER_DROP_MS] of that keeps one more. Also times
     * each refill for the log. True when the count changed.
     */
    private fun refill(s: Sample, previous: Sample?, dropped: Boolean): Boolean {
        lastRefill = null
        lastBoostNote = null
        val target = s.bufferTargetMs
        if (target <= 0L) return false
        val low = lowMarkMs(target)
        val goal = refillGoalMs(target)
        val firstLook = previous == null && seekSeenMs == 0L
        val newSeek = s.seekAtMs > seekSeenMs && !firstLook
        if (s.seekAtMs > seekSeenMs) seekSeenMs = s.seekAtMs
        val seeked = newSeek || dropped
        val stalled = previous != null && s.rebuffers > previous.rebuffers
        if (seeked) aboveLow = false
        val dip = aboveLow && s.playing && s.loading && !s.bufferFull && s.aheadMs < low
        if (s.aheadMs >= low) aboveLow = true

        val why = when {
            stalled -> "stall"
            seeked && s.aheadMs < low -> "seek"
            dip -> "dip"
            else -> null
        }
        if (seeked || stalled || (why != null && refillFromMs < 0L)) {
            refillFromMs = s.nowMs
            refillWhy = why ?: "short seek"
            refillRequestMs = s.requestMs
            refillCheckAtMs = s.nowMs + REFILL_CHECK_MS
        }
        if (refillFromMs >= 0L && refillFromMs != s.nowMs) {
            val took = s.nowMs - refillFromMs
            val refilled = s.aheadMs >= goal || s.bufferFull || (!s.loading && s.playing && s.aheadMs >= low)
            if (refilled || took >= REFILL_GIVE_UP_MS) {
                val running = if (refillRequestMs >= 0L && s.requestMs >= refillRequestMs) {
                    " requests=" + "%.1f".format(java.util.Locale.ROOT, (s.requestMs - refillRequestMs).toDouble() / took)
                } else ""
                lastRefill = "refill $refillWhy " + (if (refilled) "tookMs=$took" else "unfinished afterMs=$took") +
                    " aheadMs=${s.aheadMs} goalMs=$goal downloads=$downloads$running"
                refillFromMs = -1L
            }
        }

        val backingOff = rateLimited(s.nowMs)
        // The back-off holds pieces up: a boost is not judged, nor stepped up, meanwhile.
        if (backingOff && boostAtMs >= 0L) boostAtMs = s.nowMs
        if (!backingOff && boostBase > 0 && downloads < boostTo && s.nowMs - boostStepAtMs >= BOOST_STEP_MS &&
            s.aheadMs <= boostStepAheadMs && !s.swapLow) {
            val to = minOf(downloads + BOOST_STEP, boostTo)
            boostStepAtMs = s.nowMs
            boostStepAheadMs = s.aheadMs
            boostAtMs = s.nowMs
            boostAheadAtMs = s.aheadMs
            change(to, s.nowMs, "boost step to=$boostTo aheadMs=${s.aheadMs}")
            return true
        }
        if (boostBase > 0 && boostAtMs >= 0L && s.nowMs - boostAtMs >= BOOST_JUDGE_MS) {
            boostAtMs = -1L
            val grew = s.aheadMs - boostAheadAtMs >= GROWTH_PASS_MS || s.aheadMs >= low
            if (!grew) {
                val to = boostBase
                endBoost(s.nowMs)
                boostBlockedUntilMs = s.nowMs + FAILED_BLOCK_MS
                change(to, s.nowMs, "boost no gain aheadMs=${s.aheadMs}")
                return true
            }
        }

        // The rate per request a boost is sized from can be stale (a quick server answering slowly after a seek):
        // still under the low mark a few seconds into the refill, the boost goes ahead whatever it said.
        if (refillCheckAtMs in 0L..s.nowMs) {
            refillCheckAtMs = -1L
            if (boostBase == 0 && !backingOff && s.aheadMs < low && !s.swapLow && s.nowMs >= boostBlockedUntilMs) {
                val most = boostCount(s, goal, sized = false)
                if (most > downloads) return startBoost(s, "check", most, low, goal)
            }
        }
        if (why == null || s.swapLow || s.nowMs < boostBlockedUntilMs) return false
        val most = boostCount(s, goal)
        if (most <= downloads) {
            lastBoostNote = boostSkipped(s, why, goal)
            return false
        }
        return startBoost(s, why, most, low, goal)
    }

    private fun boostSkipped(s: Sample, why: String, goalMs: Long): String {
        val film = maxOf(s.fileBitrate, recentFilmBps) / 8
        val raw = rawRateNow()
        val carry = raw?.let { effectiveBps(downloads, pieceBytes, waitMs(), it) * 8 / 1000 } ?: -1L
        return "boost $why not needed downloads=$downloads carryKbps=$carry " +
            "wantedKbps=${refillRate(film, s.aheadMs, goalMs) * 8 / 1000} waitMs=${waitMs()} aheadMs=${s.aheadMs}"
    }

    private fun startBoost(s: Sample, why: String, most: Int, low: Long, goal: Long): Boolean {
        refillCheckAtMs = -1L
        boostTo = most
        boostStepAtMs = s.nowMs
        boostStepAheadMs = s.aheadMs
        val to = if (stepBoost) minOf(downloads + BOOST_STEP, most) else most
        var base = if (boostBase > 0) boostBase else downloads
        if (why == "dip" && boostBase == 0 && s.nowMs - boostEndedAtMs < KEEP_AFTER_DROP_MS) {
            base = maxOf(base, minOf(boostEndedBase + 1, to))
        }
        trialStartMs = -1L
        trialHeavy = false
        comfortableSinceMs = -1L
        boostBase = base.coerceAtLeast(1)
        boostAtMs = s.nowMs
        boostAheadAtMs = s.aheadMs
        change(to, s.nowMs, "boost $why base=$boostBase lowMs=$low goalMs=$goal" +
            (if (to < most) " to=$most" else "") + budgetTag(s))
        return true
    }

    private var boostTo = 0
    private var boostStepAtMs = 0L
    private var boostStepAheadMs = 0L

    private fun endBoost(nowMs: Long) {
        boostTo = 0
        boostEndedAtMs = nowMs
        boostEndedBase = boostBase
        boostBase = 0
        boostAtMs = -1L
    }

    /**
     * Requests for a refill: the most the server's budget ([roomCap], never above [AutoSpeedup.MAX_IN_FLIGHT]), the
     * piece budget and free memory allow, fewer when the rate one request carries already regains [goalMs] of buffer
     * within [REFILL_WANTED_MS]; never fewer than now.
     */
    private fun boostCount(s: Sample, goalMs: Long, sized: Boolean = true): Int {
        var n = minOf(maxDownloads, roomCap, AutoSpeedup.MAX_IN_FLIGHT, requestLimit)
        if (pieceBudgetBytes > 0L && pieceBytes > 0L) {
            while (n > downloads && lentBytes(n, pieceBytes) > pieceBudgetBytes) n--
        }
        if (!s.userBudget) {
            val idle = s.idleBytes.coerceAtLeast(0L)
            val usable = if (s.roomBytes > Long.MAX_VALUE - idle) Long.MAX_VALUE else s.roomBytes + idle
            while (n > downloads && lentBytes(n, pieceBytes) - lentBytes(downloads, pieceBytes) > usable) n--
        }
        val film = maxOf(s.fileBitrate, recentFilmBps) / 8
        val raw = rawRateNow()
        if (sized && raw != null && film > 0L && n > downloads) {
            val wanted = refillRate(film, s.aheadMs, goalMs)
            val need = (downloads..n).firstOrNull { effectiveBps(it, pieceBytes, waitMs(), raw) >= wanted } ?: n
            n = minOf(n, need)
        }
        return n.coerceAtLeast(downloads)
    }

    /** One download fewer still carries the film's recent scenes with margin, or nothing says otherwise. */
    private fun recentCarriedBy(n: Int): Boolean {
        val recent = recentTarget()
        if (recent <= 0L || n < 1) return true
        val raw = rawRateNow() ?: return true
        return effectiveBps(n, pieceBytes, waitMs(), raw) >= recent
    }

    private fun startTrial(s: Sample, to: Int, lengthMs: Long, why: String) {
        val steady = lengthMs >= STEADY_TRIAL_MS
        trialBaselineBps = if (steady) medianRateBps(s.nowMs - STARVE_WINDOW_MS, s.nowMs)
            else rateBps(s.nowMs - STARVE_WINDOW_MS, s.nowMs)
        // The new download's first piece waits for its first byte; it is not counted.
        val perRequest = if (trialBaselineBps > 0L) trialBaselineBps / downloads.coerceAtLeast(1) else 0L
        trialSettleMs = if (!steady || perRequest <= 0L || pieceBytes <= 0L) TRIAL_SETTLE_MS
            else (pieceBytes * 1000L / perRequest).coerceIn(TRIAL_SETTLE_MS, MAX_TRIAL_SETTLE_MS)
        trialStartMs = s.nowMs
        trialFromMs = -1L
        trialNeedsSwitch = downloads == 1
        trialBackTo = downloads
        trialNeedRate = 0L
        trialHeavy = false
        trialLengthMs = lengthMs
        triedAny = true
        change(to, s.nowMs, why)
    }

    private fun budgetTag(s: Sample): String = if (s.userBudget) " userBudget=true" else ""

    /**
     * At least [STEADY_TRIAL_MS], and long enough for two of the new download's pieces at the old rate, so a try is
     * not judged on one or two pieces whose rates spread widely.
     */
    private fun trialLength(baselineBps: Long): Long {
        if (pieceBytes <= 0L || baselineBps <= 0L) return STEADY_TRIAL_MS
        return (TRIAL_SETTLE_MS + 2 * pieceBytes * 1000L / baselineBps).coerceIn(STEADY_TRIAL_MS, MAX_TRIAL_MS)
    }

    /** The median of the rates of each [RATE_STEP_MS] between [fromMs] and [toMs]: one quick or slow piece moves it little. */
    private fun medianRateBps(fromMs: Long, toMs: Long): Long {
        val rates = ArrayList<Long>()
        var a = fromMs
        while (a + RATE_STEP_MS <= toMs) {
            rates += rateBps(a, a + RATE_STEP_MS)
            a += RATE_STEP_MS
        }
        if (rates.size < 3) return rateBps(fromMs, toMs)
        rates.sort()
        return if (rates.size % 2 == 1) rates[rates.size / 2] else (rates[rates.size / 2 - 1] + rates[rates.size / 2]) / 2
    }

    private fun finishTrial(s: Sample) {
        val from = trialFromMs + trialSettleMs
        val trialBps = if (trialLengthMs >= STEADY_TRIAL_MS) medianRateBps(from, s.nowMs) else rateBps(from, s.nowMs)
        val playedThrough = samples.filter { it.nowMs >= from }.all { it.playing }
        val grew = playedThrough && s.aheadMs - aheadAt(from) >= GROWTH_PASS_MS
        // A resting loader only vouches for a try that is not slower than before it.
        val waited = loadingShare(from, s.nowMs) < LOADING_PASS_PERCENT &&
            (trialBaselineBps <= 0L || trialBps >= trialBaselineBps)
        val faster = if (trialBaselineBps > 0L) {
            trialBps * 100 >= trialBaselineBps * (100 + GAIN_PERCENT)
        } else {
            trialNeedRate > 0L && trialBps >= trialNeedRate
        }
        trialStartMs = -1L
        val running = requestsBetween(from, s.nowMs)?.let { " requests=${"%.1f".format(java.util.Locale.ROOT, it)}" } ?: ""
        val detail = "kbps=${trialBps * 8 / 1000} baselineKbps=${trialBaselineBps * 8 / 1000}$running"
        val heavy = trialHeavy
        trialHeavy = false
        // A remux start stays: one download was never measured, and the memory and comfort rules still hand back.
        if (faster || grew || waited || heavy) {
            if (downloads > 1 && trialBps > 0L) {
                perDownloadBps = perRequestBps(trialBps, from, s.nowMs)
                measuredPieceBytes = pieceBytes
            }
            peakKept = maxOf(peakKept, downloads)
            nextStepAtMs = s.nowMs + STEP_GAP_MS
            lastChange = "kept downloads=$downloads $detail grew=$grew waited=$waited"
        } else {
            blockedUntilMs = s.nowMs + if (trialNeedRate > 0L) STEP_GAP_MS else FAILED_BLOCK_MS
            change(trialBackTo, s.nowMs, "no gain $detail")
        }
    }

    private fun change(to: Int, nowMs: Long, why: String) {
        lastChange = "downloads ${downloads}->$to $why"
        heldFor = null
        startGuessBps = 0L
        downloads = to.coerceIn(1, maxDownloads.coerceAtLeast(1))
        nextStepAtMs = maxOf(nextStepAtMs, nowMs + STEP_GAP_MS)
    }

    /** Short of buffer and not gaining while the player loads all the time, or a rebuffer in the window. */
    private fun starving(s: Sample): Boolean {
        val start = s.nowMs - STARVE_WINDOW_MS
        val oldest = samples.lastOrNull { it.nowMs <= start } ?: return false
        if (s.rebuffers > oldest.rebuffers) return true
        if (s.aheadMs >= STARVE_AHEAD_MS || samples.any { it.nowMs >= start && it.bufferFull }) return false
        if (samples.any { it.nowMs >= start && !it.loading }) return false
        return s.aheadMs - oldest.aheadMs <= GROWTH_STARVE_MS
    }

    /**
     * A long buffer, or a resting loader with 10 s ahead or with the buffer as full as it can get: at the user's Max
     * buffer, or at the memory it may hold. Handing a download back then gives its memory to the buffer; if the film
     * runs short again a download comes back and stays for the film.
     */
    private fun comfortable(s: Sample): Boolean {
        if (s.aheadMs >= COMFORTABLE_AHEAD_MS) return true
        val from = s.nowMs - STARVE_WINDOW_MS
        if (loadingShare(from, s.nowMs) >= LOADING_PASS_PERCENT) return false
        if (s.aheadMs >= COMFORTABLE_IDLE_AHEAD_MS) return true
        return samples.any {
            it.nowMs >= from && (it.bufferFull || (it.maxBufferMs > 0L && it.aheadMs >= it.maxBufferMs - FULL_SLACK_MS))
        }
    }

    /**
     * The fewest downloads a comfortable buffer hands back to: 2 while one download, measured alone or as its share of
     * a kept try, carries under [START_SINGLE_ENOUGH_PERCENT] % of the film's rate, or nothing was measured for a
     * remux; else 1.
     */
    private fun comfortableFloor(s: Sample): Int {
        if (s.fileBitrate <= 0L) return 1
        val one = if (singleRateBps > 0L) singleRateBps else if (perDownloadBps > 0L) perDownloadBps else knownPerDownloadBps
        // Nothing measured yet: a remux is assumed too heavy for one download, as its start already assumed.
        if (one <= 0L) return if (s.fileBitrate >= START_THREE_BPS) 2 else 1
        return if (one * 100 < s.fileBitrate / 8 * START_SINGLE_ENOUGH_PERCENT) 2 else 1
    }

    /** What the downloads delivered while the player loaded ([bps], bits per second), or that the loader mostly rested. */
    data class Delivered(val bps: Long, val rested: Boolean)

    /**
     * What the downloads delivered over the last [PlaybackLine.DELIVERED_WINDOW_MS] while the player loaded, null
     * until [DELIVERED_MIN_MS] of samples since the start or the last seek. Rested when the loader ran less than
     * [DELIVERED_LOADING_PERCENT] % of that time: the line then carried more than the film needed.
     */
    fun deliveredNow(nowMs: Long): Delivered? {
        val first = samples.firstOrNull() ?: return null
        if (first.nowMs > nowMs - DELIVERED_MIN_MS) return null
        val from = nowMs - PlaybackLine.DELIVERED_WINDOW_MS
        var span = 0L
        var loadingMs = 0L
        var bytes = 0L
        var prev: Sample? = null
        for (b in samples) {
            val a = prev
            prev = b
            if (a == null || b.nowMs <= from || b.nowMs > nowMs) continue
            val ms = b.nowMs - maxOf(a.nowMs, from)
            if (ms <= 0L) continue
            span += ms
            if (a.loading && b.loading && !b.bufferFull) {
                loadingMs += b.nowMs - a.nowMs
                bytes += (b.bytes - a.bytes).coerceAtLeast(0L)
            }
        }
        if (span <= 0L) return null
        if (loadingMs * 100 < span * DELIVERED_LOADING_PERCENT) return Delivered(0L, rested = true)
        return Delivered(bytes * 8_000L / loadingMs, rested = false)
    }

    /** The requests the plan says this film needs: the fewest that carry its target, else the downloads now. */
    fun plannedRequests(fileBitrate: Long): Int =
        (planFor(fileBitrate, minOf(maxDownloads, roomCap, requestLimit))?.downloads ?: downloads).coerceAtLeast(1)

    /** When the server last answered "too many requests" to playback, on the samples' clock. */
    val refusedAtMs: Long get() = rateLimitedAtMs

    /** One download has run long enough, after the stream settled, to say what it carries. */
    private fun singleMeasured(s: Sample): Boolean =
        s.nowMs - firstSampleMs >= START_SETTLE_MS + START_MEASURE_MS &&
            samples.first().nowMs <= s.nowMs - START_MEASURE_MS

    private fun rateBps(fromMs: Long, toMs: Long): Long {
        val a = samples.lastOrNull { it.nowMs <= fromMs } ?: samples.firstOrNull() ?: return 0L
        val b = samples.lastOrNull { it.nowMs <= toMs } ?: return 0L
        val ms = b.nowMs - a.nowMs
        return if (ms <= 0L) 0L else (b.bytes - a.bytes).coerceAtLeast(0L) * 1000L / ms
    }

    private fun aheadAt(ms: Long): Long = (samples.lastOrNull { it.nowMs <= ms } ?: samples.first()).aheadMs

    private fun loadingShare(fromMs: Long, toMs: Long): Int {
        val inRange = samples.filter { it.nowMs in fromMs..toMs }
        if (inRange.isEmpty()) return 100
        return inRange.count { it.loading } * 100 / inRange.size
    }

    /** Downloads and piece size planned together; [effectiveBps] is what they carry with the first-byte waits. */
    data class PieceChoice(val downloads: Int, val pieceBytes: Long, val effectiveBps: Long)

    companion object {
        private const val MB = 1024L * 1024L

        /** The piece sizes Automatic chooses from. */
        val PIECE_STEPS = listOf(8L * MB, 12L * MB, 16L * MB, 24L * MB)

        /** The film's rate with headroom, in bytes per second: what the downloads should carry together. */
        fun targetBps(fileBitrate: Long): Long = if (fileBitrate > 0L) fileBitrate / 8 * TARGET_PERCENT / 100 else 0L

        /** The share of each piece spent waiting for the first byte, in percent. */
        fun waitPercent(firstByteMs: Long, pieceBytes: Long, rawBps: Long): Double {
            if (firstByteMs <= 0L || rawBps <= 0L) return 0.0
            val transferMs = pieceBytes * 1000.0 / rawBps
            return firstByteMs * 100.0 / (firstByteMs + transferMs)
        }

        /** What [downloads] carry together on [pieceBytes] pieces; one download is a single long request without waits. */
        fun effectiveBps(downloads: Int, pieceBytes: Long, firstByteMs: Long, rawBps: Long): Long =
            if (downloads <= 1) rawBps
            else (downloads * rawBps * (100.0 - waitPercent(firstByteMs, pieceBytes, rawBps)) / 100.0).toLong()

        /**
         * One download's rate while data flows, from what it delivered on [pieceBytes] pieces including the waits, for
         * when the pieces were not timed. The wait it is given may be longer than a piece request's (the start's, with
         * an add-on's redirect), so the wait taken out is never more than half of a piece's time: at most twice what
         * was delivered.
         */
        fun rawRate(deliveredBps: Long, pieceBytes: Long, firstByteMs: Long): Long {
            if (deliveredBps <= 0L || pieceBytes <= 0L || firstByteMs <= 0L) return deliveredBps
            val pieceMs = pieceBytes * 1000.0 / deliveredBps
            val transferMs = maxOf(pieceMs - firstByteMs, pieceMs / RAW_MOST_FACTOR)
            return (pieceBytes * 1000.0 / transferMs).toLong()
        }

        /**
         * What the chunk session can hold at most on [pieceBytes] pieces: the downloads plus the piece being read, or a
         * full read-ahead of whole pieces ([readAheadPieces] stops at [readAheadMaxBytes] in whole pieces) plus the piece
         * being read, whichever is more. Spare pooled buffers come on top.
         */
        fun heldBytes(downloads: Int, pieceBytes: Long, readAheadMaxBytes: Long): Long {
            if (pieceBytes <= 0L) return 0L
            val readAhead = if (readAheadMaxBytes > 0L) (readAheadMaxBytes / pieceBytes + 1) * pieceBytes else 0L
            return maxOf(lentBytes(downloads, pieceBytes), readAhead)
        }

        /** The biggest piece up to [current] for which [downloads] fit [budgetBytes]; the smallest size when none does. */
        fun fittingPiece(downloads: Int, current: Long, budgetBytes: Long): Long =
            PIECE_STEPS.lastOrNull { it <= current && lentBytes(downloads, it) <= budgetBytes } ?: PIECE_STEPS.first()

        /**
         * Downloads and piece size together within [budgetBytes] ([lentBytes]): the fewest downloads that carry
         * [targetBps] (fewer requests, more of the server's request budget left for thumbnails), on the smallest piece
         * that does (quicker start and seek); when none does, what carries the most. Null when the first byte, the rate
         * or the target is unknown.
         */
        fun planPieces(
            firstByteMs: Long,
            rawBps: Long,
            targetBps: Long,
            budgetBytes: Long,
            maxDownloads: Int
        ): PieceChoice? {
            if (firstByteMs < 0L || rawBps <= 0L || targetBps <= 0L || budgetBytes <= 0L) return null
            val single = PieceChoice(1, pieceForDownloads(2, firstByteMs, rawBps, targetBps, budgetBytes)
                ?: PIECE_STEPS.first(), rawBps)
            if (single.effectiveBps >= targetBps || maxDownloads <= 1) return single
            var best = single
            for (n in 2..maxDownloads) {
                for (piece in PIECE_STEPS) {
                    if (lentBytes(n, piece) > budgetBytes) continue
                    val choice = PieceChoice(n, piece, effectiveBps(n, piece, firstByteMs, rawBps))
                    if (choice.effectiveBps >= targetBps) return choice
                    if (choice.effectiveBps > best.effectiveBps) best = choice
                }
            }
            return best
        }

        /**
         * The piece size for [downloads] already chosen: the smallest that carries [targetBps] within [budgetBytes],
         * else the biggest that fits. Without a target, the smallest whose first-byte wait stays under
         * [WAIT_SHARE_PERCENT] %. Null when the first byte or the rate is unknown.
         */
        fun pieceForDownloads(downloads: Int, firstByteMs: Long, rawBps: Long, targetBps: Long, budgetBytes: Long): Long? {
            if (firstByteMs < 0L || rawBps <= 0L || budgetBytes <= 0L) return null
            val n = downloads.coerceAtLeast(2)
            val fits = PIECE_STEPS.filter { lentBytes(n, it) <= budgetBytes }
            if (fits.isEmpty()) return PIECE_STEPS.first()
            val good = if (targetBps > 0L) fits.firstOrNull { effectiveBps(n, it, firstByteMs, rawBps) >= targetBps }
                else fits.firstOrNull { waitPercent(firstByteMs, it, rawBps) < WAIT_SHARE_PERCENT }
            return good ?: fits.last()
        }

        const val TARGET_PERCENT = 150
        /** The film's recent scenes, carried with this margin, when that is more than [TARGET_PERCENT] % of its average. */
        const val RECENT_TARGET_PERCENT = 120

        /** Under this much buffer a playing film goes straight to more requests: 40 % of what the box holds, 4 to 10 s. */
        fun lowMarkMs(bufferTargetMs: Long): Long = (bufferTargetMs * 40 / 100).coerceIn(LOW_MARK_MIN_MS, LOW_MARK_MAX_MS)

        /** A refill is done at this much buffer: nine tenths of what the box holds, at most [REFILL_GOAL_MAX_MS]. */
        fun refillGoalMs(bufferTargetMs: Long): Long = minOf(bufferTargetMs * 90 / 100, REFILL_GOAL_MAX_MS)

        /** Bytes per second that regain [goalMs] of buffer from [aheadMs] within [REFILL_WANTED_MS] at [filmBps]. */
        fun refillRate(filmBps: Long, aheadMs: Long, goalMs: Long): Long =
            filmBps + filmBps * (goalMs - aheadMs).coerceAtLeast(0L) / REFILL_WANTED_MS

        const val LOW_MARK_MIN_MS = 4_000L
        const val LOW_MARK_MAX_MS = 10_000L
        const val REFILL_GOAL_MAX_MS = 30_000L
        const val REFILL_WANTED_MS = 10_000L
        const val REFILL_GIVE_UP_MS = 60_000L
        const val BOOST_JUDGE_MS = 15_000L
        /** A refill still under the low mark this long after it began boosts even when the rate said no need. */
        const val REFILL_CHECK_MS = 5_000L
        /** A refill on a server with no history climbs this many requests every [BOOST_STEP_MS] while the buffer falls. */
        const val BOOST_STEP = 2
        const val BOOST_STEP_MS = 2_000L
        /** After "too many requests", the buffer's fall is the server's back-off, not a slow line, for this long. */
        const val RATE_LIMIT_QUIET_MS = 15_000L
        const val WAIT_SHARE_PERCENT = 15.0
        /** A piece size is kept at least this long after a change, and no bigger one follows a smaller this soon. */
        const val PIECE_HOLD_MS = 120_000L
        /** The rate and first byte the size is judged on are averaged over this long. */
        const val PIECE_SMOOTH_MS = 30_000L
        /** A bigger piece only when the rule's size carries this much more. */
        const val PIECE_GAIN_PERCENT = 10
        const val RAW_MOST_FACTOR = 2.0
        /** Timed pieces needed in the window before their rate replaces the delivered one. */
        const val MIN_FLOW_PIECES = 2

        /** Memory the extra downloads hold at [downloads]: each one in flight plus the piece being read. */
        fun lentBytes(downloads: Int, pieceBytes: Long): Long = if (downloads > 1) (downloads + 1) * pieceBytes else 0L

        /**
         * Memory stops the extra downloads only once the box is past its own low-memory line, or close to it while
         * Android says memory is critical. Android's earlier warnings are left to the buffer: Fire OS sends them to
         * every app with hundreds of MB free and closes idle apps on its own to make room. [warned] is null without a
         * buffer keeper; then Android's [lowMemory] decides.
         */
        fun memoryShort(warned: Boolean?, warnedCritical: Boolean, roomBytes: Long, lowMemory: Boolean): Boolean {
            if (warned == null) return lowMemory
            return roomBytes < -AutoSpeedup.MARGIN_BYTES || (warnedCritical && roomBytes < 0L)
        }

        /**
         * Pieces the chunk session may fetch beyond one per download, from free memory read just now while the
         * [current] ones are already held: they keep the downloads busy while the player's buffer is full and cover
         * dips. Grows by up to [READ_AHEAD_STEP] while more than [READ_AHEAD_KEEP_BYTES] stays free, so the server
         * never sees a burst of requests, shrinks below it, never past
         * [maxBytes] in all (the box's share, [READ_AHEAD_MAX_BYTES] on a 2 GB box) or 3 per download.
         */
        fun readAheadPieces(
            current: Int,
            roomBytes: Long,
            pieceBytes: Long,
            downloads: Int,
            maxBytes: Long = READ_AHEAD_MAX_BYTES
        ): Int {
            if (downloads <= 1 || pieceBytes <= 0L) return 0
            val spare = roomBytes - READ_AHEAD_KEEP_BYTES
            val step = if (spare >= 0L) (spare / pieceBytes).toInt() else -(((-spare) + pieceBytes - 1) / pieceBytes).toInt()
            val max = minOf((maxBytes / pieceBytes).toInt() - downloads, downloads * 3).coerceAtLeast(0)
            return (current + minOf(step, READ_AHEAD_STEP)).coerceIn(0, max)
        }

        /**
         * [roomBytes] lowered while seek thumbnails are generating, so the read-ahead leaves [thumbFloorBytes] of
         * MemAvailable ([availBytes], below zero when unknown) for their decodes.
         */
        fun readAheadRoom(roomBytes: Long, availBytes: Long, thumbFloorBytes: Long): Long =
            if (thumbFloorBytes <= 0L || availBytes < 0L) roomBytes else minOf(roomBytes, availBytes - thumbFloorBytes)

        /**
         * The read-ahead on a 2 GB box, lent out of the buffer like the downloads ([AutoSpeedup.lentBytes]) instead of
         * sized from free memory: what [budgetBytes] leaves beside the downloads, at most one piece per download, none
         * once free memory is below the margin ([roomBytes] under zero).
         */
        fun lentReadAhead(downloads: Int, pieceBytes: Long, budgetBytes: Long, roomBytes: Long): Int {
            if (downloads <= 1 || pieceBytes <= 0L || roomBytes < 0L) return 0
            val left = (budgetBytes - lentBytes(downloads, pieceBytes)).coerceAtLeast(0L)
            return (left / pieceBytes).toInt().coerceIn(0, downloads)
        }

        const val READ_AHEAD_KEEP_BYTES = 25L * MB
        const val READ_AHEAD_STEP = 2
        const val READ_AHEAD_MAX_BYTES = 112L * MB

        /**
         * Downloads a film starts on from its average bitrate. One long download on a TV box's network carried about
         * 30 to 85 Mbit/s in the box tests, enough for 1080p and streaming 4K (under 40) but not for a 4K remux.
         * Never above 3; more are added one at a time while the film runs short. On a [smallBox] (2 GB) the start
         * holds no more than two 16 MB downloads did ([SMALL_BOX_START_BYTES]), since three 16 MB downloads ran the
         * Fire Stick short of memory: 3 with 8 MB pieces, 2 with 16 MB. When this server's rate per download is known
         * ([perDownloadBps]), the film starts on as many as carry [START_SINGLE_ENOUGH_PERCENT] % of its rate
         * ([START_ROOMY_PERCENT] % on a 3 GB box or larger, which has the memory and hands extras back later), up to
         * [maxDownloads] (a 2 GB box still within [SMALL_BOX_START_BYTES]), but never on fewer than a heavy film starts
         * on without it: one quick film before does not make the next remux start short.
         */
        fun startDownloads(
            fileBitrate: Long,
            maxDownloads: Int,
            pieceBytes: Long = 0L,
            smallBox: Boolean = maxDownloads <= 4,
            perDownloadBps: Long = 0L
        ): Int {
            val known = perDownloadBps > 0L && fileBitrate > 0L
            if (!known) return guessDownloads(fileBitrate, maxDownloads, pieceBytes, smallBox, 0L)
            val normal = if (fileBitrate >= START_THREE_BPS) guessDownloads(fileBitrate, maxDownloads, pieceBytes, smallBox, 0L) else 1
            return maxOf(normal, guessDownloads(fileBitrate, maxDownloads, pieceBytes, smallBox, perDownloadBps))
        }

        private fun guessDownloads(
            fileBitrate: Long,
            maxDownloads: Int,
            pieceBytes: Long,
            smallBox: Boolean,
            perDownloadBps: Long
        ): Int {
            val known = perDownloadBps > 0L && fileBitrate > 0L
            val want = when {
                known -> {
                    val percent = if (smallBox) START_SINGLE_ENOUGH_PERCENT else START_ROOMY_PERCENT
                    ((fileBitrate / 8 * percent / 100 + perDownloadBps - 1) / perDownloadBps).toInt()
                }
                fileBitrate >= START_THREE_BPS -> if (smallBox) SMALL_BOX_HEAVY_START else 3
                fileBitrate >= START_TWO_BPS -> 2
                else -> 1
            }
            val smallMost = when {
                known -> maxDownloads
                fileBitrate >= START_THREE_BPS -> SMALL_BOX_HEAVY_START
                else -> START_MAX
            }
            val cap = if (smallBox) smallBoxStartCap(pieceBytes, smallMost)
                else if (known) maxDownloads else START_MAX
            return minOf(want, cap, maxDownloads).coerceAtLeast(1)
        }

        private fun smallBoxStartCap(pieceBytes: Long, most: Int): Int {
            if (pieceBytes <= 0L) return 2
            var n = most.coerceAtLeast(2)
            while (n > 2 && lentBytes(n, pieceBytes) > SMALL_BOX_START_BYTES) n--
            return n
        }

        const val SMALL_BOX_START_BYTES = 48L * MB
        /** A remux on a 2 GB box from a server not measured yet: five 8 MB downloads fit [SMALL_BOX_START_BYTES]. */
        const val SMALL_BOX_HEAVY_START = 5
        const val START_TWO_BPS = 40_000_000L
        const val START_THREE_BPS = 60_000_000L
        const val START_MAX = 3
        /** One download carrying this share of the film's rate is enough; below it the film starts on more. */
        const val START_SINGLE_ENOUGH_PERCENT = 130
        const val START_ROOMY_PERCENT = 150
        const val START_SETTLE_MS = 2_000L
        const val START_MEASURE_MS = 4_000L
        /** Ahead within this of the Max buffer counts as full: the player tops up from 3 s below it. */
        const val FULL_SLACK_MS = 4_000L
        /** The first try of a film that is already short this early is judged sooner. */
        const val EARLY_MS = 90_000L
        const val EARLY_LOW_AHEAD_MS = 5_000L
        const val EARLY_TRIAL_MS = 7_000L
        const val GAIN_PERCENT = 15
        const val STARVE_AHEAD_MS = 15_000L
        const val STARVE_WINDOW_MS = 8_000L
        const val GROWTH_STARVE_MS = 500L
        const val TRIAL_MS = 10_000L
        const val MAX_TRIAL_MS = 30_000L
        const val SWITCH_WAIT_MS = 30_000L
        const val TRIAL_SETTLE_MS = 3_000L
        /** A try for one more download runs at least this long and is judged on its median rate. */
        const val STEADY_TRIAL_MS = 20_000L
        const val MAX_TRIAL_SETTLE_MS = 8_000L
        const val RATE_STEP_MS = 2_000L
        const val GROWTH_PASS_MS = 2_000L
        const val LOADING_PASS_PERCENT = 75
        const val STEP_GAP_MS = 2_000L
        const val FAILED_BLOCK_MS = 30_000L
        const val MEMORY_BLOCK_MS = 15_000L
        const val COMFORTABLE_AHEAD_MS = 30_000L
        const val COMFORTABLE_IDLE_AHEAD_MS = 10_000L
        const val COMFORTABLE_MS = 60_000L
        /** Running short again this soon after a download was handed back: keep the count for the rest of the film. */
        const val KEEP_AFTER_DROP_MS = 180_000L
        const val HISTORY_MS = 40_000L
        const val DELIVERED_MIN_MS = 20_000L
        const val DELIVERED_LOADING_PERCENT = 50
        const val SEEK_DROP_MS = 2_000L
    }
}

/**
 * The piece size during a film. Down at once (and only) when the downloads no longer fit the memory budget with it.
 * Up one step toward the rule's size ([AutoSpeedupPolicy.pieceForDownloads], judged on the rate and first byte averaged
 * over [AutoSpeedupPolicy.PIECE_SMOOTH_MS]) when that size carries [AutoSpeedupPolicy.PIECE_GAIN_PERCENT] % more than
 * the current one, no sooner than [AutoSpeedupPolicy.PIECE_HOLD_MS] after the last change or a step down, and with the
 * free memory for the bigger pieces plus one new piece, as both sizes are held while the session changes over.
 */
internal class PieceSizer {
    data class Step(val to: Long, val why: String, val held: Boolean = false)

    private class Reading(val atMs: Long, val rawBps: Long, val firstByteMs: Long)

    private val readings = ArrayDeque<Reading>()
    private var changedAtMs = -1L
    private var downAtMs = Long.MIN_VALUE / 2
    private var heldTo = 0L

    fun next(
        nowMs: Long,
        piece: Long,
        downloads: Int,
        rawBps: Long?,
        firstByteMs: Long,
        targetBps: Long,
        budgetBytes: Long,
        roomBytes: Long,
        readAheadMaxBytes: Long
    ): Step? {
        if (changedAtMs < 0L) changedAtMs = nowMs
        val fit = AutoSpeedupPolicy.fittingPiece(downloads, piece, budgetBytes)
        if (fit < piece) {
            changedAtMs = nowMs
            downAtMs = nowMs
            heldTo = 0L
            return Step(fit, "memory downloads=$downloads budgetMb=${budgetBytes / MB}")
        }
        if (rawBps != null && rawBps > 0L && firstByteMs >= 0L) readings.addLast(Reading(nowMs, rawBps, firstByteMs))
        while (readings.isNotEmpty() && nowMs - readings.first().atMs > AutoSpeedupPolicy.PIECE_SMOOTH_MS) {
            readings.removeFirst()
        }
        if (readings.isEmpty()) return null
        if (nowMs - changedAtMs < AutoSpeedupPolicy.PIECE_HOLD_MS) return null
        if (nowMs - downAtMs < AutoSpeedupPolicy.PIECE_HOLD_MS) return null
        val raw = readings.sumOf { it.rawBps } / readings.size
        val firstByte = readings.sumOf { it.firstByteMs } / readings.size
        val want = AutoSpeedupPolicy.pieceForDownloads(downloads, firstByte, raw, targetBps, budgetBytes) ?: return null
        if (want <= piece) return null
        val now = AutoSpeedupPolicy.effectiveBps(downloads, piece, firstByte, raw)
        val then = AutoSpeedupPolicy.effectiveBps(downloads, want, firstByte, raw)
        if (then * 100 < now * (100 + AutoSpeedupPolicy.PIECE_GAIN_PERCENT)) return null
        val steps = AutoSpeedupPolicy.PIECE_STEPS
        val to = steps.firstOrNull { it > piece } ?: return null
        val why = "rawKbps=${raw * 8 / 1000} firstByteMs=$firstByte downloads=$downloads " +
            "gain=${(then - now) * 100 / now.coerceAtLeast(1L)}%"
        val extra = (AutoSpeedupPolicy.heldBytes(downloads, to, readAheadMaxBytes) -
            AutoSpeedupPolicy.heldBytes(downloads, piece, readAheadMaxBytes)).coerceAtLeast(0L) + to
        if (roomBytes < extra) {
            if (heldTo == to) return null
            heldTo = to
            return Step(to, "roomMb=${roomBytes / MB} $why", held = true)
        }
        heldTo = 0L
        changedAtMs = nowMs
        return Step(to, why)
    }

    private companion object {
        const val MB = 1024L * 1024L
    }
}

/**
 * One piece request: [ttfbMs] from sending it to the first body byte, [transferMs] and [bytes] after that byte.
 * [reused] = it went out on a pooled connection (null when unknown); [redirected] = the wait includes a redirect.
 */
internal data class PieceTiming(
    val atMs: Long,
    val ttfbMs: Long,
    val transferMs: Long,
    val bytes: Long,
    val reused: Boolean?,
    val redirected: Boolean
)

/**
 * The piece requests of the last [windowMs]: the wait for the first byte (on reused connections, on new ones, and the
 * one the plan uses: reused when there are any) and the rate while data flows, bytes over transfer time. A request
 * that went through a redirect counts for the rate only. Also the film's whole average wait, for [HostSpeeds].
 */
internal class PieceTimes(private val windowMs: Long = AutoSpeedupPolicy.PIECE_SMOOTH_MS) {
    data class Averages(
        val pieces: Int,
        val timed: Int,
        val reused: Int,
        val ttfbMs: Long,
        val reusedTtfbMs: Long,
        val newTtfbMs: Long,
        val flowPieces: Int,
        val flowBps: Long
    )

    private val pieces = ArrayDeque<PieceTiming>()
    private var filmReusedMs = 0L
    private var filmReused = 0
    private var filmNewMs = 0L
    private var filmNew = 0

    @Synchronized
    fun add(t: PieceTiming) {
        if (t.ttfbMs < 0L || t.transferMs < 0L || t.bytes < 0L) return
        pieces.addLast(t)
        prune(t.atMs)
        if (t.redirected) return
        if (t.reused == true) {
            filmReusedMs += t.ttfbMs
            filmReused++
        } else {
            filmNewMs += t.ttfbMs
            filmNew++
        }
    }

    @Synchronized
    fun averages(nowMs: Long): Averages {
        prune(nowMs)
        var timed = 0
        var timedMs = 0L
        var reused = 0
        var reusedMs = 0L
        var flowPieces = 0
        var flowBytes = 0L
        var flowMs = 0L
        for (p in pieces) {
            if (!p.redirected) {
                timed++
                timedMs += p.ttfbMs
                if (p.reused == true) {
                    reused++
                    reusedMs += p.ttfbMs
                }
            }
            if (p.bytes >= MIN_FLOW_BYTES && p.transferMs > 0L) {
                flowPieces++
                flowBytes += p.bytes
                flowMs += p.transferMs
            }
        }
        val reusedAvg = if (reused > 0) reusedMs / reused else -1L
        val newAvg = if (timed > reused) (timedMs - reusedMs) / (timed - reused) else -1L
        val ttfb = if (reused > 0) reusedAvg else if (timed > 0) timedMs / timed else -1L
        return Averages(pieces.size, timed, reused, ttfb, reusedAvg, newAvg, flowPieces,
            if (flowMs > 0L) flowBytes * 1000L / flowMs else 0L)
    }

    /** The wait of the film's piece requests so far (reused connections first), -1 while none was timed. */
    @Synchronized
    fun filmTtfbMs(): Long = when {
        filmReused > 0 -> filmReusedMs / filmReused
        filmNew > 0 -> filmNewMs / filmNew
        else -> -1L
    }

    private fun prune(nowMs: Long) {
        while (pieces.isNotEmpty() && nowMs - pieces.first().atMs > windowMs) pieces.removeFirst()
    }

    private companion object {
        const val MIN_FLOW_BYTES = 1024L * 1024L
    }
}
