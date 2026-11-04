package com.nuvio.tv.data.wetrakr

import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import okio.BufferedSource

data class WeTrakrConfiguration(
    val clientId: String,
    val appVersion: String,
    val baseUrl: String = "https://api.wetrakr.com"
)

enum class WeTrakrHttpMethod { GET, POST, DELETE }

class WeTrakrHttpRequest(
    val method: WeTrakrHttpMethod,
    val path: String,
    val query: Map<String, String> = emptyMap(),
    val body: String? = null,
    val accessToken: String? = null,
    val limitKey: String = "app",
    val idempotencyKey: String? = if (method == WeTrakrHttpMethod.GET) null else UUID.randomUUID().toString(),
    val handlesRateLimit: Boolean = false
) {
    override fun toString(): String = "WeTrakrHttpRequest(method=$method)"
}

class WeTrakrHttpResponse(
    val status: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap()
) {
    fun header(name: String): String? = headers.entries
        .firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    fun errorCode(): String? = runCatching {
        val root = Json.parseToJsonElement(body) as? JsonObject ?: return@runCatching null
        when (val error = root["error"]) {
            is JsonPrimitive -> error.contentOrNull
            is JsonObject -> (error["code"] as? JsonPrimitive)?.contentOrNull
            else -> null
        }
    }.getOrNull()?.takeIf { it.matches(Regex("[A-Za-z_]{1,64}")) }

    override fun toString(): String = "WeTrakrHttpResponse(status=$status)"
}

class WeTrakrApiException(
    val status: Int? = null,
    val code: String? = null,
    val retryAtEpochMs: Long? = null
) : Exception("WeTrakr request failed${status?.let { " (HTTP $it)" }.orEmpty()}") {
    val isDailyLimit: Boolean
        get() = status == 429 && code in DAILY_LIMIT_CODES

    val isKeySuspended: Boolean
        get() = status == 423

    companion object {
        val DAILY_LIMIT_CODES = setOf("QUOTA_EXCEEDED", "APP_QUOTA_EXCEEDED", "APP_USER_QUOTA_EXCEEDED")
    }
}

data class WeTrakrQuota(
    val limit: Long,
    val remaining: Long,
    val resetsAtEpochMs: Long?,
    val blockedUntilEpochMs: Long? = null
) {
    val used: Long
        get() = (limit - remaining).coerceIn(0L, limit)
}

fun interface WeTrakrHttpEngine {
    suspend fun execute(request: WeTrakrHttpRequest): WeTrakrHttpResponse
}

