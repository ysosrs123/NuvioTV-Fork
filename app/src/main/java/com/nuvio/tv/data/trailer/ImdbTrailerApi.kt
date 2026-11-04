package com.nuvio.tv.data.trailer

import com.nuvio.tv.core.network.IPv4FirstDns
import android.os.SystemClock
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nuvio.tv.core.network.NoDelaySocketFactory
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** A title's main trailer straight from IMDb's data service, without loading the title page. */
@Singleton
class ImdbTrailerApi internal constructor(
    private val client: OkHttpClient,
    private val endpoint: String,
    private val budgetMs: Long,
    now: () -> Long
) {
    @Inject constructor() : this(
        OkHttpClient.Builder().dns(IPv4FirstDns()).socketFactory(NoDelaySocketFactory).connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS).callTimeout(4, TimeUnit.SECONDS).build(),
        ENDPOINT,
        BUDGET_MS,
        SystemClock::elapsedRealtime
    )

    private val backoff = ImdbApiBackoff(now)

    private sealed interface Outcome {
        data class Answer(val body: String) : Outcome
        data object Refused : Outcome
        data object Failed : Outcome
    }

    /** Found or Missing when IMDb answered, Pending when it did not (the title page is tried then). */
    internal suspend fun primaryVideo(imdbId: String): ImdbTrailerData.Result {
        require(Regex("tt\\d+").matches(imdbId))
        if (!backoff.allowed()) return ImdbTrailerData.Result.Pending
        val request = Request.Builder().url(endpoint)
            .header("x-imdb-client-name", CLIENT_NAME)
            .post(requestBody(imdbId).toRequestBody("application/json".toMediaType()))
            .build()
        return when (val outcome = withTimeoutOrNull(budgetMs) { post(request) } ?: Outcome.Failed) {
            Outcome.Refused -> ImdbTrailerData.Result.Pending.also { backoff.refused() }
            Outcome.Failed -> ImdbTrailerData.Result.Pending.also { backoff.failed() }
            is Outcome.Answer -> {
                val result = ImdbTrailerData.parse(outcome.body, true)
                when {
                    result != ImdbTrailerData.Result.Pending -> backoff.succeeded()
                    hasErrors(outcome.body) -> backoff.refused()
                }
                result
            }
        }
    }

    private suspend fun post(request: Request): Outcome = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resume(Outcome.Failed)
            }
            override fun onResponse(call: Call, response: Response) {
                val outcome = response.use {
                    if (!it.isSuccessful) Outcome.Refused
                    else runCatching { it.body?.string() }.getOrNull()?.let(Outcome::Answer) ?: Outcome.Failed
                }
                if (continuation.isActive) continuation.resume(outcome)
            }
        })
    }

    internal companion object {
        const val ENDPOINT = "https://api.graphql.imdb.com/"
        const val CLIENT_NAME = "nuvio-tv"
        const val BUDGET_MS = 4_000L
        private const val QUERY = "query TitleTrailer(\$id: ID!) { title(id: \$id) { primaryVideos(first: 1) " +
            "{ edges { node { id playbackURLs { url videoMimeType videoDefinition } } } } } }"

        fun requestBody(imdbId: String): String = JsonObject().apply {
            addProperty("query", QUERY)
            add("variables", JsonObject().apply { addProperty("id", imdbId) })
        }.toString()

        private fun hasErrors(body: String): Boolean = runCatching {
            JsonParser.parseString(body).asJsonObject.get("errors")?.isJsonArray == true
        }.getOrDefault(false)
    }
}

/**
 * When the title page is used instead of the service: half an hour after a refusal, five minutes after two
 * failed attempts in a row, so one dropped request does not cost the rest of the session.
 */
internal class ImdbApiBackoff(private val now: () -> Long) {
    private var failures = 0
    private var pausedUntilMs = Long.MIN_VALUE

    @Synchronized fun allowed(): Boolean = now() >= pausedUntilMs

    @Synchronized fun succeeded() { failures = 0 }

    @Synchronized fun refused() {
        failures = 0
        pausedUntilMs = now() + REFUSAL_PAUSE_MS
    }

    @Synchronized fun failed() {
        if (++failures < FAILURES_BEFORE_PAUSE) return
        failures = 0
        pausedUntilMs = now() + FAILURE_PAUSE_MS
    }

    companion object {
        const val REFUSAL_PAUSE_MS = 30 * 60_000L
        const val FAILURE_PAUSE_MS = 5 * 60_000L
        const val FAILURES_BEFORE_PAUSE = 2
    }
}
