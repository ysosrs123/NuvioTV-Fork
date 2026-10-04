package com.nuvio.tv.core.network

import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.Certificate
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.Executor
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.internal.tls.OkHostnameVerifier

/**
 * Certificates the user accepted for their own media servers, one SHA-256 fingerprint per host.
 * Hosts without an entry get exactly the platform's normal checks.
 */
object ServerTrust {
    @Volatile
    private var pins: Map<String, Set<String>> = emptyMap()

    @Volatile
    private var pending: Map<String, Set<String>> = emptyMap()

    @Volatile
    private var serverHosts: Set<String> = emptySet()

    private val lock = Any()

    private val pools: MutableSet<ConnectionPool> = Collections.newSetFromMap(WeakHashMap())

    internal var closer: Executor = Executor { task -> Thread(task, "server-trust-close").apply { isDaemon = true }.start() }

    fun update(pinsByHost: Map<String, Set<String>>, hosts: Set<String>) = synchronized(lock) {
        val updatedPins = pinsByHost.mapKeys { normalizeHost(it.key) }
        val updatedHosts = hosts.mapTo(mutableSetOf(), ::normalizeHost)
        val withdrawn = pins.any { (host, old) -> !updatedPins[host].orEmpty().containsAll(old) } ||
            !updatedHosts.containsAll(serverHosts)
        pins = updatedPins
        serverHosts = updatedHosts
        if (withdrawn) closePooledConnections()
    }

    /** Idle connections in [pool] are closed whenever a certificate or server stops being trusted. */
    fun closeConnectionsOnWithdrawal(pool: ConnectionPool) {
        synchronized(lock) { pools += pool }
    }

    private fun closePooledConnections() {
        val targets = pools.toList()
        if (targets.isNotEmpty()) closer.execute { targets.forEach(ConnectionPool::evictAll) }
    }

    /** Accepts [fingerprint] for [host] for a sign-in the user just approved, until [clearPending]. */
    fun allow(host: String, fingerprint: String) = synchronized(lock) {
        val key = normalizeHost(host)
        pending = pending + (key to (pending[key].orEmpty() + fingerprint.lowercase(Locale.ROOT)))
    }

    fun clearPending() = synchronized(lock) {
        val dropped = pending.any { (host, accepted) -> !pins[host].orEmpty().containsAll(accepted) }
        pending = emptyMap()
        if (dropped) closePooledConnections()
    }

    fun isServerHost(host: String): Boolean = normalizeHost(host) in serverHosts

    fun isPinned(host: String, certificate: Certificate?): Boolean {
        val fingerprint = certificate?.let(::sha256Fingerprint) ?: return false
        val key = normalizeHost(host)
        return fingerprint in pins[key].orEmpty() || fingerprint in pending[key].orEmpty()
    }

    private fun isPinnedAnywhere(certificate: Certificate): Boolean {
        val fingerprint = sha256Fingerprint(certificate)
        return pins.values.any { fingerprint in it } || pending.values.any { fingerprint in it }
    }

    /** For clients that skip certificate checks: a configured media server's host still gets a real check. */
    val uncheckedClientVerifier: HostnameVerifier = HostnameVerifier { host, session ->
        !isServerHost(host) || verifiesServerHost(host, session, OkHostnameVerifier)
    }

    internal fun verifiesServerHost(
        host: String,
        session: SSLSession,
        names: HostnameVerifier,
        trusted: (Array<X509Certificate>) -> Boolean = ::authorityTrusts
    ): Boolean {
        val chain = session.certificateChain() ?: return false
        return isPinned(host, chain.first()) || (trusted(chain) && names.verify(host, session))
    }

