package com.nuvio.tv.core.player.thumbnail

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The one thing started before the stream pick, kept by title: taken once after the pick, else given back when another
 * title's is held, when the list is left without a pick, or when nobody took it in time ([CopyPrePick.expired]).
 */
internal class PrePickSlot<T : Any>(private val now: () -> Long, private val giveBack: (T, String) -> Unit) {
    private var identity: String? = null
    private var item: T? = null
    private var heldAtMs = 0L
    private var pickedAtMs: Long? = null

    @get:Synchronized val heldFor: String? get() = identity.takeIf { item != null }

    private fun clear() {
        identity = null
        item = null
        heldAtMs = 0L
        pickedAtMs = null
    }

    /** Holds [t] for [id]; the one held before is given back. */
    @Synchronized
    fun hold(id: String, t: T) {
        val before = item
        clear()
        identity = id
        item = t
        heldAtMs = now()
        if (before != null && before !== t) giveBack(before, "another title's copy is held now")
    }

    /** [id] was picked: what is held for it, or null; another title's is given back. */
    @Synchronized
    fun picked(id: String): T? {
        val t = item ?: return null
        if (identity != id) {
            clear()
            giveBack(t, "another title was picked")
            return null
        }
        if (pickedAtMs == null) pickedAtMs = now()
        return t
    }

    /** What is held for [id], once, after its pick; anything else held is given back. */
    @Synchronized
    fun take(id: String): T? {
        val t = item ?: return null
        val mine = identity == id && pickedAtMs != null
        clear()
        if (!mine) giveBack(t, "another title's thumbnails started")
        return t.takeIf { mine }
    }

    /** The list of [id] was left: what is held for it is given back unless it was picked. */
    @Synchronized
    fun leave(id: String) {
        val t = item ?: return
        if (identity == id && pickedAtMs == null) {
            clear()
            giveBack(t, "the stream list was left without a pick")
        }
    }

    /** What is held for [id] is given back. */
    @Synchronized
    fun drop(id: String) {
        val t = item ?: return
        if (identity == id) {
            clear()
            giveBack(t, "no copy is opened at the pick")
        }
    }

    /** [t] is given back, and no longer held if it still was. */
    @Synchronized
    fun release(t: T, why: String) {
        if (item === t) clear()
        giveBack(t, why)
    }

    /** What is held is given back once its time is up. */
    @Synchronized
    fun expire() {
        val t = item ?: return
        if (CopyPrePick.expired(heldAtMs, pickedAtMs, now())) {
            clear()
            giveBack(t, if (pickedAtMs != null) "not taken in time after the pick" else "held too long without a pick")
        }
    }
}

/**
 * The lighter copy opened while the stream list is shown, before the pick: chosen for the title with the best 4K stream
 * of the list taken as the one that plays. At the pick it is handed to the player's thumbnails when it still fits the
 * stream picked (the same rules), else given back and the copy is opened at the pick as before.
 */
internal object CopyPrePick {
    private const val TAG = "ThumbCopy"
    /** The list stays up this long after it finished loading before the copy is opened. */
    const val LIST_DELAY_MS = 1_500L
    /** After the pick the title's thumbnails take it within this long, else it is given back. */
    const val TAKE_WINDOW_MS = 45_000L
    /** Without a pick it is given back this long after it was opened. */
    const val HOLD_MAX_MS = 5L * 60 * 1000
    /** The thumbnails wait this long at most for the check against the stream picked. */
    const val FIT_WAIT_MS = 5_000L

    /** The list opens a copy: thumbnails before play on for all titles, a title by its own id, the app's own player. */
    fun starts(
        prepare: ThumbPrepareMode, mode: SeekThumbMode, exactIdentity: Boolean, internalExoPlayer: Boolean,
        listShown: Boolean, picked: Boolean,
    ): Boolean = prepare != ThumbPrepareMode.OFF && mode == SeekThumbMode.ALL && exactIdentity && internalExoPlayer &&
        listShown && !picked

