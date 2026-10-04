package com.nuvio.tv.core.network

import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.Executor
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ServerTrustTest {
    private val media = certificate(
        """
            -----BEGIN CERTIFICATE-----
            MIIBmDCCAT6gAwIBAgIUYPRseI13HWHz9DqlBm12fWpJekowCgYIKoZIzj0EAwIw
            FTETMBEGA1UEAwwKbWVkaWEuaG9tZTAgFw0yNjEwMDMwODMxNTZaGA8yMTI2MDkw
            OTA4MzE1NlowFTETMBEGA1UEAwwKbWVkaWEuaG9tZTBZMBMGByqGSM49AgEGCCqG
            SM49AwEHA0IABNIOQk1WehT7fK98/RZ01aElpY0C7S4/DhXGLSQ2J+bWl70rV8Z3
            uDYTKP8DEnw9iFlTILcmaRUMwh+rcuXCYfajajBoMB0GA1UdDgQWBBRD//If1Ne/
            ogM3jjad4NjGmcG/zTAfBgNVHSMEGDAWgBRD//If1Ne/ogM3jjad4NjGmcG/zTAP
            BgNVHRMBAf8EBTADAQH/MBUGA1UdEQQOMAyCCm1lZGlhLmhvbWUwCgYIKoZIzj0E
            AwIDSAAwRQIhAKjAIy8gRmkphND4qW3dr7+KLB/UwrWujanj5A+U5jMgAiBGs4ws
            EtKL4/lX/K2YvwJZrZPzT3wSU0F1Q84CXqQiAg==
            -----END CERTIFICATE-----
        """
    )
    private val other = certificate(
        """
            -----BEGIN CERTIFICATE-----
            MIIBmDCCAT6gAwIBAgIUNhvYOSQL1AqOPyx3j56csaOj/OcwCgYIKoZIzj0EAwIw
            FTETMBEGA1UEAwwKb3RoZXIuaG9tZTAgFw0yNjEwMDMwODMxNTZaGA8yMTI2MDkw
            OTA4MzE1NlowFTETMBEGA1UEAwwKb3RoZXIuaG9tZTBZMBMGByqGSM49AgEGCCqG
            SM49AwEHA0IABGBRjG82Ss2D/jFNnhxbOsoK3QvCnDTLUp1u/4LDe9nnlaxvvFIa
            fpGB0b74B2FPEIxdr4OHP7bkz1WqvlQE8R2jajBoMB0GA1UdDgQWBBQ6UXXTlRP3
            dxOQlF/lFvBKKIN5QDAfBgNVHSMEGDAWgBQ6UXXTlRP3dxOQlF/lFvBKKIN5QDAP
            BgNVHRMBAf8EBTADAQH/MBUGA1UdEQQOMAyCCm90aGVyLmhvbWUwCgYIKoZIzj0E
            AwIDSAAwRQIgRC5A48VXMIa0SnQt2e8lCKyHDo229k0Z/LaDlcBkSLsCIQD38/1U
            zmAzCuzUyWlAGeCd5ODUzKVVQzDuj2ShQpTSrA==
            -----END CERTIFICATE-----
        """
    )
    private val mediaFingerprint = "d8054fa7d0c28d0c0d5c3728eebcb404e6c4f7a8fc67cdb1acf0201b3623d402"

    private val refusingPlatform = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
            throw CertificateException("not trusted")
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    @Before
    fun reset() = ServerTrust.update(emptyMap(), emptySet())

    private val defaultCloser = ServerTrust.closer

    @After
    fun clear() {
        ServerTrust.update(emptyMap(), emptySet())
        ServerTrust.closer = defaultCloser
    }

    @Test
    fun fingerprintsMatchWhatOpensslPrints() {
        assertEquals(mediaFingerprint, sha256Fingerprint(media))
        assertEquals(
            "D8:05:4F:A7:D0:C2:8D:0C:0D:5C:37:28:EE:BC:B4:04:E6:C4:F7:A8:FC:67:CD:B1:AC:F0:20:1B:36:23:D4:02",
            displayFingerprint(mediaFingerprint)
        )
    }

    @Test
    fun onlyAnAcceptedCertificatePassesWhenThePlatformRefusesIt() {
        val trust = ServerTrust.trustManager(refusingPlatform)
        assertThrows(CertificateException::class.java) { trust.checkServerTrusted(arrayOf(media), "EC") }

        ServerTrust.update(mapOf("media.home" to setOf(mediaFingerprint)), setOf("media.home"))
        trust.checkServerTrusted(arrayOf(media), "EC")
        assertThrows(CertificateException::class.java) { trust.checkServerTrusted(arrayOf(other), "EC") }
    }

    @Test
    fun anAcceptedCertificateIsTiedToItsHost() {
        ServerTrust.update(mapOf("Media.Home" to setOf(mediaFingerprint)), setOf("media.home"))
        val verifier = ServerTrust.hostnameVerifier(HostnameVerifier { _, _ -> false })

        assertTrue(verifier.verify("media.home", session(media)))
        assertFalse(verifier.verify("other.home", session(media)))
        assertFalse(verifier.verify("media.home", session(other)))
        assertTrue(ServerTrust.hostnameVerifier(HostnameVerifier { _, _ -> true }).verify("other.home", session(other)))
    }

    @Test
    fun aSignInApprovalSurvivesOtherSavesUntilTheSignInEnds() {
        ServerTrust.allow("MEDIA.home", mediaFingerprint.uppercase())
        assertTrue(ServerTrust.isPinned("media.home", media))

        ServerTrust.update(emptyMap(), setOf("Media.Home"))
        assertTrue(ServerTrust.isPinned("media.home", media))
        assertTrue(ServerTrust.isServerHost("media.HOME"))
        assertFalse(ServerTrust.isServerHost("cdn.example.com"))

        ServerTrust.clearPending()
        assertFalse(ServerTrust.isPinned("media.home", media))
    }

    @Test
    fun aPinOnlyHelpsTheHostItWasAcceptedFor() {
        ServerTrust.update(mapOf("media.home" to setOf(mediaFingerprint)), setOf("media.home", "other.home"))
        val trust = ServerTrust.trustManager(refusingPlatform) as X509ExtendedTrustManager

        trust.checkServerTrusted(arrayOf(media), "EC", socketFor("media.home"))
        assertThrows(CertificateException::class.java) { trust.checkServerTrusted(arrayOf(media), "EC", socketFor("other.home")) }
        assertFalse(ServerTrust.hostnameVerifier(HostnameVerifier { _, _ -> true }).verify("other.home", session(media)))
    }

    @Test
    fun authoritiesTheUserInstalledOnlyCountForMediaServers() {
        val userAuthorities = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        ServerTrust.update(emptyMap(), setOf("media.home"))
        val trust = ServerTrust.trustManager(refusingPlatform, userAuthorities) as X509ExtendedTrustManager

        trust.checkServerTrusted(arrayOf(media), "EC", socketFor("media.home"))
        assertThrows(CertificateException::class.java) { trust.checkServerTrusted(arrayOf(media), "EC", socketFor("api.example.com")) }
        assertThrows(CertificateException::class.java) { trust.checkServerTrusted(arrayOf(media), "EC") }
    }

    @Test
    fun clientsThatSkipCertificateChecksStillCheckAMediaServerHost() {
        ServerTrust.update(emptyMap(), setOf("media.home"))
        assertFalse(ServerTrust.uncheckedClientVerifier.verify("MEDIA.home", session(media)))
        assertTrue(ServerTrust.uncheckedClientVerifier.verify("cdn.example.com", session(other)))

        ServerTrust.update(mapOf("media.home" to setOf(mediaFingerprint)), setOf("media.home"))
        assertTrue(ServerTrust.uncheckedClientVerifier.verify("MEDIA.home", session(media)))
        assertFalse(ServerTrust.uncheckedClientVerifier.verify("media.home", session(other)))
    }

    @Test
    fun aMediaServerHostNeedsATrustedCertificateThatNamesIt() {
        val matches = HostnameVerifier { _, _ -> true }
        val differs = HostnameVerifier { _, _ -> false }

        assertTrue(ServerTrust.verifiesServerHost("media.home", session(other), matches) { true })
        assertFalse(ServerTrust.verifiesServerHost("media.home", session(other), differs) { true })
        assertFalse(ServerTrust.verifiesServerHost("media.home", session(other), matches) { false })
        assertFalse(ServerTrust.verifiesServerHost("media.home", mockk { every { peerCertificates } returns arrayOf<java.security.cert.Certificate>() }, matches) { true })
    }

    @Test
    fun aForgottenCertificateStopsWorkingOnTheNextConnection() {
        ServerTrust.update(mapOf("media.home" to setOf(mediaFingerprint)), setOf("media.home"))
        val verifier = ServerTrust.hostnameVerifier(HostnameVerifier { _, _ -> true }) { false }
        assertTrue(verifier.verify("media.home", session(media)))

        ServerTrust.update(emptyMap(), setOf("media.home"))
        assertFalse(verifier.verify("media.home", session(media)))
        assertTrue(ServerTrust.hostnameVerifier(HostnameVerifier { _, _ -> true }) { true }.verify("media.home", session(media)))
        assertTrue(verifier.verify("cdn.example.com", session(other)))
    }

    @Test
    fun describesWhatIsWrongWithTheCertificate() {
        val now = media.notBefore.time + 1_000L
        val info = ServerCertificateProbe.describe("Media.Home", arrayOf(media), now)!!
        assertEquals(CertificateProblem.UNTRUSTED, info.problem)
        assertEquals("media.home", info.host)
        assertEquals(mediaFingerprint, info.fingerprint)
        assertTrue(info.subject.contains("CN=media.home"))

        val expired = ServerCertificateProbe.describe("media.home", arrayOf(media), media.notAfter.time + 1_000L)!!
        assertEquals(CertificateProblem.EXPIRED, expired.problem)
    }

    @Test
    fun forgettingOrReplacingACertificateClosesPooledConnections() {
        withPooledConnection { pool, open ->
            ServerTrust.update(mapOf("media.home" to setOf(mediaFingerprint)), setOf("media.home"))
            open()
            ServerTrust.update(mapOf("media.home" to setOf(mediaFingerprint)), setOf("media.home", "other.home"))
            assertEquals(1, pool.connectionCount())

            ServerTrust.update(emptyMap(), setOf("media.home", "other.home"))
            assertEquals(0, pool.connectionCount())

            ServerTrust.update(mapOf("media.home" to setOf(mediaFingerprint)), setOf("media.home", "other.home"))
            open()
            ServerTrust.update(mapOf("media.home" to setOf("ff91")), setOf("media.home", "other.home"))
            assertEquals(0, pool.connectionCount())
        }
    }

    @Test
    fun removingAServerClosesPooledConnections() {
        withPooledConnection { pool, open ->
            ServerTrust.update(emptyMap(), setOf("media.home", "other.home"))
            open()
            ServerTrust.update(emptyMap(), setOf("other.home"))
            assertEquals(0, pool.connectionCount())
        }
    }

    @Test
    fun aCertificateAcceptedForASignInThatFailedClosesItsConnections() {
        withPooledConnection { pool, open ->
            ServerTrust.allow("media.home", mediaFingerprint)
            ServerTrust.update(mapOf("media.home" to setOf(mediaFingerprint)), setOf("media.home"))
            open()
            ServerTrust.clearPending()
            assertEquals(1, pool.connectionCount())

            ServerTrust.allow("other.home", mediaFingerprint)
            ServerTrust.clearPending()
            assertEquals(0, pool.connectionCount())
        }
    }

    private fun withPooledConnection(block: (ConnectionPool, open: () -> Unit) -> Unit) {
        ServerTrust.closer = Executor { it.run() }
        val server = MockWebServer()
        repeat(4) { server.enqueue(MockResponse().setBody("ok")) }
        server.start()
        val pool = ConnectionPool()
        ServerTrust.closeConnectionsOnWithdrawal(pool)
        val client = OkHttpClient.Builder().connectionPool(pool).build()
        try {
            block(pool) {
                client.newCall(Request.Builder().url(server.url("/")).build()).execute().use { it.body?.string() }
                assertEquals(1, pool.connectionCount())
            }
        } finally {
            pool.evictAll()
            server.shutdown()
        }
    }

    private fun session(certificate: X509Certificate): SSLSession = mockk {
        every { peerCertificates } returns arrayOf(certificate)
    }

    private fun socketFor(host: String): SSLSocket {
        val handshake = mockk<SSLSession> { every { peerHost } returns host }
        return mockk { every { handshakeSession } returns handshake }
    }

    private fun certificate(pem: String): X509Certificate =
        CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(pem.trimIndent().toByteArray())) as X509Certificate
}
