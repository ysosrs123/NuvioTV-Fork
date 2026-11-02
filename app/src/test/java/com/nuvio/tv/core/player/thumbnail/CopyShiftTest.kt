package com.nuvio.tv.core.player.thumbnail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** A copy a few logos or credits longer or shorter: one shift found from the keyframes, and how it maps. */
class CopyShiftTest {
    private val filmUs = 7_912_000_000L
    private val frameUs = 41_708L

    /** Scene cuts on frame boundaries, about every 4 s. */
    private fun cuts(seed: Int, untilUs: Long = filmUs): LongArray {
        val rnd = Random(seed)
        val out = ArrayList<Long>()
        var t = 0L
        while (t < untilUs) {
            t += (1 + rnd.nextInt(190)) * frameUs
            out += t
        }
        return out.toLongArray()
    }

    /** A remux: a keyframe on most cuts plus one every 24 frames. */
    private fun remux(cuts: LongArray): LongArray {
        val rnd = Random(7)
        val gop = (0 until (filmUs / (24 * frameUs)).toInt()).map { it * 24 * frameUs }
        return (gop + cuts.filter { rnd.nextInt(10) < 8 }).distinct().sorted().toLongArray()
    }

    /** A web copy: a keyframe on most cuts, at least one every 250 frames. */
    private fun web(cuts: LongArray, shiftUs: Long): LongArray {
        val rnd = Random(11)
        val kept = cuts.filter { rnd.nextInt(10) < 9 }.map { it + shiftUs }.filter { it >= 0L }
        val out = ArrayList<Long>()
        var last = 0L
        for (k in kept) {
            while (k - last > 250 * frameUs) {
                last += 250 * frameUs
                out += last
            }
            out += k
            last = k
        }
        return out.toLongArray()
    }

    @Test
    fun `a copy ten seconds longer at the start lines up`() {
        val c = cuts(1)
        val shift = 240 * frameUs
        val found = ReleaseMatch.findShift(remux(c), web(c, shift), ReleaseMatch.MAX_SHIFT_MS * 1000L, filmUs)
        assertNotNull(found)
        assertEquals(shift.toDouble(), found!!.shiftUs.toDouble(), 2_000.0)
        assertTrue(found.thirds.all { it >= ReleaseMatch.SHIFT_MIN_THIRD })
        assertTrue(found.pairs > 10 * found.atZero.coerceAtLeast(1))
    }

    @Test
    fun `a copy that starts sooner lines up with a negative shift`() {
        val c = cuts(2)
        val shift = -700 * frameUs
        val found = ReleaseMatch.findShift(remux(c), web(c, shift), ReleaseMatch.MAX_SHIFT_MS * 1000L, filmUs)
        assertEquals(shift.toDouble(), found!!.shiftUs.toDouble(), 2_000.0)
    }

    @Test
    fun `a copy with other credits only at the end keeps no shift`() {
        val c = cuts(3)
        val copy = web(c.filter { it < filmUs - 120_000_000L }.toLongArray(), 0L)
        val found = ReleaseMatch.findShift(remux(c), copy, ReleaseMatch.SAME_LENGTH_MS * 1000L, filmUs)
        assertNotNull(found)
        assertEquals(0.0, found!!.shiftUs.toDouble(), 2_000.0)
    }

    @Test
    fun `a scene added in the middle is another cut`() {
        val c = cuts(4)
        val half = filmUs / 3
        val copy = (web(c.filter { it < half }.toLongArray(), 0L) +
            web(c.filter { it >= half }.toLongArray(), 45_000_000L)).sorted().toLongArray()
        assertNull(ReleaseMatch.findShift(remux(c), copy, ReleaseMatch.MAX_SHIFT_MS * 1000L, filmUs))
    }

    @Test
    fun `another film has no shift`() {
        assertNull(ReleaseMatch.findShift(remux(cuts(5)), web(cuts(6), 0L), ReleaseMatch.MAX_SHIFT_MS * 1000L, filmUs))
    }

    @Test
    fun `too few keyframes decide nothing`() {
        assertNull(ReleaseMatch.findShift(LongArray(5) { it * 1_000_000L }, LongArray(5) { it * 1_000_000L },
            ReleaseMatch.MAX_SHIFT_MS * 1000L, filmUs))
    }

