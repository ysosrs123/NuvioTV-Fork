package com.nuvio.tv.core.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.container.NalUnitUtil
import java.nio.ByteBuffer

/**
 * H.264 streams that declare a level above 5.2 although the picture fits 5.1 or 5.2. Encoders write that when
 * the bitrate ceiling is set absurdly high, and hardware decoders (Amlogic) refuse the stream as unreadable.
 * The level is one byte in each sequence parameter set, so it is rewritten on the way to the decoder.
 */
internal object H264LevelLabel {

    class Relabel(val fromLevelIdc: Int, val toLevelIdc: Int)

    class RelabelledFormat(val format: Format, val relabel: Relabel)

    private class Level(val idc: Int, val maxFrameMbs: Int, val maxMbsPerSecond: Long, val maxDpbMbs: Int)

    private val TARGETS = listOf(
        Level(51, 36_864, 983_040, 184_320),
        Level(52, 36_864, 2_073_600, 184_320),
    )
    private const val HIGHEST_COMMON_LEVEL = 52
    private const val MAX_DPB_FRAMES = 16
    private const val ASSUMED_FRAME_RATE = 60f
    private const val NAL_SPS = 7
    private const val LEVEL_OFFSET = 3

    /** Parameter sets sit at the start of a sample; the scan stops at the first slice or after this many bytes. */
    private const val SCAN_BYTES = 8 * 1024

    /**
     * The level to declare instead, or null when [levelIdc] is 5.2 or lower or the stream needs more than 5.2.
     * [dpbFrames]: the pictures the decoder has to hold, references plus reordering.
     */
    fun replacementLevel(levelIdc: Int, width: Int, height: Int, frameRate: Float, dpbFrames: Int): Int? {
        if (levelIdc <= HIGHEST_COMMON_LEVEL || width <= 0 || height <= 0) return null
        val frameMbs = ((width + 15) / 16) * ((height + 15) / 16)
        val mbsPerSecond = frameMbs * (if (frameRate > 0f) frameRate else ASSUMED_FRAME_RATE)
        return TARGETS.firstOrNull { level ->
            frameMbs <= level.maxFrameMbs &&
                mbsPerSecond <= level.maxMbsPerSecond &&
                dpbFrames <= minOf(MAX_DPB_FRAMES, level.maxDpbMbs / frameMbs)
        }?.idc
    }

    /** [nalStart] is the NAL header of a sequence parameter set that ends before [nalEnd]. */
    fun relabelFor(data: ByteArray, nalStart: Int, nalEnd: Int, frameRate: Float): Relabel? {
        if (nalStart + LEVEL_OFFSET >= nalEnd) return null
        if ((data[nalStart + LEVEL_OFFSET].toInt() and 0xFF) <= HIGHEST_COMMON_LEVEL) return null
        val sps = runCatching { NalUnitUtil.parseSpsNalUnit(data, nalStart, nalEnd) }.getOrNull() ?: return null
        val dpbFrames = minOf(MAX_DPB_FRAMES, sps.maxNumRefFrames + sps.maxNumReorderFrames)
        val to = replacementLevel(sps.levelIdc, sps.width, sps.height, frameRate, dpbFrames) ?: return null
        return Relabel(sps.levelIdc, to)
    }

    /** The format with the level corrected in its set-up data and codec string, or null when it stays as it is. */
    fun relabel(format: Format): RelabelledFormat? {
        if (format.sampleMimeType != MimeTypes.VIDEO_H264) return null
        var relabel: Relabel? = null
        val patched = format.initializationData.map { nal ->
            val at = firstNalHeader(nal) ?: return@map nal
            if ((nal[at].toInt() and 0x1F) != NAL_SPS || at + LEVEL_OFFSET >= nal.size) return@map nal
            val r = relabel
                ?: relabelFor(nal, at, nal.size, format.frameRate)?.also { relabel = it }
                ?: return@map nal
            if ((nal[at + LEVEL_OFFSET].toInt() and 0xFF) != r.fromLevelIdc) return@map nal
            nal.copyOf().also { it[at + LEVEL_OFFSET] = r.toLevelIdc.toByte() }
        }
        val r = relabel ?: return null
        val corrected = format.buildUpon()
            .setInitializationData(patched)
            .setCodecs(relabelCodecs(format.codecs, r))
            .build()
        return RelabelledFormat(corrected, r)
    }

    /**
     * Rewrites the level in the sequence parameter sets at the start of an Annex-B sample, in place, between
     * [start] and [end]. Returns how many were changed.
     */
    fun relabelParameterSets(data: ByteBuffer, start: Int, end: Int, relabel: Relabel): Int {
        var changed = 0
        forEachLeadingNal(data, start, end) { header, type ->
            val at = header + LEVEL_OFFSET
            if (type == NAL_SPS && at < end && (data.get(at).toInt() and 0xFF) == relabel.fromLevelIdc) {
                data.put(at, relabel.toLevelIdc.toByte())
                changed++
            }
        }
        return changed
    }

    /** Annex-B parameter sets or a keyframe with the level corrected; the same array when nothing changes. */
    fun relabelAnnexB(data: ByteArray): ByteArray {
        val source = ByteBuffer.wrap(data)
        var relabel: Relabel? = null
        forEachLeadingNal(source, 0, data.size) { header, type ->
            if (type == NAL_SPS && relabel == null) {
                relabel = relabelFor(data, header, nalEnd(data, header), Format.NO_VALUE.toFloat())
            }
        }
        val r = relabel ?: return data
        val out = data.copyOf()
        relabelParameterSets(ByteBuffer.wrap(out), 0, out.size, r)
        return out
    }

    fun describe(levelIdc: Int): String = "${levelIdc / 10}.${levelIdc % 10}"

    private fun relabelCodecs(codecs: String?, relabel: Relabel): String? {
        if (codecs == null || !AVC_CODECS.matches(codecs)) return codecs
        return codecs.dropLast(2) + "%02X".format(relabel.toLevelIdc)
    }

    private inline fun forEachLeadingNal(data: ByteBuffer, start: Int, end: Int, action: (header: Int, type: Int) -> Unit) {
        val stop = minOf(end, start + SCAN_BYTES)
        var i = start
        while (i + 3 < stop) {
            if (data.get(i).toInt() == 0 && data.get(i + 1).toInt() == 0 && data.get(i + 2).toInt() == 1) {
                val type = data.get(i + 3).toInt() and 0x1F
                if (type in 1..5) return
                action(i + 3, type)
                i += 4
            } else {
                i++
            }
        }
    }

    private fun firstNalHeader(data: ByteArray): Int? {
        var i = 0
        while (i + 3 < data.size) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) return i + 3
            i++
        }
        return null
    }

    private fun nalEnd(data: ByteArray, header: Int): Int {
        var i = header
        while (i + 2 < data.size) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) return i
            i++
        }
        return data.size
    }

    private val AVC_CODECS = Regex("avc[13]\\.[0-9A-Fa-f]{6}")
}
