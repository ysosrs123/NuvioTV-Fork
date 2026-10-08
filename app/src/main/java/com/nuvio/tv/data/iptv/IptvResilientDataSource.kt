package com.nuvio.tv.data.iptv

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import com.nuvio.tv.core.iptv.LiveAltSvc
import com.nuvio.tv.core.iptv.LiveReconnect
import com.nuvio.tv.core.iptv.LiveTsSync
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

class IptvStreamSyncException : IOException("Transport stream sync lost")

class IptvResilientDataSource(private val upstream: DataSource, private val enabled: (DataSpec) -> Boolean, private val bufferedMs: () -> Long,
    private val active: () -> Boolean, private val onReconnect: () -> Unit = {},
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 }, private val sleep: (Long) -> Unit = { Thread.sleep(it) }) : DataSource {
    private val buffer = ByteArray(LiveTsSync.PACKET * 64)
    private var spec: DataSpec? = null
    private var resilient = false
    private var opened = false
    private var start = 0
    private var end = 0
    private var syncFrom = 0
    private var synced = false
    private var inPacket = 0
    private var goodBytes = 0L
    private var episodeAt: Long? = null
    private var window = 0L
    private var attempt = 0

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
        resilient = enabled(dataSpec)
        start = 0; end = 0; syncFrom = 0; synced = false; inPacket = 0; goodBytes = 0; episodeAt = null; attempt = 0
        val length = upstream.open(dataSpec)
        opened = true
        return if (resilient) C.LENGTH_UNSET.toLong() else length
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (!resilient) return upstream.read(target, offset, length)
        if (length == 0) return 0
        while (true) {
            val ready = LiveTsSync.ready((if (synced) end else syncFrom) - start, inPacket)
            if (ready > 0) {
                val count = minOf(length, ready)
                System.arraycopy(buffer, start, target, offset, count)
                start += count
                inPacket = (inPacket + count) % LiveTsSync.PACKET
                if (start == end) { start = 0; end = 0; syncFrom = 0 }
                return count
            }
            val more = try { fill() } catch (error: IOException) { reconnect(error); continue }
            if (!more) reconnect(null)
        }
    }

    private fun fill(): Boolean {
        if (start > 0) {
            System.arraycopy(buffer, start, buffer, 0, end - start)
            end -= start; syncFrom = (syncFrom - start).coerceAtLeast(0); start = 0
        }
        if (end == buffer.size) throw IptvStreamSyncException()
        val count = upstream.read(buffer, end, buffer.size - end)
        if (count == C.RESULT_END_OF_INPUT) return false
        end += count
        goodBytes += count
        if (!synced) {
            val found = LiveTsSync.find(buffer, syncFrom, end)
            if (found >= 0) {
                System.arraycopy(buffer, found, buffer, syncFrom, end - found)
                end -= found - syncFrom
                synced = true
            } else if (end - syncFrom > SEARCH_LIMIT) throw IptvStreamSyncException()
        }
        return true
    }

    private fun reconnect(cause: IOException?) {
        var failure = cause
        while (true) {
            val status = (failure as? HttpDataSource.InvalidResponseCodeException)?.responseCode
            if (!active() || Thread.currentThread().isInterrupted || failure is IptvStreamSyncException ||
                failure is HttpDataSource.CleartextNotPermittedException || (status != null && LiveReconnect.handOver(status)))
                throw failure ?: InterruptedIOException("Live stream closed")
            val now = clock()
            val began = episodeAt
            if (began == null || goodBytes >= LiveReconnect.STEADY_BYTES) { episodeAt = now; window = LiveReconnect.window(bufferedMs()); attempt = 0 }
            val wait = LiveReconnect.next(attempt, now - (episodeAt ?: now), window) ?: throw failure ?: IOException("Live stream ended")
            closeUpstream()
            end = start + LiveTsSync.ready((if (synced) end else syncFrom) - start, inPacket)
            syncFrom = end; synced = false
            if (wait > 0) try { sleep(wait) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt(); throw InterruptedIOException("Live reconnect interrupted")
            }
            attempt++; goodBytes = 0
            if (!active()) throw InterruptedIOException("Live stream closed")
            try {
                upstream.open(requireNotNull(spec))
                opened = true
                onReconnect()
                return
            } catch (error: IOException) { failure = error }
        }
    }

    private fun closeUpstream() {
        if (!opened) return
        opened = false
        try { upstream.close() } catch (_: IOException) {}
    }

    override fun getUri(): Uri? = upstream.uri ?: spec?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        spec = null; resilient = false; start = 0; end = 0; syncFrom = 0
        opened = false
        upstream.close()
    }

    private companion object { const val SEARCH_LIMIT = LiveTsSync.PACKET * 24 }
}

