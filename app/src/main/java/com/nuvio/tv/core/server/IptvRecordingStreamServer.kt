package com.nuvio.tv.core.server

import com.nuvio.tv.core.iptv.SetupByteRange
import com.nuvio.tv.core.iptv.SetupPairing
import com.nuvio.tv.core.iptv.SetupRangeStream
import com.nuvio.tv.core.iptv.SetupRecordingDownloads
import com.nuvio.tv.data.iptv.IptvRecordingReader
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.SecureRandom
import kotlin.concurrent.thread

class IptvRecordingStreamServer private constructor(port: Int, private val reader: IptvRecordingReader, private val token: String,
    private val playlist: ByteArray?) : NanoHTTPD(HOST, port) {
    private val path = "/r/$token/$MEDIA"
    private val listPath = "/r/$token/$LIST"

    val url: String get() = "http://$HOST:$listeningPort${if (playlist != null) listPath else path}"

    override fun useGzipWhenAccepted(r: Response): Boolean = false

    override fun serve(session: IHTTPSession): Response {
        val head = session.method == Method.HEAD
        if (session.uri != path && (playlist == null || session.uri != listPath)) return text(Response.Status.NOT_FOUND, "Not found")
        if (!head && session.method != Method.GET) return text(Response.Status.METHOD_NOT_ALLOWED, "Method not allowed")
        if (playlist != null && session.uri == listPath) {
            return newFixedLengthResponse(Response.Status.OK, MIME_LIST, ByteArrayInputStream(if (head) ByteArray(0) else playlist), playlist.size.toLong())
                .also { it.addHeader("Cache-Control", "no-store") }
        }
        val length = try { reader.length() } catch (_: IOException) { return text(Response.Status.SERVICE_UNAVAILABLE, "Unavailable") }
        val range = SetupRecordingDownloads.range(session.headers["range"], length, session.headers["if-range"])
        if (range == SetupByteRange.Unsatisfiable) return text(Response.Status.RANGE_NOT_SATISFIABLE, "Range not satisfiable").also {
            it.addHeader("Content-Range", SetupRecordingDownloads.unsatisfiedRange(length))
            it.addHeader("Accept-Ranges", "bytes")
        }
        val part = range as? SetupByteRange.Part
        val count = part?.length ?: length
        val body = if (head) ByteArrayInputStream(ByteArray(0)) else SetupRangeStream(
            { position, buffer, offset, size -> reader.read(position, buffer, offset, size) }, part?.first ?: 0L, count, onClose = {})
        return newFixedLengthResponse(if (part == null) Response.Status.OK else Response.Status.PARTIAL_CONTENT, MIME_TS, body, count).also { response ->
            response.addHeader("Accept-Ranges", "bytes")
            response.addHeader("Cache-Control", "no-store")
            part?.let { response.addHeader("Content-Range", SetupRecordingDownloads.contentRange(it, length)) }
        }
    }

    override fun stop() {
        super.stop()
        thread(name = "recording-close", isDaemon = true) { runCatching { reader.close() } }
    }

    private fun text(status: Response.Status, body: String): Response = newFixedLengthResponse(status, "text/plain; charset=utf-8", body)

    companion object {
        private const val HOST = "127.0.0.1"
        private const val MIME_TS = "video/mp2t"
        private const val MIME_LIST = "application/vnd.apple.mpegurl"
        const val MEDIA = "recording.ts"
        private const val LIST = "recording.m3u8"

        fun start(reader: IptvRecordingReader, playlist: String? = null, startPort: Int = 8150, maxAttempts: Int = 20): IptvRecordingStreamServer? {
            val token = SetupPairing.hex(ByteArray(16).also(SecureRandom()::nextBytes))
            for (port in startPort until startPort + maxAttempts) {
                try {
                    return IptvRecordingStreamServer(port, reader, token, playlist?.toByteArray(Charsets.UTF_8)).also { it.start(SOCKET_READ_TIMEOUT, true) }
                } catch (_: IOException) {
                }
            }
            return null
        }
    }
}
