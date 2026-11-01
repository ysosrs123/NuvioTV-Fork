package com.nuvio.tv.core.player.dvmkv

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.SubtitleParser
import com.nuvio.tv.core.player.DolbyVisionBaseLayerColorTest
import com.nuvio.tv.core.player.DolbyVisionConversionConfig
import com.nuvio.tv.core.player.DolbyVisionMatroskaTransformer
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.io.ByteArrayOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class MatroskaDvBaseLayerColorTest {

    @Before
    fun baseLayerMode() {
        DolbyVisionCompatibility.setHdr10BaseLayerModeActive(true)
    }

    @After
    fun reset() {
        DolbyVisionCompatibility.setHdr10BaseLayerModeActive(false)
    }

    @Test
    fun `stripped profile seven base layer without a Colour element is signalled as HDR10`() {
        val format = videoFormat(profile = 7, compatibility = 6, vuiTransfer = 16)
        assertEquals(MimeTypes.VIDEO_H265, format.sampleMimeType)
        assertEquals(C.COLOR_TRANSFER_ST2084, format.colorInfo!!.colorTransfer)
        assertEquals(C.COLOR_SPACE_BT2020, format.colorInfo!!.colorSpace)
        assertEquals(C.COLOR_RANGE_LIMITED, format.colorInfo!!.colorRange)
    }

    @Test
    fun `stripped profile eight point four base layer is signalled as HLG`() {
        val format = videoFormat(profile = 8, compatibility = 4, vuiTransfer = 18)
        assertEquals(MimeTypes.VIDEO_H265, format.sampleMimeType)
        assertEquals(C.COLOR_TRANSFER_HLG, format.colorInfo!!.colorTransfer)
    }

    @Test
    fun `profile eight point four with a BT2020 VUI still follows its compatibility id`() {
        assertEquals(C.COLOR_TRANSFER_HLG, videoFormat(profile = 8, compatibility = 4, vuiTransfer = 14).colorInfo!!.colorTransfer)
    }

    @Test
    fun `Dolby Vision kept as Dolby Vision gets no added colour info`() {
        DolbyVisionCompatibility.setHdr10BaseLayerModeActive(false)
        val format = videoFormat(profile = 7, compatibility = 6, vuiTransfer = 16)
        assertEquals(MimeTypes.VIDEO_DOLBY_VISION, format.sampleMimeType)
        assertNull(format.colorInfo)
    }

    @Test
    fun `plain HEVC without a Colour element is untouched`() {
        assertNull(videoFormat(profile = null, compatibility = 0, vuiTransfer = 16).colorInfo)
    }

    private fun videoFormat(profile: Int?, compatibility: Int, vuiTransfer: Int): Format {
        val parts = mutableListOf(
            uint(0xD7, 1),
            uint(0x83, 1),
            element(0x86, "V_MPEGH/ISO/HEVC".toByteArray()),
            element(0x63A2, hvcc(sps(vuiTransfer))),
            element(0xE0, uint(0xB0, 3840), uint(0xBA, 2160))
        )
        if (profile != null) {
            parts += element(
                0x41E4,
                uint(0x41E7, 0x64766343L),
                element(0x41ED, DolbyVisionBaseLayerColorTest.dvcc(profile, compatibility))
            )
        }
        val header = element(0x1A45DFA3, element(0x4282, "matroska".toByteArray()), uint(0x4287, 4), uint(0x4285, 2))
        val info = element(0x1549A966, uint(0x2AD7B1, 1_000_000))
        val file = header + element(0x18538067, info, element(0x1654AE6B, element(0xAE, *parts.toTypedArray())))
        return extractVideoFormat(file)
    }

    private fun extractVideoFormat(file: ByteArray): Format {
        var format: Format? = null
        val output = mockk<ExtractorOutput>(relaxed = true)
        every { output.track(any(), any()) } answers {
            val trackOutput = mockk<TrackOutput>(relaxed = true)
            val captured = slot<Format>()
            every { trackOutput.format(capture(captured)) } answers { format = captured.captured }
            trackOutput
        }
        val transformer = DolbyVisionMatroskaTransformer(DolbyVisionConversionConfig(false), stripRpuOnly = true)
        val extractor = MatroskaExtractor(SubtitleParser.Factory.UNSUPPORTED, 0, transformer)
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
                format?.let { return it }
                if (extractor.read(input, holder) == Extractor.RESULT_END_OF_INPUT) return format!!
            }
        } catch (e: ParserException) {
            // android.util.SparseArray is a stub on the JVM, so the extractor finds no tracks once Tracks ends.
            format?.let { return it }
            throw e
        }
        return format!!
    }

    /** A 3840x2160 Main 10 SPS whose VUI declares BT.2020 primaries and matrix and [transfer]. */
    private fun sps(transfer: Int): ByteArray {
        val w = BitWriter()
        w.bits(0, 4); w.bits(0, 3); w.bits(1, 1)
        w.bits(0, 2); w.bits(0, 1); w.bits(2, 5)
        w.bits(0x20000000L, 32)
        w.bits(1, 1); w.bits(0, 1); w.bits(0, 1); w.bits(1, 1)
        w.bits(0, 44)
        w.bits(153, 8)
        w.ue(0); w.ue(1); w.ue(3840); w.ue(2160)
        w.bits(0, 1)
        w.ue(2); w.ue(2); w.ue(4)
        w.bits(1, 1); w.ue(4); w.ue(2); w.ue(0)
        w.ue(0); w.ue(3); w.ue(0); w.ue(3); w.ue(0); w.ue(0)
        w.bits(0, 4)
        w.ue(0)
        w.bits(0, 3)
        w.bits(1, 1)
        w.bits(0, 1); w.bits(0, 1)
        w.bits(1, 1); w.bits(5, 3); w.bits(0, 1); w.bits(1, 1)
        w.bits(9, 8); w.bits(transfer.toLong(), 8); w.bits(9, 8)
        w.bits(0, 1); w.bits(0, 3); w.bits(0, 1); w.bits(0, 1); w.bits(0, 1)
        w.bits(0, 1)
        w.bits(0, 1)
        w.bits(1, 1)
        return byteArrayOf(0x42, 0x01) + escape(w.toBytes())
    }

    private fun hvcc(sps: ByteArray): ByteArray {
        val header = ByteArray(23)
        header[0] = 1
        header[1] = 0x02
        header[12] = 153.toByte()
        header[16] = 0x01
        header[17] = 0x02
        header[18] = 0x02
        header[21] = 0x03
        header[22] = 1
        return header + byteArrayOf((0x80 or 33).toByte(), 0, 1, (sps.size shr 8).toByte(), sps.size.toByte()) + sps
    }

    private fun escape(rbsp: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var zeros = 0
        for (b in rbsp) {
            val v = b.toInt() and 0xFF
            if (zeros == 2 && v <= 3) {
                out.write(3)
                zeros = 0
            }
            out.write(v)
            zeros = if (v == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    private class BitWriter {
        private val out = ByteArrayOutputStream()
        private var current = 0
        private var count = 0

        fun bits(value: Long, n: Int) {
            for (i in n - 1 downTo 0) {
                current = (current shl 1) or ((value ushr i) and 1L).toInt()
                if (++count == 8) {
                    out.write(current)
                    current = 0
                    count = 0
                }
            }
        }

        fun ue(value: Int) {
            val codeNum = value + 1L
            val length = 64 - java.lang.Long.numberOfLeadingZeros(codeNum)
            bits(0, length - 1)
            bits(codeNum, length)
        }

        fun toBytes(): ByteArray {
            if (count > 0) bits(0, 8 - count)
            return out.toByteArray()
        }
    }

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
}
