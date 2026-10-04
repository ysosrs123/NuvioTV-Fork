package com.nuvio.tv.ui.screens.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.*
import org.junit.Test

class SubtitleTrackFormatTest {
    @Test fun `decoded PGS exposes image controls instead of font controls`() {
        val format = Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES)
            .setCodecs("application/pgs").setLabel("English (SDH)").build()
        assertEquals("PGS", subtitleCodecName(format))
        assertTrue(isBitmapSubtitleCodec(subtitleCodecName(format)))
    }

    @Test fun `extraction decoding preserves text and authored ASS identities`() {
        for ((mime, name) in listOf(MimeTypes.APPLICATION_SUBRIP to "SRT",
            MimeTypes.TEXT_VTT to "VTT", MimeTypes.TEXT_SSA to "SSA",
            MimeTypes.APPLICATION_DVBSUBS to "DVB")) {
            val raw = Format.Builder().setSampleMimeType(mime).build()
            val decoded = raw.buildUpon().setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES)
                .setCodecs(mime).build()
            assertEquals(name, subtitleCodecName(raw))
            assertEquals(name, subtitleCodecName(decoded))
        }
    }

    @Test fun `labels do not guess format and comma separated codec metadata is supported`() {
        val unknown = Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES)
            .setLabel("English SDH").build()
        assertNull(subtitleCodecName(unknown))
        assertEquals("SRT", subtitleCodecName(unknown.buildUpon()
            .setCodecs("unknown, application/x-subrip ").build()))
    }
}
