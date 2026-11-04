package com.nuvio.tv.ui.screens.player

import android.graphics.Paint
import android.text.TextPaint
import androidx.media3.ui.CaptionStyleCompat
import com.nuvio.tv.data.local.SubtitleEdgeStyle
import com.nuvio.tv.data.local.SubtitleOutlineSize
import com.nuvio.tv.data.local.SubtitleStyleSettings
import com.nuvio.tv.data.local.legacySubtitleEdgeStyle
import com.nuvio.tv.data.local.next
import com.nuvio.tv.data.local.normalizeSubtitleShadowOpacity
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.*
import org.junit.Test

class SubtitleOutlineShadowTest {
    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()

    @Test fun `old text edge choices carry over to outline and shadow`() {
        val cases = listOf(
            SubtitleStyleSettings(edgeStyle = SubtitleEdgeStyle.OUTLINE) to (SubtitleOutlineSize.NORMAL to false),
            SubtitleStyleSettings(edgeStyle = SubtitleEdgeStyle.DROP_SHADOW) to (SubtitleOutlineSize.OFF to true),
            SubtitleStyleSettings(edgeStyle = SubtitleEdgeStyle.NONE) to (SubtitleOutlineSize.OFF to false),
            SubtitleStyleSettings(outlineEnabled = true) to (SubtitleOutlineSize.NORMAL to false),
            SubtitleStyleSettings(outlineEnabled = false) to (SubtitleOutlineSize.OFF to false)
        )
        for ((style, expected) in cases) {
            assertEquals("$style", expected.first, style.effectiveOutlineSize)
            assertEquals("$style", expected.second, style.effectiveShadowEnabled)
            assertEquals(70, style.shadowOpacity)
        }
    }

    @Test fun `new choices win over the old edge choice one at a time`() {
        val shadowUser = SubtitleStyleSettings(edgeStyle = SubtitleEdgeStyle.DROP_SHADOW)
        assertEquals(SubtitleOutlineSize.THICK, shadowUser.copy(outlineSize = SubtitleOutlineSize.THICK).effectiveOutlineSize)
        assertTrue(shadowUser.copy(outlineSize = SubtitleOutlineSize.THICK).effectiveShadowEnabled)
        assertFalse(shadowUser.copy(shadowEnabled = false).effectiveShadowEnabled)
        assertEquals(SubtitleOutlineSize.OFF, shadowUser.copy(shadowEnabled = false).effectiveOutlineSize)
    }

    @Test fun `the single edge choice written for older builds`() {
        for (size in SubtitleOutlineSize.entries) for (shadow in listOf(false, true)) {
            val expected = when {
                size != SubtitleOutlineSize.OFF -> SubtitleEdgeStyle.OUTLINE
                shadow -> SubtitleEdgeStyle.DROP_SHADOW
                else -> SubtitleEdgeStyle.NONE
            }
            assertEquals(expected, legacySubtitleEdgeStyle(size, shadow))
        }
        assertEquals(SubtitleEdgeStyle.DROP_SHADOW, legacySubtitleEdgeStyle(null, null))
        assertEquals(SubtitleEdgeStyle.OUTLINE, legacySubtitleEdgeStyle("FUTURE_EDGE", null))
        assertEquals(SubtitleEdgeStyle.NONE, legacySubtitleEdgeStyle(null, false))
    }

    @Test fun `the player button steps through the outline sizes and back to off`() {
        assertEquals(
            listOf(SubtitleOutlineSize.THIN, SubtitleOutlineSize.NORMAL, SubtitleOutlineSize.THICK, SubtitleOutlineSize.OFF),
            SubtitleOutlineSize.entries.map { it.next() }
        )
    }

