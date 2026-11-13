package com.nuvio.tv.core.profile

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lets a profile PIN be checked on the box while the account server cannot be reached. Keeps a salted,
 * stretched hash from the last PIN the server accepted, never the PIN itself.
 */
@Singleton
class OfflinePinStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs by lazy { context.getSharedPreferences(FILE, Context.MODE_PRIVATE) }
    private val vault = OfflinePinVault(object : OfflinePinVault.Records {
        override fun get(key: String): String? = prefs.getString(key, null)
        override fun put(key: String, value: String?) {
            prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
        }
    })

    fun remember(profileId: Int, pin: String) = vault.remember(profileId, pin)
    fun forget(profileId: Int) = vault.forget(profileId)
    fun forgetIfMatches(profileId: Int, pin: String) = vault.forgetIfMatches(profileId, pin)
    internal fun check(profileId: Int, pin: String): OfflinePinVault.Answer =
        vault.check(profileId, pin, System.currentTimeMillis())

    companion object {
        private const val FILE = "arc_offline_pin"

        fun clear(context: Context) {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().clear().apply()
        }
    }
}

internal class OfflinePinVault(
    private val records: Records,
    private val iterations: Int = ITERATIONS,
    private val random: SecureRandom = SecureRandom()
) {
    interface Records {
        fun get(key: String): String?
        fun put(key: String, value: String?)
    }

    sealed interface Answer {
        /** Nothing saved for this profile: only the server can say. */
        data object NoCopy : Answer
        data object Unlocked : Answer
        data class Refused(val retryAfterSeconds: Int) : Answer
    }

    fun remember(profileId: Int, pin: String) {
        if (pin.isEmpty()) return
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        records.put(hashKey(profileId), "$iterations:${salt.hex()}:${stretch(pin, salt, iterations).hex()}")
        records.put(triesKey(profileId), null)
    }

    fun forget(profileId: Int) {
        records.put(hashKey(profileId), null)
        records.put(triesKey(profileId), null)
    }

    /** The server refused a PIN this box still accepts: it was changed somewhere else. */
    fun forgetIfMatches(profileId: Int, pin: String) {
        val saved = saved(profileId) ?: return
        if (saved.matches(pin)) forget(profileId)
    }

    fun check(profileId: Int, pin: String, nowMs: Long): Answer {
        val saved = saved(profileId) ?: return Answer.NoCopy
        val tries = records.get(triesKey(profileId))?.split(':')
        val failed = tries?.getOrNull(0)?.toIntOrNull() ?: 0
        val waitUntilMs = tries?.getOrNull(1)?.toLongOrNull() ?: 0L
        if (nowMs < waitUntilMs) {
            return Answer.Refused(((waitUntilMs - nowMs + 999L) / 1000L).toInt().coerceAtMost(WAIT_SECONDS))
        }
        if (saved.matches(pin)) {
            records.put(triesKey(profileId), null)
            return Answer.Unlocked
        }
        return if (failed + 1 >= MAX_TRIES) {
            records.put(triesKey(profileId), "0:${nowMs + WAIT_SECONDS * 1000L}")
            Answer.Refused(WAIT_SECONDS)
        } else {
            records.put(triesKey(profileId), "${failed + 1}:0")
            Answer.Refused(0)
        }
    }

    private inner class Saved(val rounds: Int, val salt: ByteArray, val hash: ByteArray) {
        fun matches(pin: String): Boolean = MessageDigest.isEqual(stretch(pin, salt, rounds), hash)
    }

    private fun saved(profileId: Int): Saved? {
        val parts = records.get(hashKey(profileId))?.split(':') ?: return null
        val rounds = parts.getOrNull(0)?.toIntOrNull()?.takeIf { it > 0 } ?: return null
        val salt = parts.getOrNull(1)?.unhex() ?: return null
        val hash = parts.getOrNull(2)?.unhex() ?: return null
        return Saved(rounds, salt, hash)
    }

    private fun stretch(pin: String, salt: ByteArray, rounds: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, rounds, HASH_BITS)
        return try {
            SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun hashKey(profileId: Int) = "h$profileId"
    private fun triesKey(profileId: Int) = "t$profileId"

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    private fun String.unhex(): ByteArray? {
        if (isEmpty() || length % 2 != 0) return null
        return runCatching { ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() } }.getOrNull()
    }

    companion object {
        // Present on every Android version Arc runs on.
        private const val ALGORITHM = "PBKDF2WithHmacSHA1"
        const val ITERATIONS = 60_000
        private const val SALT_BYTES = 16
        private const val HASH_BITS = 160
        const val MAX_TRIES = 5
        const val WAIT_SECONDS = 300
    }
}
