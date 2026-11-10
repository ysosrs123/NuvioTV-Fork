package com.nuvio.tv.data.mediaserver

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Which server item a catalog id came from, for titles a server row has shown. Saved so it outlives the app run. */
@Singleton
class ServerOrigins internal constructor(
    private val read: () -> String?,
    private val write: (String) -> Unit
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        read = { context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null) },
        write = { context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, it).apply() }
    )

    private val lock = Any()
    private var loaded = false
    private val items = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean = size > MAX_ENTRIES
    }

    fun remember(connectionId: String, type: String, titles: List<Pair<String, List<String>>>) = synchronized(lock) {
        load()
        var changed = false
        titles.forEach { (itemId, catalogIds) ->
            catalogIds.forEach { id -> if (items.put(key(connectionId, type, id), itemId) != itemId) changed = true }
        }
        if (changed) save()
    }

    fun itemId(connectionId: String, type: String, catalogIds: List<String>): String? = synchronized(lock) {
        load()
        catalogIds.firstNotNullOfOrNull { items[key(connectionId, type, it)] }
    }

    /** True when something was forgotten, so a caller can look the title up again. */
    fun forget(connectionId: String, type: String, catalogIds: List<String>): Boolean = synchronized(lock) {
        load()
        val removed = catalogIds.mapNotNull { items.remove(key(connectionId, type, it)) }.toSet()
        if (removed.isEmpty()) return false
        items.values.removeAll { it in removed }
        save()
        true
    }

    private fun key(connectionId: String, type: String, catalogId: String) = "$connectionId|$type|$catalogId"

    private fun load() {
        if (loaded) return
        loaded = true
        val text = read() ?: return
        runCatching {
            Json.parseToJsonElement(text).jsonObject.forEach { (key, value) ->
                value.jsonPrimitive.contentOrNull?.let { items[key] = it }
            }
        }
    }

    private fun save() {
        write(JsonObject(items.mapValues { JsonPrimitive(it.value) }).toString())
    }

    internal companion object {
        private const val FILE = "server_origins"
        private const val KEY = "origins"
        private const val MAX_ENTRIES = 5_000

        fun inMemory(): ServerOrigins {
            var stored: String? = null
            return ServerOrigins({ stored }, { stored = it })
        }
    }
}
