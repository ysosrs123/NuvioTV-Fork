package com.nuvio.tv.core.party

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

class NostrPartyTransportTest {

    private class ManualExecutor : Executor {
        private val queue = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) {
            queue.addLast(command)
        }

        fun runAll(): Boolean {
            var ran = false
            while (queue.isNotEmpty()) {
                queue.removeFirst().run()
                ran = true
            }
            return ran
        }
    }

    private enum class Behaviour { ACCEPT, DEAD, REJECT_RATE, REJECT_CLOCK }

    private class FakeRelay(val url: String, var behaviour: Behaviour = Behaviour.ACCEPT) {
        inner class Connection(val callbacks: RelayCallbacks) : RelayConnection {
            var open = true
            val subscriptions = HashMap<String, String>()

            override fun send(text: String): Boolean {
                if (!open) return false
                received += text
                val array = Json.parseToJsonElement(text).jsonArray
                when (array[0].jsonPrimitive.content) {
                    "REQ" -> {
                        val filter = array[2].jsonObject
                        subscriptions[array[1].jsonPrimitive.content] = filter["#x"]!!.jsonArray[0].jsonPrimitive.content
                        callbacks.onText("[\"EOSE\",\"${array[1].jsonPrimitive.content}\"]")
                    }
                    "CLOSE" -> subscriptions.remove(array[1].jsonPrimitive.content)
                    "EVENT" -> publish(this, array[1].jsonObject)
                }
                return true
            }

            override fun close() {
                open = false
                connections.remove(this)
            }
        }

        val connections = mutableListOf<Connection>()
        val received = mutableListOf<String>()
        var tamper = false

        fun connect(callbacks: RelayCallbacks): RelayConnection {
            val connection = Connection(callbacks)
            if (behaviour == Behaviour.DEAD) {
                connection.open = false
                callbacks.onClosed()
            } else {
                connections += connection
                callbacks.onOpen()
            }
            return connection
        }

        private fun publish(from: Connection, event: JsonObject) {
            val id = event["id"]!!.jsonPrimitive.content
            when (behaviour) {
                Behaviour.REJECT_RATE -> {
                    from.callbacks.onText("[\"OK\",\"$id\",false,\"rate-limited: you note too much\"]")
                    return
                }
                Behaviour.REJECT_CLOCK -> {
                    from.callbacks.onText("[\"OK\",\"$id\",false,\"invalid: created_at too far off\"]")
                    return
                }
                else -> from.callbacks.onText("[\"OK\",\"$id\",true,\"\"]")
            }
            val room = event["tags"]!!.jsonArray.map { it.jsonArray }.first { it[0].jsonPrimitive.content == "x" }[1].jsonPrimitive.content
            val forwarded = if (tamper) JsonObject(event + ("content" to JsonPrimitive("AAAA"))) else event
            connections.toList().forEach { connection ->
                connection.subscriptions.filterValues { it == room }.keys.forEach { subscription ->
                    val message: JsonArray = buildJsonArray {
                        add(JsonPrimitive("EVENT"))
                        add(JsonPrimitive(subscription))
                        add(forwarded)
                    }
                    connection.callbacks.onText(message.toString())
                }
            }
        }

        fun dropAll() {
            connections.toList().forEach { connection ->
                connection.open = false
                connections.remove(connection)
                connection.callbacks.onClosed()
            }
        }
    }

    private class Recorder : PartyTransport.Listener {
        val messages = mutableListOf<Pair<String, String>>()
        val problems = mutableListOf<PartyTransportProblem>()
        var connected = false
        var connects = 0
        override fun onConnected() {
            connected = true
            connects++
        }

        override fun onDisconnected() {
            connected = false
        }

        override fun onMessage(senderId: String, payload: String) {
            messages += senderId to payload
        }

        override fun onProblem(problem: PartyTransportProblem) {
            problems += problem
        }
    }

    private class World(urls: List<String>) {
        val relays = urls.associateWith { FakeRelay(it) }
        val worker = ManualExecutor()
        var nowMs = 0L
        val connector = object : RelayConnector {
            override fun connect(url: String, callbacks: RelayCallbacks): RelayConnection = relays.getValue(url).connect(callbacks)
        }
        val transports = mutableListOf<NostrPartyTransport>()

        fun transport(): NostrPartyTransport = NostrPartyTransport(
            connector = connector,
            relays = relays.keys.toList(),
            identity = PartyIdentity.random(),
            worker = worker,
            deliver = worker,
            wallClockSeconds = { 1_700_000_000L + nowMs / 1000 },
            monotonicMs = { nowMs },
            kdfIterations = 20,
        ).also { transports += it }

        fun pump() {
            while (worker.runAll()) Unit
        }

        fun advance(ms: Long) {
            var left = ms
            while (left > 0) {
                nowMs += 1_000
                left -= 1_000
                transports.forEach { it.tick() }
                pump()
            }
        }
    }

    private val urls = listOf("wss://r1", "wss://r2", "wss://r3", "wss://r4", "wss://r5")

    @Test
    fun `a message reaches the other member once and never its sender`() {
        val world = World(urls)
        val a = world.transport()
        val b = world.transport()
        val fromA = Recorder()
        val fromB = Recorder()
        a.open("H7KQ29", fromA)
        b.open("H7KQ29", fromB)
        world.pump()
        assertTrue(fromA.connected && fromB.connected)
        assertEquals(1, fromA.connects)
        a.send("hello")
        world.pump()
        assertEquals(listOf(a.localId to "hello"), fromB.messages)
        assertTrue(fromA.messages.isEmpty())
        assertEquals(3, world.relays.values.count { relay -> relay.received.any { it.startsWith("[\"EVENT\"") } })
        assertTrue(world.relays.values.flatMap { it.received }.none { "hello" in it })
    }

    @Test
    fun `another room hears nothing`() {
        val world = World(urls)
        val a = world.transport()
        val b = world.transport()
        val fromB = Recorder()
        a.open("H7KQ29", Recorder())
        b.open("H7KQ28", fromB)
        world.pump()
        a.send("hello")
        world.pump()
        assertTrue(fromB.messages.isEmpty())
    }

    @Test
    fun `dead relays are replaced by the next ones`() {
        val world = World(urls)
        world.relays.getValue("wss://r1").behaviour = Behaviour.DEAD
        world.relays.getValue("wss://r2").behaviour = Behaviour.DEAD
        val a = world.transport()
        val b = world.transport()
        val fromB = Recorder()
        a.open("H7KQ29", Recorder())
        b.open("H7KQ29", fromB)
        world.pump()
        a.send("one")
        world.pump()
        assertEquals(listOf("one"), fromB.messages.map { it.second })
        assertEquals(3, world.relays.values.count { it.connections.size == 2 })
    }

    @Test
    fun `the active relays come from different providers`() {
        val world = World(
            listOf("wss://offchain.pub", "wss://nostr.bitcoiner.social", "wss://nos.lol", "wss://nostr.mom", "wss://relay.snort.social")
        )
        world.transport().open("H7KQ29", Recorder())
        world.pump()
        val used = world.relays.filterValues { it.connections.isNotEmpty() }.keys
        assertEquals(setOf("wss://offchain.pub", "wss://nos.lol", "wss://relay.snort.social"), used)
    }

    @Test
    fun `relays of one provider still fill the pool when nothing else is left`() {
        val world = World(listOf("wss://offchain.pub", "wss://nostr.bitcoiner.social", "wss://nos.lol", "wss://nostr.mom"))
        world.transport().open("H7KQ29", Recorder())
        world.pump()
        assertEquals(3, world.relays.values.count { it.connections.isNotEmpty() })
    }

    @Test
    fun `the built-in list starts with three providers`() {
        assertEquals(3, PartyRelays.DEFAULT.take(PartyRelays.ACTIVE).map(PartyRelays::provider).distinct().size)
        assertEquals("example.org", PartyRelays.provider("wss://Example.org:443/path"))
    }

    @Test
    fun `losing every relay reconnects and reports a room that stays unreachable`() {
        val world = World(urls.take(3))
        val a = world.transport()
        val listener = Recorder()
        a.open("H7KQ29", listener)
        world.pump()
        assertTrue(listener.connected)
        world.relays.values.forEach { it.behaviour = Behaviour.DEAD }
        world.relays.values.forEach { it.dropAll() }
        world.pump()
        assertFalse(listener.connected)
        world.advance(25_000)
        assertEquals(listOf(PartyTransportProblem.NO_RELAY_REACHABLE), listener.problems)
        world.relays.values.forEach { it.behaviour = Behaviour.ACCEPT }
        world.advance(70_000)
        assertTrue(listener.connected)
        assertEquals(2, listener.connects)
    }

    @Test
    fun `a relay that keeps refusing events is dropped and a clock refusal is reported`() {
        val world = World(urls)
        world.relays.getValue("wss://r1").behaviour = Behaviour.REJECT_RATE
        world.relays.getValue("wss://r2").behaviour = Behaviour.REJECT_CLOCK
        val a = world.transport()
        val b = world.transport()
        val fromA = Recorder()
        val fromB = Recorder()
        a.open("H7KQ29", fromA)
        b.open("H7KQ29", fromB)
        world.pump()
        repeat(3) {
            a.send("m$it")
            world.pump()
        }
        assertEquals(listOf("m0", "m1", "m2"), fromB.messages.map { it.second })
        assertEquals(listOf(PartyTransportProblem.DEVICE_CLOCK_REJECTED), fromA.problems)
        assertEquals(1, world.relays.getValue("wss://r1").connections.size)
        assertEquals(1, world.relays.getValue("wss://r2").connections.size)
        assertEquals(1, world.relays.getValue("wss://r4").connections.size)
        assertEquals(1, world.relays.getValue("wss://r5").connections.size)
        a.send("m3")
        world.pump()
        assertEquals("m3", fromB.messages.last().second)
    }

    @Test
    fun `tampered events are ignored`() {
        val world = World(urls.take(1))
        world.relays.getValue("wss://r1").tamper = true
        val a = world.transport()
        val b = world.transport()
        val fromB = Recorder()
        a.open("H7KQ29", Recorder())
        b.open("H7KQ29", fromB)
        world.pump()
        a.send("hello")
        world.pump()
        assertTrue(fromB.messages.isEmpty())
    }

    @Test
    fun `messages sent while connecting are delivered once connected`() {
        val world = World(urls)
        val a = world.transport()
        val b = world.transport()
        val fromB = Recorder()
        b.open("H7KQ29", fromB)
        world.pump()
        a.open("H7KQ29", Recorder())
        a.send("early")
        world.pump()
        assertEquals(listOf("early"), fromB.messages.map { it.second })
    }

    @Test
    fun `closing leaves the relays`() {
        val world = World(urls)
        val a = world.transport()
        a.open("H7KQ29", Recorder())
        world.pump()
        assertEquals(3, world.relays.values.sumOf { it.connections.size })
        a.close()
        world.pump()
        assertEquals(0, world.relays.values.sumOf { it.connections.size })
    }
}
