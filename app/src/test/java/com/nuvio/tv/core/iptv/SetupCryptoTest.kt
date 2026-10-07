package com.nuvio.tv.core.iptv

import java.security.SecureRandom
import org.junit.Assert.*
import org.junit.Test

class SetupCryptoTest {
    private val plain = ("{\"format\":\"nuvio-livetv-setup\",\"secret\":\"hunter2\"}" + " ".repeat(2000)).toByteArray()
    private val pass = "correct horse".toCharArray()

    private fun reason(block: () -> Unit): SetupCryptoException.Reason = try { block(); fail(); throw AssertionError() } catch (error: SetupCryptoException) { error.reason }

    @Test fun backupRoundTripsAndNeverHoldsPlainText() {
        val sealed = SetupVault.seal(plain, pass, iterations = SetupVault.MIN_ITERATIONS)
        assertTrue(SetupVault.isBackup(sealed))
        assertFalse(String(sealed, Charsets.ISO_8859_1).contains("hunter2"))
        assertArrayEquals(plain, SetupVault.open(sealed, "correct horse".toCharArray()))
        assertFalse(SetupVault.seal(plain, pass, iterations = SetupVault.MIN_ITERATIONS).contentEquals(sealed))
    }

    @Test fun wrongPassphraseAndTamperingAreRefused() {
        val sealed = SetupVault.seal(plain, pass, iterations = SetupVault.MIN_ITERATIONS)
        assertEquals(SetupCryptoException.Reason.LOCKED, reason { SetupVault.open(sealed, "correct horsf".toCharArray()) })
        assertEquals(SetupCryptoException.Reason.LOCKED, reason { SetupVault.open(sealed, CharArray(0)) })
        for (index in listOf(20, 30, sealed.size / 2, sealed.size - 1)) {
            val changed = sealed.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
            assertEquals(index.toString(), SetupCryptoException.Reason.LOCKED, reason { SetupVault.open(changed, pass) })
        }
        assertEquals(SetupCryptoException.Reason.LOCKED, reason { SetupVault.open(sealed.copyOf(sealed.size - 3), pass) })
        val iterations = sealed.copyOf().also { it[11] = (it[11].toInt() xor 1).toByte() }
        assertEquals(SetupCryptoException.Reason.LOCKED, reason { SetupVault.open(iterations, pass) })
    }

    @Test fun otherFilesAndUnknownVersionsAreRecognised() {
        assertEquals(SetupCryptoException.Reason.NOT_BACKUP, reason { SetupVault.open("hello world, this is not a backup at all....".toByteArray(), pass) })
        assertEquals(SetupCryptoException.Reason.NOT_BACKUP, reason { SetupVault.open(ByteArray(10), pass) })
        val sealed = SetupVault.seal(plain, pass, iterations = SetupVault.MIN_ITERATIONS)
        assertEquals(SetupCryptoException.Reason.UNSUPPORTED, reason { SetupVault.open(sealed.copyOf().also { it[6] = 2 }, pass) })
        assertEquals(SetupCryptoException.Reason.UNSUPPORTED, reason { SetupVault.open(sealed.copyOf().also { it[8] = 0x7f }, pass) })
        assertEquals(SetupCryptoException.Reason.UNSUPPORTED, reason { SetupVault.open(sealed.copyOf().also { it[8] = 0; it[9] = 0; it[10] = 0; it[11] = 1 }, pass) })
        assertEquals(SetupCryptoException.Reason.TOO_LARGE, reason { SetupVault.open(ByteArray(SetupVault.MAX_FILE_BYTES + 1), pass) })
    }

    @Test fun passphrasesNeedEightCharactersAndCompareAfterNormalising() {
        assertFalse(SetupVault.acceptable("short".toCharArray()))
        assertFalse(SetupVault.acceptable("        ".toCharArray()))
        assertTrue(SetupVault.acceptable("eight ch".toCharArray()))
        try { SetupVault.seal(plain, "short".toCharArray(), iterations = SetupVault.MIN_ITERATIONS); fail() } catch (_: IllegalArgumentException) { }
        val composed = SetupVault.seal(plain, "café au lait".toCharArray(), iterations = SetupVault.MIN_ITERATIONS)
        assertArrayEquals(plain, SetupVault.open(composed, "café au lait".toCharArray()))
    }

