package com.nuvio.tv.core.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import com.nuvio.tv.core.player.thumbnail.HardwareSetup
import com.nuvio.tv.core.player.thumbnail.VideoCodec
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class H264LevelLabelTest {

    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private val start = b(0, 0, 0, 1)

    // 1920x816, 24 fps, 4 reference frames, 2 reorder frames, High profile, level_idc 62 (x264 with a 1 Gbit/s ceiling).
    private val sps62 = b(
        0x67, 0x64, 0x08, 0x3E, 0xAC, 0xD9, 0x40, 0x78, 0x06, 0x7B, 0x01, 0x10, 0x00,
        0x00, 0x03, 0x00, 0x10, 0x00, 0x00, 0x03, 0x03, 0x08, 0xF0, 0x80, 0x41, 0x96
    )
    private val pps = b(0x68, 0xEF, 0xBC, 0xB0)
    private val sei = b(0x06, 0x05, 0x04, 0x11, 0x22, 0x33, 0x44, 0x80)
    private val idr = b(0x65, 0x88, 0x84, 0x00, 0x33, 0xFF)
    private val slice = b(0x41, 0x9A, 0x24, 0x6C, 0x41, 0x7F)

    private fun withLevel(sps: ByteArray, level: Int) = sps.copyOf().also { it[3] = level.toByte() }

    private fun h264(
        sps: ByteArray = sps62,
        codecs: String? = "avc1.64083E",
        frameRate: Float = 24f,
        mime: String = MimeTypes.VIDEO_H264,
    ): Format = Format.Builder()
        .setSampleMimeType(mime)
        .setCodecs(codecs)
        .setWidth(1920)
        .setHeight(816)
        .setFrameRate(frameRate)
        .setInitializationData(listOf(start + sps, start + pps))
        .build()

    @Test fun levelsUpTo52StayAsDeclared() {
        for (level in listOf(30, 40, 41, 50, 51, 52)) {
            assertNull(H264LevelLabel.replacementLevel(level, 1920, 1080, 24f, 4))
        }
    }

    @Test fun anOverDeclared1080pStreamBecomes51() {
        assertEquals(51, H264LevelLabel.replacementLevel(62, 1920, 816, 24f, 6))
        assertEquals(51, H264LevelLabel.replacementLevel(60, 1920, 1080, 60f, 16))
        assertEquals(51, H264LevelLabel.replacementLevel(61, 720, 576, 25f, 16))
    }

    @Test fun anUnknownFrameRateCountsAs60() {
        assertEquals(51, H264LevelLabel.replacementLevel(62, 1920, 1080, Format.NO_VALUE.toFloat(), 4))
        assertEquals(52, H264LevelLabel.replacementLevel(62, 3840, 2160, Format.NO_VALUE.toFloat(), 4))
    }

    @Test fun fourKTakes51Or52ByFrameRate() {
        assertEquals(51, H264LevelLabel.replacementLevel(62, 3840, 2160, 30f, 5))
        assertEquals(52, H264LevelLabel.replacementLevel(62, 3840, 2160, 60f, 5))
    }

    @Test fun aStreamThatNeedsLevel6StaysAsDeclared() {
        // Above 60 fps at 4K, more pictures to hold than 5.2 allows at 4K, and 8K.
        assertNull(H264LevelLabel.replacementLevel(62, 3840, 2160, 120f, 5))
        assertNull(H264LevelLabel.replacementLevel(62, 3840, 2160, 30f, 6))
        assertNull(H264LevelLabel.replacementLevel(62, 7680, 4320, 30f, 4))
        assertNull(H264LevelLabel.replacementLevel(62, 0, 0, 24f, 4))
    }

    @Test fun theSampleHeaderIsReadAndRelabelled() {
        val relabel = H264LevelLabel.relabelFor(sps62, 0, sps62.size, 24f)
        assertNotNull(relabel)
        assertEquals(62, relabel!!.fromLevelIdc)
        assertEquals(51, relabel.toLevelIdc)
        assertNull(H264LevelLabel.relabelFor(withLevel(sps62, 41), 0, sps62.size, 24f))
        assertNull(H264LevelLabel.relabelFor(b(0x67, 0x64, 0x08), 0, 3, 24f))
    }

    @Test fun theFormatGetsTheLevelInSetUpDataAndCodecString() {
        val original = h264()
        val relabelled = H264LevelLabel.relabel(original)
        assertNotNull(relabelled)
        val format = relabelled!!.format
        assertEquals("avc1.640833", format.codecs)
        assertArrayEquals(start + withLevel(sps62, 51), format.initializationData[0])
        assertArrayEquals(start + pps, format.initializationData[1])
        assertEquals(1920, format.width)
        assertEquals(24f, format.frameRate, 0f)
        // The track's own data is not written to.
        assertArrayEquals(start + sps62, original.initializationData[0])
        assertEquals("avc1.64083E", original.codecs)
    }

    @Test fun otherFormatsAndHonestLevelsAreLeftAlone() {
        assertNull(H264LevelLabel.relabel(h264(sps = withLevel(sps62, 41), codecs = "avc1.640829")))
        assertNull(H264LevelLabel.relabel(h264(mime = MimeTypes.VIDEO_H265)))
        assertNull(H264LevelLabel.relabel(h264(mime = MimeTypes.VIDEO_DOLBY_VISION)))
        assertNull(
            H264LevelLabel.relabel(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).setCodecs("avc1.64083E").build())
        )
    }

    @Test fun aMissingOrOddCodecStringIsKept() {
        assertNull(H264LevelLabel.relabel(h264(codecs = null))!!.format.codecs)
        assertEquals("avc1", H264LevelLabel.relabel(h264(codecs = "avc1"))!!.format.codecs)
        assertEquals("avc3.640833", H264LevelLabel.relabel(h264(codecs = "avc3.64083e"))!!.format.codecs)
    }

    @Test fun parameterSetsInASampleAreRewrittenInPlace() {
        val relabel = H264LevelLabel.Relabel(62, 51)
        val keyframe = start + sps62 + start + pps + b(0, 0, 1) + sei + start + idr
        val buffer = ByteBuffer.allocateDirect(keyframe.size + 16)
        buffer.put(keyframe).flip()
        assertEquals(1, H264LevelLabel.relabelParameterSets(buffer, buffer.position(), buffer.limit(), relabel))
        val out = ByteArray(keyframe.size).also { buffer.get(it) }
        assertArrayEquals(start + withLevel(sps62, 51) + start + pps + b(0, 0, 1) + sei + start + idr, out)
    }

    @Test fun samplesWithoutParameterSetsAreNotTouched() {
        val relabel = H264LevelLabel.Relabel(62, 51)
        // A sequence parameter set after the first slice is not looked for, and a different level is not rewritten.
        val later = start + slice + start + sps62
        val other = start + withLevel(sps62, 40) + start + idr
        for (sample in listOf(start + slice, later, other, ByteArray(0), b(0, 0, 1))) {
            val buffer = ByteBuffer.wrap(sample.copyOf())
            assertEquals(0, H264LevelLabel.relabelParameterSets(buffer, 0, sample.size, relabel))
            assertArrayEquals(sample, buffer.array())
        }
    }

    @Test fun onlyTheGivenRangeIsRewritten() {
        val relabel = H264LevelLabel.Relabel(62, 51)
        val sample = start + sps62 + start + idr
        val padded = ByteArray(8) + sample + start + sps62
        val buffer = ByteBuffer.wrap(padded)
        assertEquals(1, H264LevelLabel.relabelParameterSets(buffer, 8, 8 + sample.size, relabel))
        assertArrayEquals(ByteArray(8) + start + withLevel(sps62, 51) + start + idr + start + sps62, padded)
    }

    @Test fun thumbnailSetUpDataAndKeyframesAreRelabelled() {
        val parameterSets = start + sps62 + start + pps
        val keyframe = start + sps62 + start + pps + start + idr
        assertArrayEquals(
            start + withLevel(sps62, 51) + start + pps,
            HardwareSetup.csd(VideoCodec.H264, parameterSets, ByteArray(0), keyframe)
        )
        assertArrayEquals(
            start + withLevel(sps62, 51) + start + pps + start + idr,
            HardwareSetup.keyframe(VideoCodec.H264, keyframe, ByteArray(0))
        )
        assertArrayEquals(start + sps62 + start + pps + start + idr, keyframe)
    }

    @Test fun thumbnailDataWithAnHonestLevelIsTheSameArray() {
        val honest = start + withLevel(sps62, 41) + start + pps + start + idr
        assertSame(honest, HardwareSetup.keyframe(VideoCodec.H264, honest, ByteArray(0)))
        val noParameterSets = start + idr
        assertSame(noParameterSets, HardwareSetup.keyframe(VideoCodec.H264, noParameterSets, ByteArray(0)))
        val otherCodec = start + sps62
        assertSame(otherCodec, HardwareSetup.keyframe(VideoCodec.MPEG2, otherCodec, ByteArray(0)))
    }
}
