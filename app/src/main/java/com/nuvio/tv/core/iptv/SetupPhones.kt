package com.nuvio.tv.core.iptv

import java.security.MessageDigest
import java.security.SecureRandom
import org.json.JSONArray
import org.json.JSONObject

class SetupPhone(val id: String, val browser: String?, val platform: String?, val pairedAt: Long, val profile: Int) {
    override fun toString() = "SetupPhone(paired=$pairedAt)"
}

interface SetupPhoneAccess {
    val enabled: Boolean
    fun issue(userAgent: String?, profile: Int): String?
    fun verify(token: String?): SetupPhone?
    fun allowed(phone: SetupPhone): Boolean?
    fun forget(token: String?): Boolean
}

class SetupPhones(
    private val random: SecureRandom = SecureRandom(),
    private val now: () -> Long = System::currentTimeMillis,
    val maxPhones: Int = 8,
) {
    private class Entry(val digest: ByteArray, val phone: SetupPhone)
    private val entries = ArrayList<Entry>()

    val phones: List<SetupPhone> @Synchronized get() = entries.map { it.phone }.sortedByDescending { it.pairedAt }

    @Synchronized fun issue(userAgent: String?, profile: Int): String {
        val token = SetupPairing.hex(ByteArray(TOKEN_BYTES).also(random::nextBytes))
        val (browser, platform) = describe(userAgent)
        entries += Entry(digest(token), SetupPhone(SetupPairing.hex(ByteArray(8).also(random::nextBytes)), browser, platform, now(), profile))
        while (entries.size > maxPhones) entries.remove(entries.minBy { it.phone.pairedAt })
        return token
    }

    @Synchronized fun verify(token: String?): SetupPhone? {
        if (token == null || !TOKEN.matches(token)) return null
        val presented = digest(token)
        var found: SetupPhone? = null
        for (entry in entries) if (MessageDigest.isEqual(entry.digest, presented)) found = entry.phone
        return found
    }

    @Synchronized fun forget(token: String?): Boolean {
        val phone = verify(token) ?: return false
        return remove(phone.id)
    }

    @Synchronized fun remove(id: String): Boolean = entries.removeAll { it.phone.id == id }

    @Synchronized fun clear() = entries.clear()

    @Synchronized fun encode(): String = JSONArray().apply {
        entries.forEach { entry ->
            put(JSONObject().put("id", entry.phone.id).put("hash", SetupPairing.hex(entry.digest)).put("browser", entry.phone.browser ?: "")
                .put("platform", entry.phone.platform ?: "").put("paired", entry.phone.pairedAt).put("profile", entry.phone.profile))
        }
    }.toString()

    @Synchronized fun load(text: String?) {
        entries.clear()
        val array = runCatching { JSONArray(text ?: return) }.getOrNull() ?: return
        for (index in 0 until minOf(array.length(), maxPhones)) {
            val item = array.optJSONObject(index) ?: continue
            val id = item.optString("id").takeIf(ID::matches) ?: continue
            val hash = item.optString("hash").takeIf(TOKEN::matches) ?: continue
            if (entries.any { it.phone.id == id }) continue
            entries += Entry(ByteArray(32) { hash.substring(it * 2, it * 2 + 2).toInt(16).toByte() },
                SetupPhone(id, item.optString("browser").takeIf { it in BROWSERS }, item.optString("platform").takeIf { it in PLATFORMS },
                    item.optLong("paired"), item.optInt("profile", -1)))
        }
    }

    override fun toString() = "SetupPhones(count=${entries.size})"

    companion object {
        const val TOKEN_BYTES = 32
        val TOKEN = Regex("[0-9a-f]{64}")
        private val ID = Regex("[0-9a-f]{16}")
        private val BROWSERS = listOf("Edge", "Samsung Internet", "Opera", "Firefox", "Chrome", "Safari")
        private val PLATFORMS = listOf("iPhone", "iPad", "Android", "ChromeOS", "Windows", "Mac", "Linux")

        private fun digest(token: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))

        fun describe(userAgent: String?): Pair<String?, String?> {
            val agent = userAgent?.take(512).orEmpty()
            val browser = when {
                "Edg/" in agent || "EdgA/" in agent || "EdgiOS/" in agent -> "Edge"
                "SamsungBrowser/" in agent -> "Samsung Internet"
                "OPR/" in agent || "OPiOS/" in agent -> "Opera"
                "Firefox/" in agent || "FxiOS/" in agent -> "Firefox"
                "Chrome/" in agent || "CriOS/" in agent -> "Chrome"
                "Safari/" in agent && "Version/" in agent -> "Safari"
                else -> null
            }
            val platform = when {
                "iPhone" in agent -> "iPhone"
                "iPad" in agent -> "iPad"
                "Android" in agent -> "Android"
                "CrOS" in agent -> "ChromeOS"
                "Windows" in agent -> "Windows"
                "Macintosh" in agent || "Mac OS X" in agent -> "Mac"
                "Linux" in agent -> "Linux"
                else -> null
            }
            return browser to platform
        }

        fun canUse(phone: SetupPhone, profile: Int, pinLocked: Set<Int>?): Boolean? = pinLocked?.let { phone.profile == profile || profile !in it }
    }
}

class SetupPairGate(perAddress: Int, overall: Int, windowMillis: Long, now: () -> Long) {
    private val addresses = SetupRateLimiter(perAddress, windowMillis, now)
    private val all = SetupRateLimiter(overall, windowMillis, now)

    fun allow(address: String): Boolean = addresses.allow(address) && all.allow(ALL)

    private companion object { const val ALL = "*" }
}
