package com.nuvio.tv.core.party

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import java.util.concurrent.Executor

class PartyFriendsTest {

    private val random = SecureRandom()
    private val sam = PartyIdentity.random(random)
    private val jo = PartyIdentity.random(random)
    private val now = 1_790_000_000L

    private fun invite(code: String = "H7KQ29") = PartyInvite(
        id = "",
        from = sam.publicKeyHex,
        name = "Sam",
        host = "Lounge",
        code = code,
        title = "Top Gun: Maverick",
        poster = "https://image.tmdb.org/p.jpg",
        watching = 2,
        avatarUrl = "https://avatars/fox.png",
        colour = "#E53935",
        sentAt = 0,
    )

    @Test
    fun `both friends derive the same key and nobody else does`() {
        repeat(5) {
            val a = PartyIdentity.random(random)
            val b = PartyIdentity.random(random)
            val c = PartyIdentity.random(random)
            val ab = PartyInvites.sharedKey(a, b.publicKeyHex)!!
            assertArrayEquals(ab, PartyInvites.sharedKey(b, a.publicKeyHex))
            assertFalse(ab.contentEquals(PartyInvites.sharedKey(c, a.publicKeyHex)))
            assertEquals(32, ab.size)
        }
    }

    @Test
    fun `shared point with the generator is the public key`() {
        val generatorX = PartyCrypto.unhex("79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798")!!
        repeat(3) {
            val identity = PartyIdentity.random(random)
            assertEquals(identity.publicKeyHex, PartyCrypto.hex(Bip340.sharedX(identity.secretKey, generatorX)!!))
        }
        assertNull(Bip340.sharedX(sam.secretKey, ByteArray(31)))
        assertNull(PartyInvites.sharedKey(sam, "zz"))
    }

    @Test
    fun `a key proof only counts for the members it was made between`() {
        val proof = PartyInvites.proof(sam, "member-p", "member-a", random)
        assertTrue(PartyInvites.checkProof(sam.publicKeyHex, "member-p", "member-a", proof))
        assertFalse(PartyInvites.checkProof(sam.publicKeyHex, "member-p", "member-b", proof))
        assertFalse(PartyInvites.checkProof(sam.publicKeyHex, "member-c", "member-a", proof))
        assertFalse(PartyInvites.checkProof(jo.publicKeyHex, "member-p", "member-a", proof))
        assertFalse(PartyInvites.checkProof(sam.publicKeyHex, "member-p", "member-a", "00"))
        assertFalse(PartyInvites.checkProof("not a key", "member-p", "member-a", proof))
    }

    @Test
    fun `an invite opens for the friend it was sent to`() {
        val event = PartyInvites.seal(sam, jo.publicKeyHex, invite(), now, random)!!
        assertEquals(PartyInvites.KIND, event.kind)
        assertNotEquals(sam.publicKeyHex, event.pubkey)
        assertEquals(listOf("x", PartyInvites.inboxTag(jo.publicKeyHex)), event.tags[0])
        assertEquals(listOf("expiration", (now + 7_200).toString()), event.tags[1])
        assertFalse(event.content.contains("H7KQ29"))
        assertFalse(event.content.contains("Top Gun"))
        assertFalse("sender key hidden from relays", event.content.contains(sam.publicKeyHex))
        assertFalse(event.tags.flatten().contains(jo.publicKeyHex))

        val opened = PartyInvites.open(event, jo, { it == sam.publicKeyHex }, now + 60)!!
        assertEquals(invite().copy(id = opened.id, sentAt = now), opened)
        assertEquals(64, opened.id.length)
    }

    @Test
    fun `a copied invite in a new wrapper does not open, a resent copy keeps its id`() {
        val event = PartyInvites.seal(sam, jo.publicKeyHex, invite(), now, random)!!
        val friend: (String) -> Boolean = { it == sam.publicKeyHex }
        val rewrapped = Nostr.sign(PartyIdentity.random(random), now, event.kind, event.tags, event.content, ByteArray(32))
        assertNull(PartyInvites.open(rewrapped, jo, friend, now))
        val first = PartyInvites.open(event, jo, friend, now)!!
        assertEquals(first.id, PartyInvites.open(event, jo, friend, now + 30)!!.id)
        val another = PartyInvites.open(PartyInvites.seal(sam, jo.publicKeyHex, invite(), now, random)!!, jo, friend, now)!!
        assertNotEquals(first.id, another.id)
    }

