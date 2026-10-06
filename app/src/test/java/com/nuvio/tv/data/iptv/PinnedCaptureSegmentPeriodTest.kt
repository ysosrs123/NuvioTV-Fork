package com.nuvio.tv.data.iptv

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.trackselection.FixedTrackSelection
import com.nuvio.tv.core.iptv.*
import com.nuvio.tv.core.iptv.RetainedCaptureFixtures.add
import java.io.FilterInputStream
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@UnstableApi
class PinnedCaptureSegmentPeriodTest {
    @get:Rule val temp=TemporaryFolder()
    private val limits=CaptureSampleStagingLimits(2L*1024*1024)
    private class Fixture(val store:CaptureSegmentStore,val input:CaptureSeekInput,val period:PinnedCaptureSegmentPeriod,
        val batch:CapturedSampleBatch,val seeks:CaptureSeekController) : AutoCloseable {
        override fun close() { period.close(); input.close(); store.close() }
    }
    private fun fixture(failFirstClose:Boolean=false):Fixture {
        val s=CaptureSegmentStore(temp.newFolder(),210000,210000); s.add(0)
        val index=CaptureTsInspectionIndex(s); val timeline=CaptureSampleTimeline(index); val w=timeline.accept(index.inspect(0))
        val seeks=CaptureSeekController(timeline); val req=requireNotNull(seeks.begin(0,10000).request)
        val original=requireNotNull(seeks.commit(req).input)
        var closes=0
        val input=if(!failFirstClose) original else CaptureSeekInput(req,InspectedCaptureInput(w.proof,object:FilterInputStream(original.media) {
            override fun close() { if(++closes==1) throw IOException("fixture"); super.close() }
        }))
        input.media.readBytes(); assertTrue(input.media.verified); assertFalse(input.media.isClosed)
        val vf=Format.Builder().setSampleMimeType("video/avc").setWidth(320).setHeight(180).build()
        val af=Format.Builder().setSampleMimeType("audio/mp4a-latm").setSampleRate(48000).setChannelCount(2).build()
        val v=(0 until 50).map { CapturedEncodedSample(it*40000L,if(it==0) C.BUFFER_FLAG_KEY_FRAME else 0,byteArrayOf(42,it.toByte())) }
        val a=(0 until 95).map { CapturedEncodedSample(-21333+it*1024_000_000L/48000,C.BUFFER_FLAG_KEY_FRAME,byteArrayOf(43,it.toByte())) }
        val b=CapturedSampleBatch(w,CapturedSampleTrack(vf,v),CapturedSampleTrack(af,a),1000)
        val p=PinnedCaptureSegmentPeriod(b,input,limits)
        var prepared=false
        p.prepare(object:MediaPeriod.Callback {
            override fun onPrepared(mediaPeriod:MediaPeriod) { assertSame(p,mediaPeriod); prepared=true }
            override fun onContinueLoadingRequested(source:MediaPeriod) { fail("Finite data is already staged") }
        },p.presentationStartUs)
        assertTrue(prepared)
        return Fixture(s,input,p,b,seeks)
    }
    private fun selected(p:PinnedCaptureSegmentPeriod):Array<SampleStream?> {
        val groups=p.trackGroups
        val streams=arrayOfNulls<SampleStream>(2)
        p.selectTracks(arrayOf(FixedTrackSelection(groups[0],0),FixedTrackSelection(groups[1],0)),booleanArrayOf(false,false),streams,BooleanArray(2),p.presentationStartUs)
        return streams
    }
    private fun buffer()=DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
    private fun first(stream:SampleStream):DecoderInputBuffer {
        val b=buffer(); assertEquals(C.RESULT_FORMAT_READ,stream.readData(FormatHolder(),b,0))
        assertEquals(C.RESULT_BUFFER_READ,stream.readData(FormatHolder(),b,0)); return b
    }
    @Test fun formatPeekOmitPayloadsAndRepeatedEofKeepTheRealPinUntilExplicitClose() {
        fixture().use { f ->
            val streams=selected(f.period); val v=requireNotNull(streams[0]); val h=FormatHolder(); val b=buffer()
            assertEquals(C.RESULT_FORMAT_READ,v.readData(h,b,0)); assertEquals("video/avc",h.format!!.sampleMimeType)
            repeat(2) { b.clear(); assertEquals(C.RESULT_BUFFER_READ,v.readData(h,b,SampleStream.FLAG_PEEK)); assertEquals(0L,b.timeUs); assertEquals(42,b.data!!.get(0).toInt()) }
            val omitted=DecoderInputBuffer.newNoDataInstance()
            assertEquals(C.RESULT_BUFFER_READ,v.readData(h,omitted,SampleStream.FLAG_OMIT_SAMPLE_DATA or SampleStream.FLAG_PEEK)); assertNull(omitted.data)
            for(i in 0 until 50) {
                b.clear(); assertEquals(C.RESULT_BUFFER_READ,v.readData(h,b,0)); assertEquals(i*40000L,b.timeUs)
                assertEquals(i.toByte(),b.data!!.get(1)); assertEquals(i==49,b.isLastSample); assertEquals(i==0,b.isKeyFrame)
                if(i==0) assertEquals(C.RESULT_FORMAT_READ,v.readData(h,b,SampleStream.FLAG_REQUIRE_FORMAT))
            }
            repeat(2) { b.clear(); assertEquals(C.RESULT_BUFFER_READ,v.readData(h,b,0)); assertTrue(b.isEndOfStream); assertEquals(C.TIME_END_OF_SOURCE,b.timeUs) }
            assertTrue(v.isReady); assertFalse(f.input.media.isClosed)
            try { f.store.add(1); fail() } catch(_:CaptureRetentionBlocked) { }
            f.period.close(); assertTrue(f.period.released); assertTrue(f.input.media.isClosed); assertFalse(v.isReady)
            f.store.add(1)
        }
    }
    @Test fun seekReturnsRealVideoFrameButFeedsInitialIdrAndNegativeAudioPreroll() {
        fixture().use { f ->
            assertEquals(80000L,f.period.presentationStartUs); val streams=selected(f.period)
            assertEquals(1040000L,f.period.seekToUs(1079999)); assertEquals(0L,first(streams[0]!!).timeUs)
            assertEquals(-21333L,first(streams[1]!!).timeUs)
            assertEquals(1080000L,f.period.getAdjustedSeekPositionUs(1111111,SeekParameters.EXACT))
            assertEquals(0L,f.period.getAdjustedSeekPositionUs(1111111,SeekParameters.CLOSEST_SYNC))
            assertEquals(1960000L,f.period.seekToUs(Long.MAX_VALUE)); assertEquals(0L,f.period.seekToUs(Long.MIN_VALUE))

            assertEquals(13600L,f.seeks.move(CapturePlaybackPosition(0,0),3600).request!!.position90k)
            assertFalse(f.seeks.acknowledge(f.input.request))
        }
    }
    @Test fun skipNeverStartsVideoAtDependentDataAndAudioAdvancesToAPrecedingFrame() {
        fixture().use { f ->
            val streams=selected(f.period); val v=streams[0]!!; val a=streams[1]!!
            assertEquals(0,v.skipData(800000)); assertEquals(0L,first(v).timeUs)
            assertEquals(0,v.skipData(1600000)); val vb=buffer(); assertEquals(C.RESULT_BUFFER_READ,v.readData(FormatHolder(),vb,0)); assertEquals(40000L,vb.timeUs)
            assertEquals(38,a.skipData(800000)); assertEquals(789333L,first(a).timeUs)
            assertEquals(48,v.skipData(Long.MAX_VALUE)); vb.clear(); assertEquals(C.RESULT_BUFFER_READ,v.readData(FormatHolder(),vb,0)); assertTrue(vb.isEndOfStream)
        }
    }
    @Test fun retainedSelectionPreservesItsCursorAndDeselectedStreamsAreFenced() {
        fixture().use { f ->
            val p=f.period; val streams=selected(p); val v=streams[0]!!; first(v)
            val selections=arrayOf<ExoTrackSelection?>(FixedTrackSelection(p.trackGroups[0],0),null)
            val reset=BooleanArray(2)
            p.selectTracks(selections,booleanArrayOf(true,false),streams,reset,p.presentationStartUs)
            assertSame(v,streams[0]); assertFalse(reset[0]); assertNull(streams[1])
            val b=buffer(); assertEquals(C.RESULT_BUFFER_READ,v.readData(FormatHolder(),b,0)); assertEquals(40000L,b.timeUs)
            p.selectTracks(arrayOfNulls<ExoTrackSelection>(2),BooleanArray(2),streams,reset,p.presentationStartUs)
            assertFalse(v.isReady)
            try { v.readData(FormatHolder(),b,0); fail() } catch(_:IllegalStateException) { }
            assertFalse(f.input.media.isClosed)
        }
    }
    @Test fun foreignDuplicateAndWrongSizedSelectionsFailWithoutChangingValidStreams() {
        fixture().use { f ->
            val p=f.period; val streams=selected(p); val v=streams[0]
            val foreign=FixedTrackSelection(TrackGroup("foreign",f.batch.video.format),0)
            for(choices in listOf(arrayOf<ExoTrackSelection?>(foreign,null),arrayOf<ExoTrackSelection?>(FixedTrackSelection(p.trackGroups[0],0),FixedTrackSelection(p.trackGroups[0],0)))) {
                try { p.selectTracks(choices,BooleanArray(2),streams,BooleanArray(2),p.presentationStartUs); fail() } catch(_:IllegalArgumentException) { }
                assertSame(v,streams[0])
            }
            try { p.selectTracks(arrayOfNulls<ExoTrackSelection>(1),BooleanArray(2),streams,BooleanArray(2),0); fail() } catch(_:IllegalArgumentException) { }
            assertEquals(0L,first(v!!).timeUs)
        }
    }
    @Test fun failedDecoderBufferAllocationDoesNotConsumeTheSampleOrExposeMutablePayload() {
        fixture().use { f ->
            val v=selected(f.period)[0]!!; val tiny=DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DISABLED)
            assertEquals(C.RESULT_FORMAT_READ,v.readData(FormatHolder(),tiny,0))
            try { v.readData(FormatHolder(),tiny,0); fail() } catch(_:DecoderInputBuffer.InsufficientCapacityException) { }
            val b=buffer(); assertEquals(C.RESULT_BUFFER_READ,v.readData(FormatHolder(),b,0)); assertEquals(0L,b.timeUs)
            b.data!!.array().fill(0); f.period.seekToUs(80000)
            val again=first(v); assertEquals(42,again.data!!.get(0).toInt())
        }
    }
    @Test fun failedPinClosureFencesReadsAndKeepsRetentionUntilConfirmedRetry() {
        fixture(true).use { f ->
            val v=selected(f.period)[0]!!
            try { f.period.close(); fail() } catch(_:IOException) { }
            assertFalse(f.period.released); assertFalse(f.input.media.isClosed); assertFalse(v.isReady)
            try { v.maybeThrowError(); fail() } catch(_:IOException) { }
            try { f.store.add(1); fail() } catch(_:CaptureRetentionBlocked) { }
            f.period.close(); assertTrue(f.period.released); f.store.add(1)
        }
    }
    @Test fun unexpectedExternalCloseAndCrossThreadAccessCannotServeStaleSamples() {
        fixture().use { f ->
            val v=selected(f.period)[0]!!; val executor=Executors.newSingleThreadExecutor()
            try { assertTrue(executor.submit<Boolean> { try { f.period.trackGroups; false } catch(_:IllegalStateException) { true } }.get(5,TimeUnit.SECONDS)) }
            finally { executor.shutdownNow() }
            f.input.close(); assertFalse(v.isReady)
            try { v.readData(FormatHolder(),buffer(),0); fail() } catch(_:IOException) { }
            f.period.close(); assertTrue(f.period.released)
        }
    }
    @Test fun finitePreloadedPeriodHasNoNetworkLoadingAndRejectsUnderBudgetConstruction() {
        fixture().use { f ->
            val p=f.period
            assertEquals(C.TIME_END_OF_SOURCE,p.bufferedPositionUs); assertEquals(C.TIME_END_OF_SOURCE,p.nextLoadPositionUs)
            assertFalse(p.isLoading); assertFalse(p.continueLoading(LoadingInfo.Builder().build())); assertEquals(C.TIME_UNSET,p.readDiscontinuity())
            try { PinnedCaptureSegmentPeriod(f.batch,f.input,limits.copy(maxSampleBytes=1)); fail() } catch(_:IllegalArgumentException) { }
            assertFalse(f.input.media.isClosed)
            p.discardBuffer(Long.MAX_VALUE,true); p.reevaluateBuffer(0)
            assertEquals(0L,first(selected(p)[0]!!).timeUs)
        }
    }
}
