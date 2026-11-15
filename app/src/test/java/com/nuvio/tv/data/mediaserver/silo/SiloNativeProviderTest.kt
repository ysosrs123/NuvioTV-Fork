package com.nuvio.tv.data.mediaserver.silo

import com.nuvio.tv.data.mediaserver.ServerConnection
import com.nuvio.tv.data.mediaserver.ServerException
import com.nuvio.tv.data.mediaserver.ServerFailure
import com.nuvio.tv.data.mediaserver.ServerMediaKind
import com.nuvio.tv.data.mediaserver.ServerSession
import com.nuvio.tv.data.mediaserver.ServerSignInIssue
import com.nuvio.tv.data.mediaserver.mediabrowser.TestHttp
import com.nuvio.tv.data.mediaserver.mediabrowser.testIdentity
import com.nuvio.tv.data.mediaserver.mediabrowser.text
import kotlinx.coroutines.test.runTest
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SiloNativeProviderTest {
    private var clock = 1_000_000L
    private val base = "http://silo.local:8090"

    private fun problem(code: String, status: Int, detail: String = "") =
        """{"type":"https://siloserver.org/docs/api/v2/problems/$code","title":"x","status":$status,"detail":"$detail","instance":"urn:silo:request:1"}"""

    private val tokenPair =
        """{"access_token":"acc","refresh_token":"ref","expires_in":28800,
           "user":{"id":"1","username":"laura","email":"","role":"user","permissions":[],"download_allowed":true,"password_change_required":false}}"""

    private class Reply(val status: Int, val body: String, val location: String? = null)

    private fun silo(routes: (Request) -> Reply?): Pair<SiloNativeProvider, TestHttp> {
        val defaults: (Request) -> Reply = { request ->
            when (request.url.encodedPath) {
                "/api/v2/system/info" -> Reply(200, """{"server_version":"x","api_major":2,"contract_digest":"d","links":{}}""")
                "/api/v2/system/identity" -> Reply(200, """{"server_id":"srv-1"}""")
                "/api/v2/auth/providers" -> Reply(200, """{"items":[{"id":"local","display_name":"Local","mode":"credentials","default":true}],"password_login":true}""")
                "/api/v2/theme/branding" -> Reply(200, """{"server_name":"Silo Home","login_subtitle":"","storage_available":true}""")
                "/api/v2/auth/device/capability" -> Reply(200, """{"revision":"r","state":"available","protocol_versions":[2],"cancel":true,"opened_signal":true,"remote_playback_handoff":false}""")
                "/api/v2/auth/logout" -> Reply(204, "")
                else -> Reply(404, problem("not_found", 404))
            }
        }
        val reply = { request: Request -> routes(request) ?: defaults(request) }
        val http = TestHttp(
            status = { reply(it).status },
            location = { reply(it).location },
            responder = { reply(it).body }
        )
        return SiloNativeProvider(http.client, testIdentity, now = { clock }) to http
    }

    private fun session(token: String, address: String = base) = ServerSession(
        ServerConnection(
            id = "c1",
            providerId = SiloNativeProvider.ID,
            name = "Silo Home",
            address = address,
            remoteServerId = "srv-1",
            remoteUserId = "1",
            userName = "laura",
            credentialRef = "k1"
        ),
        token,
        address
    )

    private fun credential(expiresAtMs: Long = clock + 3_600_000L, profileToken: String? = null) =
        """{"access":"acc","refresh":"ref","expires_at_ms":$expiresAtMs,"profile_id":"p2","profile_name":"Kids"${
            profileToken?.let { ",\"profile_token\":\"$it\"" }.orEmpty()
        }}"""

    @Test
    fun findingTheServerReadsItsIdentityNameAndSignInWays() = runTest {
        val (provider, http) = silo { null }

        val server = provider.findServer("silo.local:8090/")

        assertEquals("http://silo.local:8090", server.address)
        assertEquals("srv-1", server.serverId)
        assertEquals("Silo Home", server.name)
        assertTrue(server.passwordSignIn)
        assertTrue(server.codeSignIn)
        assertNull(server.network)
        assertTrue(http.requests.all { it.header("Authorization") == null })
        assertTrue(http.requests.all { it.header("User-Agent") == "Nuvio on Test TV" })
    }

    @Test
    fun anOlderSiloWithoutAnIdentityUsesItsJellyfinId() = runTest {
        val (provider, _) = silo { request ->
            when (request.url.encodedPath) {
                "/api/v2/system/identity" -> Reply(404, problem("not_found", 404))
                "/System/Info/Public" -> Reply(200, """{"ServerName":"Vault","ProductName":"Jellyfin Server","Id":"jf-7"}""")
                else -> null
            }
        }

        val server = provider.findServer("https://vault.example")

        assertEquals("jf-7", server.serverId)
        assertEquals("https://vault.example", server.address)
        assertEquals("jf-7", provider.locate("https://vault.example")?.serverId)
    }

    @Test
    fun anOlderSiloWithNeitherIdIsKnownByItsAddress() = runTest {
        val (provider, _) = silo { request ->
            if (request.url.encodedPath == "/api/v2/system/identity") Reply(404, problem("not_found", 404)) else null
        }

        val server = provider.findServer(base)

        assertEquals(base, server.serverId)
        assertTrue(server.codeSignIn)
    }

    @Test
    fun tailscaleSignInIsOfferedWithTheNameSiloKnows() = runTest {
        val (provider, _) = silo { request ->
            if (request.url.encodedPath != "/api/v2/auth/providers") return@silo null
            Reply(200, """{"items":[{"id":"plugin-5","display_name":"Tailscale","mode":"network","default":false,
                "installation_id":"5","network_sign_in_path":"/api/v2/auth/network/5/sign-in",
                "network_identity":{"display_name":"Paul","username":"paul@example"}}],"password_login":false}""")
        }

        val server = provider.findServer(base)

        assertEquals("Paul", server.network?.name)
        assertFalse(server.passwordSignIn)
    }

    @Test
    fun anAddressThatIsNotSilosOwnApiSaysSo() = runTest {
        val (provider, _) = silo { request ->
            if (request.url.encodedPath == "/api/v2/system/info") Reply(200, "<!doctype html><html></html>") else null
        }

        try {
            provider.findServer("http://silo.local:8097")
            fail("expected a refusal")
        } catch (error: ServerException) {
            assertEquals(ServerSignInIssue.SILO_NOT_NATIVE, error.issue)
        }
    }

    @Test
    fun aMoveToHttpsOnTheSameHostIsFollowedButNeverBackToHttp() = runTest {
        val (provider, _) = silo { request ->
            if (request.url.encodedPath == "/api/v2/system/info" && !request.url.isHttps) {
                Reply(308, "", location = "https://silo.local/api/v2/system/info")
            } else {
                null
            }
        }

        assertEquals("https://silo.local", provider.findServer("http://silo.local").address)

        val (downgrading, _) = silo { request ->
            if (request.url.encodedPath == "/api/v2/system/info" && request.url.isHttps) {
                Reply(308, "", location = "http://silo.local/api/v2/system/info")
            } else {
                null
            }
        }
        try {
            downgrading.findServer("https://silo.local")
            fail("expected a refusal")
        } catch (error: ServerException) {
            assertEquals(ServerSignInIssue.SILO_NOT_NATIVE, error.issue)
        }
    }

    @Test
    fun aCodeIsShownInTwoGroupsAndItsEndMovesWithTheServer() = runTest {
        val (provider, http) = silo { request ->
            when (request.url.encodedPath) {
                "/api/v2/auth/device/start" -> Reply(201, """{"device_code":"dev-1","user_code":"4821-7730","match_code":"warm pony",
                    "verification_uri":"https://silo.example/activate","verification_uri_complete":"https://silo.example/activate?code=48217730",
                    "expires_at":"2026-01-02T03:15:00.000Z","expires_in":900,"interval":3,"device_name":"x","device_platform":"android-tv",
                    "client_purpose":"device_login","temporary":false}""")
                "/api/v2/auth/device/poll" -> Reply(200, """{"status":"pending","poll_after":3,"opened":true,
                    "expires_at":"2026-01-02T03:20:00.000Z","profile_id":"","profile_token":"","temporary":false}""")
                else -> null
            }
        }
        val server = provider.findServer(base)

        val code = provider.startCode(server)
        val status = provider.pollCode(server, code)

        assertEquals("4821 7730", code.shownCode)
        assertEquals("https://silo.example/activate?code=48217730", code.linkWithCode)
        assertEquals(clock + 900_000L, code.expiresAtMs)
        assertEquals(SiloCodeStatus.Waiting(opened = true, expiresAtMs = clock + 1_200_000L, pollAfterMs = 3_000L), status)
        val start = http.requests.first { it.url.encodedPath == "/api/v2/auth/device/start" }
        assertEquals("""{"device_name":"Nuvio on Test TV","device_platform":"android-tv"}""", start.text)
        assertNull(start.header("Authorization"))
    }

    @Test
    fun anApprovedCodeHandsOverTheTokensAndAnUnknownOneHasExpired() = runTest {
        var approved = true
        val (provider, _) = silo { request ->
            when (request.url.encodedPath) {
                "/api/v2/auth/device/poll" -> if (approved) {
                    Reply(200, """{"status":"approved","poll_after":3,"opened":false,"tokens":$tokenPair,"profile_id":"","profile_token":"","temporary":false}""")
                } else {
                    Reply(404, problem("not_found", 404))
                }
                else -> null
            }
        }
        val server = provider.findServer(base)
        val code = SiloCode("dev-1", "48217730", "l", "l", clock + 900_000L, null, 3_000L)

        val status = provider.pollCode(server, code) as SiloCodeStatus.Approved
        approved = false

        assertEquals("laura", status.tokens.userName)
        assertEquals(SiloCodeStatus.Ended(SiloCodeEnd.EXPIRED), provider.pollCode(server, code))
    }

    @Test
    fun passwordSignInSendsOnlyTheNameAndPassword() = runTest {
        val (provider, http) = silo { request ->
            if (request.url.encodedPath == "/api/v2/auth/login") Reply(200, tokenPair) else null
        }
        val server = provider.findServer(base)

        val tokens = provider.passwordSignIn(server, " laura ", "pw")

        assertEquals("1", tokens.userId)
        assertEquals(clock + 28_800_000L, tokens.accessExpiresAtMs)
        assertEquals("""{"username":"laura","password":"pw"}""", http.requests.last().text)
    }

    @Test
    fun passwordSignInRefusalsGetTheirOwnMessages() = runTest {
        val cases = mapOf(
            Reply(401, problem("invalid_token", 401)) to (ServerFailure.AUTH_REQUIRED to null),
            Reply(403, problem("local_login_disabled", 403)) to (ServerFailure.FORBIDDEN to ServerSignInIssue.PASSWORD_SIGN_IN_OFF),
            Reply(429, problem("rate_limited", 429)) to (ServerFailure.FAILED to ServerSignInIssue.TOO_MANY_TRIES),
            Reply(403, problem("not_permitted", 403, "Your directory refused the account.")) to
                (ServerFailure.FORBIDDEN to ServerSignInIssue.SERVER_MESSAGE)
        )
        cases.forEach { (reply, expected) ->
            val (provider, _) = silo { request -> if (request.url.encodedPath == "/api/v2/auth/login") reply else null }
            val server = provider.findServer(base)
            try {
                provider.passwordSignIn(server, "laura", "pw")
                fail("expected a refusal")
            } catch (error: ServerException) {
                assertEquals(expected.first, error.failure)
                assertEquals(expected.second, error.issue)
            }
        }
    }

    @Test
    fun aTemporaryPasswordEndsTheSignInAndSaysToChangeItFirst() = runTest {
        val temporary = tokenPair.replace("\"password_change_required\":false", "\"password_change_required\":true")
        val (provider, http) = silo { request ->
            if (request.url.encodedPath == "/api/v2/auth/login") Reply(200, temporary) else null
        }
        val server = provider.findServer(base)

        try {
            provider.passwordSignIn(server, "laura", "pw")
            fail("expected a refusal")
        } catch (error: ServerException) {
            assertEquals(ServerSignInIssue.TEMPORARY_PASSWORD, error.issue)
        }
        val logout = http.requests.last()
        assertEquals("/api/v2/auth/logout", logout.url.encodedPath)
        assertEquals("Bearer acc", logout.header("Authorization"))
    }

    @Test
    fun tailscaleSignInPostsAnEmptyObjectToThePathSiloGave() = runTest {
        val (provider, http) = silo { request ->
            when (request.url.encodedPath) {
                "/api/v2/auth/providers" -> Reply(200, """{"items":[{"id":"plugin-5","display_name":"Tailscale","mode":"network",
                    "default":false,"network_sign_in_path":"/api/v2/auth/network/5/sign-in","network_identity":{"display_name":"","username":"paul"}}],
                    "password_login":true}""")
                "/api/v2/auth/network/5/sign-in" -> Reply(200, tokenPair)
                else -> null
            }
        }
        val server = provider.findServer(base)

        provider.networkSignIn(server)

        val signIn = http.requests.last()
        assertEquals("{}", signIn.text)
        assertEquals("POST", signIn.method)
        assertEquals("paul", server.network?.name)
    }

    @Test
    fun profilesAndPinsGoWithTheAccountTokenOnly() = runTest {
        val (provider, http) = silo { request ->
            when (request.url.encodedPath) {
                "/api/v2/profiles" -> Reply(200, """{"items":[
                    {"id":"p1","name":"Laura","has_pin":false,"is_child":false,"is_primary":true},
                    {"id":"p2","name":"Kids","has_pin":true,"is_child":true,"is_primary":false}],
                    "avatar_upload_enabled":false,"max_advisory_age_supported":true,"require_advisory_age_supported":true}""")
                "/api/v2/profiles/p2/verify-pin" -> if ("1234" in request.text) {
                    Reply(200, """{"valid":true,"profile_token":"pvt_1","expires_at":null}""")
                } else {
                    Reply(200, """{"valid":false,"expires_at":null}""")
                }
                else -> null
            }
        }
        val server = provider.findServer(base)
        val tokens = SiloTokens("acc", "ref", clock + 1_000L, "1", "laura", false)

        val profiles = provider.profiles(server, tokens)
        val kids = profiles.last()

        assertEquals(listOf("Laura", "Kids"), profiles.map { it.name })
        assertTrue(kids.hasPin && kids.isChild)
        assertNull(provider.verifyPin(server, tokens, kids, "0000"))
        assertEquals("pvt_1", provider.verifyPin(server, tokens, kids, "1234"))
        http.requests.filter { it.url.encodedPath.startsWith("/api/v2/profiles") }.forEach {
            assertEquals("Bearer acc", it.header("Authorization"))
            assertNull(it.header("X-Profile-Id"))
        }
    }

    @Test
    fun theSavedSignInCarriesTheProfileForEveryCall() = runTest {
        val (provider, http) = silo { request ->
            if (request.url.encodedPath == "/api/v2/user/libraries") {
                Reply(200, """{"items":[{"id":"3","name":"Shows","type":"series","sort_order":2},
                    {"id":"1","name":"Films","type":"movies","sort_order":1},
                    {"id":"9","name":"Audiobooks","type":"audiobooks","sort_order":3}]}""")
            } else {
                null
            }
        }
        val server = provider.findServer(base)
        val tokens = SiloTokens("acc", "ref", clock + 1_000L, "1", "laura", false)
        val kids = SiloProfile("p2", "Kids", hasPin = true, isChild = true, isPrimary = false)

        val signIn = provider.signIn(server, tokens, kids, "pvt_1")
        val libraries = provider.libraries(session(signIn.token))

        assertEquals("srv-1", signIn.serverId)
        assertEquals("Silo Home", signIn.serverName)
        assertEquals(listOf("Films" to ServerMediaKind.MOVIE, "Shows" to ServerMediaKind.SERIES), libraries.map { it.name to it.kind })
        val call = http.requests.last()
        assertEquals("Bearer acc", call.header("Authorization"))
        assertEquals("p2", call.header("X-Profile-Id"))
        assertEquals("pvt_1", call.header("X-Profile-Token"))
        assertEquals("Kids", provider.profileName(session(signIn.token)))
        assertFalse(signIn.toString().contains("acc"))
    }

    @Test
    fun aRenewalKeepsTheProfileAndStoresBothNewTokens() = runTest {
        val (provider, http) = silo { request ->
            if (request.url.encodedPath == "/api/v2/auth/refresh") {
                Reply(200, """{"access_token":"acc2","refresh_token":"ref2","expires_in":3600}""")
            } else {
                null
            }
        }
        val old = session(credential(profileToken = "pvt_1"))

        val renewed = provider.renewCredential(old, ServerException(ServerFailure.AUTH_REQUIRED, code = "invalid_token"))!!

        assertEquals("""{"refresh_token":"ref"}""", http.requests.last().text)
        assertNull(http.requests.last().header("Authorization"))
        val headers = provider.authHeaders(session(renewed))
        assertEquals("Bearer acc2", headers["Authorization"])
        assertEquals("p2", headers["X-Profile-Id"])
        assertEquals("pvt_1", headers["X-Profile-Token"])
        assertEquals(clock + 3_600_000L, provider.credentialOrNull(renewed)!!.accessExpiresAtMs)
        assertEquals("ref2", provider.credentialOrNull(renewed)!!.refresh)
    }

    @Test
    fun aRefusedRenewalSignsOutButAnUnavailableServerKeepsTheTokens() = runTest {
        var reply = Reply(401, problem("session_expired", 401))
        val (provider, _) = silo { request -> if (request.url.encodedPath == "/api/v2/auth/refresh") reply else null }
        val old = session(credential())

        try {
            provider.renewCredential(old, null)
            fail("expected sign-out")
        } catch (error: ServerException) {
            assertEquals(ServerFailure.AUTH_REQUIRED, error.failure)
        }
        reply = Reply(503, problem("dependency_unavailable", 503))
        try {
            provider.renewCredential(old, null)
            fail("expected a retry later")
        } catch (error: ServerException) {
            assertEquals(ServerFailure.UNREACHABLE, error.failure)
        }
        assertNull(provider.renewCredential(old, ServerException(ServerFailure.FORBIDDEN, code = "permission_denied")))
    }

    @Test
    fun anyAuthAnswerIsWorthOneRenewalExceptAProfileCheck() = runTest {
        val (provider, http) = silo { request ->
            if (request.url.encodedPath == "/api/v2/auth/refresh") {
                Reply(200, """{"access_token":"acc2","refresh_token":"ref2","expires_in":3600}""")
            } else {
                null
            }
        }
        val old = session(credential())

        val renewed = provider.renewCredential(old, ServerException(ServerFailure.AUTH_REQUIRED, code = "authentication_required"))
        val profileCheck = provider.renewCredential(
            old,
            ServerException(ServerFailure.AUTH_REQUIRED, code = SiloNativeProvider.PROFILE_VERIFICATION)
        )

        assertEquals("acc2", provider.credentialOrNull(renewed!!)!!.access)
        assertNull(profileCheck)
        assertEquals(1, http.requests.count { it.url.encodedPath == "/api/v2/auth/refresh" })
    }

    @Test
    fun aRenewalSiloRejectsAsInvalidKeepsTheSignIn() = runTest {
        val (provider, _) = silo { request ->
            if (request.url.encodedPath == "/api/v2/auth/refresh") Reply(422, problem("validation_failed", 422)) else null
        }

        try {
            provider.renewCredential(session(credential()), null)
            fail("expected the renewal to fail")
        } catch (error: ServerException) {
            assertTrue(error.failure != ServerFailure.AUTH_REQUIRED)
        }
    }

    @Test
    fun aTokenIsRenewedAMinuteBeforeItRunsOut() {
        val (provider, _) = silo { null }

        assertFalse(provider.renewalDue(session(credential(expiresAtMs = clock + 61_000L))))
        assertTrue(provider.renewalDue(session(credential(expiresAtMs = clock + 59_000L))))
        assertFalse(provider.renewalDue(session("not a silo sign-in")))
    }

    @Test
    fun anotherProfileNeedsItsPinAndKeepsTheSameSignIn() = runTest {
        val (provider, _) = silo { request ->
            if (request.url.encodedPath == "/api/v2/profiles/p3/verify-pin") {
                Reply(200, """{"valid":true,"profile_token":"pvt_3","expires_at":null}""")
            } else {
                null
            }
        }
        val current = session(credential())
        val laura = SiloProfile("p1", "Laura", hasPin = false, isChild = false, isPrimary = true)
        val paul = SiloProfile("p3", "Paul", hasPin = true, isChild = false, isPrimary = false)

        val toLaura = provider.credentialOrNull(provider.withProfile(current, laura, null)!!)!!
        val toPaul = provider.credentialOrNull(provider.withProfile(current, paul, "1111")!!)!!

        assertEquals("p1" to null, toLaura.profileId to toLaura.profileToken)
        assertEquals("p3" to "pvt_3", toPaul.profileId to toPaul.profileToken)
        assertEquals("acc", toPaul.access)
        assertNull(provider.withProfile(current, paul, ""))
    }

    @Test
    fun signingOutEndsThisSessionOnTheServer() = runTest {
        val (provider, http) = silo { null }

        provider.signOut(session(credential()))

        val logout = http.requests.single()
        assertEquals("/api/v2/auth/logout", logout.url.encodedPath)
        assertEquals("Bearer acc", logout.header("Authorization"))
    }
}
