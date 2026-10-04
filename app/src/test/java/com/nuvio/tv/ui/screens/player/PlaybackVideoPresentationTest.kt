package com.nuvio.tv.ui.screens.player

import org.junit.Assert.*
import org.junit.Test

class PlaybackVideoPresentationTest {
    private fun mode(tunnel: Boolean = false, decoder: String? = "c2.android.hevc.decoder", selected: Boolean = true,
                     configured: Boolean = true, counters: Boolean = true) =
        PlaybackVideoPresentation.resolve(selected, configured, tunnel, decoder, counters)

    @Test fun `actual untunnelled codec supports frame and cadence measurements`() {
        val m = mode(); assertEquals(PlaybackVideoPresentation.MEDIA_CODEC, m)
        assertTrue(m.supportsFrameLead); assertTrue(m.supportsCadenceJudgement); assertTrue(m.supportsDropCounters)
    }
    @Test fun `applied tunneling suppresses offset and cadence judgement`() {
        val m = mode(tunnel = true); assertEquals(PlaybackVideoPresentation.TUNNELLED, m)
        assertFalse(m.supportsFrameLead); assertFalse(m.supportsCadenceJudgement); assertTrue(m.supportsDropCounters)
    }
    @Test fun `native decoder counters do not imply supported output measurements`() {
        for (name in listOf("amstream_dves_hevc", "amstream_dves_hevc.sideband")) {
            val m = mode(decoder = name)
            assertEquals(PlaybackVideoPresentation.NATIVE, m); assertFalse(m.supportsDropCounters)
            assertFalse(m.supportsFrameLead); assertFalse(m.supportsCadenceJudgement)
        }
    }
    @Test fun `missing selected or configured video does not use saved tunnel state`() {
        for (m in listOf(mode(tunnel = true, selected = false), mode(tunnel = true, configured = false), mode(decoder = null)))
            assertEquals(PlaybackVideoPresentation.UNCONFIGURED, m)
    }
    @Test fun `unknown renderer is not treated as a codec because counters exist`() {
        assertEquals(PlaybackVideoPresentation.UNSUPPORTED, mode(decoder = "custom-video"))
    }
    @Test fun `missing current counters cannot display stale codec offsets`() {
        assertEquals(PlaybackVideoPresentation.UNCONFIGURED, mode(counters = false))
    }
    @Test fun `native to codec and tunnel fallback recompute support without latching`() {
        assertFalse(mode(decoder = "amstream_dves_hevc").supportsFrameLead)
        assertTrue(mode(decoder = "OMX.amlogic.hevc.decoder").supportsFrameLead)
        assertFalse(mode(tunnel = true).supportsFrameLead)
        assertTrue(mode(tunnel = false).supportsFrameLead)
    }
    @Test fun `applied tunneling remains explicit without supported counters`() {
        for (m in listOf(mode(tunnel = true, decoder = "custom-video"),
            mode(tunnel = true, decoder = null), mode(tunnel = true, counters = false))) {
            assertTrue(m.isTunnelled)
            assertFalse(m.supportsDropCounters)
            assertFalse(m.supportsFrameLead)
            assertFalse(m.supportsCadenceJudgement)
        }
    }
}
