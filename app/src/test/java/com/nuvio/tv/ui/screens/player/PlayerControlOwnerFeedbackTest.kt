package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.local.*
import com.nuvio.tv.ui.screens.settings.*
import org.junit.Assert.*
import org.junit.Test

class PlayerControlOwnerFeedbackTest {
    private val all = PlayerControlAction.entries.toSet()
    private val more = PlayerControlAction.MORE
    private val speed = PlayerControlAction.SPEED
    private val play = PlayerControlAction.PLAY_PAUSE
    private fun sizes(w: Int = 40) = all.associateWith { PlayerControlDeckSize(w, 20) }
    private fun plan(layout: PlayerControlLayout, sizes: Map<PlayerControlAction, PlayerControlDeckSize> = sizes(), width: Int = 320, preview: Boolean = false) =
        playerControlPrimaryDeckPlan(layout, all, sizes, width, 10, preview, 15)
    @Test fun `More cannot enter move but remains hideable and popup preview follows visibility`() {
        val s = PlayerControlVisualEditorState(PlayerControlLayoutDraft(PlayerControlLayoutSnapshot(1, null, null))).select(more)
        assertFalse(s.canMove); assertSame(s, s.beginMove()); assertTrue(s.canPreviewMore)
        val hidden = s.toggle(); assertFalse(hidden.canPreviewMore); assertFalse(hidden.moving)
        assertTrue(speed in playerControlCollapsedActions(hidden.draft.preview, all))
        assertFalse(plan(hidden.draft.preview).cells.any { it.action == speed })
        assertEquals(s.draft.preview, hidden.toggle().draft.preview)
    }
    @Test fun `More preference location is immutable through direct model mutation`() {
        val l = PlayerControlLayout.original(true)
        assertSame(l, l.withGroup(more, PlayerControlGroup.LEFT)); assertSame(l, l.move(more, -1))
        assertSame(l, l.move(more, 1)); assertFalse(l.withVisibility(more, false).entries.single { it.action == more }.visible)
    }
    @Test fun `other actions still cross menu boundary and cancellation restores exact draft`() {
        val l = PlayerControlLayout.original(true)
        val s = PlayerControlVisualEditorState(PlayerControlLayoutDraft(PlayerControlLayoutSnapshot(2, l.encode(), l)), speed).beginMove()
        assertTrue(speed in playerControlCollapsedActions(l, all))
        val moved = s.step(-1); assertFalse(speed in playerControlCollapsedActions(moved.draft.preview, all))
        assertEquals(l, moved.cancelMove().draft.layout)
        assertTrue(speed in playerControlCollapsedActions(moved.step(1).place().draft.preview, all))
    }
    @Test fun `older relocated More preserves menu boundary but renders at right end`() {
        val original = PlayerControlLayout.original(false)
        val legacy = PlayerControlLayout.normalized(original.entries.map { if (it.action == more) it.copy(group = PlayerControlGroup.LEFT) else it })
        val decoded = PlayerControlLayout.decode(legacy.encode())!!
        assertEquals(legacy, decoded); assertEquals(emptySet<PlayerControlAction>(), playerControlCollapsedActions(decoded, all))
        val cell = plan(decoded).cells.single { it.action == more }
        assertEquals(320, cell.x + cell.width); assertEquals(0, cell.y)
    }
    @Test fun `empty centre lends enough space to avoid side icon wrap`() {
        val shown = setOf(play, PlayerControlAction.RESTART, PlayerControlAction.AUDIO, PlayerControlAction.SUBTITLES, PlayerControlAction.SOURCES, more)
        val l = PlayerControlLayout.normalized(PlayerControlAction.entries.map { a -> PlayerControlPlacement(a,
            if (a == play || a == PlayerControlAction.RESTART) PlayerControlGroup.LEFT else PlayerControlGroup.RIGHT, a in shown) })
        val p = plan(l)
        assertEquals(20, p.mainHeight); assertTrue(p.cells.all { it.y == 0 })
        assertEquals(320, p.cells.single { it.action == more }.x + 40)
    }
    @Test fun `More remains in first row and far right when labels need additional rows`() {
        val l = PlayerControlLayout.original(true).withGroup(PlayerControlAction.STATS, PlayerControlGroup.CENTRE)
        val p = plan(l, sizes(75))
        val cell = p.cells.single { it.action == more }; assertEquals(0, cell.y); assertEquals(320, cell.x + cell.width)
        assertTrue(p.cells.any { !it.hidden && it.y > 0 })
        for (a in p.cells) for (b in p.cells) if (a.action != b.action)
            assertFalse(a.x < b.x+b.width && b.x < a.x+a.width && a.y < b.y+b.height && b.y < a.y+a.height)
    }
    @Test fun `short More shares a label row when measured buttons fit`() {
        val l = PlayerControlLayout.original(true).withGroup(PlayerControlAction.STATS, PlayerControlGroup.CENTRE)
            .withGroup(PlayerControlAction.SUBTITLES, PlayerControlGroup.CENTRE)
        val s = sizes(40).toMutableMap().apply { put(more, PlayerControlDeckSize(32, 20)) }
        val p = plan(l, s, 500)
        assertEquals(0, p.cells.single { it.action == more }.y)
        assertEquals(0, p.cells.single { it.action == PlayerControlAction.SOURCES }.y)
    }
    @Test fun `preview ghosts preserve live cells after fixed More and centre borrowing`() {
        val l = PlayerControlLayout.original(true)
        assertEquals(plan(l).cells, plan(l, preview = true).cells.filterNot { it.hidden })
        assertEquals(all, plan(l, preview = true).cells.map { it.action }.toSet())
    }
    @Test fun `default display and reset are Icons in both renderers without overwriting explicit Labels`() {
        for (v2 in listOf(false, true)) {
            assertEquals(PlayerControlButtonStyle.PREVIOUS, PlayerControlLayout.effective(null, v2).style)
            val labelled = PlayerControlLayout.original(v2).withStyle(PlayerControlButtonStyle.LABELLED)
            assertSame(labelled, PlayerControlLayout.effective(labelled, v2))
            val reset = PlayerControlLayoutDraft(PlayerControlLayoutSnapshot(1, labelled.encode(), labelled), PlayerControlLayout.original(v2)).reset()
            assertNull(reset.layout); assertEquals(PlayerControlButtonStyle.PREVIOUS, reset.preview.style)
        }
    }
    @Test fun `capability loss and all hidden still leave no invented runtime actions`() {
        val l = all.fold(PlayerControlLayout.original(true)) { value, a -> value.withVisibility(a, false) }
        assertTrue(plan(l).cells.isEmpty()); assertNull(l.focusFallback(more, all))
        assertEquals(all, plan(l, preview = true).cells.map { it.action }.toSet())
    }
}
