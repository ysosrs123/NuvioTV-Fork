package com.nuvio.tv.ui.v2.scale

import com.nuvio.tv.domain.model.DeviceUiPreferences
import com.nuvio.tv.domain.model.UiScaleMode
import com.nuvio.tv.domain.model.VisualQualityMode
import com.nuvio.tv.ui.v2.diagnostics.UiCanvasSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class UiScaleResolverTest {
    private val resolver = UiScaleResolver()
    private val canvas = UiCanvasSnapshot(1920, 1080, 2f, 320, 960, 540, 1, 3840, 2160, 60f)

    @Test
    fun `automatic normalizes logical canvases and applies multiplicative fine adjustment`() {
        assertEquals(75, resolver.resolve(canvas, DeviceUiPreferences()).percent)
        assertEquals(100, resolver.resolve(canvas.copy(baseDensity = 1.5f), DeviceUiPreferences()).percent)
        assertEquals(79, resolver.resolve(canvas, DeviceUiPreferences(autoScaleFineTunePercent = 5)).percent)
        assertEquals(72, resolver.resolve(canvas, DeviceUiPreferences(autoScaleFineTunePercent = -10)).percent)
    }

    @Test
    fun `HDMI resolution refresh rate and rendering quality never determine geometry`() {
        val expected = resolver.resolve(canvas, DeviceUiPreferences())
        for (quality in VisualQualityMode.entries) {
            val output = canvas.copy(outputWidthPx = 1920, outputHeightPx = 1080, refreshRateHz = 23.976f)
            assertEquals(expected, resolver.resolve(output, DeviceUiPreferences(visualQualityMode = quality)))
        }
    }

    @Test
    fun `manual scale ignores canvas and automatic fine adjustment`() {
        val preferences = DeviceUiPreferences(uiScaleMode = UiScaleMode.MANUAL, manualUiScalePercent = 90, autoScaleFineTunePercent = 10)
        assertEquals(90, resolver.resolve(canvas.copy(windowWidthPx = 0), preferences).percent)
        assertEquals(75, resolver.resolve(canvas, preferences.copy(manualUiScalePercent = 5)).percent)
        assertEquals(115, resolver.resolve(canvas, preferences.copy(manualUiScalePercent = 200)).percent)
    }

    @Test
    fun `automatic clamps bad vendor metrics and falls back safely before layout`() {
        assertEquals(100, resolver.resolve(canvas.copy(baseDensity = Float.NaN), DeviceUiPreferences()).percent)
        assertEquals(100, resolver.resolve(canvas.copy(windowHeightPx = 0), DeviceUiPreferences()).percent)
        assertEquals(75, resolver.resolve(canvas.copy(baseDensity = 5f), DeviceUiPreferences()).percent)
        assertEquals(120, resolver.resolve(canvas.copy(baseDensity = 0.5f), DeviceUiPreferences(autoScaleFineTunePercent = 10)).percent)
    }
}
