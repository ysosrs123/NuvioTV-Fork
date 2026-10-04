package com.nuvio.tv.core.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/** Caller deadlines do not wait for an uninterruptible reader or DNS resolver.
 * Admission stays occupied until reader cleanup AND its private workers have finished. */
internal class BoundedParallelSpeedTest(
    private val playback: DiagnosticPlaybackGuard,
    private val limits: Limits = Limits(),
    private val coordinator: DiagnosticRunCoordinator = DiagnosticRunCoordinator(playback),
    private val nanoTime: () -> Long = System::nanoTime
) {
    data class Limits(
        val totalBytes: Long = 72L * 1024 * 1024,
        val warmupBytes: Long = 8L * 1024 * 1024,
        val warmupMs: Long = 750,
        val measuredMs: Long = 8_000,
        val minimumMeasuredMs: Long = 500,
        val sampleMs: Long = 500,
        val totalMs: Long = 20_000
    ) {
        init {
            require(totalBytes > 0 && warmupBytes in 0 until totalBytes)
            require(totalMs > 0 && warmupMs in 0..totalMs && measuredMs in 1..totalMs)
            require(minimumMeasuredMs in 0..measuredMs && sampleMs > 0)
        }
    }
    interface Session : AutoCloseable {
        fun open()
        fun read(buffer: ByteArray): Int
        val clampTrips: Int
        /** Cancel HTTP only; buffer teardown belongs to the reader's finally. */
        fun cancel()
        val isQuiescent: Boolean
    }
    data class Result(
        val mbps: Double? = null,
        val samples: List<Double> = emptyList(),
        val failure: String? = null,
        val clampTrips: Int = 0,
        val warmupBytes: Long = 0,
        val measuredBytes: Long = 0,
        val measuredNanos: Long = 0,
        val totalBytes: Long = 0,
        val payloadLimited: Boolean = false
    )
    private val busy = AtomicBoolean()
    private fun executor(name: String) = ThreadPoolExecutor(
        1, 1, 30L, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(1),
        { task -> Thread(task, name).apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy()
    ).apply { allowCoreThreadTimeOut(true) }
    private val readers = executor("parallel-test-reader")
    private val cancellers = executor("parallel-test-cancel")

    suspend fun run(create: (DiagnosticPayloadBudget) -> Session): Result = try {
        coordinator.run(DiagnosticRunCoordinator.Limits(limits.totalMs, limits.totalBytes)) { run ->
            runOwned(create, run.acquire(limits.totalBytes))
        }
    } catch (e: DiagnosticRunCoordinator.Unavailable) {
        Result(failure = if (e.reason == DiagnosticRunCoordinator.Reason.TIMED_OUT) "Parallel test timed out" else e.message)
    }

    private suspend fun runOwned(create: (DiagnosticPayloadBudget) -> Session, work: AutoCloseable): Result {
        if (!busy.compareAndSet(false, true)) { work.close(); return Result(failure = "Earlier parallel test is still finishing") }
        val owner = Owner()
        var submitted = false
        var registration: AutoCloseable? = null
        try {
            val job = currentCoroutineContext().job
            registration = playback.register { job.cancel(CancellationException("Playback started")) }
                ?: return Result(failure = "Playback is active")
            return withTimeoutOrNull(limits.totalMs) {
                coroutineScope {
                    val sampler = launch(start = CoroutineStart.UNDISPATCHED) {
                        while (true) {
                            delay(limits.sampleMs)
                            if (owner.sample()) owner.stop()
                        }
                    }
                    try {
                        suspendCancellableCoroutine { continuation ->
                            continuation.invokeOnCancellation { owner.stop() }
                            try {
                                readers.execute {
                                    owner.reader = Thread.currentThread()
                                    var result: Result
                                    try {
                                        val session = create(owner.budget)
                                        owner.session = session
                                        if (owner.stopped.get()) throw IOException("Test cancelled before open")
                                        session.open()
                                        val buffer = ByteArray(64 * 1024)
                                        val warmStart = nanoTime()
                                        var eof = false
                                        while (!owner.stopped.get() && owner.budget.snapshot().bytes < limits.warmupBytes &&
                                            nanoTime() - warmStart < limits.warmupMs * 1_000_000) {
                                            val read = session.read(buffer)
                                            if (read < 0) { eof = true; break }
                                            if (read == 0) throw IOException("Read made no progress")
                                        }
                                        owner.begin()
                                        while (!eof && !owner.stopped.get()) {
                                            if (owner.expired()) break
                                            val read = session.read(buffer)
                                            if (read < 0) eof = true
                                            if (read == 0) throw IOException("Read made no progress")
                                        }
                                        result = owner.result(null)
                                    } catch (t: Throwable) {
                                        // Budget exhaustion can be wrapped by Media3/chunk futures.
                                        val limited = owner.budget.snapshot().bytes >= limits.totalBytes
                                        result = owner.result(if (t !is Error && (limited || owner.expired())) null else t.javaClass.simpleName)
                                    } finally {
                                        owner.stop()
                                        // Do not tear down native buffers while this reader is using them.
                                        Thread.interrupted()
                                    }
                                    var clean = false
                                    try {
                                        try { owner.session?.cancel() } finally { owner.session?.close() }
                                        clean = true
                                    } catch (_: Throwable) {
                                        result = result.copy(mbps = null, failure = "Test cleanup failed")
                                    }
                                    // A stuck resolver/reader cannot accumulate more cells after caller timeout.
                                    while (!owner.cancelFinished.get() || (owner.session?.isQuiescent == false)) {
                                        try { Thread.sleep(20) } catch (_: InterruptedException) { }
                                    }
                                    result = result.copy(totalBytes = owner.budget.snapshot().bytes)
                                    owner.reader = null
                                    if (clean) { busy.set(false); work.close() } // Failed teardown retains admission conservatively.
                                    continuation.resume(result)
                                }
                                submitted = true
                            } catch (_: RuntimeException) {
                                continuation.resume(Result(failure = "Parallel test worker unavailable"))
                            }
                        }
                    } finally { sampler.cancel() }
                }
            } ?: Result(failure = "Parallel test timed out")
        } finally {
            owner.stop()
            registration?.close()
            if (!submitted) { busy.set(false); work.close() }
        }
    }

    private inner class Owner {
        val budget = DiagnosticPayloadBudget(limits.totalBytes, nanoTime)
        val stopped = AtomicBoolean()
        val cancelFinished = AtomicBoolean()
        @Volatile var session: Session? = null
        @Volatile var reader: Thread? = null
        private var start: DiagnosticPayloadBudget.Snapshot? = null
        private var last: DiagnosticPayloadBudget.Snapshot? = null
        private var end: DiagnosticPayloadBudget.Snapshot? = null
        private val samples = mutableListOf<Double>()
        @Synchronized fun begin() {
            if (!stopped.get()) budget.snapshot().also { start = it; last = it }
        }
        @Synchronized fun expired(): Boolean = start?.let {
            nanoTime() - it.nanos >= limits.measuredMs * 1_000_000 || budget.snapshot().bytes >= limits.totalBytes
        } ?: false
        @Synchronized fun sample(): Boolean {
            if (end != null || start == null) return false
            val now = budget.snapshot()
            last?.let { previous ->
                if (now.nanos > previous.nanos) samples += (now.bytes - previous.bytes) * 8_000.0 / (now.nanos - previous.nanos)
            }
            last = now
            return expired()
        }
        fun stop() {
            if (!stopped.compareAndSet(false, true)) return
            synchronized(this) { if (end == null) end = budget.snapshot() }
            budget.stop()
            // Interrupt and HTTP cancellation never close/free the reader's native buffers.
            val activeReader = reader
            if (activeReader == null) { cancelFinished.set(true); return }
            activeReader.interrupt()
            cancellers.execute {
                try { session?.cancel() } finally { cancelFinished.set(true) }
            }
        }
        @Synchronized fun result(error: String?): Result {
            val finish = end ?: budget.snapshot().also { end = it }
            val begin = start
            val bytes = if (begin == null) 0 else finish.bytes - begin.bytes
            val nanos = if (begin == null) 0 else (finish.nanos - begin.nanos).coerceAtLeast(0)
            val limited = finish.bytes >= limits.totalBytes
            val valid = error == null && bytes > 0 && nanos > 0 && nanos >= limits.minimumMeasuredMs * 1_000_000
            return Result(
                mbps = if (valid) bytes * 8_000.0 / nanos else null,
                samples = samples.toList(), failure = if (valid) null else error ?: "Insufficient measured payload or duration",
                clampTrips = session?.clampTrips ?: 0, warmupBytes = begin?.bytes ?: finish.bytes,
                measuredBytes = bytes, measuredNanos = nanos, totalBytes = finish.bytes, payloadLimited = limited
            )
        }
    }
}
