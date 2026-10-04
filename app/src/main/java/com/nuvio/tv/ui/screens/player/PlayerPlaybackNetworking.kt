package com.nuvio.tv.ui.screens.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.nuvio.tv.core.network.IPv4FirstDns
import com.nuvio.tv.core.network.ServerTrust
import com.nuvio.tv.core.network.withServerTrust
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

internal object PlayerPlaybackNetworking {
    private const val LOOPBACK_READ_TIMEOUT_SECONDS = 65L

    private val trustAllManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val playbackHostnameVerifier: HostnameVerifier = ServerTrust.uncheckedClientVerifier

    private val sslContext: SSLContext by lazy {
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
        }
    }

    /**
     * Fallback OkHttpClient equipped with trust-all SSL configuration for self-signed
     * or untrusted local media servers (e.g. self-signed WebDAV / Plex / Jellyfin).
     */
    internal val trustAllPlaybackHttpClient: OkHttpClient by lazy {
        val dispatcher = okhttp3.Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 32
        }
        OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .dns(IPv4FirstDns())
            .eventListenerFactory(PlaybackConnectionEvents)
            .sslSocketFactory(sslContext.socketFactory, trustAllManager)
            .hostnameVerifier(playbackHostnameVerifier)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Primary OkHttpClient using standard system SSL certificates and full SNI support.
     * Includes an automatic fallback to [trustAllPlaybackHttpClient] if an [SSLException]
     * occurs on self-signed local media servers.
     */
    private val playbackSslFallbackInterceptor = okhttp3.Interceptor { chain ->
        try {
            chain.proceed(chain.request())
        } catch (e: SSLException) {
            if (ServerTrust.isServerHost(chain.request().url.host)) throw e
            trustAllPlaybackHttpClient.newCall(chain.request()).execute()
        }
    }

    internal val playbackHttpClient: OkHttpClient by lazy {
        val dispatcher = okhttp3.Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 32
        }
        OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .dns(IPv4FirstDns())
            // Attached HERE because OkHttpClient.Builder(client) copies
            // eventListenerFactory, so every newBuilder() derivative inherits
            // it -- the prewarm client, createHttpDataSourceFactory's client
            // and PlayerMediaSourceFactory's chunk-session client -- and
            // applyNetworkOptimizations sets no listener, so nothing
            // overwrites it. One attachment covers all three startup opens.
            .eventListenerFactory(PlaybackConnectionEvents)
            .withServerTrust()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .addInterceptor(playbackSslFallbackInterceptor)
            .build()
            .also { ServerTrust.closeConnectionsOnWithdrawal(it.connectionPool) }
    }

    /** Diagnostic owners never borrow playback's dispatcher, pool or event listener. */
    internal fun createContentLengthProbeCallFactory(
        primary: OkHttpClient = playbackHttpClient,
        fallback: () -> OkHttpClient = { trustAllPlaybackHttpClient }
    ): okhttp3.Call.Factory = createDiagnosticCallFactory(primary, fallback, 5_000, 2_000, "Nuvio-length-probe")

    internal fun createStreamSpeedTestCallFactory(
        primary: OkHttpClient = playbackHttpClient,
        fallback: () -> OkHttpClient = { trustAllPlaybackHttpClient }
    ): okhttp3.Call.Factory = createDiagnosticCallFactory(primary, fallback, 15_000, 3_000, "Nuvio-stream-test")

    private fun createDiagnosticCallFactory(
        primary: OkHttpClient,
        fallback: () -> OkHttpClient,
        totalMs: Long,
        ioMs: Long,
        threadName: String
    ): okhttp3.Call.Factory {
        // A blocked platform resolver may outlive cancellation. One worker and one queued
        // follow-up bound that resource; Each diagnostic owner holds admission until callbacks end.
        val executor = java.util.concurrent.ThreadPoolExecutor(
            0, 1, 30L, TimeUnit.SECONDS, java.util.concurrent.ArrayBlockingQueue<Runnable>(1),
            java.util.concurrent.ThreadFactory { task ->
                Thread(task, threadName).apply { isDaemon = true }
            }
        )
        val dispatcher = okhttp3.Dispatcher(executor).apply {
            maxRequests = 1
            maxRequestsPerHost = 1
        }
        val pool = okhttp3.ConnectionPool(0, 1L, TimeUnit.SECONDS)
        fun isolated(client: OkHttpClient) = client.newBuilder()
            .apply { interceptors().clear(); networkInterceptors().clear() }
            .dispatcher(dispatcher).connectionPool(pool).cache(null)
            .eventListener(okhttp3.EventListener.NONE)
            .retryOnConnectionFailure(false)
            .callTimeout(totalMs, TimeUnit.MILLISECONDS)
            .connectTimeout(ioMs, TimeUnit.MILLISECONDS).readTimeout(ioMs, TimeUnit.MILLISECONDS)
            .writeTimeout(ioMs, TimeUnit.MILLISECONDS).build()
        // Retain playback's existing TLS compatibility policy, with both calls owned by
        // the same cancellable wrapper instead of a blocking fallback interceptor.
        return PlaybackPrewarmCallFactory(isolated(primary), {
            isolated(fallback())
        })
    }

    private val prewarmCoordinator = PlaybackPrewarmCoordinator(
        onSelection = PrefetchWindowStore::clear,
        onEvent = { event -> android.util.Log.i("PlaybackPrewarm", "PREWARM_RESULT $event") },
        publish = publish@{ request, resolvedUrl, window, head ->
            if (!canReusePrewarmFor(request.url.toString(), resolvedUrl)) return@publish false
            val entry = ParallelRangeDataSource.BootstrapCacheEntry(
                requestUri = android.net.Uri.parse(request.url.toString()),
                startPosition = window.start,
                resolvedUri = android.net.Uri.parse(resolvedUrl),
                openLength = if (head) window.total else window.bytes.size.toLong(),
                totalFileLength = window.total,
                bootstrapData = window.bytes,
                bootstrapSize = window.bytes.size,
                createdAtUptimeMs = android.os.SystemClock.uptimeMillis(),
                prewarmIdentity = PlaybackPrewarmIdentity.from(
                    request.url.toString(), request.headers.toMap()
                )
            )
            if (head) PrefetchWindowStore.putHead(entry) else PrefetchWindowStore.putTail(entry)
            true
        }
    )

    /** Selected playback only: one bounded head/suffix pair, with no application-level warm retries. */
    fun prewarmSelectedPlayback(url: String?, headers: Map<String, String>?, enableHttp2: Boolean) {
        val request = prewarmRequest(url, headers) ?: return
        if (!canReusePrewarmFor(request.url.toString())) return
        prewarmCoordinator.start(request) {
            // Re-read effective settings per selection; keep the playback pool, DNS and event listener.
            val primary = playbackHttpClient.newBuilder()
                // Warm-up owns its fallback call so cancellation also stops its body read.
                .apply { interceptors().remove(playbackSslFallbackInterceptor) }
                .let { NuvioExoPlayerPerformanceHelper.applyNetworkOptimizations(it, enableHttp2) }
                .callTimeout(15, TimeUnit.SECONDS)
                .build()
                .also { logPoolIdentity("prewarm", it) }
            PlaybackPrewarmCallFactory(primary, fallback = {
                trustAllPlaybackHttpClient.newBuilder()
                    .let { NuvioExoPlayerPerformanceHelper.applyNetworkOptimizations(it, enableHttp2) }
                    .callTimeout(15, TimeUnit.SECONDS)
                    .build()
                    .also { logPoolIdentity("prewarm-tls-fallback", it) }
            })
        }
    }

    fun cancelSelectedPlaybackPrewarm(url: String, headers: Map<String, String>) {
        prewarmRequest(url, headers)?.let(prewarmCoordinator::cancel)
    }

    // The chunk client also uses the mutable extension cookie jar. Until its cookie version can
    // be bound to a warm entry, fall through to normal playback for these authenticated sources.
    internal fun canReusePrewarmFor(url: String, resolvedUrl: String = url): Boolean =
        listOf(url, resolvedUrl).all { value ->
            val parsed = value.toHttpUrlOrNull()
            parsed != null && com.nuvio.tv.NuvioApplication.extensionCookieJar.loadForRequest(parsed).isEmpty()
        }

    internal fun prewarmRequest(url: String?, headers: Map<String, String>?): okhttp3.Request? {
        return try {
            val normalized = PlayerMediaSourceFactory.normalizePlaybackRequest(url?.trim().orEmpty(), headers)
            val builder = okhttp3.Request.Builder().url(normalized.url)
                .header("User-Agent", PlayerMediaSourceFactory.DEFAULT_USER_AGENT)
            normalized.headers.forEach { (name, value) -> builder.header(name, value) }
            // Optional warmed bytes must describe the same identity representation used by range playback.
            builder.header("Accept-Encoding", "identity").header("Icy-MetaData", "1")
                .removeHeader("Range").build()
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /**
     * Pool sharing only works if the prewarm client and the client the
     * probe uses hold the SAME ConnectionPool instance. The pool is a fixed
     * singleton val, so matching ids are the EXPECTED steady state; a
     * mismatch here means the invariant is broken.
     */
    private fun logPoolIdentity(label: String, client: OkHttpClient) {
        val poolId = System.identityHashCode(client.connectionPool)
        val protos = client.protocols.joinToString(",")
        android.util.Log.i(
            "NuvioNet",
            "POOL_ID client=$label pool=$poolId protocols=$protos " +
                "maxIdle=${NuvioExoPlayerPerformanceHelper.NUVIO_SHARED_POOL_MAX_IDLE} " +
                "demand=${NuvioExoPlayerPerformanceHelper.connectionPoolSize}"
        )
    }

    fun createHttpClient(
        defaultHeaders: Map<String, String> = emptyMap(),
        useLongReadTimeout: Boolean = false
    ): OkHttpClient {
        val builder = playbackHttpClient.newBuilder()
        if (useLongReadTimeout) {
            builder.readTimeout(LOOPBACK_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
        if (defaultHeaders.any { it.key.equals("Authorization", ignoreCase = true) }) {
            // OkHttp strips the Authorization header on cross-host redirects.
            // WebDAV servers behind reverse proxies commonly redirect to a
            // different host/port, causing auth to be lost. A network
            // interceptor ensures the header is always present on every
            // outgoing request — same behavior as mpv/curl.
            val authValue = defaultHeaders.entries
                .first { it.key.equals("Authorization", ignoreCase = true) }
                .value
            builder.addNetworkInterceptor { chain ->
                val request = chain.request()
                if (request.header("Authorization") == null) {
                    chain.proceed(
                        request.newBuilder()
                            .header("Authorization", authValue)
                            .build()
                    )
                } else {
                    chain.proceed(request)
                }
            }
        }
        return builder
            .let { NuvioExoPlayerPerformanceHelper.applyNetworkOptimizations(it) }
            .build()
            .also { logPoolIdentity("datasource", it) }
    }

    @UnstableApi
    fun createHttpDataSourceFactory(
        defaultHeaders: Map<String, String> = emptyMap(),
        useLongReadTimeout: Boolean = false
    ): DataSource.Factory {
        val client = createHttpClient(defaultHeaders, useLongReadTimeout)
        val httpFactory = OkHttpDataSource.Factory(client).apply {
            setDefaultRequestProperties(defaultHeaders)
            if (defaultHeaders.none { it.key.equals("User-Agent", ignoreCase = true) }) {
                setUserAgent(PlayerMediaSourceFactory.DEFAULT_USER_AGENT)
            }
        }
        return LoggingDataSourceFactory(httpFactory, "HTTP")
    }

    @UnstableApi
    fun createDataSourceFactory(
        context: android.content.Context,
        defaultHeaders: Map<String, String> = emptyMap()
    ): DataSource.Factory {
        return DefaultDataSource.Factory(context, createHttpDataSourceFactory(defaultHeaders))
    }

    fun openConnection(
        url: String,
        headers: Map<String, String>,
        method: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        range: String? = null
    ): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            requestMethod = method
            setRequestProperty("User-Agent", headers["User-Agent"] ?: PlayerMediaSourceFactory.DEFAULT_USER_AGENT)
            headers.forEach { (key, value) ->
                if (key.equals("Range", ignoreCase = true)) return@forEach
                if (key.equals("User-Agent", ignoreCase = true)) return@forEach
                setRequestProperty(key, value)
            }
            range?.let { setRequestProperty("Range", it) }
        }
    }
}
