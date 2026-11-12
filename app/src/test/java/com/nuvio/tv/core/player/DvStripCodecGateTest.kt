package com.nuvio.tv.core.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.*
import io.mockk.*
import org.junit.After
import org.junit.Before
import org.junit.Test

class DvStripCodecGateTest {
    private class TestMp4Extractor : Extractor {
        lateinit var output: TrackOutput
        override fun init(output: ExtractorOutput) { this.output = output.track(0, C.TRACK_TYPE_VIDEO) }
        override fun sniff(input: ExtractorInput) = true
        override fun read(input: ExtractorInput, seekPosition: PositionHolder) = C.RESULT_END_OF_INPUT
        override fun seek(position: Long, timeUs: Long) {}
        override fun release() {}
    }

    @Before fun mockBridge() {
        mockkObject(DoviBridge)
        every { DoviBridge.isAvailable() } returns false
        every { DoviBridge.processVideoSampleNonAllocating(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 0
    }

    @After fun unmockBridge() = unmockkObject(DoviBridge)

    private fun setup(): Pair<TrackOutput, TrackOutput> {
        val source = TestMp4Extractor()
        val downstream = mockk<TrackOutput>(relaxed = true)
        val output = mockk<ExtractorOutput>(relaxed = true)
        every { output.track(0, C.TRACK_TYPE_VIDEO) } returns downstream
        DolbyVisionExtractorsFactory(
            ExtractorsFactory { arrayOf(source) },
            DolbyVisionConversionConfig(active = false),
            stripDvRpu = true,
            stripHdr10PlusSei = true
        ).createExtractors().single().init(output)
        return source.output to downstream
    }

    private fun assertPassesThroughUntouched(mimeType: String, codecs: String, sample: ByteArray) {
        val (wrapped, downstream) = setup()
        val format = Format.Builder().setSampleMimeType(mimeType).setCodecs(codecs).build()
        wrapped.format(format)
        val data = ParsableByteArray(sample)
        wrapped.sampleData(data, sample.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
        wrapped.sampleMetadata(0, C.BUFFER_FLAG_KEY_FRAME, sample.size, 0, null)
        verify(exactly = 1) { downstream.format(refEq(format)) }
        verify(exactly = 1) { downstream.sampleData(refEq(data), sample.size, TrackOutput.SAMPLE_DATA_PART_MAIN) }
        verify(exactly = 1) { downstream.sampleMetadata(0, C.BUFFER_FLAG_KEY_FRAME, sample.size, 0, null) }
        verify(exactly = 0) {
            DoviBridge.processVideoSampleNonAllocating(any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    private fun assertProcessed(mimeType: String, codecs: String) {
        val (wrapped, _) = setup()
        val sample = byteArrayOf(0, 0, 0, 4, 0x4e, 1, 4, 0x80.toByte())
        wrapped.format(Format.Builder().setSampleMimeType(mimeType).setCodecs(codecs).build())
        wrapped.sampleData(ParsableByteArray(sample), sample.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
        wrapped.sampleMetadata(0, C.BUFFER_FLAG_KEY_FRAME, sample.size, 0, null)
        verify(exactly = 1) {
            DoviBridge.processVideoSampleNonAllocating(any(), sample.size, any(), any(), any(), any(), any(), true, true)
        }
    }

    @Test fun h264SamplePassesThroughUntouched() {
        // SEI then a key-frame slice.
        assertPassesThroughUntouched(
            "video/avc", "avc1.64001F",
            byteArrayOf(0, 0, 0, 2, 0x06, 0x05, 0, 0, 0, 2, 0x65, 0x88.toByte())
        )
    }

    @Test fun mpeg2SamplePassesThroughUntouched() {
        assertPassesThroughUntouched("video/mpeg2", "mp2v", byteArrayOf(0, 0, 1, 0xb3.toByte(), 0x78, 0x04))
    }

    @Test fun av1SamplePassesThroughUntouched() {
        assertPassesThroughUntouched("video/av01", "av01.0.08M.10", byteArrayOf(0x12, 0, 0x0a, 0x0b, 0, 0))
    }

    @Test fun av1DolbyVisionSamplePassesThroughUntouched() {
        assertPassesThroughUntouched("video/dolby-vision", "dav1.10.06", byteArrayOf(0x12, 0, 0x0a, 0x0b, 0, 0))
    }

    @Test fun hevcSampleIsStillProcessed() {
        assertProcessed("video/hevc", "hvc1.2.4.L153.B0")
    }

    @Test fun dolbyVisionHevcSampleIsStillProcessed() {
        assertProcessed("video/dolby-vision", "dvhe.08.06")
    }

    @Test fun dolbyVisionDvh1SampleIsStillProcessed() {
        assertProcessed("video/dolby-vision", "dvh1.08.06")
    }
}
