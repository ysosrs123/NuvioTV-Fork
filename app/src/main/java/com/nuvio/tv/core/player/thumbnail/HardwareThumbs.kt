package com.nuvio.tv.core.player.thumbnail

import android.content.Context
import com.nuvio.tv.ui.screens.player.isSoftwareVideoDecoderName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Thumbnails from the box's hardware video decoder, only while no film decodes (generating screen, after the film).
 * Follows the "Hardware decoder for thumbnails" setting, read again as each title starts ([refresh]);
 * `adb shell setprop debug.nuvio.thumb.hw 0` (or 1) overrides it.
 */
internal object HardwareThumbSwitch {
    @Volatile internal var setting = true

    val on: Boolean
        get() = onFor(setting, runCatching {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
                .invoke(null, "debug.nuvio.thumb.hw") as? String
        }.getOrNull())

    fun onFor(setting: Boolean, property: String?): Boolean = when (property?.trim()) {
        "0" -> false
        "1" -> true
        else -> setting
    }

    suspend fun refresh(context: Context) {
        setting = withContext(Dispatchers.IO) {
            runCatching { SeekThumbnailPreferences.hardwareEnabled(context) }.getOrDefault(true)
        }
    }
}

/** Which keyframes may go to the hardware decoder. */
internal object HardwareRoute {
    const val MIN_SDK = 24
    /** VC-1 gave no pictures from the hardware decoder in tests; MPEG-1 was not tried. */
    val CODECS = setOf(VideoCodec.H264, VideoCodec.HEVC, VideoCodec.MPEG2, VideoCodec.MPEG4, VideoCodec.VP9, VideoCodec.AV1)

    /**
     * Decoders that hold a lone picture until told the stream ends: MPEG-4 Part 2 and H.264 everywhere (Amlogic's H.264
     * decoder keeps a Blu-ray keyframe back for reordering), every format outside Amlogic (MediaTek gave none in 3 s).
     */
    fun endsEachKeyframe(codec: VideoCodec, codecName: String): Boolean =
        codec == VideoCodec.MPEG4 || codec == VideoCodec.H264 ||
            !codecName.lowercase().let { it.startsWith("c2.amlogic.") || it.startsWith("omx.amlogic.") }

    private val LIKELY_MIMES = setOf("video/hevc", "video/avc", "video/dolby-vision", "video/mpeg2", "video/x-vnd.on2.vp9",
        "video/av01", "video/mp4v-es")

    /** From the player's own format, before the index is read: whether the hardware path will most likely take the title. */
    fun likelyFor(sdk: Int, mime: String?, codecs: String?): Boolean {
        if (sdk < MIN_SDK || mime == null || mime !in LIKELY_MIMES) return false
        val c = codecs?.lowercase() ?: return true
        return !c.startsWith("dvhe.05") && !c.startsWith("dvh1.05")
    }

    /** Null when the hardware decoder may make this title's thumbnails, else why software stays. */
    fun softwareBecause(
        sdk: Int,
        phase: FetchPhase,
        codec: VideoCodec,
        bitDepth: Int,
        dolbyVisionProfile: Int?,
        titleOff: String?,
        blocked: Boolean,
        decoder: Boolean,
        fourKFromAppMemory: Boolean = false,
        appMemoryWhy: String? = null,
    ): String? = when {
        sdk < MIN_SDK -> "Android $sdk"
        phase == FetchPhase.PLAYBACK -> "film playing"
        codec !in CODECS -> "codec ${codec.name}"
        bitDepth > 10 -> "$bitDepth-bit"
        dolbyVisionProfile == 5 -> "Dolby Vision profile 5"
        fourKFromAppMemory -> appMemoryWhy ?: AppMemoryHardware.OFF_TEXT
        titleOff != null -> titleOff
        blocked -> "failed ${HardwareFailureBook.FAILURES} times on this box, software for ${HardwareFailureBook.BLOCK_DAYS} days"
        !decoder -> "no hardware decoder"
        else -> null
    }

