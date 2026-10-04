package com.nuvio.tv.ui.screens.player

import java.net.URI
import java.util.concurrent.atomic.AtomicLong

internal enum class PlaybackTransferCoverage(val label: String, val available: Boolean) {
    PROGRESSIVE("film data only", true),
    CHUNKED_PARTIAL("counted chunk payload · partial", true),
    UNAVAILABLE("unavailable", false)
}

internal data class PlaybackEndpoint(val scheme: String, val host: String, val port: Int) {
    val display: String get() = if (port == if (scheme == "https") 443 else 80) host else "$host:$port"
    companion object {
        fun from(url: String?): PlaybackEndpoint? = runCatching {
            val uri = URI(url ?: return null)
            val scheme = uri.scheme?.lowercase() ?: return null
            if (scheme != "http" && scheme != "https") return null
            val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
            val port = if (uri.port == -1) { if (scheme == "https") 443 else 80 } else uri.port
            if (port !in 1..65535) return null
            PlaybackEndpoint(scheme, host, port)
        }.getOrNull()
    }
}

internal data class PlaybackTransferSnapshot(
    val sessionId: Long,
    val sampledAtMs: Long,
    val coverage: PlaybackTransferCoverage,
    val networkBytes: Long,
    val readBytes: Long,
    val endpoint: PlaybackEndpoint?,
    val contentLength: Long?
)

/** Each factory retains its owner. Late callbacks can only update their old owner. */
internal class PlaybackTransferSession(
    val sourceUrl: String?,
    val coverage: PlaybackTransferCoverage
) {
    val id: Long = nextId.incrementAndGet()
    private var network = 0L
    private var read = 0L
    private var endpoint: PlaybackEndpoint? = null
    private var contentLength: Long? = null

    @Synchronized fun recordBytes(bytes: Int, isNetwork: Boolean) {
        if (!coverage.available || bytes <= 0) return
        fun add(value: Long) = if (Long.MAX_VALUE - value < bytes) Long.MAX_VALUE else value + bytes
        read = add(read)
        if (isNetwork) network = add(network)
    }

    @Synchronized fun recordEndpoint(url: String?) {
        if (coverage.available) PlaybackEndpoint.from(url)?.let { endpoint = it }
    }

    @Synchronized fun recordLength(requestedUrl: String, position: Long, length: Long, unbounded: Boolean, identityEncoded: Boolean) {
        if (!coverage.available || requestedUrl != sourceUrl || !unbounded || !identityEncoded ||
            position < 0 || length <= 0 || Long.MAX_VALUE - position < length) return
        contentLength = position + length
    }

    /** Read the clock inside the lock so the counters and time form one sample. */
    @Synchronized fun snapshot(clockMs: () -> Long): PlaybackTransferSnapshot = PlaybackTransferSnapshot(
        id, clockMs(), coverage, network, read, endpoint, contentLength)

    companion object { private val nextId = AtomicLong() }
}

internal data class PlaybackConnectSample(val sessionId: Long, val endpoint: PlaybackEndpoint, val elapsedMs: Long, val sampledAtMs: Long) {
    fun matches(snapshot: PlaybackTransferSnapshot): Boolean = sessionId == snapshot.sessionId &&
        endpoint == snapshot.endpoint && snapshot.sampledAtMs - sampledAtMs in 0..10_000L
}
