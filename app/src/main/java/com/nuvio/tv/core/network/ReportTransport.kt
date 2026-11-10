package com.nuvio.tv.core.network

import android.content.Context
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.dnsoverhttps.DnsOverHttps
import okio.Buffer
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** The client that carries reports to Arc's relay: signed, compressed, and able to get past a DNS block. */
object ReportTransport {
    fun client(context: Context, base: OkHttpClient, signingKey: String): OkHttpClient {
        val installId = ReportInstallId(context)
        val bootstrap = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
        val dns = ReportDns(
            system = Dns.SYSTEM,
            fallbacks = listOf(DnsService.CLOUDFLARE, DnsService.GOOGLE).map { service ->
                DnsOverHttps.Builder()
                    .client(bootstrap)
                    .url(service.url!!.toHttpUrl())
                    .bootstrapDnsHosts(service.bootstrap.map { InetAddress.getByName(it) })
                    .build()
            }
        )
        return base.newBuilder()
            .cache(null)
            .dns(dns)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(ReportRetryInterceptor(dns))
            .addInterceptor(ReportSigningInterceptor(signingKey, installId::get))
            .build()
    }
}

/** A random id for this install, so the relay can limit reports per box. It says nothing about the box or viewer. */
class ReportInstallId(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("issue_reports", Context.MODE_PRIVATE)

    fun get(): String = prefs.getString(KEY, null) ?: UUID.randomUUID().toString().also {
        prefs.edit().putString(KEY, it).apply()
    }

    private companion object {
        const val KEY = "install_id"
    }
}

object ReportSigning {
    fun signature(key: String, timeMs: Long, method: String, path: String, install: String, body: ByteArray): String {
        val message = "$timeMs\n$method\n$path\n$install\n${sha256Hex(body)}"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8)).toHex()
    }

    private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

/**
 * Compresses and signs each report. A box whose clock is wrong is told the relay's time once, and the report is signed
 * again with that time.
 */
class ReportSigningInterceptor(
    private val key: String,
    private val installId: () -> String,
    private val now: () -> Long = System::currentTimeMillis
) : Interceptor {
    @Volatile
    private var clockOffsetMs = 0L

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val plain = original.body?.let { body -> Buffer().also { body.writeTo(it) }.readByteArray() } ?: ByteArray(0)
        val packed = gzip(plain)
        val first = chain.proceed(signed(original, packed))
        if (first.code != 401) return first
        val serverTime = first.header("x-arc-server-time")?.toLongOrNull() ?: return first
        first.close()
        clockOffsetMs = serverTime - now()
        return chain.proceed(signed(original, packed))
    }

    private fun signed(request: Request, packed: ByteArray): Request {
        val time = now() + clockOffsetMs
        val install = installId()
        val signature = ReportSigning.signature(key, time, request.method, request.url.encodedPath, install, packed)
        return request.newBuilder()
            .header("Content-Encoding", "gzip")
            .header("X-Arc-Install", install)
            .header("X-Arc-Time", time.toString())
            .header("X-Arc-Signature", signature)
            .method(request.method, packed.toRequestBody(request.body?.contentType()))
            .build()
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(bytes.size / 4 + 64)
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }
}

/**
 * Some networks block or redirect free hosting names at the DNS level. Answers that cannot be the relay (no answer,
 * a local or blank address) send the lookup to DNS-over-HTTPS services instead, and that choice is kept.
 */
class ReportDns(private val system: Dns, private val fallbacks: List<Dns>) : Dns {
    @Volatile
    var preferFallback = false

    override fun lookup(hostname: String): List<InetAddress> {
        if (!preferFallback) {
            val answer = runCatching { system.lookup(hostname) }.getOrNull()
            if (!answer.isNullOrEmpty() && answer.none { it.cannotBeRelay() }) return answer
            preferFallback = true
        }
        for (dns in fallbacks) {
            runCatching { dns.lookup(hostname) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return system.lookup(hostname)
    }

    private fun InetAddress.cannotBeRelay(): Boolean =
        isAnyLocalAddress || isLoopbackAddress || isSiteLocalAddress || isLinkLocalAddress
}

/** A report that cannot connect with the box's own DNS answer is tried once more through DNS-over-HTTPS. */
class ReportRetryInterceptor(private val dns: ReportDns) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        return try {
            chain.proceed(chain.request())
        } catch (e: IOException) {
            if (dns.preferFallback || chain.call().isCanceled()) throw e
            dns.preferFallback = true
            chain.proceed(chain.request())
        }
    }
}
