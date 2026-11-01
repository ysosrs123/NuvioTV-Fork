package com.nuvio.tv.core.player.thumbnail

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Reuse of another release's thumbnails (same title, same length): timing match, slot mapping, and the picture check
 * on small contrast-normalised greyscale signatures.
 */
internal object ReleaseMatch {
    /** Lengths this close share the picture timing as is: less than one 10 s thumbnail step apart. */
    const val SAME_LENGTH_MS = 8_000L
    /** 25 fps against 23.976 fps, either way round. */
    const val PAL_MIN = 0.956
    const val PAL_MAX = 0.963
    const val SIG_W = 32
    const val SIG_H = 18
    /** Below this spread (0-255 grey) a picture is too flat to compare. */
    const val FLAT_STDEV = 4.0
    const val MATCH_MIN = 0.90f
    const val DIFFERENT_MAX = 0.40f
    /**
     * A same-frame pair from here up to [MATCH_MIN] is borderline: never a match on its own, more pictures decide
     * ([tally]).
     */
    const val FAIL_MIN = 0.60f
    /** Borderline pairs that end the check for this copy unless more pairs matched. */
    const val BORDERLINE_CHECKS = 3
    /** A neighbour slot this much better than the slot itself means the timing is shifted. */
    const val SHIFT_MARGIN = 0.08f
    const val CHECKS_NEEDED = 3
    const val CHECK_SPARES = 3
    /**
     * Own and borrowed keyframe this close: the same picture, so a mismatch is real. Under half a frame up to 60 fps:
     * keyframes one frame apart (41.7 ms at 24 fps) are two pictures, across a scene cut two different scenes.
     */
    const val SAME_FRAME_US = 12_000L
    const val NEAR_US = 250_000L
    /** Farther apart than this a pair is not compared, and a borrowed picture is not copied into the slot. */
    const val WIDE_US = 1_000_000L

    /** Candidate time per own time (other / own), 1.0 for the same timing, null when not reusable. */
    fun ratio(ownMs: Long, otherMs: Long): Double? {
        if (ownMs <= 0L || otherMs <= 0L) return null
        if (abs(ownMs - otherMs) <= SAME_LENGTH_MS) return 1.0
        val r = otherMs.toDouble() / ownMs
        return if (r in PAL_MIN..PAL_MAX || 1.0 / r in PAL_MIN..PAL_MAX) r else null
    }

    /** Another release's saved set is worth trying: enough pictures, the same length or PAL timing, never found different. */
    fun reusable(ownMs: Long, otherMs: Long, pictures: Int, minPictures: Int, verdict: Boolean?): Boolean =
        verdict != false && pictures >= minPictures && ratio(ownMs, otherMs) != null

    /** The candidate's slot holding the same moment as own [slot] (slot centres), -1 outside it. */
    fun mapSlot(slot: Int, ratio: Double, otherSlotCount: Int): Int {
        if (slot < 0) return -1
        val m = if (ratio == 1.0) slot else ((slot + 0.5) * ratio - 0.5).roundToInt()
        return if (m in 0 until otherSlotCount) m else -1
    }

    fun slotCount(durationMs: Long, spacingMs: Long): Int =
        if (durationMs <= 0L) 0 else ((durationMs - 1) / spacingMs).toInt() + 1

    /**
     * Check slots from [eligible] (slot to the own keyframe's offset from the borrowed one in us, own minus borrowed;
     * a plain gap counts as an own keyframe after the borrowed one), closest pairs first: same-frame pairs, then within
     * [NEAR_US], then [WIDE_US], each first with the own keyframe after the borrowed one (a copy's keyframes sit on
     * scene cuts, so an own picture just before one is the scene before), then either way. Within a tier spread over
     * the title: near 1/6, 1/2 and 5/6, then near 1/4, 1/2 and 3/4.
     */
    fun pickCheckSlots(eligible: List<Pair<Int, Long>>, slotCount: Int): List<Int> {
        if (eligible.isEmpty() || slotCount <= 0) return emptyList()
        val total = CHECKS_NEEDED + CHECK_SPARES
        val targets = (0 until CHECKS_NEEDED).map { (2 * it + 1).toDouble() / (2 * CHECKS_NEEDED) } +
            (0 until CHECK_SPARES).map { (it + 1).toDouble() / (CHECK_SPARES + 1) }
        val picked = ArrayList<Int>()
        val tiers = listOf(SAME_FRAME_US to false, NEAR_US to true, WIDE_US to true, NEAR_US to false, WIDE_US to false)
        for ((tier, afterOnly) in tiers) {
            val pool = eligible.filter { abs(it.second) <= tier && (!afterOnly || it.second >= 0L) && it.first !in picked }
                .map { it.first }.toMutableList()
            for (f in targets) {
                if (picked.size >= total || pool.isEmpty()) break
                val want = f * slotCount
                val s = pool.minByOrNull { abs(it + 0.5 - want) } ?: break
                picked += s
                pool.remove(s)
            }
        }
        return picked
    }

