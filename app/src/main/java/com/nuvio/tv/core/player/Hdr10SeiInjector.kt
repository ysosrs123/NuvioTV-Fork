package com.nuvio.tv.core.player

/**
 * Builds HDR10 static metadata SEI (MDCV + CLLI) and injects it into a
 * base-layer HEVC stream whose Dolby Vision RPU was stripped, so an HDR10 sink
 * tone-maps against the source's mastering metadata. The two NALs are built
 * once per stream and inserted ahead of the first VCL NAL of each sample, in
 * length-delimited or Annex-B framing.
 *
 * Layouts follow Rec. ITU-T H.265 D.2.28 (payloadType 137) and D.2.35 (144).
 *  - Mastering luminance comes from the RPU's source_*_pq via the ST 2084 EOTF.
 *  - MaxCLL/MaxFALL come from the L6 block; CLLI is omitted when both are 0.
 *  - Display primaries are assumed BT.2020 + D65; the RPU does not carry them.
 *
 * [selfTest] round-trips the bytes. RPU parsing: quietvoid/libdovi (MIT).
 */
internal object Hdr10SeiInjector {

    private const val NAL_HEADER_BYTE0 = 0x4E   // forbidden 0, nal_unit_type 39 (prefix SEI), layer 0
    private const val NAL_HEADER_BYTE1 = 0x01   // nuh_temporal_id_plus1 = 1
    private const val SEI_TYPE_MDCV = 137
    private const val SEI_TYPE_CLLI = 144
    private const val VCL_NAL_MAX_TYPE = 31     // HEVC VCL NAL unit types are 0..31

    // BT.2020 primaries and D65 white point in MDCV units of 0.00002 (coordinate
    // * 50000), given in the spec's G, B, R primary order.
    private const val PRIM_G_X = 8500    // 0.170
    private const val PRIM_G_Y = 39850   // 0.797
    private const val PRIM_B_X = 6550    // 0.131
    private const val PRIM_B_Y = 2300    // 0.046
    private const val PRIM_R_X = 35400   // 0.708
    private const val PRIM_R_Y = 14600   // 0.292
    private const val WHITE_X = 15635    // 0.3127 (D65)
    private const val WHITE_Y = 16450    // 0.3290 (D65)

    // ---------------------------------------------------------------------------
    // SEI authoring
    // ---------------------------------------------------------------------------

    /** SMPTE ST 2084 (PQ) EOTF applied to a 12-bit RPU code value; returns nits. */
    fun pqCodeToNits(pq12: Int): Double {
        val e = pq12.coerceIn(0, 4095).toDouble() / 4095.0
        val m1 = 0.1593017578125
        val m2 = 78.84375
        val c1 = 0.8359375
        val c2 = 18.8515625
        val c3 = 18.6875
        val ep = Math.pow(e, 1.0 / m2)
        val num = (ep - c1).coerceAtLeast(0.0)
        val den = c2 - c3 * ep
        val l = if (den <= 0.0) 0.0 else Math.pow(num / den, 1.0 / m1)
        return l * 10000.0   // normalized display luminance -> nits (PQ peak is 10000 cd/m^2)
    }

    /** MDCV (mastering_display_colour_volume) prefix-SEI NAL, 24-byte payload. */
    fun buildMdcvSeiNal(sourceMinPq: Int, sourceMaxPq: Int): ByteArray {
        // SEI luminance is in units of 0.0001 cd/m^2.
        val maxLum = Math.round(pqCodeToNits(sourceMaxPq) * 10000.0).coerceIn(0L, 0xFFFFFFFFL)
        val minLum = Math.round(pqCodeToNits(sourceMinPq) * 10000.0).coerceIn(0L, 0xFFFFFFFFL)
        val p = ByteArray(24)
        var i = 0
        i = putU16(p, i, PRIM_G_X); i = putU16(p, i, PRIM_G_Y)
        i = putU16(p, i, PRIM_B_X); i = putU16(p, i, PRIM_B_Y)
        i = putU16(p, i, PRIM_R_X); i = putU16(p, i, PRIM_R_Y)
        i = putU16(p, i, WHITE_X); i = putU16(p, i, WHITE_Y)
        i = putU32(p, i, maxLum); putU32(p, i, minLum)
        return wrapSeiNal(SEI_TYPE_MDCV, p)
    }

