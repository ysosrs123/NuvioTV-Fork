package com.nuvio.tv.ui.screens.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mkv.MatroskaExtractor as StockMatroskaExtractor
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import com.nuvio.tv.core.player.DolbyVisionConversionConfig
import com.nuvio.tv.core.player.DolbyVisionExtractorsFactory
import com.nuvio.tv.core.player.HdrColorSignalingExtractor
import com.nuvio.tv.core.player.dvmkv.MatroskaExtractor as DvMatroskaExtractor
import io.github.peerless2012.ass.media.AssHandler
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.*
import org.junit.Test

class PlayerSubtitleExtractorTest {
    private val parser = DefaultSubtitleParserFactory()
    private val handler = mockk<AssHandler>(relaxed = true)

    @Test
    fun `stock MKV is replaced and unrelated extractors retain identity`() {
        val other = mockk<Extractor>()
        val factory = ExtractorsFactory { arrayOf(StockMatroskaExtractor(), other) }
        val result = factory.withAssMkvSupportCompat(parser, handler).createExtractors()
        assertTrue(result[0] is NuvioAssMatroskaExtractor)
        assertSame(other, result[1])
    }

    @Test
    fun `real DV factory retains transformer for inactive conversion and HDR strip modes`() {
        for ((active, strip) in listOf(false to false, true to false, false to true, true to true)) {
            val dv = DolbyVisionExtractorsFactory(
                ExtractorsFactory { arrayOf(StockMatroskaExtractor()) },
                DolbyVisionConversionConfig(active = active, forcedMode = 2),
                stripDvRpu = strip,
                stripHdr10PlusSei = true,
                injectHdr10Sei = true
            ).createExtractors().single()
            val original = dv.underlyingImplementation as DvMatroskaExtractor
            val result = ExtractorsFactory { arrayOf(dv) }
                .withAssMkvSupportCompat(parser, handler).createExtractors().single()
            assertEquals(strip && !active, result is HdrColorSignalingExtractor)
            val replacement = result.underlyingImplementation as NuvioAssMatroskaExtractor
            assertSame(original.dolbyVisionSampleTransformer, replacement.dolbyVisionSampleTransformer)
            assertNotNull(replacement.dolbyVisionSampleTransformer)
        }
    }

    @Test
    fun `URI and headers reach real wrapped MKV factory`() {
        val uri = mockk<Uri>()
        val headers = mapOf("Content-Type" to listOf("video/x-matroska"))
        val source = mockk<ExtractorsFactory>()
        every { source.createExtractors(uri, headers) } returns arrayOf(StockMatroskaExtractor())
        val factory = DolbyVisionExtractorsFactory(source, DolbyVisionConversionConfig(false), stripDvRpu = true)
            .withAssMkvSupportCompat(parser, handler)
        val result = factory.createExtractors(uri, headers).single()
        assertTrue(result is HdrColorSignalingExtractor)
        assertTrue(result.underlyingImplementation is NuvioAssMatroskaExtractor)
        verify(exactly = 1) { source.createExtractors(uri, headers) }
        verify(exactly = 0) { source.createExtractors() }
    }

    @Test
    fun `replacement still emits HDR10 signaling and passes audio through`() {
        val result = DolbyVisionExtractorsFactory(
            ExtractorsFactory { arrayOf(StockMatroskaExtractor()) },
            DolbyVisionConversionConfig(false), stripDvRpu = true
        ).withAssMkvSupportCompat(parser, handler).createExtractors().single()
        val output = mockk<ExtractorOutput>(relaxed = true)
        val video = mockk<TrackOutput>(relaxed = true)
        val audio = mockk<TrackOutput>(relaxed = true)
        every { output.track(1, C.TRACK_TYPE_VIDEO) } returns video
        every { output.track(2, C.TRACK_TYPE_AUDIO) } returns audio
        result.init(output)
        val field = DvMatroskaExtractor::class.java.getDeclaredField("extractorOutput").apply { isAccessible = true }
        val wrappedOutput = field.get(result.underlyingImplementation) as ExtractorOutput
        val captured = slot<Format>()
        val format = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).setCodecs("dvhe.07.06").build()
        wrappedOutput.track(1, C.TRACK_TYPE_VIDEO).format(format)
        verify { video.format(capture(captured)) }
        assertEquals(C.COLOR_SPACE_BT2020, captured.captured.colorInfo!!.colorSpace)
        assertEquals(C.COLOR_RANGE_LIMITED, captured.captured.colorInfo!!.colorRange)
        assertEquals(C.COLOR_TRANSFER_ST2084, captured.captured.colorInfo!!.colorTransfer)
        assertEquals(format.codecs, captured.captured.codecs)
        assertSame(audio, wrappedOutput.track(2, C.TRACK_TYPE_AUDIO))
        result.release()
    }
}
