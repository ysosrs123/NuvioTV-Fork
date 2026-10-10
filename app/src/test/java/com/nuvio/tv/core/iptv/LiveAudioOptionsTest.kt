package com.nuvio.tv.core.iptv

import org.junit.Assert.*
import org.junit.Test

class LiveAudioOptionsTest {
    @Test fun displayFollowsNuvioUnlessOverridden() {
        assertEquals(LiveDisplayPlan(LiveDisplayMatch.START, true),
            LiveAudioOptions.display(LiveFrameRateChoice.NUVIO, LiveResolutionChoice.NUVIO, LiveDisplayMatch.START, true))
        assertEquals(LiveDisplayPlan(LiveDisplayMatch.OFF, true),
            LiveAudioOptions.display(LiveFrameRateChoice.OFF, LiveResolutionChoice.NUVIO, LiveDisplayMatch.START_STOP, true))
        assertEquals(LiveDisplayPlan(LiveDisplayMatch.START_STOP, false),
            LiveAudioOptions.display(LiveFrameRateChoice.START_STOP, LiveResolutionChoice.OFF, LiveDisplayMatch.OFF, true))
        assertEquals(LiveDisplayPlan(LiveDisplayMatch.OFF, true),
            LiveAudioOptions.display(LiveFrameRateChoice.NUVIO, LiveResolutionChoice.ON, LiveDisplayMatch.OFF, false))
    }

    @Test fun passthroughOnlyOnTheMainPlayerAndOnlyWhenFollowingNuvio() {
        val main = LiveAudioOptions.audio(LivePassthroughChoice.NUVIO, false, LiveAudioDecoder.AUTOMATIC, true, primary = true, surfaceCorner = true)
        assertEquals(LiveAudioPlan(passthrough = true, tunnelling = false, preferAppDecoder = false, gain = true, surroundLift = true), main)
        assertFalse(LiveAudioOptions.audio(LivePassthroughChoice.OFF, false, LiveAudioDecoder.AUTOMATIC, true, true, true).passthrough)
        assertFalse(LiveAudioOptions.audio(LivePassthroughChoice.NUVIO, false, LiveAudioDecoder.AUTOMATIC, true, false, true).passthrough)
        assertTrue(LiveAudioOptions.audio(LivePassthroughChoice.NUVIO, false, LiveAudioDecoder.PREFER_APP, false, true, true).preferAppDecoder)
    }

    @Test fun tunnellingTurnsOffGainAndNeedsTheMainPlayerHardwareAudioAndASurface() {
        val tunnel = LiveAudioOptions.audio(LivePassthroughChoice.NUVIO, true, LiveAudioDecoder.AUTOMATIC, true, primary = true, surfaceCorner = true)
        assertTrue(tunnel.tunnelling); assertFalse(tunnel.gain); assertFalse(tunnel.surroundLift)
        assertFalse(LiveAudioOptions.audio(LivePassthroughChoice.NUVIO, true, LiveAudioDecoder.AUTOMATIC, true, primary = false, surfaceCorner = true).tunnelling)
        assertFalse(LiveAudioOptions.audio(LivePassthroughChoice.NUVIO, true, LiveAudioDecoder.PREFER_APP, true, primary = true, surfaceCorner = true).tunnelling)
        val texture = LiveAudioOptions.audio(LivePassthroughChoice.NUVIO, true, LiveAudioDecoder.AUTOMATIC, true, primary = true, surfaceCorner = false)
        assertFalse(texture.tunnelling); assertTrue(texture.gain); assertTrue(texture.surroundLift)
        assertFalse(LiveAudioOptions.audio(LivePassthroughChoice.NUVIO, false, LiveAudioDecoder.AUTOMATIC, false, true, true).surroundLift)
    }

    @Test fun languagesResolveDeviceDefaultAndChosen() {
        assertEquals(emptyList<String>(), LiveAudioOptions.languages(LiveAudioOptions.LANGUAGE_DEFAULT, listOf("en")))
        assertEquals(emptyList<String>(), LiveAudioOptions.languages(null, listOf("en")))
        assertEquals(listOf("en", "nl"), LiveAudioOptions.languages(LiveAudioOptions.LANGUAGE_DEVICE, listOf("en", " NL", "en", "")))
        assertEquals(listOf("de"), LiveAudioOptions.languages("DE", listOf("en")))
    }

    @Test fun surroundPicksMostChannelsInTheSameLanguage() {
        val tracks = listOf(LiveAudioCandidate(2, "en", true), LiveAudioCandidate(6, "en", true), LiveAudioCandidate(8, "de", true))
        assertEquals(1, LiveAudioOptions.surround(tracks, 0))
        assertEquals(2, LiveAudioOptions.surround(tracks, 2))
        assertEquals(1, LiveAudioOptions.surround(tracks, 1))
    }

    @Test fun surroundFallsBackToStereoAndSkipsUnplayableTracks() {
        val tracks = listOf(LiveAudioCandidate(2, "en", true), LiveAudioCandidate(6, "en", false))
        assertEquals(0, LiveAudioOptions.surround(tracks, 0))
        assertNull(LiveAudioOptions.surround(tracks, null))
        assertEquals(5, LiveAudioOptions.surround(tracks, 5))
        assertEquals(0, LiveAudioOptions.surround(listOf(LiveAudioCandidate(2, null, true), LiveAudioCandidate(2, null, true)), 0))
    }

    @Test fun surroundTreatsUnknownLanguagesAsMatching() {
        val tracks = listOf(LiveAudioCandidate(2, null, true), LiveAudioCandidate(6, "en-AU", true), LiveAudioCandidate(6, "und", true))
        assertEquals(1, LiveAudioOptions.surround(tracks, 0))
        assertEquals(2, LiveAudioOptions.surround(listOf(LiveAudioCandidate(2, "en", true), LiveAudioCandidate(6, "fr", true), LiveAudioCandidate(6, "und", true)), 0))
        assertEquals(1, LiveAudioOptions.surround(listOf(LiveAudioCandidate(2, "en_GB", true), LiveAudioCandidate(6, "en", true)), 0))
    }
}
