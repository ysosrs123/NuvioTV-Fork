package com.nuvio.tv.core.iptv

import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.net.URI
import java.nio.charset.CodingErrorAction

/** Metadata only. Parsing never opens channel, logo, guide or catch-up URLs. */
data class PlaylistChannel(
    val name: String,
    val locator: String,
    val attributes: Map<String, String>,
) {
    val guideId: String? get() = attributes["tvg-id"]?.takeIf { it.isNotBlank() }
    val group: String? get() = attributes["group-title"]?.takeIf { it.isNotBlank() }
    // Playback locators can contain credentials; never expose them in routine logging.
    override fun toString(): String = "PlaylistChannel(metadata withheld)"
}

enum class PlaylistKind { CATALOGUE, HLS, INVALID }
enum class PlaylistIssue {
    MISSING_HEADER, MALFORMED_RECORD, INVALID_LOCATOR, MISSING_LOCATOR,
    UNSUPPORTED_EXTENSION, LIMIT_EXCEEDED, INVALID_ENCODING, EMPTY_CATALOGUE,
}
data class PlaylistDiagnostic(val line: Int, val issue: PlaylistIssue)
data class PlaylistCatalogue(
    val kind: PlaylistKind,
    val channels: List<PlaylistChannel>,
    val diagnostics: List<PlaylistDiagnostic>,
    val guideUrls: List<String> = emptyList(),
) {
    val canPublish: Boolean get() = kind == PlaylistKind.CATALOGUE && channels.isNotEmpty() && diagnostics.isEmpty()
}

data class PlaylistLimits(
    val maxCharacters: Int = 8 * 1024 * 1024,
    val maxLineCharacters: Int = 16 * 1024,
    val maxChannels: Int = 20_000,
    val maxDiagnostics: Int = 100,
) {
    init {
        require(maxCharacters > 0 && maxLineCharacters > 0 && maxChannels > 0 && maxDiagnostics > 0)
    }
}

