package com.nuvio.tv.core.player.thumbnail

/** Minimal EBML element access over an in-memory buffer. */
internal object Ebml {
    const val UNKNOWN_SIZE = -1L

    /** Returns (id, length) or null when the buffer ends inside the id. */
    fun readId(b: ByteArray, p: Int): LongArray? {
        if (p >= b.size) return null
        val first = b[p].toInt() and 0xFF
        if (first == 0) throw UnsupportedMediaException("invalid EBML id")
        var len = 1
        var mask = 0x80
        while (first and mask == 0) {
            mask = mask shr 1
            len++
            if (len > 4) throw UnsupportedMediaException("invalid EBML id length")
        }
        if (p + len > b.size) return null
        var v = 0L
        for (i in 0 until len) v = (v shl 8) or (b[p + i].toLong() and 0xFF)
        return longArrayOf(v, len.toLong())
    }

    /** Returns (value, length) with value = [UNKNOWN_SIZE] for the all-ones pattern, or null at buffer end. */
    fun readSize(b: ByteArray, p: Int): LongArray? {
        if (p >= b.size) return null
        val first = b[p].toInt() and 0xFF
        if (first == 0) throw UnsupportedMediaException("invalid EBML size")
        var len = 1
        var mask = 0x80
        while (first and mask == 0) {
            mask = mask shr 1
            len++
        }
        if (p + len > b.size) return null
        var v = (first and (mask - 1)).toLong()
        for (i in 1 until len) v = (v shl 8) or (b[p + i].toLong() and 0xFF)
        if (v == (1L shl (7 * len)) - 1) v = UNKNOWN_SIZE
        return longArrayOf(v, len.toLong())
    }

    class Element(val id: Long, val headerStart: Int, val dataStart: Int, val dataEnd: Int)

    /** Complete children of b[start, end). Stops quietly at the first incomplete element. */
    fun children(b: ByteArray, start: Int, end: Int): List<Element> {
        val out = ArrayList<Element>()
        var p = start
        while (p < end) {
            val id = readId(b, p) ?: break
            val size = readSize(b, p + id[1].toInt()) ?: break
            val data = p + id[1].toInt() + size[1].toInt()
            val dataEnd: Long = if (size[0] == UNKNOWN_SIZE) end.toLong() else data + size[0]
            if (dataEnd > end || dataEnd < data) break
            out.add(Element(id[0], p, data, dataEnd.toInt()))
            p = dataEnd.toInt()
        }
        return out
    }

    fun uint(b: ByteArray, a: Int, e: Int): Long {
        var v = 0L
        for (i in a until e) v = (v shl 8) or (b[i].toLong() and 0xFF)
        return v
    }

    fun float(b: ByteArray, a: Int, e: Int): Double = when (e - a) {
        4 -> java.lang.Float.intBitsToFloat(uint(b, a, e).toInt()).toDouble()
        8 -> java.lang.Double.longBitsToDouble(uint(b, a, e))
        else -> 0.0
    }

    fun ascii(b: ByteArray, a: Int, e: Int): String = String(b, a, e - a, Charsets.ISO_8859_1).trimEnd('\u0000')
}

