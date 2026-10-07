package com.nuvio.tv.data.iptv

import com.nuvio.tv.core.iptv.EspnScoreboard
import com.nuvio.tv.core.iptv.SportsCacheEntry
import com.nuvio.tv.core.iptv.SportsDbEvents
import com.nuvio.tv.core.iptv.SportsFixture
import com.nuvio.tv.core.iptv.SportsFixtureCodec
import com.nuvio.tv.core.iptv.SportsLeague
import com.nuvio.tv.core.iptv.SportsService
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

enum class SportsFetchFailure { HTTP_STATUS, BODY_LIMIT, INVALID_RESPONSE, NETWORK }
class SportsFetchException(val failure: SportsFetchFailure, val status: Int? = null) : IOException("Sports fixtures: $failure")

class IptvSportsFixturesClient(private val http: OkHttpClient = newClient(), private val espnBase: HttpUrl = ESPN_BASE.toHttpUrl(),
    private val sportsDbBase: HttpUrl = SPORTSDB_BASE.toHttpUrl()) {

    suspend fun fixtures(service: SportsService, league: SportsLeague, date: LocalDate, key: String?, nowMillis: Long): List<SportsFixture> {
        val url = url(service, league, date, key) ?: return emptyList()
        val body = get(url)
        return try {
            when (service) {
                SportsService.ESPN -> EspnScoreboard.parse(body, league)
                SportsService.THESPORTSDB -> SportsDbEvents.parse(body, league, nowMillis)
                SportsService.OFF -> emptyList()
            }
        } catch (_: Exception) { throw SportsFetchException(SportsFetchFailure.INVALID_RESPONSE) }
    }

    internal fun url(service: SportsService, league: SportsLeague, date: LocalDate, key: String?): HttpUrl? = when (service) {
        SportsService.OFF -> null
        SportsService.ESPN -> league.espn?.let { path ->
            espnBase.newBuilder().apply { path.split('/').forEach(::addPathSegment) }.addPathSegment("scoreboard")
                .addQueryParameter("dates", date.format(DateTimeFormatter.BASIC_ISO_DATE)).build()
        }
        SportsService.THESPORTSDB -> league.sportsDb?.takeIf { key != null && IptvSportsPreferences.validKey(key) }?.let { name ->
            sportsDbBase.newBuilder().addPathSegment(key!!.trim()).addPathSegment("eventsday.php")
                .addQueryParameter("d", date.toString()).addQueryParameter("l", name.replace(' ', '_')).build()
        }
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
        const val MAX_BYTES = 4L * 1024 * 1024
        private const val USER_AGENT = "Nuvio-Live/1"
        private const val ESPN_BASE = "https://site.api.espn.com/apis/site/v2/sports/"
        private const val SPORTSDB_BASE = "https://www.thesportsdb.com/api/v1/json/"
        fun newClient(): OkHttpClient = OkHttpClient.Builder()
            .dispatcher(Dispatcher().apply { maxRequests = 2; maxRequestsPerHost = 2 })
            .followRedirects(true).followSslRedirects(false).retryOnConnectionFailure(true)
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS).build()
    }
}

class IptvSportsFixturesStore(private val directory: File) {
    @Synchronized fun read(key: String): SportsCacheEntry? {
        val file = file(key) ?: return null
        if (!file.isFile || file.length() > MAX_FILE) return null
        return try { SportsFixtureCodec.decode(file.readText()) } catch (_: IOException) { null }
    }

    @Synchronized fun write(key: String, entry: SportsCacheEntry) {
        val file = file(key) ?: return
        try {
            if (!directory.isDirectory && !directory.mkdirs()) return
            val temporary = File(directory, file.name + ".tmp")
            temporary.writeText(SportsFixtureCodec.encode(entry))
            if (!temporary.renameTo(file)) temporary.delete()
        } catch (error: IOException) { IptvLog.failure("sports cache write", error) }
    }

    @Synchronized fun prune(keep: Set<String>) {
        val names = keep.mapNotNull { file(it)?.name }.toSet()
        directory.listFiles()?.filter { it.isFile && it.name !in names }?.forEach { it.delete() }
    }

    private fun file(key: String): File? = key.takeIf { it.matches(KEY) }?.let { File(directory, "$it.json") }

    companion object {
        private const val MAX_FILE = 2L * 1024 * 1024
        private val KEY = Regex("[a-z0-9-]{1,80}")
        fun key(service: SportsService, league: SportsLeague, date: LocalDate) =
            "${service.name.lowercase()}-${league.id}-${date.format(DateTimeFormatter.BASIC_ISO_DATE)}"
    }
}
