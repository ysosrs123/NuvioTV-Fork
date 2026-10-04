package com.nuvio.tv.data.local

import com.google.gson.Gson
import com.nuvio.tv.domain.model.WatchedItem
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class WatchedItemsDecoderTest {
    private val gson = Gson()
    private fun item(id: Int, timestamp: Long = id.toLong()) = WatchedItem("id$id", "movie", "fixture", watchedAt = timestamp)
    private fun raw(id: Int, timestamp: Long = id.toLong()) = gson.toJson(item(id, timestamp))

    @Test fun `5431 baseline only parses new or replaced entries and retains immutable snapshots`() {
        val calls = AtomicInteger()
        val decoder = WatchedItemsDecoder { json ->
            calls.incrementAndGet()
            runCatching { gson.fromJson(json, WatchedItem::class.java) }.getOrNull()
        }
        val initial = (0 until 5431).mapTo(linkedSetOf()) { raw(it) }
        val before = decoder.decode(initial)
        assertEquals(5431, calls.get())
        assertSame(before, decoder.decode(initial.toSet()))
        assertEquals(5431, calls.get())
        val after = decoder.decode(initial + raw(5431))
        assertEquals(5432, calls.get())
        assertSame(before[0], after[0])
        assertEquals(5431, before.size)
        val replaced = decoder.decode((initial - raw(0)) + raw(0, 9999))
        assertEquals(5433, calls.get())
        assertEquals(9999L, replaced.last().watchedAt)
        decoder.decode(initial - raw(1))
        assertEquals(5434, calls.get()) // id0 original was absent from the previous revision
        assertEquals(4L, decoder.decodeRuns)
        assertThrows(UnsupportedOperationException::class.java) { (after as MutableList<*>).clear() }
    }

    @Test fun `malformed and null records are cached until removed and order is retained`() {
        val calls = AtomicInteger()
        val decoder = WatchedItemsDecoder { json ->
            calls.incrementAndGet()
            runCatching { gson.fromJson(json, WatchedItem::class.java) }.getOrNull()
        }
        val raw = linkedSetOf(raw(3), "{broken", "null", raw(1))
        assertEquals(listOf(item(3), item(1)), decoder.decode(raw))
        decoder.decode(raw + raw(2))
        assertEquals(5, calls.get())
        decoder.decode(raw - "{broken")
        decoder.decode(raw)
        assertEquals(6, calls.get())
        decoder.clear()
        assertEquals(emptyList<WatchedItem>(), decoder.decode(emptySet()))
        decoder.decode(raw)
        assertEquals(10, calls.get())
    }
}