    /** The colour decision from the title's first software decode; Dolby Vision IPT needs the software reshaping. */
    fun planUsable(decision: ColourDecision): Boolean = decision.transfer != ThumbTransfer.DOVI_IPT
}

/** Annex-B helpers for the hardware decoder. */
internal object HardwareNal {
    private const val HEVC_RPU = 62

    /** The keyframe without Dolby Vision RPUs (HEVC NAL 62), which the plain HEVC decoder does not expect. */
    fun withoutRpu(au: ByteArray, length: Int = au.size): ByteArray {
        val nals = nalRanges(au, length)
        if (nals.none { type(au, it.first, true) == HEVC_RPU }) return if (length == au.size) au else au.copyOf(length)
        val out = ByteArrayOutputStream(length)
        for (r in nals) {
            if (type(au, r.first, true) == HEVC_RPU) continue
            out.write(START)
            out.write(au, r.first, r.last - r.first + 1)
        }
        return out.toByteArray()
    }

    /** Parameter sets only (HEVC VPS/SPS/PPS, H.264 SPS/PPS), each behind a 4-byte start code: the decoder's csd-0. */
    fun parameterSets(annexB: ByteArray, hevc: Boolean): ByteArray {
        val out = ByteArrayOutputStream(annexB.size)
        for (r in nalRanges(annexB, annexB.size)) {
            val t = type(annexB, r.first, hevc)
            if (if (hevc) t in 32..34 else t == 7 || t == 8) {
                out.write(START)
                out.write(annexB, r.first, r.last - r.first + 1)
            }
        }
        return out.toByteArray()
    }

    /** NAL payloads between start codes (3 or 4 bytes), trailing zeros of the next start code left out. */
    fun nalRanges(au: ByteArray, length: Int): List<IntRange> {
        val out = ArrayList<IntRange>()
        var i = 0
        var start = -1
        while (i + 2 < length) {
            if (au[i].toInt() == 0 && au[i + 1].toInt() == 0 && au[i + 2].toInt() == 1) {
                if (start >= 0) {
                    var end = i
                    while (end > start && au[end - 1].toInt() == 0) end--
                    if (end > start) out += start until end
                }
                i += 3
                start = i
            } else {
                i++
            }
        }
        if (start in 0 until length) out += start until length
        return out
    }

    /** MPEG-2: the sequence header and what follows it up to the first picture start code (00 00 01 00), or null. */
    fun mpeg2Headers(b: ByteArray): ByteArray? {
        var seq = -1
        var i = 0
        while (i + 3 < b.size) {
            if (b[i].toInt() == 0 && b[i + 1].toInt() == 0 && b[i + 2].toInt() == 1) {
                val code = b[i + 3].toInt() and 0xFF
                if (code == 0xB3 && seq < 0) seq = i
                if (code == 0x00) return if (seq >= 0) b.copyOfRange(seq, i) else null
                i += 3
            } else {
                i++
            }
        }
        return if (seq >= 0) b.copyOfRange(seq, b.size) else null
    }

    /** MPEG-4 Part 2 without its user data blocks (00 00 01 B2, e.g. "XviD0050"), which the decoder refuses. */
    fun withoutMpeg4UserData(b: ByteArray): ByteArray {
        val starts = ArrayList<Int>()
        var i = 0
        while (i + 3 < b.size) {
            if (b[i].toInt() == 0 && b[i + 1].toInt() == 0 && b[i + 2].toInt() == 1) {
                starts += i
                i += 3
            } else {
                i++
            }
        }
        if (starts.none { (b[it + 3].toInt() and 0xFF) == 0xB2 }) return b
        val out = ByteArrayOutputStream(b.size)
        if (starts.first() > 0) out.write(b, 0, starts.first())
        for ((k, s) in starts.withIndex()) {
            val e = starts.getOrElse(k + 1) { b.size }
            if ((b[s + 3].toInt() and 0xFF) != 0xB2) out.write(b, s, e - s)
        }
        return out.toByteArray()
    }

