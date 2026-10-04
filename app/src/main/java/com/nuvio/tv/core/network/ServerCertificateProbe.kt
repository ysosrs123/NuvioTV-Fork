package com.nuvio.tv.core.network

import java.net.InetSocketAddress
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.internal.tls.OkHostnameVerifier

enum class CertificateProblem {
    UNTRUSTED,
    NAME_MISMATCH,
    EXPIRED
}

data class ServerCertificateInfo(
    val host: String,
    val fingerprint: String,
    val subject: String,
    val issuer: String,
    val validFromMs: Long,
    val validUntilMs: Long,
    val problem: CertificateProblem
)

/**
 * Reads the certificate a server presents without sending it anything: the handshake is stopped as soon as
 * the certificate has arrived, so no request, token or password can travel on this connection.
 */
object ServerCertificateProbe {
    private const val TIMEOUT_MS = 5_000

    suspend fun probe(address: String): ServerCertificateInfo? = withContext(Dispatchers.IO) {
        val url = address.toHttpUrlOrNull()?.takeIf { it.isHttps } ?: return@withContext null
        val chain = runCatching { presentedChain(url.host, url.port) }.getOrNull() ?: return@withContext null
        describe(url.host, chain, System.currentTimeMillis())
    }

    internal fun describe(host: String, chain: Array<X509Certificate>, nowMs: Long): ServerCertificateInfo? {
        val leaf = chain.firstOrNull() ?: return null
        val platformTrusted = ServerTrust.platformTrusts(chain)
        val nameMatches = OkHostnameVerifier.verify(host, leaf)
        val problem = when {
            nowMs > leaf.notAfter.time || nowMs < leaf.notBefore.time -> CertificateProblem.EXPIRED
            platformTrusted && nameMatches -> return null
            platformTrusted -> CertificateProblem.NAME_MISMATCH
            else -> CertificateProblem.UNTRUSTED
        }
        return ServerCertificateInfo(
            host = normalizeHost(host),
            fingerprint = sha256Fingerprint(leaf),
            subject = leaf.subjectX500Principal.name,
            issuer = leaf.issuerX500Principal.name,
            validFromMs = leaf.notBefore.time,
            validUntilMs = leaf.notAfter.time,
            problem = problem
        )
    }

    private fun presentedChain(host: String, port: Int): Array<X509Certificate>? {
        var recorded: Array<X509Certificate>? = null
        val recorder = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = Unit

            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
                recorded = arrayOf(*chain)
                throw CertificateException("Certificate recorded")
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(recorder), null) }
        val socket = context.socketFactory.createSocket() as SSLSocket
        socket.use {
            it.soTimeout = TIMEOUT_MS
            it.connect(InetSocketAddress(host, port), TIMEOUT_MS)
            if (!host.any { c -> c == ':' } && !host.all { c -> c.isDigit() || c == '.' }) {
                it.sslParameters = it.sslParameters.apply { serverNames = listOf(SNIHostName(host)) }
            }
            runCatching { it.startHandshake() }
        }
        return recorded
    }
}
