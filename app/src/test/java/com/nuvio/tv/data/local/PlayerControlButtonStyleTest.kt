package com.nuvio.tv.data.local

import com.nuvio.tv.ui.screens.settings.*
import org.junit.Assert.*
import org.junit.Test

class PlayerControlButtonStyleTest {
    private val stats=PlayerControlAction.STATS
    private val audio=PlayerControlAction.AUDIO
    private fun draft(v2: Boolean=true) = PlayerControlLayoutDraft(PlayerControlLayoutSnapshot(7,null,null),previewDefault=PlayerControlLayout.original(v2))
    @Test fun `legacy custom layouts retain their placements and existing Labelled appearance`() {
        val l=PlayerControlLayout.decode("v1|stats,centre,0;audio,left,1")!!
        assertEquals(PlayerControlButtonStyle.LABELLED,l.style)
        assertEquals(PlayerControlPlacement(stats,PlayerControlGroup.CENTRE,false),l.entries.single { it.action==stats })
        assertEquals(PlayerControlGroup.LEFT,l.entries.single { it.action==audio }.group)
    }
    @Test fun `both styles roundtrip with hidden actions and reordered regions`() {
        for (style in PlayerControlButtonStyle.entries) {
            val l=PlayerControlLayout.default().withStyle(style).withVisibility(stats,false).withGroup(audio,PlayerControlGroup.CENTRE)
            assertEquals(l,PlayerControlLayout.decode(l.encode()))
        }
    }
    @Test fun `unknown style and future version retain display fallback rather than guessing`() {
        assertNull(PlayerControlLayout.decode("v2|future|stats,right,1"));assertNull(PlayerControlLayout.decode("v3|previous|stats,right,1"))
        assertNull(PlayerControlLayout.decode("v2|labelled"));assertNull(PlayerControlLayout.decode("v2|previous|"))
    }
    @Test fun `style is preserved across every layout edit operation`() {
        val l=PlayerControlLayout.default().withStyle(PlayerControlButtonStyle.LABELLED)
        assertEquals(PlayerControlButtonStyle.LABELLED,l.withVisibility(stats,false).withGroup(audio,PlayerControlGroup.LEFT).move(audio,-1).style)
    }
    @Test fun `style participates in equality so a real style change emits layout updates`() {
        val a=PlayerControlLayout.default();val b=a.withStyle(PlayerControlButtonStyle.LABELLED)
        assertNotEquals(a,b);assertEquals(a.entries,b.entries);assertSame(a,a.withStyle(PlayerControlButtonStyle.PREVIOUS))
    }
    @Test fun `untouched template includes primary actions plus the existing More expansion and episode region`() {
        for (v2 in listOf(false,true)) {
            val l=PlayerControlLayout.original(v2)
            if (v2) assertEquals(listOf(PlayerControlAction.STATS,PlayerControlAction.AUDIO,PlayerControlAction.SUBTITLES,PlayerControlAction.SOURCES,PlayerControlAction.EPISODES),
                l.entries.filter { it.group==PlayerControlGroup.RIGHT && it.visible }.take(5).map { it.action })
            assertEquals(15,l.entries.count { it.visible });assertEquals(PlayerControlButtonStyle.PREVIOUS,l.style)
            assertTrue(l.entries.single { it.action==PlayerControlAction.SPEED }.visible)
            assertEquals(if(v2) PlayerControlGroup.RIGHT else PlayerControlGroup.LEFT,l.entries.single { it.action==PlayerControlAction.EPISODES }.group)
        }
    }
    @Test fun `style choice is provisional and keeps the captured profile and original null revision`() {
        val start=draft();val changed=start.style(PlayerControlButtonStyle.LABELLED)
        assertNull(start.layout);assertEquals(PlayerControlButtonStyle.LABELLED,changed.preview.style)
        assertSame(start.snapshot,changed.snapshot);assertNull(changed.snapshot.serialized);assertEquals(7,changed.snapshot.profileId)
    }
    @Test fun `Restore defaults restores template and Previous style without losing save ownership`() {
        val start=draft(false);val reset=start.style(PlayerControlButtonStyle.LABELLED).visibility(stats,false).reset()
        assertNull(reset.layout);assertEquals(start.preview,reset.preview);assertSame(start.snapshot,reset.snapshot)
    }
    @Test fun `vertical move inserts into the wrapped sibling slot and Back restores the exact prior draft`() {
        val prior=draft().style(PlayerControlButtonStyle.LABELLED)
        val s=PlayerControlVisualEditorState(prior,stats).beginMove().vertical(PlayerControlAction.SOURCES)
        assertTrue(s.moving);assertEquals(stats,s.selected)
        val before=prior.preview.entries.filter { it.group==PlayerControlGroup.RIGHT }.map { it.action }
        val after=s.draft.preview.entries.filter { it.group==PlayerControlGroup.RIGHT }.map { it.action }
        assertEquals(before.indexOf(PlayerControlAction.SOURCES),after.indexOf(stats))
        assertTrue(after.indexOf(PlayerControlAction.SOURCES)<after.indexOf(stats))
        assertEquals(PlayerControlButtonStyle.LABELLED,s.draft.preview.style);assertSame(prior,s.cancelMove().draft)
    }
    @Test fun `vertical move ignores unrelated groups absent targets and non moving state`() {
        val s=PlayerControlVisualEditorState(draft(),stats)
        assertSame(s,s.vertical(audio));val moving=s.beginMove()
        assertSame(moving,moving.vertical(PlayerControlAction.PLAY_PAUSE));assertSame(moving,moving.vertical(null))
        assertSame(moving,moving.vertical(stats))
    }
    @Test fun `style is locked during a move and after stale editor rejection`() {
        val moving=PlayerControlVisualEditorState(draft()).beginMove()
        assertSame(moving,moving.style(PlayerControlButtonStyle.LABELLED))
        val rejected=moving.reject();assertSame(rejected,rejected.style(PlayerControlButtonStyle.LABELLED))
    }
    @Test fun `hidden vertical reordering never makes the action available or visible`() {
        val prior=draft().visibility(stats,false)
        val changed=PlayerControlVisualEditorState(prior,stats).beginMove().vertical(audio).place()
        assertFalse(changed.draft.preview.entries.single { it.action==stats }.visible)
        assertFalse(changed.moving);assertSame(prior.snapshot,changed.draft.snapshot)
    }
}
