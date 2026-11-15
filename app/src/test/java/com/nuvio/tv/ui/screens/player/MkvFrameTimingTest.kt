package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.TrackOutput
import com.nuvio.tv.core.player.FrameRateUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MkvFrameTimingTest {

    // I, then groups of P8 B4 B2 B1 B3 B6 B5 B7, with Matroska's whole-millisecond times.
    private fun mkvDecodeOrder(sampleCount: Int, frameUs: Double): List<Long> {
        val order = ArrayList<Long>()
        order += 0L
        var base = 0
        while (order.size < sampleCount) {
            for (offset in intArrayOf(8, 4, 2, 1, 3, 6, 5, 7)) {
                if (order.size == sampleCount) break
                order += Math.round((base + offset) * frameUs / 1000.0) * 1000L
            }
            base += 8
        }
        return order
    }

    private fun snappedRate(frameUs: Double, count: Int = MKV_FRAME_TIMING_SAMPLES): Float? =
        frameRateFromSampleTimes(mkvDecodeOrder(count, frameUs))?.let(FrameRateUtils::snapToStandardRate)

    private fun timedThroughOutput(frameUs: Double): List<Float> {
        val rates = ArrayList<Float>()
        val output = MkvFrameTimingTrackOutput(NoOpTrackOutput(), rates::add)
        output.format(videoFormat(frameRate = Format.NO_VALUE.toFloat()))
        mkvDecodeOrder(1_000, frameUs).forEach { output.sampleMetadata(it, 0, 1, 0, null) }
        return rates
    }

    @Test
    fun `23_976 with millisecond times is not mistaken for 24`() {
        assertEquals(24_000f / 1001f, snappedRate(1_001_000.0 / 24.0)!!, 0.001f)
    }

    @Test
    fun `24 with millisecond times stays 24`() {
        assertEquals(24f, snappedRate(1_000_000.0 / 24.0)!!, 0.001f)
    }

    @Test
    fun `25 and 50 read as themselves`() {
        assertEquals(25f, snappedRate(40_000.0)!!, 0.001f)
        assertEquals(50f, snappedRate(20_000.0)!!, 0.001f)
    }

    @Test
    fun `59_94 and 60 with millisecond times are told apart over the timing span`() {
        val ntsc = timedThroughOutput(1_001_000.0 / 60.0)
        val sixty = timedThroughOutput(1_000_000.0 / 60.0)
        assertEquals(1, ntsc.size)
        assertEquals(1, sixty.size)
        assertEquals(60_000f / 1001f, FrameRateUtils.snapToStandardRate(ntsc.single()), 0.001f)
        assertEquals(60f, FrameRateUtils.snapToStandardRate(sixty.single()), 0.001f)
    }

    @Test
    fun `timing waits for the span, not only the sample count`() {
        val rates = ArrayList<Float>()
        val output = MkvFrameTimingTrackOutput(NoOpTrackOutput(), rates::add)
        output.format(videoFormat(frameRate = Format.NO_VALUE.toFloat()))
        mkvDecodeOrder(MKV_FRAME_TIMING_SAMPLES, 1_001_000.0 / 60.0).forEach { output.sampleMetadata(it, 0, 1, 0, null) }
        assertTrue(rates.isEmpty())
    }

    @Test
    fun `variable frame rate gives no rate`() {
        val film = mkvDecodeOrder(60, 1_001_000.0 / 24.0)
        val start = film.max() + 33_000L
        val video = mkvDecodeOrder(60, 1_001_000.0 / 30.0).map { it + start }
        assertNull(frameRateFromSampleTimes(film + video))
    }

    @Test
    fun `too few samples give no rate`() {
        assertNull(frameRateFromSampleTimes(mkvDecodeOrder(40, 41_708.0)))
    }

    @Test
    fun `an MKV without a stated rate is timed once`() {
        val rates = ArrayList<Float>()
        val output = MkvFrameTimingTrackOutput(NoOpTrackOutput(), rates::add)
        output.format(videoFormat(frameRate = Format.NO_VALUE.toFloat()))
        val times = mkvDecodeOrder(MKV_FRAME_TIMING_SAMPLES * 2, 1_001_000.0 / 24.0)
        times.forEach { output.sampleMetadata(it, 0, 1, 0, null) }
        assertEquals(1, rates.size)
        assertEquals(24_000f / 1001f, FrameRateUtils.snapToStandardRate(rates.single()), 0.001f)
    }

    @Test
    fun `an MKV with a stated rate is left alone`() {
        val rates = ArrayList<Float>()
        val output = MkvFrameTimingTrackOutput(NoOpTrackOutput(), rates::add)
        output.format(videoFormat(frameRate = 23.976f))
        mkvDecodeOrder(MKV_FRAME_TIMING_SAMPLES, 41_708.0).forEach { output.sampleMetadata(it, 0, 1, 0, null) }
        assertTrue(rates.isEmpty())
    }

    @Test
    fun `a seek starts the timing over`() {
        val rates = ArrayList<Float>()
        val output = MkvFrameTimingTrackOutput(NoOpTrackOutput(), rates::add)
        output.format(videoFormat(frameRate = Format.NO_VALUE.toFloat()))
        mkvDecodeOrder(MKV_FRAME_TIMING_SAMPLES / 2, 41_708.0).forEach { output.sampleMetadata(it, 0, 1, 0, null) }
        output.restart()
        mkvDecodeOrder(MKV_FRAME_TIMING_SAMPLES, 41_708.0).forEach {
            output.sampleMetadata(it + 600_000_000L, 0, 1, 0, null)
        }
        assertEquals(1, rates.size)
        assertEquals(24_000f / 1001f, FrameRateUtils.snapToStandardRate(rates.single()), 0.001f)
    }

    @Test
    fun `1080i50 TS with both fields of a frame at one time reads as 50`() {
        val fieldPairs = mkvDecodeOrder(MKV_FRAME_TIMING_SAMPLES * 2, 40_000.0).flatMap { listOf(it, it) }
        assertEquals(50f, FrameRateUtils.snapToStandardRate(frameRateFromSampleTimes(fieldPairs)!!), 0.001f)
    }

    @Test
    fun `TS with one field per sample reads as 50 and progressive 25 stays 25`() {
        assertEquals(50f, snappedRate(20_000.0, MKV_FRAME_TIMING_SAMPLES * 2)!!, 0.001f)
        assertEquals(25f, snappedRate(40_000.0, MKV_FRAME_TIMING_SAMPLES * 2)!!, 0.001f)
    }

    @Test
    fun `a TS without a stated rate is timed through the output`() {
        val rates = ArrayList<Float>()
        val output = MkvFrameTimingTrackOutput(NoOpTrackOutput(), rates::add)
        output.format(videoFormat(frameRate = Format.NO_VALUE.toFloat(), container = MimeTypes.VIDEO_MP2T))
        mkvDecodeOrder(400, 40_000.0).flatMap { listOf(it, it) }.forEach { output.sampleMetadata(it, 0, 1, 0, null) }
        assertEquals(1, rates.size)
        assertEquals(50f, FrameRateUtils.snapToStandardRate(rates.single()), 0.001f)
    }

    @Test
    fun `an MP4 without a stated rate is not timed`() {
        val rates = ArrayList<Float>()
        val output = MkvFrameTimingTrackOutput(NoOpTrackOutput(), rates::add)
        output.format(videoFormat(frameRate = Format.NO_VALUE.toFloat(), container = MimeTypes.VIDEO_MP4))
        mkvDecodeOrder(400, 40_000.0).forEach { output.sampleMetadata(it, 0, 1, 0, null) }
        assertTrue(rates.isEmpty())
    }

    private fun videoFormat(frameRate: Float, container: String = MimeTypes.VIDEO_MATROSKA) = Format.Builder()
        .setContainerMimeType(container)
        .setSampleMimeType(MimeTypes.VIDEO_H264)
        .setFrameRate(frameRate)
        .build()

    private class NoOpTrackOutput : TrackOutput {
        override fun format(format: Format) = Unit
        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int =
            C.RESULT_END_OF_INPUT
        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) = Unit
        override fun sampleMetadata(
            timeUs: Long,
            flags: Int,
            size: Int,
            offset: Int,
            cryptoData: TrackOutput.CryptoData?
        ) = Unit
    }
}
