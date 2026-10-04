package com.nuvio.tv.core.party

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** What identifies the file a member is playing, strongest first: torrent hash, name and size, content hash. */
data class PartyFingerprint(
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val filename: String? = null,
    val sizeBytes: Long? = null,
    val videoHash: String? = null,
    val durationMs: Long? = null,
) {
    val isEmpty: Boolean
        get() = infoHash.isNullOrBlank() && filename.isNullOrBlank() && sizeBytes == null && videoHash.isNullOrBlank()
}

data class PartyMedia(
    val contentId: String,
    val contentType: String,
    val videoId: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val title: String = "",
    val episodeTitle: String? = null,
    val poster: String? = null,
    val backdrop: String? = null,
    val logo: String? = null,
    val fingerprint: PartyFingerprint = PartyFingerprint(),
    val streamName: String? = null,
    val addonName: String? = null,
    val sharedUrl: String? = null,
) {
    fun sameTitle(other: PartyMedia?): Boolean =
        other != null && contentId == other.contentId && videoId == other.videoId &&
            season == other.season && episode == other.episode

    fun sameFile(other: PartyMedia?): Boolean =
        sameTitle(other) && other != null && fingerprint.copy(durationMs = null) == other.fingerprint.copy(durationMs = null)
}

enum class PartyAction(val wire: String) {
    PLAY("play"), PAUSE("pause"), SEEK("seek");

    companion object {
        fun of(wire: String?): PartyAction? = entries.firstOrNull { it.wire == wire }
    }
}

enum class PartyHoldReason(val wire: String) {
    BUFFERING("buffering"), LOADING("loading");

    companion object {
        fun of(wire: String?): PartyHoldReason? = entries.firstOrNull { it.wire == wire }
    }
}

data class PartyHoldInfo(val reason: PartyHoldReason, val memberId: String?, val untilHostClock: Long)

sealed interface PartyMessage {
    data class Hello(
        val name: String,
        val host: Boolean,
        val term: Int,
        val speedOk: Boolean,
        val avatar: String? = null,
        val colour: String? = null,
        val friendKey: String? = null,
        val friendProof: String? = null,
    ) : PartyMessage

    data class Media(val term: Int, val epoch: Int, val media: PartyMedia) : PartyMessage

    data class State(
        val term: Int,
        val epoch: Int,
        val sentAt: Long,
        val hostClock: Long,
        val positionMs: Long,
        val playing: Boolean,
        val hold: PartyHoldInfo? = null,
        val roster: List<String> = emptyList(),
        val guestsControl: Boolean = true,
        val catchingUp: List<String> = emptyList(),
    ) : PartyMessage

    data class Command(val epoch: Int, val action: PartyAction, val positionMs: Long) : PartyMessage

    data class Status(
        val epoch: Int,
        val name: String,
        val positionMs: Long,
        val stalled: Boolean,
        val ready: Boolean,
        val speedOk: Boolean,
        val driftMs: Long? = null,
        val away: Boolean = false,
        val avatar: String? = null,
        val colour: String? = null,
    ) : PartyMessage

    data class Ping(val t0: Long) : PartyMessage

    data class Pong(val to: String, val t0: Long, val t1: Long) : PartyMessage

    data class End(val term: Int) : PartyMessage

    /** [proof] shows the sender holds [key], for [to] only; see [PartyInvites.proof]. */
    data class FriendRequest(
        val to: String,
        val key: String,
        val proof: String,
        val name: String,
        val avatar: String? = null,
        val colour: String? = null,
    ) : PartyMessage

    data class FriendAccept(
        val to: String,
        val key: String,
        val proof: String,
        val name: String,
        val avatar: String? = null,
        val colour: String? = null,
    ) : PartyMessage

    data object Bye : PartyMessage
}

