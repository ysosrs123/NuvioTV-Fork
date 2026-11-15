package com.nuvio.tv.ui.screens.player

import android.graphics.Paint
import android.text.TextPaint
import com.nuvio.tv.data.local.DEFAULT_SUBTITLE_SHADOW_SIZE
import com.nuvio.tv.data.local.SubtitleOutlineSize
import com.nuvio.tv.data.local.SubtitleStyleSettings
import com.nuvio.tv.data.local.normalizeSubtitleShadowSize
import com.nuvio.tv.data.local.subtitleShadowScale
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SubtitleShadowSizeTest {

    @Test fun `size 2 is the shadow earlier builds drew, in both players`() {
        assertEquals(2, SubtitleStyleSettings().shadowSize)
        assertEquals(1f, subtitleShadowScale(DEFAULT_SUBTITLE_SHADOW_SIZE), 0f)
        val style = SubtitleStyleSettings(outlineSize = SubtitleOutlineSize.OFF, shadowEnabled = true)
        assertEquals(MPV_SUBTITLE_SHADOW_OFFSET, mpvSubtitleEdges(style, false).shadowOffset, 0.0)
        assertEquals(SubtitleEdgeSpan(0f, subtitleShadowColor(70)), subtitleEdgeSpan(style))
    }

    @Test fun `sizes stay between 1 and 8`() {
        assertEquals(1, normalizeSubtitleShadowSize(0))
        assertEquals(8, normalizeSubtitleShadowSize(12))
        assertEquals(4f, subtitleShadowScale(8), 0f)
        assertEquals(.5f, subtitleShadowScale(1), 0f)
    }

    @Test fun `a bigger shadow moves further out and softer in ExoPlayer`() {
        val fill = paint(Paint.Style.FILL)
        SubtitleEdgeSpan(0f, subtitleShadowColor(70), subtitleShadowScale(8)).updateDrawState(fill)
        verify {
            fill.setShadowLayer(
                48f * SUBTITLE_SHADOW_RADIUS_RATIO * 4f,
                48f * SUBTITLE_SHADOW_OFFSET_RATIO * 4f,
                48f * SUBTITLE_SHADOW_OFFSET_RATIO * 4f,
                subtitleShadowColor(70)
            )
        }
    }

    @Test fun `MPV offset follows the same size`() {
        val style = SubtitleStyleSettings(outlineSize = SubtitleOutlineSize.NORMAL, shadowEnabled = true, shadowSize = 6)
        assertEquals(MPV_SUBTITLE_SHADOW_OFFSET * 3, mpvSubtitleEdges(style, false).shadowOffset, 1e-9)
    }

    @Test fun `spans with another shadow size are not reused`() {
        assertNotEquals(SubtitleEdgeSpan(.1f, 5, 1f), SubtitleEdgeSpan(.1f, 5, 2f))
    }

    private fun paint(style: Paint.Style): TextPaint = mockk(relaxed = true) {
        every { this@mockk.style } returns style
        every { textSize } returns 48f
    }
}
