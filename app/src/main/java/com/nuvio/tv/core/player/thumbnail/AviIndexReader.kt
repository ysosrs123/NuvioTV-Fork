package com.nuvio.tv.core.player.thumbnail

/**
 * AVI keyframe index, from the OpenDML super index ('indx' -> 'ix##', the only index covering files over 1 GB)
 * or the legacy 'idx1' after 'movi'. AVI has no timestamps: pts = frame number x dwScale / dwRate.
 */
internal object AviIndexReader {
    private const val MAX_IDX1_BYTES = 64L * 1024 * 1024
    private const val READ_STEP = 2 * 1024 * 1024
    private const val AVIIF_KEYFRAME = 0x10
    private const val MAX_FIRST_FRAME = 1024 * 1024

    fun looksLikeAvi(head: ByteArray): Boolean =
        head.size >= 12 && LegacyCodecs.fourccAt(head, 0) == "RIFF" && LegacyCodecs.fourccAt(head, 8) == "AVI "

    private fun u16(b: ByteArray, p: Int): Int = (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, p: Int): Long =
        (b[p].toLong() and 0xFF) or ((b[p + 1].toLong() and 0xFF) shl 8) or
            ((b[p + 2].toLong() and 0xFF) shl 16) or ((b[p + 3].toLong() and 0xFF) shl 24)
    private fun s32(b: ByteArray, p: Int): Int = u32(b, p).toInt()
    private fun u64(b: ByteArray, p: Int): Long = u32(b, p) or (u32(b, p + 4) shl 32)

    private class Stream(
        val index: Int,
        val scale: Long,
        val rate: Long,
        val length: Long,
        val width: Int,
        val height: Int,
        val fourcc: String,
        val extradata: ByteArray,
        /** OpenDML super-index entries: (offset of an 'ix##' chunk, its size). */
        val superIndex: List<Pair<Long, Int>>,
    )

    fun read(reader: RangeReader, head: ByteArray): MediaIndex {
        if (!looksLikeAvi(head)) throw UnsupportedMediaException("not AVI")
        // Top-level chunks of the first RIFF: LIST hdrl, LIST movi, idx1.
        var pos = 12L
        var hdrl: ByteArray? = null
        var moviPos = -1L
        var moviEnd = -1L
        while (moviPos < 0) {
            val hdr = chunkHeader(reader, head, pos) ?: break
            val (fcc, size) = hdr
            if (fcc == "LIST") {
                val listType = LegacyCodecs.fourccAt(bytesAt(reader, head, pos + 8, 4), 0)
                if (listType == "hdrl") hdrl = bytesAt(reader, head, pos + 12, (size - 4).toInt())
                if (listType == "movi") {
                    moviPos = pos
                    moviEnd = pos + 8 + size
                }
            }
            pos += 8 + size + (size and 1)
        }
        val list = hdrl ?: throw UnsupportedMediaException("AVI without hdrl")
        if (moviPos < 0) throw UnsupportedMediaException("AVI without movi")
        val stream = firstVideoStream(list) ?: throw UnsupportedMediaException("AVI without a video stream")
        val codec = LegacyCodecs.codecForFourcc(stream.fourcc)
            ?: throw UnsupportedMediaException("AVI video ${stream.fourcc}")
        if (stream.rate <= 0 || stream.scale <= 0) throw UnsupportedMediaException("AVI without a frame rate")

        val entries = if (stream.superIndex.isNotEmpty()) {
            openDmlKeyframes(reader, stream)
        } else {
            idx1Keyframes(reader, stream, moviPos, moviEnd + (moviEnd and 1))
        }
        if (entries.size < 2) throw UnsupportedMediaException("too few AVI keyframes (${entries.size})")
        val pts = LongArray(entries.size) { entries[it].frame * stream.scale * 1_000_000L / stream.rate }
        val keyframes = KeyframeIndex(
            ptsUs = pts,
            offset = LongArray(entries.size) { entries[it].dataPos },
            relPos = LongArray(entries.size) { -1L },
            size = IntArray(entries.size) { entries[it].size },
        )
        var extradata = stream.extradata
        if (codec == VideoCodec.MPEG4 && !LegacyCodecs.hasMpeg4Vol(extradata)) {
            // XviD in AVI: the VOL header is only in-band before the first frame, lift it into extradata.
            val first = entries.first()
            val frame = reader.read(first.dataPos, minOf(first.size, MAX_FIRST_FRAME))
            LegacyCodecs.mpeg4HeadersBeforeVop(frame)?.let { extradata = it }
        }
        val video = VideoTrack(
            codec = codec, width = stream.width, height = stream.height, displayAspect = 0.0,
            nalLengthSize = 4, parameterSetsAnnexB = ByteArray(0), bitDepth = 8, colour = ContainerColour(),
            dolbyVision = null, trackNumber = stream.index.toLong(), extradata = extradata,
        )
        return MediaIndex(
            kind = ContainerKind.AVI,
            fileLength = reader.totalLength,
            durationUs = stream.length * stream.scale * 1_000_000L / stream.rate,
            video = video,
            keyframes = keyframes,
        )
    }

