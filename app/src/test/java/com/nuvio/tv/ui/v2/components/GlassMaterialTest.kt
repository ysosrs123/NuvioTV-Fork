package com.nuvio.tv.ui.v2.components

import com.nuvio.tv.domain.model.GlassPreset
import com.nuvio.tv.domain.model.VisualQualityMode
import com.nuvio.tv.ui.v2.quality.*
import org.junit.Assert.*
import org.junit.Test

class GlassMaterialTest {
    private val box = UiRenderCapabilities(34, false, 3_895_869_440L, 256, true, 1920, 1080, 0x30200)

    @Test fun `all presets respect device tier and playback blur limits without changing focus`() {
        listOf(box, box.copy(api = 30), box.copy(hardwareAccelerated = false)).forEach { device ->
            VisualQualityMode.entries.forEach { mode ->
                listOf(false, true).forEach { playback ->
                    val base = GlassQualityTokens.resolve(VisualQualityResolver.resolve(mode, device), playback)
                    (GlassPreset.entries.map { it.blurStrengthPercent } + listOf(-100, 500)).forEach { strength ->
                        val tuned = base.withBlurStrength(strength)
                        assertTrue(tuned.blurRadiusDp in 0f..base.blurRadiusDp)
                        assertTrue(tuned.inputScale <= base.inputScale)
                        assertEquals(base.focusBloomAlpha, tuned.focusBloomAlpha)
                        assertEquals(base.edgeLayers, tuned.edgeLayers)
                        if (!base.liveBlur || strength <= 0) assertFalse(tuned.liveBlur)
                        if (playback) assertEquals(GlassQualityTokens.Performance, tuned)
                    }
                }
            }
        }
    }

    @Test fun `blur strength changes radius independently and zero removes capture cost`() {
        val base = GlassQualityTokens.resolve(VisualQualityResolver.resolve(VisualQualityMode.MAXIMUM, box), false)
        assertEquals(12f, base.withBlurStrength(50).blurRadiusDp)
        assertEquals(24f, base.withBlurStrength(100).blurRadiusDp)
        assertEquals(0f, base.withBlurStrength(0).inputScale)
        assertEquals(0f, base.withBlurStrength(0).noise)
    }

    @Test fun `body stays translucent readable and monotonically clearer across every role`() {
        GlassRole.entries.forEach { role ->
            listOf(false, true).forEach { playback ->
                val values = (0..100).map { glassBodyAlpha(role, playback, .28f, it) }
                assertTrue(values.all { it in .10f.. .88f })
                assertTrue(values.zipWithNext().all { (a, b) -> a >= b })
            }
            assertEquals(glassBodyAlpha(role, false, .50f, 60), glassBodyAlpha(role, false, .94f, 60))
        }
    }

    @Test fun `balanced retains the approved neutral player body opacity`() {
        assertEquals(.18f, glassBodyAlpha(GlassRole.CONTROL, true, .18f, 60), .0001f)
        assertEquals(.28f, glassBodyAlpha(GlassRole.PANEL, true, .28f, 60), .0001f)
        assertEquals(.38f, glassBodyAlpha(GlassRole.HUD, true, .38f, 60), .0001f)
    }

    @Test fun `smoked body follows the transparency setting within a readable range`() {
        assertEquals(.62f, smokedGlassBodyAlpha(60), .0001f)
        val values = (0..100).map { smokedGlassBodyAlpha(it) }
        assertTrue(values.all { it in .50f.. .86f })
        assertTrue(values.zipWithNext().all { (a, b) -> a >= b })
    }

    @Test fun `smoked fill tops any thinner body up to the smoked opacity and never thins a denser one`() {
        listOf(0, 30, 60, 100).forEach { transparency ->
            val target = smokedGlassBodyAlpha(transparency)
            listOf(.025f, .08f, .10f, .48f).forEach { body ->
                val fill = smokedGlassFillAlpha(body, transparency)
                assertEquals(target, 1f - (1f - body) * (1f - fill), .0001f)
            }
            assertEquals(0f, smokedGlassFillAlpha(.90f, transparency))
        }
    }
}
