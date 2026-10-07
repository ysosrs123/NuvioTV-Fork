package com.nuvio.tv.core.iptv

import java.net.URI
import java.security.SecureRandom
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

enum class SetupKind(val wire: String) {
    M3U("m3u"), XTREAM("xtream"), STALKER("stalker"), GUIDE("guide");
    val guide: Boolean get() = this == GUIDE
    companion object { fun of(wire: String?): SetupKind? = entries.firstOrNull { it.wire == wire } }
}

enum class SetupField { LABEL, ADDRESS, USERNAME, PASSWORD, MAC }

class SetupInputException(val field: String) : IllegalArgumentException("Invalid setup field")

sealed interface SetupChange

class SetupDraft(val kind: SetupKind, val targetId: String?, val label: String, val address: String, val username: String, val password: String) : SetupChange {
    val edit: Boolean get() = targetId != null
    override fun toString() = "SetupDraft(kind=$kind, edit=$edit, values withheld)"

    fun changes(current: SetupListingItem?): Set<SetupField> = buildSet {
        if (current == null || current.label != label) add(SetupField.LABEL)
        if (address.isNotEmpty()) add(SetupField.ADDRESS)
        when (kind) {
            SetupKind.XTREAM -> { if (username.isNotEmpty()) add(SetupField.USERNAME); if (password.isNotEmpty()) add(SetupField.PASSWORD) }
            SetupKind.STALKER -> if (username.isNotEmpty()) add(SetupField.MAC)
            else -> Unit
        }
    }

    fun movesServer(currentOrigin: String?): Boolean = edit && address.isNotEmpty() && SetupText.origin(address) != currentOrigin

    fun missingLogin(currentOrigin: String?): String? {
        if (!movesServer(currentOrigin)) return null
        return when (kind) {
            SetupKind.XTREAM -> if (username.isEmpty()) "username" else if (password.isEmpty()) "password" else null
            SetupKind.STALKER -> if (username.isEmpty()) "mac" else null
            else -> null
        }
    }

    fun connection(stored: SetupConnection?): SetupConnection {
        require(edit == (stored != null))
        missingLogin(stored?.endpoint?.let(SetupText::origin))?.let { throw SetupInputException(it) }
        val endpoint = address.ifEmpty { stored?.endpoint.orEmpty() }
        require(endpoint.isNotEmpty())
        return when (kind) {
            SetupKind.XTREAM -> SetupConnection(endpoint, username.ifEmpty { stored?.username.orEmpty() }, password.ifEmpty { stored?.password.orEmpty() })
                .also { require(!it.username.isNullOrEmpty() && !it.password.isNullOrEmpty()) }
            SetupKind.STALKER -> SetupConnection(endpoint, StalkerPortal.normalizeMac(username.ifEmpty { stored?.username.orEmpty() }) ?: throw IllegalArgumentException())
            SetupKind.M3U, SetupKind.GUIDE -> SetupConnection(endpoint)
        }
    }
}

class SetupConnection(val endpoint: String, val username: String? = null, val password: String? = null) {
    override fun toString() = "SetupConnection(values withheld)"
}

data class SetupListingItem(val id: String, val label: String, val kind: SetupKind, val host: String?, val editable: Boolean, val origin: String? = null)

data class SetupListing(val sources: List<SetupListingItem> = emptyList(), val guides: List<SetupListingItem> = emptyList(),
    val profile: Int = 0, val profiles: List<SetupProfile> = emptyList(), val links: Map<String, List<String>> = emptyMap()) {
    fun find(kind: SetupKind, id: String): SetupListingItem? = (if (kind.guide) guides else sources).firstOrNull { it.id == id }

    fun toJson(pending: Boolean): String = JSONObject()
        .put("sources", items(sources)).put("guides", items(guides)).put("pending", pending).put("profile", profile)
        .put("profiles", JSONArray().apply { profiles.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("locked", it.locked)) } })
        .toString()

    private fun items(list: List<SetupListingItem>) = JSONArray().apply {
        list.forEach { item ->
            put(JSONObject().put("id", item.id).put("label", item.label).put("kind", item.kind.wire).put("host", item.host ?: "").put("editable", item.editable)
                .apply { if (!item.kind.guide) put("guides", JSONArray(links[item.id].orEmpty())) })
        }
    }
}

