package com.nuvio.tv.core.network

import org.junit.Assert.*
import org.junit.Test

class DiagnosticNetworkTrackerTest {
    private fun ready(t: DiagnosticNetworkTracker, n: String = "a") {
        t.available(n); t.capabilities(n, "wifi|validated"); t.links(n, "dns-one")
    }
    @Test fun `token requires registration and both initial route facts`() {
        val t = DiagnosticNetworkTracker(); assertNull(t.token("a")); t.start()
        t.available("a"); assertNull(t.token("a")); t.capabilities("a", "wifi"); assertNull(t.token("a"))
        t.links("a", "dns"); assertNotNull(t.token("a"))
    }
    @Test fun `switch away and back cannot restore old token`() {
        val t = DiagnosticNetworkTracker(); t.start(); ready(t); val before = t.token("a")
        t.available("b"); ready(t, "b"); ready(t, "a"); assertNotEquals(before, t.token("a"))
    }
    @Test fun `equal route updates preserve observation`() {
        val t = DiagnosticNetworkTracker(); t.start(); ready(t); val before = t.token("a")
        ready(t); assertEquals(before, t.token("a"))
    }
    @Test fun `same network capability and DNS changes invalidate observations`() {
        val t = DiagnosticNetworkTracker(); t.start(); ready(t); val first = t.token("a")
        t.capabilities("a", "vpn|validated"); val second = t.token("a"); assertNotEquals(first, second)
        t.links("a", "dns-two"); assertNotEquals(second, t.token("a"))
    }
    @Test fun `late events from former network cannot alter new route`() {
        val t = DiagnosticNetworkTracker(); t.start(); ready(t); ready(t, "b"); val before = t.token("b")
        t.lost("a"); t.capabilities("a", "late"); t.links("a", "late")
        assertEquals(before, t.token("b"))
    }
    @Test fun `closed lifetime rejects late callbacks`() {
        val t = DiagnosticNetworkTracker(); t.start(); ready(t); t.close(); val revision = t.changes.value
        ready(t); t.lost("a"); assertNull(t.token("a")); assertEquals(revision, t.changes.value)
    }
    @Test fun `loss and new registration expire tokens`() {
        val t = DiagnosticNetworkTracker(); t.start(); ready(t); val before = t.token("a")
        t.lost("a"); assertNull(t.token("a")); ready(t); assertNotEquals(before, t.token("a"))
        val next = t.token("a"); t.close(); t.start(); ready(t); assertNotEquals(next, t.token("a"))
    }
    @Test fun `active network mismatch cannot obtain token before callback catches up`() {
        val t = DiagnosticNetworkTracker(); t.start(); ready(t)
        assertNull(t.token(null)); assertNull(t.token("b")); assertNotNull(t.token("a"))
    }

    @Test fun `older API can observe default network while route detail remains honestly unknown`() {
        val t = DiagnosticNetworkTracker(requireRouteFacts = false); t.start(); t.available("a")
        val token = t.token("a")!!; assertFalse(token.routeFactsKnown)
        t.capabilities("a", "wifi"); t.links("a", "dns"); assertTrue(t.token("a")!!.routeFactsKnown)
        assertNotEquals(token, t.token("a"))
    }
    @Test fun `blocked and unblocked access cannot reuse previous observation`() {
        val t = DiagnosticNetworkTracker(); t.start(); ready(t); val token = t.token("a")
        t.blocked("a", true); assertNull(t.token("a"))
        t.blocked("a", false); assertNotEquals(token, t.token("a"))
    }

}
