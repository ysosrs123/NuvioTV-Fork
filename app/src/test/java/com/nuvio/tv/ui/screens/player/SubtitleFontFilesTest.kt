package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.R
import com.nuvio.tv.data.local.SubtitleEdgeStyle
import com.nuvio.tv.data.local.SubtitleStyleSettings
import com.nuvio.tv.domain.model.AppFont
import com.nuvio.tv.domain.model.SubtitleFont
import java.io.File
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class SubtitleFontFilesTest {
    private val fontDir = listOf(File("src/main/res/font"), File("app/src/main/res/font")).first { it.isDirectory }
    private val resourceNames = R.font::class.java.fields.associate { it.getInt(null) to it.name }

    private class Names(val families: Set<String>, val fullNames: Set<String>, val weight: Int, val variable: Boolean)

    private fun names(resource: Int): Names {
        val file = File(fontDir, "${requireNotNull(resourceNames[resource])}.ttf")
        val data = ByteBuffer.wrap(file.readBytes())
        assertEquals("${file.name} is not a TrueType font", 0x00010000, data.getInt(0))
        val tables = (0 until data.getShort(4).toInt()).associate { i ->
            val record = 12 + 16 * i
            String(data.array(), record, 4, Charsets.US_ASCII) to data.getInt(record + 8)
        }
        val nameOffset = tables.getValue("name")
        val count = data.getShort(nameOffset + 2).toInt() and 0xFFFF
        val storage = nameOffset + (data.getShort(nameOffset + 4).toInt() and 0xFFFF)
        val families = mutableSetOf<String>()
        val fullNames = mutableSetOf<String>()
        for (i in 0 until count) {
            val record = nameOffset + 6 + 12 * i
            if (data.getShort(record).toInt() != 3) continue
            val id = data.getShort(record + 6).toInt()
            val length = data.getShort(record + 8).toInt() and 0xFFFF
            val offset = data.getShort(record + 10).toInt() and 0xFFFF
            val text = String(data.array(), storage + offset, length, Charsets.UTF_16BE).lowercase()
            if (id == 1) families += text
            if (id == 4) fullNames += text
        }
        return Names(families, fullNames, data.getShort(tables.getValue("OS/2") + 4).toInt(), "fvar" in tables)
    }

    @Test fun `every subtitle font has bundled files and the UI font list is unchanged`() {
        assertEquals(listOf("Inter", "DM Sans", "Open Sans", "Atkinson Hyperlegible Next", "Source Sans 3"),
            AppFont.entries.map { it.displayName })
        assertEquals(AppFont.entries.map { it.name }, SubtitleFont.entries.mapNotNull { it.appFont?.name })
        for (font in AppFont.entries) assertEquals(font.name, SubtitleFont.entries.single { it.appFont == font }.name)
        for (resource in mpvSubtitleFontResources()) assertTrue(File(fontDir, "${resourceNames[resource]}.ttf").isFile)
        assertEquals(mpvSubtitleFontResources().size, mpvSubtitleFontResources().toSet().size)
        for (font in SubtitleFont.entries) {
            assertTrue(font.fontResource() in mpvSubtitleFontResources())
            font.boldFontResource()?.let { assertTrue(it in mpvSubtitleFontResources()) }
        }
    }

    @Test fun `MPV names match the family libass will find in each file`() {
        for (font in SubtitleFont.entries - SubtitleFont.NOTO_SANS_SEMIBOLD) for (bold in listOf(false, true)) {
            val name = font.mpvFontName(bold).lowercase()
            assertTrue("$font", name in names(font.fontResource()).families)
            font.boldFontResource()?.let { assertTrue("$font bold", name in names(it).families) }
            assertEquals(bold, font.mpvBold(bold))
        }
    }

    @Test fun `MPV Noto keeps Latin first and finds every bundled script under the same name`() {
        val noto = SubtitleFont.NOTO_SANS_SEMIBOLD
        for (bold in listOf(false, true)) {
            val name = noto.mpvFontName(bold).lowercase()
            val latin = names(if (bold) R.font.subtitle_noto_sans_bold else R.font.subtitle_noto_sans_semibold)
            assertTrue(name in latin.fullNames)
            assertFalse(name in latin.families)
            for ((semiBold, boldFile) in NOTO_SUBTITLE_SCRIPT_FONTS) {
                assertTrue(name in names(if (bold) boldFile else semiBold).families)
                assertFalse(name in names(if (bold) semiBold else boldFile).families)
            }
            assertFalse(noto.mpvBold(bold))
        }
        assertEquals(600, subtitleFontWeight(noto, false))
        assertEquals(700, subtitleFontWeight(noto, true))
        assertEquals(400, subtitleFontWeight(SubtitleFont.ROBOTO, false))
        assertEquals(400, subtitleFontWeight(null, false))
    }

    @Test fun `renderer default still reaches the MPV fallback font only`() {
        val name = (null as SubtitleFont?).mpvFontName(false).lowercase()
        for (resource in mpvSubtitleFontResources()) {
            val file = names(resource)
            assertFalse(name in file.families || name in file.fullNames)
        }
        assertEquals(MPV_RENDERER_DEFAULT_SUBTITLE_FONT, (null as SubtitleFont?).mpvFontName(true))
        assertTrue((null as SubtitleFont?).mpvBold(true))
    }

    @Test fun `every subtitle font and bold setting gives a real file at the right weight`() {
        for (font in SubtitleFont.entries) for (bold in listOf(false, true)) {
            val face = font.face(bold)
            val file = names(face.resource)
            assertEquals("$font $bold", if (bold) 700 else font.regularWeight(), face.weight)
            if (face.weightFromFile) assertEquals("$font $bold", face.weight, file.weight)
            else assertTrue("$font $bold needs a weight axis", file.variable)
            assertTrue(font.mpvFontName(bold).isNotBlank())
        }
        assertEquals(R.font.subtitle_noto_sans_bold, SubtitleFont.NOTO_SANS_SEMIBOLD.face(true).resource)
        assertEquals(R.font.subtitle_asap_condensed_bold, SubtitleFont.ASAP_CONDENSED.face(true).resource)
        assertEquals(R.font.subtitle_montserrat_bold, SubtitleFont.MONTSERRAT.face(true).resource)
        for ((semiBold, boldFile) in NOTO_SUBTITLE_SCRIPT_FONTS) {
            assertEquals(600, names(semiBold).weight)
            assertEquals(700, names(boldFile).weight)
        }
    }

    @Test fun `MPV colours borders shadow size and position do not depend on the font`() {
        for (edge in SubtitleEdgeStyle.entries) for (ass in listOf(false, true)) for (bold in listOf(false, true)) {
            val base = SubtitleStyleSettings(edgeStyle = edge, bold = bold, textColor = 0x11223344, outlineColor = 0x55667788,
                backgroundColor = 0x7F000000, size = 135, verticalOffset = 25)
            val reference = mpvSubtitleEdges(base, ass)
            for (font in SubtitleFont.entries) assertEquals("$font $edge", reference, mpvSubtitleEdges(base.copy(font = font), ass))
        }
    }
}
