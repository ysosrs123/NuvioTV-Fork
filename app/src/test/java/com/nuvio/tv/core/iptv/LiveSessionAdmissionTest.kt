package com.nuvio.tv.core.iptv

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LiveSessionAdmissionTest {
    private val key = AcquisitionKey("account", "channel", "main", 1)
    private val viewer = ConsumerReservation(LiveConsumerRole.VIEWER, 1, 100)
    private val recorder = ConsumerReservation(LiveConsumerRole.RECORDING, 0, 20, 500)
    private fun governor() = LiveSessionAdmission(DeviceAdmissionLimits(2, 1000, 2000))
    private fun admitted(result: LiveAdmissionResult) = (result as LiveAdmissionResult.Admitted).lease

    @Test fun watchAndRecordShareUpstreamButHaveIndependentLifetimes() {
        val g = governor()
        val watch = g.acquire(key, 50, viewer) as LiveAdmissionResult.Admitted
        val record = g.acquire(key, 50, recorder) as LiveAdmissionResult.Admitted
        assertTrue(watch.openUpstream); assertFalse(record.openUpstream)
        assertEquals(mapOf("account" to 1), g.snapshot().upstreamsByAccount)
        assertEquals(170, g.snapshot().memoryBytes)
        assertNull(g.release(watch.lease)); assertEquals(0, g.snapshot().decoders)
        assertEquals(70, g.snapshot().memoryBytes)
        assertEquals(1, g.snapshot().consumers)
        val close = g.release(record.lease)!!
        assertEquals(50, g.snapshot().memoryBytes)
        assertEquals(1, g.snapshot().closingAcquisitions)
        assertTrue(g.completeClose(close)); assertEquals(0, g.snapshot().memoryBytes)
    }
    @Test fun unknownQuotaIsOneEvenAcrossAliasesAndWhileClosing() {
        val g = governor()
        val first = admitted(g.acquire(key, 50, viewer))
        val close = g.release(first)!!
        assertEquals(LiveAdmissionResult.Denied(AdmissionDenial.ACCOUNT_LIMIT), g.acquire(key.copy(channelId = "other"), 50, viewer))
        assertEquals(LiveAdmissionResult.Denied(AdmissionDenial.ACQUISITION_CLOSING), g.acquire(key, 50, viewer))
        g.completeClose(close)
        assertTrue(g.acquire(key.copy(channelId = "other"), 50, viewer) is LiveAdmissionResult.Admitted)
    }
    @Test fun staleCloseCannotReleaseNewGeneration() {
        val g = governor()
        val old = admitted(g.acquire(key, 50, viewer))
        val ticket = g.release(old)!!
        assertTrue(g.completeClose(ticket))
        val fresh = admitted(g.acquire(key, 50, viewer))
        assertFalse(g.completeClose(ticket)); assertNull(g.release(old))
        assertNotEquals(old.acquisitionId, fresh.acquisitionId)
        assertEquals(1, g.snapshot().consumers)
    }
    @Test fun mutedTileReleaseCannotClearAnotherOwnersAudioOrDisplay() {
        val g = governor(); g.setAccountLimit("account", 2)
        val a = admitted(g.acquire(key, 50, viewer))
        val b = admitted(g.acquire(key.copy(channelId = "b"), 50, viewer))
        assertTrue(g.selectAudioOwner(a)); assertTrue(g.selectDisplayOwner(a))
        g.release(b)
        assertEquals(a.id, g.snapshot().audioOwner); assertEquals(a.id, g.snapshot().displayOwner)
        assertFalse(g.selectAudioOwner(b))
    }
    @Test fun captureOnlyCannotBecomeAudioOrDisplayOwner() {
        val g = governor(); val recording = admitted(g.acquire(key, 50, recorder))
        assertFalse(g.selectAudioOwner(recording)); assertFalse(g.selectDisplayOwner(recording))
        assertEquals(0, g.snapshot().decoders)
    }
    @Test fun limitsRejectBeforeMutatingReservations() {
        val g = governor(); g.setAccountLimit("account", 5)
        admitted(g.acquire(key, 50, viewer))
        val before = g.snapshot()
        assertEquals(LiveAdmissionResult.Denied(AdmissionDenial.DEVICE_DECODERS), g.acquire(key.copy(channelId = "b"), 50, viewer.copy(decoders = 2)))
        assertEquals(LiveAdmissionResult.Denied(AdmissionDenial.DEVICE_MEMORY), g.acquire(key.copy(channelId = "b"), Long.MAX_VALUE, recorder))
        assertEquals(LiveAdmissionResult.Denied(AdmissionDenial.DEVICE_STORAGE), g.acquire(key, 50, recorder.copy(storageBytes = 2001)))
        assertEquals(before, g.snapshot())
    }
    @Test fun loweringAccountLimitAllowsExistingSharingButNotNewUpstream() {
        val g = governor(); g.setAccountLimit("account", 2)
        admitted(g.acquire(key, 50, viewer))
        admitted(g.acquire(key.copy(channelId = "b"), 50, viewer))
        g.setAccountLimit("account", 1)
        assertTrue(g.acquire(key, 50, recorder) is LiveAdmissionResult.Admitted)
        assertEquals(LiveAdmissionResult.Denied(AdmissionDenial.ACCOUNT_LIMIT), g.acquire(key.copy(channelId = "c"), 50, recorder))
    }
    @Test fun concurrentAdmissionsCannotOversubscribeUnknownAccount() {
        val g = governor()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val futures = (1..32).map { index -> pool.submit<LiveAdmissionResult> {
                start.await(); g.acquire(key.copy(channelId = index.toString()), 50, recorder)
            } }
            start.countDown()
            assertEquals(1, futures.map { it.get(5, TimeUnit.SECONDS) }.count { it is LiveAdmissionResult.Admitted })
            assertEquals(1, g.snapshot().consumers)
        } finally { pool.shutdownNow() }
    }
    @Test fun concurrentSharingKeepsOnlyOneUpstream() {
        val g = LiveSessionAdmission(DeviceAdmissionLimits(1, 10000, 50000))
        val start = CountDownLatch(1); val pool = Executors.newFixedThreadPool(8)
        try {
            val futures = (1..32).map { pool.submit<LiveAdmissionResult> { start.await(); g.acquire(key, 50, recorder) } }
            start.countDown()
            val results = futures.map { it.get(5, TimeUnit.SECONDS) as LiveAdmissionResult.Admitted }
            assertEquals(1, results.count { it.openUpstream })
            assertEquals(32, g.snapshot().consumers)
            val close = results.mapNotNull { g.release(it.lease) }.single()
            assertTrue(g.completeClose(close)); assertEquals(0, g.snapshot().consumers)
        } finally { pool.shutdownNow() }
    }
    @Test fun recordingAndLiveIntentDenyAllVodAuxiliaryWork() {
        for (purpose in PlaybackPurpose.entries) {
            assertEquals(purpose == PlaybackPurpose.VOD, purpose.allowsVodNetworkOptimizations)
            assertEquals(purpose == PlaybackPurpose.VOD, purpose.allowsUpstreamThumbnails)
        }
    }

    @Test fun resizeMovesASoleViewerToSmallerBudgetsAndRefusesGrowthPastTheLimit() {
        val g = governor()
        val lease = admitted(g.acquire(key, 400, ConsumerReservation(LiveConsumerRole.VIEWER, 1, 500)))
        assertTrue(g.resize(lease, 50, viewer))
        assertEquals(150, g.snapshot().memoryBytes)
        assertEquals(1, g.snapshot().decoders)
        assertFalse(g.resize(lease, 600, ConsumerReservation(LiveConsumerRole.VIEWER, 1, 500)))
        assertEquals(150, g.snapshot().memoryBytes)
        admitted(g.acquire(key, 50, recorder))
        assertFalse(g.resize(lease, 50, ConsumerReservation(LiveConsumerRole.VIEWER, 1, 60)))
        g.release(lease)?.let(g::completeClose)
        assertFalse(g.resize(lease, 50, viewer))
    }
}
