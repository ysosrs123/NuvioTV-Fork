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
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@UnstableApi
class PinnedCaptureSegmentPeriodAndroidTest {
    @get:Rule val temp=TemporaryFolder()
    private val limits=CaptureSampleStagingLimits(2L*1024*1024)
    private fun seek(store:CaptureSegmentStore):CaptureSeekInput {
        val index=CaptureTsInspectionIndex(store); val timeline=CaptureSampleTimeline(index); timeline.accept(index.inspect(0))
        val controller=CaptureSeekController(timeline); return requireNotNull(controller.commit(controller.begin(0,10000).request!!).input)
    }
    @Test fun actualTsStagingFeedsEveryEncodedSampleAndKeepsThePinThroughStreamEof() {
        CaptureSegmentStore(temp.newFolder(),210000,210000).use { store ->
            store.add(0); val input=seek(store)
            PinnedCaptureSegmentPeriod.stage(input,limits).use { p ->
                assertTrue(input.media.verified); assertFalse(input.media.isClosed)
                p.prepare(object:MediaPeriod.Callback {
                    override fun onPrepared(mediaPeriod:MediaPeriod) = Unit
                    override fun onContinueLoadingRequested(source:MediaPeriod) { fail() }
                },p.presentationStartUs)
                assertEquals(80000L,p.presentationStartUs)
                val streams=arrayOfNulls<SampleStream>(2)
                p.selectTracks(arrayOf(FixedTrackSelection(p.trackGroups[0],0),FixedTrackSelection(p.trackGroups[1],0)),BooleanArray(2),streams,BooleanArray(2),p.presentationStartUs)
                for(kind in 0..1) {
                    val stream=streams[kind]!!; val holder=FormatHolder(); val b=DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
                    assertEquals(C.RESULT_FORMAT_READ,stream.readData(holder,b,0)); var count=0
                    while(true) {
                        b.clear(); assertEquals(C.RESULT_BUFFER_READ,stream.readData(holder,b,0)); if(b.isEndOfStream) break
                        assertTrue(b.data!!.position()>0)
                        if(count==0) assertEquals(if(kind==0) 0L else -21333L,b.timeUs)
                        count++
                    }
                    assertEquals(if(kind==0) 50 else 95,count)
                }
                try { store.add(1); fail() } catch(_:CaptureRetentionBlocked) { }
            }
            assertTrue(input.media.isClosed); store.add(1)
        }
    }
    @Test fun failedStagingNeverTransfersCallerOwnershipToAPartialPeriod() {
        CaptureSegmentStore(temp.newFolder(),210000,210000).use { store ->
            store.add(0); val input=seek(store)
            try { PinnedCaptureSegmentPeriod.stage(input,limits) { throw InterruptedException("fixture") }; fail() }
            catch(_:InterruptedException) { }
            assertFalse(input.media.isClosed); assertFalse(input.media.verified)
            try { store.add(1); fail() } catch(_:CaptureRetentionBlocked) { }
            input.close(); store.add(1)
        }
    }
}
