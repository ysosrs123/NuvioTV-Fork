package com.nuvio.tv.data.iptv

import com.nuvio.tv.core.iptv.EspnSummary
import com.nuvio.tv.core.iptv.FixtureStatus
import com.nuvio.tv.core.iptv.SportsFixture
import com.nuvio.tv.core.iptv.SportsLeague
import com.nuvio.tv.core.iptv.SportsLeagues
import com.nuvio.tv.core.iptv.SportsService
import com.nuvio.tv.core.iptv.SportsSummary
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

class IptvSportsSummaryClient(private val http: OkHttpClient = IptvSportsFixturesClient.newClient(), private val espnBase: HttpUrl = ESPN_BASE.toHttpUrl()) {

    suspend fun summary(league: SportsLeague, eventId: String): SportsSummary {
        val url = url(league, eventId) ?: throw SportsFetchException(SportsFetchFailure.INVALID_RESPONSE)
        val body = get(url)
        return withContext(Dispatchers.Default) {
            try { EspnSummary.parse(body, league) } catch (_: Exception) { throw SportsFetchException(SportsFetchFailure.INVALID_RESPONSE) }
        }
    }

    internal fun url(league: SportsLeague, eventId: String): HttpUrl? {
        val path = league.espn ?: return null
        if (!eventId.matches(EVENT)) return null
        return espnBase.newBuilder().apply { path.split('/').forEach(::addPathSegment) }.addPathSegment("summary").addQueryParameter("event", eventId).build()
    }

    private suspend fun get(url: HttpUrl): String = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).header("Accept", "application/json").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(SportsFetchException(SportsFetchFailure.NETWORK))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        if (response.code != 200) throw SportsFetchException(SportsFetchFailure.HTTP_STATUS, response.code)
                        val body = response.body ?: throw SportsFetchException(SportsFetchFailure.INVALID_RESPONSE)
                        if (body.contentLength() > MAX_BYTES) throw SportsFetchException(SportsFetchFailure.BODY_LIMIT)
                        val source = body.source()
                        if (source.request(MAX_BYTES + 1)) throw SportsFetchException(SportsFetchFailure.BODY_LIMIT)
                        val text = source.buffer.readUtf8()
                        if (continuation.isActive) continuation.resume(text)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error as? SportsFetchException ?: SportsFetchException(SportsFetchFailure.NETWORK))
                    }
                }
            }
        })
    }

    companion object {
        const val MAX_BYTES = 2L * 1024 * 1024
        private const val USER_AGENT = "Nuvio-Live/1"
        private const val ESPN_BASE = "https://site.api.espn.com/apis/site/v2/sports/"
        private val EVENT = Regex("[0-9]{1,20}")
    }
}

class IptvSportsSummaryWatch(private val client: IptvSportsSummaryClient, private val scope: CoroutineScope, private val liveMillis: Long = LIVE_MILLIS,
    private val waitingMillis: Long = WAITING_MILLIS, private val maxBackoffMillis: Long = MAX_BACKOFF_MILLIS) {
    private val state = MutableStateFlow<SportsSummary?>(null)
    val summary: StateFlow<SportsSummary?> = state.asStateFlow()
    private var job: Job? = null
    private var key: String? = null

    @Synchronized fun start(fixture: SportsFixture, service: SportsService = SportsService.ESPN) {
        val league = SportsLeagues.byId(fixture.league)?.takeIf { service == SportsService.ESPN && it.espn != null }
        if (key == fixture.key && job?.isActive == true) return
        job?.cancel()
        if (key != fixture.key) state.value = null
        key = fixture.key
        if (league == null) { job = null; return }
        job = scope.launch {
            var failures = 0
            while (true) {
                val wait = try {
                    val result = client.summary(league, fixture.id)
                    state.value = result
                    failures = 0
                    when {
                        result.status == FixtureStatus.FINAL || fixture.status == FixtureStatus.FINAL && result.status != FixtureStatus.LIVE -> return@launch
                        result.status == FixtureStatus.LIVE -> liveMillis
                        else -> waitingMillis
                    }
                } catch (cancel: CancellationException) { throw cancel } catch (error: Exception) {
                    failures = minOf(failures + 1, 20)
                    if (error !is SportsFetchException) IptvLog.failure("sports summary", error)
                    backoff(failures)
                }
                delay(wait)
            }
        }
    }

    @Synchronized fun stop() {
        job?.cancel()
        job = null
    }

    internal fun backoff(failures: Int): Long = minOf(maxBackoffMillis, liveMillis shl minOf(failures - 1, 10).coerceAtLeast(0))

    companion object {
        const val LIVE_MILLIS = 30_000L
        const val WAITING_MILLIS = 2L * 60 * 1000
        const val MAX_BACKOFF_MILLIS = 10L * 60 * 1000
    }
}
