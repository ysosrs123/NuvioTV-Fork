package com.nuvio.tv.core.scout.ask

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private const val TOKEN = "good-token"
private const val KEY = "sk-phone-SECRET-987"

class AskPhoneServerTest {
    private val keys = mutableListOf<AskConfig>()
    private val questions = mutableListOf<String>()
    private val state = AskPhoneState(
        AskProvider.GROQ,
        AskProvider.GROQ,
        listOf(AskPhoneKeyStatus(AskProvider.GROQ, true, "")),
        "The key works.",
        true
    )
    private val server = AskPhoneServer.startOnAvailablePort(
        token = TOKEN,
        page = { "<html>page</html>" },
        state = { state },
        onKey = { keys += it; true },
        onQuestion = { questions += it },
        startPort = java.net.ServerSocket(0).use { it.localPort }
    )!!

    @After
    fun stop() = server.stop()

    private fun call(path: String, token: String? = null, body: String? = null): Pair<Int, String> {
        val connection = URL("http://127.0.0.1:${server.listeningPort}$path").openConnection() as HttpURLConnection
        token?.let { connection.setRequestProperty("x-ask-token", it) }
        if (body != null) {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = connection.responseCode
        val stream = if (code < 400) connection.inputStream else connection.errorStream
        return code to stream.bufferedReader().readText()
    }

    @Test
    fun `the page needs the code from the QR`() {
        assertEquals(403, call("/").first)
        assertEquals(403, call("/?t=wrong").first)
        assertEquals(200, call("/?t=$TOKEN").first)
    }

    @Test
    fun `nothing can be read or sent without the code`() {
        assertEquals(403, call("/api/state").first)
        assertEquals(403, call("/api/key", body = """{"provider":"groq","key":"$KEY"}""").first)
        assertEquals(403, call("/api/key", "wrong", """{"provider":"groq","key":"$KEY"}""").first)
        assertEquals(403, call("/api/question", body = """{"question":"hello"}""").first)
        assertTrue(keys.isEmpty())
        assertTrue(questions.isEmpty())
    }

    @Test
    fun `a key arrives once and is never sent back`() {
        assertEquals(200, call("/api/key", TOKEN, """{"provider":"groq","key":"$KEY","model":"","baseUrl":""}""").first)
        assertEquals(KEY, keys.single().key)
        val (code, body) = call("/api/state", TOKEN)
        assertEquals(200, code)
        assertFalse(body.contains(KEY))
        assertTrue(body.contains("The key works."))
    }

    @Test
    fun `a question arrives trimmed and bad input is refused`() {
        assertEquals(200, call("/api/question", TOKEN, """{"question":"  Filme wie Inception  "}""").first)
        assertEquals("Filme wie Inception", questions.single())
        assertEquals(400, call("/api/question", TOKEN, """{"question":"   "}""").first)
        assertEquals(400, call("/api/question", TOKEN, "not json").first)
        assertEquals(400, call("/api/key", TOKEN, """{"provider":"groq","key":"bad key"}""").first)
        assertEquals(400, call("/api/key", TOKEN, """{"provider":"groq","key":"${"x".repeat(20_000)}"}""").first)
        assertEquals(404, call("/api/other", TOKEN).first)
    }

    @Test
    fun `the phone page holds the code, every service and no key`() {
        val prefs = mockk<SharedPreferences>()
        every { prefs.getString(any(), any()) } returns null
        val resources = mockk<Resources>(relaxed = true)
        every { resources.configuration } returns mockk<Configuration>(relaxed = true)
        val context = mockk<Context>()
        every { context.getSharedPreferences(any(), any()) } returns prefs
        every { context.resources } returns resources
        every { context.getString(any()) } returns "text </script> \"quoted\""
        every { context.getString(any(), *anyVararg()) } returns "text with arg"
        val html = AskPhonePage.html(context, TOKEN, startOnKey = true)
        assertTrue(html.contains(TOKEN))
        AskProvider.entries.forEach { assertTrue(html.contains("\"${it.id}\"")) }
        assertFalse(html.contains(KEY))
        val script = html.substringAfter("<script>").substringBefore("</script>")
        assertFalse(script.contains("</script"))
        System.getenv("ASK_PAGE_DUMP")?.let { File(it).writeText(html) }
    }
}
