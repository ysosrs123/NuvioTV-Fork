package com.nuvio.tv.core.server

import com.nuvio.tv.core.iptv.SetupAssignments
import com.nuvio.tv.core.iptv.SetupByteRange
import com.nuvio.tv.core.iptv.SetupChange
import com.nuvio.tv.core.iptv.SetupChangeBook
import com.nuvio.tv.core.iptv.SetupConnectionLimiter
import com.nuvio.tv.core.iptv.SetupCookies
import com.nuvio.tv.core.iptv.SetupDrafts
import com.nuvio.tv.core.iptv.SetupGuard
import com.nuvio.tv.core.iptv.SetupHeaders
import com.nuvio.tv.core.iptv.SetupIdleTimer
import com.nuvio.tv.core.iptv.SetupInputException
import com.nuvio.tv.core.iptv.SetupKind
import com.nuvio.tv.core.iptv.SetupLan
import com.nuvio.tv.core.iptv.SetupLinkSigner
import com.nuvio.tv.core.iptv.SetupListing
import com.nuvio.tv.core.iptv.SetupLookup
import com.nuvio.tv.core.iptv.SetupPairGate
import com.nuvio.tv.core.iptv.SetupPairing
import com.nuvio.tv.core.iptv.SetupPhone
import com.nuvio.tv.core.iptv.SetupPhoneAccess
import com.nuvio.tv.core.iptv.SetupRangeStream
import com.nuvio.tv.core.iptv.SetupRateLimiter
import com.nuvio.tv.core.iptv.SetupRecordingDownloads
import com.nuvio.tv.core.iptv.SetupSettings
import com.nuvio.tv.core.iptv.SetupSettingsInput
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.Socket
import java.security.SecureRandom
import java.time.ZoneId
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

