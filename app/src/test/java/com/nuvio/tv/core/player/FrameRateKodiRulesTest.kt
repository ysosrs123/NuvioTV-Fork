package com.nuvio.tv.core.player

import com.nuvio.tv.core.player.FrameRateUtils.DisplayModeSpec
import com.nuvio.tv.ui.screens.player.trackAfrDeadlineMs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameRateKodiRulesTest {

    private val film = 24000f / 1001f
    private val ntsc30 = 30000f / 1001f

    private val uhd = listOf(
        mode(1, 3840, 2160, 59.94f),
        mode(2, 3840, 2160, 60f),
        mode(3, 3840, 2160, 50f),
        mode(4, 3840, 2160, 30f),
        mode(5, 3840, 2160, 29.97f),
        mode(6, 3840, 2160, 25f),
        mode(7, 3840, 2160, 24f),
        mode(8, 3840, 2160, 23.976f),
        mode(9, 1920, 1080, 59.94f),
        mode(10, 1920, 1080, 50f),
        mode(11, 1920, 1080, 25f),
        mode(12, 1920, 1080, 23.976f)
    )

    @Test
    fun `25 and 30 fps play at the double rate`() {
        assertEquals(50f, pickRate(uhd, 25f), 0.01f)
        assertEquals(59.94f, pickRate(uhd, ntsc30), 0.01f)
        assertEquals(60f, pickRate(uhd, 30f), 0.01f)
    }

    @Test
    fun `film rates still take the exact rate`() {
        assertEquals(23.976f, pickRate(uhd, film), 0.01f)
        assertEquals(24f, pickRate(uhd, 24f), 0.01f)
        assertEquals(50f, pickRate(uhd, 50f), 0.01f)
        assertEquals(59.94f, pickRate(uhd, 60000f / 1001f), 0.01f)
    }

    @Test
    fun `25 fps keeps 25 Hz when the display has no 50 Hz`() {
        val modes = listOf(mode(1, 3840, 2160, 60f), mode(2, 3840, 2160, 25f))
        assertEquals(25f, pickRate(modes, 25f), 0.01f)
    }

    @Test
    fun `an encode a few pixels over 1080p stays at 1080p`() {
        val id = FrameRateUtils.selectDisplayMode(uhd, uhd[0], 25f, 1928, 1080, resolutionMatchingEnabled = true)?.modeId
        assertEquals(10, id)
        val wide = FrameRateUtils.selectDisplayMode(uhd, uhd[0], film, 1920, 1096, resolutionMatchingEnabled = true)?.modeId
        assertEquals(12, wide)
        val clearlyBigger = FrameRateUtils.selectDisplayMode(uhd, uhd[0], film, 2048, 1080, resolutionMatchingEnabled = true)?.modeId
        assertEquals(8, clearlyBigger)
    }

    @Test
    fun `whole millisecond frame durations snap to the standard rate`() {
        assertEquals(film, FrameRateUtils.snapToStandardRate(1000f / 42f), 0.0001f)
        assertEquals(ntsc30, FrameRateUtils.snapToStandardRate(1000f / 33f), 0.0001f)
        assertEquals(60000f / 1001f, FrameRateUtils.snapToStandardRate(1000f / 17f), 0.0001f)
        assertEquals(25f, FrameRateUtils.snapToStandardRate(1000f / 40f), 0.0001f)
        assertEquals(23.7f, FrameRateUtils.snapToStandardRate(23.7f), 0.0001f)
    }

    @Test
    fun `a sibling rate counts as reached only after the mode id changed`() {
        assertFalse(FrameRateUtils.switchReported(1, 59.94f, 2, 60f, fromModeId = 1))
        assertTrue(FrameRateUtils.switchReported(2, 60f, 2, 60f, fromModeId = 1))
        assertTrue(FrameRateUtils.switchReported(5, 60.0001f, 2, 60f, fromModeId = 1))
        assertFalse(FrameRateUtils.switchReported(8, 23.976f, 7, 24f, fromModeId = 8))
        assertFalse(FrameRateUtils.switchReported(1, 59.94f, 7, 24f, fromModeId = 1))
    }

    @Test
    fun `restore writes back when the window still asks for the film mode`() {
        assertTrue(FrameRateUtils.restoreNeedsWrite(displayModeId = 1, requestedModeId = 8, targetModeId = 1))
        assertTrue(FrameRateUtils.restoreNeedsWrite(displayModeId = 8, requestedModeId = 8, targetModeId = 1))
        assertFalse(FrameRateUtils.restoreNeedsWrite(displayModeId = 1, requestedModeId = 1, targetModeId = 1))
        assertFalse(FrameRateUtils.restoreNeedsWrite(displayModeId = 1, requestedModeId = 0, targetModeId = 1))
    }

    @Test
    fun `the settle wait lasts two seconds then follows the hdmi audio link`() {
        assertFalse(AfrSettle.settled(1_500L, 1, userDelayMs = 0L))
        assertFalse(AfrSettle.settled(2_100L, 0, userDelayMs = 0L))
        assertTrue(AfrSettle.settled(2_100L, 1, userDelayMs = 0L))
        assertTrue(AfrSettle.settled(2_100L, null, userDelayMs = 0L))
        assertTrue(AfrSettle.settled(5_000L, 0, userDelayMs = 0L))
    }

    @Test
    fun `a delay of its own raises the minimum and the cap`() {
        assertFalse(AfrSettle.settled(4_000L, 1, userDelayMs = 6_000L))
        assertTrue(AfrSettle.settled(6_000L, 1, userDelayMs = 6_000L))
        assertFalse(AfrSettle.settled(8_000L, 0, userDelayMs = 6_000L))
        assertTrue(AfrSettle.settled(9_000L, 0, userDelayMs = 6_000L))
        assertEquals(13_000L, AfrSettle.capMs(60_000L))
    }

    @Test
    fun `the track path deadline covers the switch and the longest settle`() {
        assertEquals(10_000L, trackAfrDeadlineMs(0L))
        assertEquals(18_000L, trackAfrDeadlineMs(10_000L))
    }

    @Test
    fun `the card says how often a frame repeats, or calls a far rate judder`() {
        assertEquals(42, DisplayRefreshMeter.repeatEverySeconds(24f, film))
        assertEquals(17, DisplayRefreshMeter.repeatEverySeconds(60f, 60000f / 1001f))
        assertEquals(null, DisplayRefreshMeter.repeatEverySeconds(59.94f, film))
        assertEquals(null, DisplayRefreshMeter.repeatEverySeconds(film, film))
    }

    @Test
    fun `48 fps takes 60 Hz rather than 50 when the display has no 48 Hz family`() {
        val modes = listOf(mode(1, 3840, 2160, 59.94f), mode(2, 3840, 2160, 60f), mode(3, 3840, 2160, 50f))
        assertEquals(60f, pickRate(modes, 48f), 0.01f)
        assertEquals(59.94f, pickRate(modes, 48000f / 1001f), 0.01f)
        val with48 = modes + mode(4, 3840, 2160, 48f)
        assertEquals(48f, pickRate(with48, 48f), 0.01f)
        val with120 = modes + mode(5, 3840, 2160, 120f)
        assertEquals(120f, pickRate(with120, 48f), 0.01f)
    }

    private fun pickRate(modes: List<DisplayModeSpec>, frameRate: Float): Float =
        FrameRateUtils.selectDisplayMode(modes, modes[0], frameRate, null, null, resolutionMatchingEnabled = false)!!
            .refreshRate

    private fun mode(id: Int, width: Int, height: Int, refreshRate: Float) =
        DisplayModeSpec(id, width, height, refreshRate)
}
