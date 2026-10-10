package com.nuvio.tv.data.iptv

import com.nuvio.tv.core.iptv.FtpProtocol
import com.nuvio.tv.core.iptv.FtpReply
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket

class IptvFtpConnector(private val settings: IptvShareSettings, private val password: String) : IptvShareConnector {
    private val trust = IptvShareTrust(settings.pin)
    override val certificate: String? get() = trust.presented

    override fun connect(): IptvShareSession {
        val session = IptvFtpSession(settings, password, trust)
        try { session.open() } catch (error: Throwable) { session.close(); throw error }
        return session
    }

    override fun toString(): String = "IptvFtpConnector(withheld)"
}

private class IptvFtpSession(private val settings: IptvShareSettings, private val password: String, private val trust: IptvShareTrust) : IptvShareSession {
    private val target = settings.target
    private val port = target.port ?: 21
    private var control: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var home: String? = null
    private var extended = true
    private var machine = true
    private var available = true
    private var active: FtpFile? = null

    fun open() = ftp {
        val plain = Socket()
        control = plain
        plain.connect(InetSocketAddress(target.host, port), CONNECT_TIMEOUT_MS)
        plain.soTimeout = IO_TIMEOUT_MS
        streams(plain)
        var greeting = reply()
        while (greeting.preliminary) greeting = reply()
        if (!greeting.ok) throw failure(greeting, IptvShareError.OTHER)
        if (target.secure) {
            val auth = command("AUTH TLS")
            if (auth.code != 234) throw IptvShareException(IptvShareError.NO_TLS)
            val secure = trust.factory.createSocket(plain, target.host, port, true) as SSLSocket
            control = secure
            secure.useClientMode = true
            secure.soTimeout = IO_TIMEOUT_MS
            secure.startHandshake()
            if (!trust.verify(target.host, secure.session)) throw SSLPeerUnverifiedException("Certificate not verified")
            streams(secure)
        }
        val anonymous = settings.guest || settings.username.isEmpty()
        val user = command("USER " + if (anonymous) "anonymous" else settings.username)
        if (user.code == 331 || user.code == 332) {
            val pass = command("PASS " + if (anonymous) "anonymous@" else password)
            if (pass.code != 230 && pass.code != 202) throw failure(pass, IptvShareError.LOGIN_REFUSED, IptvShareError.LOGIN_REFUSED)
        } else if (user.code != 230) throw failure(user, IptvShareError.LOGIN_REFUSED, IptvShareError.LOGIN_REFUSED)
        if (target.secure) {
            command("PBSZ 0")
            if (!command("PROT P").ok) throw IptvShareException(IptvShareError.NO_TLS)
        }
        command("OPTS UTF8 ON")
        command("TYPE I").let { if (!it.ok) throw failure(it, IptvShareError.OTHER) }
        home = command("PWD").takeIf { it.code == 257 }?.let { FtpProtocol.directory(it.text) }
    }

    override fun length(path: String): Long? = ftp { size(path) }

    override fun openWrite(path: String): IptvShareFile = ftp { FtpFile(path, size(path) ?: 0) }

    override fun openRead(path: String): IptvShareFile = ftp { FtpFile(path, size(path) ?: throw IptvShareException(IptvShareError.FOLDER_NOT_FOUND)) }

    override fun rename(from: String, to: String, replace: Boolean) = ftp {
        if (replace && from != to) command("DELE $to")
        if (!replace && from != to && size(to) != null) throw IptvShareException(IptvShareError.OTHER)
        val first = command("RNFR $from")
        if (first.code != 350) throw failure(first, IptvShareError.FOLDER_NOT_FOUND)
        val second = command("RNTO $to")
        if (!second.ok) throw failure(second, IptvShareError.ACCESS_DENIED)
    }

    override fun delete(path: String): Boolean = ftp {
        val reply = command("DELE $path")
        when {
            reply.ok -> true
            reply.code == 550 && size(path) == null -> false
            else -> throw failure(reply, IptvShareError.ACCESS_DENIED)
        }
    }

