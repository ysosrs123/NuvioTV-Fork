package com.nuvio.tv.core.iptv

import org.json.JSONArray
import org.json.JSONObject

data class SetupProfile(val id: Int, val name: String, val locked: Boolean)

class SetupChannel(val id: String, val name: String, val guideFeed: String?, val guideId: String?) {
    override fun toString() = "SetupChannel(id=$id)"
}

class SetupGuideChannel(val id: String, val name: String) {
    override fun toString() = "SetupGuideChannel(values withheld)"
}

interface SetupLookup {
    fun channels(sourceId: String, query: String): List<SetupChannel>?
    fun channel(sourceId: String, channelId: String): SetupChannel?
    fun guideChannels(feedId: String, query: String): List<SetupGuideChannel>?
}

class SetupGuideLinks(val sourceId: String, val feeds: List<String>) : SetupChange {
    override fun toString() = "SetupGuideLinks(feeds=${feeds.size})"
}

class SetupChannelGuide(val sourceId: String, val channelId: String, val feedId: String?, val guideId: String?, val guideName: String?) : SetupChange {
    val automatic: Boolean get() = feedId == null
    override fun toString() = "SetupChannelGuide(automatic=$automatic, values withheld)"
}

class SetupProfileChoice(val profileId: Int) : SetupChange {
    override fun toString() = "SetupProfileChoice(profile=$profileId)"
}

object SetupAssignments {
    const val MAX_RESULTS = 40
    const val MAX_QUERY = 64
    private val ID = Regex("[A-Za-z0-9_-]{1,80}")

    fun query(value: String?): String? {
        val text = value?.trim() ?: return ""
        if (text.length > MAX_QUERY || text.any { it.isISOControl() || it.category == CharCategory.FORMAT }) return null
        return text
    }

    fun parseLinks(body: String): SetupGuideLinks {
        val json = parse(body)
        val source = id(json, "source")
        val array = json.opt("guides") as? JSONArray ?: throw SetupInputException("guides")
        if (array.length() > SetupBundles.MAX_LINKED) throw SetupInputException("guides")
        val feeds = (0 until array.length()).map { (array.opt(it) as? String)?.takeIf(ID::matches) ?: throw SetupInputException("guides") }
        if (feeds.distinct().size != feeds.size) throw SetupInputException("guides")
        return SetupGuideLinks(source, feeds)
    }

    fun parseChannelGuide(body: String): SetupChannelGuide {
        val json = parse(body)
        val source = id(json, "source")
        val channel = id(json, "channel")
        val feed = optional(json, "feed", 80)?.also { if (!ID.matches(it)) throw SetupInputException("feed") }
        val guide = optional(json, "guide", 4096)?.takeIf(String::isNotBlank)
        val name = optional(json, "guideName", 512)
        if ((feed == null) != (guide == null)) throw SetupInputException("guide")
        return SetupChannelGuide(source, channel, feed, guide, if (feed == null) null else name)
    }

    fun parseProfile(body: String): SetupProfileChoice {
        val json = parse(body)
        val id = json.opt("profile") as? Int ?: throw SetupInputException("profile")
        if (id < 0) throw SetupInputException("profile")
        return SetupProfileChoice(id)
    }

    fun checkLinks(change: SetupGuideLinks, listing: SetupListing): String? {
        if (listing.find(SetupKind.M3U, change.sourceId) == null) return "missing"
        if (change.feeds.any { feed -> listing.guides.none { it.id == feed } }) return "missing"
        return if (listing.links[change.sourceId].orEmpty() == change.feeds) "unchanged" else null
    }

    fun checkChannelGuide(change: SetupChannelGuide, listing: SetupListing, current: SetupChannel?): String? {
        if (listing.find(SetupKind.M3U, change.sourceId) == null || current == null) return "missing"
        if (change.feedId != null && change.feedId !in listing.links[change.sourceId].orEmpty()) return "missing"
        return if (current.guideFeed == change.feedId && current.guideId == change.guideId) "unchanged" else null
    }

    fun checkProfile(change: SetupProfileChoice, listing: SetupListing): String? {
        val profile = listing.profiles.firstOrNull { it.id == change.profileId } ?: return "missing"
        if (profile.id == listing.profile) return "unchanged"
        return if (profile.locked) "locked" else null
    }

    fun channelsJson(channels: List<SetupChannel>): String = JSONObject().put("channels", JSONArray().apply {
        channels.forEach { put(JSONObject().put("id", it.id).put("name", it.name).putOpt("feed", it.guideFeed).putOpt("guide", it.guideId)) }
    }).toString()

    fun guideChannelsJson(channels: List<SetupGuideChannel>): String = JSONObject().put("channels", JSONArray().apply {
        channels.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) }
    }).toString()

    private fun parse(body: String): JSONObject {
        if (body.length > SetupDrafts.MAX_BODY_BYTES || !SetupDrafts.shallowJson(body)) throw SetupInputException("body")
        return try { JSONObject(body) } catch (_: Exception) { throw SetupInputException("body") }
    }

    private fun id(json: JSONObject, key: String): String = optional(json, key, 80)?.takeIf(ID::matches) ?: throw SetupInputException(key)

    private fun optional(json: JSONObject, key: String, limit: Int): String? {
        if (!json.has(key) || json.isNull(key)) return null
        val value = json.opt(key) as? String ?: throw SetupInputException(key)
        if (value.length > limit || value.any { it.isISOControl() || it.category == CharCategory.FORMAT }) throw SetupInputException(key)
        return value
    }
}
