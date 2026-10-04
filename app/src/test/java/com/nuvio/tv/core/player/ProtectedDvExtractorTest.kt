package com.nuvio.tv.core.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.*
import io.mockk.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ProtectedDvExtractorTest {
    // The factory dispatches by container class name, as it does for Media3's Mp4Extractor.
    private class TestMp4Extractor : Extractor {
        lateinit var output: TrackOutput
        override fun init(output: ExtractorOutput) { this.output = output.track(0, C.TRACK_TYPE_VIDEO) }
        override fun sniff(input: ExtractorInput) = true
        override fun read(input: ExtractorInput, seekPosition: PositionHolder) = C.RESULT_END_OF_INPUT
        override fun seek(position: Long, timeUs: Long) {}
        override fun release() {}
    }
    private fun setup(): Pair<TrackOutput, TrackOutput> {
        val source = TestMp4Extractor()
        val downstream = mockk<TrackOutput>(relaxed = true)
        val output = mockk<ExtractorOutput>(relaxed = true)
        every { output.track(0, C.TRACK_TYPE_VIDEO) } returns downstream
        val factory = DolbyVisionExtractorsFactory(
            ExtractorsFactory { arrayOf(source) }, DolbyVisionConversionConfig(active = true)
        )
        factory.createExtractors().single().init(output)
        return source.output to downstream
    }
    @Test fun protectedFormatAndEncryptedSamplePassThroughUnchanged() {
        val (wrapped, downstream) = setup()
        val format = Format.Builder().setSampleMimeType("video/dolby-vision")
            .setCodecs("dvhe.07.06").setCryptoType(1).build()
        wrapped.format(format)
        val data = ParsableByteArray(byteArrayOf(0,0,0,2,124,1))
        wrapped.sampleData(data,6,TrackOutput.SAMPLE_DATA_PART_MAIN)
        val crypto = mockk<TrackOutput.CryptoData>()
        wrapped.sampleMetadata(0,C.BUFFER_FLAG_ENCRYPTED,6,0,crypto)
        verify(exactly=1) { downstream.format(refEq(format)) }
        verify(exactly=1) { downstream.sampleData(refEq(data),6,TrackOutput.SAMPLE_DATA_PART_MAIN) }
        verify(exactly=1) { downstream.sampleMetadata(0,C.BUFFER_FLAG_ENCRYPTED,6,0,refEq(crypto)) }
    }
    @Test fun unexpectedEncryptionAfterClearLeadFailsBeforeRewriting() {
        val (wrapped, downstream) = setup()
        wrapped.format(Format.Builder().setSampleMimeType("video/dolby-vision").setCodecs("dvhe.07.06").build())
        wrapped.sampleData(ParsableByteArray(byteArrayOf(0,0,0,2,124,1)),6,TrackOutput.SAMPLE_DATA_PART_MAIN)
        try {
            wrapped.sampleMetadata(0,C.BUFFER_FLAG_ENCRYPTED,6,0,null)
            fail("Unexpected encrypted sample must not enter the clear DV transform")
        } catch (expected: IOException) { }
        verify(exactly=0) { downstream.sampleMetadata(any(),any(),any(),any(),any()) }
    }
}
