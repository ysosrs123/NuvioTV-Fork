package com.nuvio.tv.data.iptv

import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import com.nuvio.tv.core.iptv.*
import com.nuvio.tv.core.iptv.RetainedCaptureFixtures.add
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@UnstableApi
class CaptureSampleBatchQueueAndroidTest {
    @get:Rule val temp=TemporaryFolder()
    private val limits=CaptureSampleStagingLimits(2L*1024*1024)
    @Test fun growingCaptureStagesEverySampleWithStablePtsAndWaitingThenExplicitEnd() {
        CaptureSegmentStore(temp.newFolder(),420000,210000).use { store ->
            val index=CaptureTsInspectionIndex(store); val timeline=CaptureSampleTimeline(index)
            var state=CaptureTransportState.RUNNING
            val factory=CaptureMedia3TimelineFactory(timeline)
            CaptureSampleBatchQueue(CaptureSampleLoadCursor(store,index,timeline,0,{state}),limits,2*limits.maxBatchBytes).use { q ->
                assertEquals(CaptureSampleLoadState.WAITING,q.loadNext().state)
                store.add(0); val zero=q.loadNext().batch!!; assertEquals(50,zero.samples.video.samples.size); assertEquals(95,zero.samples.audio.samples.size)
                assertEquals(-21333L,zero.samples.audio.samples.first().timeUs)
                store.add(1); val one=q.loadNext().batch!!; assertEquals(50,one.samples.video.samples.size); assertEquals(94,one.samples.audio.samples.size)
                assertEquals(2000000L,one.samples.video.samples.first().timeUs)
                assertEquals(CaptureSampleLoadState.CAPACITY,q.loadNext().state); q.release(zero); store.add(2)
                val two=q.loadNext().batch!!; assertEquals(50,two.samples.video.samples.size); assertEquals(94,two.samples.audio.samples.size)
                assertEquals(4000000L,two.samples.video.samples.first().timeUs)
                q.release(one); assertEquals(CaptureSampleLoadState.WAITING,q.loadNext().state)
                val t=factory.snapshot(q.snapshotBatches().map { it.samples },state); val w=t.getWindow(0,Timeline.Window())
                assertTrue(w.isDynamic); assertEquals(4000000L,w.positionInFirstPeriodUs)
                state=CaptureTransportState.COMPLETE; assertEquals(CaptureSampleLoadState.ENDED,q.loadNext().state)
                assertFalse(factory.snapshot(q.snapshotBatches().map { it.samples },state).getWindow(0,Timeline.Window()).isDynamic)
                assertTrue(q.residentBytes>0); q.close(); assertEquals(0L,q.residentBytes)
            }
        }
    }
    @Test fun explicitEpochBoundaryNeverStagesTheNextDiscontinuousSegment() {
        CaptureSegmentStore(temp.newFolder(),420000,210000).use { store ->
            val index=CaptureTsInspectionIndex(store); val timeline=CaptureSampleTimeline(index)
            store.add(0); store.add(1,continuity=1)
            CaptureSampleBatchQueue(CaptureSampleLoadCursor(store,index,timeline,0,{CaptureTransportState.RUNNING}),limits,2*limits.maxBatchBytes).use { q ->
                assertNotNull(q.loadNext().batch); val boundary=q.loadNext()
                assertEquals(CaptureSampleLoadState.DISCONTINUITY,boundary.state); assertNull(boundary.batch)
                assertEquals(1L,boundary.boundary!!.epoch); assertEquals(1,q.snapshotBatches().size)
            }
        }
    }
    @Test fun cancelledActualStagePublishesNothingAndRetainsAnchorUntilExplicitClose() {
        CaptureSegmentStore(temp.newFolder(),210000,210000).use { store ->
            val index=CaptureTsInspectionIndex(store); val timeline=CaptureSampleTimeline(index); store.add(0)
            var enteredStager=false; var stageChecks=0
            CaptureSampleBatchQueue(CaptureSampleLoadCursor(store,index,timeline,0,{CaptureTransportState.RUNNING}),limits,limits.maxBatchBytes,
                stage={ input,bounds,cancel -> enteredStager=true; LocalCaptureSampleStager(bounds).stage(input.window,input.media,cancel) }).use { q ->
                try { q.loadNext { if(enteredStager && ++stageChecks==2) throw CancellationException("fixture") }; fail() } catch (_:CancellationException) { }
                assertTrue(enteredStager); assertEquals(2,stageChecks)
                assertEquals(CaptureSampleLoadState.CANCELLED,q.loadNext().state); assertTrue(q.snapshotBatches().isEmpty())
                try { store.add(1); fail() } catch (_:CaptureRetentionBlocked) { }
                q.close(); store.add(1)
            }
        }
    }
}
