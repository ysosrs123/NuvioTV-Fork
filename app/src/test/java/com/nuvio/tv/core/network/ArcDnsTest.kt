package com.nuvio.tv.core.network

import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ArcDnsTest {
    private class FakeDns(var answer: (String) -> List<InetAddress>) : Dns {
        val asked: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override fun lookup(hostname: String): List<InetAddress> {
            asked += hostname
            return answer(hostname)
        }
    }

    private fun ip(text: String) = InetAddress.getByName(text)

    private fun unreachable(host: String) = UnknownHostException(host).apply { initCause(SocketTimeoutException("timeout")) }

    private var clock = 0L
    private val queued = mutableListOf<Runnable>()
    private val reachability = mutableListOf<Boolean>()

    private fun resolver(doh: Dns, system: Dns) = CachingDohDns(
        doh = doh,
        system = system,
        now = { clock },
        background = { queued += it },
        onReachable = { reachability += it }
    )

    @Test
    fun localNamesStayOnTheBox() {
        val doh = FakeDns { listOf(ip("1.2.3.4")) }
        val system = FakeDns { listOf(ip("192.168.1.50")) }
        val dns = resolver(doh, system)
        for (host in listOf("nas", "nas.lan", "jellyfin.local", "box.home.arpa", "printer.internal", "192.168.1.50", "fe80::1", "nas.fritz.box", "jellyfin.tail1234.ts.net")) {
            dns.lookup(host)
        }
        assertTrue(doh.asked.isEmpty())
        assertEquals(9, system.asked.size)
        assertFalse(isLocalName("api.themoviedb.org"))
        assertFalse(isLocalName("homestead.example.com"))
    }

    @Test
    fun freshAnswersComeFromTheCache() {
        val doh = FakeDns { listOf(ip("1.2.3.4")) }
        val dns = resolver(doh, FakeDns { fail(); emptyList() })
        assertEquals(listOf(ip("1.2.3.4")), dns.lookup("api.themoviedb.org"))
        clock += CachingDohDns.FRESH_MS - 1
        assertEquals(listOf(ip("1.2.3.4")), dns.lookup("API.themoviedb.org."))
        assertEquals(1, doh.asked.size)
        assertTrue(queued.isEmpty())
    }

    @Test
    fun staleAnswerIsServedWhileItRefreshesInTheBackground() {
        var answer = "1.2.3.4"
        val doh = FakeDns { listOf(ip(answer)) }
        val dns = resolver(doh, FakeDns { fail(); emptyList() })
        dns.lookup("image.tmdb.org")
        clock += CachingDohDns.FRESH_MS
        answer = "5.6.7.8"
        assertEquals(listOf(ip("1.2.3.4")), dns.lookup("image.tmdb.org"))
        dns.lookup("image.tmdb.org")
        assertEquals(1, queued.size)
        queued.removeAt(0).run()
        assertEquals(listOf(ip("5.6.7.8")), dns.lookup("image.tmdb.org"))
        assertEquals(2, doh.asked.size)
    }

    @Test
    fun answerOlderThanTheStaleLimitIsLookedUpAgain() {
        val doh = FakeDns { listOf(ip("1.2.3.4")) }
        val dns = resolver(doh, FakeDns { fail(); emptyList() })
        dns.lookup("api.torbox.app")
        clock += CachingDohDns.STALE_MS
        dns.lookup("api.torbox.app")
        assertEquals(2, doh.asked.size)
        assertTrue(queued.isEmpty())
    }

    @Test
    fun unreachableServiceHandsOverToTheBoxUntilABackgroundCheckSucceeds() {
        var up = false
        val doh = FakeDns { host -> if (up) listOf(ip("1.2.3.4")) else throw unreachable(host) }
        val system = FakeDns { listOf(ip("9.9.9.9")) }
        val dns = resolver(doh, system)
        assertEquals(listOf(ip("9.9.9.9")), dns.lookup("api.themoviedb.org"))
        assertEquals(listOf(ip("9.9.9.9")), dns.lookup("v3-cinemeta.strem.io"))
        assertEquals(1, doh.asked.size)
        assertEquals(listOf(false), reachability)
        assertTrue(queued.isEmpty())

        up = true
        clock += CachingDohDns.BACKOFF_MS[0]
        assertEquals(listOf(ip("9.9.9.9")), dns.lookup("api.themoviedb.org"))
        assertEquals(listOf(ip("9.9.9.9")), dns.lookup("image.tmdb.org"))
        assertEquals(1, queued.size)
        queued.removeAt(0).run()
        assertEquals(listOf(false, true), reachability)
        assertEquals(listOf(ip("1.2.3.4")), dns.lookup("api.themoviedb.org"))
        assertEquals(4, system.asked.size)
    }

    @Test
    fun failedChecksWaitLongerEachTime() {
        val doh = FakeDns { host -> throw unreachable(host) }
        val dns = resolver(doh, FakeDns { listOf(ip("9.9.9.9")) })
        dns.lookup("api.themoviedb.org")
        for (wait in listOf(60_000L, 120_000L, 300_000L, 900_000L, 900_000L)) {
            clock += wait - 1
            dns.lookup("api.themoviedb.org")
            assertTrue(queued.isEmpty())
            clock += 1
            dns.lookup("api.themoviedb.org")
            assertEquals(1, queued.size)
            queued.removeAt(0).run()
        }
        assertEquals(6, doh.asked.size)
        assertEquals(listOf(false), reachability)
    }

    @Test
    fun staleAnswerIsDroppedWhenItsRefreshFails() {
        var fail = false
        val doh = FakeDns { host -> if (fail) throw UnknownHostException("$host: NXDOMAIN") else listOf(ip("1.2.3.4")) }
        val dns = resolver(doh, FakeDns { fail(); emptyList() })
        dns.lookup("cdn.example.com")
        clock += CachingDohDns.FRESH_MS
        fail = true
        assertEquals(listOf(ip("1.2.3.4")), dns.lookup("cdn.example.com"))
        queued.removeAt(0).run()
        try {
            dns.lookup("cdn.example.com")
            fail()
        } catch (_: UnknownHostException) {
        }
        assertEquals(3, doh.asked.size)
    }

    @Test
    fun asksForIpv4OnlyAndForBothWhenThereIsNone() {
        val ipv4 = FakeDns { host -> if (host == "v6only.example.com") throw UnknownHostException(host) else listOf(ip("1.2.3.4")) }
        val both = FakeDns { listOf(ip("2606:4700::1")) }
        val dns = Ipv4FirstDoh(ipv4, both)
        assertEquals(listOf(ip("1.2.3.4")), dns.lookup("api.themoviedb.org"))
        assertTrue(both.asked.isEmpty())
        assertEquals(listOf(ip("2606:4700::1")), dns.lookup("v6only.example.com"))
        assertEquals(listOf("v6only.example.com"), both.asked)
        try {
            Ipv4FirstDoh(FakeDns { throw unreachable(it) }, both).lookup("api.themoviedb.org")
            fail()
        } catch (e: UnknownHostException) {
            assertTrue(e.isUnreachable())
        }
        assertEquals(1, both.asked.size)
    }

    private fun <T> together(count: Int, block: () -> T): List<T> {
        val pool = Executors.newFixedThreadPool(count)
        val start = CountDownLatch(1)
        val results = (1..count).map { pool.submit<T> { start.await(); block() } }
        start.countDown()
        return results.map { it.get(5, TimeUnit.SECONDS) }.also { pool.shutdown() }
    }

    @Test
    fun lookupsOfOneNameAtOnceShareOneRequest() {
        val doh = FakeDns { Thread.sleep(200); listOf(ip("1.2.3.4")) }
        val dns = resolver(doh, FakeDns { fail(); emptyList() })
        val answers = together(8) { dns.lookup("api.themoviedb.org") }
        assertTrue(answers.all { it == listOf(ip("1.2.3.4")) })
        assertEquals(1, doh.asked.size)
    }

    @Test
    fun waitingLookupsGoStraightToTheBoxOnceTheServiceIsUnreachable() {
        val doh = FakeDns { host -> Thread.sleep(200); throw unreachable(host) }
        val system = FakeDns { listOf(ip("9.9.9.9")) }
        val dns = resolver(doh, system)
        val answers = together(8) { dns.lookup("v3-cinemeta.strem.io") }
        assertTrue(answers.all { it == listOf(ip("9.9.9.9")) })
        assertEquals(1, doh.asked.size)
        assertEquals(8, system.asked.size)
    }

    @Test
    fun noSuchNameIsNotRetriedWithTheBox() {
        val doh = FakeDns { host -> throw UnknownHostException("$host: NXDOMAIN") }
        val system = FakeDns { listOf(ip("10.10.10.10")) }
        val dns = resolver(doh, system)
        try {
            dns.lookup("api.themoviedb.org")
            fail()
        } catch (_: UnknownHostException) {
        }
        assertTrue(system.asked.isEmpty())
        assertTrue(reachability.isEmpty())
    }

    @Test
    fun okHttpFailureShapesAreTold() {
        assertTrue(unreachable("a").isUnreachable())
        assertTrue(UnknownHostException("a").apply { initCause(IOException("HTTP 503")) }.isUnreachable())
        assertFalse(UnknownHostException("a: NXDOMAIN").isUnreachable())
        assertFalse(UnknownHostException("a").apply { initCause(UnknownHostException("a: SERVFAIL")) }.isUnreachable())
    }

    @Test
    fun choicesAndAddresses() {
        assertEquals(DnsService.SYSTEM, DnsService.fromId(null))
        assertEquals(DnsService.SYSTEM, DnsService.fromId("nope"))
        assertEquals(DnsService.QUAD9, DnsService.fromId("quad9"))
        assertNull(DnsChoice(DnsService.SYSTEM).url)
        assertEquals("cloudflare-dns.com", DnsChoice(DnsService.CLOUDFLARE).url?.host)
        assertEquals("dns.nextdns.io", DnsChoice(DnsService.CUSTOM, " https://dns.nextdns.io/abc123 ").url?.host)
        assertNull(DnsChoice(DnsService.CUSTOM, "http://dns.example.com/dns-query").url)
        assertNull(DnsChoice(DnsService.CUSTOM, "not an address").url)
        assertNull(DnsChoice(DnsService.CUSTOM, "").url)
        DnsService.entries.filter { it.url != null }.forEach { assertEquals(it.name, 2, it.bootstrap.size) }
    }

    @Test
    fun ipv4StaysFirst() {
        val dns = IPv4FirstDns(FakeDns { listOf(ip("2606:4700::1"), ip("1.1.1.1")) })
        assertEquals(ip("1.1.1.1"), dns.lookup("example.com").first())
    }
}
