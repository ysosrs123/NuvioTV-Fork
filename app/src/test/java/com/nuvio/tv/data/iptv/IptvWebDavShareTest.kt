package com.nuvio.tv.data.iptv

import com.nuvio.tv.core.iptv.RecordingShareAddress
import com.nuvio.tv.core.iptv.RecordingShareTarget
import com.nuvio.tv.core.iptv.RecordingUpload
import com.nuvio.tv.core.iptv.WebDavAuth
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

object TestCertificate {
    private const val P12 = "MIID+gIBAzCCA6QGCSqGSIb3DQEHAaCCA5UEggORMIIDjTCCASQGCSqGSIb3DQEHAaCCARUEggERMIIBDTCCAQkGCyqGSIb3DQEMCgECoIG9MIG6MGYGCSqGSIb3DQEFDTBZMDgGCSqGSIb3DQEFDDArBBRqbVljYfeGaNFmuIeaTdiiULKfXAICJxACASAwDAYIKoZIhvcNAgkFADAdBglghkgBZQMEASoEEA8wz3ESfRISEGy3p8KJdNEEUMc1brlQ4vZSMgu9Zub+S9qOhn4PiUH0wWiLLzemUiTc3gcU4w3ZQ5Bc5hVNWnWBJZQvxNgSOnXBTvqKE3ajhqKr1oWty7IHJm1akyIGt33tMTowFQYJKoZIhvcNAQkUMQgeBgBuAGEAczAhBgkqhkiG9w0BCRUxFAQSVGltZSAxNzkxMzU0ODIwNjU0MIICYQYJKoZIhvcNAQcGoIICUjCCAk4CAQAwggJHBgkqhkiG9w0BBwEwZgYJKoZIhvcNAQUNMFkwOAYJKoZIhvcNAQUMMCsEFJWwi8Ky8/Z2qBAQ8ujiU19F1KZRAgInEAIBIDAMBggqhkiG9w0CCQUAMB0GCWCGSAFlAwQBKgQQvVRG3uRYhq5TEs9PhbtiPoCCAdBtLRt9chEFs34uBbIDQHtysdHViYDtC4hqJ/CGPrdo7jp7mY4la0qsWKZ1SZvQZvbqBfzSb1q1s1xxMEY0PT3uYbANuNljPVSFAPRTMk+s67QM4bMQ9f+J5Ni8OYN/UQNqdzEZU9tIeiQsRQvJCPSi98o/cHkag27JOTru+gkp3cPoL0g8slmearAlXGO4kw8Fva3dmMbiOe9jIyRMfgXwY8jkCvf5HPr+Xca9IpMdXo2NmOZT2VB2KTU2FC805B+Xw5uIH/wH5hhpbUCycRDtXQqwwwndjuP7JGZ6WzbmCnKO1qmtBLgBkA1z5TxzY4aKbjRMQ8n6aKogiPVbDaczzZNsCq+RWu2K+romeAVj0NZmOUE9VMJsUkcJCR5LpO44Oiuo4Ov5QwvaJFWi6DQyhCUp+6xLg21k5rFWaYBaaZtL27CUAqxza/Ey1qBwMKh/+IOSRGp6pZ6MorsWr9ZztcAS9aJX64WiP9axS0Mt4t1NMzF1jkb4fJ8PrbwqXi8kpCTJSVe+FezjX6QU/vNdA+p3LaUkV55nSEO11CisiLyrsuQLnDItv5Cy4wZSSe4/WnXATh4s70sc2SvzQIDI+j3v/soyNwjpkmsI3dd5/zBNMDEwDQYJYIZIAWUDBAIBBQAEIP34BHTzyZbhZWL7HcT3dEuVHq4H65ozXFlVWyC0kZvuBBQX62b11bLimnd+xUz6lpTejNvG4wICJxA="
    private val store: KeyStore by lazy {
        KeyStore.getInstance("PKCS12").apply { load(ByteArrayInputStream(Base64.getDecoder().decode(P12)), "secret".toCharArray()) }
    }
    val context: SSLContext by lazy {
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, "secret".toCharArray()) }
        SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }
    }
    val fingerprint: String by lazy { IptvShareTrust.fingerprint(store.getCertificate("nas") as X509Certificate) }
}

class FakeDav(private val partial: Boolean = false, private val auth: String? = null, private val user: String = "alice", private val pass: String = "pä55") : Dispatcher() {
    val files = LinkedHashMap<String, ByteArray>()
    val folders = HashSet<String>().apply { add("") }
    val requests = CopyOnWriteArrayList<RecordedRequest>()
    var free: Long? = 5_000_000_000
    private val nonce = AtomicInteger()

