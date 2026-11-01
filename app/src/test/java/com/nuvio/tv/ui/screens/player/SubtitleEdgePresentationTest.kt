package com.nuvio.tv.ui.screens.player

import androidx.media3.ui.CaptionStyleCompat
import com.nuvio.tv.data.local.SubtitleEdgeStyle
import com.nuvio.tv.data.local.SubtitleStyleSettings
import com.nuvio.tv.domain.model.AppFont
import com.nuvio.tv.domain.model.SubtitleFont
import org.junit.Assert.*
import org.junit.Test

class SubtitleEdgePresentationTest {
    @Test fun `Media3 maps all edge modes to its actual supported constants`() {
        assertEquals(CaptionStyleCompat.EDGE_TYPE_NONE, subtitleCaptionEdge(SubtitleEdgeStyle.NONE))
        assertEquals(CaptionStyleCompat.EDGE_TYPE_OUTLINE, subtitleCaptionEdge(SubtitleEdgeStyle.OUTLINE))
        assertEquals(CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, subtitleCaptionEdge(SubtitleEdgeStyle.DROP_SHADOW))
    }
    @Test fun `MPV shadow is visible without outlines when no background is set`() {
        val edges = mpvSubtitleEdges(SubtitleStyleSettings(edgeStyle = SubtitleEdgeStyle.DROP_SHADOW), false)
        assertEquals(0.0, edges.outlineSize, 0.0)
        assertEquals(1.5, edges.shadowOffset, 0.0)
        assertEquals("outline-and-shadow", edges.borderStyle)
        assertEquals(SUBTITLE_SHADOW_COLOR, edges.backColor)
    }
    @Test fun `MPV shadow with a background keeps the saved background and its opacity`() {
        for (alpha in listOf(0x20, 0x80, 0xB3, 0xFF)) {
            val background = (alpha shl 24) or 0x202020
            val edges = mpvSubtitleEdges(
                SubtitleStyleSettings(edgeStyle = SubtitleEdgeStyle.DROP_SHADOW, backgroundColor = background), false
            )
            assertEquals("background-box", edges.borderStyle)
            assertEquals(background, edges.backColor)
            assertEquals(5.0, edges.shadowOffset, 0.0)
            assertEquals(0.0, edges.outlineSize, 0.0)
        }
    }
    @Test fun `Media3 shadow with a background paints the box as the window so it keeps its opacity`() {
        for (alpha in listOf(0x20, 0x80, 0xB3, 0xFF)) {
            val background = (alpha shl 24) or 0x202020
            val colors = exoSubtitleColors(
                SubtitleStyleSettings(edgeStyle = SubtitleEdgeStyle.DROP_SHADOW, backgroundColor = background)
            )
            assertEquals(0, colors.background)
            assertEquals(background, colors.window)
            assertEquals(SUBTITLE_SHADOW_COLOR, colors.edgeColor)
        }
    }
    @Test fun `Media3 keeps per line backgrounds for the other edge styles and shadow without background`() {
        val background = 0x80202020.toInt()
        for (edge in listOf(SubtitleEdgeStyle.NONE, SubtitleEdgeStyle.OUTLINE)) {
            val style = SubtitleStyleSettings(edgeStyle = edge, backgroundColor = background, outlineColor = 0xFF112233.toInt())
            assertEquals(ExoSubtitleColors(background, 0, 0xFF112233.toInt()), exoSubtitleColors(style))
        }
        assertEquals(
            ExoSubtitleColors(0, 0, SUBTITLE_SHADOW_COLOR),
            exoSubtitleColors(SubtitleStyleSettings(edgeStyle = SubtitleEdgeStyle.DROP_SHADOW))
        )
    }
    @Test fun `MPV outline and none retain existing box padding and saved background`() {
        for (edge in listOf(SubtitleEdgeStyle.NONE, SubtitleEdgeStyle.OUTLINE)) {
            val edges = mpvSubtitleEdges(SubtitleStyleSettings(edgeStyle = edge, backgroundColor = -16777216), false)
            assertEquals("background-box", edges.borderStyle)
            assertEquals(5.0, edges.shadowOffset, 0.0)
            assertEquals(-16777216, edges.backColor)
            assertEquals(if (edge == SubtitleEdgeStyle.OUTLINE) 1.0 else 0.0, edges.outlineSize, 0.0)
        }
    }
    @Test fun `legacy off gives no shadow and no outline with transparent background`() {
        val edges = mpvSubtitleEdges(SubtitleStyleSettings(outlineEnabled = false), false)
        assertEquals(0.0, edges.outlineSize, 0.0); assertEquals(0.0, edges.shadowOffset, 0.0)
    }
    @Test fun `all font choices have distinct bundled Android resources`() {
        assertEquals(5, AppFont.entries.map { it.fontResource() }.toSet().size)
        assertEquals(SubtitleFont.entries.size, SubtitleFont.entries.map { it.fontResource() }.toSet().size)
        assertEquals("SourceSans3VF", SubtitleFont.SOURCE_SANS_3.mpvFontName(false))
        assertEquals("Atkinson Hyperlegible Next", SubtitleFont.ATKINSON_HYPERLEGIBLE_NEXT.mpvFontName(false))
    }
}