    /** MPEG-4 Part 2: what comes before the first picture (VOP, 00 00 01 B6) when it holds a VOL, user data removed. */
    fun mpeg4Headers(b: ByteArray): ByteArray? {
        if (!LegacyCodecs.hasMpeg4Vol(b)) return null
        val head = LegacyCodecs.mpeg4HeadersBeforeVop(b) ?: b
        return withoutMpeg4UserData(head)
    }

    /** AV1 OBU types up to the first frame, or null when the bytes are not a run of sized OBUs. */
    fun av1ObuTypes(b: ByteArray): List<Int>? {
        val types = ArrayList<Int>()
        var p = 0
        while (p < b.size) {
            val h = b[p].toInt() and 0xFF
            if (h and 0x80 != 0 || h and 0x02 == 0) return null
            val type = (h shr 3) and 0x0F
            p += 1 + ((h shr 2) and 1)
            var size = 0L
            var shift = 0
            while (true) {
                if (p >= b.size || shift > 49) return null
                val v = b[p++].toInt() and 0xFF
                size = size or ((v and 0x7F).toLong() shl shift)
                shift += 7
                if (v and 0x80 == 0) break
            }
            if (p + size > b.size) return null
            types += type
            if (type == AV1_FRAME || type == AV1_FRAME_HEADER) break
            p += size.toInt()
        }
        return types
    }

    /**
     * An AV1 keyframe with the sequence header in front when it carries none: every picture follows a reset, and the
     * header may only be in the stream's first frame. It comes from av1C's configOBUs (after its 4 bytes).
     */
    fun av1WithSequenceHeader(au: ByteArray, av1c: ByteArray): ByteArray {
        val types = av1ObuTypes(au) ?: return au
        if (AV1_SEQUENCE_HEADER in types || av1c.size <= 4) return au
        val config = av1c.copyOfRange(4, av1c.size)
        if (av1ObuTypes(config)?.contains(AV1_SEQUENCE_HEADER) != true) return au
        // A temporal delimiter (2 bytes, size 0) stays first.
        val at = if (types.firstOrNull() == AV1_TEMPORAL_DELIMITER) 2 else 0
        return au.copyOfRange(0, at) + config + au.copyOfRange(at, au.size)
    }

    private const val AV1_SEQUENCE_HEADER = 1
    private const val AV1_TEMPORAL_DELIMITER = 2
    private const val AV1_FRAME_HEADER = 3
    private const val AV1_FRAME = 6

    private fun type(au: ByteArray, at: Int, hevc: Boolean): Int =
        if (hevc) (au[at].toInt() shr 1) and 0x3F else au[at].toInt() and 0x1F

    private val START = byteArrayOf(0, 0, 0, 1)
}

/** What each format's hardware decoder gets: its set-up data (csd-0) and the keyframe as queued. */
internal object HardwareSetup {
    /**
     * H.264/HEVC: the parameter sets. MPEG-2: the sequence header; MPEG-4 Part 2: the headers before the first picture,
     * without user data; both from the track or else the keyframe. VP9, AV1: none.
     */
    fun csd(codec: VideoCodec, parameterSetsAnnexB: ByteArray, extradata: ByteArray, keyframe: ByteArray): ByteArray? =
        when (codec) {
            VideoCodec.H264, VideoCodec.HEVC ->
                HardwareNal.parameterSets(parameterSetsAnnexB, codec == VideoCodec.HEVC).takeIf { it.isNotEmpty() }
            VideoCodec.MPEG2 -> HardwareNal.mpeg2Headers(extradata) ?: HardwareNal.mpeg2Headers(keyframe)
            VideoCodec.MPEG4 -> HardwareNal.mpeg4Headers(extradata) ?: HardwareNal.mpeg4Headers(keyframe)
            else -> null
        }

