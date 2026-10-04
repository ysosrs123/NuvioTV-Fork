package com.nuvio.tv.core.player.thumbnail

/**
 * Codec identification and decoder extradata for the codecs beyond H.264/HEVC. They need MPV's FFmpeg: the
 * libmediainfo FFmpeg 6.0 fallback has no VC-1 / MPEG-4 Part 2 / AV1 decoders, so those titles get no thumbnails.
 */
internal object LegacyCodecs {
    /** Video FourCC (AVI strh/strf, Matroska V_MS/VFW/FOURCC) -> codec, or null when not supported. */
    fun codecForFourcc(fourcc: String): VideoCodec? = when (fourcc.uppercase()) {
        "XVID", "XVIX", "DIVX", "DX50", "FMP4", "MP4V", "M4S2", "3IV2", "BLZ0", "DXGM", "RMP4", "SEDG", "UMP4",
        "WV1F", "GEOX", "HDX4", "SMP4" -> VideoCodec.MPEG4
        "MPG2", "MPEG", "MMES", "EM2V", "M2V1" -> VideoCodec.MPEG2
        "MPG1", "PIM1" -> VideoCodec.MPEG1
        "WVC1", "WVP2" -> VideoCodec.VC1
        "WMV3", "WMVP" -> VideoCodec.WMV3
        "H264", "X264", "AVC1", "DAVC", "VSSH" -> VideoCodec.H264
        "HEVC", "H265", "HVC1", "HEV1" -> VideoCodec.HEVC
        "AV01" -> VideoCodec.AV1
        else -> null
    }

    fun fourccAt(b: ByteArray, at: Int): String =
        if (at + 4 <= b.size) String(b, at, 4, Charsets.ISO_8859_1) else ""

    /** In-band headers before the first VOP start code (00 00 01 B6), or null without a VOL (00 00 01 20..2F). */
    fun mpeg4HeadersBeforeVop(frame: ByteArray): ByteArray? {
        var vol = false
        var i = 0
        while (i + 3 < frame.size) {
            if (frame[i].toInt() == 0 && frame[i + 1].toInt() == 0 && frame[i + 2].toInt() == 1) {
                val code = frame[i + 3].toInt() and 0xFF
                if (code in 0x20..0x2F) vol = true
                if (code == 0xB6) return if (vol && i > 0) frame.copyOfRange(0, i) else null
                i += 3
            } else {
                i++
            }
        }
        return null
    }

    fun hasMpeg4Vol(extradata: ByteArray): Boolean {
        for (i in 0 until extradata.size - 3) {
            if (extradata[i].toInt() == 0 && extradata[i + 1].toInt() == 0 && extradata[i + 2].toInt() == 1 &&
                (extradata[i + 3].toInt() and 0xFF) in 0x20..0x2F
            ) return true
        }
        return false
    }

    /** av1C (AV1CodecConfigurationRecord): 10/12-bit from high_bitdepth / twelve_bit, else 8. */
    fun av1BitDepth(av1c: ByteArray): Int {
        if (av1c.size < 3) return 8
        val flags = av1c[2].toInt() and 0xFF
        val high = (flags shr 6) and 1
        val twelve = (flags shr 5) and 1
        return if (high == 0) 8 else if (twelve == 1) 12 else 10
    }

    /**
     * MP4 'esds' payload (after the FullBox header) -> (objectTypeIndication, DecoderSpecificInfo) or null.
     * Descriptor sizes are 7 bits per byte, top bit = more.
     */
    fun parseEsds(b: ByteArray, start: Int, end: Int): Pair<Int, ByteArray>? {
        var p = start
        fun readLen(): Int {
            var len = 0
            for (k in 0 until 4) {
                if (p >= end) return -1
                val v = b[p++].toInt() and 0xFF
                len = (len shl 7) or (v and 0x7F)
                if (v and 0x80 == 0) break
            }
            return len
        }
        if (p >= end || b[p++].toInt() != 0x03) return null      // ES_Descriptor
        if (readLen() < 0) return null
        p += 2                                                   // ES_ID
        if (p >= end) return null
        val flags = b[p++].toInt() and 0xFF
        if (flags and 0x80 != 0) p += 2                          // dependsOn_ES_ID
        if (flags and 0x40 != 0) { if (p >= end) return null; p += 1 + (b[p].toInt() and 0xFF) }   // URL
        if (flags and 0x20 != 0) p += 2                          // OCR_ES_Id
        if (p >= end || b[p++].toInt() != 0x04) return null      // DecoderConfigDescriptor
        if (readLen() < 13) return null
        val objectType = b[p].toInt() and 0xFF
        p += 13
        if (p >= end || b[p++].toInt() != 0x05) return objectType to ByteArray(0)   // no DecoderSpecificInfo
        val dsiLen = readLen()
        if (dsiLen < 0 || p + dsiLen > end) return null
        return objectType to b.copyOfRange(p, p + dsiLen)
    }

    /** MP4 objectTypeIndication -> codec (0x20 MPEG-4 Visual, 0x60-0x65 MPEG-2, 0x6A MPEG-1). */
    fun codecForObjectType(ot: Int): VideoCodec? = when (ot) {
        0x20 -> VideoCodec.MPEG4
        in 0x60..0x65 -> VideoCodec.MPEG2
        0x6A -> VideoCodec.MPEG1
        else -> null
    }
}
