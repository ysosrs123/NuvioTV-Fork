package com.nuvio.tv.data.mediaserver.plex

import com.nuvio.tv.data.mediaserver.ServerRepository
import java.net.URLDecoder
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Plex artwork needs the sign-in token. Links the app keeps (rows, Continue Watching, synced history) carry only a mark
 * in their fragment naming the connection; the token is added when the picture is downloaded.
 */
object PlexArtworkLink {
    private const val MARK = "nuvio-plex="

    /** A resized copy of [path] (a server path such as /library/metadata/1/thumb/2) through the server's picture service. */
    fun picture(address: String, connectionId: String, path: String, width: Int, height: Int): String? {
        if (path.startsWith("http://", true) || path.startsWith("https://", true)) return path
        if (!path.startsWith("/")) return null
        val base = address.toHttpUrlOrNull() ?: return null
        val url = base.newBuilder()
            .encodedPath(base.encodedPath.trimEnd('/') + "/photo/:/transcode")
            .query(null)
            .addQueryParameter("width", width.toString())
            .addQueryParameter("height", height.toString())
            .addQueryParameter("minSize", "1")
            .addQueryParameter("upscale", "1")
            .addQueryParameter("url", path)
            .build()
        return "$url#$MARK${URLEncoder.encode(connectionId, "UTF-8")}"
    }

    fun connectionId(url: HttpUrl): String? {
        val value = url.encodedFragment?.split('&')?.firstOrNull { it.startsWith(MARK) }?.removePrefix(MARK) ?: return null
        return runCatching { URLDecoder.decode(value, "UTF-8") }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}

/** The address and token a marked Plex link is fetched with right now. */
class PlexArtworkAccess(val address: String, val token: String) {
    override fun toString(): String = "PlexArtworkAccess(address=$address)"
}

@Singleton
class PlexArtworkSigner internal constructor(private val access: (String) -> PlexArtworkAccess?) {
    @Inject
    constructor(repository: ServerRepository) : this({ connectionId ->
        val session = repository.session(connectionId)
        val provider = session?.let { repository.provider(it.connection) } as? PlexProvider
        val token = session?.let { provider?.serverToken(it) }
        if (session != null && token != null) PlexArtworkAccess(session.address, token) else null
    })

    fun access(connectionId: String): PlexArtworkAccess? = access.invoke(connectionId)
}

/** Signs marked Plex artwork links, and points them at the address the server is reached at now. */
class PlexArtworkInterceptor(private val signer: () -> PlexArtworkSigner?) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val connectionId = PlexArtworkLink.connectionId(request.url) ?: return chain.proceed(request)
        val access = signer()?.access(connectionId) ?: return chain.proceed(request)
        val url = moved(request.url, access.address)
        val server = access.address.toHttpUrlOrNull() ?: return chain.proceed(request)
        if (url.scheme != server.scheme || url.host != server.host || url.port != server.port) return chain.proceed(request)
        return chain.proceed(
            request.newBuilder()
                .url(url)
                .header(PlexAccountClient.TOKEN_HEADER, access.token)
                .build()
        )
    }

    private fun moved(url: HttpUrl, address: String): HttpUrl {
        val now = address.toHttpUrlOrNull() ?: return url
        if (now.scheme == url.scheme && now.host == url.host && now.port == url.port) return url
        if (!url.encodedPath.endsWith(PHOTO_PATH)) return url
        return url.newBuilder()
            .scheme(now.scheme)
            .host(now.host)
            .port(now.port)
            .encodedPath(now.encodedPath.trimEnd('/') + PHOTO_PATH)
            .build()
    }

    private companion object {
        const val PHOTO_PATH = "/photo/:/transcode"
    }
}
