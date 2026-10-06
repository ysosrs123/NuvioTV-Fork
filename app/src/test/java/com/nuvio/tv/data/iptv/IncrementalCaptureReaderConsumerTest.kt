package com.nuvio.tv.data.iptv

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import com.nuvio.tv.core.iptv.*
import com.nuvio.tv.core.iptv.RetainedCaptureFixtures.add
import com.nuvio.tv.core.iptv.RetainedCaptureFixtures.bytes
import java.io.FilterInputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@UnstableApi
class IncrementalCaptureReaderConsumerTest {
    @get:Rule val temp=TemporaryFolder()
    private val limits=CaptureSampleStagingLimits(2L*1024*1024)
    private inner class Fixture(val retain:Long=420000) : AutoCloseable {
        val store=CaptureSegmentStore(temp.newFolder(),retain,210000,
            CaptureStoragePolicy(1,CaptureSpaceProbe { CaptureSpaceReading(1_000_000_000,1,"fixture") }))
        val index=CaptureTsInspectionIndex(store); val timeline=CaptureSampleTimeline(index)
        val producer=MutableStateFlow(CaptureTransportState.RUNNING)
        val events=MutableStateFlow(0)
        fun cursor(open:((CaptureSampleWindow)->InspectedCaptureInput)?=null)=if(open==null)
            CaptureSampleLoadCursor(store,index,timeline,0,{producer.value})
            else CaptureSampleLoadCursor(store,index,timeline,0,{producer.value},2,open)
        fun consumer(cursor:CaptureSampleLoadCursor=cursor(),timeout:Long=15000,
            eventFlow:Flow<Unit> = events.map { Unit },
            stage:(CaptureSampleLoadInput,CaptureSampleStagingLimits,()->Unit)->CapturedSampleBatch = ::fake):IncrementalCaptureReaderConsumer {
            return IncrementalCaptureReaderConsumer(CaptureSampleBatchQueue(cursor,limits,2*limits.maxBatchBytes,stage=stage),
                CaptureMedia3TimelineFactory(timeline),{producer.value},eventFlow,closeTimeoutMs=timeout)
        }
        override fun close()=store.close()
    }
    private fun fake(i:CaptureSampleLoadInput,b:CaptureSampleStagingLimits,x:()->Unit):CapturedSampleBatch {
        x(); i.media.readBytes(); x(); assertTrue(i.media.verified)
        val base=i.window.start90k*1_000_000/90_000
        val vf=Format.Builder().setSampleMimeType("video/avc").setWidth(320).setHeight(180).build()
        val af=Format.Builder().setSampleMimeType("audio/mp4a-latm").setSampleRate(48000).setChannelCount(2).build()
        val video=(0 until i.media.inspection.videoFrames).map { CapturedEncodedSample(base+it*40000L,if(it==0) C.BUFFER_FLAG_KEY_FRAME else 0,byteArrayOf(42,it.toByte())) }
        val audio=(0 until i.media.inspection.audioFrames).map { CapturedEncodedSample(i.window.audioStart90k*1_000_000/90_000+it*1024_000_000L/48000,C.BUFFER_FLAG_KEY_FRAME,byteArrayOf(43,it.toByte())) }
        assertTrue(b.maxBatchBytes>=1000)
        return CapturedSampleBatch(i.window,CapturedSampleTrack(vf,video),CapturedSampleTrack(af,audio),1000)
    }
    private suspend fun await(c:IncrementalCaptureReaderConsumer,s:IncrementalReaderState,count:Int?=null):IncrementalReaderSnapshot =
        withTimeout(5000) { c.state.first { it.state==s && (count==null || it.batches.size==count) } }