    @Test
    fun `lengths a few minutes apart are worth lining up`() {
        assertFalse(ReleaseMatch.shiftCandidate(7_912_000L, 7_915_000L))
        assertTrue(ReleaseMatch.shiftCandidate(7_912_000L, 7_922_000L))
        assertTrue(ReleaseMatch.shiftCandidate(6_417_000L, 6_388_000L))
        assertTrue(ReleaseMatch.shiftCandidate(7_200_000L, 7_380_000L))
        assertFalse(ReleaseMatch.shiftCandidate(7_200_000L, 7_380_001L))
        assertFalse(ReleaseMatch.shiftCandidate(0L, 7_200_000L))
    }

    @Test
    fun `a shifted timing maps both ways and moves slots`() {
        val t = ReleaseMatch.Timing(1.0, 10_000_000L)
        assertEquals(25_000_000L, t.toCopyUs(15_000_000L))
        assertEquals(15_000_000L, t.toOwnUs(25_000_000L))
        assertEquals(6, t.mapSlot(5, 800, 10_000L))
        assertEquals(-1, t.mapSlot(799, 800, 10_000L))
        val back = ReleaseMatch.Timing(1.0, -12_500_000L)
        assertEquals(-1, back.mapSlot(0, 800, 10_000L))
        assertEquals(4, back.mapSlot(5, 800, 10_000L))
        assertEquals("shifted +10.00 s", t.describe())
        assertEquals("same length", ReleaseMatch.Timing.SAME.describe())
        assertEquals(123, ReleaseMatch.Timing.SAME.mapSlot(123, 720, 10_000L))
    }

    @Test
    fun `a shifted copy serves the own slots one step along`() {
        val owners = CopyChoice.ownSlotsByCopySlot(10, ReleaseMatch.Timing(1.0, 10_000_000L), 11, 10_000L)
        assertEquals(listOf(0), owners[1]?.toList())
        assertEquals(listOf(9), owners[10]?.toList())
        assertNull(owners[0])
    }

    @Test
    fun `a length within three minutes leaves the decision to the keyframes`() {
        fun offer(ms: Long) = CopyOffer("a", "a", CopyKind.LINK, 720, CopyCodec.H264, false, true, 950_000_000L, "mkv",
            ms, "Parasite.2019.720p.WEB-DL.H264-GRP")
        val playing = "Parasite.2019.2160p.UHD.BluRay.REMUX.DV.HEVC.TrueHD-GRP"
        assertNull(CopyChoice.rejection(offer(7_922_000L), playing, 7_912_000L))
        assertNotNull(CopyChoice.rejection(offer(8_200_000L), playing, 7_912_000L))
    }

    @Test
    fun `the same file from another add-on is the same size to the byte`() {
        fun offer(id: String, size: Long?, height: Int = 720) =
            CopyOffer(id, id, CopyKind.LINK, height, CopyCodec.H264, false, true, size, "mkv", null, "x")
        assertTrue(CopyFirst.sameFile(offer("a", 996_147_200L), offer("b", 996_147_200L)))
        assertFalse(CopyFirst.sameFile(offer("a", 996_147_200L), offer("b", 1_213_202_432L)))
        assertFalse(CopyFirst.sameFile(offer("a", null), offer("b", null)))
        assertFalse(CopyFirst.sameFile(offer("a", 996_147_200L), offer("b", 996_147_200L, 1080)))
        assertTrue(CopyFirst.turnsDownTheFile("length 7922 s against 7912 s, and no one shift lines its keyframes up"))
        assertFalse(CopyFirst.turnsDownTheFile("reads failed (IOException)"))
        assertFalse(CopyFirst.turnsDownTheFile(null))
    }

    @Test
    fun `the backup copy is another file`() {
        fun offer(id: String, size: Long) =
            CopyOffer(id, id, CopyKind.LINK, 720, CopyCodec.H264, false, true, size, "mkv", null, "x")
        val top = offer("aios", 996_147_200L)
        val order = listOf(top, offer("stremthru", 996_147_200L), offer("debridio", 1_158_676_480L), offer("comet", 1_213_202_432L))
        assertEquals("debridio", CopyFirst.backupOf(order, top)?.id)
        assertNull(CopyFirst.backupOf(listOf(top, offer("same", 996_147_200L)), top))
        assertTrue(CopyFirst.turnsDownTheFile("only 30 s long, not the film"))
    }
}