    @Test
    fun `invites from strangers, for someone else, too old or tampered with are dropped`() {
        val event = PartyInvites.seal(sam, jo.publicKeyHex, invite(), now, random)!!
        val friend: (String) -> Boolean = { it == sam.publicKeyHex }
        assertNull("not a friend", PartyInvites.open(event, jo, { false }, now))
        assertNull("someone else", PartyInvites.open(event, PartyIdentity.random(random), { true }, now))
        assertNull("expired", PartyInvites.open(event, jo, friend, now + 7_200 + 601))
        assertNotNull("just inside", PartyInvites.open(event, jo, friend, now + 7_200 + 599))
        assertNull("from the future", PartyInvites.open(event, jo, friend, now - 601))
        assertNull("other kind", PartyInvites.open(event.copy(kind = Nostr.KIND_PARTY), jo, friend, now))
        assertNull("bad signature", PartyInvites.open(event.copy(sig = "00".repeat(64)), jo, friend, now))
        val content = event.content
        val flipped = content.substring(0, 30) + (if (content[30] == 'A') 'B' else 'A') + content.substring(31)
        assertNull("tampered", PartyInvites.open(event.copy(content = flipped), jo, friend, now))
        val outer = PartyInvites.sharedKey(jo, event.pubkey)!!
        val envelope = Json.parseToJsonElement(PartyCrypto.open(outer, content)!!).jsonObject
        val forgedEnvelope = """{"from":"${jo.publicKeyHex}","box":"${envelope["box"]!!.jsonPrimitive.content}"}"""
        val forged = event.copy(content = PartyCrypto.seal(outer, forgedEnvelope, random))
        assertNull("claims another sender", PartyInvites.open(forged, jo, { true }, now))
        assertNull("invalid code", PartyInvites.seal(sam, jo.publicKeyHex, invite("!!"), now, random)?.let { PartyInvites.open(it, jo, friend, now) })
    }

    @Test
    fun `a taken back invite opens as a cancellation for that party only`() {
        val friend: (String) -> Boolean = { it == sam.publicKeyHex }
        val cancel = PartyInvites.sealCancel(sam, jo.publicKeyHex, "H7KQ29", now, random)!!
        assertEquals(PartyInvites.KIND, cancel.kind)
        assertFalse(cancel.content.contains("H7KQ29"))
        val opened = PartyInvites.open(cancel, jo, friend, now)!!
        assertTrue(opened.cancelled)
        assertEquals("H7KQ29", opened.code)
        assertEquals(sam.publicKeyHex, opened.from)
        assertNull(PartyInvites.open(cancel, jo, { false }, now))
        assertNull(PartyInvites.open(cancel, PartyIdentity.random(random), { true }, now))
        assertFalse(PartyInvites.open(PartyInvites.seal(sam, jo.publicKeyHex, invite(), now, random)!!, jo, friend, now)!!.cancelled)
    }

    @Test
    fun `the friend list survives a round trip and sorts by last time together`() {
        val friends = listOf(
            PartyFriend(jo.publicKeyHex, "Jo", "https://a/jo.png", "#E53935", addedAt = 10, lastTogetherAt = 50, lastTitle = "Top Gun"),
            PartyFriend(sam.publicKeyHex, "Sam", addedAt = 20),
        )
        val decoded = PartyFriendCodec.decode(PartyFriendCodec.encode(friends))
        assertEquals(friends, decoded)
        assertEquals(listOf("Jo", "Sam"), PartyFriendCodec.sorted(decoded).map { it.name })
        assertEquals(emptyList<PartyFriend>(), PartyFriendCodec.decode("rubbish"))
        assertEquals(emptyList<PartyFriend>(), PartyFriendCodec.decode("""[{"key":"short","name":"x","added":1}]"""))
    }

    @Test
    fun `friend messages and the friend key in hello survive the codec`() {
        val request = PartyMessage.FriendRequest("id01", sam.publicKeyHex, "ab".repeat(64), "Sam", "https://a/p.png", "#1E88E5")
        val accept = PartyMessage.FriendAccept("id00", jo.publicKeyHex, "cd".repeat(64), "Jo")
        val hello = PartyMessage.Hello("Sam", host = true, term = 1, speedOk = true, avatar = "https://a/p.png", colour = "#1E88E5", friendKey = sam.publicKeyHex)
        listOf(request, accept, hello).forEach { assertEquals(it, PartyCodec.decode(PartyCodec.encode(it))) }
        val badKey = PartyCodec.encode(request).replace(sam.publicKeyHex, "nothex")
        assertNull(PartyCodec.decode(badKey))
        val oldHello = PartyCodec.decode("""{"v":1,"t":"hello","name":"Old","host":false,"term":0,"speedOk":true}""")
        assertEquals(PartyMessage.Hello("Old", false, 0, true), oldHello)
    }

    // ---- Inbox against fake relays ----

