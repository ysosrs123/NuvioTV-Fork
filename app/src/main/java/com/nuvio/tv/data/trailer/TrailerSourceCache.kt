package com.nuvio.tv.data.trailer

import com.google.gson.JsonParser
import java.net.URI
import java.net.URLDecoder
import java.time.Clock
import java.util.Base64

internal class TrailerSourceCache(private val clock: Clock = Clock.systemUTC(), private val capacity: Int = 128) {
    data class Entry(val source: TrailerPlaybackSource?, val validUntilMs: Long)
    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)

    @Synchronized fun get(key: String): Entry? {
        val entry = entries[key] ?: return null
        if (clock.millis() >= entry.validUntilMs) { entries.remove(key); return null }
        return entry
    }

    @Synchronized fun put(key: String, source: TrailerPlaybackSource?, ttlMs: Long = 30 * 60_000L) {
        val deadline = minOf(clock.millis() + ttlMs,
            source?.let { TrailerSourceExpiry.expiresAtMs(it) } ?: Long.MAX_VALUE)
        if (deadline <= clock.millis()) return
        entries[key] = Entry(source, deadline)
        while (entries.size > capacity) entries.remove(entries.keys.first())
    }

    @Synchronized fun invalidate(videoUrl: String) {
        entries.entries.removeAll { it.value.source?.videoUrl == videoUrl }
    }

    @Synchronized fun clear() = entries.clear()
}

internal object TrailerSourceExpiry {
    private const val SAFETY_MARGIN_MS = 60_000L
    fun expiresAtMs(source: TrailerPlaybackSource): Long =
        minOf(source.validUntilMs ?: Long.MAX_VALUE,
            listOfNotNull(source.videoUrl, source.audioUrl).mapNotNull(::urlExpiryMs).minOrNull() ?: Long.MAX_VALUE)

    fun isUsable(source: TrailerPlaybackSource, nowMs: Long = System.currentTimeMillis()): Boolean =
        nowMs < expiresAtMs(source)

    private fun urlExpiryMs(url: String): Long? = runCatching {
        val uri = URI(url)
        val query = uri.rawQuery.orEmpty().split('&').mapNotNull {
            val split = it.indexOf('=')
            if (split < 0) null else it.substring(0, split).lowercase() to
                URLDecoder.decode(it.substring(split + 1), "UTF-8")
        }.toMap()
        val epoch = query["expires"]?.toLongOrNull() ?: query["expire"]?.toLongOrNull()
            ?: Regex("/expire/(\\d+)/").find(uri.path.orEmpty())?.groupValues?.get(1)?.toLongOrNull()
            ?: query["policy"]?.let { policy ->
                // CloudFront uses a modified base64 alphabet, not standard base64url.
                val decoded = Base64.getDecoder().decode(policy.replace('-', '+').replace('_', '=').replace('~', '/'))
                JsonParser.parseString(String(decoded, Charsets.UTF_8)).asJsonObject
                    .getAsJsonArray("Statement").mapNotNull { statement ->
                        statement.asJsonObject.getAsJsonObject("Condition")
                            ?.getAsJsonObject("DateLessThan")?.get("AWS:EpochTime")?.asLong
                    }.minOrNull()
            }
        epoch?.let { Math.multiplyExact(it, 1000L) - SAFETY_MARGIN_MS }
    }.getOrNull()
}
