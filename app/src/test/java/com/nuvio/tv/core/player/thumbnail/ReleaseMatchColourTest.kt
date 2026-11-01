package com.nuvio.tv.core.player.thumbnail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.math.pow
import kotlin.random.Random

/**
 * The picture check across colour paths: an SDR copy against the same frame of an HDR or Dolby Vision title, whose
 * thumbnail went through another tone curve and colour matrix, still matches; other scenes and frames one apart do not.
 */
class ReleaseMatchColourTest {
    /** A colour scene: soft coloured blobs over a tilted background, sampled at any size. */
    private class Scene(seed: Int) {
        private val rnd = Random(seed)
        private val blobs = List(9) { DoubleArray(7) { rnd.nextDouble() } }
        private val tilt = rnd.nextDouble() * 2 - 1

        fun rgb(u: Double, v: Double): DoubleArray {
            val c = doubleArrayOf(0.35 + 0.15 * tilt * (u - 0.5), 0.33, 0.30 - 0.1 * tilt * (v - 0.5))
            for (b in blobs) {
                val d = (u - b[0]).pow(2) + (v - b[1]).pow(2)
                val w = (b[2] * 2 - 1) * exp(-d / (0.008 + 0.03 * b[3]))
                c[0] += w * b[4]
                c[1] += w * b[5]
                c[2] += w * b[6]
            }
            return DoubleArray(3) { c[it].coerceIn(0.0, 1.0) }
        }
    }

