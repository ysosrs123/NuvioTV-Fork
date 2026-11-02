package com.nuvio.tv.core.player.thumbnail

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.nuvio.tv.data.local.ImagePerformancePreferences
import com.nuvio.tv.ui.screens.player.ParallelRangeDataSource
import com.nuvio.tv.ui.screens.player.SlowStartServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** The player's side of a copy pass: its source list, and links to read a copy from. */
internal interface CopySources {
    /** The playing title's source list as copy offers; null while it is not loaded yet. */
    suspend fun offers(): CopyOffers?

    /** Loads the source list now instead of once playback has settled in (usually a cache hit from the details page). */
    suspend fun loadNow() = Unit

    /**
     * Follows [offer]'s redirects now with one tiny read, so the pass starts on the final link; only for links with
     * nothing to add or remove. The milliseconds it took, null when not done.
     */
    suspend fun prewarm(offer: CopyOffer): Long? = null

    /**
     * A link to read [offer] from, or null. A cached TorBox copy added to the user's list is removed again through
     * [CopyLink.done]; a debrid resolve link ([CopyKind.RESOLVE]) may leave the title in that account.
     */
    suspend fun open(offer: CopyOffer): CopyLink?

    /** Whether [offer]'s server is known to send many keyframes in one request; null when not known. */
    fun multiRange(offer: CopyOffer): Boolean? = null
}

internal class CopyOffers(
    val playingText: String,
    val offers: List<CopyOffer>,
    /** The playing stream is a media server's transcode. */
    val playingTranscoded: Boolean = false,
    /** The playing stream's own facts from the source list (height, length, size); null when it is not in the list. */
    val playing: CopyOffer? = null,
)

/**
 * Never logged: [url] and [headers] may carry tokens. [via] says how it was reached, for the log. [done] is called once
 * the pass no longer reads it.
 */
internal class CopyLink(
    val url: String,
    val headers: Map<String, String>,
    val via: String,
    /** Requests at once at most, on top of the usual lane rules. */
    val maxLanes: Int = Int.MAX_VALUE,
    /** [url] is where the link's redirects led when [CopySources.prewarm] followed them ahead of the pass. */
    val prewarmed: Boolean = false,
    /** The file's first bytes, read while the redirects were followed; null when not read. */
    val head: ByteArray? = null,
    /** The file's length from that answer, -1 when not known. */
    val length: Long = -1L,
    val done: () -> Unit = {},
) {
    /** Header and index read as soon as the link opened ([CopyHeadStart]); the pass takes them instead of reading again. */
    @Volatile var preread: Deferred<CopyPreread?>? = null
    /** The reader of [preread] while it reads; closed with [dropPreread]. */
    @Volatile var prereadReader: HttpRangeReader? = null

    /** Copy pictures made from [preread] before the title's session ([CopyEarly]). */
    @Volatile var early: CopyEarly? = null

    /** Stops a read ahead nobody takes, and the pictures made from it, and closes its reader. */
    fun dropPreread() {
        early?.drop()
        early = null
        preread?.cancel()
        preread = null
        prereadReader?.close()
        prereadReader = null
    }

    /** The pictures made before the session, once; making them stops. */
    suspend fun takeEarly(): List<CopyEarlyPicture> {
        val e = early ?: return emptyList()
        early = null
        return e.take()
    }

    /**
     * The header and index read ahead, waiting up to [waitMs] for a read still under way; null when there is none or it
     * failed. The caller owns the reader from then on.
     */
    suspend fun takePreread(waitMs: Long): CopyPreread? {
        val d = preread ?: return null
        val got = withTimeoutOrNull(waitMs) { runCatching { d.await() }.getOrNull() }
        if (got == null) {
            if (!kotlinx.coroutines.currentCoroutineContext().isActive) {
                dropPreread()
                throw CancellationException("stopped")
            }
            dropPreread()
            return null
        }
        preread = null
        prereadReader = null
        return got
    }
}

/** A copy's header and index read ahead, with the reader that read them (the pass reads on with it). */
internal class CopyPreread(
    val reader: HttpRangeReader,
    val head: ByteArray,
    val index: MediaIndex,
    val mkv: Boolean,
    /** First bytes: 0 when they came with the link's redirects. */
    val headMs: Long,
    val readMs: Long,
    val doneAtMs: Long,
)

/** The playing title as the copy pass needs it. */
internal class CopyOwn(
    val identity: String,
    val durationMs: Long,
    val fileLength: Long,
    val pictureW: Int,
    val pictureH: Int,
    /** Keyframe time (us) playback lands on for each own slot, -1 without one. */
    val slotPtsUs: LongArray,
    /** The title's own store, read only here. */
    val store: ThumbStore,
    val anchorSlot: Int,
    /** Height class of the playing picture: no copy above it is used. */
    val maxHeight: Int = 2160,
    /** The title plays from Usenet: its thumbnails come from a copy so the stream itself is spared. */
    val usenet: Boolean = false,
    /** The generating screen counts every [screenStride]th own slot: those are made first. 1: no such order. */
    val screenStride: Int = 1,
    /** The title plays from a media server away from home: its stream is never read for the copy's pictures. */
    val awayServer: Boolean = false,
    /** Not 4K: a copy is used only when it is lighter than the playing file ([CopyFirst.notLighter]). */
    val lighterOnly: Boolean = false,
    /** When the stream was picked (elapsed realtime), for the log's timings; 0 when not known. */
    val pickedAtMs: Long = 0L,
    /** The playing picture is HDR or Dolby Vision. */
    val hdr: Boolean = false,
    /** Every keyframe of the playing file (us, sorted): a copy of another length is lined up against them. */
    val keyframesUs: LongArray = LongArray(0),
    /** A copy picture farther than this from where a seek lands is not used for that slot ([CopyPass.ownOnlySlots]). */
    val wideUs: Long = 2_000_000L,
)

/** A copy's pictures, ready for the picture check: its store and its timing against the playing title. */
internal class CopySet(val store: ThumbStore, val timing: ReleaseMatch.Timing, val slotCount: Int, val label: String)

/**
 * 4K thumbnails from a smaller copy of the same title in the player's own source list, and a Usenet title's from a
 * copy that is not on Usenet (so the stream is not read for them). The copy's pictures go to a
 * store of their own (same title, keyed by the copy's length and the picture size, never the copy file's own store),
 * fitted to the 4K title's picture size and taken at the keyframes nearest to where a 4K seek lands. The session checks them against its own pictures and copies them over as another release.
 *
 * Requests only while playback can spare them, at background priority, a few at once by server
 * ([UsenetCopy.fetches]) and none while playback's buffer runs low ([PlaybackHeadroom]); on small boxes one at a time
 * behind the dropped-frame [pacer] while playing. Behind the generating screen ([screenUp], playback held) more at
 * once.
 */
