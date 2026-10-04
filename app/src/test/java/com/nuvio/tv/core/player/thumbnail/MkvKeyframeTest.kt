package com.nuvio.tv.core.player.thumbnail

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Synthetic Matroska files: index parsing, cluster-start and CueRelativePosition extraction, DV NAL stripping. */
class MkvKeyframeTest {

    private fun id(v: Long): ByteArray {
        val n = when { v > 0xFFFFFF -> 4; v > 0xFFFF -> 3; v > 0xFF -> 2; else -> 1 }
        return ByteArray(n) { ((v shr (8 * (n - 1 - it))) and 0xFF).toByte() }
    }

    /** 8-byte size vint, so offsets are easy to predict. */
    private fun size8(v: Long) = ByteArray(8) { if (it == 0) 0x01 else ((v shr (8 * (7 - it))) and 0xFF).toByte() }

    private fun el(idv: Long, vararg body: ByteArray): ByteArray {
        val b = body.fold(ByteArray(0)) { a, x -> a + x }
        return id(idv) + size8(b.size.toLong()) + b
    }

    private fun uint(idv: Long, v: Long, n: Int = 4) = el(idv, ByteArray(n) { ((v shr (8 * (n - 1 - it))) and 0xFF).toByte() })
    private fun str(idv: Long, s: String) = el(idv, s.toByteArray())

    /** Minimal hvcC with one fake SPS. */
    private fun hvcc(): ByteArray {
        val h = ByteArray(23)
        h[0] = 1
        h[17] = 0x02            // bit depth luma minus 8 = 2
        h[21] = 0x03            // lengthSizeMinusOne = 3
        h[22] = 1               // one array
        val sps = byteArrayOf(0x42, 0x01, 0x11, 0x22)
        return h + byteArrayOf((0x80 or 33).toByte(), 0, 1, 0, sps.size.toByte()) + sps
    }

    private fun nal(type: Int, payload: Int): ByteArray {
        val body = byteArrayOf((type shl 1).toByte(), 0x01, payload.toByte(), payload.toByte())
        return byteArrayOf(0, 0, 0, body.size.toByte()) + body
    }

    private fun simpleBlock(track: Int, relTc: Int, key: Boolean, frame: ByteArray) = el(
        0xA3, byteArrayOf((0x80 or track).toByte(), (relTc shr 8).toByte(), relTc.toByte(), if (key) 0x80.toByte() else 0), frame,
    )

    private class Built(val bytes: ByteArray, val clusterAbs: List<Long>, val relPos: List<Long>)

    /**
     * EBML | Segment(unknown size) { SeekHead(Info, Tracks, Cues) Info Tracks Cluster* Cues }.
     * Each cluster: Timecode, an audio block, then the video keyframe, so the keyframe is not first.
     */
    private fun build(withRelPos: Boolean, dvNals: Boolean = false): Built {
        val ebml = el(0x1A45DFA3, str(0x4282, "matroska"))
        val info = el(0x1549A966, uint(0x2AD7B1, 1_000_000), el(0x4489, java.nio.ByteBuffer.allocate(8).putDouble(30_000.0).array()))
        val track = el(0xAE, uint(0xD7, 1, 1), uint(0x83, 1, 1), str(0x86, "V_MPEGH/ISO/HEVC"), el(0x63A2, hvcc()),
            el(0xE0, uint(0xB0, 3840, 2), uint(0xBA, 2160, 2)))
        val audio = el(0xAE, uint(0xD7, 2, 1), uint(0x83, 2, 1), str(0x86, "A_AC3"))
        val tracks = el(0x1654AE6B, track, audio)
        // clusters at 0 s, 10 s, 20 s
        val clusters = ArrayList<ByteArray>()
        val rels = ArrayList<Long>()
        for (k in 0 until 3) {
            val frame = nal(19, k) + (if (dvNals) nal(62, 9) + nal(63, 9) else ByteArray(0)) + nal(1, k)
            val tc = uint(0xE7, k * 10_000L)
            val audioBlock = simpleBlock(2, 0, true, ByteArray(300) { 7 })
            val video = simpleBlock(1, 0, true, frame)
            rels.add((tc.size + audioBlock.size).toLong())
            clusters.add(el(0x1F43B675, tc, audioBlock, video))
        }
        fun seek(target: Long, pos: Long) = el(0x4DBB, el(0x53AB, id(target)), uint(0x53AC, pos, 8))
        val seekHeadLen = el(0x114D9B74, seek(0x1549A966, 0), seek(0x1654AE6B, 0), seek(0x1C53BB6B, 0)).size
        val infoPos = seekHeadLen.toLong()
        val tracksPos = infoPos + info.size
        var p = tracksPos + tracks.size
        val clusterRel = clusters.map { c -> p.also { p += c.size } }
        val cuesPos = p
        val seekHead = el(0x114D9B74, seek(0x1549A966, infoPos), seek(0x1654AE6B, tracksPos), seek(0x1C53BB6B, cuesPos))
        check(seekHead.size == seekHeadLen)
        val cuePoints = clusterRel.mapIndexed { k, cr ->
            val pos = mutableListOf(uint(0xF7, 1, 1), uint(0xF1, cr, 8))
            if (withRelPos) pos.add(uint(0xF0, rels[k], 4))
            el(0xBB, uint(0xB3, k * 10_000L), el(0xB7, *pos.toTypedArray()))
        }
        val cues = el(0x1C53BB6B, *cuePoints.toTypedArray())
        val segBody = seekHead + info + tracks + clusters.fold(ByteArray(0)) { a, x -> a + x } + cues
        val segHeader = id(0x18538067) + byteArrayOf(0x01, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())   // unknown size
        val file = ebml + segHeader + segBody
        val segData = (ebml.size + segHeader.size).toLong()
        return Built(file, clusterRel.map { segData + it }, rels)
    }

