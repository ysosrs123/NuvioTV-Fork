package com.nuvio.tv.core.iptv

import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

enum class HlsCaptureFailure { INVALID_PLAYLIST, UNSUPPORTED_PLAYLIST, ADDRESS, LIMIT, CHANGED_SEGMENT,
    EXPIRED, STALLED, CLOSED, HTTP, NETWORK }
class HlsCaptureException(val failure: HlsCaptureFailure) : IOException("HLS capture: $failure")

data class HlsCaptureSegment(val sequence: Long, val durationMs: Long, val discontinuity: Long,
    val address: URI) {
    override fun toString() = "HlsCaptureSegment(sequence=$sequence, address withheld)"
}
data class HlsCapturePlaylist(val targetMs: Long, val mediaSequence: Long,
    val segments: List<HlsCaptureSegment>, val ended: Boolean) {
    override fun toString() = "HlsCapturePlaylist(segments=${segments.size}, ended=$ended)"
}

class HlsCapturePlaylistParser(val maxBytes: Int = 256 * 1024, private val maxSegments: Int = 1024) {
    init { require(maxBytes in 1..1024 * 1024 && maxSegments in 1..4096) }

    fun parse(bytes: ByteArray, address: URI): HlsCapturePlaylist {
        try {
            if (bytes.size > maxBytes) fail(HlsCaptureFailure.LIMIT)
            validateAddress(address)
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            if (text.any { it == '\uFEFF' || (it.code < 32 && it !in "\r\n") || it.code == 127 }) fail()
            val lines = text.split('\n')
            if (lines.size > 8192 || lines.any { it.length > 8192 }) fail(HlsCaptureFailure.LIMIT)
            if (lines.firstOrNull()?.trimEnd('\r') != "#EXTM3U") fail()
            var target: Long? = null
            var firstSequence = 0L
            var discontinuity = 0L
            var pending: Long? = null
            var ended = false
            val seen = mutableSetOf<String>()
            val segments = mutableListOf<HlsCaptureSegment>()
            fun unique(tag: String) { if (!seen.add(tag)) fail() }
            for (raw in lines.drop(1)) {
                val line = raw.trimEnd('\r')
                if (line.isEmpty()) continue
                if (line != line.trim()) fail()
                if (ended) fail()
                if (!line.startsWith('#')) {
                    val duration = pending ?: fail()
                    if (segments.size >= maxSegments) fail(HlsCaptureFailure.LIMIT)
                    val resolved = address.resolve(line)
                    validateAddress(resolved)

                    if (!sameOrigin(address, resolved)) fail(HlsCaptureFailure.ADDRESS)
                    val sequence = Math.addExact(firstSequence, segments.size.toLong())
                    if (sequence == Long.MAX_VALUE) fail(HlsCaptureFailure.LIMIT)
                    segments += HlsCaptureSegment(sequence, duration, discontinuity, resolved)
                    pending = null
                    continue
                }
                val tag = line.substringBefore(':')
                val value = line.substringAfter(':', "")
                when (tag) {
                    "#EXTM3U" -> fail()
                    "#EXT-X-TARGETDURATION" -> {
                        unique(tag); target = integer(value).also { if (it !in 1..120) fail() } * 1000
                    }
                    "#EXT-X-MEDIA-SEQUENCE" -> {
                        unique(tag); if (segments.isNotEmpty() || pending != null) fail()
                        firstSequence = integer(value)
                    }
                    "#EXT-X-DISCONTINUITY-SEQUENCE" -> {
                        unique(tag); if (segments.isNotEmpty() || pending != null || "discontinuity" in seen) fail()
                        discontinuity = integer(value)
                    }
                    "#EXT-X-DISCONTINUITY" -> {
                        if (line != tag || pending != null) fail()
                        seen += "discontinuity"; discontinuity = Math.addExact(discontinuity, 1)
                    }
                    "#EXTINF" -> {
                        if (pending != null || ',' !in value) fail()
                        val duration = value.substringBefore(',')
                        if (!duration.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,9})?"))) fail()
                        pending = BigDecimal(duration).multiply(BigDecimal(1000))
                            .setScale(0, RoundingMode.HALF_UP).longValueExact().also { if (it <= 0) fail() }
                    }
                    "#EXT-X-ENDLIST" -> { unique(tag); if (line != tag || pending != null) fail(); ended = true }
                    "#EXT-X-VERSION" -> { unique(tag); if (integer(value) !in 1..7) fail(HlsCaptureFailure.UNSUPPORTED_PLAYLIST) }
                    "#EXT-X-PLAYLIST-TYPE" -> { unique(tag); if (value !in setOf("EVENT", "VOD")) fail() }
                    "#EXT-X-INDEPENDENT-SEGMENTS" -> { unique(tag); if (line != tag) fail() }
                    "#EXT-X-PROGRAM-DATE-TIME" -> Unit
                    else -> if (tag.startsWith("#EXT")) fail(HlsCaptureFailure.UNSUPPORTED_PLAYLIST)
                }
            }
            val duration = target ?: fail()
            if (pending != null || segments.isEmpty()) fail()
            if (segments.any { (it.durationMs + 500) / 1000 > duration / 1000 }) fail()
            if ("#EXT-X-PLAYLIST-TYPE" in seen && text.contains("#EXT-X-PLAYLIST-TYPE:VOD") && !ended) fail()
            return HlsCapturePlaylist(duration, firstSequence, segments.toList(), ended)
        } catch (error: HlsCaptureException) { throw error }
        catch (_: Exception) { fail() }
    }

    private fun integer(value: String): Long {
        if (!value.matches(Regex("[0-9]{1,19}"))) fail()
        return value.toLongOrNull() ?: fail(HlsCaptureFailure.LIMIT)
    }
    internal fun validateAddress(address: URI) {
        if (address.scheme !in setOf("http", "https") || address.host == null ||
            address.rawUserInfo != null || address.rawFragment != null || address.toString().length > 8192 ||
            address.port !in -1..65535 || address.port == 0) fail(HlsCaptureFailure.ADDRESS)
    }
    private fun sameOrigin(a: URI, b: URI): Boolean {
        fun port(u: URI) = if (u.port != -1) u.port else if (u.scheme == "https") 443 else 80
        return a.scheme == b.scheme && a.host.equals(b.host, ignoreCase = true) && port(a) == port(b)
    }
    private fun fail(reason: HlsCaptureFailure = HlsCaptureFailure.INVALID_PLAYLIST): Nothing = throw HlsCaptureException(reason)
}
