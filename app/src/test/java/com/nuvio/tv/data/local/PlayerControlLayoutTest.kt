package com.nuvio.tv.data.local

import org.junit.Assert.*
import org.junit.Test

class PlayerControlLayoutTest {
    private val play = PlayerControlAction.PLAY_PAUSE
    private val audio = PlayerControlAction.AUDIO
    private val stats = PlayerControlAction.STATS
    private val left = PlayerControlGroup.LEFT
    private val centre = PlayerControlGroup.CENTRE
    private val right = PlayerControlGroup.RIGHT
    private val all = PlayerControlAction.entries.toSet()
    @Test fun `unset and future versions use original renderers without pretending a custom layout`() {
        assertNull(PlayerControlLayout.decode(null)); assertNull(PlayerControlLayout.decode("v2|play_pause,left,0"))
        assertNull(PlayerControlLayout.decode("v1|")); assertNull(PlayerControlLayout.decode("garbage"))
    }
    @Test fun `mixed groups and hidden entries survive stable preference roundtrip`() {
        val layout = PlayerControlLayout.default().withGroup(play, centre).withGroup(audio, left).withVisibility(stats, false).move(audio, -1)
        assertEquals(layout, PlayerControlLayout.decode(layout.encode()))
        assertTrue(layout.encode().startsWith("v2|previous|")); assertTrue(layout.encode().contains("play_pause,centre,1"))
    }
    @Test fun `missing actions migrate without overwriting the existing arrangement`() {
        val layout = PlayerControlLayout.decode("v1|audio,centre,0;play_pause,right,1")!!
        assertEquals(PlayerControlAction.entries.size, layout.entries.size)
        assertEquals(PlayerControlPlacement(audio, centre, false), layout.entries.single { it.action == audio })
        assertEquals(listOf(play, stats), layout.visibleGroups(all).getValue(right).take(2))
    }
    @Test fun `first valid duplicate wins and invalid or unknown records do not erase choices`() {
        val layout = PlayerControlLayout.decode("v1|future_action,left,1;audio,left,2;audio,centre,0;audio,right,1;stats,wrong,1;play_pause,right,1")!!
        assertEquals(PlayerControlPlacement(audio, centre, false), layout.entries.single { it.action == audio })
        assertEquals(right, layout.entries.single { it.action == stats }.group)
    }
    @Test fun `oversized and excessively repeated saved layouts are bounded`() {
        assertNull(PlayerControlLayout.decode("v1|" + "x".repeat(4097)))
        assertNull(PlayerControlLayout.decode("v1|" + List(65) { "audio,left,1" }.joinToString(";")))
    }
    @Test fun `move crosses hidden siblings but cannot reorder another group`() {
        val start = PlayerControlLayout.default().withVisibility(PlayerControlAction.RESTART, false)
        val changed = start.move(PlayerControlAction.EPISODES, -1)
        assertEquals(listOf(play, PlayerControlAction.EPISODES, PlayerControlAction.RESTART, PlayerControlAction.NEXT_EPISODE), changed.entries.filter { it.group == left }.map { it.action })
        assertEquals(start.entries.filter { it.group == right }, changed.entries.filter { it.group == right })
        assertFalse(changed.entries.single { it.action == PlayerControlAction.RESTART }.visible)
    }
    @Test fun `edge or non-step moves preserve arrangement`() {
        val layout = PlayerControlLayout.default()
        assertEquals(layout, layout.move(play, -1)); assertEquals(layout, layout.move(play, 5))
        assertEquals(layout, layout.move(PlayerControlAction.INFO, 1))
    }
    @Test fun `move into a new group appends once and preserves visibility and other order`() {
        val before = PlayerControlLayout.default().withVisibility(audio, false)
        val layout = before.withGroup(audio, left)
        assertEquals(audio, layout.entries.filter { it.group == left }.last().action)
        assertFalse(layout.entries.single { it.action == audio }.visible)
        assertEquals(before.entries.filter { it.group == right && it.action != audio }, layout.entries.filter { it.group == right })
        assertEquals(layout, layout.withGroup(audio, left))
    }
    @Test fun `saved visibility cannot enable unavailable content or engine actions`() {
        val layout = PlayerControlLayout.default()
        val groups = layout.visibleGroups(setOf(play, stats))
        assertEquals(listOf(play), groups.getValue(left)); assertEquals(listOf(stats), groups.getValue(right))
        assertTrue(groups.getValue(centre).isEmpty())
    }
    @Test fun `all hidden layouts have a timeline focus fallback and no invented controls`() {
        val layout = all.fold(PlayerControlLayout.default()) { current, action -> current.withVisibility(action, false) }
        assertTrue(layout.focusOrder(all).isEmpty()); assertNull(layout.focusFallback(play, all))
        assertEquals(layout, PlayerControlLayout.decode(layout.encode()))
    }
    @Test fun `cross-group focus order is stable and return falls back after capability loss`() {
        val layout = PlayerControlLayout.default().withGroup(audio, centre)
        assertEquals(listOf(play, audio, stats), layout.focusOrder(setOf(stats, audio, play)))
        assertEquals(audio, layout.focusFallback(audio, setOf(play, audio)))
        assertEquals(play, layout.focusFallback(audio, setOf(play, stats)))
        assertEquals(stats, layout.focusFallback(audio, setOf(stats)))
        assertNull(layout.focusFallback(audio, emptySet()))
    }
    @Test fun `normalization and exposed lists cannot mutate a previous layout`() {
        val input = mutableListOf(PlayerControlPlacement(audio, centre, false))
        val layout = PlayerControlLayout.normalized(input); val saved = layout.encode()
        input.clear(); (layout.entries as MutableList).clear()
        assertEquals(saved, layout.encode()); assertEquals(PlayerControlAction.entries.size, layout.entries.size)
    }
    @Test fun `interleaving of different groups does not change normalized equality or local order`() {
        val one = PlayerControlLayout.normalized(listOf(PlayerControlPlacement(audio, centre), PlayerControlPlacement(play, left), PlayerControlPlacement(stats, centre)))
        val two = PlayerControlLayout.normalized(listOf(PlayerControlPlacement(play, left), PlayerControlPlacement(audio, centre), PlayerControlPlacement(stats, centre)))
        assertEquals(one, two); assertEquals(one.hashCode(), two.hashCode())
        assertEquals(listOf(audio, stats), one.visibleGroups(all).getValue(centre))
    }
    @Test fun `repeated edits remain complete unique and capability filtered`() {
        var layout = PlayerControlLayout.default()
        repeat(240) { index ->
            val action = PlayerControlAction.entries[index % all.size]
            layout = layout.withGroup(action, PlayerControlGroup.entries[index % 3]).withVisibility(action, index % 2 == 0).move(action, if (index % 2 == 0) 1 else -1)
            assertEquals(all, layout.entries.map { it.action }.toSet()); assertEquals(all.size, layout.entries.size)
            assertEquals(layout, PlayerControlLayout.decode(layout.encode()))
            assertTrue(layout.focusOrder(setOf(play, audio)).all { it == play || it == audio })
        }
    }
}
