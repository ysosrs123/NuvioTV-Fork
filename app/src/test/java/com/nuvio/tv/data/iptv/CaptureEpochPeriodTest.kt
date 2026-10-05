package com.nuvio.tv.data.iptv

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.trackselection.FixedTrackSelection
import com.nuvio.tv.core.iptv.*
import com.nuvio.tv.core.iptv.RetainedCaptureFixtures.add
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real reader/cursor/queue/pins and Java MediaPeriod APIs; SYNTHETIC encoded payloads, no codecs. */
@UnstableApi
class CaptureEpochPeriodTest {
    @get:Rule val temp=TemporaryFolder()
    private val limits=CaptureSampleStagingLimits(2L*1024*1024)
    private inner class Fixture : AutoCloseable {
        val store=CaptureSegmentStore(temp.newFolder(),630000,210000,
            CaptureStoragePolicy(1,CaptureSpaceProbe { CaptureSpaceReading(1_000_000_000,1,"fixture") }))
        val index=CaptureTsInspectionIndex(store); val timeline=CaptureSampleTimeline(index)
        val producer=MutableStateFlow(CaptureTransportState.RUNNING); val events=MutableSharedFlow<Unit>(replay=1)
        val reader=IncrementalCaptureReaderConsumer(CaptureSampleBatchQueue(CaptureSampleLoadCursor(store,index,timeline,0,{producer.value}),limits,2*limits.maxBatchBytes,stage=::fake),
            CaptureMedia3TimelineFactory(timeline),{producer.value},events,closeTimeoutMs=100)
        var period:CaptureEpochPeriod?=null
        suspend fun ready(state:IncrementalReaderState, count:Int):IncrementalReaderSnapshot = withTimeout(5000) {
            reader.state.first { it.state==state && it.batches.size==count && reader.borrowSnapshot(it) === it }
        }
        suspend fun open():CaptureEpochPeriod {
            reader.start(); val s=ready(IncrementalReaderState.WAITING,1)
            return CaptureEpochPeriod.create(reader,s,s.timeline.getUidOfPeriod(0)).also { p -> period=p
                p.prepare(object:MediaPeriod.Callback {
                    override fun onPrepared(mediaPeriod:MediaPeriod) { assertSame(p,mediaPeriod) }
                    override fun onContinueLoadingRequested(source:MediaPeriod) { assertSame(p,source) }
                },0)
            }
        }
        override fun close() { period?.close(); runBlocking { assertTrue(reader.close()) }; store.close() }
    }
    private fun fake(i:CaptureSampleLoadInput,b:CaptureSampleStagingLimits,x:()->Unit):CapturedSampleBatch {
        x(); i.media.readBytes(); x(); assertTrue(i.media.verified)
        val vf=Format.Builder().setSampleMimeType("video/avc").setWidth(320).setHeight(180).build()
        val af=Format.Builder().setSampleMimeType("audio/mp4a-latm").setSampleRate(48000).setChannelCount(2).build()
        val base=i.window.start90k*1_000_000/90_000
        val v=(0 until i.media.inspection.videoFrames).map { CapturedEncodedSample(base+it*40000L,if(it==0) C.BUFFER_FLAG_KEY_FRAME else 0,byteArrayOf(42,it.toByte())) }
        val a=(0 until i.media.inspection.audioFrames).map { CapturedEncodedSample(i.window.audioStart90k*1_000_000/90_000+it*1024_000_000L/48000,C.BUFFER_FLAG_KEY_FRAME,byteArrayOf(43,it.toByte())) }
        assertTrue(b.maxBatchBytes>=1000)
        return CapturedSampleBatch(i.window,CapturedSampleTrack(vf,v),CapturedSampleTrack(af,a),1000)
    }
    private fun select(p:CaptureEpochPeriod):Array<SampleStream?> {
        val s=arrayOfNulls<SampleStream>(2)
        p.selectTracks(arrayOf(FixedTrackSelection(p.trackGroups[0],0),FixedTrackSelection(p.trackGroups[1],0)),BooleanArray(2),s,BooleanArray(2),0)
        return s
    }
    private fun buffer()=DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
    private fun drain(s:SampleStream,count:Int,firstUs:Long,stepUs:Long?=null) {
        val b=buffer(); assertEquals(C.RESULT_FORMAT_READ,s.readData(FormatHolder(),b,0))
        repeat(count) { n -> b.clear(); assertEquals(C.RESULT_BUFFER_READ,s.readData(FormatHolder(),b,0)); assertFalse(b.isEndOfStream)
            if(n==0) assertEquals(firstUs,b.timeUs); stepUs?.let { assertEquals(firstUs+n*it,b.timeUs) }
        }
    }
    @Test fun waitingTailIsNothingThenGrowthContinuesAtSameCursorWithoutFiniteEos() = runBlocking<Unit> {
        Fixture().use { f -> f.store.add(0); val p=f.open(); val s=select(p); val v=s[0]!!
            drain(v,50,0,40000); val b=buffer()
            repeat(2) { assertEquals(C.RESULT_NOTHING_READ,v.readData(FormatHolder(),b,0)); assertFalse(b.isEndOfStream) }
            assertFalse(v.isReady); assertTrue(p.bufferedPositionUs>1960000); assertNotEquals(C.TIME_END_OF_SOURCE,p.nextLoadPositionUs)
            f.store.add(1); f.events.emit(Unit); val grown=f.ready(IncrementalReaderState.CAPACITY,2)
            assertTrue(p.refresh(grown)); assertTrue(v.isReady); b.clear()
            assertEquals(C.RESULT_BUFFER_READ,v.readData(FormatHolder(),b,0)); assertEquals(2000000L,b.timeUs); assertTrue(b.isKeyFrame)
            assertFalse(p.refresh(grown)); assertFalse(p.refresh(grown.copy(revision=grown.revision+1)))
            assertFalse(f.reader.requestRelease(grown.batches.first()))
        }
    }
    @Test fun playerLoadingCallbacksCannotStartATailPollingLoop() = runBlocking<Unit> {
        Fixture().use { f -> f.store.add(0); val p=f.open(); val before=f.reader.state.value
            val loading=LoadingInfo.Builder().setPlaybackPositionUs(0).build()
            repeat(100) { assertFalse(p.continueLoading(loading)) }
            assertSame(before,f.reader.state.value); assertFalse(p.isLoading)
        }
    }
    @Test fun onlyCompleteEmitsEosAndBorrowStillRetainsTheActualPin() = runBlocking<Unit> {
        Fixture().use { f -> f.store.add(0); val p=f.open(); val v=select(p)[0]!!
            f.producer.value=CaptureTransportState.COMPLETE; f.events.emit(Unit); val done=f.ready(IncrementalReaderState.ENDED,1)
            assertTrue(p.refresh(done)); val b=buffer(); assertEquals(C.RESULT_FORMAT_READ,v.readData(FormatHolder(),b,0))
            repeat(50) { b.clear(); assertEquals(C.RESULT_BUFFER_READ,v.readData(FormatHolder(),b,0)); assertEquals(it==49,b.isLastSample) }
            repeat(2) { b.clear(); assertEquals(C.RESULT_BUFFER_READ,v.readData(FormatHolder(),b,0)); assertTrue(b.isEndOfStream) }
            assertEquals(C.TIME_END_OF_SOURCE,p.bufferedPositionUs); assertFalse(f.reader.requestRelease(done.batches.single()))
            p.close(); assertFalse(v.isReady); assertTrue(f.reader.requestRelease(done.batches.single()))
            f.ready(IncrementalReaderState.ENDED,0)
        }
    }
    @Test fun stopAndEpochBoundaryNeverBecomeSuccessfulEos() = runBlocking<Unit> {
        for(boundary in listOf(false,true)) Fixture().use { f -> f.store.add(0); val p=f.open(); val v=select(p)[0]!!; drain(v,50,0)
            if(boundary) f.store.add(1,continuity=1) else f.producer.value=CaptureTransportState.FAILED
            f.events.emit(Unit); val next=f.ready(if(boundary) IncrementalReaderState.DISCONTINUITY else IncrementalReaderState.STOPPED,1)
            if(boundary) assertTrue(p.refresh(next)) else assertFalse(p.refresh(next)) // STOPPED cannot extend a borrow.
            try { v.readData(FormatHolder(),buffer(),0); fail() } catch(_:IOException) { }
            try { v.maybeThrowError(); fail() } catch(_:IOException) { }
        }
    }
    @Test fun closeFencesReaderButCannotReleaseBorrowedArrays() = runBlocking<Unit> {
        Fixture().use { f -> f.store.add(0); val p=f.open(); val v=select(p)[0]!!
            assertFalse(f.reader.close()); assertEquals(IncrementalReaderState.CLOSING,f.reader.state.value.state)
            assertFalse(v.isReady); try { v.readData(FormatHolder(),buffer(),0); fail() } catch(_:IOException) { }
            assertFalse(f.reader.close()); p.close(); assertTrue(f.reader.close()); assertTrue(f.reader.state.value.batches.isEmpty())
        }
    }
    @Test fun runtimeRetainsReaderReservationUntilPeriodStopsAndPreservesRecorder() = runBlocking<Unit> {
        Fixture().use { f -> f.store.add(0)
            val admission=LiveSessionAdmission(DeviceAdmissionLimits(2,10_000_000,100_000_000)); val runtime=SharedCaptureRuntime(admission)
            val key=AcquisitionKey("fixture","borrowed","ts",1); val storage=CaptureStorageReservation(630000,210000,3_000_000)
            var transportCloses=0
            val pipeline=CapturePipeline(f.store,object:OwnedCaptureTransport { override fun start()=Unit; override suspend fun close():Boolean { transportCloses++; return true } })
            val joined=runtime.join(key,10,3,storage,ConsumerReservation(LiveConsumerRole.TIMESHIFT,0,f.reader.minimumMemoryReservationBytes),{pipeline},{f.reader}) as CaptureJoinResult.Joined
            val s=f.ready(IncrementalReaderState.WAITING,1); val p=CaptureEpochPeriod.create(f.reader,s,s.timeline.getUidOfPeriod(0)); f.period=p
            val record=runtime.join(key,10,3,storage,ConsumerReservation(LiveConsumerRole.RECORDING,0,5),{error("Shared")},{object:OwnedCaptureConsumer { override fun start()=Unit; override suspend fun close()=true }}) as CaptureJoinResult.Joined
            assertFalse(runtime.close(joined.token)); assertEquals(f.reader.minimumMemoryReservationBytes+18,admission.snapshot().memoryBytes); assertEquals(0,transportCloses)
            p.close(); assertTrue(runtime.close(joined.token)); assertEquals(18L,admission.snapshot().memoryBytes); assertEquals(0,transportCloses)
            assertTrue(runtime.close(record.token)); assertEquals(1,transportCloses); assertEquals(0,admission.snapshot().consumers)
        }
    }
    @Test fun seekIntoLaterRowFeedsItsIdrAndAudioPhaseAndPeekDoesNotConsume() = runBlocking<Unit> {
        Fixture().use { f -> f.store.add(0); val p=f.open(); val s=select(p)
            f.store.add(1); f.events.emit(Unit); assertTrue(p.refresh(f.ready(IncrementalReaderState.CAPACITY,2)))
            assertEquals(3040000L,p.seekToUs(3079999)); val b=buffer(); val h=FormatHolder()
            assertEquals(C.RESULT_FORMAT_READ,s[0]!!.readData(h,b,0))
            repeat(2) { b.clear(); assertEquals(C.RESULT_BUFFER_READ,s[0]!!.readData(h,b,SampleStream.FLAG_PEEK)); assertEquals(2000000L,b.timeUs) }
            val no=DecoderInputBuffer.newNoDataInstance(); assertEquals(C.RESULT_BUFFER_READ,s[0]!!.readData(h,no,SampleStream.FLAG_OMIT_SAMPLE_DATA)); assertNull(no.data)
            assertEquals(C.RESULT_FORMAT_READ,s[1]!!.readData(h,b,0)); b.clear(); assertEquals(C.RESULT_BUFFER_READ,s[1]!!.readData(h,b,0)); assertEquals(2005333L,b.timeUs)
            assertEquals(2000000L,p.getAdjustedSeekPositionUs(3000000,SeekParameters.CLOSEST_SYNC)); assertEquals(3960000L,p.seekToUs(Long.MAX_VALUE))
        }
    }
    @Test fun explicitConsumedPrefixReleaseRefillsWhileEpochUidAndAbsolutePtsStayStable() = runBlocking<Unit> {
        Fixture().use { f -> f.store.add(0); val p=f.open(); val s=select(p)
            f.store.add(1); f.events.emit(Unit); val two=f.ready(IncrementalReaderState.CAPACITY,2); assertTrue(p.refresh(two))
            assertFalse(p.retireConsumedPrefix(2000000)); drain(s[0]!!,50,0); assertFalse(p.retireConsumedPrefix(2000000)); drain(s[1]!!,95,-21333)
            assertTrue(p.retireConsumedPrefix(2000000)); val one=f.ready(IncrementalReaderState.WAITING,1); assertTrue(p.refresh(one)); assertSame(p.uid,one.timeline.getUidOfPeriod(0))
            assertEquals(2000000L,one.timeline.getWindow(0,Timeline.Window()).positionInFirstPeriodUs)
            f.store.add(2); f.events.emit(Unit); assertTrue(p.refresh(f.ready(IncrementalReaderState.CAPACITY,2)))
            val b=buffer(); assertEquals(C.RESULT_BUFFER_READ,s[0]!!.readData(FormatHolder(),b,0)); assertEquals(2000000L,b.timeUs)
            assertEquals(2000000L,p.seekToUs(0)); assertEquals(4960000L,p.seekToUs(4999999))
        }
    }
    @Test fun duplicateOrForeignBorrowAndPlaybackThreadOrSelectionAreFenced() = runBlocking<Unit> {
        Fixture().use { f -> f.store.add(0); val p=f.open(); val now=f.reader.state.value
            assertNull(f.reader.acquireBorrow(now)); try { CaptureEpochPeriod.create(f.reader,now.copy(),p.uid); fail() } catch(_:IOException) { }
            val executor=Executors.newSingleThreadExecutor()
            try { assertTrue(executor.submit<Boolean> { try { p.trackGroups; false } catch(_:IllegalStateException) { true } }.get(5,TimeUnit.SECONDS)) } finally { executor.shutdownNow() }
            val s=select(p); val prior=s[0]; val foreign=FixedTrackSelection(androidx.media3.common.TrackGroup("foreign",Format.Builder().setSampleMimeType("video/avc").build()),0)
            try { p.selectTracks(arrayOf<ExoTrackSelection?>(foreign,null),BooleanArray(2),s,BooleanArray(2),0); fail() } catch(_:IllegalArgumentException) { }
            assertSame(prior,s[0]); p.close(); p.close(); assertFalse(prior!!.isReady)
        }
    }
}
