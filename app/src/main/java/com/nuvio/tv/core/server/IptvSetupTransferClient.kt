package com.nuvio.tv.core.server

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.nuvio.tv.core.iptv.SetupGuard
import com.nuvio.tv.core.iptv.SetupLan
import com.nuvio.tv.core.iptv.SetupTransferCrypto
import com.nuvio.tv.core.iptv.SetupTransferWire
import java.net.Proxy
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject

data class IptvTransferPeer(val name: String, val address: String)

class IptvSetupTransferClient(private val client: OkHttpClient = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).followRedirects(false)
    .followSslRedirects(false).retryOnConnectionFailure(false).connectTimeout(5, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
    .writeTimeout(60, TimeUnit.SECONDS).build()) {

    sealed interface Result {
        data object Sent : Result
        data class WrongCode(val attemptsLeft: Int) : Result
        data object Renewed : Result
        data object Busy : Result
        data object Unreachable : Result
        data object Refused : Result
        data object TooLarge : Result
    }

    fun send(address: String, code: String, payload: ByteArray): Result {
        val base = base(address) ?: return Result.Unreachable
        if (!SetupTransferCrypto.validCode(code)) return Result.WrongCode(-1)
        return try {
            val hello = call(Request.Builder().url("$base/copy/hello").get().build()) { response ->
                if (response.code != 200) null else response.body?.let { limited(it.byteStream(), SetupTransferWire.MAX_JSON_BYTES) }
            } ?: return Result.Unreachable
            val offer = SetupTransferWire.offer(code, hello)
            try {
                val (status, answer) = call(post("$base/copy/offer", offer.body.toRequestBody(JSON))) { response ->
                    response.code to response.body?.let { limited(it.byteStream(), SetupTransferWire.MAX_JSON_BYTES) }.orEmpty()
                }
                when (status) {
                    200 -> Unit
                    403 -> return Result.WrongCode(runCatching { JSONObject(answer).getInt("attemptsLeft") }.getOrDefault(0))
                    410 -> return Result.Renewed
                    409 -> return Result.Busy
                    else -> return Result.Refused
                }
                val ticket = SetupTransferWire.confirm(offer, answer) ?: return Result.Refused
                val box = offer.session.seal(payload)
                if (box.size > SetupTransferWire.MAX_BOX_BYTES) return Result.TooLarge
                val request = Request.Builder().url("$base/copy/payload").header(SetupGuard.HEADER, "1").header(SetupTransferWire.TICKET_HEADER, ticket)
                    .post(box.toRequestBody(BINARY)).build()
                when (call(request) { it.code }) {
                    202 -> Result.Sent
                    409 -> Result.Busy
                    else -> Result.Refused
                }
            } finally { offer.session.close() }
        } catch (_: java.io.IOException) {
            Result.Unreachable
        } catch (_: IllegalArgumentException) {
            Result.Refused
        } catch (_: org.json.JSONException) {
            Result.Refused
        }
    }

    private fun post(url: String, body: okhttp3.RequestBody) = Request.Builder().url(url).header(SetupGuard.HEADER, "1").post(body).build()

    private fun <T> call(request: Request, read: (Response) -> T): T = client.newCall(request).execute().use(read)

    private fun limited(input: java.io.InputStream, limit: Int): String? {
        val bytes = input.readNBytesCompat(limit + 1)
        return if (bytes.size > limit) null else String(bytes, Charsets.UTF_8)
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (out.size() < limit) {
            val count = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (count < 0) break
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }

    companion object {
        private val JSON = "application/json".toMediaType()
        private val BINARY = "application/octet-stream".toMediaType()

        fun base(address: String): String? {
            val text = address.trim().removePrefix("http://").trimEnd('/')
            val host = text.substringBefore(':')
            val port = if (':' in text) text.substringAfter(':').toIntOrNull() ?: return null else IptvSetupTransferServer.START_PORT
            if (!SetupLan.isLanAddress(host) || port !in 1..65535) return null
            return "http://$host:$port"
        }
    }
}

class IptvSetupTransferDiscovery(context: Context) {
    private val manager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private val found = LinkedHashMap<String, IptvTransferPeer>()
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var onChange: ((List<IptvTransferPeer>) -> Unit)? = null
    private var own: String? = null

    @Synchronized fun register(name: String, port: Int, address: String) {
        val nsd = manager ?: return
        unregister()
        own = address
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) = Unit
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) = Unit
        }
        val info = NsdServiceInfo().apply { serviceName = name.take(60); serviceType = SetupTransferWire.SERVICE_TYPE; setPort(port) }
        if (runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }.isSuccess) registration = listener
    }

    @Synchronized fun unregister() {
        registration?.let { listener -> runCatching { manager?.unregisterService(listener) } }
        registration = null
    }

    @Synchronized fun discover(ownAddress: String?, onPeers: (List<IptvTransferPeer>) -> Unit): Boolean {
        val nsd = manager ?: return false
        stopDiscovery()
        own = ownAddress
        found.clear()
        onChange = onPeers
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) = enqueue(info)
            override fun onServiceLost(info: NsdServiceInfo) = lost(info)
        }
        return runCatching { nsd.discoverServices(SetupTransferWire.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }.isSuccess.also { if (it) discovery = listener }
    }

    @Synchronized fun stopDiscovery() {
        discovery?.let { listener -> runCatching { manager?.stopServiceDiscovery(listener) } }
        discovery = null
        onChange = null
        queue.clear()
        resolving = false
    }

    fun close() { stopDiscovery(); unregister() }

    @Synchronized private fun enqueue(info: NsdServiceInfo) {
        if (discovery == null || queue.size >= 16) return
        queue.addLast(info)
        next()
    }

    @Synchronized private fun lost(info: NsdServiceInfo) {
        if (found.remove(info.serviceName) != null) publish()
    }

    @Suppress("DEPRECATION")
    @Synchronized private fun next() {
        val nsd = manager ?: return
        if (resolving || discovery == null) return
        val info = queue.removeFirstOrNull() ?: return
        resolving = true
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = done(null)
            override fun onServiceResolved(info: NsdServiceInfo) = done(info)
        }
        if (runCatching { nsd.resolveService(info, listener) }.isFailure) { resolving = false; next() }
    }

    @Suppress("DEPRECATION")
    @Synchronized private fun done(info: NsdServiceInfo?) {
        resolving = false
        val host = info?.host?.hostAddress
        if (info != null && SetupLan.isLanAddress(host) && info.port in 1..65535 && found.size < 16) {
            val address = "$host:${info.port}"
            if (address != own) { found[info.serviceName] = IptvTransferPeer(info.serviceName.take(60), address); publish() }
        }
        next()
    }

    private fun publish() { onChange?.invoke(found.values.toList()) }
}
