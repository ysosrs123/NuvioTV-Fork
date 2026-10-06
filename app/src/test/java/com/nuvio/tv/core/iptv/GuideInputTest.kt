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
    private fun issue(bytes: ByteArray): GuideFormatIssue? = try { parseGuideInput(bytes.inputStream(), {}, {}); null } catch (error: GuideFormatException) { error.issue }
    private fun channels(bytes: ByteArray) = mutableListOf<GuideChannel>().also { parseGuideInput(bytes.inputStream(), it::add, {}) }.map { it.externalId }

    @Test fun byteOrderMarkLeadingWhitespaceAndDeclarationsAreAccepted() {
        val declared = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE tv SYSTEM \"xmltv.dtd\">\n$xml"
        for (bytes in listOf(byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + declared.toByteArray(), "\r\n  \t$declared".toByteArray(),
            byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + "\n\n$declared".toByteArray(), gzip("\n$declared"), gzipBytes(gzip(xml))))
            assertEquals(listOf("one"), channels(bytes))
    }
    @Test fun declaredSingleByteEncodingIsHonoured() {
        val latin = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><tv><channel id=\"caf\u00e9\"/></tv>".toByteArray(Charsets.ISO_8859_1)
        assertEquals(listOf("caf\u00e9"), channels(latin))
    }
    @Test fun utf16WithByteOrderMarkIsAccepted() {
        assertEquals(listOf("one"), channels("\uFEFF$xml".toByteArray(Charsets.UTF_16BE)))
        assertEquals(listOf("one"), channels(byteArrayOf(0xff.toByte(), 0xfe.toByte()) + xml.toByteArray(Charsets.UTF_16LE)))
    }
    @Test fun nonGuideBodiesNameTheirFormat() {
        assertEquals(GuideFormatIssue.EMPTY, issue(ByteArray(0)))
        assertEquals(GuideFormatIssue.EMPTY, issue(" \r\n ".toByteArray()))
        assertEquals(GuideFormatIssue.EMPTY, issue(gzip("")))
        assertEquals(GuideFormatIssue.HTML, issue("<!DOCTYPE html><html><body>Not found</body></html>".toByteArray()))
        assertEquals(GuideFormatIssue.HTML, issue("\n<HTML>\n<head><title>403</title></head></HTML>".toByteArray()))
        assertEquals(GuideFormatIssue.HTML, issue("<?xml version=\"1.0\"?><!-- gateway --><html xmlns=\"http://www.w3.org/1999/xhtml\"/>".toByteArray()))
        assertEquals(GuideFormatIssue.HTML, issue(gzip("<html><body>Error</body></html>")))
        assertEquals(GuideFormatIssue.JSON, issue("{\"user_info\":{\"auth\":0}}".toByteArray()))
        assertEquals(GuideFormatIssue.JSON, issue("[]".toByteArray()))
        assertEquals(GuideFormatIssue.ZIP, issue(byteArrayOf(0x50, 0x4b, 3, 4, 0, 0)))
        assertEquals(GuideFormatIssue.NOT_XML, issue("Account expired".toByteArray()))
        assertEquals(GuideFormatIssue.NOT_XMLTV, issue("<?xml version=\"1.0\"?><error>expired</error>".toByteArray()))
        assertEquals(GuideFormatIssue.NOT_XML, issue(gzipBytes(gzipBytes(gzip(xml)))))
    }
    @Test fun inputLargerThanTheOldFixedBudgetIsAcceptedUpToTheExpandedLimit() {
        val padding = ("<channel id=\"pad\"><display-name>" + "x".repeat(4000) + "</display-name></channel>").toByteArray()
        val parts = listOf("<tv>".toByteArray()) + List(17_000) { padding } + listOf("</tv>".toByteArray())
        assertTrue(parts.sumOf { it.size.toLong() } > 64L * 1024 * 1024)
        val big = java.io.SequenceInputStream(java.util.Collections.enumeration(parts.map { ByteArrayInputStream(it) }))
        assertEquals(17_000, parseGuideInput(big, {}, {}).channels)
        try { parseGuideInput(xml.byteInputStream(), {}, {}, maxInputBytes = 15); fail() }
        catch (error: GuideFormatException) { assertEquals(GuideFormatIssue.INPUT_LIMIT, error.issue) }
    }
    private fun gzipBytes(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()
}
