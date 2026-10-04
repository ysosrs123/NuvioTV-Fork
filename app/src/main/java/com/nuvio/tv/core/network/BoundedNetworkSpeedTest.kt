package com.nuvio.tv.core.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal object GeneralNetworkSpeedTest {
    private val runner by lazy { BoundedNetworkSpeedTest(createClient(), DiagnosticPlaybackGuard.shared, coordinator = DiagnosticRunCoordinator.shared) }

    internal fun createClient(): OkHttpClient {
        val executor = ThreadPoolExecutor(4, 4, 30L, TimeUnit.SECONDS,
            ArrayBlockingQueue<Runnable>(4), ThreadFactory { task ->
                Thread(task, "Nuvio-network-test").apply { isDaemon = true }
            }).apply { allowCoreThreadTimeOut(true) }
        val dispatcher = Dispatcher(executor).apply { maxRequests = 4; maxRequestsPerHost = 4 }
        val client = OkHttpClient.Builder().dispatcher(dispatcher)
            .connectionPool(ConnectionPool(0, 1L, TimeUnit.SECONDS)).dns(IPv4FirstDns())
            .retryOnConnectionFailure(false).callTimeout(20, TimeUnit.SECONDS)
            .connectTimeout(3, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS)
            .writeTimeout(3, TimeUnit.SECONDS).build()
        return client
    }

    suspend fun run(onLatency: (Long) -> Unit, onDownloading: () -> Unit) =
        runner.run(onLatency, onDownloading)
}

