package com.nuvio.tv.core.network

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class DnsService(val id: String, val url: String?, val bootstrap: List<String>) {
    SYSTEM("system", null, emptyList()),
    CLOUDFLARE("cloudflare", "https://cloudflare-dns.com/dns-query", listOf("1.1.1.1", "1.0.0.1")),
    GOOGLE("google", "https://dns.google/dns-query", listOf("8.8.8.8", "8.8.4.4")),
    QUAD9("quad9", "https://dns.quad9.net/dns-query", listOf("9.9.9.9", "149.112.112.112")),
    ADGUARD("adguard", "https://dns.adguard-dns.com/dns-query", listOf("94.140.14.14", "94.140.15.15")),
    CUSTOM("custom", null, emptyList());

    companion object {
        fun fromId(id: String?): DnsService = entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}

data class DnsChoice(val service: DnsService, val customUrl: String = "") {
    val url: HttpUrl?
        get() = when (service) {
            DnsService.SYSTEM -> null
            DnsService.CUSTOM -> parseDohUrl(customUrl)
            else -> service.url?.toHttpUrlOrNull()
        }
}

fun parseDohUrl(raw: String): HttpUrl? {
    val url = raw.trim().toHttpUrlOrNull() ?: return null
    return url.takeIf { it.isHttps && it.host.isNotEmpty() }
}

/** Names that only the home network knows, which a public DNS service cannot answer. */
fun isLocalName(hostname: String): Boolean {
    val host = hostname.trimEnd('.').lowercase()
    if (host.isEmpty() || '.' !in host || ':' in host) return true
    if (IPV4_LITERAL.matches(host)) return true
    return LOCAL_SUFFIXES.any { host.endsWith(it) }
}

private val IPV4_LITERAL = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
private val LOCAL_SUFFIXES = listOf(
    ".local", ".lan", ".home", ".home.arpa", ".internal", ".localdomain", ".localhost", ".arpa", ".corp", ".intranet",
    ".box", ".ts.net"
)

/**
 * Looks names up with a DNS-over-HTTPS service, one request per name at a time, keeps answers for a short while and
 * refreshes them in the background. When the service cannot be reached every lookup goes to [system] and a background
 * lookup checks the service again after each step of [backoffMs]; a real "no such name" answer is not retried with
 * [system], since that would bring back the answer the user is avoiding.
 */
internal class CachingDohDns(
    private val doh: Dns,
    private val system: Dns,
    private val now: () -> Long,
    private val background: (Runnable) -> Unit,
    private val onReachable: (Boolean) -> Unit = {},
    private val freshMs: Long = FRESH_MS,
    private val staleMs: Long = STALE_MS,
    private val backoffMs: List<Long> = BACKOFF_MS
) : Dns {
    private class Entry(val addresses: List<InetAddress>, val at: Long)

    private val cache = ConcurrentHashMap<String, Entry>()
    private val refreshing = ConcurrentHashMap.newKeySet<String>()
    private val locks = ConcurrentHashMap<String, Any>()
    private val probing = AtomicBoolean(false)
    private val state = Any()

    @Volatile
    private var failedRounds = 0

    @Volatile
    private var probeAt = Long.MIN_VALUE

    private val unreachable: Boolean
        get() = failedRounds > 0

    override fun lookup(hostname: String): List<InetAddress> {
        if (isLocalName(hostname)) return system.lookup(hostname)
        val key = hostname.trimEnd('.').lowercase()
        cache[key]?.let { entry ->
            val age = now() - entry.at
            if (age < freshMs) return entry.addresses
            if (age < staleMs && !unreachable) {
                refreshLater(key)
                return entry.addresses
            }
        }
        if (unreachable) {
            probeLater(key)
            return system.lookup(hostname)
        }
        synchronized(locks.computeIfAbsent(key) { Any() }) {
            cache[key]?.takeIf { now() - it.at < freshMs }?.let { return it.addresses }
            if (unreachable) return system.lookup(hostname)
            return try {
                fetch(key)
            } catch (e: UnknownHostException) {
                if (!e.isUnreachable()) throw e
                wentUnreachable()
                system.lookup(hostname)
            }
        }
    }

    private fun fetch(key: String): List<InetAddress> {
        val addresses = try {
            doh.lookup(key)
        } catch (e: UnknownHostException) {
            cache.remove(key)
            throw e
        }
        cache[key] = Entry(addresses, now())
        return addresses
    }

    private fun refreshLater(key: String) {
        if (!refreshing.add(key)) return
        background(Runnable {
            try {
                synchronized(locks.computeIfAbsent(key) { Any() }) {
                    if (!unreachable) {
                        try {
                            fetch(key)
                        } catch (e: UnknownHostException) {
                            if (e.isUnreachable()) wentUnreachable()
                        }
                    }
                }
            } finally {
                refreshing.remove(key)
            }
        })
    }

    private fun probeLater(key: String) {
        if (now() < probeAt || !probing.compareAndSet(false, true)) return
        background(Runnable {
            try {
                fetch(key)
                backReachable()
            } catch (e: UnknownHostException) {
                if (e.isUnreachable()) probeFailed() else backReachable()
            } finally {
                probing.set(false)
            }
        })
    }

    private fun wentUnreachable() {
        synchronized(state) {
            if (failedRounds > 0) return
            failedRounds = 1
            probeAt = now() + backoffMs.first()
        }
        onReachable(false)
    }

    private fun probeFailed() = synchronized(state) {
        failedRounds++
        probeAt = now() + backoffMs[minOf(failedRounds, backoffMs.size) - 1]
    }

    private fun backReachable() {
        synchronized(state) {
            if (failedRounds == 0) return
            failedRounds = 0
        }
        onReachable(true)
    }

    companion object {
        const val FRESH_MS = 60_000L
        const val STALE_MS = 300_000L
        val BACKOFF_MS = listOf(60_000L, 120_000L, 300_000L, 900_000L)
    }
}

/** Asks for IPv4 addresses only, and for both kinds when a name has no IPv4 address. */
internal class Ipv4FirstDoh(private val ipv4: Dns, private val both: Dns) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = try {
            ipv4.lookup(hostname)
        } catch (e: UnknownHostException) {
            if (e.isUnreachable()) throw e
            emptyList()
        }
        return addresses.ifEmpty { both.lookup(hostname) }
    }
}

