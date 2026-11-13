package com.nuvio.tv.core.report

import androidx.media3.common.Format
import androidx.media3.exoplayer.audio.AudioSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackOutputLinesTest {
    @Test fun `a display line names the mode, rate, size and HDR kinds`() {
        assertEquals(
            "mode=3 hz=23.976 size=3840x2160 hdr=DV+HDR10+HLG",
            PlaybackOutputLines.display(3, 23.976f, 3840, 2160, intArrayOf(3, 2, 1, 2))
        )
        assertEquals("mode=1 hz=60.000 size=1920x1080 hdr=none", PlaybackOutputLines.display(1, 60f, 1920, 1080, intArrayOf()))
        assertEquals("mode=1 hz=50.000 size=1920x1080 hdr=unknown", PlaybackOutputLines.display(1, 50f, 1920, 1080, null))
    }

    @Test fun `an HDMI line names the plug state and the encodings`() {
        assertEquals("state=plugged encodings=2,5,6,14 maxChannels=8", PlaybackOutputLines.hdmi(1, intArrayOf(5, 2, 6, 14), 8))
        assertEquals("state=unplugged", PlaybackOutputLines.hdmi(0, null, -1))
    }

    @Test fun `only a real change makes a new line`() {
        val filter = ChangeFilter()
        assertTrue(filter.changed("a"))
        assertFalse(filter.changed("a"))
        assertTrue(filter.changed("b"))
        filter.clear()
        assertTrue(filter.changed("b"))
    }

    @Test fun `a sound output error is told by kind and code, never by its text`() {
        val write = AudioSink.WriteException(-32, Format.Builder().build(), true)
        assertEquals("sink_error kind=write code=-32 recoverable=true", PlaybackOutputLines.sinkError(write))
        val other = PlaybackOutputLines.sinkError(IllegalStateException("https://example.com/x?token=abc"))
        assertEquals("sink_error kind=IllegalStateException", other)
    }

    @Test fun `repeated sound output errors make one line per interval with a count`() {
        val limiter = SinkErrorLimiter(intervalMs = 2_000L)
        assertEquals("e", limiter.line(1_000L, "e"))
        assertEquals(null, limiter.line(1_500L, "e"))
        assertEquals(null, limiter.line(2_999L, "e"))
        assertEquals("e heldBack=2", limiter.line(3_000L, "e"))
        assertEquals("e", limiter.line(6_000L, "e"))
    }
}
