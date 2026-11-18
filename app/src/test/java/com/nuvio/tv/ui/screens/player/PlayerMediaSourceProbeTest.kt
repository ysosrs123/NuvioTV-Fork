package com.nuvio.tv.ui.screens.player

import androidx.media3.common.MimeTypes
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PlayerMediaSourceProbeTest {

    private val server = MockWebServer()
    private val client = OkHttpClient()

    @Before fun setUp() = server.start()

    @After fun tearDown() = server.shutdown()

    private fun bdav(size: Int) = ByteArray(size).also { bytes -> for (i in 4 until size step 192) bytes[i] = 0x47 }

    private fun body(bytes: ByteArray, code: Int = 206) =
        MockResponse().setResponseCode(code).setBody(Buffer().write(bytes))

    private fun probe(path: String = "/d/AB12CD34", checkBluray: Boolean = true, headers: Map<String, String> = emptyMap()) =
        runBlocking {
            PlayerMediaSourceFactory.probeNetworkFormat(server.url(path).toString(), headers, checkBluray, client)
        }

    @Test
    fun rangedAnswerWithTheBlurayLayout_isFoundInOneRequest() {
        server.enqueue(body(bdav(1024)))
        val result = probe()
        assertTrue(result.isBlurayTransportStream)
        assertEquals(MimeTypes.VIDEO_MP2T, result.mimeType)
        assertEquals(1, server.requestCount)
        assertEquals("bytes=0-1023", server.takeRequest().getHeader("Range"))
    }

    @Test
    fun serverIgnoringTheRange_isStillRecognised() {
        server.enqueue(body(bdav(64 * 1024), code = 200))
        assertTrue(probe().isBlurayTransportStream)
    }

    @Test
    fun otherAnswersOfTheCheck_nameTheContainerWithoutAnExtraRequest() {
        server.enqueue(body(ByteArray(1024), code = 200).setHeader("Content-Type", "video/x-matroska"))
        val result = probe()
        assertFalse(result.isBlurayTransportStream)
        assertEquals(MimeTypes.VIDEO_MATROSKA, result.mimeType)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun refusedRequest_isNotABlurayFileAndIsNotRepeated() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("gone"))
        val result = probe()
        assertFalse(result.isBlurayTransportStream)
        assertNull(result.mimeType)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun shortBodyOrWebPage_isNotABlurayFile() {
        server.enqueue(body(bdav(400)))
        assertFalse(probe().isBlurayTransportStream)
        server.enqueue(
            body("<!DOCTYPE html><html><body>Link expired</body></html>".toByteArray(), code = 200)
                .setHeader("Content-Type", "text/html")
        )
        assertFalse(probe().isBlurayTransportStream)
    }

    @Test
    fun withoutTheCheck_theProbeDoesNotLookForTheLayout() {
        server.enqueue(body(bdav(1024), code = 200))
        server.enqueue(body(bdav(1024)))
        val result = probe(checkBluray = false)
        assertFalse(result.isBlurayTransportStream)
        assertNull(server.takeRequest().getHeader("Range"))
        assertEquals("bytes=0-2048", server.takeRequest().getHeader("Range"))
    }

    @Test
    fun aRedirectToAnotherPortKeepsTheHeadersButNotTheLogin() {
        val other = MockWebServer().apply { start() }
        try {
            other.enqueue(body(bdav(1024)))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/file").toString()))
            val result = probe(headers = mapOf("Authorization" to "Bearer abc", "Referer" to "https://addon.example/"))
            assertTrue(result.isBlurayTransportStream)
            assertEquals("Bearer abc", server.takeRequest().getHeader("Authorization"))
            val hop = other.takeRequest()
            assertNull(hop.getHeader("Authorization"))
            assertEquals("https://addon.example/", hop.getHeader("Referer"))
        } finally {
            other.shutdown()
        }
    }

    @Test
    fun stalledHost_isGivenUpOnWithoutTheOldProbe() {
        server.enqueue(body(bdav(1024)).setHeadersDelay(10, java.util.concurrent.TimeUnit.SECONDS))
        server.enqueue(body(bdav(1024)))
        val result = probe()
        assertFalse(result.isBlurayTransportStream)
        assertNull(result.mimeType)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun nonHttpLinks_areNotProbed() {
        val result = runBlocking {
            PlayerMediaSourceFactory.probeNetworkFormat("file:///storage/usb/00001.m2ts", emptyMap(), true, client)
        }
        assertFalse(result.isBlurayTransportStream)
        assertNull(result.mimeType)
        assertEquals(0, server.requestCount)
    }
}
