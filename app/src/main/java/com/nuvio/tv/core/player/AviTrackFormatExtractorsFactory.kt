package com.nuvio.tv.core.player

import android.net.Uri
import android.util.Log
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
import androidx.media3.extractor.ForwardingExtractorInput
import androidx.media3.extractor.ForwardingSeekMap
import androidx.media3.extractor.ForwardingTrackOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.avi.AviExtractor
import java.io.EOFException
import java.io.IOException

/**
 * Completes the video track format of AVI files: the stock AVI extractor reports neither the frame rate nor the
 * container, so frame rate matching and the decode-order time repair had nothing to work with. A video stream the
 * extractor drops because it does not know the codec (DivX 3 and other old FourCCs) is reported as an unknown
 * video track, so the player can tell there is a picture it cannot decode instead of playing sound only.
 * AC-3 and DTS audio chunks are split into single syncframes, see [AviAudioFramer].
 */
@UnstableApi
internal class AviTrackFormatExtractorsFactory(private val delegate: ExtractorsFactory) : ExtractorsFactory {

    override fun createExtractors(): Array<Extractor> =
        delegate.createExtractors().map(::wrap).toTypedArray()

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        delegate.createExtractors(uri, responseHeaders).map(::wrap).toTypedArray()

    private fun wrap(extractor: Extractor): Extractor =
        if (extractor is AviExtractor) AviTrackFormatExtractor(extractor) else extractor
}

@UnstableApi
private class AviTrackFormatExtractor(private val delegate: AviExtractor) : Extractor {

    private var headerRead = false
    private var video: AviHeader.Video? = null
    private var streams: List<AviHeader.Stream> = emptyList()
    private val audioTracks = ArrayList<AudioFrameTrackOutput>()
    private var watchingIndex = true
    private var moviListPosition = C.INDEX_UNSET.toLong()
    private var indexBodyPosition = C.INDEX_UNSET.toLong()
    private var indexBodySize = 0L
    private var videoStreamId = C.INDEX_UNSET
    @Volatile private var videoSeekTable: AviVideoSeekTable? = null

    override fun init(output: ExtractorOutput) {
        delegate.init(object : ExtractorOutput {
            private var videoTrackSeen = false

            override fun track(id: Int, type: Int): TrackOutput {
                val track = output.track(id, type)
                if (type == C.TRACK_TYPE_AUDIO) {
                    val clock = streams.getOrNull(id)?.let(AviAudioClock::forStream)
                    return AudioFrameTrackOutput(track, id, clock).also { audioTracks += it }
                }
                if (type != C.TRACK_TYPE_VIDEO) return track
                videoTrackSeen = true
                if (videoStreamId == C.INDEX_UNSET) videoStreamId = id
                return VideoTrackOutput(track)
            }

            override fun endTracks() {
                val dropped = video
                if (!videoTrackSeen && dropped != null) {
                    output.track(UNKNOWN_VIDEO_TRACK_ID, C.TRACK_TYPE_VIDEO).format(
                        Format.Builder()
                            .setContainerMimeType(MimeTypes.VIDEO_AVI)
                            .setSampleMimeType(MimeTypes.VIDEO_UNKNOWN)
                            .setCodecs(dropped.fourCc)
                            .build()
                    )
                }
                output.endTracks()
            }

            override fun seekMap(seekMap: SeekMap) {
                watchingIndex = false
                output.seekMap(VideoSeekMap(seekMap))
            }
        })
    }

    @Throws(IOException::class)
    override fun sniff(input: ExtractorInput): Boolean = delegate.sniff(input)

