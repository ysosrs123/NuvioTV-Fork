package com.nuvio.tv.data.iptv

import com.nuvio.tv.core.iptv.*
import java.io.InputStream
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

class IptvCaptureHttp(private val http: OkHttpClient = newClient(), private val closeTimeoutMs: Long = 5000) : HlsCaptureHttp {
    init {
        require(!http.followRedirects && !http.followSslRedirects && !http.retryOnConnectionFailure)
        require(http.cookieJar === CookieJar.NO_COOKIES && http.cache == null)
        require(http.authenticator === okhttp3.Authenticator.NONE && http.proxyAuthenticator === okhttp3.Authenticator.NONE)
        require(http.connectTimeoutMillis > 0 && http.readTimeoutMillis > 0 && http.callTimeoutMillis > 0)
        require(closeTimeoutMs > 0)
    }
    private class Slot(val call: Call) {
        @Volatile var response: Response? = null
        @Volatile var connecting = true
        @Volatile var delivered = false
        @Volatile var closeUncertain = false
        var cleanup: Job? = null
    }
    private val slots = mutableSetOf<Slot>()
    private val closing = Mutex()
    private var stopped = false

    override suspend fun open(address: URI, maxBytes: Long): InputStream {
        var opened: Slot? = null
        try { return withContext(Dispatchers.IO) {
        require(maxBytes in 1 until Long.MAX_VALUE)
        val url = address.toString().toHttpUrlOrNull()?.takeIf {
            it.username.isEmpty() && it.password.isEmpty() && it.fragment == null
        } ?: throw HlsCaptureException(HlsCaptureFailure.ADDRESS)
        val request = Request.Builder().url(url).header("Accept-Encoding", "identity")
            .header("Connection", "close").build()
        val slot = synchronized(this@IptvCaptureHttp) {
            if (stopped) throw HlsCaptureException(HlsCaptureFailure.CLOSED)
            check(slots.isEmpty()) { "Only one capture response may be open" }
            Slot(http.newCall(request)).also { slots += it }
        }
        opened = slot
        val context = currentCoroutineContext()
        coroutineScope {
            val cancel = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { if (!slot.delivered) slot.call.cancel() }
            }
            try {
                val response = slot.call.execute()
                slot.response = response
                context.ensureActive()
                if (synchronized(this@IptvCaptureHttp) { stopped }) throw HlsCaptureException(HlsCaptureFailure.CLOSED)
                if (response.code != 200) throw HlsCaptureException(HlsCaptureFailure.HTTP)
                val encoding = response.header("Content-Encoding")
                if (encoding != null && !encoding.equals("identity", ignoreCase = true)) throw HlsCaptureException(HlsCaptureFailure.HTTP)
                val body = response.body
                val declared = body.contentLength()
                if (declared > maxBytes) throw HlsCaptureException(HlsCaptureFailure.LIMIT)
                val input = body.byteStream()
                val stream = object : InputStream() {
                    private var count = 0L
                    private var ended = false
                    private fun checkOpen() {
                        if (ended || slot.call.isCanceled()) throw HlsCaptureException(HlsCaptureFailure.CLOSED)
                    }
                    private fun received(size: Int): Int {
                        if (size > 0) {
                            count += size
                            if (count > maxBytes || (declared >= 0 && count > declared)) throw HlsCaptureException(HlsCaptureFailure.LIMIT)
                        } else if (size < 0 && declared >= 0 && count != declared) {
                            throw HlsCaptureException(HlsCaptureFailure.HTTP)
                        }
                        return size
                    }
                    override fun read(): Int {
                        val bytes = ByteArray(1)
                        return if (read(bytes, 0, 1) < 0) -1 else bytes[0].toInt() and 255
                    }
                    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                        require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
                        if (length == 0) return 0
                        checkOpen()
                        try {
                            return received(input.read(bytes, offset, minOf(length.toLong(), maxBytes - count + 1).toInt()))
                        } catch (error: HlsCaptureException) { throw error }
                        catch (_: Exception) { throw HlsCaptureException(HlsCaptureFailure.NETWORK) }
                    }
                    @Synchronized override fun close() {
                        if (ended) return
                        closeSlot(slot)
                        ended = true
                    }
                }
                slot.delivered = true
                stream
            } catch (error: Exception) {
                if (error is CancellationException || error is HlsCaptureException) throw error
                throw HlsCaptureException(HlsCaptureFailure.NETWORK)
            } finally {
                cancel.cancel()

                if (!slot.delivered) {
                    try { closeSlot(slot) } catch (_: Exception) {                               }
                }
                slot.connecting = false
            }
        }
        } } catch (error: Exception) {
            opened?.delivered = false
            throw error
        }
    }

    override suspend fun close(): Boolean = closing.withLock {
        synchronized(this) { stopped = true; slots.toList() }.forEach { it.call.cancel() }
        val attempted = mutableSetOf<Slot>()
        withTimeoutOrNull(closeTimeoutMs) {
            while (true) {
                val active = synchronized(this@IptvCaptureHttp) { slots.toList() }
                if (active.isEmpty()) return@withTimeoutOrNull true
                for (slot in active) {
                    if (!slot.connecting && !slot.delivered && attempted.add(slot)) synchronized(slot) {
                        if (slot.cleanup?.isCompleted != false) {
                            slot.cleanup = CoroutineScope(Dispatchers.IO).launch {
                                try { closeSlot(slot) } catch (_: Exception) { }
                            }
                        }
                    }
                }
                if (active.all { !it.connecting && !it.delivered && it in attempted && it.cleanup?.isCompleted == true }) {
                    return@withTimeoutOrNull synchronized(this@IptvCaptureHttp) { slots.isEmpty() }
                }
                delay(10)
            }
            @Suppress("UNREACHABLE_CODE") false
        } ?: false
    }

    private fun closeSlot(slot: Slot) {
        if (slot.closeUncertain) throw HlsCaptureException(HlsCaptureFailure.NETWORK)

        try { slot.response?.body?.source()?.close() }
        catch (_: Exception) { slot.closeUncertain = true; throw HlsCaptureException(HlsCaptureFailure.NETWORK) }
        synchronized(this) { slots.remove(slot) }
    }

    companion object {
        fun newClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .cookieJar(CookieJar.NO_COOKIES)
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS).build()
    }
}
