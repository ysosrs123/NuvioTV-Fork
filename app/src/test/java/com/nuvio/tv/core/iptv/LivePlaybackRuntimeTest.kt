package com.nuvio.tv.core.iptv

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class LivePlaybackRuntimeTest {
    private val admission = LiveSessionAdmission(DeviceAdmissionLimits(2, 1000, 0))
    private val runtime = LivePlaybackRuntime(admission, closeWaitMs = 0)
    private fun key(id: String) = AcquisitionKey("shared", id, "main", 1)
    private class Handle(override val decoderReleased: Boolean = false, val closeAction: suspend () -> Boolean = { true }) : OwnedLivePlayback {
        var starts = 0
        var interrupts = 0
        var abandoned = 0
        override fun start() { starts++ }
        override suspend fun close() = closeAction()
        override fun interrupt() { interrupts++ }
        override fun abandon() { abandoned++ }
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
    @Test fun closeIsRetriedBrieflyBeforeTheSwitchIsRefused() = runBlocking {
        val patient = LivePlaybackRuntime(admission, closeWaitMs = 2_000)
        var attempts = 0
        patient.open(key("one"), 10, 20) { Handle { ++attempts >= 3 } }
        assertEquals(LiveOpenResult.OPENED, patient.open(key("two"), 10, 20) { Handle() })
        assertEquals(3, attempts)
        assertEquals(1, admission.snapshot().consumers)
    }
    @Test fun closeThatNeverConfirmsIsRefusedAfterTheRetryWindow() = runBlocking {
        val patient = LivePlaybackRuntime(admission, closeWaitMs = 300)
        var attempts = 0
        patient.open(key("one"), 10, 20) { Handle { attempts++; false } }
        val started = System.nanoTime()
        assertEquals(LiveOpenResult.CLOSE_UNCONFIRMED, patient.open(key("two"), 10, 20) { error("Must not construct") })
        assertTrue((System.nanoTime() - started) / 1_000_000 >= 300)
        assertTrue(attempts > 2)
        assertEquals(1, admission.snapshot().consumers)
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
    @Test fun switchWaitsForASlowCloseInsteadOfRefusing() = runBlocking {
        val patient = LivePlaybackRuntime(admission, closeWaitMs = 1_500)
        val started = System.nanoTime()
        patient.open(key("one"), 10, 20, maxUpstreams = 1) { Handle { (System.nanoTime() - started) / 1_000_000 >= 900 } }
        assertEquals(LiveOpenResult.OPENED, patient.open(key("two"), 10, 20, maxUpstreams = 1) { Handle() })
        assertEquals(1, admission.snapshot().consumers)
    }
    @Test fun singleConnectionProviderNeverOverlapsAfterTheWait() = runBlocking {
        val patient = LivePlaybackRuntime(admission, closeWaitMs = 200)
        val first = Handle(true) { false }
        patient.open(key("one"), 10, 20, maxUpstreams = 1) { first }
        assertEquals(LiveOpenResult.CLOSE_UNCONFIRMED, patient.open(key("two"), 10, 20, maxUpstreams = 1) { error("Must not construct") })
        assertEquals(0, first.abandoned)
        assertEquals(1, admission.snapshot().consumers)
    }
    @Test fun multiConnectionProviderProceedsOnceTheDecoderIsFree() = runBlocking {
        val patient = LivePlaybackRuntime(admission, closeWaitMs = 200)
        val first = Handle(true) { false }
        patient.open(key("one"), 10, 20, maxUpstreams = 2) { first }
        assertEquals(LiveOpenResult.OPENED, patient.open(key("two"), 10, 20, maxUpstreams = 2) { Handle() })
        assertEquals(1, first.abandoned)
        assertEquals(1, admission.snapshot().consumers)
        assertEquals(1, admission.snapshot().decoders)
    }
    @Test fun busyDecoderBlocksTheSwitchWhateverTheLimit() = runBlocking {
        val patient = LivePlaybackRuntime(admission, closeWaitMs = 200)
        patient.open(key("one"), 10, 20, maxUpstreams = 4) { Handle(false) { false } }
        assertEquals(LiveOpenResult.CLOSE_UNCONFIRMED, patient.open(key("two"), 10, 20, maxUpstreams = 4) { error("Must not construct") })
    }
    @Test fun otherProviderMayOpenWhileTheOldConnectionsDrain() = runBlocking {
        val patient = LivePlaybackRuntime(admission, closeWaitMs = 200)
        patient.open(key("one"), 10, 20, maxUpstreams = 1) { Handle(true) { false } }
        assertEquals(LiveOpenResult.OPENED, patient.open(AcquisitionKey("other", "two", "main", 1), 10, 20, maxUpstreams = 1) { Handle() })
        assertNull(admission.snapshot().upstreamsByAccount["shared"])
    }
    @Test fun interruptReachesOnlyTheOwnersPlayback() = runBlocking {
        val first = Handle()
        runtime.open(key("one"), 10, 20, "screen") { first }
        runtime.interrupt("other")
        assertEquals(0, first.interrupts)
        runtime.interrupt("screen")
        assertEquals(1, first.interrupts)
        assertTrue(runtime.stop("screen"))
    }
    @Test fun overlapRules() {
        assertFalse(LiveSwitchOverlap.allowed("a", "a", 1, true))
        assertTrue(LiveSwitchOverlap.allowed("a", "a", 2, true))
        assertTrue(LiveSwitchOverlap.allowed("a", "a", null, true))
        assertTrue(LiveSwitchOverlap.allowed("a", "b", 1, true))
        assertFalse(LiveSwitchOverlap.allowed("a", "b", 4, false))
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