    @Throws(IOException::class)
    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        if (!headerRead && input.position == 0L) {
            peekHeader(input)
            headerRead = true
        }
        return delegate.read(if (watchingIndex) IndexWatchingInput(input) else input, seekPosition)
    }

    // The header list can be longer than a few KB when an audio stream with an index block comes first.
    @Throws(IOException::class)
    private fun peekHeader(input: ExtractorInput) {
        try {
            val start = ByteArray(AviHeader.LIST_START_BYTES)
            val startLength = peekUpTo(input, start)
            input.resetPeekPosition()
            val head = ByteArray(AviHeader.probeBytes(start, startLength))
            val length = peekUpTo(input, head)
            video = AviHeader.read(head, length)
            streams = AviHeader.streams(head, length)
        } finally {
            input.resetPeekPosition()
        }
    }

    private fun onIndexRead(body: ByteArray, offset: Int, length: Int) {
        val entries = AviIndex.audioEntries(body, offset, length, moviListPosition, audioTracks.map { it.id })
        audioTracks.forEach { track -> entries[track.id]?.let(track::useIndex) }
        val videoStream = streams.getOrNull(videoStreamId) ?: return
        videoSeekTable = AviIndex.videoKeyFrames(body, offset, length, moviListPosition, videoStreamId)
            ?.let { AviVideoSeekTable.create(videoStream, it) }
    }

    /** Keeps the stock answer when the video reader can start there, see [AviVideoSeekTable]. */
    private inner class VideoSeekMap(seekMap: SeekMap) : ForwardingSeekMap(seekMap) {
        override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
            val points = super.getSeekPoints(timeUs)
            val table = videoSeekTable ?: return points
            return if (table.isSafeStart(points.first.position)) points else table.seekPoints(timeUs)
        }
    }

    /** Sees the reader find the movi list and read the idx1 body, which it does before the first sample. */
    private inner class IndexWatchingInput(private val input: ExtractorInput) : ForwardingExtractorInput(input) {
        override fun peekFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
            val at = input.peekPosition
            val peeked = input.peekFully(target, offset, length, allowEndOfInput)
            if (peeked) onPeeked(at, target, offset, length)
            return peeked
        }

        override fun peekFully(target: ByteArray, offset: Int, length: Int) {
            val at = input.peekPosition
            input.peekFully(target, offset, length)
            onPeeked(at, target, offset, length)
        }

        override fun readFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
            val at = input.position
            val read = input.readFully(target, offset, length, allowEndOfInput)
            if (read) onRead(at, target, offset, length)
            return read
        }

        override fun readFully(target: ByteArray, offset: Int, length: Int) {
            val at = input.position
            input.readFully(target, offset, length)
            onRead(at, target, offset, length)
        }

        private fun onPeeked(at: Long, data: ByteArray, offset: Int, length: Int) {
            if (length >= 12 && tag(data, offset) == "LIST" && tag(data, offset + 8) == "movi") moviListPosition = at
        }

        private fun onRead(at: Long, data: ByteArray, offset: Int, length: Int) {
            if (length == 8 && tag(data, offset) == "idx1") {
                indexBodyPosition = at + 8
                indexBodySize = (data[offset + 4].toLong() and 0xFF) or ((data[offset + 5].toLong() and 0xFF) shl 8) or
                    ((data[offset + 6].toLong() and 0xFF) shl 16) or ((data[offset + 7].toLong() and 0xFF) shl 24)
            } else if (at == indexBodyPosition && length.toLong() == indexBodySize) {
                indexBodyPosition = C.INDEX_UNSET.toLong()
                onIndexRead(data, offset, length)
            }
        }

        private fun tag(data: ByteArray, at: Int) = String(data, at, 4, Charsets.ISO_8859_1)
    }

    @Throws(IOException::class)
    private fun peekUpTo(input: ExtractorInput, target: ByteArray): Int {
        var length = 0
        while (length < target.size) {
            val read = input.peek(target, length, target.size - length)
            if (read == C.RESULT_END_OF_INPUT) break
            length += read
        }
        return length
    }

    override fun seek(position: Long, timeUs: Long) {
        audioTracks.forEach { it.reset() }
        delegate.seek(position, timeUs)
    }

    override fun release() = delegate.release()
    override fun getUnderlyingImplementation(): Extractor = delegate

    private inner class VideoTrackOutput(track: TrackOutput) : ForwardingTrackOutput(track) {
        override fun format(format: Format) {
            val builder = format.buildUpon()
            if (format.containerMimeType == null) builder.setContainerMimeType(MimeTypes.VIDEO_AVI)
            val frameRate = video?.frameRate ?: Format.NO_VALUE.toFloat()
            if (format.frameRate <= 0f && frameRate > 0f) builder.setFrameRate(frameRate)
            super.format(builder.build())
        }
    }

    private class AudioFrameTrackOutput(
        private val track: TrackOutput,
        val id: Int,
        private val clock: AviAudioClock?,
    ) : TrackOutput {
        private var framer: AviAudioFramer? = null
        private var sampleMimeType: String? = null
        private val frame = ParsableByteArray()
        private var chunkDataPosition = NO_POSITION
        private var seeked = false
        private var loggedClock = false
        private var loggedSeek = false
        private var loggedEngaged = false
        private var loggedPassthrough = false
        private var loggedReanchors = 0
        private val sink = AviAudioFramer.FrameSink { data, offset, size, timeUs ->
            frame.reset(data, offset + size)
            frame.setPosition(offset)
            track.sampleData(frame, size)
            track.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, size, 0, null)
        }

        fun reset() {
            framer?.reset()
            clock?.reset()
            chunkDataPosition = NO_POSITION
            seeked = true
        }

        fun useIndex(entries: AviIndex.Entries) {
            if (clock?.useIndex(entries) == false) {
                Log.w(TAG, "stream=$id idx1 entries out of file order, timing by the reader after seeks")
            }
        }

        private fun noteChunkStart(input: DataReader, length: Int) {
            if (chunkDataPosition == NO_POSITION && length > 0) {
                chunkDataPosition = (input as? ExtractorInput)?.position ?: UNKNOWN_POSITION
            }
        }

        private fun chunkTimeUs(readerTimeUs: Long, size: Int): Long {
            if (clock == null) {
                if (!loggedClock) {
                    loggedClock = true
                    Log.i(TAG, "clock stream=$id off, header gives no usable rate")
                }
                return readerTimeUs
            }
            val timeUs = clock.chunkTimeUs(chunkDataPosition, size, readerTimeUs)
            if (!loggedClock) {
                loggedClock = true
                Log.i(
                    TAG,
                    "clock stream=$id by=${if (clock.countsBytes) "bytes" else "chunks"} " +
                        "indexChunks=${clock.indexedChunks} shiftUs=${timeUs - readerTimeUs}"
                )
            } else if (seeked && !loggedSeek) {
                loggedSeek = true
                Log.i(TAG, "after seek stream=$id shiftUs=${timeUs - readerTimeUs} indexed=${clock.lastChunkIndexed}")
            }
            seeked = false
            return timeUs
        }

        override fun format(format: Format) {
            if (framer == null && sampleMimeType == null) {
                framer = AviAudioFramer.codecFor(format.sampleMimeType, format.sampleRate)
                    ?.let { AviAudioFramer(it, format.sampleRate) }
            }
            sampleMimeType = format.sampleMimeType
            track.format(format)
        }

        override fun durationUs(durationUs: Long) = track.durationUs(durationUs)

        @Throws(IOException::class)
        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            noteChunkStart(input, length)
            val framer = framer ?: return track.sampleData(input, length, allowEndOfInput, sampleDataPart)
            val read = framer.append(length) { target, offset, count -> input.read(target, offset, count) }
            if (read == C.RESULT_END_OF_INPUT) {
                if (allowEndOfInput) return C.RESULT_END_OF_INPUT
                throw EOFException()
            }
            return read
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            val framer = framer ?: return track.sampleData(data, length, sampleDataPart)
            var remaining = length
            while (remaining > 0) {
                remaining -= framer.append(remaining) { target, offset, count ->
                    data.readBytes(target, offset, count)
                    count
                }
            }
        }

        override fun sampleMetadata(
            timeUs: Long,
            flags: Int,
            size: Int,
            offset: Int,
            cryptoData: TrackOutput.CryptoData?
        ) {
            val chunkTimeUs = chunkTimeUs(timeUs, size)
            chunkDataPosition = NO_POSITION
            val framer = framer ?: return track.sampleMetadata(chunkTimeUs, flags, size, offset, cryptoData)
            framer.endChunk(chunkTimeUs, sink)
            logChanges(framer)
        }

        private fun logChanges(framer: AviAudioFramer) {
            if (framer.engaged && !loggedEngaged) {
                loggedEngaged = true
                Log.i(
                    TAG,
                    "split codec=$sampleMimeType frameBytes=${framer.firstFrameBytes} " +
                        "bitrate=${framer.firstFrameBitrate} framesPerChunk=${framer.firstChunkFrames} " +
                        "skippedBytes=${framer.bytesDropped}"
                )
            }
            if (framer.passingThrough && !loggedPassthrough) {
                loggedPassthrough = true
                Log.w(TAG, "no syncframes found codec=$sampleMimeType, chunks passed on unchanged")
            }
            if (framer.reanchors > loggedReanchors && loggedReanchors < MAX_REANCHOR_LOGS) {
                loggedReanchors = framer.reanchors
                Log.i(TAG, "re-anchor driftUs=${framer.lastReanchorDriftUs} count=${framer.reanchors}")
            }
        }
    }

    private companion object {
        const val UNKNOWN_VIDEO_TRACK_ID = 0x7FFF
        const val TAG = "AVI_AUDIO_TIME"
        const val MAX_REANCHOR_LOGS = 5
        const val NO_POSITION = -1L
        const val UNKNOWN_POSITION = -2L
    }
}

