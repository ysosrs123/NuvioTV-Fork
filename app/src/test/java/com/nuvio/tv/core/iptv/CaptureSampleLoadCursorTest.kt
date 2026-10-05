package com.nuvio.tv.core.iptv

import com.nuvio.tv.core.iptv.RetainedCaptureFixtures.add
import com.nuvio.tv.core.iptv.RetainedCaptureFixtures.bytes
import com.nuvio.tv.core.iptv.RetainedCaptureFixtures.transformPts
import java.io.FilterInputStream
import java.io.IOException
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CaptureSampleLoadCursorTest {
    @get:Rule val temp = TemporaryFolder()
    private class Fixture(val store: CaptureSegmentStore) : AutoCloseable {
        val index = CaptureTsInspectionIndex(store)
        val timeline = CaptureSampleTimeline(index)
        var state = CaptureTransportState.RUNNING
        fun cursor(start: Long = 0, cap: Int = 2) = CaptureSampleLoadCursor(store,index,timeline,start,{state},cap)
        override fun close() = store.close()
    }
    private fun fixture(bytes: Long = 420000) = Fixture(CaptureSegmentStore(temp.newFolder(),bytes,210000))
    private fun ready(c: CaptureSampleLoadCursor): CaptureSampleLoadInput {
        val r=c.poll(); assertEquals(CaptureSampleLoadState.READY,r.state); return r.input!!
    }
    private fun completed(c: CaptureSampleLoadCursor): CaptureSampleLoadInput {
        val t=ready(c); t.media.readBytes(); assertTrue(t.media.verified); c.complete(t); return t
    }
    @Test fun waitingTailCanGrowAndOnlyActualCompletionEndsWithoutReleasingAnchor() {
        fixture().use { f -> f.cursor().use { c ->
            assertEquals(CaptureSampleLoadState.WAITING,c.poll().state)
            f.store.add(0); val zero=completed(c); c.release(zero)
            assertEquals(CaptureSampleLoadState.WAITING,c.poll().state)
            f.store.add(1); try { f.store.add(2); fail() } catch (_:CaptureRetentionBlocked) { }
            val one=completed(c); assertEquals(180000L,one.window.start90k); c.release(one)
            f.store.add(2); val two=completed(c); assertEquals(360000L,two.window.start90k); c.release(two)
            f.state=CaptureTransportState.COMPLETE; assertEquals(CaptureSampleLoadState.ENDED,c.poll().state)
            try { f.store.close(); fail() } catch (_:IllegalStateException) { }
        } }
    }
    @Test fun repeatedReadyUsesSameInputAndOnlyVerifiedCompletionAllowsAdvancement() {
        fixture().use { f -> f.store.add(0); f.cursor().use { c ->
            val t=ready(c); assertSame(t,ready(c)); assertEquals(1,c.openInputs)
            try { c.complete(t); fail() } catch (_:IllegalArgumentException) { }
            assertSame(t,ready(c)); t.media.readBytes(); c.complete(t)
            try { c.complete(t); fail() } catch (_:IllegalArgumentException) { }
            assertEquals(CaptureSampleLoadState.WAITING,c.poll().state); assertFalse(t.media.isClosed)
            c.release(t); assertEquals(0,c.openInputs)
        } }
    }
    @Test fun capRetainsCompletedInputsAndExplicitReleaseAllowsExactSuccessor() {
        fixture().use { f -> f.store.add(0); f.store.add(1); f.cursor().use { c ->
            val a=completed(c); val b=completed(c)
            assertEquals(2,c.openInputs); assertEquals(CaptureSampleLoadState.CAPACITY,c.poll().state)
            try { f.store.add(2); fail() } catch (_:CaptureRetentionBlocked) { }
            c.release(a); f.store.add(2); val next=ready(c)
            assertEquals(2L,next.media.segment.sequence); assertEquals(0L,next.window.epoch)
            assertEquals(360960L,next.window.audioStart90k); c.release(b)
        } }
    }
    @Test fun stoppedStatesDrainPublishedRowsButNeverManufactureSuccessfulEof() {
        for (s in listOf(CaptureTransportState.FAILED,CaptureTransportState.BACKPRESSURE,CaptureTransportState.STORAGE_BLOCKED,CaptureTransportState.CLOSED)) {
            fixture().use { f -> f.store.add(0); f.state=s; f.cursor().use { c ->
                c.release(completed(c)); val r=c.poll(); assertEquals(CaptureSampleLoadState.STOPPED,r.state); assertEquals(s,r.producerState)
                assertEquals(r,c.poll())
            } }
        }
    }
    @Test fun completionIsSampledBeforeTheSnapshotOfItsFinalPublication() {
        fixture().use { f ->
            var calls=0
            CaptureSampleLoadCursor(f.store,f.index,f.timeline,0,{
                if(calls++==0) f.store.add(0); CaptureTransportState.COMPLETE
            }).use { c -> c.release(completed(c)); assertEquals(CaptureSampleLoadState.ENDED,c.poll().state) }
        }
    }
    @Test fun anEvictedLazyStartIsExpiredAndNeverSubstitutesTheAvailableRow() {
        fixture(210000).use { f -> f.store.add(0); f.cursor().use { c ->
            f.store.add(1); val r=c.poll(); assertEquals(CaptureSampleLoadState.EXPIRED,r.state)
            assertNull(r.input); assertEquals(r,c.poll()); assertEquals(0,c.openInputs)
        } }
    }
    @Test fun captureAndActualPtsEpochChangesAreExplicitStickyBoundaries() {
        for (timestamp in listOf(false,true)) fixture().use { f ->
            f.store.add(0)
            f.store.add(1,continuity=if(timestamp) 0 else 1,media=if(timestamp) transformPts(bytes(1)) { it+9000 } else bytes(1))
            f.cursor().use { c ->
                val a=completed(c); c.release(a); val r=c.poll()
                assertEquals(CaptureSampleLoadState.DISCONTINUITY,r.state); assertNull(r.input)
                assertEquals(1L,r.boundary!!.epoch)
                assertEquals(if(timestamp) CaptureEpochStart.TIMESTAMP_CHANGE else CaptureEpochStart.CAPTURE_DISCONTINUITY,r.boundary.epochStart)
                assertEquals(r,c.poll()); assertEquals(0,c.openInputs)
            }
        }
    }
    @Test fun prepublishedWindowsReuseTheirIdentityAndCannotAutomaticallyCrossEpochs() {
        fixture().use { f ->
            f.store.add(0); val a=f.timeline.accept(f.index.inspect(0))
            f.store.add(1,continuity=1); val b=f.timeline.accept(f.index.inspect(1))
            f.cursor().use { c -> val t=completed(c); assertSame(a,t.window); c.release(t); assertSame(b,c.poll().boundary) }
        }
    }
    @Test fun cancellationAfterAcquiringRetentionIsStickyAndRequiresExplicitCleanup() {
        fixture(210000).use { f -> f.store.add(0); f.cursor().use { c ->
            var checks=0
            try { c.poll { if(++checks==2) throw CancellationException("fixture") }; fail() } catch (_:CancellationException) { }
            assertEquals(CaptureSampleLoadState.CANCELLED,c.poll().state)
            assertFalse(c.isClosed); try { f.store.add(1); fail() } catch (_:CaptureRetentionBlocked) { }
            c.close(); assertTrue(c.isClosed); f.store.add(1)
        } }
    }
    @Test fun inspectionFailureRetainsItsCandidateAndNeverRetriesOrExposesPartialMedia() {
        fixture(210000).use { f ->
            val corrupt=bytes().also { it[0]=0 }; f.store.add(0,media=corrupt)
            f.cursor().use { c ->
                val r=c.poll(); assertEquals(CaptureSampleLoadState.FAILED,r.state); assertNull(r.input)
                assertEquals(r,c.poll()); try { f.store.add(1); fail() } catch (_:CaptureRetentionBlocked) { }
                c.close(); f.store.add(1)
            }
        }
    }
    @Test fun failedInputReleaseRetainsCapacityAndFailedCursorCloseFencesUntilRetry() {
        fixture().use { f ->
            f.store.add(0); f.store.add(1); val closes=mutableMapOf<Long,Int>()
            CaptureSampleLoadCursor(f.store,f.index,f.timeline,0,{f.state},2,{ w ->
                InspectedCaptureInput(w.proof,object:FilterInputStream(f.index.open(w.proof)) {
                    override fun close() {
                        val n=w.proof.segment.sequence; val count=(closes[n]?:0)+1; closes[n]=count
                        if(count==1) throw IOException("fixture close"); super.close()
                    }
                })
            }).use { c ->
                val a=completed(c); val b=completed(c)
                try { c.release(a); fail() } catch (_:IOException) { }
                assertEquals(2,c.openInputs); assertEquals(CaptureSampleLoadState.CAPACITY,c.poll().state)
                c.release(a); assertTrue(a.media.isClosed); assertEquals(1,c.openInputs)
                try { c.close(); fail() } catch (_:IOException) { }
                assertFalse(c.isClosed); assertFalse(b.media.isClosed)
                try { c.poll(); fail() } catch (_:IllegalStateException) { }
                c.close(); assertTrue(c.isClosed); assertTrue(b.media.isClosed); assertEquals(2,closes[0L]); assertEquals(2,closes[1L])
            }
        }
    }
    @Test fun foreignTicketsAndInvalidBoundsCannotAdvanceOrReleaseTheWrongCursor() {
        fixture().use { f -> f.store.add(0)
            for ((start,cap) in listOf(-1L to 2,2L to 2,0L to 17)) {
                try { f.cursor(start,cap); fail() } catch (_:IllegalArgumentException) { }
            }
            f.cursor().use { a -> f.cursor().use { b ->
                val ta=completed(a); val tb=completed(b)
                try { a.release(tb); fail() } catch (_:IllegalArgumentException) { }
                assertFalse(tb.media.isClosed); assertEquals(1,a.openInputs); a.release(ta); b.release(tb)
            } }
        }
    }
    @Test fun reentrantCallbacksCannotBypassTheInputCapOrCloseAnInFlightOwner() {
        fixture().use { f -> f.store.add(0); f.cursor(cap=1).use { c ->
            var checks=0
            val loaded=c.poll {
                if(++checks==2) {
                    try { c.poll(); fail("Reentrant poll must be fenced before another pin") } catch (_:IllegalStateException) { }
                    try { c.close(); fail("Reentrant close must not close active IO") } catch (_:IllegalStateException) { }
                }
            }
            assertEquals(CaptureSampleLoadState.READY,loaded.state); assertEquals(1,c.openInputs)
            loaded.input!!.media.readBytes(); c.complete(loaded.input); c.release(loaded.input)
            c.close(); assertTrue(c.isClosed)
        } }
    }

}
