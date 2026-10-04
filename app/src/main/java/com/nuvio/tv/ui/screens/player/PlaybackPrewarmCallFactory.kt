package com.nuvio.tv.ui.screens.player

import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import okio.Timeout
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/** Warm-up TLS fallback stays inside one cancellation owner and one elapsed-time budget. */
internal class PlaybackPrewarmCallFactory(
    private val primary: Call.Factory,
    private val fallback: () -> Call.Factory,
    private val nanoTime: () -> Long = System::nanoTime
) : Call.Factory {
    override fun newCall(request: Request): Call = WarmCall(request)

    private inner class WarmCall(
        private val request: Request,
        private val primaryCall: Call = primary.newCall(request)
    ) : Call by primaryCall {
        private val lock = Any()
        private var active: Call? = primaryCall
        private var executed = false
        private var cancelled = false
        private var completed = false
        private val budget = Timeout().timeout(15, TimeUnit.SECONDS)

        override fun request() = request
        override fun timeout() = budget
        override fun isExecuted() = synchronized(lock) { executed }
        override fun isCanceled() = synchronized(lock) { cancelled }
        override fun clone(): Call = newCall(request)
        override fun execute(): Response = throw UnsupportedOperationException("Warm-up calls are asynchronous")

        override fun cancel() = synchronized(lock) {
            cancelled = true
            active?.cancel()
            Unit
        }

        override fun enqueue(responseCallback: Callback) {
            synchronized(lock) { check(!executed) { "Already executed" }; executed = true }
            val start = nanoTime()
            val totalBudget = budget.timeoutNanos().takeIf { it > 0 } ?: TimeUnit.SECONDS.toNanos(15)
            fun remaining(): Long {
                val elapsedBudget = totalBudget - (nanoTime() - start)
                return if (budget.hasDeadline()) minOf(elapsedBudget, budget.deadlineNanoTime() - nanoTime())
                else elapsedBudget
            }
            fun fail(error: IOException) {
                val deliver = synchronized(lock) {
                    if (completed) false else { completed = true; active = null; true }
                }
                if (deliver) responseCallback.onFailure(this, error)
            }
            fun launch(factory: () -> Call.Factory, mayFallback: Boolean) {
                val call = try {
                    synchronized(lock) {
                        val left = remaining()
                        if (cancelled || left <= 0L) null else {
                            factory().newCall(request).also {
                                it.timeout().timeout(left, TimeUnit.NANOSECONDS)
                                active = it
                            }
                        }
                    }
                } catch (e: RuntimeException) {
                    fail(IOException("Warm-up call creation failed", e)); return
                }
                if (call == null) { fail(IOException("Warm-up cancelled or timed out")); return }
                try {
                    call.enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            if (mayFallback && e is SSLException && !isCanceled() && remaining() > 0L) {
                                launch(fallback, false)
                            } else fail(e)
                        }
                        override fun onResponse(call: Call, response: Response) {
                            if (isCanceled() || remaining() <= 0L) {
                                call.cancel()
                                response.close()
                                fail(IOException("Warm-up cancelled or timed out"))
                                return
                            }
                            try {
                                // Keep active until the consumer finishes reading/closing the body.
                                responseCallback.onResponse(this@WarmCall, response)
                            } finally {
                                synchronized(lock) { completed = true; active = null }
                            }
                        }
                    })
                } catch (e: RuntimeException) {
                    call.cancel()
                    fail(IOException("Warm-up enqueue failed", e))
                }
            }
            launch({ Call.Factory { primaryCall } }, true)
        }
    }
}
