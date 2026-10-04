package com.nuvio.tv.core.iptv

import org.json.JSONArray
import org.json.JSONObject

enum class ArchiveAvailability { UNKNOWN, UNAVAILABLE, ADVERTISED }
data class XtreamChannel(
    val providerId: String,
    val name: String,
    val categoryId: String?,
    val guideId: String?,
    val archive: ArchiveAvailability,
    val archiveDays: Int?,
)
data class XtreamCatalogue(val channels: List<XtreamChannel>, val invalidRows: Int) {
    val canPublish: Boolean get() = channels.isNotEmpty() && invalidRows == 0
}

/** Metadata interpretation only; flags advertise archive availability, never prove playback. */
class XtreamCatalogueParser(private val maxCharacters: Int = 8 * 1024 * 1024, private val maxChannels: Int = 20_000) {
    init { require(maxCharacters > 0 && maxChannels > 0) }
    fun parse(json: String): XtreamCatalogue {
        require(json.length <= maxCharacters) { "Catalogue size limit" }
        val array = JSONArray(json)
        require(array.length() <= maxChannels) { "Channel count limit" }
        val channels = linkedMapOf<String, XtreamChannel>()
        var invalid = 0
        for (index in 0 until array.length()) {
            val row = array.optJSONObject(index)
            val id = row?.opt("stream_id")?.let(::identifier)
            val name = row?.opt("name") as? String
            if (row == null || id == null || name.isNullOrBlank()) { invalid++; continue }
            val flag = when (val value = row.opt("tv_archive")) {
                true, 1, 1L, "1" -> ArchiveAvailability.ADVERTISED
                false, 0, 0L, "0" -> ArchiveAvailability.UNAVAILABLE
                else -> ArchiveAvailability.UNKNOWN
            }
            val days = identifier(row.opt("tv_archive_duration"))?.toIntOrNull()?.takeIf { it > 0 }
            val channel = XtreamChannel(id, name, identifier(row.opt("category_id")),
                (row.opt("epg_channel_id") as? String)?.takeIf(String::isNotBlank), flag,
                days.takeIf { flag == ArchiveAvailability.ADVERTISED })
            val previous = channels[id]
            if (previous != null && previous != channel) invalid++ else channels[id] = channel
        }
        return XtreamCatalogue(channels.values.toList(), invalid)
    }

    companion object {
        internal fun identifier(value: Any?): String? = when (value) {
            is String -> value.takeIf { it.isNotEmpty() && it.all { char -> char in '0'..'9' } }
            is Int -> value.takeIf { it >= 0 }?.toString()
            is Long -> value.takeIf { it >= 0 }?.toString()
            else -> null // Reject null/JSON null, booleans, fractional and floating point IDs.
        }
    }
}
