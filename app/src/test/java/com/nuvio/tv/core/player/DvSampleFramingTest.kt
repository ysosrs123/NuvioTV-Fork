package com.nuvio.tv.core.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.*
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DvSampleFramingTest {
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

    private fun framingSentToBridge(sample: ByteArray): Int {
        val source = TestMp4Extractor()
        val output = mockk<ExtractorOutput>(relaxed = true)
        every { output.track(0, C.TRACK_TYPE_VIDEO) } returns mockk(relaxed = true)
        DolbyVisionExtractorsFactory(ExtractorsFactory { arrayOf(source) }, DolbyVisionConversionConfig(active = true))
            .createExtractors().single().init(output)
        source.output.format(Format.Builder().setSampleMimeType("video/dolby-vision").setCodecs("dvhe.07.06").build())
        source.output.sampleData(ParsableByteArray(sample.copyOf()), sample.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
        source.output.sampleMetadata(0, C.BUFFER_FLAG_KEY_FRAME, sample.size, 0, null)
        val framing = slot<Int>()
        verify { DoviBridge.processVideoSampleNonAllocating(any(), sample.size, capture(framing), any(), any(), any(), any(), any(), any()) }
        return framing.captured
    }

    @Test fun mp4ExtractorStartCodesGoToTheBridgeAsAnnexB() {
        assertEquals(0, framingSentToBridge(byteArrayOf(0, 0, 0, 1, 0x7c, 1, 0x19, 0x80.toByte())))
    }

    @Test fun lengthPrefixedSampleStaysLengthDelimited() {
        assertEquals(1, framingSentToBridge(byteArrayOf(0, 0, 0, 4, 0x7c, 1, 0x19, 0x80.toByte())))
    }

    @Test fun lengthPrefixOfA300ByteNalIsNotAStartCode() {
        assertEquals(1, framingSentToBridge(byteArrayOf(0, 0, 1, 0x2c, 0x7c, 1, 0x19, 0x80.toByte())))
    }

    @Test fun tooShortSampleHasNoStartCode() {
        assertFalse(hasFourByteStartCode(byteArrayOf(0, 0, 0), 3))
    }
}
