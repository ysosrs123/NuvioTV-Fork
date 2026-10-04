package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.local.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.roundToInt

class PlayerControlMoreActionsTest {
    private val all=PlayerControlAction.entries.toSet()
    private val more=PlayerControlAction.MORE
    private val speed=PlayerControlAction.SPEED
    private val primary=PlayerControlAction.STATS
    private val play=PlayerControlAction.PLAY_PAUSE
    private val extras=setOf(speed,PlayerControlAction.ASPECT,PlayerControlAction.EXTERNAL,PlayerControlAction.ENGINE,PlayerControlAction.REPORT)
    private fun sizes(width:Int=40,height:Int=20)=all.associateWith { PlayerControlDeckSize(width,height) }
    private fun plan(l:PlayerControlLayout,expanded:Boolean=false,preview:Boolean=false,available:Set<PlayerControlAction> = all)=
        playerControlDeckPlan(l,available,sizes(),320,10,preview,15,expanded)
    @Test fun `original templates mirror primary and advanced More sets in both themes`() {
        for (v2 in listOf(false,true)) assertEquals(extras,playerControlCollapsedActions(PlayerControlLayout.original(v2),all))
    }
    @Test fun `closed and open deck expose different sets without changing saved data`() {
        val l=PlayerControlLayout.original(true);val bytes=l.encode()
        assertEquals(all-extras,playerControlDeckAvailable(l,all,false));assertEquals(all,playerControlDeckAvailable(l,all,true))
        assertEquals(bytes,l.encode());assertEquals(l,PlayerControlLayout.decode(bytes))
    }
    @Test fun `hidden advanced actions stay hidden during expansion`() {
        val l=PlayerControlLayout.original(true).withVisibility(speed,false)
        assertFalse(speed in playerControlCollapsedActions(l,all));assertFalse(plan(l,true).cells.any { it.action==speed })
    }
    @Test fun `unsupported advanced actions never appear during expansion`() {
        val l=PlayerControlLayout.original(true);val available=all-speed
        assertFalse(speed in playerControlCollapsedActions(l,available));assertFalse(plan(l,true,available=available).cells.any { it.action==speed })
    }
    @Test fun `hiding More hides its suffix from runtime controls`() {
        val l=PlayerControlLayout.original(true).withVisibility(more,false)
        assertEquals(extras,playerControlCollapsedActions(l,all));assertFalse(plan(l).cells.any { it.action in extras })
    }
    @Test fun `unsupported More cannot strand available controls`() {
        val l=PlayerControlLayout.original(true)
        assertTrue(playerControlCollapsedActions(l,all-more).isEmpty());assertTrue(plan(l,available=all-more).cells.any { it.action==speed })
    }
    @Test fun `legacy relocated More leaves previous area directly reachable`() {
        val l=PlayerControlLayout.normalized(PlayerControlLayout.original(true).entries.map { if (it.action==more) it.copy(group=PlayerControlGroup.LEFT) else it })
        assertTrue(playerControlCollapsedActions(l,all).isEmpty());assertTrue(plan(l).cells.any { it.action==speed })
    }
    @Test fun `moving an action before or after More changes expansion membership`() {
        var l=PlayerControlLayout.original(true)
        assertTrue(speed in playerControlCollapsedActions(l,all));l=l.move(speed,-1)
        assertFalse(speed in playerControlCollapsedActions(l,all));assertTrue(plan(l).cells.any { it.action==speed })
        l=l.move(speed,1);assertTrue(speed in playerControlCollapsedActions(l,all))
    }
    @Test fun `preview collapsed cells are editable ghosts without shifting live positions`() {
        val l=PlayerControlLayout.original(true);val live=plan(l);val preview=plan(l,preview=true)
        assertEquals(live.cells,preview.cells.filterNot { it.hidden });assertEquals(extras,preview.collapsedActions)
        assertEquals(all,preview.cells.map { it.action }.toSet());assertTrue(preview.cells.filter { it.action in extras }.all { it.hidden })
        assertEquals(plan(l,true).cells,plan(l,true,true).cells.filterNot { it.hidden })
    }
    @Test fun `expansion never drops transport controls below wrapped right actions`() {
        val l=PlayerControlLayout.original(true)
        for (expanded in listOf(false,true)) {
            val p=plan(l,expanded);assertEquals(0,p.cells.single { it.action==play }.y)
            assertEquals(0,p.cells.single { it.action==PlayerControlAction.RESTART }.y)
        }
        assertTrue(plan(l,true).height>plan(l).height)
    }
    @Test fun `all hidden continues to own no runtime button even with expansion open`() {
        val l=all.fold(PlayerControlLayout.default()) { v,a -> v.withVisibility(a,false) }
        assertTrue(plan(l,true).cells.isEmpty());assertEquals(all,plan(l,true,true).cells.map { it.action }.toSet())
    }
    @Test fun `density canvas font and slider sweeps keep measured cells bounded and nonoverlapping`() {
        for (canvas in listOf(720,1280,1920,3840)) for (density in listOf(1f,1.5f,2f,3f))
        for (scale in listOf(.75f,1f,1.25f,1.5f,2f)) for (font in listOf(1f,1.3f)) for (labelled in listOf(false,true)) {
            val d=density*scale;val gap=(8*d).roundToInt();val h=(42*d*font).roundToInt()
            val sizes=all.associateWith { PlayerControlDeckSize(((if(labelled) 140 else 42)*d).roundToInt(),h) }
            val l=PlayerControlLayout.original(true)
            for(expanded in listOf(false,true)) {
                val p=playerControlDeckPlan(l,all,sizes,canvas,gap,true,(16*d*font).roundToInt(),expanded)
                assertEquals(all,p.cells.map { it.action }.toSet())
                assertTrue(p.cells.all { it.x>=0 && it.y>=0 && it.x+it.width<=canvas && it.y+it.height<=p.height })
                for(a in p.cells) for(b in p.cells) if(a.action!=b.action)
                    assertFalse(a.x<b.x+b.width && b.x<a.x+a.width && a.y<b.y+b.height && b.y<a.y+a.height)
                assertEquals(0,p.cells.single { it.action==play }.y)
            }
        }
    }
    @Test fun `transport top alignment persists as long translated labels wrap to single columns`() {
        val l=PlayerControlLayout.original(true)
        for(width in listOf(200,320,640,1280)) {
            val p=playerControlDeckPlan(l,all,sizes(600,56),width,12,true,20,true)
            assertEquals(0,p.cells.single { it.action==play }.y);assertTrue(p.cells.all { it.x+it.width<=width })
        }
    }

