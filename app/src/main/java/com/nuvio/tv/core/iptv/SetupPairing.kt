package com.nuvio.tv.core.iptv

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale

object SetupLan {
    fun ipv4(address: String?): IntArray? {
        val text = address?.trim()?.removePrefix("::ffff:")?.removePrefix("::FFFF:") ?: return null
        val parts = text.split('.')
        if (parts.size != 4) return null
        return IntArray(4) { index ->
            val part = parts[index]
            if (part.isEmpty() || part.length > 3 || part.any { it !in '0'..'9' } || (part.length > 1 && part[0] == '0')) return null
            part.toInt().takeIf { it <= 255 } ?: return null
        }
    }

    fun isLanAddress(address: String?): Boolean {
        val (a, b) = ipv4(address) ?: return false
        return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254)
    }
}

class SetupPairing(
    private val random: SecureRandom = SecureRandom(),
    val maxAttempts: Int = 5,
    val maxSessions: Int = 4,
) {
    class Credentials(val token: String, val code: String, val revision: Int) {
        override fun toString() = "Credentials(revision=$revision)"
    }
    sealed interface Result {
        class Paired(val sessionId: String) : Result { override fun toString() = "Paired" }
        data class WrongCode(val attemptsLeft: Int) : Result
        data object Renewed : Result
        data object UnknownLink : Result
    }

    @Volatile var credentials: Credentials = Credentials(newToken(), newCode(), 0)
        private set
    private var attempts = 0
    private var closed = false
    private val sessions = LinkedHashMap<String, String>()

    val sessionCount: Int @Synchronized get() = sessions.size

    @Synchronized fun knowsLink(token: String): Boolean =
        !closed && TOKEN.matches(token) && (same(token, credentials.token) || sessions.values.any { same(it, token) })

    @Synchronized fun pair(token: String, code: String): Result {
        val current = credentials
        if (closed || !TOKEN.matches(token) || !same(token, current.token)) return Result.UnknownLink
        val entered = code.filter { it != ' ' && it != '-' }
        if (entered.length == CODE_LENGTH && same(entered, current.code)) {
            val sessionId = newSessionId()
            sessions[digest(sessionId)] = current.token
            while (sessions.size > maxSessions) sessions.remove(sessions.keys.first())
            attempts = 0
            credentials = Credentials(current.token, newCode(current.code), current.revision + 1)
            return Result.Paired(sessionId)
        }
        attempts++
        if (attempts >= maxAttempts) {
            attempts = 0
            credentials = Credentials(newToken(), newCode(current.code), current.revision + 1)
            return Result.Renewed
        }
        return Result.WrongCode(maxAttempts - attempts)
    }

    @Synchronized fun session(token: String, sessionId: String?): String? {
        if (closed || sessionId == null || !SESSION.matches(sessionId) || !TOKEN.matches(token)) return null
        val key = digest(sessionId)
        val bound = sessions[key] ?: return null
        return key.takeIf { same(bound, token) }
    }

    @Synchronized fun close() {
        closed = true
        sessions.clear()
        credentials = Credentials(newToken(), newCode(), credentials.revision + 1)
    }

    private fun newToken(): String = buildString(TOKEN_LENGTH) { repeat(TOKEN_LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }
    private fun newCode(previous: String? = null): String {
        while (true) String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000)).let { if (it != previous) return it }
    }
    private fun newSessionId(): String = hex(ByteArray(32).also(random::nextBytes))

    companion object {
        const val TOKEN_LENGTH = 26
        const val CODE_LENGTH = 6
        private const val ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789"
        val TOKEN = Regex("[a-km-np-z2-9]{$TOKEN_LENGTH}")
        private val SESSION = Regex("[0-9a-f]{64}")

        fun same(a: String, b: String): Boolean = MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
        fun digest(value: String): String = hex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))
        fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
            for (byte in bytes) { val number = byte.toInt() and 255; append(HEX[number ushr 4]); append(HEX[number and 15]) }
        }
        private const val HEX = "0123456789abcdef"
    }
}