    @Synchronized override fun dispatch(request: RecordedRequest): MockResponse {
        requests += request
        if (auth != null && !authorised(request.getHeader("Authorization"), request.method!!, request.path!!)) {
            val challenge = if (auth == "basic") "Basic realm=\"nas\"" else "Digest realm=\"nas\", qop=\"auth\", nonce=\"n${nonce.incrementAndGet()}\", algorithm=MD5"
            return MockResponse().setResponseCode(401).addHeader("WWW-Authenticate", challenge)
        }
        val segments = request.requestUrl!!.pathSegments.filter { it.isNotEmpty() }
        if (segments.take(2) != listOf("my dav", "files")) return MockResponse().setResponseCode(404)
        val path = segments.drop(2).joinToString("/")
        val parent = path.substringBeforeLast('/', "")
        return when (request.method) {
            "OPTIONS" -> MockResponse().addHeader("DAV", if (partial) "1, 2, 3, sabredav-partialupdate" else "1, 2")
            "PROPFIND" -> propfind(path, request.getHeader("Depth") == "1")
            "MKCOL" -> when {
                path in folders || path in files -> MockResponse().setResponseCode(405)
                parent !in folders -> MockResponse().setResponseCode(409)
                else -> { folders += path; MockResponse().setResponseCode(201) }
            }
            "PUT" -> if (parent !in folders) MockResponse().setResponseCode(409) else {
                files[path] = request.body.readByteArray(); MockResponse().setResponseCode(201)
            }
            "PATCH" -> {
                val current = files[path] ?: return MockResponse().setResponseCode(404)
                if (request.getHeader("X-Update-Range") != "append" || request.getHeader("Content-Type") != "application/x-sabredav-partialupdate")
                    return MockResponse().setResponseCode(400)
                files[path] = current + request.body.readByteArray()
                MockResponse().setResponseCode(204)
            }
            "MOVE" -> {
                val data = files[path] ?: return MockResponse().setResponseCode(404)
                val destination = okhttp3.HttpUrl.Builder().scheme("http").host("x").build().resolve(request.getHeader("Destination")!!)!!
                val to = destination.pathSegments.filter { it.isNotEmpty() }.drop(2).joinToString("/")
                if (to in files && request.getHeader("Overwrite") == "F") return MockResponse().setResponseCode(412)
                files.remove(path)
                files[to] = data
                MockResponse().setResponseCode(201)
            }
            "DELETE" -> if (files.remove(path) != null) MockResponse().setResponseCode(204) else MockResponse().setResponseCode(404)
            "GET", "HEAD" -> {
                val data = files[path] ?: return MockResponse().setResponseCode(404)
                val from = request.getHeader("Range")?.removePrefix("bytes=")?.substringBefore('-')?.toInt() ?: 0
                MockResponse().setResponseCode(if (from > 0) 206 else 200).setBody(okio.Buffer().write(data, from, data.size - from))
            }
            else -> MockResponse().setResponseCode(405)
        }
    }

    private fun propfind(path: String, depth: Boolean): MockResponse {
        if (path !in folders && path !in files) return MockResponse().setResponseCode(404)
        val entries = mutableListOf(path)
        if (depth && path in folders) entries += (files.keys + folders).filter { it.isNotEmpty() && it.substringBeforeLast('/', "") == path }
        val body = entries.joinToString("", "<?xml version=\"1.0\"?><d:multistatus xmlns:d=\"DAV:\">", "</d:multistatus>") { entry ->
            val href = "/my%20dav/files/" + entry.split('/').filter { it.isNotEmpty() }.joinToString("/") { RecordingShareAddress.encode(it) } + if (entry in folders) "/" else ""
            "<d:response><d:href>$href</d:href><d:propstat><d:prop>" +
                (if (entry in folders) "<d:resourcetype><d:collection/></d:resourcetype>" else "<d:resourcetype/><d:getcontentlength>${files[entry]!!.size}</d:getcontentlength>") +
                (free?.let { "<d:quota-available-bytes>$it</d:quota-available-bytes>" } ?: "") + "</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
        }
        return MockResponse().setResponseCode(207).setBody(body)
    }

    private fun authorised(header: String?, method: String, uri: String): Boolean {
        if (header == null) return false
        if (auth == "basic") return header == WebDavAuth.basic(user, pass)
        val params = Regex("(\\w+)=\"?([^\",]*)\"?").findAll(header.removePrefix("Digest ")).associate { it.groupValues[1] to it.groupValues[2] }
        fun md5(text: String) = MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
        val expected = md5("${md5("$user:nas:$pass")}:${params["nonce"]}:${params["nc"]}:${params["cnonce"]}:auth:${md5("$method:$uri")}")
        return params["uri"] == uri && params["response"] == expected && params["username"] == user
    }
}

