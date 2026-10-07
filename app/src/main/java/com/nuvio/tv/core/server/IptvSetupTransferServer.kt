package com.nuvio.tv.core.server

import com.nuvio.tv.core.iptv.SetupConnectionLimiter
import com.nuvio.tv.core.iptv.SetupGuard
import com.nuvio.tv.core.iptv.SetupHeaders
import com.nuvio.tv.core.iptv.SetupIdleTimer
import com.nuvio.tv.core.iptv.SetupLan
import com.nuvio.tv.core.iptv.SetupRateLimiter
import com.nuvio.tv.core.iptv.SetupTransferReceiver
import com.nuvio.tv.core.iptv.SetupTransferWire
import fi.iki.elonen.NanoHTTPD
import java.io.InputStream
import java.net.Socket
import org.json.JSONObject

class IptvSetupTransferServer private constructor(
    private val host: String,
    port: Int,
    private val receiver: SetupTransferReceiver,
    private val name: String,
    private val onPayload: (ByteArray) -> Boolean,
    now: () -> Long,
) : NanoHTTPD(host, port) {
    private val idle = SetupIdleTimer(IDLE_TIMEOUT_MILLIS, now)
    private val requests = SetupRateLimiter(REQUESTS_PER_MINUTE, MINUTE, now)
    private val offers = SetupRateLimiter(OFFERS_PER_MINUTE, MINUTE, now)
    private val runner = SetupBoundedRunner(SetupConnectionLimiter(MAX_CLIENTS, MAX_CLIENTS_PER_ADDRESS), CONNECTION_DEADLINE_MILLIS, "IptvCopy")

    init { setAsyncRunner(runner) }

    private val authority: String get() = "$host:$listeningPort"
    val address: String get() = authority
    val port: Int get() = listeningPort
    val code: String get() = receiver.code
    val idleExpired: Boolean get() = idle.expired()

    fun renew() = receiver.renew()

    override fun stop() {
        receiver.close()
        super.stop()
    }

    override fun useGzipWhenAccepted(r: Response): Boolean = false

    override fun createClientHandler(finalAccept: Socket, inputStream: InputStream): ClientHandler {
        val address = finalAccept.inetAddress?.hostAddress
        if (!SetupLan.isLanAddress(address)) runCatching { finalAccept.close() }
        return super.createClientHandler(finalAccept, inputStream).also { runner.address(it, address.orEmpty()) }
    }

    override fun serve(session: IHTTPSession): Response {
        val response = try { route(session) } catch (_: Throwable) { json(Response.Status.INTERNAL_ERROR, error("server")) }
        response.closeConnection(true)
        return response
    }

    private fun route(session: IHTTPSession): Response {
        val remote = session.remoteIpAddress
        if (!SetupLan.isLanAddress(remote)) return json(Response.Status.FORBIDDEN, error("network"))
        if (!SetupGuard.hostMatches(session.headers["host"], authority)) return json(Response.Status.BAD_REQUEST, error("host"))
        if (!requests.allow(remote)) return json(Response.Status.TOO_MANY_REQUESTS, error("rate"))
        val post = session.method == Method.POST
        if (post && session.headers[SetupGuard.HEADER]?.trim() != "1") return json(Response.Status.FORBIDDEN, error("request"))
        return when {
            session.uri == "/copy/hello" && session.method == Method.GET -> {
                idle.touch()
                json(Response.Status.OK, SetupTransferWire.hello(receiver.hello(), name))
            }
            session.uri == "/copy/offer" && post -> offer(session, remote)
            session.uri == "/copy/payload" && post -> payload(session)
            else -> json(Response.Status.NOT_FOUND, error("missing"))
        }
    }

    private fun offer(session: IHTTPSession, remote: String): Response {
        if (!offers.allow(remote)) return json(Response.Status.TOO_MANY_REQUESTS, error("rate"))
        val body = read(session, SetupTransferWire.MAX_JSON_BYTES) ?: return json(Response.Status.BAD_REQUEST, error("body"))
        val input = SetupTransferWire.readOffer(String(body, Charsets.UTF_8)) ?: return json(Response.Status.BAD_REQUEST, error("body"))
        idle.touch()
        return when (val answer = receiver.offer(input.revision, input.key, input.nonce, input.proof)) {
            is SetupTransferReceiver.Offer.Accepted -> json(Response.Status.OK, SetupTransferWire.accepted(answer))
            is SetupTransferReceiver.Offer.Refused -> json(Response.Status.FORBIDDEN, JSONObject().put("error", "code").put("attemptsLeft", answer.attemptsLeft).toString())
            SetupTransferReceiver.Offer.Renewed -> json(Response.Status.GONE, error("renewed"))
            SetupTransferReceiver.Offer.Busy -> json(Response.Status.CONFLICT, error("busy"))
        }
    }

    private fun payload(session: IHTTPSession): Response {
        val ticket = session.headers[SetupTransferWire.TICKET_HEADER]?.trim()?.takeIf { it.matches(TICKET) } ?: return json(Response.Status.BAD_REQUEST, error("ticket"))
        val body = read(session, SetupTransferWire.MAX_BOX_BYTES) ?: return json(Response.Status.PAYLOAD_TOO_LARGE, error("body"))
        idle.touch()
        return when (val delivery = receiver.deliver(ticket, body)) {
            is SetupTransferReceiver.Delivery.Opened -> if (onPayload(delivery.payload)) json(Response.Status.ACCEPTED, JSONObject().put("received", true).toString())
                else json(Response.Status.CONFLICT, error("busy"))
            SetupTransferReceiver.Delivery.Unknown -> json(Response.Status.NOT_FOUND, error("ticket"))
            SetupTransferReceiver.Delivery.Damaged -> json(Response.Status.BAD_REQUEST, error("damaged"))
        }
    }

    private fun read(session: IHTTPSession, limit: Int): ByteArray? {
        if (session.headers.containsKey("transfer-encoding")) return null
        val length = session.headers["content-length"]?.trim()?.toLongOrNull() ?: return null
        if (length < 0 || length > limit) return null
        val bytes = ByteArray(length.toInt())
        val input = session.inputStream
        var read = 0
        while (read < bytes.size) {
            val count = input.read(bytes, read, bytes.size - read)
            if (count < 0) return null
            read += count
        }
        return bytes
    }

    private fun json(status: Response.Status, body: String): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", body).also { response ->
            SetupHeaders.security().forEach { (key, value) -> response.addHeader(key, value) }
        }

    private fun error(code: String): String = JSONObject().put("error", code).toString()

    companion object {
        const val IDLE_TIMEOUT_MILLIS = 10 * 60_000L
        const val START_PORT = 8210
        private const val MINUTE = 60_000L
        private const val MAX_CLIENTS = 4
        private const val MAX_CLIENTS_PER_ADDRESS = 2
        private const val CONNECTION_DEADLINE_MILLIS = 90_000L
        private const val REQUESTS_PER_MINUTE = 60
        private const val OFFERS_PER_MINUTE = 10
        private val TICKET = Regex("[0-9a-f]{32}")

        fun start(host: String, name: String, onPayload: (ByteArray) -> Boolean, receiver: SetupTransferReceiver = SetupTransferReceiver(),
            now: () -> Long = System::currentTimeMillis, startPort: Int = START_PORT, maxAttempts: Int = 10): IptvSetupTransferServer? {
            if (!SetupLan.isLanAddress(host)) return null
            for (port in startPort until startPort + maxAttempts) {
                try {
                    val server = IptvSetupTransferServer(host, port, receiver, name, onPayload, now)
                    server.start(SOCKET_READ_TIMEOUT, false)
                    return server
                } catch (_: Exception) {
                }
            }
            return null
        }
    }
}