    /** CLLI (content_light_level_info) prefix-SEI NAL, or null when both are 0. */
    fun buildClliSeiNal(maxCll: Int, maxFall: Int): ByteArray? {
        if (maxCll <= 0 && maxFall <= 0) return null
        val p = ByteArray(4)
        putU16(p, 0, maxCll.coerceIn(0, 0xFFFF))
        putU16(p, 2, maxFall.coerceIn(0, 0xFFFF))
        return wrapSeiNal(SEI_TYPE_CLLI, p)
    }

    /**
     * The SEI NALs to inject for a stream, from its RPU static metadata. MDCV is
     * always present; CLLI is included only when MaxCLL/MaxFALL are known.
     * Returns an empty list when no metadata is available.
     */
    fun buildSeiNals(meta: DoviBridge.RpuStaticMetadata?): List<ByteArray> {
        if (meta == null) return emptyList()
        val out = ArrayList<ByteArray>(2)
        out += buildMdcvSeiNal(meta.sourceMinPq, meta.sourceMaxPq)
        buildClliSeiNal(meta.maxCll ?: 0, meta.maxFall ?: 0)?.let { out += it }
        return out
    }

    // Assemble one prefix-SEI NAL: [2-byte header][emulation-escaped RBSP], where
    // RBSP = payloadType byte + payloadSize byte + payload + rbsp_trailing_bits.
    // Both type and size are < 255 here, so each is a single byte (no 0xFF run).
    private fun wrapSeiNal(payloadType: Int, payload: ByteArray): ByteArray {
        val rbsp = ByteArray(2 + payload.size + 1)
        rbsp[0] = payloadType.toByte()
        rbsp[1] = payload.size.toByte()
        System.arraycopy(payload, 0, rbsp, 2, payload.size)
        rbsp[rbsp.size - 1] = 0x80.toByte()   // rbsp_trailing_bits: stop bit + alignment
        val escaped = escapeRbsp(rbsp)
        val nal = ByteArray(2 + escaped.size)
        nal[0] = NAL_HEADER_BYTE0.toByte()
        nal[1] = NAL_HEADER_BYTE1.toByte()
        System.arraycopy(escaped, 0, nal, 2, escaped.size)
        return nal
    }

