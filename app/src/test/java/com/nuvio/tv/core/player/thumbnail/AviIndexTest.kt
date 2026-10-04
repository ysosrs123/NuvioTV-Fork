package com.nuvio.tv.core.player.thumbnail

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Synthetic AVI (idx1): keyframe index, frame-number pts, extraction, VOL lifted into extradata. */
class AviIndexTest {
    private class BytesReader(private val b: ByteArray) : RangeReader {
        override val totalLength: Long get() = b.size.toLong()
        override fun read(offset: Long, length: Int): ByteArray =
            b.copyOfRange(offset.toInt(), minOf(b.size, (offset + length).toInt()))
    }

    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
    private fun chunk(fcc: String, body: ByteArray): ByteArray {
        val pad = if (body.size % 2 == 1) byteArrayOf(0) else ByteArray(0)
        return fcc.toByteArray(Charsets.ISO_8859_1) + le32(body.size) + body + pad
    }
    private fun list(type: String, vararg kids: ByteArray): ByteArray =
        chunk("LIST", type.toByteArray(Charsets.ISO_8859_1) + kids.fold(ByteArray(0)) { a, k -> a + k })

    private val vol = byteArrayOf(0, 0, 1, 0x00, 0, 0, 1, 0x20, 0x11, 0x22)            // VO + VOL start codes
    private fun vop(tag: Int) = byteArrayOf(0, 0, 1, 0xB6.toByte(), tag.toByte(), tag.toByte())

    private class Built(val bytes: ByteArray, val frames: List<ByteArray>)

    /** 6 frames at 25 fps, keyframes 0 and 3 (frame 0 carries the VOL), one audio chunk after frame 1. */
    private fun build(): Built {
        val frames = listOf(vol + vop(0), vop(1), vop(2), vop(3), vop(4), vop(5))
        val strh = "vids".toByteArray(Charsets.ISO_8859_1) + "XVID".toByteArray(Charsets.ISO_8859_1) +
            ByteArray(12) + le32(1) + le32(25) + le32(0) + le32(frames.size) + ByteArray(20)
        val strf = le32(40) + le32(320) + le32(240) + byteArrayOf(1, 0, 24, 0) +
            "XVID".toByteArray(Charsets.ISO_8859_1) + ByteArray(20)
        val hdrl = list("hdrl", chunk("avih", ByteArray(56)), list("strl", chunk("strh", strh), chunk("strf", strf)))
        val movi = ByteArrayOutputStream()
        val index = ByteArrayOutputStream()
        val moviBodyStart = 4                                  // offsets are relative to the 'movi' fourcc
        var rel = moviBodyStart
        for ((i, f) in frames.withIndex()) {
            val c = chunk("00dc", f)
            index.write("00dc".toByteArray(Charsets.ISO_8859_1)); index.write(le32(if (i == 0 || i == 3) 0x10 else 0))
            index.write(le32(rel)); index.write(le32(f.size))
            movi.write(c); rel += c.size
            if (i == 1) {
                val a = chunk("01wb", ByteArray(7))
                index.write("01wb".toByteArray(Charsets.ISO_8859_1)); index.write(le32(0x10))
                index.write(le32(rel)); index.write(le32(7))
                movi.write(a); rel += a.size
            }
        }
        val moviList = list("movi", movi.toByteArray())
        val riffBody = "AVI ".toByteArray(Charsets.ISO_8859_1) + hdrl + moviList + chunk("idx1", index.toByteArray())
        return Built("RIFF".toByteArray(Charsets.ISO_8859_1) + le32(riffBody.size) + riffBody, frames)
    }

    @Test
    fun idx1KeyframesPtsAndExtraction() {
        val m = build()
        val r = BytesReader(m.bytes)
        val idx = AviIndexReader.read(r, r.read(0, 256 * 1024))
        assertEquals(ContainerKind.AVI, idx.kind)
        assertEquals(VideoCodec.MPEG4, idx.video.codec)
        assertEquals(2, idx.keyframes.count)
        assertEquals(0L, idx.keyframes.ptsUs[0])
        assertEquals(120_000L, idx.keyframes.ptsUs[1])                   // frame 3 at 25 fps
        assertEquals(240_000L, idx.durationUs)
        assertArrayEquals(vol, idx.video.extradata)
        val ex = KeyframeExtractor(idx, r)
        assertArrayEquals(m.frames[0], ex.extract(0))
        assertArrayEquals(m.frames[3], ex.extract(1))
    }
}