object SetupDrafts {
    const val MAX_BODY_BYTES = 64 * 1024
    const val MAX_LABEL = 240
    const val MAX_ADDRESS = 16_384
    const val MAX_CREDENTIAL = 4096
    private val ID = Regex("[A-Za-z0-9_-]{1,80}")

    fun parse(body: String): SetupDraft {
        if (body.length > MAX_BODY_BYTES || !shallowJson(body)) throw SetupInputException("body")
        val json = try { JSONObject(body) } catch (_: Exception) { throw SetupInputException("body") }
        val kind = SetupKind.of(text(json, "kind", 16)) ?: throw SetupInputException("kind")
        val target = text(json, "id", 80).ifEmpty { null }?.also { if (!ID.matches(it)) throw SetupInputException("id") }
        val label = text(json, "label", MAX_LABEL).trim()
        if (label.isEmpty() || label.any(Char::isISOControl)) throw SetupInputException("label")
        val rawAddress = text(json, "address", MAX_ADDRESS).trim()
        if (rawAddress.isEmpty() && target == null) throw SetupInputException("address")
        val address = if (rawAddress.isEmpty()) "" else address(kind, rawAddress) ?: throw SetupInputException("address")
        var username = ""
        var password = ""
        when (kind) {
            SetupKind.XTREAM -> {
                username = credential(text(json, "username", MAX_CREDENTIAL).trim()) ?: throw SetupInputException("username")
                password = credential(text(json, "password", MAX_CREDENTIAL).trim()) ?: throw SetupInputException("password")
                if (target == null && username.isEmpty()) throw SetupInputException("username")
                if (target == null && password.isEmpty()) throw SetupInputException("password")
            }
            SetupKind.STALKER -> {
                val mac = text(json, "mac", 64).trim()
                if (mac.isNotEmpty() || target == null) username = StalkerPortal.normalizeMac(mac) ?: throw SetupInputException("mac")
            }
            else -> Unit
        }
        return SetupDraft(kind, target, label, address, username, password)
    }

    fun shallowJson(body: String, maxDepth: Int = 4): Boolean {
        var depth = 0
        var inString = false
        var escaped = false
        for (char in body) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
                continue
            }
            when (char) {
                '"' -> inString = true
                '{', '[' -> if (++depth > maxDepth) return false
                '}', ']' -> depth--
                ' ', '\t', '\n', '\r', ':', ',', '-', '+', '.' -> Unit
                in '0'..'9', in 'a'..'z', in 'A'..'Z' -> Unit
                else -> return false
            }
        }
        return !inString
    }

    fun checkTarget(draft: SetupDraft, listing: SetupListing): String? {
        val id = draft.targetId ?: return null
        val current = listing.find(draft.kind, id) ?: return "missing"
        if (!current.editable || current.kind != draft.kind) return "locked"
        return if (draft.changes(current).isEmpty()) "unchanged" else null
    }

    fun checkLogin(draft: SetupDraft, listing: SetupListing): String? {
        val current = draft.targetId?.let { listing.find(draft.kind, it) } ?: return null
        return draft.missingLogin(current.origin)
    }

    fun address(kind: SetupKind, value: String): String? {
        if (value.length > MAX_ADDRESS || value.any { it.isWhitespace() || it.isISOControl() }) return null
        val address = if ("://" in value) value else "http://$value"
        val uri = try { URI(address) } catch (_: Exception) { return null }
        if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.rawUserInfo != null || uri.rawFragment != null) return null
        return when (kind) {
            SetupKind.XTREAM -> address.takeIf { uri.rawQuery == null && uri.rawPath.orEmpty().substringAfterLast('/').lowercase(Locale.ROOT).endsWith(".php").not() }
            SetupKind.STALKER -> address.takeIf { StalkerPortal.apiUrl(it) != null }
            SetupKind.M3U, SetupKind.GUIDE -> address
        }
    }

    private fun credential(value: String): String? =
        value.takeIf { it != "." && it != ".." && it.none { char -> char.code < 32 || char.code == 127 } }

    private fun text(json: JSONObject, key: String, limit: Int): String {
        if (!json.has(key) || json.isNull(key)) return ""
        val value = json.opt(key) as? String ?: throw SetupInputException(key)
        if (value.length > limit || value.any { it.category == CharCategory.FORMAT }) throw SetupInputException(key)
        return value
    }
}

