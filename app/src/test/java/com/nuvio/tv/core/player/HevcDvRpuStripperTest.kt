package com.nuvio.tv.core.player

import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@UnstableApi
class HevcDvRpuStripperTest {
    private fun nal(type: Int, layerId: Int = 0, payload: Int = 3): ByteArray {
        val body = ByteArray(2 + payload) { 0x11 }
        body[0] = ((type shl 1) or (layerId ushr 5)).toByte()
        body[1] = (((layerId and 0x1F) shl 3) or 1).toByte()
        val size = body.size
        return byteArrayOf((size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte()) + body
    }

    @Test
    fun `strip keeps only the base layer`() {
        val base = nal(1)
        val sample = base + nal(62) + nal(63) + nal(1, layerId = 1)
        val out = ExposedByteArrayOutputStream(sample.size)
        assertTrue(HevcDvRpuStripper.stripRpuLengthDelimited(sample, sample.size, 4, out))
        assertArrayEquals(base, out.toByteArray())
    }
}