/** Reads the first video stream's frame rate and FourCC from the first bytes of an AVI file (hdrl list). */
internal object AviHeader {
    const val LIST_START_BYTES = 24
    private const val MIN_PROBE_BYTES = 4096
    private const val MAX_PROBE_BYTES = 256 * 1024

    /** How much of the file to look at: the whole hdrl list when its size is known, within sane bounds. */
    fun probeBytes(start: ByteArray, length: Int): Int {
        if (length < LIST_START_BYTES || fourCc(start, 0) != "RIFF" || fourCc(start, 8) != "AVI " ||
            fourCc(start, 12) != "LIST" || fourCc(start, 20) != "hdrl"
        ) {
            return MIN_PROBE_BYTES
        }
        val headerListEnd = 20L + u32(start, 16)
        return headerListEnd.coerceIn(MIN_PROBE_BYTES.toLong(), MAX_PROBE_BYTES.toLong()).toInt()
    }
    private const val MIN_FPS = 5f
    private const val MAX_FPS = 121f
    private const val STREAM_HEADER_BYTES = 48

    /** [frameRate] is [Format.NO_VALUE] when the header carries no usable rate. */
    data class Video(val frameRate: Float, val fourCc: String?)

    /** One stream header, numbered in file order as the chunk ids number them; [avgBytesPerSecond] is for audio. */
    data class Stream(
        val number: Int,
        val type: String,
        val scale: Long,
        val rate: Long,
        val length: Long,
        val sampleSize: Long,
        val avgBytesPerSecond: Long = 0,
    ) {
        val isAudio: Boolean get() = type == "auds"
    }