    override fun list(folder: String): List<String> = ftp {
        if (machine) {
            val lines = listing("MLSD" + if (folder.isEmpty()) "" else " $folder")
            if (lines != null) return@ftp FtpProtocol.names(lines, true)
            machine = false
        }
        FtpProtocol.names(listing("NLST" + if (folder.isEmpty()) "" else " $folder") ?: throw IptvShareException(IptvShareError.OTHER), false)
    }

    override fun ensureFolder(folder: String) = ftp {
        var path = ""
        for (part in folder.split('/').filter { it.isNotEmpty() }) {
            path = if (path.isEmpty()) part else "$path/$part"
            val made = command("MKD $path")
            if (made.code == 257 || exists(path)) continue
            throw failure(made, IptvShareError.ACCESS_DENIED)
        }
    }

    override fun freeBytes(): Long = ftp {
        if (!available) return@ftp Long.MAX_VALUE
        val reply = command("AVBL" + if (target.folder.isEmpty()) "" else " ${target.folder}")
        if (reply.code != 213) { available = false; return@ftp Long.MAX_VALUE }
        reply.text.trim().split(' ').firstNotNullOfOrNull { it.toLongOrNull() }?.takeIf { it >= 0 } ?: Long.MAX_VALUE
    }

    override fun close() {
        try { active?.abandon() } catch (_: Exception) { }
        active = null
        val socket = control ?: return
        try {
            socket.soTimeout = CLOSE_TIMEOUT_MS
            send("QUIT")
            reply()
        } catch (_: Exception) { }
        try { socket.close() } catch (_: Exception) { }
        control = null
    }

    private fun streams(socket: Socket) {
        input = BufferedInputStream(socket.getInputStream())
        output = BufferedOutputStream(socket.getOutputStream())
    }

    private fun size(path: String): Long? {
        val reply = command("SIZE $path")
        if (reply.code == 213) return reply.text.trim().split(' ').firstNotNullOfOrNull { it.toLongOrNull() } ?: throw failure(reply, IptvShareError.OTHER)
        if (reply.code == 550) return null
        val fallback = command("MLST $path")
        if (fallback.code == 550) return null
        if (fallback.code != 250) throw failure(reply, IptvShareError.OTHER)
        val line = fallback.text.lines().drop(1).firstOrNull { it.isNotBlank() } ?: return null
        return FtpProtocol.facts(line)["size"]?.toLongOrNull()
    }

    private fun exists(folder: String): Boolean {
        if (command("CWD $folder").code != 250) return false
        val back = home
        if (back != null) command("CWD $back") else repeat(folder.count { it == '/' } + 1) { command("CDUP") }
        return true
    }

