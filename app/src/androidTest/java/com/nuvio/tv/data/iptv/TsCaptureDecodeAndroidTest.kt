package com.nuvio.tv.data.iptv

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.*
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.core.iptv.*
import kotlinx.coroutines.*
import java.net.URI
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

/** Local synthetic media only. Exact shipped Media3 AARs, fresh extractor/decoder per segment. */
@androidx.media3.common.util.UnstableApi
class TsCaptureDecodeAndroidTest {
    private fun fixture(n: Int) = requireNotNull(javaClass.getResourceAsStream("/iptv-ts/segment0$n.ts")).use { it.readBytes() }

    @Test fun eachInspectedSegmentDecodesEverySampleThroughLocalMedia3BridgeAndAndroidCodecs() {
        for (segment in 0..2) {
            val bytes = fixture(segment)
            val evidence = TsCaptureInspector().inspect(bytes.inputStream())
            val tracks = extract(bytes)
            val video = tracks.single { it.format?.sampleMimeType == "video/avc" }
            val audio = tracks.single { it.format?.sampleMimeType == "audio/mp4a-latm" }
            assertEquals(evidence.videoFrames, video.samples.size)
            assertEquals(evidence.audioFrames, audio.samples.size)
            assertEquals(evidence.videoFirstPts90k * 1_000_000 / 90_000, video.samples.first().pts)
            assertTrue(video.samples.first().flags and C.BUFFER_FLAG_KEY_FRAME != 0)
            assertTrue(video.samples.zipWithNext().all { (a, b) -> b.pts - a.pts == 40_000L })
            decode(video, segment); decode(audio, segment)
        }
    }

