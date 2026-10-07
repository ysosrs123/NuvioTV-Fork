package com.nuvio.tv.data.iptv

import com.nuvio.tv.core.iptv.CHANNEL_LOGO_ATTRIBUTE
import com.nuvio.tv.core.iptv.ChannelCandidate
import com.nuvio.tv.core.iptv.GuideProgramme
import com.nuvio.tv.core.iptv.VodCategory
import com.nuvio.tv.core.iptv.VodKind
import com.nuvio.tv.core.iptv.VodMovie
import com.nuvio.tv.core.iptv.VodMovieInfo
import com.nuvio.tv.core.iptv.VodParseCount
import com.nuvio.tv.core.iptv.VodSeries
import com.nuvio.tv.core.iptv.VodSeriesInfo
import com.nuvio.tv.core.iptv.XtreamCatalogueParser
import com.nuvio.tv.core.iptv.XtreamShortGuide
import com.nuvio.tv.core.iptv.XtreamVodParser
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit
import java.util.logging.Logger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

data class XtreamAccount(val advertisedConnections: Int?, val expiresAtSeconds: Long?, val output: String)
data class XtreamDownload(val account: XtreamAccount, val records: List<IptvCatalogueRecord>, val canPublish: Boolean) {
    override fun toString() = "XtreamDownload(channels=${records.size}, canPublish=$canPublish)"
}