class IptvLiveSocketFactory(private val receiveBytes: Int, private val delegate: SocketFactory = getDefault()) : SocketFactory() {
    private fun tune(socket: Socket): Socket = socket.apply { runCatching { receiveBufferSize = receiveBytes } }
    override fun createSocket(): Socket = tune(delegate.createSocket())
    override fun createSocket(host: String?, port: Int): Socket = tune(delegate.createSocket(host, port))
    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket = tune(delegate.createSocket(host, port, localHost, localPort))
    override fun createSocket(host: InetAddress?, port: Int): Socket = tune(delegate.createSocket(host, port))
    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        tune(delegate.createSocket(address, port, localAddress, localPort))

    companion object {
        const val LOW_MEMORY_BYTES = 1024 * 1024
        const val NORMAL_BYTES = 4 * 1024 * 1024
    }
}

class IptvStreamNetwork : Interceptor {
    @Volatile var protocol: String? = null
        private set

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        protocol = response.protocol.toString()
        val url = chain.request().url.toString()
        if (altSvc.get(url) == null) {
            val h3 = LiveAltSvc.advertisesH3(response.header("Alt-Svc"))
            altSvc.put(url, if (h3) YES else NO)
            IptvLog.info("alt-svc h3=${if (h3) YES else NO} proto=$protocol")
        }
        return response
    }

    fun h3(url: String): Boolean? = altSvc.get(url)?.let { it == YES }

    companion object {
        private const val YES = "yes"
        private const val NO = "no"
        private val altSvc = IptvHostMemory("alt-svc:", null)
    }
}

class IptvLiveCalls : EventListener.Factory {
    private val calls = HashSet<Call>()
    private var closed = false

    val count: Int @Synchronized get() = calls.size

    override fun create(call: Call): EventListener = object : EventListener() {
        override fun callStart(call: Call) = started(call)
        override fun callEnd(call: Call) = ended(call)
        override fun callFailed(call: Call, ioe: IOException) = ended(call)
    }

    fun cancelAll() {
        val running = synchronized(this) { closed = true; calls.toList() }
        running.forEach { it.cancel() }
    }

    private fun started(call: Call) {
        val late = synchronized(this) { calls += call; closed }
        if (late) call.cancel()
    }

    @Synchronized private fun ended(call: Call) { calls -= call }
}

class IptvWarmSocket : Socket() {
    override fun connect(endpoint: SocketAddress?, timeout: Int) {
        if (isConnected) {
            if (endpoint == remoteSocketAddress) return
            close()
            throw ConnectException("Prepared connection does not match")
        }
        super.connect(endpoint, timeout)
    }
}

