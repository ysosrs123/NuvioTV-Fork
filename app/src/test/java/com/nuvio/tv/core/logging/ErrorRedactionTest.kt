package com.nuvio.tv.core.logging

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.PrintWriter
import java.io.StringWriter
import java.net.UnknownHostException

class ErrorRedactionTest {
    private val token = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ0ZXN0In0.c2lnbmF0dXJlLXRlc3Q"
    private val apiKey = "sb_publishable_testkey0123456789abcdef"

    private val supabaseMessage = """
        Unsupported provider credential: debrid:alldebrid
        URL: https://example.supabase.co/rest/v1/rpc/sync_seed_provider_credentials
        Headers: [Authorization=[Bearer $token], apikey=[$apiKey], Content-Type=[application/json]]
        Http Method: POST
    """.trimIndent()

    @Test
    fun `request headers are dropped and the rest stays readable`() {
        val cleaned = supabaseMessage.withoutRequestSecrets()

        assertFalse(cleaned.contains(token))
        assertFalse(cleaned.contains(apiKey))
        assertFalse(cleaned.contains("eyJ"))
        assertTrue(cleaned.contains("Unsupported provider credential: debrid:alldebrid"))
        assertTrue(cleaned.contains("https://example.supabase.co/rest/v1/rpc/"))
        assertTrue(cleaned.contains("Http Method: POST"))
    }

    @Test
    fun `a bearer token outside a headers line is masked`() {
        val cleaned = "request failed (Authorization: Bearer abc.def-123) after $token".withoutRequestSecrets()

        assertFalse(cleaned.contains("abc.def-123"))
        assertFalse(cleaned.contains(token))
    }

    @Test
    fun `the logged error keeps class, stack and cause without secrets`() {
        val cause = IllegalStateException("inner Bearer $token")
        val error = RuntimeException(supabaseMessage, cause)

        val logged = error.forLog()
        val printed = StringWriter().also { logged.printStackTrace(PrintWriter(it)) }.toString()

        assertTrue(printed.startsWith("java.lang.RuntimeException: Unsupported provider credential"))
        assertTrue(printed.contains("Caused by: java.lang.IllegalStateException: inner Bearer REDACTED"))
        assertFalse(printed.contains(token))
        assertFalse(printed.contains(apiKey))
        assertArrayEquals(error.stackTrace, logged.stackTrace)
    }

    @Test
    fun `an offline error is left as it is`() {
        val error = RuntimeException("offline", UnknownHostException("example.supabase.co"))

        assertSame(error, error.forLog())
    }
}
