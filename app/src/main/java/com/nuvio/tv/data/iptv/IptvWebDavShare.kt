package com.nuvio.tv.data.iptv

import com.nuvio.tv.core.iptv.RecordingShareAddress
import com.nuvio.tv.core.iptv.WebDavAuth
import com.nuvio.tv.core.iptv.WebDavChallenge
import com.nuvio.tv.core.iptv.WebDavEntry
import com.nuvio.tv.core.iptv.WebDavXml
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.Pipe
import okio.buffer

class IptvWebDavConnector(private val settings: IptvShareSettings, private val password: String,
    private val base: OkHttpClient = shared) : IptvShareConnector {
    private val trust = IptvShareTrust(settings.pin)
    override val certificate: String? get() = trust.presented

    override fun connect(): IptvShareSession {
        val auth = IptvWebDavAuth(if (settings.guest) "" else settings.username, password)
        val builder = base.newBuilder().authenticator(auth).addInterceptor(auth)
        if (settings.target.secure) builder.sslSocketFactory(trust.factory, trust).hostnameVerifier { host, session -> trust.verify(host, session) }
        val session = IptvWebDavSession(builder.build(), settings, trust)
        try { session.start() } catch (error: Throwable) { session.close(); throw error }
        return session
    }

    override fun toString(): String = "IptvWebDavConnector(withheld)"

    companion object {
        private val shared: OkHttpClient by lazy {
            OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS)
                .followRedirects(false).followSslRedirects(false).build()
        }
    }
}

internal class IptvWebDavAuth(private val username: String, private val password: String) : Authenticator, Interceptor {
    @Volatile private var challenge: WebDavChallenge? = null
    private val count = AtomicInteger()
    private val random = SecureRandom()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val current = challenge
        if (current == null || request.header(AUTHORIZATION) != null) return chain.proceed(request)
        val header = header(current, request) ?: return chain.proceed(request)
        return chain.proceed(request.newBuilder().header(AUTHORIZATION, header).build())
    }

    override fun authenticate(route: Route?, response: Response): Request? {
        if (username.isEmpty()) return null
        val offered = WebDavAuth.challenges(response.headers("WWW-Authenticate"))
        val chosen = offered.firstOrNull { it.scheme == "digest" && header(it, response.request) != null } ?: offered.firstOrNull { it.scheme == "basic" } ?: return null
        if (response.request.header(AUTHORIZATION) != null && !(chosen.scheme == "digest" && chosen.params["stale"].equals("true", true))) return null
        if (generateSequence(response.priorResponse) { it.priorResponse }.count { it.code == 401 } >= 2) return null
        challenge = chosen
        count.set(0)
        return header(chosen, response.request)?.let { response.request.newBuilder().header(AUTHORIZATION, it).build() }
    }

    private fun header(challenge: WebDavChallenge, request: Request): String? = when (challenge.scheme) {
        "basic" -> WebDavAuth.basic(username, password)
        "digest" -> WebDavAuth.digest(challenge, username, password, request.method,
            request.url.encodedPath + (request.url.encodedQuery?.let { "?$it" } ?: ""), count.incrementAndGet(),
            ByteArray(8).also(random::nextBytes).joinToString("") { "%02x".format(it) })
        else -> null
    }

    override fun toString(): String = "IptvWebDavAuth(withheld)"

    private companion object { const val AUTHORIZATION = "Authorization" }
}

private class IptvWebDavSession(private val client: OkHttpClient, private val settings: IptvShareSettings, private val trust: IptvShareTrust) : IptvShareSession {
    private val target = settings.target
    private var partial = false
    override val append: Boolean get() = partial

    fun start() = dav {
        call(request("", "OPTIONS", collection = true)).use { response ->
            if (response.code == 401) throw IptvShareException(IptvShareError.LOGIN_REFUSED)
            partial = response.headers("DAV").any { it.contains("sabredav-partialupdate", true) }
        }
        call(propfind("", 0, collection = true, WebDavXml.propfind("resourcetype"))).use { response ->
            when (response.code) {
                401 -> throw IptvShareException(IptvShareError.LOGIN_REFUSED)
                404 -> throw IptvShareException(IptvShareError.SHARE_NOT_FOUND)
                else -> Unit
            }
        }
    }

    override fun length(path: String): Long? = dav { entry(path)?.let { it.length ?: head(path) } }

    override fun openWrite(path: String): IptvShareFile = openWrite(path, -1)

    override fun openWrite(path: String, total: Long): IptvShareFile = dav {
        if (!partial) return@dav Upload(path, total.takeIf { it >= 0 })
        val existing = length(path)
        if (existing == null) call(request(path, "PUT", ByteArray(0).toRequestBody(null))).use { check(it, IptvShareError.FOLDER_NOT_FOUND) }
        Patch(path, existing ?: 0)
    }

    override fun openRead(path: String): IptvShareFile = dav {
        val size = length(path) ?: throw IptvShareException(IptvShareError.FOLDER_NOT_FOUND)
        Ranged(path, size)
    }

