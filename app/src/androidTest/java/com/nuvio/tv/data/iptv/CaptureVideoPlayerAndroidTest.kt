package com.nuvio.tv.data.iptv

import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.core.iptv.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

@UnstableApi
class CaptureVideoPlayerAndroidTest {
    private fun <T> main(block:()->T):T {
        val task=FutureTask<T> { block() }
        assertTrue(Handler(Looper.getMainLooper()).post(task))
        return task.get(30,TimeUnit.SECONDS)
    }
    private fun memory(label:String) {
        val m=Debug.MemoryInfo(); Debug.getMemoryInfo(m)
        Log.i("IptvCapturePlayer", "$label totalPssKb=${m.totalPss} javaHeapKb=${m.memoryStats["summary.java-heap"]} nativeHeapKb=${m.memoryStats["summary.native-heap"]} graphicsKb=${m.memoryStats["summary.graphics"]}")
    }
    @Test fun actualAdmittedVideoAndIndependentLeaseShareOneTransportUntilFinalClose()=runBlocking<Unit> { play(0) }
    @Test fun actualRendererDiscardsInitialIdrPrerollBeforeRequestedStart()=runBlocking<Unit> { play(1000) }

    private suspend fun play(startMs:Long) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val directory=File(context.cacheDir,"capture-player-${UUID.randomUUID()}")
        val thread=HandlerThread("iptv-offscreen-drain").also { it.start() }
        val frames=AtomicInteger()
        val output=ImageReader.newInstance(320,180,ImageFormat.YUV_420_888,3)
        output.setOnImageAvailableListener({ reader -> reader.acquireLatestImage()?.use { frames.incrementAndGet() } },Handler(thread.looper))
        val pts=Collections.synchronizedList(mutableListOf<Long>())
        val stageLimits=CaptureSampleStagingLimits(2L*1024*1024)
        val store=CaptureSegmentStore(directory,2L*1024*1024,512L*1024,CaptureStoragePolicy(1024*1024,AndroidCaptureSpaceProbe))
        var pulls=0; var upstreamClosed=false
        val upstream=object:CaptureSegmentSource {
            override suspend fun next():CaptureInput? {
                if(pulls==3) return null
                val n=pulls++
                val bytes=requireNotNull(javaClass.getResourceAsStream("/iptv-ts/segment0$n.ts")).use { it.readBytes() }
                return CaptureInput(n*2000L,(n+1)*2000L,0,bytes.inputStream())
            }
            override suspend fun close():Boolean { upstreamClosed=true; return true }
        }
        val transport=SegmentCaptureTransport(store,upstream)
        val index=CaptureTsInspectionIndex(store); val timeline=CaptureSampleTimeline(index)
        val reader=IncrementalCaptureReaderConsumer(CaptureSampleBatchQueue(CaptureSampleLoadCursor(store,index,timeline,0,{transport.state.value}),stageLimits,4*stageLimits.maxBatchBytes),
            CaptureMedia3TimelineFactory(timeline),{transport.state.value},transport.refreshEvents)
        val source=CaptureEpochMediaSource(reader,retiresPlayedBatches=true)
        val player=CaptureVideoPlayer(context,source,output.surface,96L*1024*1024,startMs,
            VideoFrameMetadataListener { timeUs,_,_,_ -> pts += timeUs },closeTimeoutMs=100)
        val owner=CapturePlaybackConsumer(source,player)
        val admission=LiveSessionAdmission(DeviceAdmissionLimits(1,160L*1024*1024,32L*1024*1024))
        val runtime=SharedCaptureRuntime(admission)
        val storage=CaptureStorageReservation(store.maxRetainedBytes,store.maxSegmentBytes,requireNotNull(store.minimumStorageOverheadBytes))
        val key=AcquisitionKey("fixture","video","ts",1)
        var confirmed=false
        try {
            memory("before startMs=$startMs")

            val record=runtime.join(key,1024*1024,1024*1024,storage,ConsumerReservation(LiveConsumerRole.RECORDING,0,1024),
                { CapturePipeline(store,transport) },{ object:OwnedCaptureConsumer { override fun start()=Unit; override suspend fun close()=true } }) as CaptureJoinResult.Joined
            val joined=main { runBlocking { runtime.join(key,1024*1024,1024*1024,storage,
                ConsumerReservation(LiveConsumerRole.VIEWER,1,owner.minimumMemoryReservationBytes),{ error("Must share actual pipeline") },{ assertSame(store,it); owner }) } }
            assertTrue("Admission: $joined",joined is CaptureJoinResult.Joined)
            val end=withTimeoutOrNull(30000) { player.state.first { it==CaptureVideoState.ENDED || it==CaptureVideoState.FAILED } }
            if(end==null) {
                val r=reader.state.value; val seen=synchronized(pts) { pts.toList() }
                throw AssertionError("Player did not finish startMs=$startMs ${main { player.describe() }} reader=${r.state} revision=${r.revision} " +
                    "batches=${r.batches.size} transport=${transport.state.value} pulls=$pulls upstreamClosed=$upstreamClosed stored=${store.snapshot().size} " +
                    "images=${frames.get()} rendered=${seen.size} firstUs=${seen.firstOrNull()} lastUs=${seen.lastOrNull()}")
            }
            if(end==CaptureVideoState.FAILED) throw AssertionError("Player failed code=${player.failureCode}",player.failure)
            assertEquals(CaptureVideoState.ENDED,end)
            assertEquals(3,pulls); assertTrue(upstreamClosed)
            assertTrue("No offscreen images",frames.get()>0)
            val rendered=synchronized(pts) { pts.toList() }
            assertTrue("No renderer timestamps",rendered.isNotEmpty())
            assertTrue("Preroll rendered: ${rendered.first()}",rendered.all { it>=startMs*1000 })
            assertTrue("Start position not honoured: ${rendered.first()}",rendered.first()<startMs*1000+2000000)
            assertTrue("Last frame missing: ${rendered.last()}",rendered.last()>=5960000L)
            assertTrue(rendered.zipWithNext().all { (a,b)->b>=a })
            assertEquals(1,admission.snapshot().decoders)
            assertEquals(1,admission.snapshot().upstreamsByAccount["fixture"])
            memory("ended startMs=$startMs")
            val token=(joined as CaptureJoinResult.Joined).token
            val entered=CountDownLatch(1); val unblock=CountDownLatch(1)
            assertTrue(Handler(Looper.getMainLooper()).post { entered.countDown(); unblock.await(10,TimeUnit.SECONDS) })
            assertTrue(entered.await(5,TimeUnit.SECONDS))
            try {
                repeat(2) { assertFalse(runtime.close(token)) }
                assertEquals(1,admission.snapshot().decoders)
                assertEquals(IncrementalReaderState.ENDED,reader.state.value.state)
            } finally { unblock.countDown() }
            withTimeout(10000) { player.state.first { it==CaptureVideoState.CLOSED } }
            assertTrue(runtime.close(token))
            assertEquals(CaptureVideoState.CLOSED,player.state.value)
            assertEquals(IncrementalReaderState.CLOSED,reader.state.value.state)
            assertEquals(0,admission.snapshot().decoders)
            assertEquals(2,admission.snapshot().consumers)
            assertEquals(3,store.snapshot().size)
            assertTrue(runtime.close(record.token)); confirmed=true
            assertEquals(0,admission.snapshot().consumers)
            assertEquals(0L,admission.snapshot().memoryBytes)
            Log.i("IptvCapturePlayer","PASS startMs=$startMs rendered=${rendered.size} firstUs=${rendered.first()} lastUs=${rendered.last()} images=${frames.get()} pulls=$pulls")
        } finally {
            if(!confirmed) confirmed=runtime.closeAll()

            if(confirmed) { output.close(); thread.quitSafely(); thread.join(5000); assertFalse(thread.isAlive); assertTrue(!directory.exists() || directory.deleteRecursively()) }
            assertTrue("Unconfirmed player/runtime closure",confirmed)
            memory("closed startMs=$startMs")
        }
    }
}
