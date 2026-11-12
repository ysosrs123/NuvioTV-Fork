package com.nuvio.tv.core.player.thumbnail

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** Synthetic MP4: keyframe offsets from the sample tables, times from stts plus the ctts offsets. */
class Mp4IndexTest {
    private class BytesReader(private val b: ByteArray) : RangeReader {
        override val totalLength: Long get() = b.size.toLong()
        override fun read(offset: Long, length: Int): ByteArray =
            b.copyOfRange(offset.toInt(), minOf(b.size, (offset + length).toInt()))
    }

    private fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun fourcc(s: String) = s.toByteArray(Charsets.ISO_8859_1)
    private fun box(type: String, vararg parts: ByteArray): ByteArray {
        val body = parts.fold(ByteArray(0)) { a, p -> a + p }
        return be32(8 + body.size) + fourcc(type) + body
    }

    /**
     * 6 samples of 10 bytes in one chunk at 1000, one second apart, keyframes 0 and 3. [ctts]: version, then
     * (sample count, offset) pairs in ms.
     */
    private fun build(cttsVersion: Int, vararg ctts: Pair<Int, Int>): ByteArray {
        val header = be32(0) + be32(0) + be32(0) + be32(1000) + be32(6000)
        val entry = ByteArray(78).also {
            it[24] = (1920 shr 8).toByte(); it[25] = 1920.toByte()
            it[26] = (1080 shr 8).toByte(); it[27] = 1080.toByte()
        }
        val stbl = box(
            "stbl",
            box("stsd", be32(0), be32(1), box("av01", entry)),
            box("stsz", be32(0), be32(10), be32(6)),
            box("stss", be32(0), be32(2), be32(1), be32(4)),
            box("stco", be32(0), be32(1), be32(1000)),
            box("stsc", be32(0), be32(1), be32(1), be32(6), be32(1)),
            box("stts", be32(0), be32(1), be32(6), be32(1000)),
            box("ctts", be32(cttsVersion shl 24), be32(ctts.size),
                ctts.fold(ByteArray(0)) { a, (count, offset) -> a + be32(count) + be32(offset) }),
        )
        val trak = box(
            "trak",
            box("mdia", box("mdhd", header), box("hdlr", be32(0), be32(0), fourcc("vide")), box("minf", stbl)),
        )
        return box("ftyp", fourcc("isom"), be32(0)) + box("moov", box("mvhd", header), trak)
    }

    private fun read(file: ByteArray): MediaIndex = Mp4IndexReader.read(BytesReader(file), file)

    @Test
    fun keyframesComeFromTheSampleTables() {
        val m = read(build(0, 6 to 2000))
        assertEquals(ContainerKind.MP4, m.kind)
        assertEquals(6_000_000L, m.durationUs)
        assertEquals(1920, m.video.width)
        assertEquals(1080, m.video.height)
        assertArrayEquals(longArrayOf(1000L, 1030L), m.keyframes.offset)
        assertArrayEquals(intArrayOf(10, 10), m.keyframes.size)
        assertArrayEquals(longArrayOf(2_000_000L, 5_000_000L), m.keyframes.ptsUs)
    }

    @Test
    fun negativeOffsetsInAVersion0TableAreRead() {
        val m = read(build(0, 3 to 0, 3 to -1000))
        assertArrayEquals(longArrayOf(0L, 2_000_000L), m.keyframes.ptsUs)
    }

    @Test
    fun negativeOffsetsInAVersion1TableAreRead() {
        val m = read(build(1, 3 to 0, 3 to -1000))
        assertArrayEquals(longArrayOf(0L, 2_000_000L), m.keyframes.ptsUs)
    }
}
