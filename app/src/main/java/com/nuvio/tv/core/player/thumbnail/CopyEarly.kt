package com.nuvio.tv.core.player.thumbnail

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException

/** A copy picture made before the title's thumbnails session: keyframe [kf] of the copy's index, at the copy's own size. */
internal class CopyEarlyPicture(val kf: Int, val jpeg: ByteArray, val width: Int, val height: Int)

/**
 * Copy pictures made as soon as the copy's index is read, before the title's thumbnails session exists: software only,
 * one request at a time, kept as small JPEGs in memory up to [maxPictures] / [maxBytes]. Nothing is stored from them
 * here, the generating screen may show them ([CopyEarlyRow]); the copy pass puts the ones whose keyframe it picks for a
 * slot into the copy's store, after which the usual picture check decides. A list nobody takes is dropped.
 */
internal class CopyEarly(
    private val label: String,
    private val pickedAtMs: Long,
    /** Each picture as it is made, keyframe and its time (ms) on the copy; the bitmap is recycled after the call. */
    private val shown: ((kf: Int, ptsMs: Long, bitmap: Bitmap) -> Unit)? = null,
) {
    companion object {
        private const val TAG = "ThumbCopy"
        const val MAX_STRONG = 60
        const val MAX_SMALL = 24
        const val BYTES_STRONG = 4L * 1024 * 1024
        const val BYTES_SMALL = 3L * 512 * 1024
        /** A small box rests this long between pictures while the film opens. */
        const val SMALL_GAP_MS = 150L
        const val JPEG_QUALITY = 90
        const val TAKE_WAIT_MS = 3_000L
        private const val READ_FAILURES = 3
        private val STRIDES = listOf(30, 6, 3, 1)
        private const val BEFORE_SLOTS = 60
        private const val AFTER_SLOTS = 120

        fun caps(strongBox: Boolean): Pair<Int, Long> =
            if (strongBox) MAX_STRONG to BYTES_STRONG else MAX_SMALL to BYTES_SMALL

        /**
         * The copy's keyframes to make first, at most [cap]: the whole film at the coarsest stride, then around
         * [anchorMs] (where playback starts), each slot's keyframe nearest its centre, as the pass picks them when the
         * copy has the title's length.
         */
        fun keyframes(copyMs: Long, anchorMs: Long, cap: Int, nearest: (Long) -> Int): List<Int> {
            val count = ReleaseMatch.slotCount(copyMs, SeekThumbnails.SPACING_MS)
            if (count <= 0 || cap <= 0) return emptyList()
            val anchor = (anchorMs.coerceAtLeast(0L) / SeekThumbnails.SPACING_MS).toInt().coerceIn(0, count - 1)
            val out = LinkedHashSet<Int>()
            for (m in SeekGrid.latticeOrder(count, STRIDES, anchor, BEFORE_SLOTS, AFTER_SLOTS, gridFirst = STRIDES.first())) {
                val kf = nearest(m * SeekThumbnails.SPACING_MS * 1000L + SeekThumbnails.SPACING_MS * 500L)
                if (kf >= 0) out += kf
                if (out.size >= cap) break
            }
            return out.toList()
        }

        /** Why no more early pictures are made now, null when they may be. */
        fun stopsBecause(made: Int, bytes: Long, cap: Pair<Int, Long>, blocked: String?): String? = when {
            made >= cap.first -> "$made pictures, the early limit"
            bytes >= cap.second -> "${bytes / 1024} KB, the early limit"
            blocked != null -> blocked
            else -> null
        }
    }

    private val pictures = ArrayList<CopyEarlyPicture>()
    @Volatile private var bytes = 0L
    @Volatile private var closed = false
    @Volatile private var job: Job? = null
    @Volatile var firstAtMs = 0L
        private set

    val count: Int get() = synchronized(pictures) { pictures.size }

    /**
     * Makes the pictures from [pre]'s index and reader. [anchorMs]: where playback starts, read once. [blocked]: why to
     * stop now (rate limits, memory), checked before every picture.
     */
    fun start(scope: CoroutineScope, pre: CopyPreread, strongBox: Boolean, anchorMs: suspend () -> Long, blocked: () -> String?) {
        job = scope.launch(Dispatchers.IO) {
            val cap = caps(strongBox)
            val idx = pre.index
            if (!ThumbNative.load()) return@launch
            val anchor = runCatching { anchorMs() }.getOrDefault(0L)
            val plan = keyframes(idx.durationUs / 1000L, anchor, cap.first) { idx.keyframes.nearest(it) }
            val extractor = KeyframeExtractor(idx, pre.reader)
            val decoder = FfmpegThumbDecoder()
            val tier = MemoryGovernor.tierFor(idx.video.width, idx.video.height)
            val dv5 = idx.video.dolbyVision?.profile == 5
            val t0 = SystemClock.elapsedRealtime()
            var failures = 0
            var why = "all planned"
            try {
                for (kf in plan) {
                    if (!isActive || closed) {
                        why = "the session took them"
                        break
                    }
                    val memory = MemoryGovernor.allow(tier, decoderWarm = decoder.warm, addressSpace = true)
                        .takeIf { !it.allowed }?.let { "memory (${it.reason})" }
                    val stop = stopsBecause(count, bytes, cap, blocked() ?: memory)
                    if (stop != null) {
                        why = stop
                        break
                    }
                    val au = try {
                        extractor.extract(kf)
                    } catch (e: IOException) {
                        if (++failures >= READ_FAILURES || closed) {
                            why = "reads failed (${e.javaClass.simpleName})"
                            break
                        }
                        null
                    } ?: continue
                    val frame = decoder.decode(au, idx.video, threads = 1) ?: continue
                    if (dv5 && !frame.colour.doviIpt) {
                        why = "Dolby Vision 5 without decoder metadata"
                        break
                    }
                    val decision = ColourClassifier.classify(idx.video.colour, frame.colour, idx.video.dolbyVision)
                    val size = ThumbGeometry.outputSize(frame.width, frame.height, frame.sarNum, frame.sarDen,
                        idx.video.displayAspect)
                    val bmp = decoder.render(frame, decision, size[0], size[1]) ?: continue
                    val jpeg = ByteArrayOutputStream(24 * 1024).also {
                        bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)
                    }.toByteArray()
                    synchronized(pictures) {
                        if (closed) {
                            bmp.recycle()
                            return@launch
                        }
                        pictures += CopyEarlyPicture(kf, jpeg, size[0], size[1])
                        bytes += jpeg.size
                    }
                    shown?.let { s -> runCatching { s(kf, idx.keyframes.ptsUs[kf] / 1000L, bmp) } }
                    bmp.recycle()
                    if (firstAtMs == 0L) {
                        firstAtMs = SystemClock.elapsedRealtime()
                        Log.i(TAG, "copy first: first early picture from $label ${firstAtMs - t0} ms after its index, " +
                            "${firstAtMs - pickedAtMs} ms after the stream pick (before the thumbnails session)")
                    }
                    if (!strongBox) delay(SMALL_GAP_MS)
                }
            } finally {
                runCatching { decoder.close() }
                Log.i(TAG, "copy first: $count early pictures from $label before the thumbnails session " +
                    "(${bytes / 1024} KB, ${SystemClock.elapsedRealtime() - t0} ms, ended: $why)")
            }
        }
    }

    /** Stops making pictures and hands over those made; empty after [drop]. */
    suspend fun take(): List<CopyEarlyPicture> {
        job?.let { j -> withTimeoutOrNull(TAKE_WAIT_MS) { j.cancelAndJoin() } ?: j.cancel() }
        return synchronized(pictures) {
            closed = true
            pictures.toList().also { pictures.clear() }
        }
    }

    fun drop() {
        job?.cancel()
        synchronized(pictures) {
            closed = true
            pictures.clear()
            bytes = 0L
        }
    }
}

