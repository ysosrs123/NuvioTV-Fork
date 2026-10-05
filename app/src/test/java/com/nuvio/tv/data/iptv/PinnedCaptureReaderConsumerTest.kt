package com.nuvio.tv.data.iptv

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import com.nuvio.tv.core.iptv.*
import com.nuvio.tv.core.iptv.RetainedCaptureFixtures.add
import java.io.FilterInputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real pins/inspection/seek/runtime; SYNTHETIC encoded samples. No Android extraction/decoder. */
@UnstableApi
class PinnedCaptureReaderConsumerTest {
    @get:Rule val temp = TemporaryFolder()
    private val limits = CaptureSampleStagingLimits(2L*1024*1024)
    private inner class Fixture : AutoCloseable {
        val store = CaptureSegmentStore(temp.newFolder(),210000,210000,
            CaptureStoragePolicy(1,CaptureSpaceProbe { CaptureSpaceReading(1_000_000_000,1,"fixture") }))
        val index = CaptureTsInspectionIndex(store)
        val timeline = CaptureSampleTimeline(index)
        val seeks = CaptureSeekController(timeline)
        val request: CaptureSeekRequest
        init { store.add(0); timeline.accept(index.inspect(0)); request = seeks.begin(0,10000).request!! }
        override fun close() = store.close()
    }
    private fun staged(original: CaptureSeekInput, bounds: CaptureSampleStagingLimits,
        cancellation: () -> Unit, failClose: Boolean = false, beforeClose: (() -> Unit)? = null): PinnedCaptureSegmentPeriod {
        var closes = 0
        val input = if (!failClose && beforeClose == null) original else CaptureSeekInput(original.request,
            InspectedCaptureInput(original.media.proof,object:FilterInputStream(original.media) {
                override fun close() { beforeClose?.invoke(); if (failClose && ++closes == 1) throw IOException("fixture close"); super.close() }
            }))
        cancellation(); input.media.readBytes(); cancellation()
        val vf = Format.Builder().setSampleMimeType("video/avc").setWidth(320).setHeight(180).build()
        val af = Format.Builder().setSampleMimeType("audio/mp4a-latm").setSampleRate(48000).setChannelCount(2).build()
        val video = (0 until 50).map { CapturedEncodedSample(it*40000L,if(it==0) C.BUFFER_FLAG_KEY_FRAME else 0,byteArrayOf(42,it.toByte())) }
        val audio = (0 until 95).map { CapturedEncodedSample(-21333+it*1024_000_000L/48000,C.BUFFER_FLAG_KEY_FRAME,byteArrayOf(43,it.toByte())) }
        return PinnedCaptureSegmentPeriod(CapturedSampleBatch(input.request.window,CapturedSampleTrack(vf,video),CapturedSampleTrack(af,audio),1000),input,bounds)
    }
    private suspend fun await(c: PinnedCaptureReaderConsumer, expected: CaptureReaderState) {
        withTimeout(5000) { c.state.first { it == expected } }
    }
    private fun pinned(f: Fixture) {
        try { f.store.add(1); fail("Pin must prevent retirement") } catch (_: CaptureRetentionBlocked) { }
    }
    @Test fun readyOwnsTheExactPeriodAndNeverAcknowledgesOrReleasesAtEof() = runBlocking<Unit> {
        Fixture().use { f ->
            val c = PinnedCaptureReaderConsumer(f.seeks,f.request,limits,stage={ i,b,x -> staged(i,b,x) })
            assertEquals(CaptureReaderState.NEW,c.state.value); assertNull(c.readyPeriod(f.request))
            c.start(); await(c,CaptureReaderState.READY)
            val p = requireNotNull(c.readyPeriod(f.request)); assertFalse(p.released); pinned(f)
            assertTrue(f.seeks.isCurrentCommitted(f.request))
            assertEquals(13600L,f.seeks.move(CapturePlaybackPosition(0,0),3600).request!!.position90k)
            assertNull(c.readyPeriod(f.request)); assertEquals(CaptureReaderState.STALE,c.state.value)
            assertFalse(p.released); pinned(f)
            assertTrue(c.close()); assertTrue(p.released); assertTrue(c.close()); f.store.add(1)
        }
    }
    @Test fun requestSupersededBeforeStartDoesNotOpenOrStageAnyInput() = runBlocking<Unit> {
        Fixture().use { f ->
            val calls = AtomicInteger(); f.seeks.begin(0,50000)
            val c = PinnedCaptureReaderConsumer(f.seeks,f.request,limits,stage={ i,b,x -> calls.incrementAndGet(); staged(i,b,x) })
            c.start(); await(c,CaptureReaderState.STALE); assertEquals(0,calls.get()); assertTrue(c.close())
            f.store.add(1)
        }
    }
    @Test fun expiredCommitDoesNotClampToOrStageAnotherRow() = runBlocking<Unit> {
        Fixture().use { f ->
            f.store.add(1)
            val c = PinnedCaptureReaderConsumer(f.seeks,f.request,limits,stage={ _,_,_ -> error("Must not stage") })
            c.start(); await(c,CaptureReaderState.EXPIRED); assertTrue(c.close())
        }
    }
    @Test fun lateStagingCompletionAfterNewSeekClosesItsPinAndCannotClearNewAnchor() = runBlocking<Unit> {
        Fixture().use { f ->
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val c = PinnedCaptureReaderConsumer(f.seeks,f.request,limits,stage={ i,b,x ->
                assertTrue(release.count == 1L); entered.countDown(); assertTrue(release.await(5,TimeUnit.SECONDS)); staged(i,b,x)
            })
            try {
                c.start(); assertTrue(entered.await(5,TimeUnit.SECONDS)); pinned(f)
                val newer = f.seeks.begin(0,90000).request!!; release.countDown(); await(c,CaptureReaderState.STALE)
                assertNull(c.readyPeriod(f.request)); assertFalse(f.seeks.acknowledge(f.request))
                val next = f.seeks.commit(newer).input!!; assertTrue(f.seeks.isCurrentCommitted(newer)); next.close()
                assertTrue(c.close()); f.store.add(1)
            } finally { release.countDown(); c.close() }
        }
    }
    @Test fun cancellationAfterStageTransfersThenClosesReturnedOwnerInsteadOfPublishing() = runBlocking<Unit> {
        Fixture().use { f ->
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            var returned: PinnedCaptureSegmentPeriod? = null
            val c = PinnedCaptureReaderConsumer(f.seeks,f.request,limits,closeTimeoutMs=40,stage={ i,b,x ->
                staged(i,b,x).also { returned=it; entered.countDown(); assertTrue(release.await(5,TimeUnit.SECONDS)) }
            })
            try {
                c.start(); assertTrue(entered.await(5,TimeUnit.SECONDS)); assertFalse(c.close()); pinned(f)
                assertEquals(CaptureReaderState.CLOSING,c.state.value); assertNull(c.readyPeriod(f.request))
                release.countDown(); withTimeout(5000) { while(!returned!!.released) delay(5) }
                assertTrue(c.close()); assertEquals(CaptureReaderState.CLOSED,c.state.value); f.store.add(1)
            } finally { release.countDown(); c.close() }
        }
    }
    @Test fun cancellationDuringStagingKeepsTheInputUntilTheSoleWorkerActuallyStops() = runBlocking<Unit> {
        Fixture().use { f ->
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val c = PinnedCaptureReaderConsumer(f.seeks,f.request,limits,closeTimeoutMs=40,stage={ i,b,x ->
                entered.countDown(); assertTrue(release.await(5,TimeUnit.SECONDS)); x(); staged(i,b,x)
            })
            try {
                c.start(); assertTrue(entered.await(5,TimeUnit.SECONDS)); assertFalse(c.close()); pinned(f)
                release.countDown(); withTimeout(5000) { while(!c.close()) delay(5) }; f.store.add(1)
            } finally { release.countDown(); c.close() }
        }
    }
    @Test fun failedStagingCleansTheUntransferredInputAndDoesNotRetry() = runBlocking<Unit> {
        Fixture().use { f ->
            val calls = AtomicInteger()
            val c = PinnedCaptureReaderConsumer(f.seeks,f.request,limits,stage={ _,_,_ -> calls.incrementAndGet(); throw IOException("fixture") })
            c.start(); await(c,CaptureReaderState.FAILED); assertEquals(1,calls.get())
            assertTrue(f.seeks.isCurrentCommitted(f.request)); assertTrue(c.close()); f.store.add(1)
        }
    }
    @Test fun failedPeriodClosureRetainsPinUntilExplicitRetry() = runBlocking<Unit> {
        Fixture().use { f ->
            val c = PinnedCaptureReaderConsumer(f.seeks,f.request,limits,stage={ i,b,x -> staged(i,b,x,true) })
            c.start(); await(c,CaptureReaderState.READY); val p = c.readyPeriod(f.request)!!
            assertFalse(c.close()); assertFalse(p.released); pinned(f)
            assertTrue(c.close()); assertTrue(p.released); f.store.add(1)
        }
    }
    @Test fun blockedCloseRetainsOneCloserAcrossTimeoutsUntilItsActualConfirmation() = runBlocking<Unit> {
        Fixture().use { f ->
            val entered = CountDownLatch(1); val release = CountDownLatch(1); val closes = AtomicInteger()
            val c = PinnedCaptureReaderConsumer(f.seeks,f.request,limits,closeTimeoutMs=40,stage={ i,b,x ->
                staged(i,b,x,beforeClose={ closes.incrementAndGet(); entered.countDown(); assertTrue(release.await(5,TimeUnit.SECONDS)) })
            })
            try {
                c.start(); await(c,CaptureReaderState.READY)
                assertFalse(c.close()); assertTrue(entered.await(5,TimeUnit.SECONDS)); assertFalse(c.close())
                assertEquals(1,closes.get()); pinned(f); assertNull(c.readyPeriod(f.request))
                release.countDown(); withTimeout(5000) { while(!c.close()) delay(5) }
                assertEquals(1,closes.get()); f.store.add(1)
            } finally { release.countDown(); c.close() }
        }
    }
    @Test fun staleWorkerWithFailedCleanupReportsRetainedOwnershipAndExplicitRetry() = runBlocking<Unit> {
        Fixture().use { f ->
            val c = PinnedCaptureReaderConsumer(f.seeks,f.request,limits,stage={ i,b,x ->
                staged(i,b,x,true).also { f.seeks.begin(0,90000) }
            })
            c.start(); await(c,CaptureReaderState.CLEANUP_REQUIRED); assertNull(c.readyPeriod(f.request)); pinned(f)
            assertFalse(f.seeks.isCurrentCommitted(f.request)); assertTrue(c.close()); f.store.add(1)
        }
    }
    @Test fun closingBeforeStartOpensNothingAndRejectsRestart() = runBlocking<Unit> {
        Fixture().use { f ->
            val c = PinnedCaptureReaderConsumer(f.seeks,f.request,limits,stage={ _,_,_ -> error("Must not stage") })
            assertTrue(c.close()); try { c.start(); fail() } catch (_: IllegalStateException) { }
            assertEquals(CaptureReaderState.CLOSED,c.state.value); f.store.add(1)
        }
    }
    @Test fun sharedRuntimeRetainsReaderMemoryAndExistingRecorderAcrossFailedClose() = runBlocking<Unit> {
        Fixture().use { f ->
            val admission = LiveSessionAdmission(DeviceAdmissionLimits(2,10_000_000,100_000_000))
            val runtime = SharedCaptureRuntime(admission); val key = AcquisitionKey("fixture","one","ts",1)
            val storage = CaptureStorageReservation(210000,210000,3_000_000)
            var transportCloses = 0
            val pipeline = CapturePipeline(f.store,object:OwnedCaptureTransport {
                override fun start() = Unit
                override suspend fun close(): Boolean { transportCloses++; return true }
            })
            val c = PinnedCaptureReaderConsumer(f.seeks,f.request,limits,stage={ i,b,x -> staged(i,b,x,true) })
            val reader = runtime.join(key,10,3,storage,ConsumerReservation(LiveConsumerRole.TIMESHIFT,0,limits.maxBatchBytes),{pipeline},{ c }) as CaptureJoinResult.Joined
            await(c,CaptureReaderState.READY)
            val record = runtime.join(key,10,3,storage,ConsumerReservation(LiveConsumerRole.RECORDING,0,5),{ error("Shared") },{ object:OwnedCaptureConsumer {
                override fun start() = Unit
                override suspend fun close() = true
            } }) as CaptureJoinResult.Joined
            assertFalse(runtime.close(reader.token)); assertEquals(limits.maxBatchBytes+18,admission.snapshot().memoryBytes)
            assertEquals(0,transportCloses); assertEquals(0,admission.snapshot().decoders); assertNull(admission.snapshot().audioOwner)
            assertTrue(runtime.close(reader.token)); assertEquals(18L,admission.snapshot().memoryBytes); assertEquals(0,transportCloses)
            f.store.add(1); assertTrue(runtime.close(record.token)); assertEquals(1,transportCloses)
            assertEquals(0,admission.snapshot().consumers); assertEquals(0L,admission.snapshot().storageBytes)
        }
    }
}
