package com.nuvio.tv.core.player.thumbnail

/**
 * Reads a progressive MP4's `moov` (front or back) through bounded range reads. Keyframes get exact byte ranges
 * (stss + stsc/stco/co64 + stsz) and times from stts + ctts minus the first edit's media_time. No fragmented MP4.
 */
internal object Mp4IndexReader {
    private const val MAX_MOOV = 48 * 1024 * 1024

    fun looksLikeMp4(head: ByteArray): Boolean =
        head.size >= 8 && String(head, 4, 4, Charsets.ISO_8859_1) in setOf("ftyp", "moov", "free", "skip", "wide", "mdat")

    private class Box(val type: String, val start: Int, val dataStart: Int, val end: Int)

    private fun u32(b: ByteArray, p: Int): Long =
        ((b[p].toLong() and 0xFF) shl 24) or ((b[p + 1].toLong() and 0xFF) shl 16) or
            ((b[p + 2].toLong() and 0xFF) shl 8) or (b[p + 3].toLong() and 0xFF)

    private fun u64(b: ByteArray, p: Int): Long = (u32(b, p) shl 32) or u32(b, p + 4)
    private fun u16(b: ByteArray, p: Int): Int = ((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF)
    private fun type(b: ByteArray, p: Int) = String(b, p, 4, Charsets.ISO_8859_1)

    private fun boxes(b: ByteArray, start: Int, end: Int): List<Box> {
        val out = ArrayList<Box>()
        var p = start
        while (p + 8 <= end) {
            var size = u32(b, p)
            var hdr = 8
            if (size == 1L) {
                if (p + 16 > end) break
                size = u64(b, p + 8)
                hdr = 16
            } else if (size == 0L) {
                size = (end - p).toLong()
            }
            if (size < hdr || p + size > end) break
            out.add(Box(type(b, p + 4), p, p + hdr, (p + size).toInt()))
            p += size.toInt()
        }
        return out
    }

    private fun child(b: ByteArray, parent: Box, t: String): Box? = boxes(b, parent.dataStart, parent.end).firstOrNull { it.type == t }

    fun read(reader: RangeReader, head: ByteArray): MediaIndex {
        val moov = locateMoov(reader, head)
        val root = Box("moov", 0, 0, moov.size)
        if (child(moov, root, "mvex") != null) throw UnsupportedMediaException("fragmented MP4")
        val mvhd = child(moov, root, "mvhd") ?: throw UnsupportedMediaException("no mvhd")
        val mvVersion = moov[mvhd.dataStart].toInt()
        val mvTimescale = u32(moov, mvhd.dataStart + if (mvVersion == 1) 20 else 12)
        val mvDuration = if (mvVersion == 1) u64(moov, mvhd.dataStart + 24) else u32(moov, mvhd.dataStart + 16)

        for (trak in boxes(moov, root.dataStart, root.end).filter { it.type == "trak" }) {
            val mdia = child(moov, trak, "mdia") ?: continue
            val hdlr = child(moov, mdia, "hdlr") ?: continue
            if (type(moov, hdlr.dataStart + 8) != "vide") continue
            val mdhd = child(moov, mdia, "mdhd") ?: continue
            val mdVersion = moov[mdhd.dataStart].toInt()
            val timescale = u32(moov, mdhd.dataStart + if (mdVersion == 1) 20 else 12)
            val stbl = child(moov, mdia, "minf")?.let { child(moov, it, "stbl") } ?: continue
            val track = parseStsd(moov, stbl) ?: continue
            val editShift = child(moov, trak, "edts")?.let { firstEditMediaTime(moov, it) } ?: 0L
            val kf = buildKeyframes(moov, stbl, timescale, editShift)
            if (kf.count < 1) throw UnsupportedMediaException("no sync samples")
            val durationUs = if (mvTimescale > 0) mvDuration * 1_000_000L / mvTimescale else 0L
            return MediaIndex(ContainerKind.MP4, reader.totalLength, durationUs, track, kf)
        }
        throw UnsupportedMediaException("no supported video track")
    }

    /** Walks top-level boxes with header-only reads until `moov`, then reads it whole (bounded). */
    private fun locateMoov(reader: RangeReader, head: ByteArray): ByteArray {
        var pos = 0L
        val total = reader.totalLength
        var guard = 0
        while (guard++ < 64) {
            val hdr = if (pos + 16 <= head.size) head.copyOfRange(pos.toInt(), pos.toInt() + 16) else reader.read(pos, 16)
            if (hdr.size < 8) break
            var size = u32(hdr, 0)
            var hdrLen = 8
            if (size == 1L) {
                size = u64(hdr, 8)
                hdrLen = 16
            } else if (size == 0L) {
                size = if (total > 0) total - pos else throw UnsupportedMediaException("box to EOF with unknown length")
            }
            val t = type(hdr, 4)
            if (t == "moov") {
                if (size > MAX_MOOV) throw UnsupportedMediaException("moov too large ($size)")
                val body = if (pos + size <= head.size) {
                    head.copyOfRange((pos + hdrLen).toInt(), (pos + size).toInt())
                } else {
                    reader.read(pos + hdrLen, (size - hdrLen).toInt())
                }
                if (body.size.toLong() != size - hdrLen) throw UnsupportedMediaException("truncated moov")
                return body
            }
            if (t == "moof") throw UnsupportedMediaException("fragmented MP4")
            if (size < hdrLen) throw UnsupportedMediaException("bad box size")
            pos += size
            if (total > 0 && pos >= total) break
        }
        throw UnsupportedMediaException("moov not found")
    }

    private fun parseStsd(b: ByteArray, stbl: Box): VideoTrack? {
        val stsd = child(b, stbl, "stsd") ?: return null
        // full box (4) + entry_count (4), then the first sample entry
        val entries = boxes(b, stsd.dataStart + 8, stsd.end)
        val se = entries.firstOrNull() ?: return null
        var codec = when (se.type) {
            "avc1", "avc3" -> VideoCodec.H264
            "hvc1", "hev1", "dvh1", "dvhe" -> VideoCodec.HEVC
            "mp4v" -> VideoCodec.MPEG4          // refined by the esds objectTypeIndication
            "av01" -> VideoCodec.AV1
            else -> return null
        }
        var extradata = ByteArray(0)
        var esdsOk = codec != VideoCodec.MPEG4
        // VisualSampleEntry: 6 reserved + 2 dri + 16 predefined/reserved, width@24, height@26, then 50 more bytes
        val width = u16(b, se.dataStart + 24)
        val height = u16(b, se.dataStart + 26)
        val sub = Box(se.type, se.start, se.dataStart + 78, se.end)
        var cfg: CodecConfig? = null
        var aspect = 0.0
        var colour = ContainerColour()
        var dv: DolbyVisionConfig? = null
        for (c in boxes(b, sub.dataStart, sub.end)) {
            when (c.type) {
                "avcC" -> if (codec == VideoCodec.H264) cfg = CodecConfigParser.parseAvcc(b.copyOfRange(c.dataStart, c.end))
                "hvcC" -> if (codec == VideoCodec.HEVC) cfg = CodecConfigParser.parseHvcc(b.copyOfRange(c.dataStart, c.end))
                "esds" -> if (se.type == "mp4v") {
                    val parsed = LegacyCodecs.parseEsds(b, c.dataStart + 4, c.end)   // + FullBox header
                    val mapped = parsed?.let { LegacyCodecs.codecForObjectType(it.first) }
                    if (parsed != null && mapped != null) {
                        codec = mapped
                        extradata = parsed.second
                        esdsOk = true
                    }
                }
                "av1C" -> if (codec == VideoCodec.AV1) extradata = b.copyOfRange(c.dataStart, c.end)
                "pasp" -> {
                    val h = u32(b, c.dataStart)
                    val v = u32(b, c.dataStart + 4)
                    if (h > 0 && v > 0 && width > 0 && height > 0) aspect = width.toDouble() * h / (height.toDouble() * v)
                }
                "colr" -> if (type(b, c.dataStart) == "nclx") {
                    colour = colour.copy(
                        primaries = u16(b, c.dataStart + 4),
                        transfer = u16(b, c.dataStart + 6),
                        matrix = u16(b, c.dataStart + 8),
                        range = if ((b[c.dataStart + 10].toInt() and 0x80) != 0) 2 else 1,
                    )
                }
                "dvcC", "dvvC", "dvwC" -> if (c.end - c.dataStart >= 5) {
                    dv = DolbyVisionConfig(
                        profile = (b[c.dataStart + 2].toInt() and 0xFF) shr 1,
                        blCompatId = (b[c.dataStart + 4].toInt() and 0xFF) shr 4,
                        elPresent = ((b[c.dataStart + 3].toInt() shr 1) and 1) == 1,
                    )
                }
            }
        }
        if (!codec.nal) {
            if (!esdsOk) return null
            val bits = if (codec == VideoCodec.AV1) LegacyCodecs.av1BitDepth(extradata) else 8
            return VideoTrack(codec, width, height, aspect, 4, ByteArray(0), bits, colour, dv,
                trackNumber = 0L, extradata = extradata)
        }
        val config = cfg ?: return null
        return VideoTrack(codec, width, height, aspect, config.nalLengthSize, config.parameterSetsAnnexB,
            config.bitDepth, colour, dv, trackNumber = 0L)
    }

    private fun firstEditMediaTime(b: ByteArray, edts: Box): Long {
        val elst = child(b, edts, "elst") ?: return 0L
        val version = b[elst.dataStart].toInt()
        val count = u32(b, elst.dataStart + 4)
        if (count < 1) return 0L
        val p = elst.dataStart + 8
        val mediaTime = if (version == 1) u64(b, p + 8) else u32(b, p + 4).toInt().toLong()
        return if (mediaTime > 0) mediaTime else 0L
    }

    private fun buildKeyframes(b: ByteArray, stbl: Box, timescale: Long, editShift: Long): KeyframeIndex {
        val stsz = child(b, stbl, "stsz") ?: throw UnsupportedMediaException("no stsz")
        val fixedSize = u32(b, stsz.dataStart + 4)
        val sampleCount = u32(b, stsz.dataStart + 8).toInt()
        fun sampleSize(i: Int): Long = if (fixedSize != 0L) fixedSize else u32(b, stsz.dataStart + 12 + 4 * i)

        // sync samples (1-based); absent stss = every sample is sync
        val stss = child(b, stbl, "stss")
        val sync: IntArray = if (stss != null) {
            val n = u32(b, stss.dataStart + 4).toInt()
            IntArray(n) { u32(b, stss.dataStart + 8 + 4 * it).toInt() - 1 }
        } else IntArray(sampleCount) { it }

        val stco = child(b, stbl, "stco")
        val co64 = child(b, stbl, "co64")
        val chunkOffsets: LongArray = when {
            stco != null -> LongArray(u32(b, stco.dataStart + 4).toInt()) { u32(b, stco.dataStart + 8 + 4 * it) }
            co64 != null -> LongArray(u32(b, co64.dataStart + 4).toInt()) { u64(b, co64.dataStart + 8 + 8 * it) }
            else -> throw UnsupportedMediaException("no stco/co64")
        }
        val stsc = child(b, stbl, "stsc") ?: throw UnsupportedMediaException("no stsc")
        val stscCount = u32(b, stsc.dataStart + 4).toInt()
        val stscFirst = IntArray(stscCount) { u32(b, stsc.dataStart + 8 + 12 * it).toInt() - 1 }
        val stscPer = IntArray(stscCount) { u32(b, stsc.dataStart + 12 + 12 * it).toInt() }

        // sample -> offset for every sample (sequential walk), then pick the sync ones
        val sampleOffset = LongArray(sampleCount)
        var s = 0
        var entry = 0
        for (chunk in chunkOffsets.indices) {
            while (entry + 1 < stscCount && stscFirst[entry + 1] <= chunk) entry++
            var off = chunkOffsets[chunk]
            repeat(stscPer[entry]) {
                if (s < sampleCount) {
                    sampleOffset[s] = off
                    off += sampleSize(s)
                    s++
                }
            }
        }

        // decode times (stts) and composition offsets (ctts)
        val dts = LongArray(sampleCount)
        val stts = child(b, stbl, "stts") ?: throw UnsupportedMediaException("no stts")
        var t = 0L
        var i = 0
        for (e in 0 until u32(b, stts.dataStart + 4).toInt()) {
            val count = u32(b, stts.dataStart + 8 + 8 * e).toInt()
            val delta = u32(b, stts.dataStart + 12 + 8 * e)
            repeat(count) {
                if (i < sampleCount) dts[i++] = t
                t += delta
            }
        }
        val ctts = child(b, stbl, "ctts")
        val cto = LongArray(sampleCount)
        if (ctts != null) {
            val v1 = b[ctts.dataStart].toInt() == 1
            i = 0
            for (e in 0 until u32(b, ctts.dataStart + 4).toInt()) {
                val count = u32(b, ctts.dataStart + 8 + 8 * e).toInt()
                val raw = u32(b, ctts.dataStart + 12 + 8 * e)
                val off = if (v1) raw.toInt().toLong() else raw
                repeat(count) { if (i < sampleCount) cto[i++] = off }
            }
        }

        val valid = sync.filter { it in 0 until sampleCount }
        return KeyframeIndex(
            ptsUs = LongArray(valid.size) { k ->
                val n = valid[k]
                ((dts[n] + cto[n] - editShift).coerceAtLeast(0L) * 1_000_000L) / timescale
            },
            offset = LongArray(valid.size) { sampleOffset[valid[it]] },
            relPos = LongArray(valid.size) { -1L },
            size = IntArray(valid.size) { sampleSize(valid[it]).toInt() },
        )
    }
}