    private class Entry(val frame: Long, val dataPos: Long, val size: Int)

    private fun chunkHeader(reader: RangeReader, head: ByteArray, pos: Long): Pair<String, Long>? {
        val total = reader.totalLength
        if (total in 1..(pos + 8)) return null
        val b = bytesAt(reader, head, pos, 8)
        if (b.size < 8) return null
        return LegacyCodecs.fourccAt(b, 0) to u32(b, 4)
    }

    private fun bytesAt(reader: RangeReader, head: ByteArray, pos: Long, len: Int): ByteArray =
        if (pos + len <= head.size) head.copyOfRange(pos.toInt(), (pos + len).toInt()) else reader.read(pos, len)

    private fun firstVideoStream(hdrl: ByteArray): Stream? {
        var p = 0
        var streamIndex = 0
        while (p + 8 <= hdrl.size) {
            val fcc = LegacyCodecs.fourccAt(hdrl, p)
            val size = u32(hdrl, p + 4).toInt()
            val end = minOf(hdrl.size, p + 8 + size)
            if (fcc == "LIST" && LegacyCodecs.fourccAt(hdrl, p + 8) == "strl") {
                val s = parseStrl(hdrl, p + 12, end, streamIndex)
                if (s != null) return s
                streamIndex++
            }
            p = p + 8 + size + (size and 1)
        }
        return null
    }

    private fun parseStrl(b: ByteArray, start: Int, end: Int, index: Int): Stream? {
        var p = start
        var isVideo = false
        var scale = 0L
        var rate = 0L
        var length = 0L
        var width = 0
        var height = 0
        var fourcc = ""
        var handler = ""
        var extradata = ByteArray(0)
        val superIndex = ArrayList<Pair<Long, Int>>()
        while (p + 8 <= end) {
            val fcc = LegacyCodecs.fourccAt(b, p)
            val size = u32(b, p + 4).toInt()
            val d = p + 8
            val dEnd = minOf(end, d + size)
            when (fcc) {
                "strh" -> if (dEnd - d >= 36) {
                    isVideo = LegacyCodecs.fourccAt(b, d) == "vids"
                    handler = LegacyCodecs.fourccAt(b, d + 4)
                    scale = u32(b, d + 20)
                    rate = u32(b, d + 24)
                    length = u32(b, d + 32)
                }
                "strf" -> if (isVideo && dEnd - d >= 40) {
                    width = s32(b, d + 4)
                    height = kotlin.math.abs(s32(b, d + 8))
                    fourcc = LegacyCodecs.fourccAt(b, d + 16)
                    if (dEnd - d > 40) extradata = b.copyOfRange(d + 40, dEnd)
                }
                "indx" -> if (isVideo && dEnd - d >= 24) {
                    val longsPerEntry = u16(b, d)
                    val indexType = b[d + 3].toInt() and 0xFF
                    val entries = u32(b, d + 4).toInt()
                    if (indexType == 0 && longsPerEntry == 4) {          // AVI_INDEX_OF_INDEXES
                        var e = d + 24
                        repeat(entries) {
                            if (e + 16 > dEnd) return@repeat
                            superIndex += u64(b, e) to u32(b, e + 8).toInt()
                            e += 16
                        }
                    }
                }
            }
            p = d + size + (size and 1)
        }
        if (!isVideo) return null
        if (LegacyCodecs.codecForFourcc(fourcc) == null && LegacyCodecs.codecForFourcc(handler) != null) fourcc = handler
        return Stream(index, scale, rate, length, width, height, fourcc, extradata, superIndex)
    }