object SetupGuard {
    const val HEADER = "x-nuvio-setup"
    enum class Rejection { HOST, HEADER, CONTENT_TYPE, ORIGIN, FETCH_SITE }

    fun hostMatches(host: String?, authority: String): Boolean = host != null && host.trim().equals(authority, ignoreCase = true)

    fun check(headers: Map<String, String>, origin: String, stateChanging: Boolean): Rejection? {
        if (!hostMatches(headers["host"], origin.removePrefix("http://"))) return Rejection.HOST
        if (headers[HEADER]?.trim() != "1") return Rejection.HEADER
        val site = headers["sec-fetch-site"]?.trim()?.lowercase(Locale.ROOT)
        if (site != null && site != "same-origin") return Rejection.FETCH_SITE
        val sentOrigin = headers["origin"]?.trim()
        if (sentOrigin != null && sentOrigin != origin) return Rejection.ORIGIN
        if (!stateChanging) return null
        val type = headers["content-type"]?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
        if (type != "application/json") return Rejection.CONTENT_TYPE
        if (sentOrigin == null && headers["referer"]?.trim()?.startsWith("$origin/") != true) return Rejection.ORIGIN
        return null
    }
}

class SetupRateLimiter(private val limit: Int, private val windowMillis: Long, private val now: () -> Long, private val maxKeys: Int = 256) {
    private class Window(var start: Long, var count: Int)
    private val windows = HashMap<String, Window>()

    @Synchronized fun allow(key: String): Boolean {
        val time = now()
        val window = windows[key] ?: run {
            if (windows.size >= maxKeys) windows.values.removeAll { time - it.start >= windowMillis }
            if (windows.size >= maxKeys) return false
            Window(time, 0).also { windows[key] = it }
        }
        if (time - window.start >= windowMillis) { window.start = time; window.count = 0 }
        window.count++
        return window.count <= limit
    }
}

class SetupConnectionLimiter(private val limit: Int, private val perAddress: Int) {
    private val open = HashMap<String, Int>()
    private var total = 0

    @Synchronized fun admit(address: String): Boolean {
        val current = open[address] ?: 0
        if (total >= limit || current >= perAddress) return false
        open[address] = current + 1
        total++
        return true
    }

    @Synchronized fun release(address: String) {
        val current = open[address] ?: return
        if (current <= 1) open.remove(address) else open[address] = current - 1
        total--
    }
}

class SetupIdleTimer(private val timeoutMillis: Long, private val now: () -> Long) {
    @Volatile private var last = now()
    fun touch() { last = now() }
    fun expired(): Boolean = now() - last >= timeoutMillis
}

object SetupHeaders {
    fun security(nonce: String? = null): Map<String, String> = linkedMapOf(
        "Content-Security-Policy" to if (nonce == null) "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"
        else "default-src 'self'; script-src 'nonce-$nonce'; style-src 'nonce-$nonce'; img-src 'self' data:; connect-src 'self'; " +
            "object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
        "X-Frame-Options" to "DENY",
        "X-Content-Type-Options" to "nosniff",
        "Referrer-Policy" to "same-origin",
        "Cache-Control" to "no-store",
        "Pragma" to "no-cache",
        "Cross-Origin-Opener-Policy" to "same-origin",
        "Cross-Origin-Resource-Policy" to "same-origin",
        "Permissions-Policy" to "camera=(), microphone=(), geolocation=()",
    )

    fun nonce(random: SecureRandom): String = SetupPairing.hex(ByteArray(16).also(random::nextBytes))
}

object SetupCookies {
    const val NAME = "nuvio_setup"

    fun read(header: String?, name: String = NAME): String? =
        header?.split(';')?.map { it.trim() }?.filter { it.startsWith("$name=") }?.singleOrNull()?.substringAfter('=')

    fun session(value: String, token: String): String {
        require(SetupPairing.TOKEN.matches(token) && value.all { it in '0'..'9' || it in 'a'..'f' })
        return "$NAME=$value; Path=/s/$token/; HttpOnly; SameSite=Strict"
    }
}
