package com.nuvio.tv.data.iptv

import com.nuvio.tv.core.iptv.RecordingShareAddress
import com.nuvio.tv.core.iptv.RecordingUpload
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FakeFtp(private val tls: SSLContext? = null, private val refuseReuse: Boolean = false, private val extended: Boolean = true,
    private val machine: Boolean = true, private val avbl: Long? = null) : Closeable {
    val files: MutableMap<String, ByteArray> = ConcurrentHashMap<String, ByteArray>()
    val folders: MutableSet<String> = ConcurrentHashMap.newKeySet<String>().apply { add("") }
    val commands = CopyOnWriteArrayList<String>()
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val port: Int get() = server.localPort

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) { try { serve(socket) } catch (_: Exception) { } finally { socket.close() } }
            }
        }
    }

    override fun close() = server.close()

    private fun serve(plain: Socket) {
        var socket: Socket = plain
        var reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        var out: OutputStream = socket.getOutputStream()
        fun say(text: String) { out.write("$text\r\n".toByteArray(Charsets.UTF_8)); out.flush() }
        var cwd = ""
        var passive: ServerSocket? = null
        var rest = 0L
        var from: String? = null
        var logged = false
        var protect = false
        fun resolve(arg: String): String = when {
            arg.startsWith("/home/u") -> arg.removePrefix("/home/u").trim('/')
            cwd.isEmpty() -> arg.trim('/')
            else -> "$cwd/${arg.trim('/')}"
        }
        fun data(): Socket {
            val raw = passive!!.accept()
            passive!!.close()
            passive = null
            if (!protect) return raw
            return (tls!!.socketFactory.createSocket(raw, raw.inetAddress.hostAddress, raw.port, true) as SSLSocket).apply { useClientMode = false; startHandshake() }
        }
        say("220-Welcome\r\n220 Ready")
        while (true) {
            val line = reader.readLine() ?: return
            val verb = line.substringBefore(' ').uppercase()
            val arg = line.substringAfter(' ', "")
            commands += if (verb == "PASS") "PASS" else line
            if (!logged && verb !in setOf("USER", "PASS", "AUTH", "QUIT")) { say("530 Please login"); continue }
            when (verb) {
                "AUTH" -> {
                    say("234 Proceed")
                    socket = (tls!!.socketFactory.createSocket(socket, null, socket.port, true) as SSLSocket).apply { useClientMode = false; startHandshake() }
                    reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                    out = socket.getOutputStream()
                }
                "USER" -> say(if (arg == "u" || arg == "anonymous") "331 Password" else "530 No")
                "PASS" -> if (arg == "pw" || arg == "anonymous@") { logged = true; say("230 OK") } else say("530 Login incorrect")
                "PBSZ" -> say("200 PBSZ=0")
                "PROT" -> { protect = arg == "P"; say("200 OK") }
                "OPTS", "TYPE" -> say("200 OK")
                "PWD" -> say("257 \"/home/u${if (cwd.isEmpty()) "" else "/$cwd"}\" is current")
                "CWD" -> { val target = resolve(arg); if (target in folders) { cwd = target; say("250 OK") } else say("550 No such directory") }
                "CDUP" -> { cwd = cwd.substringBeforeLast('/', ""); say("250 OK") }
                "EPSV" -> if (!extended) say("502 Not implemented") else {
                    passive = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
                    say("229 Entering Extended Passive Mode (|||${passive!!.localPort}|)")
                }
                "PASV" -> {
                    passive = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
                    say("227 Entering Passive Mode (10,0,0,9,${passive!!.localPort / 256},${passive!!.localPort % 256})")
                }
                "SIZE" -> files[resolve(arg)]?.let { say("213 ${it.size}") } ?: say("550 Not found")
                "MKD" -> { val target = resolve(arg); if (target in folders || target.substringBeforeLast('/', "") !in folders) say("550 Exists") else { folders += target; say("257 \"$target\" created") } }
                "DELE" -> if (files.remove(resolve(arg)) != null) say("250 Deleted") else say("550 Not found")
                "RNFR" -> if (resolve(arg) in files) { from = resolve(arg); say("350 Ready") } else say("550 Not found")
                "RNTO" -> { val to = resolve(arg); if (to in files) say("553 Exists") else { files[to] = files.remove(from!!)!!; say("250 Renamed") } }
                "REST" -> { rest = arg.toLong(); say("350 Restarting") }
                "AVBL" -> if (avbl == null) say("502 Not implemented") else say("213 $avbl")
                "MLSD", "NLST" -> {
                    if (verb == "MLSD" && !machine) { say("500 Unknown"); continue }
                    val dir = if (arg.isEmpty()) cwd else resolve(arg)
                    if (dir !in folders) { passive?.close(); passive = null; say("550 No such directory"); continue }
                    say("150 Listing")
                    data().use { socket ->
                        val names = files.keys.filter { it.substringBeforeLast('/', "") == dir }
                        val text = if (verb == "MLSD") (listOf("type=cdir; .") + names.map { "type=file;size=${files[it]!!.size}; ${it.substringAfterLast('/')}" }).joinToString("\r\n", postfix = "\r\n")
                            else names.joinToString("\r\n", postfix = if (names.isEmpty()) "" else "\r\n")
                        socket.getOutputStream().write(text.toByteArray(Charsets.UTF_8))
                    }
                    say("226 Done")
                }
                "RETR" -> {
                    val content = files[resolve(arg)]
                    if (content == null) { say("550 Not found"); continue }
                    say("150 Sending")
                    val ok = try { data().use { it.getOutputStream().write(content, rest.toInt(), content.size - rest.toInt()) }; true } catch (_: Exception) { false }
                    rest = 0
                    say(if (ok) "226 Done" else "426 Aborted")
                }
                "APPE", "STOR" -> {
                    val target = resolve(arg)
                    if (target.substringBeforeLast('/', "") !in folders) { passive?.close(); passive = null; say("553 Cannot create"); continue }
                    say("150 Receiving")
                    if (protect && refuseReuse) { passive!!.accept().close(); passive!!.close(); passive = null; say("522 SSL connection failed: session reuse required"); continue }
                    val received = ByteArrayOutputStream()
                    data().use { it.getInputStream().copyTo(received) }
                    files[target] = (if (verb == "APPE") files[target] ?: ByteArray(0) else ByteArray(0)) + received.toByteArray()
                    say("226 Stored")
                }
                "QUIT" -> { say("221 Bye"); return }
                else -> say("502 Unknown")
            }
        }
    }
}

