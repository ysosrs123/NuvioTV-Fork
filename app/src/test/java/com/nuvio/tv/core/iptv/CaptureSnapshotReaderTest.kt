package com.nuvio.tv.core.iptv

import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CaptureSnapshotReaderTest {
    @get:Rule val temp = TemporaryFolder()
    private fun CaptureSegmentStore.add(start: Long, continuity: Long = 0) =
        append(start, start + 1000, continuity, ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)))

    @Test fun fixedSnapshotReadsAcrossSegmentsAndDoesNotTurnALiveTailIntoEof() {
        CaptureSegmentStore(temp.newFolder(), 16, 4).use { store ->
            store.add(0); store.add(1000)
            store.openSnapshotFrom(0).use { reader ->
                store.add(2000)
                assertEquals(CaptureBounds(0, 2000), reader.bounds)
                assertEquals(8L, reader.length)
                assertEquals(1, reader.read())
                assertArrayEquals(byteArrayOf(2,3,4,1,2,3,4), reader.readBytes())
                assertEquals(-1, reader.read())
                assertEquals(0, reader.read(ByteArray(0), 0, 0))
            }
        }
    }

    @Test fun gapAndDiscontinuityStopReaderAtActualBoundary() {
        CaptureSegmentStore(temp.newFolder(), 20, 4).use { store ->
            store.add(0); store.add(2000); store.add(3000, 1); store.add(4000, 1)
            store.openSnapshotFrom(0).use { assertEquals(4, it.readBytes().size) }
            store.openSnapshotFrom(1).use { assertEquals(CaptureBounds(2000, 3000), it.bounds) }
            store.openSnapshotFrom(2).use { assertEquals(CaptureBounds(3000, 5000), it.bounds) }
        }
    }

    @Test fun snapshotPinsBeforeFirstReadAndRemainsPinnedAtEofUntilClose() {
        val store = CaptureSegmentStore(temp.newFolder(), 8, 4)
        store.add(0); store.add(1000)
        val reader = store.openSnapshotFrom(0)
        try { store.add(2000); fail() } catch (_: CaptureRetentionBlocked) { }
        assertEquals(8, reader.readBytes().size)
        try { store.close(); fail() } catch (_: IllegalStateException) { }
        try { store.add(2000); fail() } catch (_: CaptureRetentionBlocked) { }
        reader.close(); reader.close()
        store.add(2000)
        try { store.openSnapshotFrom(0); fail() } catch (_: IllegalArgumentException) { }
        try { reader.read(); fail() } catch (_: IOException) { }
        store.close()
    }

    @Test fun readerChecksArgumentsAndDoesNotLeakPinAfterNormalClose() {
        CaptureSegmentStore(temp.newFolder(), 8, 4).use { store ->
            store.add(0)
            store.openSnapshotFrom(0).use { reader ->
                try { reader.read(ByteArray(1), Int.MAX_VALUE, 1); fail() } catch (_: IndexOutOfBoundsException) { }
                assertEquals(2L, reader.skip(2))
                assertEquals(3, reader.read())
            }
            store.add(1000); store.add(2000)
        }
    }
}
