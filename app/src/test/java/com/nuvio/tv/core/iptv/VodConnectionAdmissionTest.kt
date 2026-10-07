package com.nuvio.tv.core.iptv

import org.junit.Assert.*
import org.junit.Test

class VodConnectionAdmissionTest {
    private val vod = ConsumerReservation(LiveConsumerRole.VOD, 0, 0)
    private val viewer = ConsumerReservation(LiveConsumerRole.VIEWER, 1, 100)
    private val recorder = ConsumerReservation(LiveConsumerRole.RECORDING, 0, 20)

    @Test fun aPlayingMovieTakesTheAccountsOnlyConnection() {
        val g = LiveSessionAdmission(DeviceAdmissionLimits(1, 1000, 0))
        g.setAccountLimit("1:acc", 1)
        val movie = g.acquire(AcquisitionKey("1:acc", "vod:a", "movie", 0), 0, vod) as LiveAdmissionResult.Admitted
        assertTrue(movie.openUpstream)
        assertEquals(mapOf("1:acc" to 1), g.snapshot().upstreamsByAccount)
        assertEquals(0, g.snapshot().decoders)
        assertEquals(LiveAdmissionResult.Denied(AdmissionDenial.ACCOUNT_LIMIT), g.acquire(AcquisitionKey("1:acc", "channel", "main", 0), 16, viewer))
        assertEquals(LiveAdmissionResult.Denied(AdmissionDenial.ACCOUNT_LIMIT), g.acquire(AcquisitionKey("1:acc", "channel", "rec", 0), 16, recorder))
        assertEquals(LiveAdmissionResult.Denied(AdmissionDenial.ACCOUNT_LIMIT), g.acquire(AcquisitionKey("1:acc", "vod:b", "movie", 0), 0, vod))
        assertFalse(g.selectAudioOwner(movie.lease))
        g.release(movie.lease)?.let(g::completeClose)
        assertEquals(emptyMap<String, Int>(), g.snapshot().upstreamsByAccount)
        assertTrue(g.acquire(AcquisitionKey("1:acc", "channel", "main", 0), 16, viewer) is LiveAdmissionResult.Admitted)
    }

    @Test fun otherAccountsAndDeviceLimitsAreUnaffected() {
        val g = LiveSessionAdmission(DeviceAdmissionLimits(1, 1000, 0))
        g.setAccountLimit("1:acc", 2)
        g.acquire(AcquisitionKey("1:acc", "vod:a", "episode", 0), 0, vod) as LiveAdmissionResult.Admitted
        assertTrue(g.acquire(AcquisitionKey("1:acc", "channel", "main", 0), 16, viewer) is LiveAdmissionResult.Admitted)
        assertTrue(g.acquire(AcquisitionKey("1:other", "vod:b", "movie", 0), 0, vod) is LiveAdmissionResult.Admitted)
        assertEquals(mapOf("1:acc" to 2, "1:other" to 1), g.snapshot().upstreamsByAccount)
    }

    @Test(expected = IllegalArgumentException::class) fun vodNeverReservesADecoderSlot() {
        ConsumerReservation(LiveConsumerRole.VOD, 1, 0)
    }
}
