package com.nuvio.tv.core.player.thumbnail

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import java.io.EOFException
import java.io.IOException

/**
 * Copies keyframes that playback downloads anyway so they become seek thumbnails without extra network. Wraps the
 * player's outermost ExtractorsFactory (after the Dolby Vision factory, so it sees what the decoder gets). A sample
 * is copied only while [SeekThumbnails] wants that keyframe, otherwise it is a pass-through.
 */
@UnstableApi
object PlaybackTap {
    fun wrap(delegate: ExtractorsFactory): ExtractorsFactory = TapExtractorsFactory(delegate)
}

@UnstableApi
private class TapExtractorsFactory(private val delegate: ExtractorsFactory) : ExtractorsFactory {
    override fun createExtractors(): Array<Extractor> =
        delegate.createExtractors().map { TapExtractor(it) }.toTypedArray()

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        delegate.createExtractors(uri, responseHeaders).map { TapExtractor(it) }.toTypedArray()
}

@UnstableApi
private class TapExtractor(private val delegate: Extractor) : Extractor {
    override fun init(output: ExtractorOutput) = delegate.init(TapExtractorOutput(output))

    @Throws(IOException::class)
    override fun sniff(input: ExtractorInput): Boolean = delegate.sniff(input)

    @Throws(IOException::class)
    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = delegate.read(input, seekPosition)

    override fun seek(position: Long, timeUs: Long) = delegate.seek(position, timeUs)
    override fun release() = delegate.release()
    override fun getUnderlyingImplementation(): Extractor = delegate.underlyingImplementation
    override fun getSniffFailureDetails() = delegate.sniffFailureDetails
}

@UnstableApi
private class TapExtractorOutput(private val delegate: ExtractorOutput) : ExtractorOutput {
    private var videoTapped = false

    override fun track(id: Int, type: Int): TrackOutput {
        val track = delegate.track(id, type)
        if (type != C.TRACK_TYPE_VIDEO || videoTapped) return track
        videoTapped = true
        return TapTrackOutput(track)
    }

    override fun endTracks() = delegate.endTracks()
    override fun seekMap(seekMap: SeekMap) = delegate.seekMap(seekMap)
}

@UnstableApi
private class TapTrackOutput(private val delegate: TrackOutput) : TrackOutput by delegate {
    private companion object {
        /** A 4K remux keyframe is ~1-3 MB. */
        const val MAX_BUFFER = 24 * 1024 * 1024
    }

    /** H.264 / HEVC, incl. Dolby Vision over them. */
    private var supported = false
    private var armed = false
    private var buf = ByteArray(0)
    private var len = 0
    private var scratch = ByteArray(0)
    private val forward = ParsableByteArray()

    override fun format(format: Format) {
        val mime = format.sampleMimeType
        val codecs = format.codecs.orEmpty().lowercase()
        supported = mime == MimeTypes.VIDEO_H265 || mime == MimeTypes.VIDEO_H264 ||
            (mime == MimeTypes.VIDEO_DOLBY_VISION && (codecs.startsWith("dvh") || codecs.startsWith("dva")))
        if (!supported) disarm()
        delegate.format(format)
    }

    @Throws(IOException::class)
    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
        if (!armed || sampleDataPart != TrackOutput.SAMPLE_DATA_PART_MAIN) {
            return delegate.sampleData(input, length, allowEndOfInput, sampleDataPart)
        }
        if (scratch.size < length) scratch = ByteArray(maxOf(length, 64 * 1024))
        val read = input.read(scratch, 0, length)
        if (read == C.RESULT_END_OF_INPUT) {
            if (allowEndOfInput) return C.RESULT_END_OF_INPUT
            throw EOFException()
        }
        if (read <= 0) return read
        append(scratch, 0, read)
        forward.reset(scratch, read)
        delegate.sampleData(forward, read, sampleDataPart)
        return read
    }

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        if (armed && sampleDataPart == TrackOutput.SAMPLE_DATA_PART_MAIN && length > 0) {
            append(data.data, data.position, length)
        }
        delegate.sampleData(data, length, sampleDataPart)
    }

    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
        delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData)
        if (armed) {
            val end = len - offset
            val start = end - size
            if (start >= 0 && size > 0 && (flags and C.BUFFER_FLAG_KEY_FRAME) != 0 &&
                (flags and C.BUFFER_FLAG_ENCRYPTED) == 0 && cryptoData == null
            ) {
                SeekThumbnails.tapOffer(timeUs, buf.copyOfRange(start, end))
            }
            // Bytes already written for the next sample (offset) stay buffered.
            if (offset in 1..len && end >= 0) {
                System.arraycopy(buf, end, buf, 0, offset)
                len = offset
            } else {
                len = 0
            }
        }
        val want = supported && SeekThumbnails.tapWants(timeUs)
        if (!want) disarm() else armed = true
    }

    private fun append(src: ByteArray, at: Int, count: Int) {
        if (len + count > MAX_BUFFER) {        // runaway sample
            disarm()
            return
        }
        if (buf.size < len + count) {
            var n = if (buf.isEmpty()) 256 * 1024 else buf.size
            while (n < len + count) n = n shl 1
            buf = buf.copyOf(n)
        }
        System.arraycopy(src, at, buf, len, count)
        len += count
    }

    private fun disarm() {
        armed = false
        len = 0
    }
}
