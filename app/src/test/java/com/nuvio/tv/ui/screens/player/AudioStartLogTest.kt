package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioStartLogTest {

    private fun state(
        effectControlSessionId: Int? = 41,
        outputTakes: Set<Int>? = setOf(C.ENCODING_AC3, C.ENCODING_E_AC3),
        mediaVolume: Int? = 12,
        mediaMuted: Boolean? = false
    ) = AudioStartState(
        routeKey = "type:hdmi_arc|name:soundbar",
        sessionId = 41,
        effectControlSessionId = effectControlSessionId,
        output = "E-AC-3 → Passthrough (E-AC-3) (48 kHz, 6ch, tunneled)",
        tunnelled = true,
        tunnellingAsked = true,
        offload = false,
        outputTakes = outputTakes,
        mediaVolume = mediaVolume,
        mediaVolumeMax = 15,
        mediaMuted = mediaMuted,
        nightMode = "OFF",
        dialogueBoost = "STEREO_TRACK",
        gainDb = 0
    )

    @Test
    fun lineNamesRouteSessionTunnelAndOutput() {
        val line = audioStartLine(state())
        assertEquals(
            "AUDIO_START: route=type:hdmi_arc|name:soundbar session=41 " +
                "output=E-AC-3 → Passthrough (E-AC-3) (48 kHz, 6ch, tunneled) tunnel=on tunnelAsked=on " +
                "offload=off outputTakes=[AC3,EAC3] mediaVolume=12/15 muted=no effectControl=open " +
                "appEffects=none night=OFF dialogue=STEREO_TRACK gainDb=0",
            line
        )
    }

    @Test
    fun unknownReadingsShowAsQuestionMarks() {
        val line = audioStartLine(state(outputTakes = null, mediaVolume = null, mediaMuted = null))
        assertTrue(line, line.contains(" outputTakes=? "))
        assertTrue(line, line.contains(" mediaVolume=? "))
        assertTrue(line, line.contains(" muted=? "))
    }

    @Test
    fun outputThatTakesNothingIsAnEmptyList() {
        assertTrue(audioStartLine(state(outputTakes = emptySet())).contains(" outputTakes=[] "))
    }

    @Test
    fun effectControlForAnotherSessionIsNamed() {
        assertEquals("open", effectControlText(sessionId = 41, effectControlSessionId = 41))
        assertEquals("none", effectControlText(sessionId = 41, effectControlSessionId = null))
        assertEquals("other-session(17)", effectControlText(sessionId = 41, effectControlSessionId = 17))
    }
}
