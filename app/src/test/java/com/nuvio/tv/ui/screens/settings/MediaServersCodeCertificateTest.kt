package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.network.CertificateProblem
import com.nuvio.tv.core.network.ServerCertificateInfo
import com.nuvio.tv.core.network.ServerCertificateProbe
import com.nuvio.tv.core.network.ServerTrust
import com.nuvio.tv.data.mediaserver.FakeServerProvider
import com.nuvio.tv.data.mediaserver.MemoryServerPersistence
import com.nuvio.tv.data.mediaserver.ServerConnection
import com.nuvio.tv.data.mediaserver.ServerException
import com.nuvio.tv.data.mediaserver.ServerFailure
import com.nuvio.tv.data.mediaserver.ServerProvider
import com.nuvio.tv.data.mediaserver.ServerQuickConnect
import com.nuvio.tv.data.mediaserver.ServerRepository
import com.nuvio.tv.data.mediaserver.ServerSignIn
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class MediaServersCodeCertificateTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val address = "https://media.home:8920"
    private val media = CertificateFactory.getInstance("X.509").generateCertificate(
        ByteArrayInputStream(
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
            """.trimIndent().toByteArray()
        )
    ) as X509Certificate
    private val certificate = ServerCertificateInfo(
        host = "media.home",
        fingerprint = "d8054fa7d0c28d0c0d5c3728eebcb404e6c4f7a8fc67cdb1acf0201b3623d402",
        subject = "CN=media.home",
        issuer = "CN=media.home",
        validFromMs = 0L,
        validUntilMs = Long.MAX_VALUE,
        problem = CertificateProblem.UNTRUSTED
    )
    private val requestsWithoutTrust = mutableListOf<String>()
    private val provider = CodeProvider()
    private val repository = ServerRepository(MemoryServerPersistence(), listOf(provider), CoroutineScope(Dispatchers.Unconfined))
    private val viewModel = MediaServersViewModel(repository, mockk(relaxed = true))

    @Before
    fun probe() {
        ServerTrust.update(emptyMap(), emptySet())
        mockkObject(ServerCertificateProbe)
        coEvery { ServerCertificateProbe.probe(address) } returns certificate
    }

    @After
    fun reset() {
        unmockkObject(ServerCertificateProbe)
        ServerTrust.clearPending()
        ServerTrust.update(emptyMap(), emptySet())
    }

    @Test
    fun aCodeSignInShowsTheCertificateTheServerPresents() = runTest {
        viewModel.startQuickConnect(provider, address) {}
        advanceUntilIdle()

        assertEquals(certificate, viewModel.signIn.value.certificate)
        assertTrue(viewModel.signIn.value.certificateForCode)
        assertEquals(QuickConnectPhase.IDLE, viewModel.quickConnect.value.phase)
    }

    @Test
    fun trustingStartsTheCodeSignInAgainWithThatCertificate() = runTest {
        viewModel.startQuickConnect(provider, address) {}
        advanceUntilIdle()
        requestsWithoutTrust.clear()

        var connected: ServerConnection? = null
        viewModel.startQuickConnect(provider, address, viewModel.signIn.value.certificate) { connected = it }
        advanceUntilIdle()

        assertEquals(emptyList<String>(), requestsWithoutTrust)
        assertNull(viewModel.signIn.value.certificate)
        assertEquals(mapOf("media.home" to certificate.fingerprint), connected?.tlsPins)
        assertTrue(ServerTrust.isPinned("media.home", media))
    }

    private inner class CodeProvider(
        private val fake: FakeServerProvider = FakeServerProvider()
    ) : ServerProvider by fake {
        override val supportsQuickConnect: Boolean = true

        private fun requireTrust(step: String) {
            if (!ServerTrust.isPinned("media.home", media)) {
                requestsWithoutTrust += step
                throw ServerException(ServerFailure.CERTIFICATE)
            }
        }

        override suspend fun quickConnectStart(address: String): ServerQuickConnect {
            requireTrust("start")
            return ServerQuickConnect(
                address = address,
                serverName = "Home",
                serverId = "server-1",
                code = "123456",
                secret = "secret",
                expiresAtMs = System.currentTimeMillis() + 600_000L
            )
        }

        override suspend fun quickConnectApproved(ticket: ServerQuickConnect): Boolean {
            requireTrust("poll")
            return true
        }

        override suspend fun quickConnectSignIn(ticket: ServerQuickConnect): ServerSignIn {
            requireTrust("sign-in")
            return fake.quickConnectSignIn(ticket)
        }
    }
}
