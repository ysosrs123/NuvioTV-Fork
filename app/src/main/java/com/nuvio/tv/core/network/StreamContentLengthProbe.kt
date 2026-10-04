package com.nuvio.tv.core.network

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

/**
 * Metadata-only diagnostic. No body is read, even if the server ignores Range.
 * The caller's deadline does not make JVM DNS interruptible: admission remains
 * occupied until cancelled transport callbacks finish, preventing accumulation.
 */
internal class StreamContentLengthProbe(
    private val calls: Call.Factory,
    private val timeoutMs: Long = 5_000L,
    private val coordinator: DiagnosticRunCoordinator = DiagnosticRunCoordinator(DiagnosticPlaybackGuard())
) {
    private val busy = AtomicBoolean(false)

    init { require(timeoutMs > 0) }

    /** Zero preserves the existing caller contract for unavailable length. */
    suspend fun probe(url: String, headers: Map<String, String>): Long = try {
        coordinator.run(DiagnosticRunCoordinator.Limits(timeoutMs, 0)) { run ->
            probeOwned(url, headers, run.acquire(0))
        }
    } catch (_: DiagnosticRunCoordinator.Unavailable) { 0L }

    private suspend fun probeOwned(url: String, headers: Map<String, String>, work: AutoCloseable): Long {
        if (!busy.compareAndSet(false, true)) { work.close(); return 0L }
        val owner = Owner { busy.set(false); work.close() }
        return try {
            withTimeoutOrNull(timeoutMs) {
                val request = Request.Builder().url(url).apply {
                    headers.forEach { (name, value) -> header(name, value) }
                    header("Accept-Encoding", "identity")
                    removeHeader("Range")
                }.build()
                val head = fetch(owner, request.newBuilder().head().build())
                if (head > 0) head else fetch(owner, request.newBuilder()
                    .get().header("Range", "bytes=0-0").build())
            } ?: 0L
        } catch (_: IOException) {
            0L
        } catch (_: IllegalArgumentException) {
            0L
        } finally {
            owner.finish()
        }
    }

    private suspend fun fetch(owner: Owner, request: Request): Long =
        suspendCancellableCoroutine { continuation ->
            val call = calls.newCall(request)
            call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
            owner.add(call)
            continuation.invokeOnCancellation { call.cancel() }
            if (!continuation.isActive) {
                owner.completed(call)
                return@suspendCancellableCoroutine
            }
            try {
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        try {
                            continuation.resumeWithException(e)
                        } finally {
                            owner.completed(call)
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        try {
                            // Cancel BEFORE close: do not drain a potentially huge ignored-range body
                            // merely to recycle a connection. The pool belongs only to this probe.
                            call.cancel()
                            val length = response.use { contentLength(it, request.method) }
                            continuation.resume(length)
                        } catch (e: IOException) {
                            continuation.resumeWithException(e)
                        } finally {
                            owner.completed(call)
                        }
                    }
                })
            } catch (e: RuntimeException) {
                call.cancel()
                owner.completed(call)
                continuation.resumeWithException(IOException("Diagnostic request could not start", e))
            }
        }

    private class Owner(private val release: () -> Unit) {
        private val active = mutableSetOf<Call>()
        private var finished = false
        private var released = false

        @Synchronized fun add(call: Call) { check(!finished); active.add(call) }
        @Synchronized fun completed(call: Call) { active.remove(call); releaseIfComplete() }
        @Synchronized fun finish() {
            finished = true
            active.toList().forEach(Call::cancel)
            releaseIfComplete()
        }
        private fun releaseIfComplete() {
            if (finished && active.isEmpty() && !released) { released = true; release() }
        }
    }

    companion object {
        private val byteZeroRange = Regex("bytes\\s+0-0/([0-9]+)", RegexOption.IGNORE_CASE)

        internal fun contentLength(response: Response, method: String): Long {
            val encodings = response.headers.values("Content-Encoding")
            if (encodings.any { !it.trim().equals("identity", ignoreCase = true) }) return 0L
            val lengths = response.headers.values("Content-Length")
            if (lengths.size > 1) return 0L
            val length = lengths.singleOrNull()?.trim()?.takeIf { it.all(Char::isDigit) }
                ?.toLongOrNull()?.takeIf { it > 0 }
            if (lengths.isNotEmpty() && length == null) return 0L
            val ranges = response.headers.values("Content-Range")
            if (response.code == 200 && ranges.isEmpty()) return length ?: 0L
            if (method != "GET" || response.code != 206 || ranges.size != 1) return 0L
            if (length != null && length != 1L) return 0L
            return byteZeroRange.matchEntire(ranges.single().trim())?.groupValues?.get(1)
                ?.toLongOrNull()?.takeIf { it > 0 } ?: 0L
        }
    }
}
