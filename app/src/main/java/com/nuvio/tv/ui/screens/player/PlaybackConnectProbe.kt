package com.nuvio.tv.ui.screens.player

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/** One diagnostic worker across player lifetimes. DNS may outlive cancellation, but cannot accumulate. */
internal class PlaybackConnectProbe(
    private val snapshot: () -> PlaybackTransferSnapshot,
    private val clockMs: () -> Long,
    private val executor: Executor = worker,
    private val resolve: (String) -> InetAddress = InetAddress::getByName,
    private val socketFactory: () -> Socket = ::Socket,
    private val timeoutMs: Long = 2_000L
) {
    init { require(timeoutMs in 1..Int.MAX_VALUE.toLong()) }
    private val busy = AtomicBoolean(false)

    suspend fun sample(): PlaybackConnectSample? {
        val before = snapshot()
        val endpoint = before.endpoint ?: return null
        if (!before.coverage.available || !busy.compareAndSet(false, true)) return null
        val submitted = AtomicBoolean(false)
        val released = AtomicBoolean(false)
        fun release() { if (released.compareAndSet(false, true)) busy.set(false) }
        val started = clockMs()
        fun current(): Boolean {
            val now = snapshot()
            return now.coverage.available && now.sessionId == before.sessionId && now.endpoint == endpoint
        }
        try {
            return withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { continuation ->
                    val owner = SocketOwner()
                    continuation.invokeOnCancellation { owner.close() }
                    if (!continuation.isActive) return@suspendCancellableCoroutine
                    submitted.set(true)
                    try {
                        executor.execute {
                            var sample: PlaybackConnectSample? = null
                            try {
                                if (continuation.isActive && current()) {
                                    val address = resolve(endpoint.host)
                                    val remaining = timeoutMs - (clockMs() - started)
                                    // Do not open a socket after cancelled/expired DNS or a source change.
                                    if (continuation.isActive && current() && remaining in 1..timeoutMs) {
                                        val socket = socketFactory()
                                        if (owner.attach(socket)) {
                                            socket.connect(InetSocketAddress(address, endpoint.port), remaining.toInt())
                                            val finished = clockMs()
                                            if (continuation.isActive && current() && finished - started in 0 until timeoutMs) {
                                                sample = PlaybackConnectSample(before.sessionId, endpoint, finished - started, finished)
                                            }
                                        }
                                    }
                                }
                            } catch (_: Exception) {
                                // Unavailable is distinct from a successful zero-latency sample.
                            } finally {
                                owner.close()
                                release()
                            }
                            continuation.resume(sample)
                        }
                    } catch (_: java.util.concurrent.RejectedExecutionException) {
                        owner.close()
                        release()
                        continuation.resume(null)
                    }
                }
            }?.takeIf { it.matches(snapshot()) }
        } finally {
            // Once submitted, only actual worker completion releases admission.
            if (!submitted.get()) release()
        }
    }

    private class SocketOwner {
        private var closed = false
        private var socket: Socket? = null
        @Synchronized fun attach(value: Socket): Boolean {
            if (closed) { runCatching { value.close() }; return false }
            socket = value
            return true
        }
        @Synchronized fun close() {
            closed = true
            val owned = socket
            socket = null
            runCatching { owned?.close() }
        }
    }

    companion object {
        // Single core starts immediately; idle threads expire. Admission prevents a DNS backlog.
        private val worker: Executor by lazy {
            ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(1),
                { task -> Thread(task, "Nuvio-hud-connect").apply { isDaemon = true } })
                .apply { allowCoreThreadTimeOut(true) }
        }
        val shared: PlaybackConnectProbe by lazy {
            PlaybackConnectProbe(PlaybackByteCounter::snapshot, android.os.SystemClock::elapsedRealtime)
        }
    }
}
