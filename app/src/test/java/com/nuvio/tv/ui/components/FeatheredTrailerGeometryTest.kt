package com.nuvio.tv.ui.components

import org.junit.Assert.*
import org.junit.Test

class FeatheredTrailerGeometryTest {
    @Test fun `fullscreen window clears row heading across densities and poster layouts`() {
        for (scale in listOf(.8f, 1f, 1.2f, 1.5f)) {
            for (rowsFraction in listOf(.49f, .52f)) {
                for (pixels in listOf(1280f to 720f, 1920f to 1080f, 3840f to 2160f)) {
                    val density = 1.5f * scale
                    val width = pixels.first / density
                    val height = pixels.second / density
                    val rowTop = height * (1f - rowsFraction)
                    val window = featheredTrailerWidth(width, height, true, rowTop - 8f)
                    assertTrue(12f + window * 9f / 16f <= rowTop - 8f + .001f)
                    assertTrue(window <= (width - 24f) * .66f + .001f)
                    assertTrue(window > 0f)
                }
            }
        }
    }

    @Test fun `compact dimensions retain build 1373 viewport`() {
        for (width in listOf(500f, 760f, 1100f)) for (height in listOf(300f, 440f, 600f)) {
            val accepted = minOf(width - 24f, (height - 24f) * 16f / 9f)
            assertEquals(accepted, featheredTrailerWidth(width, height, false, 1f), .001f)
        }
    }

    @Test fun `fit masks track wide tall and square image edges without stretching`() {
        for (aspect in listOf(2.39f, 16f / 9f, 4f / 3f, 1f, 9f / 16f)) {
            val rect = fittedTrailerImage(640f, 360f, aspect)
            assertEquals(aspect, rect.width / rect.height, .001f)
            assertEquals(640f, 2f * rect.left + rect.width, .001f)
            assertEquals(360f, 2f * rect.top + rect.height, .001f)
            assertTrue(rect.top >= 0f && rect.left >= 0f)
            assertTrue(rect.width <= 640f && rect.height <= 360.001f)
        }
        assertTrue(fittedTrailerImage(640f, 360f, 2.39f).top > 40f)
        assertTrue(fittedTrailerImage(640f, 360f, 4f / 3f).left > 70f)
    }

    @Test fun `common encoded cinema bars finish inside transparent feather region`() {
        val encodedBar = (1f - (16f / 9f) / 2.39f) / 2f
        assertTrue(trailerVerticalFeatherInset(16f / 9f) >= encodedBar)
        assertTrue(trailerVerticalFeatherInset(2.39f) < .003f)
    }

    @Test fun `unknown video dimensions use finite fit bounds until metadata arrives`() {
        for (aspect in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val rect = fittedTrailerImage(640f, 360f, aspect)
            assertEquals(640f, rect.width, .001f)
            assertEquals(360f, rect.height, .001f)
        }
    }
}
