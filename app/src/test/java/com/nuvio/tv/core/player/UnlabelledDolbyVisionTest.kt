package com.nuvio.tv.core.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnlabelledDolbyVisionTest {
    private val rpus: Map<String, ByteArray> by lazy {
        javaClass.classLoader!!.getResourceAsStream("dovi/rpu-guard-cases.txt")!!.bufferedReader().readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .associate { line ->
                val parts = line.trim().split(Regex("\\s+"))
                parts[0] to ByteArray(parts[4].length / 2) { parts[4].substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            }
    }

    private val hevc4k = Format.Builder()
        .setSampleMimeType(MimeTypes.VIDEO_H265)
        .setCodecs("hvc1.2.4.L153.B0")
        .setWidth(3840)
        .setHeight(2160)
        .setFrameRate(23.976f)
        .build()

    // A length-delimited access unit: a parameter-set NAL, the RPU NAL, a slice NAL.
    private fun sample(rpu: ByteArray?): ByteArray {
        val nals = listOfNotNull(byteArrayOf(0x40, 0x01, 0x0C, 0x01), rpu, byteArrayOf(0x26, 0x01, 0x55, 0x66, 0x77))
        return nals.fold(ByteArray(0)) { acc, nal ->
            acc + byteArrayOf((nal.size ushr 24).toByte(), (nal.size ushr 16).toByte(), (nal.size ushr 8).toByte(), nal.size.toByte()) + nal
        }
    }

    private fun relabel(rpuName: String?, format: Format = hevc4k): Format? {
        val data = sample(rpuName?.let { rpus.getValue(it) })
        return UnlabelledDolbyVision.relabel(format, data, data.size, 4)
    }

    @Test
    fun `a Profile 5 RPU in plain HEVC relabels the track as Dolby Vision Profile 5`() {
        val out = relabel("p5")!!
        assertEquals(MimeTypes.VIDEO_DOLBY_VISION, out.sampleMimeType)
        assertEquals("dvhe.05.06", out.codecs)
        assertEquals(3840, out.width)
        assertEquals(23.976f, out.frameRate)
    }

    @Test
    fun `other profiles, damaged RPUs and frames without an RPU stay as they are`() {
        assertNull(relabel("p8"))
        assertNull(relabel("p7"))
        assertNull(relabel("bad-crc"))
        assertNull(relabel("curve-type-2"))
        assertNull(relabel(null))
    }

    @Test
    fun `the first RPU decides, read where the sample says it is`() {
        val data = sample(rpus.getValue("p5"))
        assertEquals(5, UnlabelledDolbyVision.profileOfFirstRpu(data, data.size, 4))
        assertNull(UnlabelledDolbyVision.profileOfFirstRpu(data, 10, 4))
        assertNull(UnlabelledDolbyVision.profileOfFirstRpu(data, data.size, 0))
    }

    @Test
    fun `level follows picture size and frame rate`() {
        assertEquals(3, UnlabelledDolbyVision.level(1920, 1080, 23.976f))
        assertEquals(5, UnlabelledDolbyVision.level(1920, 1080, 59.94f))
        assertEquals(2, UnlabelledDolbyVision.level(1280, 720, 30f))
        assertEquals(6, UnlabelledDolbyVision.level(3840, 2160, 24f))
        assertEquals(6, UnlabelledDolbyVision.level(3840, 1600, 23.976f))
        assertEquals(7, UnlabelledDolbyVision.level(3840, 2160, 29.97f))
        assertEquals(9, UnlabelledDolbyVision.level(3840, 2160, 60f))
        assertEquals(6, UnlabelledDolbyVision.level(3840, 2160, Format.NO_VALUE.toFloat()))
        assertEquals(6, UnlabelledDolbyVision.level(Format.NO_VALUE, Format.NO_VALUE, 24f))
        assertEquals(12, UnlabelledDolbyVision.level(7680, 4320, 60f))
    }

    @Test
    fun `only HEVC tracks with no Dolby Vision label are candidates`() {
        assertTrue(UnlabelledDolbyVision.isCandidate(MimeTypes.VIDEO_H265, "hvc1.2.4.L153.B0", null))
        assertTrue(UnlabelledDolbyVision.isCandidate(null, null, ByteArray(0)))
        assertFalse(UnlabelledDolbyVision.isCandidate(MimeTypes.VIDEO_H265, "dvhe.05.06", null))
        assertFalse(UnlabelledDolbyVision.isCandidate(null, "hev1.2.4.L153", byteArrayOf(1, 0, 0x0A)))
        assertFalse(UnlabelledDolbyVision.isCandidate(MimeTypes.VIDEO_H264, "avc1.640028", null))
    }

    @Test
    fun `the MKV transformer probes only when the box decodes Profile 5`() {
        val on = DolbyVisionMatroskaTransformer(
            DolbyVisionConversionConfig(active = false, relabelUnlabelledP5 = true, stats = DolbyVisionStatsSession())
        )
        val off = DolbyVisionMatroskaTransformer(
            DolbyVisionConversionConfig(active = false, stats = DolbyVisionStatsSession())
        )
        assertTrue(on.probesFirstSample("hvc1.2.4.L153.B0", null))
        assertFalse(on.probesFirstSample("dvhe.05.06", null))
        assertFalse(off.probesFirstSample("hvc1.2.4.L153.B0", null))

        val data = sample(rpus.getValue("p5"))
        assertEquals("dvhe.05.06", on.relabelFromFirstSample(hevc4k, data, data.size, 4)?.codecs)
        val p8 = sample(rpus.getValue("p8"))
        assertNull(on.relabelFromFirstSample(hevc4k, p8, p8.size, 4))
    }
}
