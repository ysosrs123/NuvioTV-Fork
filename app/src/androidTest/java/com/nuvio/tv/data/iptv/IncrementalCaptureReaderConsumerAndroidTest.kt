package com.nuvio.tv.data.iptv

import androidx.media3.common.Timeline
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
class IncrementalCaptureReaderConsumerAndroidTest {
    @get:Rule val temp=TemporaryFolder()
    private val limits=CaptureSampleStagingLimits(2L*1024*1024)
    @Test fun actualStagingPublishesCachedTimelineAndPreservesPinsThroughWaitingAndEnd() = runBlocking<Unit> {
        CaptureSegmentStore(temp.newFolder(),420000,210000).use { store ->
            val index=CaptureTsInspectionIndex(store); val timeline=CaptureSampleTimeline(index)
            val producer=MutableStateFlow(CaptureTransportState.RUNNING); val events=MutableStateFlow(0)
            val c=IncrementalCaptureReaderConsumer(CaptureSampleBatchQueue(CaptureSampleLoadCursor(store,index,timeline,0,{producer.value}),limits,2*limits.maxBatchBytes),
                CaptureMedia3TimelineFactory(timeline),{producer.value},events.map { Unit })
            try {
                c.start(); withTimeout(10000) { c.state.first { it.state==IncrementalReaderState.WAITING } }
                store.add(0); events.value++
                val one=withTimeout(10000) { c.state.first { it.state==IncrementalReaderState.WAITING && it.batches.size==1 } }
                assertEquals(50,one.batches.single().samples.video.samples.size); assertEquals(95,one.batches.single().samples.audio.samples.size)
                assertEquals(-21333L,one.batches.single().samples.audio.samples.first().timeUs)
                assertTrue(one.timeline.getWindow(0,Timeline.Window()).isDynamic)
                store.add(1); events.value++
                val two=withTimeout(10000) { c.state.first { it.state==IncrementalReaderState.CAPACITY && it.batches.size==2 } }
                assertEquals(94,two.batches.last().samples.audio.samples.size)
                producer.value=CaptureTransportState.COMPLETE; events.value++
                assertTrue(c.requestRelease(two.batches.first())); assertNull(c.borrowSnapshot(two))
                val end=withTimeout(10000) { c.state.first { it.state==IncrementalReaderState.ENDED && it.batches.size==1 } }
                assertFalse(end.timeline.getWindow(0,Timeline.Window()).isDynamic); assertEquals(2000000L,end.timeline.getWindow(0,Timeline.Window()).positionInFirstPeriodUs)
                assertTrue(c.close()); assertEquals(IncrementalReaderState.CLOSED,c.state.value.state); assertTrue(c.state.value.batches.isEmpty())
            } finally { assertTrue(c.close()) }
        }
    }
    @Test fun actualEpochBoundaryRequiresNewOwnerAndSurvivesEncodedBatchRelease() = runBlocking<Unit> {
        CaptureSegmentStore(temp.newFolder(),420000,210000).use { store ->
            val index=CaptureTsInspectionIndex(store); val timeline=CaptureSampleTimeline(index); store.add(0); store.add(1,continuity=1)
            val c=IncrementalCaptureReaderConsumer(CaptureSampleBatchQueue(CaptureSampleLoadCursor(store,index,timeline,0,{CaptureTransportState.RUNNING}),limits,2*limits.maxBatchBytes),
                CaptureMedia3TimelineFactory(timeline),{CaptureTransportState.RUNNING})
            try {
                c.start(); val old=withTimeout(10000) { c.state.first { it.state==IncrementalReaderState.DISCONTINUITY } }
                assertEquals(1L,old.boundary!!.epoch); assertEquals(1,old.batches.size); assertFalse(c.requestLoad())
                assertTrue(c.requestRelease(old.batches.single()))
                val released=withTimeout(10000) { c.state.first { it.state==IncrementalReaderState.DISCONTINUITY && it.batches.isEmpty() } }
                assertSame(old.boundary,released.boundary)
            } finally { assertTrue(c.close()) }
        }
    }
}
