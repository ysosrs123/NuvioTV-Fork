package com.nuvio.tv.core.player.thumbnail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 4K hardware thumbnails on a small box whose decoder takes its pictures from app memory. */
class HardwareAppMemoryTest {
    private fun mem(avail: Long, swapFree: Long = 200L, swapTotal: Long = 512L) =
        MemoryGovernor.Meminfo(avail, swapTotal, swapFree)

    private fun why(
        on: Boolean = true,
        screenUp: Boolean = true,
        letGo: Boolean = true,
        sinceLetGoMs: Long = 5_000L,
        availNow: Long = 500L,
        availAtLetGo: Long = 300L,
    ) = AppMemoryHardware.softwareBecause(on, screenUp, letGo, sinceLetGoMs, { availNow }, availAtLetGo)

    @Test fun theSwitchFollowsThePropertyAndOtherwiseTheDefault() {
        assertEquals(AppMemoryHardware.ON_BY_DEFAULT, AppMemoryHardware.onFor(null))
        assertEquals(AppMemoryHardware.ON_BY_DEFAULT, AppMemoryHardware.onFor(""))
        assertEquals(AppMemoryHardware.ON_BY_DEFAULT, AppMemoryHardware.onFor("true"))
        assertFalse(AppMemoryHardware.onFor("0"))
        assertTrue(AppMemoryHardware.onFor("1"))
        assertTrue(AppMemoryHardware.onFor(" 1 "))
    }

    @Test fun theSmallInputBufferIsOnUnlessSetToZero() {
        assertTrue(AppMemoryHardware.smallInputFor(null))
        assertTrue(AppMemoryHardware.smallInputFor("1"))
        assertFalse(AppMemoryHardware.smallInputFor("0"))
    }

    @Test fun theOpenFloorCanBeSetBetween130And200Only() {
        assertEquals(150L, AppMemoryHardware.openFloorFor(null))
        assertEquals(150L, AppMemoryHardware.openFloorFor("abc"))
        assertEquals(130L, AppMemoryHardware.openFloorFor("130"))
        assertEquals(175L, AppMemoryHardware.openFloorFor(" 175 "))
        assertEquals(150L, AppMemoryHardware.openFloorFor("120"))
        assertEquals(150L, AppMemoryHardware.openFloorFor("250"))
        assertTrue(AppMemoryHardware.MIN_OPEN_FLOOR_MB > AppMemoryHardware.KEEP_FLOOR_MB)
    }

    @Test fun theReleaseBeforeTheOpenIsOnUnlessSetToZero() {
        assertTrue(AppMemoryHardware.releaseFor(null))
        assertTrue(AppMemoryHardware.releaseFor("1"))
        assertFalse(AppMemoryHardware.releaseFor("0"))
    }

    @Test fun aLowerOpenFloorLetsThe430MbStartOpen() {
        // On a 2 GB Fire TV Stick: 394-430 MB when the screen came up; 300 MB charged.
        assertFalse(AppMemoryHardware.verdict(mem(430), warm = false, costMb = 300).allowed)
        assertTrue(AppMemoryHardware.verdict(mem(430), warm = false, costMb = 300, openFloorMb = 130).allowed)
        assertFalse(AppMemoryHardware.verdict(mem(394), warm = false, costMb = 300, openFloorMb = 130).allowed)
        // An open decoder keeps the keep floor whatever the open floor is.
        assertFalse(AppMemoryHardware.verdict(mem(119), warm = true, costMb = 300, openFloorMb = 130).allowed)
    }

    @Test fun theReleaseStepsAddUpWhatEachGaveBack() {
        val r = AppMemoryRelease(394)
        assertEquals("4K hardware open, 1 software decoders closed: MemAvailable 394 -> 420 MB",
            r.step("1 software decoders closed", 420).line())
        r.step("idle thumbnail buffers given back", 425)
        r.step("Java heap collected", -1)
        val last = r.step("settled 300 ms", 431)
        assertEquals(425L, last.beforeMb)
        assertEquals(431L, r.lastMb)
        assertEquals(0L, r.steps[2].gainedMb)
        assertEquals("4K hardware open: MemAvailable 394 -> 431 MB after giving back the thumbnails' own memory (+37 MB: " +
            "1 software decoders closed +26, idle thumbnail buffers given back +5, Java heap collected +0, settled 300 ms +6)",
            r.summary())
    }

