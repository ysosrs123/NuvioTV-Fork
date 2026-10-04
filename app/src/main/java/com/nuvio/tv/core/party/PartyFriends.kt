package com.nuvio.tv.core.party

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** What a member shows of the profile in use: the avatar picture and its colour. */
data class PartyProfile(
    val avatarUrl: String? = null,
    val colour: String? = null,
)

data class PartyFriend(
    val key: String,
    val name: String,
    val avatarUrl: String? = null,
    val colour: String? = null,
    val addedAt: Long,
    val lastTogetherAt: Long = addedAt,
    val lastTitle: String? = null,
)

/** Someone in the room asked to add this device as a friend. */
data class PartyFriendRequest(
    val memberId: String,
    val key: String,
    val name: String,
    val avatarUrl: String?,
    val colour: String?,
)

data class PartyInvite(
    /** Same for every copy of one invite, however it was wrapped. */
    val id: String,
    val from: String,
    val name: String,
    val host: String,
    val code: String,
    val title: String?,
    val poster: String?,
    val watching: Int,
    val avatarUrl: String?,
    val colour: String?,
    val sentAt: Long,
    /** The sender took this invite back; [code] says which party. */
    val cancelled: Boolean = false,
)

object PartyFriendCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(friends: List<PartyFriend>): String = buildJsonArray {
        friends.forEach { friend ->
            add(
                buildJsonObject {
                    put("key", JsonPrimitive(friend.key))
                    put("name", JsonPrimitive(friend.name))
                    optional("avatar", friend.avatarUrl)
                    optional("colour", friend.colour)
                    put("added", JsonPrimitive(friend.addedAt))
                    put("together", JsonPrimitive(friend.lastTogetherAt))
                    optional("title", friend.lastTitle)
                }
            )
        }
    }.toString()

    fun decode(text: String?): List<PartyFriend> {
        if (text.isNullOrBlank()) return emptyList()
        val array = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val key = obj.string("key")?.takeIf(PartyInvites::isKey) ?: return@mapNotNull null
            val added = obj.long("added") ?: return@mapNotNull null
            PartyFriend(
                key = key,
                name = obj.string("name").orEmpty(),
                avatarUrl = PartyInvites.cleanAvatar(obj.string("avatar")),
                colour = obj.string("colour"),
                addedAt = added,
                lastTogetherAt = obj.long("together") ?: added,
                lastTitle = obj.string("title"),
            )
        }.distinctBy { it.key }
    }

    /** Most recent first: the last party together, or when the friend was added. */
    fun sorted(friends: List<PartyFriend>): List<PartyFriend> =
        friends.sortedWith(compareByDescending<PartyFriend> { it.lastTogetherAt }.thenByDescending { it.addedAt })
}

/**
 * Invites travel as stored events that wait on the relays for [LIFETIME_SECONDS]. A relay sees an opaque inbox tag
 * and a throwaway signer. The outer layer opens only for the recipient and names the sender; the inner one opens only
 * with the key the two friends share, which shows it really came from that friend.
 */
object PartyInvites {
    const val KIND = 4711
    const val LIFETIME_SECONDS = 7_200L
    private const val CLOCK_SLACK_SECONDS = 600L
    private const val INBOX_PREFIX = "nuvio-party-inbox:"
    private const val PROOF_PREFIX = "nuvio-party-friend:"
    private const val KEY_SALT = "nuvio-party-invite-v1"
    private const val MAX_TEXT = 200
    private val json = Json { ignoreUnknownKeys = true }

    fun isKey(value: String): Boolean = value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

    /** Pictures from other devices are only loaded over https. */
    fun cleanImage(url: String?): String? = url?.takeIf { it.startsWith("https://") && it.length <= 500 }

    fun cleanColour(colour: String?): String? = colour?.takeIf { COLOUR.matches(it) }

    /** An avatar from another device: a picture of the built-in set, or an https picture. */
    fun cleanAvatar(ref: String?): String? = if (PartyAvatars.isBundled(ref)) ref else cleanImage(ref)

    private val COLOUR = Regex("#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?")

    fun inboxTag(friendKey: String): String = PartyCrypto.hex(PartyCrypto.sha256((INBOX_PREFIX + friendKey).toByteArray(Charsets.UTF_8)))

    /** Everyone in the room, for a key shown in Hello. */
    const val ANYONE = "*"

    /**
     * Shows that the room member [fromId] holds [identity]'s key, for the member [toId] (or [ANYONE]).
     * Bound to both ids, so it cannot be replayed by another member or towards someone else.
     */
    fun proof(identity: PartyIdentity, fromId: String, toId: String, random: SecureRandom): String =
        PartyCrypto.hex(
            Bip340.sign(proofMessage(fromId, toId, identity.publicKeyHex), identity.secretKey, ByteArray(32).also(random::nextBytes))
        )

    fun checkProof(key: String, fromId: String, toId: String, proof: String): Boolean {
        if (!isKey(key)) return false
        val signature = PartyCrypto.unhex(proof) ?: return false
        val publicKey = PartyCrypto.unhex(key) ?: return false
        return runCatching { Bip340.verify(proofMessage(fromId, toId, key), publicKey, signature) }.getOrDefault(false)
    }

    private fun proofMessage(fromId: String, toId: String, key: String): ByteArray =
        PartyCrypto.sha256((PROOF_PREFIX + fromId + ":" + toId + ":" + key).toByteArray(Charsets.UTF_8))

