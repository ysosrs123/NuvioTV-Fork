package com.nuvio.tv.ui.screens.player

import android.os.SystemClock
import android.util.Log
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Logs one summary line per connection open on the playback path (the probe,
 * chunk 0 and the tail continuation sit on the startup critical path). Per
 * call rather than per callback, to keep the line count low on long files.
 *
 *  - pooled=true with no connect phase is a ConnectionPool reuse.
 *  - opens>1 in a single call is a redirect to a different address.
 *  - proto is the negotiated protocol.
 *  - range distinguishes the prewarm (bytes=0-0) from the probe and chunks.
 *  - preDns / route / ttfb / acqToHdr split the time outside dns/tcp/tls:
 *    callStart->dnsStart, dnsEnd->connectStart, connectEnd->responseHeadersEnd.
 *    acqToHdr also covers pooled calls, which emit no dns/connect events.
 */
internal class PlaybackConnectionEventListener(
    private val id: Long
) : EventListener() {
    // One terminal emission per call, ever.
    private val emitted = AtomicBoolean(false)


    private var callT0 = 0L
    private var dnsT0 = 0L
    private var connT0 = 0L
    private var tlsT0 = 0L

    // Absolute stamps so the residual time splits into named windows.
    // dnsEndT/connEndT/headersT
    // bracket window 2 (route planning) and window 3 (request write + TTFB);
    // acqT prices connection acquisition on BOTH cold and pooled calls --
    // dns/connect events never fire on a pooled call, so acqT is the only
    // stamp that decomposes the pooled 265-375 ms call-to-headers.
    private var dnsEndT = 0L
    private var connEndT = 0L
    private var headersT = 0L
    private var acqT = 0L

    private var dnsMs = -1L
    private var connMs = -1L
    private var tlsMs = -1L
    private var headersMs = -1L

    private var opens = 0
    private var range: String? = null
    private var host: String? = null
    private var proto: String? = null
    // How many playback calls were already in flight when this one started
    private var inflightAtStart = -1
    private var hostInflightAtStart = -1
    private var code = -1

    private fun now() = SystemClock.elapsedRealtime()

    override fun callStart(call: Call) {
        callT0 = now()
        val request = call.request()
        range = request.header("Range")
        val reqHost = request.url.host
        host = reqHost
        val before = PlaybackConnectionEvents.enter(reqHost)
        inflightAtStart = before[0]
        hostInflightAtStart = before[1]
    }

    override fun dnsStart(call: Call, domainName: String) {
        dnsT0 = now()
    }

    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
        dnsEndT = now()
        dnsMs = if (dnsT0 > 0L) (dnsEndT - dnsT0).coerceAtLeast(0L) else -1L
    }

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        opens += 1
        connT0 = now()
    }

    override fun secureConnectStart(call: Call) {
        tlsT0 = now()
    }

    override fun secureConnectEnd(call: Call, handshake: Handshake?) {
        tlsMs = if (tlsT0 > 0L) (now() - tlsT0).coerceAtLeast(0L) else -1L
    }

    override fun connectEnd(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?
    ) {
        connEndT = now()
        connMs = if (connT0 > 0L) (connEndT - connT0).coerceAtLeast(0L) else -1L
        if (protocol != null) proto = protocol.toString()
    }

    override fun connectFailed(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
        ioe: IOException
    ) {
        connMs = if (connT0 > 0L) (now() - connT0).coerceAtLeast(0L) else -1L
        val currentHost = host ?: "unknown"
        val msg = "NET_CONN id=$id connectFailed after ${connMs}ms host=$currentHost err=${ioe.message}"
        Log.w(TAG, msg)
        PlaybackConnectionEvents.recordEvent(msg)
    }

    override fun connectionAcquired(call: Call, connection: Connection) {
        acqT = now()
        if (proto == null) proto = connection.protocol().toString()
    }

    override fun responseHeadersEnd(call: Call, response: Response) {
        headersT = now()
        headersMs = if (callT0 > 0L) (headersT - callT0).coerceAtLeast(0L) else -1L
        code = response.code
        if (!response.isRedirect) {
            PlaybackConnectionEvents.setResolvedHost(response.request.url.host)
        }
    }

    override fun callEnd(call: Call) {
        emit("ok")
    }

    override fun callFailed(call: Call, ioe: IOException) {
        emit("failed")
    }

    override fun canceled(call: Call) {
        emit("canceled")
    }

    private fun emit(outcome: String) {
        if (!emitted.compareAndSet(false, true)) return

        val currentHost = host ?: "unknown"
        if (callT0 > 0L) {
            PlaybackConnectionEvents.exit(currentHost)
        }

        val totalMs = if (callT0 > 0L) (now() - callT0).coerceAtLeast(0L) else -1L
        val rangeLabel = range ?: "none"
        val protoLabel = proto ?: "unknown"
        // Derived windows; -1 when the bracketing events never fired
        // (e.g. dns/connect on a pooled call). preDns = dispatcher/queue wait,
        // route = dnsEnd->connectStart, ttfb = connectEnd->headers (request
        // write + server think), acqToHdr = acquisition->headers, the only
        // decomposition available on a pooled call.
        val preDnsMs = if (dnsT0 > 0L && callT0 > 0L) (dnsT0 - callT0).coerceAtLeast(0L) else -1L
        val routeMs = if (dnsEndT > 0L && connT0 > 0L) (connT0 - dnsEndT).coerceAtLeast(0L) else -1L
        val ttfbMs = if (connEndT > 0L && headersT > 0L) (headersT - connEndT).coerceAtLeast(0L) else -1L
        val acqToHdrMs = if (acqT > 0L && headersT > 0L) (headersT - acqT).coerceAtLeast(0L) else -1L
        val logLine = "NET_CONN id=$id outcome=$outcome pooled=${opens == 0} opens=$opens " +
            "dns=${dnsMs}ms connect=${connMs}ms tls=${tlsMs}ms " +
            "headers=${headersMs}ms total=${totalMs}ms " +
            "preDns=${preDnsMs}ms route=${routeMs}ms ttfb=${ttfbMs}ms acqToHdr=${acqToHdrMs}ms " +
            "inflight=$inflightAtStart hostInflight=$hostInflightAtStart " +
            "code=$code proto=$protoLabel range=$rangeLabel host=$currentHost"
        // Logcat stays the primary emission; log captures read NET_CONN from
        // logcat. The in-app ring buffer is additive (feeds the in-app
        // network log surface).
        Log.i(TAG, logLine)
        PlaybackConnectionEvents.recordEvent(logLine)
    }

    private companion object {
        const val TAG = "NuvioNet"
    }
}

