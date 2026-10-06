package com.nuvio.tv.data.iptv

import android.os.Handler
import android.os.HandlerThread
import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.trackselection.FixedTrackSelection
import androidx.media3.exoplayer.upstream.DefaultAllocator
import com.nuvio.tv.core.iptv.*
import com.nuvio.tv.core.iptv.RetainedCaptureFixtures.add
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@UnstableApi
class CaptureEpochMediaSourceAndroidTest {
    @get:Rule val temp=TemporaryFolder()
    private val limits=CaptureSampleStagingLimits(2L*1024*1024)
    private fun <T> on(handler:Handler,block:()->T):T {
        val task=FutureTask<T> { block() }; assertTrue(handler.post(task)); return task.get(10,TimeUnit.SECONDS)
    }
    private inner class Fixture : AutoCloseable {
        val store=CaptureSegmentStore(temp.newFolder(),420000,210000)
        val index=CaptureTsInspectionIndex(store); val timeline=CaptureSampleTimeline(index)
        val producer=MutableStateFlow(CaptureTransportState.RUNNING); val events=MutableSharedFlow<Unit>(replay=1)
        val reader=IncrementalCaptureReaderConsumer(CaptureSampleBatchQueue(CaptureSampleLoadCursor(store,index,timeline,0,{producer.value}),limits,2*limits.maxBatchBytes),
            CaptureMedia3TimelineFactory(timeline),{producer.value},events,closeTimeoutMs=100)
        val source=CaptureEpochMediaSource(reader,closeTimeoutMs=100)
        val thread=HandlerThread("iptv-validation-source").also { it.start() }; val handler=Handler(thread.looper)
        val published=MutableStateFlow<Timeline?>(null)
        val caller=MediaSource.MediaSourceCaller { s,t -> assertSame(source,s); assertSame(thread,Thread.currentThread()); published.value=t }
        var period:CaptureEpochPeriod?=null; var prepared=false
        fun prepare() { on(handler) { source.prepareSource(caller,null,PlayerId.UNSET); prepared=true } }
        suspend fun ready(state:IncrementalReaderState,count:Int)=withTimeout(10000) { reader.state.first { it.state==state && it.batches.size==count } }
        override fun close() {
            on(handler) { period?.let { source.releasePeriod(it); period=null }; if(prepared) { source.releaseSource(caller); prepared=false } }
            runBlocking { assertTrue(source.close()) }; thread.quitSafely(); thread.join(5000); assertFalse(thread.isAlive); store.close()
        }
    }
    @Test fun actualSourcePublishesOnPlaybackLooperAndGrowsTheOwnedPeriod()=runBlocking<Unit> {
        Fixture().use { f -> f.store.add(0); f.source.start(); f.ready(IncrementalReaderState.WAITING,1); f.prepare()
            val initial=withTimeout(10000) { f.published.first { it!=null && !it.isEmpty } }!!
            val streams=on(f.handler) {
                val p=f.source.createPeriod(MediaSource.MediaPeriodId(initial.getUidOfPeriod(0)),DefaultAllocator(true,65536),0) as CaptureEpochPeriod
                f.period=p
                p.prepare(object:MediaPeriod.Callback {
                    override fun onPrepared(mediaPeriod:MediaPeriod) { assertSame(p,mediaPeriod) }
                    override fun onContinueLoadingRequested(source:MediaPeriod) { assertSame(p,source) }
                },0)
                val s=arrayOfNulls<SampleStream>(2)
                p.selectTracks(arrayOf(FixedTrackSelection(p.trackGroups[0],0),FixedTrackSelection(p.trackGroups[1],0)),BooleanArray(2),s,BooleanArray(2),0)
                val b=DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
                assertEquals(C.RESULT_FORMAT_READ,s[0]!!.readData(FormatHolder(),b,0))
                repeat(50) { b.clear(); assertEquals(C.RESULT_BUFFER_READ,s[0]!!.readData(FormatHolder(),b,0)); assertEquals(it*40000L,b.timeUs) }
                b.clear(); assertEquals(C.RESULT_NOTHING_READ,s[0]!!.readData(FormatHolder(),b,0)); s
            }
            f.store.add(1); f.events.emit(Unit); f.ready(IncrementalReaderState.CAPACITY,2)
            withTimeout(10000) { f.published.first { it!=null && it.getPeriod(0,Timeline.Period()).durationUs>=4000000 } }
            on(f.handler) {
                val b=DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
                assertEquals(C.RESULT_BUFFER_READ,streams[0]!!.readData(FormatHolder(),b,0)); assertEquals(2000000L,b.timeUs); assertTrue(b.isKeyFrame)
                try { f.source.createPeriod(MediaSource.MediaPeriodId(Any()),DefaultAllocator(true,65536),0); fail() } catch(_:IllegalStateException) { }
            }
            assertFalse(f.source.close()); assertEquals(IncrementalReaderState.CLOSING,f.reader.state.value.state)
        }
    }
    @Test fun initiallyEmptyRunningSourceNeverPublishesTemporaryEmptyEof()=runBlocking<Unit> {
        Fixture().use { f -> f.source.start(); f.ready(IncrementalReaderState.WAITING,0); f.prepare(); on(f.handler) { Unit }; assertNull(f.published.value)
            f.producer.value=CaptureTransportState.COMPLETE; f.events.emit(Unit); f.ready(IncrementalReaderState.ENDED,0)
            val complete=withTimeout(10000) { f.published.first { it!=null } }!!; assertTrue(complete.isEmpty)
            assertFalse(f.source.close())
        }
    }
}
