package com.nuvio.tv.core.profile

import com.nuvio.tv.core.profile.OfflinePinVault.Answer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflinePinVaultTest {

    private val kept = mutableMapOf<String, String>()
    private val records = object : OfflinePinVault.Records {
        override fun get(key: String): String? = kept[key]
        override fun put(key: String, value: String?) {
            if (value == null) kept.remove(key) else kept[key] = value
        }
    }
    private val vault = OfflinePinVault(records, iterations = 50)
    private val now = 1_000_000L

    @Test
    fun `nothing saved means only the server can say`() {
        assertEquals(Answer.NoCopy, vault.check(1, "1234", now))
    }

    @Test
    fun `the last accepted PIN unlocks and a wrong one does not`() {
        vault.remember(1, "1234")
        assertEquals(Answer.Unlocked, vault.check(1, "1234", now))
        assertEquals(Answer.Refused(0), vault.check(1, "4321", now))
    }

    @Test
    fun `the PIN itself is never kept`() {
        vault.remember(1, "1234")
        assertTrue(kept.isNotEmpty())
        assertFalse(kept.values.any { "1234" in it.split(':').drop(1) })
    }

    @Test
    fun `the same PIN on two profiles is kept differently`() {
        vault.remember(1, "1234")
        vault.remember(2, "1234")
        assertNotEquals(kept["h1"], kept["h2"])
        assertEquals(Answer.NoCopy, vault.check(3, "1234", now))
    }

    @Test
    fun `five wrong tries mean a wait, and the right PIN does not shorten it`() {
        vault.remember(1, "1234")
        repeat(OfflinePinVault.MAX_TRIES - 1) { assertEquals(Answer.Refused(0), vault.check(1, "0000", now)) }
        assertEquals(Answer.Refused(OfflinePinVault.WAIT_SECONDS), vault.check(1, "0000", now))
        assertEquals(Answer.Refused(OfflinePinVault.WAIT_SECONDS - 10), vault.check(1, "1234", now + 10_000L))
        assertEquals(Answer.Unlocked, vault.check(1, "1234", now + OfflinePinVault.WAIT_SECONDS * 1000L))
    }

    @Test
    fun `a right PIN clears the count of wrong tries`() {
        vault.remember(1, "1234")
        repeat(OfflinePinVault.MAX_TRIES - 1) { vault.check(1, "0000", now) }
        assertEquals(Answer.Unlocked, vault.check(1, "1234", now))
        assertEquals(Answer.Refused(0), vault.check(1, "0000", now))
    }

    @Test
    fun `a removed PIN is forgotten`() {
        vault.remember(1, "1234")
        vault.forget(1)
        assertEquals(Answer.NoCopy, vault.check(1, "1234", now))
        assertTrue(kept.isEmpty())
    }

    @Test
    fun `a PIN the server refuses is forgotten only if the box still accepted it`() {
        vault.remember(1, "1234")
        vault.forgetIfMatches(1, "9999")
        assertEquals(Answer.Unlocked, vault.check(1, "1234", now))
        vault.forgetIfMatches(1, "1234")
        assertEquals(Answer.NoCopy, vault.check(1, "1234", now))
    }

    @Test
    fun `a damaged record is treated as nothing saved`() {
        kept["h1"] = "not-a-record"
        assertEquals(Answer.NoCopy, vault.check(1, "1234", now))
        kept["h1"] = "50:zz:00"
        assertEquals(Answer.NoCopy, vault.check(1, "1234", now))
    }
}
