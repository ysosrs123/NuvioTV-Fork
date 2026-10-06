package com.nuvio.tv.data.iptv

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import com.nuvio.tv.core.iptv.*
import com.nuvio.tv.core.iptv.RetainedCaptureFixtures.add
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@UnstableApi
class CaptureEpochMediaSourceOwnerTest {
    @get:Rule val temp=TemporaryFolder()
    private val limits=CaptureSampleStagingLimits(2L*1024*1024)
    private inner class Fixture : AutoCloseable {
        val store=CaptureSegmentStore(temp.newFolder(),210000,210000,
            CaptureStoragePolicy(1,CaptureSpaceProbe { CaptureSpaceReading(1_000_000_000,1,"fixture") }))
        val index=CaptureTsInspectionIndex(store); val timeline=CaptureSampleTimeline(index)
        val reader=IncrementalCaptureReaderConsumer(CaptureSampleBatchQueue(CaptureSampleLoadCursor(store,index,timeline,0,{CaptureTransportState.RUNNING}),limits,2*limits.maxBatchBytes,stage=::fake),
            CaptureMedia3TimelineFactory(timeline),{CaptureTransportState.RUNNING},closeTimeoutMs=100)
        val source=CaptureEpochMediaSource(reader,closeTimeoutMs=100)
        suspend fun ready(count:Int)=withTimeout(5000) { reader.state.first { it.state==IncrementalReaderState.WAITING && it.batches.size==count && reader.borrowSnapshot(it)===it } }
        override fun close() { runBlocking { assertTrue(source.close()) }; store.close() }
    }
    private fun fake(i:CaptureSampleLoadInput,b:CaptureSampleStagingLimits,x:()->Unit):CapturedSampleBatch {
        x(); i.media.readBytes(); x(); assertTrue(i.media.verified)
        val vf=Format.Builder().setSampleMimeType("video/avc").setWidth(320).setHeight(180).build()
        val af=Format.Builder().setSampleMimeType("audio/mp4a-latm").setSampleRate(48000).setChannelCount(2).build()
        val v=(0 until 50).map { CapturedEncodedSample(it*40000L,if(it==0) C.BUFFER_FLAG_KEY_FRAME else 0,byteArrayOf(42,it.toByte())) }
        val a=(0 until 95).map { CapturedEncodedSample(-21333+it*1024_000_000L/48000,C.BUFFER_FLAG_KEY_FRAME,byteArrayOf(43,it.toByte())) }
        assertTrue(b.maxBatchBytes>=1000); return CapturedSampleBatch(i.window,CapturedSampleTrack(vf,v),CapturedSampleTrack(af,a),1000)
    }
    @Test fun admittedSourceOwnsReaderAndObserverWithoutOpeningAPlayerOrLooper()=runBlocking<Unit> {
        Fixture().use { f -> f.source.start(); f.ready(0); assertEquals(f.reader.minimumMemoryReservationBytes,f.source.minimumMemoryReservationBytes)
            assertTrue(f.source.close()); assertEquals(IncrementalReaderState.CLOSED,f.reader.state.value.state)
            try { f.source.start(); fail() } catch(_:IllegalStateException) { }
        }
    }
    @Test fun activeReaderBorrowRetainsActualSourceRuntimeReservationAndOtherConsumer()=runBlocking<Unit> {
        Fixture().use { f -> f.store.add(0)
            val admission=LiveSessionAdmission(DeviceAdmissionLimits(2,10_000_000,100_000_000)); val runtime=SharedCaptureRuntime(admission)
            val key=AcquisitionKey("fixture","source","ts",1); val storage=CaptureStorageReservation(210000,210000,3_000_000)
            var transportCloses=0
            val pipeline=CapturePipeline(f.store,object:OwnedCaptureTransport { override fun start()=Unit; override suspend fun close():Boolean { transportCloses++; return true } })
            val joined=runtime.join(key,10,3,storage,ConsumerReservation(LiveConsumerRole.TIMESHIFT,0,f.source.minimumMemoryReservationBytes),{pipeline},{f.source}) as CaptureJoinResult.Joined
            val s=f.ready(1); val p=CaptureEpochPeriod.create(f.reader,s,s.timeline.getUidOfPeriod(0))
            val record=runtime.join(key,10,3,storage,ConsumerReservation(LiveConsumerRole.RECORDING,0,5),{error("Shared")},{object:OwnedCaptureConsumer { override fun start()=Unit; override suspend fun close()=true }}) as CaptureJoinResult.Joined
            try { assertFalse(runtime.close(joined.token)); assertEquals(f.source.minimumMemoryReservationBytes+18,admission.snapshot().memoryBytes); assertEquals(0,transportCloses) }
            finally { p.close() }
            assertTrue(runtime.close(joined.token)); assertEquals(18L,admission.snapshot().memoryBytes); assertEquals(0,transportCloses)
            assertTrue(runtime.close(record.token)); assertEquals(1,transportCloses)
        }
    }
    @Test fun closeBeforeStartOwnsCleanupAndFencesFutureSourceStartup()=runBlocking<Unit> {
        Fixture().use { f -> assertTrue(f.source.close()); assertEquals(IncrementalReaderState.CLOSED,f.reader.state.value.state)
            try { f.source.start(); fail() } catch(_:IllegalStateException) { }
        }
    }
}
