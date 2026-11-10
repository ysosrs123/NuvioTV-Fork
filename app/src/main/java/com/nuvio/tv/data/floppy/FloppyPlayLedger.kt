package com.nuvio.tv.data.floppy

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** When this box told Floppy a film was finished, per profile and TMDB id, so an unmark can take back only those plays. */
class FloppyPlayLedger(private val persistence: FloppyAuthPersistence) {
    private val lock = Any()

    fun record(profileId: Int, tmdb: Long, playedAtMs: Long) = synchronized(lock) {
        val plays = read(profileId)
        val stamps = (plays[tmdb].orEmpty() + playedAtMs).distinct().takeLast(MAX_PER_TITLE)
        val kept = (plays - tmdb).entries.toList().takeLast(MAX_TITLES - 1).associate { it.key to it.value }
        write(profileId, kept + (tmdb to stamps))
    }

    fun stamps(profileId: Int, tmdb: Long): List<Long> = synchronized(lock) { read(profileId)[tmdb].orEmpty() }

    fun forget(profileId: Int, tmdb: Long) = synchronized(lock) { write(profileId, read(profileId) - tmdb) }

    private fun read(profileId: Int): Map<Long, List<Long>> {
        val text = persistence.read(profileId) ?: return emptyMap()
        return runCatching {
            Json.parseToJsonElement(text).jsonObject.entries.mapNotNull { (key, value) ->
                val id = key.toLongOrNull() ?: return@mapNotNull null
                id to value.jsonArray.mapNotNull { (it as? JsonPrimitive)?.longOrNull }
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    private fun write(profileId: Int, plays: Map<Long, List<Long>>) {
        if (plays.isEmpty()) {
            persistence.write(profileId, null)
            return
        }
        persistence.write(
            profileId,
            JsonObject(plays.entries.associate { (id, stamps) -> id.toString() to JsonArray(stamps.map(::JsonPrimitive)) }).toString()
        )
    }

    private companion object {
        const val MAX_TITLES = 500
        const val MAX_PER_TITLE = 10
    }
}

class AndroidFloppyPlayPersistence(context: Context) : FloppyAuthPersistence {
    private val preferences = context.getSharedPreferences("floppy_plays", Context.MODE_PRIVATE)

    override fun read(profileId: Int): String? = preferences.getString("profile.$profileId", null)

    override fun write(profileId: Int, value: String?) {
        preferences.edit().apply {
            if (value == null) remove("profile.$profileId") else putString("profile.$profileId", value)
        }.apply()
    }

    override fun clear() {
        preferences.edit().clear().apply()
    }
}