/** Which pictures made before the session go into which copy slots, once the title's grid is known. */
internal object CopyEarlyFit {
    data class Use(val picture: CopyEarlyPicture, val slots: List<Int>, val plan: CopyFit.Plan)

    class Result(val used: List<Use>, val otherKeyframe: Int, val otherShape: Int)

    /**
     * Each picture serves the copy slots whose keyframe the pass picked for it ([kfSlots]) and that are still [wanted];
     * a picture of another size than the pass would draw for the title's [ow] x [oh] picture is left out.
     */
    fun slots(
        early: List<CopyEarlyPicture>, kfSlots: Map<Int, List<Int>>, ow: Int, oh: Int, wanted: (Int) -> Boolean,
    ): Result {
        val used = ArrayList<Use>()
        var otherKeyframe = 0
        var otherShape = 0
        for (p in early) {
            val slots = kfSlots[p.kf].orEmpty().filter(wanted)
            if (slots.isEmpty()) {
                otherKeyframe++
                continue
            }
            val plan = CopyFit.plan(p.width, p.height, ow, oh)
            if (plan == null || plan.renderW != p.width || plan.renderH != p.height) {
                otherShape++
                continue
            }
            used += Use(p, slots, plan)
        }
        return Result(used, otherKeyframe, otherShape)
    }
}

