package com.nuvio.tv.core.iptv

import org.junit.Assert.*
import org.junit.Test

class SetupPhonesTest {
    private var now = 1_000L
    private fun phones(max: Int = 8) = SetupPhones(now = { now }, maxPhones = max)
    private val android = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Mobile Safari/537.36"
    private val iphone = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.6 Mobile/15E148 Safari/604.1"

    @Test fun issuedTokensAre256BitAndVerifyOnlyThemselves() {
        val book = phones()
        val token = book.issue(android, 2)
        assertTrue(SetupPhones.TOKEN.matches(token))
        assertNotEquals(token, book.issue(android, 2))
        val phone = book.verify(token)
        assertNotNull(phone)
        assertEquals("Chrome", phone!!.browser)
        assertEquals("Android", phone.platform)
        assertEquals(2, phone.profile)
        assertEquals(1_000L, phone.pairedAt)
        assertNull(book.verify(token.dropLast(1) + if (token.last() == '0') '1' else '0'))
        assertNull(book.verify(token.uppercase()))
        assertNull(book.verify(""))
        assertNull(book.verify(null))
        assertNull(phones().verify(token))
        assertFalse(phone.toString().contains(token))
        assertFalse(book.toString().contains(token))
    }

    @Test fun onlyHashesAreStoredAndTheyLoadBack() {
        val book = phones()
        val token = book.issue(iphone, 1)
        val saved = book.encode()
        assertFalse(saved.contains(token))
        val loaded = phones().apply { load(saved) }
        assertEquals("Safari", loaded.verify(token)?.browser)
        assertEquals("iPhone", loaded.verify(token)?.platform)
        assertEquals(book.phones.single().id, loaded.phones.single().id)
        assertTrue(phones().apply { load("not json") }.phones.isEmpty())
        assertTrue(phones().apply { load(null) }.phones.isEmpty())
        assertTrue(phones().apply { load("""[{"id":"zz","hash":"00"}]""") }.phones.isEmpty())
    }

    @Test fun removingOneOrAllRevokesTokens() {
        val book = phones()
        val first = book.issue(android, 1)
        now += 10
        val second = book.issue(iphone, 1)
        assertEquals(listOf("iPhone", "Android"), book.phones.map { it.platform })
        assertTrue(book.remove(book.verify(first)!!.id))
        assertNull(book.verify(first))
        assertNotNull(book.verify(second))
        assertFalse(book.remove("0123456789abcdef"))
        book.clear()
        assertNull(book.verify(second))
        val third = book.issue(android, 1)
        assertTrue(book.forget(third))
        assertNull(book.verify(third))
        assertFalse(book.forget(third))
    }

    @Test fun theOldestPhoneMakesRoomWhenFull() {
        val book = phones(max = 2)
        val first = book.issue(android, 1)
        now += 1
        val second = book.issue(android, 1)
        now += 1
        val third = book.issue(iphone, 1)
        assertNull(book.verify(first))
        assertNotNull(book.verify(second))
        assertNotNull(book.verify(third))
        assertEquals(2, book.phones.size)
    }

    @Test fun userAgentsBecomeFixedNamesOnly() {
        assertEquals("Chrome" to "Android", SetupPhones.describe(android))
        assertEquals("Safari" to "iPhone", SetupPhones.describe(iphone))
        assertEquals("Edge" to "Windows", SetupPhones.describe("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Safari/537.36 Edg/129.0"))
        assertEquals("Firefox" to "Mac", SetupPhones.describe("Mozilla/5.0 (Macintosh; Intel Mac OS X 14.6; rv:131.0) Gecko/20100101 Firefox/131.0"))
        assertEquals("Samsung Internet" to "Android", SetupPhones.describe("Mozilla/5.0 (Linux; Android 14; SM-S921B) AppleWebKit/537.36 (KHTML, like Gecko) SamsungBrowser/26.0 Chrome/122.0 Mobile Safari/537.36"))
        assertEquals(null to null, SetupPhones.describe("<script>alert(1)</script>"))
        assertEquals(null to null, SetupPhones.describe(null))
    }

    @Test fun aPhoneCannotOpenAnotherProfileThatHasAPin() {
        val phone = SetupPhone("0123456789abcdef", null, null, 0, 1)
        assertEquals(true, SetupPhones.canUse(phone, 1, setOf(1, 2)))
        assertEquals(true, SetupPhones.canUse(phone, 3, setOf(1, 2)))
        assertEquals(false, SetupPhones.canUse(phone, 2, setOf(1, 2)))
    }

    @Test fun noProfileOpensUntilPinLocksAreKnown() {
        val phone = SetupPhone("0123456789abcdef", null, null, 0, 1)
        assertNull(SetupPhones.canUse(phone, 1, null))
        assertNull(SetupPhones.canUse(phone, 2, null))
        assertEquals(true, SetupPhones.canUse(phone, 2, emptySet()))
    }

    @Test fun pairingIsLimitedPerAddressAndOverall() {
        val gate = SetupPairGate(perAddress = 2, overall = 3, windowMillis = 60_000, now = { now })
        assertTrue(gate.allow("192.168.1.5"))
        assertTrue(gate.allow("192.168.1.5"))
        assertFalse(gate.allow("192.168.1.5"))
        assertTrue(gate.allow("192.168.1.6"))
        assertFalse(gate.allow("192.168.1.7"))
        now += 60_000
        assertTrue(gate.allow("192.168.1.7"))
        assertTrue(gate.allow("192.168.1.5"))
    }

    @Test fun pairingCanReopenWithAFreshLinkAndCode() {
        val pairing = SetupPairing()
        val before = pairing.credentials
        val paired = pairing.pair(before.token, before.code) as SetupPairing.Result.Paired
        pairing.close()
        assertFalse(pairing.open)
        pairing.reopen()
        assertTrue(pairing.open)
        assertNotEquals(before.token, pairing.credentials.token)
        assertNull(pairing.session(before.token, paired.sessionId))
        assertFalse(pairing.knowsLink(before.token))
        assertTrue(pairing.pair(pairing.credentials.token, pairing.credentials.code) is SetupPairing.Result.Paired)
    }

    @Test fun phoneCookieIsHttpOnlyAndScopedToThePhonePage() {
        val token = phones().issue(android, 1)
        val cookie = SetupCookies.phone(token)
        assertTrue(cookie.startsWith("nuvio_phone=$token;"))
        assertTrue("HttpOnly" in cookie && "SameSite=Strict" in cookie && "Path=/p/" in cookie && "Max-Age=" in cookie)
        assertThrows(IllegalArgumentException::class.java) { SetupCookies.phone("abc; Path=/") }
        assertEquals(token, SetupCookies.read("nuvio_setup=ab; nuvio_phone=$token", SetupCookies.PHONE))
        assertTrue("Max-Age=0" in SetupCookies.forgetPhone())
    }

    @Test fun pendingChangesCanExpire() {
        val book = SetupChangeBook(now = { now })
        val id = book.propose("phone:1", SetupProfileChoice(2))!!
        assertTrue(book.resolve(id, SetupChangeBook.Status.EXPIRED))
        assertEquals(SetupChangeBook.Status.EXPIRED, book.status("phone:1", id))
        assertFalse(book.coolingDown("phone:1"))
    }
}
