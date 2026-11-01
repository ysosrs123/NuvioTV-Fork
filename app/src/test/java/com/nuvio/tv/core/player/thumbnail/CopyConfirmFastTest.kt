package com.nuvio.tv.core.player.thumbnail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The copy is chosen fast, its check pictures come first, and an unconfirmed copy keeps its turns. */
class CopyConfirmFastTest {
    private val ownMs = 7_434_000L
    private val mb = 1024L * 1024

    private class Memory : HardwareFailureMemory {
        val map = HashMap<String, String>()
        override fun get(key: String): String? = map[key]
        override fun put(key: String, value: String) {
            map[key] = value
        }
    }

    private val addons = listOf("AIOS", "StremThru Torz", "Debridio", "Comet", "Eagle", "Torrentio", "MediaFusion")
    private val heights = listOf(2160, 1080, 720, 480, 0)

    /** A source list like a busy one: [n] sources of five sizes, most of them 4K or 1080p remuxes and WEB copies. */
    private fun syntheticOffers(n: Int, seed: Int = 7): CopyOffers {
        val rnd = Random(seed)
        val offers = List(n) { i ->
            val h = heights[rnd.nextInt(heights.size)]
            val kind = CopyKind.entries[rnd.nextInt(CopyKind.entries.size)]
            val web = rnd.nextBoolean()
            val tags = buildString {
                append("Jaws.1975.").append(if (h > 0) "${h}p." else "")
                append(if (web) "WEB-DL." else "BluRay.REMUX.")
                append(if (rnd.nextBoolean()) "H264" else "HEVC")
                if (rnd.nextInt(10) == 0) append(".REMASTERED")
                if (rnd.nextInt(12) == 0) append(".Extended")
                append("-GRP$i [TB⚡] cached ")
                append("☕".repeat(rnd.nextInt(3)))
            }
            CopyOffer(
                id = "s$i", label = "${addons[i % addons.size]}: source $i", kind = kind, height = h,
                codec = if (tags.contains("H264")) CopyCodec.H264 else CopyCodec.HEVC, hdr = rnd.nextBoolean(), web = web,
                sizeBytes = if (rnd.nextInt(8) == 0) null else (500 + rnd.nextInt(80_000)) * mb,
                container = listOf("mkv", "mp4", "avi", "m2ts", null)[rnd.nextInt(5)],
                durationMs = if (rnd.nextInt(3) == 0) null else ownMs + rnd.nextLong(-20_000L, 20_000L),
                text = "$tags ${"x".repeat(rnd.nextInt(200))}", remote = rnd.nextInt(10) == 0,
                source = addons[i % addons.size],
            )
        }
        val playing = CopyOffer("playing", "playing", CopyKind.DEBRID, 2160, CopyCodec.HEVC, true, false, 75_989_174_022L,
            "mkv", ownMs, "Jaws.1975.2160p.UHD.Blu-ray.Remux.DV.HDR10P.HEVC.TrueHD.Atmos.7.1-CiNEPHiLES", source = "AIOS")
        return CopyOffers("Jaws.1975.2160p.UHD.Blu-ray.Remux.DV.HDR10P.HEVC.TrueHD.Atmos.7.1-CiNEPHiLES", offers,
            playing = playing)
    }

    @Test
    fun theOrderAsksAboutEachOfferOnceNotOnEveryComparison() {
        val offers = syntheticOffers(300).offers
        var penalisedCalls = 0
        var batchedCalls = 0
        val ordered = CopyChoice.order(offers, ownMs = ownMs,
            penalised = { penalisedCalls++; it.id.hashCode() % 5 == 0 }) { batchedCalls++; null }
        assertEquals(300, ordered.size)
        assertEquals(300, penalisedCalls)
        assertEquals(300, batchedCalls)
        // Penalised sources still rank after the others of their kind.
        val firstPenalised = ordered.indexOfFirst { it.id.hashCode() % 5 == 0 && it.kind != CopyKind.USENET && !it.remote }
        val lastClean = ordered.indexOfLast {
            it.id.hashCode() % 5 != 0 && it.kind != CopyKind.USENET && !(it.kind == CopyKind.SERVER && it.remote)
        }
        assertTrue(firstPenalised > lastClean)
    }

