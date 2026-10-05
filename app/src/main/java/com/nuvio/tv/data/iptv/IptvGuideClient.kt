package com.nuvio.tv.data.iptv

import java.io.FilterInputStream
import java.io.InputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

sealed interface GuideDownload<out T> {
    data object NotModified : GuideDownload<Nothing>
    data class Imported<T>(val result: T) : GuideDownload<T>
}

/** Response and import share a structured lifetime: cancellation closes the call and awaits cleanup. */
class IptvGuideClient(
    private val http: OkHttpClient = IptvMetadataClient.newClient(),
    private val maxTransferBytes: Long = 64L * 1024 * 1024,
) {
    init {
        require(maxTransferBytes in 1 until Long.MAX_VALUE)
        require(!http.followRedirects && !http.followSslRedirects)
    }

    /** Consumer must fully validate its stream and check cancellation immediately before promotion. */
    suspend fun <T> fetch(address: String, validators: CatalogueValidators? = null,
        consume: (InputStream, CatalogueValidators, () -> Unit) -> T): GuideDownload<T> = withContext(Dispatchers.IO) {
        require(listOf(validators?.etag, validators?.lastModified).all { it == null || (it.length <= 4096 && '\r' !in it && '\n' !in it) })
        permits.withPermit {
            var url = address.toHttpUrlOrNull()?.takeIf(::usable) ?: throw MetadataException(MetadataFailure.INVALID_ADDRESS)
            var redirected = false
            val visited = mutableSetOf<HttpUrl>()
            repeat(6) {
                currentCoroutineContext().ensureActive()
                if (!visited.add(url)) throw MetadataException(MetadataFailure.REDIRECT_LIMIT)
                val request = Request.Builder().url(url)
                    .header("Accept", "application/xml, text/xml, application/gzip, */*")
                    .header("Connection", "close") // No stale-socket retry after an HTTP/1.0 response.
                    // Keep wire bytes visible to the budget; parseGuideInput handles gzip once.
                    .header("Accept-Encoding", "identity")
                    .apply {
                        validators?.takeUnless { redirected }?.etag?.let { header("If-None-Match", it) }
                        validators?.takeUnless { redirected }?.lastModified?.let { header("If-Modified-Since", it) }
                    }.build()
                when (val step = response(request, !redirected && validators?.let { it.etag != null || it.lastModified != null } == true) { input, received, check ->
                    // A redirect may resolve somewhere else on the next refresh. Do not store the
                    // destination's validators against the original short URL or forward them.
                    consume(input, if (redirected) CatalogueValidators() else received, check)
                }) {
                    is Step.Done -> return@withPermit step.value
                    is Step.Redirect -> {
                        val next = url.resolve(step.location)?.takeIf(::usable) ?: throw MetadataException(MetadataFailure.INVALID_ADDRESS)
                        val sameOrigin = next.scheme == url.scheme && next.host == url.host && next.port == url.port
                        // Support HTTPS short links, without downgrade, cookies, authorization,
                        // referrer, inherited query parameters or conditional headers.
                        if (!sameOrigin && next.scheme != "https") throw MetadataException(MetadataFailure.REDIRECT_REQUIRES_REVIEW)
                        redirected = true
                        url = next
                    }
                }
            }
            throw MetadataException(MetadataFailure.REDIRECT_LIMIT)
        }
    }

    private suspend fun <T> response(request: Request, conditional: Boolean,
        consume: (InputStream, CatalogueValidators, () -> Unit) -> T): Step<T> = coroutineScope {
        val context = currentCoroutineContext()
        val call = http.newCall(request)
        val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            val received = try { call.execute() } catch (_: IOException) {
                context.ensureActive()
                throw MetadataException(MetadataFailure.NETWORK)
            }
            received.use { response ->
                context.ensureActive()
                when (response.code) {
                    301, 302, 303, 307, 308 -> Step.Redirect(response.header("Location") ?: throw MetadataException(MetadataFailure.INVALID_RESPONSE))
                    304 -> {
                        if (!conditional) throw MetadataException(MetadataFailure.INVALID_RESPONSE)
                        Step.Done(GuideDownload.NotModified)
                    }
                    200 -> {
                        val body = response.body
                        if (body.contentLength() > maxTransferBytes) throw MetadataException(MetadataFailure.BODY_LIMIT)
                        val encoding = response.header("Content-Encoding")?.trim()?.lowercase()
                        if (encoding != null && encoding !in setOf("identity", "gzip")) throw MetadataException(MetadataFailure.INVALID_RESPONSE)
                        val headers = CatalogueValidators(response.header("ETag"), response.header("Last-Modified"))
                        if (listOf(headers.etag, headers.lastModified).any { it != null && it.length > 4096 }) throw MetadataException(MetadataFailure.INVALID_RESPONSE)
                        val check = { context.ensureActive(); if (call.isCanceled()) throw MetadataException(MetadataFailure.NETWORK) }
                        val input = object : FilterInputStream(body.byteStream()) {
                            var count = 0L
                            fun consumed(size: Int) { if (size > 0) { count += size; if (count > maxTransferBytes) throw MetadataException(MetadataFailure.BODY_LIMIT) } }
                            override fun read(): Int { check(); return `in`.read().also { if (it >= 0) consumed(1) } }
                            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                                check()
                                return `in`.read(bytes, offset, minOf(length.toLong(), maxTransferBytes - count + 1).toInt()).also(::consumed)
                            }
                        }
                        Step.Done(GuideDownload.Imported(consume(input, headers, check)))
                    }
                    else -> throw MetadataException(MetadataFailure.HTTP_STATUS, response.code)
                }
            }
        } catch (error: Exception) {
            context.ensureActive()
            if (error is CancellationException || error is MetadataException) throw error
            // Never expose credential-bearing URLs, parser excerpts, or network-stack causes.
            throw MetadataException(MetadataFailure.INVALID_RESPONSE)
        } finally { cancellation.cancel() }
    }

    private sealed interface Step<out T> {
        data class Done<T>(val value: GuideDownload<T>) : Step<T>
        class Redirect(val location: String) : Step<Nothing>
    }
    private companion object {
        val permits = Semaphore(2) // Synchronous OkHttp calls do not use Dispatcher admission limits.
        fun usable(url: HttpUrl) = url.username.isEmpty() && url.password.isEmpty() && url.fragment == null
    }
}
