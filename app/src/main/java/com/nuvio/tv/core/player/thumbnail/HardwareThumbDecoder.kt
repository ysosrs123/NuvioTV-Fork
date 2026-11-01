package com.nuvio.tv.core.player.thumbnail

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.SystemClock
import java.nio.ByteBuffer

/** What the title's first software decode settled: colour and picture size. */
internal class HardwarePlan(val decision: ColourDecision, val outW: Int, val outH: Int)

/**
 * The hardware decoder gave no picture. [missed]: it timed out or ran out of buffers, which more decoders at once can
 * cause; otherwise it failed outright. [keyframeOnly]: this keyframe does not fit, the next ones may.
 */
internal class HardwareThumbFailure(
    val why: String,
    val missed: Boolean = false,
    val keyframeOnly: Boolean = false,
) : Exception(why)

internal object HardwareCodecs {
    class Choice(val name: String, val maxInstances: Int)

    private val cache = HashMap<String, Choice?>()

    fun mime(codec: VideoCodec): String? = when (codec) {
        VideoCodec.H264 -> MediaFormat.MIMETYPE_VIDEO_AVC
        VideoCodec.HEVC -> MediaFormat.MIMETYPE_VIDEO_HEVC
        VideoCodec.MPEG2 -> MediaFormat.MIMETYPE_VIDEO_MPEG2
        VideoCodec.MPEG4 -> MediaFormat.MIMETYPE_VIDEO_MPEG4
        VideoCodec.VP9 -> MediaFormat.MIMETYPE_VIDEO_VP9
        VideoCodec.AV1 -> "video/av01"
        else -> null
    }

    /** The box's hardware decoder for [track], or null. Read once per format. */
    fun choose(track: VideoTrack): Choice? {
        if (track.codec !in HardwareRoute.CODECS) return null
        val mime = mime(track.codec) ?: return null
        val tenBit = track.bitDepth > 8
        val key = "$mime ${track.width}x${track.height} $tenBit"
        synchronized(cache) { if (cache.containsKey(key)) return cache[key] }
        val found = runCatching { candidates(mime, track.width, track.height) }.getOrDefault(emptyList())
        val pick = HardwareCodecPick.pick(found, tenBit)?.let { Choice(it.name, it.maxInstances) }
        synchronized(cache) { cache[key] = pick }
        return pick
    }

    @SuppressLint("NewApi")
    private fun candidates(mime: String, width: Int, height: Int): List<HardwareCodecCandidate> =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, ignoreCase = true) } }
            .map { info ->
                val caps = info.getCapabilitiesForType(mime)
                val sdk = Build.VERSION.SDK_INT
                HardwareCodecCandidate(
                    name = info.name,
                    hardware = HardwareCodecPick.hardware(sdk, info.name,
                        accelerated = { sdk >= 29 && info.isHardwareAccelerated },
                        softwareOnly = { sdk >= 29 && info.isSoftwareOnly }),
                    secure = info.name.endsWith(".secure") ||
                        caps.isFeatureRequired(MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback),
                    sizeSupported = caps.videoCapabilities?.isSizeSupported(width, height) == true,
                    tenBit = caps.profileLevels.any { HardwareCodecPick.tenBitProfile(mime, it.profile) },
                    maxInstances = caps.maxSupportedInstances,
                )
            }
}

/** Failures counted per box (its firmware) and decoder, and the decoder counts that worked. */
internal object HardwareFailures {
    @Volatile private var book: HardwareFailureBook? = null
    @Volatile private var lanes: HardwareLaneBook? = null
    @Volatile private var costs: HardwareCostBook? = null

    private fun memory(context: Context) = object : HardwareFailureMemory {
        private val prefs = context.applicationContext.getSharedPreferences("seek_thumbs_hw", Context.MODE_PRIVATE)
        override fun get(key: String): String? = prefs.getString(key, null)
        override fun put(key: String, value: String) = prefs.edit().putString(key, value).apply()
    }

    fun book(context: Context): HardwareFailureBook = book ?: synchronized(this) {
        book ?: HardwareFailureBook(memory(context)) { System.currentTimeMillis() }.also { book = it }
    }

    fun laneBook(context: Context): HardwareLaneBook = lanes ?: synchronized(this) {
        lanes ?: HardwareLaneBook(memory(context)).also { lanes = it }
    }

    fun costBook(context: Context): HardwareCostBook = costs ?: synchronized(this) {
        costs ?: HardwareCostBook(memory(context)).also { costs = it }
    }

    val box: String get() = Integer.toHexString(Build.FINGERPRINT.hashCode())

    fun key(decoder: String): String = "$box|$decoder"
}

/** The video memory pool as the app can read it, null where the box does not show it. */
internal object VideoPoolReader {
    fun read(): VideoPool? = runCatching { VideoPool.parse(java.io.File("/proc/meminfo").readText()) }.getOrNull()
}

/**
 * One hardware decoder in synchronous mode, drawing to a [GlThumbShrink]. Reset after every picture: some decoders
 * (Amlogic) keep only the first picture when keyframes come back to back. [smallInput]: the input buffer is sized to the
 * keyframes ([HardwareInputSize]) instead of [MAX_INPUT_BYTES]. Not thread-safe: each decode lane owns one.
 */
internal class HardwareThumbDecoder(val codecName: String, val smallInput: Boolean = false) : ThumbLaneDecoder {
    companion object {
        const val TIMEOUT_MS = 3_000L
        const val MAX_INPUT_BYTES = 8 * 1024 * 1024
    }