/** Matroska/WebM keyframe index from the first SeekHead and the Cues, through bounded range reads. */
internal object MkvIndexReader {
    const val ID_EBML = 0x1A45DFA3L
    private const val ID_DOCTYPE = 0x4282L
    const val ID_SEGMENT = 0x18538067L
    private const val ID_SEEKHEAD = 0x114D9B74L
    private const val ID_SEEK = 0x4DBBL
    private const val ID_SEEKID = 0x53ABL
    private const val ID_SEEKPOS = 0x53ACL
    private const val ID_INFO = 0x1549A966L
    private const val ID_TCSCALE = 0x2AD7B1L
    private const val ID_DURATION = 0x4489L
    private const val ID_TRACKS = 0x1654AE6BL
    private const val ID_TRACKENTRY = 0xAEL
    private const val ID_TRACKNUM = 0xD7L
    private const val ID_TRACKTYPE = 0x83L
    private const val ID_CODECID = 0x86L
    private const val ID_CODECPRIV = 0x63A2L
    private const val ID_CONTENT_ENCODINGS = 0x6D80L
    private const val ID_VIDEO = 0xE0L
    private const val ID_PIXW = 0xB0L
    private const val ID_PIXH = 0xBAL
    private const val ID_DISPW = 0x54B0L
    private const val ID_DISPH = 0x54BAL
    private const val ID_DISPUNIT = 0x54B2L
    private const val ID_COLOUR = 0x55B0L
    private const val ID_MATRIX = 0x55B1L
    private const val ID_RANGE = 0x55B9L
    private const val ID_TRANSFER = 0x55BAL
    private const val ID_PRIMARIES = 0x55BBL
    private const val ID_MAXCLL = 0x55BCL
    private const val ID_MASTERING = 0x55D0L
    private const val ID_LUMINANCE_MAX = 0x55D9L
    private const val ID_BLOCKADDMAP = 0x41E4L
    private const val ID_BLOCKADDTYPE = 0x41E7L
    private const val ID_BLOCKADDEXTRA = 0x41EDL
    const val ID_CUES = 0x1C53BB6BL
    private const val ID_CUEPOINT = 0xBBL
    private const val ID_CUETIME = 0xB3L
    private const val ID_CUETRACKPOS = 0xB7L
    private const val ID_CUETRACK = 0xF7L
    private const val ID_CUECLUSTER = 0xF1L
    private const val ID_CUEREL = 0xF0L
    const val ID_CLUSTER = 0x1F43B675L

    const val HEAD_BYTES = 256 * 1024
    private const val MAX_META_ELEMENT = 4 * 1024 * 1024
    private const val MAX_CUES = 16 * 1024 * 1024

    fun looksLikeMatroska(head: ByteArray): Boolean =
        head.size >= 4 && Ebml.uint(head, 0, 4) == ID_EBML

