package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.local.*
import org.junit.Assert.*
import org.junit.Test

class PlayerControlDeckGeometryTest {
    private val all = PlayerControlAction.entries.toSet()
    private val play = PlayerControlAction.PLAY_PAUSE
    private val restart = PlayerControlAction.RESTART
    private val stats = PlayerControlAction.STATS
    private val audio = PlayerControlAction.AUDIO
    private val sub = PlayerControlAction.SUBTITLES
    private val source = PlayerControlAction.SOURCES
    private fun layout(vararg entries: Pair<PlayerControlAction, PlayerControlGroup>): PlayerControlLayout =
        PlayerControlLayout.normalized(PlayerControlAction.entries.map { action ->
            PlayerControlPlacement(action, entries.firstOrNull { it.first == action }?.second ?: PlayerControlGroup.LEFT,
                entries.any { it.first == action }) })
    private fun sizes(width: Int = 40, height: Int = 20) = all.associateWith { PlayerControlDeckSize(width, height) }
    private fun plan(l: PlayerControlLayout, width: Int = 320, ghost: Boolean = false, available: Set<PlayerControlAction> = all,
        sizes: Map<PlayerControlAction, PlayerControlDeckSize> = sizes()) = playerControlDeckPlan(l, available, sizes, width, 10, ghost, 15)
    private fun PlayerControlDeckPlan.cell(a: PlayerControlAction) = cells.single { it.action == a }
    @Test fun `left centre and right align to their real viewport regions`() {
        val p = plan(layout(play to PlayerControlGroup.LEFT, stats to PlayerControlGroup.CENTRE, audio to PlayerControlGroup.RIGHT))
        assertEquals(0,p.cell(play).x); assertEquals(140,p.cell(stats).x); assertEquals(280,p.cell(audio).x)
        assertEquals(20,p.mainHeight)
    }
    @Test fun `wrapped columns have actual up down and horizontal neighbours`() {
        val l = layout(play to PlayerControlGroup.CENTRE,stats to PlayerControlGroup.RIGHT,audio to PlayerControlGroup.RIGHT,sub to PlayerControlGroup.RIGHT,source to PlayerControlGroup.RIGHT)
        val p = plan(l)
        assertEquals(230,p.cell(stats).x);assertEquals(280,p.cell(audio).x)
        assertEquals(0,p.cell(stats).y);assertEquals(30,p.cell(sub).y)
        assertEquals(sub,p.neighbour(stats,PlayerControlDirection.DOWN))
        assertEquals(audio,p.neighbour(source,PlayerControlDirection.UP))
        assertEquals(audio,p.neighbour(stats,PlayerControlDirection.RIGHT))
        assertEquals(sub,p.neighbour(source,PlayerControlDirection.LEFT))
        assertNull(p.neighbour(sub,PlayerControlDirection.DOWN))
    }
    @Test fun `short groups stay directly below times when another area wraps`() {
        val p = plan(layout(play to PlayerControlGroup.LEFT,restart to PlayerControlGroup.CENTRE,stats to PlayerControlGroup.RIGHT,audio to PlayerControlGroup.RIGHT,sub to PlayerControlGroup.RIGHT))
        assertEquals(0,p.cell(play).y);assertEquals(30,p.cell(sub).y);assertEquals(50,p.mainHeight)
        assertEquals(restart,p.neighbour(play,PlayerControlDirection.RIGHT))
    }
    @Test fun `preview ghosts do not move or resize any live player cell`() {
        val l = PlayerControlLayout.original(true).withGroup(stats,PlayerControlGroup.CENTRE).withVisibility(audio,false)
        val runtime = plan(l); val preview = plan(l,ghost=true)
        assertEquals(runtime.cells,preview.cells.filterNot { it.hidden });assertEquals(runtime.mainHeight,preview.mainHeight)
        assertTrue(preview.cells.filter { it.hidden }.all { it.y > preview.mainHeight })
        assertEquals(all,preview.cells.map { it.action }.toSet())
    }
    @Test fun `movie availability moves episode actions to editable ghosts without enabling runtime actions`() {
        val l = PlayerControlLayout.original(true);val movie = all-setOf(PlayerControlAction.EPISODES,PlayerControlAction.NEXT_EPISODE)
        val p = plan(l,ghost=true,available=movie);val live=plan(l,available=movie)
        assertTrue(p.cell(PlayerControlAction.EPISODES).hidden);assertTrue(p.cell(PlayerControlAction.NEXT_EPISODE).hidden)
        assertEquals(live.cells,p.cells.filterNot { it.hidden })
    }
    @Test fun `all hidden runtime stays empty while every ghost remains reachable in the preview`() {
        val l = all.fold(PlayerControlLayout.default()) { value,a -> value.withVisibility(a,false) }
        assertTrue(plan(l).cells.isEmpty());assertEquals(0,plan(l).height)
        val p=plan(l,ghost=true);assertEquals(16,p.cells.size);assertEquals(0,p.mainHeight);assertEquals(0,p.hiddenLabelY)
        assertTrue(p.cells.all { it.hidden });assertNotNull(p.neighbour(play,PlayerControlDirection.RIGHT))
    }
    @Test fun `measured label widths determine wrapping rather than a fixed column count`() {
        val l=layout(play to PlayerControlGroup.LEFT,restart to PlayerControlGroup.LEFT,stats to PlayerControlGroup.CENTRE)
        assertEquals(20,plan(l).mainHeight)
        val wide=sizes().toMutableMap().apply { put(play,PlayerControlDeckSize(75,20)) }
        assertEquals(50,plan(l,sizes=wide).mainHeight)
    }
    @Test fun `mixed old icon and labelled heights centre within their shared row`() {
        val l=layout(stats to PlayerControlGroup.RIGHT,audio to PlayerControlGroup.RIGHT)
        val mixed=sizes().toMutableMap().apply { put(audio,PlayerControlDeckSize(40,36)) }
        val p=plan(l,sizes=mixed)
        assertEquals(8,p.cell(stats).y);assertEquals(0,p.cell(audio).y);assertEquals(36,p.mainHeight)
    }
    @Test fun `one region containing all actions wraps without overlap or lost identities`() {
        val l=all.fold(PlayerControlLayout.default()) { value,a -> value.withGroup(a,PlayerControlGroup.RIGHT).withVisibility(a,true) }
        val p=plan(l);assertEquals(16,p.cells.size);assertEquals(80,p.height)
        for (c in p.cells) assertTrue(c.x>=0 && c.x+c.width<=320)
        assertEquals(16,p.cells.map { it.action }.distinct().size)
    }
    @Test fun `ghost focus links return into the bottom live row`() {
        val p=plan(layout(play to PlayerControlGroup.LEFT),ghost=true)
        val ghost=p.cells.first { it.hidden };assertEquals(play,p.neighbour(ghost.action,PlayerControlDirection.UP))
        assertNotNull(p.neighbour(play,PlayerControlDirection.DOWN))
    }
    @Test fun `neighbour edges and missing actions return null rather than an unattached target`() {
        val p=plan(layout(play to PlayerControlGroup.LEFT))
        for (d in PlayerControlDirection.entries) assertNull(p.neighbour(play,d))
        assertNull(p.neighbour(stats,PlayerControlDirection.DOWN))
    }
    @Test fun `narrow and zero widths keep cells inside viewport without negative dimensions`() {
        val l=PlayerControlLayout.default()
        for (width in 0..35) {
            val p=plan(l,width=width,ghost=true)
            assertEquals(16,p.cells.size)
            assertTrue(p.cells.all { it.x>=0 && it.width>=0 && it.x+it.width<=width && it.y>=0 && it.y+it.height<=p.height })
        }
    }
    @Test fun `viewport remainder preserves exact right alignment`() {
        val p=plan(layout(audio to PlayerControlGroup.RIGHT),width=322)
        assertEquals(322,p.cell(audio).x+p.cell(audio).width)
    }
    @Test fun `repeated moves and visibility switches never alter the action identity set`() {
        var l=PlayerControlLayout.default()
        repeat(120) { i ->
            val a=PlayerControlAction.entries[i%16]
            l=l.withGroup(a,PlayerControlGroup.entries[i%3]).withVisibility(a,i%2==0).move(a,1)
            val runtime=plan(l);val preview=plan(l,ghost=true)
            assertEquals(all,preview.cells.map { it.action }.toSet());assertEquals(runtime.cells,preview.cells.filterNot { it.hidden })
        }
    }
}