/** Bounded staging parser; only a complete, unambiguous catalogue is publishable. */
class PlaylistCatalogueParser(private val limits: PlaylistLimits = PlaylistLimits()) {
    fun parse(input: InputStream, finalResponseUri: URI? = null): PlaylistCatalogue =
        parse(InputStreamReader(input, Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)), finalResponseUri)

    fun parse(input: Reader, finalResponseUri: URI? = null): PlaylistCatalogue {
        val channels = mutableListOf<PlaylistChannel>()
        val issues = mutableListOf<PlaylistDiagnostic>()
        var header = false
        var sawHls = false
        var lineNumber = 0
        var pending: Pair<String, Map<String, String>>? = null
        var defaults = emptyMap<String, String>()
        var guideUrls = emptyList<String>()
        fun issue(reason: PlaylistIssue) {
            if (issues.size >= limits.maxDiagnostics) throw LimitExceeded()
            issues += PlaylistDiagnostic(lineNumber, reason)
        }
        try {
            val reader = BoundedLines(input, limits)
            while (true) {
                val raw = reader.next() ?: break
                lineNumber++
                val line = (if (lineNumber == 1) raw.removePrefix("\uFEFF") else raw).trim()
                if (line.isEmpty()) continue
                if (!header) {
                    if (line != "#EXTM3U" && !line.startsWith("#EXTM3U ") && !line.startsWith("#EXTM3U\t")) {
                        return PlaylistCatalogue(PlaylistKind.INVALID, emptyList(), listOf(PlaylistDiagnostic(lineNumber, PlaylistIssue.MISSING_HEADER)))
                    }
                    defaults = attributes(line.removePrefix("#EXTM3U")) ?: run {
                        issue(PlaylistIssue.MALFORMED_RECORD)
                        emptyMap()
                    }
                    guideUrls = listOfNotNull(defaults["url-tvg"], defaults["x-tvg-url"])
                        .flatMap { it.split(',') }.mapNotNull { resolveHttp(it.trim(), finalResponseUri) }.distinct()
                    header = true
                    continue
                }
                if (line.startsWith("#EXT-X-")) {
                    sawHls = true
                    continue
                }
                if (line.startsWith("#EXTINF:")) {
                    if (pending != null) issue(PlaylistIssue.MISSING_LOCATOR)
                    pending = null
                    val body = line.substringAfter(':')
                    val comma = outsideQuoteComma(body)
                    if (comma < 0) { issue(PlaylistIssue.MALFORMED_RECORD); continue }
                    val prefix = body.substring(0, comma).trim()
                    val duration = prefix.takeWhile { !it.isWhitespace() }
                    val attrs = attributes(prefix.removePrefix(duration))
                    val name = body.substring(comma + 1).trim()
                    if (duration.toDoubleOrNull()?.isFinite() != true || attrs == null || name.isEmpty()) {
                        issue(PlaylistIssue.MALFORMED_RECORD)
                        continue
                    }
                    val inherited = defaults.filterKeys { it in catchupAttributes } + attrs
                    pending = name to inherited
                } else if (line.startsWith("#EXTGRP:")) {
                    pending = pending?.let { (name, attrs) ->
                        name to if ("group-title" in attrs) attrs else attrs + ("group-title" to line.substringAfter(':'))
                    }
                } else if (line.startsWith("#EXTVLCOPT:") || line.startsWith("#KODIPROP:")) {
                    // Do not silently drop authentication/header semantics and publish broken channels.
                    issue(PlaylistIssue.UNSUPPORTED_EXTENSION)
                } else if (!line.startsWith('#')) {
                    val record = pending
                    pending = null
                    if (record == null) { issue(PlaylistIssue.MALFORMED_RECORD); continue }
                    val locator = resolveHttp(line, finalResponseUri)
                    if (locator == null) { issue(PlaylistIssue.INVALID_LOCATOR); continue }
                    if (channels.size >= limits.maxChannels) throw LimitExceeded()
                    channels += PlaylistChannel(record.first, locator, record.second)
                }
            }
            if (pending != null) issue(PlaylistIssue.MISSING_LOCATOR)
        } catch (_: LimitExceeded) {
            return PlaylistCatalogue(PlaylistKind.INVALID, emptyList(), listOf(PlaylistDiagnostic(lineNumber, PlaylistIssue.LIMIT_EXCEEDED)))
        } catch (_: java.nio.charset.CharacterCodingException) {
            return PlaylistCatalogue(PlaylistKind.INVALID, emptyList(), listOf(PlaylistDiagnostic(lineNumber, PlaylistIssue.INVALID_ENCODING)))
        }
        // HLS evidence anywhere invalidates every staged catalogue row, including late evidence.
        if (sawHls) return PlaylistCatalogue(PlaylistKind.HLS, emptyList(), emptyList())
        if (!header) return PlaylistCatalogue(PlaylistKind.INVALID, emptyList(), listOf(PlaylistDiagnostic(0, PlaylistIssue.MISSING_HEADER)))
        if (channels.isEmpty() && issues.isEmpty()) issues += PlaylistDiagnostic(lineNumber, PlaylistIssue.EMPTY_CATALOGUE)
        return PlaylistCatalogue(PlaylistKind.CATALOGUE, channels.toList(), issues.toList(), guideUrls)
    }

    private fun outsideQuoteComma(text: String): Int {
        var quoted = false
        text.forEachIndexed { index, char ->
            if (char == '"') quoted = !quoted
            if (char == ',' && !quoted) return index
        }
        return -1
    }

    private fun attributes(text: String): Map<String, String>? {
        val result = linkedMapOf<String, String>()
        var i = 0
        while (i < text.length) {
            while (i < text.length && text[i].isWhitespace()) i++
            if (i == text.length) break
            val start = i
            while (i < text.length && (text[i].isLetterOrDigit() || text[i] in "-_")) i++
            if (i == start) return null
            val name = text.substring(start, i).lowercase(java.util.Locale.ROOT)
            while (i < text.length && text[i].isWhitespace()) i++
            if (i == text.length || text[i++] != '=') return null
            while (i < text.length && text[i].isWhitespace()) i++
            val value: String
            if (i < text.length && text[i] == '"') {
                val valueStart = ++i
                while (i < text.length && text[i] != '"') i++
                if (i == text.length) return null
                value = text.substring(valueStart, i++)
                if (i < text.length && !text[i].isWhitespace()) return null
            } else {
                val valueStart = i
                while (i < text.length && !text[i].isWhitespace()) i++
                value = text.substring(valueStart, i)
            }
            if (name in result && result[name] != value) return null
            result[name] = value
        }
        return result
    }

    private class LimitExceeded : RuntimeException()
    private class BoundedLines(input: Reader, private val limits: PlaylistLimits) {
        private val reader = input.buffered()
        private var count = 0
        fun next(): String? {
            val line = StringBuilder()
            while (true) {
                val next = reader.read()
                if (next < 0) return if (line.isEmpty()) null else line.toString()
                if (++count > limits.maxCharacters) throw LimitExceeded()
                if (next == '\n'.code) return line.toString().removeSuffix("\r")
                if (line.length >= limits.maxLineCharacters) throw LimitExceeded()
                line.append(next.toChar())
            }
        }
    }

    companion object {
        private val catchupAttributes = setOf("catchup", "catchup-source", "catchup-days", "catchup-correction")
        internal fun resolveHttp(value: String, base: URI?): String? = try {
            val uri = URI(value)
            val resolved = if (uri.isAbsolute) uri else base?.resolve(uri)
            resolved?.takeIf {
                it.scheme?.lowercase(java.util.Locale.ROOT) in setOf("http", "https") &&
                    !it.host.isNullOrBlank() && it.rawFragment == null
            }?.toASCIIString()
        } catch (_: Exception) { null }
    }
}