    override fun rename(from: String, to: String, replace: Boolean) = dav {
        val request = request(from, "MOVE").newBuilder().header("Destination", RecordingShareAddress.url(target, to))
            .header("Overwrite", if (replace) "T" else "F").build()
        call(request).use { check(it, IptvShareError.FOLDER_NOT_FOUND) }
    }

    override fun delete(path: String): Boolean = dav {
        call(request(path, "DELETE")).use { response -> if (response.code == 404) false else { check(response, IptvShareError.FOLDER_NOT_FOUND); true } }
    }

    override fun list(folder: String): List<String> = dav {
        call(propfind(folder, 1, collection = true, WebDavXml.propfind("resourcetype"))).use { response ->
            check(response, IptvShareError.FOLDER_NOT_FOUND)
            val own = WebDavXml.hrefPath(RecordingShareAddress.url(target, folder))
            WebDavXml.parse(text(response)).filter { it.path != own && it.path.isNotEmpty() }.map { it.path.substringAfterLast('/') }
        }
    }

    override fun ensureFolder(folder: String) = dav {
        if (entry(folder, collection = true)?.collection == true) return@dav
        var path = ""
        for (part in folder.split('/').filter { it.isNotEmpty() }) {
            path = if (path.isEmpty()) part else "$path/$part"
            call(request(path, "MKCOL", collection = true)).use { response ->
                if (response.code != 405 && response.code != 301) check(response, IptvShareError.ACCESS_DENIED)
            }
        }
    }

    override fun freeBytes(): Long = dav {
        val entry = call(propfind(target.folder, 0, collection = true, WebDavXml.propfind("quota-available-bytes"))).use { response ->
            if (response.code != 207) null else WebDavXml.parse(text(response)).firstOrNull()
        }
        entry?.available?.takeIf { it >= 0 } ?: Long.MAX_VALUE
    }

    override fun close() { }

    private fun entry(path: String, collection: Boolean = false): WebDavEntry? =
        call(propfind(path, 0, collection, WebDavXml.propfind("resourcetype", "getcontentlength"))).use { response ->
            if (response.code == 404) null else {
                check(response, IptvShareError.FOLDER_NOT_FOUND)
                WebDavXml.parse(text(response)).firstOrNull() ?: throw IptvShareException(IptvShareError.OTHER)
            }
        }

    private fun head(path: String): Long = call(request(path, "HEAD")).use { response ->
        check(response, IptvShareError.FOLDER_NOT_FOUND)
        response.header("Content-Length")?.toLongOrNull() ?: 0
    }

    private fun request(path: String, method: String, body: RequestBody? = null, collection: Boolean = false): Request =
        Request.Builder().url(RecordingShareAddress.url(target, path, collection)).method(method, body).build()

    private fun propfind(path: String, depth: Int, collection: Boolean, xml: String): Request =
        request(path, "PROPFIND", xml.toRequestBody(XML), collection).newBuilder().header("Depth", depth.toString()).build()

    private fun call(request: Request): Response = client.newCall(request).execute()

    private fun text(response: Response): String {
        val body = response.body
        if (body.contentLength() > MAX_XML) throw IptvShareException(IptvShareError.OTHER)
        val source = body.source()
        source.request(MAX_XML + 1)
        if (source.buffer.size > MAX_XML) throw IptvShareException(IptvShareError.OTHER)
        return source.buffer.readUtf8()
    }

    private inner class Patch(private val path: String, private var size: Long) : IptvShareFile {
        override val length: Long get() = size

        override fun write(offset: Long, buffer: ByteArray, start: Int, count: Int) {
            dav { append(offset, buffer, start, count) }
        }

        private fun append(offset: Long, buffer: ByteArray, start: Int, count: Int) {
            val range = if (offset == size) "append" else "bytes=$offset-${offset + count - 1}"
            val request = request(path, "PATCH", buffer.toRequestBody(PARTIAL, start, count)).newBuilder().header("X-Update-Range", range).build()
            call(request).use { check(it, IptvShareError.FOLDER_NOT_FOUND) }
            size = maxOf(size, offset + count)
        }

        override fun read(offset: Long, buffer: ByteArray, start: Int, count: Int): Int = throw IOException("Write only")

        override fun flush() {
            size = dav { length(path) } ?: throw IptvShareException(IptvShareError.FOLDER_NOT_FOUND)
        }

        override fun close() { }
    }