/**
 * The generating screen's row while it checks: the copy's pictures made before the session, in the order they were
 * made, until the session's own row has pictures. Shown like a copy's thumbnails before the picture check, unmarked.
 */
internal object CopyEarlyRow {
    /** Pictures kept for the row: the six it shows and the ones gliding out. */
    const val KEEP = 8
    /** Width the row's pictures are scaled down to at most, in pixels. */
    const val MAX_WIDTH_PX = 240

    class Frame<T>(val seq: Int, val kf: Int, val ptsMs: Long, val picture: T)

    class Row<T>(val identity: String, val frames: List<Frame<T>>) {
        val newestSeq: Int get() = frames.lastOrNull()?.seq ?: 0
    }

    class Added<T>(val row: Row<T>?, val dropped: List<T>)

    /** Pictures are taken only while the screen checks, for the title it is up for. */
    fun wanted(identity: String, screenIdentity: String?, ui: SeekThumbnails.PrepareUi): Boolean =
        ui is SeekThumbnails.PrepareUi.Checking && identity.isNotEmpty() && identity == screenIdentity

    /**
     * [row] with [picture] of keyframe [kf] for [identity] added as the newest; a row of another title is dropped whole,
     * a keyframe already in the row is not added again ([Added.dropped] holds [picture] then).
     */
    fun <T> add(row: Row<T>?, identity: String, kf: Int, ptsMs: Long, picture: T, keep: Int = KEEP): Added<T> {
        val dropped = ArrayList<T>()
        val same = row?.takeIf { it.identity == identity }
        if (row != null && same == null) row.frames.mapTo(dropped) { it.picture }
        if (same != null && same.frames.any { it.kf == kf }) return Added(same, listOf(picture))
        val frames = ArrayList(same?.frames.orEmpty())
        frames += Frame((same?.newestSeq ?: 0) + 1, kf, ptsMs, picture)
        while (frames.size > keep.coerceAtLeast(1)) dropped += frames.removeAt(0).picture
        return Added(Row(identity, frames), dropped)
    }

    /** The row shows the early pictures: while checking, and while generating until the session's row has one. */
    fun showsEarly(ui: SeekThumbnails.PrepareUi, rowIdentity: String?, screenIdentity: String?, early: Int): Boolean =
        early > 0 && rowIdentity != null && rowIdentity == screenIdentity && when (ui) {
            is SeekThumbnails.PrepareUi.Checking -> true
            is SeekThumbnails.PrepareUi.Generating -> ui.recent.isEmpty()
            else -> false
        }

    /** The early pictures are no longer needed: the screen is gone, ready, or shows the session's own row. */
    fun ends(ui: SeekThumbnails.PrepareUi): Boolean = when (ui) {
        is SeekThumbnails.PrepareUi.Checking -> false
        is SeekThumbnails.PrepareUi.Generating -> ui.recent.isNotEmpty()
        else -> true
    }

    /** [w] x [h] scaled to at most [maxW] wide, the shape kept; never larger. */
    fun scaledSize(w: Int, h: Int, maxW: Int = MAX_WIDTH_PX): IntArray {
        if (w <= 0 || h <= 0) return intArrayOf(0, 0)
        if (w <= maxW) return intArrayOf(w, h)
        return intArrayOf(maxW, ((h.toLong() * maxW + w / 2) / w).toInt().coerceAtLeast(1))
    }
}
