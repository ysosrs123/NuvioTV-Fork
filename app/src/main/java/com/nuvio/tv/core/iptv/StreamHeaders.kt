package com.nuvio.tv.core.iptv

import java.io.ByteArrayOutputStream
import java.util.Locale
import org.json.JSONObject

object StreamHeaders {
    const val USER_AGENT = "http-user-agent"
    const val REFERRER = "http-referrer"
    const val ORIGIN = "http-origin"
    const val MAX_VALUE = 512
    val attributes = setOf(USER_AGENT, REFERRER, ORIGIN)
    private val requestNames = mapOf(USER_AGENT to "User-Agent", REFERRER to "Referer", ORIGIN to "Origin")
    private val kodiHeaders = setOf("inputstream.adaptive.stream_headers", "inputstream.adaptive.manifest_headers", "inputstream.adaptive.common_headers")

    fun attribute(name: String): String? = when (name.trim().lowercase(Locale.ROOT)) {
        "user-agent", "useragent", "http-user-agent" -> USER_AGENT
        "referer", "referrer", "http-referer", "http-referrer" -> REFERRER
        "origin", "http-origin" -> ORIGIN
        else -> null
    }

    fun clean(value: String): String? = value.trim().removeSurrounding("\"").trim()
        .takeIf { it.isNotEmpty() && it.length <= MAX_VALUE && it.all { c -> c == '\t' || c in ' '..'~' } }

    fun directive(line: String): Map<String, String> = when {
        line.startsWith("#EXTVLCOPT:") -> line.substringAfter(':').let { option ->
            if ('=' !in option) emptyMap() else pairs(listOf(option.substringBefore('=') to option.substringAfter('=')))
        }
        line.startsWith("#EXTHTTP:") -> try {
            val json = JSONObject(line.substringAfter(':'))
            pairs(json.keys().asSequence().mapNotNull { key -> (json.opt(key) as? String)?.let { key to it } }.toList())
        } catch (_: Exception) { emptyMap() }
        line.startsWith("#KODIPROP:") -> line.substringAfter(':').let { property ->
            if (property.substringBefore('=').trim().lowercase(Locale.ROOT) in kodiHeaders) query(property.substringAfter('=', "")) else emptyMap()
        }
        else -> emptyMap()
    }

    fun splitLocator(line: String): Pair<String, Map<String, String>> {
        val bar = line.indexOf('|')
        return if (bar < 0) line to emptyMap() else line.substring(0, bar).trim() to query(line.substring(bar + 1))
    }

    fun requestHeaders(attributes: Map<String, String>): Map<String, String> =
        requestNames.mapNotNull { (key, name) -> attributes[key]?.let(::clean)?.let { name to it } }.toMap()

    private fun query(text: String): Map<String, String> =
        pairs(text.split('&').filter { '=' in it }.take(16).map { decode(it.substringBefore('=')) to decode(it.substringAfter('=')) })

    private fun pairs(entries: List<Pair<String, String>>): Map<String, String> {
        val result = linkedMapOf<String, String>()
        for ((name, value) in entries) {
            val key = attribute(name) ?: continue
            clean(value)?.let { result[key] = it }
        }
        return result
    }

    private fun decode(text: String): String {
        if ('%' !in text) return text
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val hex = if (c == '%' && i + 2 < text.length) text.substring(i + 1, i + 3).takeIf { h -> h.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' } }?.toInt(16) else null
            if (hex != null) { out.write(hex); i += 3 }
            else { out.write(c.toString().toByteArray(Charsets.UTF_8)); i++ }
        }
        return out.toString(Charsets.UTF_8.name())
    }
}
