package com.nuvio.tv.data.wetrakr

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeTrakrAuthRepositoryTest {
    private val deviceCode = """{"device_code":"dev-1","user_code":"K7Q2MX","verification_url":"https://wetrakr.com/activate","expires_in":600,"interval":5}"""
    private val tokens = """{"access_token":"a1","refresh_token":"r1","token_type":"Bearer","expires_in":604800}"""

    @Test
    fun `the device flow shows the code and signs in once approved`() = runTest {
        val harness = WeTrakrHarness()
        harness.engine.on(WeTrakrHttpMethod.POST, "/oauth/device/code", ok(deviceCode))
        harness.engine.on(
            WeTrakrHttpMethod.POST, "/oauth/device/token",
            ok("""{"error":"authorization_pending"}""", status = 400), ok(tokens)
        )
        val session = harness.auth.startDeviceAuthorization()
        assertEquals("K7Q2MX", session.userCode)
        assertEquals("https://wetrakr.com/activate?code=K7Q2MX", session.verificationUriComplete)
        val body = Json.parseToJsonElement(harness.engine.calls("/oauth/device/code").single().body!!).jsonObject
        assertEquals("client-key", body["client_id"]!!.jsonPrimitive.content)

        harness.now += 5_000
        assertEquals(WeTrakrDevicePollResult.Pending, harness.auth.pollDeviceAuthorization())
        harness.now += 5_000
        assertEquals(WeTrakrDevicePollResult.Authorized, harness.auth.pollDeviceAuthorization())
        assertTrue(harness.store.state.value.isAuthenticated)
        assertNull(harness.store.state.value.session)
        val poll = Json.parseToJsonElement(harness.engine.calls("/oauth/device/token").last().body!!).jsonObject
        assertEquals("dev-1", poll["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `polling waits for the interval and slows down when asked`() = runTest {
        val harness = WeTrakrHarness()
        harness.engine.on(WeTrakrHttpMethod.POST, "/oauth/device/code", ok(deviceCode))
        harness.engine.on(WeTrakrHttpMethod.POST, "/oauth/device/token", ok("", status = 429))
        harness.auth.startDeviceAuthorization()
        assertEquals(WeTrakrDevicePollResult.Pending, harness.auth.pollDeviceAuthorization())
        assertTrue(harness.engine.calls("/oauth/device/token").isEmpty())
        harness.now += 5_000
        harness.auth.pollDeviceAuthorization()
        assertEquals(10, harness.store.state.value.session?.intervalSeconds)
    }

    @Test
    fun `a refused or expired code ends the sign-in`() = runTest {
        for ((status, result, error) in listOf(
            Triple(418, WeTrakrDevicePollResult.Denied, WeTrakrAuthError.ACCESS_DENIED),
            Triple(410, WeTrakrDevicePollResult.Expired, WeTrakrAuthError.CODE_EXPIRED)
        )) {
            val harness = WeTrakrHarness()
            harness.engine.on(WeTrakrHttpMethod.POST, "/oauth/device/code", ok(deviceCode))
            harness.engine.on(WeTrakrHttpMethod.POST, "/oauth/device/token", ok("", status = status))
            harness.auth.startDeviceAuthorization()
            harness.now += 5_000
            assertEquals(result, harness.auth.pollDeviceAuthorization())
            assertNull(harness.store.state.value.session)
            assertEquals(error, harness.store.state.value.error)
            assertFalse(harness.store.state.value.isAuthenticated)
        }
    }

    @Test
    fun `an app key WeTrakr does not know is reported as such`() = runTest {
        val harness = WeTrakrHarness()
        harness.engine.on(WeTrakrHttpMethod.POST, "/oauth/device/code", ok("{}", status = 401))
        val error = runCatching { harness.auth.startDeviceAuthorization() }.exceptionOrNull() as WeTrakrAuthException
        assertEquals(WeTrakrAuthError.INVALID_CLIENT, error.error)
    }

    @Test
    fun `a rejected token is refreshed once and the rotated refresh token is kept`() = runTest {
        val harness = WeTrakrHarness()
        harness.signIn()
        harness.engine.on(WeTrakrHttpMethod.GET, "/sync/last_activities", ok("", status = 401), ok("{}"))
        harness.engine.on(
            WeTrakrHttpMethod.POST, "/oauth/token/refresh",
            ok("""{"access_token":"a2","refresh_token":"r2","new_refresh_token":"r2","expires_in":604800}""")
        )
        assertEquals(200, harness.api.get("/sync/last_activities").status)
        assertEquals("a2", harness.store.authorization()!!.tokens.accessToken)
        assertEquals("r2", harness.store.authorization()!!.tokens.refreshToken)
        assertEquals("a2", harness.engine.calls("/sync/last_activities").last().accessToken)
    }

    @Test
    fun `a refresh token WeTrakr no longer accepts signs the profile out`() = runTest {
        val harness = WeTrakrHarness()
        harness.signIn()
        harness.now += 8L * 24 * 60 * 60 * 1_000
        harness.engine.on(WeTrakrHttpMethod.POST, "/oauth/token/refresh", ok("""{"message":"revoked"}""", status = 401))
        val error = runCatching { harness.api.get("/sync/last_activities") }.exceptionOrNull() as WeTrakrAuthException
        assertEquals(WeTrakrAuthError.AUTHORIZATION_REVOKED, error.error)
        assertFalse(harness.store.state.value.isAuthenticated)
    }

    @Test
    fun `disconnecting forgets the tokens here and revokes them on WeTrakr`() = runTest {
        val harness = WeTrakrHarness()
        harness.signIn()
        harness.engine.on(WeTrakrHttpMethod.POST, "/oauth/logout", ok("""{"revoked":true}"""))
        assertTrue(harness.auth.disconnect())
        assertFalse(harness.store.state.value.isAuthenticated)
        val call = harness.engine.calls("/oauth/logout").single()
        assertEquals("access", call.accessToken)
        assertEquals("refresh", Json.parseToJsonElement(call.body!!).jsonObject["refresh_token"]!!.jsonPrimitive.content)
        assertFalse(harness.persistence.values[1].orEmpty().contains("\"access\""))
    }

    @Test
    fun `each profile keeps its own sign-in`() {
        val harness = WeTrakrHarness()
        harness.signIn()
        harness.store.selectProfile(2)
        assertFalse(harness.store.state.value.isAuthenticated)
        harness.store.selectProfile(1)
        assertTrue(harness.store.state.value.isAuthenticated)
        harness.store.removeProfile(1)
        assertFalse(harness.store.state.value.isAuthenticated)
    }
}