    @Test fun normalizedStagedSamplesDecodeWithNegativeAudioAndStableSuccessorOffsets() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "capture-normalized-${UUID.randomUUID()}")
        try {
            CaptureSegmentStore(directory, 2L * 1024 * 1024, 512L * 1024).use { store ->
                val index = CaptureTsInspectionIndex(store)
                val timeline = CaptureSampleTimeline(index)
                for (segment in 0..2) {
                    store.append(segment * 2000L, (segment + 1) * 2000L, 0, fixture(segment).inputStream())
                    val window = timeline.accept(index.inspect(segment.toLong()))
                    index.open(window.proof).use { input ->
                        val batch = LocalCaptureSampleStager(CaptureSampleStagingLimits(2L * 1024 * 1024)).stage(window, input)
                        assertEquals(segment * 2000000L, batch.video.samples.first().timeUs)
                        assertEquals(listOf(-21333L, 2005333L, 4010667L)[segment], batch.audio.samples.first().timeUs)
                        for (staged in listOf(batch.video, batch.audio)) {
                            val track = CollectedTrack().also { it.format = staged.format }
                            staged.samples.forEach { sample ->
                                val bytes = ByteBuffer.allocate(sample.size)
                                sample.copyTo(bytes)
                                track.samples += Sample(sample.timeUs, sample.flags, bytes.array())
                            }
                            decode(track, segment)
                        }
                    }
                }
            }
        } finally { assertTrue(!directory.exists() || directory.deleteRecursively()) }
    }

    @Test fun platformExtractorSampleCountIsRecordedSeparatelyFromStructuralBounds() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "ts-entry-${UUID.randomUUID()}.ts")
        val extractor = MediaExtractor()
        try {
            file.writeBytes(fixture(0)); extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).single { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == "video/avc" }
            extractor.selectTrack(track)
            var frames = 0
            while (extractor.sampleTime >= 0) { frames++; extractor.advance() }
            // Firmware-dependent observation, not playback bounds. AM9 delivered 49/50;
            // the primary test requires all 50 through the shipped Media3 HLS extractor.
            assertTrue(frames in 1..50)
            Log.i("IptvTsDecode", "platformExtractor videoFrames=$frames structuralFrames=50")
        } finally { extractor.release(); assertTrue(!file.exists() || file.delete()) }
    }

    @Test fun realHlsCaptureAndLocalLiveReaderFeedCompleteMediaIntoTheDecoderBridge() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "capture-entry-${UUID.randomUUID()}")
        val requests = mutableListOf<String>()
        val http = object : HlsCaptureHttp {
            override suspend fun open(address: URI, maxBytes: Long): java.io.InputStream {
                requests += address.path
                val bytes = if (address.path == "/media.m3u8") {
                    ("#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:0\n" +
                        (0..2).joinToString("") { "#EXTINF:2,\nsegment0$it.ts\n" } + "#EXT-X-ENDLIST\n").toByteArray()
                } else fixture(address.path.removePrefix("/segment0").removeSuffix(".ts").toInt())
                check(bytes.size <= maxBytes); return bytes.inputStream()
            }
            override suspend fun close() = true
        }
        val store = CaptureSegmentStore(directory, 2L * 1024 * 1024, 512L * 1024)
        val source = HlsCaptureSegmentSource(URI("https://fixture.invalid/media.m3u8"), http, 512L * 1024)
        val transport = SegmentCaptureTransport(store, source)
        try {
            transport.start()
            withTimeout(5000) { while (transport.state.value == CaptureTransportState.RUNNING) delay(10) }
            assertEquals(CaptureTransportState.COMPLETE, transport.state.value)
            assertEquals(listOf("/media.m3u8", "/segment00.ts", "/segment01.ts", "/segment02.ts"), requests)
            val bytes = ByteArrayOutputStream()
            store.openLiveFrom(0) { transport.state.value }.use { reader ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val result = reader.read(buffer)
                    if (result.state == CaptureLiveReadState.ENDED) break
                    assertEquals(CaptureLiveReadState.DATA, result.state)
                    bytes.write(buffer, 0, result.bytes); check(bytes.size() <= 1024 * 1024)
                }
            }
            val captured = bytes.toByteArray()
            assertArrayEquals(fixture(0) + fixture(1) + fixture(2), captured)
            val inspection = TsCaptureInspector().inspect(captured.inputStream())
            assertEquals(150, inspection.videoFrames); assertEquals(283, inspection.audioFrames)
            val tracks = extract(captured)
            decode(tracks.single { it.format?.sampleMimeType == "video/avc" }, -1)
            decode(tracks.single { it.format?.sampleMimeType == "audio/mp4a-latm" }, -1)
        } finally {
            try { assertTrue(transport.close()) } finally {
                store.close(); assertTrue(directory.deleteRecursively())
            }
        }
    }

    @Test fun changedTruncatedOversizedAndCancelledInputCannotCompleteLocalExtraction() {
        val original = fixture(0)
        val inspection = TsCaptureInspector().inspect(original.inputStream())
        fun output() = object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = CollectedTrack()
            override fun endTracks() = Unit
            override fun seekMap(seekMap: SeekMap) = Unit
        }
        // First TS packet is SDT: changing its opaque payload preserves the elementary streams,
        // so only the inspection/hash fence should reject this same-length replacement.
        val changed = original.copyOf().also { it[100] = (it[100].toInt() xor 1).toByte() }
        for (bytes in listOf(changed, original.copyOf(original.size - 188), original + byteArrayOf(0))) {
            try { LocalTsSegmentExtractor.extract(bytes.inputStream(), inspection, output()); fail("Changed inspected bytes accepted") }
            catch (_: java.io.IOException) { }
        }
        var closed = false; var checks = 0
        val input = object : java.io.ByteArrayInputStream(original) { override fun close() { closed = true; super.close() } }
        try { LocalTsSegmentExtractor.extract(input, inspection, output()) { if (++checks == 10) throw InterruptedException() }; fail() }
        catch (_: InterruptedException) { }
        assertFalse(closed)
    }

    private data class Sample(val pts: Long, val flags: Int, val bytes: ByteArray)
    private class CollectedTrack : TrackOutput {
        var format: Format? = null
        val samples = mutableListOf<Sample>()
        private val pending = ByteArrayOutputStream()
        override fun format(format: Format) { this.format = format }
        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            check(length <= 1024 * 1024)
            val bytes = ByteArray(length); val n = input.read(bytes, 0, length)
            if (n < 0) { if (!allowEndOfInput) throw EOFException(); return -1 }
            pending.write(bytes, 0, n); check(pending.size() <= 1024 * 1024); return n
        }
        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            check(length <= 1024 * 1024)
            val bytes = ByteArray(length); data.readBytes(bytes, 0, length)
            pending.write(bytes); check(pending.size() <= 1024 * 1024)
        }
        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            check(cryptoData == null)
            val bytes = pending.toByteArray(); val end = bytes.size - offset
            check(size > 0 && size <= end)
            samples += Sample(timeUs, flags, bytes.copyOfRange(end - size, end)); check(samples.size <= 1024)
            pending.reset(); pending.write(bytes, end, offset)
        }
    }

    private fun extract(bytes: ByteArray): List<CollectedTrack> {
        val tracks = mutableMapOf<Int, CollectedTrack>()
        val output = object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = tracks.getOrPut(id) { CollectedTrack() }
            override fun endTracks() = Unit
            override fun seekMap(seekMap: SeekMap) = Unit
        }
        val inspection = TsCaptureInspector().inspect(bytes.inputStream())
        LocalTsSegmentExtractor.extract(bytes.inputStream(), inspection, output)
        return tracks.values.toList()
    }

    private fun decode(track: CollectedTrack, segment: Int) {
        val source = requireNotNull(track.format); val mime = requireNotNull(source.sampleMimeType)
        val format = if (mime.startsWith("video/")) MediaFormat.createVideoFormat(mime, source.width, source.height)
            else MediaFormat.createAudioFormat(mime, source.sampleRate, source.channelCount)
        source.initializationData.forEachIndexed { i, data -> format.setByteBuffer("csd-$i", ByteBuffer.wrap(data)) }
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 1024)
        val name = requireNotNull(MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format))
        val decoder = MediaCodec.createByCodecName(name); var started = false
        try {
            decoder.configure(format, null, null, 0); decoder.start(); started = true
            val info = MediaCodec.BufferInfo(); val outputs = mutableListOf<Long>()
            var next = 0; var inputEnded = false; var outputEnded = false; var outputBytes = 0L
            val deadline = SystemClock.elapsedRealtime() + 20_000
            while (!outputEnded && SystemClock.elapsedRealtime() < deadline) {
                if (!inputEnded) {
                    val slot = decoder.dequeueInputBuffer(10_000)
                    if (slot >= 0) {
                        if (next == track.samples.size) {
                            decoder.queueInputBuffer(slot, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputEnded = true
                        } else {
                            val sample = track.samples[next++]
                            val buffer = requireNotNull(decoder.getInputBuffer(slot)); buffer.clear(); buffer.put(sample.bytes)
                            decoder.queueInputBuffer(slot, 0, sample.bytes.size, sample.pts, 0)
                        }
                    }
                }
                val slot = decoder.dequeueOutputBuffer(info, 10_000)
                if (slot >= 0) {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        outputs += info.presentationTimeUs; outputBytes += info.size
                    }
                    outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    decoder.releaseOutputBuffer(slot, false)
                }
            }
            assertTrue("Decoder reached EOS: $name", outputEnded)
            assertEquals("Decoder frame count: $name", track.samples.size, outputs.size)
            assertTrue(outputBytes > 0)
            assertEquals("Input/output PTS: $name", track.samples.map { it.pts }, outputs)
            Log.i("IptvTsDecode", "segment=$segment mime=$mime decoder=$name frames=${outputs.size} firstUs=${outputs.first()} lastUs=${outputs.last()} decodedBytes=$outputBytes")
        } finally { try { if (started) decoder.stop() } finally { decoder.release() } }
    }
}
