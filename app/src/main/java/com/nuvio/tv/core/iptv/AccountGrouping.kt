package com.nuvio.tv.core.iptv

import java.net.URI
import java.security.MessageDigest
import java.util.Locale

data class AccountGroupHint(val sourceId: String, val xtream: Boolean, val endpoint: String, val username: String?, val stalker: Boolean = false) {
    override fun toString() = "AccountGroupHint(sourceId=$sourceId)"
}

const val DEFAULT_ACCOUNT_ID = "shared-default"

fun suggestAccountGroups(hints: List<AccountGroupHint>): Map<String, String> = hints.associate { hint ->
    hint.sourceId to (providerKey(hint)?.let { key -> (if (hint.xtream) "xt-" else if (hint.stalker) "stb-" else "m3u-") + digest(key) } ?: DEFAULT_ACCOUNT_ID)
}

object AccountGroups {
    const val PREFIX = "grp-"
    const val MAX_LIMIT = 16

    fun isGroup(id: String, sources: Int): Boolean =
        sources >= 2 || id.startsWith(PREFIX) || (sources >= 1 && !id.startsWith("src-"))

    fun newId(random: java.util.Random): String = PREFIX + (1..12).map { "0123456789abcdef"[random.nextInt(16)] }.joinToString("")

    fun suggestions(suggested: Map<String, String>, current: Map<String, String>): List<Pair<String, List<String>>> = suggested
        .filterValues { it != DEFAULT_ACCOUNT_ID }.entries.groupBy({ it.value }, { it.key })
        .filter { (_, sources) -> sources.size >= 2 && sources.map { current[it] }.distinct().size > 1 }
        .map { (id, sources) -> id to sources }.sortedBy { it.first }
}

fun <T> moveItem(items: List<T>, from: Int, to: Int): List<T> {
    require(from in items.indices && to in items.indices)
    return items.toMutableList().apply { add(to, removeAt(from)) }
}

private fun providerKey(hint: AccountGroupHint): String? {
    val uri = try { URI(hint.endpoint.trim()) } catch (_: Exception) { return null }
    val host = uri.host?.lowercase(Locale.ROOT)?.takeIf(String::isNotBlank) ?: return null
    val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
    val port = if (uri.port >= 0) uri.port else if (scheme == "https") 443 else 80
    if (hint.stalker) return StalkerPortal.normalizeMac(hint.username)?.let { "$host:$port\u0000$it" }
    if (!hint.xtream) return "$host:$port"
    val user = hint.username?.takeIf(String::isNotEmpty) ?: return null
    return "$host:$port\u0000$user"
}

private fun digest(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).take(8).joinToString("") { "%02x".format(it) }