    @Test fun `shadow opacity keeps to 10 percent steps between 10 and 100`() {
        assertEquals(10, normalizeSubtitleShadowOpacity(-20))
        assertEquals(10, normalizeSubtitleShadowOpacity(4))
        assertEquals(20, normalizeSubtitleShadowOpacity(15))
        assertEquals(70, normalizeSubtitleShadowOpacity(72))
        assertEquals(100, normalizeSubtitleShadowOpacity(250))
        assertEquals(0xB3000000.toInt(), subtitleShadowColor(70))
        assertEquals(0xFF000000.toInt(), subtitleShadowColor(100))
        assertEquals(0x1A000000, subtitleShadowColor(10))
        assertEquals(0x80000000.toInt(), subtitleShadowColor(50))
    }

    @Test fun `outline widths follow the text size with normal at Media3's width`() {
        assertEquals(0f, subtitleOutlineStrokeRatio(SubtitleOutlineSize.OFF), 0f)
        val textSize = 48f
        assertEquals(2f, textSize * subtitleOutlineStrokeRatio(SubtitleOutlineSize.THIN), 1e-4f)
        assertEquals(4f, textSize * subtitleOutlineStrokeRatio(SubtitleOutlineSize.NORMAL), 1e-4f)
        assertEquals(8f, textSize * subtitleOutlineStrokeRatio(SubtitleOutlineSize.THICK), 1e-4f)
        assertEquals(4f, textSize * SUBTITLE_SHADOW_OFFSET_RATIO, 1e-4f)
        assertTrue(SUBTITLE_SHADOW_RADIUS_RATIO < SUBTITLE_SHADOW_OFFSET_RATIO)
    }

    @Test fun `Media3 settings for every outline and shadow choice`() {
        for (size in SubtitleOutlineSize.entries) for (shadow in listOf(false, true)) {
            val style = SubtitleStyleSettings(outlineSize = size, shadowEnabled = shadow, outlineColor = white, textColor = black)
            val edges = exoSubtitleEdges(style)
            when {
                size != SubtitleOutlineSize.OFF -> {
                    assertEquals(CaptionStyleCompat.EDGE_TYPE_OUTLINE, edges.edgeType)
                    assertEquals(white, edges.edgeColor)
                }
                shadow -> {
                    assertEquals(CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, edges.edgeType)
                    assertEquals(subtitleShadowColor(70), edges.edgeColor)
                }
                else -> assertEquals(CaptionStyleCompat.EDGE_TYPE_NONE, edges.edgeType)
            }
            val span = subtitleEdgeSpan(style)
            if (size == SubtitleOutlineSize.OFF && !shadow) {
                assertNull(span)
            } else {
                assertEquals(subtitleOutlineStrokeRatio(size), span!!.strokeRatio, 0f)
                assertEquals(if (shadow) subtitleShadowColor(70) else 0, span.shadowColor)
            }
        }
    }

    @Test fun `a background box leaves the shadow out but keeps the outline`() {
        val style = SubtitleStyleSettings(
            outlineSize = SubtitleOutlineSize.THICK, shadowEnabled = true, backgroundColor = 0x80000000.toInt()
        )
        assertFalse(drawsSubtitleShadow(style))
        assertEquals(CaptionStyleCompat.EDGE_TYPE_OUTLINE, exoSubtitleEdges(style).edgeType)
        assertEquals(0, subtitleEdgeSpan(style)!!.shadowColor)
        assertNull(subtitleEdgeSpan(style.copy(outlineSize = SubtitleOutlineSize.OFF)))
    }

    @Test fun `the outline pass gets the chosen width and the shadow behind it`() {
        val paint = paint(Paint.Style.FILL_AND_STROKE)
        SubtitleEdgeSpan(subtitleOutlineStrokeRatio(SubtitleOutlineSize.THICK), subtitleShadowColor(70)).updateDrawState(paint)
        verify { paint.strokeWidth = match { kotlin.math.abs(it - 8f) < 1e-3f } }
        verify { paint.setShadowLayer(48f * SUBTITLE_SHADOW_RADIUS_RATIO, 48f * SUBTITLE_SHADOW_OFFSET_RATIO, 48f * SUBTITLE_SHADOW_OFFSET_RATIO, subtitleShadowColor(70)) }
        val fill = paint(Paint.Style.FILL)
        SubtitleEdgeSpan(subtitleOutlineStrokeRatio(SubtitleOutlineSize.THICK), subtitleShadowColor(70)).updateDrawState(fill)
        verify(exactly = 0) { fill.strokeWidth = any() }
        verify(exactly = 0) { fill.setShadowLayer(any(), any(), any(), any()) }
    }

