package com.nuvio.tv.core.iptv

import java.io.ByteArrayInputStream
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SharedCaptureRuntimeTest {
    @get:Rule val temp = TemporaryFolder()
    private val admission = LiveSessionAdmission(DeviceAdmissionLimits(2, 1000, 10_000_000))
    private val runtime = SharedCaptureRuntime(admission)
    private val key = AcquisitionKey("account", "one", "ts", 1)
    private val storage = CaptureStorageReservation(8, 4, 2_200_000)
    private val viewer = ConsumerReservation(LiveConsumerRole.VIEWER, 1, 20)
    private val recorder = ConsumerReservation(LiveConsumerRole.RECORDING, 0, 5)
    private class Transport : OwnedCaptureTransport {
        var starts = 0
        var closes = 0
        var confirmed = true
        var failStart = false
        override fun start() { starts++; if (failStart) error("fixture") }
        override suspend fun close(): Boolean { closes++; return confirmed }
    }
    private class Consumer(val closing: suspend () -> Boolean = { true }) : OwnedCaptureConsumer {
        var starts = 0
        var closes = 0
        var failStart = false
        override fun start() { starts++; if (failStart) error("fixture") }
        override suspend fun close(): Boolean { closes++; return closing() }
    }
    private fun pipeline(transport: Transport) = CapturePipeline(CaptureSegmentStore(temp.newFolder(), 8, 4,
        CaptureStoragePolicy(1, CaptureSpaceProbe { CaptureSpaceReading(100_000_000, 1, "fixture") })), transport)
    private suspend fun join(pipeline: CapturePipeline, reservation: ConsumerReservation = viewer,
        consumer: OwnedCaptureConsumer = Consumer()): CaptureJoinResult =
        runtime.join(key, 10, 3, storage, reservation, { pipeline }, { consumer })
    private fun token(result: CaptureJoinResult) = (result as CaptureJoinResult.Joined).token

    @Test fun laterJoinerStartsATransportLeftUnstartedByAnUncertainFirstJoin() = runBlocking {
        val transport = Transport(); val pipeline = pipeline(transport)
        val underReserved = object : OwnedCaptureConsumer {
            override val minimumMemoryReservationBytes = 100L
            override fun start() = Unit
            override suspend fun close() = false
        }
        val failed = join(pipeline, consumer = underReserved) as CaptureJoinResult.Failed
        assertNotNull(failed.pendingConsumer); assertEquals(0, transport.starts)
        token(join(pipeline, recorder)); assertEquals(1, transport.starts)
    }

    @Test fun sharedRecordingOutlivesViewerAndFinalCloseReleasesInfrastructure() = runBlocking {
        val transport = Transport(); val pipeline = pipeline(transport)
        val view = token(join(pipeline)); val record = token(join(pipeline, recorder))
        assertEquals(1, transport.starts)
        assertEquals(1, admission.snapshot().upstreamsByAccount["account"])
        assertEquals(storage.totalBytes, admission.snapshot().storageBytes)
        assertEquals(38L, admission.snapshot().memoryBytes)
        assertTrue(runtime.close(view))
        assertEquals(0, transport.closes)
        assertEquals(0, admission.snapshot().decoders)
        assertNull(admission.snapshot().audioOwner)
        assertEquals(18L, admission.snapshot().memoryBytes)
        assertTrue(runtime.close(record))
        assertEquals(1, transport.closes)
        assertEquals(0, admission.snapshot().consumers)
        assertTrue(admission.snapshot().upstreamsByAccount.isEmpty())
        assertEquals(0L, admission.snapshot().storageBytes)
    }

    @Test fun uncertainConsumerCloseRetainsDecoderUntilConfirmed() = runBlocking {
        var confirmed = false
        val transport = Transport(); val pipeline = pipeline(transport)
        val view = token(join(pipeline, consumer = Consumer { confirmed }))
        assertFalse(runtime.close(view))
        assertEquals(1, admission.snapshot().decoders)
        assertNotNull(admission.snapshot().audioOwner)
        assertEquals(0, transport.closes)
        confirmed = true
        assertTrue(runtime.close(view))
    }

    @Test fun uncertainTransportCloseHoldsStorageMemoryAndAccountAndRejectsNewJoin() = runBlocking {
        val transport = Transport().apply { confirmed = false }; val pipeline = pipeline(transport)
        assertFalse(runtime.close(token(join(pipeline))))
        assertEquals(0, admission.snapshot().decoders)
        assertEquals(13L, admission.snapshot().memoryBytes)
        assertEquals(storage.totalBytes, admission.snapshot().storageBytes)
        assertEquals(CaptureJoinResult.Closing, join(pipeline))
        val denied = admission.acquire(key.copy(channelId = "two"), 10, recorder)
        assertEquals(AdmissionDenial.ACCOUNT_LIMIT, (denied as LiveAdmissionResult.Denied).reason)
        transport.confirmed = true
        assertTrue(runtime.retryClosing())
        assertTrue(admission.snapshot().upstreamsByAccount.isEmpty())
    }

    @Test fun forgottenLocalReaderKeepsInfrastructureEvenAfterTransportCloses() = runBlocking {
        val transport = Transport(); val pipeline = pipeline(transport)
        val view = token(join(pipeline))
        pipeline.store.append(0, 1000, 0, ByteArrayInputStream(byteArrayOf(1, 2)))
        val reader = pipeline.store.openSnapshotFrom(0)
        assertFalse(runtime.close(view))
        assertEquals(storage.totalBytes, admission.snapshot().storageBytes)
        assertEquals(1, reader.read())
        reader.close()
        assertTrue(runtime.retryClosing())
        assertEquals(1, transport.closes)
        assertEquals(0, admission.snapshot().consumers)
    }

    @Test fun foreignAcquisitionCannotBeSharedWithoutItsActualPipeline() = runBlocking {
        val lease = (admission.acquire(key, 10, recorder) as LiveAdmissionResult.Admitted).lease
        val result = runtime.join(key, 10, 3, storage, viewer,
            { error("Cannot create second upstream") }, { error("Cannot construct") })
        assertEquals(CaptureJoinResult.SharingUnavailable, result)
        assertEquals(1, admission.snapshot().consumers)
        admission.release(lease)?.let(admission::completeClose)
        Unit
    }

    @Test fun capacityAndIncompatiblePlansAreRejectedBeforeFactoriesRun() = runBlocking {
        val transport = Transport(); val pipeline = pipeline(transport)
        val first = token(join(pipeline)); val second = token(join(pipeline))
        assertEquals(AdmissionDenial.DEVICE_DECODERS, (join(pipeline) as CaptureJoinResult.Denied).reason)
        val mismatch = runtime.join(key, 10, 4, storage, recorder,
            { error("No construction") }, { error("No construction") })
        assertEquals(AdmissionDenial.INCOMPATIBLE_RESERVATION, (mismatch as CaptureJoinResult.Denied).reason)
        assertEquals(1, transport.starts)
        assertTrue(runtime.close(first)); assertTrue(runtime.close(second))
    }

    @Test fun failedStartupCanBeRetriedOnlyAfterActualClosure() = runBlocking {
        val transport = Transport().apply { failStart = true; confirmed = false }
        val pipeline = pipeline(transport)
        assertEquals(CaptureJoinResult.Failed(), join(pipeline))
        assertEquals(1, admission.snapshot().consumers)
        assertEquals(0, admission.snapshot().decoders)
        assertEquals(CaptureJoinResult.Closing, join(pipeline))
        transport.confirmed = true
        assertTrue(runtime.retryClosing())
    }

    @Test fun failedViewerStartDoesNotStopRecordingAndReturnsCleanupToken() = runBlocking {
        val transport = Transport(); val pipeline = pipeline(transport)
        val record = token(join(pipeline, recorder))
        var closed = false
        val bad = Consumer { closed }.apply { failStart = true }
        val failed = join(pipeline, consumer = bad) as CaptureJoinResult.Failed
        assertNotNull(failed.pendingConsumer)
        assertEquals(1, admission.snapshot().decoders)
        closed = true
        assertTrue(runtime.close(failed.pendingConsumer!!))
        assertEquals(0, transport.closes)
        assertTrue(runtime.close(record))
    }

    @Test fun cancelledCloseStillFinishesReleaseAndNeverStopsRemainingRecording() = runBlocking {
        val closing = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Boolean>()
        val transport = Transport(); val pipeline = pipeline(transport)
        val view = token(join(pipeline, consumer = Consumer { closing.complete(Unit); finish.await() }))
        val record = token(join(pipeline, recorder))
        val job = launch { runtime.close(view) }
        closing.await(); job.cancel()
        assertEquals(1, admission.snapshot().decoders)
        finish.complete(true); job.join()
        assertEquals(0, admission.snapshot().decoders)
        assertEquals(0, transport.closes)
        assertTrue(runtime.close(record))
    }

    @Test fun staleTokensCannotCloseReplacementAndShutdownCanRetryFailedConsumers() = runBlocking {
        val old = token(join(pipeline(Transport())))
        assertTrue(runtime.close(old))
        var canClose = false
        val transport = Transport(); val pipeline = pipeline(transport)
        token(join(pipeline, consumer = Consumer { canClose }))
        assertTrue(runtime.close(old))
        assertEquals(0, transport.closes)
        assertFalse(runtime.closeAll())
        assertEquals(CaptureJoinResult.Closing, join(pipeline))
        canClose = true
        assertTrue(runtime.closeAll())
        assertEquals(0, admission.snapshot().consumers)
    }

    @Test fun factoryFailureAndConsumerCapacityFailureReturnUnusedReservations() = runBlocking {
        val failed = runtime.join(key, 10, 3, storage, viewer,
            { error("fixture") }, { error("Must not construct") })
        assertEquals(CaptureJoinResult.Failed(), failed)
        assertEquals(0, admission.snapshot().consumers)
        val denied = runtime.join(key, 10, 3, storage,
            viewer.copy(memoryBytes = 1000), { error("Must not construct") }, { error("Must not construct") })
        assertEquals(AdmissionDenial.DEVICE_MEMORY, (denied as CaptureJoinResult.Denied).reason)
        assertTrue(admission.snapshot().upstreamsByAccount.isEmpty())
    }

    @Test fun cancelledJoinWithUncertainConsumerCanBeRecoveredByShutdown() = runBlocking {
        var closed = false
        val transport = Transport(); val pipeline = pipeline(transport)
        val consumer = object : OwnedCaptureConsumer {
            override fun start() { throw CancellationException("fixture") }
            override suspend fun close() = closed
        }
        try { join(pipeline, consumer = consumer); fail() } catch (_: CancellationException) { }
        assertEquals(1, admission.snapshot().decoders)
        closed = true
        assertTrue(runtime.closeAll())
        assertEquals(0, admission.snapshot().consumers)
    }
    @Test fun unguardedPipelineCannotOpenUpstreamOrConstructAConsumer() = runBlocking {
        val transport=Transport()
        val pipeline=CapturePipeline(CaptureSegmentStore(temp.newFolder(),8,4),transport)
        val result=runtime.join(key,10,3,storage,viewer,{ pipeline },{ error("Cannot construct") })
        assertEquals(CaptureJoinResult.Failed(),result); assertEquals(0,transport.starts)
        assertEquals(0,admission.snapshot().consumers); assertEquals(1,transport.closes)
    }

    @Test fun physicalDenialRetainsInfrastructureUntilTheUnstartedTransportConfirmsClosure() = runBlocking {
        var usable=100_000_000L
        val transport=Transport().apply { confirmed=false }
        val pipeline=CapturePipeline(CaptureSegmentStore(temp.newFolder(),8,4,
            CaptureStoragePolicy(1,CaptureSpaceProbe { CaptureSpaceReading(usable,1,"fixture") })),transport)
        usable=0
        assertEquals(CaptureJoinResult.Failed(),runtime.join(key,10,3,storage,viewer,{ pipeline },{ error("Cannot construct") }))
        assertEquals(0,transport.starts); assertEquals(0,admission.snapshot().decoders)
        assertEquals(storage.totalBytes,admission.snapshot().storageBytes)
        assertEquals(CaptureJoinResult.Closing,join(pipeline))
        transport.confirmed=true; assertTrue(runtime.retryClosing()); assertEquals(0L,admission.snapshot().storageBytes)
    }
    @Test fun storageDenialOfAnotherConsumerDoesNotStopTheExistingRecording() = runBlocking {
        var usable=100_000_000L
        val transport=Transport()
        val pipeline=CapturePipeline(CaptureSegmentStore(temp.newFolder(),8,4,
            CaptureStoragePolicy(1,CaptureSpaceProbe { CaptureSpaceReading(usable,1,"fixture") })),transport)
        val record=token(join(pipeline,recorder)); usable=0
        assertEquals(CaptureJoinResult.Failed(),runtime.join(key,10,3,storage,viewer,{ error("No new pipeline") },{ error("No consumer") }))
        assertEquals(1,transport.starts); assertEquals(0,transport.closes)
        assertEquals(2,admission.snapshot().consumers); assertEquals(0,admission.snapshot().decoders)
        usable=100_000_000; assertTrue(runtime.close(record)); assertEquals(0,admission.snapshot().consumers)
    }
    @Test fun consumerMemoryFloorIsValidatedBeforeAnyNewTransportStarts() = runBlocking<Unit> {
        val transport=Transport(); val pipeline=pipeline(transport); var starts=0; var closes=0
        val consumer=object:OwnedCaptureConsumer {
            override val minimumMemoryReservationBytes=21L
            override fun start() { starts++ }
            override suspend fun close():Boolean { closes++; return true }
        }
        assertEquals(CaptureJoinResult.Failed(),join(pipeline,consumer=consumer))
        assertEquals(0,starts); assertEquals(1,closes); assertEquals(0,transport.starts)
        assertEquals(1,transport.closes); assertEquals(0,admission.snapshot().consumers)
    }
    @Test fun underReservedJoinCannotStopAnExistingRecorderOrReleaseUnconfirmedMemory() = runBlocking<Unit> {
        val transport=Transport(); val pipeline=pipeline(transport); val record=token(join(pipeline,recorder))
        var confirmed=false; var starts=0
        val consumer=object:OwnedCaptureConsumer {
            override val minimumMemoryReservationBytes=21L
            override fun start() { starts++ }
            override suspend fun close()=confirmed
        }
        val result=join(pipeline,consumer=consumer) as CaptureJoinResult.Failed
        assertNotNull(result.pendingConsumer); assertEquals(0,starts); assertEquals(0,transport.closes)
        assertEquals(38L,admission.snapshot().memoryBytes)
        confirmed=true; assertTrue(runtime.close(result.pendingConsumer!!))
        assertEquals(18L,admission.snapshot().memoryBytes); assertEquals(0,transport.closes)
        assertTrue(runtime.close(record))
    }

    @Test fun decoderFloorRejectsCaptureOnlyReservationBeforeTransportOrPlayerStart() = runBlocking<Unit> {
        val transport=Transport(); val pipeline=pipeline(transport); var starts=0
        val consumer=object:OwnedCaptureConsumer {
            override val minimumDecoderReservationCount=1
            override fun start() { starts++ }
            override suspend fun close()=true
        }
        assertEquals(CaptureJoinResult.Failed(),join(pipeline,recorder,consumer))
        assertEquals(0,starts); assertEquals(0,transport.starts); assertEquals(0,admission.snapshot().consumers)
    }

}
