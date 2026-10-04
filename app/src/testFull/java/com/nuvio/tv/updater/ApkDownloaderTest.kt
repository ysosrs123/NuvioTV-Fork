package com.nuvio.tv.updater

import kotlinx.coroutines.test.runTest
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ApkDownloaderTest {
    @get:Rule val temp = TemporaryFolder()
    private fun downloader(code: Int = 200, body: ResponseBody = "apk".toResponseBody()): ApkDownloader {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(code).message("fixture").body(body).build()
        }.build()
        return ApkDownloader(client)
    }
    @Test fun `successful download reports bytes and replaces cached file`() = runTest {
        val target = temp.newFile().apply { writeText("old") }
        var downloaded = 0L
        val result = downloader().download("https://example.invalid/fixture.apk", target) { done, total ->
            downloaded = done; assertEquals(3L, total)
        }
        assertTrue(result.isSuccess)
        assertEquals("apk", target.readText())
        assertEquals(3L, downloaded)
    }
    @Test fun `HTTP failure removes stale file`() = runTest {
        val target = temp.newFile().apply { writeText("old") }
        assertTrue(downloader(403).download("https://example.invalid/fixture.apk", target) { _, _ -> }.isFailure)
        assertFalse(target.exists())
    }
    @Test fun `truncated response never leaves installable cache entry`() = runTest {
        val body = object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength() = 10L
            override fun source() = Buffer().writeUtf8("short")
        }
        val target = temp.newFile()
        assertTrue(downloader(body = body).download("https://example.invalid/fixture.apk", target) { _, _ -> }.isFailure)
        assertFalse(target.exists())
    }
}
