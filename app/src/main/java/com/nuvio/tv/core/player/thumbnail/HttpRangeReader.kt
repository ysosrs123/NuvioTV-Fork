package com.nuvio.tv.core.player.thumbnail

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The server asked us to slow down (429/503). [retryAfterMs] is the server's Retry-After, null when it sent none;
 * [url] is the address that answered, after redirects.
 */
internal class RateLimitedException(
    val retryAfterMs: Long?,
    val status: Int = TOO_MANY_REQUESTS,
    val url: String? = null,
) : IOException("rate limited") {
    companion object {
        const val TOO_MANY_REQUESTS = 429
    }
}

/**
 * Bounded Range reads over the playback OkHttp client, so a title's thumbnail fetches reuse keep-alive connections.
 * Safe for reads from several threads at once. Never open-ended; a 200 (Range ignored) is refused without reading
 * the body. Never log URLs or headers.
 */
internal class HttpRangeReader(
    private val url: String,
    private val headers: Map<String, String>,
    private val client: OkHttpClient,
) : RangeReader {
    private companion object {
        val RESOLVED_URL_FAILURES = setOf(401, 403, 404, 410)
    }

    @Volatile
    override var totalLength: Long = -1L
        private set

    private val inFlight: MutableSet<Call> = Collections.newSetFromMap(ConcurrentHashMap<Call, Boolean>())

    @Volatile
    private var closed = false

    /** URL after redirects, so later requests skip the redirect round trip (0.25-0.7 s each on debrid links). */
    @Volatile
    private var effectiveUrl: String = url

    val resolvedUrl: String get() = effectiveUrl

    private val requestCount = AtomicInteger()
    private val byteCount = AtomicLong()

    val requests: Int get() = requestCount.get()
    val bytes: Long get() = byteCount.get()

    fun cancel() {
        for (call in inFlight) call.cancel()
    }

    /** Cancels every read under way and refuses new ones. */
    fun close() {
        closed = true
        cancel()
    }

    override fun read(offset: Long, length: Int): ByteArray {
        require(offset >= 0 && length > 0)
        val target = effectiveUrl
        return try {
            readFrom(target, offset, length)
        } catch (e: HttpStatusException) {
            // The redirect target may be short-lived: retry once from the original.
            if (target != url && e.code in RESOLVED_URL_FAILURES) {
                effectiveUrl = url
                readFrom(url, offset, length)
            } else {
                throw e
            }
        }
    }

    private class HttpStatusException(val code: Int) : IOException("HTTP $code")

    private fun readFrom(target: String, offset: Long, length: Int): ByteArray {
        val builder = Request.Builder().url(target)
        for ((k, v) in headers) {
            if (!k.equals("Range", ignoreCase = true)) builder.header(k, v)
        }
        val request = builder
            .header("Range", "bytes=$offset-${offset + length - 1}")
            .header("Accept-Encoding", "identity")
            .build()
        if (closed) throw IOException("closed")
        val call = client.newCall(request)
        inFlight.add(call)
        if (closed) call.cancel()
        requestCount.incrementAndGet()
        try {
            call.execute().use { resp ->
                when (resp.code) {
                    206 -> Unit
                    416 -> return ByteArray(0)
                    429, 503 -> throw RateLimitedException(
                        ThumbRateLimit.retryAfterMs(resp.header("Retry-After"), System.currentTimeMillis()),
                        resp.code,
                        resp.request.url.toString(),
                    )
                    200 -> throw UnsupportedMediaException("server ignored Range")
                    else -> throw HttpStatusException(resp.code)
                }
                resp.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()?.let { totalLength = it }
                val finalUrl = resp.request.url.toString()
                if (finalUrl != target) effectiveUrl = finalUrl
                val body = resp.body ?: throw IOException("empty body")
                val out = ByteArray(length)
                var n = 0
                body.byteStream().use { input ->
                    while (n < length) {
                        val r = input.read(out, n, length - n)
                        if (r < 0) break
                        n += r
                    }
                }
                byteCount.addAndGet(n.toLong())
                return if (n == length) out else out.copyOf(n)
            }
        } finally {
            inFlight.remove(call)
        }
    }
}
