package com.nuvio.tv.core.iptv

import java.net.URI
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

class BundleSource(val key: String, val label: String, val kind: SetupKind, val endpoint: String?, val username: String? = null,
    val password: String? = null, val account: String? = null, val connections: Int? = null) {
    val complete: Boolean get() = endpoint != null && when (kind) {
        SetupKind.XTREAM -> !username.isNullOrEmpty() && !password.isNullOrEmpty()
        SetupKind.STALKER -> StalkerPortal.normalizeMac(username.orEmpty()) != null
        else -> true
    }
    fun withoutLogin(): BundleSource = BundleSource(key, label, kind, if (kind == SetupKind.M3U && SetupBundles.carriesLogin(endpoint)) null else endpoint,
        null, null, account, connections)
    override fun equals(other: Any?) = other is BundleSource && key == other.key && label == other.label && kind == other.kind && endpoint == other.endpoint &&
        username == other.username && password == other.password && account == other.account && connections == other.connections
    override fun hashCode() = key.hashCode()
    override fun toString() = "BundleSource(key=$key, kind=$kind, values withheld)"
}

data class BundleAccount(val key: String, val label: String, val limit: Int)

class BundleGuide(val key: String, val label: String, val endpoint: String) {
    override fun equals(other: Any?) = other is BundleGuide && key == other.key && label == other.label && endpoint == other.endpoint
    override fun hashCode() = key.hashCode()
    override fun toString() = "BundleGuide(key=$key, values withheld)"
}

data class BundleLinks(val source: String, val guides: List<String>)

data class ChannelMatch(val name: String, val providerId: String? = null, val locator: String? = null, val guideId: String? = null) {
    override fun toString() = "ChannelMatch(values withheld)"
}

data class BundleOverlay(val source: String, val match: ChannelMatch, val customName: String? = null, val favourite: Int? = null,
    val hidden: Boolean = false, val format: String = "auto", val guideFeed: String? = null, val guideChannel: String? = null) {
    override fun toString() = "BundleOverlay(source=$source, values withheld)"
}

data class BundleSettings(val live: SetupSettings, val preview: Boolean = true, val stats: Boolean = false, val theme: String? = null,
    val black: Boolean = false, val solid: Boolean = true, val plain: Boolean = true)

data class SetupBundleSummary(val sources: Int, val needLogin: Int, val guides: Int, val groups: Int, val overlays: Int, val settings: Boolean, val logins: Boolean)

data class SetupBundle(val logins: Boolean, val sources: List<BundleSource>, val accounts: List<BundleAccount> = emptyList(),
    val guides: List<BundleGuide> = emptyList(), val links: List<BundleLinks> = emptyList(), val overlays: List<BundleOverlay> = emptyList(),
    val settings: BundleSettings? = null) {

    fun withoutLogins(): SetupBundle {
        val kept = guides.filterNot { SetupBundles.carriesLogin(it.endpoint) }
        val keys = kept.map { it.key }.toSet() + SetupBundles.PROVIDER
        return SetupBundle(false, sources.map { it.withoutLogin() }, accounts, kept,
            links.map { it.copy(guides = it.guides.filter(keys::contains)) },
            overlays.map { if (it.guideFeed != null && it.guideFeed !in keys) it.copy(guideFeed = null, guideChannel = null) else it }, settings)
    }

    fun summary() = SetupBundleSummary(sources.count { it.complete }, sources.count { !it.complete }, guides.size, accounts.size, overlays.size, settings != null, logins)

    override fun toString() = "SetupBundle(sources=${sources.size}, guides=${guides.size}, overlays=${overlays.size})"
}

object SetupBundles {
    const val FORMAT = "nuvio-livetv-setup"
    const val VERSION = 1
    const val PROVIDER = "provider"
    const val MAX_SOURCES = 64
    const val MAX_GUIDES = 200
    const val MAX_LINKED = 16
    const val MAX_OVERLAYS = 200_000
    private val KEY = Regex("[asg][0-9]{1,4}")
    private val FORMATS = listOf("auto", "hls", "mpegts")

    fun locatorDigest(locator: String): String = SetupPairing.hex(MessageDigest.getInstance("SHA-256").digest(locator.toByteArray(Charsets.UTF_8))).take(32)

    fun carriesLogin(endpoint: String?): Boolean {
        val uri = try { URI(endpoint ?: return false) } catch (_: Exception) { return true }
        return uri.rawUserInfo != null || !uri.rawQuery.isNullOrEmpty()
    }

