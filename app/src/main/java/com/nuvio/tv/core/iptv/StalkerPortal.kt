package com.nuvio.tv.core.iptv

import java.net.URI
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

data class StalkerChannel(val id: String, val name: String, val number: Int?, val command: String,
    val genreId: String?, val guideId: String?, val logo: String? = null) {
    override fun toString(): String = "StalkerChannel(id=$id)"
}

data class StalkerCatalogue(val channels: List<StalkerChannel>, val invalidRows: Int) {
    val canPublish: Boolean get() = channels.isNotEmpty() && invalidRows == 0
}

object StalkerPortal {
    private val MAC = Regex("(?i)^([0-9a-f]{2}:){5}[0-9a-f]{2}$")
    private val TOKEN = Regex("^[A-Za-z0-9._~+/=-]{1,512}$")
    private val COMMAND_PREFIXES = listOf("ffmpeg ", "ffrt ", "ffrt2 ", "ffrt3 ", "auto ")

    fun normalizeMac(value: String?): String? = value?.trim()?.takeIf(MAC::matches)?.uppercase(Locale.ROOT)

    fun apiUrl(portal: String): String? {
        val uri = try { URI(portal.trim()) } catch (_: Exception) { return null }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.rawUserInfo != null ||
            uri.rawQuery != null || uri.rawFragment != null) return null
        val origin = "$scheme://${uri.rawAuthority}"
        val segments = (uri.rawPath ?: "").split('/').filter(String::isNotEmpty)
        val stalker = segments.indexOf("stalker_portal")
        if (stalker >= 0) return origin + "/" + segments.take(stalker + 1).joinToString("/") + "/server/load.php"
        if (segments.lastOrNull()?.endsWith(".php", ignoreCase = true) == true) return origin + "/" + segments.joinToString("/")
        val base = if (segments.lastOrNull() == "c") segments.dropLast(1) else segments
        return origin + (if (base.isEmpty()) "" else "/" + base.joinToString("/")) + "/portal.php"
    }

    fun parseToken(json: String): String =
        (JSONObject(json).getJSONObject("js").opt("token") as? String)?.takeIf(TOKEN::matches)
            ?: throw IllegalArgumentException("Missing portal token")

    fun requireProfile(json: String) {
        val profile = JSONObject(json).opt("js") as? JSONObject ?: throw IllegalArgumentException("Missing portal profile")
        val status = profile.opt("status")?.toString()
        if (profile.opt("blocked")?.toString() == "1" || (status != null && status != "0") ||
            !(profile.opt("block_msg") as? String).isNullOrBlank()) throw SecurityException("Portal account blocked")
    }

    fun parseGenres(json: String, maxGenres: Int = 10_000): Map<String, String> {
        val array = JSONObject(json).get("js") as? JSONArray ?: throw IllegalArgumentException("Missing portal genres")
        require(array.length() <= maxGenres) { "Genre limit" }
        return buildMap {
            for (index in 0 until array.length()) {
                val row = array.optJSONObject(index) ?: continue
                val id = identifier(row.opt("id")) ?: continue
                val title = (row.opt("title") as? String)?.takeIf { it.isNotBlank() && it.length <= 4096 } ?: continue
                if (id != "*") put(id, title)
            }
        }
    }

    fun parseChannels(json: String, maxChannels: Int = 20_000, logoBase: String? = null): StalkerCatalogue {
        val js = JSONObject(json).get("js")
        val array = when (js) {
            is JSONArray -> js
            is JSONObject -> js.optJSONArray("data") ?: throw IllegalArgumentException("Missing portal channels")
            else -> throw IllegalArgumentException("Missing portal channels")
        }
        require(array.length() <= maxChannels) { "Channel count limit" }
        val channels = linkedMapOf<String, StalkerChannel>()
        var invalid = 0
        for (index in 0 until array.length()) {
            val row = array.optJSONObject(index)
            val id = row?.opt("id")?.let(::identifier)
            val name = (row?.opt("name") as? String)?.trim()
            val command = (row?.opt("cmd") as? String)?.trim()
            if (row == null || id == null || name.isNullOrEmpty() || name.length > 4096 || command.isNullOrEmpty() ||
                command.length > 16_384 || streamUrl(command) == null) { invalid++; continue }
            val channel = StalkerChannel(id, name, identifier(row.opt("number"))?.toIntOrNull(), command,
                identifier(row.opt("tv_genre_id")), (row.opt("xmltv_id") as? String)?.takeIf { it.isNotBlank() && it.length <= 4096 },
                channelLogoUrl(row.opt("logo") as? String, logoBase))
            val previous = channels[id]
            if (previous != null && previous != channel) invalid++ else channels[id] = channel
        }
        return StalkerCatalogue(channels.values.toList(), invalid)
    }

    fun parseLink(json: String): String {
        val command = (JSONObject(json).getJSONObject("js").opt("cmd") as? String)?.trim()
            ?: throw IllegalArgumentException("Missing stream link")
        return streamUrl(command) ?: throw IllegalArgumentException("Unsupported stream link")
    }

    fun streamUrl(command: String): String? {
        var value = command.trim()
        COMMAND_PREFIXES.firstOrNull { value.startsWith(it, ignoreCase = true) }?.let { value = value.substring(it.length).trim() }
        val uri = try { URI(value) } catch (_: Exception) { return null }
        return value.takeIf { uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.rawUserInfo == null }
    }

    private fun identifier(value: Any?): String? = when (value) {
        is String -> value.trim().takeIf { it.isNotEmpty() && it.length <= 64 && it.all { c -> c.isLetterOrDigit() || c in "_-*" } }
        is Int -> value.takeIf { it >= 0 }?.toString()
        is Long -> value.takeIf { it >= 0 }?.toString()
        else -> null
    }
}
