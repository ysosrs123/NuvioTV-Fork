package com.nuvio.tv.core.player.thumbnail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Thumbnail decodes on boxes with swap: refused under the swap floors in every state, and the decoders let go. */
class ThumbSwapHoldTest {

    private fun mem(availMb: Long = 400, swapTotalMb: Long = 512, swapFreeMb: Long = 300) =
        MemoryGovernor.Meminfo(availMb, swapTotalMb, swapFreeMb)

    private fun fireStick(availMb: Long = 400, swapFreeMb: Long, funded: Boolean, chargeMb: Long = 0) =
        MemoryGovernor.decodeVerdict(mem(availMb = availMb, swapFreeMb = swapFreeMb), chargeMb, funded, lowRam = true)

    @Test
    fun pausedFundedDecodeIsRefusedUnder48MbSwapWhateverMemAvailableSays() {
        val v = fireStick(availMb = 900, swapFreeMb = 47, funded = true)
        assertFalse(v.allowed)
        assertTrue(v.swapLow)
        assertEquals(47L, v.swapFreeMb)
        assertTrue(v.reason, v.reason.startsWith("SwapFree 47 MB < 48"))
        assertTrue(fireStick(availMb = 900, swapFreeMb = 48, funded = true).allowed)
        val empty = fireStick(availMb = 190, swapFreeMb = 0, funded = true)
        assertFalse("swap at 0 while paused", empty.allowed)
        assertTrue(empty.swapLow)
    }

    @Test
    fun decodeDuringPlaybackBacksOffForSwapEvenWithMemAvailableFine() {
        val v = fireStick(availMb = 450, swapFreeMb = 63, funded = false, chargeMb = 80)
        assertFalse(v.allowed)
        assertTrue(v.swapLow)
        assertTrue(fireStick(availMb = 450, swapFreeMb = 64, funded = false, chargeMb = 80).allowed)
    }

    @Test
    fun softwareCarriesOnAfterTheHardwareDecoderClosedWhileTheBoxHasRoom() {
        // On a 2 GB Fire TV Stick: the decoder closed at SwapFree 43, software was then held at 44 with 425 MB free.
        val held = GovernorVerdict(false, "SwapFree 44 MB < 64", 44L)
        val lifted = MemoryGovernor.afterHardwareClose(held, mem(availMb = 425, swapFreeMb = 44), DecodeTier.SW_4K_STRIPS, lowRam = true)
        assertTrue(lifted.reason, lifted.allowed)
        assertTrue(lifted.reason, lifted.reason.contains("400 MB free after the 4K hardware decoder closed"))
        // Not with the box really out of swap, nor without 100 MB of room over the floor and the decode's own size.
        assertFalse(MemoryGovernor.afterHardwareClose(GovernorVerdict(false, "SwapFree 31 MB < 64", 31L),
            mem(availMb = 425, swapFreeMb = 31), DecodeTier.SW_4K_STRIPS, lowRam = true).allowed)
        assertFalse(MemoryGovernor.afterHardwareClose(held, mem(availMb = 324, swapFreeMb = 44), DecodeTier.SW_4K_STRIPS, lowRam = true).allowed)
        assertTrue(MemoryGovernor.afterHardwareClose(held, mem(availMb = 325, swapFreeMb = 44), DecodeTier.SW_4K_STRIPS, lowRam = true).allowed)
        // A verdict that is not a swap refusal is passed through untouched.
        val notSwap = GovernorVerdict(false, "MemAvailable 150 MB < 200")
        assertEquals(notSwap, MemoryGovernor.afterHardwareClose(notSwap, mem(availMb = 900), DecodeTier.SW_4K_STRIPS, lowRam = true))
        val ok = GovernorVerdict(true, "ok")
        assertEquals(ok, MemoryGovernor.afterHardwareClose(ok, mem(), DecodeTier.SW_4K_STRIPS, lowRam = true))
    }

    @Test
    fun memAvailableRefusalIsNotASwapRefusal() {
        val v = fireStick(availMb = 150, swapFreeMb = 0, funded = false)
        assertFalse(v.allowed)
        assertFalse("the MemAvailable floor is checked first", v.swapLow)
        assertTrue(GovernorVerdict(true, "ok").let { !it.swapLow })
    }

    @Test
    fun swapCheckAloneMatchesTheDecodeVerdict() {
        assertNotNull(MemoryGovernor.swapVerdict(mem(swapFreeMb = 47), funded = true))
        assertNull(MemoryGovernor.swapVerdict(mem(swapFreeMb = 48), funded = true))
        assertNotNull(MemoryGovernor.swapVerdict(mem(swapFreeMb = 63), funded = false))
        assertNull(MemoryGovernor.swapVerdict(mem(swapFreeMb = 64), funded = false))
        assertNull("no swap on the box", MemoryGovernor.swapVerdict(mem(swapTotalMb = 0, swapFreeMb = 0), funded = true))
        assertNull("Xiaomi: 658 MB available beside a full swap",
            MemoryGovernor.swapVerdict(mem(availMb = 658, swapFreeMb = 62), funded = false, chargeMb = 120))
        assertNotNull("Fire Stick: little memory and a full swap",
            MemoryGovernor.swapVerdict(mem(availMb = 250, swapFreeMb = 36), funded = false))
    }

    @Test
    fun boxesWithoutSwapAndStrongBoxesKeepTheirDecodes() {
        assertTrue(MemoryGovernor.decodeVerdict(mem(availMb = 500, swapTotalMb = 0, swapFreeMb = 0), 80, false, lowRam = false).allowed)
        assertTrue(MemoryGovernor.decodeVerdict(mem(availMb = 1_500, swapFreeMb = 900), 80, false, lowRam = false).allowed)
    }

