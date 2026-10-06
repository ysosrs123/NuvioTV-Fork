package com.nuvio.tv.core.server

import com.nuvio.tv.core.iptv.SetupChangeBook
import com.nuvio.tv.core.iptv.SetupCookies
import com.nuvio.tv.core.iptv.SetupDraft
import com.nuvio.tv.core.iptv.SetupDrafts
import com.nuvio.tv.core.iptv.SetupGuard
import com.nuvio.tv.core.iptv.SetupHeaders
import com.nuvio.tv.core.iptv.SetupIdleTimer
import com.nuvio.tv.core.iptv.SetupInputException
import com.nuvio.tv.core.iptv.SetupLan
import com.nuvio.tv.core.iptv.SetupListing
import com.nuvio.tv.core.iptv.SetupPairing
import com.nuvio.tv.core.iptv.SetupRateLimiter
import fi.iki.elonen.NanoHTTPD
import java.io.InputStream
import java.net.Socket
import java.security.SecureRandom
import org.json.JSONObject

class IptvSetupServer private constructor(
    private val host: String,
    port: Int,
    private val listing: () -> SetupListing,
    private val onChangeProposed: (String, SetupDraft, String) -> Unit,
    now: () -> Long,
) : NanoHTTPD(host, port) {

    private val random = SecureRandom()
    private val pairing = SetupPairing(random)
    private val changes = SetupChangeBook(random, now = now)
    private val idle = SetupIdleTimer(IDLE_TIMEOUT_MILLIS, now)
    private val requests = SetupRateLimiter(REQUESTS_PER_MINUTE, MINUTE, now)
    private val pairAttempts = SetupRateLimiter(PAIR_ATTEMPTS_PER_MINUTE, MINUTE, now)

    init {
        setAsyncRunner(BoundedRunner(MAX_CLIENTS))
    }

    private val authority: String get() = "$host:$listeningPort"
    private val origin: String get() = "http://$authority"

    val address: String get() = "$origin/s/${pairing.credentials.token}/"
    val code: String get() = pairing.credentials.code
    val revision: Int get() = pairing.credentials.revision
    val pairedDevices: Int get() = pairing.sessionCount
    val idleExpired: Boolean get() = idle.expired()

    fun resolve(id: String, status: SetupChangeBook.Status): Boolean = changes.resolve(id, status)

    override fun stop() {
        changes.rejectPending()
        pairing.close()
        super.stop()
    }

    override fun useGzipWhenAccepted(r: Response): Boolean = false

    override fun createClientHandler(finalAccept: Socket, inputStream: InputStream): ClientHandler {
        if (!SetupLan.isLanAddress(finalAccept.inetAddress?.hostAddress)) runCatching { finalAccept.close() }
        return super.createClientHandler(finalAccept, inputStream)
    }

    override fun serve(session: IHTTPSession): Response {
        val response = try {
            route(session)
        } catch (rejected: BodyRejected) {
            json(rejected.status, error("body"))
        } catch (_: Throwable) {
            json(Response.Status.INTERNAL_ERROR, error("server"))
        }
        response.closeConnection(true)
        return response
    }

    private fun route(session: IHTTPSession): Response {
        val remote = session.remoteIpAddress
        if (!SetupLan.isLanAddress(remote)) return text(Response.Status.FORBIDDEN, "Forbidden")
        if (!requests.allow(remote)) return json(Response.Status.TOO_MANY_REQUESTS, error("rate"))
        if (!SetupGuard.hostMatches(session.headers["host"], authority)) return text(Response.Status.BAD_REQUEST, "Bad request")
        val match = PATH.matchEntire(session.uri) ?: return ended()
        val token = match.groupValues[1]
        val rest = match.groupValues[2]
        val api = rest.startsWith("/api/")
        if (!pairing.knowsLink(token)) return if (api) json(Response.Status.NOT_FOUND, error("link")) else ended()
        idle.touch()
        if (!api) return when {
            session.method != Method.GET -> text(Response.Status.METHOD_NOT_ALLOWED, "Method not allowed")
            rest.isEmpty() -> redirect("/s/$token/")
            rest == "/" -> page()
            else -> text(Response.Status.NOT_FOUND, "Not found")
        }
        val post = session.method == Method.POST
        if (!post && session.method != Method.GET) return json(Response.Status.METHOD_NOT_ALLOWED, error("method"))
        if (SetupGuard.check(session.headers, origin, stateChanging = post) != null) return json(Response.Status.FORBIDDEN, error("request"))
        val path = rest.removePrefix("/api/")
        if (path == "pair") return if (post) pair(session, token, remote) else json(Response.Status.METHOD_NOT_ALLOWED, error("method"))
        val owner = pairing.session(token, SetupCookies.read(session.headers["cookie"]))
            ?: return json(Response.Status.UNAUTHORIZED, error("session"))
        return when {
            path == "state" && !post -> json(Response.Status.OK, listing().toJson(pending = changes.hasPending()))
            path == "changes" && post -> propose(session, owner, remote)
            path.startsWith("changes/") && !post -> status(owner, path.removePrefix("changes/"))
            else -> json(Response.Status.NOT_FOUND, error("missing"))
        }
    }

    private fun pair(session: IHTTPSession, token: String, remote: String): Response {
        if (!pairAttempts.allow(remote)) return json(Response.Status.TOO_MANY_REQUESTS, error("rate"))
        val code = try {
            val body = readBody(session)
            if (!SetupDrafts.shallowJson(body)) return json(Response.Status.BAD_REQUEST, error("body"))
            JSONObject(body).opt("code") as? String ?: ""
        } catch (rejected: BodyRejected) {
            throw rejected
        } catch (_: Exception) {
            return json(Response.Status.BAD_REQUEST, error("body"))
        }
        return when (val result = pairing.pair(token, code)) {
            is SetupPairing.Result.Paired -> json(Response.Status.OK, JSONObject().put("paired", true).toString()).also {
                it.addHeader("Set-Cookie", SetupCookies.session(result.sessionId, token))
            }
            is SetupPairing.Result.WrongCode -> json(Response.Status.FORBIDDEN, JSONObject().put("error", "code").put("attemptsLeft", result.attemptsLeft).toString())
            SetupPairing.Result.Renewed -> json(Response.Status.GONE, error("renewed"))
            SetupPairing.Result.UnknownLink -> json(Response.Status.NOT_FOUND, error("link"))
        }
    }

    private fun propose(session: IHTTPSession, owner: String, remote: String): Response {
        val draft = try {
            SetupDrafts.parse(readBody(session))
        } catch (invalid: SetupInputException) {
            return json(Response.Status.BAD_REQUEST, JSONObject().put("error", "invalid").put("field", invalid.field).toString())
        }
        SetupDrafts.checkTarget(draft, listing())?.let { problem ->
            val status = when (problem) {
                "missing" -> Response.Status.NOT_FOUND
                "locked" -> Response.Status.FORBIDDEN
                else -> Response.Status.BAD_REQUEST
            }
            return json(status, error(problem))
        }
        SetupDrafts.checkLogin(draft, listing())?.let { field ->
            return json(Response.Status.BAD_REQUEST, JSONObject().put("error", "invalid").put("field", field).put("reason", "server").toString())
        }
        if (changes.coolingDown(owner)) return json(Response.Status.CONFLICT, error("cooldown"))
        val id = changes.propose(owner, draft) ?: return json(Response.Status.CONFLICT, error("busy"))
        try {
            onChangeProposed(id, draft, remote)
        } catch (_: Exception) {
            changes.resolve(id, SetupChangeBook.Status.FAILED)
            return json(Response.Status.INTERNAL_ERROR, error("server"))
        }
        return json(Response.Status.ACCEPTED, JSONObject().put("id", id).toString())
    }

    private fun status(owner: String, id: String): Response {
        val status = id.takeIf(CHANGE_ID::matches)?.let { changes.status(owner, it) }
            ?: return json(Response.Status.NOT_FOUND, error("missing"))
        return json(Response.Status.OK, JSONObject().put("status", status.name.lowercase()).toString())
    }

    private fun readBody(session: IHTTPSession): String {
        if (session.headers.containsKey("transfer-encoding")) throw BodyRejected(Response.Status.LENGTH_REQUIRED)
        val length = session.headers["content-length"]?.trim()?.toLongOrNull() ?: throw BodyRejected(Response.Status.LENGTH_REQUIRED)
        if (length < 0 || length > SetupDrafts.MAX_BODY_BYTES) throw BodyRejected(Response.Status.PAYLOAD_TOO_LARGE)
        val bytes = ByteArray(length.toInt())
        val input = session.inputStream
        var read = 0
        while (read < bytes.size) {
            val count = input.read(bytes, read, bytes.size - read)
            if (count < 0) throw BodyRejected(Response.Status.BAD_REQUEST)
            read += count
        }
        return String(bytes, Charsets.UTF_8)
    }

    private fun page(): Response {
        val nonce = SetupHeaders.nonce(random)
        return secured(newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", IptvSetupWebPage.page(nonce)), nonce)
    }

    private fun ended(): Response {
        val nonce = SetupHeaders.nonce(random)
        return secured(newFixedLengthResponse(Response.Status.NOT_FOUND, "text/html; charset=utf-8", IptvSetupWebPage.ended(nonce)), nonce)
    }

    private fun redirect(location: String): Response =
        secured(newFixedLengthResponse(Response.Status.REDIRECT_SEE_OTHER, MIME_PLAINTEXT, "")).also { it.addHeader("Location", location) }

    private fun json(status: Response.Status, body: String): Response =
        secured(newFixedLengthResponse(status, "application/json; charset=utf-8", body))

    private fun text(status: Response.Status, body: String): Response =
        secured(newFixedLengthResponse(status, "text/plain; charset=utf-8", body))

    private fun secured(response: Response, nonce: String? = null): Response {
        SetupHeaders.security(nonce).forEach { (name, value) -> response.addHeader(name, value) }
        return response
    }

    private fun error(code: String): String = JSONObject().put("error", code).toString()

    private class BodyRejected(val status: Response.Status) : Exception()

    private class BoundedRunner(private val limit: Int) : AsyncRunner {
        private val running = ArrayList<ClientHandler>()

        override fun closeAll() {
            val handlers = synchronized(running) { ArrayList(running) }
            handlers.forEach { it.close() }
        }

        override fun closed(clientHandler: ClientHandler) {
            synchronized(running) { running.remove(clientHandler) }
        }

        override fun exec(clientHandler: ClientHandler) {
            val accepted = synchronized(running) { (running.size < limit).also { if (it) running.add(clientHandler) } }
            if (!accepted) {
                clientHandler.close()
                return
            }
            Thread(clientHandler, "IptvSetupClient").apply {
                isDaemon = true
                setUncaughtExceptionHandler { _, _ ->
                    clientHandler.close()
                    closed(clientHandler)
                }
            }.start()
        }
    }

    companion object {
        const val IDLE_TIMEOUT_MILLIS = 10 * 60_000L
        private const val MINUTE = 60_000L
        private const val MAX_CLIENTS = 8
        private const val REQUESTS_PER_MINUTE = 120
        private const val PAIR_ATTEMPTS_PER_MINUTE = 10
        private val PATH = Regex("/s/([^/]+)(.*)")
        private val CHANGE_ID = Regex("[0-9a-f]{32}")

        fun start(
            host: String,
            listing: () -> SetupListing,
            onChangeProposed: (String, SetupDraft, String) -> Unit,
            now: () -> Long = System::currentTimeMillis,
            startPort: Int = 8100,
            maxAttempts: Int = 10
        ): IptvSetupServer? {
            if (!SetupLan.isLanAddress(host)) return null
            for (port in startPort until startPort + maxAttempts) {
                try {
                    val server = IptvSetupServer(host, port, listing, onChangeProposed, now)
                    server.start(SOCKET_READ_TIMEOUT, false)
                    return server
                } catch (_: Exception) {
                }
            }
            return null
        }
    }
}
