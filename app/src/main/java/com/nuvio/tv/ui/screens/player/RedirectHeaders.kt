package com.nuvio.tv.ui.screens.player

import okhttp3.HttpUrl
import okhttp3.OkHttpClient

/**
 * Puts the stream's headers back on every hop of a redirect chain (OkHttp drops them when the host or port
 * changes). Logins and tokens go only to the host and port the request started on (or the same host moving from
 * http to https), never from https down to http.
 */
internal object RedirectHeaders {
    private class Origin(val url: HttpUrl)

    fun OkHttpClient.Builder.keepHeadersOnRedirect(headers: Map<String, String>): OkHttpClient.Builder {
        if (headers.isEmpty()) return this
        addInterceptor { chain ->
            val request = chain.request()
            if (request.tag(Origin::class.java) != null) {
                chain.proceed(request)
            } else {
                chain.proceed(request.newBuilder().tag(Origin::class.java, Origin(request.url)).build())
            }
        }
        addNetworkInterceptor { chain ->
            val request = chain.request()
            val origin = request.tag(Origin::class.java)?.url ?: request.url
            val sendAuth = authorizationAllowed(origin, request.url)
            val next = request.newBuilder()
            for ((key, value) in headers) {
                if (isCredential(key) && !sendAuth) continue
                next.header(key, value)
            }
            if (!sendAuth) CREDENTIAL_HEADERS.forEach(next::removeHeader)
            chain.proceed(next.build())
        }
        return this
    }

    fun authorizationAllowed(origin: HttpUrl, target: HttpUrl): Boolean {
        if (!origin.host.equals(target.host, ignoreCase = true)) return false
        if (origin.isHttps && !target.isHttps) return false
        return origin.port == target.port || (!origin.isHttps && target.isHttps)
    }

    fun isCredential(key: String) = CREDENTIAL_HEADERS.any { it.equals(key, ignoreCase = true) }

    private val CREDENTIAL_HEADERS = listOf(
        "Authorization",
        "Proxy-Authorization",
        "Cookie",
        "X-Plex-Token",
        "X-Emby-Token",
        "X-Emby-Authorization",
        "X-MediaBrowser-Token",
        "X-Profile-Token"
    )
}