    @Test
    fun theOrderKeepsEveryRuleAndTiesStayInListOrder() {
        fun o(id: String, kind: CopyKind = CopyKind.LINK, height: Int = 1080, size: Long? = 4_000 * mb) =
            CopyOffer(id, id, kind, height, CopyCodec.H264, false, true, size, "mkv", null, "Film.1080p.WEB")
        val ordered = CopyChoice.order(listOf(
            o("usenet", CopyKind.USENET, 720),
            o("big1080", size = 9_000 * mb),
            o("tie1"),
            o("small720", height = 720, size = 2_000 * mb),
            o("tie2"),
            o("added720", CopyKind.TORBOX_ADD, 720, 1_000 * mb),
        ), ownMs = ownMs).map { it.id }
        assertEquals(listOf("added720", "small720", "tie1", "tie2", "big1080", "usenet"), ordered)
    }

    @Test
    fun threeHundredSourcesAreRankedWellUnderASecond() {
        val list = syntheticOffers(300)
        val book = CopySourceBook(Memory()) { 1_000_000L }
        list.offers.take(40).forEach { book.count(it, CopySourceBook.SLOW) }
        val batched: (CopyOffer) -> Boolean? = { CopyChoice.resolveBatched("https://x.example/resolve/torbox/${it.id}", it.text) }
        fun once(): Long {
            val t0 = System.nanoTime()
            val verdicts = CopyFirst.headStartVerdicts(list, null, batched)
            val top = CopyFirst.headStartPick(verdicts, list, { book.penalised(it) }, batched)
            // The pass's own look at the same list.
            val editions = CopyChoice.editions(list.playingText)
            val usable = list.offers.filter { o ->
                (CopyChoice.rejection(o, list.playingText, ownMs, CopyChoice.FOUR_K, fourKCopies = true, ownEditions = editions)
                    ?: CopyChoice.fourKCopyRejection(o, list.playing, batched(o))) == null
            }
            val ranked = CopyChoice.order(usable, ownMs = ownMs, penalised = { book.penalised(it) }, batched = batched)
            CopyChoice.reasonTally(verdicts.mapNotNull { it.second })
            assertTrue(top == null || ranked.isNotEmpty())
            return (System.nanoTime() - t0) / 1_000_000L
        }
        val cold = once()
        repeat(20) { once() }
        val warm = (0 until 10).map { once() }.sorted()[5]
        println("copy ranking of 300 sources: cold $cold ms, warm median $warm ms")
        // A 2 GB box runs this some 10 to 20 times slower than a PC: under 50 ms here keeps it under a second there.
        assertTrue("cold $cold ms", cold < 1_000L)
        assertTrue("warm $warm ms", warm < 50L)
    }

    @Test
    fun theEditionsOfThePlayingTitleWorkedOutOnceGiveTheSameVerdicts() {
        val list = syntheticOffers(300, seed = 11)
        val editions = CopyChoice.editions(list.playingText)
        for (o in list.offers) {
            assertEquals(
                CopyChoice.rejection(o, list.playingText, ownMs, fourKCopies = true),
                CopyChoice.rejection(o, list.playingText, ownMs, fourKCopies = true, ownEditions = editions),
            )
        }
        val extended = "Jaws.1975.Extended.2160p.REMUX"
        val e = CopyChoice.editions(extended)
        for (o in list.offers) {
            assertEquals(CopyChoice.rejection(o, extended, 0L), CopyChoice.rejection(o, extended, 0L, ownEditions = e))
        }
    }

    @Test
    fun theHeadStartPicksTheSameCopyFromVerdictsWorkedOutOnce() {
        val list = syntheticOffers(300, seed = 3)
        val penalised: (CopyOffer) -> Boolean = { it.id.length % 2 == 0 }
        val verdicts = CopyFirst.headStartVerdicts(list, null)
        assertEquals(CopyFirst.headStartPick(list, null, penalised)?.id,
            CopyFirst.headStartPick(verdicts, list, penalised)?.id)
    }

    /** Own slots every 10 s landing on 4K keyframes every [ownGopUs]; a copy with keyframes at scene cuts. */
    private fun timing(ownGopUs: Long, copyKeyframesUs: LongArray, slots: Int, ratio: Double = 1.0):
        Triple<Map<Int, IntArray>, LongArray, (Int) -> Long> {
        val landing = LongArray(slots) { s ->
            val centre = s * 10_000_000L + 5_000_000L
            (centre + ownGopUs / 2) / ownGopUs * ownGopUs
        }
        val copyCount = ReleaseMatch.slotCount((slots * 10_000L * ratio).toLong(), 10_000L)
        val owners = CopyChoice.ownSlotsByCopySlot(slots, ratio, copyCount)
        val kfOf = LongArray(copyCount) { -1L }
        for ((m, served) in owners) {
            val want = (landing[served.first()] * ratio).toLong()
            kfOf[m] = copyKeyframesUs.minByOrNull { kotlin.math.abs(it - want) } ?: -1L
        }
        return Triple(owners, landing, { m -> kfOf.getOrElse(m) { -1L } })
    }