    fun encode(bundle: SetupBundle): String = JSONObject().put("format", FORMAT).put("version", VERSION).put("logins", bundle.logins)
        .put("sources", JSONArray().apply { bundle.sources.forEach { s ->
            put(JSONObject().put("key", s.key).put("label", s.label).put("kind", s.kind.wire).putOpt("endpoint", s.endpoint)
                .putOpt("username", s.username).putOpt("password", s.password).putOpt("account", s.account).putOpt("connections", s.connections))
        } })
        .put("accounts", JSONArray().apply { bundle.accounts.forEach { put(JSONObject().put("key", it.key).put("label", it.label).put("limit", it.limit)) } })
        .put("guides", JSONArray().apply { bundle.guides.forEach { put(JSONObject().put("key", it.key).put("label", it.label).put("endpoint", it.endpoint)) } })
        .put("links", JSONArray().apply { bundle.links.forEach { put(JSONObject().put("source", it.source).put("guides", JSONArray(it.guides))) } })
        .put("overlays", JSONArray().apply { bundle.overlays.forEach { put(overlay(it)) } })
        .putOpt("settings", bundle.settings?.let(::settings))
        .toString()

    fun overlay(o: BundleOverlay): JSONObject = JSONObject().put("source", o.source).put("name", o.match.name).putOpt("providerId", o.match.providerId)
        .putOpt("locator", o.match.locator).putOpt("guideId", o.match.guideId).putOpt("customName", o.customName).putOpt("favourite", o.favourite)
        .apply { if (o.hidden) put("hidden", true); if (o.format != "auto") put("format", o.format) }
        .putOpt("guideFeed", o.guideFeed).putOpt("guideChannel", o.guideChannel)

    fun readOverlay(json: JSONObject, sources: Set<String>?, guides: Set<String>?): BundleOverlay {
        val source = text(json, "source", 80) ?: throw SetupInputException("overlay")
        if (sources != null && source !in sources) throw SetupInputException("overlay")
        val match = ChannelMatch(text(json, "name", 4096)?.takeIf(String::isNotBlank) ?: throw SetupInputException("overlay"),
            text(json, "providerId", 4096), text(json, "locator", 64), text(json, "guideId", 4096))
        val favourite = if (json.has("favourite") && !json.isNull("favourite")) (json.opt("favourite") as? Int)?.takeIf { it >= 0 } ?: throw SetupInputException("overlay") else null
        val hidden = if (json.has("hidden")) json.opt("hidden") as? Boolean ?: throw SetupInputException("overlay") else false
        val format = text(json, "format", 16) ?: "auto"
        if (format !in FORMATS) throw SetupInputException("overlay")
        val feed = text(json, "guideFeed", 80)
        val channel = text(json, "guideChannel", 4096)
        if ((feed == null) != (channel == null) || (feed != null && guides != null && feed !in guides && feed != PROVIDER) || channel?.isBlank() == true) throw SetupInputException("overlay")
        return BundleOverlay(source, match, text(json, "customName", 512)?.takeIf(String::isNotBlank), favourite, hidden, format, feed, channel)
    }

    fun settings(s: BundleSettings): JSONObject = JSONObject(s.live.toJson()).apply { remove("multiview") }.put("preview", s.preview).put("stats", s.stats)
        .putOpt("theme", s.theme).put("black", s.black).put("solid", s.solid).put("plain", s.plain)