    fun read(reader: RangeReader, head: ByteArray): MediaIndex {
        val ebml = Ebml.readId(head, 0) ?: throw UnsupportedMediaException("short header")
        if (ebml[0] != ID_EBML) throw UnsupportedMediaException("not Matroska")
        val ebmlSize = Ebml.readSize(head, ebml[1].toInt()) ?: throw UnsupportedMediaException("short header")
        val ebmlData = (ebml[1] + ebmlSize[1]).toInt()
        val ebmlEnd = (ebmlData + ebmlSize[0]).toInt()
        val doctype = Ebml.children(head, ebmlData, ebmlEnd).firstOrNull { it.id == ID_DOCTYPE }
            ?.let { Ebml.ascii(head, it.dataStart, it.dataEnd) }
        if (doctype != null && doctype != "matroska" && doctype != "webm") {
            throw UnsupportedMediaException("doctype $doctype")
        }
        val seg = Ebml.readId(head, ebmlEnd) ?: throw UnsupportedMediaException("short header")
        if (seg[0] != ID_SEGMENT) throw UnsupportedMediaException("no Segment")
        val segSize = Ebml.readSize(head, ebmlEnd + seg[1].toInt()) ?: throw UnsupportedMediaException("short header")
        val segData = ebmlEnd + seg[1].toInt() + segSize[1].toInt()

        val seek = HashMap<Long, Long>()
        var info: ByteArray? = null
        var tracks: ByteArray? = null
        var cues: ByteArray? = null
        for (el in Ebml.children(head, segData, head.size)) {
            when (el.id) {
                ID_SEEKHEAD -> for (s in Ebml.children(head, el.dataStart, el.dataEnd)) {
                    if (s.id != ID_SEEK) continue
                    var target = -1L
                    var pos = -1L
                    for (f in Ebml.children(head, s.dataStart, s.dataEnd)) {
                        if (f.id == ID_SEEKID) target = Ebml.uint(head, f.dataStart, f.dataEnd)
                        else if (f.id == ID_SEEKPOS) pos = Ebml.uint(head, f.dataStart, f.dataEnd)
                    }
                    if (target >= 0 && pos >= 0 && target !in seek) seek[target] = segData + pos
                }
                ID_INFO -> info = head.copyOfRange(el.dataStart, el.dataEnd)
                ID_TRACKS -> tracks = head.copyOfRange(el.dataStart, el.dataEnd)
                ID_CUES -> cues = head.copyOfRange(el.dataStart, el.dataEnd)
            }
            if (el.id == ID_CLUSTER) break
        }
        if (info == null) info = seek[ID_INFO]?.let { fetchElement(reader, it, ID_INFO, MAX_META_ELEMENT) }
        if (tracks == null) tracks = seek[ID_TRACKS]?.let { fetchElement(reader, it, ID_TRACKS, MAX_META_ELEMENT) }
        if (info == null || tracks == null) throw UnsupportedMediaException("Info/Tracks not found")
        if (cues == null) {
            cues = seek[ID_CUES]?.let { fetchElement(reader, it, ID_CUES, MAX_CUES) }
                ?: throw UnsupportedMediaException("no Cues referenced by the SeekHead")
        }

        var tcScale = 1_000_000L
        var duration = 0.0
        for (el in Ebml.children(info, 0, info.size)) {
            if (el.id == ID_TCSCALE) tcScale = Ebml.uint(info, el.dataStart, el.dataEnd)
            else if (el.id == ID_DURATION) duration = Ebml.float(info, el.dataStart, el.dataEnd)
        }
        val video = parseVideoTrack(tracks)
        val kf = parseCues(cues, segData.toLong(), tcScale, video.trackNumber)
        if (kf.count < 2) throw UnsupportedMediaException("too few video cues (${kf.count})")
        return MediaIndex(
            kind = ContainerKind.MATROSKA,
            fileLength = reader.totalLength,
            durationUs = (duration * tcScale / 1000.0).toLong(),
            video = video,
            keyframes = kf,
            segmentDataStart = segData.toLong(),
            timecodeScaleNs = tcScale,
        )
    }

    private fun fetchElement(reader: RangeReader, pos: Long, expectedId: Long, cap: Int): ByteArray {
        val hdr = reader.read(pos, 16)
        val id = Ebml.readId(hdr, 0) ?: throw UnsupportedMediaException("short element header")
        if (id[0] != expectedId) throw UnsupportedMediaException("SeekHead points at the wrong element")
        val size = Ebml.readSize(hdr, id[1].toInt()) ?: throw UnsupportedMediaException("short element header")
        if (size[0] == Ebml.UNKNOWN_SIZE || size[0] > cap || size[0] <= 0) {
            throw UnsupportedMediaException("element size ${size[0]}")
        }
        val body = reader.read(pos + id[1] + size[1], size[0].toInt())
        if (body.size.toLong() != size[0]) throw UnsupportedMediaException("truncated element")
        return body
    }

    /** V_MS/VFW/FOURCC carries a BITMAPINFOHEADER: FourCC at byte 16, extradata after the 40-byte header. */
    private fun legacyCodec(codecId: String, cp: ByteArray?): Pair<VideoCodec, ByteArray>? {
        val priv = cp ?: ByteArray(0)
        return when {
            codecId.startsWith("V_MPEG4/ISO/ASP") || codecId.startsWith("V_MPEG4/ISO/SP") ||
                codecId.startsWith("V_MPEG4/ISO/AP") -> VideoCodec.MPEG4 to priv
            codecId == "V_MPEG2" -> VideoCodec.MPEG2 to priv
            codecId == "V_MPEG1" -> VideoCodec.MPEG1 to priv
            codecId == "V_AV1" -> VideoCodec.AV1 to priv
            codecId == "V_MS/VFW/FOURCC" && priv.size >= 40 -> {
                val c = LegacyCodecs.codecForFourcc(LegacyCodecs.fourccAt(priv, 16))
                if (c == null || c.nal) null else c to priv.copyOfRange(40, priv.size)
            }
            else -> null
        }
    }

