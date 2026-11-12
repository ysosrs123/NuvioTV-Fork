package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.player.BufferSizing
import org.junit.Assert.assertEquals
import org.junit.Test

class AutoSpeedupLimitsTest {

    private val mb = 1024L * 1024L

    @Test
    fun `two gigabyte boxes go up to six`() {
        assertEquals(AutoSpeedup.Limits(6, 16L * mb, 8L * mb, smallBox = true, pieceBudgetBytes = 64L * mb), AutoSpeedup.limitsFor(1_900L * mb))
        assertEquals(6, AutoSpeedup.limitsFor(2_559L * mb).maxDownloads)
        assertEquals(true, AutoSpeedup.limitsFor(1_900L * mb).smallBox)
        assertEquals(false, AutoSpeedup.limitsFor(2_900L * mb).smallBox)
    }

    @Test
    fun `three gigabyte boxes go up to eight`() {
        val three = AutoSpeedup.Limits(8, 16L * mb, 8L * mb, readAheadMaxBytes = 192L * mb,
            pieceBudgetBytes = 144L * mb)
        assertEquals(three, AutoSpeedup.limitsFor(2_560L * mb))
        assertEquals(three, AutoSpeedup.limitsFor(2_900L * mb))
    }

    @Test
    fun `four gigabyte boxes and up go up to ten`() {
        val four = AutoSpeedup.Limits(10, 24L * mb, 8L * mb, readAheadMaxBytes = 256L * mb,
            pieceBudgetBytes = 256L * mb)
        assertEquals(four, AutoSpeedup.limitsFor(3_584L * mb))
        assertEquals(four, AutoSpeedup.limitsFor(3_800L * mb))
        assertEquals(four, AutoSpeedup.limitsFor(8_000L * mb))
    }

    @Test
    fun `unknown memory keeps one download`() {
        assertEquals(1, AutoSpeedup.limitsFor(0L).maxDownloads)
    }

    @Test
    fun `room is free memory above the low-memory line and the margin`() {
        val free = BufferSizing.FreeMemory(availBytes = 400L * mb, thresholdBytes = 144L * mb, lowMemory = false)
        assertEquals(400L * mb - 144L * mb - AutoSpeedup.MARGIN_BYTES, AutoSpeedup.roomAboveFloor(free))
        val short = BufferSizing.FreeMemory(availBytes = 160L * mb, thresholdBytes = 144L * mb, lowMemory = false)
        assertEquals(true, AutoSpeedup.roomAboveFloor(short) < 0L)
        assertEquals(Long.MAX_VALUE, AutoSpeedup.roomAboveFloor(null))
    }

    @Test
    fun `film bitrate comes from the server's size over the length`() {
        // 60 GB over 100 minutes is 80 Mbit/s.
        assertEquals(80_000_000L, AutoSpeedup.fileBitrate(60_000_000_000L, 1L, 6_000_000L))
        assertEquals(80_000_000L, AutoSpeedup.fileBitrate(null, 60_000_000_000L, 6_000_000L))
        assertEquals(80_000_000L, AutoSpeedup.fileBitrate(0L, 60_000_000_000L, 6_000_000L))
    }

    @Test
    fun `film bitrate is unknown without a length or with figures that cannot be right`() {
        assertEquals(0L, AutoSpeedup.fileBitrate(60_000_000_000L, null, Long.MIN_VALUE + 1))
        assertEquals(0L, AutoSpeedup.fileBitrate(null, null, 6_000_000L))
        assertEquals(0L, AutoSpeedup.fileBitrate(60_000_000_000L, null, 60_000L))
    }

    @Test
    fun `lent memory is each download plus the piece being read`() {
        assertEquals(0L, AutoSpeedupPolicy.lentBytes(1, 16L * mb))
        assertEquals(48L * mb, AutoSpeedupPolicy.lentBytes(2, 16L * mb))
        assertEquals(80L * mb, AutoSpeedupPolicy.lentBytes(4, 16L * mb))
    }

    @Test
    fun `swap is low under 50 MB, only on boxes that have swap`() {
        assertEquals(true, AutoSpeedup.swapLow(swapTotalMb = 512L, swapFreeMb = 0L))
        assertEquals(true, AutoSpeedup.swapLow(swapTotalMb = 512L, swapFreeMb = 49L))
        assertEquals(false, AutoSpeedup.swapLow(swapTotalMb = 512L, swapFreeMb = 50L))
        assertEquals(false, AutoSpeedup.swapLow(swapTotalMb = 0L, swapFreeMb = 0L))
    }

