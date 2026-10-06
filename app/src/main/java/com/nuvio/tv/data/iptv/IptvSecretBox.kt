package com.nuvio.tv.data.iptv

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.IOException
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

interface IptvSecretBox {
    fun seal(context: String, plaintext: String): ByteArray
    fun open(context: String, ciphertext: ByteArray): String
}

class AndroidIptvSecretBox(private val alias: String = "com.nuvio.tv.iptv.v1") : IptvSecretBox {
    private val key: SecretKey by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { synchronized(keyLock) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    } }

    override fun seal(context: String, plaintext: String): ByteArray = protect {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(context.toByteArray(Charsets.UTF_8))
        check(cipher.iv.size == 12)
        byteArrayOf(1) + cipher.iv + cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
    }

    override fun open(context: String, ciphertext: ByteArray): String = protect {
        require(ciphertext.size >= 29 && ciphertext[0] == 1.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, ciphertext.copyOfRange(1, 13)))
        cipher.updateAAD(context.toByteArray(Charsets.UTF_8))
        cipher.doFinal(ciphertext, 13, ciphertext.size - 13).toString(Charsets.UTF_8)
    }

    private fun <T> protect(block: () -> T): T = try { block() } catch (_: Exception) {
        throw IOException("IPTV protected storage unavailable")
    }
    private companion object { val keyLock = Any() }
}

class EnvelopeIptvSecretBox(private val root: IptvSecretBox, private val random: SecureRandom = SecureRandom()) : IptvSecretBox {
    private val current: Pair<SecretKey, ByteArray> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val raw = ByteArray(32).also(random::nextBytes)
        val wrapped = root.seal(KEY_CONTEXT, raw.joinToString("") { "%02x".format(it) })
        check(wrapped.size <= 255)
        SecretKeySpec(raw, "AES") to wrapped
    }
    private val opened = ConcurrentHashMap<String, SecretKey>()

    override fun seal(context: String, plaintext: String): ByteArray = protect {
        val (key, wrapped) = current
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(context.toByteArray(Charsets.UTF_8))
        byteArrayOf(2, wrapped.size.toByte()) + wrapped + iv + cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
    }

    override fun open(context: String, ciphertext: ByteArray): String {
        if (ciphertext.isEmpty() || ciphertext[0] != 2.toByte()) return root.open(context, ciphertext)
        return protect {
            val length = ciphertext[1].toInt() and 255
            require(ciphertext.size >= 2 + length + 12 + 16)
            val wrapped = ciphertext.copyOfRange(2, 2 + length)
            val key = keyFor(wrapped)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, ciphertext, 2 + length, 12))
            cipher.updateAAD(context.toByteArray(Charsets.UTF_8))
            cipher.doFinal(ciphertext, 14 + length, ciphertext.size - 14 - length).toString(Charsets.UTF_8)
        }
    }

    private fun keyFor(wrapped: ByteArray): SecretKey {
        val id = String(wrapped, Charsets.ISO_8859_1)
        opened[id]?.let { return it }
        val hex = root.open(KEY_CONTEXT, wrapped)
        require(hex.length == 64)
        val key = SecretKeySpec(ByteArray(32) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }, "AES")
        if (opened.size >= 64) opened.clear()
        return opened.putIfAbsent(id, key) ?: key
    }

    private fun <T> protect(block: () -> T): T = try { block() } catch (error: IOException) { throw error } catch (_: Exception) {
        throw IOException("IPTV protected storage unavailable")
    }
    private companion object { const val KEY_CONTEXT = "nuvio-iptv-data-key" }
}
