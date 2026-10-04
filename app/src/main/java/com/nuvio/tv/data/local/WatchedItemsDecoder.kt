package com.nuvio.tv.data.local

import com.google.gson.Gson
import com.nuvio.tv.domain.model.WatchedItem
import com.nuvio.tv.domain.model.WatchedMutationKey
import com.nuvio.tv.domain.model.mutationKey
import java.util.Collections

/** Accessed under its owner's mutex. Null parses are cached along with valid items. */
internal class WatchedItemsDecoder(private val gson: Gson = Gson(), private val parse: (String) -> WatchedItem? = { raw ->
    runCatching { gson.fromJson(raw, WatchedItem::class.java) }.getOrNull()
}) {
    private var entries = linkedMapOf<String, WatchedItem?>()
    private var snapshot: List<WatchedItem> = emptyList()
    internal var decodeRuns = 0L; private set
    internal var parsedEntries = 0L; private set

    fun decode(raw: Set<String>): List<WatchedItem> {
        if (entries.keys == raw) return snapshot
        val next = LinkedHashMap<String, WatchedItem?>(raw.size)
        val result = ArrayList<WatchedItem>(raw.size)
        for (json in raw) {
            val item = if (entries.containsKey(json)) entries[json] else {
                parsedEntries++
                parse(json)
            }
            next[json] = item
            if (item != null) result.add(item)
        }
        entries = next
        snapshot = Collections.unmodifiableList(result)
        decodeRuns++
        return snapshot
    }

    fun key(raw: String): WatchedMutationKey? = entries[raw]?.mutationKey()

    fun clear() {
        entries.clear()
        snapshot = emptyList()
    }
}
