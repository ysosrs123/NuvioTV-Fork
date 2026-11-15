package com.nuvio.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer

class ImportedSubtitleFontsTest {

    private val fonts = listOf(File("src/main/res/font"), File("app/src/main/res/font")).first { it.isDirectory }

    @Test
    fun `accepts TrueType and OpenType files with a valid table directory`() {
        assertTrue(isSupportedSubtitleFont("Font.ttf", fontBytes(trueType)))
        assertTrue(isSupportedSubtitleFont("Font.OTF", fontBytes(openType)))
    }

    @Test
    fun `rejects other file types, unknown signatures and broken tables`() {
        assertFalse(isSupportedSubtitleFont("Font.woff", fontBytes(trueType)))
        assertFalse(isSupportedSubtitleFont("Font", fontBytes(trueType)))
        assertFalse(isSupportedSubtitleFont("Font.ttf", fontBytes("wOFF".encodeToByteArray())))
        assertFalse(isSupportedSubtitleFont("Font.ttf", fontBytes(trueType, tables = 4).copyOf(40)))
        assertFalse(isSupportedSubtitleFont("Font.ttf", fontBytes(trueType, tableLength = 4_096)))
        assertFalse(isSupportedSubtitleFont("Font.ttf", fontBytes(trueType).copyOf(ImportedSubtitleFonts.MAX_BYTES + 1)))
    }

    @Test
    fun `a real font passes and gives the family name libass looks it up by`() {
        val bytes = File(fonts, "subtitle_montserrat_regular.ttf").readBytes()
        assertTrue(isSupportedSubtitleFont("Montserrat-Regular.ttf", bytes))
        assertEquals("Montserrat", subtitleFontFamilyName(bytes))
    }

    @Test
    fun `line height matches the table the bundled fonts use for MPV sizing`() {
        val montserrat = subtitleFontLineHeightEm(File(fonts, "subtitle_montserrat_regular.ttf").readBytes())
        assertNotNull(montserrat)
        assertEquals(1.379, montserrat!!, 0.01)
        val asap = subtitleFontLineHeightEm(File(fonts, "subtitle_asap_condensed_regular.ttf").readBytes())
        assertEquals(1.319, asap!!, 0.01)
    }

    @Test
    fun `files without names or metrics give null instead of failing`() {
        assertNull(subtitleFontFamilyName(fontBytes(trueType)))
        assertNull(subtitleFontLineHeightEm(fontBytes(trueType)))
    }

    @Test
    fun `only imported ids are accepted`() {
        assertTrue(ImportedSubtitleFonts.isImportedId("custom-0f8fad5b-d9cb-469f-a165-70867728950e"))
        assertFalse(ImportedSubtitleFonts.isImportedId("MONTSERRAT"))
        assertFalse(ImportedSubtitleFonts.isImportedId("custom-../../etc"))
        assertFalse(ImportedSubtitleFonts.isImportedId(null))
    }

    private val trueType = byteArrayOf(0, 1, 0, 0)
    private val openType = "OTTO".encodeToByteArray()

    private fun fontBytes(signature: ByteArray, tables: Int = 1, tableLength: Int = 16): ByteArray {
        val dataOffset = 12 + tables * 16
        val buffer = ByteBuffer.allocate(dataOffset + tables * 16)
        buffer.put(signature)
        buffer.putShort(tables.toShort())
        buffer.putShort(0)
        buffer.putShort(0)
        buffer.putShort(0)
        repeat(tables) { index ->
            buffer.put("tb$index ".take(4).encodeToByteArray())
            buffer.putInt(0)
            buffer.putInt(dataOffset + index * 16)
            buffer.putInt(tableLength)
        }
        return buffer.array()
    }
}
