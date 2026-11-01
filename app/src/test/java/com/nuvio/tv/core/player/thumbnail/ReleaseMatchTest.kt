package com.nuvio.tv.core.player.thumbnail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.math.pow
import kotlin.random.Random

/** Reuse across releases: which lengths match, how slots map, and the picture check. */
class ReleaseMatchTest {
    private val spacing = 10_000L

    /** A scene as a continuous picture, so any resolution samples the same image. */
    private class Scene(seed: Int, private val subjectU: Double? = null) {
        private val rnd = Random(seed)
        private val blobs = List(7) { DoubleArray(4) { rnd.nextDouble() } }
        private val tilt = rnd.nextDouble() * 2 - 1

        fun grey(u: Double, v: Double): Double {
            var g = 110 + 40 * tilt * (u - 0.5)
            for (b in blobs) {
                val d = (u - b[0]).pow(2) + (v - b[1]).pow(2)
                g += (b[2] * 2 - 1) * 110 * exp(-d / (0.01 + 0.03 * b[3]))
            }
            if (subjectU != null) g += 100 * exp(-((u - subjectU).pow(2) / 0.006 + (v - 0.55).pow(2) / 0.03))
            return g.coerceIn(0.0, 255.0)
        }
    }

    private fun render(
        scene: Scene, w: Int, h: Int, bars: Int = 0, noise: Int = 0, seed: Int = 9,
        look: (Double) -> Double = { it },
    ): IntArray {
        val rnd = Random(seed)
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val inBar = y < bars || y >= h - bars
            val v = if (inBar) 0.0 else {
                val vv = (y - bars + 0.5) / (h - 2 * bars)
                look(scene.grey((x + 0.5) / w, vv)) + (if (noise > 0) rnd.nextInt(-noise, noise + 1) else 0)
            }
            val g = v.toInt().coerceIn(0, 255)
            px[y * w + x] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
        }
        return px
    }

    private fun sig(px: IntArray, w: Int, h: Int, bars: Int = 0) =
        ReleaseMatch.signature(px, w, h, 0, bars, 0, bars)

    private fun corr(a: FloatArray?, b: FloatArray?): Float? =
        if (a == null || b == null) null else ReleaseMatch.correlation(a, b)

    @Test fun anotherReleaseOfTheSameLengthIsWorthReadingTheIndexFor() {
        // No Country for Old Men: 7333 s, the other release 1.5 s longer, 123 pictures saved.
        assertTrue(ReleaseMatch.reusable(7_333_000L, 7_334_500L, 123, 12, null))
        assertTrue(ReleaseMatch.reusable(7_333_000L, 7_334_500L, 123, 12, true))
        assertTrue(ReleaseMatch.reusable(7_333_000L, (7_333_000L * 0.959).toLong(), 40, 12, null))
    }

    @Test fun anotherCutTooFewPicturesOrAKnownMismatchIsNot() {
        assertFalse(ReleaseMatch.reusable(7_333_000L, 7_342_000L, 123, 12, null))
        assertFalse(ReleaseMatch.reusable(7_333_000L, 8_100_000L, 123, 12, null))
        assertFalse(ReleaseMatch.reusable(7_333_000L, 7_333_000L, 11, 12, null))
        assertFalse(ReleaseMatch.reusable(7_333_000L, 7_333_000L, 123, 12, false))
    }

    @Test fun lengthsWithinEightSecondsShareTheTiming() {
        assertEquals(1.0, ReleaseMatch.ratio(7_200_000L, 7_201_900L)!!, 0.0)
        assertEquals(1.0, ReleaseMatch.ratio(7_200_000L, 7_205_000L)!!, 0.0)
        assertEquals(1.0, ReleaseMatch.ratio(7_200_000L, 7_208_000L)!!, 0.0)
        assertEquals(1.0, ReleaseMatch.ratio(7_200_000L, 7_192_000L)!!, 0.0)
        assertNull(ReleaseMatch.ratio(7_200_000L, 7_208_001L))
        assertNull(ReleaseMatch.ratio(7_200_000L, 7_191_999L))
        assertEquals(1.0, ReleaseMatch.ratio(1_320_000L, 1_327_500L)!!, 0.0)
        assertNull(ReleaseMatch.ratio(7_200_000L, 7_260_000L))
        assertNull(ReleaseMatch.ratio(0L, 7_200_000L))
    }

    @Test fun aPalSpeedUpIsScaledEitherWay() {
        val film = 7_200_000L
        val pal = (film * 23.976 / 25.0).toLong()
        val down = ReleaseMatch.ratio(film, pal)!!
        assertEquals(23.976 / 25.0, down, 1e-4)
        val up = ReleaseMatch.ratio(pal, film)!!
        assertEquals(25.0 / 23.976, up, 1e-4)
        assertNull(ReleaseMatch.ratio(film, (film * 0.90).toLong()))
        assertNull(ReleaseMatch.ratio(film, (film * 0.97).toLong()))
    }

    @Test fun slotsMapByTheRatio() {
        assertEquals(123, ReleaseMatch.mapSlot(123, 1.0, 720))
        assertEquals(-1, ReleaseMatch.mapSlot(720, 1.0, 720))
        val r = 23.976 / 25.0
        val count = ReleaseMatch.slotCount((7_200_000L * r).toLong(), spacing)
        // own 6005 s is 5757.6 s in the faster copy: slot 575
        assertEquals(575, ReleaseMatch.mapSlot(600, r, count))
        assertEquals(0, ReleaseMatch.mapSlot(0, r, count))
        assertEquals(count - 1, ReleaseMatch.mapSlot(719, r, count))
        assertEquals(720, ReleaseMatch.slotCount(7_200_000L, spacing))
        assertEquals(721, ReleaseMatch.slotCount(7_200_001L, spacing))
    }

    @Test fun checkSlotsAreSpreadOverTheTitle() {
        val picked = ReleaseMatch.pickCheckSlots((0 until 600).map { it to 0L }, 600)
        assertEquals(6, picked.size)
        assertEquals(picked.size, picked.toSet().size)
        val first = picked.take(3)
        assertTrue(first[0] in 90..110)
        assertTrue(first[1] in 290..310)
        assertTrue(first[2] in 490..510)
        // Few eligible slots: still the ones nearest the targets, never twice.
        val few = ReleaseMatch.pickCheckSlots(listOf(5, 6, 400, 401).map { it to 0L }, 600)
        assertEquals(listOf(6, 400, 401, 5), few)
        assertTrue(ReleaseMatch.pickCheckSlots(emptyList(), 600).isEmpty())
    }

    @Test fun sameFramePairsComeFirst() {
        // Same-frame pairs only at the start and the end; nearer pairs in the middle come after them.
        val eligible = listOf(20 to 10_000L, 580 to 5_000L, 300 to 200_000L, 100 to 900_000L, 500 to 2_000_000L)
        val picked = ReleaseMatch.pickCheckSlots(eligible.filter { it.second <= ReleaseMatch.WIDE_US }, 600)
        assertEquals(listOf(20, 580, 300, 100), picked)
    }

    @Test fun onlyASameFramePairCanProveAMismatch() {
        val near = ReleaseMatch.SAME_FRAME_US
        assertEquals(ReleaseMatch.Outcome.MISMATCH, ReleaseMatch.outcome(0.1f, 0.2f, null, near))
        // A scene cut between keyframes 0.3 s apart: no verdict either way.
        assertEquals(ReleaseMatch.Outcome.SKIP, ReleaseMatch.outcome(0.1f, 0.2f, null, 300_000L))
        assertEquals(ReleaseMatch.Outcome.MISMATCH, ReleaseMatch.outcome(0.5f, 0.95f, null, near))
        assertEquals(ReleaseMatch.Outcome.SKIP, ReleaseMatch.outcome(0.7f, 0.95f, null, 300_000L))
        assertEquals(ReleaseMatch.Outcome.PASS, ReleaseMatch.outcome(0.95f, 0.9f, 0.4f, 300_000L))
        // Alike but not alike enough on the same frame: never a match, more pictures decide.
        assertEquals(ReleaseMatch.Outcome.FAIL, ReleaseMatch.outcome(0.85f, null, null, near))
        assertEquals(ReleaseMatch.Outcome.SKIP, ReleaseMatch.outcome(0.5f, null, null, 300_000L))
        assertEquals(ReleaseMatch.Outcome.SKIP, ReleaseMatch.outcome(null, null, null, near))
    }

    @Test fun keyframesTwoThirdsOfASecondApartAreNoBorderlinePicture() {
        // A WEB copy against a Blu-ray remux, keyframes 667 ms apart: the picture moved in between.
        assertEquals(ReleaseMatch.Outcome.SKIP, ReleaseMatch.outcome(0.80f, 0.6f, 0.7f, 667_000L))
        assertEquals(ReleaseMatch.Outcome.SKIP, ReleaseMatch.outcome(0.80f, null, null, ReleaseMatch.NEAR_US))
        assertEquals(ReleaseMatch.Outcome.PASS, ReleaseMatch.outcome(0.93f, 0.7f, 0.7f, 667_000L))
        assertEquals(ReleaseMatch.Outcome.FAIL, ReleaseMatch.outcome(0.80f, null, null, 0L))
    }

    @Test fun borderlinePicturesNeedMoreMatchesThanThemselves() {
        val t = ReleaseMatch::tally
        assertEquals(ReleaseMatch.Tally.GOING, t(0, listOf(0.8f)))
        assertEquals(ReleaseMatch.Tally.GOING, t(2, listOf(0.8f)))
        assertEquals(ReleaseMatch.Tally.MATCHED, t(3, listOf(0.8f)))
        assertEquals(ReleaseMatch.Tally.MATCHED, t(3, emptyList()))
        // Two borderline pairs and two matches: the third match decides.
        assertEquals(ReleaseMatch.Tally.GOING, t(2, listOf(0.8f, 0.75f)))
        assertEquals(ReleaseMatch.Tally.MATCHED, t(3, listOf(0.8f, 0.75f)))
        assertEquals(ReleaseMatch.Tally.NOT_MATCHED, t(1, listOf(0.8f, 0.75f, 0.7f)))
        assertEquals(ReleaseMatch.Tally.NOT_MATCHED, t(3, listOf(0.8f, 0.75f, 0.7f)))
    }

    @Test fun twoDifferentFilmsNeverPassTheCheck() {
        // Scenes of another film: same-frame pairs are mismatches, pairs further apart decide nothing.
        var passes = 0
        val borderline = ArrayList<Float>()
        var mismatches = 0
        for (seed in 40 until 52) {
            val own = sig(render(Scene(seed), 480, 270), 480, 270)
            val other = sig(render(Scene(seed + 100), 240, 135), 240, 135)
            val c = corr(own, other) ?: continue
            for (gap in longArrayOf(0L, 300_000L, 667_000L)) {
                when (ReleaseMatch.outcome(c, null, null, gap)) {
                    ReleaseMatch.Outcome.PASS -> passes++
                    ReleaseMatch.Outcome.FAIL -> borderline += c
                    ReleaseMatch.Outcome.MISMATCH -> mismatches++
                    ReleaseMatch.Outcome.SKIP -> Unit
                }
            }
        }
        assertEquals(0, passes)
        assertTrue("mismatches $mismatches", mismatches >= 2)
        assertTrue(ReleaseMatch.tally(passes, borderline) != ReleaseMatch.Tally.MATCHED)
    }

    @Test fun anotherShotOfASimilarSceneDoesNotMatch() {
        val own = sig(render(Scene(31, subjectU = 0.3), 480, 270), 480, 270)
        val moved = sig(render(Scene(31, subjectU = 0.62), 240, 135), 240, 135)
        val c = corr(own, moved)!!
        assertTrue("correlation $c", c < ReleaseMatch.MATCH_MIN)
        for (gap in longArrayOf(0L, 300_000L)) {
            assertTrue(ReleaseMatch.outcome(c, null, null, gap) != ReleaseMatch.Outcome.PASS)
        }
        // The same shot still matches.
        val again = corr(own, sig(render(Scene(31, subjectU = 0.3), 240, 135, noise = 5), 240, 135))!!
        assertEquals("correlation $again", ReleaseMatch.Outcome.PASS, ReleaseMatch.outcome(again, c, null, 0L))
    }

    @Test fun theSamePictureMatches() {
        val a = render(Scene(1), 480, 270)
        val b = render(Scene(1), 240, 135, noise = 6, seed = 3)       // other copy, smaller and noisier
        val c = corr(sig(a, 480, 270), sig(b, 240, 135))!!
        assertTrue("correlation $c", c > 0.95f)
        assertEquals(ReleaseMatch.Verdict.MATCH, ReleaseMatch.verdict(c, null, null))
    }

    @Test fun anSdrCopyMatchesAToneMappedLookAlike() {
        val sdr = render(Scene(4), 480, 270)
        val brighter = render(Scene(4), 240, 135, look = { it * 0.6 + 60 })
        val toneMapped = render(Scene(4), 240, 135, look = { 255 * (it / 255).pow(0.6) * 0.85 + 10 })
        val s = sig(sdr, 480, 270)
        val c1 = corr(s, sig(brighter, 240, 135))!!
        val c2 = corr(s, sig(toneMapped, 240, 135))!!
        assertTrue("brightness/contrast $c1", c1 > 0.97f)
        assertTrue("tone map $c2", c2 > 0.90f)
        assertEquals(ReleaseMatch.Verdict.MATCH, ReleaseMatch.verdict(c2, null, null))
    }

    @Test fun aDifferentSceneIsRejected() {
        val own = sig(render(Scene(10), 480, 270), 480, 270)
        val worst = (11..30).maxOf { corr(own, sig(render(Scene(it), 240, 135), 240, 135))!! }
        assertTrue("best unrelated scene $worst", worst < ReleaseMatch.MATCH_MIN)
        val c = corr(own, sig(render(Scene(12), 240, 135), 240, 135))!!
        val v = ReleaseMatch.verdict(c, null, null)
        assertTrue("$c gave $v", v == ReleaseMatch.Verdict.DIFFERENT || v == ReleaseMatch.Verdict.UNSURE)
        assertEquals(ReleaseMatch.Verdict.DIFFERENT, ReleaseMatch.verdict(0.1f, 0.2f, 0.05f))
    }

    @Test fun aOneSlotShiftIsRejected() {
        val own = sig(render(Scene(21), 480, 270), 480, 270)
        val same = corr(own, sig(render(Scene(20), 240, 135), 240, 135))
        val after = corr(own, sig(render(Scene(21), 240, 135, noise = 4), 240, 135))
        assertEquals(ReleaseMatch.Verdict.SHIFTED, ReleaseMatch.verdict(same, null, after))
        // A still scene: the neighbours match nearly as well, the slot itself counts.
        assertEquals(ReleaseMatch.Verdict.MATCH, ReleaseMatch.verdict(0.97f, 0.93f, 0.94f))
    }

    @Test fun barsAreLeftOutOfTheSignature() {
        val boxed = render(Scene(7), 480, 270, bars = 34)
        val open = render(Scene(7), 240, 135, bars = 17)
        val c = corr(sig(boxed, 480, 270, 34), sig(open, 240, 135, 17))!!
        assertTrue("correlation $c", c > 0.95f)
    }

    @Test fun aFlatPictureDecidesNothing() {
        val black = IntArray(480 * 270) { 0xFF101010.toInt() }
        assertNull(ReleaseMatch.signature(black, 480, 270))
        assertEquals(ReleaseMatch.Verdict.UNSURE, ReleaseMatch.verdict(null, 0.9f, 0.9f))
        assertEquals(ReleaseMatch.Verdict.UNSURE, ReleaseMatch.verdict(0.6f, 0.5f, null))
        assertNotNull(ReleaseMatch.signature(render(Scene(2), 64, 36), 64, 36))
    }
}
