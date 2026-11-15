package com.nuvio.tv.core.formatcheck

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/** Serves the unpacked clips to the player on this device only, the way a stream reaches it. */
internal class LocalClipServer(private val folder: File) {
    private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val clients: MutableSet<Socket> = ConcurrentHashMap.newKeySet()

    fun url(file: String) = "http://127.0.0.1:${socket.localPort}/$file"

    fun start() {
        thread(name = "format-check-clips", isDaemon = true) {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                // A connection that never sends its request, or a reader that went away without closing, would
                // otherwise hold its thread for the rest of the app's life.
                runCatching { client.soTimeout = CLIENT_READ_TIMEOUT_MS }
                clients += client
                thread(name = "format-check-clip", isDaemon = true) {
                    try {
                        client.use { runCatching { serve(it) } }
                    } finally {
                        clients -= client
                    }
                }
            }
        }
    }

    fun stop() {
        runCatching { socket.close() }
        clients.toList().forEach { runCatching { it.close() } }
        clients.clear()
    }

    internal fun openConnections(): Int = clients.size

    private fun serve(client: Socket) {
        val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.ISO_8859_1))
        val requestLine = reader.readLine() ?: return
        var rangeHeader: String? = null
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Range:", ignoreCase = true)) rangeHeader = line.substringAfter(':').trim()
        }
        val parts = requestLine.split(' ')
        val method = parts.getOrNull(0).orEmpty()
        val name = parts.getOrNull(1).orEmpty().substringBefore('?').removePrefix("/")
        val output = client.getOutputStream()
        val file = File(folder, name)
        if (!FormatCheckPack.isSafeEntryName(name) || !file.isFile) {
            output.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            return
        }
        val range = parseRange(rangeHeader, file.length())
        val start = range?.first ?: 0L
        val end = range?.last ?: (file.length() - 1)
        val headers = buildString {
            append(if (range != null) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            append("Content-Type: ${mimeOf(name)}\r\n")
            append("Accept-Ranges: bytes\r\n")
            append("Content-Length: ${end - start + 1}\r\n")
            if (range != null) append("Content-Range: bytes $start-$end/${file.length()}\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(headers.toByteArray())
        if (method == "HEAD") return
        file.inputStream().use { input ->
            input.skip(start)
            var left = end - start + 1
            val buffer = ByteArray(64 * 1024)
            while (left > 0) {
                val read = input.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
                if (read < 0) break
                output.write(buffer, 0, read)
                left -= read
            }
        }
    }

    internal companion object {
        const val CLIENT_READ_TIMEOUT_MS = 15_000
        fun parseRange(header: String?, size: Long): LongRange? {
            val match = Regex("bytes=(\\d+)-(\\d*)").matchEntire(header?.trim().orEmpty()) ?: return null
            val start = match.groupValues[1].toLongOrNull() ?: return null
            if (start >= size) return null
            val end = match.groupValues[2].toLongOrNull()?.coerceAtMost(size - 1) ?: (size - 1)
            return if (end >= start) start..end else null
        }

        fun mimeOf(name: String) = when (name.substringAfterLast('.').lowercase()) {
            "mkv" -> "video/x-matroska"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "ts" -> "video/mp2t"
            "avi" -> "video/x-msvideo"
            else -> "application/octet-stream"
        }
    }
}
