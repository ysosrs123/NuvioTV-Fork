package com.nuvio.tv.data.iptv

import com.nuvio.tv.core.iptv.HostKey
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class IptvFormatProbeTest {
    private class MapStore : IptvHostStore {
        val values = linkedMapOf<String, String>()
        override fun read(key: String) = values[key]
        override fun write(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
    }

    private val client = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()
    private fun ts(packets: Int) = ByteArray(packets * 188).also { b -> repeat(packets) { b[it * 188] = 0x47 } }

    @Test fun hlsBodyIsRecognisedOnceAndRememberedPerHashedHost() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("#EXTM3U\n#EXT-X-TARGETDURATION:6\n"))
            val store = MapStore()
            val probe = IptvFormatProbe(IptvHostMemory("probe-", store))
            val url = server.url("/live/u/p/42").toString()
            assertEquals(IptvStreamFormat.HLS, probe.probe(client, url, mapOf("User-Agent" to "Agent/1", "Referer" to "https://r.invalid/")))
            val request = server.takeRequest()
            assertEquals("Agent/1", request.getHeader("User-Agent"))
            assertEquals("https://r.invalid/", request.getHeader("Referer"))
            assertEquals(IptvStreamFormat.HLS, probe.probe(client, server.url("/live/u/p/43").toString(), emptyMap()))
            assertEquals(1, server.requestCount)
            assertEquals(mapOf("probe-" + HostKey.of(url) to "HLS"), store.values)
            assertFalse(store.values.keys.single().contains(server.hostName))
            assertEquals(IptvStreamFormat.HLS, IptvFormatProbe(IptvHostMemory("probe-", store)).known(url))
        }
    }

    @Test fun transportStreamIsReadPartiallyAndClosed() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(Buffer().write(ts(4000))))
            val probe = IptvFormatProbe(IptvHostMemory("probe-", null))
            assertEquals(IptvStreamFormat.MPEG_TS, probe.probe(client, server.url("/stream").toString(), emptyMap()))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun redirectTargetAndContentTypeDecideWhenBodyIsUnclear() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/cdn/index.m3u8"))
            server.enqueue(MockResponse().setBody("x"))
            val probe = IptvFormatProbe(IptvHostMemory("probe-", null))
            assertEquals(IptvStreamFormat.HLS, probe.probe(client, server.url("/play/1").toString(), emptyMap()))
        }
        MockWebServer().use { server ->
            server.enqueue(MockResponse().addHeader("Content-Type", "video/mp2t").setBody("x"))
            assertEquals(IptvStreamFormat.MPEG_TS, IptvFormatProbe(IptvHostMemory("probe-", null)).probe(client, server.url("/play/1").toString(), emptyMap()))
        }
    }

    @Test fun failuresAndUnknownBodiesAreNotRemembered() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403))
            server.enqueue(MockResponse().setBody("<html>login</html>"))
            val store = MapStore()
            val probe = IptvFormatProbe(IptvHostMemory("probe-", store))
            assertNull(probe.probe(client, server.url("/a").toString(), emptyMap()))
            assertNull(probe.probe(client, server.url("/b").toString(), emptyMap()))
            assertTrue(store.values.isEmpty())
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun tellingAddressNeedsNoRequestAndForgetClearsMemory() {
        val store = MapStore()
        val probe = IptvFormatProbe(IptvHostMemory("probe-", store))
        assertEquals(IptvStreamFormat.MPEG_TS, probe.probe(client, "http://unreachable.invalid/live/1.ts", emptyMap()))
        val memory = IptvHostMemory("probe-", store)
        memory.put("http://a.invalid/x", "HLS")
        assertEquals(IptvStreamFormat.HLS, IptvFormatProbe(memory).known("http://a.invalid/other"))
        IptvFormatProbe(memory).forget("http://a.invalid/y")
        assertNull(IptvFormatProbe(memory).known("http://a.invalid/other"))
        assertTrue(store.values.isEmpty())
    }

    @Test fun badHeaderValuesDoNotThrow() {
        assertNull(IptvFormatProbe(IptvHostMemory("probe-", null)).probe(client, "http://unreachable.invalid/x", mapOf("User-Agent" to "a\nb")))
    }
}
