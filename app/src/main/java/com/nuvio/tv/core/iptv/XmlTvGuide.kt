package com.nuvio.tv.core.iptv

import java.io.InputStream
import java.io.FilterInputStream
import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

data class GuideTimestamp(val epochMillis: Long, val precisionDigits: Int, val raw: String) {
    val precise: Boolean get() = precisionDigits >= 12
}
data class LocalizedGuideText(val text: String, val language: String?)
data class GuideChannel(val externalId: String, val names: List<LocalizedGuideText>)
data class GuideProgramme(
    val channelExternalId: String,
    val start: GuideTimestamp,
    val stop: GuideTimestamp?,
    val titles: List<LocalizedGuideText>,
    val descriptions: List<LocalizedGuideText>,
) {
    val canSchedulePrecisely: Boolean get() = start.precise && stop?.precise == true
}
data class GuideParseSummary(val channels: Int, val programmes: Int, val rejectedProgrammes: Int)

fun guideQuarantineAccepted(acceptedProgrammes: Long, quarantinedProgrammes: Long): Boolean {
    require(acceptedProgrammes >= 0 && quarantinedProgrammes >= 0)
    return quarantinedProgrammes <= maxOf(16L, (acceptedProgrammes + quarantinedProgrammes) / 50)
}

fun mergeGuideChannel(existing: GuideChannel, duplicate: GuideChannel, maxNames: Int = 32): GuideChannel {
    require(existing.externalId == duplicate.externalId && maxNames > 0)
    return GuideChannel(existing.externalId, (existing.names + duplicate.names).distinct().take(maxNames))
}
data class GuideParseLimits(
    val expandedBytes: Long = 512L * 1024 * 1024,
    val depth: Int = 16,
    val textCharacters: Int = 32 * 1024,
    val channels: Int = 50_000,
    val programmes: Int = 2_000_000,
) {
    init { require(expandedBytes > 0 && depth > 0 && textCharacters > 0 && channels > 0 && programmes > 0) }
}

class XmlTvGuideParser(private val limits: GuideParseLimits = GuideParseLimits()) {
    fun parse(input: InputStream, channel: (GuideChannel) -> Unit, programme: (GuideProgramme) -> Unit): GuideParseSummary {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        try {
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
        } catch (unsupported: org.xmlpull.v1.XmlPullParserException) {
            if (parser.getFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL)) throw unsupported
        }
        parser.setInput(LimitedInput(input, limits.expandedBytes, limits.textCharacters * 4L + 64 * 1024), null)
        var channelCount = 0
        var programmeCount = 0
        var rejected = 0
        var recordId: String? = null
        var recordStart: GuideTimestamp? = null
        var recordStop: GuideTimestamp? = null
        var badStop = false
        var recordType: String? = null
        var textType: String? = null
        var textLanguage: String? = null
        var text = StringBuilder()
        val names = mutableListOf<LocalizedGuideText>()
        val descriptions = mutableListOf<LocalizedGuideText>()
        var rootSeen = false
        var rootEnded = false
        var recordTextCharacters = 0