class IptvWebDavShareTest {
    @get:Rule val temp = TemporaryFolder()
    private val server = MockWebServer()

    @After fun stop() { server.shutdown() }

    private fun settings(folder: String = "TV/Live", user: String = "alice", pin: String? = null, secure: Boolean = false): IptvShareSettings {
        val target = RecordingShareAddress.webDav("${if (secure) "https" else "http"}://${server.hostName}:${server.port}/my%20dav/files", folder)!!
        return IptvShareSettings(target, user, "", user.isEmpty(), "probe0000", pin)
    }

    private fun start(dav: FakeDav): FakeDav { server.dispatcher = dav; server.start(); return dav }
    private fun bytes(size: Int, seed: Int) = ByteArray(size) { ((it * 13 + seed) % 251).toByte() }
    private fun spool(vararg pieces: ByteArray): File {
        val dir = temp.newFolder()
        var start = 0L
        pieces.forEach { File(dir, RecordingUpload.spoolName(start)).writeBytes(it); start += it.size }
        return dir
    }

    @Test fun probeCreatesFoldersWritesAndRemovesWithBasicAuthOnlyAfterTheChallenge() {
        val dav = start(FakeDav(auth = "basic"))
        val connector = IptvWebDavConnector(settings(), "pä55")
        assertEquals(IptvShareCheck(null, 5_000_000_000), IptvShareProbe.run(connector, "TV/Live"))
        assertTrue("TV/Live" in dav.folders)
        assertTrue(dav.files.isEmpty())
        assertNull(dav.requests.first().getHeader("Authorization"))
        val put = dav.requests.single { it.method == "PUT" }
        assertEquals(64L * 1024, put.bodySize)
        assertEquals("65536", put.getHeader("Content-Length"))
        assertFalse(connector.toString().contains(server.hostName))
    }

    @Test fun wrongPasswordAndMissingBaseAreReported() {
        start(FakeDav(auth = "digest"))
        assertEquals(IptvShareError.LOGIN_REFUSED, IptvShareProbe.run(IptvWebDavConnector(settings(), "wrong"), "TV").error)
        val missing = settings().let { it.copy(target = it.target.copy(share = "other")) }
        assertEquals(IptvShareError.LOGIN_REFUSED, IptvShareProbe.run(IptvWebDavConnector(missing.copy(username = ""), ""), "").error)
        assertEquals(IptvShareError.SHARE_NOT_FOUND, IptvShareProbe.run(IptvWebDavConnector(missing, "pä55"), "").error)
    }

    @Test fun digestUploadWaitsForTheEndThenSendsOneStreamedPut() = runBlocking {
        val dav = start(FakeDav(auth = "digest"))
        dav.folders += "TV"
        val a = bytes(3000, 1)
        val b = bytes(1700, 2)
        val dir = spool(a, b)
        var polls = 0
        val uploader = IptvRecordingUploader(IptvWebDavConnector(settings("TV"), "pä55"), pause = { polls += 1 }, chunkBytes = 1024, minimumFreeBytes = 0)
        val result = uploader.upload(dir, "TV/show one.ts", { 4700 }, { polls >= 3 }, 60_000)
        assertEquals(IptvUploadOutcome(IptvUploadResult.DONE, 4700), result)
        assertArrayEquals(a + b, dav.files["TV/show one.ts"])
        assertNull(dav.files["TV/show one.ts.part"])
        assertFalse(dir.exists())
        val puts = dav.requests.filter { it.method == "PUT" }
        assertEquals(1, puts.size)
        assertEquals("4700", puts.single().getHeader("Content-Length"))
        assertTrue(puts.single().getHeader("Authorization")!!.startsWith("Digest "))
        assertTrue(dav.requests.none { it.method == "PATCH" })
    }

    @Test fun foldersAreCreatedAndAFullShareKeepsTheSpool() = runBlocking {
        val dav = start(FakeDav())
        val dir = spool(bytes(500, 3))
        val result = IptvRecordingUploader(IptvWebDavConnector(settings("Missing/Deep"), ""), pause = { }, now = { 0L }, minimumFreeBytes = 0)
            .upload(dir, "Missing/Deep/a.ts", { Long.MAX_VALUE }, { true }, 0)
        assertEquals(IptvUploadResult.DONE, result.result)
        dav.files.clear()
        dav.folders.removeIf { it.isNotEmpty() }
        val failing = spool(bytes(500, 4))
        dav.free = 10
        val pending = IptvRecordingUploader(IptvWebDavConnector(settings("TV"), ""), pause = { }, now = { 0L }, minimumFreeBytes = 100)
            .upload(failing, "TV/b.ts", { Long.MAX_VALUE }, { true }, 0)
        assertEquals(IptvUploadResult.PENDING, pending.result)
        assertEquals(IptvShareError.FULL, pending.error)
        assertTrue(File(failing, RecordingUpload.spoolName(0)).exists())
    }

