package com.nuvio.tv.core.iptv

import org.junit.Assert.*
import org.junit.Test

class ChannelOrderTest {
    private val s = ChannelOrder.SCALE
    private val list = listOf(OrderedChannel("a", 0), OrderedChannel("b", s), OrderedChannel("c", 2 * s), OrderedChannel("d", 3 * s))
    private fun apply(channels: List<OrderedChannel>, writes: Map<String, Long>) =
        channels.map { it.copy(key = writes[it.id] ?: it.key) }.sortedWith(compareBy({ it.key }, { it.id }))

    @Test fun movesOnePlaceWithASingleWrite() {
        val up = ChannelOrder.plan(list, "c", ListMove.UP)
        assertEquals(setOf("c"), up.keys)
        assertEquals(listOf("a", "c", "b", "d"), apply(list, up).map { it.id })
        val down = ChannelOrder.plan(list, "b", ListMove.DOWN)
        assertEquals(listOf("a", "c", "b", "d"), apply(list, down).map { it.id })
    }

    @Test fun movesToTopAndBottom() {
        assertEquals(listOf("d", "a", "b", "c"), apply(list, ChannelOrder.plan(list, "d", ListMove.TOP)).map { it.id })
        assertEquals(listOf("b", "c", "d", "a"), apply(list, ChannelOrder.plan(list, "a", ListMove.BOTTOM)).map { it.id })
    }

    @Test fun noMoveAtTheEdgesOrForUnknownChannels() {
        assertTrue(ChannelOrder.plan(list, "a", ListMove.UP).isEmpty())
        assertTrue(ChannelOrder.plan(list, "a", ListMove.TOP).isEmpty())
        assertTrue(ChannelOrder.plan(list, "d", ListMove.DOWN).isEmpty())
        assertTrue(ChannelOrder.plan(list, "x", ListMove.UP).isEmpty())
        assertTrue(ChannelOrder.plan(listOf(OrderedChannel("a", 0)), "a", ListMove.BOTTOM).isEmpty())
    }

    @Test fun exhaustedGapsRenumberTheWholeList() {
        var current = list
        repeat(120) {
            val id = if (it % 2 == 0) "c" else "b"
            val writes = ChannelOrder.plan(current, id, ListMove.UP)
            assertFalse(writes.isEmpty())
            current = apply(current, writes)
            assertEquals(current.size, current.map { c -> c.key }.distinct().size)
        }
        assertEquals(listOf("a", "b", "c", "d"), current.map { it.id })
    }

    @Test fun tiesAreRenumbered() {
        val tied = listOf(OrderedChannel("a", 5), OrderedChannel("b", 5), OrderedChannel("c", 5))
        val writes = ChannelOrder.plan(tied, "c", ListMove.UP)
        assertEquals(listOf("a", "c", "b"), tied.map { it.copy(key = writes[it.id] ?: it.key) }.sortedBy { it.key }.map { it.id })
    }

    @Test fun listMovesFollowTheSameRules() {
        assertEquals(listOf(2, 1, 3), movedList(listOf(1, 2, 3), 1, ListMove.UP))
        assertEquals(listOf(1, 3, 2), movedList(listOf(1, 2, 3), 1, ListMove.DOWN))
        assertEquals(listOf(3, 1, 2), movedList(listOf(1, 2, 3), 2, ListMove.TOP))
        assertNull(movedList(listOf(1, 2, 3), 2, ListMove.BOTTOM))
        assertNull(movedList(listOf(1, 2, 3), -1, ListMove.TOP))
    }

    @Test fun categoryOrderKeepsSavedNamesFirstAndNewOnesAfter() {
        assertEquals(listOf("Sport", "News", "Film", "Kids"), CategoryOrder.apply(listOf("News", "Sport", "Film", "Kids"), listOf("Sport", "Gone", "News")))
        assertEquals(listOf("News", "Sport"), CategoryOrder.apply(listOf("News", "Sport"), emptyList()))
    }

    @Test fun categoryMoveKeepsHiddenNamesAtTheEnd() {
        assertEquals(listOf("Sport", "News", "Film", "Adult"), CategoryOrder.move(listOf("News", "Sport", "Film"), listOf("Adult"), "Sport", ListMove.UP))
        assertEquals(listOf("Film", "News", "Sport", "Adult"), CategoryOrder.move(listOf("News", "Sport", "Film"), listOf("Adult"), "Film", ListMove.TOP))
        assertNull(CategoryOrder.move(listOf("News", "Sport"), emptyList(), "Sport", ListMove.BOTTOM))
        assertNull(CategoryOrder.move(listOf("News"), emptyList(), "Missing", ListMove.UP))
    }
}
