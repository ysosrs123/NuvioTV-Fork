package com.nuvio.tv.ui.v2.appearance

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.*
import org.junit.Test

class ArtworkAccentTest {
    @Test fun `transparent and neutral artwork retain the fallback`() {
        assertNull(sampleArtworkAccent(intArrayOf(0x00FF0000, 0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF777777.toInt())))
    }

    @Test fun `dominant saturated hue survives a neutral backdrop`() {
        val pixels = IntArray(100) { if (it < 75) 0xFF777777.toInt() else 0xFFD04020.toInt() }
        val color = requireNotNull(sampleArtworkAccent(pixels))
        assertTrue(color.red > color.green && color.red > color.blue)
        assertTrue(color.luminance() in 0.24f..0.65f)
    }

    @Test fun `dark blue and bright yellow are clamped into the readable range`() {
        for (pixel in listOf(0xFF101060.toInt(), 0xFFFFFF00.toInt())) {
            val color = requireNotNull(sampleArtworkAccent(intArrayOf(pixel)))
            assertTrue(color.luminance() in 0.24f..0.65f)
        }
    }

    @Test fun `outgoing crossfade image cannot refresh the new selection`() {
        val state = ArtworkAccentState(mutableStateOf(Color.White))
        state.select("movie:b", "https://example.test/b.jpg")
        state.imageLoaded("https://example.test/a.jpg")
        assertEquals(0, state.imageGeneration)
        state.imageLoaded("https://example.test/b.jpg")
        assertEquals(1, state.imageGeneration)
        state.select("movie:c", null)
        assertNull(state.artwork)
    }
}
