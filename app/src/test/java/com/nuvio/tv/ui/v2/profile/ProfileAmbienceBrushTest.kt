package com.nuvio.tv.ui.v2.profile

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ProfileAmbienceBrushTest {
    @Test fun glowKeepsItsColourCentreAndReach() {
        val color = Color(0xFF3366CC)
        val expected = Brush.radialGradient(
            listOf(color.copy(alpha = 0.13f), Color.Transparent),
            center = Offset(960f, 1080f * 0.48f), radius = 1920f * 0.55f)
        assertEquals(expected, profileAmbienceBrush(color, Size(1920f, 1080f)))
    }

    @Test fun anotherColourGivesAnotherGlow() {
        val size = Size(3840f, 2160f)
        assertNotEquals(profileAmbienceBrush(Color.Red, size), profileAmbienceBrush(Color.Blue, size))
    }
}
