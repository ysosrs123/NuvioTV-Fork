package com.nuvio.tv.data.iptv

import java.io.FilterInputStream
import java.io.InputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
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

class IptvGuideClient(
    private val http: OkHttpClient = IptvMetadataClient.newClient().newBuilder().readTimeout(60, TimeUnit.SECONDS).callTimeout(15, TimeUnit.MINUTES).build(),
    private val maxTransferBytes: Long = 512L * 1024 * 1024,
) {
    init {
        require(maxTransferBytes in 1 until Long.MAX_VALUE)
        require(!http.followRedirects && !http.followSslRedirects)
    }

    suspend fun <T> fetch(address: String, validators: CatalogueValidators? = null,
        consume: (InputStream, CatalogueValidators, () -> Unit) -> T): GuideDownload<T> = withContext(Dispatchers.IO) {
        require(listOf(validators?.etag, validators?.lastModified).all { it == null || (it.length <= 4096 && '\r' !in it && '\n' !in it) })
        permits.withPermit {
            var url = address.toHttpUrlOrNull()?.newBuilder()?.fragment(null)?.build()?.takeIf(::usable) ?: throw MetadataException(MetadataFailure.INVALID_ADDRESS)
            var redirected = false
            val visited = mutableSetOf<HttpUrl>()
            repeat(IptvMetadataClient.MAX_REDIRECTS + 1) {
                currentCoroutineContext().ensureActive()
                if (!visited.add(url)) throw MetadataException(MetadataFailure.REDIRECT_LIMIT)
                val request = Request.Builder().url(url)
                    .header("Accept", "application/xml, text/xml, application/gzip, */*")
                    .header("Connection", "close")

                    .header("Accept-Encoding", "identity")
                    .apply {
                        validators?.takeUnless { redirected }?.etag?.let { header("If-None-Match", it) }
                        validators?.takeUnless { redirected }?.lastModified?.let { header("If-Modified-Since", it) }
                    }.build()
                when (val step = response(request, !redirected && validators?.let { it.etag != null || it.lastModified != null } == true) { input, received, check ->

                    consume(input, if (redirected) CatalogueValidators() else received, check)
                }) {
                    is Step.Done -> return@withPermit step.value
                    is Step.Redirect -> {
                        val next = url.resolve(step.location)?.newBuilder()?.fragment(null)?.build()?.takeIf(::usable) ?: throw MetadataException(MetadataFailure.INVALID_ADDRESS)
                        val sameOrigin = next.scheme == url.scheme && next.host == url.host && next.port == url.port

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
            if (error is CancellationException) throw error
            IptvLog.failure("guide", error)
            if (error is MetadataException) throw error
            throw MetadataException(MetadataFailure.INVALID_RESPONSE)
        } finally { cancellation.cancel() }
    }

    private sealed interface Step<out T> {
        data class Done<T>(val value: GuideDownload<T>) : Step<T>
        class Redirect(val location: String) : Step<Nothing>
    }
    private companion object {
        val permits = Semaphore(2)
        fun usable(url: HttpUrl) = url.username.isEmpty() && url.password.isEmpty() && url.fragment == null
    }
}