    internal fun trustManager(platform: X509TrustManager, userAuthorities: X509TrustManager? = null): X509TrustManager =
        object : X509ExtendedTrustManager() {
            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
                platform.checkClientTrusted(chain, authType)

            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String, socket: Socket) =
                platform.checkClientTrusted(chain, authType)

            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String, engine: SSLEngine) =
                platform.checkClientTrusted(chain, authType)

            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) =
                verify(chain, authType, host = null) { platform.checkServerTrusted(chain, authType) }

            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String, socket: Socket) {
                val host = (socket as? SSLSocket)?.handshakeSession?.peerHost
                verify(chain, authType, host) {
                    (platform as? X509ExtendedTrustManager)?.checkServerTrusted(chain, authType, socket)
                        ?: platform.checkServerTrusted(chain, authType)
                }
            }

            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String, engine: SSLEngine) {
                val host = engine.handshakeSession?.peerHost ?: engine.peerHost
                verify(chain, authType, host) {
                    (platform as? X509ExtendedTrustManager)?.checkServerTrusted(chain, authType, engine)
                        ?: platform.checkServerTrusted(chain, authType)
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = platform.acceptedIssuers

            private fun verify(
                chain: Array<out X509Certificate>,
                authType: String,
                host: String?,
                platformCheck: () -> Unit
            ) {
                try {
                    platformCheck()
                } catch (error: CertificateException) {
                    val leaf = chain.firstOrNull() ?: throw error
                    if (host == null) {
                        if (isPinnedAnywhere(leaf)) return
                        throw error
                    }
                    if (isPinned(host, leaf)) return
                    val trustedByUser = userAuthorities != null && isServerHost(host) &&
                        runCatching { userAuthorities.checkServerTrusted(chain, authType) }.isSuccess
                    if (!trustedByUser) throw error
                }
            }
        }

    /** A certificate pinned for one host never counts for another, even when it names that host too. */
    internal fun hostnameVerifier(
        default: HostnameVerifier,
        trusted: (Array<X509Certificate>) -> Boolean = ::authorityTrusts
    ): HostnameVerifier = HostnameVerifier { host, session ->
        val leaf = session.leafCertificate()
        when {
            isPinned(host, leaf) -> true
            leaf != null && isPinnedAnywhere(leaf) -> false
            isServerHost(host) -> verifiesServerHost(host, session, default, trusted)
            else -> default.verify(host, session)
        }
    }

    private val platformTrustManager: X509TrustManager by lazy { trustManagerFor(null) }

    private val userAuthorityTrustManager: X509TrustManager? by lazy {
        runCatching {
            trustManagerFor(KeyStore.getInstance("AndroidCAStore").apply { load(null) })
        }.getOrNull()
    }

    private fun trustManagerFor(keyStore: KeyStore?): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(keyStore)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    val x509TrustManager: X509TrustManager by lazy { trustManager(platformTrustManager, userAuthorityTrustManager) }

    val sslSocketFactory: SSLSocketFactory by lazy {
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(x509TrustManager), null) }.socketFactory
    }

    val verifier: HostnameVerifier by lazy { hostnameVerifier(OkHostnameVerifier) }

    internal fun platformTrusts(chain: Array<X509Certificate>): Boolean =
        runCatching { platformTrustManager.checkServerTrusted(chain, chain.first().publicKey.algorithm) }.isSuccess

    private fun authorityTrusts(chain: Array<X509Certificate>): Boolean =
        platformTrusts(chain) || userAuthorityTrustManager?.let { authorities ->
            runCatching { authorities.checkServerTrusted(chain, chain.first().publicKey.algorithm) }.isSuccess
        } == true
}

fun OkHttpClient.Builder.withServerTrust(): OkHttpClient.Builder =
    sslSocketFactory(ServerTrust.sslSocketFactory, ServerTrust.x509TrustManager)
        .hostnameVerifier(ServerTrust.verifier)

fun sha256Fingerprint(certificate: Certificate): String =
    MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it) }

/** Upper-case pairs separated by colons, as browsers and openssl print them. */
fun displayFingerprint(fingerprint: String): String =
    fingerprint.uppercase(Locale.ROOT).chunked(2).joinToString(":")

internal fun normalizeHost(host: String): String = host.trim().trim('[', ']').lowercase(Locale.ROOT)

private fun SSLSession.leafCertificate(): Certificate? = runCatching { peerCertificates.firstOrNull() }.getOrNull()

private fun SSLSession.certificateChain(): Array<X509Certificate>? =
    runCatching { peerCertificates.filterIsInstance<X509Certificate>().toTypedArray() }.getOrNull()?.takeIf { it.isNotEmpty() }
