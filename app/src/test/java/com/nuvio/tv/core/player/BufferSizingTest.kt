package com.nuvio.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BufferSizingTest {

    private val mb = 1024L * 1024L
    private val gb = 1024L * mb

    private fun free(availMb: Long, thresholdMb: Long, low: Boolean = false) =
        BufferSizing.FreeMemory(availMb * mb, thresholdMb * mb, low)

    @Test
    fun `one size per box`() {
        assertEquals(200, BufferSizing.autoBufferMbForRam(0L))
        assertEquals(100, BufferSizing.autoBufferMbForRam((0.9 * gb).toLong()))
        assertEquals(125, BufferSizing.autoBufferMbForRam((1.3 * gb).toLong()))
        assertEquals(200, BufferSizing.autoBufferMbForRam((1.9 * gb).toLong()))
        assertEquals(500, BufferSizing.autoBufferMbForRam((2.8 * gb).toLong()))
        assertEquals(1000, BufferSizing.autoBufferMbForRam((3.8 * gb).toLong()))
        assertEquals(1600, BufferSizing.autoBufferMbForRam((5.6 * gb).toLong()))
        assertEquals(2000, BufferSizing.autoBufferMbForRam(8 * gb))
    }

    @Test
    fun `small boxes default to two connections with native memory`() {
        assertEquals(2, BufferSizing.defaultConnectionCount((1.9 * gb).toLong(), nativeMemory = true, fallback = 2))
        assertEquals(2, BufferSizing.defaultConnectionCount(0L, nativeMemory = true, fallback = 2))
        assertEquals(4, BufferSizing.defaultConnectionCount((2.8 * gb).toLong(), nativeMemory = true, fallback = 2))
        assertEquals(4, BufferSizing.defaultConnectionCount((3.8 * gb).toLong(), nativeMemory = true, fallback = 2))
        assertEquals(2, BufferSizing.defaultConnectionCount((3.8 * gb).toLong(), nativeMemory = false, fallback = 2))
        assertTrue(BufferSizing.isSmallBox((2.2 * gb).toLong()))
        assertFalse(BufferSizing.isSmallBox((2.4 * gb).toLong()))
    }

    private data class Row(val boxMb: Int, val availMb: Long, val thresholdMb: Long, val windowMb: Int, val heldMb: Int, val expectMb: Int)

    @Test
    fun `size follows the memory that is free`() {
        val rows = listOf(
            // Fire TV Stick 4K Max, other apps stopped: room for the whole 2 GB size.
            Row(200, 740, 130, 96, 0, 200),
            // A little less free: what is spare.
            Row(200, 588, 130, 96, 0, 194),
            // Same box with apps in the background (MemAvailable 425 MB measured idle): the floor.
            Row(200, 425, 130, 96, 0, 50),
            // Somewhere between: what is spare.
            Row(200, 488, 130, 96, 0, 94),
            // Previous player still holds 80 MB that comes back.
            Row(200, 488, 130, 96, 80, 174),
            // A Fire Stick with 500 MB free and no chunk window: 188 MB with the 168 MB start-up reserve.
            Row(200, 500, 144, 0, 0, 188),
            // AM9 Pro with plenty free: the 4 GB size.
            Row(1000, 2400, 220, 192, 0, 1000),
            // AM9 Pro with 1.3 GB free.
            Row(1000, 1288, 220, 192, 0, 708),
            // Custom buffers keep the chunks inside the budget, so no window outside it.
            Row(200, 408, 130, 0, 0, 110),
        )
        for (r in rows) {
            val sized = BufferSizing.sizedBudgetMb(r.boxMb, free(r.availMb, r.thresholdMb), r.windowMb, r.heldMb)
            assertEquals(r.toString(), r.expectMb, sized)
        }
    }

    @Test
    fun `low memory starts at the floor`() {
        assertEquals(50, BufferSizing.sizedBudgetMb(1000, free(2400, 220, low = true), 0, 0))
    }

    @Test
    fun `a 4K film on a box whose decoder lives in app memory keeps more for the decoder`() {
        // Fire TV Stick 4K Max before a 4K DV remux: sized 97 MB, then the decoder took 200 MB and the box ran short.
        assertEquals(97, BufferSizing.sizedBudgetMb(200, free(409, 144), 0, 0))
        assertEquals(50, BufferSizing.sizedBudgetMb(200, free(409, 144), 0, 0, fourKInAppMemory = true))
        assertEquals(108, BufferSizing.sizedBudgetMb(200, free(500, 144), 0, 0, fourKInAppMemory = true))
        assertEquals(200, BufferSizing.sizedBudgetMb(200, free(700, 144), 0, 0, fourKInAppMemory = true))
        assertEquals(220, BufferSizing.videoOutputReserveMb(true))
        assertEquals(140, BufferSizing.videoOutputReserveMb(false))
    }

    @Test
    fun `4K is read from the name or from a bitrate only 4K carries`() {
        assertTrue(BufferSizing.looksFourK(0L, "Example.Movie.1975.2160p.UHD.BluRay.REMUX.DV.HDR.TrueHD.Atmos.7.1.mkv"))
        assertTrue(BufferSizing.looksFourK(0L, null, "TorBox 4K"))
        assertTrue(BufferSizing.looksFourK(81_700_000L, "movie.mkv"))
        assertFalse(BufferSizing.looksFourK(35_000_000L, "Example.Show.S01E01.1080p.BluRay.REMUX.AVC.DTS-HD.MA.2.0.mkv"))
        assertFalse(BufferSizing.looksFourK(0L, "The.4Kids.Show.S01E01.720p.mkv"))
        assertFalse(BufferSizing.looksFourK(0L, null, null))
    }

    @Test
    fun `only boxes under 1_15 GB count as 1 GB boxes, the low-RAM flag only when the total is unknown`() {
        assertTrue(BufferSizing.isOneGbBox(904 * mb, lowRamDevice = true))
        assertTrue(BufferSizing.isOneGbBox(904 * mb, lowRamDevice = false))
        assertTrue(BufferSizing.isOneGbBox(0L, lowRamDevice = true))
        assertFalse(BufferSizing.isOneGbBox(0L, lowRamDevice = false))
        assertFalse(BufferSizing.isOneGbBox((1.2 * gb).toLong(), lowRamDevice = true))
        assertFalse(BufferSizing.isOneGbBox((1.4 * gb).toLong(), lowRamDevice = true))
        assertFalse(BufferSizing.isOneGbBox((1.9 * gb).toLong(), lowRamDevice = true))
        assertFalse(BufferSizing.isOneGbBox((3.8 * gb).toLong(), lowRamDevice = false))
    }

    @Test
    fun `a 1 GB box keeps 8 s of the film as its floor, other boxes keep 50 MB`() {
        assertEquals(24, BufferSizing.sizedFloorMb(oneGbBox = true, bitrateBps = 0L))
        assertEquals(16, BufferSizing.sizedFloorMb(oneGbBox = true, bitrateBps = 8_000_000L))
        assertEquals(29, BufferSizing.sizedFloorMb(oneGbBox = true, bitrateBps = 30_000_000L))
        assertEquals(50, BufferSizing.sizedFloorMb(oneGbBox = true, bitrateBps = 80_000_000L))
        for (bps in listOf(0L, 8_000_000L, 30_000_000L, 80_000_000L)) {
            assertEquals(50, BufferSizing.sizedFloorMb(oneGbBox = false, bitrateBps = bps))
        }
    }

    @Test
    fun `a 904 MB low-RAM TV sizes below the old 50 MB floor`() {
        val oneGb = BufferSizing.isOneGbBox(904 * mb, lowRamDevice = true)
        val boxMb = BufferSizing.autoBufferMbForRam(904 * mb)
        assertEquals(100, boxMb)
        // 248 MB free against a 64 MB line: 16 MB spare once the decoder's share is kept.
        val tv = free(248, 64)
        assertEquals(50, BufferSizing.sizedBudgetMb(boxMb, tv, 0, 0))
        val hevc1080p = BufferSizing.sizedFloorMb(oneGb, 8_000_000L)
        assertEquals(16, BufferSizing.sizedBudgetMb(boxMb, tv, 0, 0, floorMb = hevc1080p))
        val unknown = BufferSizing.sizedFloorMb(oneGb, 0L)
        assertEquals(24, BufferSizing.sizedBudgetMb(boxMb, tv, 0, 0, floorMb = unknown))
        assertEquals(16, BufferSizing.sizedBudgetMb(boxMb, free(400, 64, low = true), 0, 0, floorMb = hevc1080p))
        // With room the box size is unchanged.
        assertEquals(100, BufferSizing.sizedBudgetMb(boxMb, free(500, 64), 0, 0, floorMb = hevc1080p))
    }

    @Test
    fun `2 GB and 4 GB boxes size exactly as before`() {
        data class Box(val totalMem: Long, val lowRam: Boolean)
        val boxes = listOf(
            Box((1.4 * gb).toLong(), true),
            Box((1.9 * gb).toLong(), true),
            Box((1.9 * gb).toLong(), false),
            Box((3.8 * gb).toLong(), false),
        )
        val cases = listOf(
            Triple(free(425, 130), 96, 0),
            Triple(free(488, 130), 96, 80),
            Triple(free(409, 144), 0, 0),
            Triple(free(2400, 220, low = true), 0, 0),
            Triple(free(1288, 220), 192, 0),
            Triple(free(248, 64), 0, 0),
        )
        for (box in boxes) {
            val boxMb = BufferSizing.autoBufferMbForRam(box.totalMem)
            for (bps in listOf(0L, 8_000_000L, 30_000_000L, 80_000_000L)) {
                val floor = BufferSizing.sizedFloorMb(BufferSizing.isOneGbBox(box.totalMem, box.lowRam), bps)
                assertEquals(BufferSizing.MIN_SIZED_BUFFER_MB, floor)
                for ((f, window, held) in cases) {
                    for (fourK in listOf(false, true)) {
                        assertEquals(
                            "$box $bps $f",
                            BufferSizing.sizedBudgetMb(boxMb, f, window, held, fourK),
                            BufferSizing.sizedBudgetMb(boxMb, f, window, held, fourK, floor)
                        )
                    }
                }
            }
        }
        assertEquals(50, BufferSizing.sizedBudgetMb(200, free(425, 130), 96, 0, floorMb = 50))
        assertEquals(708, BufferSizing.sizedBudgetMb(1000, free(1288, 220), 192, 0, floorMb = 50))
        assertEquals(50, BufferSizing.sizedBudgetMb(1000, free(2400, 220, low = true), 0, 0, floorMb = 50))
    }

    @Test
    fun `unknown free memory keeps the box size`() {
        assertEquals(150, BufferSizing.sizedBudgetMb(150, null, 96, 0))
    }

    @Test
    fun `the floor never raises a smaller size`() {
        assertEquals(40, BufferSizing.sizedBudgetMb(40, free(300, 130), 0, 0))
    }

    @Test
    fun `the back buffer always leaves 5 s ahead at 80 Mbit s and is dropped below 10 s`() {
        assertEquals(0, BufferSizing.backBufferMsForTarget(12_000, 50 * mb))
        assertEquals(0, BufferSizing.backBufferMsForTarget(12_000, 62 * mb))
        assertEquals(0, BufferSizing.backBufferMsForTarget(12_000, 150 * mb))
        assertEquals(12_000, BufferSizing.backBufferMsForTarget(12_000, 1000 * mb))
        assertEquals(1_500, BufferSizing.backBufferMsForTarget(1_500, 150 * mb))
        assertEquals(10_000, BufferSizing.backBufferMsForTarget(12_000, 300 * mb))
        assertEquals(0, BufferSizing.backBufferMsForTarget(0, 1000 * mb))
        val bytesPerSecond = 10 * mb
        for (targetMb in 50L..2000L) {
            val backMs = BufferSizing.backBufferMsForTarget(12_000, targetMb * mb)
            val backBytes = backMs * bytesPerSecond / 1000
            val aheadBytes = BufferSizing.MIN_AHEAD_MS * bytesPerSecond / 1000
            assertTrue("target $targetMb MB", backBytes + aheadBytes <= targetMb * mb)
            assertTrue("target $targetMb MB", backBytes <= targetMb * mb / 3)
        }
    }

    @Test
    fun `the back buffer follows the film's real bitrate`() {
        val rate20 = BufferSizing.backBufferBytesPerSecond(20_000_000L)
        assertEquals(2_500_000L, rate20)
        // 2 GB box at 100 MB: under 10 s of an 80 Mbit/s remux fits, so none is kept; the 12 s set fits a 20 Mbit/s film.
        assertEquals(0, BufferSizing.backBufferMsForTarget(12_000, 100 * mb, BufferSizing.backBufferBytesPerSecond(80_000_000L)))
        assertEquals(12_000, BufferSizing.backBufferMsForTarget(12_000, 100 * mb, rate20))
        assertEquals(20_971, BufferSizing.backBufferMsForTarget(30_000, 150 * mb, rate20))
        // 50 MB, the smallest size: under 10 s at either rate, so the buffer ahead gets it all.
        assertEquals(0, BufferSizing.backBufferMsForTarget(30_000, 50 * mb, BufferSizing.backBufferBytesPerSecond(80_000_000L)))
        assertEquals(0, BufferSizing.backBufferMsForTarget(30_000, 50 * mb, rate20))
        for (bps in listOf(5_000_000L, 20_000_000L, 60_000_000L, 90_000_000L, 120_000_000L)) {
            val rate = BufferSizing.backBufferBytesPerSecond(bps)
            for (targetMb in 50L..1000L step 10L) {
                val backMs = BufferSizing.backBufferMsForTarget(120_000, targetMb * mb, rate)
                val backBytes = backMs * rate / 1000
                if (backMs > 0) assertTrue("$bps at $targetMb MB", backBytes + BufferSizing.MIN_AHEAD_MS * rate / 1000 <= targetMb * mb)
                assertTrue("$bps at $targetMb MB", backBytes <= targetMb * mb / 3)
            }
        }
    }

    @Test
    fun `with room the back buffer keeps its setting, half the minimum only decides what stays ahead`() {
        val rate20 = BufferSizing.backBufferBytesPerSecond(20_000_000L)
        assertEquals(30_000, BufferSizing.backBufferMsForTarget(30_000, 1000 * mb, rate20, 15_000, 45_000))
        assertEquals(5_000, BufferSizing.backBufferMsForTarget(5_000, 1000 * mb, rate20, 15_000, 45_000))
        assertEquals(30_000, BufferSizing.backBufferMsForTarget(30_000, 1000 * mb, rate20, 0, 45_000))
    }

    @Test
    fun `2 GB box, 60 Mbit s film, back buffer 30 s, Min 15 s, Max 45 s keeps the official seconds ahead`() {
        val rate = BufferSizing.backBufferBytesPerSecond(60_000_000L)
        assertEquals(7_500_000L, rate)
        fun aheadMs(targetBytes: Long, backMs: Int) = (targetBytes - backMs.toLong() * rate / 1000L) * 1000L / rate
        // The official app: 200 MiB, back buffer min(30 s, 15 s / 2) = 7.5 s, 20.46 s ahead.
        val official = aheadMs(200 * mb, 7_500)
        assertEquals(20_462L, official)

        // Before: 150 MiB and the back buffer fitted to a third of it, 6.99 s back and 13.98 s ahead.
        val before = BufferSizing.backBufferMsForTarget(30_000, 150 * mb, rate)
        assertEquals(0, before)
        assertEquals(20_971L, aheadMs(150 * mb, before))

        // Now: 200 MiB; the 7.5 s that would fit behind is under one 10 s skip, so all of it stays ahead.
        val now = BufferSizing.backBufferMsForTarget(30_000, 200 * mb, rate, 15_000, 45_000, 200 * mb)
        assertEquals(0, now)
        assertTrue(aheadMs(200 * mb, now) >= official)

        // Lowered by the free memory: the back buffer only gets what is left over the official amount ahead.
        val slightly = BufferSizing.backBufferMsForTarget(30_000, 190 * mb, rate, 15_000, 45_000, 200 * mb)
        assertEquals(0, slightly)
        assertTrue(aheadMs(190 * mb, slightly) >= official)
        val lowered = BufferSizing.backBufferMsForTarget(30_000, 150 * mb, rate, 15_000, 45_000, 200 * mb)
        assertEquals(0, lowered)
        assertTrue(aheadMs(150 * mb, lowered) >= official)
        // Below the official amount the buffer ahead takes all of it.
        val tight = BufferSizing.backBufferMsForTarget(30_000, 140 * mb, rate, 15_000, 45_000, 200 * mb)
        assertEquals(0, tight)
        assertEquals(19_573L, aheadMs(140 * mb, tight))
    }

    @Test
    fun `the buffer ahead never gets less than the official amount, Max or the target allow`() {
        for (bps in listOf(5_000_000L, 25_000_000L, 60_000_000L, 120_000_000L)) {
            val rate = BufferSizing.backBufferBytesPerSecond(bps)
            for (setMs in listOf(1_500, 12_000, 30_000, 120_000)) {
                for (targetMb in 50L..400L step 10L) {
                    val target = targetMb * mb
                    val reference = 200 * mb
                    val backMs = BufferSizing.backBufferMsForTarget(setMs, target, rate, 15_000, 45_000, reference)
                    val label = "$bps bps, set $setMs, $targetMb MB"
                    assertTrue(label, backMs <= setMs)
                    val ahead = target - backMs.toLong() * rate / 1000L
                    val officialAhead = reference - minOf(setMs, 7_500).toLong() * rate / 1000L
                    val wanted = minOf(officialAhead, 45_000L * rate / 1000L, target)
                    assertTrue(label, ahead >= wanted)
                    if (backMs > 0) assertTrue(label, ahead >= BufferSizing.MIN_AHEAD_MS * rate / 1000L)
                }
            }
        }
    }

    @Test
    fun `without Min and Max the fit is unchanged`() {
        val rate20 = BufferSizing.backBufferBytesPerSecond(20_000_000L)
        for (targetMb in listOf(50L, 62L, 100L, 150L, 1000L)) {
            assertEquals(
                BufferSizing.backBufferMsForTarget(30_000, targetMb * mb, rate20),
                BufferSizing.backBufferMsForTarget(30_000, targetMb * mb, rate20, 0, 0, 2000 * mb)
            )
        }
    }

    @Test
    fun `an unknown or implausible bitrate falls back safely`() {
        assertEquals(10 * mb, BufferSizing.backBufferBytesPerSecond(0L))
        assertEquals(10 * mb, BufferSizing.backBufferBytesPerSecond(-1L))
        assertEquals(1 * mb, BufferSizing.backBufferBytesPerSecond(1_000_000L))
        assertEquals(0, BufferSizing.backBufferMsForTarget(12_000, 150 * mb, 0L))
        assertEquals(
            BufferSizing.backBufferMsForTarget(12_000, 150 * mb),
            BufferSizing.backBufferMsForTarget(12_000, 150 * mb, BufferSizing.backBufferBytesPerSecond(0L))
        )
    }
}
