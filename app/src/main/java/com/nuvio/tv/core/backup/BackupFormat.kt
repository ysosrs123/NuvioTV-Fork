package com.nuvio.tv.core.backup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** What a restore can bring back, each chosen separately. */
enum class BackupPart { PROFILES, SETTINGS, SECRETS, DEVICE, HISTORY }

enum class BackupStoreKind {
    /** A settings file per profile: `name` for profile 1, `name_pN` for the others. */
    PROFILE_STORE,
    /** One settings file for the whole box. */
    BOX_STORE,
    /** Classic shared preferences. */
    PREFS,
    /** Sign-ins kept by [com.nuvio.tv.core.profile.ProtectedProfilePreferences], one value per profile. */
    PROTECTED,
    /** Files in the app's own folder. */
    FILE,
    /** A [BackupSection]'s own text. */
    SECTION
}

/** A stored value with its type, so a restore writes back exactly what was read. */
sealed interface BackupValue {
    data class Text(val value: String) : BackupValue
    data class Flag(val value: Boolean) : BackupValue
    data class Whole(val value: Int) : BackupValue
    data class Big(val value: Long) : BackupValue
    data class Real(val value: Float) : BackupValue
    data class Precise(val value: Double) : BackupValue
    data class Texts(val value: Set<String>) : BackupValue

    companion object {
        fun of(raw: Any?): BackupValue? = when (raw) {
            is String -> Text(raw)
            is Boolean -> Flag(raw)
            is Int -> Whole(raw)
            is Long -> Big(raw)
            is Float -> Real(raw)
            is Double -> Precise(raw)
            is Set<*> -> if (raw.all { it is String }) Texts(raw.map { it as String }.toSet()) else null
            else -> null
        }
    }
}

data class BackupEntry(
    val kind: BackupStoreKind,
    val store: String,
    val profileId: Int? = null,
    val values: Map<String, BackupValue> = emptyMap(),
    /** PROTECTED: the sign-in as its owner saved it. FILE: the file's text. */
    val text: String? = null
)

data class BackupDevice(val manufacturer: String, val model: String, val sdk: Int)

data class BackupHeader(
    val createdAtMs: Long,
    val appVersion: String,
    val device: BackupDevice,
    val profileNames: List<String>,
    val parts: Set<BackupPart>,
    /** Each [BackupSection] in the file, and whether its passwords are in the sealed part. */
    val sections: Map<String, Boolean> = emptyMap()
)

/** The file as written: everything apart from the passwords and keys, which are only kept sealed. */
data class BackupDocument(
    val header: BackupHeader,
    val entries: Map<BackupPart, List<BackupEntry>>,
    val sealedSecrets: SealedBox? = null
)

data class SealedBox(
    val kdf: String,
    val iterations: Int,
    val salt: String,
    val iv: String,
    val data: String
)

class BackupFormatException(message: String) : Exception(message)

object BackupFormat {
    const val FORMAT = "arc-backup"
    const val VERSION = 1

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(document: BackupDocument): String = buildJsonObject {
        put("format", FORMAT)
        put("version", VERSION)
        put("header", encodeHeader(document.header))
        put("parts", buildJsonObject {
            document.entries.forEach { (part, entries) ->
                if (part != BackupPart.SECRETS) put(part.name.lowercase(), encodeEntries(entries))
            }
        })
        document.sealedSecrets?.let { box ->
            put("secrets", buildJsonObject {
                put("kdf", box.kdf)
                put("iterations", box.iterations)
                put("salt", box.salt)
                put("iv", box.iv)
                put("data", box.data)
            })
        }
    }.toString()

    fun decode(text: String): BackupDocument {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: throw BackupFormatException("not json")
        if (root.string("format") != FORMAT) throw BackupFormatException("not an Arc backup")
        val version = (root["version"] as? JsonPrimitive)?.intOrNull ?: throw BackupFormatException("no version")
        if (version > VERSION) throw BackupFormatException("newer version")
        val header = decodeHeader(root["header"] as? JsonObject ?: throw BackupFormatException("no header"))
        val parts = (root["parts"] as? JsonObject).orEmpty().mapNotNull { (name, value) ->
            val part = BackupPart.entries.firstOrNull { it.name.lowercase() == name } ?: return@mapNotNull null
            if (part == BackupPart.SECRETS) return@mapNotNull null
            part to decodeEntries(value)
        }.toMap()
        val sealed = (root["secrets"] as? JsonObject)?.let { box ->
            SealedBox(
                kdf = box.string("kdf") ?: throw BackupFormatException("secrets"),
                iterations = ((box["iterations"] as? JsonPrimitive)?.longOrNull ?: throw BackupFormatException("secrets"))
                    .coerceIn(1L, BackupCrypto.MAX_ITERATIONS.toLong()).toInt(),
                salt = box.string("salt") ?: throw BackupFormatException("secrets"),
                iv = box.string("iv") ?: throw BackupFormatException("secrets"),
                data = box.string("data") ?: throw BackupFormatException("secrets")
            )
        }
        return BackupDocument(header, parts, sealed)
    }