    @Test fun partialUpdateServersReceiveAppendsWhileRecording() = runBlocking {
        val dav = start(FakeDav(partial = true))
        val a = bytes(700, 5)
        val b = bytes(300, 6)
        val dir = spool(a, b)
        val result = IptvRecordingUploader(IptvWebDavConnector(settings(""), ""), pause = { }, chunkBytes = 256, minimumFreeBytes = 0)
            .upload(dir, "c.ts", { Long.MAX_VALUE }, { true }, 60_000)
        assertEquals(IptvUploadOutcome(IptvUploadResult.DONE, 1000), result)
        assertArrayEquals(a + b, dav.files["c.ts"])
        assertTrue(dav.requests.count { it.method == "PATCH" } >= 4)
        assertEquals(0L, dav.requests.first { it.method == "PUT" }.bodySize)
    }

    @Test fun readerListingRenameAndDeleteWorkThroughTheSession() {
        val dav = start(FakeDav())
        val data = bytes(5000, 7)
        dav.folders += "TV"
        dav.files["TV/a b.ts"] = data
        val connector = IptvWebDavConnector(settings("TV"), "")
        IptvShareReader(connector, "TV/a b.ts", windowBytes = 1024).use { reader ->
            assertEquals(5000L, reader.length())
            val buffer = ByteArray(100)
            assertEquals(100, reader.read(4000, buffer, 0, 100))
            assertArrayEquals(data.copyOfRange(4000, 4100), buffer)
            assertEquals(100, reader.read(10, buffer, 0, 100))
            assertArrayEquals(data.copyOfRange(10, 110), buffer)
            assertEquals(-1, reader.read(5000, buffer, 0, 100))
        }
        assertTrue(dav.requests.any { it.getHeader("Range") == "bytes=4000-" })
        connector.connect().use { session ->
            assertEquals(listOf("a b.ts"), session.list("TV"))
            assertEquals(5000L, session.length("TV/a b.ts"))
            assertNull(session.length("TV/none.ts"))
            session.rename("TV/a b.ts", "TV/c.ts", true)
            assertArrayEquals(data, dav.files["TV/c.ts"])
            assertTrue(session.delete("TV/c.ts"))
            assertFalse(session.delete("TV/c.ts"))
            assertEquals(5_000_000_000, session.freeBytes())
            dav.free = null
            assertEquals(Long.MAX_VALUE, session.freeBytes())
            assertFalse(session.append)
            assertEquals(IptvShareError.FOLDER_NOT_FOUND, (runCatching { session.list("Nope") }.exceptionOrNull() as IptvShareException).error)
        }
    }

    @Test fun selfSignedCertificateIsReportedThenAcceptedOnlyWhenPinned() {
        server.useHttps(TestCertificate.context.socketFactory, false)
        val dav = start(FakeDav())
        val plain = IptvWebDavConnector(settings("", secure = true), "")
        val first = IptvShareProbe.run(plain, "")
        if (first.error != IptvShareError.CERTIFICATE) {
            val chain = try { plain.connect().use { it.list("") }; "connected" } catch (error: Exception) {
                generateSequence<Throwable>(error) { it.cause }.take(8).joinToString(" <- ") { "${it.javaClass.name}: ${it.message}" }
            }
            fail("probe ${first.error}, presented ${plain.certificate}, ${chain}")
        }
        assertEquals(TestCertificate.fingerprint, plain.certificate)
        val other = IptvWebDavConnector(settings("", secure = true, pin = TestCertificate.fingerprint.replaceRange(0, 2, "00")), "")
        assertEquals(IptvShareError.CERTIFICATE, IptvShareProbe.run(other, "").error)
        val pinned = IptvWebDavConnector(settings("", secure = true, pin = TestCertificate.fingerprint), "")
        assertEquals(null, IptvShareProbe.run(pinned, "").error)
        assertNull(pinned.certificate)
        assertTrue(dav.requests.any { it.method == "PUT" })
    }

    @Test fun targetsAreWithheldFromText() {
        val target = RecordingShareTarget("nas", 1, "a", "b")
        assertFalse(IptvShareSettings(target, "u", "", false, "probe0000").toString().contains("nas"))
    }
}
