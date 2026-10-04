package com.nuvio.tv.core.party

import java.security.SecureRandom
import java.util.concurrent.Executor

/**
 * Listens for invites to this device's inbox tag while the app is open, and sends invites to every known relay
 * so a friend listening on any of them gets it. All state is touched on [worker] only; callbacks go through [deliver].
 */
internal class PartyInbox(
    private val connector: RelayConnector,
    private val relays: () -> List<String>,
    private val worker: Executor,
    private val deliver: Executor,
    private val wallClockSeconds: () -> Long,
    private val monotonicMs: () -> Long,
    private val random: SecureRandom = SecureRandom(),
    private val log: (String) -> Unit = {},
) {
    private class Slot(val url: String, val subscriptionId: String, val startedAt: Long) {
        var connection: RelayConnection? = null
        var open = false
    }

    private class Delivery(val event: NostrEvent, val startedAt: Long, val done: (Int, Int) -> Unit) {
        val waiting = HashMap<String, RelayConnection?>()
        var accepted = 0
        var tried = 0
        var finished = false
    }

    private val slots = mutableListOf<Slot>()
    private val retryAt = HashMap<String, Long>()
    private val failures = HashMap<String, Int>()
    private val seen = object : LinkedHashMap<String, Boolean>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean = size > SEEN_LIMIT
    }
    private val deliveries = mutableListOf<Delivery>()
    private var tags: Set<String> = emptySet()
    private var onEvent: ((NostrEvent) -> Unit)? = null
    private var nextCandidate = 0

    /** Starts listening on [inboxTags] (one per profile with friends), or stops when there are none. */
    fun listen(inboxTags: Set<String>, onEvent: (NostrEvent) -> Unit) {
        worker.execute {
            if (inboxTags == tags) {
                this.onEvent = onEvent
                return@execute
            }
            closeSlots()
            tags = inboxTags
            this.onEvent = if (inboxTags.isEmpty()) null else onEvent
            if (inboxTags.isNotEmpty()) {
                log("inbox listening for ${inboxTags.size}")
                maintain()
            } else {
                log("inbox closed")
            }
        }
    }

    /** Sends [event] to every relay; [done] gets how many accepted it and how many were tried. */
    fun publish(event: NostrEvent, done: (accepted: Int, tried: Int) -> Unit) {
        worker.execute {
            val delivery = Delivery(event, monotonicMs(), done)
            deliveries += delivery
            val text = Nostr.eventMessage(event)
            relays().distinct().forEach { url ->
                delivery.tried++
                val listening = slots.firstOrNull { it.url == url && it.open }
                if (listening != null) {
                    delivery.waiting[url] = null
                    if (listening.connection?.send(text) != true) delivery.waiting.remove(url)
                    return@forEach
                }
                delivery.waiting[url] = null
                val callbacks = object : RelayCallbacks {
                    override fun onOpen() = worker.execute {
                        if (!delivery.finished && url in delivery.waiting) {
                            if (delivery.waiting[url]?.send(text) != true) answered(delivery, url, false)
                        }
                    }
                    override fun onText(text: String) = worker.execute { onDeliveryText(delivery, url, text) }
                    override fun onClosed() = worker.execute { answered(delivery, url, false) }
                }
                val connection = runCatching { connector.connect(url, callbacks) }.getOrNull()
                if (connection == null) answered(delivery, url, false) else if (url in delivery.waiting) delivery.waiting[url] = connection
            }
            finishIfDone(delivery)
        }
    }

    /** Refills the listening pool and gives up on slow sends. Call about once a second. */
    fun tick() {
        worker.execute {
            val now = monotonicMs()
            slots.toList().forEach { slot ->
                if (!slot.open && now - slot.startedAt > CONNECT_TIMEOUT_MS) fail(slot, now, "no answer")
            }
            maintain()
            deliveries.toList().forEach { delivery ->
                if (now - delivery.startedAt > SEND_TIMEOUT_MS) {
                    delivery.waiting.keys.toList().forEach { answered(delivery, it, false) }
                }
            }
        }
    }

    private fun onDeliveryText(delivery: Delivery, url: String, text: String) {
        val message = Nostr.parseRelayMessage(text) as? RelayMessage.Ok ?: return
        if (message.eventId != delivery.event.id) return
        if (!message.accepted) log("invite refused by ${host(url)}: ${message.message.take(100)}")
        answered(delivery, url, message.accepted)
    }

    private fun answered(delivery: Delivery, url: String, accepted: Boolean) {
        if (delivery.finished || url !in delivery.waiting) return
        val connection = delivery.waiting.remove(url)
        connection?.close()
        if (accepted) delivery.accepted++
        finishIfDone(delivery)
    }

    private fun finishIfDone(delivery: Delivery) {
        if (delivery.finished || delivery.waiting.isNotEmpty()) return
        delivery.finished = true
        deliveries.remove(delivery)
        log("invite sent to ${delivery.accepted} of ${delivery.tried} relays")
        deliver.execute { delivery.done(delivery.accepted, delivery.tried) }
    }

    private fun maintain() {
        val inbox = tags.takeIf { it.isNotEmpty() } ?: return
        val known = relays().distinct()
        if (known.isEmpty()) return
        val now = monotonicMs()
        for (spread in listOf(true, false)) {
            var attempts = 0
            while (slots.size < PartyRelays.ACTIVE && attempts < known.size) {
                val url = known[nextCandidate % known.size]
                nextCandidate++
                attempts++
                if (slots.any { it.url == url }) continue
                if ((retryAt[url] ?: Long.MIN_VALUE) > now) continue
                if (spread && slots.any { PartyRelays.provider(it.url) == PartyRelays.provider(url) }) continue
                start(url, inbox, now)
            }
        }
    }

    private fun start(url: String, inbox: Set<String>, now: Long) {
        val slot = Slot(url, "i" + PartyCrypto.hex(ByteArray(6).also(random::nextBytes)), now)
        slots += slot
        val callbacks = object : RelayCallbacks {
            override fun onOpen() = worker.execute { onOpen(slot, inbox) }
            override fun onText(text: String) = worker.execute { onText(slot, text) }
            override fun onClosed() = worker.execute { if (slot in slots) fail(slot, monotonicMs(), "closed") }
        }
        slot.connection = runCatching { connector.connect(url, callbacks) }.getOrNull()
        if (slot.connection == null) fail(slot, now, "could not connect")
    }

    private fun onOpen(slot: Slot, inbox: Set<String>) {
        if (slot !in slots || tags != inbox) return
        val since = wallClockSeconds() - PartyInvites.LIFETIME_SECONDS - LOOKBACK_SLACK_SECONDS
        if (slot.connection?.send(Nostr.requestMessage(slot.subscriptionId, PartyInvites.KIND, inbox, since)) != true) {
            fail(slot, monotonicMs(), "subscribe failed")
            return
        }
        slot.open = true
        failures.remove(slot.url)
    }

    private fun onText(slot: Slot, text: String) {
        val inbox = tags
        if (inbox.isEmpty() || slot !in slots) return
        when (val message = Nostr.parseRelayMessage(text)) {
            is RelayMessage.Event -> {
                val event = message.event
                if (message.subscriptionId != slot.subscriptionId || event.kind != PartyInvites.KIND) return
                if (event.tags.none { it.size >= 2 && it[0] == Nostr.ROOM_TAG && it[1] in inbox }) return
                if (seen.put(event.id, true) != null) return
                val target = onEvent ?: return
                deliver.execute { target(event) }
            }
            is RelayMessage.Closed -> if (message.subscriptionId == slot.subscriptionId) {
                fail(slot, monotonicMs(), "subscription closed: ${message.message.take(100)}")
            }
            is RelayMessage.Ok -> deliveries.toList().forEach { delivery ->
                if (message.eventId == delivery.event.id) {
                    if (!message.accepted) log("invite refused by ${host(slot.url)}: ${message.message.take(100)}")
                    answered(delivery, slot.url, message.accepted)
                }
            }
            else -> Unit
        }
    }

    private fun fail(slot: Slot, now: Long, reason: String) {
        if (!slots.remove(slot)) return
        runCatching { slot.connection?.close() }
        releaseDeliveries(slot.url)
        val count = (failures[slot.url] ?: 0) + 1
        failures[slot.url] = count
        val wait = (RETRY_STEP_MS * count).coerceAtMost(RETRY_MAX_MS)
        retryAt[slot.url] = now + wait
        log("inbox relay ${host(slot.url)} dropped ($reason), retry in ${wait / 1000} s")
        maintain()
    }

    /** Sends that went through a listening connection to [url] get no answer from it any more. */
    private fun releaseDeliveries(url: String) {
        deliveries.toList().forEach { delivery ->
            if (url in delivery.waiting && delivery.waiting[url] == null) answered(delivery, url, false)
        }
    }

    private fun closeSlots() {
        slots.toList().forEach { slot ->
            slot.connection?.let { connection ->
                if (slot.open) connection.send(Nostr.closeMessage(slot.subscriptionId))
                connection.close()
            }
            releaseDeliveries(slot.url)
        }
        slots.clear()
    }

    private fun host(url: String): String = url.substringAfter("://").trimEnd('/')

    private companion object {
        const val SEEN_LIMIT = 256
        const val CONNECT_TIMEOUT_MS = 10_000L
        const val SEND_TIMEOUT_MS = 12_000L
        const val RETRY_STEP_MS = 10_000L
        const val RETRY_MAX_MS = 120_000L
        const val LOOKBACK_SLACK_SECONDS = 600L
    }
}
