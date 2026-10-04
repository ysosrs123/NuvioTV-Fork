package com.nuvio.tv.core.player.thumbnail

import java.io.ByteArrayOutputStream

/**
 * Fetches one indexed keyframe with bounded reads and returns it ready for the decoder (H.264/HEVC as Annex-B
 * with the track's parameter sets prepended). Not thread-safe: one instance per fetch under way.
 */
internal class KeyframeExtractor(private val index: MediaIndex, private val reader: RangeReader) {
    private companion object {
        const val ID_SIMPLEBLOCK = 0xA3L
        const val ID_BLOCKGROUP = 0xA0L
        const val ID_BLOCK = 0xA1L
        const val ID_REFBLOCK = 0xFBL
        const val ID_TIMECODE = 0xE7L
        const val MIN_WINDOW = 256 * 1024
        const val MAX_WINDOW = 12 * 1024 * 1024
        const val MAX_KEYFRAME = 16 * 1024 * 1024
        const val MAX_CONTINUATIONS = 4
        const val MAX_CLUSTER_SCAN = 32 * 1024 * 1024
    }

    // Recent span (cluster or block start to keyframe end), sizes the next window. A new maximum is taken at once
    // and decays 1/8 per keyframe, so one large keyframe does not inflate every later read.
    private var largestSeen = 0
    /** Matroska cluster header length (id + size field), learned from the first cluster read. */
    private var clusterHeaderLen = -1
    var bytesRead = 0L
        private set
    var requests = 0
        private set

    private fun read(offset: Long, length: Int): ByteArray {
        requests++
        val b = reader.read(offset, length)
        bytesRead += b.size
        return b
    }

    private fun window(): Int = (largestSeen * 5 / 4 + 64 * 1024).coerceIn(MIN_WINDOW, MAX_WINDOW)

    private fun noteSpan(span: Int) {
        largestSeen = maxOf(span, largestSeen - largestSeen / 8)
    }

    /** Keyframe [i] ready for the decoder, or null when it cannot be located. */
    fun extract(i: Int): ByteArray? {
        val kf = index.keyframes
        val sample: ByteArray = when (index.kind) {
            ContainerKind.MP4, ContainerKind.AVI -> {
                val size = kf.size[i]
                if (size <= 0 || size > MAX_KEYFRAME) return null
                val b = read(kf.offset[i], size)
                if (b.size != size) return null
                b
            }
            ContainerKind.MATROSKA -> mkvKeyframe(kf.offset[i], kf.relPos[i], kf.ptsUs[i]) ?: return null
        }
        return when {
            !index.video.codec.nal -> sample                                  // raw sample + extradata
            index.kind == ContainerKind.AVI -> tapSampleToAnnexB(sample, index.video)   // Annex-B in AVI chunks
            else -> toAnnexB(sample)
        }
    }

    private class Found(val frame: ByteArray?, val endInBuffer: Int, val incompleteUntil: Int)

    private fun mkvKeyframe(clusterAbs: Long, relPos: Long, ptsUs: Long): ByteArray? {
        if (relPos >= 0 && clusterHeaderLen > 0) {
            val start = clusterAbs + clusterHeaderLen + relPos
            val buf = read(start, window())
            val block = parseBlockAt(buf, 0)
            if (block != null) {
                if (block.frame != null) {
                    noteSpan(block.endInBuffer)
                    return block.frame
                }
                if (block.incompleteUntil in 1..MAX_KEYFRAME) {
                    val more = read(start + buf.size, block.incompleteUntil - buf.size)
                    val whole = buf + more
                    parseBlockAt(whole, 0)?.frame?.let {
                        noteSpan(whole.size)
                        return it
                    }
                }
            }
            // The jump did not land on a video keyframe block: fall through to the cluster scan.
        }
        val learnedBefore = clusterHeaderLen > 0
        var buf = read(clusterAbs, window())
        var found = scanCluster(buf, relPos, ptsUs)
        if (!learnedBefore && relPos >= 0 && clusterHeaderLen > 0 && clusterHeaderLen + relPos >= buf.size) {
            // The block lies beyond this window (large clusters): the header length is known now, jump straight to it.
            return mkvKeyframe(clusterAbs, relPos, ptsUs)
        }
        // Old muxers (mkvmerge 2.x) write no CueRelativePosition and put the keyframe deep in large clusters.
        var continuations = 0
        while (found != null && found.frame == null && found.incompleteUntil > buf.size &&
            continuations < MAX_CONTINUATIONS && buf.size < MAX_CLUSTER_SCAN
        ) {
            val need = maxOf(found.incompleteUntil - buf.size, window())
            val more = read(clusterAbs + buf.size, need)
            if (more.isEmpty()) break
            buf += more
            found = scanCluster(buf, relPos, ptsUs)
            continuations++
        }
        val frame = found?.frame ?: return null
        noteSpan(found.endInBuffer)
        return frame
    }

