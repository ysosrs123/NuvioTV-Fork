package com.nuvio.tv.core.iptv

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CaptureStoragePolicyTest {
    @get:Rule val temp = TemporaryFolder()
    private class Probe : CaptureSpaceProbe {
        var reading = CaptureSpaceReading(100_000_000,4096,"fixture")
        var calls = 0
        var fail = false
        var onRead: ((File) -> Unit)? = null
        override fun read(directory: File): CaptureSpaceReading { calls++; if(fail) throw IOException(); onRead?.invoke(directory); return reading }
    }
    private fun denied(reason: CaptureStorageFailure, action: () -> Unit) {
        try { action(); fail("Expected storage rejection") } catch(e:CaptureStorageUnavailable) { assertEquals(reason,e.reason) }
    }
    @Test fun exactThresholdAndAllocationRoundingDoNotChargeAnAlreadyAllocatedBlockTwice() {
        val p=Probe(); val f=CaptureStoragePolicy(100,p).bind(temp.newFolder())
        p.reading=p.reading.copy(usableBytes=4196); f.growth(0,1)
        p.reading=p.reading.copy(usableBytes=4195); denied(CaptureStorageFailure.INSUFFICIENT_SPACE) { f.growth(0,1) }
        p.reading=p.reading.copy(usableBytes=100); f.growth(1,4096)
        denied(CaptureStorageFailure.INSUFFICIENT_SPACE) { f.growth(4096,4097) }
        p.reading=p.reading.copy(usableBytes=8292); f.growth(0,1,1)
        assertEquals(100+2*4096L+4*4096L,f.overhead(1,2))
    }
    @Test fun zeroNegativeUnknownUnitProbeFailureAndOverflowFailClosed() {
        val dir=temp.newFolder(); val p=Probe(); val f=CaptureStoragePolicy(1,p).bind(dir)
        for(r in listOf(p.reading.copy(usableBytes=-1),p.reading.copy(allocationUnitBytes=0),p.reading.copy(volumeId=""))) {
            p.reading=r; denied(CaptureStorageFailure.INVALID_READING) { f.growth(0,1) }
        }
        p.reading=CaptureSpaceReading(0,4096,"fixture"); denied(CaptureStorageFailure.INSUFFICIENT_SPACE) { f.growth(0,1) }
        p.fail=true; denied(CaptureStorageFailure.PROBE_FAILED) { f.growth(0,1) }; p.fail=false
        p.reading=CaptureSpaceReading(Long.MAX_VALUE,4096,"fixture")
        denied(CaptureStorageFailure.INVALID_READING) { f.growth(0,Long.MAX_VALUE) }
        denied(CaptureStorageFailure.INVALID_READING) { f.admission(Long.MAX_VALUE,1) }
    }
    @Test fun mountIdentityOrAllocationUnitChangesInvalidateTheBoundFence() {
        val p=Probe(); val f=CaptureStoragePolicy(1,p).bind(temp.newFolder())
        p.reading=p.reading.copy(volumeId="other"); denied(CaptureStorageFailure.VOLUME_CHANGED) { f.growth(0,0) }
        p.reading=p.reading.copy(volumeId="fixture",allocationUnitBytes=8192)
        denied(CaptureStorageFailure.VOLUME_CHANGED) { f.growth(0,0) }
    }
    @Test fun initialPeakSpaceRejectionDoesNotReadTheSourceOrEvictCommittedMedia() {
        val p=Probe(); val dir=temp.newFolder()
        CaptureSegmentStore(dir,4,4,CaptureStoragePolicy(100,p)).use { s ->
            s.append(0,1,0,ByteArrayInputStream(byteArrayOf(1,2,3,4)))
            val old=s.snapshot(); var reads=0; p.reading=p.reading.copy(usableBytes=100)
            denied(CaptureStorageFailure.INSUFFICIENT_SPACE) { s.append(1,2,0,object:ByteArrayInputStream(byteArrayOf(5)) {
                override fun read(b:ByteArray,o:Int,n:Int):Int { reads++; return super.read(b,o,n) }
            }) }
            assertEquals(0,reads); assertEquals(old,s.snapshot()); assertEquals(1,dir.listFiles()!!.count { it.name.startsWith("segment-") })
        }
    }
    @Test fun externalConsumptionDuringStreamingDiscardsPendingBytesAndPreservesSequenceAndPins() {
        val p=Probe(); val dir=temp.newFolder()
        CaptureSegmentStore(dir,65536,65536,CaptureStoragePolicy(100,p)).use { s ->
            s.append(0,1,0,ByteArrayInputStream(byteArrayOf(1))); val old=s.snapshot(); val pin=s.pinFrom(0)
            var reads=0
            denied(CaptureStorageFailure.INSUFFICIENT_SPACE) { s.append(1,2,0,object:ByteArrayInputStream(ByteArray(65536)) {
                override fun read(b:ByteArray,o:Int,n:Int):Int { val r=super.read(b,o,minOf(n,4096)); if(++reads==2) p.reading=p.reading.copy(usableBytes=100); return r }
            }) }
            assertEquals(old,s.snapshot()); assertFalse(dir.listFiles()!!.any { it.name.startsWith("pending-") })
            p.reading=p.reading.copy(usableBytes=100_000_000); pin.close()
            assertEquals(1L,s.append(1,2,0,ByteArrayInputStream(byteArrayOf(2))).sequence)
        }
    }
    @Test fun failedIndexMarginAfterStagingCannotPromoteOrDeleteLastGoodFiles() {
        val p=Probe(); val dir=temp.newFolder()
        CaptureSegmentStore(dir,4,4,CaptureStoragePolicy(100,p)).use { s ->
            s.append(0,1,0,ByteArrayInputStream(byteArrayOf(1,2,3,4))); val old=s.snapshot()
            var checks=0
            denied(CaptureStorageFailure.INSUFFICIENT_SPACE) { s.append(1,2,0,ByteArrayInputStream(byteArrayOf(5))) {
                if(++checks==4) p.reading=p.reading.copy(usableBytes=100)
            } }
            assertEquals(old,s.snapshot()); assertTrue(File(dir,old.single().fileName).exists())
            assertFalse(dir.listFiles()!!.any { it.name.startsWith("segment-") && it.name!=old.single().fileName })
        }
        p.reading=p.reading.copy(usableBytes=100_000_000)
        CaptureSegmentStore(dir,4,4,CaptureStoragePolicy(100,p)).use { assertEquals(0L,it.snapshot().single().sequence) }
    }
    @Test fun governedAdmissionRejectsAnUnguardedStoreAndAnUndersizedReservation() {
        CaptureSegmentStore(temp.newFolder(),8,4).use { s -> denied(CaptureStorageFailure.UNGUARDED) { s.checkStorageReservation(CaptureStorageReservation(8,4,1)) } }
        val p=Probe()
        CaptureSegmentStore(temp.newFolder(),8,4,CaptureStoragePolicy(100,p)).use { s ->
            denied(CaptureStorageFailure.RESERVATION_TOO_SMALL) { s.checkStorageReservation(CaptureStorageReservation(8,4,1)) }
            val plan=CaptureStorageReservation(8,4,requireNotNull(s.minimumStorageOverheadBytes))
            s.checkStorageReservation(plan)
            p.reading=p.reading.copy(usableBytes=plan.totalBytes-1)
            denied(CaptureStorageFailure.INSUFFICIENT_SPACE) { s.checkStorageReservation(plan) }
            p.reading=p.reading.copy(usableBytes=plan.totalBytes); s.checkStorageReservation(plan)
        }
    }
    @Test fun physicalFailureStopsTheTransportWithoutRetryAndKeepsCommittedBytesReadable() = runBlocking {
        val p=Probe()
        CaptureSegmentStore(temp.newFolder(),8,4,CaptureStoragePolicy(100,p)).use { s ->
            s.append(0,1,0,ByteArrayInputStream(byteArrayOf(1))); p.reading=p.reading.copy(usableBytes=100)
            var pulls=0; var closed=0
            val source=object:CaptureSegmentSource {
                override suspend fun next():CaptureInput { pulls++; return CaptureInput(1,2,0,ByteArrayInputStream(byteArrayOf(2))) }
                override suspend fun close():Boolean { closed++; return true }
            }
            val t=SegmentCaptureTransport(s,source); t.start()
            withTimeout(5000) { t.state.first { it==CaptureTransportState.STORAGE_BLOCKED } }
            assertTrue(t.close()); assertEquals(1,pulls); assertTrue(closed>=1)
            s.open(0).use { assertEquals(1,it.read()) }
        }
    }
    @Test fun lowMarginAfterIndexWriteKeepsOldIndexAuthoritativeAndScopedOrphanRecoveryWorks() {
        val p=Probe(); val dir=temp.newFolder()
        CaptureSegmentStore(dir,4,4,CaptureStoragePolicy(100,p)).use { s ->
            s.append(0,1,0,ByteArrayInputStream(byteArrayOf(1,2,3,4)))
            val before=File(dir,"index.bin").readBytes()
            p.onRead={ if(File(it,"index.new").exists()) p.reading=p.reading.copy(usableBytes=99) }
            denied(CaptureStorageFailure.INSUFFICIENT_SPACE) { s.append(1,2,0,ByteArrayInputStream(byteArrayOf(5))) }
            assertArrayEquals(before,File(dir,"index.bin").readBytes()); assertTrue(File(dir,"index.new").exists())
        }
        p.onRead=null; p.reading=p.reading.copy(usableBytes=100_000_000)
        CaptureSegmentStore(dir,4,4,CaptureStoragePolicy(100,p)).use { s ->
            assertFalse(File(dir,"index.new").exists()); assertEquals(0L,s.snapshot().single().sequence)
            assertEquals(1L,s.append(1,2,0,ByteArrayInputStream(byteArrayOf(5))).sequence)
        }
    }
    @Test fun cancelledOrInterruptedProbesPreserveTheCallersControlSignal() {
        var signal:Exception?=null
        val probe=CaptureSpaceProbe { signal?.let { throw it }; CaptureSpaceReading(100_000_000,4096,"fixture") }
        val f=CaptureStoragePolicy(1,probe).bind(temp.newFolder())
        for(e in listOf(CancellationException("fixture"),InterruptedException("fixture"))) {
            signal=e
            try { f.growth(0,1); fail() } catch(actual:Exception) { assertSame(e,actual) }
        }
    }
}
