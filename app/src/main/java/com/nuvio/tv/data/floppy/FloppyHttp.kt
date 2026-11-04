package com.nuvio.tv.data.floppy

import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer

enum class FloppyHttpMethod { GET, POST }

class FloppyHttpRequest(
    val serverUrl: String,
    val method: FloppyHttpMethod,
    val path: String,
    val accessToken: String? = null,
    val form: Map<String, String>? = null,
    val json: String? = null
) {
    override fun toString(): String = "FloppyHttpRequest($method $path)"
}

class FloppyHttpResponse(val status: Int, val body: String)

fun interface FloppyHttpEngine {
    suspend fun execute(request: FloppyHttpRequest): FloppyHttpResponse
}

/** Turns what the user typed into a server root, or null when it cannot be one. */
fun normalizeFloppyServerUrl(input: String): String? {
    val trimmed = input.trim().trimEnd('/')
    if (trimmed.isEmpty() || trimmed.any(Char::isWhitespace)) return null
    val withScheme = if ("://" in trimmed) trimmed else "http://$trimmed"
    val url = withScheme.toHttpUrlOrNull() ?: return null
    if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) return null
    return url.toString().trimEnd('/')
}

internal fun floppyUrl(serverUrl: String, path: String): HttpUrl {
    require(path.startsWith('/') && !path.startsWith("//") && path.none { it == '?' || it == '#' })
    val base = requireNotNull(serverUrl.toHttpUrlOrNull())
    return base.newBuilder().encodedPath(base.encodedPath.trimEnd('/') + path).query(null).fragment(null).build()
}

class OkHttpFloppyEngine(
    client: OkHttpClient,
    private val appVersion: String
) : FloppyHttpEngine {
    private val client = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    override suspend fun execute(request: FloppyHttpRequest): FloppyHttpResponse =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(buildRequest(request))
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            val body = response.body?.let { readBody(it.source()) }.orEmpty()
                            if (continuation.isActive) continuation.resume(FloppyHttpResponse(response.code, body))
                        } catch (error: IOException) {
                            if (continuation.isActive) continuation.resumeWithException(error)
                        }
                    }
                }
            })
        }

    internal fun buildRequest(request: FloppyHttpRequest): Request =
        Request.Builder()
            .url(floppyUrl(request.serverUrl, request.path))
            .header("Accept", "application/json")
            .header("User-Agent", "NuvioTV/$appVersion")
            .apply {
                request.accessToken?.let { header("Authorization", "Bearer $it") }
                when (request.method) {
                    FloppyHttpMethod.GET -> get()
                    FloppyHttpMethod.POST -> post(
                        request.form?.let { fields ->
                            FormBody.Builder().apply { fields.forEach { (key, value) -> add(key, value) } }.build()
                        } ?: request.json.orEmpty().toRequestBody(JSON)
                    )
                }
            }
            .build()

    private fun readBody(source: okio.BufferedSource): String {
        val buffer = Buffer()
        while (true) {
            val count = source.read(buffer, 8_192L)
            if (count == -1L) return buffer.readUtf8()
            if (buffer.size > MAX_BODY_BYTES) throw IOException("Floppy response too large")
        }
    }

    private companion object {
        val JSON = "application/json".toMediaType()
        const val MAX_BODY_BYTES = 2L * 1024L * 1024L
    }
}
