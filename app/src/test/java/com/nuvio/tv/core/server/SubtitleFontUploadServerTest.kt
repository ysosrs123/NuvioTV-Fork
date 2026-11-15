package com.nuvio.tv.core.server

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleFontUploadServerTest {

    @Test
    fun `upload key is long, random and checked exactly`() {
        val key = SubtitleFontUploadServer.newKey()
        assertEquals(32, key.length)
        assertNotEquals(key, SubtitleFontUploadServer.newKey())
        assertTrue(SubtitleFontUploadServer.isKeyValid(key, key))
        assertFalse(SubtitleFontUploadServer.isKeyValid(key, null))
        assertFalse(SubtitleFontUploadServer.isKeyValid(key, ""))
        assertFalse(SubtitleFontUploadServer.isKeyValid(key, key.dropLast(1)))
        assertFalse(SubtitleFontUploadServer.isKeyValid("", ""))
    }

    @Test
    fun `body is read whole when it matches the declared length`() {
        val body = ByteArray(200_000) { (it % 251).toByte() }
        assertArrayEquals(body, SubtitleFontUploadServer.readBounded(ByteArrayInputStream(body), body.size.toLong(), 1_000_000))
    }

    @Test
    fun `a short body or a length over the cap is refused`() {
        assertNull(SubtitleFontUploadServer.readBounded(ByteArrayInputStream(ByteArray(10)), 20, 1_000))
        assertNull(SubtitleFontUploadServer.readBounded(ByteArrayInputStream(ByteArray(10)), 2_000, 1_000))
        assertNull(SubtitleFontUploadServer.readBounded(ByteArrayInputStream(ByteArray(0)), 0, 1_000))
    }

    @Test
    fun `a claimed large body that sends nothing allocates only one chunk`() {
        var reads = 0
        val silent = object : InputStream() {
            override fun read(): Int = -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                reads++
                assertTrue(len <= 64 * 1024)
                return -1
            }
        }
        assertNull(SubtitleFontUploadServer.readBounded(silent, 10L * 1024 * 1024, 10 * 1024 * 1024))
        assertEquals(1, reads)
    }
}
