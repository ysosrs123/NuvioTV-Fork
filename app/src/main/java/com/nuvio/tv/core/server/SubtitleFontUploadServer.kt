package com.nuvio.tv.core.server

import android.content.Context
import android.util.Log
import com.nuvio.tv.core.player.ImportedSubtitleFonts
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

class SubtitleFontUploadServer(
    private val context: Context,
    private val onFontUploaded: (fileName: String, bytes: ByteArray) -> Boolean,
    private val logoProvider: () -> ByteArray?,
    port: Int,
    val key: String = newKey()
) : NanoHTTPD(port) {

    private val uploading = AtomicBoolean(false)

    @Volatile
    var lastActivityAt: Long = System.currentTimeMillis()
        private set

    override fun serve(session: IHTTPSession): Response = try {
        route(session)
    } catch (t: Throwable) {
        Log.w(TAG, "Font upload request failed: ${t.javaClass.simpleName}")
        jsonResponse(Response.Status.INTERNAL_ERROR, """{"error":"failed"}""")
    }

    private fun route(session: IHTTPSession): Response = when {
        session.method == Method.GET && session.uri == "/logo.png" -> serveLogo()
        !isKeyValid(key, session.parameters["k"]?.firstOrNull() ?: session.headers["x-upload-key"]) ->
            newFixedLengthResponse(Response.Status.UNAUTHORIZED, MIME_PLAINTEXT, "Not allowed")
        session.method == Method.GET && session.uri == "/" -> {
            lastActivityAt = System.currentTimeMillis()
            newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", SubtitleFontWebPage.html(context))
        }
        session.method == Method.POST && session.uri == "/api/font" -> handleUpload(session)
        else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
    }

    private fun serveLogo(): Response {
        val bytes = logoProvider() ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        return newFixedLengthResponse(Response.Status.OK, "image/png", ByteArrayInputStream(bytes), bytes.size.toLong())
    }

    private fun handleUpload(session: IHTTPSession): Response {
        val fileName = session.headers["x-file-name"]
            ?.let { runCatching { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }.getOrNull() }
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
        val length = session.headers["content-length"]?.toLongOrNull()
        if (fileName == null || length == null || length <= 0 || length > ImportedSubtitleFonts.MAX_BYTES) {
            return jsonResponse(Response.Status.BAD_REQUEST, """{"error":"invalid"}""")
        }
        if (!uploading.compareAndSet(false, true)) {
            return jsonResponse(Response.Status.SERVICE_UNAVAILABLE, """{"error":"busy"}""")
        }
        try {
            lastActivityAt = System.currentTimeMillis()
            val bytes = readBounded(session.inputStream, length, ImportedSubtitleFonts.MAX_BYTES)
            if (bytes == null || !onFontUploaded(fileName, bytes)) {
                return jsonResponse(Response.Status.BAD_REQUEST, """{"error":"invalid"}""")
            }
            return jsonResponse(Response.Status.OK, """{"status":"added"}""")
        } finally {
            uploading.set(false)
        }
    }

    private fun jsonResponse(status: Response.Status, body: String): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", body)

    companion object {
        private const val TAG = "SubtitleFontUpload"
        private const val READ_CHUNK = 64 * 1024

        fun newKey(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        fun isKeyValid(expected: String, given: String?): Boolean {
            if (given == null || expected.isEmpty()) return false
            return MessageDigest.isEqual(
                expected.toByteArray(StandardCharsets.UTF_8),
                given.toByteArray(StandardCharsets.UTF_8)
            )
        }

        /**
         * Reads exactly [declaredLength] bytes, growing the buffer only as data arrives, so a request that claims a
         * large body costs nothing until it sends one. Null when the body is short or over [maxBytes].
         */
        fun readBounded(input: InputStream, declaredLength: Long, maxBytes: Int): ByteArray? {
            if (declaredLength <= 0 || declaredLength > maxBytes) return null
            val out = ByteArrayOutputStream(minOf(declaredLength.toInt(), READ_CHUNK))
            val chunk = ByteArray(READ_CHUNK)
            var remaining = declaredLength
            while (remaining > 0) {
                val read = input.read(chunk, 0, minOf(chunk.size.toLong(), remaining).toInt())
                if (read <= 0) return null
                out.write(chunk, 0, read)
                remaining -= read
            }
            return out.toByteArray()
        }

        fun startOnAvailablePort(
            context: Context,
            onFontUploaded: (fileName: String, bytes: ByteArray) -> Boolean,
            logoProvider: () -> ByteArray?,
            startPort: Int = 8094,
            maxAttempts: Int = 10
        ): SubtitleFontUploadServer? {
            for (port in startPort until startPort + maxAttempts) {
                val server = SubtitleFontUploadServer(context, onFontUploaded, logoProvider, port)
                if (runCatching { server.start(SOCKET_READ_TIMEOUT, false) }.isSuccess) return server
            }
            return null
        }
    }
}
