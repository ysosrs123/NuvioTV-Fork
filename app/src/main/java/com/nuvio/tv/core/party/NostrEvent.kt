package com.nuvio.tv.core.party

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

internal data class NostrEvent(
    val id: String,
    val pubkey: String,
    val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val sig: String,
)

internal sealed interface RelayMessage {
    data class Event(val subscriptionId: String, val event: NostrEvent) : RelayMessage
    data class Ok(val eventId: String, val accepted: Boolean, val message: String) : RelayMessage
    data class EndOfStored(val subscriptionId: String) : RelayMessage
    data class Closed(val subscriptionId: String, val message: String) : RelayMessage
    data class Notice(val message: String) : RelayMessage
}

internal object Nostr {
    /** Ephemeral range: relays forward these and do not store them. */
    const val KIND_PARTY = 25711
    const val ROOM_TAG = "x"

    private val json = Json { ignoreUnknownKeys = true }

    fun sign(
        identity: PartyIdentity,
        createdAt: Long,
        kind: Int,
        tags: List<List<String>>,
        content: String,
        auxRand: ByteArray,
    ): NostrEvent {
        val id = PartyCrypto.sha256(serializeForId(identity.publicKeyHex, createdAt, kind, tags, content).toByteArray(Charsets.UTF_8))
        val signature = Bip340.sign(id, identity.secretKey, auxRand)
        return NostrEvent(
            id = PartyCrypto.hex(id),
            pubkey = identity.publicKeyHex,
            createdAt = createdAt,
            kind = kind,
            tags = tags,
            content = content,
            sig = PartyCrypto.hex(signature),
        )
    }

    fun verify(event: NostrEvent): Boolean {
        val expected = PartyCrypto.sha256(
            serializeForId(event.pubkey, event.createdAt, event.kind, event.tags, event.content).toByteArray(Charsets.UTF_8)
        )
        if (PartyCrypto.hex(expected) != event.id) return false
        val pubkey = PartyCrypto.unhex(event.pubkey) ?: return false
        val signature = PartyCrypto.unhex(event.sig) ?: return false
        return Bip340.verify(expected, pubkey, signature)
    }

    fun serializeForId(pubkey: String, createdAt: Long, kind: Int, tags: List<List<String>>, content: String): String =
        buildString {
            append("[0,")
            appendEscaped(pubkey)
            append(',').append(createdAt).append(',').append(kind).append(",[")
            tags.forEachIndexed { i, tag ->
                if (i > 0) append(',')
                append('[')
                tag.forEachIndexed { j, value ->
                    if (j > 0) append(',')
                    appendEscaped(value)
                }
                append(']')
            }
            append("],")
            appendEscaped(content)
            append(']')
        }

    private fun StringBuilder.appendEscaped(value: String) {
        append('"')
        for (char in value) {
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> append(char)
            }
        }
        append('"')
    }

    fun eventMessage(event: NostrEvent): String = buildJsonArray {
        add(JsonPrimitive("EVENT"))
        add(toJson(event))
    }.toString()

    fun requestMessage(subscriptionId: String, kind: Int, roomTag: String, since: Long): String =
        requestMessage(subscriptionId, kind, listOf(roomTag), since)

    fun requestMessage(subscriptionId: String, kind: Int, tags: Collection<String>, since: Long): String = buildJsonArray {
        add(JsonPrimitive("REQ"))
        add(JsonPrimitive(subscriptionId))
        add(
            buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(kind)) })
                put("#$ROOM_TAG", buildJsonArray { tags.forEach { add(JsonPrimitive(it)) } })
                put("since", JsonPrimitive(since))
            }
        )
    }.toString()

    fun closeMessage(subscriptionId: String): String = buildJsonArray {
        add(JsonPrimitive("CLOSE"))
        add(JsonPrimitive(subscriptionId))
    }.toString()

    fun parseRelayMessage(text: String): RelayMessage? {
        val array = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonArray ?: return null
        fun string(index: Int): String? = (array.getOrNull(index) as? JsonPrimitive)?.contentOrNull
        return when (string(0)) {
            "EVENT" -> {
                val subscriptionId = string(1) ?: return null
                val event = (array.getOrNull(2) as? JsonObject)?.let(::fromJson) ?: return null
                RelayMessage.Event(subscriptionId, event)
            }
            "OK" -> RelayMessage.Ok(
                eventId = string(1) ?: return null,
                accepted = (array.getOrNull(2) as? JsonPrimitive)?.booleanOrNull ?: return null,
                message = string(3).orEmpty(),
            )
            "EOSE" -> RelayMessage.EndOfStored(string(1) ?: return null)
            "CLOSED" -> RelayMessage.Closed(string(1) ?: return null, string(2).orEmpty())
            "NOTICE" -> RelayMessage.Notice(string(1).orEmpty())
            else -> null
        }
    }

    private fun toJson(event: NostrEvent): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(event.id))
        put("pubkey", JsonPrimitive(event.pubkey))
        put("created_at", JsonPrimitive(event.createdAt))
        put("kind", JsonPrimitive(event.kind))
        put(
            "tags",
            buildJsonArray {
                event.tags.forEach { tag -> add(buildJsonArray { tag.forEach { add(JsonPrimitive(it)) } }) }
            }
        )
        put("content", JsonPrimitive(event.content))
        put("sig", JsonPrimitive(event.sig))
    }

    private fun fromJson(obj: JsonObject): NostrEvent? {
        fun string(key: String): String? = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val tags = (obj["tags"] as? JsonArray)?.map { tag: JsonElement ->
            (tag as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull ?: return null } ?: return null
        } ?: return null
        return NostrEvent(
            id = string("id") ?: return null,
            pubkey = string("pubkey") ?: return null,
            createdAt = (obj["created_at"] as? JsonPrimitive)?.longOrNull ?: return null,
            kind = (obj["kind"] as? JsonPrimitive)?.intOrNull ?: return null,
            tags = tags,
            content = string("content") ?: return null,
            sig = string("sig") ?: return null,
        )
    }
}
