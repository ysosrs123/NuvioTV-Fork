package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerLateVideoPolicyTest {

    private fun sample(rendered: Int, dropped: Int, playing: Boolean = true, baseline: Float? = null) =
        PlayerLateVideoPolicy.Sample(playing, rendered, dropped, baseline)

    private fun run(vararg samples: PlayerLateVideoPolicy.Sample): PlayerLateVideoPolicy.Decision {
        var state = PlayerLateVideoPolicy.State()
        var decision: PlayerLateVideoPolicy.Decision = PlayerLateVideoPolicy.Decision.Continue
        for (sample in samples) {
            val step = PlayerLateVideoPolicy.step(state, sample)
            state = step.state
            decision = step.decision
            if (decision != PlayerLateVideoPolicy.Decision.Continue) break
        }
        return decision
    }

    @Test
    fun `smooth playback is healthy and reports its frame rate`() {
        val decision = run(sample(24, 0), sample(24, 0), sample(24, 0))
        assertEquals(PlayerLateVideoPolicy.Decision.Healthy(24f), decision)
    }

    @Test
    fun `three seconds of mostly dropped frames asks for a resync`() {
        val decision = run(sample(5, 19), sample(5, 19), sample(5, 19))
        assertEquals(PlayerLateVideoPolicy.Decision.Resync, decision)
    }

    @Test
    fun `a short burst of drops is not enough`() {
        val decision = run(sample(5, 19), sample(5, 19), sample(24, 0), sample(24, 0), sample(24, 0))
        assertTrue(decision is PlayerLateVideoPolicy.Decision.Healthy)
    }

    @Test
    fun `a few drops next to a full frame rate are fine`() {
        assertFalse(PlayerLateVideoPolicy.isBad(sample(24, 6)))
        assertFalse(PlayerLateVideoPolicy.isBad(sample(2, 3)))
    }

    @Test
    fun `slow picture without counted drops is caught once a normal rate is known`() {
        val decision = run(
            sample(5, 0, baseline = 24f),
            sample(5, 0, baseline = 24f),
            sample(5, 0, baseline = 24f),
        )
        assertEquals(PlayerLateVideoPolicy.Decision.Resync, decision)
    }

    @Test
    fun `slow picture is not judged without a known normal rate`() {
        assertFalse(PlayerLateVideoPolicy.isBad(sample(5, 0)))
        assertFalse(PlayerLateVideoPolicy.isBad(sample(5, 0, baseline = 10f)))
    }

    @Test
    fun `paused or buffering time is never bad and restarts the count`() {
        val decision = run(
            sample(5, 19),
            sample(5, 19),
            sample(0, 0, playing = false),
            sample(5, 19),
            sample(5, 19),
            sample(24, 0),
            sample(24, 0),
            sample(24, 0),
        )
        assertTrue(decision is PlayerLateVideoPolicy.Decision.Healthy)
    }

    @Test
    fun `the watch ends on its own`() {
        val paused = Array(PlayerLateVideoPolicy.MAX_IDLE_SAMPLES) { sample(0, 0, playing = false) }
        assertEquals(PlayerLateVideoPolicy.Decision.Stop, run(*paused))
    }

    @Test
    fun `mixed seconds end the watch without a resync`() {
        val mixed = Array(PlayerLateVideoPolicy.MAX_SAMPLES) { i ->
            if (i % 2 == 0) sample(5, 19) else sample(24, 0)
        }
        assertEquals(PlayerLateVideoPolicy.Decision.Stop, run(*mixed))
    }

    @Test
    fun `a long pause does not use up the watch`() {
        val paused = Array(PlayerLateVideoPolicy.MAX_SAMPLES + 5) { sample(0, 0, playing = false) }
        val decision = run(*paused, sample(5, 19), sample(5, 19), sample(5, 19))
        assertEquals(PlayerLateVideoPolicy.Decision.Resync, decision)
    }

    @Test
    fun `only hardware decoders are watched`() {
        assertTrue(PlayerLateVideoPolicy.isHardwareDecoder("OMX.amlogic.hevc.decoder.awesome2"))
        assertTrue(PlayerLateVideoPolicy.isHardwareDecoder("c2.mtk.hevc.decoder"))
        assertFalse(PlayerLateVideoPolicy.isHardwareDecoder("c2.android.av1.decoder"))
        assertFalse(PlayerLateVideoPolicy.isHardwareDecoder("OMX.google.h264.decoder"))
        assertFalse(PlayerLateVideoPolicy.isHardwareDecoder("amstream_dves_hevc"))
        assertFalse(PlayerLateVideoPolicy.isHardwareDecoder(null))
    }
}