    fun keyframe(codec: VideoCodec, au: ByteArray, extradata: ByteArray): ByteArray = when (codec) {
        VideoCodec.HEVC -> HardwareNal.withoutRpu(au)
        VideoCodec.AV1 -> HardwareNal.av1WithSequenceHeader(au, extradata)
        VideoCodec.MPEG4 -> HardwareNal.withoutMpeg4UserData(au)
        else -> au
    }
}

/**
 * The video memory pool (CMA) from /proc/meminfo, in MB. Each hardware decoder takes its pictures from it; where the
 * box does not show it, missed pictures alone limit the decoder count.
 */
internal class VideoPool(val freeMb: Int, val totalMb: Int) {
    companion object {
        /** No decoder is added below this much free (4K, up to 1080p); one is taken away below [DROP_BELOW_MB]. */
        const val ADD_MARGIN_4K_MB = 200
        const val ADD_MARGIN_HD_MB = 80
        const val DROP_BELOW_MB = 48

        /** Above 1080p: what one more decoder is expected to take (more if more was seen), and the free pool below which one stays. */
        const val COST_4K_MB = 165
        const val DROP_4K_TO_ONE_MB = 150

        /** Above 1080p, a box with less RAM than this (or RAM not known) keeps one hardware decoder. */
        const val SMALL_BOX_TOTAL_MB = 3_072L

        /** A smaller CMA area is not where the decoders live (MediaTek shows 16 MB, always empty). */
        const val MIN_POOL_KB = 64L * 1024

        fun parse(meminfo: String): VideoPool? {
            var free = -1L
            var total = -1L
            for (line in meminfo.lineSequence()) {
                val kb = line.substringAfter(':', "").trim().substringBefore(' ').toLongOrNull() ?: continue
                when {
                    line.startsWith("CmaFree:") -> free = kb
                    line.startsWith("CmaTotal:") -> total = kb
                }
            }
            if (free < 0 || total < MIN_POOL_KB) return null
            return VideoPool((free / 1024).toInt(), (total / 1024).toInt())
        }

        fun addMarginMb(fourK: Boolean): Int = if (fourK) ADD_MARGIN_4K_MB else ADD_MARGIN_HD_MB

        fun mayAdd(freeMb: Int?, fourK: Boolean): Boolean = freeMb == null || freeMb >= addMarginMb(fourK)

        /** Above 1080p: the pool must be readable and keep [ADD_MARGIN_4K_MB] after one more decoder's [costMb]. */
        fun mayAdd4K(freeMb: Int?, costMb: Int): Boolean = freeMb != null && freeMb - costMb >= ADD_MARGIN_4K_MB

        fun oneOnly4K(totalRamMb: Long, poolReadable: Boolean): Boolean =
            totalRamMb < SMALL_BOX_TOTAL_MB || !poolReadable

        fun mustDrop(freeMb: Int?): Boolean = freeMb != null && freeMb < DROP_BELOW_MB
    }
}

/**
 * How many hardware decoders work at once: one at first, one more while thumbnails come faster, one fewer at the first
 * missed picture or when the last one brought no gain; then it stays. [cap]: at most [MAX], and one below the decoder's
 * instance limit, which the player shares. [start]: the count remembered for this box. Video memory: none is added
 * without [VideoPool]'s margin, and one is taken away when the pool runs low. Above 1080p: one decoder only on a small
 * box or where the pool cannot be read, and back to one when the pool runs low. Worker only.
 */