    /** OpenDML: each 'ix##' standard index lists (offset from its base, size | 0x80000000 when not a keyframe). */
    private fun openDmlKeyframes(reader: RangeReader, s: Stream): List<Entry> {
        val out = ArrayList<Entry>()
        var frame = 0L
        for ((offset, size) in s.superIndex) {
            if (size <= 32) continue
            val ix = reader.read(offset, size + 8)
            if (ix.size < 32 || !LegacyCodecs.fourccAt(ix, 0).startsWith("ix")) continue
            val longsPerEntry = u16(ix, 8)
            val indexType = ix[11].toInt() and 0xFF
            val count = u32(ix, 12).toInt()
            val base = u64(ix, 20)
            if (indexType != 1 || longsPerEntry != 2) continue            // AVI_INDEX_OF_CHUNKS, 2 dwords
            var e = 32
            repeat(count) {
                if (e + 8 > ix.size) return@repeat
                val off = u32(ix, e)
                val raw = u32(ix, e + 4)
                val sz = (raw and 0x7FFFFFFFL).toInt()
                if (raw and 0x80000000L == 0L && sz > 0) out += Entry(frame, base + off, sz)
                frame++
                e += 8
            }
        }
        return out
    }

    /** Legacy idx1: 16-byte entries (ckid, flags, offset, size); offsets are relative to 'movi' or absolute. */
    private fun idx1Keyframes(reader: RangeReader, s: Stream, moviPos: Long, idx1Pos: Long): List<Entry> {
        val hdr = reader.read(idx1Pos, 8)
        if (hdr.size < 8 || LegacyCodecs.fourccAt(hdr, 0) != "idx1") throw UnsupportedMediaException("AVI without an index")
        val len = u32(hdr, 4)
        if (len <= 0 || len > MAX_IDX1_BYTES) throw UnsupportedMediaException("AVI idx1 size $len")
        val id = "%02d".format(s.index)
        val raw = ArrayList<Triple<Long, Long, Int>>()   // frame, offset field, size (keyframes only)
        var frame = 0L
        var read = 0L
        while (read < len) {
            val step = minOf(READ_STEP.toLong(), len - read).toInt() / 16 * 16
            if (step <= 0) break
            val buf = reader.read(idx1Pos + 8 + read, step)
            var e = 0
            while (e + 16 <= buf.size) {
                val ckid = LegacyCodecs.fourccAt(buf, e)
                if (ckid.startsWith(id) && (ckid.endsWith("dc") || ckid.endsWith("db"))) {
                    val flags = u32(buf, e + 4).toInt()
                    val size = u32(buf, e + 12).toInt()
                    if (flags and AVIIF_KEYFRAME != 0 && size > 0) raw += Triple(frame, u32(buf, e + 8), size)
                    frame++
                }
                e += 16
            }
            if (buf.size < step) break
            read += step
        }
        if (raw.isEmpty()) return emptyList()
        // Offsets are normally relative to the 'movi' fourcc (list start + 8); some muxers write absolute ones.
        val firstField = raw.first().second
        val relative = moviPos + 8 + firstField
        val probe = reader.read(relative, 4)
        val base = if (LegacyCodecs.fourccAt(probe, 0).startsWith(id)) moviPos + 8 else 0L
        return raw.map { (f, off, size) -> Entry(f, base + off + 8, size) }
    }
}