    @Test fun packedDataIsBoundedWhenOpened() {
        val bomb = SetupPacking.gzip(ByteArray(2_000_000))
        assertEquals(SetupCryptoException.Reason.TOO_LARGE, reason { SetupPacking.gunzip(bomb, 1_000_000) })
        assertEquals(SetupCryptoException.Reason.DAMAGED, reason { SetupPacking.gunzip(byteArrayOf(1, 2, 3), 100) })
    }

    private fun handshake(senderCode: String, receiverCode: String): Pair<SetupTransferSession, SetupTransferSession> {
        val random = SecureRandom()
        val receiver = SetupTransferKeys(random)
        val sender = SetupTransferKeys(random)
        val nr = ByteArray(16).also(random::nextBytes)
        val ns = ByteArray(16).also(random::nextBytes)
        val atSender = SetupTransferCrypto.session(sender, receiver.publicKey, senderCode, receiver.publicKey, sender.publicKey, nr, ns)
        val atReceiver = SetupTransferCrypto.session(receiver, sender.publicKey, receiverCode, receiver.publicKey, sender.publicKey, nr, ns)
        return atSender to atReceiver
    }

    @Test fun transferNeedsTheSameCodeOnBothSides() {
        val (sender, receiver) = handshake("123456", "123456")
        assertTrue(receiver.accepts(SetupTransferCrypto.SENDER, sender.proof(SetupTransferCrypto.SENDER)))
        assertTrue(sender.accepts(SetupTransferCrypto.RECEIVER, receiver.proof(SetupTransferCrypto.RECEIVER)))
        assertFalse(sender.accepts(SetupTransferCrypto.RECEIVER, sender.proof(SetupTransferCrypto.SENDER)))
        val box = sender.seal(plain)
        assertFalse(String(box, Charsets.ISO_8859_1).contains("hunter2"))
        assertArrayEquals(plain, receiver.open(box))
        val (wrongSender, wrongReceiver) = handshake("123456", "123457")
        assertFalse(wrongReceiver.accepts(SetupTransferCrypto.SENDER, wrongSender.proof(SetupTransferCrypto.SENDER)))
        assertEquals(SetupCryptoException.Reason.LOCKED, reason { wrongReceiver.open(wrongSender.seal(plain)) })
    }

    @Test fun tamperedTransferPayloadsAndForeignKeysAreRefused() {
        val (sender, receiver) = handshake("000042", "000042")
        val box = sender.seal(plain)
        for (index in listOf(0, 13, box.size - 1)) {
            val changed = box.copyOf().also { it[index] = (it[index].toInt() xor 0x40).toByte() }
            assertEquals(SetupCryptoException.Reason.LOCKED, reason { receiver.open(changed) })
        }
        assertEquals(SetupCryptoException.Reason.DAMAGED, reason { receiver.open(ByteArray(8)) })
        val other = SetupTransferKeys()
        try { SetupTransferCrypto.publicKey(other.publicKey.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }); fail() } catch (_: Exception) { }
        try { SetupTransferCrypto.publicKey(ByteArray(91)); fail() } catch (_: Exception) { }
        val ec384 = java.security.KeyPairGenerator.getInstance("EC").run { initialize(java.security.spec.ECGenParameterSpec("secp384r1")); generateKeyPair() }
        try { SetupTransferCrypto.publicKey(ec384.public.encoded); fail() } catch (_: Exception) { }
        assertNotNull(SetupTransferCrypto.publicKey(other.publicKey))
        assertFalse(SetupTransferCrypto.validCode("12345")); assertFalse(SetupTransferCrypto.validCode("12345a")); assertTrue(SetupTransferCrypto.validCode("012345"))
    }

    @Test fun hkdfMatchesTheRfcVector() {
        fun hex(text: String) = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val okm = SetupTransferCrypto.hkdf(hex("000102030405060708090a0b0c"), hex("0b".repeat(22)), hex("f0f1f2f3f4f5f6f7f8f9"), 42)
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865", SetupPairing.hex(okm))
    }
}
