package com.nuvio.tv.data.iptv

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import com.nuvio.tv.core.iptv.*
import com.nuvio.tv.core.iptv.RetainedCaptureFixtures.add
import java.io.FilterInputStream
import java.io.IOException
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Actual cursor/pinned files, SYNTHETIC compressed batches. No Android TS reader/decoder. */
@UnstableApi
class CaptureSampleBatchQueueTest {
    @get:Rule val temp=TemporaryFolder()
    private val limits=CaptureSampleStagingLimits(2L*1024*1024)
    private class Fixture(val store:CaptureSegmentStore) : AutoCloseable {
        val index=CaptureTsInspectionIndex(store); val timeline=CaptureSampleTimeline(index)
        var state=CaptureTransportState.RUNNING
        fun cursor()=CaptureSampleLoadCursor(store,index,timeline,0,{state})
        override fun close()=store.close()
    }
    private fun fixture(bytes:Long=420000)=Fixture(CaptureSegmentStore(temp.newFolder(),bytes,210000))
    private fun fake(input:CaptureSampleLoadInput,bounds:CaptureSampleStagingLimits,cancel:()->Unit):CapturedSampleBatch {
        cancel(); input.media.readBytes(); cancel(); assertTrue(input.media.verified)
        val base=input.window.start90k*1_000_000/90_000
        val vf=Format.Builder().setSampleMimeType("video/avc").setWidth(320).setHeight(180).build()
        val af=Format.Builder().setSampleMimeType("audio/mp4a-latm").setSampleRate(48000).setChannelCount(2).build()
        val v=(0 until input.media.inspection.videoFrames).map { CapturedEncodedSample(base+it*40000L,if(it==0) C.BUFFER_FLAG_KEY_FRAME else 0,byteArrayOf(42,it.toByte())) }
        val a=(0 until input.media.inspection.audioFrames).map { CapturedEncodedSample(input.window.audioStart90k*1_000_000/90_000+it*1024_000_000L/48000,C.BUFFER_FLAG_KEY_FRAME,byteArrayOf(43,it.toByte())) }
        assertTrue(bounds.maxBatchBytes>=1000)
        return CapturedSampleBatch(input.window,CapturedSampleTrack(vf,v),CapturedSampleTrack(af,a),1000)
    }
    private fun ready(q:CaptureSampleBatchQueue):CaptureLoadedBatch { val r=q.loadNext(); assertEquals(CaptureSampleLoadState.READY,r.state); return r.batch!! }
    @Test fun queueCapsPinsAndStagesExactSuccessorsWithoutEndingAWaitingTail() {
        fixture().use { f ->
            f.store.add(0); f.store.add(1)
            CaptureSampleBatchQueue(f.cursor(),limits,2*limits.maxBatchBytes,stage=::fake).use { q ->
                val a=ready(q); val b=ready(q); assertEquals(2000L,q.residentBytes)
                assertEquals(CaptureSampleLoadState.CAPACITY,q.loadNext().state)
                q.release(a); f.store.add(2); val c=ready(q); assertEquals(2L,c.samples.window.proof.segment.sequence)
                assertEquals(4000000L,c.samples.video.samples.first().timeUs)
                q.release(b); assertEquals(CaptureSampleLoadState.WAITING,q.loadNext().state)
                f.state=CaptureTransportState.COMPLETE; assertEquals(CaptureSampleLoadState.ENDED,q.loadNext().state)
                assertEquals(1000L,q.residentBytes); assertEquals(listOf(c),q.snapshotBatches())
                q.close(); assertEquals(0L,q.residentBytes); assertTrue(q.isClosed)
            }
        }
    }
    @Test fun nextWorstCaseByteBudgetIsChargedBeforeOpeningEvenWhenActualBatchesAreSmall() {
        fixture().use { f -> f.store.add(0); f.store.add(1)
            CaptureSampleBatchQueue(f.cursor(),limits,limits.maxBatchBytes,stage=::fake).use { q ->
                val a=ready(q); assertEquals(CaptureSampleLoadState.CAPACITY,q.loadNext().state)
                assertEquals(1,f.timeline.snapshot().windows.size); q.release(a)
                assertEquals(1L,ready(q).samples.window.proof.segment.sequence)
            }
        }
    }
    @Test fun failedStageDoesNotPublishAdvanceOrRetryAndRetainsThePinnedInput() {
        fixture(210000).use { f -> f.store.add(0); var calls=0
            CaptureSampleBatchQueue(f.cursor(),limits,limits.maxBatchBytes,stage={ i,_,_ -> calls++; i.media.readBytes(); throw IOException("fixture") }).use { q ->
                assertEquals(CaptureSampleLoadState.FAILED,q.loadNext().state); assertEquals(CaptureSampleLoadState.FAILED,q.loadNext().state)
                assertEquals(1,calls); assertTrue(q.snapshotBatches().isEmpty())
                try { f.store.add(1); fail() } catch (_:CaptureRetentionBlocked) { }
                q.close(); f.store.add(1)
            }
        }
    }
    @Test fun cancellationAfterStageKeepsTheCandidateWithoutPublishingUntilConfirmedClose() {
        fixture(210000).use { f -> f.store.add(0); var cancel=false
            CaptureSampleBatchQueue(f.cursor(),limits,limits.maxBatchBytes,stage={ i,b,x -> fake(i,b,x).also { cancel=true } }).use { q ->
                try { q.loadNext { if(cancel) throw CancellationException("fixture") }; fail() } catch (_:CancellationException) { }
                assertEquals(CaptureSampleLoadState.CANCELLED,q.loadNext().state); assertEquals(1000L,q.residentBytes)
                assertTrue(q.snapshotBatches().isEmpty()); try { f.store.add(1); fail() } catch (_:CaptureRetentionBlocked) { }
                q.close(); assertEquals(0L,q.residentBytes); f.store.add(1)
            }
        }
    }
    @Test fun failedReleaseOrCloseKeepsBatchBytesAndPinsUntilAnExplicitConfirmedRetry() {
        for(close in listOf(false,true)) fixture(210000).use { f -> f.store.add(0); var closes=0
            val cursor=CaptureSampleLoadCursor(f.store,f.index,f.timeline,0,{f.state},2,{ w ->
                InspectedCaptureInput(w.proof,object:FilterInputStream(f.index.open(w.proof)) {
                    override fun close() { if(++closes==1) throw IOException("fixture"); super.close() }
                })
            })
            CaptureSampleBatchQueue(cursor,limits,limits.maxBatchBytes,stage=::fake).use { q ->
                val b=ready(q)
                try { if(close) q.close() else q.release(b); fail() } catch (_:IOException) { }
                assertEquals(1000L,q.residentBytes); assertFalse(q.isClosed)
                try { f.store.add(1); fail() } catch (_:CaptureRetentionBlocked) { }
                if(close) { try { q.snapshotBatches(); fail() } catch (_:IllegalStateException) { } }
                else assertEquals(listOf(b),q.snapshotBatches())
                q.close(); assertEquals(0L,q.residentBytes); assertEquals(2,closes); f.store.add(1)
            }
        }
    }
    @Test fun invalidBudgetsAndForeignBatchesDoNotCloseOrReleaseAnotherOwner() {
        fixture().use { f -> f.store.add(0)
            f.cursor().use { cursor ->
                try { CaptureSampleBatchQueue(cursor,limits,limits.maxBatchBytes-1); fail() } catch (_:IllegalArgumentException) { }
                assertFalse(cursor.isClosed)
            }
            CaptureSampleBatchQueue(f.cursor(),limits,limits.maxBatchBytes,stage=::fake).use { a ->
                CaptureSampleBatchQueue(f.cursor(),limits,limits.maxBatchBytes,stage=::fake).use { b ->
                    val one=ready(a); val two=ready(b)
                    try { a.release(two); fail() } catch (_:IllegalArgumentException) { }
                    assertEquals(1000L,a.residentBytes); assertEquals(1000L,b.residentBytes); a.release(one); b.release(two)
                }
            }
        }
    }
    @Test fun reentrantFactoryCannotLoadAgainOrCloseDuringStaging() {
        fixture().use { f -> f.store.add(0)
            lateinit var q:CaptureSampleBatchQueue
            q=CaptureSampleBatchQueue(f.cursor(),limits,limits.maxBatchBytes,stage={ i,b,x ->
                try { q.loadNext(); fail("Reentrant load must be fenced") } catch (_:IllegalStateException) { }
                try { q.close(); fail("Reentrant close must not close the staging input") } catch (_:IllegalStateException) { }
                fake(i,b,x)
            })
            q.use { val loaded=ready(q); assertFalse(q.isClosed); assertEquals(listOf(loaded),q.snapshotBatches()); assertEquals(1000L,q.residentBytes) }
        }
    }

}
