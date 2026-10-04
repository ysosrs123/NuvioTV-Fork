package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.network.DiagnosticPayloadBudget
import java.io.IOException
import okhttp3.Callback
import okhttp3.Response
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One test cell owns these HTTP resources, including its established TLS fallback policy. */
internal class ParallelDiagnosticCalls(
    connections: Int,
    primary: OkHttpClient = PlayerPlaybackNetworking.playbackHttpClient,
    private val payload: DiagnosticPayloadBudget? = null,
    fallback: () -> OkHttpClient = { PlayerPlaybackNetworking.trustAllPlaybackHttpClient }
) : Call.Factory, AutoCloseable {
    init { require(connections in 1..16) }
    private val closed = AtomicBoolean(false)
    private val callLock = Any()
    private val ownedCalls = mutableListOf<Call>()
    // Keep raw calls through body consumption; Media3 returns from onResponse after headers.
    // This also bounds retained call references and TLS fallback attempts per cell.
    private fun tracked(client: OkHttpClient) = Call.Factory { request ->
        synchronized(callLock) {
            check(!closed.get()) { "Diagnostic HTTP owner is closed" }
            check(ownedCalls.size < 128) { "Diagnostic cell request limit reached" }
            client.newCall(request).also { ownedCalls.add(it) }.let { raw ->
                object : Call by raw {
                    override fun enqueue(responseCallback: Callback) {
                        raw.enqueue(object : Callback {
                            override fun onFailure(call: Call, e: IOException) = responseCallback.onFailure(call, e)
                            override fun onResponse(call: Call, response: Response) {
                                if (response.headers.values("Content-Encoding").any { !it.trim().equals("identity", true) }) {
                                    call.cancel(); response.close()
                                    responseCallback.onFailure(call, IOException("Encoded diagnostic response"))
                                    return
                                }
                                val body = response.body
                                responseCallback.onResponse(call, if (payload != null && body != null)
                                    response.newBuilder().body(payload.wrap(body)).build() else response)
                            }
                        })
                    }
                }
            }
        }
    }
    private val executor = ThreadPoolExecutor(
        connections, connections, 30L, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(64),
        { task -> Thread(task, "parallel-test-http").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()
    ).apply { allowCoreThreadTimeOut(true) }
    private val dispatcher = Dispatcher(executor).apply {
        maxRequests = connections
        maxRequestsPerHost = connections
    }
    private val pool = ConnectionPool(0, 1L, TimeUnit.SECONDS)
    private fun isolated(client: OkHttpClient) = client.newBuilder()
        .apply { interceptors().clear(); networkInterceptors().clear() }
        .dispatcher(dispatcher).connectionPool(pool).cache(null).eventListener(EventListener.NONE)
        .retryOnConnectionFailure(false).callTimeout(15, TimeUnit.SECONDS)
        .connectTimeout(3, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.SECONDS).build()
    private val calls = PlaybackPrewarmCallFactory(tracked(isolated(primary)), {
        check(!closed.get()) { "Diagnostic HTTP owner is closed" }
        tracked(isolated(fallback()))
    })
    override fun newCall(request: Request): Call {
        check(!closed.get()) { "Diagnostic HTTP owner is closed" }
        return calls.newCall(request.newBuilder().header("Accept-Encoding", "identity").build())
    }
    val isQuiescent: Boolean get() = closed.get() && executor.isTerminated

    override fun close() {
        val calls = synchronized(callLock) {
            if (!closed.compareAndSet(false, true)) return
            ownedCalls.toList().also { ownedCalls.clear() }
        }
        try {
            calls.forEach(Call::cancel)
            dispatcher.cancelAll()
            pool.evictAll()
        } finally {
            // Let queued cancelled calls deliver their failure callback; do not discard them.
            executor.shutdown()
        }
    }
}
