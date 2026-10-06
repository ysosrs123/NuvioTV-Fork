package com.nuvio.tv.data.iptv

import com.nuvio.tv.core.iptv.ChannelCandidate
import com.nuvio.tv.core.iptv.XtreamCatalogueParser
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.logging.Logger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
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
            val locator = base.newBuilder().addPathSegment("live").addPathSegment(user).addPathSegment(pass)
                .addPathSegment("${row.providerId}.${account.output}").build().toString()
            val attributes = buildMap {
                row.categoryId?.let { id -> put("category-id", id); categories[id]?.let { put("group-title", it) } }
                put("archive-availability", row.archive.name)
                row.archiveDays?.let { put("archive-days", it.toString()) }
            }
            IptvCatalogueRecord(ChannelCandidate(row.name, locator, providerId = row.providerId, guideId = row.guideId), attributes)
        }
        return XtreamDownload(account, records, catalogue.canPublish)
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
        private fun diagnose(stage: String, error: Exception) {
            val location = error.stackTrace.firstOrNull()?.let { "${it.className}.${it.methodName}:${it.lineNumber}" }.orEmpty()
            val failure = (error as? MetadataException)?.failure?.name.orEmpty()
            Logger.getLogger("NuvioXtream").warning("$stage ${error.javaClass.simpleName} $failure at $location")
        }

        fun guideUrl(connection: IptvSourceConnection): String = serverBase(connection).newBuilder()
            .addPathSegment("xmltv.php").addQueryParameter("username", connection.username)
            .addQueryParameter("password", connection.password).build().toString()

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
