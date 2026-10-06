package com.nuvio.tv.core.iptv

import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URI

class RecordingStreamException(val failure: RecordingFailure) : IOException("Recording stream: $failure")

data class RecordingSegment(val sequence: Long, val address: URI, val durationMs: Long, val discontinuity: Boolean) {
    override fun toString() = "RecordingSegment(sequence=$sequence, address withheld)"
}

sealed interface RecordingPlaylist {
    data class Variants(val addresses: List<URI>) : RecordingPlaylist {
        override fun toString() = "Variants(${addresses.size})"
    }
    data class Media(val targetMs: Long, val mediaSequence: Long, val segments: List<RecordingSegment>, val ended: Boolean) : RecordingPlaylist {
        override fun toString() = "Media(segments=${segments.size}, ended=$ended)"
    }
}

object RecordingPlaylistParser {
    const val MAX_BYTES = 1024 * 1024
    private const val MAX_SEGMENTS = 4096
    private const val MAX_VARIANTS = 64
    private val FRAGMENTED = Regex("\\.(m4s|mp4|m4a|m4v|cmfv|cmfa|aac|ac3|ec3|mp3|vtt|webvtt)$", RegexOption.IGNORE_CASE)

    fun parse(text: String, base: URI): RecordingPlaylist {
        val lines = text.removePrefix("﻿").split('\n').map { it.trim() }
        if (lines.firstOrNull() != "#EXTM3U") fail(RecordingFailure.UNSUPPORTED_STREAM)
        if (lines.size > 100_000) fail(RecordingFailure.UNSUPPORTED_STREAM)
        val variants = mutableListOf<Pair<Long, URI>>()
        val segments = mutableListOf<RecordingSegment>()
        var target = 0L
        var sequence = 0L
        var sequenceSeen = false
        var pendingDuration: Long? = null
        var pendingVariant: Long? = null
        var discontinuity = false
        var ended = false
        for (line in lines.drop(1)) {
            if (line.isEmpty()) continue
            if (!line.startsWith("#")) {
                val address = resolve(base, line)
                when {
                    pendingVariant != null -> {
                        if (variants.size >= MAX_VARIANTS) fail(RecordingFailure.UNSUPPORTED_STREAM)
                        variants += pendingVariant to address
                        pendingVariant = null
                    }
                    else -> {
                        if (segments.size >= MAX_SEGMENTS) fail(RecordingFailure.UNSUPPORTED_STREAM)
                        if (FRAGMENTED.containsMatchIn(address.path.orEmpty())) fail(RecordingFailure.UNSUPPORTED_STREAM)
                        segments += RecordingSegment(sequence + segments.size, address, pendingDuration ?: 0, discontinuity)
                        pendingDuration = null
                        discontinuity = false
                    }
                }
                continue
            }
            val tag = line.substringBefore(':')
            val value = line.substringAfter(':', "")
            when (tag) {
                "#EXT-X-STREAM-INF" -> pendingVariant = attribute(value, "BANDWIDTH")?.toLongOrNull() ?: 0L
                "#EXT-X-KEY" -> if (attribute(value, "METHOD")?.uppercase() != "NONE") fail(RecordingFailure.ENCRYPTED_STREAM)
                "#EXT-X-SESSION-KEY" -> if (attribute(value, "METHOD")?.uppercase() != "NONE") fail(RecordingFailure.ENCRYPTED_STREAM)
                "#EXT-X-MAP", "#EXT-X-BYTERANGE", "#EXT-X-PART", "#EXT-X-PRELOAD-HINT" -> fail(RecordingFailure.UNSUPPORTED_STREAM)
                "#EXT-X-TARGETDURATION" -> target = (value.toLongOrNull() ?: fail(RecordingFailure.UNSUPPORTED_STREAM)).coerceIn(1, 120) * 1000
                "#EXT-X-MEDIA-SEQUENCE" -> {
                    if (segments.isNotEmpty() || sequenceSeen) fail(RecordingFailure.UNSUPPORTED_STREAM)
                    sequence = value.toLongOrNull()?.takeIf { it >= 0 && it < Long.MAX_VALUE / 2 } ?: fail(RecordingFailure.UNSUPPORTED_STREAM)
                    sequenceSeen = true
                }
                "#EXTINF" -> pendingDuration = duration(value.substringBefore(','))
                "#EXT-X-DISCONTINUITY" -> discontinuity = true
                "#EXT-X-ENDLIST" -> ended = true
            }
        }
        if (variants.isNotEmpty()) {
            if (segments.isNotEmpty()) fail(RecordingFailure.UNSUPPORTED_STREAM)
            return RecordingPlaylist.Variants(variants.sortedByDescending { it.first }.map { it.second })
        }
        if (segments.isEmpty() && !ended) fail(RecordingFailure.UNSUPPORTED_STREAM)
        val longest = segments.maxOfOrNull { it.durationMs } ?: 0
        return RecordingPlaylist.Media(if (target > 0) target else maxOf(longest, 1000).coerceAtMost(120_000), sequence, segments.toList(), ended)
    }

    private fun resolve(base: URI, line: String): URI {
        val address = try { base.resolve(line.replace(" ", "%20").replace("|", "%7C")) } catch (_: Exception) { fail(RecordingFailure.UNSUPPORTED_STREAM) }
        if (address.scheme?.lowercase() !in setOf("http", "https") || address.host.isNullOrEmpty() || address.rawUserInfo != null) {
            fail(RecordingFailure.UNSUPPORTED_STREAM)
        }
        return address
    }

    private fun duration(value: String): Long = try {
        BigDecimal(value.trim()).multiply(BigDecimal(1000)).setScale(0, RoundingMode.HALF_UP).longValueExact().coerceIn(0, 600_000)
    } catch (_: Exception) { 0 }

    internal fun attribute(list: String, name: String): String? {
        var index = 0
        while (index < list.length) {
            val equals = list.indexOf('=', index).takeIf { it > 0 } ?: return null
            val key = list.substring(index, equals).trim()
            var end: Int
            val value: String
            if (equals + 1 < list.length && list[equals + 1] == '"') {
                val close = list.indexOf('"', equals + 2).takeIf { it >= 0 } ?: return null
                value = list.substring(equals + 2, close)
                end = close + 1
            } else {
                end = list.indexOf(',', equals + 1).let { if (it < 0) list.length else it }
                value = list.substring(equals + 1, end).trim()
            }
            if (key.equals(name, ignoreCase = true)) return value
            end = list.indexOf(',', end).let { if (it < 0) list.length else it }
            index = end + 1
        }
        return null
    }

    private fun fail(failure: RecordingFailure): Nothing = throw RecordingStreamException(failure)
}

class RecordingSegmentCursor(private val liveEdgeSegments: Int = 3) {
    init { require(liveEdgeSegments > 0) }
    data class Step(val segments: List<RecordingSegment>, val gap: Boolean)
    var lastSequence: Long? = null
        private set

    fun next(playlist: RecordingPlaylist.Media): Step {
        val segments = playlist.segments
        val last = lastSequence ?: return Step(segments.takeLast(liveEdgeSegments), false)
        if (segments.isEmpty()) return Step(emptyList(), false)
        if (segments.last().sequence + maxOf(segments.size * 2L, 10L) < last) return Step(segments.takeLast(liveEdgeSegments), true)
        val newer = segments.filter { it.sequence > last }
        return Step(newer, newer.firstOrNull()?.let { it.sequence > last + 1 } == true)
    }

    fun appended(segment: RecordingSegment) {
        lastSequence = segment.sequence
    }

    fun restart() {
        lastSequence = null
    }
}
