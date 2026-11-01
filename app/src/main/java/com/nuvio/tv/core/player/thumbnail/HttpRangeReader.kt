package com.nuvio.tv.core.player.thumbnail

import android.util.Log
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.nio.ByteBuffer
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
 * Safe for reads from several threads at once. Never open-ended and never above [ThumbReads.MAX_READ]; a 200 (Range
 * ignored) is refused without reading the body. Never log URLs or headers. Each read takes a slot in the
 * [ConnectionLedger] first.
 */
internal class HttpRangeReader(
    private val url: String,
    private val headers: Map<String, String>,
    private val client: OkHttpClient,
    private val ledger: ConnectionLedger = ConnectionLedger.app,
    /** How long a read waits for a free connection in the [ConnectionLedger]. */
    @Volatile var slotWaitMs: Long = ConnectionLedger.SLOT_WAIT_MS,
) : RangeReader {
    private companion object {
        const val TAG = "ThumbWorker"
        val RESOLVED_URL_FAILURES = setOf(401, 403, 404, 410)
        const val TOO_MANY_PEEK_BYTES = 512L
    }

    @Volatile
    override var totalLength: Long = -1L
        private set

    /** The file's length from an answer read before this reader, e.g. the one that followed the link's redirects. */
    fun noteLength(length: Long) {
        if (length > 0 && totalLength < 0) totalLength = length
    }

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

    /** Reads waiting for a ledger slot when [cancel] runs give up too. */
    @Volatile
    private var cancels = 0

    fun cancel() {
        cancels++
        for (call in inFlight) call.cancel()
    }

    /** Cancels every read under way and refuses new ones. */
    fun close() {
        closed = true
        cancel()
    }

    override fun read(offset: Long, length: Int): ByteArray {
        val out = ByteArray(capped(length))
        val n = readInto(offset, out.size, ByteBuffer.wrap(out))
        return if (n == out.size) out else out.copyOf(n)
    }

    override fun readInto(offset: Long, length: Int, dst: ByteBuffer): Int {
        require(offset >= 0 && length > 0)
        val n = capped(minOf(length, dst.remaining()))
        if (n <= 0) return 0
        val target = effectiveUrl
        return try {
            readFrom(target, offset, n, dst)
        } catch (e: HttpStatusException) {
            // The redirect target may be short-lived: retry once from the original.
            if (target != url && e.code in RESOLVED_URL_FAILURES) {
                effectiveUrl = url
                readFrom(url, offset, n, dst)
            } else {
                throw e
            }
        }
    }

    /** A longer read is cut to the cap (callers treat it like a short read at end of file). */
    private fun capped(length: Int): Int {
        if (length <= ThumbReads.MAX_READ) return length
        Log.w(TAG, "range read of ${length / 1024} KB cut to ${ThumbReads.MAX_READ / 1024} KB")
        return ThumbReads.MAX_READ
    }

    class HttpStatusException(val code: Int, val tooMany: Boolean) : IOException("HTTP $code")

    private fun readFrom(target: String, offset: Long, length: Int, dst: ByteBuffer): Int {
        val builder = Request.Builder().url(target)
        for ((k, v) in headers) {
            if (!k.equals("Range", ignoreCase = true)) builder.header(k, v)
        }
        val request = builder
            .header("Range", "bytes=$offset-${offset + length - 1}")
            .header("Accept-Encoding", "identity")
            .build()
        if (closed) throw IOException("closed")
        val cancels0 = cancels
        var slot: ConnectionLedger.Slot? = null
        var call: Call? = null
        try {
            val held = ledger.acquire(target, slotWaitMs) { closed || cancels != cancels0 }
            slot = held
            val c = client.newCall(request)
            call = c
            inFlight.add(c)
            if (closed) c.cancel()
            requestCount.incrementAndGet()
            c.execute().use { resp ->
                held.moveTo(resp.request.url.toString())
                when (resp.code) {
                    206 -> Unit
                    416 -> return 0
                    429, 503 -> throw RateLimitedException(
                        ThumbRateLimit.retryAfterMs(resp.header("Retry-After"), System.currentTimeMillis()),
                        resp.code,
                        resp.request.url.toString(),
                    )
                    200 -> throw UnsupportedMediaException("server ignored Range")
                    else -> throw HttpStatusException(resp.code, ConnectionLedger.saysTooMany(
                        runCatching { resp.peekBody(TOO_MANY_PEEK_BYTES).string() }.getOrNull()))
                }
                resp.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()?.let { totalLength = it }
                val finalUrl = resp.request.url.toString()
                if (finalUrl != target) effectiveUrl = finalUrl
                val body = resp.body ?: throw IOException("empty body")
                // Straight from OkHttp's segments into dst: no array of the whole range.
                val start = dst.position()
                val limit = dst.limit()
                dst.limit(start + length)
                try {
                    val source = body.source()
                    while (dst.hasRemaining()) {
                        if (source.read(dst) < 0) break
                    }
                } finally {
                    dst.limit(limit)
                }
                val n = dst.position() - start
                byteCount.addAndGet(n.toLong())
                held.succeeded()
                return n
            }
        } catch (e: IOException) {
            val refusal = ConnectionLedger.refusalOf(e) ?: (e as? HttpStatusException)?.let {
                when {
                    it.tooMany -> Refusal.TOO_MANY
                    // A 403 from a short-lived redirect target is an expired link, retried from the original.
                    it.code == 403 && target == url -> Refusal.CONNECTION
                    else -> null
                }
            }
            refusal?.let { slot?.refused(it) }
            throw e
        } finally {
            slot?.close()
            call?.let { inFlight.remove(it) }
        }
    }

    /** The server answered a many-range request with something other than its parts: single reads from now on. */
    class MultiRangeRefused(why: String) : IOException(why)

    /**
     * One request for several byte ranges, each `first until last` exclusive, sorted and apart. Returns the parts as
     * (start, bytes); a server that merges ranges may send one part covering several. Throws [MultiRangeRefused] when
     * the server ignores or refuses the ranges; never reads a whole-file answer. One [ConnectionLedger] slot.
     */
    fun readRanges(ranges: List<LongRange>, maxBytes: Long): List<Pair<Long, ByteArray>> {
        require(ranges.isNotEmpty())
        val asked = ranges.sumOf { it.last - it.first }
        if (asked > maxBytes) throw MultiRangeRefused("asked ${asked / 1024} KB above ${maxBytes / 1024} KB")
        val target = effectiveUrl
        val builder = Request.Builder().url(target)
        for ((k, v) in headers) {
            if (!k.equals("Range", ignoreCase = true)) builder.header(k, v)
        }
        val request = builder
            .header("Range", "bytes=" + ranges.joinToString(",") { "${it.first}-${it.last - 1}" })
            .header("Accept-Encoding", "identity")
            .build()
        if (closed) throw IOException("closed")
        val cancels0 = cancels
        var slot: ConnectionLedger.Slot? = null
        var call: Call? = null
        try {
            val held = ledger.acquire(target, slotWaitMs) { closed || cancels != cancels0 }
            slot = held
            val c = client.newCall(request)
            call = c
            inFlight.add(c)
            if (closed) c.cancel()
            requestCount.incrementAndGet()
            c.execute().use { resp ->
                held.moveTo(resp.request.url.toString())
                when (resp.code) {
                    206 -> Unit
                    429, 503 -> throw RateLimitedException(
                        ThumbRateLimit.retryAfterMs(resp.header("Retry-After"), System.currentTimeMillis()),
                        resp.code,
                        resp.request.url.toString(),
                    )
                    200 -> throw MultiRangeRefused("server sent the whole file")
                    400, 416, 501 -> throw MultiRangeRefused("HTTP ${resp.code}")
                    else -> throw HttpStatusException(resp.code, ConnectionLedger.saysTooMany(
                        runCatching { resp.peekBody(TOO_MANY_PEEK_BYTES).string() }.getOrNull()))
                }
                val finalUrl = resp.request.url.toString()
                if (finalUrl != target) effectiveUrl = finalUrl
                val body = resp.body ?: throw IOException("empty body")
                val type = resp.header("Content-Type").orEmpty()
                val parts = if (type.startsWith("multipart/byteranges", ignoreCase = true)) {
                    val boundary = MultipartRanges.boundary(type) ?: throw MultiRangeRefused("no boundary")
                    MultipartRanges.read(body.source(), boundary, maxBytes) { totalLength = it }
                } else {
                    // One part: fine when it covers every range (merged), else the server served only the first.
                    val cr = MultipartRanges.contentRange(resp.header("Content-Range"))
                        ?: throw MultiRangeRefused("no Content-Range")
                    cr.third.takeIf { it > 0 }?.let { totalLength = it }
                    val len = cr.second - cr.first + 1
                    if (ranges.size > 1 && (cr.first > ranges.first().first || cr.second < ranges.last().last - 1)) {
                        throw MultiRangeRefused("server sent one range")
                    }
                    if (len > maxBytes) throw MultiRangeRefused("merged answer of ${len / 1024} KB")
                    listOf(cr.first to body.source().readByteArray(len))
                }
                byteCount.addAndGet(parts.sumOf { it.second.size.toLong() })
                held.succeeded()
                return parts
            }
        } catch (e: IOException) {
            val refusal = ConnectionLedger.refusalOf(e) ?: (e as? HttpStatusException)?.takeIf { it.tooMany }?.let {
                Refusal.TOO_MANY
            }
            refusal?.let { slot?.refused(it) }
            throw e
        } finally {
            slot?.close()
            call?.let { inFlight.remove(it) }
        }
    }
}
