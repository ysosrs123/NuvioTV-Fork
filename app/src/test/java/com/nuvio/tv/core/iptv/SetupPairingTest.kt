package com.nuvio.tv.core.iptv

import org.junit.Assert.*
import org.junit.Test

class SetupPairingTest {
    private fun wrong(code: String) = ((code.toInt() + 1) % 1_000_000).toString().padStart(6, '0')

    @Test fun onlyPrivateAndLinkLocalIpv4AddressesCountAsLan() {
        for (good in listOf("10.0.0.1", "10.255.255.255", "172.16.0.1", "172.31.255.254", "192.168.1.20", "169.254.10.3", "::ffff:192.168.0.4"))
            assertTrue(good, SetupLan.isLanAddress(good))
        for (bad in listOf("127.0.0.1", "0.0.0.0", "8.8.8.8", "172.15.0.1", "172.32.0.1", "100.64.0.1", "192.169.0.1", "169.253.0.1",
                "192.168.1", "192.168.1.256", "192.168.01.5", "192.168.1.5.6", "fe80::1", "::1", "localhost", "", " ", null))
            assertFalse(bad.toString(), SetupLan.isLanAddress(bad))
    }

    @Test fun linksAndCodesHaveTheExpectedShape() {
        val pairing = SetupPairing()
        val first = pairing.credentials
        assertTrue(SetupPairing.TOKEN.matches(first.token))
        assertTrue(first.code.matches(Regex("[0-9]{6}")))
        assertFalse(first.toString().contains(first.token) || first.toString().contains(first.code))
        assertNotEquals(first.token, SetupPairing().credentials.token)
    }

    @Test fun theRightCodeGivesASessionBoundToTheLinkAndRenewsTheCode() {
        val pairing = SetupPairing()
        val (token, code) = pairing.credentials.let { it.token to it.code }
        val paired = pairing.pair(token, code) as SetupPairing.Result.Paired
        assertNotNull(pairing.session(token, paired.sessionId))
        assertNull(pairing.session(SetupPairing().credentials.token, paired.sessionId))
        assertNull(pairing.session(token, paired.sessionId.reversed()))
        assertNull(pairing.session(token, null))
        assertEquals(token, pairing.credentials.token)
        assertNotEquals(code, pairing.credentials.code)
        assertEquals(SetupPairing.Result.WrongCode(4), pairing.pair(token, code))
        assertEquals(1, pairing.sessionCount)
    }

    @Test fun fiveWrongCodesBurnTheLinkAndIssueANewOne() {
        val pairing = SetupPairing()
        val old = pairing.credentials
        for (left in 4 downTo 1) assertEquals(SetupPairing.Result.WrongCode(left), pairing.pair(old.token, wrong(old.code)))
        assertEquals(SetupPairing.Result.Renewed, pairing.pair(old.token, wrong(old.code)))
        val renewed = pairing.credentials
        assertNotEquals(old.token, renewed.token)
        assertTrue(renewed.revision > old.revision)
        assertFalse(pairing.knowsLink(old.token))
        assertEquals(SetupPairing.Result.UnknownLink, pairing.pair(old.token, old.code))
        assertEquals(SetupPairing.Result.UnknownLink, pairing.pair(old.token, renewed.code))
        assertTrue(pairing.pair(renewed.token, renewed.code) is SetupPairing.Result.Paired)
    }

    @Test fun malformedCodesCountAsAttempts() {
        val pairing = SetupPairing(maxAttempts = 2)
        val token = pairing.credentials.token
        assertEquals(SetupPairing.Result.WrongCode(1), pairing.pair(token, "12345"))
        assertEquals(SetupPairing.Result.Renewed, pairing.pair(token, "abcdef"))
    }

