package com.nuvio.tv.core.iptv

import org.json.JSONArray
import org.json.JSONObject

data class HeldCatalogue(val decision: RefreshDecision, val previous: Int, val candidate: Int, val configurationVersion: Long, val heldAtMillis: Long)

class HeldCandidate(val channel: ChannelCandidate, val attributes: Map<String, String>) {
    override fun toString() = "HeldCandidate(metadata withheld)"
}

class HeldContent(val records: List<HeldCandidate>, val etag: String?, val lastModified: String?, val guideUrls: List<String>) {
    override fun toString() = "HeldContent(records=${records.size})"
}

enum class ReviewChoice { ACCEPT, KEEP }

object CatalogueReview {
    const val MAX_AGE_MILLIS = 14L * 24 * 60 * 60 * 1000

    fun holds(decision: RefreshDecision, previous: Int): Boolean =
        previous > 0 && (decision == RefreshDecision.SHRINK_REQUIRES_REVIEW || decision == RefreshDecision.EMPTY_REQUIRES_REVIEW)

    fun current(held: HeldCatalogue?, configurationVersion: Long, now: Long): HeldCatalogue? =
        held?.takeIf { it.configurationVersion == configurationVersion && now - it.heldAtMillis in 0..MAX_AGE_MILLIS }

    fun clearsHeld(decision: RefreshDecision): Boolean = decision == RefreshDecision.PUBLISH

    fun encodeSummary(held: HeldCatalogue): String = JSONObject().put("decision", held.decision.name).put("previous", held.previous)
        .put("candidate", held.candidate).put("config", held.configurationVersion).put("at", held.heldAtMillis).toString()

    fun decodeSummary(text: String): HeldCatalogue? = runCatching {
        val json = JSONObject(text)
        val decision = RefreshDecision.valueOf(json.getString("decision"))
        HeldCatalogue(decision, json.getInt("previous"), json.getInt("candidate"), json.getLong("config"), json.getLong("at"))
            .takeIf { holds(decision, it.previous) && it.candidate >= 0 }
    }.getOrNull()

    fun encodeContent(content: HeldContent): String = JSONObject().putOpt("etag", content.etag).putOpt("lastModified", content.lastModified)
        .put("guides", JSONArray(content.guideUrls))
        .put("records", JSONArray().apply { content.records.forEach { r ->
            put(JSONObject().put("name", r.channel.name).put("locator", r.channel.locator).putOpt("providerId", r.channel.providerId)
                .putOpt("guideId", r.channel.guideId).putOpt("variant", r.channel.variant).put("attributes", JSONObject(r.attributes)))
        } }).toString()

    fun decodeContent(text: String): HeldContent {
        val json = JSONObject(text)
        val records = json.getJSONArray("records").let { array ->
            require(array.length() <= 20_000)
            (0 until array.length()).map { index ->
                val r = array.getJSONObject(index)
                val attrs = r.getJSONObject("attributes")
                HeldCandidate(ChannelCandidate(r.getString("name"), r.getString("locator"), r.optional("providerId"), r.optional("guideId"), r.optional("variant")),
                    attrs.keys().asSequence().associateWith { attrs.getString(it) })
            }
        }
        val guides = json.optJSONArray("guides")?.let { array -> (0 until array.length()).map { array.getString(it) } }.orEmpty()
        return HeldContent(records, json.optional("etag"), json.optional("lastModified"), guides)
    }

    private fun JSONObject.optional(key: String): String? = if (!has(key) || isNull(key)) null else getString(key)
}
