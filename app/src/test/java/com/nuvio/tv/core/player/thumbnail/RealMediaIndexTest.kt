package com.nuvio.tv.core.player.thumbnail

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

/**
 * Opt-in: index readers and keyframe extractor over local media files. NUVIO_THUMB_REAL_FILES names a text file
 * with one media path per line. With NUVIO_THUMB_OUT set, each extracted keyframe is written there.
 */
class RealMediaIndexTest {

    private class FileReader(path: String) : RangeReader {
        private val f = RandomAccessFile(path, "r")
        var reads = 0
        var bytes = 0L
        override val totalLength: Long = f.length()
        override fun read(offset: Long, length: Int): ByteArray {
            reads++
            if (offset >= totalLength) return ByteArray(0)
            val n = minOf(length.toLong(), totalLength - offset).toInt()
            val b = ByteArray(n)
            f.seek(offset)
            f.readFully(b)
            bytes += n
            return b
        }
        fun close() = f.close()
    }

    @Test
    fun realFiles() {
        val listPath = System.getenv("NUVIO_THUMB_REAL_FILES")
        assumeTrue("NUVIO_THUMB_REAL_FILES not set", listPath != null && File(listPath).exists())
        val out = System.getenv("NUVIO_THUMB_OUT")?.let { File(it).apply { mkdirs() } }
        val failures = ArrayList<String>()
        for (line in File(listPath!!).readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }) {
            val path = line.substringAfter('|')
            val label = line.substringBefore('|', File(path).nameWithoutExtension).take(40).replace(Regex("[^A-Za-z0-9_-]"), "_")
            val r = FileReader(path)
            try {
                val head = r.read(0, MkvIndexReader.HEAD_BYTES)
                val idx = when {
                    MkvIndexReader.looksLikeMatroska(head) -> MkvIndexReader.read(r, head)
                    Mp4IndexReader.looksLikeMp4(head) -> Mp4IndexReader.read(r, head)
                    AviIndexReader.looksLikeAvi(head) -> AviIndexReader.read(r, head)
                    else -> throw UnsupportedMediaException("unknown container")
                }
                val indexReads = r.reads
                val indexKb = r.bytes / 1024
                val k = idx.keyframes
                val relPct = if (k.count > 0) 100 * k.relPos.count { it >= 0 } / k.count else 0
                val ex = KeyframeExtractor(idx, r)
                val picks = (0 until 8).map { (k.count - 1) * (it + 1) / 9 }.distinct()
                var ok = 0
                for ((n, i) in picks.withIndex()) {
                    val au = ex.extract(i)
                    if (au != null && au.size > idx.video.parameterSetsAnnexB.size + 4) {
                        ok++
                        // NAL codecs: Annex-B as is; others: extradata + raw frame = a decodable elementary stream.
                        val ext = when (idx.video.codec) {
                            VideoCodec.HEVC -> "hevc"; VideoCodec.H264 -> "h264"; VideoCodec.MPEG4 -> "m4v"
                            VideoCodec.MPEG2, VideoCodec.MPEG1 -> "m2v"; VideoCodec.VC1, VideoCodec.WMV3 -> "vc1"
                            VideoCodec.AV1 -> "obu"
                        }
                        val bytes = if (idx.video.codec.nal) au else idx.video.extradata + au
                        out?.let { File(it, "${label}_$n.$ext").writeBytes(bytes) }
                    }
                }
                println("REAL $label | ${idx.kind} ${idx.video.codec} ${idx.video.width}x${idx.video.height} " +
                    "${idx.video.bitDepth}-bit dv=${idx.video.dolbyVision?.profile} aspect=${"%.3f".format(idx.video.displayAspect)} " +
                    "colour=${idx.video.colour.transfer}/${idx.video.colour.primaries} | keyframes=${k.count} relPos=$relPct% " +
                    "dur=${idx.durationUs / 1_000_000}s | index reads=$indexReads kb=$indexKb | extracted $ok/${picks.size} " +
                    "reads=${ex.requests} kb=${ex.bytesRead / 1024} | first kf s=" +
                    (0 until minOf(5, k.count)).joinToString(",") { "%.3f".format(k.ptsUs[it] / 1e6) })
                if (ok != picks.size) failures.add("$label: extracted $ok/${picks.size}")
            } catch (e: UnsupportedMediaException) {
                println("REAL $label | unsupported: ${e.message}")
            } catch (e: Exception) {
                failures.add("$label: ${e.javaClass.simpleName}: ${e.message}")
                println("REAL $label | ERROR ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                r.close()
            }
        }
        assertTrue("failures: $failures", failures.isEmpty())
    }
}
