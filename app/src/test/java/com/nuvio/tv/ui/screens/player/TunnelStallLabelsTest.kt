package com.nuvio.tv.ui.screens.player

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TunnelStallLabelsTest {

    @Test
    fun `known formats come in a fixed order with PCM last`() {
        val classes = setOf("pcm", MimeTypes.AUDIO_TRUEHD, "audio/eac3", MimeTypes.AUDIO_AC3)
        assertEquals(listOf("AC-3", "E-AC-3", "TrueHD", "PCM"), PlayerTunnelAvSyncPolicy.memoLabels(classes))
    }

    @Test
    fun `dts family and atmos variants get their own names`() {
        val classes = setOf(MimeTypes.AUDIO_DTS_X, MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_DTS_EXPRESS, MimeTypes.AUDIO_E_AC3_JOC)
        assertEquals(
            listOf("E-AC-3 JOC", "DTS-HD", "DTS Express", "DTS:X"),
            PlayerTunnelAvSyncPolicy.memoLabels(classes)
        )
    }

    @Test
    fun `unknown classes are shortened and blanks dropped`() {
        assertEquals(listOf("X-WEIRD", "PCM"), PlayerTunnelAvSyncPolicy.memoLabels(setOf("audio/x-weird;foo=1", " ", "pcm")))
        assertEquals(emptyList<String>(), PlayerTunnelAvSyncPolicy.memoLabels(emptySet()))
    }

    @Test
    fun `diagnostics line names the sound types and is absent when nothing is remembered`() {
        assertEquals(
            "Tunnel off after a stall: E-AC-3, PCM",
            AudioCapabilityReport.tunnelStallLine(setOf("pcm", "audio/eac3"))
        )
        assertNull(AudioCapabilityReport.tunnelStallLine(emptySet()))
    }
}