    /**
     * Each copy slot with its gap (us, own timeline) to the nearest own landing keyframe it serves, within [WIDE_US]:
     * the copy slot's keyframe ([copyKeyframeUs], copy timeline, negative for none) against the landing keyframes
     * ([ownLandingUs], -1 for none) of the own slots it serves ([owners]), signed: own minus copy, the nearest one.
     * [pickCheckSlots] over these gives the copy pictures the check can decide on first.
     */
    fun copyGaps(
        owners: Map<Int, IntArray>, copyKeyframeUs: (Int) -> Long, ownLandingUs: LongArray, ratio: Double,
    ): List<Pair<Int, Long>> {
        if (ratio <= 0.0) return emptyList()
        val gaps = ArrayList<Pair<Int, Long>>()
        for ((m, served) in owners) {
            val copyUs = copyKeyframeUs(m).takeIf { it >= 0L } ?: continue
            val onOwn = (copyUs / ratio).toLong()
            var best = Long.MAX_VALUE
            for (s in served) {
                val landing = ownLandingUs.getOrElse(s) { -1L }
                if (landing < 0L) continue
                val d = landing - onOwn
                if (abs(d) < abs(best) || (abs(d) == abs(best) && d > best)) best = d
            }
            if (best != Long.MAX_VALUE && abs(best) <= WIDE_US) gaps += m to best
        }
        return gaps
    }

    /**
     * [SIG_W] x [SIG_H] area-averaged grey of the picture inside the given margins, as ranks (brightest cell highest),
     * mean 0 and spread 1: any change that keeps brighter cells brighter (another tone curve, an HDR or Dolby Vision
     * picture mapped otherwise than an SDR copy, brightness or contrast) keeps the signature. Null when flat.
     */
    fun signature(px: IntArray, w: Int, h: Int, left: Int = 0, top: Int = 0, right: Int = 0, bottom: Int = 0): FloatArray? {
        val cw = w - left - right
        val ch = h - top - bottom
        if (cw < 2 || ch < 2 || px.size < w * h) return null
        val sig = FloatArray(SIG_W * SIG_H)
        for (gy in 0 until SIG_H) {
            val y0 = top + gy * ch / SIG_H
            val y1 = maxOf(y0 + 1, top + (gy + 1) * ch / SIG_H)
            for (gx in 0 until SIG_W) {
                val x0 = left + gx * cw / SIG_W
                val x1 = maxOf(x0 + 1, left + (gx + 1) * cw / SIG_W)
                var sum = 0.0
                for (y in y0 until y1) {
                    val row = y * w
                    for (x in x0 until x1) {
                        val c = px[row + x]
                        sum += 0.299 * ((c shr 16) and 0xFF) + 0.587 * ((c shr 8) and 0xFF) + 0.114 * (c and 0xFF)
                    }
                }
                sig[gy * SIG_W + gx] = (sum / ((y1 - y0) * (x1 - x0))).toFloat()
            }
        }
        val mean = sig.average()
        val sd = sqrt(sig.sumOf { (it - mean) * (it - mean) } / sig.size)
        if (sd < FLAT_STDEV) return null
        return ranked(sig)
    }

