package com.nuvio.tv.core.player.thumbnail

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.atomic.AtomicBoolean

/** Where a copy comes from: copies of one provider come from the same servers. */
internal enum class CopyProvider { TORBOX, PREMIUMIZE, OTHER_DEBRID, HOME_SERVER, AWAY_SERVER, LINK, USENET }

/**
 * What copies from each source did lately, kept across runs: a copy the picture check turned down, or one slow to open
 * or to read, counts against its source (provider and add-on). The count halves every [HALF_LIFE_MS]; a source at
 * [LAST_AT] or more ranks its copies last. A confirmed copy clears its source.
 */
internal class CopySourceBook(private val memory: HardwareFailureMemory, private val now: () -> Long) {
    companion object {
        const val HALF_LIFE_MS = 3L * 24 * 60 * 60 * 1000
        const val LAST_AT = 1.0
        /** The picture check turned the copy down: another film or another cut. */
        const val WRONG = 1.0
        /** No link, or slow to open or to read its index. */
        const val SLOW = 0.5
        const val SLOW_OPEN_MS = 30_000L
        const val SLOW_INDEX_MS = 8_000L
        private const val KEY = "copy_sources_2"
        private const val MAX_SOURCES = 64
        private val CONTROL = Regex("[\t\n\r|]")

        /** A copy turned down by the picture check counts against its source only for an SDR title. */
        fun countsWrong(playingHdr: Boolean): Boolean = !playingHdr

        /** The playing picture is HDR or Dolby Vision: PQ or HLG transfer, BT.2020 primaries, or a Dolby Vision track. */
        fun hdrPicture(transfer: Int, primaries: Int, dolbyVision: Boolean): Boolean =
            dolbyVision || transfer == 16 || transfer == 18 || primaries == 9

        fun keyOf(o: CopyOffer): String =
            CopyChoice.providerOf(o).name.lowercase() + "|" + o.source.trim().lowercase().replace(CONTROL, " ")

        fun decayed(score: Double, atMs: Long, nowMs: Long): Double =
            if (nowMs <= atMs) score else score * Math.pow(0.5, (nowMs - atMs).toDouble() / HALF_LIFE_MS)
    }

    private class Entry(val score: Double, val atMs: Long)

    private var entries: LinkedHashMap<String, Entry>? = null

    private fun loaded(): LinkedHashMap<String, Entry> = entries ?: LinkedHashMap<String, Entry>().also { map ->
        memory.get(KEY)?.lineSequence()?.forEach { line ->
            val parts = line.split('\t')
            val score = parts.getOrNull(1)?.toDoubleOrNull()
            val at = parts.getOrNull(2)?.toLongOrNull()
            if (parts.size == 3 && score != null && at != null) map[parts[0]] = Entry(score, at)
        }
        entries = map
    }

    private fun save(map: LinkedHashMap<String, Entry>) {
        val t = now()
        val kept = map.entries.filter { decayed(it.value.score, it.value.atMs, t) >= 0.05 }.takeLast(MAX_SOURCES)
        memory.put(KEY, kept.joinToString("\n") { "${it.key}\t${it.value.score}\t${it.value.atMs}" })
    }

    @Synchronized
    fun score(o: CopyOffer): Double = loaded()[keyOf(o)]?.let { decayed(it.score, it.atMs, now()) } ?: 0.0

    fun penalised(o: CopyOffer): Boolean = score(o) >= LAST_AT

    /** Counts [weight] against [o]'s source; the new count. */
    @Synchronized
    fun count(o: CopyOffer, weight: Double): Double {
        val map = loaded()
        val key = keyOf(o)
        val t = now()
        val score = (map.remove(key)?.let { decayed(it.score, it.atMs, t) } ?: 0.0) + weight
        map[key] = Entry(score, t)
        save(map)
        return score
    }

    @Synchronized
    fun cleared(o: CopyOffer) {
        val map = loaded()
        if (map.remove(keyOf(o)) != null) save(map)
    }
}

internal object CopySourceBooks {
    @Volatile private var book: CopySourceBook? = null

    fun app(context: android.content.Context): CopySourceBook = book ?: synchronized(this) {
        book ?: CopySourceBook(object : HardwareFailureMemory {
            private val prefs = context.applicationContext.getSharedPreferences("seek_thumbs_copy", android.content.Context.MODE_PRIVATE)
            override fun get(key: String): String? = prefs.getString(key, null)
            override fun put(key: String, value: String) = prefs.edit().putString(key, value).apply()
        }) { System.currentTimeMillis() }.also { book = it }
    }
}