/** General HTTP diagnostic only: this does not model the playback chunk scheduler. */
internal class BoundedNetworkSpeedTest(
    private val calls: Call.Factory,
    private val playback: DiagnosticPlaybackGuard,
    private val limits: Limits = Limits(),
    private val coordinator: DiagnosticRunCoordinator = DiagnosticRunCoordinator(playback),
    private val nanoTime: () -> Long = System::nanoTime
) {
    data class Limits(
        val totalBytes: Long = 64L * 1024 * 1024,
        val totalMs: Long = 20_000,
        val downloadMs: Long = 10_000,
        val workers: Int = 4,
        val htmlBytes: Int = 256 * 1024,
        val scriptBytes: Int = 2 * 1024 * 1024,
        val jsonBytes: Int = 256 * 1024
    ) {
        init {
            require(totalBytes > 0 && totalMs > 0 && downloadMs in 1..totalMs)
            require(workers in 1..4 && htmlBytes > 0 && scriptBytes > 0 && jsonBytes > 0)
        }
    }
    enum class Failure { BUSY, PLAYBACK_ACTIVE, TIMED_OUT, UNAVAILABLE }
    data class Result(
        val latencyMs: Long? = null,
        val mbps: Double? = null,
        val measuredBytes: Long = 0,
        val elapsedNanos: Long = 0,
        val servingHosts: Set<String> = emptySet(),
        val failedWorkers: Int = 0,
        val failure: Failure? = null
    )
    private val busy = AtomicBoolean(false)

    suspend fun run(onLatency: (Long) -> Unit = {}, onDownloading: () -> Unit = {}): Result = try {
        coordinator.run(DiagnosticRunCoordinator.Limits(limits.totalMs, limits.totalBytes)) { run ->
            runOwned(onLatency, onDownloading, run.acquire(limits.totalBytes))
        }
    } catch (e: DiagnosticRunCoordinator.Unavailable) { Result(failure = Failure.valueOf(if (e.reason == DiagnosticRunCoordinator.Reason.PAYLOAD_LIMIT) "UNAVAILABLE" else e.reason.name)) }

    private suspend fun runOwned(onLatency: (Long) -> Unit, onDownloading: () -> Unit, work: AutoCloseable): Result {
        if (!busy.compareAndSet(false, true)) { work.close(); return Result(failure = Failure.BUSY) }
        val owner = Owner(limits.totalBytes) { busy.set(false); work.close() }
        var registration: AutoCloseable? = null
        return try {
            val job = currentCoroutineContext().job
            registration = playback.register { job.cancel(CancellationException("Playback started")) }
                ?: return Result(failure = Failure.PLAYBACK_ACTIVE)
            withTimeoutOrNull(limits.totalMs) {
                var latencyNanos = 0L
                repeat(3) {
                    val start = nanoTime()
                    val read = fetch(owner, "https://cloudflare.com/cdn-cgi/trace", 1, collect = false)
                    if (read.bytes == 0L) throw IOException("Empty HTTP timing response")
                    latencyNanos += (nanoTime() - start).coerceAtLeast(0)
                }
                val latencyMs = (latencyNanos / 3 / 1_000_000).coerceAtLeast(1)
                onLatency(latencyMs)
                val html = text(owner, "https://fast.com", limits.htmlBytes)
                val script = scriptPath(html) ?: throw IOException("Speed-test script unavailable")
                val token = apiToken(text(owner, "https://fast.com$script", limits.scriptBytes))
                    ?: throw IOException("Speed-test token unavailable")
                val targets = targets(text(owner,
                    "https://api.fast.com/netflix/speedtest/v2?https=true&token=$token&urlCount=4",
                    limits.jsonBytes), limits.workers)
                if (targets.isEmpty()) throw IOException("Speed-test sources unavailable")
                val perWorker = owner.budget.remaining() / targets.size
                if (perWorker <= 0) throw IOException("Diagnostic byte budget exhausted")
                onDownloading()
                val window = Window(nanoTime())
                val failures = AtomicInteger()
                try {
                    withTimeoutOrNull(limits.downloadMs) {
                        coroutineScope {
                            targets.map { url -> async {
                                try {
                                    fetch(owner, url, perWorker, collect = false,
                                        range = true, onBytes = window::record, onHost = window::host)
                                } catch (_: IOException) { failures.incrementAndGet() }
                            } }.awaitAll()
                        }
                    }
                } finally {
                    window.finish(nanoTime())
                }
                window.result(latencyMs, failures.get())
            } ?: Result(failure = Failure.TIMED_OUT)
        } catch (_: IOException) {
            Result(failure = Failure.UNAVAILABLE)
        } catch (_: org.json.JSONException) {
            Result(failure = Failure.UNAVAILABLE)
        } finally {
            owner.finish()
            registration?.close()
        }
    }

    private suspend fun text(owner: Owner, url: String, max: Int): String =
        fetch(owner, url, max.toLong(), collect = true).text!!

    private data class ReadResult(val bytes: Long, val text: String?)

    private suspend fun fetch(
        owner: Owner, url: String, maxBytes: Long, collect: Boolean, range: Boolean = false,
        onBytes: (Int) -> Unit = {}, onHost: (String) -> Unit = {}
    ): ReadResult = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(url).header("Accept-Encoding", "identity")
            .header("User-Agent", "Mozilla/5.0").apply {
                if (range) header("Range", "bytes=0-${maxBytes - 1}")
            }.build()
        val call = calls.newCall(request)
        call.timeout().timeout(limits.totalMs, TimeUnit.MILLISECONDS)
        owner.add(call)
        continuation.invokeOnCancellation { call.cancel() }
        if (!continuation.isActive) {
            owner.completed(call)
            return@suspendCancellableCoroutine
        }
        try {
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    try { continuation.resumeWithException(e) } finally { owner.completed(call) }
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            try {
                                if (!continuation.isActive) throw IOException("Diagnostic cancelled")
                                if (it.code != 200 && it.code != 206) throw IOException("HTTP diagnostic failed")
                                if (it.headers.values("Content-Encoding").any { v -> !v.equals("identity", true) })
                                    throw IOException("Encoded diagnostic response")
                                if (it.code == 206 && (!range || !validRange(it.header("Content-Range"), maxBytes)))
                                    throw IOException("Invalid diagnostic range")
                                val body = it.body ?: throw IOException("Missing diagnostic response")
                                if (collect && body.contentLength() > maxBytes) throw IOException("Discovery response too large")
                                onHost(it.request.url.host)
                                val output = if (collect) ByteArrayOutputStream(8192) else null
                                val input = body.byteStream()
                                val buffer = ByteArray(minOf(32_768L, maxBytes).toInt())
                                var total = 0L
                                while (continuation.isActive && total < maxBytes) {
                                    val reserved = owner.budget.reserve(minOf(buffer.size.toLong(), maxBytes - total).toInt())
                                    if (reserved == 0) break
                                    val read = try { input.read(buffer, 0, reserved) } catch (e: IOException) {
                                        owner.budget.complete(reserved, 0); throw e
                                    }
                                    owner.budget.complete(reserved, read.coerceAtLeast(0))
                                    if (read < 0) break
                                    if (read == 0) throw IOException("Diagnostic read made no progress")
                                    total += read
                                    onBytes(read)
                                    output?.write(buffer, 0, read)
                                }
                                if (collect && (total == maxBytes || !continuation.isActive || owner.budget.remaining() == 0L))
                                    throw IOException("Discovery response limit reached")
                                ReadResult(total, output?.toString(Charsets.UTF_8.name()))
                            } finally {
                                // Never drain an ignored-range/oversized body to recycle the socket.
                                call.cancel()
                            }
                        }
                        continuation.resume(result)
                    } catch (e: IOException) {
                        continuation.resumeWithException(e)
                    } finally {
                        owner.completed(call)
                    }
                }
            })
        } catch (e: RuntimeException) {
            call.cancel(); owner.completed(call)
            continuation.resumeWithException(IOException("Diagnostic request could not start", e))
        }
    }

    internal class Budget(private val maximum: Long) {
        private var available = maximum
        @Synchronized fun reserve(requested: Int): Int = minOf(available, requested.toLong()).toInt()
            .also { available -= it }
        @Synchronized fun complete(reserved: Int, read: Int) {
            require(read in 0..reserved)
            available += reserved - read
        }
        @Synchronized fun remaining(): Long = available
    }

    private class Owner(maximum: Long, private val release: () -> Unit) {
        val budget = Budget(maximum)
        private val calls = mutableSetOf<Call>()
        private var finished = false
        private var released = false
        @Synchronized fun add(call: Call) { check(!finished); calls.add(call) }
        @Synchronized fun completed(call: Call) { calls.remove(call); releaseIfComplete() }
        @Synchronized fun finish() {
            finished = true
            calls.toList().forEach(Call::cancel)
            releaseIfComplete()
        }
        private fun releaseIfComplete() {
            if (finished && calls.isEmpty() && !released) { released = true; release() }
        }
    }

    private inner class Window(private val start: Long) {
        private var bytes = 0L
        private var end: Long? = null
        private val hosts = mutableSetOf<String>()
        @Synchronized fun record(count: Int) { if (end == null) bytes += count }
        @Synchronized fun host(value: String) { if (end == null) hosts.add(value) }
        @Synchronized fun finish(now: Long) { if (end == null) end = now }
        @Synchronized fun result(latency: Long, failures: Int): Result {
            val elapsed = (end ?: start) - start
            val rate = if (bytes > 0 && elapsed > 0) bytes * 8_000.0 / elapsed else null
            return Result(latency, rate, bytes, elapsed.coerceAtLeast(0), hosts.toSet(), failures,
                if (rate == null) Failure.UNAVAILABLE else null)
        }
    }

    companion object {
        internal fun scriptPath(html: String): String? =
            Regex("<script src=\"(/app[^\"]{1,500}\\.js)\"").find(html)?.groupValues?.get(1)
        internal fun apiToken(script: String): String? =
            Regex("token:\"([A-Za-z0-9_-]{1,128})\"").find(script)?.groupValues?.get(1)
        internal fun targets(json: String, limit: Int): List<String> {
            val array = JSONObject(json).getJSONArray("targets")
            return (0 until minOf(array.length(), limit)).mapNotNull { index ->
                array.optJSONObject(index)?.optString("url")?.toHttpUrlOrNull()
                    ?.takeIf { it.isHttps && it.username.isEmpty() && it.password.isEmpty() }?.toString()
            }.distinct()
        }
        private fun validRange(value: String?, cap: Long): Boolean {
            val match = Regex("bytes 0-([0-9]+)/([0-9]+)", RegexOption.IGNORE_CASE)
                .matchEntire(value?.trim().orEmpty()) ?: return false
            val end = match.groupValues[1].toLongOrNull() ?: return false
            val total = match.groupValues[2].toLongOrNull() ?: return false
            return end < cap && end < total
        }
    }
}
