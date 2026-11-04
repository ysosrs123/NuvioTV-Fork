package com.nuvio.tv.data.wetrakr

import java.io.IOException
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WeTrakrHttpClientTest {
    private var now = 1_793_750_400_000L

    @Test
    fun `requests carry the app key, version and an idempotency key on writes only`() {
        val engine = OkHttpWeTrakrEngine(OkHttpClient(), WeTrakrConfiguration("client-key", "1.0"))
        val read = engine.buildRequest(WeTrakrHttpRequest(WeTrakrHttpMethod.GET, "/sync/last_activities", accessToken = "token"))
        assertEquals("client-key", read.header("wetrakr-api-key"))
        assertEquals("1", read.header("wetrakr-api-version"))
        assertEquals("Bearer token", read.header("Authorization"))
        assertNull(read.header("Idempotency-Key"))
        val write = engine.buildRequest(WeTrakrHttpRequest(WeTrakrHttpMethod.POST, "/sync/tracking", body = "{}"))
        assertNotNull(write.header("Idempotency-Key"))
        assertEquals("https://api.wetrakr.com/sync/tracking", write.url.toString())
    }

    @Test
    fun `a daily limit blocks further calls until the reset and records the quota`() = runTest {
        val engine = FakeWeTrakrEngine()
        engine.on(
            WeTrakrHttpMethod.GET, "/sync/last_activities",
            ok("""{"error":"QUOTA_EXCEEDED"}""", "X-Quota-Limit" to "1000", "X-Quota-Remaining" to "0", "X-Quota-Reset" to "3600", status = 429)
        )
        val http = WeTrakrHttpClient(engine, nowEpochMs = { now }, sleep = { now += it })
        val error = runCatching { http.execute(WeTrakrHttpRequest(WeTrakrHttpMethod.GET, "/sync/last_activities", limitKey = "user:1")) }
            .exceptionOrNull() as WeTrakrApiException
        assertTrue(error.isDailyLimit)
        assertEquals(now + 3_600_000L, error.retryAtEpochMs)
        assertEquals(now + 3_600_000L, http.quotas.value["user:1"]?.blockedUntilEpochMs)
        assertEquals(1000L, http.quotas.value["user:1"]?.used)
        runCatching { http.execute(WeTrakrHttpRequest(WeTrakrHttpMethod.GET, "/sync/last_activities", limitKey = "user:1")) }
        assertEquals(1, engine.requests.size)
        now += 3_600_001L
        runCatching { http.execute(WeTrakrHttpRequest(WeTrakrHttpMethod.GET, "/sync/last_activities", limitKey = "user:1")) }
        assertEquals(2, engine.requests.size)
    }

    @Test
    fun `too many simultaneous writes waits and retries the same write`() = runTest {
        val engine = FakeWeTrakrEngine()
        engine.on(
            WeTrakrHttpMethod.POST, "/sync/tracking",
            ok("""{"error":"CONCURRENCY_LIMIT"}""", "Retry-After" to "1", status = 429),
            ok("""{"added":{}}""")
        )
        val http = WeTrakrHttpClient(engine, nowEpochMs = { now }, sleep = { now += it })
        val request = WeTrakrHttpRequest(WeTrakrHttpMethod.POST, "/sync/tracking", body = "{}")
        assertEquals(200, http.execute(request).status)
        assertEquals(2, engine.requests.size)
        assertEquals(engine.requests[0].idempotencyKey, engine.requests[1].idempotencyKey)
    }

    @Test
    fun `server errors on reads are retried, transport failures give up after three tries`() = runTest {
        val engine = FakeWeTrakrEngine()
        engine.on(WeTrakrHttpMethod.GET, "/a", ok("", status = 503), ok("[]"))
        val http = WeTrakrHttpClient(engine, nowEpochMs = { now }, sleep = { now += it })
        assertEquals(200, http.execute(WeTrakrHttpRequest(WeTrakrHttpMethod.GET, "/a")).status)
        var attempts = 0
        val failing = WeTrakrHttpClient({ attempts++; throw IOException("down") }, nowEpochMs = { now }, sleep = { now += it })
        try {
            failing.execute(WeTrakrHttpRequest(WeTrakrHttpMethod.GET, "/a"))
            fail()
        } catch (error: WeTrakrApiException) {
            assertEquals("transport_failure", error.code)
        }
        assertEquals(3, attempts)
    }

    @Test
    fun `a per-minute limit blocks only that account until the window resets`() = runTest {
        val engine = FakeWeTrakrEngine()
        engine.on(WeTrakrHttpMethod.GET, "/a", ok("""{"message":"Too many requests. Please slow down."}""", "RateLimit-Reset" to "20", status = 429))
        val http = WeTrakrHttpClient(engine, nowEpochMs = { now }, sleep = { now += it })
        val error = runCatching { http.execute(WeTrakrHttpRequest(WeTrakrHttpMethod.GET, "/a", limitKey = "user:1")) }
            .exceptionOrNull() as WeTrakrApiException
        assertEquals(now + 20_000L, error.retryAtEpochMs)
        engine.on(WeTrakrHttpMethod.GET, "/a", ok("[]"))
        assertEquals(200, http.execute(WeTrakrHttpRequest(WeTrakrHttpMethod.GET, "/a", limitKey = "user:2")).status)
    }

    @Test
    fun `error codes are read from both answer shapes`() {
        assertEquals("QUOTA_EXCEEDED", WeTrakrHttpResponse(429, """{"error":"QUOTA_EXCEEDED"}""").errorCode())
        assertEquals("INVALID_MEDIA_ID", WeTrakrHttpResponse(400, """{"success":false,"error":{"code":"INVALID_MEDIA_ID"}}""").errorCode())
        assertNull(WeTrakrHttpResponse(500, "<html>").errorCode())
    }
}
