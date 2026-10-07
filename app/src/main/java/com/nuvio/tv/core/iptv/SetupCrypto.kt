package com.nuvio.tv.core.iptv

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.text.Normalizer
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class SetupCryptoException(val reason: Reason) : Exception("Setup data could not be opened") {
    enum class Reason { NOT_BACKUP, UNSUPPORTED, LOCKED, TOO_LARGE, DAMAGED }
}

object SetupPacking {
    fun gzip(data: ByteArray): ByteArray = ByteArrayOutputStream(data.size / 4 + 64).also { out -> GZIPOutputStream(out).use { it.write(data) } }.toByteArray()

    fun gunzip(data: ByteArray, limit: Int): ByteArray {
        val out = ByteArrayOutputStream(minOf(limit, data.size * 4 + 64))
        try {
            GZIPInputStream(ByteArrayInputStream(data)).use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (out.size() + count > limit) throw SetupCryptoException(SetupCryptoException.Reason.TOO_LARGE)
                    out.write(buffer, 0, count)
                }
            }
        } catch (error: SetupCryptoException) { throw error } catch (_: Exception) { throw SetupCryptoException(SetupCryptoException.Reason.DAMAGED) }
        return out.toByteArray()
    }
}

object SetupVault {
    const val EXTENSION = "nuviotv"
    const val ITERATIONS = 310_000
    const val MIN_ITERATIONS = 100_000
    const val MAX_ITERATIONS = 5_000_000
    const val MIN_PASSPHRASE = 8
    const val MAX_PASSPHRASE = 256
    const val MAX_FILE_BYTES = 32 * 1024 * 1024
    const val MAX_PLAIN_BYTES = 64 * 1024 * 1024
    private val MAGIC = "NVLTVB".toByteArray(Charsets.US_ASCII)
    private const val VERSION: Byte = 1
    private const val KDF_PBKDF2_SHA256: Byte = 1
    private const val HEADER = 6 + 1 + 1 + 4 + 16 + 12

    fun acceptable(passphrase: CharArray): Boolean = passphrase.size in MIN_PASSPHRASE..MAX_PASSPHRASE && passphrase.any { !it.isWhitespace() }

    fun seal(plain: ByteArray, passphrase: CharArray, random: SecureRandom = SecureRandom(), iterations: Int = ITERATIONS): ByteArray {
        require(iterations in MIN_ITERATIONS..MAX_ITERATIONS && acceptable(passphrase))
        val salt = ByteArray(16).also(random::nextBytes)
        val nonce = ByteArray(12).also(random::nextBytes)
        val header = ByteBuffer.allocate(HEADER).put(MAGIC).put(VERSION).put(KDF_PBKDF2_SHA256).putInt(iterations).put(salt).put(nonce).array()
        val key = derive(passphrase, salt, iterations)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(header)
            val sealed = header + cipher.doFinal(SetupPacking.gzip(plain))
            check(sealed.size <= MAX_FILE_BYTES)
            return sealed
        } finally { key.fill(0) }
    }

    fun isBackup(head: ByteArray): Boolean = head.size >= MAGIC.size && MAGIC.indices.all { head[it] == MAGIC[it] }

    fun open(data: ByteArray, passphrase: CharArray): ByteArray {
        if (data.size > MAX_FILE_BYTES) throw SetupCryptoException(SetupCryptoException.Reason.TOO_LARGE)
        if (data.size < HEADER + 16 || !isBackup(data)) throw SetupCryptoException(SetupCryptoException.Reason.NOT_BACKUP)
        val buffer = ByteBuffer.wrap(data, MAGIC.size, HEADER - MAGIC.size)
        val version = buffer.get()
        val kdf = buffer.get()
        val iterations = buffer.getInt()
        if (version != VERSION || kdf != KDF_PBKDF2_SHA256 || iterations !in MIN_ITERATIONS..MAX_ITERATIONS)
            throw SetupCryptoException(SetupCryptoException.Reason.UNSUPPORTED)
        if (passphrase.isEmpty() || passphrase.size > MAX_PASSPHRASE) throw SetupCryptoException(SetupCryptoException.Reason.LOCKED)
        val salt = data.copyOfRange(12, 28)
        val nonce = data.copyOfRange(28, 40)
        val key = derive(passphrase, salt, iterations)
        val packed = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(data, 0, HEADER)
            cipher.doFinal(data, HEADER, data.size - HEADER)
        } catch (_: AEADBadTagException) {
            throw SetupCryptoException(SetupCryptoException.Reason.LOCKED)
        } catch (_: java.security.GeneralSecurityException) {
            throw SetupCryptoException(SetupCryptoException.Reason.LOCKED)
        } finally { key.fill(0) }
        return SetupPacking.gunzip(packed, MAX_PLAIN_BYTES)
    }

    private fun derive(passphrase: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val normalised = Normalizer.normalize(java.nio.CharBuffer.wrap(passphrase), Normalizer.Form.NFC).toCharArray()
        val spec = PBEKeySpec(normalised, salt, iterations, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
            normalised.fill('\u0000')
        }
    }
}