    /** Parses a SimpleBlock/BlockGroup element at [p]; frame != null only for a complete video keyframe. */
    private fun parseBlockAt(buf: ByteArray, p: Int): Found? {
        val id = Ebml.readId(buf, p) ?: return null
        val size = Ebml.readSize(buf, p + id[1].toInt()) ?: return null
        if (size[0] == Ebml.UNKNOWN_SIZE) return null
        val data = p + id[1].toInt() + size[1].toInt()
        val end = data + size[0]
        if (id[0] != ID_SIMPLEBLOCK && id[0] != ID_BLOCKGROUP) return null
        if (end > buf.size) return Found(null, 0, end.toInt())
        return if (id[0] == ID_SIMPLEBLOCK) {
            blockFrame(buf, data, end.toInt(), keyFromFlags = true, keyByGroup = false)?.let { Found(it, end.toInt(), 0) }
                ?: Found(null, end.toInt(), 0)
        } else {
            var bStart = -1
            var bEnd = -1
            var hasRef = false
            for (c in Ebml.children(buf, data, end.toInt())) {
                if (c.id == ID_BLOCK) { bStart = c.dataStart; bEnd = c.dataEnd }
                else if (c.id == ID_REFBLOCK) hasRef = true
            }
            if (bStart < 0) return Found(null, end.toInt(), 0)
            blockFrame(buf, bStart, bEnd, keyFromFlags = false, keyByGroup = !hasRef)?.let { Found(it, end.toInt(), 0) }
                ?: Found(null, end.toInt(), 0)
        }
    }

    /** Block payload for the video track when it is an unlaced keyframe; else null. */
    private fun blockFrame(buf: ByteArray, a: Int, e: Int, keyFromFlags: Boolean, keyByGroup: Boolean): ByteArray? {
        val tn = Ebml.readSize(buf, a) ?: return null     // track number is a size-style vint
        if (tn[0] != index.video.trackNumber) return null
        val flagsAt = a + tn[1].toInt() + 2
        if (flagsAt >= e) return null
        val flags = buf[flagsAt].toInt() and 0xFF
        if (flags and 0x06 != 0) return null              // laced: never for video keyframes in practice
        val key = if (keyFromFlags) flags and 0x80 != 0 else keyByGroup
        if (!key) return null
        return buf.copyOfRange(flagsAt + 1, e)
    }

    /** Walks a cluster from its start. Without [relPos] the first video keyframe within 1 ms of [ptsUs] is taken. */
    private fun scanCluster(buf: ByteArray, relPos: Long, ptsUs: Long): Found? {
        val id = Ebml.readId(buf, 0) ?: return null
        if (id[0] != MkvIndexReader.ID_CLUSTER) return null
        val size = Ebml.readSize(buf, id[1].toInt()) ?: return null
        val hdr = id[1].toInt() + size[1].toInt()
        clusterHeaderLen = hdr
        if (relPos >= 0) {
            val at = hdr + relPos
            if (at >= buf.size) return Found(null, 0, (at + 64 * 1024).toInt())
            return parseBlockAt(buf, at.toInt())
        }
        val end = if (size[0] == Ebml.UNKNOWN_SIZE) buf.size else minOf(buf.size.toLong(), hdr + size[0]).toInt()
        var clusterTc = 0L
        var p = hdr
        while (p < end) {
            val cid = Ebml.readId(buf, p) ?: return Found(null, 0, buf.size + window())
            if (cid[0] == MkvIndexReader.ID_CLUSTER) return null       // next cluster: not in this one
            val csize = Ebml.readSize(buf, p + cid[1].toInt()) ?: return Found(null, 0, buf.size + window())
            val data = p + cid[1].toInt() + csize[1].toInt()
            val dend = data + csize[0]
            if (dend > buf.size) return Found(null, 0, dend.toInt())
            if (cid[0] == ID_TIMECODE) {
                clusterTc = Ebml.uint(buf, data, dend.toInt())
            } else if (cid[0] == ID_SIMPLEBLOCK || cid[0] == ID_BLOCKGROUP) {
                val f = parseBlockAt(buf, p)
                if (f?.frame != null && blockTimeMatches(buf, p, cid, csize, clusterTc, ptsUs)) return f
            }
            p = dend.toInt()
        }
        return if (size[0] != Ebml.UNKNOWN_SIZE && hdr + size[0] <= buf.size) null else Found(null, 0, buf.size + window())
    }

