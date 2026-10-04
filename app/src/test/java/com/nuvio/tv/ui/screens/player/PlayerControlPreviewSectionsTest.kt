package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.local.*
import org.junit.Assert.*
import org.junit.Test

class PlayerControlPreviewSectionsTest {
    private val all=PlayerControlAction.entries.toSet()
    private val more=PlayerControlAction.MORE
    private val speed=PlayerControlAction.SPEED
    private val info=PlayerControlAction.INFO
    private val extras=setOf(speed,PlayerControlAction.ASPECT,PlayerControlAction.EXTERNAL,PlayerControlAction.ENGINE,PlayerControlAction.REPORT)
    private fun plan(l:PlayerControlLayout,available:Set<PlayerControlAction> = all,width:Int=320,preview:Boolean=true,size:Int=40,height:Int=20,gap:Int=10)=
        playerControlPrimaryDeckPlan(l,available,all.associateWith { PlayerControlDeckSize(size,height) },width,gap,preview,15)
    private fun PlayerControlDeckPlan.left()=cells.filter { it.hidden && it.x < moreLabelX }.map { it.action }.toSet()
    private fun PlayerControlDeckPlan.right()=cells.filter { it.hidden && it.x >= moreLabelX }.map { it.action }.toSet()

    @Test fun `explicit hidden and unavailable actions are left while supported More members are right`() {
        val l=PlayerControlLayout.original(true).withVisibility(speed,false)
        val available=all-PlayerControlAction.ASPECT
        val p=plan(l,available)
        assertEquals(setOf(info,speed,PlayerControlAction.ASPECT),p.left())
        assertEquals(extras-setOf(speed,PlayerControlAction.ASPECT),p.right())
        assertEquals(p.hiddenLabelY,p.moreLabelY)
        assertEquals(all,p.cells.map { it.action }.toSet());assertEquals(all.size,p.cells.size)
        assertEquals(plan(l,available,preview=false).cells,p.cells.filterNot { it.hidden })
    }
    @Test fun `parent More hidden retains menu section and showing it restores the saved choices`() {
        val original=PlayerControlLayout.original(true)
        val hidden=original.withVisibility(more,false)
        assertEquals(extras,plan(hidden).right())
        assertEquals(setOf(info,more),plan(hidden).left())
        assertEquals(original,hidden.withVisibility(more,true))
        assertEquals(extras,plan(hidden.withVisibility(more,true)).right())
        assertFalse(plan(hidden,preview=false).cells.any { it.action in extras })
    }
    @Test fun `visibility toggles and crossing the menu boundary reclassify one keyed action`() {
        val original=PlayerControlLayout.original(true)
        assertTrue(speed in plan(original).right())
        val hidden=original.withVisibility(speed,false)
        assertTrue(speed in plan(hidden).left());assertFalse(speed in plan(hidden).right())
        assertEquals(original,hidden.withVisibility(speed,true))
        val direct=original.move(speed,-1)
        assertFalse(speed in plan(direct).right());assertTrue(plan(direct).cells.any { it.action==speed && !it.hidden })
        assertTrue(speed in plan(direct.move(speed,1)).right())
    }
    @Test fun `sections remain bounded and disjoint through themes styles widths and long wrapped labels`() {
        for(v2 in listOf(false,true)) for(style in PlayerControlButtonStyle.entries)
        for(width in (0..35).toList()+listOf(320,1280,1920)) for(size in listOf(40,200,600)) {
            val l=PlayerControlLayout.original(v2).withStyle(style).withVisibility(PlayerControlAction.STATS,false)
            val p=plan(l,width=width,size=size,height=56)
            assertEquals(all,p.cells.map { it.action }.toSet());assertEquals(all.size,p.cells.size)
            assertEquals(plan(l,width=width,preview=false,size=size,height=56).cells,p.cells.filterNot { it.hidden })
            assertTrue(p.cells.all { it.x>=0 && it.y>=0 && it.x+it.width<=width && it.y+it.height<=p.height })
            for(a in p.cells) for(b in p.cells) if(a.action!=b.action)
                assertFalse(a.x<b.x+b.width && b.x<a.x+a.width && a.y<b.y+b.height && b.y<a.y+a.height)
            assertTrue(p.cells.filter { it.hidden && it.action in p.collapsedActions }.all { it.x>=p.moreLabelX })
            assertTrue(p.cells.filter { it.hidden && it.action !in p.collapsedActions }.all { it.x+it.width<=p.previewSectionWidth })
        }
    }
    @Test fun `remote navigation crosses sections wraps More rows and returns to live controls`() {
        val l=PlayerControlLayout.original(true).withVisibility(PlayerControlAction.STATS,false).withVisibility(PlayerControlAction.AUDIO,false)
        val p=plan(l)
        assertEquals(speed,p.neighbour(info,PlayerControlDirection.RIGHT))
        assertEquals(info,p.neighbour(speed,PlayerControlDirection.LEFT))
        assertEquals(PlayerControlAction.ENGINE,p.neighbour(speed,PlayerControlDirection.DOWN))
        assertEquals(PlayerControlAction.ASPECT,p.neighbour(PlayerControlAction.ENGINE,PlayerControlDirection.UP))
        val up=p.neighbour(info,PlayerControlDirection.UP)
        assertNotNull(up);assertFalse(p.cells.single { it.action==up }.hidden)
    }
    @Test fun `each More row ends at the panel right edge and preserves menu order`() {
        for(v2 in listOf(false,true)) for(style in PlayerControlButtonStyle.entries)
        for(width in listOf(320,321,1280,1920)) for(size in listOf(40,200)) {
            val layout=PlayerControlLayout.original(v2).withStyle(style)
            val p=plan(layout,width=width,size=size)
            val menu=p.cells.filter { it.action in p.collapsedActions }
            assertTrue(menu.isNotEmpty())
            menu.groupBy { it.y }.values.forEach { row -> assertEquals(width,row.maxOf { it.x+it.width }) }
            assertEquals(layout.entries.filter { it.action in p.collapsedActions }.map { it.action },menu.map { it.action })
        }
    }
    @Test fun `enabled supported More members stay bright including when parent More is hidden`() {
        for(parentVisible in listOf(false,true)) {
            val layout=PlayerControlLayout.original(true).withVisibility(more,parentVisible)
            val available=all-PlayerControlAction.ASPECT
            val p=plan(layout,available)
            layout.entries.filter { it.action in p.collapsedActions }.forEach { entry ->
                assertFalse(playerControlPreviewFaded(entry,available))
            }
            val hidden=layout.withVisibility(speed,false)
            assertTrue(playerControlPreviewFaded(hidden.entries.single { it.action==speed },available))
            assertTrue(playerControlPreviewFaded(layout.entries.single { it.action==PlayerControlAction.ASPECT },available))
        }
    }
}
