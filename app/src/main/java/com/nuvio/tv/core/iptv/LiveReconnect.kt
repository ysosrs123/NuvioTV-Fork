package com.nuvio.tv.core.iptv

import java.util.Locale

object LiveReconnect {
    private val DELAYS_MS = longArrayOf(0, 250, 1_000, 2_000)
    private val TRANSIENT_STATUS = setOf(500, 502, 504)
    const val MAX_WINDOW_MS = 15_000L
    const val RESERVE_MS = 750L
    const val STEADY_BYTES = 512L * 1024

    fun window(bufferedMs: Long): Long = (bufferedMs - RESERVE_MS).coerceIn(0, MAX_WINDOW_MS)

    fun delay(attempt: Int): Long = DELAYS_MS.getOrNull(attempt.coerceAtLeast(0)) ?: DELAYS_MS.last()

    fun next(attempt: Int, elapsedMs: Long, windowMs: Long): Long? {
        val wait = delay(attempt)
        return if (attempt <= 0 || elapsedMs + wait < windowMs) wait else null
    }

    fun handOver(httpStatus: Int): Boolean = httpStatus !in TRANSIENT_STATUS
}

object LiveTsSync {
    const val PACKET = 188
    const val SYNC = 0x47.toByte()

    fun find(bytes: ByteArray, from: Int, to: Int): Int {
        var index = from
        while (index + 2 * PACKET < to) {
            if (bytes[index] == SYNC && bytes[index + PACKET] == SYNC && bytes[index + 2 * PACKET] == SYNC) return index
            index++
        }
        return -1
    }

    fun ready(available: Int, emittedInPacket: Int): Int = ((available + emittedInPacket) / PACKET * PACKET - emittedInPacket).coerceAtLeast(0)
}

object LiveAltSvc {
    fun advertisesH3(header: String?): Boolean = header?.lowercase(Locale.ROOT)?.split(',')?.any { entry ->
        entry.trim().substringBefore('=').trim().let { it == "h3" || it.startsWith("h3-") }
    } == true
}

object LiveUserAgent {
    const val DEFAULT = "default"
    const val CUSTOM = "custom"
    private const val CUSTOM_PREFIX = "custom:"
    const val MAX_LENGTH = 200
    val PRESETS: Map<String, String> = linkedMapOf(
        "vlc" to "VLC/3.0.21 LibVLC/3.0.21",
        "kodi" to "Kodi/21.2 (Linux; Android 11) Android/11 Sys_CPU/aarch64 App_Bitness/64 Version/21.2",
        "okhttp" to "okhttp/4.12.0",
        "smarters" to "IPTVSmartersPro")

    fun custom(text: String): String? = clean(text)?.let { CUSTOM_PREFIX + it }

    fun kind(choice: String?): String = when {
        choice == null -> DEFAULT
        choice.startsWith(CUSTOM_PREFIX) -> CUSTOM
        choice in PRESETS -> choice
        else -> DEFAULT
    }

    fun customText(choice: String?): String? = choice?.takeIf { it.startsWith(CUSTOM_PREFIX) }?.removePrefix(CUSTOM_PREFIX)?.let(::clean)

    fun resolve(choice: String?): String? = when (kind(choice)) {
        DEFAULT -> null
        CUSTOM -> customText(choice)
        else -> PRESETS[choice]
    }

    fun pick(channel: String?, source: String?, fallback: String): String = channel ?: source ?: fallback

    private fun clean(text: String): String? = StreamHeaders.clean(text)?.takeIf { it.length <= MAX_LENGTH }
}
