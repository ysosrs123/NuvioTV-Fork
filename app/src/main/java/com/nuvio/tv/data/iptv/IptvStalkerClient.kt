package com.nuvio.tv.data.iptv

import com.nuvio.tv.core.iptv.ChannelCandidate
import com.nuvio.tv.core.iptv.StalkerPortal
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
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

data class StalkerDownload(val records: List<IptvCatalogueRecord>, val canPublish: Boolean) {
    override fun toString() = "StalkerDownload(channels=${records.size}, canPublish=$canPublish)"
}

class IptvStalkerClient(private val http: OkHttpClient = IptvMetadataClient.newClient(),
    private val maxBodyBytes: Int = 8 * 1024 * 1024) {
    init {
        require(maxBodyBytes in 1..16 * 1024 * 1024)
        require(!http.followRedirects && !http.followSslRedirects && !http.retryOnConnectionFailure)
    }

    suspend fun catalogue(connection: IptvSourceConnection): StalkerDownload {
        val session = open(connection)
        val genres = parse { StalkerPortal.parseGenres(session.call("itv", "get_genres", budget = 1024 * 1024)) }
        currentCoroutineContext().ensureActive()
        val catalogue = parse { StalkerPortal.parseChannels(session.call("itv", "get_all_channels", budget = maxBodyBytes)) }
        val records = catalogue.channels.map { channel ->
            val attributes = buildMap {
                put(COMMAND_ATTRIBUTE, channel.command)
                channel.genreId?.let { id -> put("category-id", id); genres[id]?.let { put("group-title", it) } }
                channel.number?.let { put("channel-number", it.toString()) }
            }
            IptvCatalogueRecord(ChannelCandidate(channel.name, requireNotNull(StalkerPortal.streamUrl(channel.command)),
                providerId = channel.id, guideId = channel.guideId), attributes)
        }
        return StalkerDownload(records, catalogue.canPublish)
    }

    suspend fun streamUrl(connection: IptvSourceConnection, command: String): String {
        require(command.length <= 16_384)
        val session = open(connection)
        return parse { StalkerPortal.parseLink(session.call("itv", "create_link", "cmd" to command, budget = 64 * 1024)) }
    }

    private suspend fun open(connection: IptvSourceConnection): Session {
        val api = apiUrl(connection)
        val mac = requireNotNull(StalkerPortal.normalizeMac(connection.username))
        val anonymous = Session(api, mac, null)
        val token = parse { StalkerPortal.parseToken(anonymous.call("stb", "handshake", "token" to "", budget = 64 * 1024)) }
        currentCoroutineContext().ensureActive()
        val session = Session(api, mac, token)
        try { StalkerPortal.requireProfile(session.call("stb", "get_profile", budget = 256 * 1024)) }
        catch (_: SecurityException) { throw MetadataException(MetadataFailure.AUTHENTICATION) }
        catch (error: MetadataException) { throw error }
        catch (_: Exception) { throw MetadataException(MetadataFailure.INVALID_RESPONSE) }
        currentCoroutineContext().ensureActive()
        return session
    }

    private inline fun <T> parse(block: () -> T): T = try { block() }
        catch (error: MetadataException) { throw error }
        catch (_: Exception) { throw MetadataException(MetadataFailure.INVALID_RESPONSE) }

    private inner class Session(val api: HttpUrl, val mac: String, val token: String?) {
        suspend fun call(type: String, action: String, vararg extra: Pair<String, String>, budget: Int): String {
            val url = api.newBuilder().addQueryParameter("type", type).addQueryParameter("action", action)
                .apply { extra.forEach { (key, value) -> addQueryParameter(key, value) } }
                .addQueryParameter("JsHttpRequest", "1-xml").build()
            val request = Request.Builder().url(url).header("Accept", "application/json").header("Connection", "close")
                .header("User-Agent", USER_AGENT).header("X-User-Agent", "Model: MAG250; Link: WiFi")
                .header("Cookie", "mac=${mac.replace(":", "%3A")}; stb_lang=en; timezone=UTC")
                .apply { token?.let { header("Authorization", "Bearer $it") } }.build()
            return text(request, minOf(budget, maxBodyBytes))
        }
    }

    private suspend fun text(request: Request, budget: Int): String = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(MetadataException(MetadataFailure.NETWORK))
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        if (response.code in listOf(301, 302, 303, 307, 308)) throw MetadataException(MetadataFailure.REDIRECT_REQUIRES_REVIEW)
                        if (response.code == 401 || response.code == 403) throw MetadataException(MetadataFailure.AUTHENTICATION)
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
                        StrictJson(text).validate()
                        if (continuation.isActive) continuation.resume(text)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error as? MetadataException ?: MetadataException(MetadataFailure.INVALID_RESPONSE))
                    }
                }
            }
        })
    }

    companion object {
        const val COMMAND_ATTRIBUTE = "stalker-cmd"
        private const val USER_AGENT = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3"

        fun apiUrl(connection: IptvSourceConnection): HttpUrl {
            StalkerPortal.normalizeMac(connection.username) ?: throw MetadataException(MetadataFailure.INVALID_ADDRESS)
            return StalkerPortal.apiUrl(connection.endpoint)?.toHttpUrlOrNull() ?: throw MetadataException(MetadataFailure.INVALID_ADDRESS)
        }
    }
}
