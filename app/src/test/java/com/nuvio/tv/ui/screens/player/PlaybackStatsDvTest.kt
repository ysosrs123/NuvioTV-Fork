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
 @Test fun masteringLineSaysWhenContentLightIsNotSet() {
  val line=DoviBridge.RpuStaticMetadata(62,3696,1,4000,0,0).toDiagnosticLine()
  assertTrue(line,line.startsWith("CLL/FALL not set · MDL "));assertTrue(line,line.length<=40)
 }
 @Test fun layersFollowTheRouteThatDecoded() {
  assertEquals(PlaybackStatsDv.Dv7Layers.RPU_BL_EL,PlaybackStatsDv.dv7LayersUsed("NATIVE_FEL","amstream_dves_hevc.sideband",false))
  assertEquals(PlaybackStatsDv.Dv7Layers.RPU_BL_EL,PlaybackStatsDv.dv7LayersUsed("NATIVE_FEL","amstream_dves_hevc",false))
  assertNull(PlaybackStatsDv.dv7LayersUsed("NATIVE_FEL","c2.amlogic.hevc.decoder",false))
  assertNull(PlaybackStatsDv.dv7LayersUsed("NATIVE_FEL",null,false))
  assertEquals(PlaybackStatsDv.Dv7Layers.BL_RPU,PlaybackStatsDv.dv7LayersUsed("DV81_LIBDOVI","OMX.amlogic.hevc.decoder.awesome2",true))
  assertNull(PlaybackStatsDv.dv7LayersUsed("DV81_LIBDOVI","OMX.amlogic.hevc.decoder.awesome2",false))
  assertEquals(PlaybackStatsDv.Dv7Layers.BL_ONLY,PlaybackStatsDv.dv7LayersUsed("HDR10_BASE_LAYER",null,false))
  assertEquals(PlaybackStatsDv.Dv7Layers.BL_ONLY,PlaybackStatsDv.dv7LayersUsed("STRIP_DV",null,false))
  assertNull(PlaybackStatsDv.dv7LayersUsed("OFF",null,false))
 }
 @Test fun cube3RouteCountsOnlyWhileFelRuns() {
  val dec="dvfel-sc2:OMX.amlogic.dolby_vision.decoder"
  assertEquals(PlaybackStatsDv.Dv7Layers.RPU_BL_EL,PlaybackStatsDv.dv7LayersUsed("NATIVE_FEL_CUBE3",dec,false,gazelleFelRunning=true))
  assertNull(PlaybackStatsDv.dv7LayersUsed("NATIVE_FEL_CUBE3",dec,false,gazelleFelRunning=false))
  assertNull(PlaybackStatsDv.dv7LayersUsed("NATIVE_FEL_CUBE3","OMX.amlogic.hevc.decoder.awesome",false,gazelleFelRunning=true))
  assertEquals("Profile 7.6 FEL · RPU + BL + EL",PlaybackStatsDv.profileRowText("7","FEL","NATIVE_FEL_CUBE3","dvhe.07.06",false,gazelleFelRunning=true))
  assertEquals("Profile 7.6 FEL",PlaybackStatsDv.profileRowText("7","FEL","NATIVE_FEL_CUBE3","dvhe.07.06",false,gazelleFelRunning=false))
 }
 @Test fun cube3ElRowsReadTheStatsSnapshot() {
  val s=longArrayOf(0,0,0,0,240,241,240,1,2,3)
  assertEquals("decoded 240 / in 241" to "overflow 1 · no CRC match 2 · stale 3",PlaybackStatsDv.gazelleElRows(s))
  assertNull(PlaybackStatsDv.gazelleElRows(null)); assertNull(PlaybackStatsDv.gazelleElRows(LongArray(3)))
 }
 @Test fun cmPreservesOriginalR14Wording() { assertEquals("v2.9",PlaybackStatsDv.cmText(dm())); assertEquals("v4.0",PlaybackStatsDv.cmText(dm().copy(cmVersion=40))) }
}