class IptvFtpShareTest {
    @get:Rule val temp = TemporaryFolder()
    private var ftp: FakeFtp? = null

    @After fun stop() { ftp?.close() }

    private fun server(fake: FakeFtp) = fake.also { ftp = it }
    private fun settings(fake: FakeFtp, folder: String = "TV/Live", user: String = "u", secure: Boolean = false, pin: String? = null) =
        IptvShareSettings(RecordingShareAddress.ftp("127.0.0.1:${fake.port}", folder, secure)!!, user, "", user.isEmpty(), "probe0000", pin)
    private fun bytes(size: Int, seed: Int) = ByteArray(size) { ((it * 7 + seed) % 251).toByte() }

    @Test fun probeCreatesFoldersAndCleansUp() {
        val fake = server(FakeFtp(avbl = 9_000_000_000))
        assertEquals(IptvShareCheck(null, 9_000_000_000), IptvShareProbe.run(IptvFtpConnector(settings(fake), "pw"), "TV/Live"))
        assertTrue("TV/Live" in fake.folders)
        assertTrue(fake.files.isEmpty())
        assertTrue(fake.commands.contains("TYPE I"))
        assertTrue(fake.commands.any { it.startsWith("MLSD") })
        assertFalse(fake.commands.any { it.contains("pw") })
    }

    @Test fun refusedLoginAndAnonymous() {
        val fake = server(FakeFtp())
        assertEquals(IptvShareError.LOGIN_REFUSED, IptvShareProbe.run(IptvFtpConnector(settings(fake), "bad"), "").error)
        assertEquals(null, IptvShareProbe.run(IptvFtpConnector(settings(fake, "", user = ""), ""), "").error)
        assertTrue(fake.commands.contains("USER anonymous"))
    }

