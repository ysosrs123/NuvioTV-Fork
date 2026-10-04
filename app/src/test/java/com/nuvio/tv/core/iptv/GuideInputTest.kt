package com.nuvio.tv.core.iptv

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InterruptedIOException
import java.util.zip.GZIPOutputStream
import org.junit.Assert.*
import org.junit.Test

class GuideInputTest {
    private val xml = "<tv><channel id=\"one\"><display-name>One</display-name></channel></tv>"
    private fun gzip(text: String): ByteArray = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(text.toByteArray()) } }.toByteArray()
    @Test fun plainAndGzipHaveIdenticalResultsAndDoNotCloseCallerStream() {
        for (bytes in listOf(xml.toByteArray(), gzip(xml))) {
            var closed = false
            val input = object : ByteArrayInputStream(bytes) { override fun close() { closed = true; super.close() } }
            val channels = mutableListOf<GuideChannel>()
            assertEquals(1, parseGuideInput(input, channels::add, {}).channels)
            assertEquals("one", channels.single().externalId)
            assertFalse(closed)
        }
    }
    @Test fun compressedExpansionIsBoundedIndependentlyOfDownloadBytes() {
        val huge = "<tv><channel id=\"one\"><display-name>" + "x".repeat(8000) + "</display-name></channel></tv>"
        assertThrows(IllegalArgumentException::class.java) { parseGuideInput(gzip(huge).inputStream(), {}, {}, GuideParseLimits(expandedBytes = 500), maxInputBytes = 500) }
    }
    @Test fun rawDownloadBudgetRejectsBeforeAcceptingTheFeed() {
        assertThrows(IllegalArgumentException::class.java) { parseGuideInput(xml.byteInputStream(), {}, {}, maxInputBytes = 15) }
    }
    @Test fun corruptGzipTrailerCannotProduceASuccessfulImport() {
        val bytes = gzip(xml).let { it.copyOf(it.size - 4) }
        assertThrows(Exception::class.java) { parseGuideInput(bytes.inputStream(), {}, {}) }
    }
    @Test fun cancellationInterruptsStreamingReads() {
        assertThrows(InterruptedIOException::class.java) { parseGuideInput(xml.byteInputStream(), {}, {}, checkCancellation = { throw InterruptedIOException("fixture") }) }
    }
}
