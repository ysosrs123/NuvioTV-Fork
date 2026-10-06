package com.nuvio.tv.data.iptv

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

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