    @Test fun `without an outline only the fill pass gets the shadow`() {
        val fill = paint(Paint.Style.FILL)
        SubtitleEdgeSpan(0f, subtitleShadowColor(40)).updateDrawState(fill)
        verify { fill.setShadowLayer(48f * SUBTITLE_SHADOW_RADIUS_RATIO, 48f * SUBTITLE_SHADOW_OFFSET_RATIO, 48f * SUBTITLE_SHADOW_OFFSET_RATIO, subtitleShadowColor(40)) }
        verify(exactly = 0) { fill.strokeWidth = any() }
        val outline = paint(Paint.Style.FILL_AND_STROKE)
        SubtitleEdgeSpan(0f, subtitleShadowColor(40)).updateDrawState(outline)
        verify(exactly = 0) { outline.setShadowLayer(any(), any(), any(), any()) }
    }

    @Test fun `an outline without shadow sets no shadow`() {
        val paint = paint(Paint.Style.FILL_AND_STROKE)
        SubtitleEdgeSpan(subtitleOutlineStrokeRatio(SubtitleOutlineSize.THIN), 0).updateDrawState(paint)
        verify { paint.strokeWidth = match { kotlin.math.abs(it - 2f) < 1e-3f } }
        verify(exactly = 0) { paint.setShadowLayer(any(), any(), any(), any()) }
    }

    @Test fun `equal spans compare equal so Media3 keeps its cached layout`() {
        assertEquals(SubtitleEdgeSpan(.1f, 5), SubtitleEdgeSpan(.1f, 5))
        assertEquals(SubtitleEdgeSpan(.1f, 5).hashCode(), SubtitleEdgeSpan(.1f, 5).hashCode())
        assertNotEquals(SubtitleEdgeSpan(.1f, 5), SubtitleEdgeSpan(.2f, 5))
        assertNotEquals(SubtitleEdgeSpan(.1f, 5), SubtitleEdgeSpan(.1f, 6))
    }

    @Test fun `MPV sizes the outline and shades the shadow by the chosen opacity`() {
        for ((size, expected) in listOf(
            SubtitleOutlineSize.OFF to 0.0, SubtitleOutlineSize.THIN to 0.5,
            SubtitleOutlineSize.NORMAL to 1.0, SubtitleOutlineSize.THICK to 2.0
        )) {
            val plain = mpvSubtitleEdges(SubtitleStyleSettings(outlineSize = size, shadowEnabled = true, shadowOpacity = 40), false)
            assertEquals(expected, plain.outlineSize, 0.0)
            assertEquals(1.5, plain.shadowOffset, 0.0)
            assertEquals("outline-and-shadow", plain.borderStyle)
            assertEquals(subtitleShadowColor(40), plain.backColor)
            val noShadow = mpvSubtitleEdges(SubtitleStyleSettings(outlineSize = size, shadowEnabled = false), false)
            assertEquals(expected, noShadow.outlineSize, 0.0)
            assertEquals(0.0, noShadow.shadowOffset, 0.0)
        }
        val ass = mpvSubtitleEdges(SubtitleStyleSettings(outlineSize = SubtitleOutlineSize.THICK, outlineWidth = 3), true)
        assertEquals(3.0, ass.outlineSize, 0.0)
        assertEquals(0.0, mpvSubtitleEdges(SubtitleStyleSettings(outlineSize = SubtitleOutlineSize.OFF), true).outlineSize, 0.0)
    }

    private fun paint(style: Paint.Style): TextPaint = mockk(relaxed = true) {
        every { this@mockk.style } returns style
        every { textSize } returns 48f
    }
}
