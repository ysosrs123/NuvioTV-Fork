package com.nuvio.tv.core.player

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.TrackOutput
import io.mockk.*
import org.junit.Assert.*
import org.junit.Test

class HdrColorSignalingTest {
    private fun outputFor(format: Format): Format {
        val delegate = mockk<Extractor>(relaxed = true)
        val downstream = mockk<ExtractorOutput>()
        val track = mockk<TrackOutput>(relaxed = true)
        every { downstream.track(1, C.TRACK_TYPE_VIDEO) } returns track
        val output = slot<ExtractorOutput>()
        every { delegate.init(capture(output)) } just Runs
        HdrColorSignalingExtractor(delegate).init(downstream)
        output.captured.track(1, C.TRACK_TYPE_VIDEO).format(format)
        val captured = slot<Format>()
        verify { track.format(capture(captured)) }
        return captured.captured
    }
    @Test fun `HLG survives plain HEVC and Dolby Vision profile eight base layer handling`() {
        for (codecs in listOf("hvc1.2.4.L153.B0", "dvhe.08.06")) {
            val format = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).setCodecs(codecs)
                .setColorInfo(ColorInfo.Builder().setColorSpace(C.COLOR_SPACE_BT2020)
                    .setColorRange(C.COLOR_RANGE_LIMITED).setColorTransfer(C.COLOR_TRANSFER_HLG).build()).build()
            assertSame(format, outputFor(format))
            assertEquals(C.COLOR_TRANSFER_HLG, outputFor(format).colorInfo!!.colorTransfer)
        }
    }
    @Test fun `missing profile seven HDR metadata still receives PQ and explicit PQ is retained`() {
        val missing = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).setCodecs("dvhe.07.06").build()
        val restored = outputFor(missing)
        assertEquals(C.COLOR_TRANSFER_ST2084, restored.colorInfo!!.colorTransfer)
        assertSame(restored, outputFor(restored))
    }
}