internal class CopyPass(
    private val context: Context,
    private val own: CopyOwn,
    private val sources: CopySources,
    private val playerProvider: () -> ExoPlayer?,
    private val lastStallAtMs: () -> Long,
    /** A 4K decoder of the session is decoding or holds its memory. */
    private val ownDecoderBusy: () -> Boolean,
    private val pacer: PlaybackDecodePacer?,
    private val screenUp: () -> Boolean = { false },
    /** The player has closed and the session finishes on its own. */
    private val playerClosed: () -> Boolean = { false },
    /** The top copy's link opened at the stream pick, taken instead of opening it again. */
    private val headStart: CopyHeadStart? = null,
) {
    private companion object {
        const val TAG = "ThumbCopy"
        const val SPACING_MS = SeekThumbnails.SPACING_MS
        val STRIDES = listOf(30, 6, 3, 1)
        const val LOCAL_BEFORE_SLOTS = 60
        const val LOCAL_AFTER_SLOTS = 120
        const val LIST_WAIT_MS = 5L * 60 * 1000
        const val LIST_POLL_MS = 250L
        const val OPEN_TIMEOUT_MS = 45_000L
        const val MAX_OPENS = 4
        const val MAX_BYTES = 200L * 1024 * 1024
        const val READY_PICTURES = 12
        /** Behind the generating screen the copy's pictures show from this many on: an empty screen waits for nothing. */
        const val READY_PICTURES_SCREEN = 2
        /** A copy this short is a sample or a placeholder, never the film. */
        const val NOT_A_FILM_MS = 10L * 60 * 1000
        const val FULL_AHEAD_MS = 14_000L
        const val QUIET_AFTER_STALL_MS = 60_000L
        const val TROUBLE_WINDOW_MS = 20_000L
        const val PLAYBACK_429_QUIET_MS = ThumbRateLimit.PLAYBACK_QUIET_MS
        const val PACE_PAUSED_MS = 150L
        const val PACE_STRONG_PLAYING_MS = 300L
        const val SMALL_MEM_FRAMES = 8
        const val READ_TRIES = 3
        const val NO_SLOT_TRIES = 10
        const val MAX_REJECT_LOGS = 12
        const val PROGRESS_LOG_EVERY = 60
        const val OWN_DECODER = "the 4K thumbnail decoder to be let go"
    }

    enum class State { LOOKING, FILLING, WAITING, DONE, STOPPED }

    private enum class Outcome { FILLED, UNUSABLE, REJECTED, STOP }

    private class Unusable(why: String) : Exception(why)

    /** Copy lengths whose keyframes followed no one shift: another cut, so another copy of that length is too. */
    private val otherCuts = HashSet<Long>()
    @Volatile private var unusableWhy: String? = null

    /**
     * How the copy's time maps to the playing file's: the same length, PAL timing, or one shift its keyframes agree on
     * (a few logos or credits more or less). A same-length copy is shifted only when its keyframes clearly say so.
     */
    private fun lineUp(ratio: Double?, copyMs: Long, index: MediaIndex): ReleaseMatch.Timing {
        if (ratio != null && ratio != 1.0) return ReleaseMatch.Timing(ratio)
        val maxUs = (if (ratio == null) ReleaseMatch.MAX_SHIFT_MS else ReleaseMatch.SAME_LENGTH_MS) * 1000L
        val found = if (own.keyframesUs.isEmpty()) null
            else ReleaseMatch.findShift(own.keyframesUs, index.keyframes.ptsUs, maxUs, own.durationMs * 1000L)
        fun note(f: ReleaseMatch.Shift) = Log.i(TAG, String.format(java.util.Locale.ROOT,
            "copy lined up by its keyframes: shift %+.2f s (%d keyframes on the same frame, %s by third, %d unshifted)",
            f.shiftUs / 1_000_000.0, f.pairs, f.thirds.joinToString("/"), f.atZero))
        if (ratio == null) {
            if (found == null) {
                otherCuts += copyMs
                throw Unusable("length ${copyMs / 1000} s against ${own.durationMs / 1000} s, and no one shift lines " +
                    "its keyframes up")
            }
            note(found)
            return ReleaseMatch.Timing(1.0, found.shiftUs)
        }
        if (found != null && kotlin.math.abs(found.shiftUs) > ReleaseMatch.SAME_FRAME_US &&
            found.pairs >= 2 * maxOf(found.atZero, 1)) {
            note(found)
            return ReleaseMatch.Timing(1.0, found.shiftUs)
        }
        return ReleaseMatch.Timing.SAME
    }
    private class Fetch(
        val m: Int, val kf: Int, val extractor: KeyframeExtractor, val shared: Boolean,
        val result: Deferred<Result<ByteArray?>>,
    )
    private class Halt(why: String) : Exception(why)
    private class GaveWay : Exception("gave way to playback")

    @Volatile var state = State.LOOKING
        private set
    /** Set once enough pictures are made for the check; cleared when the pass moves on to another copy. */
    @Volatile var ready: CopySet? = null
        private set
    /** Copy slots stored so far, all copies tried. */
    @Volatile var made = 0
        private set
    /** Own slots the current copy has no picture close enough for: the title's own pass makes them meanwhile. */
    @Volatile var ownOnlySlots: Set<Int> = emptySet()
        private set
    /** When a copy's link last came back open (elapsed realtime), 0 before. */
    @Volatile var linkOpenedAt = 0L
        private set
    /**
     * Since when the pass waits for the title's own 4K decoder to be let go, or for memory (elapsed realtime); 0 while
     * it does not.
     */
    @Volatile var ownDecoderWaitSince = 0L
        private set

    val filling: Boolean get() = state == State.FILLING
    val ended: Boolean get() = state == State.DONE || state == State.STOPPED

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val blocks = CopyHostBlocks.app
    @Volatile private var job: Job? = null
    val workerJob: Job? get() = job
    /** The store the pass writes now; verdicts about any other store are old and ignored. */
    @Volatile private var currentKey: String? = null
    @Volatile private var reader: HttpRangeReader? = null
    @Volatile private var stopping = false
    @Volatile private var rejectedKey: String? = null
    @Volatile private var confirmedKey: String? = null
    @Volatile private var undecidedKey: String? = null
    @Volatile private var verdictWhy = ""
    @Volatile private var requestStartAt = 0L
    @Volatile private var requestEndAt = 0L
    private var seenStallAt = 0L
    private var quietUntil = 0L
    private var lastPlaying = false
    private var stopWhy: String? = null
    private val headroom = PlaybackHeadroom()
    /** Bumped when playback takes lanes back: reads started before it are cancelled and fetched again later. */
    @Volatile private var givenWay = 0
    @Volatile private var watchJob: Job? = null
    @Volatile private var laneCap = Int.MAX_VALUE
    @Volatile private var ledgerUrl: String? = null
    private var lanesLogged = ""

    fun start() {
        seenStallAt = lastStallAtMs()
        job = scope.launch {
            try {
                run()
            } catch (ce: CancellationException) {
                if (!ended) state = State.STOPPED
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG, "ended: ${t.javaClass.simpleName}: ${t.message}")
                state = State.STOPPED
            }
        }
        watchJob = scope.launch { watchWhileFetching() }.also { w -> job?.invokeOnCompletion { w.cancel() } }
    }

    fun stop(why: String) {
        if (stopping) return
        stopping = true
        if (!ended) Log.i(TAG, "stopped: $why ($made thumbnails from copies)")
        reader?.close()
        job?.cancel()
        watchJob?.cancel()
        if (!ended) state = State.STOPPED
    }

    fun cancelReads() {
        reader?.cancel()
    }

    /** The picture check found [key]'s pictures to be another picture: the next copy is tried. */
    fun rejected(key: String, why: String) {
        if (key != currentKey) return
        verdictWhy = why
        rejectedKey = key
        reader?.cancel()
    }

    fun confirmed(key: String) {
        if (key == currentKey) confirmedKey = key
    }

    /** The check could not decide on [key]: no more pictures are made from copies this session. */
    fun undecided(key: String, why: String) {
        if (key != currentKey) return
        verdictWhy = why
        undecidedKey = key
    }

    private fun readyAt(): Int = if (screenUp()) READY_PICTURES_SCREEN else READY_PICTURES

    private fun isRejected(): Boolean = currentKey != null && rejectedKey == currentKey

    private fun sincePick(): String =
        if (own.pickedAtMs > 0L) ", ${SystemClock.elapsedRealtime() - own.pickedAtMs} ms after the stream pick" else ""

    private suspend fun run() {
        try {
            look()
        } finally {
            headStart?.release()
        }
    }

    private suspend fun look() {
        MemoryGovernor.init(context)
        Log.i(TAG, "looking for ${when {
            own.usenet -> "a copy of this Usenet title"
            own.awayServer -> "a copy of this title from a media server away from home"
            own.lighterOnly -> "a lighter copy of this title"
            else -> "a smaller copy of this 4K title"
        }} " +
            "(${own.store.slots().size} own thumbnails stored, ${own.durationMs / 1000} s)")
        val fourK = own.maxHeight >= CopyChoice.FOUR_K && !own.usenet && !own.awayServer
        fun verdict(o: CopyOffer, offers: CopyOffers, editions: Set<CopyChoice.Edition>): String? =
            (if (own.awayServer && !CopyChoice.awayUsable(o)) "a media server away from home" else null)
                ?: CopyChoice.rejection(o, offers.playingText, own.durationMs, own.maxHeight, own.usenet, fourKCopies = fourK,
                    ownEditions = editions)
                ?: (if (fourK) CopyChoice.fourKCopyRejection(o, offers.playing, sources.multiRange(o)) else null)
                ?: (if (own.lighterOnly) CopyFirst.notLighter(o, own.fileLength, own.durationMs) else null)
        val book = runCatching { CopySourceBooks.app(context) }.getOrNull()
        var checks = 0
        var opens = 0
        // The same file is often listed by several add-ons: once turned down, its other listings are not opened.
        val refused = ArrayList<CopyOffer>()
        fun alreadyTurnedDown(o: CopyOffer): Boolean {
            val same = refused.firstOrNull { CopyFirst.sameFile(it, o) } ?: return false
            Log.i(TAG, "not tried: ${o.label} - the same file as ${same.label}, already turned down")
            return true
        }

        /** Opens and tries [offer]; true when the pass ended with it. */
        suspend fun attempt(offer: CopyOffer): Boolean {
            val openStart = SystemClock.elapsedRealtime()
            var opened: CopyLink? = null
            val link = runCatching { withTimeoutOrNull(OPEN_TIMEOUT_MS) {
                (headStart?.take(offer) ?: sources.open(offer)).also { opened = it }
            } }
                .onFailure {
                    if (it is CancellationException) {
                        opened?.let { o -> o.dropPreread(); runCatching { o.done() } }
                        throw it
                    }
                }.getOrNull()
            if (link == null) {
                opened?.let { it.dropPreread(); runCatching { it.done() } }
                Log.i(TAG, "${offer.label}: no link")
                if (!stopping) learn(book, offer, CopySourceBook.SLOW, "no link")
                return false
            }
            val openMs = SystemClock.elapsedRealtime() - openStart
            if (openMs >= CopySourceBook.SLOW_OPEN_MS && headStart?.owns(offer) != true) {
                learn(book, offer, CopySourceBook.SLOW, "link open in $openMs ms")
            }
            opens++
            linkOpenedAt = SystemClock.elapsedRealtime()
            Log.i(TAG, "trying ${describe(offer)} via ${link.via}${sincePick()}")
            unusableWhy = null
            val outcome = try {
                tryCopy(offer, link)
            } finally {
                link.dropPreread()
                runCatching { link.done() }
            }
            if (outcome == Outcome.REJECTED || (outcome == Outcome.UNUSABLE && CopyFirst.turnsDownTheFile(unusableWhy))) {
                refused += offer
            }
            if (outcome == Outcome.UNUSABLE && unusableWhy?.endsWith("not the film") == true) {
                learn(book, offer, CopySourceBook.WRONG, "a sample or another file, not the film")
            }
            when (outcome) {
                Outcome.FILLED -> when (awaitVerdict()) {
                    Outcome.FILLED -> {
                        runCatching { book?.cleared(offer) }
                        end(State.DONE, "${offer.label}: confirmed by pictures")
                        return true
                    }
                    Outcome.REJECTED -> {
                        checks++
                        refused += offer
                        turnedDown(book, offer)
                    }
                    else -> {
                        end(State.STOPPED, stopWhy ?: "the picture check could not decide ($verdictWhy)")
                        return true
                    }
                }
                Outcome.REJECTED -> {
                    checks++
                    turnedDown(book, offer)
                }
                Outcome.STOP -> {
                    end(State.STOPPED, stopWhy ?: "stopped")
                    return true
                }
                Outcome.UNUSABLE -> Unit
            }
            ready = null
            currentKey = null
            ownOnlySlots = emptySet()
            state = State.LOOKING
            return false
        }

        // The copy whose link opened at the stream pick goes first, on the list it was chosen from: the whole list is
        // read and ranked again only when it is not used.
        val early = headStart?.takeIf { it.opened }
        val earlyOffer = early?.offer
        val earlyList = early?.listed
        var first: CopyOffer? = null
        if (early != null && earlyOffer != null && earlyList != null) {
            val why = verdict(earlyOffer, earlyList, CopyChoice.editions(earlyList.playingText))
            if (why == null) {
                first = earlyOffer
                Log.i(TAG, "copy first: the pass starts ${SystemClock.elapsedRealtime() - early.openedAtMs} ms after the " +
                    "link of ${earlyOffer.label} opened${sincePick()} - it goes first, the other sources are ranked " +
                    "only if it is not used")
                if (attempt(earlyOffer)) return
            } else {
                Log.i(TAG, "copy first: ${earlyOffer.label}, opened at the stream pick, not used - $why")
            }
        }
        if (stopping) return end(State.STOPPED, stopWhy ?: "stopped")
        runCatching { sources.loadNow() }.onFailure { if (it is CancellationException) throw it }
        val offers = awaitOffers() ?: return end(State.STOPPED, "the player's source list did not load")
        val usable = ArrayList<CopyOffer>()
        val reasons = ArrayList<String>()
        var logged = 0
        val editions = CopyChoice.editions(offers.playingText)
        for (o in offers.offers) {
            val why = verdict(o, offers, editions)
            if (why == null) {
                usable += o
            } else {
                reasons += why
                if (logged++ < MAX_REJECT_LOGS) Log.i(TAG, "not used: ${o.label} - $why")
            }
        }
        if (reasons.isNotEmpty()) Log.i(TAG, "not used: " + CopyChoice.reasonTally(reasons))
        val ranked = CopyChoice.order(usable.filter { o -> first == null || !CopyFirst.sameOffer(o, first) }, own.usenet,
            own.durationMs, own.awayServer, penalised = { book?.penalised(it) == true }) { sources.multiRange(it) }
        if (ranked.isEmpty() && first == null) {
            return end(State.DONE, "no copy of this title among ${offers.offers.size} sources fits")
        }
        if (ranked.isEmpty()) Log.i(TAG, "no other copy of this title among ${offers.offers.size} sources fits")
        else Log.i(TAG, "${ranked.size} ${if (first != null) "other " else ""}possible copies${sincePick()}, in order: " +
            ranked.take(5).joinToString("; ") { describe(it) })
        for (offer in ranked) {
            if (stopping || checks >= CopyChoice.MAX_TRIES || opens >= MAX_OPENS) break
            if (alreadyTurnedDown(offer)) continue
            if (attempt(offer)) return
        }
        if (own.awayServer) {
            return end(State.DONE, (if (checks > 0) "no copy matched the pictures of the media server stream"
                else "no copy could be used") + " - only the spot a seek asks for")
        }
        val ownFile = if (own.usenet) "the Usenet stream" else "the 4K file"
        end(State.DONE, if (checks > 0) "no copy matched the pictures of $ownFile - $ownFile makes the rest" else "no copy could be used")
    }

    /**
     * The picture check turned [offer] down. Against an HDR or Dolby Vision title the own pictures come through another
     * colour path than an SDR copy's, so that is not held against the copy's source.
     */
    private fun turnedDown(book: CopySourceBook?, offer: CopyOffer) {
        if (CopySourceBook.countsWrong(own.hdr)) {
            learn(book, offer, CopySourceBook.WRONG, "turned down by the picture check")
        } else {
            Log.i(TAG, "${offer.label}: turned down by the picture check - not counted against its source " +
                "(the playing title is HDR or Dolby Vision)")
        }
    }

    private fun learn(book: CopySourceBook?, offer: CopyOffer, weight: Double, why: String) {
        val score = runCatching { book?.count(offer, weight) }.getOrNull() ?: return
        Log.i(TAG, "${offer.label}: $why - counted against ${CopySourceBook.keyOf(offer)} (now %.1f%s)".format(score,
            if (score >= CopySourceBook.LAST_AT) ", its copies rank last for now" else ""))
    }

    /** The decode tier of the copy being read: 4K only for a 4K copy of a 4K title. */
    @Volatile private var copyTier = DecodeTier.SW_UP_TO_1080P

    private fun end(s: State, why: String) {
        if (ended) return
        Log.i(TAG, "${if (s == State.DONE) "done" else "stopped"}: $why ($made thumbnails from copies)")
        state = s
    }

    private fun describe(o: CopyOffer): String =
        "${o.label} [${o.kind.name.lowercase()}, ${o.height}p ${o.codec.name}${if (o.hdr) " HDR" else ""}" +
            "${if (o.web) " WEB" else ""}${o.sizeBytes?.let { ", ${it / (1024 * 1024)} MB" } ?: ""}" +
            "${o.durationMs?.let { ", ${it / 1000} s" } ?: ""}${if (CopyChoice.heavy(o, own.durationMs)) ", heavy" else ""}]"

    private suspend fun awaitOffers(): CopyOffers? {
        val deadline = SystemClock.elapsedRealtime() + LIST_WAIT_MS
        var said = false
        while (!stopping && SystemClock.elapsedRealtime() < deadline) {
            val o = runCatching { sources.offers() }.onFailure { if (it is CancellationException) throw it }.getOrNull()
            if (o != null) return o
            if (!said) {
                said = true
                Log.d(TAG, "waiting for the player's source list")
            }
            delay(LIST_POLL_MS)
        }
        return null
    }

    private suspend fun awaitVerdict(): Outcome {
        val key = currentKey ?: return Outcome.STOP
        state = State.WAITING
        Log.i(TAG, "copy made ($made thumbnails), waiting for the picture check")
        while (!stopping) {
            when (key) {
                confirmedKey -> return Outcome.FILLED
                rejectedKey -> {
                    Log.i(TAG, "the picture check turned the copy down ($verdictWhy)")
                    return Outcome.REJECTED
                }
                undecidedKey -> return Outcome.STOP
            }
            delay(2_000L)
        }
        return Outcome.STOP
    }

    private suspend fun tryCopy(offer: CopyOffer, link: CopyLink): Outcome {
        val host = RateLimitedHosts.hostOf(link.url)
        host?.let { h -> blocks.blocked(h, System.currentTimeMillis())?.let { why ->
            Log.i(TAG, "${offer.label}: that server is left alone for now ($why)")
            return Outcome.UNUSABLE
        } }
        if (own.usenet && SlowStartServer.isUsenet(link.url)) {
            Log.i(TAG, "${offer.label}: not used - a Usenet stream")
            return Outcome.UNUSABLE
        }
        ledgerUrl = link.url
        laneCap = link.maxLanes.coerceAtLeast(1)
        if (laneCap < Int.MAX_VALUE) ConnectionLedger.app.capThumbnails(link.url, laneCap)
        if (!awaitReady(null, host)) {
            link.dropPreread()
            return Outcome.STOP
        }
        val asked = SystemClock.elapsedRealtime()
        val pre = link.takePreread(CopyFirst.PREREAD_WAIT_MS)
        val r = pre?.reader ?: HttpRangeReader(link.url, link.headers, thumbnailHttpClient(link.headers),
            slotWaitMs = ConnectionLedger.START_SLOT_WAIT_MS)
        reader = r
        try {
            val t0 = SystemClock.elapsedRealtime()
            val linkHead = link.head?.takeIf { pre == null && link.length > 0L && it.isNotEmpty() }
            if (linkHead != null) r.noteLength(link.length)
            val head = pre?.head ?: linkHead ?: request(host) { r.read(0, MkvIndexReader.HEAD_BYTES) }
            val headMs = pre?.headMs ?: if (linkHead != null) 0L else SystemClock.elapsedRealtime() - t0
            val fileLength = r.totalLength
            ledgerUrl = r.resolvedUrl
            if (laneCap < Int.MAX_VALUE) ConnectionLedger.app.capThumbnails(r.resolvedUrl, laneCap)
            // A Usenet title may borrow the same release from elsewhere, never through another Usenet stream.
            if (own.usenet) {
                if (SlowStartServer.isUsenet(r.resolvedUrl)) throw Unusable("a Usenet stream")
            } else if (fileLength == own.fileLength) {
                throw Unusable("the same file as playback")
            }
            val mkv = MkvIndexReader.looksLikeMatroska(head)
            if (!mkv && !Mp4IndexReader.looksLikeMp4(head)) throw Unusable("not MKV or MP4")
            var idx: MediaIndex? = pre?.index
            val split = SplitRangeReader(r, CopyFirst.indexParts(MemoryGovernor.isStrongBox, laneCap,
                ConnectionLedger.app.thumbnailRoom(r.resolvedUrl)))
            val (durationUs, video) = pre?.index?.let { it.durationUs to it.video } ?: if (mkv) {
                request(host) { MkvIndexReader.peek(r, head) }.let { it.durationUs to it.video }
            } else {
                request(host) { Mp4IndexReader.read(split, head) }.also { idx = it }.let { it.durationUs to it.video }
            }
            val copyMs = durationUs / 1000L
            val ratio = ReleaseMatch.ratio(own.durationMs, copyMs)
            val shiftable = ReleaseMatch.shiftCandidate(own.durationMs, copyMs) && own.keyframesUs.isNotEmpty()
            if (copyMs in 1 until NOT_A_FILM_MS && own.durationMs >= NOT_A_FILM_MS) {
                throw Unusable("only ${copyMs / 1000} s long, not the film")
            }
            if (ratio == null && !shiftable) throw Unusable("length ${copyMs / 1000} s against ${own.durationMs / 1000} s")
            if (copyMs in otherCuts) throw Unusable("length ${copyMs / 1000} s, the cut of a copy already turned down")
            val tier = MemoryGovernor.tierFor(video.width, video.height)
            val fourKCopy = tier == DecodeTier.SW_4K && own.maxHeight >= CopyChoice.FOUR_K && offer.height == CopyChoice.FOUR_K
            if (tier != DecodeTier.SW_UP_TO_1080P && !fourKCopy) {
                throw Unusable("${video.width}x${video.height} is not a smaller copy")
            }
            copyTier = tier
            if (CopyChoice.heightClass(video.width, video.height) > own.maxHeight) {
                throw Unusable("${video.width}x${video.height} is above the playing ${own.maxHeight}p")
            }
            val natural = ThumbGeometry.outputSize(video.width, video.height, 1, 1, video.displayAspect)
            CopyFit.plan(natural[0], natural[1], own.pictureW, own.pictureH)
                ?: throw Unusable("picture shape ${natural[0]}x${natural[1]} against ${own.pictureW}x${own.pictureH}")
            val index = idx ?: request(host) { MkvIndexReader.read(split, head) }
            val timing = lineUp(ratio, copyMs, index)
            val read = if (pre != null) {
                "header and index ${r.bytes / 1024} KB read as its link opened, in ${pre.readMs} ms, ready " +
                    "${(asked - pre.doneAtMs).coerceAtLeast(0L)} ms before the pass asked" +
                    (if (pre.doneAtMs > asked) " (waited ${pre.doneAtMs - asked} ms)" else "")
            } else {
                "header and index ${r.bytes / 1024} KB in ${SystemClock.elapsedRealtime() - t0} ms (first bytes " +
                    (if (linkHead != null) "came with the link's redirects" else "with the link's redirects $headMs ms") +
                    ", ${if (link.prewarmed) "link resolved ahead" else "link resolved now"})"
            }
            Log.i(TAG, "${offer.label}: ${video.codec} ${video.width}x${video.height}, ${copyMs / 1000} s (${timing.describe()}), " +
                "${index.keyframes.count} keyframes, $read${sincePick()}")
            val indexMs = pre?.readMs ?: (SystemClock.elapsedRealtime() - t0)
            if (indexMs >= CopySourceBook.SLOW_INDEX_MS) {
                learn(runCatching { CopySourceBooks.app(context) }.getOrNull(), offer, CopySourceBook.SLOW,
                    "header and index in $indexMs ms")
            }
            val rgb565 = runCatching { ImagePerformancePreferences(context).rgb565Enabled }.getOrDefault(true)
            val key = ThumbStore.copyKeyFor(own.identity, fileLength, own.pictureW, own.pictureH)
            if (own.store.verdictOn(key) == false) throw Unusable("its pictures did not match this title before")
            if (ThumbStore.isBusy(key)) throw Unusable("another pass is writing its thumbnails")
            ThumbStore.markBusy(key)
            val store = runCatching { ThumbStore.openKey(context, key, rgb565) }.getOrElse {
                ThumbStore.unmarkBusy(key)
                throw it
            }
            if (store.isMemoryOnly) {
                ThumbStore.unmarkBusy(key)
                stopWhy = "storage nearly full"
                return Outcome.STOP
            }
            r.slotWaitMs = ConnectionLedger.SLOT_WAIT_MS
            currentKey = key
            try {
                store.noteDuration(copyMs)
                if (store.cachedKeyframeIndex() == null) store.setKeyframeIndex(KeyframeIndexCodec.encode(index))
                val early = if (pre != null) link.takeEarly() else emptyList()
                return fill(offer, r, index, store, timing, copyMs, host, early)
            } finally {
                store.flush()
                ThumbStore.unmarkBusy(store.key)
            }
        } catch (e: Unusable) {
            unusableWhy = e.message
            Log.i(TAG, "${offer.label}: not used - ${e.message}")
            return Outcome.UNUSABLE
        } catch (e: Halt) {
            stopWhy = e.message
            return Outcome.STOP
        } catch (e: IOException) {
            if (stopping) return Outcome.STOP
            Log.i(TAG, "${offer.label}: not used - reads failed (${e.javaClass.simpleName})")
            return Outcome.UNUSABLE
        } finally {
            reader = null
            r.close()
        }
    }

    private suspend fun fill(
        offer: CopyOffer, r: HttpRangeReader, idx: MediaIndex, store: ThumbStore, timing: ReleaseMatch.Timing, copyMs: Long, host: String?,
        early: List<CopyEarlyPicture> = emptyList(),
    ): Outcome {
        val copyCount = ReleaseMatch.slotCount(copyMs, SPACING_MS)
        val owners = CopyChoice.ownSlotsByCopySlot(own.slotPtsUs.size, timing, copyCount, SPACING_MS)
        val pts = idx.keyframes.ptsUs
        // The copy's keyframe nearest to where a 4K seek lands, not to the slot centre.
        val kfOf = IntArray(copyCount) { -1 }
        for ((m, served) in owners) {
            val s = UsenetCopy.pictureFor(served, own.screenStride)
            val landing = own.slotPtsUs[s].takeIf { it >= 0 } ?: (s * SPACING_MS * 1000L + SPACING_MS * 500L)
            kfOf[m] = idx.keyframes.nearest(timing.toCopyUs(landing).coerceAtLeast(0L))
        }
        val far = HashSet<Int>()
        for (s in own.slotPtsUs.indices) if (own.slotPtsUs[s] >= 0L) far += s
        for ((m, served) in owners) {
            val kf = kfOf[m].takeIf { it >= 0 } ?: continue
            val at = timing.toOwnUs(pts[kf])
            for (s in served) if (kotlin.math.abs(own.slotPtsUs[s] - at) <= own.wideUs) far -= s
        }
        ownOnlySlots = far
        if (far.isNotEmpty()) Log.i(TAG, "${far.size} slots this copy cannot serve go to the title's own pass at once")
        val kfSlots = HashMap<Int, MutableList<Int>>()
        kfOf.forEachIndexed { m, kf -> if (kf >= 0) kfSlots.getOrPut(kf) { ArrayList() }.add(m) }
        val anchor = timing.mapSlot(own.anchorSlot, copyCount, SPACING_MS).coerceAtLeast(0)
        // The pictures the check can decide on come first: the copy is confirmed before the rest is made.
        val gaps = ReleaseMatch.copyGaps(owners, { m -> kfOf[m].takeIf { it >= 0 }?.let { pts[it] } ?: -1L },
            own.slotPtsUs, timing).filter { (m, _) -> owners[m]?.any { !own.store.has(slotUs(it)) } == true }
        val confirm = ReleaseMatch.pickCheckSlots(gaps, copyCount)
        val confirmSet = confirm.toHashSet()
        val sameFrame = gaps.count { it.first in confirmSet && kotlin.math.abs(it.second) <= ReleaseMatch.SAME_FRAME_US }
        val order = confirm + UsenetCopy.screenFirst(
            SeekGrid.latticeOrder(copyCount, STRIDES, anchor, LOCAL_BEFORE_SLOTS, LOCAL_AFTER_SLOTS), owners, own.screenStride)
            .filter { it !in confirmSet }
        val coarse = (0 until copyCount step STRIDES.first()).toList()
        val extractors = ArrayList<KeyframeExtractor>()
        val spare = ArrayDeque<KeyframeExtractor>()
        fun bytesRead() = extractors.sumOf { it.bytesRead }
        // Keyframes in batches where the server allows it, as for the title's own pass; single reads while the film plays.
        val batch = BatchRangeReader(r, MultiRangeSupport.app(context),
            if (MemoryGovernor.isStrongBox) BatchRangeReader.STRONG_BUDGET else BatchRangeReader.SMALL_BUDGET)
        val k = idx.keyframes
        fun planned(kf: Int): BatchRangeReader.Planned? =
            if (idx.kind == ContainerKind.MATROSKA) k.relPos[kf].takeIf { it >= 0 }?.let { BatchRangeReader.Planned(k.offset[kf] + it, 0) }
            else k.size[kf].takeIf { it > 0 }?.let { BatchRangeReader.Planned(k.offset[kf], it) }
        val decoder = FfmpegThumbDecoder()
        // A 4K copy decodes in software only: the 4K hardware decoder's memory rules belong to the title's own pass.
        val hardware = if (HardwareThumbSwitch.on && copyTier == DecodeTier.SW_UP_TO_1080P) CopyHardware(offer.label) else null
        val attempts = HashMap<Int, Int>()
        val dv5 = idx.video.dolbyVision?.profile == 5
        val smallBox = !MemoryGovernor.isStrongBox
        var pictures = 0
        val t0 = SystemClock.elapsedRealtime()
        state = State.FILLING
        Log.i(TAG, "making thumbnails from ${offer.label} (${order.size} slots, ${store.slots().size} stored already, " +
            "${confirm.size} for the picture check first, $sameFrame on the same frame)" +
            if (pacer != null) ", behind the dropped-frame pacer while playing" else "")
        if (early.isNotEmpty()) {
            val fit = CopyEarlyFit.slots(early, kfSlots, own.pictureW, own.pictureH,
                wanted = { m -> !store.has(slotUs(m)) && owners[m]?.all { own.store.has(slotUs(it)) } != true })
            var dropped = 0
            for ((p, slots, plan) in fit.used) {
                val bmp = atBackground { BitmapFactory.decodeByteArray(p.jpeg, 0, p.jpeg.size) } ?: continue
                atBackground { store.put(LongArray(slots.size) { slotUs(slots[it]) }, pts[p.kf], compose(bmp, plan)) }
                made += slots.size
                pictures++
                dropped += slots.size
            }
            Log.i(TAG, "copy first: ${early.size} pictures made before the session, $pictures put into $dropped slots " +
                "(${fit.otherKeyframe} for another keyframe or slots already made, ${fit.otherShape} of another size)" +
                sincePick())
            if (pictures > 0) {
                Log.i(TAG, "first thumbnail from ${offer.label} made before the session, in place " +
                    "${SystemClock.elapsedRealtime() - t0} ms after its index was taken${sincePick()}")
                if (!MemoryGovernor.isStrongBox) store.trimMemory(SMALL_MEM_FRAMES)
                if (pictures >= readyAt() || coarse.all { store.has(slotUs(it)) } ||
                    (confirm.size >= ReleaseMatch.CHECKS_NEEDED && confirm.all { store.has(slotUs(it)) })) {
                    ready = CopySet(store, timing, copyCount, offer.label)
                }
            }
        }
        try {
            val end: Outcome? = coroutineScope {
                val pending = ArrayDeque<Fetch>()
                val again = ArrayDeque<Int>()
                val underWay = HashSet<Int>()
                val refused = AtomicBoolean(false)
                var next = 0
                var out: Outcome? = null
                try {
                    fun planBatches() {
                        if (lastPlaying) {
                            batch.plan(emptyList())
                            return
                        }
                        val coming = (again.asSequence() + order.asSequence().drop(next))
                            .map { kfOf[it] }
                            .filter { kf -> kf >= 0 && kf !in underWay && (attempts[kf] ?: 0) < 2 }
                            .filter { kf -> kfSlots[kf]?.any { !store.has(slotUs(it)) } == true }
                            .distinct()
                            .take(BatchRangeReader.PIECES * ThumbFetchLanes.MAX)
                        batch.plan(coming.mapNotNull { planned(it) }.toList())
                    }
                    work@ while (true) {
                        if (stopping) {
                            out = Outcome.STOP
                            break@work
                        }
                        if (isRejected()) {
                            out = Outcome.REJECTED
                            break@work
                        }
                        if (undecidedKey == store.key) {
                            out = Outcome.STOP
                            break@work
                        }
                        if (bytesRead() >= MAX_BYTES) {
                            Log.i(TAG, "byte limit reached (${bytesRead() / (1024 * 1024)} MB)")
                            break@work
                        }
                        val lanes = minOf(lanes(r.resolvedUrl, smallBox), laneCap)
                        if (lanes == 1 && pending.size > 1 && !refused.get()) {
                            // Back to one at a time (the screen went down, or playback's buffer ran low): the queued
                            // pictures are fetched again later.
                            for (f in pending.asReversed()) {
                                f.result.cancel()
                                attempts[f.kf] = ((attempts[f.kf] ?: 1) - 1).coerceAtLeast(0)
                                underWay -= f.kf
                                again.addFirst(f.m)
                            }
                            pending.clear()
                            r.cancel()
                        }
                        planBatches()
                        while (!refused.get() && pending.size < lanes && bytesRead() < MAX_BYTES &&
                            (!lastPlaying || (pending.size < headroom.lanes.coerceAtLeast(1) &&
                                pending.count { it.result.isCompleted } < PlaybackHeadroom.DECODE_AHEAD))) {
                            val retried = again.isNotEmpty()
                            val m = if (retried) again.first() else order.getOrNull(next) ?: break
                            val kf = kfOf[m]
                            if (kf >= 0 && kf in underWay) break
                            val served = owners[m]
                            if (retried) again.removeFirst() else next++
                            if (kf < 0 || store.has(slotUs(m)) || served == null ||
                                served.all { own.store.has(slotUs(it)) } || (attempts[kf] ?: 0) >= 2) continue
                            if (!awaitReady(decoder, host)) {
                                out = if (isRejected()) Outcome.REJECTED else Outcome.STOP
                                break@work
                            }
                            attempts[kf] = (attempts[kf] ?: 0) + 1
                            val ex = spare.removeFirstOrNull() ?: KeyframeExtractor(idx, batch).also { extractors += it }
                            underWay += kf
                            val way = givenWay
                            pending += Fetch(m, kf, ex, shared = lanes > 1, async(Dispatchers.IO) {
                                runCatching { fetchKeyframe(ex, kf, host, way) }.also { if (it.exceptionOrNull() is Halt) refused.set(true) }
                            })
                        }
                        val f = pending.removeFirstOrNull() ?: break@work
                        val got = f.result.await()
                        underWay -= f.kf
                        spare += f.extractor
                        val failure = got.exceptionOrNull()
                        if (failure is GaveWay) {
                            attempts[f.kf] = ((attempts[f.kf] ?: 1) - 1).coerceAtLeast(0)
                            again.addFirst(f.m)
                            continue@work
                        }
                        if (failure != null) {
                            if (failure is CancellationException) throw failure
                            if (failure is Halt && isRejected()) {
                                out = Outcome.REJECTED
                                break@work
                            }
                            throw failure
                        }
                        val au = got.getOrNull() ?: continue@work
                        // Fetched next to others, or swap ran low meanwhile: playback, memory and the other decoder
                        // are asked again before decoding.
                        val swapLow = !MemoryGovernor.swapAllows(funded = false).allowed
                        if ((f.shared || swapLow) && !awaitReady(decoder, host)) {
                            out = if (isRejected()) Outcome.REJECTED else Outcome.STOP
                            break@work
                        }
                        if (swapLow && !MemoryGovernor.swapAllows(funded = false).allowed) continue@work
                        val kf = f.kf
                        val playing = lastPlaying
                        suspend fun counted(n: Int) {
                            made += n
                            pictures++
                            if (pictures == 1) Log.i(TAG, "first thumbnail from ${offer.label} " +
                                "${SystemClock.elapsedRealtime() - t0} ms after its index${sincePick()}")
                            if (!MemoryGovernor.isStrongBox) store.trimMemory(SMALL_MEM_FRAMES)
                            if (ready == null && (pictures >= readyAt() || coarse.all { store.has(slotUs(it)) } ||
                                    (confirm.size >= ReleaseMatch.CHECKS_NEEDED && confirm.all { store.has(slotUs(it)) }))) {
                                ready = CopySet(store, timing, copyCount, offer.label)
                            }
                            if (pictures % PROGRESS_LOG_EVERY == 0) {
                                Log.i(TAG, "$pictures thumbnails from ${offer.label} in " +
                                    "${(SystemClock.elapsedRealtime() - t0) / 1000} s, ${bytesRead() / (1024 * 1024)} MB")
                            }
                            delay(pace(playing))
                        }
                        val hwPicture = hardware?.make(au, idx.video, hardwarePhase())
                        if (hwPicture != null) {
                            val slots = kfSlots[kf].orEmpty().filter { !store.has(slotUs(it)) }
                            if (slots.isEmpty()) {
                                hwPicture.recycle()
                                continue@work
                            }
                            atBackground { store.put(LongArray(slots.size) { slotUs(slots[it]) }, pts[kf], hwPicture, jpeg = true) }
                            counted(slots.size)
                            continue@work
                        }
                        val frame = paced(playing) { atBackground { decoder.decode(au, idx.video, threads = 1) } }
                            ?: continue@work
                        if (dv5 && !frame.colour.doviIpt) throw Unusable("Dolby Vision 5 without decoder metadata")
                        val decision = ColourClassifier.classify(idx.video.colour, frame.colour, idx.video.dolbyVision)
                        val size = ThumbGeometry.outputSize(frame.width, frame.height, frame.sarNum, frame.sarDen,
                            idx.video.displayAspect)
                        val plan = CopyFit.plan(size[0], size[1], own.pictureW, own.pictureH) ?: continue@work
                        hardware?.learn(decision, plan)
                        val rendered = paced(playing) {
                            atBackground { decoder.render(frame, decision, plan.renderW, plan.renderH) }
                        } ?: continue@work
                        hardware?.softwareMade(rendered)
                        val slots = kfSlots[kf].orEmpty().filter { !store.has(slotUs(it)) }
                        if (slots.isEmpty()) continue@work
                        paced(playing) {
                            atBackground { store.put(LongArray(slots.size) { slotUs(slots[it]) }, pts[kf], compose(rendered, plan)) }
                        }
                        counted(slots.size)
                    }
                } finally {
                    if (pending.isNotEmpty()) {
                        pending.forEach { it.result.cancel() }
                        r.cancel()
                    }
                }
                out
            }
            if (end != null) return end
        } finally {
            runCatching { decoder.close() }
            hardware?.close()
        }
        if (pictures == 0 && store.slots().isEmpty()) {
            Log.i(TAG, "${offer.label}: no thumbnail could be made")
            return Outcome.UNUSABLE
        }
        if (ready == null) ready = CopySet(store, timing, copyCount, offer.label)
        Log.i(TAG, "${offer.label}: $pictures thumbnails in ${(SystemClock.elapsedRealtime() - t0) / 1000} s, " +
            "${bytesRead() / (1024 * 1024)} MB, stored=${store.slots().size} of $copyCount${sincePick()}")
        return Outcome.FILLED
    }

    private fun slotUs(slot: Int): Long = slot.toLong() * SPACING_MS * 1000L

    /** Hardware only with no film decoding: behind the generating screen (playback held) or after the player closed. */
    private fun hardwarePhase(): FetchPhase = when {
        playerClosed() -> FetchPhase.PLAYER_CLOSED
        screenUp() && !lastPlaying -> FetchPhase.GENERATING
        else -> FetchPhase.PLAYBACK
    }

    /**
     * A copy's thumbnails from the hardware decoder, one decoder, under the same rules as the title's own: the copy's
     * first keyframe is decoded in software for colour and size, a failure sends the rest of the copy to software and
     * counts against the box. Behind the generating screen the held player keeps its decoder, so only where the
     * decoder allows two at once. Pass thread only.
     */
    private inner class CopyHardware(private val label: String) {
        private var decoder: HardwareThumbDecoder? = null
        private var plan: HardwarePlan? = null
        private var fit: CopyFit.Plan? = null
        private var off: String? = null
        private var whyLogged: String? = null
        private var flatSeen = false
        private var name: String? = null

        fun learn(decision: ColourDecision, p: CopyFit.Plan) {
            if (plan != null || off != null) return
            if (HardwareRoute.planUsable(decision)) {
                plan = HardwarePlan(decision, p.renderW, p.renderH)
                fit = p
            } else {
                off = decision.reason
            }
        }

        /** The finished copy thumbnail, or null when this keyframe is made in software. */
        fun make(au: ByteArray, track: VideoTrack, phase: FetchPhase): Bitmap? {
            val choice = choice(track, phase)
            if (choice == null) {
                closeDecoder()
                return null
            }
            val p = plan ?: return null
            val f = fit ?: return null
            val dec = decoder?.takeIf { it.codecName == choice.name } ?: run {
                closeDecoder()
                HardwareThumbDecoder(choice.name).also {
                    decoder = it
                    name = choice.name
                    Log.i(TAG, "hardware decoder for $label: ${choice.name} on Android API ${Build.VERSION.SDK_INT}")
                }
            }
            val bmp = try {
                atBackground { dec.make(au, track, p) }
            } catch (e: HardwareThumbFailure) {
                if (!e.keyframeOnly) failed(e.why)
                return null
            }
            if (HardwarePicture.flat(bmp.width, bmp.height, bmp::getPixel)) {
                // Black or one colour: the software decode of the same keyframe decides.
                bmp.recycle()
                flatSeen = true
                return null
            }
            return compose(bmp, f)
        }

        /** After a software render of a keyframe the hardware gave as one colour. */
        fun softwareMade(rendered: Bitmap) {
            if (!flatSeen) return
            flatSeen = false
            if (!HardwarePicture.flat(rendered.width, rendered.height, rendered::getPixel)) {
                failed("one-colour picture where software shows one")
            }
        }

        private fun choice(track: VideoTrack, phase: FetchPhase): HardwareCodecs.Choice? {
            val c = HardwareCodecs.choose(track)
            val blocked = c != null &&
                runCatching { HardwareFailures.book(context).blocked(HardwareFailures.key(c.name)) }.getOrDefault(false)
            val why = HardwareRoute.softwareBecause(Build.VERSION.SDK_INT, phase, track.codec, track.bitDepth,
                track.dolbyVision?.profile, off, blocked, c != null) ?: if (phase == FetchPhase.GENERATING && c != null &&
                c.maxInstances < 2) "one decoder at a time, the held player has it"
            else if (phase == FetchPhase.GENERATING && c != null && !MemoryGovernor.isStrongBox)
                "small box, the held player keeps the video memory" else null
            if (why == null) return c
            if (why != whyLogged && phase != FetchPhase.PLAYBACK && why != off) {
                whyLogged = why
                Log.i(TAG, "software for $label: $why")
            }
            return null
        }

        private fun failed(why: String) {
            if (off != null) return
            off = why
            closeDecoder()
            val n = name ?: return
            val blocked = runCatching { HardwareFailures.book(context).failed(HardwareFailures.key(n)) }.getOrDefault(false)
            Log.w(TAG, "back to software for $label: $why" + if (blocked) " ($n failed " +
                "${HardwareFailureBook.FAILURES} times on this box: software for ${HardwareFailureBook.BLOCK_DAYS} days)" else "")
        }

        private fun closeDecoder() {
            decoder?.let { runCatching { it.close() } }
            decoder = null
        }

        fun close() = closeDecoder()
    }


    private fun lanes(url: String, smallBox: Boolean): Int {
        val ledger = ConnectionLedger.app
        val screen = screenUp() && !lastPlaying
        val n = UsenetCopy.fetches(screenUp(), lastPlaying, ledger.thumbnailRoom(url), smallBox,
            rateLimited = RateLimitedHosts.appSession.contains(url), torBox = LedgerKey.of(url) == LedgerKey.TORBOX,
            refused = ledger.refusedLately(url), headroom = headroom.lanes, surplus = lastPlaying && headroom.surplus)
        val key = "$n/$screen/$lastPlaying"
        if (key != lanesLogged) {
            lanesLogged = key
            val where = when {
                screen -> "generating screen"
                lastPlaying -> "playing, ${headroom.why}"
                else -> "paused"
            }
            Log.i(TAG, "lanes=$n ${ledger.budgetNote(url)} ($where)")
        }
        return n
    }

    private fun pace(playing: Boolean): Long = when {
        !playing -> if (screenUp()) 0L else PACE_PAUSED_MS
        pacer != null -> synchronized(pacer) { pacer.paceMs }
        else -> PACE_STRONG_PLAYING_MS
    }

    private fun compose(src: Bitmap, plan: CopyFit.Plan): Bitmap {
        if (plan.top == 0 && src.width == own.pictureW && src.height == own.pictureH) return src
        val out = Bitmap.createBitmap(own.pictureW, own.pictureH, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.BLACK)
            drawBitmap(src, 0f, plan.top.toFloat(), null)
        }
        src.recycle()
        return out
    }

    /** A copy request; a server that turns it away is left alone ([CopyHostBlocks]) and the pass stops. */
    private fun <T> request(host: String?, block: () -> T): T {
        requestStartAt = SystemClock.elapsedRealtime()
        try {
            return block()
        } catch (e: UnsupportedMediaException) {
            throw Unusable(e.message ?: "unsupported file")
        } catch (e: ConnectionLedger.NoSlotException) {
            throw e
        } catch (e: IOException) {
            if (stopping) throw e
            val status = e as? HttpRangeReader.HttpStatusException
            val refused = e is RateLimitedException || ConnectionLedger.refusalOf(e) != null ||
                status?.tooMany == true || status?.code == 401 || status?.code == 403
            if (refused) {
                (e as? RateLimitedException)?.let { RateLimitedHosts.appSession.note(it) }
                host?.let { blocks.refused(it, System.currentTimeMillis()) }
                throw Halt("the server turned a copy request away (${status?.let { "HTTP ${it.code}" } ?: e.javaClass.simpleName})" +
                    " - left alone for ${CopyHostBlocks.REFUSED_MS / 60_000} min")
            }
            if (status?.code == 404 || status?.code == 410) throw Unusable("link gone (HTTP ${status.code})")
            throw e
        } finally {
            requestEndAt = SystemClock.elapsedRealtime()
        }
    }

    private suspend fun fetchKeyframe(ex: KeyframeExtractor, kf: Int, host: String?, way: Int): ByteArray? {
        var failures = 0
        var noSlot = 0
        while (true) {
            try {
                return request(host) { ex.extract(kf) }
            } catch (e: ConnectionLedger.NoSlotException) {
                if (stopping) throw Halt("stopped")
                if (way != givenWay) throw GaveWay()
                if (++noSlot >= NO_SLOT_TRIES) throw Halt("no connection free on that server")
                delay(2_000L)
            } catch (e: IOException) {
                if (stopping || isRejected()) throw Halt("stopped")
                if (way != givenWay) throw GaveWay()
                if (++failures >= READ_TRIES) throw Unusable("reads keep failing (${e.javaClass.simpleName})")
                delay(2_000L * failures)
            }
        }
    }

    private class Sample(
        val playing: Boolean, val buffering: Boolean, val loading: Boolean, val aheadMs: Long, val toEnd: Boolean,
        val failed: Boolean,
    )

    private suspend fun sample(): Sample? = withContext(Dispatchers.Main) {
        val p = playerProvider() ?: return@withContext null
        val duration = if (p.duration == C.TIME_UNSET) 0L else p.duration
        Sample(
            playing = p.isPlaying,
            buffering = p.playbackState == Player.STATE_BUFFERING,
            loading = p.isLoading,
            aheadMs = p.bufferedPosition - p.currentPosition,
            toEnd = duration > 0 && p.bufferedPosition >= duration - 1_000L,
            failed = ConnectionLedger.sourceTrouble(p.playerError?.errorCode),
        )
    }

    /**
     * Waits until playback can spare a copy request and a decode. False when the pass has to stop: stopped, the copy
     * was turned down, or playback had trouble soon after a copy request.
     */
    private suspend fun awaitReady(decoder: FfmpegThumbDecoder?, host: String?): Boolean {
        var waitingFor: String? = null
        while (true) {
            if (stopping || isRejected()) return false
            val now = SystemClock.elapsedRealtime()
            val s = sample()
            val stall = lastStallAtMs()
            if (s?.failed == true || stall > seenStallAt) {
                val newStall = stall > seenStallAt
                val at = if (newStall) stall else now
                if (newStall) seenStallAt = stall
                if (blocks.blames(requestStartAt, requestEndAt, at, TROUBLE_WINDOW_MS)) {
                    host?.let { blocks.trouble(it, System.currentTimeMillis()) }
                    stopWhy = "playback ${if (newStall) "stalled" else "failed"} " +
                        "${((at - requestEndAt).coerceAtLeast(0L)) / 1000} s after a copy request - " +
                        "that server is left alone for the rest of the day"
                    return false
                }
                if (newStall) quietUntil = at + QUIET_AFTER_STALL_MS
            }
            val playback429 = ThumbRateLimit.playbackClampedNow() ||
                (ParallelRangeDataSource.hudClampLastHitAtMs > 0 &&
                    SystemClock.uptimeMillis() - ParallelRangeDataSource.hudClampLastHitAtMs < PLAYBACK_429_QUIET_MS)
            val refusalQuietMs = ConnectionLedger.app.refusalQuietMs(ledgerUrl)
            if (s != null) noteHeadroom(now, s)
            var memory = false
            val why = when {
                now < quietUntil -> "a quiet minute after a playback stall"
                s?.failed == true -> "a playback error"
                playback429 -> "playback was rate limited"
                refusalQuietMs > 0L -> "the server to calm down after too many requests"
                s == null -> null
                s.buffering -> "playback buffering"
                s.playing && headroom.lanes == 0 -> "playback's buffer to recover"
                s.playing && !headroom.surplus && !(s.aheadMs >= ConnectionLedger.HEALTHY_AHEAD_MS || s.toEnd ||
                    (s.aheadMs >= FULL_AHEAD_MS && !s.loading)) -> "a healthy playback buffer"
                pacer != null && s.playing && !synchronized(pacer) { pacer.allows(settleTarget = false) } ->
                    "a pause (dropped frames or a stall stopped copy decodes while playing)"
                decoder != null && !MemoryGovernor.isStrongBox && ownDecoderBusy() -> {
                    decoder.trim()
                    OWN_DECODER
                }
                decoder != null -> MemoryGovernor.allow(copyTier, decoderWarm = decoder.warm, addressSpace = true)
                    .takeIf { !it.allowed }?.let {
                        memory = true
                        decoder.trim()
                        "memory (${it.reason})"
                    }
                else -> null
            }
            ownDecoderWaitSince = if (why == OWN_DECODER || memory) ownDecoderWaitSince.takeIf { it > 0L } ?: now else 0L
            if (why == null) {
                if (waitingFor != null) Log.d(TAG, "carrying on (waited for $waitingFor)")
                lastPlaying = s?.playing == true
                return true
            }
            if (why != waitingFor) {
                Log.d(TAG, "waiting for $why")
                waitingFor = why
            }
            delay(if (memory) 5_000L else 2_000L)
        }
    }

    private fun noteHeadroom(now: Long, s: Sample) {
        headroom.source = ledgerUrl ?: reader?.resolvedUrl
        val before = headroom.gaveWay
        if (!headroom.note(now, s.playing, s.buffering, s.loading, s.aheadMs, s.toEnd)) return
        Log.i(TAG, if (headroom.lanes == 0) "lanes=0 (paused: ${headroom.why})"
            else "lanes up to ${headroom.lanes} (${headroom.why})")
        if (headroom.gaveWay != before && headroom.surplus && state == State.FILLING) {
            givenWay++
            reader?.cancel()
            Log.i(TAG, "gave way: copy reads cancelled (${headroom.why})")
        }
    }

    /** While copy reads run during playback, playback is looked at every second so they give way at once. */
    private suspend fun watchWhileFetching() {
        while (true) {
            delay(1_000L)
            if (stopping || !lastPlaying || state != State.FILLING) continue
            val s = sample() ?: continue
            noteHeadroom(SystemClock.elapsedRealtime(), s)
        }
    }

    private inline fun <T> paced(playing: Boolean, block: () -> T): T {
        val p = pacer
        if (p == null || !playing) return block()
        synchronized(p) { p.decodeStarted(SystemClock.elapsedRealtime()) }
        try {
            return block()
        } finally {
            synchronized(p) { p.decodeEnded(SystemClock.elapsedRealtime()) }
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
