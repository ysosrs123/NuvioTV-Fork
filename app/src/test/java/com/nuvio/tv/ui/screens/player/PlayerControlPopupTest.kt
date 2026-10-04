package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.local.*
import org.junit.Assert.*
import org.junit.Test

class PlayerControlPopupTest {
    private val all = PlayerControlAction.entries.toSet()
    private val more = PlayerControlAction.MORE
    private val speed = PlayerControlAction.SPEED
    private val extras = listOf(speed, PlayerControlAction.ASPECT, PlayerControlAction.EXTERNAL, PlayerControlAction.ENGINE, PlayerControlAction.REPORT)
    private fun primary(l: PlayerControlLayout, preview: Boolean = false, width: Int = 320) = playerControlPrimaryDeckPlan(l, all,
        all.associateWith { PlayerControlDeckSize(70, 40) }, width, 8, preview, 20)

    @Test fun `both templates supply ordered supported popup actions`() {
        for (v2 in listOf(false,true)) assertEquals(extras, playerControlMoreEntries(PlayerControlLayout.original(v2), all, false).map { it.action })
    }
    @Test fun `preview exposes hidden and unavailable suffix members without enabling runtime actions`() {
        val l = PlayerControlLayout.original(true).withVisibility(speed,false)
        val available = all - PlayerControlAction.ASPECT
        val runtime = playerControlMoreEntries(l,available,false)
        assertFalse(runtime.any { it.action==speed || it.action==PlayerControlAction.ASPECT || it.action==PlayerControlAction.INFO })
        val preview = playerControlMoreEntries(l,available,true)
        assertTrue(preview.any { it.action==speed && !it.visible }); assertTrue(preview.any { it.action==PlayerControlAction.ASPECT })
        assertTrue(preview.any { it.action==PlayerControlAction.INFO && !it.visible })
    }
    @Test fun `hidden More has no popup and keeps suffix actions hidden`() {
        val l=PlayerControlLayout.original(true).withVisibility(more,false)
        assertTrue(playerControlMoreEntries(l,all,false).isEmpty());assertFalse(primary(l).cells.any { it.action==speed })
    }
    @Test fun `unavailable More cannot create an unreachable popup`() {
        val l=PlayerControlLayout.original(true)
        assertTrue(playerControlMoreEntries(l,all-more,false).isEmpty())
        assertTrue(playerControlDeckAvailable(l,all-more,false).containsAll(extras))
    }
    @Test fun `legacy More boundary remains readable while further More movement is blocked`() {
        val l=PlayerControlLayout.normalized(PlayerControlLayout.original(true).entries.map { if (it.action==more) it.copy(group=PlayerControlGroup.LEFT) else it })
        assertTrue(playerControlMoreEntries(l,all,false).isEmpty());assertTrue(primary(l).cells.any { it.action==speed })
        assertSame(l,l.move(more,-1));assertTrue(playerControlMoreEntries(l,all,false).isEmpty())
    }
    @Test fun `moving across More separates direct and popup actions`() {
        val l=PlayerControlLayout.original(true)
        assertTrue(playerControlMoreEntries(l,all,false).any { it.action==speed })
        assertFalse(playerControlMoreEntries(l.move(speed,-1),all,false).any { it.action==speed })
        assertTrue(primary(l.move(speed,-1)).cells.any { it.action==speed })
    }
    @Test fun `opening menu does not require changing serialized layout`() {
        val l=PlayerControlLayout.original(true); val saved=l.encode()
        playerControlMoreEntries(l,all,false);primary(l);primary(l,true)
        assertEquals(saved,l.encode());assertEquals(l,PlayerControlLayout.decode(saved))
    }
    @Test fun `primary plan excludes popup rows which previously increased chrome height`() {
        val l=PlayerControlLayout.original(true); val sizes=all.associateWith { PlayerControlDeckSize(70,40) }
        val primary=primary(l)
        val oldExpanded=playerControlDeckPlan(l,all,sizes,320,8,false,20,true)
        assertTrue(oldExpanded.mainHeight>primary.mainHeight)
        assertFalse(primary.cells.any { it.action in extras })
        assertEquals(0,primary.cells.single { it.action==PlayerControlAction.PLAY_PAUSE }.y)
    }
    @Test fun `preview keeps one stable editable ghost for every popup member`() {
        val p=primary(PlayerControlLayout.original(true),true)
        assertEquals(all,p.cells.map { it.action }.toSet());assertEquals(all.size,p.cells.size)
        assertTrue(p.cells.filter { it.action in extras }.all { it.hidden })
        assertEquals(primary(PlayerControlLayout.original(true)).cells,p.cells.filterNot { it.hidden })
    }
    @Test fun `long label primary packing remains bounded and top aligned`() {
        for(width in listOf(200,320,640,1280)) {
            val p=primary(PlayerControlLayout.original(true),true,width)
            assertTrue(p.cells.all { it.x>=0 && it.x+it.width<=width && it.y>=0 && it.y+it.height<=p.height })
            assertEquals(0,p.cells.single { it.action==PlayerControlAction.PLAY_PAUSE }.y)
        }
    }
    @Test fun `popup prefers above its anchor when there is room`() {
        assertEquals(PlayerControlPopupPosition(500,300),playerControlPopupPosition(PlayerControlPopupBounds(700,608,800,650),PlayerControlPopupBounds(0,0,1000,800),300,300,8))
    }
    @Test fun `popup opens below when above is too small`() {
        assertEquals(PlayerControlPopupPosition(500,58),playerControlPopupPosition(PlayerControlPopupBounds(700,20,800,50),PlayerControlPopupBounds(0,0,1000,800),300,300,8))
    }
    @Test fun `popup clamps to the preview pane instead of the whole screen`() {
        val p=playerControlPopupPosition(PlayerControlPopupBounds(1300,440,1400,480),PlayerControlPopupBounds(200,300,1500,700),300,280,8)
        assertEquals(1100,p.x);assertEquals(300,p.y)
    }
    @Test fun `popup clamps left and right moved anchors`() {
        val viewport=PlayerControlPopupBounds(100,100,700,600)
        assertEquals(100,playerControlPopupPosition(PlayerControlPopupBounds(100,450,140,490),viewport,300,200,8).x)
        assertEquals(400,playerControlPopupPosition(PlayerControlPopupBounds(900,450,950,490),viewport,300,200,8).x)
    }
    @Test fun `zero viewport has a deterministic safe origin`() {
        assertEquals(PlayerControlPopupPosition(0,0),playerControlPopupPosition(PlayerControlPopupBounds(0,0,0,0),PlayerControlPopupBounds(0,0,0,0),0,0,0))
    }
    @Test fun `viewport and scale sweeps keep bounded menu placements inside allowed space`() {
        for(w in listOf(320,720,1280,1920,3840)) for(h in listOf(200,480,720,1080)) for(scale in listOf(.5f,1f,1.5f,2f)) {
            val viewport=PlayerControlPopupBounds(30,40,w+30,h+40)
            val menuW=minOf(w,(280*scale).toInt());val menuH=minOf(h,(300*scale).toInt())
            for(x in listOf(30,w/2,w+30)) for(y in listOf(40,h/2,h+40)) {
                val p=playerControlPopupPosition(PlayerControlPopupBounds(x,y,x+30,y+30),viewport,menuW,menuH,8)
                assertTrue(p.x>=viewport.left && p.x+menuW<=viewport.right)
                assertTrue(p.y>=viewport.top && p.y+menuH<=viewport.bottom)
            }
        }
    }
    @Test fun `Control deck and Invisible retain distinct panel and backdrop policy`() {
        assertEquals(PlayerControlChromePolicy(true,.55f),playerControlChromePolicy(true,true,false))
        assertEquals(PlayerControlChromePolicy(false,.78f),playerControlChromePolicy(true,false,false))
        assertEquals(PlayerControlChromePolicy(false,.65f),playerControlChromePolicy(false,true,false))
    }
    @Test fun `cinematic backdrop does not erase the deck panel distinction or rewrite style`() {
        val l=PlayerControlLayout.original(true).withStyle(PlayerControlButtonStyle.LABELLED);val saved=l.encode()
        assertEquals(PlayerControlChromePolicy(true,.20f),playerControlChromePolicy(true,true,true))
        assertEquals(PlayerControlChromePolicy(false,.20f),playerControlChromePolicy(true,false,true))
        assertEquals(saved,l.encode());assertEquals(PlayerControlButtonStyle.LABELLED,l.style)
    }
    @Test fun `popup return retains a supported origin rather than stealing primary focus`() {
        assertEquals(speed,playerControlPopupFocusFallback(PlayerControlLayout.original(true),all,speed))
        assertEquals(speed,playerControlPopupFocusFallback(PlayerControlLayout.original(true),all,PlayerControlAction.PLAY_PAUSE))
    }
    @Test fun `unsupported returning popup action recovers inside remaining menu`() {
        assertEquals(PlayerControlAction.ASPECT,playerControlPopupFocusFallback(PlayerControlLayout.original(true),all-speed,speed))
    }
    @Test fun `empty popup hands recovery to Close instead of a primary button behind it`() {
        assertNull(playerControlPopupFocusFallback(PlayerControlLayout.original(true),all-extras.toSet(),speed))
    }

}
