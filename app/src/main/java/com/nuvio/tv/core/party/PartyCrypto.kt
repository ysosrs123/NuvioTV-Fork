package com.nuvio.tv.core.party

import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.toByteString
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** What a relay sees of a room (the tag) and what only holders of the code can derive (the key). */
class PartyRoomKeys(val roomTag: String, val payloadKey: ByteArray)

class PartyIdentity(val secretKey: ByteArray, val publicKeyHex: String) {
    companion object {
        fun random(random: SecureRandom = SecureRandom()): PartyIdentity {
            while (true) {
                val secret = ByteArray(32).also(random::nextBytes)
                val public = runCatching { Bip340.publicKey(secret) }.getOrNull() ?: continue
                return PartyIdentity(secret, PartyCrypto.hex(public))
            }
        }
    }
}

object PartyCrypto {
    private const val KDF_ITERATIONS = 100_000
    private const val KDF_SALT = "nuvio-party-v1"
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128

    /** Slow on purpose: a six-character code must not be cheap to guess from what a relay sees. */
    fun deriveRoom(code: String, iterations: Int = KDF_ITERATIONS): PartyRoomKeys {
        val derived = pbkdf2(code.toByteArray(Charsets.UTF_8), KDF_SALT.toByteArray(Charsets.UTF_8), iterations, 48)
        return PartyRoomKeys(
            roomTag = hex(derived.copyOfRange(32, 48)),
            payloadKey = derived.copyOfRange(0, 32),
        )
    }

    fun seal(key: ByteArray, plaintext: String, random: SecureRandom): String {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        val sealed = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return (nonce + sealed).toByteString().base64()
    }

    fun open(key: ByteArray, sealed: String): String? = runCatching {
        val raw = sealed.decodeBase64()?.toByteArray() ?: return null
        if (raw.size <= NONCE_BYTES) return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BITS, raw, 0, NONCE_BYTES),
        )
        String(cipher.doFinal(raw, NONCE_BYTES, raw.size - NONCE_BYTES), Charsets.UTF_8)
    }.getOrNull()

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    fun hex(data: ByteArray): String = data.toByteString().hex()

    fun unhex(value: String): ByteArray? = runCatching { value.decodeHex().toByteArray() }.getOrNull()

    internal fun pbkdf2(password: ByteArray, salt: ByteArray, iterations: Int, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password, "HmacSHA256"))
        val out = ByteArray(length)
        var block = 1
        var offset = 0
        while (offset < length) {
            mac.update(salt)
            mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
            var u = mac.doFinal()
            val t = u.copyOf()
            for (i in 1 until iterations) {
                u = mac.doFinal(u)
                for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
            }
            val take = minOf(t.size, length - offset)
            System.arraycopy(t, 0, out, offset, take)
            offset += take
            block++
        }
        return out
    }
}
