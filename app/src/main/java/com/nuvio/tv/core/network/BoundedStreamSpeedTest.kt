package com.nuvio.tv.core.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Single-response HTTP sample. This does not benchmark the parallel playback scheduler. */
internal class BoundedStreamSpeedTest(
    private val calls: Call.Factory,
    private val playback: DiagnosticPlaybackGuard,
    private val limits: Limits = Limits(),
    private val coordinator: DiagnosticRunCoordinator = DiagnosticRunCoordinator(playback),
    private val nanoTime: () -> Long = System::nanoTime
) {
    data class Limits(
        val warmupBytes: Long = 8L * 1024 * 1024,
        val measuredBytes: Long = 64L * 1024 * 1024,
        val warmupMs: Long = 750,
        val measuredMs: Long = 8_000,
        val totalMs: Long = 15_000
    ) {
        init {
            require(warmupBytes > 0 && measuredBytes > 0 && warmupBytes <= Long.MAX_VALUE - measuredBytes)
            require(totalMs > 0 && warmupMs in 1..totalMs && measuredMs in 1..totalMs)
        }
        val totalBytes: Long get() = warmupBytes + measuredBytes
    }
    enum class Failure { BUSY, PLAYBACK_ACTIVE, TIMED_OUT, UNAVAILABLE }
    data class Result(
        val mbps: Double? = null,
        val warmupBytes: Long = 0,
        val measuredBytes: Long = 0,
        val measuredNanos: Long = 0,
        val servingEndpoint: String? = null,
        val failure: Failure? = null
    ) {
        val totalBytes: Long get() = warmupBytes + measuredBytes
    }
    private val busy = AtomicBoolean(false)

    suspend fun run(url: String, headers: Map<String, String>): Result = try {
        coordinator.run(DiagnosticRunCoordinator.Limits(limits.totalMs, limits.totalBytes)) { run ->
            runOwned(url, headers, run.acquire(limits.totalBytes))
        }
    } catch (e: DiagnosticRunCoordinator.Unavailable) { Result(failure = Failure.valueOf(if (e.reason == DiagnosticRunCoordinator.Reason.PAYLOAD_LIMIT) "UNAVAILABLE" else e.reason.name)) }

    private suspend fun runOwned(url: String, headers: Map<String, String>, work: AutoCloseable): Result {
        if (!busy.compareAndSet(false, true)) { work.close(); return Result(failure = Failure.BUSY) }
        val owner = Owner { busy.set(false); work.close() }
        var registration: AutoCloseable? = null
        try {
            val job = currentCoroutineContext().job
            registration = playback.register { job.cancel(CancellationException("Playback started")) }
                ?: return Result(failure = Failure.PLAYBACK_ACTIVE)
            return withTimeoutOrNull(limits.totalMs) {
                val request = Request.Builder().url(url).apply {
                    headers.forEach { (name, value) -> header(name, value) }
                    header("Accept-Encoding", "identity")
                    header("Range", "bytes=0-${limits.totalBytes - 1}")
                }.build()
                fetch(owner, request)
            } ?: Result(failure = Failure.TIMED_OUT)
        } catch (_: IOException) {
            return Result(failure = Failure.UNAVAILABLE)
        } catch (_: IllegalArgumentException) {
            return Result(failure = Failure.UNAVAILABLE)
        } finally {
            owner.finish()
            registration?.close()
        }
    }

    private suspend fun fetch(owner: Owner, request: Request): Result = suspendCancellableCoroutine { continuation ->
        val call = calls.newCall(request)
        call.timeout().timeout(limits.totalMs, TimeUnit.MILLISECONDS)
        owner.add(call)
        continuation.invokeOnCancellation { call.cancel() }
        if (!continuation.isActive) { owner.completed(call); return@suspendCancellableCoroutine }
        try {
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    try { continuation.resumeWithException(e) } finally { owner.completed(call) }
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            try {
                                if (!continuation.isActive || !validResponse(it)) throw IOException("Unusable stream response")
                                val body = it.body ?: throw IOException("Missing stream body")
                                val input = body.byteStream()
                                val buffer = ByteArray(minOf(64 * 1024L, limits.totalBytes).toInt())
                                fun readPhase(maxBytes: Long, targetMs: Long): Pair<Long, Long> {
                                    val start = nanoTime()
                                    var bytes = 0L
                                    while (continuation.isActive && bytes < maxBytes &&
                                        (nanoTime() - start) / 1_000_000 < targetMs) {
                                        val read = input.read(buffer, 0, minOf(buffer.size.toLong(), maxBytes - bytes).toInt())
                                        if (read < 0) break
                                        if (read == 0) throw IOException("Stream read made no progress")
                                        bytes += read
                                    }
                                    if (!continuation.isActive) throw IOException("Stream test cancelled")
                                    return bytes to (nanoTime() - start).coerceAtLeast(0)
                                }
                                // Phase targets are checked at read boundaries. The outer deadline
                                // cancels the call, including blocked reads; the payload ceilings never overshoot.
                                val warmup = readPhase(limits.warmupBytes, limits.warmupMs)
                                val measured = readPhase(limits.measuredBytes, limits.measuredMs)
                                val rate = if (measured.first > 0 && measured.second > 0)
                                    measured.first * 8_000.0 / measured.second else null
                                val url = it.request.url
                                val endpoint = if (url.port == if (url.isHttps) 443 else 80) url.host else "${url.host}:${url.port}"
                                Result(rate, warmup.first, measured.first, measured.second, endpoint,
                                    if (rate == null) Failure.UNAVAILABLE else null)
                            } finally {
                                // Stop before closing so ignored ranges are not drained for pool reuse.
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
            continuation.resumeWithException(IOException("Stream test could not start", e))
        }
    }

    private fun validResponse(response: Response): Boolean {
        if (response.code != 200 && response.code != 206) return false
        if (response.headers.values("Content-Encoding").any { !it.trim().equals("identity", true) }) return false
        val lengths = response.headers.values("Content-Length")
        val length = lengths.singleOrNull()?.trim()?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toLongOrNull()
        if (lengths.size > 1 || (lengths.isNotEmpty() && (length == null || length <= 0))) return false
        val ranges = response.headers.values("Content-Range")
        if (response.code == 200) return ranges.isEmpty()
        if (ranges.size != 1) return false
        val range = Regex("bytes 0-([0-9]+)/([0-9]+)", RegexOption.IGNORE_CASE).matchEntire(ranges.single().trim()) ?: return false
        val end = range.groupValues[1].toLongOrNull() ?: return false
        val total = range.groupValues[2].toLongOrNull() ?: return false
        return end < limits.totalBytes && end < total && (length == null || length == end + 1)
    }

    private class Owner(private val release: () -> Unit) {
        private val active = mutableSetOf<Call>()
        private var finished = false
        private var released = false
        @Synchronized fun add(call: Call) { check(!finished); active.add(call) }
        @Synchronized fun completed(call: Call) { active.remove(call); releaseIfComplete() }
        @Synchronized fun finish() { finished = true; active.toList().forEach(Call::cancel); releaseIfComplete() }
        private fun releaseIfComplete() {
            if (finished && active.isEmpty() && !released) { released = true; release() }
        }
    }
}
