package com.nuvio.tv.core.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import java.util.Locale

/**
 * Some older encodes drop the Dolby Vision configuration record, so a Profile 5 track arrives as plain HEVC and a
 * plain HEVC decoder shows its IPT picture purple and green. The first frame's RPU still says Profile 5: read it
 * (with [DoviRpuGuard], no native code) and label the track Dolby Vision Profile 5 again.
 */
internal object UnlabelledDolbyVision {
    private const val DEFAULT_FRAME_RATE = 24f

    /** HEVC with no Dolby Vision codec string and no Dolby Vision configuration record. */
    fun isCandidate(sampleMimeType: String?, codecs: String?, dolbyVisionConfigBytes: ByteArray?): Boolean =
        (sampleMimeType == null || sampleMimeType == MimeTypes.VIDEO_H265) &&
            !codecs.orEmpty().trim().lowercase(Locale.ROOT).startsWith("dv") &&
            (dolbyVisionConfigBytes == null || dolbyVisionConfigBytes.isEmpty())

    /** The profile the sample's first RPU describes, or null when it has none or the RPU is refused. */
    fun profileOfFirstRpu(sample: ByteArray, sampleLength: Int, nalLengthFieldLength: Int): Int? {
        val (offset, length) = HevcDvRpuStripper.findRpuNalLengthDelimited(sample, sampleLength, nalLengthFieldLength)
            ?: return null
        val result = DoviRpuGuard.check(sample, offset, length, escaped = true)
        return result.profile.takeIf { result.verdict == DoviRpuGuard.Verdict.OK }
    }

    /** [format] labelled Dolby Vision Profile 5 when [sample]'s first RPU says Profile 5, otherwise null. */
    fun relabel(format: Format, sample: ByteArray, sampleLength: Int, nalLengthFieldLength: Int): Format? {
        if (profileOfFirstRpu(sample, sampleLength, nalLengthFieldLength) != 5) return null
        val codecs = String.format(Locale.ROOT, "dvhe.05.%02d", level(format.width, format.height, format.frameRate))
        return format.buildUpon()
            .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
            .setCodecs(codecs)
            .build()
    }

    private class Level(val id: Int, val maxWidth: Int, val maxPixelsPerSecond: Long)

    private val levels = listOf(
        Level(1, 1280, 22_118_400),
        Level(2, 1280, 27_648_000),
        Level(3, 1920, 49_766_400),
        Level(4, 1920, 62_208_000),
        Level(5, 1920, 124_416_000),
        Level(6, 3840, 199_065_600),
        Level(7, 3840, 248_832_000),
        Level(8, 3840, 398_131_200),
        Level(9, 3840, 497_664_000),
        Level(10, 3840, 995_328_000),
        Level(12, 7680, 1_990_656_000),
        Level(13, 7680, 3_981_312_000)
    )

    /** The lowest Dolby Vision level that fits the picture; 6 (3840x2160 at 24) when the size is unknown. */
    fun level(width: Int, height: Int, frameRate: Float): Int {
        if (width <= 0 || height <= 0) return 6
        val fps = if (frameRate > 0f) frameRate else DEFAULT_FRAME_RATE
        val longSide = maxOf(width, height)
        val pixelsPerSecond = (width.toLong() * height * fps).toLong()
        return levels.firstOrNull { longSide <= it.maxWidth && pixelsPerSecond <= it.maxPixelsPerSecond }?.id ?: 13
    }
}