    private fun listing(line: String): List<String>? {
        val data = try { transfer(line, null) } catch (error: IptvShareException) {
            if (error.error == IptvShareError.FOLDER_NOT_FOUND && error.cause is UnsupportedCommand) return null
            if (line.startsWith("NLST") && error.cause is EmptyListing) return emptyList()
            throw error
        }
        val bytes = ByteArrayOutputStream()
        try {
            data.getInputStream().use { stream ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    bytes.write(buffer, 0, count)
                    if (bytes.size() > MAX_LISTING) throw IptvShareException(IptvShareError.OTHER)
                }
            }
        } finally { try { data.close() } catch (_: Exception) { } }
        val done = reply()
        if (!done.ok) throw failure(done, IptvShareError.OTHER)
        return bytes.toString(Charsets.UTF_8.name()).split('\n')
    }

    private fun transfer(line: String, rest: Long?): Socket {
        active?.let { it.finish(); active = null }
        val data = passive()
        try {
            if (rest != null && rest > 0) {
                val restart = command("REST $rest")
                if (restart.code != 350) throw failure(restart, IptvShareError.OTHER)
            }
            val started = command(line)
            if (started.code != 150 && started.code != 125) {
                val cause = when {
                    started.code in setOf(500, 501, 502, 504) && (line.startsWith("MLSD")) -> UnsupportedCommand()
                    line.startsWith("NLST") && (started.code == 450 || started.code == 550 && started.text.contains("no files", true)) -> EmptyListing()
                    else -> null
                }
                val error = if (cause is UnsupportedCommand) IptvShareError.FOLDER_NOT_FOUND
                    else if (line.startsWith("STOR") || line.startsWith("APPE")) code(started, IptvShareError.ACCESS_DENIED) else code(started, IptvShareError.FOLDER_NOT_FOUND)
                throw IptvShareException(error, cause)
            }
            if (!target.secure) return data
            return try {
                (trust.factory.createSocket(data, target.host, port, true) as SSLSocket).apply {
                    useClientMode = true
                    soTimeout = IO_TIMEOUT_MS
                    startHandshake()
                    if (!trust.verify(target.host, session)) throw SSLPeerUnverifiedException("Certificate not verified")
                }
            } catch (error: IOException) { throw reused(error) }
        } catch (error: Throwable) {
            try { data.close() } catch (_: Exception) { }
            throw error
        }
    }

    private fun passive(): Socket {
        val peer = control?.inetAddress ?: throw IptvShareException(IptvShareError.DISCONNECTED)
        var address: InetSocketAddress? = null
        if (extended) {
            val reply = command("EPSV")
            val port = if (reply.code == 229) FtpProtocol.extendedPort(reply.text) else null
            if (port != null) address = InetSocketAddress(peer, port) else extended = false
        }
        if (address == null) {
            val reply = command("PASV")
            val offered = (if (reply.code == 227) FtpProtocol.passive(reply.text) else null) ?: throw failure(reply, IptvShareError.OTHER)
            val host = FtpProtocol.dataHost(offered.first, target.host, peer.address)
            address = if (host == offered.first) InetSocketAddress(host, offered.second) else InetSocketAddress(peer, offered.second)
        }
        val socket = Socket()
        try {
            socket.connect(address, CONNECT_TIMEOUT_MS)
            socket.soTimeout = IO_TIMEOUT_MS
        } catch (error: Throwable) {
            try { socket.close() } catch (_: Exception) { }
            throw error
        }
        return socket
    }

    private fun reused(error: IOException): IOException {
        val reply = try {
            control?.soTimeout = CLOSE_TIMEOUT_MS
            reply()
        } catch (_: Exception) { null } finally { try { control?.soTimeout = IO_TIMEOUT_MS } catch (_: Exception) { } }
        return when {
            reply != null && FtpProtocol.reuseRequired(reply) -> IptvShareException(IptvShareError.TLS_REUSE, error)
            reply != null && !reply.ok -> failure(reply, IptvShareError.DISCONNECTED)
            else -> error
        }
    }

    private fun command(line: String): FtpReply {
        active?.let { it.finish(); active = null }
        send(line)
        return reply()
    }

    private fun send(line: String) {
        if ('\r' in line || '\n' in line) throw IptvShareException(IptvShareError.OTHER)
        val out = output ?: throw IptvShareException(IptvShareError.DISCONNECTED)
        out.write((line + "\r\n").toByteArray(Charsets.UTF_8))
        out.flush()
    }

    private fun reply(): FtpReply {
        val first = line()
        val code = first.take(3).toIntOrNull()?.takeIf { first.length == 3 || first[3] == ' ' || first[3] == '-' } ?: throw IptvShareException(IptvShareError.OTHER)
        val text = StringBuilder(first.drop(4))
        if (first.length > 3 && first[3] == '-') {
            while (true) {
                val next = line()
                if (next.startsWith("$code ")) { text.append('\n').append(next.drop(4)); break }
                text.append('\n').append(next)
                if (text.length > MAX_REPLY) throw IptvShareException(IptvShareError.OTHER)
            }
        }
        return FtpReply(code, text.toString())
    }

    private fun line(): String {
        val stream = input ?: throw IptvShareException(IptvShareError.DISCONNECTED)
        val bytes = ByteArrayOutputStream()
        while (true) {
            val value = stream.read()
            if (value < 0) throw EOFException("Control connection closed")
            if (value == '\n'.code) break
            if (bytes.size() < MAX_LINE) bytes.write(value)
        }
        return bytes.toString(Charsets.UTF_8.name()).trimEnd('\r')
    }

    private fun code(reply: FtpReply, missing: IptvShareError, refused: IptvShareError = IptvShareError.ACCESS_DENIED): IptvShareError = when (reply.code) {
        530, 532 -> refused
        550 -> missing
        452, 552 -> IptvShareError.FULL
        553 -> IptvShareError.ACCESS_DENIED
        421, 425, 426 -> IptvShareError.DISCONNECTED
        522 -> IptvShareError.TLS_REUSE
        else -> if (FtpProtocol.reuseRequired(reply) && target.secure) IptvShareError.TLS_REUSE else IptvShareError.OTHER
    }

    private fun failure(reply: FtpReply, missing: IptvShareError, refused: IptvShareError = IptvShareError.ACCESS_DENIED) =
        IptvShareException(code(reply, missing, refused))

    private inner class FtpFile(private val path: String, private var remote: Long) : IptvShareFile {
        private var data: Socket? = null
        private var reading: InputStream? = null
        private var writing: OutputStream? = null
        private var position = 0L
        override val length: Long get() = if (writing != null) position else remote

        override fun read(offset: Long, buffer: ByteArray, start: Int, count: Int): Int = ftp {
            if (offset >= remote) return@ftp -1
            if (reading == null || position != offset) {
                end()
                val socket = transfer("RETR $path", offset)
                data = socket
                reading = socket.getInputStream()
                position = offset
                active = this
            }
            val read = reading!!.read(buffer, start, count)
            if (read < 0) { end(); -1 } else { position += read; read }
        }

        override fun write(offset: Long, buffer: ByteArray, start: Int, count: Int) {
            ftp {
                if (writing != null && offset != position) finish()
                if (writing == null) {
                    if (reading != null) end()
                    val socket = when (offset) {
                        remote -> transfer("APPE $path", null)
                        0L -> transfer("STOR $path", null)
                        else -> throw IOException("Unaligned write")
                    }
                    data = socket
                    writing = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
                    position = offset
                    active = this
                }
                try { writing!!.write(buffer, start, count) } catch (error: IOException) { abandon(); throw reused(error) }
                position += count
            }
        }

        override fun flush() {
            ftp {
                if (writing != null) finish()
                remote = size(path) ?: 0
            }
        }

        fun finish() {
            if (active === this) active = null
            val out = writing
            if (out != null) {
                writing = null
                try { out.flush(); data?.close() } catch (error: IOException) { data = null; throw reused(error) }
                data = null
                val done = reply()
                if (!done.ok) throw failure(done, IptvShareError.ACCESS_DENIED)
                remote = position
            } else if (reading != null) end()
        }

        private fun end() {
            if (active === this) active = null
            val socket = data ?: return
            data = null
            reading = null
            try { socket.close() } catch (_: Exception) { }
            try {
                control?.soTimeout = CLOSE_TIMEOUT_MS
                reply()
            } catch (_: Exception) { } finally { try { control?.soTimeout = IO_TIMEOUT_MS } catch (_: Exception) { } }
        }

        fun abandon() {
            if (active === this) active = null
            writing = null
            reading = null
            try { data?.close() } catch (_: Exception) { }
            data = null
        }

        override fun close() {
            try { if (writing != null) finish() else end() } catch (_: Exception) { abandon() }
        }
    }

    private class UnsupportedCommand : IOException("Unsupported")
    private class EmptyListing : IOException("Empty")

    private inline fun <T> ftp(block: () -> T): T = try { block() } catch (error: IptvShareException) { throw error } catch (error: Exception) {
        throw IptvShareException(networkError(error, trust), error)
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val IO_TIMEOUT_MS = 60_000
        const val CLOSE_TIMEOUT_MS = 5_000
        const val MAX_LINE = 8 * 1024
        const val MAX_REPLY = 64 * 1024
        const val MAX_LISTING = 8 * 1024 * 1024
    }
}