    fun decode(text: String): SetupBundle {
        val json = try { JSONObject(text) } catch (_: Exception) { throw SetupInputException("body") }
        if (json.optString("format") != FORMAT) throw SetupInputException("format")
        if (json.optInt("version", -1) != VERSION) throw SetupInputException("version")
        val logins = json.opt("logins") as? Boolean ?: throw SetupInputException("logins")
        val accounts = array(json, "accounts", 64).map { a ->
            BundleAccount(key(a), label(a), (a.opt("limit") as? Int)?.takeIf { it in 1..16 } ?: throw SetupInputException("account"))
        }
        val accountKeys = accounts.map { it.key }.toSet()
        val sources = array(json, "sources", MAX_SOURCES).map { s ->
            val kind = SetupKind.of(text(s, "kind", 16))?.takeIf { !it.guide } ?: throw SetupInputException("source")
            val endpoint = text(s, "endpoint", SetupDrafts.MAX_ADDRESS)?.let { SetupDrafts.address(kind, it) ?: throw SetupInputException("source") }
            val username = text(s, "username", SetupDrafts.MAX_CREDENTIAL)
            val password = text(s, "password", SetupDrafts.MAX_CREDENTIAL)
            val account = text(s, "account", 80)?.also { if (it !in accountKeys) throw SetupInputException("source") }
            val connections = if (s.has("connections") && !s.isNull("connections")) (s.opt("connections") as? Int)?.takeIf { it in 1..SourceConnections.MAX } ?: throw SetupInputException("source") else null
            BundleSource(key(s), label(s), kind, endpoint, username, password, account, connections)
        }
        val guides = array(json, "guides", MAX_GUIDES).map { g ->
            BundleGuide(key(g), label(g), text(g, "endpoint", SetupDrafts.MAX_ADDRESS)?.let { SetupDrafts.address(SetupKind.GUIDE, it) } ?: throw SetupInputException("guide"))
        }
        val sourceKeys = sources.map { it.key }.toSet()
        val guideKeys = guides.map { it.key }.toSet()
        if (sourceKeys.size != sources.size || guideKeys.size != guides.size || accountKeys.size != accounts.size) throw SetupInputException("key")
        val links = array(json, "links", MAX_SOURCES).map { l ->
            val source = text(l, "source", 80)?.takeIf { it in sourceKeys } ?: throw SetupInputException("links")
            val ids = l.optJSONArray("guides") ?: throw SetupInputException("links")
            if (ids.length() > MAX_LINKED) throw SetupInputException("links")
            BundleLinks(source, (0 until ids.length()).map { (ids.opt(it) as? String)?.takeIf { id -> id in guideKeys || id == PROVIDER } ?: throw SetupInputException("links") }
                .also { if (it.distinct().size != it.size) throw SetupInputException("links") })
        }
        if (links.map { it.source }.distinct().size != links.size) throw SetupInputException("links")
        val overlays = array(json, "overlays", MAX_OVERLAYS).map { readOverlay(it, sourceKeys, guideKeys) }
        val settings = json.optJSONObject("settings")?.let(::readSettings)
        return SetupBundle(logins, sources, accounts, guides, links, overlays, settings)
    }

    fun readSettings(json: JSONObject): BundleSettings {
        fun flag(key: String, default: Boolean) = if (!json.has(key)) default else json.opt(key) as? Boolean ?: throw SetupInputException("settings")
        fun choice(key: String, allowed: List<String>, default: String) = if (!json.has(key)) default else (json.opt(key) as? String)?.takeIf { it in allowed } ?: throw SetupInputException("settings")
        fun minutes(key: String, allowed: List<Int>, default: Int) = if (!json.has(key)) default else (json.opt(key) as? Int)?.takeIf { it in allowed } ?: throw SetupInputException("settings")
        val base = SetupSettings()
        val live = SetupSettings(choice("format", SetupSettings.FORMATS, base.format), flag("timeshift", base.timeshift), flag("sport", base.sport),
            choice("startView", SetupSettings.START_VIEWS, base.startView), choice("layout", SetupSettings.LAYOUTS, base.layout),
            choice("quality", SetupSettings.QUALITIES, base.quality), minutes("recordEarly", SetupSettings.EARLY_MINUTES, base.recordEarly),
            minutes("recordLate", SetupSettings.LATE_MINUTES, base.recordLate))
        val theme = if (json.has("theme") && !json.isNull("theme")) (json.opt("theme") as? String)?.takeIf { it.length <= 40 && it.all { c -> c.isLetterOrDigit() || c == '_' || c == '-' } }
            ?: throw SetupInputException("settings") else null
        return BundleSettings(live, flag("preview", true), flag("stats", false), theme, flag("black", false), flag("solid", true), flag("plain", true))
    }

    private fun array(json: JSONObject, name: String, limit: Int): List<JSONObject> {
        if (!json.has(name)) return emptyList()
        val array = json.optJSONArray(name) ?: throw SetupInputException(name)
        if (array.length() > limit) throw SetupInputException(name)
        return (0 until array.length()).map { array.opt(it) as? JSONObject ?: throw SetupInputException(name) }
    }

    private fun key(json: JSONObject): String = text(json, "key", 8)?.takeIf(KEY::matches) ?: throw SetupInputException("key")

    private fun label(json: JSONObject): String =
        text(json, "label", SetupDrafts.MAX_LABEL)?.trim()?.takeIf { it.isNotEmpty() && it.none(Char::isISOControl) } ?: throw SetupInputException("label")

    private fun text(json: JSONObject, key: String, limit: Int): String? {
        if (!json.has(key) || json.isNull(key)) return null
        val value = json.opt(key) as? String ?: throw SetupInputException(key)
        if (value.length > limit || value.any { it.code < 32 && it != '\t' }) throw SetupInputException(key)
        return value
    }
}