    @Test fun uploadAppendsResumesAndRenames() = runBlocking {
        val fake = server(FakeFtp(extended = false, machine = false))
        fake.folders += "TV"
        val a = bytes(3000, 1)
        val b = bytes(1200, 2)
        val dir = temp.newFolder()
        File(dir, RecordingUpload.spoolName(0)).writeBytes(a)
        File(dir, RecordingUpload.spoolName(3000)).writeBytes(b)
        fake.files["TV/show.ts.part"] = a.copyOf(1000)
        val result = IptvRecordingUploader(IptvFtpConnector(settings(fake, "TV"), "pw"), pause = { }, chunkBytes = 512, minimumFreeBytes = 0)
            .upload(dir, "TV/show.ts", { Long.MAX_VALUE }, { true }, 60_000)
        assertEquals(IptvUploadOutcome(IptvUploadResult.DONE, 4200), result)
        assertArrayEquals(a + b, fake.files["TV/show.ts"])
        assertNull(fake.files["TV/show.ts.part"])
        assertFalse(dir.exists())
        assertTrue(fake.commands.any { it == "APPE TV/show.ts.part" })
        assertTrue(fake.commands.none { it.startsWith("STOR") })
        assertTrue(fake.commands.any { it == "PASV" })
        assertEquals(2, fake.commands.count { it.startsWith("APPE") })
    }

    @Test fun readerSeeksListsAndDeletes() {
        val fake = server(FakeFtp())
        val data = bytes(10_000, 3)
        fake.folders += "TV"
        fake.files["TV/a b.ts"] = data
        val connector = IptvFtpConnector(settings(fake, "TV"), "pw")
        IptvShareReader(connector, "TV/a b.ts", windowBytes = 2048).use { reader ->
            assertEquals(10_000L, reader.length())
            val buffer = ByteArray(300)
            assertEquals(300, reader.read(0, buffer, 0, 300))
            assertArrayEquals(data.copyOfRange(0, 300), buffer)
            assertEquals(300, reader.read(2048, buffer, 0, 300))
            assertArrayEquals(data.copyOfRange(2048, 2348), buffer)
            assertEquals(300, reader.read(7000, buffer, 0, 300))
            assertArrayEquals(data.copyOfRange(7000, 7300), buffer)
        }
        assertTrue(fake.commands.contains("REST 7000"))
        connector.connect().use { session ->
            assertEquals(listOf("a b.ts"), session.list("TV"))
            assertEquals(10_000L, session.length("TV/a b.ts"))
            assertNull(session.length("TV/x.ts"))
            session.rename("TV/a b.ts", "TV/b.ts", true)
            assertTrue(session.delete("TV/b.ts"))
            assertFalse(session.delete("TV/b.ts"))
            assertEquals(Long.MAX_VALUE, session.freeBytes())
            assertEquals(IptvShareError.FOLDER_NOT_FOUND, (runCatching { session.list("None") }.exceptionOrNull() as IptvShareException).error)
            assertEquals(IptvShareError.ACCESS_DENIED, (runCatching { session.ensureFolder("A/B") ; session.openWrite("Q/x.ts").use { it.write(0, data, 0, 10); it.flush() } }
                .exceptionOrNull() as IptvShareException).error)
        }
    }

    @Test fun explicitTlsNeedsThePinAndReportsSessionReuse() {
        val fake = server(FakeFtp(tls = TestCertificate.context, avbl = 1L shl 40))
        val plain = IptvFtpConnector(settings(fake, "", secure = true), "pw")
        assertEquals(IptvShareError.CERTIFICATE, IptvShareProbe.run(plain, "").error)
        assertEquals(TestCertificate.fingerprint, plain.certificate)
        assertEquals(IptvShareCheck(null, 1L shl 40), IptvShareProbe.run(IptvFtpConnector(settings(fake, "", secure = true, pin = TestCertificate.fingerprint), "pw"), ""))
        assertTrue(fake.commands.containsAll(listOf("AUTH TLS", "PBSZ 0", "PROT P")))
        val strict = server(FakeFtp(tls = TestCertificate.context, refuseReuse = true))
        assertEquals(IptvShareError.TLS_REUSE, IptvShareProbe.run(IptvFtpConnector(settings(strict, "", secure = true, pin = TestCertificate.fingerprint), "pw"), "").error)
        fake.close()
    }
}