/**
 * Thumbnails from a lighter copy first, on every box and for every source with copies: before play the copy's link is
 * opened at the stream pick ([CopyHeadStart]), during play at the film's first frame. The film's own requests come
 * first; the copy gets what is left, at most [MAX_LANES] at once.
 */
internal object CopyFirst {
    /** Without the copy's link open this long after the stream pick, thumbnails are made the usual way. */
    const val FALLBACK_MS = 20_000L
    /** The generating screen waits for the copy to be confirmed until this long after the stream pick. */
    const val SCREEN_WAIT_MS = 40_000L
    /** Copy requests at once at most, inside the server's or account's own budget. */
    const val MAX_LANES = 4
    /** For a title that is not 4K, a copy is worth reading only at this share of the playing file's bitrate or less. */
    const val LIGHTER_SHARE = 0.6
    const val OPEN_TIMEOUT_MS = 45_000L
    /** Ranges an index read is cut into at most ([SplitRangeReader]), inside [MAX_LANES]; [SMALL_INDEX_PARTS] on a small box. */
    const val INDEX_PARTS = 3
    const val SMALL_INDEX_PARTS = 2
    /** The pass waits this long at most for a header and index still being read ahead, then reads them itself. */
    const val PREREAD_WAIT_MS = 10_000L
    /**
     * Behind the screen after its wait, a copy not confirmed yet still gets turns on a smaller box: once it has waited
     * this long for the title's own 4K decoder, the title's own grid waits until the copy made [TURN_PICTURES] more
     * pictures or [TURN_MS] went by.
     */
    const val TURN_AFTER_MS = 3_000L
    const val TURN_MS = 10_000L
    const val TURN_PICTURES = 8

    /** A copy turn starts: the copy is still being made, not confirmed, and waited [waitedMs] for the own decoder. */
    fun turnStarts(strongBox: Boolean, filling: Boolean, unconfirmed: Boolean, waitedMs: Long): Boolean =
        !strongBox && filling && unconfirmed && waitedMs >= TURN_AFTER_MS

    /** The copy's turn is over after [sinceMs] with [made] pictures made in it, or once it stopped or was decided. */
    fun turnOver(filling: Boolean, unconfirmed: Boolean, sinceMs: Long, made: Int): Boolean =
        !filling || !unconfirmed || sinceMs >= TURN_MS || made >= TURN_PICTURES

    /**
     * Why [o] is not lighter than the playing file of [ownBytes] and [ownMs], null when it is: its bitrate at most
     * [LIGHTER_SHARE] of the playing file's.
     */
    fun notLighter(o: CopyOffer, ownBytes: Long?, ownMs: Long?): String? {
        val own = ownBytes?.takeIf { it > 0L } ?: return "the playing file's size is not known"
        val size = o.sizeBytes?.takeIf { it > 0L } ?: return "size not stated, not known to be lighter"
        val ownLen = ownMs?.takeIf { it > 0L }
        val copyLen = o.durationMs?.takeIf { it > 0L } ?: ownLen
        val scaled = if (ownLen != null && copyLen != null) size.toDouble() * ownLen / copyLen else size.toDouble()
        return if (scaled <= own * LIGHTER_SHARE) null
            else "not lighter than the playing file (${size / (1024 * 1024)} MB against ${own / (1024 * 1024)} MB)"
    }

    /**
     * The generating screen keeps waiting for the copy: until its link is open [FALLBACK_MS] after the stream pick,
     * then until it is confirmed [SCREEN_WAIT_MS] after the pick. [linkOpen]: a copy's link is open.
     */
    fun screenWaits(ended: Boolean, linkOpen: Boolean, confirmed: Boolean, sincePickMs: Long): Boolean = when {
        confirmed || ended -> false
        !linkOpen -> sincePickMs < FALLBACK_MS
        else -> sincePickMs < SCREEN_WAIT_MS
    }

    /** The copy pass waited for its link past the cut-off: it is stopped and the title makes its own thumbnails. */
    fun fallsBack(linkOpen: Boolean, sincePickMs: Long): Boolean = !linkOpen && sincePickMs >= FALLBACK_MS

