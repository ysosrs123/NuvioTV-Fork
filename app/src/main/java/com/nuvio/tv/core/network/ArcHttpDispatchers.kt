package com.nuvio.tv.core.network

import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Each OkHttp call thread holds its own stack, and a 32-bit process runs out of address space for them first. */
object ArcHttpDispatchers {
    const val SMALL_IMAGE_REQUESTS = 12
    const val SMALL_SHARED_REQUESTS = 16
    /** Every API client shares this host limit on 32-bit: add-ons on one host, TMDB, Trakt and a Party socket. */
    const val SMALL_SHARED_REQUESTS_PER_HOST = 8
    private const val SMALL_STACK_BYTES = 512L * 1024

    private val is64Bit: Boolean by lazy { runCatching { android.os.Process.is64Bit() }.getOrDefault(true) }

    fun maxRequestsFor(is64Bit: Boolean, normal: Int, small: Int): Int =
        if (is64Bit) normal else minOf(normal, small)

    fun image(normalMaxRequests: Int, maxRequestsPerHost: Int): Dispatcher {
        val max = maxRequestsFor(is64Bit, normalMaxRequests, SMALL_IMAGE_REQUESTS)
        return newDispatcher("OkHttp Image").apply {
            maxRequests = max
            this.maxRequestsPerHost = minOf(maxRequestsPerHost, max)
        }
    }

    private val shared: Dispatcher? by lazy {
        if (is64Bit) null else newDispatcher("OkHttp Dispatcher").apply {
            maxRequests = SMALL_SHARED_REQUESTS
            maxRequestsPerHost = SMALL_SHARED_REQUESTS_PER_HOST
        }
    }

    /** On 32-bit the plain API clients share one dispatcher; 64-bit keeps OkHttp's own per client. */
    fun OkHttpClient.Builder.withSharedDispatcher(): OkHttpClient.Builder = apply { shared?.let { dispatcher(it) } }

    private fun newDispatcher(name: String): Dispatcher =
        if (is64Bit) Dispatcher() else Dispatcher(smallStackExecutor(name))

    private fun smallStackExecutor(name: String): ThreadPoolExecutor {
        val count = AtomicInteger()
        val factory = ThreadFactory { r ->
            Thread(null, r, "$name ${count.incrementAndGet()}", SMALL_STACK_BYTES).apply { isDaemon = false }
        }
        return ThreadPoolExecutor(0, Int.MAX_VALUE, 60, TimeUnit.SECONDS, SynchronousQueue(), factory)
    }
}
