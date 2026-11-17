package com.nuvio.tv.ui.screens.player

import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.H264Reader
import androidx.media3.extractor.ts.PesReader
import androidx.media3.extractor.ts.SeiReader
import androidx.media3.extractor.ts.TsPayloadReader
import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class TsFieldPairsTest {

    /** Records committed samples as byte ranges of everything written, so wrong offsets show up as gaps or overlaps. */
    private class Track : TrackOutput {
        var written = 0L
        val samples = ArrayList<LongArray>()
        override fun format(format: Format) = Unit
        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int =
            throw UnsupportedOperationException()
        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            data.skipBytes(length)
            written += length
        }
        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            val end = written - offset
            samples += longArrayOf(timeUs, flags.toLong(), end - size, end)
        }

        fun assertContiguous() {
            for (i in 1 until samples.size) assertEquals(samples[i - 1][3], samples[i][2])
        }
    }

    private val avc = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build()

    private fun FieldPairTrackOutput.write(bytes: Int) = sampleData(ParsableByteArray(bytes), bytes, TrackOutput.SAMPLE_DATA_PART_MAIN)

    @Test
    fun `two fields with the frame's time become one sample`() {
        val track = Track()
        val pairs = FieldPairTrackOutput(track).apply { format(avc) }
        pairs.write(100); pairs.sampleMetadata(40_000, 1, 100, 0, null)
        pairs.write(60); pairs.sampleMetadata(40_000, 0, 60, 0, null)
        pairs.write(80); pairs.sampleMetadata(80_000, 0, 80, 0, null)
        pairs.write(50); pairs.sampleMetadata(80_000, 0, 50, 0, null)
        pairs.flush()

        assertEquals(2, track.samples.size)
        assertEquals(listOf(40_000L, 80_000L), track.samples.map { it[0] })
        assertEquals(listOf(160L, 130L), track.samples.map { it[3] - it[2] })
        assertEquals(1L, track.samples[0][1])
        assertEquals(2, pairs.joined)
        track.assertContiguous()
        assertEquals(track.written, track.samples.last()[3])
    }

    @Test
    fun `bytes already written for the next sample keep their place`() {
        val track = Track()
        val pairs = FieldPairTrackOutput(track).apply { format(avc) }
        pairs.write(100); pairs.write(30); pairs.sampleMetadata(0, 1, 100, 30, null)
        pairs.write(40); pairs.sampleMetadata(0, 0, 70, 0, null)
        pairs.write(20); pairs.sampleMetadata(40_000, 0, 20, 0, null)
        pairs.flush()

        assertEquals(listOf(170L, 20L), track.samples.map { it[3] - it[2] })
        track.assertContiguous()
    }

    @Test
    fun `only two samples with the same time are joined`() {
        val track = Track()
        val pairs = FieldPairTrackOutput(track).apply { format(avc) }
        pairs.write(100); pairs.sampleMetadata(40_000, 1, 100, 0, null)
        pairs.write(60); pairs.sampleMetadata(40_000, 0, 60, 0, null)
        pairs.write(70); pairs.sampleMetadata(40_000, 0, 70, 0, null)
        pairs.write(50); pairs.sampleMetadata(40_000, 0, 50, 0, null)
        pairs.write(30); pairs.sampleMetadata(40_000, 0, 30, 0, null)
        pairs.flush()

        assertEquals(listOf(160L, 120L, 30L), track.samples.map { it[3] - it[2] })
        assertEquals(listOf(1L, 0L, 0L), track.samples.map { it[1] })
        assertEquals(2, pairs.joined)
        track.assertContiguous()
        assertEquals(track.written, track.samples.last()[3])
    }

    @Test
    fun `progressive frames pass through unchanged`() {
        val track = Track()
        val pairs = FieldPairTrackOutput(track).apply { format(avc) }
        for (i in 0 until 5) { pairs.write(10 + i); pairs.sampleMetadata(i * 40_000L, 0, 10 + i, 0, null) }
        pairs.flush()

        assertEquals(5, track.samples.size)
        assertEquals(0, pairs.joined)
        track.assertContiguous()
    }

    @Test
    fun `other formats are not held back`() {
        val track = Track()
        val pairs = FieldPairTrackOutput(track).apply { format(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).build()) }
        pairs.write(10); pairs.sampleMetadata(0, 1, 10, 0, null)
        pairs.write(10); pairs.sampleMetadata(0, 0, 10, 0, null)
        assertEquals(2, track.samples.size)
    }

    @Test
    fun `a seek drops the held sample`() {
        val track = Track()
        val pairs = FieldPairTrackOutput(track).apply { format(avc) }
        pairs.write(10); pairs.sampleMetadata(0, 1, 10, 0, null)
        pairs.drop()
        pairs.flush()
        assertEquals(0, track.samples.size)
    }

    /** Broadcast 1080i50 with both fields in each PES packet (TS_FIELD_SAMPLE, or the file left on this PC). */
    @Test
    fun `each frame of a 1080i broadcast reaches the decoder as one sample`() {
        val sample = File(System.getenv("TS_FIELD_SAMPLE") ?: "E:/1080i.HDTV.H264.ts")
        assumeTrue(sample.isFile)

        val stock = run(sample, pair = false)
        val paired = run(sample, pair = true)
        val stockTimes = stock.samples.map { it[0] }
        val pairedTimes = paired.samples.map { it[0] }
        println("stock samples=${stock.samples.size} distinct=${stockTimes.distinct().size}")
        println("paired samples=${paired.samples.size} distinct=${pairedTimes.distinct().size}")

        assertTrue(stock.samples.size > stockTimes.distinct().size * 19 / 10)
        assertEquals(pairedTimes.distinct().size, paired.samples.size)
        assertEquals(stockTimes.distinct().size, paired.samples.size)
        paired.assertContiguous()
        assertEquals(stock.samples.last()[3], paired.samples.last()[3])
        val stockFirstFields = stock.samples.filterIndexed { i, s -> i == 0 || s[0] != stock.samples[i - 1][0] }
        assertEquals(stockFirstFields.map { it[1] }, paired.samples.map { it[1] })
    }

    private fun run(sample: File, pair: Boolean): Track {
        val track = Track()
        var pairs: FieldPairTrackOutput? = null
        val output = object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput =
                if (pair) FieldPairTrackOutput(track).also { pairs = it } else track
            override fun endTracks() = Unit
            override fun seekMap(seekMap: SeekMap) = Unit
        }
        val reader: TsPayloadReader = PesReader(H264Reader(SeiReader(emptyList(), "video/mp2t"), false, false, "video/mp2t"))
        reader.init(TimestampAdjuster(0), output, TsPayloadReader.TrackIdGenerator(0, 1))

        val packet = ByteArray(188)
        var videoPid = -1
        RandomAccessFile(sample, "r").use { file ->
            val packets = minOf(file.length(), 40L * 1024 * 1024) / 188
            repeat(packets.toInt()) {
                file.readFully(packet)
                if (packet[0].toInt() != 0x47) return@repeat
                val pid = ((packet[1].toInt() and 0x1F) shl 8) or (packet[2].toInt() and 0xFF)
                val start = (packet[1].toInt() and 0x40) != 0
                val adaptation = (packet[3].toInt() shr 4) and 3
                if (adaptation and 1 == 0) return@repeat
                val payload = if (adaptation == 3) 5 + (packet[4].toInt() and 0xFF) else 4
                if (payload >= 188) return@repeat
                if (videoPid < 0 && start && packet[payload].toInt() == 0 && packet[payload + 1].toInt() == 0 &&
                    packet[payload + 2].toInt() == 1 && (packet[payload + 3].toInt() and 0xF0) == 0xE0
                ) {
                    videoPid = pid
                }
                if (pid != videoPid) return@repeat
                val data = ParsableByteArray(packet.copyOf())
                data.position = payload
                reader.consume(data, if (start) TsPayloadReader.FLAG_PAYLOAD_UNIT_START_INDICATOR else 0)
            }
        }
        pairs?.flush()
        return track
    }
}