    /** Ranges an index read is cut into: [INDEX_PARTS] (2 on a small box), never more than the link or the server allow. */
    fun indexParts(strongBox: Boolean, linkLanes: Int, room: Int): Int =
        minOf(if (strongBox) INDEX_PARTS else SMALL_INDEX_PARTS, linkLanes, room).coerceAtLeast(1)

    /**
     * Why the copy's header and index are not read as soon as its link opens (the pass reads them later), null when
     * they are: never while the server or playback was told to slow down, or while memory is short.
     */
    fun prereadBlocked(rateLimited: Boolean, playbackClamped: Boolean, refusalQuietMs: Long, memory: String?): String? = when {
        rateLimited -> "the server asked for fewer requests"
        playbackClamped -> "playback was rate limited"
        refusalQuietMs > 0L -> "the server is calming down after too many requests"
        memory != null -> "memory ($memory)"
        else -> null
    }

    /** [a] and [b] are the same entry of the same source list. */
    fun sameOffer(a: CopyOffer, b: CopyOffer): Boolean = a.id == b.id && a.label == b.label && a.sizeBytes == b.sizeBytes

    /** During play the copy starts at the film's first frame; behind the generating screen at once. */
    fun startsNow(screenUp: Boolean, firstFrameShown: Boolean): Boolean = screenUp || firstFrameShown

    /**
     * The copy whose link is opened at the stream pick, from the source list alone: the same rules as the copy pass,
     * with the playing stream's own facts ([CopyOffers.playing]) for its height, length, size and provider. Null when
     * the playing picture is not known or nothing fits; Usenet copies are left to the pass (each open may count as a
     * grab).
     */
    fun headStartPick(
        offers: CopyOffers, ownBytes: Long?, penalised: (CopyOffer) -> Boolean = { false },
        batched: (CopyOffer) -> Boolean? = { null },
    ): CopyOffer? = headStartPick(headStartVerdicts(offers, ownBytes, batched), offers, penalised, batched)

    /** [headStartPick] from verdicts already worked out by [headStartVerdicts]. */
    fun headStartPick(
        verdicts: List<Pair<CopyOffer, String?>>, offers: CopyOffers, penalised: (CopyOffer) -> Boolean = { false },
        batched: (CopyOffer) -> Boolean? = { null },
    ): CopyOffer? = CopyChoice.order(verdicts.filter { it.second == null }.map { it.first },
        ownMs = offers.playing?.durationMs, penalised = penalised, batched = batched).firstOrNull()

    /** Each offer with why the head start leaves it out, null when it may be opened; empty when the playing picture is not known. */
    fun headStartVerdicts(
        offers: CopyOffers, ownBytes: Long?, batched: (CopyOffer) -> Boolean? = { null },
    ): List<Pair<CopyOffer, String?>> {
        val playing = offers.playing ?: return emptyList()
        if (playing.height == 0 || offers.playingTranscoded) return emptyList()
        val fourK = playing.height > 1080
        val ownMs = playing.durationMs ?: 0L
        val bytes = ownBytes ?: playing.sizeBytes
        val editions = CopyChoice.editions(offers.playingText)
        return offers.offers.map { o ->
            o to ((if (o.kind == CopyKind.USENET) "a Usenet link is opened by the pass only" else null)
                ?: CopyChoice.rejection(o, offers.playingText, ownMs, maxHeight = if (fourK) CopyChoice.FOUR_K else playing.height,
                    fourKCopies = fourK, ownEditions = editions)
                ?: (if (fourK) CopyChoice.fourKCopyRejection(o, playing, batched(o)) else notLighter(o, bytes, ownMs.takeIf { it > 0L })))
        }
    }
}

/**
 * The top copy's link, opened at the stream pick while the player opens the film, so the copy pass starts on it. One
 * per title; a link the pass does not take is given back ([CopyLink.done]: a TorBox copy added for it is removed).
 */
