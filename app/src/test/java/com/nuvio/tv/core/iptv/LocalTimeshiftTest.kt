package com.nuvio.tv.core.iptv

import org.junit.Assert.*
import org.junit.Test

class LocalTimeshiftTest {
    private val gb = 1_000_000_000L

    @Test fun bitrateNeedsHalfTheMeasuringTimeAndStaysInRange() {
        assertEquals(8_000_000L, LocalTimeshiftSizing.bitrate(10_000_000, 10_000))
        assertNull(LocalTimeshiftSizing.bitrate(10_000_000, 4_000))
        assertNull(LocalTimeshiftSizing.bitrate(0, 10_000))
        assertEquals(LocalTimeshiftSizing.MIN_BITS, LocalTimeshiftSizing.bitrate(1_000, 10_000))
        assertEquals(LocalTimeshiftSizing.MAX_BITS, LocalTimeshiftSizing.bitrate(500_000_000, 10_000))
    }

    @Test fun bytesForAMinuteCountIncludeHeadroomAndAreWholePackets() {
        val bytes = LocalTimeshiftSizing.bytesFor(8_000_000, 30)
        assertEquals(0L, bytes % LocalTimeshiftSizing.PACKET)
        assertTrue(bytes >= 1_000_000L * 1800 * 115 / 100 && bytes < 1_000_000L * 1800 * 115 / 100 + LocalTimeshiftSizing.PACKET)
    }

    @Test fun chosenLengthIsUsedWhenThereIsRoom() {
        for (length in listOf(LocalTimeshiftLength.MINUTES_15, LocalTimeshiftLength.MINUTES_30)) {
            assertEquals(LocalTimeshiftSizing.bytesFor(8_000_000, length.minutes!!), LocalTimeshiftSizing.capacity(length, 8_000_000, 100 * gb))
        }
        assertEquals(LocalTimeshiftSizing.bytesFor(8_000_000, 30), LocalTimeshiftSizing.capacity(LocalTimeshiftLength.AUTOMATIC, 8_000_000, 100 * gb))
    }

    @Test fun absoluteCapKeepsTheFileBelowTheFat32Limit() {
        val capacity = LocalTimeshiftSizing.capacity(LocalTimeshiftLength.MINUTES_60, 10_000_000, 100 * gb)!!
        assertTrue(capacity <= LocalTimeshiftSizing.ABSOLUTE_CAP && capacity < 4L * 1024 * 1024 * 1024)
        assertEquals(0L, capacity % LocalTimeshiftSizing.PACKET)
        assertEquals(LocalTimeshiftSizing.alignDown(LocalTimeshiftSizing.ABSOLUTE_CAP), capacity)
    }

    @Test fun lowSpaceShortensTheBufferAndKeepsTheSafetyMargin() {
        val usable = 1_500_000_000L
        val capacity = LocalTimeshiftSizing.capacity(LocalTimeshiftLength.AUTOMATIC, 8_000_000, usable)!!
        assertEquals(LocalTimeshiftSizing.alignDown(usable - LocalTimeshiftSizing.SAFETY_BYTES), capacity)
        assertTrue(capacity < LocalTimeshiftSizing.bytesFor(8_000_000, 30))
        assertTrue(LocalTimeshiftSizing.minutes(capacity, 8_000_000) in 5 until 30)
    }

    @Test fun tooLittleSpaceRefusesTheBuffer() {
        assertNull(LocalTimeshiftSizing.capacity(LocalTimeshiftLength.MINUTES_15, 8_000_000, 700_000_000))
        assertNull(LocalTimeshiftSizing.capacity(LocalTimeshiftLength.AUTOMATIC, 8_000_000, 0))
        assertNull(LocalTimeshiftSizing.provisional(600_000_000))
        val provisional = LocalTimeshiftSizing.provisional(10 * gb)!!
        assertEquals(0L, provisional % LocalTimeshiftSizing.PACKET)
        assertTrue(provisional >= LocalTimeshiftSizing.bytesFor(LocalTimeshiftSizing.MAX_BITS, 1))
    }

