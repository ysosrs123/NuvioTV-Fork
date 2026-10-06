package com.nuvio.tv.core.iptv

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class LivePlaybackRuntimeTest {
    private val admission = LiveSessionAdmission(DeviceAdmissionLimits(2, 1000, 0))
    private val runtime = LivePlaybackRuntime(admission)
    private fun key(id: String) = AcquisitionKey("shared", id, "main", 1)
    private class Handle(val closeAction: suspend () -> Boolean = { true }) : OwnedLivePlayback {
        var starts = 0
        override fun start() { starts++ }
        override suspend fun close() = closeAction()
    }
    @Test fun replacementCannotOpenUntilOldDecoderAndTransportHaveClosed() = runBlocking {
        val closing = CompletableDeferred<Unit>(); val closed = CompletableDeferred<Boolean>()
        val first = Handle { closing.complete(Unit); closed.await() }
        assertEquals(LiveOpenResult.OPENED, runtime.open(key("one"), 10, 20) { purpose ->
            assertEquals(PlaybackPurpose.LIVE_CHANNEL, purpose)
            assertFalse(purpose.allowsVodNetworkOptimizations)
            first
        })
        var opened = false
        val next = async { runtime.open(key("two"), 10, 20) { opened = true; Handle() } }
        closing.await()
        assertFalse(opened)
        assertEquals(1, admission.snapshot().decoders)
        assertEquals(30L, admission.snapshot().memoryBytes)
        assertEquals(1, admission.snapshot().upstreamsByAccount["shared"])
        closed.complete(true)
        assertEquals(LiveOpenResult.OPENED, next.await())
        assertTrue(opened)
        assertTrue(runtime.stop())
        assertEquals(0, admission.snapshot().consumers)
        assertTrue(admission.snapshot().upstreamsByAccount.isEmpty())
    }
    @Test fun failedClosureHoldsAllReservationsAndBlocksReplacement() = runBlocking {
        var canClose = false
        runtime.open(key("one"), 10, 20) { Handle { canClose } }
        assertEquals(LiveOpenResult.CLOSE_UNCONFIRMED, runtime.open(key("two"), 10, 20) { error("Must not construct") })
        assertEquals(1, admission.snapshot().consumers)
        assertNotNull(admission.snapshot().audioOwner)
        canClose = true
        assertTrue(runtime.stop())
        assertNull(admission.snapshot().audioOwner)
    }
    @Test fun cancelledSwitchStillClosesOldTransportWithoutOpeningNewOne() = runBlocking {
        val closing = CompletableDeferred<Unit>(); val closed = CompletableDeferred<Boolean>()
        runtime.open(key("one"), 10, 20) { Handle { closing.complete(Unit); closed.await() } }
        val next = launch { runtime.open(key("two"), 10, 20) { error("Cancelled request must not open") } }
        closing.await(); next.cancel(); closed.complete(true); next.join()
        assertEquals(0, admission.snapshot().consumers)
    }
    @Test fun sharingIsDeniedUntilTransportSharingExists() = runBlocking {
        val capture = admission.acquire(key("one"), 10, ConsumerReservation(LiveConsumerRole.RECORDING, 0, 0)) as LiveAdmissionResult.Admitted
        assertEquals(LiveOpenResult.SHARING_UNAVAILABLE, runtime.open(key("one"), 10, 20) { error("No second upstream") })
        assertEquals(1, admission.snapshot().consumers)
        assertEquals(0, admission.snapshot().decoders)
        admission.release(capture.lease)?.let(admission::completeClose)
        Unit
    }
    @Test fun startFailureClosesBeforeReturningCapacity() = runBlocking {
        var closed = false
        assertEquals(LiveOpenResult.FAILED, runtime.open(key("one"), 10, 20) { object : OwnedLivePlayback {
            override fun start() { error("Fixture start failure") }
            override suspend fun close(): Boolean { closed = true; return true }
        } })
        assertTrue(closed); assertEquals(0, admission.snapshot().consumers)
    }
    @Test fun accountLimitDeniesBeforeConstruction() = runBlocking {
        admission.acquire(key("capture"), 10, ConsumerReservation(LiveConsumerRole.RECORDING, 0, 0))
        assertEquals(LiveOpenResult.CAPACITY, runtime.open(key("one"), 10, 20) { error("No capacity") })
    }
    @Test fun groupStreamLimitAppliesToTheAccountBeforeAdmission() = runBlocking {
        admission.acquire(key("capture"), 10, ConsumerReservation(LiveConsumerRole.RECORDING, 0, 0))
        assertEquals(LiveOpenResult.CAPACITY, runtime.open(key("one"), 10, 20, maxUpstreams = 1) { error("No capacity") })
        assertEquals(LiveOpenResult.OPENED, runtime.open(key("one"), 10, 20, maxUpstreams = 2) { Handle() })
        assertEquals(2, admission.snapshot().upstreamsByAccount.values.single())
    }
    @Test fun oldScreenCannotStopTheNewOwner() = runBlocking {
        runtime.open(key("one"), 10, 20, "old-screen") { Handle() }
        runtime.open(key("two"), 10, 20, "new-screen") { Handle() }
        assertTrue(runtime.stop("old-screen"))
        assertEquals(1, admission.snapshot().consumers)
        assertTrue(runtime.stop("new-screen"))
        assertEquals(0, admission.snapshot().consumers)
    }
    @Test fun cancelledPreparationReturnsReservationWithoutOpeningMedia() = runBlocking {
        val preparing = CompletableDeferred<Unit>()
        val job = launch { runtime.open(key("one"), 10, 20) { preparing.complete(Unit); awaitCancellation() } }
        preparing.await(); job.cancelAndJoin()
        assertEquals(0, admission.snapshot().consumers)
        assertTrue(admission.snapshot().upstreamsByAccount.isEmpty())
    }
    @Test fun requestFenceCountsConnectingWorkAndRejectsLateLoads() {
        val fence = LiveRequestFence()
        val first = requireNotNull(fence.enter()); val second = requireNotNull(fence.enter())
        fence.stopAccepting()
        assertNull(fence.enter()); assertEquals(2, fence.active.value)
        fence.leave(first); fence.leave(first)
        assertEquals(1, fence.active.value)
        fence.leave(second); assertEquals(0, fence.active.value)
        assertNull(fence.enter())
    }
}
