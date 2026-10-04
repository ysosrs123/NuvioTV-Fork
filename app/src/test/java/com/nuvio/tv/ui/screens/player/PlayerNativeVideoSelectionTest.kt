package com.nuvio.tv.ui.screens.player
import org.junit.Assert.*
import org.junit.Test
class PlayerNativeVideoSelectionTest {
 @Test fun `factory offering native renderer does not select it`() { val s=PlayerNativeVideoSelection(); val owner=Any(); s.activate(owner); assertFalse(s.isSelected) }
 @Test fun `selected lifecycle emits before any decoder name`() { val s=PlayerNativeVideoSelection(); val owner=Any(); s.activate(owner); s.update(owner,true); assertTrue(s.changes.value); s.update(owner,false); assertFalse(s.changes.value) }
 @Test fun `old enable and disable callbacks cannot change a replacement player`() { val s=PlayerNativeVideoSelection(); val old=Any(); val fresh=Any(); s.activate(old); s.update(old,true); s.activate(fresh); s.update(old,true); assertFalse(s.isSelected); s.update(fresh,true); s.update(old,false); assertTrue(s.isSelected) }
 @Test fun `release and MPV switch invalidate outstanding native callbacks`() { val s=PlayerNativeVideoSelection(); val owner=Any(); s.activate(owner); s.update(owner,true); s.clear(); s.update(owner,true); assertFalse(s.isSelected) }
 @Test fun `unused new factory cannot unset an actually reused renderer`() { val s=PlayerNativeVideoSelection(); val retained=Any(); s.activate(retained); s.update(retained,true); s.update(Any(),false); assertTrue(s.isSelected); s.update(retained,false); assertFalse(s.isSelected) }
}