internal class HardwareLanes(
    maxInstances: Int,
    start: Int = 1,
    private val fourK: Boolean = false,
    /** Total RAM in MB, 0 when not known. Only read above 1080p. */
    totalRamMb: Long = Long.MAX_VALUE,
    /** Free video memory in MB, null where it cannot be read. */
    private val poolFreeMb: () -> Int? = { null },
) {
    class Change(val from: Int, val to: Int, val reason: String)

    companion object {
        const val MAX = 3
        const val WINDOW = 8
        const val GAIN = 1.10

        fun capFor(maxInstances: Int): Int = (maxInstances - 1).coerceIn(1, MAX)
    }

    private val freeAtStart: Int? = poolFreeMb()

    /** Above 1080p on a small box or where the pool cannot be read: why there is only one decoder, else null. */
    val oneOnly: String? = if (!fourK || !VideoPool.oneOnly4K(totalRamMb, freeAtStart != null)) null else listOfNotNull(
        if (totalRamMb in 1 until VideoPool.SMALL_BOX_TOTAL_MB) "RAM $totalRamMb MB"
        else if (totalRamMb <= 0L) "RAM not known" else null,
        if (freeAtStart == null) "video memory pool not readable" else null,
    ).joinToString(", ")

    val cap = if (oneOnly != null) 1 else capFor(maxInstances)
    var lanes = startLanes(start.coerceIn(1, cap))
        private set
    var settled = false
        private set
    var pictures = 0
        private set
    private val rates = DoubleArray(MAX + 1)
    private var windowStart = -1L
    private var inWindow = 0

    /** Thumbnails per second measured at [n] decoders, 0 when not measured. */
    fun rate(n: Int): Double = rates.getOrElse(n) { 0.0 }

    /** Above 1080p: [VideoPool.COST_4K_MB], or more when the open decoders have taken more each. */
    fun costMb(free: Int?): Int {
        val seen = if (free != null && freeAtStart != null) (freeAtStart - free) / lanes else 0
        return maxOf(VideoPool.COST_4K_MB, seen)
    }

    private fun startLanes(n: Int): Int {
        if (!fourK || n <= 1) return n
        val free = freeAtStart ?: return 1
        var k = n
        while (k > 1 && free - k * VideoPool.COST_4K_MB < VideoPool.ADD_MARGIN_4K_MB) k--
        return k
    }

    /** A [Change] with the same count before and after: the count stays, for the reason given. */
    fun onPicture(nowMs: Long): Change? {
        pictures++
        val free = poolFreeMb()
        if (lanes > 1 && fourK && (free == null || free < VideoPool.DROP_4K_TO_ONE_MB)) {
            settled = true
            return set(1, if (free == null) "video memory pool not readable" else "video memory $free MB free")
        }
        if (lanes > 1 && VideoPool.mustDrop(free)) {
            settled = true
            return set(lanes - 1, "video memory $free MB free")
        }
        if (settled) return null
        if (windowStart < 0) {
            windowStart = nowMs
            inWindow = 0
            return null
        }
        inWindow++
        if (inWindow < WINDOW) return null
        val r = inWindow * 1000.0 / (nowMs - windowStart).coerceAtLeast(1L)
        rates[lanes] = r
        val below = if (lanes > 1) rates[lanes - 1] else 0.0
        if (lanes > 1 && r < below * GAIN) {
            settled = true
            return set(lanes - 1, "no faster than ${lanes - 1}")
        }
        if (lanes >= cap) {
            settled = true
            return null
        }
        if (fourK) {
            val cost = costMb(free)
            if (!VideoPool.mayAdd4K(free, cost)) {
                settled = true
                return Change(lanes, lanes, "video memory $free MB free, one more takes about $cost MB and " +
                    "${VideoPool.ADD_MARGIN_4K_MB} MB must stay free")
            }
        } else if (!VideoPool.mayAdd(free, false)) {
            settled = true
            return Change(lanes, lanes, "video memory $free MB free, ${VideoPool.addMarginMb(false)} MB wanted for one more")
        }
        return set(lanes + 1, "faster")
    }

    fun onMiss(): Change? {
        settled = true
        return if (lanes > 1) set(lanes - 1, "missed picture") else null
    }

    private fun set(to: Int, why: String): Change {
        val c = Change(lanes, to, why)
        lanes = to
        windowStart = -1L
        inWindow = 0
        return c
    }
}