internal class CopyHeadStart(
    val identity: String,
    val pickedAtMs: Long = SystemClock.elapsedRealtime(),
    private val client: (Map<String, String>) -> OkHttpClient = ::thumbnailHttpClient,
) {
    private companion object {
        const val TAG = "ThumbCopy"
        const val LIST_POLL_MS = 300L
    }

    @Volatile var offer: CopyOffer? = null
        private set
    @Volatile var openedAtMs = 0L
        private set
    /** The source list the copy was chosen from. */
    @Volatile var listed: CopyOffers? = null
        private set
    /** The link opened: [openedAtMs] is set and a link came back. */
    @Volatile var opened = false
        private set
    private val link = CompletableDeferred<CopyLink?>()
    /** The copy chosen, or null once it is clear none is. */
    private val chosen = CompletableDeferred<CopyOffer?>()
    private val handed = AtomicBoolean(false)
    private val released = AtomicBoolean(false)
    private val givenBack = AtomicBoolean(false)
    @Volatile private var job: Job? = null

    private fun giveBack(l: CopyLink?) {
        if (l != null && givenBack.compareAndSet(false, true)) {
            l.dropPreread()
            runCatching { l.done() }
        }
    }

    private fun since(): Long = SystemClock.elapsedRealtime() - pickedAtMs

    /**
     * [anchorMs]: where playback starts, for the pictures made before the session ([CopyEarly]); [early]: whether
     * they are made at all.
     */
    fun start(
        scope: CoroutineScope, sources: CopySources, book: CopySourceBook?, ownBytes: Long?,
        anchorMs: suspend () -> Long = { 0L }, early: Boolean = false,
    ) {
        job = scope.launch {
            try {
                runCatching { sources.loadNow() }.onFailure { if (it is CancellationException) throw it }
                var offers: CopyOffers? = null
                var buildMs = 0L
                while (offers == null && since() < CopyFirst.FALLBACK_MS) {
                    val asked = SystemClock.elapsedRealtime()
                    offers = runCatching { sources.offers() }.onFailure { if (it is CancellationException) throw it }.getOrNull()
                    buildMs = SystemClock.elapsedRealtime() - asked
                    if (offers == null) delay(LIST_POLL_MS)
                }
                val listedAt = SystemClock.elapsedRealtime()
                if (offers == null) {
                    Log.i(TAG, "copy first: source list not loaded ${CopyFirst.FALLBACK_MS / 1000} s after the stream pick")
                    link.complete(null)
                    return@launch
                }
                val batched: (CopyOffer) -> Boolean? = { sources.multiRange(it) }
                val ranking = SystemClock.elapsedRealtime()
                val verdicts = CopyFirst.headStartVerdicts(offers, ownBytes, batched)
                val top = CopyFirst.headStartPick(verdicts, offers, { book?.penalised(it) == true }, batched)
                val rankMs = SystemClock.elapsedRealtime() - ranking
                if (top == null) {
                    Log.i(TAG, "copy first: source list ${since()} ms after the stream pick, " + if (verdicts.isEmpty())
                        "the playing picture is not known yet - the pass decides" else
                        "no copy fits (" + CopyChoice.reasonTally(verdicts.mapNotNull { it.second }) + ")")
                    link.complete(null)
                    return@launch
                }
                offer = top
                listed = offers
                chosen.complete(top)
                Log.i(TAG, "copy first: ${top.label} chosen ${since()} ms after the stream pick " +
                    "(${verdicts.count { it.second == null }} of ${offers.offers.size} sources fit, list ready " +
                    "${listedAt - pickedAtMs} ms after the pick, read in $buildMs ms, ranked in $rankMs ms)")
                runCatching { sources.prewarm(top) }.onFailure { if (it is CancellationException) throw it }
                var got: CopyLink? = null
                try {
                    withTimeoutOrNull(CopyFirst.OPEN_TIMEOUT_MS) { sources.open(top).also { got = it } }
                } catch (e: CancellationException) {
                    giveBack(got)
                    throw e
                }
                val l = got
                openedAtMs = SystemClock.elapsedRealtime()
                opened = l != null
                Log.i(TAG, if (l != null) "copy first: link of ${top.label} open ${since()} ms after the stream pick via ${l.via}"
                    else "copy first: no link for ${top.label} (${since()} ms after the stream pick)")
                if (l != null && !released.get()) l.preread = scope.async(Dispatchers.IO) {
                    preread(top, l, ownBytes)?.also { p -> if (early && !released.get()) startEarly(scope, top, l, p, anchorMs) }
                }
                if (!link.complete(l) || (released.get() && !handed.get())) giveBack(l)
            } catch (e: CancellationException) {
                link.complete(null)
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "copy first: head start ended (${t.javaClass.simpleName})")
                link.complete(null)
            } finally {
                chosen.complete(null)
            }
        }
    }

    /**
     * The copy's header and keyframe index, read at once on the link just opened, while the player still opens the
     * film: the first bytes come with the link's redirects when [CopySources.prewarm] followed them, the index is read
     * in a few ranges at once ([CopyFirst.indexParts]). Null when it is not done or fails: the pass then reads them.
     */
    private fun preread(o: CopyOffer, l: CopyLink, ownBytes: Long?): CopyPreread? {
        val blocked = CopyFirst.prereadBlocked(RateLimitedHosts.appSession.contains(l.url), ThumbRateLimit.playbackClampedNow(),
            ConnectionLedger.app.refusalQuietMs(l.url), MemoryGovernor.pressure())
        if (blocked != null) {
            Log.i(TAG, "copy first: header and index of ${o.label} left to the pass ($blocked)")
            return null
        }
        val t0 = SystemClock.elapsedRealtime()
        val r = runCatching {
            HttpRangeReader(l.url, l.headers, client(l.headers), slotWaitMs = ConnectionLedger.START_SLOT_WAIT_MS)
        }.getOrNull() ?: return null
        l.prereadReader = r
        try {
            if (l.maxLanes < Int.MAX_VALUE) ConnectionLedger.app.capThumbnails(l.url, l.maxLanes)
            val linkHead = l.head?.takeIf { l.length > 0L && it.isNotEmpty() }
            if (linkHead != null) r.noteLength(l.length)
            val head = linkHead ?: r.read(0, MkvIndexReader.HEAD_BYTES)
            val headMs = if (linkHead != null) 0L else SystemClock.elapsedRealtime() - t0
            if (ownBytes != null && ownBytes > 0L && r.totalLength == ownBytes) {
                r.close()
                return null
            }
            val parts = CopyFirst.indexParts(MemoryGovernor.isStrongHardware, l.maxLanes, ConnectionLedger.app.thumbnailRoom(r.resolvedUrl))
            val split = SplitRangeReader(r, parts)
            val mkv = MkvIndexReader.looksLikeMatroska(head)
            val index = when {
                mkv -> MkvIndexReader.read(split, head)
                Mp4IndexReader.looksLikeMp4(head) -> Mp4IndexReader.read(split, head)
                else -> {
                    r.close()
                    return null
                }
            }
            val done = SystemClock.elapsedRealtime()
            Log.i(TAG, "copy first: header and index of ${o.label} read ${done - openedAtMs} ms after its link opened " +
                "(${(r.bytes + if (linkHead != null) linkHead.size else 0) / 1024} KB, " +
                (if (linkHead != null) "first bytes with the link's redirects" else "first bytes in $headMs ms") +
                ", index in up to $parts ranges at once), ${since()} ms after the stream pick")
            return CopyPreread(r, head, index, mkv, headMs, done - t0, done)
        } catch (e: CancellationException) {
            r.close()
            throw e
        } catch (t: Throwable) {
            r.close()
            Log.i(TAG, "copy first: header and index of ${o.label} not read ahead (${t.javaClass.simpleName}) - the pass reads them")
            return null
        }
    }

    private fun startEarly(scope: CoroutineScope, o: CopyOffer, l: CopyLink, p: CopyPreread, anchorMs: suspend () -> Long) {
        val e = CopyEarly(o.label, pickedAtMs)
        l.early = e
        e.start(scope, p, MemoryGovernor.isStrongHardware, anchorMs) {
            if (released.get() && !handed.get()) "the title's session did not start" else
                CopyFirst.prereadBlocked(RateLimitedHosts.appSession.contains(l.url), ThumbRateLimit.playbackClampedNow(),
                    ConnectionLedger.app.refusalQuietMs(l.url), MemoryGovernor.pressure())
        }
    }

    /** The link opened ahead for [o], once; null when it was for another copy or did not open. */
    suspend fun take(o: CopyOffer): CopyLink? {
        if (released.get()) return null
        val mine = withTimeoutOrNull(CopyFirst.FALLBACK_MS) { chosen.await() } ?: return null
        if (!CopyFirst.sameOffer(mine, o)) return null
        if (released.get() || !handed.compareAndSet(false, true)) return null
        val l = withTimeoutOrNull(CopyFirst.OPEN_TIMEOUT_MS) { link.await() }
        if (l == null) handed.set(false)
        return l
    }

    /** Gives back a link nobody took and stops a head start still under way. */
    fun release() {
        if (!released.compareAndSet(false, true)) return
        job?.cancel()
        chosen.complete(null)
        if (!handed.get() && link.isCompleted) giveBack(runCatching { link.getCompleted() }.getOrNull())
    }
}
