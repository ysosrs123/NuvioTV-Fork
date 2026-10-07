package com.nuvio.tv.core.iptv

import java.net.URI
import java.security.MessageDigest
import java.util.Locale

enum class SniffedFormat { HLS, MPEG_TS }

object StreamFormatSniff {
    private val hlsTypes = setOf("application/vnd.apple.mpegurl", "application/x-mpegurl", "audio/mpegurl", "audio/x-mpegurl")
    private val tsTypes = setOf("video/mp2t", "video/mpeg", "video/mpegts", "video/m2ts")
    private val hlsWords = setOf("m3u8", "m3u", "hls")
    private val tsWords = setOf("ts", "mpegts", "mts", "m2ts")

    fun fromUrl(url: String): SniffedFormat? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val name = uri.rawPath.orEmpty().substringAfterLast('/').lowercase(Locale.ROOT)
        word(name.substringAfterLast('.', ""))?.let { return it }
        return uri.rawQuery.orEmpty().split('&').firstNotNullOfOrNull { part ->
            if (part.substringBefore('=').lowercase(Locale.ROOT) in setOf("output", "extension", "type", "format")) word(part.substringAfter('=', "").lowercase(Locale.ROOT)) else null
        }
    }

    fun fromContentType(value: String?): SniffedFormat? = when (value?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)) {
        in hlsTypes -> SniffedFormat.HLS
        in tsTypes -> SniffedFormat.MPEG_TS
        else -> null
    }

    fun fromBytes(bytes: ByteArray, length: Int = bytes.size): SniffedFormat? {
        val size = length.coerceAtMost(bytes.size)
        var start = if (size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0
        while (start < size && bytes[start].toInt().toChar().isWhitespace()) start++
        if (size - start >= 7 && String(bytes, start, 7, Charsets.US_ASCII) == "#EXTM3U") return SniffedFormat.HLS
        for (offset in 0 until minOf(TS_PACKET, size)) {
            val packets = minOf(3, (size - offset - 1) / TS_PACKET + 1)
            if (packets < 2 && !(offset == 0 && size <= TS_PACKET)) break
            if ((0 until packets).all { bytes[offset + it * TS_PACKET] == SYNC }) return SniffedFormat.MPEG_TS
        }
        return null
    }

    fun decide(bytes: ByteArray, length: Int, contentType: String?, finalUrl: String): SniffedFormat? =
        fromBytes(bytes, length) ?: fromContentType(contentType) ?: fromUrl(finalUrl)

    private fun word(value: String): SniffedFormat? = when (value) {
        in hlsWords -> SniffedFormat.HLS
        in tsWords -> SniffedFormat.MPEG_TS
        else -> null
    }

    private const val TS_PACKET = 188
    private const val SYNC = 0x47.toByte()
}

object HostKey {
    fun of(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() } ?: return null
        val port = uri.port.takeIf { it > 0 } ?: when (uri.scheme?.lowercase(Locale.ROOT)) { "https" -> 443; else -> 80 }
        return MessageDigest.getInstance("SHA-256").digest("$host:$port".toByteArray(Charsets.UTF_8))
            .take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