class SetupTransferKeys(random: SecureRandom = SecureRandom()) {
    private val pair: KeyPair = KeyPairGenerator.getInstance("EC").run { initialize(ECGenParameterSpec("secp256r1"), random); generateKeyPair() }
    val publicKey: ByteArray = pair.public.encoded

    fun agree(peer: ByteArray): ByteArray = KeyAgreement.getInstance("ECDH").run {
        init(pair.private)
        doPhase(SetupTransferCrypto.publicKey(peer), true)
        generateSecret()
    }
}

class SetupTransferSession internal constructor(private val sealKey: ByteArray, private val proofKey: ByteArray, val transcript: ByteArray) {
    fun proof(role: String): ByteArray = SetupTransferCrypto.hmac(proofKey, role.toByteArray(Charsets.US_ASCII) + transcript)

    fun accepts(role: String, proof: ByteArray): Boolean = MessageDigest.isEqual(proof(role), proof)

    fun seal(plain: ByteArray, random: SecureRandom = SecureRandom()): ByteArray {
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sealKey, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(transcript)
        return nonce + cipher.doFinal(SetupPacking.gzip(plain))
    }

    fun open(box: ByteArray, limit: Int = SetupVault.MAX_PLAIN_BYTES): ByteArray {
        if (box.size < 12 + 16) throw SetupCryptoException(SetupCryptoException.Reason.DAMAGED)
        val packed = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(sealKey, "AES"), GCMParameterSpec(128, box, 0, 12))
            cipher.updateAAD(transcript)
            cipher.doFinal(box, 12, box.size - 12)
        } catch (_: java.security.GeneralSecurityException) { throw SetupCryptoException(SetupCryptoException.Reason.LOCKED) }
        return SetupPacking.gunzip(packed, limit)
    }

    fun close() { sealKey.fill(0); proofKey.fill(0) }
}

object SetupTransferCrypto {
    const val CODE_LENGTH = 6
    const val NONCE_BYTES = 16
    const val SENDER = "sender"
    const val RECEIVER = "receiver"
    private const val LABEL = "nuvio-livetv-copy-v1"
    private val P = BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16)
    private val A = BigInteger("ffffffff00000001000000000000000000000000fffffffffffffffffffffffc", 16)
    private val B = BigInteger("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b", 16)

    fun validCode(code: String): Boolean = code.length == CODE_LENGTH && code.all { it in '0'..'9' }

    fun publicKey(encoded: ByteArray): ECPublicKey {
        if (encoded.size !in 64..128) throw IllegalArgumentException("Invalid key")
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded)) as? ECPublicKey ?: throw IllegalArgumentException("Invalid key")
        val curve = key.params.curve
        val field = curve.field as? java.security.spec.ECFieldFp ?: throw IllegalArgumentException("Invalid key")
        if (field.p != P || curve.a != A || curve.b != B) throw IllegalArgumentException("Invalid key")
        val x = key.w.affineX
        val y = key.w.affineY
        if (x.signum() < 0 || y.signum() < 0 || x >= P || y >= P) throw IllegalArgumentException("Invalid key")
        if (y.multiply(y).mod(P) != x.modPow(BigInteger.valueOf(3), P).add(A.multiply(x)).add(B).mod(P)) throw IllegalArgumentException("Invalid key")
        return key
    }

    fun session(keys: SetupTransferKeys, peer: ByteArray, code: String, receiverKey: ByteArray, senderKey: ByteArray,
        receiverNonce: ByteArray, senderNonce: ByteArray): SetupTransferSession {
        require(validCode(code) && receiverNonce.size == NONCE_BYTES && senderNonce.size == NONCE_BYTES)
        val shared = keys.agree(peer)
        try {
            val transcript = MessageDigest.getInstance("SHA-256").run {
                update(LABEL.toByteArray(Charsets.US_ASCII))
                for (part in listOf(receiverKey, senderKey, receiverNonce, senderNonce)) { update(ByteBuffer.allocate(4).putInt(part.size).array()); update(part) }
                digest()
            }
            val material = hkdf(transcript, shared, "$LABEL|$code".toByteArray(Charsets.US_ASCII), 64)
            return SetupTransferSession(material.copyOfRange(0, 32), material.copyOfRange(32, 64), transcript).also { material.fill(0) }
        } finally { shared.fill(0) }
    }

    fun hkdf(salt: ByteArray, input: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * 32)
        val prk = hmac(salt, input)
        val out = ByteArrayOutputStream(length)
        var block = ByteArray(0)
        var counter = 1
        while (out.size() < length) {
            block = hmac(prk, block + info + byteArrayOf(counter.toByte()))
            out.write(block)
            counter++
        }
        prk.fill(0)
        return out.toByteArray().copyOf(length)
    }

    fun hmac(key: ByteArray, data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(if (key.isEmpty()) ByteArray(32) else key, "HmacSHA256"))
        doFinal(data)
    }
}
