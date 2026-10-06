package com.nuvio.tv.data.iptv

import androidx.media3.common.DataReader
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.PesReader
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.extractor.ts.TsPayloadReader
import com.nuvio.tv.core.iptv.TsCaptureInspection
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

@androidx.media3.common.util.UnstableApi
internal object LocalTsSegmentExtractor {
    fun extract(input: InputStream, inspection: TsCaptureInspection, output: ExtractorOutput,
        checkCancellation: () -> Unit = {}) {
        require(inspection.bytes in 188..(64L * 1024 * 1024))
        val standard = DefaultTsPayloadReaderFactory()
        var video: PesReader? = null
        val factory = object : TsPayloadReader.Factory {
            override fun createInitialPayloadReaders() = standard.createInitialPayloadReaders()
            override fun createPayloadReader(streamType: Int, esInfo: TsPayloadReader.EsInfo): TsPayloadReader? {

                if (streamType == TsExtractor.TS_STREAM_TYPE_ID3) return null
                val reader = standard.createPayloadReader(streamType, esInfo)
                if (streamType == TsExtractor.TS_STREAM_TYPE_H264) {
                    check(video == null)
                    video = reader as? PesReader ?: throw IOException("Local capture video reader unavailable")
                }
                return reader
            }
        }
        val extractor = TsExtractor(TsExtractor.MODE_HLS, TimestampAdjuster(TimestampAdjuster.MODE_NO_OFFSET), factory)
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        val data = DataReader { bytes, offset, length ->
            checkCancellation()
            val n = input.read(bytes, offset, minOf(length.toLong(), inspection.bytes - count + 1).toInt())
            if (n == 0) throw IOException("Local capture read made no progress")
            if (n > 0) {
                count += n
                if (count > inspection.bytes) throw IOException("Local capture differs from inspection")
                digest.update(bytes, offset, n)
            }
            n
        }
        try {
            extractor.init(output)
            val source = DefaultExtractorInput(data, 0, inspection.bytes)
            val position = PositionHolder(); var calls = 0L
            while (true) {
                checkCancellation()
                if (++calls > inspection.bytes / 188 + 100) throw IOException("Local capture extractor limit")
                val result = extractor.read(source, position)
                if (result == Extractor.RESULT_END_OF_INPUT) break
                if (result != Extractor.RESULT_CONTINUE) throw IOException("Unexpected local capture seek")
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            if (count != inspection.bytes || hash != inspection.sha256) throw IOException("Local capture differs from inspection")
            checkCancellation()
            val finalVideo = video ?: throw IOException("Local capture video missing")
            finalVideo.consume(ParsableByteArray(), TsPayloadReader.FLAG_PAYLOAD_UNIT_START_INDICATOR)
        } finally { extractor.release() }
    }
}