class IptvSetupServer private constructor(
    private val host: String,
    port: Int,
    private val listing: () -> SetupListing,
    private val settings: () -> SetupSettings,
    private val lookup: SetupLookup,
    private val onChangeProposed: (IptvSetupServer, String, SetupChange, String, Int, SetupPhone?) -> Unit,
    private val recordings: SetupRecordingSource?,
    private val phones: SetupPhoneAccess?,
    private val now: () -> Long,
) : NanoHTTPD(host, port) {

    private val random = SecureRandom()
    private val pairing = SetupPairing(random)
    private val changes = SetupChangeBook(random, now = now)
    private val idle = SetupIdleTimer(IDLE_TIMEOUT_MILLIS, now)
    private val requests = SetupRateLimiter(REQUESTS_PER_MINUTE, MINUTE, now)
    private val pairAttempts = SetupPairGate(PAIR_ATTEMPTS_PER_MINUTE, PAIR_ATTEMPTS_ALL_PER_MINUTE, MINUTE, now)
    private val phoneFailures = SetupRateLimiter(PHONE_FAILURES_PER_MINUTE, MINUTE, now)
    private val signer = SetupLinkSigner(random)
    private val streams = Semaphore(MAX_STREAMS)
    private val runner = SetupBoundedRunner(SetupConnectionLimiter(MAX_CLIENTS, MAX_CLIENTS_PER_ADDRESS), CONNECTION_DEADLINE_MILLIS, "IptvSetup")

    init {
        setAsyncRunner(runner)
    }

    private val authority: String get() = "$host:$listeningPort"
    private val origin: String get() = "http://$authority"

    val address: String get() = "$origin/s/${pairing.credentials.token}/"
    val phoneAddress: String get() = "$origin${SetupCookies.PHONE_PATH}"
    val boundHost: String get() = host
    val port: Int get() = listeningPort
    val pairingOpen: Boolean get() = pairing.open
    val code: String get() = pairing.credentials.code
    val revision: Int get() = pairing.credentials.revision
    val pairedDevices: Int get() = pairing.sessionCount
    val idleExpired: Boolean get() = idle.expired()

    fun resolve(id: String, status: SetupChangeBook.Status): Boolean = changes.resolve(id, status)

    fun openPairing() {
        if (!pairing.open) pairing.reopen()
        idle.touch()
    }

    fun closePairing() = pairing.close()

    fun touch() = idle.touch()

    fun rejectPending() = changes.rejectPending()

    override fun stop() {
        changes.rejectPending()
        pairing.close()
        super.stop()
    }

    override fun useGzipWhenAccepted(r: Response): Boolean = false

    override fun createClientHandler(finalAccept: Socket, inputStream: InputStream): ClientHandler {
        val address = finalAccept.inetAddress?.hostAddress
        if (!SetupLan.isLanAddress(address)) runCatching { finalAccept.close() }
        return super.createClientHandler(finalAccept, inputStream).also { runner.address(it, address.orEmpty()) }
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
        if (!SetupGuard.hostMatches(session.headers["host"], authority)) return text(Response.Status.BAD_REQUEST, "Bad request")
        if (!requests.allow(remote)) return json(Response.Status.TOO_MANY_REQUESTS, error("rate"))
        if (session.uri == SetupCookies.PHONE_PATH.removeSuffix("/")) return redirect(SetupCookies.PHONE_PATH)
        if (session.uri.startsWith(SetupCookies.PHONE_PATH)) return phoneRoute(session, remote, session.uri.removePrefix(SetupCookies.PHONE_PATH))
        val match = PATH.matchEntire(session.uri) ?: return ended()
        val token = match.groupValues[1]
        val rest = match.groupValues[2]
        val api = rest.startsWith("/api/")
        if (!pairing.knowsLink(token)) return if (api) json(Response.Status.NOT_FOUND, error("link")) else ended()
        if (!api) return when {
            session.method != Method.GET -> text(Response.Status.METHOD_NOT_ALLOWED, "Method not allowed")
            rest.isEmpty() -> redirect("/s/$token/")
            rest == "/" -> page()
            else -> text(Response.Status.NOT_FOUND, "Not found")
        }
        val path = rest.removePrefix("/api/")
        RECORDING_FILE.matchEntire(path)?.let { return recordingFile(session, it.groupValues[1]) }
        val post = session.method == Method.POST
        if (!post && session.method != Method.GET) return json(Response.Status.METHOD_NOT_ALLOWED, error("method"))
        if (SetupGuard.check(session.headers, origin, stateChanging = post) != null) return json(Response.Status.FORBIDDEN, error("request"))
        if (path == "pair") return if (post) pair(session, token, remote) else json(Response.Status.METHOD_NOT_ALLOWED, error("method"))
        val owner = pairing.session(token, SetupCookies.read(session.headers["cookie"]))
            ?: return json(Response.Status.UNAUTHORIZED, error("session"))
        idle.touch()
        return api(session, path, post, owner, remote, null)
    }

    private fun phoneRoute(session: IHTTPSession, remote: String, rest: String): Response {
        val cookie = SetupCookies.read(session.headers["cookie"], SetupCookies.PHONE)
        if (!rest.startsWith("api/")) return when {
            session.method != Method.GET -> text(Response.Status.METHOD_NOT_ALLOWED, "Method not allowed")
            rest.isNotEmpty() -> text(Response.Status.NOT_FOUND, "Not found")
            phones?.verify(cookie) == null -> unpaired()
            else -> page().also { it.addHeader("Set-Cookie", SetupCookies.phone(requireNotNull(cookie))) }
        }
        val path = rest.removePrefix("api/")
        RECORDING_FILE.matchEntire(path)?.let { return recordingFile(session, it.groupValues[1]) }
        val post = session.method == Method.POST
        if (!post && session.method != Method.GET) return json(Response.Status.METHOD_NOT_ALLOWED, error("method"))
        if (SetupGuard.check(session.headers, origin, stateChanging = post) != null) return json(Response.Status.FORBIDDEN, error("request"))
        val access = phones
        val phone = access?.verify(cookie) ?: return if (phoneFailures.allow(remote)) json(Response.Status.UNAUTHORIZED, error("phone"))
            else json(Response.Status.TOO_MANY_REQUESTS, error("rate"))
        if (path == "forget") return if (!post) json(Response.Status.METHOD_NOT_ALLOWED, error("method")) else {
            access.forget(cookie)
            json(Response.Status.OK, JSONObject().put("forgotten", true).toString()).also { it.addHeader("Set-Cookie", SetupCookies.forgetPhone()) }
        }
        when (access.allowed(phone)) {
            null -> return json(Response.Status.SERVICE_UNAVAILABLE, error("starting")).also { it.addHeader("Retry-After", "2") }
            false -> return json(Response.Status.FORBIDDEN, error("profile"))
            true -> Unit
        }
        idle.touch()
        return api(session, path, post, PHONE_OWNER + phone.id, remote, phone)
    }

    private fun api(session: IHTTPSession, path: String, post: Boolean, owner: String, remote: String, phone: SetupPhone?): Response {
        return when {
            path == "state" && !post -> json(Response.Status.OK, listing().toJson(pending = changes.hasPending()))
            path == "settings" && !post -> json(Response.Status.OK, settings().toJson())
            path == "settings" && post -> proposeSettings(session, owner, Sender(remote, phone))
            path == "changes" && post -> propose(session, owner, Sender(remote, phone))
            path == "links" && post -> proposeLinks(session, owner, Sender(remote, phone))
            path == "channel-guide" && post -> proposeChannelGuide(session, owner, Sender(remote, phone))
            path == "profile" && post -> proposeProfile(session, owner, Sender(remote, phone))
            path == "channels" && !post -> channels(session)
            path == "guide-channels" && !post -> guideChannels(session)
            path == "recordings" && !post -> recordingList()
            path.startsWith("changes/") && !post -> status(owner, path.removePrefix("changes/"))
            else -> json(Response.Status.NOT_FOUND, error("missing"))
        }
    }

    private class Sender(val address: String, val phone: SetupPhone?)

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
            is SetupPairing.Result.Paired -> {
                val phone = phones?.takeIf { it.enabled }?.issue(session.headers["user-agent"], listing().profile)
                json(Response.Status.OK, JSONObject().put("paired", true).apply { if (phone != null) put("phone", SetupCookies.PHONE_PATH) }.toString()).also {
                    idle.touch()
                    it.addHeader("Set-Cookie", if (phone != null) SetupCookies.phone(phone) else SetupCookies.session(result.sessionId, token))
                }
            }
            is SetupPairing.Result.WrongCode -> json(Response.Status.FORBIDDEN, JSONObject().put("error", "code").put("attemptsLeft", result.attemptsLeft).toString())
            SetupPairing.Result.Renewed -> json(Response.Status.GONE, error("renewed"))
            SetupPairing.Result.UnknownLink -> json(Response.Status.NOT_FOUND, error("link"))
        }
    }

    private fun propose(session: IHTTPSession, owner: String, remote: Sender): Response {
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
        return submit(owner, draft, remote)
    }

    private fun proposeSettings(session: IHTTPSession, owner: String, remote: Sender): Response {
        val change = try {
            SetupSettingsInput.parse(readBody(session))
        } catch (invalid: SetupInputException) {
            return json(Response.Status.BAD_REQUEST, JSONObject().put("error", "invalid").put("field", invalid.field).toString())
        }
        val current = settings()
        SetupSettingsInput.check(change, current)?.let { field ->
            return json(Response.Status.BAD_REQUEST, JSONObject().put("error", "invalid").put("field", field).toString())
        }
        if (change.changes(current).isEmpty()) return json(Response.Status.BAD_REQUEST, error("unchanged"))
        return submit(owner, change, remote)
    }

    private fun proposeLinks(session: IHTTPSession, owner: String, remote: Sender): Response {
        val change = try { SetupAssignments.parseLinks(readBody(session)) } catch (invalid: SetupInputException) { return invalid(invalid) }
        val listing = listing()
        SetupAssignments.checkLinks(change, listing)?.let { return refused(it) }
        return submit(owner, change, remote, listing.profile)
    }

    private fun proposeChannelGuide(session: IHTTPSession, owner: String, remote: Sender): Response {
        val change = try { SetupAssignments.parseChannelGuide(readBody(session)) } catch (invalid: SetupInputException) { return invalid(invalid) }
        val listing = listing()
        val current = if (listing.find(SetupKind.M3U, change.sourceId) == null) null else lookup.channel(change.sourceId, change.channelId)
        SetupAssignments.checkChannelGuide(change, listing, current)?.let { return refused(it) }
        if (change.feedId != null && lookup.guideChannels(change.feedId, change.guideName.orEmpty().take(SetupAssignments.MAX_QUERY))
                ?.any { it.id == change.guideId } != true) return refused("missing")
        return submit(owner, change, remote, listing.profile)
    }

    private fun proposeProfile(session: IHTTPSession, owner: String, remote: Sender): Response {
        val change = try { SetupAssignments.parseProfile(readBody(session)) } catch (invalid: SetupInputException) { return invalid(invalid) }
        val listing = listing()
        SetupAssignments.checkProfile(change, listing)?.let { return refused(it) }
        return submit(owner, change, remote, listing.profile)
    }

    private fun channels(session: IHTTPSession): Response {
        val source = session.parameters["source"]?.singleOrNull()?.takeIf(ID::matches) ?: return json(Response.Status.BAD_REQUEST, error("source"))
        val query = SetupAssignments.query(session.parameters["q"]?.singleOrNull()) ?: return json(Response.Status.BAD_REQUEST, error("query"))
        if (listing().find(SetupKind.M3U, source) == null) return json(Response.Status.NOT_FOUND, error("missing"))
        val found = lookup.channels(source, query) ?: return json(Response.Status.NOT_FOUND, error("missing"))
        return json(Response.Status.OK, SetupAssignments.channelsJson(found.take(SetupAssignments.MAX_RESULTS)))
    }

    private fun guideChannels(session: IHTTPSession): Response {
        val feed = session.parameters["feed"]?.singleOrNull()?.takeIf(ID::matches) ?: return json(Response.Status.BAD_REQUEST, error("feed"))
        val query = SetupAssignments.query(session.parameters["q"]?.singleOrNull()) ?: return json(Response.Status.BAD_REQUEST, error("query"))
        if (listing().find(SetupKind.GUIDE, feed) == null) return json(Response.Status.NOT_FOUND, error("missing"))
        val found = lookup.guideChannels(feed, query) ?: return json(Response.Status.NOT_FOUND, error("missing"))
        return json(Response.Status.OK, SetupAssignments.guideChannelsJson(found.take(SetupAssignments.MAX_RESULTS)))
    }

    private fun recordingList(): Response {
        val profile = listing().profile
        val time = now()
        val entries = recordings?.list(profile).orEmpty()
        return json(Response.Status.OK, SetupRecordingDownloads.json(entries) { "api/recordings/${it.id}/file?" + signer.query(profile, it.id, time) })
    }

    private fun recordingFile(session: IHTTPSession, id: String): Response {
        val head = session.method == Method.HEAD
        if (!head && session.method != Method.GET) return text(Response.Status.METHOD_NOT_ALLOWED, "Method not allowed")
        val source = recordings
        val profile = listing().profile
        if (source == null || !SetupRecordingDownloads.ID.matches(id)) return text(Response.Status.NOT_FOUND, "Not found")
        if (!signer.verify(profile, id, session.parameters["e"]?.singleOrNull(), session.parameters["t"]?.singleOrNull(), now()))
            return text(Response.Status.FORBIDDEN, "This link has expired. Open the recordings list on the setup page again.")
        if (!streams.tryAcquire()) return text(Response.Status.SERVICE_UNAVAILABLE, "Busy").also { it.addHeader("Retry-After", "15") }
        idle.touch()
        var file: SetupRecordingFile? = null
        val finished = AtomicBoolean(false)
        val finish = {
            if (finished.compareAndSet(false, true)) {
                try { file?.close() } catch (_: Exception) { }
                streams.release()
            }
        }
        try {
            val opened = source.open(profile, id) ?: return text(Response.Status.NOT_FOUND, "Not found").also { finish() }
            file = opened
            val length = opened.reader.length()
            val range = SetupRecordingDownloads.range(session.headers["range"], length, session.headers["if-range"])
            if (range == SetupByteRange.Unsatisfiable) {
                finish()
                return text(Response.Status.RANGE_NOT_SATISFIABLE, "Range not satisfiable").also {
                    it.addHeader("Content-Range", SetupRecordingDownloads.unsatisfiedRange(length))
                    it.addHeader("Accept-Ranges", "bytes")
                }
            }
            val part = range as? SetupByteRange.Part
            val count = part?.length ?: length
            val lastRead = AtomicLong(now())
            val body: InputStream = if (head) ByteArrayInputStream(ByteArray(0)).also { finish() } else SetupRangeStream(
                { position, buffer, offset, size -> opened.reader.read(position, buffer, offset, size) }, part?.first ?: 0L, count,
                onClose = finish, onRead = { lastRead.set(now()); idle.touch() })
            val response = secured(newFixedLengthResponse(if (part == null) Response.Status.OK else Response.Status.PARTIAL_CONTENT, MIME_TS, body, count))
            response.addHeader("Accept-Ranges", "bytes")
            response.addHeader("Content-Disposition", SetupRecordingDownloads.disposition(
                SetupRecordingDownloads.fileName(opened.entry.title, opened.entry.startMillis, ZoneId.systemDefault()),
                attachment = session.parameters["inline"]?.singleOrNull() != "1"))
            part?.let { response.addHeader("Content-Range", SetupRecordingDownloads.contentRange(it, length)) }
            if (!head) runner.stream(STREAM_CHECK_MILLIS) { now() - lastRead.get() > STREAM_STALL_MILLIS }
            return response
        } catch (error: Exception) {
            finish()
            throw error
        }
    }

    private fun invalid(invalid: SetupInputException): Response =
        json(Response.Status.BAD_REQUEST, JSONObject().put("error", "invalid").put("field", invalid.field).toString())

    private fun refused(problem: String): Response = json(when (problem) {
        "missing" -> Response.Status.NOT_FOUND
        "locked" -> Response.Status.FORBIDDEN
        else -> Response.Status.BAD_REQUEST
    }, error(problem))

    private fun submit(owner: String, change: SetupChange, remote: Sender, profile: Int = listing().profile): Response {
        if (changes.coolingDown(owner)) return json(Response.Status.CONFLICT, error("cooldown"))
        val id = changes.propose(owner, change) ?: return json(Response.Status.CONFLICT, error("busy"))
        try {
            onChangeProposed(this, id, change, remote.address, profile, remote.phone)
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

    private fun unpaired(): Response {
        val nonce = SetupHeaders.nonce(random)
        return secured(newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/html; charset=utf-8", IptvSetupWebPage.unpaired(nonce)), nonce)
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

    companion object {
        const val IDLE_TIMEOUT_MILLIS = 10 * 60_000L
        private const val MINUTE = 60_000L
        private const val MAX_CLIENTS = 8
        private const val MAX_CLIENTS_PER_ADDRESS = 4
        private const val MAX_STREAMS = 3
        private const val STREAM_CHECK_MILLIS = 15_000L
        private const val STREAM_STALL_MILLIS = 120_000L
        private const val MIME_TS = "video/mp2t"
        private const val CONNECTION_DEADLINE_MILLIS = 10_000L
        private const val REQUESTS_PER_MINUTE = 120
        private const val PAIR_ATTEMPTS_PER_MINUTE = 10
        private const val PAIR_ATTEMPTS_ALL_PER_MINUTE = 30
        private const val PHONE_FAILURES_PER_MINUTE = 20
        private const val PHONE_OWNER = "phone:"
        private val PATH = Regex("/s/([^/]+)(.*)")
        private val CHANGE_ID = Regex("[0-9a-f]{32}")
        private val ID = Regex("[A-Za-z0-9_-]{1,80}")
        private val RECORDING_FILE = Regex("recordings/([^/]{1,80})/file")

        fun start(
            host: String,
            listing: () -> SetupListing,
            settings: () -> SetupSettings,
            lookup: SetupLookup,
            onChangeProposed: (IptvSetupServer, String, SetupChange, String, Int, SetupPhone?) -> Unit,
            recordings: SetupRecordingSource? = null,
            phones: SetupPhoneAccess? = null,
            pairingOpen: Boolean = true,
            preferredPort: Int? = null,
            now: () -> Long = System::currentTimeMillis,
            startPort: Int = 8100,
            maxAttempts: Int = 10
        ): IptvSetupServer? {
            if (!SetupLan.isLanAddress(host)) return null
            val range = startPort until startPort + maxAttempts
            for (port in (listOfNotNull(preferredPort?.takeIf { it in range }) + range).distinct()) {
                try {
                    val server = IptvSetupServer(host, port, listing, settings, lookup, onChangeProposed, recordings, phones, now)
                    if (!pairingOpen) server.closePairing()
                    server.start(SOCKET_READ_TIMEOUT, false)
                    return server
                } catch (_: Exception) {
                }
            }
            return null
        }
    }
}

internal class SetupBoundedRunner(private val connections: SetupConnectionLimiter, private val deadlineMillis: Long, private val name: String) : NanoHTTPD.AsyncRunner {
    private val addresses = HashMap<NanoHTTPD.ClientHandler, String>()
    private val running = HashMap<NanoHTTPD.ClientHandler, String>()
    private val deadlines = HashMap<NanoHTTPD.ClientHandler, ScheduledFuture<*>>()
    private val threads = HashMap<NanoHTTPD.ClientHandler, Thread>()
    private val timer = ScheduledThreadPoolExecutor(1, ThreadFactory { task ->
        Thread(task, "${name}Deadline").apply { isDaemon = true }
    }).apply { removeOnCancelPolicy = true }

    fun address(clientHandler: NanoHTTPD.ClientHandler, address: String) {
        synchronized(this) { addresses[clientHandler] = address }
    }

    fun stream(periodMillis: Long, stalled: () -> Boolean) {
        synchronized(this) {
            val handler = threads.entries.firstOrNull { it.value === Thread.currentThread() }?.key ?: return
            deadlines.remove(handler)?.cancel(false) ?: return
            try {
                deadlines[handler] = timer.scheduleWithFixedDelay({ if (stalled()) handler.close() }, periodMillis, periodMillis, TimeUnit.MILLISECONDS)
            } catch (_: RejectedExecutionException) {
                handler.close()
            }
        }
    }

    override fun closeAll() {
        val handlers = synchronized(this) { ArrayList(running.keys) }
        handlers.forEach { it.close() }
        timer.shutdownNow()
    }

    override fun closed(clientHandler: NanoHTTPD.ClientHandler) {
        synchronized(this) {
            addresses.remove(clientHandler)
            deadlines.remove(clientHandler)?.cancel(false)
            threads.remove(clientHandler)
            running.remove(clientHandler)?.let(connections::release)
        }
    }

    override fun exec(clientHandler: NanoHTTPD.ClientHandler) {
        val accepted = synchronized(this) {
            val address = addresses.remove(clientHandler).orEmpty()
            if (!connections.admit(address)) return@synchronized false
            try {
                deadlines[clientHandler] = timer.schedule(Runnable { clientHandler.close() }, deadlineMillis, TimeUnit.MILLISECONDS)
            } catch (_: RejectedExecutionException) {
                connections.release(address)
                return@synchronized false
            }
            running[clientHandler] = address
            true
        }
        if (!accepted) {
            clientHandler.close()
            return
        }
        val thread = Thread(clientHandler, "${name}Client").apply {
            isDaemon = true
            setUncaughtExceptionHandler { _, _ ->
                clientHandler.close()
                closed(clientHandler)
            }
        }
        synchronized(this) { if (clientHandler in running) threads[clientHandler] = thread }
        thread.start()
    }
}