    @Test
    fun `small boxes use 8 MB pieces only when the server answers quickly`() {
        val small = AutoSpeedup.limitsFor(1_900L * mb)
        assertEquals(8L * mb, AutoSpeedup.pieceFor(small, 260L))
        assertEquals(8L * mb, AutoSpeedup.pieceFor(small, 599L))
        assertEquals(16L * mb, AutoSpeedup.pieceFor(small, 600L))
        assertEquals(16L * mb, AutoSpeedup.pieceFor(small, 750L))
        assertEquals(16L * mb, AutoSpeedup.pieceFor(small, -1L))
    }

    @Test
    fun `every box uses small pieces on a quick server and bigger ones on a slow one`() {
        assertEquals(8L * mb, AutoSpeedup.pieceFor(AutoSpeedup.limitsFor(2_900L * mb), 100L))
        assertEquals(16L * mb, AutoSpeedup.pieceFor(AutoSpeedup.limitsFor(2_900L * mb), 730L))
        assertEquals(8L * mb, AutoSpeedup.pieceFor(AutoSpeedup.limitsFor(8_000L * mb), 100L))
        assertEquals(24L * mb, AutoSpeedup.pieceFor(AutoSpeedup.limitsFor(8_000L * mb), 730L))
    }

    @Test
    fun `the read-ahead may hold more on bigger boxes`() {
        assertEquals(AutoSpeedupPolicy.READ_AHEAD_MAX_BYTES, AutoSpeedup.limitsFor(1_900L * mb).readAheadMaxBytes)
        assertEquals(192L * mb, AutoSpeedup.limitsFor(2_900L * mb).readAheadMaxBytes)
        assertEquals(256L * mb, AutoSpeedup.limitsFor(8_000L * mb).readAheadMaxBytes)
    }

    @Test
    fun `the quickest of the first requests sets the piece, then it stays`() {
        try {
            AutoSpeedup.begin("https://a/quick", 1_900L * mb)
            assertEquals(16L * mb, AutoSpeedup.pieceBytes)
            AutoSpeedup.onFirstByte(900L)
            assertEquals(16L * mb, AutoSpeedup.pieceBytes)
            AutoSpeedup.onFirstByte(260L)
            assertEquals(8L * mb, AutoSpeedup.pieceBytes)
            AutoSpeedup.onFirstByte(900L)
            assertEquals(8L * mb, AutoSpeedup.fixPiece())
            assertEquals(24L * mb, AutoSpeedupPolicy.lentBytes(2, AutoSpeedup.pieceBytes))
        } finally {
            AutoSpeedup.end()
        }
    }

    @Test
    fun `a slow server or an unmeasured one keeps 16 MB once the chunk session opens`() {
        try {
            AutoSpeedup.begin("https://a/slow", 1_900L * mb)
            AutoSpeedup.onFirstByte(750L)
            AutoSpeedup.onFirstByte(800L)
            assertEquals(16L * mb, AutoSpeedup.fixPiece())
            AutoSpeedup.end()
            AutoSpeedup.begin("https://a/unmeasured", 1_900L * mb)
            assertEquals(16L * mb, AutoSpeedup.fixPiece())
            AutoSpeedup.onFirstByte(100L)
            assertEquals(16L * mb, AutoSpeedup.pieceBytes)
        } finally {
            AutoSpeedup.end()
        }
    }

    @Test
    fun `bigger boxes take the small piece from a quick server`() {
        try {
            AutoSpeedup.begin("https://a/big", 4_000L * mb)
            AutoSpeedup.onFirstByte(100L)
            AutoSpeedup.onFirstByte(120L)
            assertEquals(8L * mb, AutoSpeedup.fixPiece())
        } finally {
            AutoSpeedup.end()
        }
    }

    @Test
    fun `the bitrate is guessed from size and runtime, else from a remux name`() {
        val gb = 1_000_000_000L
        assertEquals(true, AutoSpeedup.guessBitrate(60 * gb, 120) in 66_000_000L..67_000_000L)
        assertEquals(true, AutoSpeedup.guessBitrate(26 * gb, 125) < 30_000_000L)
        assertEquals(80_000_000L, AutoSpeedup.guessBitrate(null, null,
            "Example.Movie.2007.UHD.BluRay.2160p.DTS-HD.MA.5.1.DV.HEVC.REMUX-FraMeSToR.mkv"))
        assertEquals(80_000_000L, AutoSpeedup.guessBitrate(null, 120, "Film 2160p BluRay TrueHD Atmos 7.1"))
        assertEquals(0L, AutoSpeedup.guessBitrate(null, null, "Tenzing.2160p.WEB-DL.DV.HDR10.DDP5.1"))
        assertEquals(0L, AutoSpeedup.guessBitrate(null, null, "Film 2160p WEB-DL TrueHD 7.1"))
        assertEquals(0L, AutoSpeedup.guessBitrate(null, null, "Film 1080p BluRay x264", null))
    }
}