class IptvXtreamClient(private val http: OkHttpClient = IptvMetadataClient.newClient(),
    private val maxBodyBytes: Int = 8 * 1024 * 1024,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 }) {
    init {
        require(maxBodyBytes in 1..16 * 1024 * 1024)
        require(!http.followRedirects && !http.followSslRedirects && !http.retryOnConnectionFailure)
    }

    suspend fun catalogue(connection: IptvSourceConnection): XtreamDownload {
        val base = serverBase(connection)
        val user = requireNotNull(connection.username)
        val pass = requireNotNull(connection.password)
        fun api(action: String? = null) = base.newBuilder().addPathSegment("player_api.php")
            .addQueryParameter("username", user).addQueryParameter("password", pass)
            .apply { action?.let { addQueryParameter("action", it) } }.build()
        val account = parseAccount(json(api(), minOf(maxBodyBytes, 256 * 1024)))
        currentCoroutineContext().ensureActive()
        val categories = parseCategories(json(api("get_live_categories"), minOf(maxBodyBytes, 1024 * 1024)))
        currentCoroutineContext().ensureActive()
        val text = json(api("get_live_streams"), maxBodyBytes)
        val catalogue = try { XtreamCatalogueParser(maxBodyBytes).parse(text) }
            catch (_: Exception) { throw MetadataException(MetadataFailure.INVALID_RESPONSE) }
        currentCoroutineContext().ensureActive()
        val records = catalogue.channels.map { row ->
            val locator = base.newBuilder().addPathSegment("live").addPathSegment("${row.providerId}.${account.output}").build().toString()
            val attributes = buildMap {
                row.categoryId?.let { id -> put("category-id", id); categories[id]?.let { put("group-title", it) } }
                put("archive-availability", row.archive.name)
                row.archiveDays?.let { put("archive-days", it.toString()) }
                row.logo?.let { put(CHANNEL_LOGO_ATTRIBUTE, it) }
            }
            IptvCatalogueRecord(ChannelCandidate(row.name, locator, providerId = row.providerId, guideId = row.guideId), attributes)
        }
        if (catalogue.invalidRows > 0) diagnose("catalogue", IllegalStateException("skipped rows ${catalogue.invalidRows}"))
        return XtreamDownload(account, records, catalogue.canPublish)
    }

    suspend fun shortGuide(connection: IptvSourceConnection, streamId: String, limit: Int = 4): List<GuideProgramme> {
        require(limit in 1..20)
        if (!streamId.matches(Regex("[0-9]{1,20}"))) throw MetadataException(MetadataFailure.INVALID_ADDRESS)
        val base = serverBase(connection)
        val url = base.newBuilder().addPathSegment("player_api.php")
            .addQueryParameter("username", connection.username).addQueryParameter("password", connection.password)
            .addQueryParameter("action", "get_short_epg").addQueryParameter("stream_id", streamId)
            .addQueryParameter("limit", limit.toString()).build()
        val text = json(url, minOf(maxBodyBytes, SHORT_GUIDE_BYTES))
        return parseSafely { XtreamShortGuide.parse(text, streamId, maxListings = limit * 2) }
    }

    suspend fun vodCategories(connection: IptvSourceConnection, kind: VodKind): List<VodCategory> {
        require(kind != VodKind.EPISODE)
        val text = json(api(connection, if (kind == VodKind.MOVIE) "get_vod_categories" else "get_series_categories"), minOf(maxBodyBytes, 1024 * 1024))
        return parseSafely { XtreamVodParser.categories(StringReader(text), kind) }
    }

    suspend fun movies(connection: IptvSourceConnection, sink: (VodMovie) -> Unit): VodParseCount =
        streamJson(api(connection, "get_vod_streams")) { reader, check -> XtreamVodParser.movies(reader, checkCancellation = check, sink = sink) }

    suspend fun series(connection: IptvSourceConnection, sink: (VodSeries) -> Unit): VodParseCount =
        streamJson(api(connection, "get_series")) { reader, check -> XtreamVodParser.series(reader, checkCancellation = check, sink = sink) }

    suspend fun seriesInfo(connection: IptvSourceConnection, seriesId: String): VodSeriesInfo {
        if (!seriesId.matches(Regex("[0-9]{1,20}"))) throw MetadataException(MetadataFailure.INVALID_ADDRESS)
        val url = api(connection, "get_series_info").newBuilder().addQueryParameter("series_id", seriesId).build()
        val text = json(url, minOf(maxBodyBytes, INFO_BYTES))
        return parseSafely { XtreamVodParser.seriesInfo(StringReader(text), seriesId) }
    }

    suspend fun movieInfo(connection: IptvSourceConnection, streamId: String): VodMovieInfo? {
        if (!streamId.matches(Regex("[0-9]{1,20}"))) throw MetadataException(MetadataFailure.INVALID_ADDRESS)
        val url = api(connection, "get_vod_info").newBuilder().addQueryParameter("vod_id", streamId).build()
        val text = json(url, minOf(maxBodyBytes, INFO_BYTES))
        return parseSafely { XtreamVodParser.movieInfo(StringReader(text)) }
    }

    private fun api(connection: IptvSourceConnection, action: String): HttpUrl = serverBase(connection).newBuilder().addPathSegment("player_api.php")
        .addQueryParameter("username", requireNotNull(connection.username)).addQueryParameter("password", requireNotNull(connection.password))
        .addQueryParameter("action", action).build()

    private val listHttp: OkHttpClient by lazy { http.newBuilder().callTimeout(15, TimeUnit.MINUTES).readTimeout(60, TimeUnit.SECONDS).build() }

    private suspend fun <T> streamJson(url: HttpUrl, read: (Reader, () -> Unit) -> T): T = coroutineScope {
        val call = listHttp.newCall(Request.Builder().url(url).header("Accept", "application/json").header("Connection", "close").build())
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { call.cancel() } }
        val job = coroutineContext.job
        try {
            withContext(Dispatchers.IO) {
                val response = try { call.execute() } catch (error: IOException) {
                    diagnose("network", error); throw MetadataException(MetadataFailure.NETWORK)
                }
                response.use {
                    if (response.code in listOf(301, 302, 303, 307, 308)) throw MetadataException(MetadataFailure.REDIRECT_REQUIRES_REVIEW)
                    if (response.code != 200) throw MetadataException(MetadataFailure.HTTP_STATUS, response.code)
                    val body = response.body ?: throw MetadataException(MetadataFailure.INVALID_RESPONSE)
                    if (body.contentLength() > LIST_BYTES) throw MetadataException(MetadataFailure.BODY_LIMIT)
                    val reader = InputStreamReader(ListBudget(body.byteStream(), LIST_BYTES), Charsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE))
                    try { read(reader) { if (!job.isActive) throw CancellationException("IPTV list cancelled") } }
                    catch (error: CancellationException) { throw error }
                    catch (error: MetadataException) { diagnose("response", error); throw error }
                    catch (error: IOException) { diagnose("network", error); throw MetadataException(MetadataFailure.NETWORK) }
                    catch (error: Exception) { diagnose("response", error); throw MetadataException(MetadataFailure.INVALID_RESPONSE) }
                }
            }
        } finally { watcher.cancel() }
    }

    private fun parseAccount(text: String): XtreamAccount = parseSafely {
        val root = JSONObject(text)
        val info = root.getJSONObject("user_info")
        if (XtreamCatalogueParser.identifier(info.opt("auth")) != "1" || info.opt("status") != "Active")
            throw MetadataException(MetadataFailure.AUTHENTICATION)
        val expiry = if (info.isNull("exp_date")) null else {
            XtreamCatalogueParser.identifier(info.opt("exp_date"))?.toLongOrNull()
                ?: throw MetadataException(MetadataFailure.INVALID_RESPONSE)
        }
        if (expiry != null && expiry <= nowSeconds()) throw MetadataException(MetadataFailure.AUTHENTICATION)
        val formats = if (info.isNull("allowed_output_formats")) null else info.getJSONArray("allowed_output_formats")
        val allowed = formats?.let { array -> (0 until array.length()).map { array.getString(it) }.toSet() }
        val output = when {
            allowed == null || "ts" in allowed -> "ts"
            "m3u8" in allowed -> "m3u8"
            else -> throw MetadataException(MetadataFailure.INVALID_RESPONSE)
        }
        XtreamAccount(XtreamCatalogueParser.identifier(info.opt("max_connections"))?.toIntOrNull()?.takeIf { it > 0 }, expiry, output)
    }

    private fun parseCategories(text: String): Map<String, String> = parseSafely {
        val array = JSONArray(text)
        require(array.length() <= 10_000)
        buildMap {
            for (i in 0 until array.length()) {
                val row = array.getJSONObject(i)
                val id = requireNotNull(XtreamCatalogueParser.identifier(row.opt("category_id")))
                val name = row.opt("category_name") as? String
                require(!name.isNullOrBlank() && name.length <= 4096)
                require(get(id) == null || get(id) == name)
                put(id, name)
            }
        }
    }

    private inline fun <T> parseSafely(block: () -> T): T = try { block() }
        catch (error: Exception) { diagnose("metadata", error); throw error as? MetadataException ?: MetadataException(MetadataFailure.INVALID_RESPONSE) }

    private suspend fun json(url: HttpUrl, budget: Int): String = suspendCancellableCoroutine { continuation ->

        val call = http.newCall(Request.Builder().url(url).header("Accept", "application/json").header("Connection", "close").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                diagnose("network", e)
                if (continuation.isActive) continuation.resumeWithException(MetadataException(MetadataFailure.NETWORK))
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        if (response.code in listOf(301, 302, 303, 307, 308)) throw MetadataException(MetadataFailure.REDIRECT_REQUIRES_REVIEW)
                        if (response.code != 200) throw MetadataException(MetadataFailure.HTTP_STATUS, response.code)
                        val body = response.body ?: throw MetadataException(MetadataFailure.INVALID_RESPONSE)
                        if (body.contentLength() > budget) throw MetadataException(MetadataFailure.BODY_LIMIT)
                        val bytes = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        body.byteStream().use { stream ->
                            while (true) {
                                if (!continuation.isActive) return
                                val count = stream.read(buffer, 0, minOf(buffer.size, budget - bytes.size() + 1))
                                if (count < 0) break
                                if (bytes.size() + count > budget) throw MetadataException(MetadataFailure.BODY_LIMIT)
                                bytes.write(buffer, 0, count)
                            }
                        }
                        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
                        checkJsonEnvelope(text)
                        if (continuation.isActive) continuation.resume(text)
                    } catch (error: Exception) {
                        diagnose("response", error)
                        if (continuation.isActive) continuation.resumeWithException(error as? MetadataException ?: MetadataException(MetadataFailure.INVALID_RESPONSE))
                    }
                }
            }
        })
    }

    companion object {
        private const val SHORT_GUIDE_BYTES = 256 * 1024
        private const val INFO_BYTES = 4 * 1024 * 1024
        private const val LIST_BYTES = 192L * 1024 * 1024

        private fun diagnose(stage: String, error: Exception) {
            val location = error.stackTrace.firstOrNull()?.let { "${it.className}.${it.methodName}:${it.lineNumber}" }.orEmpty()
            val failure = (error as? MetadataException)?.let { listOfNotNull(it.failure.name, it.status).joinToString(" ") }.orEmpty()
            Logger.getLogger("NuvioXtream").warning("$stage ${error.javaClass.simpleName} $failure at $location")
        }

        fun guideUrl(connection: IptvSourceConnection): String = serverBase(connection).newBuilder()
            .addPathSegment("xmltv.php").addQueryParameter("username", connection.username)
            .addQueryParameter("password", connection.password).build().toString()

        fun streamUrl(connection: IptvSourceConnection, locator: String): String {
            val stream = locator.toHttpUrlOrNull()?.pathSegments?.lastOrNull()
                ?.takeIf { it.matches(Regex("[0-9]+\\.[A-Za-z0-9]{1,8}")) } ?: throw MetadataException(MetadataFailure.INVALID_ADDRESS)
            return serverBase(connection).newBuilder().addPathSegment("live").addPathSegment(requireNotNull(connection.username))
                .addPathSegment(requireNotNull(connection.password)).addPathSegment(stream).build().toString()
        }

        fun movieUrl(connection: IptvSourceConnection, streamId: String, extension: String): String = vodUrl(connection, "movie", streamId, extension)

        fun episodeUrl(connection: IptvSourceConnection, episodeId: String, extension: String): String = vodUrl(connection, "series", episodeId, extension)

        private fun vodUrl(connection: IptvSourceConnection, folder: String, id: String, extension: String): String {
            if (!id.matches(Regex("[0-9]{1,20}")) || !extension.matches(Regex("[A-Za-z0-9]{1,8}"))) throw MetadataException(MetadataFailure.INVALID_ADDRESS)
            return serverBase(connection).newBuilder().addPathSegment(folder).addPathSegment(requireNotNull(connection.username))
                .addPathSegment(requireNotNull(connection.password)).addPathSegment("$id.$extension").build().toString()
        }

        fun serverBase(connection: IptvSourceConnection): HttpUrl {
            val url = connection.endpoint.toHttpUrlOrNull()
                ?: throw MetadataException(MetadataFailure.INVALID_ADDRESS)
            if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null ||
                url.pathSegments.last().endsWith(".php", ignoreCase = true) ||
                listOf(connection.username, connection.password).any { it.isNullOrEmpty() || it.length > 4096 || it == "." || it == ".." || it.any { char -> char.code < 32 || char.code == 127 } })
                throw MetadataException(MetadataFailure.INVALID_ADDRESS)
            return if (url.encodedPath.endsWith('/')) url else url.newBuilder().addPathSegment("").build()
        }

        private fun checkJsonEnvelope(text: String) {
            StrictJson(text).validate()
        }
    }
}

