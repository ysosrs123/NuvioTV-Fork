package com.nuvio.tv.ui.screens.player

import android.graphics.Color
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import com.nuvio.tv.R
import com.nuvio.tv.data.local.SubtitleStyleSettings
import io.mockk.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test

class PlayerSubtitleStylingTest {
    private val view = mockk<PlayerView>(relaxed = true)
    private val subtitles = mockk<SubtitleView>(relaxed = true)
    private val player = mockk<ExoPlayer>(relaxed = true)
    private val pending = mutableListOf<Runnable>()
    private val tags = mutableMapOf<Int, Any?>()
    private val styles = mutableListOf<CaptionStyleCompat>()
    private val custom = SubtitleStyleSettings(size = 160, bold = true, verticalOffset = 40,
        backgroundColor = Color.BLUE, outlineEnabled = true)

    @Before
    fun setup() {
        // JVM Android stubs leave Typeface.DEFAULT fields null. Mock the Android
        // resource boundary while exercising the real SubtitleView application.
        mockkStatic("com.nuvio.tv.ui.screens.player.SubtitleFontsKt")
        every { subtitleTypeface(any(), any(), any()) } returns mockk<android.graphics.Typeface>()
        every { view.subtitleView } returns subtitles
        every { view.player } returns player
        every { view.getTag(any()) } answers { tags[firstArg()] }
        every { view.setTag(any(), any()) } answers { tags[firstArg()] = secondArg() }
        every { subtitles.getTag(any()) } returns null
        every { subtitles.post(any()) } answers { pending.add(firstArg()); true }
        every { subtitles.setStyle(capture(styles)) } just Runs
        every { subtitles.height } returns 1000
        every { subtitles.paddingLeft } returns 4
        every { subtitles.paddingTop } returns 8
        every { subtitles.paddingRight } returns 12
    }

    @After
    fun cleanupTypefaceBoundary() { unmockkStatic("com.nuvio.tv.ui.screens.player.SubtitleFontsKt") }

    @Test
    fun `plain text applies fixed drop shadow through actual subtitle view`() {
        select(MimeTypes.APPLICATION_SUBRIP)
        view.applySubtitleStyleIfNeeded(custom.copy(
            edgeStyle = com.nuvio.tv.data.local.SubtitleEdgeStyle.DROP_SHADOW
        ))
        assertEquals(CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, styles.last().edgeType)
        assertEquals(SUBTITLE_SHADOW_COLOR, styles.last().edgeColor)
        assertEquals(Color.BLUE, styles.last().backgroundColor)
    }

    @Test
    fun `ASS ignores selected font and drop shadow without resolving custom typeface`() {
        select(MimeTypes.TEXT_SSA)
        view.applySubtitleStyleIfNeeded(custom.copy(
            font = com.nuvio.tv.domain.model.AppFont.SOURCE_SANS_3,
            edgeStyle = com.nuvio.tv.data.local.SubtitleEdgeStyle.DROP_SHADOW
        ))
        assertEquals(CaptionStyleCompat.EDGE_TYPE_NONE, styles.last().edgeType)
        assertNull(styles.last().typeface)
        verify(exactly = 0) { subtitleTypeface(any(), any(), any()) }
        assertEquals(Color.TRANSPARENT, styles.last().backgroundColor)
        verify { subtitles.setApplyEmbeddedStyles(true); subtitles.setApplyEmbeddedFontSizes(true) }
        verify(exactly = 0) { subtitles.setFixedTextSize(any(), any()) }
    }

    private fun select(mime: String, codecs: String? = null) {
        val format = Format.Builder().setSampleMimeType(mime).setCodecs(codecs).build()
        every { player.currentTracks } returns Tracks(listOf(Tracks.Group(
            TrackGroup(format), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true)
        )))
    }

    @Test
    fun `SRT to ASS clears overrides and ignores queued SRT padding`() {
        select(MimeTypes.APPLICATION_SUBRIP)
        view.applySubtitleStyleIfNeeded(custom)
        assertEquals(Color.BLUE, styles.last().backgroundColor)
        verify { subtitles.setFixedTextSize(any(), 38.4f); subtitles.setApplyEmbeddedFontSizes(false) }
        select(MimeTypes.TEXT_SSA)
        view.applySubtitleStyleIfNeeded(custom)
        val ass = styles.last()
        assertEquals(Color.TRANSPARENT, ass.backgroundColor)
        assertEquals(Color.TRANSPARENT, ass.windowColor)
        assertEquals(CaptionStyleCompat.EDGE_TYPE_NONE, ass.edgeType)
        assertNull(ass.typeface)
        verify {
            subtitles.setApplyEmbeddedStyles(true)
            subtitles.setApplyEmbeddedFontSizes(true)
            subtitles.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION)
            subtitles.setBottomPaddingFraction(SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION)
            subtitles.setPadding(4, 8, 12, 0)
        }
        pending.forEach { it.run() }
        verify(exactly = 0) { subtitles.setPadding(4, 8, 12, 100) }
    }

    @Test
    fun `ASS to WebVTT restores customization and latest padding only`() {
        select(MimeTypes.TEXT_SSA)
        view.applySubtitleStyleIfNeeded(custom)
        select(MimeTypes.TEXT_VTT)
        view.applySubtitleStyleIfNeeded(custom)
        view.applySubtitleStyleIfNeeded(custom.copy(verticalOffset = 20))
        pending.forEach { it.run() }
        assertEquals(Color.BLUE, styles.last().backgroundColor)
        assertEquals(CaptionStyleCompat.EDGE_TYPE_OUTLINE, styles.last().edgeType)
        verify { subtitles.setApplyEmbeddedFontSizes(false); subtitles.setPadding(4, 8, 12, 50) }
        verify(exactly = 0) { subtitles.setPadding(4, 8, 12, 100) }
    }

    @Test
    fun `codec-tagged ASS cues use embedded styling`() {
        select(MimeTypes.APPLICATION_MEDIA3_CUES, MimeTypes.TEXT_SSA)
        view.applySubtitleStyleIfNeeded(custom)
        verify { subtitles.setApplyEmbeddedFontSizes(true) }
        verify(exactly = 0) { subtitles.setFixedTextSize(any(), any()) }
    }

    @Test
    fun `forced application invalidates older identical padding configuration`() {
        select(MimeTypes.APPLICATION_SUBRIP)
        view.applySubtitleStyleIfNeeded(custom)
        view.applySubtitleStyleIfNeeded(custom, force = true)
        pending.forEach { it.run() }
        verify(exactly = 1) { subtitles.setPadding(4, 8, 12, 100) }
    }
}
