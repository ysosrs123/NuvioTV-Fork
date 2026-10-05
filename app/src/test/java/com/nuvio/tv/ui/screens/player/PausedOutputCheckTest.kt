package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PausedOutputCheckTest {

    private fun sample(audioUs: Long?, frames: Int?, trackId: Int = 1) =
        PausedOutputCheck.Sample(audioUs?.let { AudioOutputTimestamp(trackId, it) }, frames)

    @Test
    fun `output that stands still is paused`() {
        val result = PausedOutputCheck.evaluate(sample(5_000_000L, 240), sample(5_000_000L, 240))
        assertFalse(result.stillPlaying)
        assertEquals(0L, result.audioAdvancedMs)
        assertEquals(0, result.framesAdvanced)
    }

    @Test
    fun `audio that runs on at normal speed is still playing`() {
        assertTrue(PausedOutputCheck.evaluate(sample(5_000_000L, 240), sample(5_400_000L, 240)).stillPlaying)
    }

    @Test
    fun `a short audio tail after pause does not count`() {
        assertFalse(PausedOutputCheck.evaluate(sample(5_000_000L, 240), sample(5_150_000L, 240)).stillPlaying)
    }

    @Test
    fun `picture that keeps advancing is still playing`() {
        val result = PausedOutputCheck.evaluate(sample(null, 240), sample(null, 242))
        assertTrue(result.stillPlaying)
        assertNull(result.audioAdvancedMs)
    }

    @Test
    fun `one more frame after pause does not count`() {
        assertFalse(PausedOutputCheck.evaluate(sample(5_000_000L, 240), sample(5_000_000L, 241)).stillPlaying)
    }

    @Test
    fun `nothing readable is never treated as playing`() {
        assertFalse(PausedOutputCheck.evaluate(sample(null, null), sample(900_000L, 300)).stillPlaying)
        assertFalse(PausedOutputCheck.evaluate(sample(0L, 0), sample(null, null)).stillPlaying)
    }

    @Test
    fun `values that went backwards are not playing`() {
        assertFalse(PausedOutputCheck.evaluate(sample(5_000_000L, 240), sample(0L, 0)).stillPlaying)
    }

    @Test
    fun `timestamps of two different tracks are not compared`() {
        val result = PausedOutputCheck.evaluate(sample(0L, 240, trackId = 1), sample(900_000L, 240, trackId = 2))
        assertFalse(result.stillPlaying)
        assertNull(result.audioAdvancedMs)
    }
}