    // Emulation prevention: after any run of two 0x00 bytes, a following byte of
    // 0x00..0x03 gets a 0x03 inserted before it.
    private fun escapeRbsp(rbsp: ByteArray): ByteArray {
        val out = ArrayList<Byte>(rbsp.size + 4)
        var zeros = 0
        for (b in rbsp) {
            val v = b.toInt() and 0xFF
            if (zeros >= 2 && v <= 0x03) {
                out.add(0x03.toByte())
                zeros = 0
            }
            out.add(b)
            zeros = if (v == 0x00) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    // ---------------------------------------------------------------------------
    // Injection
    // ---------------------------------------------------------------------------

    /**
     * Insert [seiNals] as length-delimited NALs immediately before the first VCL
     * NAL of a length-delimited sample. If [seiNals] is empty the input is
     * returned unchanged.
     */
    fun injectLengthDelimited(
        sample: ByteArray,
        sampleLen: Int,
        nalLengthFieldLength: Int,
        seiNals: List<ByteArray>
    ): ByteArray {
        if (seiNals.isEmpty() || nalLengthFieldLength !in 1..4) return sample.copyOf(sampleLen)
        val insertAt = firstVclOffsetLengthDelimited(sample, sampleLen, nalLengthFieldLength)
        val extra = seiNals.sumOf { nalLengthFieldLength + it.size }
        val out = ByteArray(sampleLen + extra)
        System.arraycopy(sample, 0, out, 0, insertAt)
        var o = insertAt
        for (nal in seiNals) {
            o = putLengthField(out, o, nal.size, nalLengthFieldLength)
            System.arraycopy(nal, 0, out, o, nal.size); o += nal.size
        }
        System.arraycopy(sample, insertAt, out, o, sampleLen - insertAt)
        return out
    }

    /**
     * Insert [seiNals] as 4-byte-start-code NALs immediately before the first VCL
     * NAL of an Annex-B sample. If [seiNals] is empty the input is returned
     * unchanged.
     */
    fun injectAnnexB(sample: ByteArray, sampleLen: Int, seiNals: List<ByteArray>): ByteArray {
        if (seiNals.isEmpty()) return sample.copyOf(sampleLen)
        val insertAt = firstVclOffsetAnnexB(sample, sampleLen)
        val extra = seiNals.sumOf { 4 + it.size }
        val out = ByteArray(sampleLen + extra)
        System.arraycopy(sample, 0, out, 0, insertAt)
        var o = insertAt
        for (nal in seiNals) {
            out[o] = 0; out[o + 1] = 0; out[o + 2] = 0; out[o + 3] = 1; o += 4
            System.arraycopy(nal, 0, out, o, nal.size); o += nal.size
        }
        System.arraycopy(sample, insertAt, out, o, sampleLen - insertAt)
        return out
    }

    // Offset at which to insert (start of the first VCL NAL's length prefix), or
    // sampleLen (append) if no VCL NAL is found.
    private fun firstVclOffsetLengthDelimited(sample: ByteArray, sampleLen: Int, nlf: Int): Int {
        var pos = 0
        while (pos + nlf <= sampleLen) {
            val nalSize = readLengthField(sample, pos, nlf)
            val nalStart = pos + nlf
            if (nalSize <= 0 || nalStart + nalSize > sampleLen) break
            val nalType = (sample[nalStart].toInt() ushr 1) and 0x3F
            if (nalType <= VCL_NAL_MAX_TYPE) return pos
            pos = nalStart + nalSize
        }
        return sampleLen
    }

    // Offset of the start code of the first VCL NAL, or sampleLen if none.
    private fun firstVclOffsetAnnexB(sample: ByteArray, sampleLen: Int): Int {
        var scan = 0
        while (scan < sampleLen) {
            val sc = findStartCode(sample, scan, sampleLen)
            if (sc < 0) break
            val scLen = startCodeLength(sample, sc, sampleLen)
            val nalBegin = sc + scLen
            if (nalBegin < sampleLen) {
                val nalType = (sample[nalBegin].toInt() ushr 1) and 0x3F
                if (nalType <= VCL_NAL_MAX_TYPE) return sc
            }
            scan = nalBegin
        }
        return sampleLen
    }

    // ---------------------------------------------------------------------------
    // Detection (inject-when-absent guard)
    // ---------------------------------------------------------------------------

    /** True if the sample already carries an MDCV or CLLI prefix-SEI NAL. */
    fun hasHdr10StaticSei(
        sample: ByteArray,
        sampleLen: Int,
        annexB: Boolean,
        nalLengthFieldLength: Int
    ): Boolean = if (annexB) {
        var scan = 0
        var found = false
        while (scan < sampleLen && !found) {
            val sc = findStartCode(sample, scan, sampleLen)
            if (sc < 0) break
            val scLen = startCodeLength(sample, sc, sampleLen)
            val nalBegin = sc + scLen
            val next = findStartCode(sample, nalBegin + 2, sampleLen)
            val nalEnd = if (next < 0) sampleLen else next
            found = isHdr10StaticSeiNal(sample, nalBegin, nalEnd - nalBegin)
            scan = nalEnd
        }
        found
    } else {
        if (nalLengthFieldLength !in 1..4) return false
        var pos = 0
        var found = false
        while (pos + nalLengthFieldLength <= sampleLen && !found) {
            val nalSize = readLengthField(sample, pos, nalLengthFieldLength)
            val nalStart = pos + nalLengthFieldLength
            if (nalSize <= 0 || nalStart + nalSize > sampleLen) break
            found = isHdr10StaticSeiNal(sample, nalStart, nalSize)
            pos = nalStart + nalSize
        }
        found
    }

    private fun isHdr10StaticSeiNal(sample: ByteArray, nalOffset: Int, nalSize: Int): Boolean {
        if (nalSize < 3) return false
        val nalType = (sample[nalOffset].toInt() ushr 1) and 0x3F
        if (nalType != 39) return false
        val payloadType = sample[nalOffset + 2].toInt() and 0xFF   // single byte at 137/144
        return payloadType == SEI_TYPE_MDCV || payloadType == SEI_TYPE_CLLI
    }

    // ---------------------------------------------------------------------------
    // Low-level byte helpers (self-contained to keep this file independently
    // testable; deliberately not sharing HevcDvRpuStripper's private helpers).
    // ---------------------------------------------------------------------------

    private fun putU16(a: ByteArray, off: Int, v: Int): Int {
        a[off] = ((v ushr 8) and 0xFF).toByte()
        a[off + 1] = (v and 0xFF).toByte()
        return off + 2
    }

    private fun putU32(a: ByteArray, off: Int, v: Long): Int {
        a[off] = ((v ushr 24) and 0xFF).toByte()
        a[off + 1] = ((v ushr 16) and 0xFF).toByte()
        a[off + 2] = ((v ushr 8) and 0xFF).toByte()
        a[off + 3] = (v and 0xFF).toByte()
        return off + 4
    }

    private fun readLengthField(a: ByteArray, off: Int, len: Int): Int {
        var v = 0
        for (k in 0 until len) v = (v shl 8) or (a[off + k].toInt() and 0xFF)
        return v
    }

    private fun putLengthField(a: ByteArray, off: Int, v: Int, len: Int): Int {
        for (k in 0 until len) a[off + k] = ((v ushr (8 * (len - 1 - k))) and 0xFF).toByte()
        return off + len
    }

    // Returns the offset of the next Annex-B start code (00 00 01 or 00 00 00 01)
    // at or after [from], or -1.
    private fun findStartCode(a: ByteArray, from: Int, end: Int): Int {
        var i = from
        while (i + 2 < end) {
            if (a[i].toInt() == 0 && a[i + 1].toInt() == 0) {
                if (a[i + 2].toInt() == 1) return i
                if (i + 3 < end && a[i + 2].toInt() == 0 && a[i + 3].toInt() == 1) return i
            }
            i++
        }
        return -1
    }

    private fun startCodeLength(a: ByteArray, at: Int, end: Int): Int =
        if (at + 3 < end && a[at + 2].toInt() == 0 && a[at + 3].toInt() == 1) 4 else 3

    // ---------------------------------------------------------------------------
    // Self-test (build -> parse round trip + injection ordering). Logs and
    // returns pass/fail; called from DoviBridge's startup self-test.
    // ---------------------------------------------------------------------------

    private data class ParsedSei(val type: Int, val payload: ByteArray)

    private fun parseSeiNal(nal: ByteArray): ParsedSei? {
        if (nal.size < 4) return null
        val rbsp = unescapeRbsp(nal, 2)
        if (rbsp.size < 3) return null
        val type = rbsp[0].toInt() and 0xFF
        val size = rbsp[1].toInt() and 0xFF
        if (2 + size > rbsp.size) return null
        return ParsedSei(type, rbsp.copyOfRange(2, 2 + size))
    }

    private fun unescapeRbsp(nal: ByteArray, from: Int): ByteArray {
        val out = ArrayList<Byte>(nal.size)
        var zeros = 0
        var i = from
        while (i < nal.size) {
            val v = nal[i].toInt() and 0xFF
            if (zeros >= 2 && v == 0x03 && i + 1 < nal.size && (nal[i + 1].toInt() and 0xFF) <= 0x03) {
                zeros = 0; i++; continue
            }
            out.add(nal[i])
            zeros = if (v == 0x00) zeros + 1 else 0
            i++
        }
        return out.toByteArray()
    }

    private fun readU16(a: ByteArray, off: Int): Int =
        ((a[off].toInt() and 0xFF) shl 8) or (a[off + 1].toInt() and 0xFF)

    private fun readU32(a: ByteArray, off: Int): Long =
        ((a[off].toLong() and 0xFF) shl 24) or ((a[off + 1].toLong() and 0xFF) shl 16) or
            ((a[off + 2].toLong() and 0xFF) shl 8) or (a[off + 3].toLong() and 0xFF)

    fun selfTest(): String {
        // MDCV round trip (~0.005 and ~1000 nits from representative PQ codes).
        val mdcv = buildMdcvSeiNal(sourceMinPq = 62, sourceMaxPq = 3079)
        val pm = parseSeiNal(mdcv) ?: return "FAIL:mdcv-parse"
        if (pm.type != SEI_TYPE_MDCV || pm.payload.size != 24) return "FAIL:mdcv-header"
        if (readU16(pm.payload, 0) != PRIM_G_X || readU16(pm.payload, 14) != WHITE_Y) return "FAIL:mdcv-primaries"
        val maxLum = readU32(pm.payload, 16)
        if (maxLum < 9_000_000L || maxLum > 11_000_000L) return "FAIL:mdcv-luminance($maxLum)"

        // CLLI round trip + zero-suppression.
        val clli = buildClliSeiNal(617, 496) ?: return "FAIL:clli-null"
        val pc = parseSeiNal(clli) ?: return "FAIL:clli-parse"
        if (pc.type != SEI_TYPE_CLLI) return "FAIL:clli-type"
        if (readU16(pc.payload, 0) != 617 || readU16(pc.payload, 2) != 496) return "FAIL:clli-values"
        if (buildClliSeiNal(0, 0) != null) return "FAIL:clli-zero-not-suppressed"

        // Emulation-prevention round trip: a payload forcing 00 00 00 must survive.
        val forced = wrapSeiNal(5, byteArrayOf(0, 0, 0, 1, 2))
        val pf = parseSeiNal(forced) ?: return "FAIL:escape-parse"
        if (!pf.payload.contentEquals(byteArrayOf(0, 0, 0, 1, 2))) return "FAIL:escape-roundtrip"

        // Injection ordering (length-delimited): SPS(33) + slice(1) -> SEI before slice.
        val ld = syntheticLd()
        val injLd = injectLengthDelimited(ld, ld.size, 4, listOf(mdcv))
        if (nalTypeSequenceLd(injLd, 4) != listOf(33, 39, 1)) return "FAIL:inject-ld-order"
        if (!hasHdr10StaticSei(injLd, injLd.size, annexB = false, nalLengthFieldLength = 4)) return "FAIL:detect-ld"

        // Injection ordering (Annex-B): SPS(33) + slice(1) -> SEI before slice.
        val ab = syntheticAnnexB()
        val injAb = injectAnnexB(ab, ab.size, listOf(mdcv))
        if (nalTypeSequenceAnnexB(injAb, injAb.size) != listOf(33, 39, 1)) return "FAIL:inject-annexb-order"
        if (!hasHdr10StaticSei(injAb, injAb.size, annexB = true, nalLengthFieldLength = 4)) return "FAIL:detect-annexb"

        return "PASS"
    }

    private fun spsNal() = byteArrayOf((33 shl 1).toByte(), 0x01, 0x11, 0x22)
    private fun sliceNal() = byteArrayOf((1 shl 1).toByte(), 0x01, 0x33, 0x44, 0x55)

    private fun syntheticLd(): ByteArray {
        val sps = spsNal(); val slice = sliceNal()
        val out = ByteArray(4 + sps.size + 4 + slice.size)
        var o = putLengthField(out, 0, sps.size, 4)
        System.arraycopy(sps, 0, out, o, sps.size); o += sps.size
        o = putLengthField(out, o, slice.size, 4)
        System.arraycopy(slice, 0, out, o, slice.size)
        return out
    }

    private fun syntheticAnnexB(): ByteArray {
        val sps = spsNal(); val slice = sliceNal()
        val out = ByteArray(4 + sps.size + 4 + slice.size)
        var o = 0
        out[o] = 0; out[o + 1] = 0; out[o + 2] = 0; out[o + 3] = 1; o += 4
        System.arraycopy(sps, 0, out, o, sps.size); o += sps.size
        out[o] = 0; out[o + 1] = 0; out[o + 2] = 0; out[o + 3] = 1; o += 4
        System.arraycopy(slice, 0, out, o, slice.size)
        return out
    }

    private fun nalTypeSequenceLd(a: ByteArray, nlf: Int): List<Int> {
        val types = ArrayList<Int>()
        var pos = 0
        while (pos + nlf <= a.size) {
            val nalSize = readLengthField(a, pos, nlf)
            val nalStart = pos + nlf
            if (nalSize <= 0 || nalStart + nalSize > a.size) break
            types += (a[nalStart].toInt() ushr 1) and 0x3F
            pos = nalStart + nalSize
        }
        return types
    }

    private fun nalTypeSequenceAnnexB(a: ByteArray, len: Int): List<Int> {
        val types = ArrayList<Int>()
        var scan = 0
        while (scan < len) {
            val sc = findStartCode(a, scan, len)
            if (sc < 0) break
            val scLen = startCodeLength(a, sc, len)
            val nalBegin = sc + scLen
            if (nalBegin < len) types += (a[nalBegin].toInt() ushr 1) and 0x3F
            scan = findStartCode(a, nalBegin + 2, len).let { if (it < 0) len else it }
        }
        return types
    }
}