    private class BytesReader(private val b: ByteArray) : RangeReader {
        var reads = 0
        var bytes = 0L
        override val totalLength: Long get() = b.size.toLong()
        override fun read(offset: Long, length: Int): ByteArray {
            reads++
            if (offset >= b.size) return ByteArray(0)
            val end = minOf(b.size.toLong(), offset + length).toInt()
            bytes += end - offset
            return b.copyOfRange(offset.toInt(), end)
        }
    }

    @Test
    fun indexFromCues() {
        val m = build(withRelPos = true)
        val r = BytesReader(m.bytes)
        val idx = MkvIndexReader.read(r, r.read(0, MkvIndexReader.HEAD_BYTES))
        assertEquals(VideoCodec.HEVC, idx.video.codec)
        assertEquals(10, idx.video.bitDepth)
        assertEquals(4, idx.video.nalLengthSize)
        assertEquals(3, idx.keyframes.count)
        assertArrayEquals(longArrayOf(0, 10_000_000, 20_000_000), idx.keyframes.ptsUs)
        assertArrayEquals(m.clusterAbs.toLongArray(), idx.keyframes.offset)
        assertArrayEquals(m.relPos.toLongArray(), idx.keyframes.relPos)
        assertEquals(30_000_000L, idx.durationUs)
    }

    private fun expectedAnnexB(k: Int): ByteArray {
        val sc = byteArrayOf(0, 0, 0, 1)
        val ps = sc + byteArrayOf(0x42, 0x01, 0x11, 0x22)
        fun raw(type: Int) = sc + byteArrayOf((type shl 1).toByte(), 0x01, k.toByte(), k.toByte())
        return ps + raw(19) + raw(1)
    }

    @Test
    fun extractFromClusterStartWithoutRelPos() {
        val m = build(withRelPos = false)
        val r = BytesReader(m.bytes)
        val idx = MkvIndexReader.read(r, r.read(0, MkvIndexReader.HEAD_BYTES))
        val ex = KeyframeExtractor(idx, r)
        for (k in 0 until 3) assertArrayEquals(expectedAnnexB(k), ex.extract(k))
    }

    @Test
    fun extractWithRelPosJumpsDirectlyAfterLearningHeader() {
        val m = build(withRelPos = true)
        val r = BytesReader(m.bytes)
        val idx = MkvIndexReader.read(r, r.read(0, MkvIndexReader.HEAD_BYTES))
        val ex = KeyframeExtractor(idx, r)
        assertArrayEquals(expectedAnnexB(0), ex.extract(0))      // learns the cluster header length
        val before = ex.requests
        assertArrayEquals(expectedAnnexB(2), ex.extract(2))
        assertEquals("direct jump = one request", before + 1, ex.requests)
        assertArrayEquals(expectedAnnexB(1), ex.extract(1))
    }