    private fun blockTimeMatches(buf: ByteArray, p: Int, cid: LongArray, csize: LongArray, clusterTc: Long, ptsUs: Long): Boolean {
        var a = p + cid[1].toInt() + csize[1].toInt()
        if (cid[0] == ID_BLOCKGROUP) {
            val block = Ebml.children(buf, a, (a + csize[0]).toInt()).firstOrNull { it.id == ID_BLOCK } ?: return false
            a = block.dataStart
        }
        val tn = Ebml.readSize(buf, a) ?: return false
        val rel = ((buf[a + tn[1].toInt()].toInt() shl 8) or (buf[a + tn[1].toInt() + 1].toInt() and 0xFF)).toShort().toLong()
        val us = (clusterTc + rel) * index.timecodeScaleNs / 1000L
        return kotlin.math.abs(us - ptsUs) <= 1_000L
    }

    private fun toAnnexB(sample: ByteArray): ByteArray? {
        val n = index.video.nalLengthSize
        val hevc = index.video.codec == VideoCodec.HEVC
        val out = ByteArrayOutputStream(sample.size + index.video.parameterSetsAnnexB.size + 64)
        out.write(index.video.parameterSetsAnnexB)
        var p = 0
        var nals = 0
        while (p + n <= sample.size) {
            var len = 0L
            for (k in 0 until n) len = (len shl 8) or (sample[p + k].toLong() and 0xFF)
            p += n
            if (len <= 0 || p + len > sample.size) break
            val type = if (hevc) (sample[p].toInt() shr 1) and 0x3F else sample[p].toInt() and 0x1F
            // Dolby Vision: drop the enhancement layer (63), keep the RPU (62), profile 5 needs it for reshaping.
            if (!(hevc && type == 63)) {
                out.write(0); out.write(0); out.write(0); out.write(1)
                out.write(sample, p, len.toInt())
                nals++
            }
            p += len.toInt()
        }
        return if (nals > 0) out.toByteArray() else null
    }
}

/**
 * A keyframe sample as the player's extractor wrote it (Annex-B or length-prefixed) -> Annex-B access unit with
 * the index's parameter sets first and the Dolby Vision enhancement layer (HEVC NAL type 63) removed.
 */
internal fun tapSampleToAnnexB(sample: ByteArray, video: VideoTrack): ByteArray? {
    val hevc = video.codec == VideoCodec.HEVC
    val out = ByteArrayOutputStream(sample.size + video.parameterSetsAnnexB.size + 64)
    out.write(video.parameterSetsAnnexB)
    var nals = 0
    fun emit(from: Int, to: Int) {
        if (to <= from) return
        val type = if (hevc) (sample[from].toInt() shr 1) and 0x3F else sample[from].toInt() and 0x1F
        if (hevc && type == 63) return
        out.write(0); out.write(0); out.write(0); out.write(1)
        out.write(sample, from, to - from)
        nals++
    }
    val annexB = sample.size >= 4 && sample[0].toInt() == 0 && sample[1].toInt() == 0 &&
        (sample[2].toInt() == 1 || (sample[2].toInt() == 0 && sample[3].toInt() == 1))
    if (annexB) {
        // NAL payloads lie between start codes (00 00 01, optionally preceded by 00).
        var i = 0
        var nalStart = -1
        while (i + 2 < sample.size) {
            if (sample[i].toInt() == 0 && sample[i + 1].toInt() == 0 && sample[i + 2].toInt() == 1) {
                if (nalStart >= 0) {
                    var end = i
                    while (end > nalStart && sample[end - 1].toInt() == 0) end--   // trailing zero of a 4-byte code
                    emit(nalStart, end)
                }
                i += 3
                nalStart = i
            } else {
                i++
            }
        }
        if (nalStart >= 0) emit(nalStart, sample.size)
    } else {
        val n = video.nalLengthSize
        var p = 0
        while (p + n <= sample.size) {
            var len = 0L
            for (k in 0 until n) len = (len shl 8) or (sample[p + k].toLong() and 0xFF)
            p += n
            if (len <= 0 || p + len > sample.size) break
            emit(p, p + len.toInt())
            p += len.toInt()
        }
    }
    return if (nals > 0) out.toByteArray() else null
}