internal class StrictJson(private val text: String) {
    private var offset = 0
    private fun peek(): Char = text.getOrNull(offset) ?: '\u0000'
    private fun space() { while (peek() in listOf(' ', '\t', '\r', '\n')) offset++ }
    private fun take(char: Char) { space(); require(peek() == char); offset++ }
    fun validate() {
        space(); require(peek() == '{' || peek() == '[')
        value(0); space(); require(offset == text.length)
    }
    private fun value(depth: Int) {
        require(depth <= 16); space()
        when (peek()) {
            '{' -> {
                offset++; space()
                if (peek() == '}') { offset++; return }
                val keys = HashSet<String>()
                while (true) {
                    space(); val start = offset; string()
                    require(keys.add(JSONTokener(text.substring(start, offset)).nextValue() as String))
                    take(':'); value(depth + 1); space()
                    if (peek() == '}') { offset++; return }
                    take(',')
                }
            }
            '[' -> {
                offset++; space()
                if (peek() == ']') { offset++; return }
                while (true) {
                    value(depth + 1); space()
                    if (peek() == ']') { offset++; return }
                    take(',')
                }
            }
            '"' -> string()
            't' -> literal("true")
            'f' -> literal("false")
            'n' -> literal("null")
            else -> number()
        }
    }
    private fun literal(value: String) { require(text.startsWith(value, offset)); offset += value.length }
    private fun string() {
        require(peek() == '"'); offset++
        val start = offset
        while (true) {
            require(offset < text.length && offset - start <= 65536)
            val char = text[offset++]
            if (char == '"') return
            require(char.code >= 32)
            if (char == '\\') {
                val escape = peek(); offset++
                if (escape == 'u') repeat(4) { require(peek() in "0123456789abcdefABCDEF"); offset++ }
                else require(escape in "\"\\/bfnrt")
            }
        }
    }
    private fun number() {
        if (peek() == '-') offset++
        if (peek() == '0') offset++ else { require(peek() in '1'..'9'); digits() }
        if (peek() == '.') { offset++; digits() }
        if (peek() == 'e' || peek() == 'E') { offset++; if (peek() == '+' || peek() == '-') offset++; digits() }
    }
    private fun digits() { require(peek() in '0'..'9'); while (peek() in '0'..'9') offset++ }
}

private class ListBudget(input: InputStream, private val maximum: Long) : FilterInputStream(input) {
    private var count = 0L
    override fun read(): Int = `in`.read().also { if (it >= 0) consumed(1) }
    override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
        `in`.read(bytes, offset, minOf(length.toLong(), maximum - count + 1).toInt()).also { if (it > 0) consumed(it) }
    private fun consumed(bytes: Int) {
        count += bytes
        if (count > maximum) throw MetadataException(MetadataFailure.BODY_LIMIT)
    }
}
