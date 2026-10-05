package com.nuvio.tv.data.iptv

import androidx.media3.common.util.UnstableApi
import com.nuvio.tv.core.iptv.*
import com.nuvio.tv.core.iptv.RetainedCaptureFixtures.add
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Actual TS staging in the async owner; no player, codec, display, audio or network. */
@UnstableApi
class PinnedCaptureReaderConsumerAndroidTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun actualStagerPublishesOnlyVerifiedPinnedPeriodAndConfirmedCloseReleasesIt() = runBlocking<Unit> {
        CaptureSegmentStore(temp.newFolder(),210000,210000).use { store ->
            store.add(0); val index = CaptureTsInspectionIndex(store); val timeline = CaptureSampleTimeline(index)
            timeline.accept(index.inspect(0)); val seeks = CaptureSeekController(timeline); val request = seeks.begin(0,10000).request!!
            val c = PinnedCaptureReaderConsumer(seeks,request,CaptureSampleStagingLimits(2L*1024*1024))
            try {
                c.start(); withTimeout(10000) { c.state.first { it != CaptureReaderState.NEW && it != CaptureReaderState.LOADING } }
                assertEquals(CaptureReaderState.READY,c.state.value); val p = c.readyPeriod(request)!!
                assertEquals(80000L,p.presentationStartUs); assertTrue(seeks.isCurrentCommitted(request))
                try { store.add(1); fail() } catch (_: CaptureRetentionBlocked) { }
                assertTrue(c.close()); assertTrue(p.released); assertTrue(seeks.isCurrentCommitted(request)); store.add(1)
            } finally { assertTrue(c.close()) }
        }
    }
    @Test fun supersededRequestNeverStagesOrSubstitutesAnotherRow() = runBlocking<Unit> {
        CaptureSegmentStore(temp.newFolder(),210000,210000).use { store ->
            store.add(0); val index = CaptureTsInspectionIndex(store); val timeline = CaptureSampleTimeline(index)
            timeline.accept(index.inspect(0)); val seeks = CaptureSeekController(timeline); val request = seeks.begin(0,10000).request!!
            val newer = seeks.begin(0,90000).request!!
            val c = PinnedCaptureReaderConsumer(seeks,request,CaptureSampleStagingLimits(2L*1024*1024))
            try {
                c.start(); withTimeout(10000) { c.state.first { it == CaptureReaderState.STALE } }
                assertNull(c.readyPeriod(request)); assertTrue(c.close())
                seeks.commit(newer).input!!.use { assertEquals(newer,it.request) }; store.add(1)
            } finally { assertTrue(c.close()) }
        }
    }
}
