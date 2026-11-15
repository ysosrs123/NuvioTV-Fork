package com.nuvio.tv.core.profile

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinAskStoreTest {

    private fun store(saved: Map<String, String> = emptyMap()): Pair<PinAskStore, MutableMap<String, Any?>> {
        val stored = saved.toMutableMap<String, Any?>()
        val editor = mockk<SharedPreferences.Editor>()
        val key = slot<String>()
        val value = slot<String>()
        every { editor.putString(capture(key), capture(value)) } answers { stored[key.captured] = value.captured; editor }
        every { editor.remove(capture(key)) } answers { stored.remove(key.captured); editor }
        every { editor.clear() } answers { stored.clear(); editor }
        every { editor.apply() } returns Unit
        val prefs = mockk<SharedPreferences> {
            every { all } answers { stored }
            every { edit() } returns editor
        }
        val context = mockk<Context> { every { getSharedPreferences("arc_pin_ask", any()) } returns prefs }
        return PinAskStore(context) to stored
    }

    @Test
    fun `a choice is saved, read back and forgotten`() {
        val (store, stored) = store()
        assertEquals(PinAsk.EVERY_TIME, store.askFor(1))
        store.set(1, PinAsk.LIMITS_ONLY)
        assertEquals(PinAsk.LIMITS_ONLY, store.askFor(1))
        assertEquals("limits", stored["p1"])
        store.forgetProfile(1)
        assertEquals(PinAsk.EVERY_TIME, store.askFor(1))
        assertTrue(stored.isEmpty())
    }

    @Test
    fun `saved choices load at start and unknown codes mean Every time`() {
        val (store, _) = store(mapOf("p2" to "not_start", "p3" to "something-new", "junk" to "limits"))
        assertEquals(PinAsk.NOT_AT_START, store.askFor(2))
        assertEquals(PinAsk.EVERY_TIME, store.askFor(3))
        assertEquals(setOf(2, 3), store.asks.value.keys)
    }

    @Test
    fun `sign out forgets every choice`() {
        val (store, stored) = store(mapOf("p2" to "not_start"))
        store.forgetAll()
        assertTrue(store.asks.value.isEmpty())
        assertTrue(stored.isEmpty())
    }

    @Test
    fun `the same flow is handed out each time`() {
        val (store, _) = store()
        assertTrue(store.asks === store.asks)
    }
}