    fun encodeEntries(entries: List<BackupEntry>): JsonArray = buildJsonArray {
        entries.forEach { entry ->
            add(buildJsonObject {
                put("kind", entry.kind.name.lowercase())
                put("store", entry.store)
                entry.profileId?.let { put("profile", it) }
                entry.text?.let { put("text", it) }
                if (entry.values.isNotEmpty()) {
                    put("values", buildJsonObject {
                        entry.values.toSortedMap().forEach { (key, value) -> put(key, encodeValue(value)) }
                    })
                }
            })
        }
    }

    fun decodeEntries(element: JsonElement?): List<BackupEntry> {
        val array = element as? JsonArray ?: return emptyList()
        return array.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val kind = BackupStoreKind.entries.firstOrNull { it.name.lowercase() == obj.string("kind") }
                ?: return@mapNotNull null
            val store = obj.string("store") ?: return@mapNotNull null
            BackupEntry(
                kind = kind,
                store = store,
                profileId = (obj["profile"] as? JsonPrimitive)?.intOrNull,
                values = (obj["values"] as? JsonObject).orEmpty().mapNotNull { (key, raw) ->
                    decodeValue(raw)?.let { key to it }
                }.toMap(),
                text = obj.string("text")
            )
        }
    }

    private fun encodeHeader(header: BackupHeader) = buildJsonObject {
        put("createdAt", header.createdAtMs)
        put("app", header.appVersion)
        put("device", buildJsonObject {
            put("manufacturer", header.device.manufacturer)
            put("model", header.device.model)
            put("sdk", header.device.sdk)
        })
        put("profiles", buildJsonArray { header.profileNames.forEach { add(JsonPrimitive(it)) } })
        put("contains", buildJsonArray { header.parts.forEach { add(JsonPrimitive(it.name.lowercase())) } })
        if (header.sections.isNotEmpty()) {
            put("sections", buildJsonObject { header.sections.forEach { (id, secrets) -> put(id, secrets) } })
        }
    }

    private fun decodeHeader(obj: JsonObject): BackupHeader {
        val device = obj["device"] as? JsonObject
        return BackupHeader(
            createdAtMs = (obj["createdAt"] as? JsonPrimitive)?.longOrNull ?: 0L,
            appVersion = obj.string("app").orEmpty(),
            device = BackupDevice(
                manufacturer = device?.string("manufacturer").orEmpty(),
                model = device?.string("model").orEmpty(),
                sdk = (device?.get("sdk") as? JsonPrimitive)?.intOrNull ?: 0
            ),
            profileNames = (obj["profiles"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
            parts = (obj["contains"] as? JsonArray)?.mapNotNull { element ->
                val name = (element as? JsonPrimitive)?.contentOrNull
                BackupPart.entries.firstOrNull { it.name.lowercase() == name }
            }.orEmpty().toSet(),
            sections = (obj["sections"] as? JsonObject).orEmpty().mapValues { (_, value) ->
                (value as? JsonPrimitive)?.booleanOrNull == true
            }
        )
    }

    private fun encodeValue(value: BackupValue): JsonObject = buildJsonObject {
        when (value) {
            is BackupValue.Text -> { put("t", "s"); put("v", value.value) }
            is BackupValue.Flag -> { put("t", "b"); put("v", value.value) }
            is BackupValue.Whole -> { put("t", "i"); put("v", value.value) }
            is BackupValue.Big -> { put("t", "l"); put("v", value.value) }
            is BackupValue.Real -> { put("t", "f"); put("v", value.value) }
            is BackupValue.Precise -> { put("t", "d"); put("v", value.value) }
            is BackupValue.Texts -> {
                put("t", "set")
                put("v", buildJsonArray { value.value.sorted().forEach { add(JsonPrimitive(it)) } })
            }
        }
    }

    private fun decodeValue(raw: JsonElement): BackupValue? {
        val obj = raw as? JsonObject ?: return null
        val v = obj["v"] ?: return null
        return when (obj.string("t")) {
            "s" -> v.jsonPrimitive.contentOrNull?.let(BackupValue::Text)
            "b" -> v.jsonPrimitive.booleanOrNull?.let(BackupValue::Flag)
            "i" -> v.jsonPrimitive.intOrNull?.let(BackupValue::Whole)
            "l" -> v.jsonPrimitive.longOrNull?.let(BackupValue::Big)
            "f" -> v.jsonPrimitive.doubleOrNull?.let { BackupValue.Real(it.toFloat()) }
            "d" -> v.jsonPrimitive.doubleOrNull?.let(BackupValue::Precise)
            "set" -> runCatching { v.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }.toSet() }
                .getOrNull()?.let(BackupValue::Texts)
            else -> null
        }
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
