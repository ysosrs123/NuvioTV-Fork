package com.nuvio.tv.ui.screens.player.autosync

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.TrackOutput
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AutoSyncExtractorsFactoryTest {
    private class TextTrackExtractor : Extractor {
        lateinit var output: TrackOutput
        override fun init(output: ExtractorOutput) { this.output = output.track(1, C.TRACK_TYPE_TEXT) }
        override fun sniff(input: ExtractorInput) = true
        override fun read(input: ExtractorInput, seekPosition: PositionHolder) = C.RESULT_END_OF_INPUT
        override fun seek(position: Long, timeUs: Long) {}
        override fun release() {}
    }

    private val player = mockk<TrackOutput>(relaxed = true)

    private fun observedTrack(mime: String): TrackOutput {
        val source = TextTrackExtractor()
        val output = mockk<ExtractorOutput>(relaxed = true)
        every { output.track(1, C.TRACK_TYPE_TEXT) } returns player
        AutoSyncExtractorsFactory(ExtractorsFactory { arrayOf(source) }, "https://host/extractor-test.mkv")
            .createExtractors().single().init(output)
        source.output.format(Format.Builder().setSampleMimeType(mime).build())
        return source.output
    }

    @Test
    fun pgsSampleBytesGoStraightToThePlayer() {
        val track = observedTrack("application/pgs")
        val input = mockk<DataReader>()
        every { player.sampleData(input, 8, false, TrackOutput.SAMPLE_DATA_PART_MAIN) } returns 8

        assertEquals(8, track.sampleData(input, 8, false, TrackOutput.SAMPLE_DATA_PART_MAIN))

        verify(exactly = 0) { input.read(any(), any(), any()) }
        verify(exactly = 0) { player.sampleData(any<ParsableByteArray>(), any(), any()) }
    }

    @Test
    fun textSampleBytesReachThePlayerUnchanged() {
        val track = observedTrack(MimeTypes.APPLICATION_MEDIA3_CUES)
        val bytes = byteArrayOf(1, 2, 3, 4)
        val input = DataReader { buffer, offset, length ->
            bytes.copyInto(buffer, offset, 0, length)
            length
        }
        val forwarded = slot<ParsableByteArray>()
        every { player.sampleData(capture(forwarded), 4, TrackOutput.SAMPLE_DATA_PART_MAIN) } just Runs

        assertEquals(4, track.sampleData(input, 4, false, TrackOutput.SAMPLE_DATA_PART_MAIN))

        assertArrayEquals(bytes, forwarded.captured.data.copyOf(4))
    }
}