    /** [look] maps a linear-ish 0..1 colour to the 0..1 colour the thumbnail shows. */
    private fun render(scene: Scene, w: Int, h: Int, noise: Int = 0, look: (DoubleArray) -> DoubleArray = { it }): IntArray {
        val rnd = Random(w * 31 + h)
        return IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val c = look(scene.rgb((x + 0.5) / w, (y + 0.5) / h))
            fun ch(k: Int) = (c[k] * 255 + (if (noise > 0) rnd.nextInt(-noise, noise + 1) else 0)).toInt().coerceIn(0, 255)
            (0xFF shl 24) or (ch(0) shl 16) or (ch(1) shl 8) or ch(2)
        }
    }

    /** The same frame decoded as PQ, mapped with another curve, BT.2020 colours shown as BT.709, a little darker. */
    private val hdrLook: (DoubleArray) -> DoubleArray = { c ->
        val m = doubleArrayOf(
            0.627 * c[0] + 0.329 * c[1] + 0.044 * c[2],
            0.069 * c[0] + 0.920 * c[1] + 0.011 * c[2],
            0.016 * c[0] + 0.088 * c[1] + 0.896 * c[2],
        )
        DoubleArray(3) { k ->
            val lin = m[k].pow(2.4)
            ((lin / (lin + 0.18)).pow(1 / 2.2) * 0.86).coerceIn(0.0, 1.0)
        }
    }

    /** A Dolby Vision picture whose IPT-like colour came out flatter: strong highlight compression, lifted blacks. */
    private val dvLook: (DoubleArray) -> DoubleArray = { c ->
        val y = 0.2627 * c[0] + 0.678 * c[1] + 0.0593 * c[2]
        DoubleArray(3) { k ->
            val sat = y + (c[k] - y) * 0.55
            (0.08 + 0.75 * (1 - exp(-2.6 * sat))).coerceIn(0.0, 1.0)
        }
    }

    private fun sig(px: IntArray, w: Int, h: Int) = ReleaseMatch.signature(px, w, h)!!

    /** The earlier check: the grid's plain grey values, mean 0 and spread 1, correlated (for comparison in the output). */
    private fun plain(a: IntArray, b: IntArray): Float {
        fun grid(px: IntArray, w: Int, h: Int): DoubleArray {
            val g = DoubleArray(ReleaseMatch.SIG_W * ReleaseMatch.SIG_H)
            val n = IntArray(g.size)
            for (i in px.indices) {
                val c = px[i]
                val cell = (i / w) * ReleaseMatch.SIG_H / h * ReleaseMatch.SIG_W + (i % w) * ReleaseMatch.SIG_W / w
                g[cell] += 0.299 * ((c shr 16) and 0xFF) + 0.587 * ((c shr 8) and 0xFF) + 0.114 * (c and 0xFF)
                n[cell]++
            }
            for (k in g.indices) g[k] /= n[k]
            val m = g.average()
            val sd = kotlin.math.sqrt(g.sumOf { (it - m) * (it - m) } / g.size)
            return DoubleArray(g.size) { (g[it] - m) / sd }
        }
        val x = grid(a, 240, 135)
        val y = grid(b, 480, 270)
        return (x.indices.sumOf { x[it] * y[it] } / x.size).toFloat()
    }

    @Test
    fun theSameFrameMatchesAcrossTheHdrAndDolbyVisionColourPaths() {
        for (seed in 1..12) {
            val sdr = sig(render(Scene(seed), 240, 135, noise = 4), 240, 135)
            val hdr = sig(render(Scene(seed), 480, 270, look = hdrLook), 480, 270)
            val dv = sig(render(Scene(seed), 480, 270, look = dvLook), 480, 270)
            val ch = ReleaseMatch.correlation(sdr, hdr)
            val cd = ReleaseMatch.correlation(sdr, dv)
            println("seed $seed: rank correlation hdr %.3f dv %.3f, plain grey correlation hdr %.3f dv %.3f".format(ch, cd,
                plain(render(Scene(seed), 240, 135, noise = 4), render(Scene(seed), 480, 270, look = hdrLook)),
                plain(render(Scene(seed), 240, 135, noise = 4), render(Scene(seed), 480, 270, look = dvLook))))
            assertEquals("seed $seed hdr $ch", ReleaseMatch.Outcome.PASS, ReleaseMatch.outcome(ch, null, null, 0L))
            assertEquals("seed $seed dv $cd", ReleaseMatch.Outcome.PASS, ReleaseMatch.outcome(cd, null, null, 0L))
        }
    }

    @Test
    fun otherScenesStillFailThroughTheSameColourPaths() {
        var mismatches = 0
        for (seed in 1..12) {
            val sdr = sig(render(Scene(seed), 240, 135, noise = 4), 240, 135)
            for (look in listOf(hdrLook, dvLook)) {
                val other = sig(render(Scene(seed + 50), 480, 270, look = look), 480, 270)
                val c = ReleaseMatch.correlation(sdr, other)
                val o = ReleaseMatch.outcome(c, null, null, 0L)
                assertTrue("seed $seed: $c", o != ReleaseMatch.Outcome.PASS)
                if (o == ReleaseMatch.Outcome.MISMATCH) mismatches++
            }
        }
        assertTrue("mismatches $mismatches of 24", mismatches >= 12)
    }

    @Test
    fun ranksKeepTheOrderAndTiesShareARank() {
        val r = ReleaseMatch.ranked(floatArrayOf(5f, 1f, 3f, 3f))
        assertTrue(r[1] < r[2] && r[2] == r[3] && r[3] < r[0])
        assertEquals(0.0, r.sumOf { it.toDouble() }, 1e-5)
        assertTrue(ReleaseMatch.ranked(floatArrayOf(2f, 2f, 2f)).all { it == 0f })
    }

    @Test
    fun keyframesOneFrameApartAreNotTheSameFrame() {
        // 24, 25, 30 and 60 fps: one frame apart is never "the same frame", where a mismatch would count.
        for (frameUs in longArrayOf(41_708L, 40_000L, 33_367L, 16_683L)) {
            assertFalse(frameUs <= ReleaseMatch.SAME_FRAME_US)
            assertEquals(ReleaseMatch.Outcome.SKIP, ReleaseMatch.outcome(0.19f, null, null, frameUs))
        }
        assertEquals(ReleaseMatch.Outcome.MISMATCH, ReleaseMatch.outcome(0.19f, null, null, 1_000L))
    }

    @Test
    fun aPairWithTheOwnPictureJustAfterTheCopysKeyframeComesFirst() {
        // Copy keyframes sit on scene cuts: an own keyframe 42 ms before one shows the scene before it.
        val eligible = listOf(100 to -41_708L, 102 to 41_708L, 300 to -120_000L, 305 to 160_000L, 500 to -30_000L,
            502 to 600_000L)
        val picked = ReleaseMatch.pickCheckSlots(eligible, 600)
        assertEquals(listOf(102, 305, 502), picked.take(3))
        assertEquals(setOf(100, 300, 500), picked.drop(3).toSet())
    }
}