class WeTrakrHttpClient(
    private val engine: WeTrakrHttpEngine,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) }
) {
    private val stateLock = Mutex()
    private val writes = Mutex()
    private val reads = Semaphore(MAX_PARALLEL_READS)
    private val blockedUntil = mutableMapOf<String, Long>()
    private val dailyBlocks = mutableMapOf<String, Long>()
    private val mutableQuotas = MutableStateFlow<Map<String, WeTrakrQuota>>(emptyMap())

    val quotas: StateFlow<Map<String, WeTrakrQuota>> = mutableQuotas.asStateFlow()

    fun hasQuotaToSpare(limitKey: String): Boolean {
        val quota = mutableQuotas.value[limitKey] ?: return true
        if (quota.resetsAtEpochMs != null && quota.resetsAtEpochMs <= nowEpochMs()) return true
        return quota.remaining > maxOf(MIN_WRITE_RESERVE, quota.limit / 10)
    }

    suspend fun execute(
        request: WeTrakrHttpRequest,
        checkScope: () -> Unit = {}
    ): WeTrakrHttpResponse = if (request.method == WeTrakrHttpMethod.GET) {
        reads.withPermit { send(request, checkScope) }
    } else {
        writes.withLock { send(request, checkScope) }
    }

    suspend fun moveLimit(previousKey: String, accountKey: String) = stateLock.withLock {
        blockedUntil.remove(previousKey)?.let { blockedUntil[accountKey] = maxOf(it, blockedUntil[accountKey] ?: 0L) }
        dailyBlocks.remove(previousKey)?.let { dailyBlocks[accountKey] = maxOf(it, dailyBlocks[accountKey] ?: 0L) }
        mutableQuotas.value[previousKey]?.let { mutableQuotas.value = mutableQuotas.value - previousKey + (accountKey to it) }
    }

    private suspend fun send(request: WeTrakrHttpRequest, checkScope: () -> Unit): WeTrakrHttpResponse {
        repeat(ATTEMPTS) { attempt ->
            checkScope()
            stateLock.withLock {
                val now = nowEpochMs()
                blockedUntil.entries.removeAll { it.value <= now }
                dailyBlocks.entries.removeAll { it.value <= now }
                dailyBlocks[request.limitKey]?.let { throw WeTrakrApiException(429, "QUOTA_EXCEEDED", it) }
                blockedUntil[request.limitKey]?.let { throw WeTrakrApiException(429, "rate_limited", it) }
            }
            val response = try {
                engine.execute(request)
            } catch (_: IOException) {
                checkScope()
                if (attempt == ATTEMPTS - 1) throw WeTrakrApiException(code = "transport_failure")
                sleep(1_000L shl attempt)
                return@repeat
            }
            checkScope()
            val now = nowEpochMs()
            val retryAt = response.header("Retry-After")?.let { weTrakrRetryAfterEpochMs(it, now) }
            stateLock.withLock { recordQuota(request.limitKey, response, now) }
            when {
                response.status == 429 && request.handlesRateLimit -> return response
                response.status == 429 -> {
                    val code = response.errorCode()
                    if (code in WeTrakrApiException.DAILY_LIMIT_CODES) {
                        val until = dailyResetEpochMs(response, now)
                        stateLock.withLock {
                            dailyBlocks[request.limitKey] = until
                            mutableQuotas.value[request.limitKey]?.let { quota ->
                                mutableQuotas.value = mutableQuotas.value +
                                    (request.limitKey to quota.copy(remaining = 0L, blockedUntilEpochMs = until))
                            }
                        }
                        throw WeTrakrApiException(429, code, until)
                    }
                    val wait = (retryAt ?: windowResetEpochMs(response, now) ?: (now + 60_000L)) - now
                    if (code == "CONCURRENCY_LIMIT" && attempt < ATTEMPTS - 1 && wait <= 5_000L) {
                        sleep(wait.coerceAtLeast(1_000L))
                        return@repeat
                    }
                    val until = now + wait.coerceAtLeast(1_000L)
                    stateLock.withLock { blockedUntil[request.limitKey] = until }
                    throw WeTrakrApiException(429, code ?: "rate_limited", until)
                }
                response.status in RETRYABLE && attempt < ATTEMPTS - 1 -> {
                    val wait = maxOf(1_000L shl attempt, (retryAt ?: 0L) - now)
                    if (wait > 5_000L) throw WeTrakrApiException(response.status, retryAtEpochMs = retryAt)
                    sleep(wait)
                }
                response.status == 409 && response.errorCode() == "IDEMPOTENCY_IN_PROGRESS" && attempt < ATTEMPTS - 1 ->
                    sleep(1_000L shl attempt)
                else -> return response
            }
        }
        throw WeTrakrApiException(code = "request_failed")
    }

    private fun recordQuota(key: String, response: WeTrakrHttpResponse, now: Long) {
        val limit = response.header("X-Quota-Limit")?.trim()?.toLongOrNull() ?: return
        val remaining = response.header("X-Quota-Remaining")?.trim()?.toLongOrNull() ?: return
        if (limit <= 0L) return
        val reset = response.header("X-Quota-Reset")?.trim()?.toLongOrNull()
            ?.takeIf { it in 0..MAX_RESET_SECONDS }?.let { now + it * 1_000L }
        mutableQuotas.value = mutableQuotas.value +
            (key to WeTrakrQuota(limit, remaining.coerceAtLeast(0L), reset ?: nextUtcMidnight(now)))
    }

    private fun dailyResetEpochMs(response: WeTrakrHttpResponse, now: Long): Long =
        response.header("X-Quota-Reset")?.trim()?.toLongOrNull()
            ?.takeIf { it in 1..MAX_RESET_SECONDS }?.let { now + it * 1_000L }
            ?: nextUtcMidnight(now)

    private fun windowResetEpochMs(response: WeTrakrHttpResponse, now: Long): Long? =
        response.header("RateLimit-Reset")?.trim()?.toLongOrNull()
            ?.takeIf { it in 1..3_600L }?.let { now + it * 1_000L }

    private companion object {
        const val ATTEMPTS = 3
        const val MAX_PARALLEL_READS = 4
        const val MAX_RESET_SECONDS = 2L * 24 * 60 * 60
        const val MIN_WRITE_RESERVE = 30L
        val RETRYABLE = setOf(500, 502, 503, 504, 520, 521, 522)
    }
}

