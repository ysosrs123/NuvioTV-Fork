package com.nuvio.tv.core.iptv

import java.net.URI
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

enum class SetupImportMode { MERGE, REPLACE }

class ExistingSource(val id: String, val kind: SetupKind, val endpoint: String, val username: String?) {
    override fun toString() = "ExistingSource(id=$id, kind=$kind)"
}

class ExistingGuide(val id: String, val endpoint: String) {
    override fun toString() = "ExistingGuide(id=$id)"
}

data class MatchableChannel(val id: String, val name: String, val providerId: String?, val locatorDigest: String?, val guideId: String?) {
    override fun toString() = "MatchableChannel(id=$id)"
}

class SetupImportPlan(
    val removeSources: List<String>, val removeGuides: List<String>,
    val createSources: List<BundleSource>, val reuseSources: Map<String, String>, val skippedSources: List<BundleSource>,
    val createGuides: List<BundleGuide>, val reuseGuides: Map<String, String>, val applySettings: Boolean,
) {
    override fun toString() = "SetupImportPlan(create=${createSources.size}, reuse=${reuseSources.size}, skipped=${skippedSources.size})"
}

data class SetupPendingImport(val links: List<String>?, val overlays: List<BundleOverlay>, val onlyUnset: Boolean) {
    override fun toString() = "SetupPendingImport(overlays=${overlays.size})"
}

object SetupMerge {
    fun identity(kind: SetupKind, endpoint: String?, username: String?): String? {
        val origin = SetupText.origin(endpoint) ?: return null
        val uri = try { URI(endpoint!!.trim()) } catch (_: Exception) { return null }
        val path = uri.rawPath.orEmpty().trimEnd('/')
        return when (kind) {
            SetupKind.M3U -> "m3u\u0000$origin$path?" + uri.rawQuery.orEmpty()
            SetupKind.XTREAM -> username?.takeIf(String::isNotEmpty)?.let { "xt\u0000$origin\u0000$it" }
            SetupKind.STALKER -> StalkerPortal.normalizeMac(username.orEmpty())?.let { "stb\u0000$origin$path\u0000" + it.uppercase(Locale.ROOT) }
            SetupKind.GUIDE -> null
        }
    }

    fun plan(bundle: SetupBundle, sources: List<ExistingSource>, guides: List<ExistingGuide>, mode: SetupImportMode): SetupImportPlan {
        val (usable, skipped) = bundle.sources.partition { it.complete }
        if (mode == SetupImportMode.REPLACE) return SetupImportPlan(sources.map { it.id }, guides.map { it.id }, usable, emptyMap(), skipped,
            bundle.guides, emptyMap(), bundle.settings != null)
        val known = sources.mapNotNull { source -> identity(source.kind, source.endpoint, source.username)?.let { it to source.id } }.toMap()
        val reuse = LinkedHashMap<String, String>()
        val create = mutableListOf<BundleSource>()
        for (source in usable) {
            val existing = identity(source.kind, source.endpoint, source.username)?.let(known::get)
            if (existing != null && existing !in reuse.values) reuse[source.key] = existing else create += source
        }
        val endpoints = guides.associate { it.endpoint.trim() to it.id }
        val reuseGuides = LinkedHashMap<String, String>()
        val createGuides = mutableListOf<BundleGuide>()
        for (guide in bundle.guides) endpoints[guide.endpoint.trim()]?.let { reuseGuides[guide.key] = it } ?: run { createGuides += guide }
        return SetupImportPlan(emptyList(), emptyList(), create, reuse, skipped, createGuides, reuseGuides, false)
    }

    fun links(existing: List<String>, imported: List<String>, limit: Int = SetupBundles.MAX_LINKED): List<String> = (existing + imported).distinct().take(limit)

    fun match(overlays: List<BundleOverlay>, channels: List<MatchableChannel>): List<Pair<BundleOverlay, String>> {
        val used = HashSet<String>()
        val matched = arrayOfNulls<String>(overlays.size)
        val passes: List<Pair<(BundleOverlay) -> String?, (MatchableChannel) -> String?>> = listOf(
            { o: BundleOverlay -> o.match.providerId?.takeIf(String::isNotBlank)?.let { "$it\u0000" + fold(o.match.name) } } to
                { c: MatchableChannel -> c.providerId?.takeIf(String::isNotBlank)?.let { "$it\u0000" + fold(c.name) } },
            { o: BundleOverlay -> o.match.providerId?.takeIf(String::isNotBlank) } to { c: MatchableChannel -> c.providerId?.takeIf(String::isNotBlank) },
            { o: BundleOverlay -> o.match.locator?.takeIf(String::isNotBlank) } to { c: MatchableChannel -> c.locatorDigest?.takeIf(String::isNotBlank) },
            { o: BundleOverlay -> o.match.guideId?.takeIf(String::isNotBlank)?.let { "$it\u0000" + fold(o.match.name) } } to
                { c: MatchableChannel -> c.guideId?.takeIf(String::isNotBlank)?.let { "$it\u0000" + fold(c.name) } },
            { o: BundleOverlay -> fold(o.match.name).takeIf(String::isNotEmpty) } to { c: MatchableChannel -> fold(c.name).takeIf(String::isNotEmpty) },
        )
        for ((overlayKey, channelKey) in passes) {
            val index = channels.filter { it.id !in used }.mapNotNull { c -> channelKey(c)?.let { it to c.id } }.groupBy({ it.first }, { it.second })
            val wanted = overlays.indices.filter { matched[it] == null }.mapNotNull { i -> overlayKey(overlays[i])?.let { it to i } }
            val demand = wanted.groupingBy { it.first }.eachCount()
            for ((key, i) in wanted) {
                val ids = index[key] ?: continue
                if (ids.size != 1 || demand[key] != 1 || ids[0] in used) continue
                matched[i] = ids[0]
                used += ids[0]
            }
        }
        return overlays.indices.mapNotNull { i -> matched[i]?.let { overlays[i] to it } }
    }

    fun encodePending(pending: SetupPendingImport): String = JSONObject().put("onlyUnset", pending.onlyUnset)
        .putOpt("links", pending.links?.let(::JSONArray))
        .put("overlays", JSONArray().apply { pending.overlays.forEach { put(SetupBundles.overlay(it)) } }).toString()

    fun decodePending(text: String): SetupPendingImport {
        val json = JSONObject(text)
        val links = json.optJSONArray("links")?.let { array -> (0 until array.length()).map { array.getString(it) } }
        require(links == null || (links.size <= SetupBundles.MAX_LINKED && links.all { it.length in 1..80 }))
        val overlays = json.getJSONArray("overlays").let { array ->
            require(array.length() <= SetupBundles.MAX_OVERLAYS)
            (0 until array.length()).map { SetupBundles.readOverlay(array.getJSONObject(it), null, null) }
        }
        return SetupPendingImport(links, overlays, json.getBoolean("onlyUnset"))
    }

    private fun fold(name: String): String = foldSearchText(name).trim()
}
