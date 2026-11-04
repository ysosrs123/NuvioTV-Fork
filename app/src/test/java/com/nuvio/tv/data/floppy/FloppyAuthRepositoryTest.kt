package com.nuvio.tv.data.floppy

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FloppyAuthRepositoryTest {
    @Test
    fun `address is normalised to a server root`() {
        assertEquals("http://192.168.1.20:8000", normalizeFloppyServerUrl(" 192.168.1.20:8000/ "))
        assertEquals("https://floppy.example.com", normalizeFloppyServerUrl("https://floppy.example.com"))
        assertEquals("https://example.com/floppy", normalizeFloppyServerUrl("https://example.com/floppy/"))
        assertNull(normalizeFloppyServerUrl(""))
        assertNull(normalizeFloppyServerUrl("not a url"))
        assertNull(normalizeFloppyServerUrl("http://user:pass@host"))
        assertNull(normalizeFloppyServerUrl("http://host/?a=1"))
    }

    @Test
    fun `api paths keep a reverse proxy prefix`() {
        assertEquals("https://example.com/floppy/oauth/token", floppyUrl("https://example.com/floppy", "/oauth/token").toString())
        assertEquals("http://h:8000/oauth/token", floppyUrl("http://h:8000", "/oauth/token").toString())
    }

    @Test
    fun `code sign-in registers the app, polls and stores rotating credentials`() = runTest {
        val h = FloppyTestHarness()
        h.floppyServer()
        h.engine.onceFirst(FloppyHttpMethod.POST, "/oauth/token", 400, """{"error":"authorization_pending"}""")

        val session = h.auth.startDeviceAuthorization("192.168.1.20:8000")

        assertEquals("K7QM-4XTD", session.userCode)
        assertEquals(FloppyTestHarness.SERVER, session.serverUrl)
        assertEquals("flp_oauth_1", session.clientId)
        assertEquals(mapOf("client_name" to "Nuvio Arc on Test"), h.engine.requests.first().form)
        assertEquals(FloppyDevicePollResult.Pending, h.auth.pollDeviceAuthorization())
        assertEquals(listOf("/oauth/register", "/oauth/device/authorization"), h.engine.paths())

        h.now += 5_000L
        assertEquals(FloppyDevicePollResult.Pending, h.auth.pollDeviceAuthorization())
        h.now += 5_000L
        assertEquals(FloppyDevicePollResult.Authorized, h.auth.pollDeviceAuthorization())

        val state = h.store.state.value
        assertTrue(state.isAuthenticated)
        assertNull(state.session)
        assertEquals(FloppyTestHarness.SERVER, state.serverUrl)
        val credentials = h.store.authorization()!!.credentials
        assertEquals("flp_refresh_1", credentials.refreshToken)
        assertEquals(h.now + 3_600_000L, credentials.expiresAtEpochMs)
        assertFalse(h.persistence.values.values.single().isBlank())
    }

    @Test
    fun `unknown app registration is made again once`() = runTest {
        val h = FloppyTestHarness()
        h.floppyServer()
        h.engine.onceFirst(FloppyHttpMethod.POST, "/oauth/device/authorization", 400, """{"error":"invalid_client"}""")

        h.auth.startDeviceAuthorization(FloppyTestHarness.SERVER)

        assertEquals(
            listOf("/oauth/register", "/oauth/device/authorization", "/oauth/register", "/oauth/device/authorization"),
            h.engine.paths()
        )
    }

    @Test
    fun `an older Floppy without code sign-in is told apart from something else`() = runTest {
        val old = FloppyTestHarness()
        old.engine.on(FloppyHttpMethod.POST, "/oauth/register", 404)
        old.engine.on(FloppyHttpMethod.GET, FloppyAuthRepository.CONNECTION_CHECK_PATH, 403)
        assertEquals(FloppyAuthError.NO_CODE_SIGN_IN, authError { old.auth.startDeviceAuthorization(FloppyTestHarness.SERVER) })

        val other = FloppyTestHarness()
        other.engine.on(FloppyHttpMethod.POST, "/oauth/register", 404)
        other.engine.on(FloppyHttpMethod.GET, FloppyAuthRepository.CONNECTION_CHECK_PATH, 404)
        assertEquals(FloppyAuthError.NOT_FLOPPY, authError { other.auth.startDeviceAuthorization(FloppyTestHarness.SERVER) })

        val nothing = FloppyTestHarness()
        assertEquals(FloppyAuthError.UNREACHABLE, authError { nothing.auth.startDeviceAuthorization(FloppyTestHarness.SERVER) })
        assertEquals(FloppyAuthError.INVALID_ADDRESS, authError { nothing.auth.startDeviceAuthorization("bad address") })
    }

    @Test
    fun `denied and expired codes end the session`() = runTest {
        val h = FloppyTestHarness()
        h.floppyServer()
        h.engine.onceFirst(FloppyHttpMethod.POST, "/oauth/token", 400, """{"error":"access_denied"}""")
        h.auth.startDeviceAuthorization(FloppyTestHarness.SERVER)
        h.now += 5_000L
        assertEquals(FloppyDevicePollResult.Denied, h.auth.pollDeviceAuthorization())
        assertNull(h.store.state.value.session)
        assertEquals(FloppyAuthError.ACCESS_DENIED, h.store.state.value.error)

        h.auth.startDeviceAuthorization(FloppyTestHarness.SERVER)
        h.now += 601_000L
        assertEquals(FloppyDevicePollResult.Expired, h.auth.pollDeviceAuthorization())
        assertFalse(h.store.state.value.isAuthenticated)
    }

    @Test
    fun `slow down stretches the poll interval`() = runTest {
        val h = FloppyTestHarness()
        h.floppyServer()
        h.engine.onceFirst(FloppyHttpMethod.POST, "/oauth/token", 400, """{"error":"slow_down"}""")
        h.auth.startDeviceAuthorization(FloppyTestHarness.SERVER)
        h.now += 5_000L
        assertEquals(FloppyDevicePollResult.Pending, h.auth.pollDeviceAuthorization())
        assertEquals(10, h.store.state.value.session!!.intervalSeconds)
    }

    @Test
    fun `app token is checked against the server before it is kept`() = runTest {
        val h = FloppyTestHarness()
        h.engine.on(FloppyHttpMethod.GET, FloppyAuthRepository.CONNECTION_CHECK_PATH, 403)
        assertEquals(FloppyAuthError.TOKEN_REJECTED, authError { h.auth.connectWithToken(FloppyTestHarness.SERVER, "flp_bad") })
        assertFalse(h.store.state.value.isAuthenticated)

        val ok = FloppyTestHarness()
        ok.floppyServer()
        ok.auth.connectWithToken("192.168.1.20:8000", "  flp_good  ")
        val credentials = ok.store.authorization()!!.credentials
        assertEquals("flp_good", credentials.accessToken)
        assertFalse(credentials.refreshes)
        assertEquals("flp_good", ok.engine.requests.single().accessToken)

        val html = FloppyTestHarness()
        html.engine.on(FloppyHttpMethod.GET, FloppyAuthRepository.CONNECTION_CHECK_PATH, 200, "<html></html>")
        assertEquals(FloppyAuthError.NOT_FLOPPY, authError { html.auth.connectWithToken(FloppyTestHarness.SERVER, "flp_x") })
    }

    @Test
    fun `refresh replaces both tokens and an app token is never refreshed`() = runTest {
        val h = signedIn()
        h.engine.onceFirst(
            FloppyHttpMethod.POST, "/oauth/token", 200,
            """{"access_token":"flp_access_2","token_type":"Bearer","expires_in":3600,"refresh_token":"flp_refresh_2"}"""
        )
        assertEquals("flp_access_1", h.auth.authorization().credentials.accessToken)

        h.now += 3_550_000L
        val refreshed = h.auth.authorization().credentials
        assertEquals("flp_access_2", refreshed.accessToken)
        assertEquals("flp_refresh_2", refreshed.refreshToken)
        val form = h.engine.requests.last().form!!
        assertEquals("refresh_token", form["grant_type"])
        assertEquals("flp_refresh_1", form["refresh_token"])
        assertEquals("flp_refresh_2", h.store.authorization()!!.credentials.refreshToken)

        val manual = FloppyTestHarness()
        manual.floppyServer()
        manual.auth.connectWithToken(FloppyTestHarness.SERVER, "flp_good")
        manual.now += 100_000_000L
        assertEquals("flp_good", manual.auth.authorization().credentials.accessToken)
        assertEquals(1, manual.engine.requests.size)
    }

    @Test
    fun `a refresh without a new refresh token or lifetime keeps the old token and an hour`() = runTest {
        val h = signedIn()
        h.engine.onceFirst(FloppyHttpMethod.POST, "/oauth/token", 200, """{"access_token":"flp_access_2","token_type":"Bearer"}""")
        h.now += 3_550_000L
        val refreshed = h.auth.authorization().credentials
        assertEquals("flp_access_2", refreshed.accessToken)
        assertEquals("flp_refresh_1", refreshed.refreshToken)
        assertEquals(h.now + 3_600_000L, refreshed.expiresAtEpochMs)
    }

    @Test
    fun `a refused refresh signs out`() = runTest {
        val h = signedIn()
        h.engine.onceFirst(FloppyHttpMethod.POST, "/oauth/token", 400, """{"error":"invalid_grant"}""")
        h.now += 3_600_000L
        assertEquals(FloppyAuthError.AUTHORIZATION_REVOKED, authError { h.auth.authorization() })
        assertFalse(h.store.state.value.isAuthenticated)
        assertEquals(FloppyTestHarness.SERVER, h.store.state.value.lastServerUrl)
    }

    @Test
    fun `disconnect forgets the account and revokes the refresh token`() = runTest {
        val h = signedIn()
        assertTrue(h.auth.disconnect())
        assertFalse(h.store.state.value.isAuthenticated)
        val revoke = h.engine.requests.last()
        assertEquals("/oauth/revoke", revoke.path)
        assertEquals(mapOf("client_id" to "flp_oauth_1", "token" to "flp_refresh_1"), revoke.form)
    }

    @Test
    fun `profiles keep separate Floppy accounts`() = runTest {
        val h = signedIn()
        h.store.selectProfile(2)
        assertFalse(h.store.state.value.isAuthenticated)
        h.store.selectProfile(1)
        assertTrue(h.store.state.value.isAuthenticated)
        h.store.removeProfile(1)
        assertFalse(h.store.state.value.isAuthenticated)
    }

    private suspend fun signedIn(): FloppyTestHarness {
        val h = FloppyTestHarness()
        h.floppyServer()
        h.auth.startDeviceAuthorization(FloppyTestHarness.SERVER)
        h.now += 5_000L
        assertEquals(FloppyDevicePollResult.Authorized, h.auth.pollDeviceAuthorization())
        h.engine.requests.clear()
        return h
    }

    private suspend fun authError(block: suspend () -> Unit): FloppyAuthError {
        try {
            block()
        } catch (error: FloppyAuthException) {
            return error.error
        }
        fail("expected a Floppy sign-in error")
        throw AssertionError()
    }
}