    /** [values] as ranks (ties share their average rank), mean 0 and spread 1; all-equal values give zeros. */
    fun ranked(values: FloatArray): FloatArray {
        val n = values.size
        val order = (0 until n).sortedBy { values[it] }
        val rank = DoubleArray(n)
        var i = 0
        while (i < n) {
            var j = i
            while (j + 1 < n && values[order[j + 1]] == values[order[i]]) j++
            val r = (i + j) / 2.0
            for (k in i..j) rank[order[k]] = r
            i = j + 1
        }
        val mean = rank.average()
        val sd = sqrt(rank.sumOf { (it - mean) * (it - mean) } / n)
        return FloatArray(n) { if (sd > 0.0) ((rank[it] - mean) / sd).toFloat() else 0f }
    }

    /** Pearson correlation of two signatures, -1..1. */
    fun correlation(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var s = 0.0
        for (i in a.indices) s += a[i] * b[i]
        return (s / a.size).toFloat()
    }

    enum class Verdict { MATCH, SHIFTED, DIFFERENT, UNSURE }

    /**
     * One own picture against the candidate's at the mapped slot ([same]) and the slots either side. Null = no
     * picture or a flat one. Only an unshifted match counts.
     */
    fun verdict(same: Float?, before: Float?, after: Float?): Verdict {
        if (same == null) return Verdict.UNSURE
        val nb = maxOf(before ?: -1f, after ?: -1f)
        return when {
            nb >= MATCH_MIN && nb > same + SHIFT_MARGIN -> Verdict.SHIFTED
            same >= MATCH_MIN -> Verdict.MATCH
            same <= DIFFERENT_MAX && nb < MATCH_MIN -> Verdict.DIFFERENT
            else -> Verdict.UNSURE
        }
    }

    /** What one compared pair does to the check. */
    enum class Outcome {
        /** Counts towards the matches needed. */
        PASS,
        /** Decides nothing: a spare pair is tried. */
        SKIP,
        /** A same-frame pair too alike to call different, too unlike to call the same: more pairs decide. */
        FAIL,
        /** A clear mismatch on the same frame. */
        MISMATCH,
    }

    /**
     * [gapUs]: own keyframe to borrowed keyframe on the own timeline. Only a same-frame pair can show a mismatch or a
     * borderline picture; pairs further apart can only match, since the picture moves between their keyframes.
     */
    fun outcome(same: Float?, before: Float?, after: Float?, gapUs: Long): Outcome {
        val sameFrame = gapUs <= SAME_FRAME_US
        return when (verdict(same, before, after)) {
            Verdict.MATCH -> Outcome.PASS
            Verdict.SHIFTED, Verdict.DIFFERENT -> if (sameFrame) Outcome.MISMATCH else Outcome.SKIP
            Verdict.UNSURE -> if (sameFrame && (same ?: -1f) >= FAIL_MIN) Outcome.FAIL else Outcome.SKIP
        }
    }

    /** One picture check for the log: where, how far apart the keyframes are, the scores and what it counts as. */
    fun checkLine(atS: Long, gapUs: Long, same: Float?, before: Float?, after: Float?, outcome: Outcome): String {
        fun score(v: Float?) = v?.let { String.format(java.util.Locale.ROOT, "%.2f", it) } ?: "-"
        val counts = when (outcome) {
            Outcome.PASS -> "a match"
            Outcome.SKIP -> "decides nothing"
            Outcome.FAIL -> "borderline"
            Outcome.MISMATCH -> "a mismatch"
        }
        return "reuse check at $atS s (keyframes ${gapUs / 1000} ms apart${if (gapUs <= SAME_FRAME_US) ", same frame" else ""}): " +
            "score ${same?.let { score(it) } ?: "no picture"} (either side ${score(before)} / ${score(after)}) - $counts"
    }

    enum class Tally { GOING, MATCHED, NOT_MATCHED }

    /**
     * The check so far: [passes] matching pairs and the [borderline] scores. Matched with [CHECKS_NEEDED] matches that
     * outnumber the borderline pairs; not matched once [BORDERLINE_CHECKS] borderline pairs are at least as many as
     * the matches.
     */
    fun tally(passes: Int, borderline: List<Float>): Tally = when {
        passes >= CHECKS_NEEDED && passes > borderline.size -> Tally.MATCHED
        borderline.size >= BORDERLINE_CHECKS && borderline.size >= passes -> Tally.NOT_MATCHED
        else -> Tally.GOING
    }
}