    private var codec: MediaCodec? = null
    private var gl: GlThumbShrink? = null
    private var openTrack: VideoTrack? = null
    private var openPlan: HardwarePlan? = null
    private var pts = 0L
    /** The input size asked for or given, kept across a close so a reopen does not start small again. */
    private var inputBytes = 0
    private val info = MediaCodec.BufferInfo()

    override val warm: Boolean get() = codec != null

    fun make(au: ThumbAu, track: VideoTrack, plan: HardwarePlan): Bitmap = make(au.toByteArray(), track, plan)

    /** One keyframe to a full-colour thumbnail of the plan's size; throws [HardwareThumbFailure]. */
    fun make(bytes: ByteArray, track: VideoTrack, plan: HardwarePlan): Bitmap {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        val data = HardwareSetup.keyframe(track.codec, bytes, track.extradata)
        if (data.size > MAX_INPUT_BYTES) throw HardwareThumbFailure("keyframe of ${data.size / 1024} KB", keyframeOnly = true)
        val inputSize = if (smallInput) HardwareInputSize.wanted(data.size, inputBytes, MAX_INPUT_BYTES) else MAX_INPUT_BYTES
        if (smallInput && codec != null && inputSize > inputBytes) close()
        inputBytes = inputSize
        try {
            val c = open(track, plan, data, inputSize)
            val surface = gl ?: throw HardwareThumbFailure("no surface")
            val ii = c.dequeueInputBuffer(left(deadline) * 1000)
            if (ii < 0) throw HardwareThumbFailure("no input buffer", missed = true)
            val input = c.getInputBuffer(ii) ?: throw HardwareThumbFailure("no input buffer", missed = true)
            input.clear()
            if (smallInput) inputBytes = maxOf(inputBytes, input.capacity())
            if (input.capacity() < data.size) {
                c.flush()
                throw HardwareThumbFailure("keyframe of ${data.size / 1024} KB", keyframeOnly = true)
            }
            input.put(data)
            val flags = if (HardwareRoute.endsEachKeyframe(track.codec, codecName)) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
            c.queueInputBuffer(ii, 0, data.size, pts++, flags)
            var oi: Int
            while (true) {
                oi = c.dequeueOutputBuffer(info, minOf(left(deadline), 50L) * 1000)
                if (oi >= 0) break
                if (left(deadline) <= 0L) throw HardwareThumbFailure("no picture in ${TIMEOUT_MS / 1000} s", missed = true)
            }
            val latch = surface.arm()
            c.releaseOutputBuffer(oi, true)
            val pixels = surface.await(latch, left(deadline).coerceAtLeast(500L))
                ?: throw HardwareThumbFailure("picture not drawn", missed = true)
            val bmp = Bitmap.createBitmap(plan.outW, plan.outH, Bitmap.Config.ARGB_8888)
            pixels.rewind()
            bmp.copyPixelsFromBuffer(pixels)
            c.flush()
            return bmp
        } catch (e: HardwareThumbFailure) {
            if (!e.keyframeOnly) close()
            throw e
        } catch (e: MediaCodec.CodecException) {
            close()
            val short = e.isTransient || e.isRecoverable || e.errorCode == MediaCodec.CodecException.ERROR_INSUFFICIENT_RESOURCE
            throw HardwareThumbFailure("codec error ${e.diagnosticInfo}", missed = short)
        } catch (e: IllegalStateException) {
            close()
            throw HardwareThumbFailure("codec state: ${e.message}")
        } catch (e: IllegalArgumentException) {
            close()
            throw HardwareThumbFailure("codec set-up: ${e.message}")
        }
    }

    private fun left(deadline: Long): Long = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)

    private fun open(track: VideoTrack, plan: HardwarePlan, keyframe: ByteArray, inputSize: Int): MediaCodec {
        codec?.takeIf { openTrack === track && openPlan === plan }?.let { return it }
        close()
        val mime = HardwareCodecs.mime(track.codec) ?: throw HardwareThumbFailure("codec ${track.codec.name}")
        val d = plan.decision
        val tone = d.transfer == ThumbTransfer.PQ || d.transfer == ThumbTransfer.HLG
        val luts = if (tone) {
            ToneLuts.from(runCatching { ThumbNative.nativeToneLuts(d.transfer.nativeCode, d.peakNits, d.bt2020) }.getOrNull())
                ?: throw HardwareThumbFailure("no tone-map tables")
        } else null
        val shrink = try {
            GlThumbShrink(plan.outW, plan.outH, luts, hlg = d.transfer == ThumbTransfer.HLG, bt2020 = d.bt2020)
        } catch (t: Throwable) {
            throw HardwareThumbFailure("GPU: ${t.message}")
        }
        gl = shrink
        val c = try {
            MediaCodec.createByCodecName(codecName)
        } catch (t: Throwable) {
            close()
            throw HardwareThumbFailure("cannot create $codecName: ${t.message}", missed = true)
        }
        codec = c
        val format = MediaFormat.createVideoFormat(mime, track.width, track.height).apply {
            HardwareSetup.csd(track.codec, track.parameterSetsAnnexB, track.extradata, keyframe)
                ?.let { setByteBuffer("csd-0", ByteBuffer.wrap(it)) }
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, inputSize)
            setInteger(MediaFormat.KEY_PRIORITY, 1)
            if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
        c.configure(format, shrink.surface, null, 0)
        c.start()
        openTrack = track
        openPlan = plan
        return c
    }

    override fun trim() = Unit

    override fun close() {
        codec?.let { c ->
            runCatching { c.stop() }
            runCatching { c.release() }
        }
        codec = null
        gl?.let { runCatching { it.release() } }
        gl = null
        openTrack = null
        openPlan = null
    }
}