/**
 * One listener instance per call, so the per-call state above needs no
 * synchronisation. Attached to PlayerPlaybackNetworking.playbackHttpClient,
 * which every playback client is derived from via newBuilder() -- and
 * OkHttpClient.Builder(client) copies eventListenerFactory, while
 * applyNetworkOptimizations sets no listener, so nothing overwrites it.
 */
internal object PlaybackConnectionEvents : EventListener.Factory {
    private val seq = AtomicLong(0L)

    // preDns is dispatcher/queue wait. To attribute it, track
    // how many playback calls are already in flight - total, and to the same
    // host - when each call starts. A high count when preDns is high means the
    // wait is concurrency/per-host-cap contention (fixable with a dedicated
    // dispatcher or a higher maxRequestsPerHost); a low count means the wait is
    // upstream of the dispatcher. Balanced 1:1: enter() at callStart, exit() in
    // emit() (the single terminal path for callEnd and callFailed).
    private val inflightTotal = AtomicInteger(0)
    private val inflightPerHost = ConcurrentHashMap<String, AtomicInteger>()

    // Host that served the bytes on the last non-redirect hop of a
    // call. Written from OkHttp dispatcher threads (responseHeadersEnd), read
    // by the ~1 Hz HUD sampler, cleared per play session. The redirector
    // sends cf-cache-status: BYPASS so the CDN rotates per session -- read
    // live, never cached -- which the per-session clear satisfies.
    @Volatile private var resolvedServingHost: String? = null
    fun setResolvedHost(host: String?) { resolvedServingHost = host?.takeIf { it.isNotBlank() } }
    fun resolvedHost(): String? = resolvedServingHost
    fun clearResolvedHost() { resolvedServingHost = null }

    // Bounded in-app ring of recent NET_CONN lines. Additive to (never
    // a replacement for) the logcat emission above.
    private val recentLogs = ConcurrentLinkedDeque<String>()
    private const val MAX_RECENT_LOGS = 50

    fun recordEvent(msg: String) {
        recentLogs.addLast(msg)
        while (recentLogs.size > MAX_RECENT_LOGS) {
            recentLogs.pollFirst()
        }
    }

    fun recentEvents(): List<String> = recentLogs.toList()

    fun clear() {
        recentLogs.clear()
        resolvedServingHost = null
        inflightTotal.set(0)
        inflightPerHost.clear()
    }

    /** Returns [totalBefore, hostBefore] - the counts prior to this call. */
    fun enter(host: String): IntArray {
        val hostCounter = inflightPerHost.getOrPut(host) { AtomicInteger(0) }
        val totalBefore = inflightTotal.getAndIncrement().coerceAtLeast(0)
        val hostBefore = hostCounter.getAndIncrement().coerceAtLeast(0)
        return intArrayOf(totalBefore, hostBefore)
    }

    fun exit(host: String) {
        inflightTotal.updateAndGet { if (it > 0) it - 1 else 0 }
        inflightPerHost[host]?.updateAndGet { if (it > 0) it - 1 else 0 }
    }

    override fun create(call: Call): EventListener =
        PlaybackConnectionEventListener(seq.incrementAndGet())
}