    private class ManualExecutor : Executor {
        private val queue = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) {
            queue.addLast(command)
        }

        fun runAll() {
            while (queue.isNotEmpty()) queue.removeFirst().run()
        }
    }

    private class FakeRelay(val url: String, var refuse: Boolean = false, var dead: Boolean = false) {
        val stored = mutableListOf<JsonObject>()
        val connections = mutableListOf<Conn>()

        inner class Conn(val callbacks: RelayCallbacks) : RelayConnection {
            var open = true
            val subscriptions = HashMap<String, Pair<Set<String>, Long>>()

            override fun send(text: String): Boolean {
                if (!open) return false
                val array = Json.parseToJsonElement(text).jsonArray
                when (array[0].jsonPrimitive.content) {
                    "REQ" -> {
                        val sub = array[1].jsonPrimitive.content
                        val filter = array[2].jsonObject
                        val tag = filter["#x"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
                        val since = filter["since"]!!.jsonPrimitive.long
                        subscriptions[sub] = tag to since
                        stored.filter { matches(it, tag, since) }.forEach { callbacks.onText("[\"EVENT\",\"$sub\",$it]") }
                        callbacks.onText("[\"EOSE\",\"$sub\"]")
                    }
                    "CLOSE" -> subscriptions.remove(array[1].jsonPrimitive.content)
                    "EVENT" -> {
                        val event = array[1].jsonObject
                        val id = event["id"]!!.jsonPrimitive.content
                        if (refuse) {
                            callbacks.onText("[\"OK\",\"$id\",false,\"blocked: not today\"]")
                        } else {
                            stored += event
                            callbacks.onText("[\"OK\",\"$id\",true,\"\"]")
                            connections.toList().forEach { c ->
                                c.subscriptions.forEach { (sub, filter) ->
                                    if (matches(event, filter.first, filter.second)) c.callbacks.onText("[\"EVENT\",\"$sub\",$event]")
                                }
                            }
                        }
                    }
                }
                return true
            }

            override fun close() {
                open = false
                connections.remove(this)
            }
        }

        private fun matches(event: JsonObject, tags: Set<String>, since: Long): Boolean =
            event["kind"]!!.jsonPrimitive.content == PartyInvites.KIND.toString() &&
                event["created_at"]!!.jsonPrimitive.long >= since &&
                event["tags"]!!.jsonArray.any { it.jsonArray[0].jsonPrimitive.content == "x" && it.jsonArray[1].jsonPrimitive.content in tags }

        fun connect(callbacks: RelayCallbacks): RelayConnection {
            val conn = Conn(callbacks)
            if (dead) {
                conn.open = false
                callbacks.onClosed()
            } else {
                connections += conn
                callbacks.onOpen()
            }
            return conn
        }
    }

    private class Net(urls: List<String>) {
        val relays = urls.associateWith { FakeRelay(it) }
        val worker = ManualExecutor()
        var ms = 0L
        val connector = object : RelayConnector {
            override fun connect(url: String, callbacks: RelayCallbacks): RelayConnection = relays.getValue(url).connect(callbacks)
        }

        fun inbox(relayList: List<String> = relays.keys.toList(), clock: Long = 1_790_000_000L) = PartyInbox(
            connector = connector,
            relays = { relayList },
            worker = worker,
            deliver = Executor { it.run() },
            wallClockSeconds = { clock },
            monotonicMs = { ms },
        )
    }

    private val urls = listOf("wss://relay.snort.social", "wss://nos.lol", "wss://relay.primal.net", "wss://relay.damus.io", "wss://nostr.mom")

    @Test
    fun `an invite reaches a friend who is listening and one who opens the app later`() {
        val net = Net(urls)
        val sender = net.inbox()
        val listening = mutableListOf<NostrEvent>()
        val receiver = net.inbox(urls.takeLast(2))
        receiver.listen(setOf(PartyInvites.inboxTag(jo.publicKeyHex))) { listening += it }
        net.worker.runAll()

        val event = PartyInvites.seal(sam, jo.publicKeyHex, invite(), now, random)!!
        var result: Pair<Int, Int>? = null
        sender.publish(event) { accepted, tried -> result = accepted to tried }
        net.worker.runAll()
        assertEquals(urls.size to urls.size, result)
        assertEquals(listOf(event.id), listening.map { it.id })
        assertNotNull(PartyInvites.open(listening.single(), jo, { it == sam.publicKeyHex }, now))

        val later = mutableListOf<NostrEvent>()
        val laterInbox = net.inbox(urls.take(1))
        laterInbox.listen(setOf(PartyInvites.inboxTag(jo.publicKeyHex))) { later += it }
        net.worker.runAll()
        assertEquals(listOf(event.id), later.map { it.id })
        assertTrue(net.relays.values.all { relay -> relay.connections.none { it.subscriptions.isEmpty() } })
    }

    @Test
    fun `the inbox ignores other inboxes and repeats, and closes when told`() {
        val net = Net(urls.take(3))
        val got = mutableListOf<NostrEvent>()
        val inbox = net.inbox()
        inbox.listen(setOf(PartyInvites.inboxTag(jo.publicKeyHex))) { got += it }
        net.worker.runAll()
        val sender = net.inbox()
        sender.publish(PartyInvites.seal(sam, PartyIdentity.random(random).publicKeyHex, invite(), now, random)!!) { _, _ -> }
        val forJo = PartyInvites.seal(sam, jo.publicKeyHex, invite(), now, random)!!
        sender.publish(forJo) { _, _ -> }
        net.worker.runAll()
        assertEquals(listOf(forJo.id), got.map { it.id })
        inbox.listen(emptySet()) { }
        net.worker.runAll()
        assertTrue(net.relays.values.all { it.connections.isEmpty() })
    }

    @Test
    fun `one inbox listens for every profile on the box`() {
        val net = Net(urls.take(3))
        val sarah = PartyIdentity.random(random)
        val got = mutableListOf<NostrEvent>()
        net.inbox().listen(setOf(PartyInvites.inboxTag(jo.publicKeyHex), PartyInvites.inboxTag(sarah.publicKeyHex))) { got += it }
        net.worker.runAll()
        val sender = net.inbox()
        val forJo = PartyInvites.seal(sam, jo.publicKeyHex, invite(), now, random)!!
        val forSarah = PartyInvites.seal(sam, sarah.publicKeyHex, invite(), now, random)!!
        sender.publish(forJo) { _, _ -> }
        sender.publish(forSarah) { _, _ -> }
        net.worker.runAll()
        assertEquals(setOf(forJo.id, forSarah.id), got.map { it.id }.toSet())
        assertNotNull(PartyInvites.open(got.first { it.id == forSarah.id }, sarah, { it == sam.publicKeyHex }, now))
        assertNull(PartyInvites.open(got.first { it.id == forSarah.id }, jo, { it == sam.publicKeyHex }, now))
    }

    @Test
    fun `built-in avatars travel as short names and anything else must be https`() {
        assertEquals("fun:animals/fox", PartyInvites.cleanAvatar("fun:animals/fox"))
        assertEquals("https://a/p.png", PartyInvites.cleanAvatar("https://a/p.png"))
        assertNull(PartyInvites.cleanAvatar("file:///sdcard/x.png"))
        assertNull(PartyInvites.cleanAvatar("fun:../etc/passwd"))
        assertNull(PartyInvites.cleanAvatar("fun:Animals/Fox"))
        assertEquals("file:///android_asset/party-avatars/animals/fox.svg", PartyAvatars.imageUri("fun:animals/fox"))
        assertEquals("https://a/p.png", PartyAvatars.imageUri("https://a/p.png"))
        assertNull(PartyAvatars.imageUri("content://x"))
        val hello = PartyMessage.Hello("Sam", true, 1, true, avatar = "fun:bottts/kai")
        assertEquals(hello, PartyCodec.decode(PartyCodec.encode(hello)))
    }

    @Test
    fun `a send counts refusals and dead relays and still finishes`() {
        val net = Net(urls.take(3))
        net.relays.getValue(urls[0]).refuse = true
        net.relays.getValue(urls[1]).dead = true
        var result: Pair<Int, Int>? = null
        net.inbox().publish(PartyInvites.seal(sam, jo.publicKeyHex, invite(), now, random)!!) { a, t -> result = a to t }
        net.worker.runAll()
        assertEquals(1 to 3, result)
    }

    @Test
    fun `a send to a relay that never answers gives up after the timeout`() {
        val silent = object : RelayConnector {
            override fun connect(url: String, callbacks: RelayCallbacks): RelayConnection = object : RelayConnection {
                override fun send(text: String) = true
                override fun close() = Unit
            }
        }
        val worker = ManualExecutor()
        var ms = 0L
        val inbox = PartyInbox(silent, { urls.take(2) }, worker, Executor { it.run() }, { now }, { ms })
        var result: Pair<Int, Int>? = null
        inbox.publish(PartyInvites.seal(sam, jo.publicKeyHex, invite(), now, random)!!) { a, t -> result = a to t }
        worker.runAll()
        assertNull(result)
        ms = 13_000
        inbox.tick()
        worker.runAll()
        assertEquals(0 to 2, result)
    }
}