    private inner class Upload(private val path: String, private val total: Long?) : IptvShareFile {
        private val pending = Buffer()
        private var pipe: Pipe? = null
        private var sink: BufferedSink? = null
        private var running: Call? = null
        private val result = CompletableFuture<Response>()
        private var written = 0L
        private var done = false
        private var confirmed = 0L
        override val length: Long get() = if (done) confirmed else written

        override fun write(offset: Long, buffer: ByteArray, start: Int, count: Int) {
            dav { send(offset, buffer, start, count) }
        }

        private fun send(offset: Long, buffer: ByteArray, start: Int, count: Int) {
            if (done || offset != written || total != null && written + count > total) throw IOException("Append unsupported")
            if (result.isDone) result.getNow(null)?.use { check(it, IptvShareError.ACCESS_DENIED) }
            val out = sink
            if (out == null) {
                pending.write(buffer, start, count)
                written += count
                if (total != null || pending.size > MEMORY_BYTES) begin()
            } else {
                out.write(buffer, start, count)
                written += count
            }
        }

        private fun begin() {
            val stream = Pipe(PIPE_BYTES).also { pipe = it }
            stream.sink.timeout().timeout(IO_TIMEOUT_S, TimeUnit.SECONDS)
            val body = object : RequestBody() {
                override fun contentType(): MediaType = STREAM
                override fun contentLength(): Long = total ?: -1
                override fun isOneShot(): Boolean = true
                override fun writeTo(sink: BufferedSink) { stream.source.use { sink.writeAll(it) } }
            }
            val call = client.newCall(request(path, "PUT", body)).also { running = it }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { result.completeExceptionally(e); stream.cancel() }
                override fun onResponse(call: Call, response: Response) { result.complete(response) }
            })
            val out = stream.sink.buffer().also { sink = it }
            out.writeAll(pending)
        }

        override fun read(offset: Long, buffer: ByteArray, start: Int, count: Int): Int = throw IOException("Write only")

        override fun flush() {
            dav { finish() }
        }

        private fun finish() {
            if (done) return
            if (total != null && written != total) throw IOException("Upload incomplete")
            val out = sink
            val response = if (out == null) call(request(path, "PUT", pending.readByteArray().toRequestBody(STREAM))) else {
                out.close()
                try { result.get(FINISH_TIMEOUT_S, TimeUnit.SECONDS) } catch (error: ExecutionException) { throw error.cause ?: error }
                catch (error: TimeoutException) { throw IptvShareException(IptvShareError.TIMEOUT, error) }
            }
            response.use { check(it, IptvShareError.FOLDER_NOT_FOUND) }
            done = true
            confirmed = entry(path)?.length ?: written
        }

        override fun close() {
            if (!done) {
                running?.cancel()
                pipe?.cancel()
            }
            try { if (!done) sink?.close() } catch (_: Exception) { }
            if (result.isDone) try { result.getNow(null)?.close() } catch (_: Exception) { }
        }
    }

    private inner class Ranged(private val path: String, override val length: Long) : IptvShareFile {
        private var response: Response? = null
        private var source: BufferedSource? = null
        private var position = -1L

        override fun read(offset: Long, buffer: ByteArray, start: Int, count: Int): Int = dav {
            if (offset >= length) return@dav -1
            if (source == null || position != offset) open(offset)
            val input = source ?: return@dav -1
            val read = input.read(buffer, start, count)
            if (read < 0) { end(); -1 } else { position += read; read }
        }

        private fun open(offset: Long) {
            end()
            val request = request(path, "GET").newBuilder().header("Range", "bytes=$offset-").header("Accept-Encoding", "identity").build()
            val opened = call(request)
            try {
                when (opened.code) {
                    206 -> Unit
                    200 -> opened.body.source().skip(offset)
                    416 -> { opened.close(); return }
                    else -> check(opened, IptvShareError.FOLDER_NOT_FOUND)
                }
            } catch (error: Throwable) { opened.close(); throw error }
            response = opened
            source = opened.body.source()
            position = offset
        }

        private fun end() {
            try { response?.close() } catch (_: Exception) { }
            response = null
            source = null
            position = -1
        }

        override fun write(offset: Long, buffer: ByteArray, start: Int, count: Int) = throw IOException("Read only")
        override fun flush() { }
        override fun close() = end()
    }

    private fun check(response: Response, missing: IptvShareError) {
        if (response.isSuccessful) return
        throw IptvShareException(when (response.code) {
            401 -> IptvShareError.LOGIN_REFUSED
            403, 423 -> IptvShareError.ACCESS_DENIED
            404, 409 -> missing
            405 -> IptvShareError.ACCESS_DENIED
            408, 504 -> IptvShareError.TIMEOUT
            507 -> IptvShareError.FULL
            else -> IptvShareError.OTHER
        })
    }

    private inline fun <T> dav(block: () -> T): T = try { block() } catch (error: IptvShareException) { throw error } catch (error: Exception) {
        throw IptvShareException(networkError(error, trust), error)
    }

    private companion object {
        val XML = "application/xml; charset=utf-8".toMediaType()
        val PARTIAL = "application/x-sabredav-partialupdate".toMediaType()
        val STREAM = "application/octet-stream".toMediaType()
        const val MAX_XML = 4L * 1024 * 1024
        const val MEMORY_BYTES = 1024L * 1024
        const val PIPE_BYTES = 256L * 1024
        const val IO_TIMEOUT_S = 60L
        const val FINISH_TIMEOUT_S = 600L
    }
}