    @Test fun existingSessionsSurviveARenewalButTheOldLinkCannotPairAgain() {
        val pairing = SetupPairing()
        val first = pairing.credentials
        val session = (pairing.pair(first.token, first.code) as SetupPairing.Result.Paired).sessionId
        repeat(pairing.maxAttempts) { pairing.pair(first.token, wrong(pairing.credentials.code)) }
        assertNotEquals(first.token, pairing.credentials.token)
        assertTrue(pairing.knowsLink(first.token))
        assertNotNull(pairing.session(first.token, session))
        assertEquals(SetupPairing.Result.UnknownLink, pairing.pair(first.token, pairing.credentials.code))
    }

    @Test fun sessionsAreCappedAndClosingEndsEverything() {
        val pairing = SetupPairing(maxSessions = 2)
        val token = pairing.credentials.token
        val sessions = List(3) { (pairing.pair(token, pairing.credentials.code) as SetupPairing.Result.Paired).sessionId }
        assertNull(pairing.session(token, sessions[0]))
        assertNotNull(pairing.session(token, sessions[2]))
        pairing.close()
        assertNull(pairing.session(token, sessions[2]))
        assertFalse(pairing.knowsLink(token))
        assertFalse(pairing.knowsLink(pairing.credentials.token))
        assertEquals(SetupPairing.Result.UnknownLink, pairing.pair(pairing.credentials.token, pairing.credentials.code))
    }

    @Test fun constantTimeComparisonMatchesEquality() {
        assertTrue(SetupPairing.same("123456", "123456"))
        assertFalse(SetupPairing.same("123456", "123457"))
        assertFalse(SetupPairing.same("123456", "1234567"))
    }

    private val origin = "http://192.168.1.20:8100"
    private fun post(vararg extra: Pair<String, String?>): Map<String, String> {
        val headers = mutableMapOf("host" to "192.168.1.20:8100", "x-nuvio-setup" to "1", "content-type" to "application/json; charset=utf-8",
            "origin" to origin, "sec-fetch-site" to "same-origin")
        for ((key, value) in extra) if (value == null) headers.remove(key) else headers[key] = value
        return headers
    }

    @Test fun stateChangingRequestsNeedTheHeaderJsonAndTheTvOrigin() {
        assertNull(SetupGuard.check(post(), origin, true))
        assertEquals(SetupGuard.Rejection.HEADER, SetupGuard.check(post("x-nuvio-setup" to null), origin, true))
        assertEquals(SetupGuard.Rejection.HEADER, SetupGuard.check(post("x-nuvio-setup" to "0"), origin, true))
        assertEquals(SetupGuard.Rejection.CONTENT_TYPE, SetupGuard.check(post("content-type" to "text/plain"), origin, true))
        assertEquals(SetupGuard.Rejection.CONTENT_TYPE, SetupGuard.check(post("content-type" to null), origin, true))
        assertEquals(SetupGuard.Rejection.CONTENT_TYPE, SetupGuard.check(post("content-type" to "application/x-www-form-urlencoded"), origin, true))
        assertEquals(SetupGuard.Rejection.ORIGIN, SetupGuard.check(post("origin" to "http://evil.example"), origin, true))
        assertEquals(SetupGuard.Rejection.ORIGIN, SetupGuard.check(post("origin" to "null"), origin, true))
        assertEquals(SetupGuard.Rejection.ORIGIN, SetupGuard.check(post("origin" to "http://192.168.1.20:8080"), origin, true))
        assertEquals(SetupGuard.Rejection.ORIGIN, SetupGuard.check(post("origin" to null), origin, true))
        assertNull(SetupGuard.check(post("origin" to null, "referer" to "$origin/s/abc/"), origin, true))
        assertEquals(SetupGuard.Rejection.ORIGIN, SetupGuard.check(post("origin" to null, "referer" to "$origin.evil.example/"), origin, true))
        assertEquals(SetupGuard.Rejection.FETCH_SITE, SetupGuard.check(post("sec-fetch-site" to "same-site"), origin, true))
        assertEquals(SetupGuard.Rejection.FETCH_SITE, SetupGuard.check(post("sec-fetch-site" to "cross-site"), origin, true))
        assertNull(SetupGuard.check(post("sec-fetch-site" to null), origin, true))
    }

