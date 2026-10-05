package com.nuvio.tv.core.iptv

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CaptureSegmentStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private fun bytes(n: Int = 4) = ByteArrayInputStream(ByteArray(n) { 42 })
    @Test fun completeSegmentsSurviveReopenAndSequenceNeverRestartsAfterEviction() {
        val dir=temp.newFolder()
        CaptureSegmentStore(dir,8,4).use { s -> repeat(3) { s.append(it*1000L,(it+1)*1000L,0,bytes()) } }
        CaptureSegmentStore(dir,8,4).use { s ->
            assertEquals(listOf(1L,2L),s.snapshot().map { it.sequence })
            assertEquals(CaptureBounds(1000,3000),s.contiguousBounds())
            assertEquals(3L,s.append(3000,4000,0,bytes()).sequence)
            s.open(3).use { assertArrayEquals(ByteArray(4) { 42 },it.readBytes()) }
        }
    }
    @Test fun pauseAnchorProtectsSuccessorsAndBackpressureDoesNotSilentlyLosePausePosition() {
        CaptureSegmentStore(temp.newFolder(),8,4).use { s ->
            s.append(0,1000,0,bytes()); val anchor=s.pinFrom(0)
            s.append(1000,2000,0,bytes())
            try { s.append(2000,3000,0,bytes()); fail() } catch (_: CaptureRetentionBlocked) { }
            assertEquals(listOf(0L,1L),s.snapshot().map { it.sequence })
            anchor.close(); s.append(2000,3000,0,bytes())
            assertEquals(listOf(1L,2L),s.snapshot().map { it.sequence })
        }
    }
    @Test fun liveReaderRetainsItsBytesEvenAfterSeparateAnchorIsReleased() {
        val s=CaptureSegmentStore(temp.newFolder(),4,4)
        s.append(0,1000,0,bytes()); val anchor=s.pinFrom(0); val reader=s.open(0)
        anchor.close()
        try { s.append(1000,2000,0,bytes()); fail() } catch (_: CaptureRetentionBlocked) { }
        try { s.close(); fail() } catch (_: IllegalStateException) { }
        assertEquals(42,reader.read()); reader.close(); reader.close()
        s.append(1000,2000,0,bytes()); s.close()
    }
    @Test fun oversizedInterruptedAndCancelledInputsCannotReplaceLastGoodSegments() {
        CaptureSegmentStore(temp.newFolder(),4,4).use { s ->
            s.append(0,1000,0,bytes())
            try { s.append(1000,2000,0,bytes(5)); fail() } catch (_: IOException) { }
            try { s.append(1000,2000,0,object: ByteArrayInputStream(byteArrayOf(1)) {
                override fun read(b: ByteArray,o: Int,n: Int): Int = throw IOException("fixture")
            }); fail() } catch (_: IOException) { }
            var checks=0
            try { s.append(1000,2000,0,bytes()) { if (++checks == 4) throw IllegalStateException("cancel") }; fail() }
            catch (_: IllegalStateException) { }
            assertEquals(listOf(0L),s.snapshot().map { it.sequence })
            s.open(0).use { assertEquals(4,it.readBytes().size) }
        }
    }
    @Test fun gapsAndDiscontinuitiesDoNotPretendToBeOneSeekWindow() {
        CaptureSegmentStore(temp.newFolder(),20,4).use { s ->
            s.append(0,1000,0,bytes()); s.append(1000,2000,0,bytes()); s.append(3000,4000,0,bytes())
            assertEquals(CaptureBounds(3000,4000),s.contiguousBounds())
            s.append(4000,5000,1,bytes()); assertEquals(CaptureBounds(4000,5000),s.contiguousBounds())
        }
    }
    @Test fun orphanCleanupIsScopedAndAnUnmarkedNonemptyDirectoryIsNeverClaimed() {
        val other=temp.newFolder(); val personal=File(other,"keep.txt").apply { writeText("keep") }
        try { CaptureSegmentStore(other,8,4); fail() } catch (_: IllegalArgumentException) { }
        assertEquals("keep",personal.readText())
        val dir=temp.newFolder(); CaptureSegmentStore(dir,8,4).close()
        val orphan=File(dir,"pending-00000000-0000-0000-0000-000000000000").apply { writeBytes(byteArrayOf(1)) }
        CaptureSegmentStore(dir,8,4).use { assertFalse(orphan.exists()); assertTrue(it.snapshot().isEmpty()) }
    }
    @Test fun exclusiveOwnershipAndTruncatedCommittedMediaFailClosed() {
        val dir=temp.newFolder(); val first=CaptureSegmentStore(dir,8,4)
        try { CaptureSegmentStore(dir,8,4); fail() } catch (_: Exception) { }
        first.append(0,1000,0,bytes()); val file=File(dir,first.snapshot().single().fileName); first.close()
        file.writeBytes(byteArrayOf(1))
        try { CaptureSegmentStore(dir,8,4); fail() } catch (_: IllegalArgumentException) { }
        assertTrue(file.exists())
    }
}
