package com.nuvio.tv.data.iptv

import com.nuvio.tv.core.iptv.PlaylistCatalogue
import com.nuvio.tv.core.iptv.PlaylistCatalogueParser
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

data class CatalogueValidators(val etag: String? = null, val lastModified: String? = null) {
    override fun toString(): String = "CatalogueValidators(values withheld)"
}

sealed interface PlaylistDownload {
    data object NotModified : PlaylistDownload
    data class Candidate(val catalogue: PlaylistCatalogue, val validators: CatalogueValidators) : PlaylistDownload {
        override fun toString(): String = "PlaylistDownload.Candidate(channels=${catalogue.channels.size})"
    }
}

enum class MetadataFailure { INVALID_ADDRESS, AUTHENTICATION, HTTP_STATUS, REDIRECT_REQUIRES_REVIEW, REDIRECT_LIMIT, BODY_LIMIT, INVALID_RESPONSE, NETWORK }
class MetadataException(val failure: MetadataFailure, val status: Int? = null) : IOException("IPTV metadata: $failure")

class IptvMetadataClient(
    private val http: OkHttpClient = newClient(),
    private val parser: PlaylistCatalogueParser = PlaylistCatalogueParser(),
    private val maxExpandedBytes: Long = 16L * 1024 * 1024,
) {
    init {
        require(maxExpandedBytes > 0)
        require(!http.followRedirects && !http.followSslRedirects) { "Redirects must be checked explicitly" }
    }

    suspend fun playlist(address: String, validators: CatalogueValidators? = null): PlaylistDownload {
        var url = address.toHttpUrlOrNull()?.newBuilder()?.fragment(null)?.build()?.takeIf(::usable) ?: throw MetadataException(MetadataFailure.INVALID_ADDRESS)
        val visited = mutableSetOf<HttpUrl>()
        var redirected = false
        repeat(MAX_REDIRECTS + 1) {
            if (!visited.add(url)) throw MetadataException(MetadataFailure.REDIRECT_LIMIT)
            val request = Request.Builder().url(url).header("Accept", "application/x-mpegURL, audio/x-mpegurl, text/plain, */*")
                .header("Connection", "close")
                .apply {
                    validators?.takeUnless { redirected }?.etag?.let { header("If-None-Match", it) }
                    validators?.takeUnless { redirected }?.lastModified?.let { header("If-Modified-Since", it) }
                }.build()
            when (val result = download(request, !redirected && validators != null && (validators.etag != null || validators.lastModified != null))) {
                is Step.Done -> return result.result
                is Step.Redirect -> {
                    val next = url.resolve(result.location)?.newBuilder()?.fragment(null)?.build()?.takeIf(::usable) ?: throw MetadataException(MetadataFailure.INVALID_ADDRESS)

                    if (url.scheme != next.scheme || url.host != next.host || url.port != next.port) {
                        throw MetadataException(MetadataFailure.REDIRECT_REQUIRES_REVIEW)
                    }
                    url = next; redirected = true
                }
            }
        }
        throw MetadataException(MetadataFailure.REDIRECT_LIMIT)
    }

    private suspend fun download(request: Request, conditional: Boolean): Step = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(MetadataException(MetadataFailure.NETWORK))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        val result = when (response.code) {
                            301, 302, 303, 307, 308 -> Step.Redirect(response.header("Location") ?: throw MetadataException(MetadataFailure.INVALID_RESPONSE))
                            304 -> {
                                if (!conditional) throw MetadataException(MetadataFailure.INVALID_RESPONSE)
                                Step.Done(PlaylistDownload.NotModified)
                            }
                            200 -> {
                                val body = response.body ?: throw MetadataException(MetadataFailure.INVALID_RESPONSE)
                                if (body.contentLength() > maxExpandedBytes) throw MetadataException(MetadataFailure.BODY_LIMIT)
                                val catalogue = parser.parse(ByteBudgetInput(body.byteStream(), maxExpandedBytes), URI(response.request.url.toString()))
                                Step.Done(PlaylistDownload.Candidate(catalogue,
                                    CatalogueValidators(response.header("ETag"), response.header("Last-Modified"))))
                            }
                            else -> throw MetadataException(MetadataFailure.HTTP_STATUS, response.code)
                        }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(
                            error as? MetadataException ?: MetadataException(MetadataFailure.INVALID_RESPONSE))
                    }
                }
            }
        })
    }

    private sealed interface Step {
        data class Done(val result: PlaylistDownload) : Step
        class Redirect(val location: String) : Step
    }

    companion object {
        const val MAX_REDIRECTS = 6
        fun newClient(): OkHttpClient = OkHttpClient.Builder()
            .dispatcher(Dispatcher().apply { maxRequests = 2; maxRequestsPerHost = 2 })
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS).build()

        private fun usable(url: HttpUrl): Boolean = url.username.isEmpty() && url.password.isEmpty() && url.fragment == null
    }
}

private class ByteBudgetInput(input: InputStream, private val maximum: Long) : FilterInputStream(input) {
    private var count = 0L
    override fun read(): Int = `in`.read().also { if (it >= 0) consumed(1) }
    override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
        `in`.read(bytes, offset, minOf(length.toLong(), maximum - count + 1).toInt()).also { if (it > 0) consumed(it) }
    private fun consumed(bytes: Int) {
        count += bytes
        if (count > maximum) throw MetadataException(MetadataFailure.BODY_LIMIT)
    }
}
