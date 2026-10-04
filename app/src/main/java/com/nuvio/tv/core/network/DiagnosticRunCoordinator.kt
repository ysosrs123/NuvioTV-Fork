package com.nuvio.tv.core.network

import android.content.Context
import com.nuvio.tv.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** One diagnostic family at a time. Nested phases inherit a run and retain it through cleanup. */
internal class DiagnosticRunCoordinator(private val playback: DiagnosticPlaybackGuard) {
    data class Limits(val timeMs: Long, val payloadBytes: Long) {
        init { require(timeMs > 0 && payloadBytes >= 0) }
    }
    enum class Reason { BUSY, PLAYBACK_ACTIVE, TIMED_OUT, PAYLOAD_LIMIT }
    class Unavailable(val reason: Reason) : RuntimeException(when (reason) {
        Reason.BUSY -> "Another diagnostic is running or finishing"
        Reason.PLAYBACK_ACTIVE -> "Playback is active"
        Reason.TIMED_OUT -> "Diagnostic run timed out"
        Reason.PAYLOAD_LIMIT -> "Diagnostic comparison reached its payload allowance"
    })
    private class Element(val owner: DiagnosticRunCoordinator, val run: Run) :
        AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Element>
    }
    private class Value<T>(val value: T)
    private val lock = Any()
    private var active: Run? = null

    suspend fun <T> run(limits: Limits, block: suspend (Run) -> T): T {
        val inherited = currentCoroutineContext()[Element]
        if (inherited != null && inherited.owner === this) {
            inherited.run.checkOpen()
            return withTimeoutOrNull(limits.timeMs) {
                Value(block(inherited.run).also { inherited.run.checkBudget() })
            }?.value ?: throw Unavailable(Reason.TIMED_OUT)
        }
        val run = synchronized(lock) {
            if (active != null) throw Unavailable(Reason.BUSY)
            Run(limits.payloadBytes).also { active = it }
        }
        var registration: AutoCloseable? = null
        try {
            val job = currentCoroutineContext().job
            registration = playback.register { job.cancel(CancellationException("Playback started")) }
                ?: throw Unavailable(Reason.PLAYBACK_ACTIVE)
            return withTimeoutOrNull(limits.timeMs) {
                withContext(Element(this@DiagnosticRunCoordinator, run)) {
                    Value(block(run).also { run.checkBudget() })
                }
            }?.value ?: throw Unavailable(Reason.TIMED_OUT)
        } finally {
            registration?.close()
            run.finish()
        }
    }

    inner class Run internal constructor(private val maximum: Long) {
        private val serial = Semaphore(1)
        private var reserved = 0L
        private var work = 0
        private var finished = false
        private var payloadLimited = false
        @Synchronized fun checkOpen() { if (finished) throw Unavailable(Reason.BUSY) }
        @Synchronized fun checkBudget() { if (payloadLimited) throw Unavailable(Reason.PAYLOAD_LIMIT) }
        @Synchronized fun reservedPayloadBytes(): Long = reserved
        @Synchronized fun isPayloadLimited(): Boolean = payloadLimited

        /** Charge each phase's enforced ceiling, including failed attempts and retries.
         * No refund assumes a timed-out worker has stopped. This is an allowance, not a byte metric. */
        suspend fun acquire(payloadCeiling: Long): AutoCloseable {
            require(payloadCeiling >= 0)
            serial.acquire() // Cancelled waits release no permit; the run's deadline includes this wait.
            try {
                synchronized(this) {
                    checkOpen()
                    if (payloadCeiling > maximum - reserved) {
                        payloadLimited = true
                        throw Unavailable(Reason.PAYLOAD_LIMIT)
                    }
                    reserved += payloadCeiling
                    work++
                }
            } catch (t: Throwable) { serial.release(); throw t }
            val closed = AtomicBoolean()
            return AutoCloseable {
                if (closed.compareAndSet(false, true)) {
                    synchronized(this) { work--; releaseIfFinished() }
                    serial.release()
                }
            }
        }
        @Synchronized internal fun finish() { finished = true; releaseIfFinished() }
        private fun releaseIfFinished() {
            if (finished && work == 0) synchronized(lock) {
                if (active === this) active = null
            }
        }
    }
    companion object {
        val shared = DiagnosticRunCoordinator(DiagnosticPlaybackGuard.shared)
        // At most eight 72MiB transfer allowances, including the baseline and retries.
        val COMPARISON = Limits(120_000, 576L * 1024 * 1024)
    }
}

internal fun DiagnosticRunCoordinator.Unavailable.displayMessage(context: Context): String = context.getString(
    when (reason) {
        DiagnosticRunCoordinator.Reason.BUSY -> R.string.network_test_busy
        DiagnosticRunCoordinator.Reason.PLAYBACK_ACTIVE -> R.string.network_test_playback_active
        DiagnosticRunCoordinator.Reason.TIMED_OUT -> R.string.diagnostic_run_timed_out
        DiagnosticRunCoordinator.Reason.PAYLOAD_LIMIT -> R.string.diagnostic_run_payload_limit
    }
)