    @Test fun switchedOffThe4KTitleStaysOnSoftwareWithTheOldReason() {
        assertEquals(AppMemoryHardware.OFF_TEXT, why(on = false))
        val route = HardwareRoute.softwareBecause(30, FetchPhase.GENERATING, VideoCodec.HEVC, 10, null, null, false, true,
            fourKFromAppMemory = true)
        assertEquals(true, route?.startsWith("4K on a small box"))
    }

    @Test fun onlyBehindTheScreenWithTheFilmLetGo() {
        assertTrue(why(screenUp = false)!!.contains("only behind the generating screen"))
        assertTrue(why(letGo = false)!!.contains("only behind the generating screen"))
        assertNull(why())
    }

    @Test fun theRouteGivesTheAppMemoryReason() {
        val r = HardwareRoute.softwareBecause(30, FetchPhase.GENERATING, VideoCodec.HEVC, 10, null, null, false, true,
            fourKFromAppMemory = true, appMemoryWhy = "waiting for the film's decoder to let go")
        assertEquals("waiting for the film's decoder to let go", r)
        assertNull(HardwareRoute.softwareBecause(30, FetchPhase.GENERATING, VideoCodec.HEVC, 10, null, null, false, true,
            fourKFromAppMemory = false, appMemoryWhy = null))
    }

    @Test fun theFilmsDecoderCountsAsGoneAfterTwoSecondsOrOnceItsMemoryIsBack() {
        assertEquals("waiting for the film's decoder to let go", why(sinceLetGoMs = 500L, availNow = 400L, availAtLetGo = 300L))
        assertNull(why(sinceLetGoMs = 500L, availNow = 450L, availAtLetGo = 300L))
        assertNull(why(sinceLetGoMs = 2_000L, availNow = 300L, availAtLetGo = 300L))
        assertFalse(AppMemoryHardware.filmLetGo(500L, { 900L }, -1L))
        assertFalse(AppMemoryHardware.filmLetGo(500L, { -1L }, 300L))
    }

    @Test fun aColdDecoderIsChargedItsCostAgainstTheOpenFloor() {
        // On a 2 GB Fire TV Stick: 530 MB at the open, the decoder took about 295.
        assertTrue(AppMemoryHardware.verdict(mem(530), warm = false, costMb = 300).allowed)
        assertTrue(AppMemoryHardware.verdict(mem(450), warm = false, costMb = 300).allowed)
        val v = AppMemoryHardware.verdict(mem(430), warm = false, costMb = 300)
        assertFalse(v.allowed)
        assertFalse(v.swapLow)
        assertTrue(v.reason.contains("floor 150"))
    }

    @Test fun anOpenDecoderKeepsGoingDownToTheKeepFloor() {
        assertTrue(AppMemoryHardware.verdict(mem(120), warm = true, costMb = 300).allowed)
        assertFalse(AppMemoryHardware.verdict(mem(119), warm = true, costMb = 300).allowed)
        // The old 2 GB floor refused at 199 MB with the decoder open.
        assertTrue(AppMemoryHardware.verdict(mem(199), warm = true, costMb = 300).allowed)
    }

    @Test fun swapFloorsForOpeningAndKeeping() {
        // On a 2 GB Fire TV Stick: opens with 107 to 116 MB of swap free closed within 22 s, 171 and 236 held.
        val cold = AppMemoryHardware.verdict(mem(600, swapFree = 116), warm = false, costMb = 300)
        assertFalse(cold.allowed)
        assertTrue(cold.swapLow)
        assertFalse(AppMemoryHardware.verdict(mem(600, swapFree = 149), warm = false, costMb = 300).allowed)
        assertTrue(AppMemoryHardware.verdict(mem(600, swapFree = 150), warm = false, costMb = 300).allowed)
        // Open: 30 MB of swap is enough while the box has 150 MB free, 48 below that.
        assertTrue(AppMemoryHardware.verdict(mem(300, swapFree = 30), warm = true, costMb = 300).allowed)
        assertFalse(AppMemoryHardware.verdict(mem(300, swapFree = 29), warm = true, costMb = 300).allowed)
        assertTrue(AppMemoryHardware.verdict(mem(150, swapFree = 30), warm = true, costMb = 300).allowed)
        assertTrue(AppMemoryHardware.verdict(mem(149, swapFree = 48), warm = true, costMb = 300).allowed)
        assertFalse(AppMemoryHardware.verdict(mem(149, swapFree = 47), warm = true, costMb = 300).allowed)
        assertEquals(30L, AppMemoryHardware.keepSwapMb(178))
        assertEquals(48L, AppMemoryHardware.keepSwapMb(140))
        assertTrue(AppMemoryHardware.verdict(mem(300, swapFree = 0, swapTotal = 0), warm = true, costMb = 300).allowed)
        assertTrue(AppMemoryHardware.verdict(mem(600, swapFree = 0, swapTotal = 0), warm = false, costMb = 300).allowed)
    }