object PartyCodec {
    const val VERSION = 1
    private const val MAX_NAME = 40
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(message: PartyMessage): String = buildJsonObject {
        put("v", JsonPrimitive(VERSION))
        when (message) {
            is PartyMessage.Hello -> {
                type("hello")
                put("name", JsonPrimitive(message.name.take(MAX_NAME)))
                put("host", JsonPrimitive(message.host))
                put("term", JsonPrimitive(message.term))
                put("speedOk", JsonPrimitive(message.speedOk))
                optional("avatar", message.avatar)
                optional("colour", message.colour)
                optional("fk", message.friendKey)
                optional("fkp", message.friendProof)
            }
            is PartyMessage.Media -> {
                type("media")
                put("term", JsonPrimitive(message.term))
                put("ep", JsonPrimitive(message.epoch))
                put("media", mediaJson(message.media))
            }
            is PartyMessage.State -> {
                type("state")
                put("term", JsonPrimitive(message.term))
                put("ep", JsonPrimitive(message.epoch))
                put("sent", JsonPrimitive(message.sentAt))
                put("hc", JsonPrimitive(message.hostClock))
                put("pos", JsonPrimitive(message.positionMs))
                put("playing", JsonPrimitive(message.playing))
                message.hold?.let { hold ->
                    put(
                        "hold",
                        buildJsonObject {
                            put("reason", JsonPrimitive(hold.reason.wire))
                            optional("who", hold.memberId)
                            put("until", JsonPrimitive(hold.untilHostClock))
                        }
                    )
                }
                put("roster", buildJsonArray { message.roster.forEach { add(JsonPrimitive(it)) } })
                put("guestsControl", JsonPrimitive(message.guestsControl))
                put("catchingUp", buildJsonArray { message.catchingUp.forEach { add(JsonPrimitive(it)) } })
            }
            is PartyMessage.Command -> {
                type("cmd")
                put("ep", JsonPrimitive(message.epoch))
                put("action", JsonPrimitive(message.action.wire))
                put("pos", JsonPrimitive(message.positionMs))
            }
            is PartyMessage.Status -> {
                type("status")
                put("ep", JsonPrimitive(message.epoch))
                put("name", JsonPrimitive(message.name.take(MAX_NAME)))
                put("pos", JsonPrimitive(message.positionMs))
                put("stalled", JsonPrimitive(message.stalled))
                put("ready", JsonPrimitive(message.ready))
                put("speedOk", JsonPrimitive(message.speedOk))
                message.driftMs?.let { put("drift", JsonPrimitive(it)) }
                if (message.away) put("away", JsonPrimitive(true))
                optional("avatar", message.avatar)
                optional("colour", message.colour)
            }
            is PartyMessage.Ping -> {
                type("ping")
                put("t0", JsonPrimitive(message.t0))
            }
            is PartyMessage.Pong -> {
                type("pong")
                put("to", JsonPrimitive(message.to))
                put("t0", JsonPrimitive(message.t0))
                put("t1", JsonPrimitive(message.t1))
            }
            is PartyMessage.End -> {
                type("end")
                put("term", JsonPrimitive(message.term))
            }
            PartyMessage.Bye -> type("bye")
            is PartyMessage.FriendRequest -> {
                type("friend")
                friendJson(message.to, message.key, message.proof, message.name, message.avatar, message.colour)
            }
            is PartyMessage.FriendAccept -> {
                type("friendOk")
                friendJson(message.to, message.key, message.proof, message.name, message.avatar, message.colour)
            }
        }
    }.toString()

    fun decode(text: String): PartyMessage? {
        val obj = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
        if (obj.int("v") != VERSION) return null
        return when (obj.string("t")) {
            "hello" -> PartyMessage.Hello(
                name = obj.string("name")?.take(MAX_NAME).orEmpty(),
                host = obj.boolean("host") ?: false,
                term = obj.int("term") ?: 0,
                speedOk = obj.boolean("speedOk") ?: true,
                avatar = PartyInvites.cleanAvatar(obj.string("avatar")),
                colour = PartyInvites.cleanColour(obj.string("colour")),
                friendKey = obj.string("fk")?.takeIf(PartyInvites::isKey),
                friendProof = obj.string("fkp"),
            )
            "media" -> PartyMessage.Media(
                term = obj.int("term") ?: return null,
                epoch = obj.int("ep") ?: return null,
                media = (obj["media"] as? JsonObject)?.let(::mediaFrom) ?: return null,
            )
            "state" -> PartyMessage.State(
                term = obj.int("term") ?: return null,
                epoch = obj.int("ep") ?: return null,
                sentAt = obj.long("sent") ?: return null,
                hostClock = obj.long("hc") ?: return null,
                positionMs = obj.long("pos") ?: return null,
                playing = obj.boolean("playing") ?: return null,
                hold = (obj["hold"] as? JsonObject)?.let { hold ->
                    PartyHoldInfo(
                        reason = PartyHoldReason.of(hold.string("reason")) ?: return null,
                        memberId = hold.string("who"),
                        untilHostClock = hold.long("until") ?: return null,
                    )
                },
                roster = obj.strings("roster"),
                guestsControl = obj.boolean("guestsControl") ?: true,
                catchingUp = obj.strings("catchingUp"),
            )
            "cmd" -> PartyMessage.Command(
                epoch = obj.int("ep") ?: return null,
                action = PartyAction.of(obj.string("action")) ?: return null,
                positionMs = obj.long("pos") ?: return null,
            )
            "status" -> PartyMessage.Status(
                epoch = obj.int("ep") ?: return null,
                name = obj.string("name")?.take(MAX_NAME).orEmpty(),
                positionMs = obj.long("pos") ?: 0L,
                stalled = obj.boolean("stalled") ?: false,
                ready = obj.boolean("ready") ?: false,
                speedOk = obj.boolean("speedOk") ?: true,
                driftMs = obj.long("drift"),
                away = obj.boolean("away") ?: false,
                avatar = PartyInvites.cleanAvatar(obj.string("avatar")),
                colour = PartyInvites.cleanColour(obj.string("colour")),
            )
            "ping" -> PartyMessage.Ping(obj.long("t0") ?: return null)
            "pong" -> PartyMessage.Pong(
                to = obj.string("to") ?: return null,
                t0 = obj.long("t0") ?: return null,
                t1 = obj.long("t1") ?: return null,
            )
            "end" -> PartyMessage.End(obj.int("term") ?: 0)
            "bye" -> PartyMessage.Bye
            "friend" -> PartyMessage.FriendRequest(
                to = obj.string("to") ?: return null,
                key = obj.string("key")?.takeIf(PartyInvites::isKey) ?: return null,
                proof = obj.string("proof") ?: return null,
                name = obj.string("name")?.take(MAX_NAME).orEmpty(),
                avatar = PartyInvites.cleanAvatar(obj.string("avatar")),
                colour = PartyInvites.cleanColour(obj.string("colour")),
            )
            "friendOk" -> PartyMessage.FriendAccept(
                to = obj.string("to") ?: return null,
                key = obj.string("key")?.takeIf(PartyInvites::isKey) ?: return null,
                proof = obj.string("proof") ?: return null,
                name = obj.string("name")?.take(MAX_NAME).orEmpty(),
                avatar = PartyInvites.cleanAvatar(obj.string("avatar")),
                colour = PartyInvites.cleanColour(obj.string("colour")),
            )
            else -> null
        }
    }

