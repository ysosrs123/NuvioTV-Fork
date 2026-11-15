package com.nuvio.tv.core.player

/**
 * The same RPU check as `dovi_rpu_guard.h`, for code that reads an RPU without the native library: checksum, the
 * header limits libdovi enforces, and the shapes that make the bundled libdovi abort the process. [Result.profile]
 * is the Dolby Vision profile the RPU describes (5, 7, 8, ...), or -1 when the RPU was refused. Keep the two in step;
 * both are tested against the same cases.
 */
internal object DoviRpuGuard {
    enum class Verdict { OK, TOO_SHORT, BAD_START, NO_END_BYTE, BAD_CRC, BAD_HEADER, BAD_CURVE, BAD_PIECES, BAD_BLOCKS, BAD_BLOCK_SIZE, UNREADABLE, TOO_LARGE }

    data class Result(val verdict: Verdict, val profile: Int)

    const val MAX_RPU_BYTES = 16_384
    private const val MAX_PIVOTS_MINUS2 = 7L
    private const val MAX_EXT_BLOCKS = 255L

    private class Refused(val verdict: Verdict) : Exception(null, null, false, false)

    private class Bits(private val data: ByteArray, bytes: Int) {
        private val total = bytes.toLong() * 8
        private var pos = 0L

        val remaining: Long get() = total - pos

        fun bits(n: Int): Long {
            if (n > remaining) throw Refused(Verdict.UNREADABLE)
            var v = 0L
            repeat(n) {
                val b = data[(pos ushr 3).toInt()].toInt()
                v = (v shl 1) or ((b shr (7 - (pos and 7).toInt())) and 1).toLong()
                pos++
            }
            return v
        }

        fun bit(): Boolean = bits(1) != 0L

        fun ue(): Long {
            var zeros = 0
            while (bits(1) == 0L) {
                if (++zeros > 32) throw Refused(Verdict.UNREADABLE)
            }
            return ((1L shl zeros) - 1) + bits(zeros)
        }

        fun skip(n: Long) {
            if (n < 0 || n > remaining) throw Refused(Verdict.UNREADABLE)
            pos += n
        }

        fun align() = skip((8 - (pos and 7)) and 7)
    }

