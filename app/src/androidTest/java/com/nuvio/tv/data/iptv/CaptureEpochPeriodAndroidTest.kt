package com.nuvio.tv.data.iptv

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.trackselection.FixedTrackSelection
import com.nuvio.tv.core.iptv.*
import com.nuvio.tv.core.iptv.RetainedCaptureFixtures.add
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Actual TS staging and Java MediaPeriod delivery; no codec/renderer/player/audio/display/network. */
@UnstableApi
class CaptureEpochPeriodAndroidTest {
    @get:Rule val temp=TemporaryFolder()
    private val limits=CaptureSampleStagingLimits(2L*1024*1024)
    private suspend fun await(r:IncrementalCaptureReaderConsumer,state:IncrementalReaderState,count:Int) = withTimeout(10000) {
        r.state.first { it.state==state && it.batches.size==count && r.borrowSnapshot(it) === it }
    }
    private fun prepared(r:IncrementalCaptureReaderConsumer,s:IncrementalReaderSnapshot):CaptureEpochPeriod {
        val p=CaptureEpochPeriod.create(r,s,s.timeline.getUidOfPeriod(0))
        p.prepare(object:MediaPeriod.Callback {
            override fun onPrepared(mediaPeriod:MediaPeriod) { assertSame(p,mediaPeriod) }
            override fun onContinueLoadingRequested(source:MediaPeriod) { assertSame(p,source) }
        },0)
        return p
    }
    private fun selected(p:CaptureEpochPeriod):Array<SampleStream?> {
        val s=arrayOfNulls<SampleStream>(2)
        p.selectTracks(arrayOf(FixedTrackSelection(p.trackGroups[0],0),FixedTrackSelection(p.trackGroups[1],0)),BooleanArray(2),s,BooleanArray(2),0)
        return s
    }
    private fun drain(s:SampleStream,count:Int,firstUs:Long) {
        val b=DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
        assertEquals(C.RESULT_FORMAT_READ,s.readData(FormatHolder(),b,0))
        repeat(count) { b.clear(); assertEquals(C.RESULT_BUFFER_READ,s.readData(FormatHolder(),b,0)); assertFalse(b.isEndOfStream); assertTrue(b.data!!.position()>0); if(it==0) assertEquals(firstUs,b.timeUs) }
    }
    @Test fun actualEncodedGrowthWaitsThenRetiresConsumedPrefixAndKeepsEpochPts() = runBlocking<Unit> {
        CaptureSegmentStore(temp.newFolder(),630000,210000).use { store ->
            val index=CaptureTsInspectionIndex(store); val timeline=CaptureSampleTimeline(index); val events=MutableSharedFlow<Unit>(replay=1); store.add(0)
            val r=IncrementalCaptureReaderConsumer(CaptureSampleBatchQueue(CaptureSampleLoadCursor(store,index,timeline,0,{CaptureTransportState.RUNNING}),limits,2*limits.maxBatchBytes),
                CaptureMedia3TimelineFactory(timeline),{CaptureTransportState.RUNNING},events)
            r.start(); val p=prepared(r,await(r,IncrementalReaderState.WAITING,1))
            try {
                val s=selected(p); drain(s[0]!!,50,0); drain(s[1]!!,95,-21333)
                assertEquals(C.RESULT_NOTHING_READ,s[0]!!.readData(FormatHolder(),DecoderInputBuffer.newNoDataInstance(),0))
                store.add(1); events.emit(Unit); val next=await(r,IncrementalReaderState.CAPACITY,2); assertTrue(p.refresh(next))
                assertFalse(r.requestRelease(next.batches.first())); assertTrue(p.retireConsumedPrefix(2000000))
                assertTrue(p.refresh(await(r,IncrementalReaderState.WAITING,1)))
                val b=DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
                assertEquals(C.RESULT_BUFFER_READ,s[0]!!.readData(FormatHolder(),b,0)); assertEquals(2000000L,b.timeUs); assertTrue(b.isKeyFrame)
            } finally { p.close(); assertTrue(r.close()) }
        }
    }
    @Test fun actualCompleteEosDoesNotReleaseBorrowUntilExplicitPeriodStop() = runBlocking<Unit> {
        CaptureSegmentStore(temp.newFolder(),210000,210000).use { store ->
            val index=CaptureTsInspectionIndex(store); val timeline=CaptureSampleTimeline(index); val producer=MutableStateFlow(CaptureTransportState.RUNNING); val events=MutableSharedFlow<Unit>(replay=1); store.add(0)
            val r=IncrementalCaptureReaderConsumer(CaptureSampleBatchQueue(CaptureSampleLoadCursor(store,index,timeline,0,{producer.value}),limits,2*limits.maxBatchBytes),
                CaptureMedia3TimelineFactory(timeline),{producer.value},events,closeTimeoutMs=100)
            r.start(); val p=prepared(r,await(r,IncrementalReaderState.WAITING,1))
            try {
                val v=selected(p)[0]!!; producer.value=CaptureTransportState.COMPLETE; events.emit(Unit); assertTrue(p.refresh(await(r,IncrementalReaderState.ENDED,1)))
                drain(v,50,0); val b=DecoderInputBuffer.newNoDataInstance(); assertEquals(C.RESULT_BUFFER_READ,v.readData(FormatHolder(),b,0)); assertTrue(b.isEndOfStream)
                assertFalse(r.close()); try { store.add(1); fail() } catch(_:CaptureRetentionBlocked) { }
                p.close(); assertTrue(r.close()); store.add(1)
            } finally { p.close(); assertTrue(r.close()) }
        }
    }
}
