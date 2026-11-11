package com.nuvio.tv.core.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCryptoTest {
    private val iterations = 2_000

    @Test
    fun `the right PIN opens what was sealed`() {
        val box = BackupCrypto.seal("[{\"store\":\"debrid_settings\"}]", "123456", iterations)
        assertEquals("[{\"store\":\"debrid_settings\"}]", BackupCrypto.open(box, "123456"))
    }

    @Test
    fun `a wrong PIN or a changed file is refused`() {
        val box = BackupCrypto.seal("secret", "1234567", iterations)
        assertThrows(WrongBackupPinException::class.java) { BackupCrypto.open(box, "7654321") }
        val tampered = box.copy(data = box.data.reversed())
        assertThrows(WrongBackupPinException::class.java) { BackupCrypto.open(tampered, "1234567") }
    }

    @Test
    fun `each seal uses fresh salt and nonce`() {
        val first = BackupCrypto.seal("same", "123456", iterations)
        val second = BackupCrypto.seal("same", "123456", iterations)
        assertNotEquals(first.salt, second.salt)
        assertNotEquals(first.iv, second.iv)
        assertNotEquals(first.data, second.data)
    }

    @Test
    fun `PINs are 6 to 16 digits`() {
        assertFalse(BackupCrypto.isValidPin("12345"))
        assertTrue(BackupCrypto.isValidPin("123456"))
        assertTrue(BackupCrypto.isValidPin("1234567890123456"))
        assertFalse(BackupCrypto.isValidPin("12345678901234567"))
        assertFalse(BackupCrypto.isValidPin("12345a"))
    }

    @Test
    fun `the full strength key is used by default`() {
        val box = BackupCrypto.seal("x", "123456")
        assertEquals(600_000, box.iterations)
        assertEquals("PBKDF2WithHmacSHA256", box.kdf)
    }
}