    @Test fun captureHintsGrowCachedTimelineWithoutPollingOrBlockingBorrowers() = runBlocking<Unit> {
        Fixture().use { f -> val c=f.consumer()
            try {
                c.start(); await(c,IncrementalReaderState.WAITING,0)
                f.store.add(0); f.events.value++
                val a=await(c,IncrementalReaderState.WAITING,1)
                withTimeout(5000) { c.state.first { it.state==IncrementalReaderState.WAITING && it.batches.size==1 && c.borrowSnapshot(it) === it } }
                assertTrue(a.timeline.getWindow(0,Timeline.Window()).isDynamic)
                assertNull(c.borrowSnapshot(a.copy()))
                try { (a.batches as MutableList<CaptureLoadedBatch>).clear(); fail() } catch (_:UnsupportedOperationException) { }
                f.producer.value=CaptureTransportState.COMPLETE; f.events.value++
                val end=await(c,IncrementalReaderState.ENDED,1); assertFalse(end.timeline.getWindow(0,Timeline.Window()).isDynamic)
                assertFalse(c.requestLoad()); assertTrue(c.requestRelease(end.batches.single()))
                assertNull(c.borrowSnapshot(end)); await(c,IncrementalReaderState.ENDED,0)
            } finally { assertTrue(c.close()) }
        }
    }
    @Test fun hintsDuringBlockedStagingAreCoalescedAndCachedReadsDoNotTouchTheQueue() = runBlocking<Unit> {
        Fixture().use { f ->
            f.store.add(0); val entered=CountDownLatch(1); val release=CountDownLatch(1); val calls=AtomicInteger()
            val c=f.consumer(stage={ i,b,x -> fake(i,b,x).also { if(calls.incrementAndGet()==1) { entered.countDown(); assertTrue(release.await(5,TimeUnit.SECONDS)) } } })
            try {
                c.start(); assertTrue(entered.await(5,TimeUnit.SECONDS))
                withTimeout(500) { async(Dispatchers.Default) { c.borrowSnapshot(c.state.value) }.await() }
                f.store.add(1); f.producer.value=CaptureTransportState.COMPLETE
                repeat(100) { c.requestLoad() }; release.countDown()
                val cap=await(c,IncrementalReaderState.CAPACITY,2); assertEquals(2,calls.get())
                assertTrue(c.requestRelease(cap.batches.first())); await(c,IncrementalReaderState.ENDED,1)
                assertEquals(2,calls.get())
            } finally { release.countDown(); assertTrue(c.close()) }
        }
    }
    @Test fun releaseInvalidatesDeliveredSnapshotThenRefillsAndPublishesStableOffset() = runBlocking<Unit> {
        Fixture().use { f -> f.store.add(0); f.store.add(1); val c=f.consumer()
            try {
                c.start(); val old=await(c,IncrementalReaderState.CAPACITY,2)
                assertTrue(c.requestRelease(old.batches.first())); assertNull(c.borrowSnapshot(old))
                val next=await(c,IncrementalReaderState.WAITING,1)
                assertEquals(2000000L,next.timeline.getWindow(0,Timeline.Window()).positionInFirstPeriodUs)
                assertFalse(c.requestRelease(old.batches.first()))
                withTimeout(5000) { c.state.first { it.state==IncrementalReaderState.WAITING && it.batches.size==1 && c.borrowSnapshot(it) === it } }
            } finally { assertTrue(c.close()) }
        }
    }
    @Test fun failedReleaseRetainsRuntimeMemoryAndPreservesTheIndependentRecorder() = runBlocking<Unit> {
        Fixture().use { f ->
            f.store.add(0); var closes=0
            val c=f.consumer(f.cursor { w -> InspectedCaptureInput(w.proof,object:FilterInputStream(f.index.open(w.proof)) {
                override fun close() { if(++closes==1) throw IOException("fixture"); super.close() }
            }) })
            val admission=LiveSessionAdmission(DeviceAdmissionLimits(2,10_000_000,100_000_000)); val runtime=SharedCaptureRuntime(admission)
            val key=AcquisitionKey("fixture","one","ts",1); val storage=CaptureStorageReservation(420000,210000,3_000_000)
            var transportCloses=0
            val pipeline=CapturePipeline(f.store,object:OwnedCaptureTransport { override fun start()=Unit; override suspend fun close():Boolean { transportCloses++; return true } })
            val read=runtime.join(key,10,3,storage,ConsumerReservation(LiveConsumerRole.TIMESHIFT,0,c.minimumMemoryReservationBytes),{pipeline},{c}) as CaptureJoinResult.Joined
            val ready=await(c,IncrementalReaderState.WAITING,1)
            val record=runtime.join(key,10,3,storage,ConsumerReservation(LiveConsumerRole.RECORDING,0,5),{error("Shared")},{object:OwnedCaptureConsumer { override fun start()=Unit; override suspend fun close()=true }}) as CaptureJoinResult.Joined
            assertTrue(c.requestRelease(ready.batches.single())); val blocked=await(c,IncrementalReaderState.RELEASE_BLOCKED)
            assertNull(c.borrowSnapshot(blocked)); assertFalse(c.requestLoad())
            assertEquals(c.minimumMemoryReservationBytes+18,admission.snapshot().memoryBytes); assertEquals(0,transportCloses)
            assertTrue(runtime.close(read.token)); assertEquals(18L,admission.snapshot().memoryBytes); assertEquals(0,transportCloses)
            assertTrue(runtime.close(record.token)); assertEquals(1,transportCloses); assertEquals(0,admission.snapshot().consumers)
        }
    }
    @Test fun cancellationWaitsForTheSoleStagerAndKeepsPinUntilConfirmedCleanup() = runBlocking<Unit> {
        Fixture(210000).use { f ->
            f.store.add(0); val entered=CountDownLatch(1); val release=CountDownLatch(1)
            val c=f.consumer(timeout=40,stage={i,b,x -> fake(i,b,x).also { entered.countDown(); assertTrue(release.await(5,TimeUnit.SECONDS)) }})
            try {
                c.start(); assertTrue(entered.await(5,TimeUnit.SECONDS)); assertFalse(c.close())
                assertNull(c.borrowSnapshot(c.state.value)); assertEquals(IncrementalReaderState.CLOSING,c.state.value.state)
                try { f.store.add(1); fail() } catch (_:CaptureRetentionBlocked) { }
                release.countDown(); withTimeout(5000) { while(!c.close()) delay(5) }
                assertEquals(IncrementalReaderState.CLOSED,c.state.value.state); assertTrue(c.state.value.batches.isEmpty()); f.store.add(1)
            } finally { release.countDown(); c.close() }
        }
    }
    @Test fun blockedCloserIsSharedAndLateConfirmationStillClearsCachedArrays() = runBlocking<Unit> {
        Fixture(210000).use { f ->
            f.store.add(0); val entered=CountDownLatch(1); val release=CountDownLatch(1); val closes=AtomicInteger()
            val c=f.consumer(f.cursor { w -> InspectedCaptureInput(w.proof,object:FilterInputStream(f.index.open(w.proof)) {
                override fun close() { closes.incrementAndGet(); entered.countDown(); assertTrue(release.await(5,TimeUnit.SECONDS)); super.close() }
            }) },timeout=40)
            try {
                c.start(); await(c,IncrementalReaderState.WAITING,1)
                assertFalse(c.close()); assertTrue(entered.await(5,TimeUnit.SECONDS)); assertFalse(c.close()); assertEquals(1,closes.get())
                try { f.store.add(1); fail() } catch (_:CaptureRetentionBlocked) { }
                release.countDown(); delay(100)
                withTimeout(5000) { while(!c.close()) delay(5) }
                assertEquals(1,closes.get()); assertEquals(IncrementalReaderState.CLOSED,c.state.value.state)
                assertTrue(c.state.value.batches.isEmpty()); f.store.add(1)
            } finally { release.countDown(); c.close() }
        }
    }
    @Test fun failedObserverCannotPublishALateSuccessfulStage() = runBlocking<Unit> {
        Fixture().use { f ->
            f.store.add(0); val fail=MutableStateFlow(false); val entered=CountDownLatch(1); val release=CountDownLatch(1)
            val c=f.consumer(eventFlow=fail.map { if(it) throw IOException("fixture events"); Unit },
                stage={ i,b,x -> fake(i,b,x).also { entered.countDown(); assertTrue(release.await(5,TimeUnit.SECONDS)) } })
            try {
                c.start(); assertTrue(entered.await(5,TimeUnit.SECONDS)); fail.value=true
                await(c,IncrementalReaderState.FAILED,0); release.countDown(); delay(100)
                assertEquals(IncrementalReaderState.FAILED,c.state.value.state); assertTrue(c.state.value.batches.isEmpty())
            } finally { release.countDown(); assertTrue(c.close()) }
        }
    }
    @Test fun explicitEpochBoundarySurvivesBatchReleaseWithoutAutomaticNavigation() = runBlocking<Unit> {
        Fixture().use { f -> f.store.add(0); f.store.add(1,continuity=1); val c=f.consumer()
            try {
                c.start(); val boundary=await(c,IncrementalReaderState.DISCONTINUITY,1)
                assertEquals(1L,boundary.boundary!!.epoch); assertFalse(c.requestLoad())
                assertTrue(c.requestRelease(boundary.batches.single()))
                val released=await(c,IncrementalReaderState.DISCONTINUITY,0); assertSame(boundary.boundary,released.boundary)
            } finally { assertTrue(c.close()) }
        }
    }
    @Test fun actualTransportPublicationHintsDriveTheReaderThroughSharedAdmission() = runBlocking<Unit> {
        Fixture().use { f ->
            val gate=Channel<Unit>(1); var sequence=0
            val source=object:CaptureSegmentSource {
                override suspend fun next():CaptureInput? { if(sequence==2) return null; if(sequence==1) gate.receive(); val n=sequence++; return CaptureInput(n*2000L,(n+1)*2000L,0,bytes(n).inputStream()) }
                override suspend fun close():Boolean { gate.close(); return true }
            }
            val transport=SegmentCaptureTransport(f.store,source)
            val c=IncrementalCaptureReaderConsumer(CaptureSampleBatchQueue(CaptureSampleLoadCursor(f.store,f.index,f.timeline,0,{transport.state.value}),limits,2*limits.maxBatchBytes,stage=::fake),
                CaptureMedia3TimelineFactory(f.timeline),{transport.state.value},transport.refreshEvents)
            val admission=LiveSessionAdmission(DeviceAdmissionLimits(2,10_000_000,100_000_000)); val runtime=SharedCaptureRuntime(admission)
            val joined=runtime.join(AcquisitionKey("fixture","transport","ts",1),10,3,CaptureStorageReservation(420000,210000,3_000_000),
                ConsumerReservation(LiveConsumerRole.TIMESHIFT,0,c.minimumMemoryReservationBytes),{CapturePipeline(f.store,transport)},{c}) as CaptureJoinResult.Joined
            try {
                await(c,IncrementalReaderState.WAITING,1); gate.send(Unit)
                val cap=await(c,IncrementalReaderState.CAPACITY,2); assertEquals(1L,transport.committedSequence.value)
                assertTrue(c.requestRelease(cap.batches.first())); await(c,IncrementalReaderState.ENDED,1)
                assertNull(admission.snapshot().audioOwner); assertEquals(0,admission.snapshot().decoders)
            } finally { assertTrue(runtime.close(joined.token)) }
        }
    }
    @Test fun closeBeforeStartFencesRestartAndEncodedFloorIsImmutableWithoutIo() = runBlocking<Unit> {
        Fixture().use { f -> val c=f.consumer()
            assertEquals(2*limits.maxBatchBytes,c.minimumMemoryReservationBytes)
            assertTrue(c.close()); assertEquals(IncrementalReaderState.CLOSED,c.state.value.state)
            assertFalse(c.requestLoad()); assertNull(c.borrowSnapshot(IncrementalReaderSnapshot(0,IncrementalReaderState.NEW)))
            try { c.start(); fail() } catch (_:IllegalStateException) { }
        }
    }
}
