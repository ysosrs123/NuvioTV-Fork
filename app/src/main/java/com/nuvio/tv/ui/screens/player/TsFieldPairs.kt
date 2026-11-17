@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.tv.ui.screens.player

import android.net.Uri
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
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.TsExtractor
import java.io.IOException

/**
 * Interlaced H.264 in TS (broadcast 1080i and 576i) is coded one field per picture, and Media3 hands each field to the
 * decoder as its own sample with the time of the frame. Some Amlogic decoders (S905X4) then put out frames with a
 * repeated time that the renderer skips, about ten a second. The two fields of a frame now go to the decoder as one
 * sample, the way most players feed it.
 *
 * Sits outside the frame-rate timing, which needs to see both fields to tell 1080i50 from 25p.
 */
internal class TsFieldPairsExtractorsFactory(private val delegate: ExtractorsFactory) : ExtractorsFactory {
    override fun createExtractors(): Array<Extractor> =
        delegate.createExtractors().map(::wrap).toTypedArray()

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        delegate.createExtractors(uri, responseHeaders).map(::wrap).toTypedArray()

    private fun wrap(extractor: Extractor): Extractor =
        if (extractor.underlyingImplementation is TsExtractor) TsFieldPairsExtractor(extractor) else extractor
}

private class TsFieldPairsExtractor(private val delegate: Extractor) : RewrappableExtractor {
    private val pairs = ArrayList<FieldPairTrackOutput>(1)

    override val wrappedExtractor: Extractor get() = delegate
    override fun rewrap(inner: Extractor): Extractor = TsFieldPairsExtractor(inner)

    override fun init(output: ExtractorOutput) = delegate.init(object : ExtractorOutput {
        override fun track(id: Int, type: Int): TrackOutput {
            val track = output.track(id, type)
            if (type != C.TRACK_TYPE_VIDEO) return track
            return FieldPairTrackOutput(track).also { pairs += it }
        }

        override fun endTracks() = output.endTracks()
        override fun seekMap(seekMap: SeekMap) = output.seekMap(seekMap)
    })

    @Throws(IOException::class)
    override fun sniff(input: ExtractorInput): Boolean = delegate.sniff(input)

    @Throws(IOException::class)
    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        val result = delegate.read(input, seekPosition)
        if (result == Extractor.RESULT_END_OF_INPUT) pairs.forEach { it.flush() }
        return result
    }

    override fun seek(position: Long, timeUs: Long) {
        pairs.forEach { it.drop() }
        delegate.seek(position, timeUs)
    }

    override fun release() = delegate.release()
    override fun getUnderlyingImplementation(): Extractor = delegate.underlyingImplementation
    override fun getSniffFailureDetails() = delegate.sniffFailureDetails
}

/**
 * Holds each H.264 sample back until the next one arrives; when the next one has the same time and follows straight on,
 * the two are committed as one sample. Only two are ever joined: a frame has two fields, and a TS that carries several
 * frames per PES (or no PTS) repeats the same time on every one of them. Other formats and samples with their own times pass through one sample late.
 */
internal class FieldPairTrackOutput(private val delegate: TrackOutput) : TrackOutput {
    private var pairing = false
    private var pending = false
    private var pendingJoined = false
    private var pendingTimeUs = 0L
    private var pendingFlags = 0
    private var pendingSize = 0
    private var writtenAfterPending = 0

    var joined = 0
        private set

    override fun format(format: Format) {
        val avc = format.sampleMimeType == MimeTypes.VIDEO_H264
        if (!avc) flush()
        pairing = avc
        delegate.format(format)
    }

    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
        val read = delegate.sampleData(input, length, allowEndOfInput, sampleDataPart)
        if (pending && read > 0) writtenAfterPending += read
        return read
    }

    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean): Int =
        sampleData(input, length, allowEndOfInput, TrackOutput.SAMPLE_DATA_PART_MAIN)

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        delegate.sampleData(data, length, sampleDataPart)
        if (pending) writtenAfterPending += length
    }

    override fun sampleData(data: ParsableByteArray, length: Int) =
        sampleData(data, length, TrackOutput.SAMPLE_DATA_PART_MAIN)

    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
        if (!pairing || cryptoData != null) {
            flush()
            delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData)
            return
        }
        if (pending && !pendingJoined && timeUs == pendingTimeUs && writtenAfterPending == size + offset) {
            pendingSize += size
            writtenAfterPending = offset
            pendingJoined = true
            joined++
            return
        }
        flush()
        pending = true
        pendingJoined = false
        pendingTimeUs = timeUs
        pendingFlags = flags
        pendingSize = size
        writtenAfterPending = offset
    }

    fun flush() {
        if (!pending) return
        pending = false
        delegate.sampleMetadata(pendingTimeUs, pendingFlags, pendingSize, writtenAfterPending, null)
    }

    fun drop() {
        pending = false
        pendingJoined = false
        writtenAfterPending = 0
    }
}
