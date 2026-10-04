package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.Format
import org.junit.Assert.*
import android.graphics.Bitmap
import androidx.media3.common.text.Cue
import com.nuvio.tv.data.local.SubtitleStyleSettings
import io.mockk.mockk
import org.junit.Test

class SubtitlePresentationTest {
    @Test fun `image scaling ignores text size and never accumulates on restyling`() {
        val bitmap = mockk<Bitmap>(relaxed = true)
        val original = Cue.Builder().setBitmap(bitmap).setSize(.6f).setBitmapHeight(.1f)
            .setPosition(.2f).setPositionAnchor(Cue.ANCHOR_TYPE_START)
            .setLine(.8f, Cue.LINE_TYPE_FRACTION).setLineAnchor(Cue.ANCHOR_TYPE_START).build()
        val presentation = SubtitlePresentation()
        val style = SubtitleStyleSettings(size = 150, bitmapSize = 60)
        val smaller = presentation.apply(original, style, false, false)
        assertEquals(.36f, smaller.size, .00001f)
        assertEquals(.06f, smaller.bitmapHeight, .00001f)
        assertEquals(.32f, smaller.position, .00001f)
        assertEquals(.84f, smaller.line, .00001f)
        val reapplied = presentation.apply(smaller, style.copy(size = 50), false, false)
        assertEquals(.36f, reapplied.size, .00001f)
        val restored = presentation.apply(reapplied, style.copy(bitmapSize = 100), false, false)
        assertEquals(.6f, restored.size, .00001f)
        assertEquals(.2f, restored.position, .00001f)
        val text = Cue.Builder().setText("English text").build()
        assertSame(text, presentation.apply(text, style, false, false))
    }

    @Test fun `bitmap detection uses codec not SDH label`() {
        for (codec in listOf("PGS", "application/pgs", "VobSub", "DVD_SUBTITLE", "DVB subtitles", "S_HDMV/PGS", "XSUB")) {
            assertTrue(codec, isBitmapSubtitleCodec(codec))
        }
        for (codec in listOf(null, "", "English SDH", "ASS", "SSA", "SRT", "WebVTT", "application/x-subrip")) {
            assertFalse(codec, isBitmapSubtitleCodec(codec))
        }
    }

    @Test fun `PQ in HEVC SPS enables dimming without container colour metadata`() {
        // 64x64 synthetic black frame, x265 BT.2020/PQ; only its Annex-B SPS retained.
        val hex = "0000000142010101600000030090000003000003001ea020810596566924caf016a12201208000000300800000030084"
        val sps = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val format = Format.Builder().setSampleMimeType("video/hevc")
            .setInitializationData(listOf(sps)).build()
        assertNull(format.colorInfo)
        assertTrue(isHdrVideoFormat(format))
        // A subsequent unknown source cannot inherit the previous source's HDR state.
        assertFalse(isHdrVideoFormat(Format.Builder().setSampleMimeType("video/hevc").build()))
        assertFalse(isHdrVideoFormat(null))
    }
    @Test fun `only detected HDR transfers or DV enable dimming`() {
        assertTrue(isHdrVideoFormat("video/hevc", C.COLOR_TRANSFER_ST2084))
        assertTrue(isHdrVideoFormat("video/hevc", C.COLOR_TRANSFER_HLG))
        assertTrue(isHdrVideoFormat("video/dolby-vision", null))
        assertFalse(isHdrVideoFormat("video/hevc", C.COLOR_TRANSFER_SDR))
        assertFalse(isHdrVideoFormat(null, null))
    }
    @Test fun `bright white is capped without making it transparent`() {
        assertEquals(0xFFB4B4B4.toInt(), capSubtitleHighlight(0xFFFFFFFF.toInt()))
    }
    @Test fun `already dim or translucent subtitles retain exact pixels`() {
        for (color in listOf(0xFF777777.toInt(), 0xFF000000.toInt(), 0x80FFFFFF.toInt(), 0x00FFFFFF)) {
            assertEquals(color, capSubtitleHighlight(color))
        }
    }
    @Test fun `highlight capping preserves hue and is idempotent`() {
        val yellow = capSubtitleHighlight(0xFFFFFF00.toInt())
        assertEquals(0, yellow and 255)
        assertEquals((yellow ushr 16) and 255, (yellow ushr 8) and 255)
        assertEquals(yellow, capSubtitleHighlight(yellow))
        assertEquals(255, yellow ushr 24)
    }
    @Test fun `bitmap resizing retains centred text and bottom baseline`() {
        assertEquals(.35f, resizedSubtitleAnchor(.2f, .6f, .3f, 0, false), .00001f)
        assertEquals(.5f, resizedSubtitleAnchor(.5f, .6f, .3f, 1, false), .00001f)
        assertEquals(.65f, resizedSubtitleAnchor(.8f, .6f, .3f, 2, false), .00001f)
        assertEquals(.85f, resizedSubtitleAnchor(.8f, .1f, .05f, 0, true), .00001f)
        assertEquals(.9f, resizedSubtitleAnchor(.9f, .1f, .05f, 2, true), .00001f)
    }
}