    fun streams(head: ByteArray, length: Int): List<Stream> {
        val end = minOf(length, head.size)
        if (end < 12 || fourCc(head, 0) != "RIFF" || fourCc(head, 8) != "AVI ") return emptyList()
        val streams = ArrayList<Stream>()
        var at = 12
        while (at + 8 <= end) {
            val id = fourCc(head, at)
            val size = u32(head, at + 4)
            if (id == "LIST" && at + 12 <= end) {
                val type = fourCc(head, at + 8)
                if (type == "movi") break
                if (type == "hdrl" || type == "strl") {
                    at += 12
                    continue
                }
            } else if (id == "strh" && at + 12 <= end) {
                val complete = size >= STREAM_HEADER_BYTES && at + 8 + STREAM_HEADER_BYTES <= end
                streams += Stream(
                    number = streams.size,
                    type = fourCc(head, at + 8),
                    scale = if (complete) u32(head, at + 28) else 0,
                    rate = if (complete) u32(head, at + 32) else 0,
                    length = if (complete) u32(head, at + 40) else 0,
                    sampleSize = if (complete) u32(head, at + 52) else 0,
                )
            } else if (id == "strf" && size >= 12 && at + 20 <= end && streams.lastOrNull()?.isAudio == true) {
                streams[streams.lastIndex] = streams.last().copy(avgBytesPerSecond = u32(head, at + 16))
            }
            val next = at + 8L + size + (size and 1L)
            if (next >= end) break
            at = next.toInt()
        }
        return streams
    }