    private fun parseVideoTrack(t: ByteArray): VideoTrack {
        for (entry in Ebml.children(t, 0, t.size)) {
            if (entry.id != ID_TRACKENTRY) continue
            var num = 0L
            var type = 0L
            var codecId = ""
            var cp: ByteArray? = null
            var w = 0
            var h = 0
            var dw = 0L
            var dh = 0L
            var dunit = 0L
            var colour = ContainerColour()
            var dv: DolbyVisionConfig? = null
            var encoded = false
            for (f in Ebml.children(t, entry.dataStart, entry.dataEnd)) {
                when (f.id) {
                    ID_TRACKNUM -> num = Ebml.uint(t, f.dataStart, f.dataEnd)
                    ID_TRACKTYPE -> type = Ebml.uint(t, f.dataStart, f.dataEnd)
                    ID_CODECID -> codecId = Ebml.ascii(t, f.dataStart, f.dataEnd)
                    ID_CODECPRIV -> cp = t.copyOfRange(f.dataStart, f.dataEnd)
                    ID_CONTENT_ENCODINGS -> encoded = true
                    ID_BLOCKADDMAP -> {
                        var bType = 0L
                        var extra: ByteArray? = null
                        for (m in Ebml.children(t, f.dataStart, f.dataEnd)) {
                            if (m.id == ID_BLOCKADDTYPE) bType = Ebml.uint(t, m.dataStart, m.dataEnd)
                            else if (m.id == ID_BLOCKADDEXTRA) extra = t.copyOfRange(m.dataStart, m.dataEnd)
                        }
                        // 'dvcC' / 'dvvC' / 'dvwC'
                        if ((bType == 0x64766343L || bType == 0x64767643L || bType == 0x64767743L) &&
                            extra != null && extra.size >= 5
                        ) {
                            dv = DolbyVisionConfig(
                                profile = (extra[2].toInt() and 0xFF) shr 1,
                                blCompatId = (extra[4].toInt() and 0xFF) shr 4,
                                elPresent = ((extra[3].toInt() shr 1) and 1) == 1,
                            )
                        }
                    }
                    ID_VIDEO -> for (v in Ebml.children(t, f.dataStart, f.dataEnd)) {
                        when (v.id) {
                            ID_PIXW -> w = Ebml.uint(t, v.dataStart, v.dataEnd).toInt()
                            ID_PIXH -> h = Ebml.uint(t, v.dataStart, v.dataEnd).toInt()
                            ID_DISPW -> dw = Ebml.uint(t, v.dataStart, v.dataEnd)
                            ID_DISPH -> dh = Ebml.uint(t, v.dataStart, v.dataEnd)
                            ID_DISPUNIT -> dunit = Ebml.uint(t, v.dataStart, v.dataEnd)
                            ID_COLOUR -> colour = parseColour(t, v.dataStart, v.dataEnd)
                        }
                    }
                }
            }
            if (type != 1L) continue
            if (encoded) throw UnsupportedMediaException("encoded (compressed) video track")
            // DisplayUnit 0 = pixels, 3 = display aspect ratio: both give an aspect, others are ignored.
            val aspect = if (dw > 0 && dh > 0 && (dunit == 0L || dunit == 3L)) dw.toDouble() / dh else 0.0
            val codec = when {
                codecId.startsWith("V_MPEGH/ISO/HEVC") -> VideoCodec.HEVC
                codecId.startsWith("V_MPEG4/ISO/AVC") -> VideoCodec.H264
                else -> {
                    // Raw-sample codecs, decoded with CodecPrivate as extradata.
                    val (legacy, extra) = legacyCodec(codecId, cp)
                        ?: throw UnsupportedMediaException("video codec $codecId")
                    val bits = if (legacy == VideoCodec.AV1) LegacyCodecs.av1BitDepth(extra) else 8
                    return VideoTrack(
                        codec = legacy, width = w, height = h, displayAspect = aspect, nalLengthSize = 4,
                        parameterSetsAnnexB = ByteArray(0), bitDepth = bits, colour = colour, dolbyVision = dv,
                        trackNumber = num, extradata = extra,
                    )
                }
            }
            val priv = cp ?: throw UnsupportedMediaException("no CodecPrivate")
            val cfg = if (codec == VideoCodec.HEVC) CodecConfigParser.parseHvcc(priv) else CodecConfigParser.parseAvcc(priv)
            return VideoTrack(
                codec = codec, width = w, height = h, displayAspect = aspect,
                nalLengthSize = cfg.nalLengthSize, parameterSetsAnnexB = cfg.parameterSetsAnnexB,
                bitDepth = cfg.bitDepth, colour = colour, dolbyVision = dv, trackNumber = num,
            )
        }
        throw UnsupportedMediaException("no video track")
    }

