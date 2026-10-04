package com.nuvio.tv.ui.screens.player

import androidx.media3.ui.CaptionStyleCompat
import com.nuvio.tv.data.local.SubtitleEdgeStyle
import com.nuvio.tv.data.local.SubtitleStyleSettings
import com.nuvio.tv.domain.model.AppFont
import org.junit.Assert.*
import org.junit.Test

class SubtitleEdgePresentationTest {
    @Test fun `Media3 maps all edge modes to its actual supported constants`() {
        assertEquals(CaptionStyleCompat.EDGE_TYPE_NONE, subtitleCaptionEdge(SubtitleEdgeStyle.NONE))
        assertEquals(CaptionStyleCompat.EDGE_TYPE_OUTLINE, subtitleCaptionEdge(SubtitleEdgeStyle.OUTLINE))
        assertEquals(CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, subtitleCaptionEdge(SubtitleEdgeStyle.DROP_SHADOW))
    }
    @Test fun `MPV shadow is visible without outlines or saved box background`() {
        val style = SubtitleStyleSettings(edgeStyle = SubtitleEdgeStyle.DROP_SHADOW, backgroundColor = -1)
        val edges = mpvSubtitleEdges(style, false)
        assertEquals(0.0, edges.outlineSize, 0.0)
        assertEquals(1.5, edges.shadowOffset, 0.0)
        assertEquals("outline-and-shadow", edges.borderStyle)
        assertEquals(SUBTITLE_SHADOW_COLOR, edges.backColor)
        assertEquals(-1, style.backgroundColor)
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
        assertEquals("SourceSans3VF", AppFont.SOURCE_SANS_3.mpvFontName())
        assertEquals("Atkinson Hyperlegible Next", AppFont.ATKINSON_HYPERLEGIBLE_NEXT.mpvFontName())
    }
}
