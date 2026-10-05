package com.nuvio.tv.data.iptv

import com.nuvio.tv.core.iptv.*
import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AndroidCaptureSpaceProbeTest {
    @get:Rule val temp=TemporaryFolder()
    @Test fun actualAndroidStatvfsReportsStableUnitsAndAvailableBlocks() {
        val dir=temp.newFolder(); val a=AndroidCaptureSpaceProbe.read(dir); val b=AndroidCaptureSpaceProbe.read(dir)
        assertTrue(a.usableBytes>0); assertTrue(a.allocationUnitBytes>0)
        assertEquals(a.volumeId,b.volumeId); assertEquals(a.allocationUnitBytes,b.allocationUnitBytes)
    }
    @Test fun guardedPrivateFilesystemSpoolCommitsAndReopensWithTheRealAndroidProbe() {
        val dir=temp.newFolder(); val policy=CaptureStoragePolicy(1024*1024,AndroidCaptureSpaceProbe)
        CaptureSegmentStore(dir,8192,4096,policy).use { s ->
            s.checkStorageReservation(CaptureStorageReservation(8192,4096,requireNotNull(s.minimumStorageOverheadBytes)))
            s.append(0,1,0,ByteArrayInputStream(ByteArray(4096) { 42 }))
        }
        CaptureSegmentStore(dir,8192,4096,policy).use { s -> s.open(0).use { assertEquals(4096,it.readBytes().size) } }
    }
}