    private fun shield(availMb: Long, swapFreeMb: Long = 0, funded: Boolean = false, chargeMb: Long = 35) =
        MemoryGovernor.decodeVerdict(mem(availMb = availMb, swapFreeMb = swapFreeMb), chargeMb, funded, lowRam = false,
            strongHardware = true)

    @Test
    fun aThreeGbBoxWithFullSwapButPlentyAvailableKeepsDecoding() {
        assertTrue("Shield log: MemAvailable 583 MB, SwapFree 0", shield(availMb = 583).allowed)
        assertTrue(shield(availMb = 794, funded = true).allowed)
        assertTrue("charge included: 535 - 35 = 500", shield(availMb = 535).allowed)
        val tight = shield(availMb = 534)
        assertFalse(tight.allowed)
        assertTrue("near the floor swap counts again", tight.swapLow)
        assertTrue(shield(availMb = 385, funded = true).allowed)
        assertTrue(shield(availMb = 384, funded = true).swapLow)
    }

    @Test
    fun smallBoxesKeepTheSwapFloorsWhateverMemAvailableSays() {
        val fire = MemoryGovernor.decodeVerdict(mem(availMb = 900, swapFreeMb = 0), 35, false, lowRam = true,
            strongHardware = false)
        assertTrue(fire.swapLow)
        val twoGbNotLowRam = MemoryGovernor.decodeVerdict(mem(availMb = 900, swapFreeMb = 0), 35, true, lowRam = false,
            strongHardware = false)
        assertTrue(twoGbNotLowRam.swapLow)
        assertNotNull(MemoryGovernor.swapVerdict(mem(availMb = 900, swapFreeMb = 0), funded = true))
    }

    @Test
    fun theSwapCheckAfterTheFetchAndTheTitleStopFollowTheSameRoom() {
        assertNull(MemoryGovernor.swapVerdict(mem(availMb = 600, swapFreeMb = 0), funded = false, lowRam = false,
            strongHardware = true))
        assertNotNull(MemoryGovernor.swapVerdict(mem(availMb = 499, swapFreeMb = 0), funded = false, lowRam = false,
            strongHardware = true))
        assertNull(MemoryGovernor.pressureReason(false, 583, 1_024, 0, 300, strongHardware = true))
        assertTrue(MemoryGovernor.pressureReason(false, 450, 1_024, 0, 300, strongHardware = true)!!.startsWith("SwapFree"))
        assertTrue(MemoryGovernor.pressureReason(false, 900, 1_024, 0, 200, strongHardware = false)!!.startsWith("SwapFree"))
    }

    @Test
    fun aSwapRefusalClosesTheDecodersOnSmallBoxesOnly() {
        val swap = fireStick(swapFreeMb = 10, funded = true)
        assertTrue("Fire TV Stick", MemoryGovernor.closesDecodersFor(swap, strongHardware = false))
        assertFalse("AM9 / Shield keep trimming", MemoryGovernor.closesDecodersFor(swap, strongHardware = true))
        val floor = fireStick(availMb = 120, swapFreeMb = 10, funded = true, chargeMb = 80)
        assertFalse(MemoryGovernor.closesDecodersFor(floor, strongHardware = false))
        assertFalse(MemoryGovernor.closesDecodersFor(GovernorVerdict(true, "ok"), strongHardware = false))
        val hovering = fireStick(swapFreeMb = 40, funded = true)
        assertFalse(hovering.allowed)
        assertFalse("just under the floor: trimmed, not closed", MemoryGovernor.closesDecodersFor(hovering, strongHardware = false))
        assertTrue(MemoryGovernor.closesDecodersFor(fireStick(swapFreeMb = 31, funded = true), strongHardware = false))
        assertFalse(MemoryGovernor.closesDecodersFor(fireStick(swapFreeMb = 32, funded = true), strongHardware = false))
    }

    @Test
    fun aRunningLaneFinishesItsDecodeAndThenLetsItsDecoderGo() {
        val lane = DecoderLane()
        lane.acquire()
        lane.close()
        assertTrue("the decode under way is not cut off", lane.inUse)
        assertTrue(lane.closePending)
        lane.release()
        assertFalse(lane.inUse)
        assertFalse(lane.closePending)
        assertFalse(lane.decoder.warm)
        lane.close()
        assertFalse("an idle lane closes at once", lane.closePending)
    }

    @Test
    fun onlyTheFirstSwapRefusalOfASessionIsLoggedAndTheSummaryCountsThemAll() {
        val holds = SwapHolds()
        assertNull(holds.summary())
        val floor = fireStick(availMb = 120, swapFreeMb = 10, funded = false, chargeMb = 80)
        assertFalse(holds.refused(floor, paused = true, funded = false, afterFetch = false, closed = false, nowMs = 0))
        assertNull("a MemAvailable refusal is not counted", holds.summary())

        val first = fireStick(swapFreeMb = 41, funded = true)
        assertTrue(holds.refused(first, paused = true, funded = true, afterFetch = false, closed = true, nowMs = 1_000))
        assertEquals(
            "decodes held for swap: SwapFree 41 MB < 48 (paused, funded), decoders closed until it recovers",
            holds.firstLine(first, paused = true, funded = true, afterFetch = false, closed = true),
        )
        val later = fireStick(swapFreeMb = 0, funded = false)
        assertFalse(holds.refused(later, paused = false, funded = false, afterFetch = true, closed = true, nowMs = 51_000))
        assertEquals(2, holds.count)
        assertEquals(0L, holds.minSwapFreeMb)
        assertEquals(
            "swap holds this session: refused 2 (paused 1, funded 1, after the fetch 1), lowest SwapFree 0 MB, " +
                "decoders closed 2, over 50 s",
            holds.summary(),
        )
    }
}
