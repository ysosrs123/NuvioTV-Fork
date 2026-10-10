package com.nuvio.tv.core.iptv

import java.io.FilterInputStream
import java.io.InputStream
import java.io.PushbackInputStream
import java.util.Locale
import java.util.zip.GZIPInputStream

enum class GuideFormatIssue { EMPTY, HTML, JSON, ZIP, NOT_XML, NOT_XMLTV, INPUT_LIMIT }
class GuideFormatException(val issue: GuideFormatIssue) : IllegalArgumentException("Guide format $issue")

fun parseGuideInput(
    input: InputStream,
    channel: (GuideChannel) -> Unit,
    programme: (GuideProgramme) -> Unit,
    limits: GuideParseLimits = GuideParseLimits(),
    maxInputBytes: Long = limits.expandedBytes,
    checkCancellation: () -> Unit = {},
    rejected: (String?, Long?) -> Unit = { _, _ -> },
    wants: ((String) -> Boolean)? = null,
): GuideParseSummary {
    require(maxInputBytes > 0)
    val bounded = object : FilterInputStream(input) {
        var count = 0L
        override fun read(): Int { checkCancellation(); return `in`.read().also { if (it >= 0) count(1) } }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            checkCancellation()
            return `in`.read(bytes, offset, minOf(length.toLong(), maxInputBytes - count + 1).toInt()).also { if (it > 0) count(it) }
        }
        fun count(amount: Int) { count += amount; if (count > maxInputBytes) throw GuideFormatException(GuideFormatIssue.INPUT_LIMIT) }
        override fun close() = Unit
    }
    return detectGuideDocument(bounded).use { XmlTvGuideParser(limits).parse(it, { checkCancellation(); channel(it) }, { checkCancellation(); programme(it) }, wants, rejected) }
}

internal fun detectGuideDocument(input: InputStream, maxGzipLayers: Int = 2): InputStream {
    var stream = PushbackInputStream(input, PEEK_BYTES)
    var layers = 0
    while (true) {
        val head = peek(stream, PEEK_BYTES)
        if (head.size >= 2 && head[0] == 0x1f.toByte() && head[1] == 0x8b.toByte()) {
            if (++layers > maxGzipLayers) throw GuideFormatException(GuideFormatIssue.NOT_XML)
            stream = PushbackInputStream(GZIPInputStream(stream), PEEK_BYTES)
            continue
        }
        if (head.isEmpty()) throw GuideFormatException(GuideFormatIssue.EMPTY)
        if (head.size >= 2 && ((head[0] == 0xfe.toByte() && head[1] == 0xff.toByte()) || (head[0] == 0xff.toByte() && head[1] == 0xfe.toByte()))) return stream
        var start = if (head.size >= 3 && head[0] == 0xef.toByte() && head[1] == 0xbb.toByte() && head[2] == 0xbf.toByte()) 3 else 0
        while (start < head.size && head[start].toInt().toChar() in " \t\r\n") start++
        if (start == head.size) {
            if (head.size < PEEK_BYTES) throw GuideFormatException(GuideFormatIssue.EMPTY)
            throw GuideFormatException(GuideFormatIssue.NOT_XML)
        }
        val text = String(head, start, head.size - start, Charsets.ISO_8859_1).lowercase(Locale.ROOT)
        when {
            text.startsWith("pk\u0003\u0004") -> throw GuideFormatException(GuideFormatIssue.ZIP)
            text.startsWith("{") || text.startsWith("[") -> throw GuideFormatException(GuideFormatIssue.JSON)
            !text.startsWith("<") -> throw GuideFormatException(GuideFormatIssue.NOT_XML)
            htmlStart.containsMatchIn(text) -> throw GuideFormatException(GuideFormatIssue.HTML)
        }
        stream.skip(start.toLong())
        return stream
    }
}

private const val PEEK_BYTES = 1024
private val htmlStart = Regex("^(<\\?xml[^>]*>\\s*)?(<!--.*?-->\\s*)*(<!doctype\\s+html|<html[\\s>]|<head[\\s>]|<body[\\s>])", RegexOption.DOT_MATCHES_ALL)

private fun peek(stream: PushbackInputStream, size: Int): ByteArray {
    val buffer = ByteArray(size)
    var filled = 0
    while (filled < size) {
        val count = stream.read(buffer, filled, size - filled)
        if (count < 0) break
        filled += count
    }
    stream.unread(buffer, 0, filled)
    return buffer.copyOf(filled)
}