/** Where failures are remembered (SharedPreferences in the app). */
internal interface HardwareFailureMemory {
    fun get(key: String): String?
    fun put(key: String, value: String)
}

/**
 * Hardware failures per box and decoder. [FAILURES] within [BLOCK_DAYS] days: software for [BLOCK_DAYS] days, then the
 * count starts again.
 */
internal class HardwareFailureBook(private val memory: HardwareFailureMemory, private val nowMs: () -> Long) {
    companion object {
        const val FAILURES = 3
        const val BLOCK_DAYS = 30
        const val BLOCK_MS = BLOCK_DAYS * 24L * 60 * 60 * 1000
    }

    private class Entry(val count: Int, val firstAtMs: Long, val blockedUntilMs: Long) {
        override fun toString() = "$count,$firstAtMs,$blockedUntilMs"
    }

    private fun read(key: String): Entry {
        val p = memory.get(key)?.split(',')?.mapNotNull { it.toLongOrNull() }
        return if (p == null || p.size != 3) Entry(0, 0L, 0L) else Entry(p[0].toInt(), p[1], p[2])
    }

    fun blocked(key: String): Boolean = synchronized(this) { nowMs() < read(key).blockedUntilMs }

    /** True when this failure starts the block. */
    fun failed(key: String): Boolean = synchronized(this) {
        val now = nowMs()
        val e = read(key)
        if (now < e.blockedUntilMs) return false
        val fresh = e.count == 0 || now - e.firstAtMs > BLOCK_MS
        val count = if (fresh) 1 else e.count + 1
        val first = if (fresh) now else e.firstAtMs
        if (count >= FAILURES) {
            memory.put(key, Entry(0, 0L, now + BLOCK_MS).toString())
            return true
        }
        memory.put(key, Entry(count, first, 0L).toString())
        return false
    }
}

/** The decoder count that worked per box, format and picture size: the next title starts there and keeps adapting. */
internal class HardwareLaneBook(private val memory: HardwareFailureMemory) {
    companion object {
        fun key(box: String, codec: VideoCodec, fourK: Boolean): String =
            "lanes|$box|${codec.name}|${if (fourK) "4k" else "hd"}"
    }

    fun start(key: String): Int = memory.get(key)?.toIntOrNull()?.takeIf { it in 1..HardwareLanes.MAX } ?: 1

    /** At the end of a title that stayed on hardware, once it made a window's worth. True when the count changed. */
    fun store(key: String, lanes: HardwareLanes): Boolean {
        if (lanes.pictures < HardwareLanes.WINDOW) return false
        val v = lanes.lanes.toString()
        if (memory.get(key) == v) return false
        memory.put(key, v)
        return true
    }
}

/**
 * When the hardware path gives up on a title well before the generating screen's 20 s stall rule: a decode running
 * for [STUCK_MS] (each picture has 3 s, so a call into the decoder or the GPU has hung), or nothing made [STUCK_MS]
 * after the first hardware decode began.
 */
internal object HardwareStall {
    const val STUCK_MS = 6_000L
    /** How often the worker looks while hardware decodes run. */
    const val LOOK_MS = 1_000L

    fun stuck(nowMs: Long, decodeSinceMs: Long): Boolean = decodeSinceMs > 0L && nowMs - decodeSinceMs >= STUCK_MS

    fun nothingMade(nowMs: Long, firstDecodeMs: Long, pictures: Int): Boolean =
        pictures == 0 && firstDecodeMs > 0L && nowMs - firstDecodeMs >= STUCK_MS
}

/** A picture with no detail: black, or one flat colour. */
internal object HardwarePicture {
    const val STEP = 8
    const val MAX_SPREAD = 8