    fun read(head: ByteArray, length: Int): Video? {
        val end = minOf(length, head.size)
        if (end < 12 || fourCc(head, 0) != "RIFF" || fourCc(head, 8) != "AVI ") return null
        var mainHeaderFps = Format.NO_VALUE.toFloat()
        var streamFps = Format.NO_VALUE.toFloat()
        var videoFourCc: String? = null
        var inVideoStream = false
        var videoStreamSeen = false
        var at = 12
        while (at + 8 <= end) {
            val id = fourCc(head, at)
            val size = u32(head, at + 4)
            if (id == "LIST" && at + 12 <= end) {
                val type = fourCc(head, at + 8)
                if (type == "movi" || (type == "strl" && videoStreamSeen)) break
                if (type == "hdrl" || type == "strl") {
                    at += 12
                    continue
                }
            } else if (id == "avih" && at + 12 <= end) {
                val microsPerFrame = u32(head, at + 8)
                if (microsPerFrame > 0) mainHeaderFps = plausible(1_000_000f / microsPerFrame)
            } else if (id == "strh" && at + 16 <= end) {
                inVideoStream = fourCc(head, at + 8) == "vids"
                if (inVideoStream) {
                    videoStreamSeen = true
                    videoFourCc = printable(fourCc(head, at + 12))
                    if (at + 36 <= end) {
                        val scale = u32(head, at + 28)
                        val rate = u32(head, at + 32)
                        if (scale > 0 && rate > 0) streamFps = plausible(rate.toFloat() / scale)
                    }
                }
            } else if (id == "strf" && inVideoStream && at + 28 <= end) {
                printable(fourCc(head, at + 24))?.let { videoFourCc = it }
            }
            val next = at + 8L + size + (size and 1L)
            if (next >= end) break
            at = next.toInt()
        }
        if (!videoStreamSeen) return null
        return Video(if (streamFps > 0f) streamFps else mainHeaderFps, videoFourCc)
    }

    private fun plausible(fps: Float): Float = if (fps in MIN_FPS..MAX_FPS) fps else Format.NO_VALUE.toFloat()

    private fun printable(fourCc: String): String? =
        fourCc.takeIf { it.isNotBlank() && it.all { c -> c in ' '..'~' } }

    private fun fourCc(b: ByteArray, at: Int): String = String(b, at, 4, Charsets.ISO_8859_1)

    private fun u32(b: ByteArray, at: Int): Long =
        (b[at].toLong() and 0xFF) or ((b[at + 1].toLong() and 0xFF) shl 8) or
            ((b[at + 2].toLong() and 0xFF) shl 16) or ((b[at + 3].toLong() and 0xFF) shl 24)
}
