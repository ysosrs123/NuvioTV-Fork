package com.nuvio.tv.core.player.thumbnail

import java.io.ByteArrayOutputStream

/** What the keyframe path needs from an avcC / hvcC decoder configuration record. */
internal class CodecConfig(val nalLengthSize: Int, val parameterSetsAnnexB: ByteArray, val bitDepth: Int)

internal object CodecConfigParser {
    private val START_CODE = byteArrayOf(0, 0, 0, 1)
    private val AVC_HIGH_PROFILES = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)

    /** ISO/IEC 14496-15 HEVCDecoderConfigurationRecord. */
    fun parseHvcc(cp: ByteArray): CodecConfig {
        if (cp.size < 23) throw UnsupportedMediaException("short hvcC")
        val bitDepth = (cp[17].toInt() and 0x07) + 8
        val lengthSize = (cp[21].toInt() and 0x03) + 1
        val out = ByteArrayOutputStream()
        var p = 23
        val arrays = cp[22].toInt() and 0xFF
        repeat(arrays) {
            if (p + 3 > cp.size) throw UnsupportedMediaException("truncated hvcC")
            val count = u16(cp, p + 1)
            p += 3
            repeat(count) {
                val n = u16(cp, p)
                if (p + 2 + n > cp.size) throw UnsupportedMediaException("truncated hvcC NAL")
                out.write(START_CODE)
                out.write(cp, p + 2, n)
                p += 2 + n
            }
        }
        return CodecConfig(lengthSize, out.toByteArray(), bitDepth)
    }

    /** ISO/IEC 14496-15 AVCDecoderConfigurationRecord. */
    fun parseAvcc(cp: ByteArray): CodecConfig {
        if (cp.size < 7) throw UnsupportedMediaException("short avcC")
        val lengthSize = (cp[4].toInt() and 0x03) + 1
        val out = ByteArrayOutputStream()
        var bitDepth = 8
        var p = 5
        val nSps = cp[p].toInt() and 0x1F
        p++
        for (i in 0 until nSps) {
            val n = u16(cp, p)
            if (p + 2 + n > cp.size) throw UnsupportedMediaException("truncated avcC SPS")
            if (i == 0) bitDepth = runCatching { avcSpsBitDepth(cp, p + 2, n) }.getOrDefault(8)
            out.write(START_CODE)
            out.write(cp, p + 2, n)
            p += 2 + n
        }
        if (p >= cp.size) throw UnsupportedMediaException("truncated avcC")
        val nPps = cp[p].toInt() and 0xFF
        p++
        repeat(nPps) {
            val n = u16(cp, p)
            if (p + 2 + n > cp.size) throw UnsupportedMediaException("truncated avcC PPS")
            out.write(START_CODE)
            out.write(cp, p + 2, n)
            p += 2 + n
        }
        return CodecConfig(lengthSize, out.toByteArray(), bitDepth)
    }

    /** bit_depth_luma from an H.264 SPS NAL (with its 1-byte header); 8 for non-High profiles. */
    internal fun avcSpsBitDepth(buf: ByteArray, off: Int, len: Int): Int {
        val r = BitReader(unescape(buf, off + 1, len - 1))
        val profileIdc = r.bits(8)
        r.bits(8)   // constraint flags + reserved
        r.bits(8)   // level_idc
        r.ue()      // seq_parameter_set_id
        if (profileIdc !in AVC_HIGH_PROFILES) return 8
        if (r.ue() == 3) r.bits(1)    // chroma_format_idc, separate_colour_plane_flag
        return r.ue() + 8
    }

    private fun u16(b: ByteArray, p: Int): Int = ((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF)

    /** Removes emulation-prevention bytes (00 00 03 -> 00 00). */
    internal fun unescape(buf: ByteArray, off: Int, len: Int): ByteArray {
        val out = ByteArrayOutputStream(len)
        var zeros = 0
        for (i in off until off + len) {
            val b = buf[i].toInt() and 0xFF
            if (zeros >= 2 && b == 3) {
                zeros = 0
                continue
            }
            out.write(b)
            zeros = if (b == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    internal class BitReader(private val d: ByteArray) {
        private var pos = 0
        fun bits(n: Int): Int {
            var v = 0
            repeat(n) {
                val byte = d[pos ushr 3].toInt() and 0xFF
                v = (v shl 1) or ((byte shr (7 - (pos and 7))) and 1)
                pos++
            }
            return v
        }

        fun ue(): Int {
            var zeros = 0
            while (bits(1) == 0) {
                zeros++
                if (zeros > 31) throw UnsupportedMediaException("bad exp-golomb")
            }
            return (1 shl zeros) - 1 + if (zeros > 0) bits(zeros) else 0
        }
    }
}