    /** [argb] reads one pixel; a grid every [STEP] pixels is checked for a luma spread of at most [MAX_SPREAD]. */
    fun flat(width: Int, height: Int, argb: (Int, Int) -> Int): Boolean {
        var lo = Int.MAX_VALUE
        var hi = Int.MIN_VALUE
        var y = STEP / 2
        while (y < height) {
            var x = STEP / 2
            while (x < width) {
                val c = argb(x, y)
                val l = (((c shr 16) and 0xFF) * 2 + ((c shr 8) and 0xFF) * 5 + (c and 0xFF)) / 8
                if (l < lo) lo = l
                if (l > hi) hi = l
                if (hi - lo > MAX_SPREAD) return false
                x += STEP
            }
            y += STEP
        }
        return lo != Int.MAX_VALUE
    }
}

/** thumbcolor's tables as exported by ThumbNative.nativeToneLuts: lin, scale, oetf (0..1), then 1 / peak. */
internal class ToneLuts(val lin: FloatArray, val scale: FloatArray, val oetf: FloatArray, val invPeak: Float) {
    companion object {
        const val SIZE = 4096
        /** Each table is uploaded as a [SIDE] x [SIDE] texture: index i sits at (i % SIDE, i / SIDE). */
        const val SIDE = 64

        fun from(all: FloatArray?): ToneLuts? {
            if (all == null || all.size != 3 * SIZE + 1) return null
            return ToneLuts(
                all.copyOfRange(0, SIZE), all.copyOfRange(SIZE, 2 * SIZE), all.copyOfRange(2 * SIZE, 3 * SIZE), all[3 * SIZE],
            )
        }

        /** thumbcolor's index for a 16-bit full-range code ([v] 0..1): the top 12 bits. */
        fun codeIndex(v: Float): Int = ((v.coerceIn(0f, 1f) * 65535f).toInt() shr 4).coerceIn(0, SIZE - 1)

        /** thumbcolor's lut_index: square-root spacing, rounded. */
        fun sqrtIndex(v: Float): Int =
            if (!(v > 0f)) 0 else (kotlin.math.sqrt(v) * (SIZE - 1) + 0.5f).toInt().coerceAtMost(SIZE - 1)
    }
}

/** A hardware decoder for one format, from MediaCodecList. */
internal class HardwareCodecCandidate(
    val name: String,
    val hardware: Boolean,
    val secure: Boolean,
    val sizeSupported: Boolean,
    val tenBit: Boolean,
    val maxInstances: Int,
)

internal object HardwareCodecPick {
    /**
     * Android 10 and later say whether a decoder is hardware. Before that the name tells: Android's own and the
     * extension decoders are software, the box maker's are not.
     */
    fun hardware(sdk: Int, name: String, accelerated: () -> Boolean, softwareOnly: () -> Boolean): Boolean =
        if (sdk >= 29) accelerated() && !softwareOnly() else !isSoftwareVideoDecoderName(name, emptySet())

    /** Profiles that mean 10-bit for each format (MediaCodecInfo.CodecProfileLevel values). */
    fun tenBitProfile(mime: String, profile: Int): Boolean = when (mime) {
        "video/hevc" -> profile == 0x2 || profile == 0x1000 || profile == 0x2000
        "video/avc" -> profile == 0x10
        "video/x-vnd.on2.vp9" -> profile in VP9_TEN_BIT
        "video/av01" -> profile == 0x2 || profile == 0x1000 || profile == 0x2000
        else -> false
    }

    // VP9 profile 2 and 3, plain, HDR and HDR10+
    private val VP9_TEN_BIT = setOf(0x4, 0x8, 0x1000, 0x2000, 0x4000, 0x8000)

    /** The first hardware, non-secure decoder that takes the size (and 10-bit when asked). */
    fun pick(candidates: List<HardwareCodecCandidate>, tenBit: Boolean): HardwareCodecCandidate? =
        candidates.firstOrNull { it.hardware && !it.secure && it.sizeSupported && (!tenBit || it.tenBit) }
}