    private fun JsonObjectBuilder.type(value: String) {
        put("t", JsonPrimitive(value))
    }

    private fun JsonObjectBuilder.friendJson(to: String, key: String, proof: String, name: String, avatar: String?, colour: String?) {
        put("to", JsonPrimitive(to))
        put("key", JsonPrimitive(key))
        put("proof", JsonPrimitive(proof))
        put("name", JsonPrimitive(name.take(MAX_NAME)))
        optional("avatar", avatar)
        optional("colour", colour)
    }

    private fun JsonObjectBuilder.optional(key: String, value: String?) {
        if (!value.isNullOrBlank()) put(key, JsonPrimitive(value))
    }

    private fun mediaJson(media: PartyMedia): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(media.contentId))
        put("type", JsonPrimitive(media.contentType))
        optional("video", media.videoId)
        media.season?.let { put("season", JsonPrimitive(it)) }
        media.episode?.let { put("episode", JsonPrimitive(it)) }
        put("title", JsonPrimitive(media.title))
        optional("episodeTitle", media.episodeTitle)
        optional("poster", media.poster)
        optional("backdrop", media.backdrop)
        optional("logo", media.logo)
        optional("stream", media.streamName)
        optional("addon", media.addonName)
        optional("url", media.sharedUrl)
        val print = media.fingerprint
        optional("hash", print.infoHash?.lowercase())
        print.fileIdx?.let { put("fileIdx", JsonPrimitive(it)) }
        optional("file", print.filename)
        print.sizeBytes?.let { put("size", JsonPrimitive(it)) }
        optional("videoHash", print.videoHash?.lowercase())
        print.durationMs?.let { put("duration", JsonPrimitive(it)) }
    }

    /** What another member says is playing. Links and artwork are checked here too: a member may not be a stock app. */
    private fun mediaFrom(obj: JsonObject): PartyMedia? = PartyMedia(
        contentId = obj.text("id", ID_MAX) ?: return null,
        contentType = obj.text("type", TYPE_MAX) ?: return null,
        videoId = obj.text("video", ID_MAX),
        season = obj.int("season"),
        episode = obj.int("episode"),
        title = obj.text("title", TEXT_MAX).orEmpty(),
        episodeTitle = obj.text("episodeTitle", TEXT_MAX),
        poster = PartyInvites.cleanImage(obj.string("poster")),
        backdrop = PartyInvites.cleanImage(obj.string("backdrop")),
        logo = PartyInvites.cleanImage(obj.string("logo")),
        fingerprint = PartyFingerprint(
            infoHash = obj.text("hash", ID_MAX),
            fileIdx = obj.int("fileIdx"),
            filename = obj.text("file", TEXT_MAX),
            sizeBytes = obj.long("size"),
            videoHash = obj.text("videoHash", ID_MAX),
            durationMs = obj.long("duration"),
        ),
        streamName = obj.text("stream", TEXT_MAX),
        addonName = obj.text("addon", TEXT_MAX),
        sharedUrl = obj.string("url")?.takeIf(PartyLinkPolicy::isShareable),
    )

    private fun JsonObject.text(key: String, max: Int): String? = string(key)?.take(max)

    private fun JsonObject.primitive(key: String): JsonPrimitive? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }

    private fun JsonObject.string(key: String): String? = primitive(key)?.contentOrNull

    private fun JsonObject.int(key: String): Int? = primitive(key)?.intOrNull

    private fun JsonObject.long(key: String): Long? = primitive(key)?.longOrNull

    private fun JsonObject.boolean(key: String): Boolean? = primitive(key)?.booleanOrNull

    private fun JsonObject.strings(key: String): List<String> =
        (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
}

private const val ID_MAX = 200
private const val TYPE_MAX = 32
private const val TEXT_MAX = 300