    fun crc32Mpeg2(data: ByteArray, from: Int, end: Int): Int {
        var crc = -1
        for (i in from until end) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 24)
            repeat(8) { crc = if (crc and Int.MIN_VALUE != 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1 }
        }
        return crc
    }

    private fun startOf(d: ByteArray, o: Int): Int {
        fun at(i: Int) = d[o + i].toInt() and 0xFF
        return when {
            at(0) == 0 && at(1) == 0 && at(2) == 0 && at(3) == 1 && at(4) == 25 -> 4
            at(0) == 0 && at(1) == 0 && at(2) == 1 && at(3) == 25 && at(4) == 8 -> 3
            (at(0) == 0 || at(0) == 124) && at(1) == 1 && at(2) == 25 && at(3) == 8 && at(4) == 9 -> 2
            at(0) == 1 && at(1) == 25 && at(2) == 8 && at(3) == 9 -> 1
            at(0) == 25 && at(1) == 8 && at(2) == 9 -> 0
            else -> -1
        }
    }

    /**
     * [escaped]: a type 62 NAL with emulation prevention bytes, as libdovi's unspec62 parser takes it; otherwise a
     * bare RPU.
     */
    fun check(data: ByteArray, offset: Int = 0, length: Int = data.size - offset, escaped: Boolean = true): Result =
        try {
            Result(Verdict.OK, walk(data, offset, length, escaped))
        } catch (refused: Refused) {
            Result(refused.verdict, -1)
        }

    private fun walk(data: ByteArray, offset: Int, length: Int, escaped: Boolean): Int {
        if (offset < 0 || length < 25 || offset > data.size - length) throw Refused(Verdict.TOO_SHORT)
        if (length > MAX_RPU_BYTES) throw Refused(Verdict.TOO_LARGE)
        val start = startOf(data, offset)
        if (start < 0) throw Refused(Verdict.BAD_START)

        val src = offset + start
        val srcLen = length - start
        val buf = ByteArray(srcLen)
        var n = 0
        for (i in 0 until srcLen) {
            if (escaped && i >= 2 && data[src + i - 2].toInt() == 0 && data[src + i - 1].toInt() == 0 &&
                data[src + i].toInt() == 3
            ) continue
            buf[n++] = data[src + i]
        }
        var end = n
        while (end > 0 && buf[end - 1].toInt() == 0) end--
        if (end < 10) throw Refused(Verdict.TOO_SHORT)
        if (buf[end - 1].toInt() and 0xFF != 0x80) throw Refused(Verdict.NO_END_BYTE)
        val stored = ((buf[end - 5].toInt() and 0xFF) shl 24) or ((buf[end - 4].toInt() and 0xFF) shl 16) or
            ((buf[end - 3].toInt() and 0xFF) shl 8) or (buf[end - 2].toInt() and 0xFF)
        if (crc32Mpeg2(buf, 1, end - 5) != stored) throw Refused(Verdict.BAD_CRC)

        val r = Bits(buf, end - 5)
        if (r.bits(8) != 25L) throw Refused(Verdict.BAD_START)
        val rpuType = r.bits(6)
        val rpuFormat = r.bits(11)
        val vdrRpuProfile = r.bits(4)
        val vdrRpuLevel = r.bits(4)
        val seqInfo = r.bit()
        var coefficientDataType = 0L
        var log2Denom = 0L
        var blMinus8 = 0L
        var elMinus8 = 0L
        var vdrMinus8 = 0L
        var reservedZero3 = 0L
        var fullRange = false
        var elSpatial = false
        var disableResidual = false
        if (seqInfo) {
            r.bit()
            coefficientDataType = r.bits(2)
            if (coefficientDataType == 0L) log2Denom = r.ue()
            r.bits(2)
            fullRange = r.bit()
            if (rpuFormat and 0x700 == 0L) {
                blMinus8 = r.ue()
                elMinus8 = r.ue() and 0xFF
                vdrMinus8 = r.ue()
                r.bit()
                reservedZero3 = r.bits(3)
                elSpatial = r.bit()
                disableResidual = r.bit()
            }
            if (coefficientDataType > 1) throw Refused(Verdict.BAD_HEADER)
        }
        val dmPresent = r.bit()
        val usePrev = r.bit()
        if (usePrev) r.ue()
        if (rpuType != 2L || vdrRpuLevel != 0L || blMinus8 != 2L || elMinus8 != 2L || vdrMinus8 > 6 || log2Denom > 23) {
            throw Refused(Verdict.BAD_HEADER)
        }
        val coefBits = if (coefficientDataType == 0L) log2Denom else 32L
        val cdt0 = coefficientDataType == 0L
        val blBits = blMinus8 + 8
        val elBits = elMinus8 + 8

        val profile = when (vdrRpuProfile) {
            0L -> if (fullRange) 5 else 0
            1L -> if (elSpatial && !disableResidual) (if (vdrMinus8 == 4L) 7 else 4) else 8
            else -> 0
        }

        if (!usePrev) {
            repeat(3) { r.ue() }
            val pieces = LongArray(3) {
                val pivotsMinus2 = r.ue()
                if (pivotsMinus2 > MAX_PIVOTS_MINUS2) throw Refused(Verdict.BAD_PIECES)
                r.skip((pivotsMinus2 + 2) * blBits)
                pivotsMinus2 + 1
            }
            val nlq = rpuFormat and 0x700 == 0L && !disableResidual
            if (nlq) {
                if (r.bits(3) != 0L) throw Refused(Verdict.BAD_HEADER)
                r.skip(2 * blBits)
            }
            r.ue()
            r.ue()
            for (count in pieces) {
                for (p in 0 until count) {
                    when (r.ue()) {
                        0L -> {
                            val orderMinus1 = r.ue()
                            if (orderMinus1 > 1) throw Refused(Verdict.BAD_CURVE)
                            if (orderMinus1 == 0L && r.bit()) throw Refused(Verdict.BAD_CURVE)
                            repeat((orderMinus1 + 2).toInt()) {
                                if (cdt0) r.ue()
                                r.skip(coefBits)
                            }
                        }
                        1L -> {
                            val orderMinus1 = r.bits(2)
                            if (orderMinus1 > 2) throw Refused(Verdict.BAD_CURVE)
                            if (cdt0) r.ue()
                            r.skip(coefBits)
                            repeat(((orderMinus1 + 1) * 7).toInt()) {
                                if (cdt0) r.ue()
                                r.skip(coefBits)
                            }
                        }
                        else -> throw Refused(Verdict.BAD_CURVE)
                    }
                }
            }
            if (nlq) {
                repeat(3) {
                    r.skip(elBits)
                    repeat(3) {
                        if (cdt0) r.ue()
                        r.skip(coefBits)
                    }
                }
            }
        }

        if (dmPresent) {
            repeat(3) { r.ue() }
            if (reservedZero3 != 1L) r.skip(9 * 16 + 3 * 32 + 9 * 16 + 3 * 16 + 32 + 5 + 2 + 2 + 2 + 12 + 12 + 10L)
            checkExtBlocks(r, cmV40 = false)
            if (r.remaining + 40 >= 56) checkExtBlocks(r, cmV40 = true)
        }
        return profile
    }

    private fun checkExtBlocks(r: Bits, cmV40: Boolean) {
        val count = r.ue()
        if (count > MAX_EXT_BLOCKS) throw Refused(Verdict.BAD_BLOCKS)
        r.align()
        for (i in 0 until count) {
            val length = r.ue()
            val level = r.bits(8)
            if (cmV40) {
                val known = when (level) {
                    8L -> length in setOf(10L, 12L, 13L, 19L, 25L)
                    9L -> length == 1L || length == 17L
                    10L -> length == 5L || length == 21L
                    else -> true
                }
                if (!known) throw Refused(Verdict.BAD_BLOCK_SIZE)
            }
            if (length > r.remaining / 8) throw Refused(Verdict.UNREADABLE)
            r.skip(length * 8)
        }
    }
}
