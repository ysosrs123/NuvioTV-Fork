package com.nuvio.tv.core.party

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class PartyBasicsTest {

    @Test
    fun `codes avoid look-alike characters and survive spacing and case`() {
        repeat(200) {
            val code = PartyCode.generate()
            assertEquals(PartyCode.LENGTH, code.length)
            assertTrue(code.none { it in "01OIL" })
            assertEquals(code, PartyCode.normalize(code.lowercase().chunked(3).joinToString(" ")))
        }
        assertNull(PartyCode.normalize("ABC12"))
        assertNull(PartyCode.normalize("ABCD10"))
        assertEquals("H7K Q29", PartyCode.display("H7KQ29"))
    }

    @Test
    fun `room keys depend on the code and nothing else`() {
        val a = PartyCrypto.deriveRoom("H7KQ29", iterations = 50)
        val b = PartyCrypto.deriveRoom("H7KQ29", iterations = 50)
        val c = PartyCrypto.deriveRoom("H7KQ28", iterations = 50)
        assertEquals(a.roomTag, b.roomTag)
        assertTrue(a.payloadKey.contentEquals(b.payloadKey))
        assertNotEquals(a.roomTag, c.roomTag)
        assertEquals(32, a.roomTag.length)
        assertEquals(32, a.payloadKey.size)
    }

    @Test
    fun `key derivation matches the published PBKDF2 vector`() {
        // RFC 7914 section 11: PBKDF2-HMAC-SHA-256, P="passwd", S="salt", c=1, dkLen=64 (first 48 bytes used here).
        val expected = "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc49ca9cccf179b645991664b39d77ef31"
        val derived = PartyCrypto.pbkdf2("passwd".toByteArray(), "salt".toByteArray(), 1, 48)
        assertEquals(expected, PartyCrypto.hex(derived))
    }

    @Test
    fun `sealed payloads open only with the room key`() {
        val random = SecureRandom()
        val key = PartyCrypto.deriveRoom("H7KQ29", iterations = 50).payloadKey
        val other = PartyCrypto.deriveRoom("H7KQ28", iterations = 50).payloadKey
        val sealed = PartyCrypto.seal(key, "{\"t\":\"bye\"}", random)
        assertEquals("{\"t\":\"bye\"}", PartyCrypto.open(key, sealed))
        assertNull(PartyCrypto.open(other, sealed))
        assertNull(PartyCrypto.open(key, sealed.dropLast(4) + "AAAA"))
        assertNotEquals(sealed, PartyCrypto.seal(key, "{\"t\":\"bye\"}", random))
    }

    @Test
    fun `event ids follow the canonical serialisation`() {
        val serialised = Nostr.serializeForId(
            pubkey = "ab",
            createdAt = 1_700_000_000,
            kind = Nostr.KIND_PARTY,
            tags = listOf(listOf("x", "room"), listOf("t", "a\"b")),
            content = "line1\nline2\t\\",
        )
        assertEquals(
            "[0,\"ab\",1700000000,25711,[[\"x\",\"room\"],[\"t\",\"a\\\"b\"]],\"line1\\nline2\\t\\\\\"]",
            serialised,
        )
    }

    @Test
    fun `signed events verify and tampering is caught`() {
        val identity = PartyIdentity.random()
        val event = Nostr.sign(identity, 1_700_000_000, Nostr.KIND_PARTY, listOf(listOf("x", "room")), "content", ByteArray(32))
        assertTrue(Nostr.verify(event))
        assertFalse(Nostr.verify(event.copy(content = "other")))
        assertFalse(Nostr.verify(event.copy(createdAt = event.createdAt + 1)))
        val parsed = Nostr.parseRelayMessage("[\"EVENT\",\"sub\",${Nostr.eventMessage(event).substringAfter(',').dropLast(1)}]")
        assertEquals(RelayMessage.Event("sub", event), parsed)
    }

    @Test
    fun `relay replies are parsed`() {
        assertEquals(RelayMessage.Ok("id", false, "rate-limited: slow down"), Nostr.parseRelayMessage("[\"OK\",\"id\",false,\"rate-limited: slow down\"]"))
        assertEquals(RelayMessage.EndOfStored("s"), Nostr.parseRelayMessage("[\"EOSE\",\"s\"]"))
        assertEquals(RelayMessage.Closed("s", "bye"), Nostr.parseRelayMessage("[\"CLOSED\",\"s\",\"bye\"]"))
        assertEquals(RelayMessage.Notice("hi"), Nostr.parseRelayMessage("[\"NOTICE\",\"hi\"]"))
        assertNull(Nostr.parseRelayMessage("not json"))
        assertNull(Nostr.parseRelayMessage("[\"EVENT\",\"s\",{\"id\":1}]"))
    }

    @Test
    fun `messages survive the wire`() {
        val media = PartyMedia(
            contentId = "tt0133093",
            contentType = "movie",
            videoId = "tt0133093",
            title = "The Matrix",
            poster = "https://example.org/p.jpg",
            fingerprint = PartyFingerprint("ABCDEF", 2, "The.Matrix.mkv", 8_200_000_000, "8E245D9679D31E12", 8_160_000),
            streamName = "2160p",
            addonName = "Addon",
            sharedUrl = "https://example.org/v.mkv",
        )
        val messages = listOf(
            PartyMessage.Hello("Lounge", host = true, term = 2, speedOk = false),
            PartyMessage.Media(2, 3, media.copy(fingerprint = media.fingerprint.copy(infoHash = "abcdef", videoHash = "8e245d9679d31e12"))),
            PartyMessage.State(2, 3, 10, 20, 30, true, PartyHoldInfo(PartyHoldReason.BUFFERING, "m1", 99), listOf("a", "b"), false, listOf("b")),
            PartyMessage.State(1, 0, 10, 10, 0, false),
            PartyMessage.Command(3, PartyAction.SEEK, 123_456),
            PartyMessage.Status(3, "Fire TV", 5, stalled = true, ready = false, speedOk = true, driftMs = -40),
            PartyMessage.Ping(77),
            PartyMessage.Pong("m1", 77, 88),
            PartyMessage.End(2),
            PartyMessage.Bye,
        )
        messages.forEach { assertEquals(it, PartyCodec.decode(PartyCodec.encode(it))) }
        assertNull(PartyCodec.decode("{\"v\":2,\"t\":\"bye\"}"))
        assertNull(PartyCodec.decode("{\"v\":1,\"t\":\"state\"}"))
        assertNull(PartyCodec.decode("[]"))
    }

    @Test
    fun `links and artwork from another member are checked on arrival`() {
        val media = PartyMedia(
            contentId = "tt0133093",
            contentType = "movie",
            title = "T".repeat(5_000),
            poster = "http://example.org/p.jpg",
            backdrop = "https://example.org/b.jpg",
            fingerprint = PartyFingerprint(null, null, null, null, null, null),
            sharedUrl = "http://192.168.1.20:8080/film.mkv",
        )
        val received = PartyCodec.decode(PartyCodec.encode(PartyMessage.Media(1, 0, media))) as PartyMessage.Media
        assertNull(received.media.sharedUrl)
        assertNull(received.media.poster)
        assertEquals("https://example.org/b.jpg", received.media.backdrop)
        assertTrue(received.media.title.length <= 300)
    }

    @Test
    fun `clock keeps the sample with the shortest round trip`() {
        val clock = PartyClock()
        assertNull(clock.hostNow(0))
        clock.addRoughHint(hostClock = 5_000, receivedAtLocal = 1_200)
        assertEquals(3_800L, clock.offsetMs)
        clock.addRoughHint(hostClock = 6_000, receivedAtLocal = 2_100)
        assertEquals(3_900L, clock.offsetMs)
        clock.addSample(sentAtLocal = 1_000, hostClock = 5_200, receivedAtLocal = 1_400)
        assertEquals(4_000L, clock.offsetMs)
        clock.addSample(sentAtLocal = 2_000, hostClock = 6_600, receivedAtLocal = 3_000)
        assertEquals(4_000L, clock.offsetMs)
        clock.addSample(sentAtLocal = 4_000, hostClock = 8_050, receivedAtLocal = 4_100)
        assertEquals(4_000L, clock.offsetMs)
        assertEquals(100L, clock.roundTripMs)
        assertEquals(9_000L, clock.hostNow(5_000))
    }

    @Test
    fun `members that may change speed nudge and only seek when far out`() {
        fun decide(drift: Long, nudging: Boolean = false) =
            PartySyncPolicy.decide(drift, speedAllowed = true, nudging = nudging, msSinceLastSeek = 1_000_000)
        assertEquals(PartySyncAction.Speed(1f), decide(100))
        assertEquals(PartySyncAction.Speed(1.05f), decide(1_500))
        assertEquals(PartySyncAction.Speed(0.95f), decide(-1_500))
        assertEquals(PartySyncAction.Speed(1.02f), decide(400))
        assertEquals(PartySyncAction.Speed(1.01f), decide(100, nudging = true))
        assertEquals(PartySyncAction.Speed(1f), decide(40, nudging = true))
        assertEquals(PartySyncAction.Seek, decide(2_500))
        assertEquals(PartySyncAction.Seek, decide(-2_500))
    }

    @Test
    fun `members that must not change speed never get a speed and seek rarely`() {
        fun decide(drift: Long, sinceSeek: Long, resync: Boolean = false) =
            PartySyncPolicy.decide(drift, speedAllowed = false, nudging = false, msSinceLastSeek = sinceSeek, resyncRequested = resync)
        for (drift in listOf(-6_000L, -1_600L, -400L, 0L, 400L, 1_400L, 1_600L, 6_000L)) {
            for (since in listOf(0L, 9_000L, 30_000L, 120_000L)) {
                assertFalse(decide(drift, since) is PartySyncAction.Speed)
            }
        }
        assertEquals(PartySyncAction.Hold, decide(1_400, 120_000))
        assertEquals(PartySyncAction.Hold, decide(1_600, 30_000))
        assertEquals(PartySyncAction.Seek, decide(1_600, 61_000))
        assertEquals(PartySyncAction.Hold, decide(6_000, 5_000))
        assertEquals(PartySyncAction.Seek, decide(6_000, 11_000))
        assertEquals(PartySyncAction.Seek, decide(400, 1_000, resync = true))
        assertEquals(PartySyncAction.Hold, decide(100, 1_000, resync = true))
    }

    @Test
    fun `streams are matched by hash, then by name and size`() {
        val host = PartyFingerprint("abc", 1, "Film.2160p.mkv", 20_000_000_000, "ffee", 7_000_000)
        fun match(candidate: PartyFingerprint) = PartyStreamMatcher.match(host, candidate)
        assertEquals(PartyMatch.SAME_FILE, match(PartyFingerprint("ABC", 1)))
        assertEquals(PartyMatch.DIFFERENT, match(PartyFingerprint("abc", 2)))
        assertEquals(PartyMatch.LIKELY_SAME, match(PartyFingerprint("abc", null)))
        assertEquals(PartyMatch.SAME_FILE, match(PartyFingerprint("abc", null, "film.2160p.mkv", 20_000_000_000)))
        assertEquals(PartyMatch.SAME_FILE, match(PartyFingerprint(null, null, "Film.2160p.mkv", 20_000_000_000)))
        assertEquals(PartyMatch.SAME_FILE, match(PartyFingerprint("zzz", 0, null, 20_000_000_000, "FFEE")))
        assertEquals(PartyMatch.LIKELY_SAME, match(PartyFingerprint(null, null, null, 20_000_000_000)))
        assertEquals(PartyMatch.DIFFERENT, match(PartyFingerprint("zzz", 0, "Film.1080p.mkv", 8_000_000_000)))
        assertEquals(PartyMatch.UNKNOWN, PartyStreamMatcher.match(PartyFingerprint(), PartyFingerprint("abc", 1)))
        val candidates = listOf(PartyFingerprint("zzz", 0), PartyFingerprint("abc", 1), PartyFingerprint("abc", 1))
        assertEquals(1, PartyStreamMatcher.pick(host, candidates) { it })
        assertNull(PartyStreamMatcher.pick(host, candidates.take(1)) { it })
        assertNull(PartyStreamMatcher.durationMismatchMs(7_000_000, 7_000_800))
        assertEquals(144_000L, PartyStreamMatcher.durationMismatchMs(7_000_000, 7_144_000))
        assertNull(PartyStreamMatcher.durationMismatchMs(null, 7_144_000))
    }

    @Test
    fun `relay lists are parsed from free text`() {
        assertEquals(
            listOf("wss://a.example", "wss://b.example", "ws://c.example"),
            PartyRelays.parse("wss://a.example, wss://b.example\nhttps://nope ws://c.example wss://a.example"),
        )
        assertTrue(PartyRelays.DEFAULT.size >= PartyRelays.ACTIVE * 2)
    }
}
