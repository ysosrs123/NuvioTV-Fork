package com.nuvio.tv.core.party

import java.security.SecureRandom
import java.util.concurrent.Executor

/**
 * Publishes room messages as encrypted ephemeral events through several public relays at once.
 * All state is touched on [worker] only; the listener is called through [deliver].
 */
internal class NostrPartyTransport(
    private val connector: RelayConnector,
    relays: List<String>,
    private val identity: PartyIdentity,
    private val worker: Executor,
    private val deliver: Executor,
    private val wallClockSeconds: () -> Long,
    private val monotonicMs: () -> Long,
    private val random: SecureRandom = SecureRandom(),
    private val kdfIterations: Int? = null,
    private val log: (String) -> Unit = {},
) : PartyTransport {

    private class Slot(val url: String, val subscriptionId: String, val startedAt: Long) {
        var connection: RelayConnection? = null
        var open = false
        var rejects = 0
        var received = 0
    }

    private class Pending(val payload: String, val at: Long)

    private val relays = relays.distinct()
    private val slots = mutableListOf<Slot>()
    private val retryAt = HashMap<String, Long>()
    private val failures = HashMap<String, Int>()
    private val seen = object : LinkedHashMap<String, Boolean>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean = size > SEEN_LIMIT
    }
    private val pending = ArrayDeque<Pending>()
    private var keys: PartyRoomKeys? = null
    private var listener: PartyTransport.Listener? = null
    private var closed = true
    private var connected = false
    private var nextCandidate = 0
    private var openedAt = 0L
    private var lastConnectedAt = 0L
    private var unreachableReported = false
    private var lastClockProblemAt = Long.MIN_VALUE
    private var sentCount = 0
    private var lastSummaryAt = 0L

    override val localId: String get() = identity.publicKeyHex

    override fun open(code: String, listener: PartyTransport.Listener) {
        worker.execute {
            this.listener = listener
            closed = false
            connected = false
            unreachableReported = false
            openedAt = monotonicMs()
            lastConnectedAt = openedAt
            keys = if (kdfIterations != null) PartyCrypto.deriveRoom(code, kdfIterations) else PartyCrypto.deriveRoom(code)
            lastSummaryAt = monotonicMs()
            log("room key ready in ${lastSummaryAt - openedAt} ms, ${relays.size} relays known")
            maintain()
        }
    }

    override fun send(payload: String) {
        worker.execute {
            if (closed) return@execute
            if (!publish(payload)) {
                pending.addLast(Pending(payload, monotonicMs()))
                while (pending.size > PENDING_LIMIT) pending.removeFirst()
            }
        }
    }

    override fun close() {
        worker.execute {
            closed = true
            listener = null
            pending.clear()
            slots.toList().forEach { slot ->
                slot.connection?.let { connection ->
                    if (slot.open) connection.send(Nostr.closeMessage(slot.subscriptionId))
                    connection.close()
                }
            }
            slots.clear()
            keys = null
        }
    }

    /** Refills the relay pool and reports a room that stays unreachable. Call about once a second. */
    fun tick() {
        worker.execute {
            if (closed) return@execute
            val now = monotonicMs()
            slots.toList().forEach { slot ->
                if (!slot.open && now - slot.startedAt > CONNECT_TIMEOUT_MS) fail(slot, now, reason = "no answer")
            }
            maintain()
            if (now - lastSummaryAt >= SUMMARY_INTERVAL_MS) {
                lastSummaryAt = now
                log("relays ${slots.joinToString { "${host(it.url)}${if (it.open) "" else "(opening)"} in=${it.received}" }} out=$sentCount")
            }
            if (!connected && !unreachableReported && now - lastConnectedAt > UNREACHABLE_AFTER_MS) {
                unreachableReported = true
                log("no relay reachable for ${now - lastConnectedAt} ms")
                report(PartyTransportProblem.NO_RELAY_REACHABLE)
            }
        }
    }

    private fun maintain() {
        if (closed || keys == null || relays.isEmpty()) return
        val now = monotonicMs()
        fill(now, spread = true)
        fill(now, spread = false)
    }

    private fun fill(now: Long, spread: Boolean) {
        var attempts = 0
        while (slots.size < PartyRelays.ACTIVE && attempts < relays.size) {
            val url = relays[nextCandidate % relays.size]
            nextCandidate++
            attempts++
            if (slots.any { it.url == url }) continue
            if ((retryAt[url] ?: Long.MIN_VALUE) > now) continue
            if (spread && slots.any { PartyRelays.provider(it.url) == PartyRelays.provider(url) }) continue
            start(url, now)
        }
    }

    private fun start(url: String, now: Long) {
        val slot = Slot(url, "p" + PartyCrypto.hex(ByteArray(6).also(random::nextBytes)), now)
        slots += slot
        val callbacks = object : RelayCallbacks {
            override fun onOpen() = worker.execute { onRelayOpen(slot) }
            override fun onText(text: String) = worker.execute { onRelayText(slot, text) }
            override fun onClosed() = worker.execute { if (slot in slots) fail(slot, monotonicMs(), reason = "closed") }
        }
        slot.connection = runCatching { connector.connect(url, callbacks) }.getOrNull()
        if (slot.connection == null) fail(slot, now, reason = "could not connect")
    }

    private fun onRelayOpen(slot: Slot) {
        val room = keys ?: return
        if (closed || slot !in slots) return
        val since = wallClockSeconds() - SUBSCRIBE_LOOKBACK_SECONDS
        if (slot.connection?.send(Nostr.requestMessage(slot.subscriptionId, Nostr.KIND_PARTY, room.roomTag, since)) != true) {
            fail(slot, monotonicMs(), reason = "subscribe failed")
            return
        }
        log("relay ${host(slot.url)} open in ${monotonicMs() - slot.startedAt} ms")
        slot.open = true
        failures.remove(slot.url)
        updateConnected()
        val now = monotonicMs()
        while (pending.isNotEmpty()) {
            val item = pending.removeFirst()
            if (now - item.at <= PENDING_MAX_AGE_MS) publish(item.payload)
        }
    }

    private fun onRelayText(slot: Slot, text: String) {
        val room = keys ?: return
        if (closed || slot !in slots) return
        when (val message = Nostr.parseRelayMessage(text)) {
            is RelayMessage.Event -> {
                val event = message.event
                if (event.kind != Nostr.KIND_PARTY || event.pubkey == identity.publicKeyHex) return
                if (event.tags.none { it.size >= 2 && it[0] == Nostr.ROOM_TAG && it[1] == room.roomTag }) return
                if (seen.containsKey(event.id)) return
                if (!Nostr.verify(event)) return
                val payload = PartyCrypto.open(room.payloadKey, event.content) ?: return
                seen[event.id] = true
                slot.received++
                val target = listener ?: return
                deliver.execute { target.onMessage(event.pubkey, payload) }
            }
            is RelayMessage.Ok -> {
                if (message.accepted) {
                    slot.rejects = 0
                    return
                }
                val reason = message.message.lowercase()
                log("relay ${host(slot.url)} refused a message: ${message.message.take(120)}")
                val clock = CLOCK_WORDS.any { it in reason }
                if (!clock && POLICY_WORDS.any { it in reason }) {
                    fail(slot, monotonicMs(), POLICY_BACKOFF_MS, "closed to us: ${message.message.take(80)}")
                    return
                }
                if (clock) {
                    val now = monotonicMs()
                    if (now - lastClockProblemAt > CLOCK_PROBLEM_REPEAT_MS || lastClockProblemAt == Long.MIN_VALUE) {
                        lastClockProblemAt = now
                        report(PartyTransportProblem.DEVICE_CLOCK_REJECTED)
                    }
                }
                slot.rejects++
                if (slot.rejects >= MAX_REJECTS) fail(slot, monotonicMs(), REJECTED_BACKOFF_MS, "refused $MAX_REJECTS messages")
            }
            is RelayMessage.Closed -> if (message.subscriptionId == slot.subscriptionId) {
                fail(slot, monotonicMs(), reason = "subscription closed: ${message.message.take(120)}")
            }
            is RelayMessage.EndOfStored, is RelayMessage.Notice, null -> Unit
        }
    }

    private fun publish(payload: String): Boolean {
        val room = keys ?: return false
        val targets = slots.filter { it.open }
        if (targets.isEmpty()) return false
        val event = Nostr.sign(
            identity = identity,
            createdAt = wallClockSeconds(),
            kind = Nostr.KIND_PARTY,
            tags = listOf(listOf(Nostr.ROOM_TAG, room.roomTag)),
            content = PartyCrypto.seal(room.payloadKey, payload, random),
            auxRand = ByteArray(32).also(random::nextBytes),
        )
        seen[event.id] = true
        sentCount++
        val text = Nostr.eventMessage(event)
        var sent = false
        targets.forEach { slot ->
            if (slot.connection?.send(text) == true) sent = true else fail(slot, monotonicMs(), reason = "send failed")
        }
        return sent
    }

    private fun fail(slot: Slot, now: Long, backoffMs: Long? = null, reason: String) {
        if (!slots.remove(slot)) return
        runCatching { slot.connection?.close() }
        val count = (failures[slot.url] ?: 0) + 1
        failures[slot.url] = count
        val wait = backoffMs ?: (RETRY_STEP_MS * count).coerceAtMost(RETRY_MAX_MS)
        retryAt[slot.url] = now + wait
        log("relay ${host(slot.url)} dropped ($reason) after ${now - slot.startedAt} ms, in=${slot.received}, retry in ${wait / 1000} s")
        updateConnected()
        maintain()
    }

    private fun updateConnected() {
        val now = slots.any { it.open }
        if (now == connected) return
        connected = now
        val target = listener ?: return
        if (now) {
            unreachableReported = false
            deliver.execute { target.onConnected() }
        } else {
            lastConnectedAt = monotonicMs()
            deliver.execute { target.onDisconnected() }
        }
    }

    private fun host(url: String): String = url.substringAfter("://").trimEnd('/')

    private fun report(problem: PartyTransportProblem) {
        val target = listener ?: return
        deliver.execute { target.onProblem(problem) }
    }

    private companion object {
        const val SEEN_LIMIT = 1024
        const val PENDING_LIMIT = 8
        const val PENDING_MAX_AGE_MS = 5_000L
        const val CONNECT_TIMEOUT_MS = 10_000L
        const val UNREACHABLE_AFTER_MS = 20_000L
        const val SUBSCRIBE_LOOKBACK_SECONDS = 120L
        const val MAX_REJECTS = 3
        const val REJECTED_BACKOFF_MS = 120_000L
        const val RETRY_STEP_MS = 5_000L
        const val RETRY_MAX_MS = 60_000L
        const val CLOCK_PROBLEM_REPEAT_MS = 60_000L
        const val SUMMARY_INTERVAL_MS = 60_000L
        val CLOCK_WORDS = listOf("created_at", "too old", "too far", "future", "expired", "timestamp")
        val POLICY_WORDS = listOf("web of trust", "policy", "blocked", "restricted", "not allowed", "whitelist", "allowlist", "auth-required", "paid")
        const val POLICY_BACKOFF_MS = 30 * 60_000L
    }
}