    private fun parseColour(t: ByteArray, a: Int, e: Int): ContainerColour {
        var c = ContainerColour()
        for (el in Ebml.children(t, a, e)) {
            c = when (el.id) {
                ID_MATRIX -> c.copy(matrix = Ebml.uint(t, el.dataStart, el.dataEnd).toInt())
                ID_RANGE -> c.copy(range = Ebml.uint(t, el.dataStart, el.dataEnd).toInt())
                ID_TRANSFER -> c.copy(transfer = Ebml.uint(t, el.dataStart, el.dataEnd).toInt())
                ID_PRIMARIES -> c.copy(primaries = Ebml.uint(t, el.dataStart, el.dataEnd).toInt())
                ID_MAXCLL -> c.copy(maxCll = Ebml.uint(t, el.dataStart, el.dataEnd).toInt())
                ID_MASTERING -> {
                    val lum = Ebml.children(t, el.dataStart, el.dataEnd).firstOrNull { it.id == ID_LUMINANCE_MAX }
                    if (lum != null) c.copy(masteringMaxNits = Ebml.float(t, lum.dataStart, lum.dataEnd).toInt()) else c
                }
                else -> c
            }
        }
        return c
    }

    /** First CueTrackPositions for the video track per CuePoint (cluster offset + optional relative position). */
    internal fun parseCues(c: ByteArray, segData: Long, tcScale: Long, videoTrack: Long): KeyframeIndex {
        val pts = ArrayList<Long>()
        val off = ArrayList<Long>()
        val rel = ArrayList<Long>()
        for (cp in Ebml.children(c, 0, c.size)) {
            if (cp.id != ID_CUEPOINT) continue
            var time = -1L
            var cluster = -1L
            var relPos = -1L
            for (f in Ebml.children(c, cp.dataStart, cp.dataEnd)) {
                if (f.id == ID_CUETIME) time = Ebml.uint(c, f.dataStart, f.dataEnd)
                else if (f.id == ID_CUETRACKPOS && cluster < 0) {
                    var track = -1L
                    var cl = -1L
                    var rp = -1L
                    for (g in Ebml.children(c, f.dataStart, f.dataEnd)) {
                        when (g.id) {
                            ID_CUETRACK -> track = Ebml.uint(c, g.dataStart, g.dataEnd)
                            ID_CUECLUSTER -> cl = Ebml.uint(c, g.dataStart, g.dataEnd)
                            ID_CUEREL -> rp = Ebml.uint(c, g.dataStart, g.dataEnd)
                        }
                    }
                    if (track == videoTrack && cl >= 0) {
                        cluster = cl
                        relPos = rp
                    }
                }
            }
            if (time >= 0 && cluster >= 0) {
                pts.add(time * tcScale / 1000L)
                off.add(segData + cluster)
                rel.add(relPos)
            }
        }
        // Cues are normally ordered, sort anyway.
        val order = pts.indices.sortedBy { pts[it] }
        return KeyframeIndex(
            ptsUs = LongArray(order.size) { pts[order[it]] },
            offset = LongArray(order.size) { off[order[it]] },
            relPos = LongArray(order.size) { rel[order[it]] },
            size = IntArray(order.size) { -1 },
        )
    }
}
