package com.nuvio.tv.core.iptv

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

object XtreamShortGuide {
    fun channelId(streamId: String) = "xtream:$streamId"

    fun parse(json: String, streamId: String, maxListings: Int = 24, titleCharacters: Int = 512, descriptionCharacters: Int = 400): List<GuideProgramme> {
        require(maxListings > 0 && titleCharacters > 0 && descriptionCharacters > 0)
        val listings = when (val root = JSONObject(json).opt("epg_listings")) {
            is JSONArray -> root
            null, JSONObject.NULL -> return emptyList()
            else -> throw IllegalArgumentException("Unexpected short guide")
        }
        require(listings.length() <= 1_000) { "Short guide listing limit" }
        return buildList {
            for (index in 0 until listings.length()) {
                val row = listings.optJSONObject(index) ?: continue
                val start = seconds(row.opt("start_timestamp")) ?: continue
                val stop = seconds(row.opt("stop_timestamp"))?.takeIf { it > start } ?: continue
                val title = text(row.opt("title"))?.let { shortenGuideText(it, titleCharacters) } ?: continue
                val language = (row.opt("lang") as? String)?.trim()?.takeIf { it.matches(languageCode) }
                val description = text(row.opt("description"))?.let { shortenGuideText(it, descriptionCharacters) }
                add(GuideProgramme(channelId(streamId), timestamp(start), timestamp(stop), listOf(LocalizedGuideText(title, language)),
                    listOfNotNull(description?.let { LocalizedGuideText(it, language) })))
            }
        }.distinctBy { it.start.epochMillis to it.stop?.epochMillis }.sortedBy { it.start.epochMillis }.take(maxListings)
    }

    internal fun text(value: Any?): String? {
        val raw = (value as? String)?.trim()?.takeIf { it.isNotEmpty() && it.length <= 64 * 1024 } ?: return null
        val decoded = try {
            val bytes = Base64.getDecoder().decode(raw)
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString().takeIf { text -> text.none { it.isISOControl() && it !in "\t\r\n" } }
        } catch (_: Exception) { null }
        return (decoded ?: raw).trim().takeIf(String::isNotEmpty)
    }

    private val languageCode = Regex("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})?")
    private const val MAX_SECONDS = 32_503_680_000L

    private fun seconds(value: Any?): Long? = when (value) {
        is Int -> value.toLong()
        is Long -> value
        is String -> value.trim().takeIf { it.length in 1..12 && it.all(Char::isDigit) }?.toLong()
        else -> null
    }?.takeIf { it in 1..MAX_SECONDS }

    private fun timestamp(seconds: Long) = GuideTimestamp(seconds * 1000, 14, seconds.toString())
}
