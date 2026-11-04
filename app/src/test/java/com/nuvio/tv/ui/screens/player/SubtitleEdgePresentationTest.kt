package com.nuvio.tv.ui.screens.player

import androidx.media3.ui.CaptionStyleCompat
import com.nuvio.tv.data.local.SubtitleEdgeStyle
import com.nuvio.tv.data.local.SubtitleStyleSettings
import com.nuvio.tv.domain.model.AppFont
import com.nuvio.tv.domain.model.SubtitleFont
import org.junit.Assert.*
import org.junit.Test

class SubtitleEdgePresentationTest {
    @Test fun `Media3 draws the outline pass for any outline and its drop shadow for a shadow alone`() {
        assertEquals(CaptionStyleCompat.EDGE_TYPE_NONE, exoSubtitleEdges(SubtitleStyleSettings(edgeStyle = SubtitleEdgeStyle.NONE)).edgeType)
        assertEquals(CaptionStyleCompat.EDGE_TYPE_OUTLINE, exoSubtitleEdges(SubtitleStyleSettings(edgeStyle = SubtitleEdgeStyle.OUTLINE)).edgeType)
        assertEquals(CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, exoSubtitleEdges(SubtitleStyleSettings(edgeStyle = SubtitleEdgeStyle.DROP_SHADOW)).edgeType)
    }
    @Test fun `MPV shadow is visible without outlines when no background is set`() {
        val edges = mpvSubtitleEdges(SubtitleStyleSettings(edgeStyle = SubtitleEdgeStyle.DROP_SHADOW), false)
        assertEquals(0.0, edges.outlineSize, 0.0)
        assertEquals(1.5, edges.shadowOffset, 0.0)
        assertEquals("outline-and-shadow", edges.borderStyle)
        assertEquals(0xB3000000.toInt(), edges.backColor)
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
    @Test fun `Media3 with a background draws the box and leaves the shadow out`() {
        for (alpha in listOf(0x20, 0x80, 0xB3, 0xFF)) {
            val background = (alpha shl 24) or 0x202020
            val style = SubtitleStyleSettings(edgeStyle = SubtitleEdgeStyle.DROP_SHADOW, backgroundColor = background)
            val edges = exoSubtitleEdges(style)
            assertEquals(background, edges.background)
            assertEquals(CaptionStyleCompat.EDGE_TYPE_NONE, edges.edgeType)
            assertNull(subtitleEdgeSpan(style))
        }
    }
    @Test fun `Media3 keeps per line backgrounds and the outline colour`() {
        val background = 0x80202020.toInt()
        for (edge in listOf(SubtitleEdgeStyle.NONE, SubtitleEdgeStyle.OUTLINE)) {
            val style = SubtitleStyleSettings(edgeStyle = edge, backgroundColor = background, outlineColor = 0xFF112233.toInt())
            assertEquals(background, exoSubtitleEdges(style).background)
            assertEquals(0xFF112233.toInt(), exoSubtitleEdges(style).edgeColor)
        }
        assertEquals(0xB3000000.toInt(), exoSubtitleEdges(SubtitleStyleSettings(edgeStyle = SubtitleEdgeStyle.DROP_SHADOW)).edgeColor)
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
