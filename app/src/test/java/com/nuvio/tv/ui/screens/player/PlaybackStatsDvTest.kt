package com.nuvio.tv.ui.screens.player
import androidx.media3.common.Format
import com.nuvio.tv.core.player.DoviBridge
import org.junit.Assert.*
import org.junit.Test
class PlaybackStatsDvTest {
 private fun dm()=DoviBridge.RpuDmInfo(29,2,0,100,100,0,0,0,0,7,2,10,10,null,0,0,0)
 @Test fun hudPreservesOriginalR14Wording() {
  assertEquals("Profile 7.6 FEL · RPU + BL + EL",PlaybackStatsDv.profileRowText("7","FEL","NATIVE_FEL","dvhe.07.06",false))
  assertEquals("Profile 8 · native",PlaybackStatsDv.profileRowText("8",null,"NATIVE_FEL","dvhe.08.06",false))
 }
 @Test fun unknownEnhancementResolutionIsNotInvented() {
  val format=Format.Builder().setWidth(3840).setHeight(2160).setCodecs("dvhe.07.06").build()
  val row=PlaybackStatsDv.formatRowText(format,dm(),true)!!;assertTrue(row.contains("EL 10-bit"));assertFalse(row.contains("3840"));assertFalse(row.contains("1920"))
 }
 @Test fun codecPrefixDoesNotConfuseHevcProfile10WithMain8bit() {
  val format=Format.Builder().setCodecs("hvc1.10.6.L153").build();assertNull(PlaybackStatsDv.formatRowText(format,null,false))
 }
 @Test fun masteringLineFitsOneHudRow() {
  val line=DoviBridge.RpuStaticMetadata(62,3696,1,4000,4000,3996).toDiagnosticLine()
  assertTrue(line,line.startsWith("CLL 4000 · FALL 3996 · MDL "));assertTrue(line.endsWith(" nits"));assertTrue(line,line.length<=40)
  assertTrue(DoviBridge.RpuStaticMetadata(62,3696,null,null,0,null).toDiagnosticLine().startsWith("CLL - · MDL "))
 }
 @Test fun cmPreservesOriginalR14Wording() { assertEquals("v2.9",PlaybackStatsDv.cmText(dm())); assertEquals("v4.0",PlaybackStatsDv.cmText(dm().copy(cmVersion=40))) }
}
