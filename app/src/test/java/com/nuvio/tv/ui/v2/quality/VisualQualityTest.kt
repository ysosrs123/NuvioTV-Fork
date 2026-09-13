package com.nuvio.tv.ui.v2.quality

import com.nuvio.tv.domain.model.VisualQualityMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualQualityTest {
    private val box = UiRenderCapabilities(34, false, 3_895_869_440L, 256, true, 1920, 1080, 0x30200)

    @Test fun `initial assessment uses graphics memory and root cost rather than RAM alone`() {
        assertEquals(VisualQualityTier.ENHANCED, VisualQualityResolver.automatic(box).tier)
        listOf(
            box.copy(api = 30), box.copy(lowRam = true), box.copy(heapMb = 192),
            box.copy(totalRamBytes = 1_750_000_000L), box.copy(hardwareAccelerated = false),
            box.copy(rootWidthPx = 3840, rootHeightPx = 2160), box.copy(glEsVersion = 0x20000),
            box.copy(rootWidthPx = 0), box.copy(totalRamBytes = 0)
        ).forEach { assertEquals(VisualQualityTier.PERFORMANCE, VisualQualityResolver.automatic(it).tier) }
        // Extra memory alone is not measured evidence for Automatic Maximum.
        assertEquals(VisualQualityTier.ENHANCED, VisualQualityResolver.automatic(box.copy(totalRamBytes = 16_000_000_000L)).tier)
    }

    @Test fun `manual quality persists its requested tier with capability-safe material fallback`() {
        val maximum = VisualQualityResolver.resolve(VisualQualityMode.MAXIMUM, box.copy(api = 30))
        assertEquals(VisualQualityTier.MAXIMUM, maximum.tier)
        val glass = GlassQualityTokens.resolve(maximum, playback = false)
        assertFalse(glass.liveBlur)
        assertEquals(0f, glass.inputScale)
        assertTrue(glass.surfaceAlpha >= 0.9f)
    }

    @Test fun `every quality tier forbids video blur and expensive player materials`() {
        VisualQualityMode.entries.forEach { mode ->
            val decision = VisualQualityResolver.resolve(mode, box)
            assertEquals(GlassQualityTokens.Performance, GlassQualityTokens.resolve(decision, playback = true))
        }
    }

    @Test fun `live glass capture resolution and radius stay bounded`() {
        listOf(VisualQualityMode.ENHANCED, VisualQualityMode.MAXIMUM).forEach { mode ->
            val glass = GlassQualityTokens.resolve(VisualQualityResolver.resolve(mode, box), playback = false)
            assertTrue(glass.liveBlur)
            assertTrue(glass.inputScale in 0.25f..0.5f)
            assertTrue(glass.blurRadiusDp in 1f..24f)
        }
    }
}
