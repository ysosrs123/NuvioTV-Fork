package com.nuvio.tv.core.iptv

import java.io.FilterInputStream
import java.io.InputStream
import java.io.PushbackInputStream
import java.util.zip.GZIPInputStream

fun parseGuideInput(
    input: InputStream,
    channel: (GuideChannel) -> Unit,
    programme: (GuideProgramme) -> Unit,
    limits: GuideParseLimits = GuideParseLimits(),
    maxInputBytes: Long = 64L * 1024 * 1024,
    checkCancellation: () -> Unit = {},
): GuideParseSummary {
    require(maxInputBytes > 0)
    val bounded = object : FilterInputStream(input) {
        var count = 0L
        override fun read(): Int { checkCancellation(); return `in`.read().also { if (it >= 0) count(1) } }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            checkCancellation()
            return `in`.read(bytes, offset, minOf(length.toLong(), maxInputBytes - count + 1).toInt()).also { if (it > 0) count(it) }
        }
        fun count(amount: Int) { count += amount; require(count <= maxInputBytes) { "Guide input byte limit" } }
        override fun close() = Unit
    }
    val peek = PushbackInputStream(bounded, 2)
    val first = peek.read(); val second = peek.read()
    if (second >= 0) peek.unread(second)
    if (first >= 0) peek.unread(first)
    val decoded = if (first == 0x1f && second == 0x8b) GZIPInputStream(peek) else peek
    return decoded.use { XmlTvGuideParser(limits).parse(it, { checkCancellation(); channel(it) }, { checkCancellation(); programme(it) }) }
}
