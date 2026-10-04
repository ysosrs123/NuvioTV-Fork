package com.nuvio.tv.core.player

import android.util.Log
import androidx.media3.common.util.UnstableApi
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Synthetic-NAL tests for the in-band rewriter in
 * [DolbyVisionMatroskaTransformer.transformHevcSample].
 *
 * Runs on the JVM with no native bridge: RPU conversion therefore always
 * fails, which exercises the RPU drop path. The structural assertions
 * (unspec63 stripped, layer-id>0 stripped, base layer preserved byte-for-byte,
 * length-field integrity) are bridge-independent.
 */
@UnstableApi
class DolbyVisionMatroskaTransformerTest {

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<Throwable>()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // dvcC config record: [2] = profile << 1 -> profile 7; [3] = level bits.
    private val p7ConfigBytes = ByteArray(24).also {
        it[0] = 0x01
        it[1] = 0x00
        it[2] = 0x0E // profile 7
        it[3] = 0x30 // level 6
    }

    /** Builds a single HEVC NAL: 2-byte header + zero-filled payload. */
    private fun nal(type: Int, layerId: Int, totalSize: Int): ByteArray {
        require(totalSize >= 2)
        val out = ByteArray(totalSize)
        out[0] = (((type and 0x3F) shl 1) or ((layerId shr 5) and 0x01)).toByte()
        out[1] = (((layerId and 0x1F) shl 3) or 0x01).toByte()
        return out
    }

    /** Length-delimits NALs with 4-byte big-endian size fields. */
    private fun sampleOf(vararg nals: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (n in nals) {
            out.write((n.size ushr 24) and 0xFF)
            out.write((n.size ushr 16) and 0xFF)
            out.write((n.size ushr 8) and 0xFF)
            out.write(n.size and 0xFF)
            out.write(n)
        }
        return out.toByteArray()
    }

    /** Walks a length-delimited sample and returns the NAL type sequence. */
    private fun nalTypesOf(sample: ByteArray, length: Int): List<Int> {
        val types = mutableListOf<Int>()
        var pos = 0
        while (pos + 4 <= length) {
            var len = 0
            for (i in 0 until 4) len = (len shl 8) or (sample[pos + i].toInt() and 0xFF)
            pos += 4
            check(len > 0 && pos + len <= length) { "malformed output at $pos (len=$len)" }
            types += (sample[pos].toInt() ushr 1) and 0x3F
            pos += len
        }
        check(pos == length) { "trailing bytes after last NAL" }
        return types
    }

    private fun newTransformer() = DolbyVisionMatroskaTransformer(
        config = DolbyVisionConversionConfig(
            active = true,
            manualDv81 = true
        )
    )

    @Test
    fun `unspec63 EL wrappers are stripped from converted output`() {
        val transformer = newTransformer()
        val sample = sampleOf(
            nal(35, 0, 3),   // AUD
            nal(1, 0, 175),  // base-layer slice
            nal(63, 0, 9),   // EL wrapper (header)
            nal(63, 0, 74),  // EL wrapper (payload)
            nal(62, 0, 377)  // RPU (conversion fails on JVM -> dropped)
        )
        val result = transformer.transformHevcSample(sample, sample.size, 4, null, p7ConfigBytes)
        assertNotNull("transform must engage on a P7 sample", result)
        val outLen = transformer.lastTransformedSampleLength()
        assertEquals(
            "only AUD + base slice survive (t63 stripped, t62 dropped on JVM)",
            listOf(35, 1),
            nalTypesOf(result!!, outLen)
        )
    }

    @Test
    fun `layer-id EL NALs are still stripped`() {
        val transformer = newTransformer()
        val sample = sampleOf(
            nal(35, 0, 3),
            nal(1, 0, 100),
            nal(1, 1, 50) // classic layer-1 EL slice
        )
        val result = transformer.transformHevcSample(sample, sample.size, 4, null, p7ConfigBytes)
        assertNotNull(result)
        assertEquals(
            listOf(35, 1),
            nalTypesOf(result!!, transformer.lastTransformedSampleLength())
        )
    }

    @Test
    fun `base layer bytes are preserved verbatim`() {
        val transformer = newTransformer()
        val slice = nal(1, 0, 64).also { it.fill(0x5A, 2) }
        val sample = sampleOf(nal(35, 0, 3), slice, nal(63, 0, 40))
        val result = transformer.transformHevcSample(sample, sample.size, 4, null, p7ConfigBytes)
        assertNotNull(result)
        val out = result!!
        val outLen = transformer.lastTransformedSampleLength()
        // Second NAL of the output must be the untouched slice.
        var pos = 4 + 3 // skip AUD (4-byte length + 3-byte NAL)
        var len = 0
        for (i in 0 until 4) len = (len shl 8) or (out[pos + i].toInt() and 0xFF)
        pos += 4
        assertEquals(slice.size, len)
        for (i in 0 until len) {
            assertEquals("slice byte $i", slice[i], out[pos + i])
        }
        assertEquals(pos + len, outLen)
    }

    @Test
    fun `sample without DV NALs passes through unchanged`() {
        val transformer = newTransformer()
        val sample = sampleOf(nal(35, 0, 3), nal(1, 0, 200))
        val result = transformer.transformHevcSample(sample, sample.size, 4, null, p7ConfigBytes)
        assertNotNull(result)
        assertEquals(
            listOf(35, 1),
            nalTypesOf(result!!, transformer.lastTransformedSampleLength())
        )
        assertEquals(sample.size, transformer.lastTransformedSampleLength())
    }
}
