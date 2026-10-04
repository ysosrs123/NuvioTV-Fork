package com.nuvio.tv.ui.v2.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UiDiagnosticsTest {
    private fun canvas(density: Float = 2f) = UiCanvasSnapshot(
        1920, 1080, density, (density * 160).toInt(), 960, 540,
        1, 3840, 2160, 59.94f
    )

    @Test
    fun `effective canvas uses framebuffer density and applied scale rather than HDMI mode`() {
        val fire = canvas()
        assertEquals(960f, fire.logicalWidthDp!!, 0.01f)
        assertEquals(1280f, fire.effectiveWidthDp(75)!!, 0.01f)
        assertEquals(720f, fire.effectiveHeightDp(75)!!, 0.01f)
        assertEquals(canvas(1.5f).effectiveWidthDp(100)!!, fire.effectiveWidthDp(75)!!, 0.01f)
        val matchedOutput = fire.copy(outputWidthPx = 1920, outputHeightPx = 1080, refreshRateHz = 23.976f)
        assertEquals(fire.effectiveWidthDp(85), matchedOutput.effectiveWidthDp(85))
    }

    @Test
    fun `unmeasured windows and corrupt density do not report invented geometry`() {
        assertNull(canvas().copy(windowWidthPx = 0).logicalWidthDp)
        assertNull(canvas().copy(baseDensity = Float.NaN).logicalWidthDp)
        assertNull(canvas().copy(baseDensity = Float.POSITIVE_INFINITY).logicalHeightDp)
        assertNull(canvas().copy(baseDensity = 0f).logicalWidthDp)
        assertNull(canvas().effectiveWidthDp(0))
    }

    @Test
    fun `frame window expires old and future samples and reports unavailable for no samples`() {
        val window = UiFrameWindow(4)
        window.record(1, 10_000_000, false)
        window.record(90, 20_000_000, true)
        window.record(110, 30_000_000, true)
        val summary = window.snapshot(100, 20)
        assertEquals(1, summary.frames)
        assertEquals(100.0, summary.jankPercent!!, 0.01)
        assertEquals(20.0, summary.p99UiMs!!, 0.01)
        window.clear()
        assertEquals(0, window.snapshot(100).frames)
        assertNull(window.snapshot(100).p95UiMs)
        assertNull(window.snapshot(100).jankPercent)
    }

    @Test
    fun `ring keeps only newest frames and uses nearest rank percentiles`() {
        val window = UiFrameWindow(100)
        for (i in 1..200) window.record(i.toLong(), i * 1_000_000L, i > 150)
        val summary = window.snapshot(200)
        assertEquals(100, summary.frames)
        assertEquals(50, summary.jankyFrames)
        assertEquals(150.0, summary.p50UiMs!!, 0.01)
        assertEquals(195.0, summary.p95UiMs!!, 0.01)
        assertEquals(199.0, summary.p99UiMs!!, 0.01)
        window.record(201, 0, true)
        assertEquals(summary, window.snapshot(201))
    }
}
