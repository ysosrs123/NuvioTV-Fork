package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.network.StreamContentLengthProbe
import kotlinx.coroutines.runBlocking
import okhttp3.*
import org.junit.Assert.*
import org.junit.Test
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLException

class ContentLengthProbeIsolationTest {
    @Test fun `diagnostic requests bypass playback interceptors and event listeners`() = runBlocking {
        val events=AtomicInteger(); val dns=AtomicInteger(); val intercepts=AtomicInteger()
        val playback=OkHttpClient.Builder().dns {
            dns.incrementAndGet(); throw UnknownHostException("synthetic no network")
        }.eventListener(object : EventListener() {
            override fun callStart(call: Call) { events.incrementAndGet() }
        }).addInterceptor { intercepts.incrementAndGet(); throw IllegalStateException("Playback interceptor") }
            .addNetworkInterceptor { throw IllegalStateException("Playback network interceptor") }.build()
        val calls=PlayerPlaybackNetworking.createContentLengthProbeCallFactory(playback) {
            error("DNS failure must not trigger TLS fallback")
        }
        assertEquals(0L,StreamContentLengthProbe(calls).probe("https://example.invalid/media",emptyMap()))
        assertEquals(1,dns.get()); assertEquals(0,events.get()); assertEquals(0,intercepts.get())
        assertEquals(0,playback.dispatcher.runningCallsCount()); assertEquals(0,playback.dispatcher.queuedCallsCount())
    }
    @Test fun `TLS fallback uses isolated scheduling and leaves playback events untouched`() = runBlocking {
        val primaryDns=AtomicInteger(); val fallbackDns=AtomicInteger(); val events=AtomicInteger()
        val listener=object : EventListener() { override fun callStart(call: Call) { events.incrementAndGet() } }
        val primary=OkHttpClient.Builder().dns {
            primaryDns.incrementAndGet(); throw SSLException("synthetic TLS failure")
        }.eventListener(listener).build()
        val fallback=OkHttpClient.Builder().dns {
            fallbackDns.incrementAndGet(); throw UnknownHostException("synthetic no network")
        }.eventListener(listener).build()
        val calls=PlayerPlaybackNetworking.createContentLengthProbeCallFactory(primary) { fallback }
        assertEquals(0L,StreamContentLengthProbe(calls).probe("https://example.invalid/media",emptyMap()))
        assertEquals(1,primaryDns.get()); assertEquals(1,fallbackDns.get()); assertEquals(0,events.get())
        assertEquals(0,primary.dispatcher.runningCallsCount()); assertEquals(0,fallback.dispatcher.runningCallsCount())
    }
}