    @Test
    fun dolbyVisionElStrippedRpuKept() {
        val m = build(withRelPos = true, dvNals = true)
        val r = BytesReader(m.bytes)
        val idx = MkvIndexReader.read(r, r.read(0, MkvIndexReader.HEAD_BYTES))
        val sc = byteArrayOf(0, 0, 0, 1)
        val ps = sc + byteArrayOf(0x42, 0x01, 0x11, 0x22)
        fun raw(type: Int, v: Int) = sc + byteArrayOf((type shl 1).toByte(), 0x01, v.toByte(), v.toByte())
        assertArrayEquals(ps + raw(19, 1) + raw(62, 9) + raw(1, 1), KeyframeExtractor(idx, r).extract(1))
    }

    @Test
    fun tappedSampleBecomesAccessUnitEitherFraming() {
        val m = build(withRelPos = true)
        val r = BytesReader(m.bytes)
        val video = MkvIndexReader.read(r, r.read(0, MkvIndexReader.HEAD_BYTES)).video
        val sc4 = byteArrayOf(0, 0, 0, 1)
        fun body(type: Int, v: Int) = byteArrayOf((type shl 1).toByte(), 0x01, v.toByte(), v.toByte())
        val ps = sc4 + byteArrayOf(0x42, 0x01, 0x11, 0x22)
        val expected = ps + sc4 + body(19, 5) + sc4 + body(62, 9) + sc4 + body(1, 5)
        // Annex-B as media3's extractors write it (mixed 4- and 3-byte start codes), EL (63) dropped, RPU kept.
        val annexB = sc4 + body(19, 5) + byteArrayOf(0, 0, 1) + body(62, 9) + sc4 + body(63, 9) + sc4 + body(1, 5)
        assertArrayEquals(expected, tapSampleToAnnexB(annexB, video))
        // Length-prefixed framing (4-byte lengths, as the synthetic hvcC declares).
        val ld = listOf(body(19, 5), body(62, 9), body(63, 9), body(1, 5))
            .fold(ByteArray(0)) { acc, b -> acc + byteArrayOf(0, 0, 0, b.size.toByte()) + b }
        assertArrayEquals(expected, tapSampleToAnnexB(ld, video))
    }

    @Test
    fun truncatedFileYieldsNullNotCrash() {
        val m = build(withRelPos = false)
        val cut = m.bytes.copyOf((m.clusterAbs[2] + 20).toInt())   // third cluster truncated
        val full = BytesReader(m.bytes)
        val idx = MkvIndexReader.read(full, full.read(0, MkvIndexReader.HEAD_BYTES))
        assertNull(KeyframeExtractor(idx, BytesReader(cut)).extract(2))
        assertNotNull(KeyframeExtractor(idx, BytesReader(cut)).extract(0))
    }

    @Test
    fun noCuesIsUnsupported() {
        val m = build(withRelPos = true)
        val noCues = m.bytes.copyOf(m.clusterAbs.last().toInt() + 10)
        val r = BytesReader(noCues)
        val e = runCatching { MkvIndexReader.read(r, r.read(0, MkvIndexReader.HEAD_BYTES)) }.exceptionOrNull()
        assertTrue(e is UnsupportedMediaException || e is java.io.IOException)
    }

    @Test
    fun nearestKeyframe() {
        val k = KeyframeIndex(longArrayOf(0, 4_000_000, 10_000_000), LongArray(3), LongArray(3), IntArray(3))
        assertEquals(0, k.nearest(1_000_000))
        assertEquals(1, k.nearest(6_000_000))
        assertEquals(2, k.nearest(8_000_000))
        assertEquals(2, k.nearest(99_000_000))
    }

    @Test
    fun indexCodecRoundTrip() {
        val m = build(withRelPos = true)
        val r = BytesReader(m.bytes)
        val idx = MkvIndexReader.read(r, r.read(0, MkvIndexReader.HEAD_BYTES))
        val back = KeyframeIndexCodec.decode(KeyframeIndexCodec.encode(idx))!!
        assertArrayEquals(idx.keyframes.ptsUs, back.keyframes.ptsUs)
        assertArrayEquals(idx.keyframes.relPos, back.keyframes.relPos)
        assertArrayEquals(idx.video.parameterSetsAnnexB, back.video.parameterSetsAnnexB)
        assertEquals(idx.segmentDataStart, back.segmentDataStart)
        assertEquals(idx.video.bitDepth, back.video.bitDepth)
    }

    @Suppress("unused")
    private fun concat(vararg parts: ByteArray) = ByteArrayOutputStream().also { o -> parts.forEach { o.write(it) } }.toByteArray()
}
