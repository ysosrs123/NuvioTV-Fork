package com.nuvio.tv.ui.screens.player

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * One thread for libmpv calls that wait on mpv's core: tearing an instance down and reading track lists.
 * On a stream that stopped delivering those calls can wait many seconds, so they never run on the main thread.
 * Work runs in order, so a new instance created after [awaitIdle] never overlaps the one released before it.
 */
internal class MpvWorker(
    /** Must run its tasks one at a time, in the order given. */
    private val executor: Executor
) {

    private val pending = AtomicInteger(0)
    private val onWorker = ThreadLocal<Boolean>()

    /** True on the worker thread itself, where waiting for the worker would wait on itself. */
    val isWorkerThread: Boolean get() = onWorker.get() == true

    private fun runMarked(task: Runnable) {
        onWorker.set(true)
        try {
            task.run()
        } finally {
            onWorker.remove()
        }
    }

    private val counting = Executor { task ->
        pending.incrementAndGet()
        try {
            executor.execute {
                try {
                    runMarked(task)
                } finally {
                    pending.decrementAndGet()
                }
            }
        } catch (error: Throwable) {
            pending.decrementAndGet()
            throw error
        }
    }

    val dispatcher: CoroutineDispatcher = counting.asCoroutineDispatcher()

    // Not counted, so [isBusy] is already false when a wait returns.
    private val idleDispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()

    /** True while earlier work, such as a release, has not finished. */
    val isBusy: Boolean get() = pending.get() > 0

    fun release(block: () -> Unit) {
        counting.execute {
            runCatching(block).onFailure { Log.w(TAG, "libmpv release failed: ${it.message}") }
        }
    }

    /** Blocks until everything queued so far has run. Never waits on the worker thread itself. */
    fun awaitIdle() {
        awaitIdle(timeoutMs = Long.MAX_VALUE)
    }

    /** False when the wait ran out first. */
    fun awaitIdle(timeoutMs: Long): Boolean {
        if (!isBusy || isWorkerThread) return true
        val done = CountDownLatch(1)
        executor.execute { done.countDown() }
        return done.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    suspend fun awaitIdleSuspending() {
        if (!isBusy || isWorkerThread) return
        withContext(idleDispatcher) { }
    }

    companion object {
        private const val TAG = "MpvWorker"

        val shared: MpvWorker by lazy {
            MpvWorker(Executors.newSingleThreadExecutor { task -> Thread(task, "mpv-worker").apply { isDaemon = true } })
        }
    }
}