    @Test fun `hiding More retains menu choices across themes styles and saved reload`() {
        for (v2 in listOf(false,true)) for (style in PlayerControlButtonStyle.entries) {
            val shown=PlayerControlLayout.original(v2).withStyle(style).withVisibility(speed,false)
            val hidden=shown.withVisibility(more,false)
            val reloaded=PlayerControlLayout.decode(hidden.encode())!!
            assertFalse(reloaded.focusOrder(playerControlDeckAvailable(reloaded,all,false)).any { it in extras })
            assertFalse(plan(reloaded).cells.any { it.action in extras })
            val restored=reloaded.withVisibility(more,true)
            assertEquals(shown,restored)
            assertFalse(playerControlMoreEntries(restored,all,false).any { it.action==speed })
            assertEquals(extras-speed,playerControlMoreEntries(restored,all,false).map { it.action }.toSet())
        }
    }
    @Test fun `stale expanded state cannot expose hidden More members and preview keeps ghosts`() {
        val hidden=PlayerControlLayout.original(true).withVisibility(more,false)
        for (expanded in listOf(false,true)) {
            assertFalse(playerControlDeckAvailable(hidden,all,expanded).any { it in extras })
            val live=plan(hidden,expanded);val preview=plan(hidden,expanded,true)
            assertFalse(live.cells.any { it.action in extras || it.action==more })
            assertEquals(live.cells,preview.cells.filterNot { it.hidden })
            assertEquals(all,preview.cells.map { it.action }.toSet())
            assertTrue(preview.cells.filter { it.action in extras }.all { it.hidden })
        }
    }
    @Test fun `moving a member out of hidden More makes only that action direct`() {
        val hidden=PlayerControlLayout.original(true).withVisibility(more,false)
        val moved=hidden.move(speed,-1)
        assertTrue(plan(moved).cells.any { it.action==speed })
        assertFalse(plan(moved).cells.any { it.action in extras-speed })
        assertFalse(playerControlCollapsedActions(moved,all).contains(speed))
        assertTrue(playerControlCollapsedActions(moved.move(speed,1),all).contains(speed))
    }
    @Test fun `hidden More still hides available members when toggle capability disappears`() {
        val hidden=PlayerControlLayout.original(true).withVisibility(more,false)
        assertFalse(plan(hidden,available=all-more).cells.any { it.action in extras })
        assertFalse(playerControlDeckAvailable(hidden,all-more,true).any { it in extras })
    }
}