        while (true) {
            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Guide import cancelled")
            when (parser.nextToken()) {
                XmlPullParser.END_DOCUMENT -> break
                XmlPullParser.DOCDECL -> {
                    val declaration = parser.text
                    require(!rootSeen && declaration != null && '[' !in declaration && declaration.trim().startsWith("tv")) { "XMLTV DTD declarations are not accepted" }
                }
                XmlPullParser.ENTITY_REF -> {
                    val known = parser.name in setOf("amp", "lt", "gt", "quot", "apos") || parser.name.startsWith("#")
                    require(if (known) parser.text != null else parser.name.matches(entityName)) { "XMLTV entity is not allowed" }
                    if (textType != null) {
                        text.append(if (known) parser.text else "&${parser.name};")
                        require(text.length <= limits.textCharacters) { "Guide text limit" }
                    }
                }
                XmlPullParser.START_TAG -> {
                    require(parser.depth <= limits.depth) { "Guide nesting limit" }
                    for (index in 0 until parser.attributeCount) require(parser.getAttributeValue(index).length <= limits.textCharacters) { "Guide attribute limit" }
                    if (parser.depth == 1) {
                        require(!rootSeen) { "Expected XMLTV root" }
                        if (parser.name != "tv" || !parser.namespace.isNullOrEmpty()) throw GuideFormatException(GuideFormatIssue.NOT_XMLTV)
                        rootSeen = true
                    } else if (parser.depth == 2 && parser.name in setOf("channel", "programme")) {
                        recordType = parser.name
                        recordId = parser.getAttributeValue(null, if (recordType == "channel") "id" else "channel")?.takeIf(String::isNotBlank)
                        recordStart = parser.getAttributeValue(null, "start")?.let(::parseTimestamp)
                        val stop = parser.getAttributeValue(null, "stop")
                        recordStop = stop?.let(::parseTimestamp)
                        badStop = stop != null && recordStop == null
                        names.clear(); descriptions.clear(); recordTextCharacters = 0
                    } else if (parser.depth == 3 && recordType != null && parser.name in setOf("display-name", "title", "desc")) {
                        textType = parser.name
                        textLanguage = parser.getAttributeValue(null, "lang")
                        text = StringBuilder()
                    }
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> if (textType != null) {
                    text.append(parser.text)
                    require(text.length <= limits.textCharacters) { "Guide text limit" }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.depth == 3 && parser.name == textType) {
                        val value = text.toString().trim()
                        recordTextCharacters += value.length
                        require(recordTextCharacters <= limits.textCharacters) { "Guide record text limit" }
                        if (value.isNotEmpty()) {
                            val localized = LocalizedGuideText(value, textLanguage)
                            if (textType == "desc") descriptions += localized else names += localized
                        }
                        textType = null
                    } else if (parser.depth == 2 && parser.name == recordType) {
                        if (recordType == "channel") {
                            require(++channelCount <= limits.channels) { "Guide channel limit" }
                            require(recordId != null) { "Missing guide channel ID" }
                            channel(GuideChannel(recordId, names.toList()))
                        } else {
                            require(++programmeCount <= limits.programmes) { "Guide programme limit" }
                            val start = recordStart
                            val stop = recordStop
                            if (recordId == null || start == null || badStop || (stop != null && stop.epochMillis <= start.epochMillis)) rejected++
                            else programme(GuideProgramme(recordId, start, stop, names.toList(), descriptions.toList()))
                        }
                        recordType = null
                    } else if (parser.depth == 1) rootEnded = true
                }
            }
        }
        require(rootSeen && rootEnded) { "Incomplete XMLTV" }
        return GuideParseSummary(channelCount, programmeCount - rejected, rejected)
    }

    private class LimitedInput(input: InputStream, private val maximum: Long, private val maximumRun: Long) : FilterInputStream(input) {
        private var consumed = 0L
        private var run = 0L
        private fun count(amount: Int): Int {
            if (amount > 0) { consumed += amount; require(consumed <= maximum) { "Expanded guide byte limit" } }
            return amount
        }
        private fun scan(byte: Int) {
            run = if (byte == '<'.code) 0 else run + 1
            require(run <= maximumRun) { "Guide token limit" }
        }
        override fun read(): Int = `in`.read().also { if (it >= 0) { count(1); scan(it) } }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = count(`in`.read(buffer, offset, length)).also { n ->
            for (index in offset until offset + n) scan(buffer[index].toInt() and 255)
        }
    }

    companion object {
        private val entityName = Regex("[A-Za-z][A-Za-z0-9]{0,31}")
        internal fun parseTimestamp(raw: String): GuideTimestamp? {
            val match = Regex("^(\\d{4}(?:\\d{2}){0,5})(?:\\s+([+-]\\d{4}|UTC|GMT))?$").matchEntire(raw.trim()) ?: return null
            val digits = match.groupValues[1]
            fun field(start: Int, end: Int, default: Int) = if (digits.length >= end) digits.substring(start, end).toInt() else default
            return try {
                val date = LocalDateTime.of(field(0, 4, 0), field(4, 6, 1), field(6, 8, 1), field(8, 10, 0), field(10, 12, 0), field(12, 14, 0))
                val zone = match.groupValues[2]
                val offset = if (zone.isEmpty() || zone == "UTC" || zone == "GMT") ZoneOffset.UTC else ZoneOffset.of(zone)
                GuideTimestamp(date.toInstant(offset).toEpochMilli(), digits.length, raw)
            } catch (_: DateTimeException) { null }
        }
    }
}