    /** The stream taken as the one that plays before the pick: the first 4K one of the list, never Usenet or an away server. */
    fun assumedPlaying(offers: List<CopyOffer>): CopyOffer? =
        offers.firstOrNull { it.height > 1080 && !it.remote && it.kind != CopyKind.USENET }

    /** The stream picked can take the copy: it is on the list the copy was chosen from, not a torrent, Usenet or away server. */
    fun pickUsable(onList: Boolean, torrent: Boolean, usenet: Boolean, awayServer: Boolean): Boolean =
        onList && !torrent && !usenet && !awayServer

    /** [chosen] still fits the stream picked: the head start's rules ([CopyFirst.headStartVerdicts]) leave it in. */
    fun fits(chosen: CopyOffer?, verdicts: List<Pair<CopyOffer, String?>>): Boolean =
        chosen != null && verdicts.any { CopyFirst.sameOffer(it.first, chosen) && it.second == null }

    /** Given back [HOLD_MAX_MS] after it was held without a pick, [TAKE_WINDOW_MS] after the pick ([pickedAtMs]). */
    fun expired(heldAtMs: Long, pickedAtMs: Long?, nowMs: Long): Boolean =
        if (pickedAtMs != null) nowMs - pickedAtMs >= TAKE_WINDOW_MS else nowMs - heldAtMs >= HOLD_MAX_MS

    /**
     * A TorBox copy added before the pick stays in the user's list when given back after a pick of a stream that names
     * no torrent and may be that copy: the same file, or a stream not on the list.
     */
    fun keepsAdded(picked: Boolean, pickedHash: String?, pickedOffer: CopyOffer?, copy: CopyOffer): Boolean =
        picked && pickedHash == null &&
            (pickedOffer == null || CopyFirst.sameFile(pickedOffer, copy) || CopyFirst.sameListing(pickedOffer, copy))

    internal class Held(val head: CopyHeadStart) {
        val fit = CompletableDeferred<Boolean>()
    }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val slot = PrePickSlot<Held>({ SystemClock.elapsedRealtime() }) { held, why ->
        Log.i(TAG, "copy first: the copy opened while the stream list was shown is given back ($why)")
        held.fit.complete(false)
        held.head.release()
    }

    val heldFor: String? get() = slot.heldFor

    /** [head], started by [start] on [scope], is held for [identity]; the one held before is given back. */
    fun hold(identity: String, head: CopyHeadStart, start: (CoroutineScope) -> Unit) {
        start(scope)
        slot.hold(identity, Held(head))
        scope.launch {
            delay(HOLD_MAX_MS)
            slot.expire()
        }
    }

    /** [identity]'s stream was picked: [fits] checks the copy against it; the thumbnails take it within [TAKE_WINDOW_MS]. */
    fun picked(identity: String, fits: suspend (CopyHeadStart) -> Boolean) {
        val held = slot.picked(identity) ?: return
        scope.launch {
            val ok = runCatching { fits(held.head) }.onFailure { if (it is CancellationException) throw it }.getOrDefault(false)
            held.fit.complete(ok)
            if (!ok) {
                slot.release(held, "it does not fit the stream picked")
            }
        }
        scope.launch {
            delay(TAKE_WINDOW_MS)
            slot.expire()
        }
    }

    /** The head start held for [identity], once, when it fits the stream picked; anything else held is given back. */
    suspend fun take(identity: String): CopyHeadStart? {
        val held = slot.take(identity) ?: return null
        if (withTimeoutOrNull(FIT_WAIT_MS) { held.fit.await() } != true) {
            held.head.release()
            return null
        }
        return held.head
    }

    /** The stream list of [identity] was left without a pick. */
    fun leave(identity: String) = slot.leave(identity)

    /** [identity] gets no copy opened at the pick: what is held for it is given back. */
    fun drop(identity: String) = slot.drop(identity)
}