    @Test fun theCostStartsAt250AndStaysInItsRange() {
        assertEquals(250, AppMemoryHardware.costMb(null))
        assertEquals(295, AppMemoryHardware.costMb(295))
        assertEquals(150, AppMemoryHardware.costMb(20))
        assertEquals(600, AppMemoryHardware.costMb(5_000))
    }

    @Test fun theCostIsLearnedFromTheDropAtOpen() {
        assertEquals(295, AppMemoryHardware.learn(null, 295L))
        assertEquals(308, AppMemoryHardware.learn(295, 320L))
        assertEquals(150, AppMemoryHardware.learn(null, 60L))
        assertNull(AppMemoryHardware.learn(295, 10L))
        assertNull(AppMemoryHardware.learn(295, -40L))
        assertNull(AppMemoryHardware.learn(295, 1_200L))
    }

    @Test fun theCostBookKeepsOnePerBoxAndFormat() {
        val m = HashMap<String, String>()
        val book = HardwareCostBook(object : HardwareFailureMemory {
            override fun get(key: String): String? = m[key]
            override fun put(key: String, value: String) { m[key] = value }
        })
        val key = HardwareCostBook.key("box1", VideoCodec.HEVC)
        assertNull(book.get(key))
        assertEquals(295, book.note(key, 295L))
        assertNull(book.note(key, 5L))
        assertEquals(295, book.get(key))
        assertEquals(308, book.note(key, 320L))
        assertNull(book.get(HardwareCostBook.key("box1", VideoCodec.H264)))
    }

    @Test fun theFilmWaitsForTheDecoderToCloseAndItsMemoryToComeBack() {
        assertFalse(AppMemoryHardware.filmMayLoad(1_000L, -1L, 200L, 500L))
        assertTrue(AppMemoryHardware.filmMayLoad(AppMemoryHardware.CLOSE_WAIT_MS, -1L, 200L, 500L))
        assertFalse(AppMemoryHardware.filmMayLoad(1_200L, 200L, 300L, 500L))
        assertTrue(AppMemoryHardware.filmMayLoad(1_200L, 200L, 440L, 500L))
        assertTrue(AppMemoryHardware.filmMayLoad(3_000L, AppMemoryHardware.BACK_WAIT_MS, 300L, 500L))
        assertTrue(AppMemoryHardware.filmMayLoad(0L, 0L, 300L, -1L))
    }

    @Test fun memoryCountsAsBackWithin60Mb() {
        assertTrue(AppMemoryHardware.memoryBack(472L, 530L))
        assertFalse(AppMemoryHardware.memoryBack(469L, 530L))
        assertFalse(AppMemoryHardware.memoryBack(-1L, 530L))
        assertTrue(AppMemoryHardware.memoryBack(-1L, -1L))
    }

    @Test fun theInputBufferFollowsTheKeyframes() {
        val mb = 1 shl 20
        val max = HardwareThumbDecoder.MAX_INPUT_BYTES
        assertEquals(mb, HardwareInputSize.wanted(40_000, 0, max))
        assertEquals(2 * mb, HardwareInputSize.wanted(1_500_000, 0, max))
        assertEquals(2 * mb, HardwareInputSize.wanted(1_800_000, 2 * mb, max))
        // A bigger keyframe at least doubles it, so a title reopens a few times at most.
        assertEquals(4 * mb, HardwareInputSize.wanted(2 * mb + 1, 2 * mb, max))
        assertEquals(5 * mb, HardwareInputSize.wanted(4 * mb, 2 * mb, max))
        assertEquals(max, HardwareInputSize.wanted(7 * mb, 4 * mb, max))
    }
}
