package com.nuvio.tv.core.player.amlfel
import com.nuvio.tv.ui.screens.player.PlayerNativeVideoSelection
import org.junit.Assert.*
import org.junit.Test
class AmlNativeSelectionLifecycleTest {
 private fun enable(r:AmlDvFelVideoRenderer) { r.javaClass.getDeclaredMethod("onEnabled",Boolean::class.javaPrimitiveType,Boolean::class.javaPrimitiveType).apply{isAccessible=true}.invoke(r,false,false) }
 private fun invoke(r:AmlDvFelVideoRenderer,name:String) { r.javaClass.getDeclaredMethod(name).apply{isAccessible=true}.invoke(r) }
 @Test fun `actual native renderer enable and disable own selection before labels`() { val s=PlayerNativeVideoSelection(); val owner=Any(); s.activate(owner); val r=AmlDvFelVideoRenderer(null,null,onSelectionChanged={s.update(owner,it)}); assertFalse(s.isSelected); enable(r); assertTrue(s.isSelected); invoke(r,"onDisabled"); assertFalse(s.isSelected) }
 @Test fun `actual renderer reset and release clear selected ownership`() { for(method in listOf("onReset","onRelease")) { val s=PlayerNativeVideoSelection(); val owner=Any(); s.activate(owner); val r=AmlDvFelVideoRenderer(null,null,onSelectionChanged={s.update(owner,it)}); enable(r); assertTrue(s.isSelected); invoke(r,method); assertFalse(s.isSelected) } }
 @Test fun `late actual old renderer lifecycle cannot clear replacement native selection`() { val s=PlayerNativeVideoSelection(); val old=Any(); val fresh=Any(); s.activate(old); val r=AmlDvFelVideoRenderer(null,null,onSelectionChanged={s.update(old,it)}); enable(r); s.activate(fresh); s.update(fresh,true); invoke(r,"onDisabled"); assertTrue(s.isSelected); enable(r); assertTrue(s.isSelected); s.clear(); invoke(r,"onRelease"); assertFalse(s.isSelected) }
}