    fun sharedKey(identity: PartyIdentity, otherKey: String): ByteArray? {
        val other = PartyCrypto.unhex(otherKey)?.takeIf { it.size == 32 } ?: return null
        val shared = Bip340.sharedX(identity.secretKey, other) ?: return null
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(KEY_SALT.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val prk = mac.doFinal(shared)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        return mac.doFinal(byteArrayOf(1))
    }

    internal fun seal(
        sender: PartyIdentity,
        recipientKey: String,
        invite: PartyInvite,
        nowSeconds: Long,
        random: SecureRandom = SecureRandom(),
    ): NostrEvent? {
        val key = sharedKey(sender, recipientKey) ?: return null
        val inner = buildJsonObject {
            put("t", JsonPrimitive("invite"))
            put("to", JsonPrimitive(recipientKey))
            put("name", JsonPrimitive(invite.name.take(MAX_TEXT)))
            put("host", JsonPrimitive(invite.host.take(MAX_TEXT)))
            put("code", JsonPrimitive(invite.code))
            optional("title", invite.title?.take(MAX_TEXT))
            optional("poster", invite.poster)
            put("watching", JsonPrimitive(invite.watching))
            optional("avatar", invite.avatarUrl)
            optional("colour", invite.colour)
            put("sent", JsonPrimitive(nowSeconds))
        }.toString()
        return wrap(sender, recipientKey, key, inner, nowSeconds, random)
    }

    /** Takes back the invites to the party [code] sent to [recipientKey]. */
    internal fun sealCancel(
        sender: PartyIdentity,
        recipientKey: String,
        code: String,
        nowSeconds: Long,
        random: SecureRandom = SecureRandom(),
    ): NostrEvent? {
        val key = sharedKey(sender, recipientKey) ?: return null
        val inner = buildJsonObject {
            put("t", JsonPrimitive("cancel"))
            put("to", JsonPrimitive(recipientKey))
            put("code", JsonPrimitive(code))
            put("sent", JsonPrimitive(nowSeconds))
        }.toString()
        return wrap(sender, recipientKey, key, inner, nowSeconds, random)
    }

    private fun wrap(
        sender: PartyIdentity,
        recipientKey: String,
        key: ByteArray,
        inner: String,
        nowSeconds: Long,
        random: SecureRandom,
    ): NostrEvent? {
        val wrapper = PartyIdentity.random(random)
        val outerKey = sharedKey(wrapper, recipientKey) ?: return null
        val envelope = buildJsonObject {
            put("from", JsonPrimitive(sender.publicKeyHex))
            put("box", JsonPrimitive(PartyCrypto.seal(key, inner, random)))
        }.toString()
        return Nostr.sign(
            identity = wrapper,
            createdAt = nowSeconds,
            kind = KIND,
            tags = listOf(
                listOf(Nostr.ROOM_TAG, inboxTag(recipientKey)),
                listOf("expiration", (nowSeconds + LIFETIME_SECONDS).toString()),
            ),
            content = PartyCrypto.seal(outerKey, envelope, random),
            auxRand = ByteArray(32).also(random::nextBytes),
        )
    }

    /** The invite (or its cancellation) in [event] when it is for [me], from a friend, unexpired and well formed. */
    internal fun open(event: NostrEvent, me: PartyIdentity, isFriend: (String) -> Boolean, nowSeconds: Long): PartyInvite? {
        if (event.kind != KIND) return null
        val tag = inboxTag(me.publicKeyHex)
        if (event.tags.none { it.size >= 2 && it[0] == Nostr.ROOM_TAG && it[1] == tag }) return null
        val outerKey = sharedKey(me, event.pubkey) ?: return null
        val opened = PartyCrypto.open(outerKey, event.content) ?: return null
        val envelope = runCatching { json.parseToJsonElement(opened) }.getOrNull() as? JsonObject ?: return null
        val from = envelope.string("from")?.takeIf(::isKey) ?: return null
        if (!isFriend(from)) return null
        val key = sharedKey(me, from) ?: return null
        val box = envelope.string("box") ?: return null
        val inner = PartyCrypto.open(key, box) ?: return null
        val obj = runCatching { json.parseToJsonElement(inner) }.getOrNull() as? JsonObject ?: return null
        val type = obj.string("t")
        if ((type != "invite" && type != "cancel") || obj.string("to") != me.publicKeyHex) return null
        val sent = obj.long("sent") ?: return null
        if (sent > nowSeconds + CLOCK_SLACK_SECONDS || nowSeconds - sent > LIFETIME_SECONDS + CLOCK_SLACK_SECONDS) return null
        val code = PartyCode.normalize(obj.string("code") ?: return null) ?: return null
        if (!Nostr.verify(event)) return null
        val name = obj.string("name").orEmpty().take(MAX_TEXT)
        return PartyInvite(
            id = PartyCrypto.hex(PartyCrypto.sha256(box.toByteArray(Charsets.UTF_8))),
            from = from,
            name = name,
            host = obj.string("host")?.take(MAX_TEXT)?.ifBlank { null } ?: name,
            code = code,
            title = obj.string("title")?.take(MAX_TEXT),
            poster = cleanImage(obj.string("poster")),
            watching = (obj.int("watching") ?: 1).coerceIn(1, 99),
            avatarUrl = cleanAvatar(obj.string("avatar")),
            colour = cleanColour(obj.string("colour")),
            sentAt = sent,
            cancelled = type == "cancel",
        )
    }
}

private fun JsonObjectBuilder.optional(key: String, value: String?) {
    if (!value.isNullOrBlank()) put(key, JsonPrimitive(value))
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
