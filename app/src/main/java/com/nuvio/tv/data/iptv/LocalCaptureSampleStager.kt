package com.nuvio.tv.data.iptv

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import com.nuvio.tv.core.iptv.CaptureExtractedSampleTiming
import com.nuvio.tv.core.iptv.CaptureSampleWindow
import com.nuvio.tv.core.iptv.InspectedCaptureInput
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Collections

internal data class CaptureSampleStagingLimits(val maxBatchBytes: Long,
    val maxSampleBytes: Int = 512 * 1024, val maxSamplesPerTrack: Int = 4096,
    val maxInitializationBytes: Int = 64 * 1024, val maxVideoPixels: Long = 1920L * 1080) {
    init {
        require(maxBatchBytes in 188..(64L * 1024 * 1024))
        require(maxSampleBytes in 1..minOf(4L * 1024 * 1024, maxBatchBytes).toInt())
        require(maxInitializationBytes in 1..minOf(1024L * 1024, maxBatchBytes).toInt())
        require(maxSamplesPerTrack in 1..4096 && maxVideoPixels in 1..(8192L * 8192))
    }
}

internal class CapturedEncodedSample(val timeUs: Long, val flags: Int, private val payload: ByteArray) {
    val size get() = payload.size
    fun copyTo(target: ByteBuffer) { require(target.remaining() >= size); target.put(payload) }
}

@UnstableApi
internal class CapturedSampleTrack(val format: Format, samples: List<CapturedEncodedSample>) {
    val samples: List<CapturedEncodedSample> = Collections.unmodifiableList(samples.toList())
}

@UnstableApi
internal class CapturedSampleBatch(val window: CaptureSampleWindow, val video: CapturedSampleTrack,
    val audio: CapturedSampleTrack, val chargedBytes: Long)

/**
 * Transactional compressed-sample staging only. No output escapes until extraction, verified EOF,
 * shape/clock checks and final cancellation all pass. Caller owns the pinned input and admission.
 * Limits cover incoming encoded data/init data, per-track pending data, samples and format dimensions;
 * parser/transient copies, object overhead, decoder buffers and aggregate heap remain admission gates.
 * Does not close input, decode, discard preroll, open a network, publish a MediaSource or enable UI.
 */
@UnstableApi
internal class LocalCaptureSampleStager(private val limits: CaptureSampleStagingLimits) {
    fun stage(window: CaptureSampleWindow, input: InspectedCaptureInput,
        checkCancellation: () -> Unit = {}): CapturedSampleBatch {
        require(input.proof === window.proof) { "Pinned input differs from capture window" }
        checkCancellation()
        val output = StagingOutput(limits)
        LocalTsSegmentExtractor.extract(input, input.inspection, output, checkCancellation)
        // An extractor may use its declared length without requesting the underlying EOF itself.
        if (input.read() != -1 || !input.verified) throw IOException("Capture staging did not verify complete input")
        val video = output.tracks.values.singleOrNull { it.type == C.TRACK_TYPE_VIDEO }
            ?: throw IOException("Capture video track missing")
        val audio = output.tracks.values.singleOrNull { it.type == C.TRACK_TYPE_AUDIO }
            ?: throw IOException("Capture audio track missing")
        val vf = video.format ?: throw IOException("Capture video format missing")
        val af = audio.format ?: throw IOException("Capture audio format missing")
        val evidence = input.inspection
        ensure(output.ended && output.tracks.size == 2 && video.pending.size() == 0 && audio.pending.size() == 0)
        ensure(vf.sampleMimeType == "video/avc" && vf.width in 1..8192 && vf.height in 1..8192 && vf.width.toLong() * vf.height <= limits.maxVideoPixels)
        ensure(af.sampleMimeType == "audio/mp4a-latm" && af.sampleRate == evidence.audioSampleRate && af.channelCount == evidence.audioChannels)
        ensure(video.samples.isNotEmpty() && video.samples.first().flags and C.BUFFER_FLAG_KEY_FRAME != 0)
        val timing = CaptureExtractedSampleTiming.validate(evidence, video.samples.map { it.time }, audio.samples.map { it.time })
        fun completed(track: StagingTrack) = CapturedSampleTrack(requireNotNull(track.format), track.samples.map {
            CapturedEncodedSample(timing.normalize(window.start90k, it.time), it.flags, it.bytes)
        })
        val batch = CapturedSampleBatch(window, completed(video), completed(audio), output.written + output.initialization)
        checkCancellation()
        return batch
    }

    private class StagingOutput(val limits: CaptureSampleStagingLimits) : ExtractorOutput {
        val tracks = mutableMapOf<Int, StagingTrack>()
        var written = 0L; var initialization = 0L; var ended = false
        override fun track(id: Int, type: Int): TrackOutput {
            tracks[id]?.let { ensure(it.type == type); return it }
            ensure(!ended && tracks.size < 2 && type in setOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO) && tracks.values.none { it.type == type })
            return StagingTrack(type, this).also { tracks[id] = it }
        }
        override fun endTracks() { ended = true }
        override fun seekMap(seekMap: SeekMap) = Unit
        fun capacity(length: Int) { ensure(length >= 0 && length <= limits.maxBatchBytes - written - initialization) }
    }

    private data class PendingSample(val time: Long, val flags: Int, val bytes: ByteArray)
    private class StagingTrack(val type: Int, private val owner: StagingOutput) : TrackOutput {
        var format: Format? = null
        val pending = ByteArrayOutputStream()
        val samples = mutableListOf<PendingSample>()
        override fun format(format: Format) {
            ensure(format.drmInitData == null)
            this.format?.let { ensure(it == format); return }
            val bytes = format.initializationData.sumOf { it.size.toLong() }
            ensure(bytes <= owner.limits.maxInitializationBytes && bytes <= owner.limits.maxBatchBytes - owner.written - owner.initialization)
            owner.initialization += bytes; this.format = format
        }
        private fun capacity(length: Int, part: Int) {
            ensure(part == TrackOutput.SAMPLE_DATA_PART_MAIN && length >= 0 && length <= owner.limits.maxSampleBytes - pending.size())
            owner.capacity(length)
        }
        private fun append(bytes: ByteArray, count: Int) { owner.written += count; pending.write(bytes, 0, count) }
        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            capacity(length, sampleDataPart); if (length == 0) return 0
            val bytes = ByteArray(length); val n = input.read(bytes, 0, length)
            if (n < 0) { if (!allowEndOfInput) throw EOFException(); return -1 }
            ensure(n in 1..length); append(bytes, n); return n
        }
        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            capacity(length, sampleDataPart)
            val bytes = ByteArray(length); data.readBytes(bytes, 0, length); append(bytes, length)
        }
        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            ensure(cryptoData == null && flags and C.BUFFER_FLAG_KEY_FRAME.inv() == 0 && samples.size < owner.limits.maxSamplesPerTrack)
            val bytes = pending.toByteArray(); val end = bytes.size - offset
            ensure(offset >= 0 && end >= 0 && size > 0 && size <= end && size <= owner.limits.maxSampleBytes)
            samples += PendingSample(timeUs, flags, bytes.copyOfRange(end - size, end))
            pending.reset(); pending.write(bytes, end, offset)
        }
    }

    private companion object { fun ensure(ok: Boolean) { if (!ok) throw IOException("Capture sample staging rejected unsupported data or budget") } }
}
