package com.nuvio.tv.data.iptv

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Format
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import com.nuvio.tv.core.iptv.*
import com.nuvio.tv.core.iptv.RetainedCaptureFixtures.add
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@UnstableApi
class CaptureMedia3TimelineTest {
    @get:Rule val temp = TemporaryFolder()
    private fun batch(w: CaptureSampleWindow): CapturedSampleBatch {
        val vf=Format.Builder().setSampleMimeType("video/avc").setWidth(320).setHeight(180).build()
        val af=Format.Builder().setSampleMimeType("audio/mp4a-latm").setSampleRate(48000).setChannelCount(2).build()
        return CapturedSampleBatch(w,CapturedSampleTrack(vf,emptyList()),CapturedSampleTrack(af,emptyList()),0)
    }

    @Test fun realMedia3WindowAndPeriodOffsetsRetainEpochOriginAndIncludeAudioTail() {
        CaptureSegmentStore(temp.newFolder(),400000,210000).use { store ->
            val index=CaptureTsInspectionIndex(store); val source=CaptureSampleTimeline(index); val factory=CaptureMedia3TimelineFactory(source)
            store.add(0); val first=batch(source.accept(index.inspect(0)))
            val initial=factory.snapshot(listOf(first),CaptureTransportState.RUNNING); val uid=initial.getUidOfPeriod(0)
            val w=initial.getWindow(0,Timeline.Window()); val p=initial.getPeriod(0,Timeline.Period(),true)
            assertEquals(1960000L,w.defaultPositionUs); assertEquals(2005333L,w.durationUs)
            assertEquals(0L,w.positionInFirstPeriodUs); assertEquals(0L,p.positionInWindowUs)
            assertTrue(w.isSeekable); assertTrue(w.isDynamic); assertFalse(w.isLive); assertEquals(C.TIME_UNSET,w.windowStartTimeMs)
            store.add(1); val second=batch(source.accept(index.inspect(1))); store.add(2); source.accept(index.inspect(2))
            val moved=factory.snapshot(listOf(first,second),CaptureTransportState.RUNNING)
            assertSame(uid,moved.getUidOfPeriod(0)); moved.getWindow(0,w); moved.getPeriod(0,p,true)
            assertEquals(2000000L,w.positionInFirstPeriodUs); assertEquals(-2000000L,p.positionInWindowUs)
            assertEquals(4010666L,p.durationUs); assertEquals(2010666L,w.durationUs); assertEquals(1960000L,w.defaultPositionUs)
        }
    }

    @Test fun projectionNeverInventsUncapturedSamplesAndCompletionRemovesDynamicFlag() {
        CaptureSegmentStore(temp.newFolder(),1048576,524288).use { store ->
            store.add(0); val index=CaptureTsInspectionIndex(store); val source=CaptureSampleTimeline(index)
            val b=batch(source.accept(index.inspect(0))); val factory=CaptureMedia3TimelineFactory(source)
            val t=factory.snapshot(listOf(b),CaptureTransportState.COMPLETE); val w=t.getWindow(0,Timeline.Window(),3600_000_000L)
            assertEquals(1960000L,w.defaultPositionUs); assertFalse(w.isDynamic); assertFalse(w.isLive)
        }
    }

    @Test fun explicitEpochWindowsNeverAutomaticallyNavigateAcrossADiscontinuity() {
        CaptureSegmentStore(temp.newFolder(),1048576,524288).use { store ->
            val index=CaptureTsInspectionIndex(store); val source=CaptureSampleTimeline(index)
            store.add(0); val a=batch(source.accept(index.inspect(0))); store.add(1,continuity=1); val b=batch(source.accept(index.inspect(1)))
            val t=CaptureMedia3TimelineFactory(source).snapshot(listOf(a,b),CaptureTransportState.RUNNING)
            assertEquals(2,t.windowCount); assertEquals(2,t.periodCount)
            assertEquals(C.INDEX_UNSET,t.getNextWindowIndex(0,Player.REPEAT_MODE_OFF,false))
            assertEquals(C.INDEX_UNSET,t.getPreviousWindowIndex(1,Player.REPEAT_MODE_OFF,false))
            assertFalse(t.getWindow(0,Timeline.Window()).isDynamic); assertTrue(t.getWindow(1,Timeline.Window()).isDynamic)
            assertNotSame(t.getUidOfPeriod(0),t.getUidOfPeriod(1))
        }
    }

    @Test fun incompleteStagedRunsCannotPublishASeekRangeAcrossAMissingSegment() {
        CaptureSegmentStore(temp.newFolder(),1048576,524288).use { store ->
            val index=CaptureTsInspectionIndex(store); val source=CaptureSampleTimeline(index); val batches=mutableListOf<CapturedSampleBatch>()
            for(n in 0..2) { store.add(n); batches+=batch(source.accept(index.inspect(n.toLong()))) }
            val factory=CaptureMedia3TimelineFactory(source)
            try { factory.snapshot(listOf(batches[0],batches[2]),CaptureTransportState.RUNNING); fail() } catch (_:IOException) { }
            assertEquals(1,factory.snapshot(batches,CaptureTransportState.RUNNING).periodCount)
        }
    }

    @Test fun emptyRetainedSnapshotKeepsUidForAdjacentFutureMediaAndForeignBatchIsIgnored() {
        CaptureSegmentStore(temp.newFolder(),210000,210000).use { store ->
            val index=CaptureTsInspectionIndex(store); val source=CaptureSampleTimeline(index); val factory=CaptureMedia3TimelineFactory(source)
            store.add(0); val first=batch(source.accept(index.inspect(0))); val uid=factory.snapshot(listOf(first),CaptureTransportState.RUNNING).getUidOfPeriod(0)
            store.add(1); assertTrue(factory.snapshot(listOf(first),CaptureTransportState.RUNNING).isEmpty)
            val next=batch(source.accept(index.inspect(1))); assertSame(uid,factory.snapshot(listOf(next),CaptureTransportState.RUNNING).getUidOfPeriod(0))
            val foreignIndex=CaptureTsInspectionIndex(store); val ownForeignSource=CaptureSampleTimeline(foreignIndex)
            val foreign=batch(ownForeignSource.accept(foreignIndex.inspect(1)))
            assertTrue(factory.snapshot(listOf(foreign),CaptureTransportState.RUNNING).isEmpty)
        }
    }

    @Test fun periodUidsAreScopedToTheSourceFactoryAndIdentityLookupIsStable() {
        CaptureSegmentStore(temp.newFolder(),1048576,524288).use { store ->
            store.add(0); val index=CaptureTsInspectionIndex(store); val source=CaptureSampleTimeline(index); val b=batch(source.accept(index.inspect(0)))
            val first=CaptureMedia3TimelineFactory(source).snapshot(listOf(b),CaptureTransportState.RUNNING)
            val second=CaptureMedia3TimelineFactory(source).snapshot(listOf(b),CaptureTransportState.RUNNING)
            assertEquals(0,first.getIndexOfPeriod(first.getUidOfPeriod(0)))
            assertEquals(C.INDEX_UNSET,second.getIndexOfPeriod(first.getUidOfPeriod(0)))
            assertNull(first.getPeriod(0,Timeline.Period(),false).uid)
            try { first.getWindow(1,Timeline.Window()); fail() } catch (_:IndexOutOfBoundsException) { }
        }
    }
}