/** OkHttp's DoH reports a failed call as UnknownHostException with the I/O error as its cause. */
internal fun UnknownHostException.isUnreachable(): Boolean {
    val cause = cause ?: return false
    return cause !is UnknownHostException
}

object ArcDns : Dns {
    private const val TAG = "ArcDns"
    private const val PREFS = "network_dns"
    private const val KEY_SERVICE = "service"
    private const val KEY_CUSTOM_URL = "custom_url"
    private val WARM_HOSTS = listOf("api.themoviedb.org", "image.tmdb.org", "v3-cinemeta.strem.io")

    @Volatile
    private var prefs: SharedPreferences? = null

    @Volatile
    private var active: Lazy<Dns>? = null

    private val _choice = MutableStateFlow(DnsChoice(DnsService.SYSTEM))
    val choice: StateFlow<DnsChoice> = _choice.asStateFlow()

    private val _reachable = MutableStateFlow(true)
    val reachable: StateFlow<Boolean> = _reachable.asStateFlow()

    private val changeListeners = CopyOnWriteArrayList<() -> Unit>()

    private val refresher by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "arc-dns-refresh").apply { isDaemon = true } }
    }

    private val dohClient by lazy {
        OkHttpClient.Builder()
            .dispatcher(Dispatcher().apply { maxRequestsPerHost = 32 })
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .callTimeout(2, TimeUnit.SECONDS)
            .build()
    }

    fun init(context: Context) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        apply(DnsChoice(DnsService.fromId(p.getString(KEY_SERVICE, null)), p.getString(KEY_CUSTOM_URL, null).orEmpty()))
    }

    fun set(choice: DnsChoice) {
        prefs?.edit()
            ?.putString(KEY_SERVICE, choice.service.id)
            ?.putString(KEY_CUSTOM_URL, choice.customUrl.trim())
            ?.apply()
        apply(choice)
        refresher.execute { changeListeners.forEach { runCatching { it() } } }
    }

    /** Runs [listener] after every change of service, for example to drop idle pooled connections. */
    fun onChange(listener: () -> Unit) {
        changeListeners += listener
    }

    override fun lookup(hostname: String): List<InetAddress> {
        val dns = active?.value ?: return Dns.SYSTEM.lookup(hostname)
        return try {
            dns.lookup(hostname)
        } catch (e: UnknownHostException) {
            if (!ServerTrust.isServerHost(hostname)) throw e
            Dns.SYSTEM.lookup(hostname)
        }
    }

    /** For home media servers: a name the chosen service does not know may still be known to the home router. */
    val withSystemRetry: Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = try {
            ArcDns.lookup(hostname)
        } catch (e: UnknownHostException) {
            if (active == null) throw e
            Dns.SYSTEM.lookup(hostname)
        }
    }

    /** Looks [hostname] up with [url] directly: no cache, no hand-over. Returns the time taken in ms. */
    fun probe(url: HttpUrl, hostname: String, bootstrap: List<String> = emptyList()): Long {
        val doh = buildDoh(url, bootstrap)
        val start = SystemClock.elapsedRealtime()
        doh.lookup(hostname)
        return SystemClock.elapsedRealtime() - start
    }

    fun probeCurrent(hostname: String): Long? {
        val current = _choice.value
        val url = current.url ?: return null
        return probe(url, hostname, current.service.bootstrap)
    }

    private fun apply(choice: DnsChoice) {
        _choice.value = choice
        _reachable.value = true
        val url = choice.url
        active = if (url == null) {
            null
        } else lazy {
            CachingDohDns(
                doh = buildDoh(url, choice.service.bootstrap),
                system = Dns.SYSTEM,
                now = SystemClock::elapsedRealtime,
                background = { refresher.execute(it) },
                onReachable = { ok ->
                    if (_reachable.value != ok) {
                        _reachable.value = ok
                        Log.w(TAG, if (ok) "dns service reachable again" else "dns service unreachable, using system lookup")
                    }
                }
            )
        }
        Log.i(TAG, "dns=${choice.service.id}")
        val warming = active ?: return
        refresher.execute { WARM_HOSTS.forEach { host -> runCatching { warming.value.lookup(host) } } }
    }

    private fun buildDoh(url: HttpUrl, bootstrap: List<String>): Dns {
        fun doh(includeIPv6: Boolean) = DnsOverHttps.Builder()
            .client(dohClient)
            .url(url)
            .includeIPv6(includeIPv6)
            .resolvePrivateAddresses(true)
            .apply {
                if (bootstrap.isNotEmpty()) bootstrapDnsHosts(bootstrap.map(InetAddress::getByName))
            }
            .build()
        return Ipv4FirstDoh(doh(includeIPv6 = false), doh(includeIPv6 = true))
    }
}
