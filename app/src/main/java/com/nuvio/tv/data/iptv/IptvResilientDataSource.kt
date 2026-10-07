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
import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory
import okhttp3.Interceptor
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