    @Test fun onlyFullScreenLiveTransportStreamsAreBuffered() {
        assertNull(LocalTimeshiftPolicy.block(true, true, true, true, false, false, true))
        assertEquals(LocalTimeshiftBlock.DISABLED, LocalTimeshiftPolicy.block(false, true, true, true, false, false, true))
        assertEquals(LocalTimeshiftBlock.MULTIVIEW, LocalTimeshiftPolicy.block(true, true, true, true, true, false, true))
        assertEquals(LocalTimeshiftBlock.NOT_FULLSCREEN, LocalTimeshiftPolicy.block(true, false, true, true, false, false, true))
        assertEquals(LocalTimeshiftBlock.NOT_LIVE, LocalTimeshiftPolicy.block(true, true, false, true, false, false, true))
        assertEquals(LocalTimeshiftBlock.NOT_TS, LocalTimeshiftPolicy.block(true, true, true, false, false, false, true))
        assertEquals(LocalTimeshiftBlock.RECORDING, LocalTimeshiftPolicy.block(true, true, true, true, false, true, true))
        assertEquals(LocalTimeshiftBlock.NO_STORAGE, LocalTimeshiftPolicy.block(true, true, true, true, false, false, false))
    }

    @Test fun failuresFallBackToDirectPlaybackAndBehindJumpsAreBounded() {
        LocalTimeshiftFailure.entries.forEach { assertEquals(LocalTimeshiftAction.Direct, LocalTimeshiftPolicy.onWriterFailure(it)) }
        assertEquals(LocalTimeshiftAction.Oldest, LocalTimeshiftPolicy.onReaderBehind(0))
        assertEquals(LocalTimeshiftAction.Oldest, LocalTimeshiftPolicy.onReaderBehind(LocalTimeshiftPolicy.MAX_BEHIND_JUMPS - 1))
        assertEquals(LocalTimeshiftAction.Direct, LocalTimeshiftPolicy.onReaderBehind(LocalTimeshiftPolicy.MAX_BEHIND_JUMPS))
        assertEquals(LocalTimeshiftAction.Live, LocalTimeshiftPolicy.onReaderStalled(writerRunning = true))
        assertEquals(LocalTimeshiftAction.Direct, LocalTimeshiftPolicy.onReaderStalled(writerRunning = false))
    }

    @Test fun stepsStayInTheBufferAndUseTheArchiveOnlyBeyondIt() {
        val now = 1_800_000_000_000L
        val oldest = now - 20 * 60_000
        assertEquals(LocalTimeshiftStep.Live, LocalTimeshiftPolicy.step(now - 5_000, now, oldest, archive = true))
        assertEquals(LocalTimeshiftStep.Live, LocalTimeshiftPolicy.step(now + 60_000, now, oldest, archive = false))
        assertEquals(LocalTimeshiftStep.Local(now - 60_000), LocalTimeshiftPolicy.step(now - 60_000, now, oldest, archive = true))
        assertEquals(LocalTimeshiftStep.Local(oldest), LocalTimeshiftPolicy.step(oldest, now, oldest, archive = false))
        assertEquals(LocalTimeshiftStep.Archive, LocalTimeshiftPolicy.step(oldest - 1, now, oldest, archive = true))
        assertEquals(LocalTimeshiftStep.Local(oldest), LocalTimeshiftPolicy.step(oldest - 60_000, now, oldest, archive = false))
        assertEquals(LocalTimeshiftStep.Live, LocalTimeshiftPolicy.step(now - 60_000, now, null, archive = false))
        assertEquals(LocalTimeshiftStep.Archive, LocalTimeshiftPolicy.step(now - 60_000, now, null, archive = true))
        assertEquals(LocalTimeshiftStep.Live, LocalTimeshiftPolicy.step(now - 60_000, now, now - 2_000, archive = false))
    }

    @Test fun behindLiveNeedsMoreThanTheLiveEdge() {
        val now = 1_800_000_000_000L
        assertFalse(LocalTimeshiftPolicy.behind(null, now))
        assertFalse(LocalTimeshiftPolicy.behind(now - LocalTimeshiftPolicy.LIVE_EDGE_MILLIS, now))
        assertTrue(LocalTimeshiftPolicy.behind(now - LocalTimeshiftPolicy.LIVE_EDGE_MILLIS - 1, now))
    }
}
