package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.ui.screens.player.RecoveryRouteCheck.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryRouteCheckTest {

    private val tv = "type:hdmi|name:sony_tv"
    private val tvPlain = "type:hdmi|name:hdmi"
    private val avr = "type:hdmi_arc|name:denon"
    private val headphones = "type:bluetooth_a2dp|name:buds"

    private fun decide(
        stable: String?,
        atError: String?,
        now: String?,
        waited: Boolean = false,
        pcm: Boolean = true
    ) = RecoveryRouteCheck.decide(stable, atError, now, waited, pcm)

    @Test
    fun hdmiBlinkAtTheErrorIsNotAChange() {
        assertEquals(Action.RETRY, decide(stable = tv, atError = null, now = tv, pcm = false))
        assertEquals(Action.RETRY, decide(stable = null, atError = null, now = tv, pcm = false))
    }

    @Test
    fun hdmiComingBackUnderAnotherNameIsTheSameOutput() {
        assertEquals(Action.RETRY, decide(stable = tv, atError = null, now = tvPlain, pcm = false))
        assertEquals(Action.RETRY, decide(stable = tv, atError = headphones, now = avr, pcm = false))
    }

    @Test
    fun comparesWithTheOutputBeforeTheFailureNotTheOneAtTheError() {
        assertEquals(Action.RETRY, decide(stable = tv, atError = headphones, now = tv, pcm = false))
    }

    @Test
    fun unchangedOutputRetries() {
        assertEquals(Action.RETRY, decide(stable = tv, atError = tv, now = tv, pcm = false))
        assertEquals(Action.RETRY, decide(stable = headphones, atError = headphones, now = headphones, pcm = false))
        assertEquals(Action.RETRY, decide(stable = tv, atError = headphones, now = headphones, pcm = false))
    }

    @Test
    fun noReadingAfterTheWaitDoesNotStopTheRetry() {
        assertEquals(Action.RETRY, decide(stable = tv, atError = tv, now = null, pcm = false))
    }

    @Test
    fun waitingForTheOutputAcceptsWhatCameBack() {
        assertEquals(Action.RETRY, decide(stable = tv, atError = null, now = headphones, waited = true, pcm = false))
    }

    @Test
    fun realChangeToAnotherDeviceDecodesToPcm() {
        assertEquals(Action.RETRY_PCM, decide(stable = tv, atError = tv, now = headphones, pcm = true))
        assertEquals(Action.RETRY_PCM, decide(stable = tv, atError = null, now = headphones, pcm = true))
    }

    @Test
    fun hdmiAfterARouteSeenOnlyDuringTheDropKeepsPassthrough() {
        val speaker = "type:built_in_speaker|name:built_in_speaker"
        assertEquals(Action.RETRY, decide(stable = speaker, atError = speaker, now = tv, pcm = true))
        assertEquals(Action.RETRY, decide(stable = headphones, atError = null, now = avr, pcm = true))
    }

    @Test
    fun realChangeWithoutPcmFallbackStops() {
        assertEquals(Action.STOP, decide(stable = tv, atError = tv, now = headphones, pcm = false))
    }

    @Test
    fun sameOutputRules() {
        assertTrue(RecoveryRouteCheck.sameOutput(null, tv))
        assertTrue(RecoveryRouteCheck.sameOutput(tv, null))
        assertTrue(RecoveryRouteCheck.sameOutput(tv, avr))
        assertFalse(RecoveryRouteCheck.sameOutput(tv, headphones))
        assertEquals(tv, RecoveryRouteCheck.referenceRoute(tv, headphones))
        assertEquals(headphones, RecoveryRouteCheck.referenceRoute(null, headphones))
    }
}
