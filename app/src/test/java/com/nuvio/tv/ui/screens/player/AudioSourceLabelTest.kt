package com.nuvio.tv.ui.screens.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioSourceLabelTest {
    private fun track(mime: String, label: String?) =
        Format.Builder().setSampleMimeType(mime).setLabel(label).build()

    @Test
    fun `a TrueHD track named Atmos shows as TrueHD Atmos`() {
        assertEquals("TrueHD Atmos", bitstreamAudioLabel(track(MimeTypes.AUDIO_TRUEHD, "TrueHD Atmos 7.1")))
    }

    @Test
    fun `plain TrueHD and other codecs keep the codec name`() {
        assertNull(bitstreamAudioLabel(track(MimeTypes.AUDIO_TRUEHD, "TrueHD 7.1")))
        assertNull(bitstreamAudioLabel(track(MimeTypes.AUDIO_AC3, "Atmos compatible")))
        assertNull(bitstreamAudioLabel(track(MimeTypes.AUDIO_TRUEHD, "Atmospheric mix")))
    }
}
