package com.nuvio.tv.core.iptv

import org.junit.Assert.*
import org.junit.Test

class LocalTimeshiftRingTest {
    private val capacity = 188L * 100_000

    @Test fun marginIsBoundedAndWholePackets() {
        val small = LocalTimeshiftRing.margin(188L * 10_000)
        assertTrue(small >= LocalTimeshiftRing.WRITE_CHUNK * 4L)
        assertTrue(LocalTimeshiftRing.margin(4_000_000_000) <= 16L * 1024 * 1024 + 188)
        assertEquals(0L, LocalTimeshiftRing.margin(capacity) % 188)
    }

    @Test fun oldestStartsAtZeroAndFollowsTheHeadAfterWrapping() {
        assertEquals(0L, LocalTimeshiftRing.oldest(0, capacity))
        assertEquals(0L, LocalTimeshiftRing.oldest(capacity - LocalTimeshiftRing.margin(capacity), capacity))
        val head = capacity * 3 + 188 * 50
        val oldest = LocalTimeshiftRing.oldest(head, capacity)
        assertEquals(head - capacity + LocalTimeshiftRing.margin(capacity), oldest)
        assertEquals(0L, oldest % 188)
        assertEquals(LocalTimeshiftWindow(oldest, head), LocalTimeshiftRing.window(head, capacity))
    }

    @Test fun logicalOffsetsMapModuloCapacityAndSplitAtTheWrap() {
        assertEquals(0L, LocalTimeshiftRing.filePosition(0, capacity))
        assertEquals(188L, LocalTimeshiftRing.filePosition(capacity + 188, capacity))
        assertEquals(capacity - 376, LocalTimeshiftRing.filePosition(capacity * 2 - 376, capacity))
        assertEquals(376, LocalTimeshiftRing.contiguous(capacity * 2 - 376, 1000, capacity))
        assertEquals(1000, LocalTimeshiftRing.contiguous(capacity * 2, 1000, capacity))
    }

    @Test fun readsBehindTheOverwritePointAreRefused() {
        val head = capacity * 2
        val oldest = LocalTimeshiftRing.oldest(head, capacity)
        assertEquals(-1L, LocalTimeshiftRing.readable(oldest - 188, head, capacity))
        assertEquals(head - oldest, LocalTimeshiftRing.readable(oldest, head, capacity))
        assertTrue(LocalTimeshiftRing.intact(oldest, head, capacity))
        assertFalse(LocalTimeshiftRing.intact(oldest, head + 188 * 10, capacity))
    }

    @Test fun clampKeepsOffsetsInsideTheReadableWindowOnPackets() {
        val head = capacity * 2 + 188 * 7
        val oldest = LocalTimeshiftRing.oldest(head, capacity)
        assertEquals(oldest, LocalTimeshiftRing.clamp(0, head, capacity))
        assertEquals(head, LocalTimeshiftRing.clamp(head + 10_000, head, capacity))
        assertEquals(oldest + 188 * 3, LocalTimeshiftRing.clamp(oldest + 188 * 3 + 50, head, capacity))
    }

    @Test fun syncFindsTheFirstConfirmedPacketStart() {
        val bytes = ByteArray(188 * 3 + 10)
        bytes[2] = 0x47; bytes[5] = 0x47; bytes[5 + 188] = 0x47; bytes[5 + 376] = 0x47
        assertEquals(5, LocalTimeshiftPackets.sync(bytes, 0, bytes.size))
        assertEquals(-1, LocalTimeshiftPackets.sync(ByteArray(400), 0, 400))
        val tail = ByteArray(100).also { it[40] = 0x47 }
        assertEquals(40, LocalTimeshiftPackets.sync(tail, 0, tail.size))
        assertEquals(5 + 188, LocalTimeshiftPackets.sync(bytes, 6, bytes.size - 6))
    }

    @Test fun indexRecordsAboutOneEntryPerSecond() {
        val index = LocalTimeshiftIndex()
        assertTrue(index.add(1_000, 0))
        assertFalse(index.add(1_500, 188))
        assertTrue(index.add(2_000, 188 * 10))
        assertFalse(index.add(3_000, 188 * 5))
        assertFalse(index.add(1_000, 188 * 20))
        assertEquals(2, index.size)
    }

    @Test fun indexLooksUpOffsetsAndTimesInsideTheWindow() {
        val index = LocalTimeshiftIndex()
        for (second in 0 until 10) index.add(10_000L + second * 1_000, second * 1_880L)
        assertEquals(0L, index.offsetAt(5_000, 0))
        assertEquals(1_880L * 3, index.offsetAt(13_400, 0))
        assertEquals(1_880L * 9, index.offsetAt(99_000, 0))
        assertEquals(1_880L * 4, index.offsetAt(11_000, 1_880L * 4))
        assertEquals(13_000L, index.timeAt(1_880L * 3 + 100))
        assertEquals(10_000L, index.timeAt(0))
        assertEquals(14_000L, index.oldestTime(1_880L * 4))
        assertEquals(19_000L, index.newestTime())
        assertNull(LocalTimeshiftIndex().offsetAt(1, 0))
        assertNull(LocalTimeshiftIndex().timeAt(1))
    }

    @Test fun indexTrimsWrittenOverEntriesAndStaysBounded() {
        val index = LocalTimeshiftIndex(maxEntries = 4)
        for (second in 0 until 10) index.add(second * 1_000L, second * 100L)
        assertEquals(4, index.size)
        assertEquals(6_000L, index.oldestTime(0))
        index.trim(850)
        assertEquals(2, index.size)
        assertEquals(9_000L, index.oldestTime(850))
        assertEquals(800L, index.offsetAt(0, 0))
    }
}