    @Test
    fun theCopyMakesSameFramePicturesForTheCheckFirstSpreadOverTheTitle() {
        val slots = 744
        val ownGop = 1_001_000L
        val rnd = Random(5)
        // Copy keyframes at scene cuts every few seconds; a share of them on an own keyframe (the same frame).
        val copyKf = generateSequence(0L) { it + 2_000_000L + rnd.nextLong(0L, 9_000_000L) }
            .takeWhile { it < slots * 10_000_000L }
            .map { t -> if (rnd.nextInt(4) == 0) t / ownGop * ownGop else t }
            .toList().toLongArray()
        val (owners, landing, copyUs) = timing(ownGop, copyKf, slots)
        val gaps = ReleaseMatch.copyGaps(owners, copyUs, landing, 1.0)
        assertTrue(gaps.all { it.second <= ReleaseMatch.WIDE_US })
        val first = ReleaseMatch.pickCheckSlots(gaps, owners.size)
        assertEquals(ReleaseMatch.CHECKS_NEEDED + ReleaseMatch.CHECK_SPARES, first.size)
        val gapOf = gaps.toMap()
        val sameFrame = gaps.count { kotlin.math.abs(it.second) <= ReleaseMatch.SAME_FRAME_US }
        assertTrue("same-frame pairs $sameFrame", sameFrame >= 6)
        // Every pick is a same-frame pair while there are enough of them, the first three near 1/6, 1/2 and 5/6.
        assertTrue(first.all { kotlin.math.abs(gapOf.getValue(it)) <= ReleaseMatch.SAME_FRAME_US })
        val n = owners.size
        assertTrue(first[0] < n / 3 && first[1] in n / 3 until 2 * n / 3 && first[2] >= 2 * n / 3)
    }

    @Test
    fun copyGapsFollowThePalTimingAndLeaveOutMissingKeyframes() {
        val ratio = 0.959
        val slots = 300
        val ownGop = 1_000_000L
        val copyKf = LongArray(800) { (it * 3_600_000L * ratio).toLong() }
        val (owners, landing, copyUs) = timing(ownGop, copyKf, slots, ratio)
        val gaps = ReleaseMatch.copyGaps(owners, copyUs, landing, ratio)
        assertTrue(gaps.isNotEmpty())
        for ((m, gap) in gaps) {
            val onOwn = (copyUs(m) / ratio).toLong()
            val best = owners.getValue(m).minOf { kotlin.math.abs(landing[it] - onOwn) }
            assertEquals(best, kotlin.math.abs(gap))
        }
        assertTrue(ReleaseMatch.copyGaps(owners, { -1L }, landing, ratio).isEmpty())
        assertTrue(ReleaseMatch.copyGaps(owners, copyUs, LongArray(slots) { -1L }, ratio).isEmpty())
        assertTrue(ReleaseMatch.copyGaps(owners, copyUs, landing, 0.0).isEmpty())
    }

    @Test
    fun aCopyThatIsNotConfirmedGetsTurnsOnASmallBoxOnly() {
        assertFalse(CopyFirst.turnStarts(strongBox = false, filling = true, unconfirmed = true, waitedMs = 2_999))
        assertTrue(CopyFirst.turnStarts(strongBox = false, filling = true, unconfirmed = true, waitedMs = 3_000))
        assertFalse(CopyFirst.turnStarts(strongBox = true, filling = true, unconfirmed = true, waitedMs = 60_000))
        assertFalse(CopyFirst.turnStarts(strongBox = false, filling = false, unconfirmed = true, waitedMs = 60_000))
        assertFalse(CopyFirst.turnStarts(strongBox = false, filling = true, unconfirmed = false, waitedMs = 60_000))
        assertFalse(CopyFirst.turnOver(filling = true, unconfirmed = true, sinceMs = 9_999, made = 7))
        assertTrue(CopyFirst.turnOver(filling = true, unconfirmed = true, sinceMs = 10_000, made = 0))
        assertTrue(CopyFirst.turnOver(filling = true, unconfirmed = true, sinceMs = 1_000, made = 8))
        assertTrue(CopyFirst.turnOver(filling = false, unconfirmed = true, sinceMs = 0, made = 0))
        assertTrue(CopyFirst.turnOver(filling = true, unconfirmed = false, sinceMs = 0, made = 0))
    }
}
