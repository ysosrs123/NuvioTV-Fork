package com.nuvio.tv.data.mediaserver.mediabrowser

import com.nuvio.tv.data.mediaserver.ServerException
import com.nuvio.tv.data.mediaserver.ServerFailure
import com.nuvio.tv.data.mediaserver.ServerQuickConnect
import com.nuvio.tv.data.mediaserver.emby.EmbyProvider
import com.nuvio.tv.data.mediaserver.jellyfin.JellyfinProvider
import com.nuvio.tv.data.mediaserver.silo.SiloProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickConnectTest {
    private val publicInfo = """{"ServerName": "HomeServer", "Version": "12.1.0", "ProductName": "Jellyfin Server", "Id": "srv1"}"""

    private fun ticket() = ServerQuickConnect(
        address = "http://192.168.1.10:8096",
        serverName = "HomeServer",
        serverId = "srv1",
        code = "681687",
        secret = "s3cr3t",
        expiresAtMs = Long.MAX_VALUE
    )

    @Test
    fun startsWithTheDeviceHeaderAndNoToken() = runTest {
        val http = TestHttp { request ->
            when (request.url.encodedPath) {
                "/System/Info/Public" -> publicInfo
                "/QuickConnect/Enabled" -> "true"
                "/QuickConnect/Initiate" -> """{"Authenticated": false, "Secret": "s3cr3t", "Code": "681687"}"""
                else -> error("Unexpected ${request.url}")
            }
        }
        val ticket = JellyfinProvider(http.client, testIdentity).quickConnectStart("192.168.1.10:8096")

        assertNotNull(ticket)
        assertEquals("681687", ticket!!.code)
        assertEquals("http://192.168.1.10:8096", ticket.address)
        assertEquals("HomeServer", ticket.serverName)
        assertTrue(ticket.expiresAtMs > System.currentTimeMillis())
        val initiate = http.requests.last()
        assertEquals("POST", initiate.method)
        val header = initiate.header("Authorization").orEmpty()
        assertTrue(header.contains("Client=\"Nuvio\"") && header.contains("DeviceId=\"device-1\"") && header.contains("Version="))
        assertFalse(header.contains("Token="))
        assertFalse(ticket.toString().contains("s3cr3t"))
    }

    @Test
    fun aServerWithQuickConnectOffIsNotAskedForACode() = runTest {
        val http = TestHttp { request ->
            if (request.url.encodedPath == "/QuickConnect/Enabled") "false" else publicInfo
        }
        assertNull(JellyfinProvider(http.client, testIdentity).quickConnectStart("192.168.1.10:8096"))
        assertTrue(http.requests.none { it.url.encodedPath == "/QuickConnect/Initiate" })
    }

    @Test
    fun aRefusedInitiateMeansSwitchedOff() = runTest {
        val http = TestHttp(status = { if (it.url.encodedPath == "/QuickConnect/Initiate") 401 else 200 }) { request ->
            if (request.url.encodedPath == "/QuickConnect/Enabled") "true" else publicInfo
        }
        assertNull(JellyfinProvider(http.client, testIdentity).quickConnectStart("192.168.1.10:8096"))
    }

    @Test
    fun pollsUntilApprovedAndTreatsAnUnknownSecretAsExpired() = runTest {
        var approved = false
        val http = TestHttp { """{"Authenticated": $approved}""" }
        val jellyfin = JellyfinProvider(http.client, testIdentity)

        assertFalse(jellyfin.quickConnectApproved(ticket()))
        approved = true
        assertTrue(jellyfin.quickConnectApproved(ticket()))
        assertEquals("s3cr3t", http.requests.last().url.queryParameter("secret"))

        val expired = TestHttp(status = { 404 }) { "\"Unknown secret\"" }
        val error = runCatching { JellyfinProvider(expired.client, testIdentity).quickConnectApproved(ticket()) }
            .exceptionOrNull() as ServerException
        assertEquals(ServerFailure.NOT_FOUND, error.failure)
    }

    @Test
    fun finishesTheSignInWithTheSecret() = runTest {
        val http = TestHttp { """{"User": {"Id": "u1", "Name": "paul"}, "AccessToken": "tok", "ServerId": "srv1"}""" }
        val signIn = JellyfinProvider(http.client, testIdentity).quickConnectSignIn(ticket())

        assertEquals("paul", signIn.userName)
        assertEquals("u1", signIn.userId)
        assertEquals("srv1", signIn.serverId)
        assertEquals("HomeServer", signIn.serverName)
        assertEquals("tok", signIn.token)
        val request = http.requests.single()
        assertEquals("/Users/AuthenticateWithQuickConnect", request.url.encodedPath)
        assertEquals("""{"Secret":"s3cr3t"}""", request.text)
    }

    @Test
    fun anUnapprovedOrLateFinishCountsAsExpired() = runTest {
        listOf(401, 404).forEach { status ->
            val http = TestHttp(status = { status }) { "" }
            val error = runCatching { JellyfinProvider(http.client, testIdentity).quickConnectSignIn(ticket()) }
                .exceptionOrNull() as ServerException
            assertEquals(ServerFailure.NOT_FOUND, error.failure)
        }
    }

    @Test
    fun onlyJellyfinOffersACode() {
        assertTrue(JellyfinProvider(TestHttp().client, testIdentity).supportsQuickConnect)
        assertFalse(EmbyProvider(TestHttp().client, testIdentity).supportsQuickConnect)
        assertFalse(SiloProvider(TestHttp().client, testIdentity).supportsQuickConnect)
    }
}