class IptvWarmConnections(private val receiveBytes: Int, private val resolve: (String) -> List<InetAddress> = { Dns.SYSTEM.lookup(it) },
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 }, private val maxAgeMs: Long = MAX_AGE_MS,
    private val connectMs: Int = CONNECT_MS) {
    private class Entry(val host: String, val port: Int, val socket: IptvWarmSocket, val at: Long)
    private val entries = ArrayList<Entry>()
    private val resolved = HashMap<String, Pair<List<InetAddress>, Long>>()
    private val redirectTargets = object : LinkedHashMap<String, Pair<String, Int>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String, Int>>?) = size > MAX_REDIRECTS
    }
    private var armed: Entry? = null
    private var armedAt = 0L
    private var generation = 0L
    private var worker: ScheduledExecutorService? = null
    private val tuned = IptvLiveSocketFactory(receiveBytes)
    @Volatile var used = 0
        private set

    val dns: Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val now = clock()
            synchronized(this@IptvWarmConnections) {
                expire(now)
                entries.firstOrNull { it.host == hostname }?.let { entry ->
                    armed = entry; armedAt = now
                    return listOf(requireNotNull(entry.socket.inetAddress))
                }
                resolved[hostname]?.takeIf { now - it.second <= RESOLVED_MS }?.let { return it.first }
            }
            return resolve(hostname)
        }
    }

    val sockets: SocketFactory = object : SocketFactory() {
        override fun createSocket(): Socket = claim() ?: tuned.createSocket()
        override fun createSocket(host: String?, port: Int): Socket = tuned.createSocket(host, port)
        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket = tuned.createSocket(host, port, localHost, localPort)
        override fun createSocket(host: InetAddress?, port: Int): Socket = tuned.createSocket(host, port)
        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
            tuned.createSocket(address, port, localAddress, localPort)
    }

    val redirects = Interceptor { chain ->
        val request = chain.request()
        chain.proceed(request).also { response ->
            if (response.isRedirect) response.header("Location")?.let(request.url::resolve)?.let { target ->
                if (target.host != request.url.host || target.port != request.url.port)
                    synchronized(this) { redirectTargets["${request.url.host}:${request.url.port}"] = target.host to target.port }
            }
        }
    }

    fun warm(url: String) {
        val parsed = url.toHttpUrlOrNull() ?: return
        val targets = synchronized(this) {
            generation++
            val wanted = listOfNotNull(parsed.host to parsed.port, redirectTargets["${parsed.host}:${parsed.port}"])
                .distinctBy { it.first }.take(MAX_WARM)
            val now = clock()
            expire(now)
            entries.filter { entry -> wanted.none { it.first == entry.host && it.second == entry.port } }.forEach(::discard)
            wanted.filter { target -> entries.none { it.host == target.first && it.port == target.second } }
        }
        if (targets.isEmpty()) return
        val ticket = synchronized(this) { generation }
        val executor = executor()
        targets.forEach { (host, port) -> executor.execute { open(host, port, ticket) } }
        executor.schedule({ synchronized(this) { expire(clock()) } }, maxAgeMs + 50, TimeUnit.MILLISECONDS)
    }

    fun drop() {
        val closing = synchronized(this) {
            generation++
            armed = null
            entries.toList().also { entries.clear() }
        }
        if (closing.isNotEmpty()) executor().execute { closing.forEach { runCatching { it.socket.close() } } }
    }

    @Synchronized fun warmCount(): Int = entries.size

    private fun open(host: String, port: Int, ticket: Long) {
        if (synchronized(this) { ticket != generation }) return
        val socket = IptvWarmSocket()
        try {
            val addresses = resolve(host)
            synchronized(this) { resolved[host] = addresses to clock() }
            val address = addresses.firstOrNull()
            if (address == null || synchronized(this) { ticket != generation }) { socket.close(); return }
            runCatching { socket.receiveBufferSize = receiveBytes }
            socket.connect(InetSocketAddress(address, port), connectMs)
        } catch (_: Exception) { runCatching { socket.close() }; return }
        val keep = synchronized(this) {
            (ticket == generation && entries.none { it.host == host }).also { if (it) entries += Entry(host, port, socket, clock()) }
        }
        if (!keep) runCatching { socket.close() }
    }

    private fun claim(): Socket? {
        val entry = synchronized(this) {
            val now = clock()
            val candidate = armed?.takeIf { now - armedAt <= ARM_MS && it in entries && now - it.at <= maxAgeMs }
            armed = null
            candidate?.also { entries.remove(it) }
        } ?: return null
        val socket = entry.socket
        val alive = try {
            socket.soTimeout = 1
            socket.getInputStream().read()
            false
        } catch (_: SocketTimeoutException) { true } catch (_: Exception) { false }
        if (!alive) { runCatching { socket.close() }; return null }
        used++
        return socket
    }

    private fun expire(now: Long) {
        entries.filter { now - it.at > maxAgeMs || it.socket.isClosed }.forEach(::discard)
        resolved.entries.removeAll { now - it.value.second > RESOLVED_MS }
    }

    private fun discard(entry: Entry) {
        entries.remove(entry)
        if (armed === entry) armed = null
        runCatching { entry.socket.close() }
    }

    @Synchronized private fun executor(): ScheduledExecutorService = worker ?: Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "IptvWarm").apply { isDaemon = true }
    }.also { worker = it }

    private companion object {
        const val MAX_AGE_MS = 10_000L
        const val CONNECT_MS = 3_000
        const val ARM_MS = 5_000L
        const val RESOLVED_MS = 60_000L
        const val MAX_WARM = 2
        const val MAX_REDIRECTS = 32
    }
}

object IptvLiveNet {
    private var base: OkHttpClient? = null
    private var warm: IptvWarmConnections? = null

    @Synchronized fun client(receiveBytes: Int): OkHttpClient = base ?: connections(receiveBytes).let { connections ->
        OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true).followRedirects(true).followSslRedirects(true)
            .connectionPool(ConnectionPool(POOL_IDLE, POOL_KEEP_ALIVE_S, TimeUnit.SECONDS))
            .dispatcher(Dispatcher().apply { maxRequests = 64; maxRequestsPerHost = 16 })
            .dns(connections.dns).socketFactory(connections.sockets).addNetworkInterceptor(connections.redirects).build()
    }.also { base = it }

    fun warm(url: String, receiveBytes: Int) = connections(receiveBytes).warm(url)

    @Synchronized fun drop() { warm?.drop() }

    val warmUsed: Int @Synchronized get() = warm?.used ?: 0

    @Synchronized private fun connections(receiveBytes: Int): IptvWarmConnections = warm ?: IptvWarmConnections(receiveBytes).also { warm = it }

    private const val POOL_IDLE = 4
    private const val POOL_KEEP_ALIVE_S = 30L
}
