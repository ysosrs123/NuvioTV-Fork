package com.nuvio.tv.core.backup

import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class WrongBackupPinException : Exception("wrong PIN")

/** Seals the passwords and keys with the viewer's PIN: a slow key from the PIN, then AES-GCM. */
object BackupCrypto {
    const val MIN_PIN_LENGTH = 6
    const val MAX_PIN_LENGTH = 10
    private const val PREFERRED_KDF = "PBKDF2WithHmacSHA256"
    private const val FALLBACK_KDF = "PBKDF2WithHmacSHA1"
    private const val ITERATIONS = 600_000
    const val MAX_ITERATIONS = 2_000_000
    private const val AAD = "arc-backup-secrets-v1"

    fun isValidPin(pin: String): Boolean = pin.length in MIN_PIN_LENGTH..MAX_PIN_LENGTH && pin.all { it in '0'..'9' }

    fun seal(plain: String, pin: String, iterations: Int = ITERATIONS, random: SecureRandom = SecureRandom()): SealedBox {
        require(isValidPin(pin))
        val kdf = availableKdf()
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(pin, salt, kdf, iterations), GCMParameterSpec(128, iv))
        cipher.updateAAD(AAD.toByteArray(Charsets.UTF_8))
        val data = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return SealedBox(kdf, iterations, encode(salt), encode(iv), encode(data))
    }

    fun open(box: SealedBox, pin: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        return try {
            cipher.init(Cipher.DECRYPT_MODE, key(pin, decode(box.salt), box.kdf, box.iterations), GCMParameterSpec(128, decode(box.iv)))
            cipher.updateAAD(AAD.toByteArray(Charsets.UTF_8))
            cipher.doFinal(decode(box.data)).toString(Charsets.UTF_8)
        } catch (error: GeneralSecurityException) {
            throw WrongBackupPinException()
        } catch (error: IllegalArgumentException) {
            throw WrongBackupPinException()
        }
    }

    private fun availableKdf(): String =
        if (runCatching { SecretKeyFactory.getInstance(PREFERRED_KDF) }.isSuccess) PREFERRED_KDF else FALLBACK_KDF

    private fun key(pin: String, salt: ByteArray, kdf: String, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, 256)
        try {
            val bytes = SecretKeyFactory.getInstance(kdf).generateSecret(spec).encoded
            return SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    private fun decode(text: String): ByteArray = Base64.getDecoder().decode(text)
}