    @Test fun everyApiRequestChecksHostAndHeader() {
        val get = post("content-type" to null, "origin" to null)
        assertNull(SetupGuard.check(get, origin, false))
        assertEquals(SetupGuard.Rejection.HOST, SetupGuard.check(post("host" to "attacker.example:8100"), origin, false))
        assertEquals(SetupGuard.Rejection.HOST, SetupGuard.check(post("host" to null), origin, false))
        assertEquals(SetupGuard.Rejection.HEADER, SetupGuard.check(post("x-nuvio-setup" to null), origin, false))
        assertEquals(SetupGuard.Rejection.ORIGIN, SetupGuard.check(post("origin" to "http://evil.example"), origin, false))
    }

    @Test fun rateLimiterAllowsABurstPerKeyThenWaitsForTheWindow() {
        var now = 0L
        val limiter = SetupRateLimiter(3, 1000, { now }, maxKeys = 2)
        repeat(3) { assertTrue(limiter.allow("a")) }
        assertFalse(limiter.allow("a"))
        assertTrue(limiter.allow("b"))
        assertFalse(limiter.allow("c"))
        now = 1000
        assertTrue(limiter.allow("a"))
        assertTrue(limiter.allow("c"))
    }

    @Test fun connectionsAreCappedInTotalAndPerAddress() {
        val connections = SetupConnectionLimiter(limit = 3, perAddress = 2)
        assertTrue(connections.admit("a")); assertTrue(connections.admit("a"))
        assertFalse(connections.admit("a"))
        assertTrue(connections.admit("b"))
        assertFalse(connections.admit("c"))
        connections.release("a")
        assertTrue(connections.admit("c"))
        assertFalse(connections.admit("a"))
        connections.release("zzz")
        assertFalse(connections.admit("d"))
        connections.release("b")
        assertTrue(connections.admit("a"))
        assertFalse(connections.admit("a"))
    }

    @Test fun idleTimerExpiresWithoutActivity() {
        var now = 0L
        val idle = SetupIdleTimer(600_000, { now })
        now = 599_999; assertFalse(idle.expired())
        idle.touch(); now += 599_999; assertFalse(idle.expired())
        now += 1; assertTrue(idle.expired())
    }

    @Test fun pagesCarryStrictSecurityHeaders() {
        val nonce = SetupHeaders.nonce(java.security.SecureRandom())
        val page = SetupHeaders.security(nonce)
        val csp = page.getValue("Content-Security-Policy")
        assertTrue(csp.startsWith("default-src 'self'"))
        assertTrue("script-src 'nonce-$nonce'" in csp && "style-src 'nonce-$nonce'" in csp)
        assertTrue("frame-ancestors 'none'" in csp && "form-action 'none'" in csp && "unsafe-inline" !in csp)
        assertEquals("DENY", page["X-Frame-Options"])
        assertEquals("no-store", page["Cache-Control"])
        assertEquals("nosniff", page["X-Content-Type-Options"])
        assertEquals("same-origin", page["Referrer-Policy"])
        assertTrue(SetupHeaders.security().getValue("Content-Security-Policy").startsWith("default-src 'none'"))
        assertNotEquals(nonce, SetupHeaders.nonce(java.security.SecureRandom()))
    }

    @Test fun sessionCookieIsHttpOnlyStrictAndScopedToTheLink() {
        val token = SetupPairing().credentials.token
        val cookie = SetupCookies.session("ab12", token)
        assertTrue(cookie.contains("HttpOnly") && cookie.contains("SameSite=Strict") && cookie.contains("Path=/s/$token/"))
        assertThrows(IllegalArgumentException::class.java) { SetupCookies.session("ab;12", token) }
        assertEquals("ab12", SetupCookies.read("theme=dark; nuvio_setup=ab12; other=1"))
        assertNull(SetupCookies.read("nuvio_setup=a; nuvio_setup=b"))
        assertNull(SetupCookies.read("xnuvio_setup=a"))
        assertNull(SetupCookies.read(null))
    }
}