internal fun nextUtcMidnight(now: Long): Long =
    LocalDate.ofInstant(Instant.ofEpochMilli(now), ZoneOffset.UTC).plusDays(1)
        .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

internal fun weTrakrRetryAfterEpochMs(value: String, now: Long): Long? =
    value.trim().toLongOrNull()?.takeIf { it in 0..86_400L }?.let { now + it * 1_000L }

class OkHttpWeTrakrEngine(
    client: OkHttpClient,
    private val configuration: WeTrakrConfiguration
) : WeTrakrHttpEngine {
    private val client = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    override suspend fun execute(request: WeTrakrHttpRequest): WeTrakrHttpResponse =
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
                            val body = response.body.let { readWeTrakrBody(it.source(), it.contentLength()) }
                            if (continuation.isActive) continuation.resume(
                                WeTrakrHttpResponse(
                                    status = response.code,
                                    body = body,
                                    headers = response.headers.toMultimap().mapValues { it.value.joinToString(",") }
                                )
                            )
                        } catch (error: IOException) {
                            if (continuation.isActive) continuation.resumeWithException(error)
                        }
                    }
                }
            })
        }

    internal fun buildRequest(request: WeTrakrHttpRequest): Request {
        require(request.path.startsWith('/') && !request.path.startsWith("//"))
        require(request.path.none { it == '?' || it == '#' })
        val base = configuration.baseUrl.toHttpUrl()
        require(base.isHttps || base.host in setOf("localhost", "127.0.0.1", "::1"))
        val url = base.newBuilder().encodedPath(request.path).query(null).fragment(null).apply {
            request.query.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        val json = "application/json".toMediaType()
        return Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "NuvioArc/${configuration.appVersion}")
            .header("wetrakr-api-key", configuration.clientId)
            .header("wetrakr-api-version", "1")
            .apply {
                request.accessToken?.let { header("Authorization", "Bearer $it") }
                request.idempotencyKey?.let { header("Idempotency-Key", it) }
                when (request.method) {
                    WeTrakrHttpMethod.GET -> get()
                    WeTrakrHttpMethod.POST -> post(request.body.orEmpty().ifEmpty { "{}" }.toRequestBody(json))
                    WeTrakrHttpMethod.DELETE -> request.body?.let { delete(it.toRequestBody(json)) } ?: delete()
                }
            }.build()
    }
}

internal fun readWeTrakrBody(
    source: BufferedSource,
    contentLength: Long,
    maxBytes: Long = 8L * 1024L * 1024L
): String {
    if (contentLength > maxBytes) throw IOException("WeTrakr response exceeds size limit")
    val buffer = Buffer()
    while (true) {
        val count = source.read(buffer, minOf(8_192L, maxBytes - buffer.size + 1L))
        if (count == -1L) return buffer.readUtf8()
        if (buffer.size > maxBytes) throw IOException("WeTrakr response exceeds size limit")
    }
}