object SetupText {
    fun host(address: String?): String? {
        val uri = try { URI(address ?: return null) } catch (_: Exception) { return null }
        if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https")) return null
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
        return if (uri.port >= 0) "$host:${uri.port}" else host
    }

    fun origin(address: String?): String? {
        val uri = try { URI(address ?: return null) } catch (_: Exception) { return null }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)?.takeIf { it == "http" || it == "https" } ?: return null
        val host = uri.host?.takeIf { it.isNotBlank() }?.lowercase(Locale.ROOT) ?: return null
        val port = uri.port.takeIf { it >= 0 && it != if (scheme == "https") 443 else 80 }
        return "$scheme://$host" + if (port != null) ":$port" else ""
    }

    fun server(address: String?): String? = origin(address)?.removePrefix("http://")

    fun displayAddress(address: String, limit: Int = 120): String {
        val uri = try { URI(address) } catch (_: Exception) { return "" }
        val host = host(address) ?: return ""
        val rest = uri.rawPath.orEmpty().takeIf { it != "/" }.orEmpty() + if (uri.rawQuery != null) "?…" else ""
        val room = (limit - host.length).coerceAtLeast(16)
        return host + if (rest.length <= room) rest else rest.take(room - 1) + "…"
    }
}

class SetupChangeBook(
    private val random: SecureRandom = SecureRandom(),
    private val maxEntries: Int = 16,
    private val now: () -> Long = System::currentTimeMillis,
    private val cooldownMillis: Long = 10_000,
) {
    enum class Status { PENDING, SAVED, REJECTED, FAILED }
    private class Entry(val owner: String, var change: SetupChange?, var status: Status)
    private val entries = LinkedHashMap<String, Entry>()
    private val rejectedAt = HashMap<String, Long>()

    @Synchronized fun coolingDown(owner: String): Boolean = rejectedAt[owner]?.let { now() - it < cooldownMillis } == true

    @Synchronized fun propose(owner: String, change: SetupChange): String? {
        if (entries.values.any { it.status == Status.PENDING }) return null
        val id = SetupPairing.hex(ByteArray(16).also(random::nextBytes))
        entries[id] = Entry(owner, change, Status.PENDING)
        while (entries.size > maxEntries) entries.remove(entries.keys.first())
        return id
    }

    @Synchronized fun hasPending(): Boolean = entries.values.any { it.status == Status.PENDING }

    @Synchronized fun status(owner: String, id: String): Status? = entries[id]?.takeIf { SetupPairing.same(it.owner, owner) }?.status

    @Synchronized fun pending(id: String): SetupChange? = entries[id]?.takeIf { it.status == Status.PENDING }?.change

    @Synchronized fun resolve(id: String, status: Status): Boolean {
        require(status != Status.PENDING)
        val entry = entries[id]?.takeIf { it.status == Status.PENDING } ?: return false
        entry.status = status
        entry.change = null
        if (status == Status.REJECTED) rejectedAt[entry.owner] = now()
        return true
    }

    @Synchronized fun rejectPending() {
        entries.values.filter { it.status == Status.PENDING }.forEach { it.status = Status.REJECTED; it.change = null }
    }
}
