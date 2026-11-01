package com.nuvio.tv.core.player

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.text.TextRenderer
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mkv.MatroskaExtractor as StockMatroskaExtractor
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import com.nuvio.tv.libass.media.AssHandler
import com.nuvio.tv.libass.media.parser.AssSubtitleParserFactory
import com.nuvio.tv.ui.screens.player.withAssMkvSupportCompat
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderTimeSubtitlesTest {

    private fun format(mime: String) = Format.Builder().setSampleMimeType(mime).build()

    @Test
    fun `PGS is always left for the renderer`() {
        assertTrue(RenderTimeSubtitles.decodedAtRenderTime(format(MimeTypes.APPLICATION_PGS), false))
        assertTrue(RenderTimeSubtitles.decodedAtRenderTime(format(MimeTypes.APPLICATION_PGS), true))
    }

    @Test
    fun `SRT and VobSub stay parsed while AutoSync reads text timings`() {
        for (mime in listOf(MimeTypes.APPLICATION_SUBRIP, MimeTypes.APPLICATION_VOBSUB)) {
            assertTrue(RenderTimeSubtitles.decodedAtRenderTime(format(mime), autoSyncObservesText = false))
            assertFalse(RenderTimeSubtitles.decodedAtRenderTime(format(mime), autoSyncObservesText = true))
        }
    }

    @Test
    fun `ASS and other formats stay parsed during extraction`() {
        for (mime in listOf(MimeTypes.TEXT_SSA, MimeTypes.TEXT_VTT, MimeTypes.APPLICATION_TX3G, MimeTypes.APPLICATION_DVBSUBS)) {
            assertFalse(RenderTimeSubtitles.decodedAtRenderTime(format(mime), false))
        }
        assertFalse(RenderTimeSubtitles.decodedAtRenderTime(Format.Builder().build(), false))
    }

    @Test
    fun `the extraction factory hides render-time formats and forwards the rest`() {
        val parser = mockk<SubtitleParser>()
        val delegate = mockk<SubtitleParser.Factory>()
        every { delegate.supportsFormat(any()) } returns true
        every { delegate.getCueReplacementBehavior(any()) } returns Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE
        every { delegate.create(any()) } returns parser
        val factory = RenderTimeSubtitles.extractionParsers(delegate, autoSyncObservesText = false)

        assertFalse(factory.supportsFormat(format(MimeTypes.APPLICATION_PGS)))
        assertFalse(factory.supportsFormat(format(MimeTypes.APPLICATION_SUBRIP)))
        assertTrue(factory.supportsFormat(format(MimeTypes.TEXT_SSA)))
        assertSame(parser, factory.create(format(MimeTypes.TEXT_SSA)))
        assertEquals(
            Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE,
            factory.getCueReplacementBehavior(format(MimeTypes.TEXT_SSA))
        )

        every { delegate.supportsFormat(any()) } returns false
        assertFalse(factory.supportsFormat(format(MimeTypes.TEXT_SSA)))
    }

    @Test
    fun `text renderers get their own decoders and other renderers are left alone`() {
        val before = TextRenderer(mockk<TextOutput>(relaxed = true), null)
        val text = TextRenderer(mockk<TextOutput>(relaxed = true), null)
        val other = mockk<Renderer>()
        val renderers = listOf<Renderer>(before, other, text)

        RenderTimeSubtitles.enableRenderTimeDecoding(renderers, fromIndex = 1)

        assertFalse(before.legacyDecodingEnabled())
        assertTrue(text.legacyDecodingEnabled())
    }

    @Test
    fun `a remux keeps PGS SRT and VobSub raw`() {
        val formats = extractFormats(dolbyVisionMkv(autoSync = false), subtitleRemux(withAss = false), expectedTracks = 3)

        assertEquals(MimeTypes.APPLICATION_PGS, formats.getValue(1).sampleMimeType)
        assertEquals(MimeTypes.APPLICATION_SUBRIP, formats.getValue(2).sampleMimeType)
        assertEquals(MimeTypes.APPLICATION_VOBSUB, formats.getValue(3).sampleMimeType)
        assertEquals("en", formats.getValue(1).language)
        assertEquals(C.SELECTION_FLAG_FORCED, formats.getValue(2).selectionFlags and C.SELECTION_FLAG_FORCED)
    }

    @Test
    fun `with AutoSync on only PGS is left raw`() {
        val formats = extractFormats(dolbyVisionMkv(autoSync = true), subtitleRemux(withAss = false), expectedTracks = 3)

        assertEquals(MimeTypes.APPLICATION_PGS, formats.getValue(1).sampleMimeType)
        assertEquals(MimeTypes.APPLICATION_MEDIA3_CUES, formats.getValue(2).sampleMimeType)
        assertEquals(MimeTypes.APPLICATION_SUBRIP, formats.getValue(2).codecs)
        assertEquals(MimeTypes.APPLICATION_MEDIA3_CUES, formats.getValue(3).sampleMimeType)
        assertEquals(MimeTypes.APPLICATION_VOBSUB, formats.getValue(3).codecs)
    }

    @Test
    fun `the libass extractor keeps PGS raw and still hands ASS to libass`() {
        val handler = mockk<AssHandler>(relaxed = true)
        val parsers = RenderTimeSubtitles.extractionParsers(AssSubtitleParserFactory(handler), false)
        val extractor = ExtractorsFactory { arrayOf(StockMatroskaExtractor()) }
            .withAssMkvSupportCompat(parsers, handler)
            .createExtractors()
            .single()

        val formats = extractFormats(extractor, subtitleRemux())

        assertEquals(MimeTypes.APPLICATION_PGS, formats.getValue(1).sampleMimeType)
        assertEquals(MimeTypes.APPLICATION_SUBRIP, formats.getValue(2).sampleMimeType)
        assertEquals(MimeTypes.APPLICATION_MEDIA3_CUES, formats.getValue(4).sampleMimeType)
        assertEquals(MimeTypes.TEXT_SSA, formats.getValue(4).codecs)
        verify(exactly = 1) { handler.createTrack(any()) }
    }

    private fun TextRenderer.legacyDecodingEnabled(): Boolean =
        TextRenderer::class.java.getDeclaredField("legacyDecodingEnabled")
            .apply { isAccessible = true }
            .getBoolean(this)

    private fun dolbyVisionMkv(autoSync: Boolean): Extractor =
        DolbyVisionExtractorsFactory(
            ExtractorsFactory { arrayOf(StockMatroskaExtractor()) },
            DolbyVisionConversionConfig(active = false),
            autoSyncObservesText = autoSync
        ).createExtractors().single()

    // SsaParser needs android.text.TextUtils, a stub in JVM tests, so ASS is only parsed by the libass factory here.
    private fun subtitleRemux(withAss: Boolean = true): ByteArray = mkv(
        *listOfNotNull(
            trackEntry(1, "S_HDMV/PGS", language("eng")),
            trackEntry(2, "S_TEXT/UTF8", uint(0x55AA, 1)),
            trackEntry(3, "S_VOBSUB", codecPrivate(VOBSUB_IDX)),
            if (withAss) trackEntry(4, "S_TEXT/ASS", codecPrivate(ASS_HEADER)) else null
        ).toTypedArray()
    )

    private fun extractFormats(extractor: Extractor, file: ByteArray, expectedTracks: Int = 4): Map<Int, Format> {
        val formats = mutableMapOf<Int, Format>()
        val output = mockk<ExtractorOutput>(relaxed = true)
        every { output.track(any(), any()) } answers {
            val id = firstArg<Int>()
            val trackOutput = mockk<TrackOutput>(relaxed = true)
            val captured = slot<Format>()
            every { trackOutput.format(capture(captured)) } answers { formats[id] = captured.captured }
            trackOutput
        }
        extractor.init(output)
        var position = 0
        val reader = object : DataReader {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (position >= file.size) return C.RESULT_END_OF_INPUT
                val count = minOf(length, file.size - position)
                System.arraycopy(file, position, buffer, offset, count)
                position += count
                return count
            }
        }
        val input = DefaultExtractorInput(reader, 0, file.size.toLong())
        val holder = PositionHolder()
        try {
            repeat(50) {
                if (formats.size == expectedTracks) return formats
                if (extractor.read(input, holder) == Extractor.RESULT_END_OF_INPUT) return formats
            }
        } catch (e: ParserException) {
            // android.util.SparseArray is a no-op stub in JVM tests, so the extractor finds no tracks when Tracks
            // ends; every format has been published by then.
            if (formats.size != expectedTracks) throw e
        }
        assertEquals(expectedTracks, formats.size)
        return formats
    }

    private fun mkv(vararg trackEntries: ByteArray): ByteArray {
        val header = element(
            0x1A45DFA3,
            element(0x4282, "matroska".toByteArray()),
            uint(0x4287, 4),
            uint(0x4285, 2)
        )
        val info = element(0x1549A966, uint(0x2AD7B1, 1_000_000))
        val tracks = element(0x1654AE6B, *trackEntries)
        return header + element(0x18538067, info, tracks)
    }

    private fun trackEntry(number: Int, codecId: String, vararg extra: ByteArray): ByteArray {
        val parts = mutableListOf(
            uint(0xD7, number.toLong()),
            uint(0x83, TYPE_SUBTITLE.toLong()),
            element(0x86, codecId.toByteArray())
        )
        parts += extra
        return element(0xAE, *parts.toTypedArray())
    }

    private fun language(value: String) = element(0x22B59C, value.toByteArray())

    private fun codecPrivate(value: String) = element(0x63A2, value.toByteArray())

    private fun uint(id: Int, value: Long): ByteArray {
        val bytes = ByteArray(8) { i -> (value ushr (8 * (7 - i))).toByte() }
        return element(id, bytes.copyOfRange(bytes.indexOfFirst { it != 0.toByte() }.coerceIn(0, 7), 8))
    }

    private fun uint(id: Int, value: Int) = uint(id, value.toLong())

    private fun element(id: Int, vararg payload: ByteArray): ByteArray {
        val body = payload.fold(ByteArray(0)) { acc, part -> acc + part }
        val out = ByteArrayOutputStream()
        val idBytes = when {
            id > 0xFFFFFF -> 4
            id > 0xFFFF -> 3
            id > 0xFF -> 2
            else -> 1
        }
        for (i in idBytes - 1 downTo 0) out.write((id ushr (8 * i)) and 0xFF)
        out.write(0x01)
        val size = body.size.toLong()
        for (i in 6 downTo 0) out.write(((size ushr (8 * i)) and 0xFF).toInt())
        out.write(body)
        return out.toByteArray()
    }

    private companion object {
        const val TYPE_SUBTITLE = 0x11
        const val VOBSUB_IDX = "size: 720x480\npalette: " +
            "000000, 828282, 828282, 828282, 828282, 828282, 828282, ffffff, " +
            "828282, bababa, 828282, 828282, 828282, 828282, 828282, 828282\n"
        const val ASS_HEADER = "[Script Info]\nScriptType: v4.00+\n\n[V4+ Styles]\n" +
            "Format: Name, Fontname, Fontsize\nStyle: Default,Arial,20\n\n" +
            "[Events]\nFormat: Layer, Start, End, Style, Text\n"
    }
}
